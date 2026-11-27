const isTour = location.pathname === "/demo.html";
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

const feedback = document.getElementById("feedback");
const launchKey = location.hash.slice(1);
if (/^[a-f0-9]{64}$/.test(launchKey)) {
  sessionStorage.setItem("achievka-session", launchKey);
  history.replaceState(null, "", location.pathname);
}
const apiKey = isTour ? "" : sessionStorage.getItem("achievka-session") || "";

async function api(path, method = "GET", body) {
  if (isTour) throw new Error("Демонстрация использует учебный профиль.");
  const headers = { "X-Achievka-Session": apiKey };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  const response = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || "Не удалось выполнить действие");
  return data;
}

function message(text, error = false) {
  feedback.textContent = text;
  feedback.hidden = !text;
  feedback.dataset.error = String(error);
}

function action(work) {
  return async () => {
    message("");
    try {
      await work();
    } catch (error) {
      message(error.message, true);
    }
  };
}



let viewName = "home";
let query = "";
let moduleName = "";
let state = null;
let signature = "";
let revision = 0, changing = 0, polling = false;
let importPreview = null;
let courseDraft = {url:"", title:""};
let coursesSignature = "";
let snapshotDraft = "", snapshotOpen = false;

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
    if (button.dataset.view === name) button.setAttribute("aria-current", "page");
    else button.removeAttribute("aria-current");
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

function device(mark) {
  const card = el("article", "device" + (mark.done ? " unlocked" : ""));
  const glyph = el("span", "glyph", mark.done ? "✦" : "◇");
  glyph.setAttribute("aria-hidden", "true");
  const detail = el("div", "badge-detail");
  detail.append(el("b", null, mark.title), el("p", "muted", mark.condition));
  card.append(glyph, detail, el("span", "badge-status", mark.done ? "Получено" : "Закрыто"));
  return card;
}

function progressBar(value, maximum, label) {
  const track = el("progress", "progress");
  track.max = Math.max(maximum, 1);
  track.value = value;
  track.setAttribute("aria-label", label);
  return track;
}

function metric(label, value, detail, mark, tone) {
  const card = el("article", "card metric " + tone);
  const symbol = el("span", "metric-icon", mark);
  symbol.setAttribute("aria-hidden", "true");
  const copy = el("div");
  copy.append(el("span", "muted", label), el("strong", null, value), el("small", "muted", detail));
  card.append(symbol, copy);
  return card;
}

const companions = {
  "buddy-ember": { kind: "Огненный лис", talent: "Находит модуль, который проще всего закончить." },
  "buddy-luna": { kind: "Лунный мотылёк", talent: "Ищет самое ценное из оставшихся заданий." },
  "buddy-amethyst": { kind: "Кристальный дракон", talent: "Хранит победную открытку с твоим результатом." }
};

function portrait(id) {
  const img = el("img", "buddy-portrait");
  img.src = "/" + id + ".png";
  img.alt = "";
  return img;
}

function openCollection() {
  searchEl.value = query = "";
  show("rewards");
  paint();
}

function companionPanel(data, mark) {
  const panel = el("article", "card buddy-panel " + mark.id);
  const copy = el("div", "buddy-advice");
  copy.append(el("h2", null, mark.title));
  let tip, button;
  if (mark.id === "buddy-amethyst") {
    tip = "«Этот кристалл твой. Сохраним, какой путь ты прошёл?»";
    button = actionButton("Скопировать открытку", async () => {
      await copyText("✦ Ачивка ✦\n" + data.studentName + " · «" + data.courseTitle + "»\n" +
        "Пройдено заданий: " + data.solvedCount + " из " + data.taskCount + " · " + data.xp + " XP\n🐉 Спутник — Аметист");
      message("Победная открытка скопирована.");
    });
  } else if (!data.next) {
    tip = mark.id === "buddy-ember" ? "«Все тропинки пройдены. Загляни в коллекцию, там тебя ждёт дракон!»" :
      "«Ни одной пропущенной звезды. В коллекции проснулся Аметист!»";
    button = actionButton("К спутникам", openCollection);
  } else {
    let target = data.next;
    if (mark.id === "buddy-ember") {
      let minimum = Infinity;
      for (const tasks of groupsOf(data.tasks).values()) {
        const remaining = tasks.filter(task => !task.done);
        if (remaining.length && remaining.length < minimum) {
          minimum = remaining.length;
          target = remaining[0];
        }
      }
      tip = "«Ближе всего к финишу модуль „" + target.module + "“. Осталось заданий: " + minimum + ".»";
    } else {
      for (const task of data.tasks) {
        if (!task.done && task.reward > target.reward) target = task;
      }
      tip = "«Вижу яркую звезду! За „" + target.title + "“ дают " + target.reward + " XP.»";
    }
    button = actionButton(data.builtIn ? "К заданию →" : "Открыть курс →", () => openCourse(data, target.id));
  }
  copy.append(el("p", "muted", tip));
  panel.append(portrait(mark.id), copy, button);
  return panel;
}

function renderHome(data) {
  const root = el("div", "home-grid");
  const hero = el("article", "quest-hero");
  const copy = el("div", "hero-copy");
  const heading = data.next ? data.next.title : data.taskCount ? "Курс пройден!" : "Заданий пока нет";
  const label = data.next ? "Следующее задание" : data.taskCount ? "Все задания выполнены" : "Текущий курс";
  copy.append(el("p", "eyebrow hero-label", label), el("h2", null, data.courseTitle), el("p", "hero-description", heading));
  const actions = el("div", "hero-actions");
  if (data.next) actions.append(el("span", "quest-xp", "+" + data.next.reward + " XP"));
  const start = actionButton(data.next ? "К заданию →" : "Открыть курс →", () => openCourse(data, data.next && data.next.id));
  start.classList.add("primary");
  actions.append(start);
  const selected = data.rewards.find(mark => mark.id === data.companion && mark.done && companions[mark.id]);
  if (!selected && data.rewards.some(mark => mark.done && companions[mark.id])) {
    const choose = actionButton("Выбрать спутника", openCollection);
    choose.className = "hero-choose";
    actions.append(choose);
  }
  copy.append(actions);
  hero.append(copy);

  const metrics = el("div", "metrics");
  const unlocked = data.achievements.filter(mark => mark.done).length;
  metrics.append(
    metric("Опыт", data.xp + " XP", "Из " + data.xpMax + " XP", "ϟ", "violet"),
    metric("Задания", data.solvedCount + " / " + data.taskCount, "Выполнено", "✓", "green"),
    metric("Достижения", unlocked + " / " + data.achievements.length, "Открыто", "✦", "amber")
  );

  const panels = el("div", "home-panels");
  const map = el("section", "card route-card");
  const mapHeading = el("div", "section-head");
  mapHeading.append(el("h2", null, "Карта курса"), actionButton("Все задания →", () => {
    moduleName = "";
    show("steps");
    paint();
  }));
  map.append(mapHeading, progressBar(data.solvedCount, data.taskCount, "Общий прогресс курса"));
  const modules = el("div", "modules");
  for (const [name, tasks] of groupsOf(data.tasks)) {
    const done = tasks.filter(task => task.done).length;
    const current = data.next && data.next.module === name;
    const node = actionButton(name, () => {
      moduleName = name;
      show("steps");
      paint();
    });
    node.className = "module-node" + (done === tasks.length ? " completed" : current ? " current" : "");
    node.prepend(el("span", "module-icon", done === tasks.length ? "✓" : current ? "◆" : "◇"));
    node.append(el("small", "muted", done + "/" + tasks.length));
    modules.append(node);
  }
  if (!modules.children.length) modules.append(el("p", "muted", "Задания курса недоступны."));
  map.append(modules);

  const badges = el("section", "card badges-card");
  const badgeHeading = el("div", "section-head");
  badgeHeading.append(el("h2", null, "Достижения"), actionButton("Все →", () => show("marks")));
  badges.append(badgeHeading);
  const preview = data.achievements.filter(mark => mark.done).slice(-2).reverse();
  if (preview.length < 2) preview.push(...data.achievements.filter(mark => !mark.done).slice(0, 2 - preview.length));
  preview.forEach(mark => badges.append(device(mark)));
  if (!preview.length) badges.append(el("p", "muted", "Достижения появятся после загрузки курса."));
  panels.append(map, badges);
  root.append(hero);
  if (!data.builtIn && !data.taskCount) root.append(actionButton("Загрузить список уроков", scan));
  if (selected) root.append(companionPanel(data, selected));
  root.append(metrics, panels);
  return root;
}

function renderMarks(data) {
  const box = el("div", "list");
  box.append(el("h2", "section-label", "Достижения"));
  const grid = el("div", "devices");
  data.achievements.filter((mark) => matches(mark.title + mark.condition + mark.detail)).forEach((mark) => grid.append(device(mark)));
  if (!grid.children.length) grid.append(el("p", "muted", "Достижения не найдены."));
  box.append(grid);
  return box;
}

function renderRewards(data) {
  const box = el("div", "list");
  const buddies = el("div", "buddy-grid");
  const collection = data.rewards.filter(mark => companions[mark.id]);
  for (const mark of collection) {
    const buddy = companions[mark.id];
    if (!matches([mark.title, mark.condition, buddy.kind, buddy.talent, "спутники"].join(" "))) continue;
    const selected = data.companion === mark.id;
    const card = el("article", "buddy-card " + mark.id + (selected ? " selected" : ""));
    const art = el("div", "buddy-art");
    const status = selected ? "С тобой" : mark.done ? "Открыт" : "Закрыт";
    art.append(portrait(mark.id), el("span", "buddy-status", status));
    const body = el("div", "buddy-info");
    body.append(el("small", "muted", buddy.kind), el("h3", null, mark.title), el("p", "buddy-talent", buddy.talent));
    if (mark.done) {
      const button = actionButton(selected ? "Оставить в коллекции" : "Взять с собой", async () => {
        await postCourse("/api/companion", { courseId: data.courseId, profileId: data.profileId, id: selected ? "" : mark.id });
        message(selected ? "Спутник вернулся в коллекцию." : mark.title + " ждёт тебя на главной.");
      });
      button.dataset.buddy = mark.id;
      body.append(button);
    } else {
      const goal = el("div", "buddy-goal");
      goal.append(el("b", null, mark.condition), el("small", "muted", mark.detail));
      body.append(goal);
    }
    card.append(art, body);
    buddies.append(card);
  }
  if (buddies.children.length) {
    const heading = el("div", "section-head");
    heading.append(el("h2", "section-label", "Спутники"), el("span", "muted",
      "Открыто " + collection.filter(mark => mark.done).length + " из " + collection.length));
    box.append(heading, buddies);
  }
  const grid = el("div", "devices");
  data.rewards.filter(mark => !companions[mark.id] && matches(mark.title + " " + mark.condition)).forEach(mark => grid.append(device(mark)));
  if (grid.children.length) box.append(el("h2", "section-label", "Титулы и знаки"), grid);
  if (!box.children.length) box.append(el("p", "muted", "Награды не найдены."));
  if (data.courseOpen && matches("Сводка закрытых шагов")) {
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
  const heading = el("div", "section-head");
  heading.append(el("h2", "section-label", moduleName || "Задания курса"));
  if (moduleName) heading.append(actionButton("Все модули", () => {
    moduleName = "";
    paint();
  }));
  box.append(heading);
  groupsOf(data.tasks).forEach((tasks, name) => {
    if (moduleName && name !== moduleName) return;
    const visible = tasks.filter((task) => matches(task.title + name));
    if (!visible.length) return;
    const block = el("section", "path-block");
    block.append(el("h3", null, name));
    const row = el("div", "steps");
    visible.forEach((task) => {
      const step = el("div", "step" + (task.done ? " done" : ""));
      const body = el("div");
      body.append(el("b", null, task.title), el("small", null, task.type + (task.done ? " · Выполнено" : "")));
      step.append(el("span", "step-number", task.done ? "✓" : String(data.tasks.indexOf(task) + 1)), body, el("span", "quest-xp", task.reward + " XP"));
      row.append(step);
    });
    block.append(row);
    box.append(block);
  });
  if (box.children.length === 1) box.append(el("p", "muted", "Задания не найдены."));
  return box;
}

function renderImportPreview() {
  const preview = importPreview;
  const card = el("section", "card import-preview");
  card.append(el("h2", null, "Проверка импорта"), el("p", null, preview.title));
  if (preview.unknown) card.append(el("p", "muted", "В снимке есть неизвестные номера: " + preview.unknown + ". Они будут пропущены."));
  if (preview.pageWarning) card.append(el("p", "muted", "Проверь страницу, на которой создан снимок: " + preview.pageWarning));
  if (preview.tasks.some(t => t.legacy)) card.append(el("p", "muted", "У старых отметок есть только номер строки. Сверь их с названиями уроков и выбери совпадения."));
  if (preview.legacy) card.append(el("p", "muted", "Снимок старого формата. Проверь, что задания относятся к этому курсу."));
  card.append(el("p", "muted", "Отметь задания, которые нужно засчитать."));
  const list = el("div", "import-list");
  for (const task of preview.tasks) {
    const row = el("label");
    const check = el("input");
    check.type = "checkbox";
    check.disabled = task.saved;
    check.checked = task.saved || preview.selected.has(task.id);
    check.addEventListener("change", () => {
      if (check.checked) preview.selected.add(task.id);
      else preview.selected.delete(task.id);
    });
    const note = task.saved ? "уже сдано" : task.legacy ? "проверь старую отметку по номеру" :
      task.changed ? "изменение в списке" : task.found ? "сайт отметил как сданное" : "";
    row.append(check, el("span", null, task.title), el("small", "muted", note));
    list.append(row);
  }
  card.append(list);
  if (preview.removed.length) card.append(el("p", "muted", "Исчезли из списка: " + preview.removed.join(", ")));
  const actions = el("div", "actions");
  actions.append(actionButton("Применить", async () => {
    if (preview !== importPreview) return;
    const solved = [...preview.selected];
    if (preview.key) await postCourse("/api/scan/commit", {key:preview.key, solved});
    else await postCourse("/api/courses/import", {...preview.context, solved});
    importPreview = null;
    if (preview.key) courseDraft = {url:"", title:""};
    else snapshotDraft = "";
    coursesSignature = "";
    paint();
    show("home");
    message("Изменения сохранены.");
  }), actionButton("Отмена", () => { importPreview = null; coursesSignature = ""; paint(); }));
  card.append(actions);
  return card;
}

function displayPreview(preview) {
  preview.selected = new Set(preview.tasks.filter(t => t.found && !t.saved && !preview.reset).map(t => t.id));
  importPreview = preview;
  coursesSignature = "";
  paint();
  show("courses");
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
      use.addEventListener("click", action(() => selectCourse(course.id)));
      actions.append(use);
    }
    const drop = el("button", "drop", "Убрать");
    drop.type = "button";
    drop.addEventListener("click", action(() => removeCourse(course.id, course.title)));
    actions.append(drop);
    card.append(text, actions);
    box.append(card);
  });
  if (importPreview) { box.prepend(renderImportPreview()); return box; }
  if (!data.builtIn) box.append(actionButton("Обновить список уроков", scan));
  const form = el("form", "card course-form");
  form.append(el("h2", null, "Новый сайт"));
  const url = el("input");
  url.placeholder = "https://сайт-курса/";
  url.required = true;
  url.value = courseDraft.url;
  url.addEventListener("input", () => courseDraft.url = url.value);
  const title = el("input");
  title.placeholder = "Название, если хочешь своё";
  title.value = courseDraft.title;
  title.addEventListener("input", () => courseDraft.title = title.value);
  const error = el("p", "gate-error");
  error.hidden = true;
  const submit = el("button", null, "Посмотреть задания");
  submit.type = "submit";
  form.append(url, title, error, submit);
  form.addEventListener("submit", (event) => {
    event.preventDefault();
    error.hidden = true;
    submit.disabled = true;
    addCourse(url.value.trim(), title.value.trim()).catch((reason) => {
      error.hidden = false;
      error.textContent = reason.message;
    }).finally(() => { submit.disabled = false; });
  });
  box.append(form);
  return box;
}

function renderLink(data) {
  const card = el("article", "card link-card");
  card.append(el("h2", null, data.studentName));
  const actions = el("div", "actions");
  actions.append(
    actionButton("Скопировать ключ профиля", async () => {
      await copyText(data.apiToken);
      message("Ключ скопирован.");
    }),
    actionButton("Открыть курс", () => openCourse(data)),
    actionButton("Сканировать снова", scan),
    actionButton("Сбросить прогресс", resetProgress)
  );
  card.append(actions, el("p", "muted", data.sourceNote));
  if (data.scannedAt) card.append(el("p", "muted", "Последнее обновление: " + new Date(data.scannedAt).toLocaleString()));

  const backup = el("details");
  backup.append(el("summary", null, "Резервная копия и перенос на другой ПК"));
  const backupActions = el("div", "actions");
  const file = el("input");
  file.type = "file";
  file.accept = ".json,application/json";
  file.hidden = true;
  file.addEventListener("change", action(async () => {
    const selected = file.files[0];
    file.value = "";
    if (!selected) return;
    if (selected.size > 64 * 1048576) throw new Error("Максимальный размер файла — 64 МБ.");
    const text = await readFile(selected);
    await mutate(async () => {
      const result = await api("/api/backup", "POST", parseImport(text));
      apply(result.state);
      message("Резервная копия загружена. Курсов в файле: " + result.imported + ".");
    });
  }));
  backupActions.append(actionButton("Сохранить JSON", exportBackup), actionButton("Загрузить JSON", () => file.click()), file);
  backup.append(el("p", "muted", isTour ? "Сохраните копию заданий, прогресса и выбранного спутника." : "В копии сохраняются уроки, прогресс, спутник и синхронизированная комната героя с покупками и ответами. После обновления с версии 1.2 открой встроенный курс один раз для переноса комнаты."), backupActions);
  card.append(backup);

  const transfer = el("details");
  transfer.open = snapshotOpen;
  transfer.addEventListener("toggle", () => { snapshotOpen = transfer.open; });
  transfer.append(el("summary", null, "Перенести выполненные задания с сайта"));
  transfer.append(el("p", "muted", "Создай закладку в браузере и вставь скопированный код в поле адреса. Открой сайт курса, нажми закладку и вставь сюда полученный JSON."));
  const pasted = el("textarea");
  pasted.placeholder = '{"solved":[1,2]}';
  pasted.setAttribute("aria-label", "Выполненные задания в формате JSON");
  pasted.value = snapshotDraft;
  pasted.addEventListener("input", () => { snapshotDraft = pasted.value; });
  const transferActions = el("div", "actions");
  transferActions.append(
    actionButton("Скопировать код закладки", async () => {
      await copyText(bookmark(data.storageKey, data.sourceUrl, data.version));
      message("Код скопирован. Вставь его в поле адреса новой закладки.");
    }),
    actionButton("Загрузить задания", async () => {
      const snapshot = parseImport(pasted.value);
      if (!snapshot || !Array.isArray(snapshot.solved)) throw new Error("В JSON нужен список solved.");
      if (snapshot.sourceUrl && snapshot.sourceUrl !== data.sourceUrl) throw new Error("Снимок относится к другому курсу.");
      if (snapshot.page && new URL(snapshot.page).origin !== new URL(data.siteUrl).origin) throw new Error("Снимок получен с другого сайта.");
      if (snapshot.version && data.version && data.version !== snapshot.version) throw new Error("Версия курса изменилась. Обнови закладку и получи снимок снова.");
      const ids = new Set(snapshot.solved);
      const pageWarning = snapshot.page && new URL(snapshot.page).pathname !== new URL(data.siteUrl).pathname ? snapshot.page : "";
      const tasks = data.tasks.map((t,i) => {
        const positional = data.version === "html-v1" && !ids.has(t.id) && ids.has(i+1);
        return {...t, saved:t.done, found:ids.has(t.id), legacy:positional};
      }).filter(t => t.found || t.legacy);
      if (!tasks.length) throw new Error("В снимке нет известных заданий этого курса.");
      const recognized = new Set(data.tasks.map(t=>t.id));
      if (data.version === "html-v1") data.tasks.forEach((t,i)=>recognized.add(i+1));
      const unknown = [...ids].filter(id=>!recognized.has(id)).length;
      displayPreview({title:data.courseTitle, legacy:!snapshot.sourceUrl, pageWarning, unknown, reset:Boolean(pageWarning), tasks, removed:[],
        context:{id:data.courseId, profileId:data.profileId, resetNonce:data.resetNonce, savedAt:data.scannedAt}});
    })
  );
  transfer.append(pasted, transferActions);
  card.append(transfer);
  return card;
}

function actionButton(label, work) {
  const button = el("button", null, label);
  button.type = "button";
  button.addEventListener("click", action(work));
  return button;
}

async function copyText(text) {
  if (window.achievkaDesktop) window.achievkaDesktop.copy(text);
  else await navigator.clipboard.writeText(text);
}

function parseImport(text) {
  try {
    return JSON.parse(text.replace(/^\uFEFF/, ""));
  } catch {
    throw new Error("Не удалось прочитать JSON.");
  }
}

function readFile(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result);
    reader.onerror = () => reject(new Error("Не удалось прочитать файл."));
    reader.readAsText(file, "UTF-8");
  });
}

async function exportBackup() {
  const data = await api("/api/backup");
  const json = JSON.stringify(data);
  if (new Blob([json]).size > 64 * 1048576) throw new Error("Размер резервной копии превышает 64 МБ.");
  if (window.achievkaDesktop) {
    if (window.achievkaDesktop.saveBackup(json)) message("Резервная копия сохранена.");
    return;
  }
  const url = URL.createObjectURL(new Blob([json], { type: "application/json" }));
  const download = el("a");
  download.href = url;
  download.download = "achievka-progress.json";
  document.body.append(download);
  download.click();
  download.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function bookmark(key, sourceUrl, version) {
  function copyProgress(storageKey, sourceUrl, version) {
    try {
      const saved = JSON.parse(localStorage.getItem(storageKey) || "{}");
      if (!Array.isArray(saved.solved)) throw new Error();
      const json = JSON.stringify({ sourceUrl, version, page:location.href.split(/[?#]/)[0], solved:saved.solved });
      const show = () => prompt("Скопируй JSON и вставь его в Ачивку", json);
      if (navigator.clipboard) {
        navigator.clipboard.writeText(json)
          .then(() => alert("Задания скопированы. Вставь их в Ачивку."), show);
      } else show();
    } catch {
      alert("В этом браузере у сайта пока нет сохранённых заданий.");
    }
  }
  return "javascript:(" + copyProgress.toString().replace(/\r?\n/g, " ") + ")(" + JSON.stringify(key) + "," + JSON.stringify(sourceUrl) + "," + JSON.stringify(version) + ")";
}

async function openCourse(data = state, taskId) {
  if (!data) return;
  const url = new URL(data.courseUrl);
  if (data.builtIn) {
    if (taskId) url.searchParams.set("task", String(taskId));
    url.searchParams.set("theme", document.documentElement.dataset.theme);
    const pair = await api("/api/courses/pair", "POST", { id: data.courseId });
    url.hash = new URLSearchParams({
      achievka: pair.key, profile: pair.profile, version: String(pair.resetNonce)
    }).toString();
  }
  if (window.achievkaDesktop) window.achievkaDesktop.open(url.toString(), !data.builtIn);
  else window.open(url.toString(), "_blank", "noopener");
  menuVisibility(false);
}

function paint() {
  if (!state) return;
  try {
  const name = state.connected ? state.studentName : "Ученик";
  helloEl.textContent = "Привет, " + name;
  courseNameEl.textContent = state.levelName || "";
  document.getElementById("profile-name").textContent = name;
  avatarEl.textContent = name.trim().charAt(0).toUpperCase() || "А";
  renderCourseMenu(state);
  views.home.replaceChildren(renderHome(state));
  views.marks.replaceChildren(renderMarks(state));
  views.rewards.replaceChildren(renderRewards(state));
  views.steps.replaceChildren(renderSteps(state));
  views.link.replaceChildren(renderLink(state));
  const courseKey = JSON.stringify([state.courses, state.profileId, query]);
  if (coursesSignature !== courseKey) {
    views.courses.replaceChildren(renderCourses(state));
    coursesSignature = courseKey;
  }
  show(viewName);
  } catch (error) {
    console.error(error);
  }
}

function apply(next) {
  const nextSignature = JSON.stringify(next);
  if (state && (state.courseId !== next.courseId || state.profileId !== next.profileId || state.resetNonce !== next.resetNonce)) {
    snapshotDraft = "";
    importPreview = null;
    coursesSignature = "";
  }
  const first = !state;
  if (state && state.courseId !== next.courseId) moduleName = "";
  state = next;
  if (first || nextSignature !== signature) {
    signature = nextSignature;
    paint();
  }
}

function renderCourseMenu(data) {
  menuCourses.replaceChildren();
  (data.courses || []).forEach((course) => {
    const button = el("button", course.active ? "on" : "", course.title);
    button.type = "button";
    button.addEventListener("click", action(() => selectCourse(course.id)));
    menuCourses.append(button);
  });
}

async function mutate(work) {
  revision++;
  changing++;
  try {
    return await work();
  } finally {
    changing--;
  }
}

function postCourse(path, body) {
  return mutate(async () => {
    const result = await api(path, "POST", body);
    apply(result);
    return result;
  });
}

async function selectCourse(id) {
  await postCourse("/api/courses/select", { id });
  menuVisibility(false);
  show("home");
}

async function addCourse(url, title) {
  displayPreview(await api("/api/scan/preview", "POST", { url, title }));
}

function removeCourse(id, title) {
  if (!confirm("Убрать курс «" + title + "» из меню? Прогресс сохранится.")) return;
  return postCourse("/api/courses/remove", { id });
}

async function load() {
  if (changing || polling) return;
  const started = revision;
  polling = true;
  try {
    const next = await api("/api/state");
    if (started === revision && !changing) apply(next);
  } finally {
    polling = false;
  }
}

async function scan() {
  message("Сканируем сайт…");
  if (state.builtIn) { menuVisibility(false); message("Встроенный курс обновляется вместе с приложением."); return; }
  const preview = await api("/api/scan/preview", "POST", {});
  menuVisibility(false);
  message("");
  displayPreview(preview);
}

async function resetProgress() {
  if (!confirm("Сбросить прогресс этого курса в текущем профиле?")) return;
  await postCourse("/api/reset");
  menuVisibility(false);
  message("Прогресс курса сброшен.");
}

document.getElementById("nav").addEventListener("click", (event) => {
  const button = event.target.closest(".nav-btn");
  if (!button) return;
  if (button.dataset.view === "steps" && moduleName) {
    moduleName = "";
    paint();
  }
  show(button.dataset.view);
});
document.querySelector(".side-end").addEventListener("click", () => show("link"));
const menuButton = document.getElementById("menu");
function menuVisibility(open) {
  menuPanel.hidden = !open;
  menuButton.setAttribute("aria-expanded", String(open));
}
menuButton.addEventListener("click", () => menuVisibility(menuPanel.hidden));
document.addEventListener("keydown", event => {
  if (event.key === "Escape" && !menuPanel.hidden) {
    menuVisibility(false);
    menuButton.focus();
  }
});
document.getElementById("rescan").addEventListener("click", action(scan));
document.getElementById("manage-courses").addEventListener("click", () => {
  menuVisibility(false);
  show("courses");
});
document.getElementById("reset").addEventListener("click", action(resetProgress));
document.getElementById("logout").addEventListener("click", action(logout));
document.getElementById("open-course").addEventListener("click", action(() => openCourse()));
searchEl.addEventListener("input", () => {
  query = searchEl.value.trim().toLowerCase();
  if (query && !["steps", "marks", "rewards"].includes(viewName)) {
    moduleName = "";
    show("steps");
  }
  paint();
});

const themeBtn = document.getElementById("theme");
const themeLabel = document.getElementById("theme-label");
const themeSwitch = document.getElementById("theme-switch");

function applyTheme(theme) {
  const dark = theme === "dark";
  document.documentElement.dataset.theme = dark ? "dark" : "light";
  if (!isTour) localStorage.setItem("achievka-theme", dark ? "dark" : "light");
  themeBtn.setAttribute("aria-pressed", dark ? "true" : "false");
  themeSwitch.classList.toggle("on", dark);
  themeLabel.textContent = dark ? "Тёмная" : "Светлая";
  if (window.achievkaDesktop) window.achievkaDesktop.setDarkTitle(dark);
}

themeBtn.addEventListener("click", () => {
  applyTheme(document.documentElement.dataset.theme === "dark" ? "light" : "dark");
});
applyTheme(isTour ? "dark" : localStorage.getItem("achievka-theme") || "dark");

const REMEMBER_KEY = "achievka-profile";
const gate = document.getElementById("gate");
const gateForm = document.getElementById("gate-form");
const gateName = document.getElementById("gate-name");
const gateToken = document.getElementById("gate-token");
const gateRemember = document.getElementById("gate-remember");
const gateError = document.getElementById("gate-error");
const gateSaved = document.getElementById("gate-saved");
const gateSubmit = gateForm.querySelector("[type=submit]");

function gateFailure(error) {
  gateError.hidden = false;
  gateError.textContent = error.message;
}

function openGate() {
  gate.hidden = false;
  gateError.hidden = true;
  loadSavedAccounts().catch(gateFailure);
}

async function loadSavedAccounts() {
  const data = await api("/api/accounts");
  gateSaved.replaceChildren();
  if (!data.accounts.length) return;
  gateSaved.append(el("p", "muted", "На этом компьютере"));
  for (const account of data.accounts) {
    const button = el("button", "gate-account", account.name);
    button.type = "button";
    button.addEventListener("click", () => submitLogin({ profile: account.id }));
    gateSaved.append(button);
  }
}

async function login(body, remember) {
  const data = await api("/api/login", "POST", body);
  if (remember) localStorage.setItem(REMEMBER_KEY, data.profileId);
  else localStorage.removeItem(REMEMBER_KEY);
  apply(data);
  gate.hidden = true;
}

async function submitLogin(body) {
  if (gateSubmit.disabled) return;
  gateSubmit.disabled = true;
  gateError.hidden = true;
  try {
    await login(body, gateRemember.checked);
  } catch (error) {
    gateFailure(error);
  } finally {
    gateSubmit.disabled = false;
  }
}

async function logout() {
  await mutate(async () => {
    await api("/api/logout", "POST");
    localStorage.removeItem(REMEMBER_KEY);
    menuVisibility(false);
    courseDraft = {url:"",title:""};
    importPreview = null;
    coursesSignature = "";
    state = null;
    signature = "";
    snapshotDraft = "";
    helloEl.textContent = "Привет";
    avatarEl.textContent = "А";
    gateName.value = "";
    gateToken.value = "";
    openGate();
  });
}

gateForm.addEventListener("submit", event => {
  event.preventDefault();
  submitLogin({ name: gateName.value.trim(), token: gateToken.value.trim() });
});

if (!isTour) {
const remembered = localStorage.getItem(REMEMBER_KEY);
if (remembered) {
  gateSubmit.disabled = true;
  login({ profile: remembered }, true).catch(() => {
    localStorage.removeItem(REMEMBER_KEY);
    openGate();
  }).finally(() => { gateSubmit.disabled = false; });
} else {
  openGate();
}
setInterval(() => {
  if (gate.hidden) load().catch(() => {});
}, 2000);
}
