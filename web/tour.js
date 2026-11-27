(() => {
  "use strict";

  if (!isTour) {
    let player, launcher;
    const close = () => {
      if (!player) return;
      player.remove();
      player = null;
      document.body.classList.remove("tour-open");
      launcher.focus();
    };
    document.querySelectorAll("[data-tour-launch]").forEach(button => button.addEventListener("click", () => {
      if (player) return;
      launcher = button;
      player = document.createElement("iframe");
      player.title = "Демонстрация Ачивки";
      player.className = "tour-frame";
      player.src = "/demo.html";
      document.body.classList.add("tour-open");
      document.body.append(player);
      player.focus();
    }));
    window.addEventListener("message", event => {
      if (player && event.source === player.contentWindow && event.origin === location.origin && event.data === "tour-close") close();
    });
    document.addEventListener("keydown", e => { if (player && e.key === "Escape") close(); });
    return;
  }

  // Each chapter starts from a reproducible checkpoint so that Back and Replay work offline.
  const chapters = [
    ["intro", "Начало", "Знакомство с Ачивкой", "Пройдём путь ученика от добавления курса до финальной награды.", 4.8],
    ["login", "Вход", "Создайте локальный профиль", "Введите имя или выберите сохранённый профиль. У каждого ученика свой прогресс.", 4.2],
    ["home", "Профиль", "Начните с главной", "Здесь собраны ближайшее задание, опыт и карта курса.", 4.4],
    ["address", "Каталог", "Добавьте свой курс", "В разделе «Курсы» вставьте ссылку и нажмите «Посмотреть задания».", 5.8],
    ["preview", "Импорт", "Проверьте отметки", "Галочки показывают, какие уроки будут засчитаны. Выберите нужные и нажмите «Применить».", 5.5],
    ["added", "Результат", "Два урока уже на карте", "У курса теперь 20 XP. Список уроков доступен и без сети.", 4.6],
    ["menu", "Курсы", "Переключайтесь между курсами", "В меню можно сменить курс, обновить список заданий или перейти в другой профиль.", 4.2],
    ["path", "Задания", "Найдите практику", "Вернёмся к встроенному Python-курсу. Поиск помогает быстро найти нужное задание.", 4.8],
    ["xp", "Прогресс", "Посмотрите результат", "После сдачи практики начислены 25 XP. На главной видны общий опыт и следующий шаг.", 4.5],
    ["marks", "Достижения", "Первое задание с кодом", "Знак «Первая программа» появился в коллекции достижений.", 4.4],
    ["buddy", "Награды", "Возьмите Искрика с собой", "Огненный лис открывается за любую сдачу. Выберите его для главного экрана.", 4.8],
    ["advice", "Спутник", "Следуйте подсказке лиса", "Искрик найдёт модуль, до завершения которого осталось меньше всего заданий.", 4.5],
    ["transfer", "Перенос", "Добавьте пройденное на сайте", "Скопируйте снимок закладкой на сайте курса, вставьте его сюда и сверьте задания перед импортом.", 5.1],
    ["backup", "Профиль", "Сохраните пройденное", "«Сохранить JSON» создаёт копию прогресса и наград. На другом ПК выберите «Загрузить JSON».", 5.4],
    ["finish", "Финиш", "Все 15 заданий пройдены", "В финале открывается Аметист. С ним можно скопировать открытку о прохождении курса.", 5.4],
    ["outro", "Финал", "Аметист в коллекции", "235 XP и кристальный дракон в коллекции Алекса.", 4.6]
  ];
  const link = "https://studio.example/lessons/";
  const total = chapters.reduce((sum, chapter) => sum + chapter[4], 0);
  const $ = s => document.querySelector(s);
  const clock = t => Math.floor(t / 60) + ":" + String(Math.floor(t % 60)).padStart(2, "0");
  let frames, index = 0, elapsed = 0, previousTime = 0, playing = true, ended = false;
  let target, focusText = "", lastCaption = "";
  document.body.className = "tour-mode";
  gate.hidden = true;
  const viewport = el("div", "tour-stage");
  const shell = $(".shell");
  document.body.prepend(viewport);
  viewport.append(shell, gate);
  const poster = el("section", "tour-poster");
  poster.innerHTML = '<div class="tour-poster-copy"><p class="tour-kicker"></p><h1></h1><p class="tour-sub"></p></div><img alt="" src="/buddy-amethyst.png">';
  const ring = el("div", "tour-ring");
  const tag = el("span", "tour-tag");
  const stageCover = el("div", "tour-shield");
  const bar = el("section", "tour-bar");
  bar.setAttribute("aria-label", "Управление демонстрацией");
  bar.innerHTML = '<div class="tour-progress" aria-label="Сцены"></div><div class="tour-row"><div class="tour-copy"><p class="tour-kicker"></p><h2></h2><p class="tour-sub"></p></div><div class="tour-controls"><div class="tour-time"></div><div class="tour-buttons"></div></div></div>';
  const buttons = bar.querySelector(".tour-buttons");
  const control = (title, label, fn) => {
    const node = el("button", "", label);
    node.type = "button";
    node.setAttribute("aria-label", title);
    node.title = title;
    node.onclick = fn;
    buttons.append(node);
    return node;
  };
  const prev = control("Назад (←)", "‹", () => enter(index - 1));
  const play = control("Пауза / продолжить (пробел)", "Ⅱ", toggle);
  const next = control("Дальше (→)", "›", () => enter(index + 1));
  control("Скрыть управление (H)", "◫", () => document.body.classList.toggle("tour-clean"));
  control("Завершить демонстрацию (Esc)", "×", quit);
  const segments = chapters.map((chapter, i) => {
    const b = el("button");
    b.type = "button";
    b.title = chapter[2];
    b.setAttribute("aria-label", "Сцена " + (i + 1) + ". " + chapter[2]);
    b.onclick = () => enter(i);
    b.append(el("i"));
    bar.firstElementChild.append(b);
    return b;
  });
  viewport.after(poster, stageCover, ring, tag, bar);
  poster.hidden = ring.hidden = tag.hidden = true;

  function quit() {
    playing = false;
    if (parent !== window) parent.postMessage("tour-close", location.origin);
    else location.replace("/");
  }
  function toggle() {
    if (!frames) return;
    if (ended) { playing = true; enter(0); }
    else playing = !playing;
    previousTime = performance.now();
    updateBar();
  }
  function focusOn(node, caption) {
    target = typeof node === "string" ? $(node) : node;
    focusText = caption;
    if (!target) return;
    const box = target.getBoundingClientRect();
    if (box.bottom > viewport.clientHeight - 30 || box.top < 30)
      viewport.scrollTop += box.top - Math.max(40, (viewport.clientHeight-box.height)/2);
  }
  function dashboard(view, snapshot) {
    state = frames[snapshot];
    coursesSignature = "";
    show(view);
    paint();
    viewport.scrollTop = 0;
  }
  function showPoster(final) {
    viewport.hidden = true;
    poster.hidden = false;
    poster.classList.toggle("tour-victory", final);
    poster.querySelector(".tour-kicker").textContent = final ? "Ачивка  /  Достижение получено" : "Ачивка  /  Демонстрация";
    poster.querySelector("h1").textContent = final ? "Курс пройден" : "От первого шага\nдо дракона";
    poster.querySelector(".tour-sub").textContent = final ? "Python с нуля   ·   15 / 15 заданий   ·   235 XP" : "Курсы, опыт и спутники";
  }
  function positionRing() {
    const box = target && target.getBoundingClientRect();
    const hide = !box || box.width < 1 || box.height < 1;
    ring.hidden = tag.hidden = hide;
    if (hide) return;
    const bottom = bar.getBoundingClientRect().top;
    const x = Math.max(5, box.left-4), y = Math.max(5, box.top-4);
    const width = Math.max(0, Math.min(innerWidth-x-6,box.width+8));
    const height = Math.max(0, Math.min(bottom-y-5,box.top+box.height+4-y));
    if (height < 4) { ring.hidden = tag.hidden = true; return; }
    ring.style.cssText = "left:"+x+"px;top:"+y+"px;width:"+width+"px;height:"+height+"px";
    tag.textContent = focusText;
    tag.style.left = Math.max(8,Math.min(x+12,innerWidth-tag.offsetWidth-10))+"px";
    tag.style.top = (y>34 ? y-28 : Math.min(bottom-30,y+height+3))+"px";
  }
  function enter(i) {
    if (!frames) return;
    index = Math.max(0,Math.min(chapters.length-1,i));
    elapsed = 0; previousTime = performance.now(); ended = false;
    target = null;
    gate.hidden = true; menuVisibility(false); snapshotOpen = false;
    poster.hidden = ring.hidden = tag.hidden = true;
    viewport.hidden = false; viewport.scrollTop = 0;
    importPreview = null; query = moduleName = searchEl.value = ""; courseDraft = {url:"",title:""};
    applyTheme("dark");
    const id = chapters[index][0];
    switch (id) {
      case "intro": case "outro": showPoster(id === "outro"); break;
      case "login": dashboard("home","start"); gate.hidden=false; gateName.value="Алекс"; focusOn("#gate-name","Имя ученика"); break;
      case "home": dashboard("home","start"); focusOn(".quest-hero .primary","К заданию"); break;
      case "address": dashboard("courses","start"); focusOn(".course-form","Новый сайт"); break;
      case "preview":
        importPreview = {title:frames.external.courseTitle,key:"demo",tasks:frames.external.tasks.map(t=>({...t,found:t.done})),
          selected:new Set(frames.external.tasks.filter(t=>t.done).map(t=>t.id)), removed:[]};
        dashboard("courses","start"); focusOn(".import-preview","Перед добавлением"); break;
      case "added": dashboard("home","external"); focusOn(".metrics","После импорта"); break;
      case "menu": dashboard("home","external"); menuVisibility(true); focusOn("#menu-panel","Меню курсов"); break;
      case "path":
        query = searchEl.value = "сумм"; dashboard("steps","practice");
        focusOn("#view-steps .step","Поиск по курсу"); break;
      case "xp": dashboard("home","earned"); focusOn(".metrics","После сдачи"); break;
      case "marks": dashboard("marks","earned"); focusOn([...document.querySelectorAll("#view-marks .device")].find(n=>n.textContent.includes("Первая программа")),"Получено"); break;
      case "buddy": dashboard("rewards","earned"); focusOn('[data-buddy="buddy-ember"]',"Выбрать спутника"); break;
      case "advice": dashboard("home","equipped"); focusOn(".buddy-panel","Искрик с вами"); break;
      case "transfer": dashboard("link","external");
        $("#view-link details:last-of-type").open = true;
        $("#view-link textarea").value = '{"solved":[1,2]}';
        focusOn("#view-link details:last-of-type","Перенос заданий"); break;
      case "backup": dashboard("link","equipped"); $("#view-link details").open = true; focusOn("#view-link details","Копия профиля"); break;
      case "finish": dashboard("home","finished"); focusOn(".buddy-panel","Финальный спутник"); break;
    }
    const ch = chapters[index];
    bar.querySelector(".tour-kicker").textContent = String(index+1).padStart(2,"0")+" / "+chapters.length+"     ·     "+ch[1];
    bar.querySelector("h2").textContent = ch[2];
    bar.querySelector(".tour-sub").textContent = ch[3];
    bar.dataset.scene = id;
    positionRing();
    requestAnimationFrame(positionRing);
    updateBar();
  }
  function updateBar() {
    prev.disabled = index === 0;
    next.disabled = index === chapters.length-1;
    play.textContent = ended ? "↻" : playing ? "Ⅱ" : "▶";
    document.body.classList.toggle("tour-ended",ended);
    play.title = ended ? "Повторить демонстрацию" : playing ? "Пауза (пробел)" : "Продолжить (пробел)";
    play.setAttribute("aria-label",play.title);
    const time = chapters.slice(0,index).reduce((s,c)=>s+c[4],0)+elapsed;
    const caption = clock(time)+" / "+clock(total);
    if (lastCaption!==caption) { bar.querySelector(".tour-time").textContent = caption; lastCaption=caption; }
    segments.forEach((b,i) => { b.firstChild.style.transform = "scaleX("+(i<index ? 1 : i>index ? 0 : elapsed/chapters[index][4])+")"; });
  }
  function tick(now) {
    if (frames && playing) {
      elapsed += Math.min(.20,Math.max(0,(now-previousTime)/1000));
      if (elapsed >= chapters[index][4]) {
        if (index === chapters.length-1) { elapsed=chapters[index][4]; playing=false; ended=true; }
        else enter(index+1);
      }
      if (chapters[index][0]==="address") {
        $(".course-form input").value = link.slice(0, Math.floor(Math.max(0,elapsed-.45)*13));
        if (elapsed>3.4 && target!==$(".course-form button")) focusOn(".course-form button","Посмотреть задания");
      }
      positionRing(); updateBar();
    }
    previousTime=now;
    requestAnimationFrame(tick);
  }
  window.addEventListener("resize",positionRing);
  document.addEventListener("keydown",e=>{
    if (e.key==="Escape") { e.preventDefault(); quit(); }
    else if (e.key===" " && (!e.target.closest("button") || e.target===play)) { e.preventDefault(); toggle(); }
    else if (e.key==="ArrowLeft" || e.key==="ArrowRight") { e.preventDefault(); enter(index+(e.key==="ArrowLeft"?-1:1)); }
    else if (e.code==="KeyH") { document.body.classList.toggle("tour-clean"); }
    else if (e.key==="Tab") {
      const controls=[...bar.querySelectorAll("button:not(:disabled)")];
      const i=controls.indexOf(document.activeElement);
      e.preventDefault(); controls[(i+(e.shiftKey?-1:1)+controls.length)%controls.length].focus();
    }
  });
  bar.querySelector("h2").textContent = "Готовим демонстрацию…";
  fetch("/tour-data.json").then(r=>{ if(!r.ok) throw Error(); return r.json(); }).then(data=>{
    frames=data;
    enter(0); play.focus(); requestAnimationFrame(tick);
  }).catch(()=>{bar.querySelector("h2").textContent="Не удалось открыть демонстрацию";bar.querySelector(".tour-sub").textContent="Закройте показ и запустите его снова.";});
})();
