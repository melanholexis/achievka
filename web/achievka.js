(function () {
  if (window.__achievkaBridge) return;
  window.__achievkaBridge = true;

  var script = document.currentScript;
  var GAME = (script && script.dataset.game) || "pystart_game_v1";
  var COURSE = (script && script.dataset.course) || "";
  var TOKEN = "achievka_token";
  var NONCE = "achievka_nonce";
  var APP = (location.hostname === "127.0.0.1" || location.hostname === "localhost")
    ? location.origin
    : "http://127.0.0.1:8741";

  var statusEl = null;

  function setStatus(text) {
    if (statusEl) statusEl.textContent = text;
  }

  function gameRaw() {
    try {
      return localStorage.getItem(GAME) || "{}";
    } catch (e) {
      return "{}";
    }
  }

  function send() {
    var token = localStorage.getItem(TOKEN);
    if (!token) return Promise.resolve();
    return fetch(APP + "/api/v1/progress?course=" + encodeURIComponent(COURSE), {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": "Bearer " + token
      },
      body: gameRaw()
    }).then(function (response) {
      return response.json();
    }).then(function (data) {
      if (!data || data.ok !== true) throw new Error("reject");
      var seen = localStorage.getItem(NONCE);
      if (seen !== null && String(data.resetNonce) !== seen) {
        localStorage.removeItem(GAME);
        localStorage.setItem(NONCE, String(data.resetNonce));
        return send();
      }
      if (data.resetNonce != null) localStorage.setItem(NONCE, String(data.resetNonce));
      var solved = 0;
      try { solved = (JSON.parse(gameRaw()).solved || []).length; } catch (e) {}
      setStatus("Ключ " + token + " принят. Сдано заданий: " + solved + ".");
    }).catch(function () {
      setStatus("Ачивка не запущена. Открой Achievka.exe и нажми «Получить ключ» ещё раз.");
    });
  }

  function register(name) {
    var token = localStorage.getItem(TOKEN) || "";
    return fetch(APP + "/api/register", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name: name, token: token })
    }).then(function (response) {
      return response.json();
    }).then(function (data) {
      if (!data || !data.token) throw new Error("no token");
      localStorage.setItem(TOKEN, data.token);
      return send();
    });
  }

  function mount() {
    var bar = document.createElement("div");
    bar.style.cssText = "position:sticky;top:0;z-index:9999;display:flex;flex-wrap:wrap;gap:8px;align-items:center;background:#ffe56a;color:#111;border-bottom:2px solid #111;padding:8px 16px;font:600 14px/1.3 Segoe UI,sans-serif;";
    var title = document.createElement("strong");
    title.textContent = "Ачивка";
    var input = document.createElement("input");
    input.placeholder = "Имя";
    input.maxLength = 40;
    input.style.cssText = "border:2px solid #111;padding:6px 8px;font:600 14px Segoe UI,sans-serif;";
    var button = document.createElement("button");
    button.type = "button";
    button.textContent = "Получить ключ";
    button.style.cssText = "border:2px solid #111;background:#fff;padding:6px 10px;font:700 14px Segoe UI,sans-serif;cursor:pointer;";
    statusEl = document.createElement("span");
    button.addEventListener("click", function () {
      var name = input.value.trim();
      if (!name) {
        setStatus("Впиши имя.");
        return;
      }
      setStatus("Соединяюсь…");
      register(name).catch(function () {
        setStatus("Ачивка не запущена. Открой Achievka.exe и нажми ещё раз.");
      });
    });
    if (localStorage.getItem(TOKEN)) {
      setStatus("Ключ " + localStorage.getItem(TOKEN) + ". Отправляю уже сданные задания…");
      send();
    } else {
      setStatus("Впиши имя. Уже сданные задания уйдут в опыт вместе с ключом.");
    }
    bar.append(title, input, button, statusEl);
    document.body.prepend(bar);
  }

  var original = localStorage.setItem.bind(localStorage);
  localStorage.setItem = function (key, value) {
    original(key, value);
    if (key === GAME) send();
  };

  if (document.body) mount();
  else document.addEventListener("DOMContentLoaded", mount);
})();
