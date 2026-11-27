package achievka;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;

final class WindowsChrome {
    private static final int IMMERSIVE_DARK = 20;
    private static final int CAPTION_COLOR = 35;
    private static final int TEXT_COLOR = 36;
    private static boolean nativeUnavailable;

    interface User32 extends StdCallLibrary {
        Pointer FindWindowExW(Pointer parent, Pointer after, WString className, WString title);
        int GetWindowThreadProcessId(Pointer window, IntByReference processId);
    }

    interface Dwm extends StdCallLibrary {
        int DwmSetWindowAttribute(Pointer window, int attribute, Pointer value, int size);
    }

    private static final class Libraries {
        static final User32 USER = Native.load("user32", User32.class);
        static final Dwm DWM = Native.load("dwmapi", Dwm.class);
    }

    private WindowsChrome() {}

    static void apply(String title, boolean dark) {
        if (nativeUnavailable || !Platform.isWindows()) return;
        try (Memory value = new Memory(Integer.BYTES)) {
            var owner = new IntByReference();
            WString caption = new WString(title);
            Pointer window = null;
            while ((window = Libraries.USER.FindWindowExW(null, window, null, caption)) != null) {
                Libraries.USER.GetWindowThreadProcessId(window, owner);
                if (Integer.toUnsignedLong(owner.getValue()) != ProcessHandle.current().pid()) continue;
                if (set(window, value, IMMERSIVE_DARK, dark ? 1 : 0) != 0) {
                    set(window, value, 19, dark ? 1 : 0);
                }
                set(window, value, CAPTION_COLOR, dark ? rgb(20, 17, 29) : rgb(251, 248, 255));
                set(window, value, TEXT_COLOR, dark ? rgb(246, 241, 255) : rgb(36, 25, 52));
            }
        } catch (LinkageError | RuntimeException e) {
            nativeUnavailable = true;
            System.err.println("Windows title bar unavailable: " + e.getMessage());
        }
    }

    private static int set(Pointer window, Memory value, int attribute, int color) {
        value.setInt(0, color);
        return Libraries.DWM.DwmSetWindowAttribute(window, attribute, value, Integer.BYTES);
    }

    private static int rgb(int red, int green, int blue) {
        return red | (green << 8) | (blue << 16);
    }
}
