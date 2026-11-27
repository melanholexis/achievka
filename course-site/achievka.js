(function () {
  const theme = new URLSearchParams(location.search).get("theme");
  if (theme === "light" || theme === "dark") sessionStorage.setItem("achievka-course-theme", theme);
  document.documentElement.dataset.theme = sessionStorage.getItem("achievka-course-theme") || "dark";
  if (location.hostname !== "127.0.0.1" || !location.pathname.startsWith("/course/")) return;
  localStorage.removeItem("achievka-user");
  localStorage.removeItem("achievka_token");

  const parameters = new URLSearchParams(location.hash.slice(1));
  const supplied = parameters.get("achievka");
  const profile = parameters.get("profile");
  const initialNonce = Number(parameters.get("version"));
  if (/^[a-f0-9]{64}$/.test(supplied) && /^[a-f0-9]{64}$/.test(profile)) {
    sessionStorage.setItem("achievka-course-token", supplied);
    sessionStorage.setItem("achievka-course-profile", profile);
    history.replaceState(null, "", location.pathname + location.search);
  }
  const token = sessionStorage.getItem("achievka-course-token");
  const account = sessionStorage.getItem("achievka-course-profile");
  if (!token || !account) return;

  const legacyKey = "pystart_game_v1";
  const prefix = legacyKey + ":" + account + ":";
  const versionKey = prefix + "reset";
  const savedNonce = Number(localStorage.getItem(versionKey)) || 0;
  let resetNonce = Number.isSafeInteger(initialNonce) ? Math.max(savedNonce, initialNonce) : savedNonce;
  let working = false;
  let pending = false;
  let ready = false;
  let stopped = false;
  let status;
  let legacyButton;
  let timer;
  let refreshAfter = 0;

  function read(key) {
    try {
      const state = JSON.parse(localStorage.getItem(key) || "{}");
      return state && typeof state === "object" && !Array.isArray(state) ? state : {};
    } catch {
      return {};
    }
  }

  function gameKey() {
    return prefix + resetNonce;
  }

  function ids(state) {
    return Array.isArray(state.solved)
      ? [...new Set(state.solved.filter(id => Number.isSafeInteger(id) && id > 0))]
      : [];
  }

  const roomFields = ["coins", "totalEarned", "purchasedCount", "doubleUntil", "avatar", "characterName", "inventory", "answers"];
  function roomOf(state) {
    const room = {};
    for (const key of roomFields) if (state[key] !== undefined) room[key] = state[key];
    return Object.keys(room).length ? JSON.stringify(room) : "";
  }

  function apply(remote, sentRoom) {
    if (remote.resetNonce < resetNonce) return;
    const oldKey = gameKey();
    const reset = resetNonce !== remote.resetNonce;
    resetNonce = remote.resetNonce;
    localStorage.setItem(versionKey, String(resetNonce));
    if (reset) localStorage.removeItem(oldKey);
    const current = read(gameKey());
    const currentIds = ids(current);
    const solved = [...new Set([...remote.solved, ...currentIds])];
    const localRoom = roomOf(current);
    const baseKey = gameKey() + ":room-base";
    const base = localStorage.getItem(baseKey) || "";
    const serverRoom = remote.room || "";
    const acceptRoom = serverRoom !== base && !(sentRoom === serverRoom && localRoom !== sentRoom);
    if (acceptRoom) {
      for (const key of roomFields) delete current[key];
      Object.assign(current, JSON.parse(serverRoom || "{}"));
    }
    localStorage.setItem(baseKey, serverRoom);
    const changed = acceptRoom || solved.length !== currentIds.length || solved.some((id, index) => currentIds[index] !== id);
    if (changed) localStorage.setItem(gameKey(), JSON.stringify({ ...current, solved }));
    pending = solved.length > remote.solved.length || roomOf(current) !== serverRoom;
    if (reset || changed) window.dispatchEvent(new Event("achievka:updated"));
    if (legacyButton) legacyButton.hidden = resetNonce !== 0 || ids(read(legacyKey)).length === 0;
    status.textContent = pending ? "Ачивка · сохраняем изменения комнаты и заданий…" :
      "Ачивка · комната сохранена, синхронизировано заданий: " + remote.solved.length;
  }

  async function sync(forceRead = false) {
    if (!status || stopped || working || (!pending && !forceRead && Date.now() < refreshAfter)) return;
    working = true;
    const sendProgress = ready && pending;
    try {
      const headers = { "Authorization": "Bearer " + token };
      let options = { headers };
      let sentRoom;
      if (sendProgress) {
        const current = read(gameKey());
        sentRoom = roomOf(current);
        headers["Content-Type"] = "application/json";
        options = { method: "POST", headers, body: JSON.stringify({
          resetNonce, solved: ids(current), room: sentRoom, roomBase: localStorage.getItem(gameKey() + ":room-base") || ""
        }) };
      }
      const response = await fetch("/api/v1/progress", options);
      if (response.status === 401 || response.status === 403) {
        status.textContent = "Открой курс заново из Ачивки, чтобы подключить синхронизацию.";
        stopped = true;
        clearInterval(timer);
        return;
      }
      if (!response.ok && response.status !== 409) throw new Error("Ошибка синхронизации");
      apply(await response.json(), sentRoom);
      ready = true;
      refreshAfter = Date.now() + 4000;
    } catch {
      status.textContent = "Связь с Ачивкой прервалась. Задания сохранены в этом окне курса.";
    } finally {
      working = false;
    }
  }

  window.AchievkaCourse = {
    read: () => read(gameKey()),
    save(state) {
      const solved = [...new Set([...ids(read(gameKey())), ...ids(state)])];
      localStorage.setItem(gameKey(), JSON.stringify({ ...state, solved }));
      pending = true;
      if (status) status.textContent = "Ачивка · сохраняем изменения…";
      void sync();
    }
  };

  document.addEventListener("DOMContentLoaded", () => {
    const bar = document.createElement("div");
    bar.className = "sync-bar";
    status = document.createElement("span");
    status.setAttribute("role", "status");
    status.textContent = "Ачивка · подключение…";
    legacyButton = document.createElement("button");
    legacyButton.type = "button";
    legacyButton.textContent = "Перенести старое сохранение";
    legacyButton.hidden = true;
    legacyButton.addEventListener("click", () => {
      if (!ready || resetNonce !== 0 || !confirm("Добавить задания из старого сохранения в текущий профиль?")) return;
      const current = read(gameKey());
      const solved = [...new Set([...ids(current), ...ids(read(legacyKey))])];
      window.AchievkaCourse.save({ ...current, solved });
      window.dispatchEvent(new Event("achievka:updated"));
    });
    bar.append(status, legacyButton);
    document.body.prepend(bar);
    timer = setInterval(() => void sync(), 1000);
    void sync(true);
  });
})();
