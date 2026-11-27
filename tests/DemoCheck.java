package achievka;

import java.nio.file.Files;
import java.nio.file.Path;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Проверяет настоящий плеер, в том числе показ до входа в профиль. */
public class DemoCheck extends Application {
    protected WebView web;
    private Main app;
    protected static int result = 1;
    private long deadline;

    public static void main(String[] args) { launch(args); System.exit(result); }

    @Override public void start(Stage stage) throws Exception {
        Path dir = Files.createTempDirectory(Path.of("out"), "tour-check-");
        app = new Main(new AccountStore(dir.resolve("accounts.json")), new CourseBook(dir.resolve("courses.json")),
            Files.readString(Path.of("course-site/course-data.js")));
        app.initialCourse();
        app.start();
        web = new WebView();
        web.getEngine().setUserDataDirectory(dir.resolve("webview").toFile());
        stage.setScene(new Scene(web, 1280, 720));
        stage.show();
        web.getEngine().load(app.launchUrl());
        waitFor("вход", "Boolean(document.querySelector('#gate [data-tour-launch]') && typeof isTour !== 'undefined')", () -> {
            js("document.querySelector('#gate [data-tour-launch]').click()");
            waitFor("вступление", "document.querySelector('.tour-frame')?.contentDocument?.querySelector('[data-scene=intro]') != null", this::ready);
        });
    }

    protected Object js(String script) { return web.getEngine().executeScript(script); }
    protected void ready() {
        js("""
            (async () => {
                const frame = document.querySelector('.tour-frame');
                const d = frame.contentDocument, w = frame.contentWindow;
                const wait = async test => {
                    for(let i=0;i<70;i++){if(test()) return; await new Promise(r=>setTimeout(r,50));}
                    throw Error('Missing element at '+d.querySelector('.tour-bar').dataset.scene);
                };
                const buttons = d.querySelectorAll('.tour-progress button');
                d.querySelector('.tour-buttons button:nth-child(2)').click();
                for(let i=0;i<buttons.length;i++){
                    buttons[i].click();
                    if(i!==0 && i!==buttons.length-1) await wait(()=>!d.querySelector('.tour-ring').hidden);
                    const scene = d.querySelector('.tour-bar').dataset.scene;
                    if(scene==='preview' && d.querySelectorAll('.import-list input:checked').length!==2) throw Error('Preview marks');
                    if(scene==='xp' && !d.querySelector('.metrics').textContent.includes('65 XP')) throw Error('Experience');
                    if(d.documentElement.dataset.theme!=="dark" || d.querySelector("iframe")) throw Error("Expected dark app screen: "+scene);
                }
                d.dispatchEvent(new w.KeyboardEvent('keydown',{key:'ArrowLeft',bubbles:true}));
                if(d.querySelector('.tour-bar').dataset.scene!=='finish') throw Error('Back');
                d.dispatchEvent(new w.KeyboardEvent('keydown',{key:'Escape',bubbles:true}));
                await wait(()=>!document.querySelector('.tour-frame'));
                if(document.getElementById('gate').hidden) throw Error('Login changed');
                const list=await api('/api/accounts');
                if(list.accounts.length) throw Error('Demo wrote a profile');
                await login({name:"Проверка изоляции"},false);
                await postCourse("/api/courses/import",{id:state.courseId,solved:[1]});
                show("rewards");
                document.querySelector('.side [data-tour-launch]').click();
                const second=document.querySelector('.tour-frame');
                await wait(()=>second.contentDocument?.querySelector('[data-scene=intro]'));
                const sceneDoc=second.contentDocument;
                const sceneButtons=sceneDoc.querySelectorAll('.tour-progress button');
                sceneButtons[sceneButtons.length-2].click();
                if(sceneDoc.querySelector('#profile-name').textContent!=="Алекс") throw Error("Personal profile visible");
                sceneDoc.querySelector('.tour-buttons button:last-child').click();
                await wait(()=>!document.querySelector('.tour-frame'));
                const actual=await api("/api/state");
                if(actual.xp!==10 || actual.studentName!=="Проверка изоляции" || viewName!=="rewards") throw Error("Profile changed");
                window.demoOK=true;
            })().catch(e=>window.demoError=String(e));
            """);
        waitFor("сцены и выход", "Boolean(window.demoOK)", this::success);
    }
    protected void success() { System.out.println("Demo OK"); result=0; Platform.exit(); }

    protected void waitFor(String name,String condition,Runnable next) {
        deadline=System.nanoTime()+20_000_000_000L;
        poll(name,condition,next);
    }
    private void poll(String name,String condition,Runnable next) {
        try {
            if(Boolean.TRUE.equals(js("Boolean(window.demoError)"))) throw new IllegalStateException(String.valueOf(js("window.demoError")));
            if(Boolean.TRUE.equals(js(condition))) { next.run(); return; }
            if(System.nanoTime()>deadline) throw new IllegalStateException("Тайм-аут: "+name);
            var tick=new PauseTransition(Duration.millis(75));
            tick.setOnFinished(e->poll(name,condition,next)); tick.play();
        } catch(Exception ex) { ex.printStackTrace(); Platform.exit(); }
    }
    @Override public void stop() { if(app!=null) app.close(); }
}
