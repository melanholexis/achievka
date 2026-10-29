const views = {
  home: document.getElementById("view-home"),
  marks: document.getElementById("view-marks"),
  rewards: document.getElementById("view-rewards"),
  steps: document.getElementById("view-steps"),
  link: document.getElementById("view-link"),
  courses: document.getElementById("view-courses")
};
const helloEl = document.getElementById("hello");
const courseNameEl = document.getElementById("course-name");
const menuCourses = document.getElementById("menu-courses");
const avatarEl = document.getElementById("avatar");
const searchEl = document.getElementById("search");
const menuPanel = document.getElementById("menu-panel");

const PULL = "javascript:(function(){var name=prompt('\u0418\u043c\u044f \u0434\u043b\u044f \u0410\u0447\u0438\u0432\u043a\u0438');if(!name||!name.trim())return;var raw=localStorage.getItem('pystart_game_v1')||'{}';var token=localStorage.getItem('achievka_token')||'';fetch('http://127.0.0.1:8741/api/register',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({name:name.trim(),token:token})}).then(function(r){return r.json()}).then(function(d){if(!d.token)throw new Error();localStorage.setItem('achievka_token',d.token);return fetch('http://127.0.0.1:8741/api/v1/progress',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+d.token},body:raw})}).then(function(){alert('\u041a\u043b\u044e\u0447 \u043f\u0440\u0438\u043d\u044f\u0442. \u0412\u0435\u0440\u043d\u0438\u0441\u044c \u0432 \u0410\u0447\u0438\u0432\u043a\u0443: \u043e\u043f\u044b\u0442 \u0443\u0436\u0435 \u0442\u0430\u043c.')}).catch(function(){alert('\u0421\u043d\u0430\u0447\u0430\u043b\u0430 \u0437\u0430\u043f\u0443\u0441\u0442\u0438 Achievka.exe')})})();";

let viewName = "home";
let query = "";
let moduleName = "";
let state = null;
let signature = "";

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text != null) node.textContent = text;
  return node;
}

function show(name) {
  viewName = name;
  Object.entries(views).forEach(([key, node]) => {
    node.hidden = key !== name;
    if (key === name) {
      node.style.animation = "none";
      void node.offsetWidth;
      node.style.animation = "";
    }
  });
  document.querySelectorAll(".nav-btn").forEach((button) => {
    button.classList.toggle("active", button.dataset.view === name);
  });
}

function matches(text) {
  return !query || text.toLowerCase().includes(query);
}

function groupsOf(tasks) {
  const groups = new Map();
  tasks.forEach((task) => {
    if (!groups.has(task.module)) groups.set(task.module, []);
    groups.get(task.module).push(task);
  });
  return groups;
}

function switchEl(on) {
  const button = el("span", "switch" + (on ? " on" : ""));
  button.append(el("i"));
  return button;
}

function device(mark) {
  const card = el("article", "device");
  const top = el("div", "device-top");
  const glyph = el("div", "glyph");
  glyph.innerHTML = mark.done
    ? '<svg viewBox="0 0 24 24"><path d="M12 3 9 9H3l5 4-2 7 6-4 6 4-2-7 5-4h-6z"/></svg>'
    : '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="7"/><path d="M12 8v4l3 2"/></svg>';
  top.append(glyph, switchEl(mark.done));
  card.append(top, el("b", null, mark.title), el("p", null, mark.condition));
  return card;
}

function scene() {
  const wrap = el("div", "scene");
  wrap.innerHTML = `
    <svg viewBox="0 0 360 150" aria-hidden="true">
      <defs>
        <linearGradient id="soft" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stop-color="#d9f6ff"/>
          <stop offset="1" stop-color="#7ed7fb"/>
        </linearGradient>
      </defs>
      <ellipse cx="250" cy="126" rx="90" ry="12" fill="#e7f6ff"/>
      <path d="M70 118c20-48 48-70 78-70 18 0 28 12 34 28" fill="none" stroke="#bfe9f8" stroke-width="8" stroke-linecap="round"/>
      <circle cx="78" cy="112" r="8" fill="#7fd4f8"/>
      <circle cx="150" cy="58" r="8" fill="#9ee4fb"/>
      <circle cx="188" cy="78" r="8" fill="#5ec8f5"/>
      <rect x="214" y="62" width="92" height="64" rx="12" fill="url(#soft)"/>
      <path d="M226 78h68M226 92h48" stroke="#fff" stroke-width="4" stroke-linecap="round"/>
      <path d="M300 48c10 8 14 18 12 30" fill="none" stroke="#9adff8" stroke-width="4" stroke-linecap="round"/>
      <circle cx="318" cy="40" r="8" fill="#c8f1ff"/>
    </svg>`;
  return wrap;
}

function ring(percent, label) {
  const radius = 52;
  const length = 2 * Math.PI * radius;
  const wrap = el("div", "ring-wrap");
  wrap.innerHTML = `
    <svg viewBox="0 0 120 120">
      <defs>
        <linearGradient id="ringGrad" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stop-color="#8ae7ff"/>
          <stop offset="1" stop-color="#3db7f6"/>
        </linearGradient>
      </defs>
      <circle class="ring-track" cx="60" cy="60" r="${radius}"></circle>
      <circle class="ring-value" cx="60" cy="60" r="${radius}"
        stroke-dasharray="${length}" stroke-dashoffset="${length}"></circle>
    </svg>`;
  const labelBox = el("div", "ring-label");
  labelBox.append(el("b", null, percent + "%"), el("span", null, label));
  wrap.append(labelBox);
  requestAnimationFrame(() => {
    const circle = wrap.querySelector(".ring-value");
    circle.style.strokeDashoffset = String(length * (1 - percent / 100));
  });
  return wrap;
}

function renderHome(data) {
  const root = el("div", "home-grid");
  const left = el("div", "stack");
  const right = el("div", "right");

  const top = el("div", "row");
  const chart = el("article", "card chart");
  const head = el("div", "chart-head");
  head.append(el("h2", null, "Опыт по модулям"), el("span", "xp-spark", "XP"));
  const bars = el("div", "bars");
  const groups = [...groupsOf(data.tasks)];
  groups.forEach(([name, tasks], index) => {
    const earned = tasks.filter((task) => task.done).reduce((sum, task) => sum + task.reward, 0);
    const total = tasks.reduce((sum, task) => sum + task.reward, 0) || 1;
    const col = el("div", "bar-col");
    const track = el("div", "bar-track");
    const fill = el("span", "bar-fill");
    track.append(fill);
    col.append(track, el("span", null, "M" + (index + 1)));
    bars.append(col);
    requestAnimationFrame(() => { fill.style.height = Math.round((earned / total) * 100) + "%"; });
    col.title = name + ": " + earned + " / " + total + " XP";
  });
  chart.append(head, bars);

  const level = el("article", "card level");
  level.append(el("div", "xp-mark", "XP"));
  level.append(el("div", "big", String(data.xp)));
  level.append(el("div", "sub", "Сейчас"));
  level.append(document.createElement("hr"));
  level.append(el("div", "big", data.nextLevelName ? String(data.nextAt) : String(data.xpMax)));
  level.append(el("div", "sub", data.nextLevelName ? "До «" + data.nextLevelName + "»" : "Весь курс"));
  top.append(chart, level);

  const label = el("h2", "section-label", "Знаки");
  const devices = el("div", "devices");
  data.achievements.filter((mark) => matches(mark.title + mark.condition)).slice(0, 6).forEach((mark) => {
    devices.append(device(mark));
  });
  left.append(top, label, devices);

  const mods = el("div", "modules");
  groups.slice(0, 4).forEach(([name]) => {
    const button = el("button", "mod" + (name === moduleName ? " on" : ""));
    button.type = "button";
    button.innerHTML = '<svg viewBox="0 0 24 24"><rect x="4" y="6" width="16" height="12" rx="2"/><path d="M8 10h8M8 14h5"/></svg>';
    button.append(document.createTextNode(name.replace("Модуль ", "М. ")));
    button.addEventListener("click", () => {
      moduleName = moduleName === name ? "" : name;
      show("steps");
      paint();
    });
    mods.append(button);
  });

  const feature = el("article", "card feature");
  feature.append(el("h2", null, data.next ? data.next.module : "Курс закрыт"));
  feature.append(scene());
  if (data.next) {
    feature.append(el("h3", null, data.next.id + ". " + data.next.title));
    feature.append(el("p", null, data.next.condition));
  } else {
    feature.append(el("h3", null, "Все задания сданы"));
    feature.append(el("p", null, "Это не оценка и не строка в журнале."));
  }
  const stats = el("div", "stats");
  stats.append(el("span", null, "Опыт шага: " + (data.next ? data.next.reward : data.xpMax) + " XP"));
  const pill = el("div", "pill");
  const percent = data.xpMax ? Math.round((data.xp / data.xpMax) * 100) : 0;
  pill.style.setProperty("--p", percent + "%");
  pill.append(el("span", null, percent + "%"));
  stats.append(pill);
  feature.append(stats);

  const dial = el("article", "card dial-card");
  const side = el("div", "dial-side");
  side.append(el("div", "chip", data.levelName));
  side.append(el("div", "chip", data.solvedCount + " / " + data.taskCount));
  const modes = el("div", "modes");
  const practice = data.achievements.find((mark) => mark.id === "practice-all");
  const order = data.achievements.find((mark) => mark.id === "in-order");
  [["Путь", true], ["Код", practice && practice.done], ["Ряд", order && order.done]].forEach(([name, on]) => {
    modes.append(el("div", "mode" + (on ? " on" : ""), name));
  });
  dial.append(side, ring(percent, "Опыт"), modes);

  right.append(mods, feature, dial);
  root.append(left, right);
  return root;
}

function renderMarks(data) {
  const box = el("div", "list");
  box.append(el("h2", "section-label", "Знаки"));
  box.append(el("p", "muted", "Условие видно до сдачи. Знак не прибавляет опыт второй раз."));
  const grid = el("div", "devices");
  data.achievements.filter((mark) => matches(mark.title + mark.condition + mark.detail)).forEach((mark) => grid.append(device(mark)));
  box.append(grid);
  return box;
}

function renderRewards(data) {
  const box = el("div", "list");
  box.append(el("h2", "section-label", "Награды"));
  box.append(el("p", "muted", "Личная отметка за свой шаг. Места среди других нет."));
  const grid = el("div", "devices");
  data.rewards.filter((mark) => matches(mark.title + mark.condition)).forEach((mark) => grid.append(device(mark)));
  if (data.courseOpen) {
    const card = el("article", "card wide");
    card.append(el("h2", null, "Сводка закрытых шагов"));
    const area = el("textarea", "copy");
    area.readOnly = true;
    area.value = data.summary;
    card.append(area);
    box.append(card);
  }
  return box;
}

function renderSteps(data) {
  const box = el("div", "list");
  box.append(el("h2", "section-label", "Шаги курса"));
  groupsOf(data.tasks).forEach((tasks, name) => {
    if (moduleName && name !== moduleName) return;
    const visible = tasks.filter((task) => matches(task.title + name));
    if (!visible.length) return;
    const block = el("section", "path-block");
    block.append(el("h3", null, name));
    const row = el("div", "steps");
    visible.forEach((task) => {
      const step = el("div", "step" + (task.done ? " done" : ""));
      step.append(el("b", null, task.id + ". " + task.title));
      step.append(el("small", null, task.type + " · " + task.reward + " XP"));
      row.append(step);
    });
    block.append(row);
    box.append(block);
  });
  return box;
}

function renderCourses(data) {
  const box = el("div", "list");
  box.append(el("h2", "section-label", "Курсы"));
  box.append(el("p", "muted", "Добавь адрес страницы курса или файла course-data.js. Сдача каждого сайта хранится отдельно."));
  (data.courses || []).forEach((course) => {
    const card = el("article", "card course-row");
    const text = el("div");
    text.append(el("b", null, course.title));
    text.append(el("p", "muted", course.siteUrl));
    const actions = el("div", "actions");
    if (course.active) {
      actions.append(el("span", "muted", "Сейчас открыт"));
    } else {
      const use = el("button", "use", "Открыть");
      use.type = "button";
      use.addEventListener("click", () => selectCourse(course.id));
      actions.append(use);
    }
    const drop = el("button", "drop", "Убрать");
    drop.type = "button";
    drop.addEventListener("click", () => removeCourse(course.id, course.title));
    actions.append(drop);
    card.append(text, actions);
    box.append(card);
  });
  const form = el("form", "card course-form");
  form.append(el("h2", null, "Новый сайт"));
  const url = el("input");
  url.placeholder = "https://сайт-курса/";
  url.required = true;
  const title = el("input");
  title.placeholder = "Название, если хочешь своё";
  const error = el("p", "gate-error");
  error.hidden = true;
  const submit = el("button", null, "Добавить и открыть");
  submit.type = "submit";
  form.append(url, title, error, submit);
  form.addEventListener("submit", (event) => {
    event.preventDefault();
    error.hidden = true;
    addCourse(url.value.trim(), title.value.trim()).catch((reason) => {
      error.hidden = false;
      error.textContent = reason.message;
    });
  });
  box.append(form);
  return box;
}

function renderLink(data) {
  const card = el("article", "card link-card");
  card.append(el("h2", null, data.connected ? data.studentName : "Связь с сайтом"));
  if (data.connected) {
    card.append(el("p", null, "Ключ " + data.apiToken + ". Опыт " + data.xp + " из " + data.xpMax + "."));
  } else {
    card.append(el("p", null, "Ключа пока нет. На сайте курса впиши имя и нажми «Получить ключ». Уже сданные задания придут вместе с ним."));
  }
  const pull = el("a", null, "Забрать сдачу с этого сайта");
  pull.href = pullBookmark(data);
  card.append(pull);
  card.append(el("p", "muted", "Если задания уже закрыты на сайте: перетащи ссылку на панель закладок, открой сайт и нажми её."));
  card.append(el("p", "muted", data.live ? data.sourceNote : "Сайт сейчас не прочитан. " + data.sourceNote));
  const actions = el("div", "actions");
  const open = el("a", null, "Открыть курс");
  open.href = "/course/";
  open.target = "_blank";
  const rescan = el("button", null, "Сканировать снова");
  rescan.type = "button";
  rescan.addEventListener("click", scan);
  const reset = el("button", null, "Сбросить прогресс");
  reset.type = "button";
  reset.addEventListener("click", resetProgress);
  actions.append(open, rescan, reset);
  card.append(actions);
  return card;
}

function paint() {
  if (!state) return;
  try {
  const name = state.connected ? state.studentName : "Ученик";
  helloEl.textContent = "Привет, " + name;
  courseNameEl.textContent = state.courseTitle || "";
  avatarEl.textContent = name.trim().charAt(0).toUpperCase() || "А";
  renderCourseMenu(state);
  views.home.replaceChildren(renderHome(state));
  views.marks.replaceChildren(renderMarks(state));
  views.rewards.replaceChildren(renderRewards(state));
  views.steps.replaceChildren(renderSteps(state));
  views.link.replaceChildren(renderLink(state));
  views.courses.replaceChildren(renderCourses(state));
  show(viewName);
  } catch (error) {
    console.error(error);
  }
}

function apply(next) {
  const nextSignature = JSON.stringify({
    xp: next.xp,
    solved: next.solvedCount,
    name: next.studentName,
    token: next.apiToken,
    live: next.live,
    tasks: next.tasks.map((task) => task.done),
    marks: next.achievements.map((mark) => mark.done),
    rewards: next.rewards.map((mark) => mark.done),
    next: next.next && next.next.id,
    course: next.courseId,
    courses: (next.courses || []).map((course) => course.id + course.title + course.active)
  });
  const first = !state;
  state = next;
  if (first || nextSignature !== signature) {
    signature = nextSignature;
    paint();
  }
}

function pullBookmark(data) {
  const key = (data.storageKey || "pystart_game_v1").replace(/[^a-z0-9_]/gi, "");
  const course = encodeURIComponent(data.courseId || "");
  return "javascript:(function(){var name=prompt('\u0418\u043c\u044f \u0434\u043b\u044f \u0410\u0447\u0438\u0432\u043a\u0438');if(!name||!name.trim())return;var raw=localStorage.getItem('" + key + "')||'{}';var token=localStorage.getItem('achievka_token')||'';fetch('http://127.0.0.1:8741/api/register',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({name:name.trim(),token:token})}).then(function(r){return r.json()}).then(function(d){if(!d.token)throw new Error();localStorage.setItem('achievka_token',d.token);return fetch('http://127.0.0.1:8741/api/v1/progress?course=" + course + "',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+d.token},body:raw})}).then(function(){alert('\u041a\u043b\u044e\u0447 \u043f\u0440\u0438\u043d\u044f\u0442. \u0412\u0435\u0440\u043d\u0438\u0441\u044c \u0432 \u0410\u0447\u0438\u0432\u043a\u0443: \u043e\u043f\u044b\u0442 \u0443\u0436\u0435 \u0442\u0430\u043c.')}).catch(function(){alert('\u0421\u043d\u0430\u0447\u0430\u043b\u0430 \u0437\u0430\u043f\u0443\u0441\u0442\u0438 Achievka.exe')})})();";
}

function renderCourseMenu(data) {
  menuCourses.replaceChildren();
  (data.courses || []).forEach((course) => {
    const button = el("button", course.active ? "on" : "", course.title);
    button.type = "button";
    button.addEventListener("click", () => selectCourse(course.id));
    menuCourses.append(button);
  });
}

async function postCourse(path, body) {
  const response = await fetch(path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body)
  });
  const data = await response.json();
  if (!response.ok) {
    throw new Error(data.error || "Не получилось");
  }
  signature = "";
  apply(data);
  return data;
}

function selectCourse(id) {
  return postCourse("/api/courses/select", { id }).then(() => {
    menuPanel.hidden = true;
    show("home");
  });
}

function addCourse(url, title) {
  return postCourse("/api/courses", { url, title }).then(() => show("home"));
}

function removeCourse(id, title) {
  if (!confirm("Убрать курс «" + title + "» из меню? Сдача на сайте останется.")) return Promise.resolve();
  return postCourse("/api/courses/remove", { id });
}

async function load() {
  const response = await fetch("/api/state");
  apply(await response.json());
}

async function scan() {
  helloEl.textContent = "Сканируем сайт…";
  const response = await fetch("/api/rescan", { method: "POST" });
  signature = "";
  apply(await response.json());
  menuPanel.hidden = true;
}

async function resetProgress() {
  if (!confirm("Сбросить только прогресс Ачивки на этом компьютере?")) return;
  const response = await fetch("/api/reset", { method: "POST" });
  signature = "";
  apply(await response.json());
  menuPanel.hidden = true;
}

document.getElementById("nav").addEventListener("click", (event) => {
  const button = event.target.closest(".nav-btn");
  if (!button) return;
  show(button.dataset.view);
});
document.querySelector(".side-end").addEventListener("click", () => show("link"));
document.getElementById("menu").addEventListener("click", () => {
  menuPanel.hidden = !menuPanel.hidden;
});
document.getElementById("rescan").addEventListener("click", scan);
document.getElementById("manage-courses").addEventListener("click", () => {
  menuPanel.hidden = true;
  show("courses");
});
document.getElementById("reset").addEventListener("click", resetProgress);
document.getElementById("logout").addEventListener("click", logout);
searchEl.addEventListener("input", () => {
  query = searchEl.value.trim().toLowerCase();
  if (query && viewName === "home") show("marks");
  paint();
});

const themeBtn = document.getElementById("theme");
const themeLabel = document.getElementById("theme-label");
const themeSwitch = document.getElementById("theme-switch");

function applyTheme(theme) {
  const dark = theme === "dark";
  document.documentElement.dataset.theme = dark ? "dark" : "light";
  localStorage.setItem("achievka-theme", dark ? "dark" : "light");
  themeBtn.setAttribute("aria-pressed", dark ? "true" : "false");
  themeSwitch.classList.toggle("on", dark);
  themeLabel.textContent = dark ? "Тёмная" : "Светлая";
}

themeBtn.addEventListener("click", () => {
  applyTheme(document.documentElement.dataset.theme === "dark" ? "light" : "dark");
});
applyTheme(localStorage.getItem("achievka-theme") || "light");

const REMEMBER_KEY = "achievka-user";
const gate = document.getElementById("gate");
const gateForm = document.getElementById("gate-form");
const gateName = document.getElementById("gate-name");
const gateToken = document.getElementById("gate-token");
const gateRemember = document.getElementById("gate-remember");
const gateError = document.getElementById("gate-error");
const gateSaved = document.getElementById("gate-saved");

function openGate() {
  gate.hidden = false;
  gateError.hidden = true;
  loadSavedAccounts();
}

function closeGate() {
  gate.hidden = true;
}

async function loadSavedAccounts() {
  const response = await fetch("/api/accounts");
  const data = await response.json();
  gateSaved.replaceChildren();
  if (!data.accounts || !data.accounts.length) return;
  gateSaved.append(el("p", "muted", "На этом компьютере"));
  data.accounts.forEach((account) => {
    const button = el("button", "gate-account", account.name);
    button.type = "button";
    button.addEventListener("click", () => {
      gateName.value = account.name;
      gateToken.value = account.token;
      gateError.hidden = true;
    });
    gateSaved.append(button);
  });
}

async function login(name, token, remember) {
  const response = await fetch("/api/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ name, token })
  });
  const data = await response.json();
  if (!response.ok) {
    throw new Error(data.error || "Не удалось войти");
  }
  if (remember) localStorage.setItem(REMEMBER_KEY, data.apiToken);
  else localStorage.removeItem(REMEMBER_KEY);
  signature = "";
  apply(data);
  closeGate();
}

async function logout() {
  localStorage.removeItem(REMEMBER_KEY);
  menuPanel.hidden = true;
  await fetch("/api/logout", { method: "POST" });
  state = null;
  signature = "";
  helloEl.textContent = "Привет";
  avatarEl.textContent = "А";
  gateName.value = "";
  gateToken.value = "";
  openGate();
}

gateForm.addEventListener("submit", (event) => {
  event.preventDefault();
  gateError.hidden = true;
  login(gateName.value.trim(), gateToken.value.trim(), gateRemember.checked).catch((error) => {
    gateError.hidden = false;
    gateError.textContent = error.message;
  });
});

const remembered = localStorage.getItem(REMEMBER_KEY);
if (remembered) {
  login("", remembered, true).catch(() => {
    localStorage.removeItem(REMEMBER_KEY);
    openGate();
  });
} else {
  openGate();
}
setInterval(() => {
  if (!gate.hidden) return;
  load().catch(() => {});
}, 2000);
