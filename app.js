"use strict";

(() => {
  const { LIMITS, CATEGORIES, USAGE, initialTasks, integerInRange, validateTask, taskSummary, formatTime, Timer,
    localDateKey, periodRange, initialFocusRecords, selectFocusRecords, focusSummary, focusTrend, focusBreakdown, monthActivity, exportFocusCsv,
    initialFocusItems, validateFocusItem, focusHourDistribution, initialProgressEntries, validateProgress,
    latestProjectProgress, allTimeFocusSummary } = FocusModel;
  const UI = Object.freeze({ tickMs: 250, toastMs: 3400, ringRadius: 122, initialGoalMinutes: 240, maxSessionDots: 8,
    recordPageSize: 20, exportReleaseMs: 60000, weekDays: 7, minYear: 1900, maxYear: 2100,
    heatLevels: 4, chartAxisLabels: 5, fullCircle: 360, jsonIndent: 2 });
  const CATEGORY_COLORS = ["#c44e22", "#e8aa84", "#8b8a98", "#7d97b5"];
  const DURATION_PRESETS = [15, 25, 45, 60];
  const PAGES = ["focus", "timer", "statistics", "tasks", "usage", "profile"];
  const MODE_NAMES = { focus: "专注", short: "短休息", long: "长休息" };
  const TIMING_NAMES = { countdown: "倒计时", countup: "正计时" };
  const timer = new Timer();
  const state = {
    tasks: initialTasks(), filter: "all", period: "today", goal: UI.initialGoalMinutes,
    linkedTask: null, linkedStep: null, records: initialFocusRecords(), activeFocus: null, overviewDay: "",
    statsPeriod: "day", statsAnchor: new Date(), includeSamples: true, recordLimit: UI.recordPageSize,
    focusItems: initialFocusItems(), selectedFocusItem: "focus-reading", nextFocusItemId: 1,
    reminders: false, nextTaskId: 1, nextRecordId: 1, nextStepId: 1,
    openProjects: new Set(), progressEntries: [], progressDrafts: new Map(), nextProgressId: 1,
    pendingProgressPrompts: [], progressDialogRecord: null, progressReturnHome: false,
  };
  state.progressEntries = initialProgressEntries(state.records).map(entry => ({ ...entry, sample: true }));
  state.records.forEach(record => {
    const entry = latestRecordProgress(record.id);
    if (entry) record.progress = { note: entry.note, percent: entry.percent, updatedAt: entry.updatedAt };
  });
  const $ = id => document.getElementById(id);
  let toastTimeout;

  function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }

  function icon(name) {
    const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    const use = document.createElementNS("http://www.w3.org/2000/svg", "use");
    svg.classList.add("icon");
    svg.setAttribute("aria-hidden", "true");
    use.setAttribute("href", `#i-${name}`);
    svg.append(use);
    return svg;
  }

  function toast(message) {
    clearTimeout(toastTimeout);
    $("toast").textContent = message;
    $("toast").classList.add("visible");
    toastTimeout = setTimeout(() => $("toast").classList.remove("visible"), UI.toastMs);
  }

  function showModal(title, content) {
    state.progressDialogRecord = null;
    state.progressReturnHome = false;
    $("modal-title").textContent = title;
    $("modal-content").replaceChildren(content);
    if (!$("modal").open) $("modal").showModal();
    $("modal-content").querySelector("input, textarea, select, button")?.focus();
  }

  function finishModalClose() {
    const returnHome = state.progressReturnHome;
    state.progressDialogRecord = null;
    state.progressReturnHome = false;
    if (returnHome) navigate("focus");
    queueMicrotask(pumpProgressPrompts);
  }

  function closeModal() {
    $("modal").close();
    finishModalClose();
  }

  function information(title, paragraphs) {
    const content = element("div", "modal-copy");
    paragraphs.forEach(text => content.append(element("p", "", text)));
    const button = element("button", "primary-button", "知道了");
    button.addEventListener("click", closeModal);
    content.append(button);
    showModal(title, content);
  }

  function confirmAction(title, message, action, label = "确认", onCancel = null) {
    const content = element("div");
    content.append(element("p", "modal-copy", message));
    const actions = element("div", "modal-actions");
    const cancel = element("button", "secondary-button", "取消");
    const confirm = element("button", "primary-button", label);
    cancel.addEventListener("click", () => { closeModal(); onCancel?.(); });
    confirm.addEventListener("click", () => { closeModal(); action(); });
    actions.append(cancel, confirm);
    content.append(actions);
    showModal(title, content);
  }

  function navigate(page, updateHash = true) {
    const safePage = PAGES.includes(page) ? page : "focus";
    PAGES.forEach(name => { $(`page-${name}`).hidden = name !== safePage; });
    if (safePage === "statistics") renderStatistics();
    if (safePage === "focus") renderProjectList();
    document.querySelectorAll("[data-page]").forEach(button => {
      const active = button.dataset.page === (["statistics", "timer"].includes(safePage) ? "focus" : safePage);
      button.classList.toggle("active", active);
      if (active) button.setAttribute("aria-current", "page");
      else button.removeAttribute("aria-current");
    });
    $("phone-content").scrollTop = 0;
    if (updateHash && location.hash !== `#${safePage}`) location.hash = safePage;
  }

  function currentFocusItem() {
    return state.focusItems.find(item => item.id === state.selectedFocusItem);
  }

  function numberLabel(value) { return Number(value.toFixed(2)); }

  function itemDescription(item) {
    return `${TIMING_NAMES[item.timerMode]} · ${item.timerMode === "countup" ? "目标 " : ""}${item.durationMinutes} 分钟`;
  }

  function updateLinkedTask() {
    const task = state.tasks.find(item => item.id === state.linkedTask);
    $("linked-task-name").textContent = task ? `${task.title}${task.done ? "（已完成）" : ""}` : "不关联待办";
  }

  function renderFocusItem() {
    const item = currentFocusItem();
    $("focus-item-name").textContent = item.title;
    $("focus-item-description").textContent = `${item.category} · ${itemDescription(item)}`;
    renderProjectList();
  }

  function resetTimer() {
    timer.reset();
    state.activeFocus = null;
  }

  function applyFocusItem(item) {
    state.selectedFocusItem = item.id;
    timer.configureFocus(item);
    state.activeFocus = null;
    renderFocusItem();
    renderTimer();
  }

  function renderTimer() {
    const isCountup = timer.mode === "focus" && timer.timerMode === "countup";
    const seconds = isCountup ? timer.elapsedSeconds : timer.remainingSeconds;
    $("timer-time").textContent = formatTime(seconds);
    $("timer-time").setAttribute("aria-label", isCountup ? "已专注时间" : "剩余时间");
    const circumference = 2 * Math.PI * UI.ringRadius;
    const progress = Math.min(1, timer.elapsedMs / timer.totalMs);
    $("ring-progress").style.strokeDasharray = circumference;
    $("ring-progress").style.strokeDashoffset = circumference * (isCountup ? 1 - progress : progress);
    $("timer-phase").textContent = timer.completed ? "这一段，已记录" : timer.mode !== "focus" ? MODE_NAMES[timer.mode]
      : isCountup && timer.targetReached ? "目标已达成，继续也很好" : timer.running ? "正在专注，不负当下" : "把节奏，交还给自己";
    $("timer-caption").textContent = isCountup ? "从零累加 · 手动结束后记录" : "按设定时长倒数 · 到时完成";
    $("timer-toggle-icon").setAttribute("href", timer.running ? "#i-pause" : "#i-play");
    $("timer-toggle-label").textContent = timer.running ? "暂停计时" : timer.completed ? "再来一段" : timer.hasProgress ? "继续计时" : `开始${MODE_NAMES[timer.mode]}`;
    $("timer-finish").hidden = !isCountup || timer.completed || !timer.hasProgress;
    $("timer-finish").disabled = timer.elapsedSeconds < 1;
    $("focus-config-summary").textContent = timer.mode !== "focus" ? `${MODE_NAMES[timer.mode]} · ${timer.durations[timer.mode]} 分钟，不计入专注统计`
      : `${itemDescription(currentFocusItem())} · ${isCountup ? "达到目标提示后继续" : "到时自动完成"}`;
    document.querySelectorAll("[data-mode]").forEach(button => button.setAttribute("aria-pressed", String(button.dataset.mode === timer.mode)));
    document.querySelectorAll("[data-timing-mode]").forEach(button => {
      const selected = button.dataset.timingMode === timer.timerMode;
      button.classList.toggle("selected", selected);
      button.setAttribute("aria-pressed", String(selected));
    });
    if (state.overviewDay !== localDateKey(new Date())) {
      renderFocusOverview();
      if (!$("page-statistics").hidden) renderAllTimeSummary();
    }
    renderHomeTimerStatus();
  }

  function renderFocusOverview() {
    const now = new Date();
    const summary = focusSummary(selectFocusRecords(state.records, { period: "day", anchor: now }));
    $("focus-minutes").textContent = numberLabel(summary.minutes);
    $("focus-sessions").textContent = summary.count;
    const dots = Array.from({ length: Math.min(summary.count + 1, UI.maxSessionDots) }, (_, index) => element("i", index < summary.count ? "" : "empty"));
    $("session-dots").replaceChildren(...dots);
    state.overviewDay = localDateKey(now);
  }

  function sessionTime() {
    return Date.parse(state.activeFocus.startedAt) + performance.now() - state.activeFocus.startedMonotonic;
  }

  function startTimer() {
    if (timer.mode === "focus") {
      const task = state.tasks.find(item => item.id === state.linkedTask);
      const item = currentFocusItem();
      if (!state.activeFocus) {
        const step = linkedStepOf(task);
        state.activeFocus = { taskId: task?.id || null,
          taskTitle: task ? FocusModel.stepFocusTitle(task, step) : item.title,
          focusItemId: item.id, focusItemTitle: item.title, category: item.category, timerMode: item.timerMode,
          targetMinutes: item.durationMinutes, startedAt: new Date().toISOString(), startedMonotonic: performance.now(),
          segments: [], segmentStart: null, targetNotified: false };
      }
      state.activeFocus.segmentStart = sessionTime();
      state.activeFocus.expectedEndAt = state.activeFocus.segmentStart + timer.remainingMs;
    }
    timer.start();
  }

  function closeActiveSegment(endedAt) {
    const session = state.activeFocus;
    if (session.segmentStart !== null && endedAt > session.segmentStart) {
      session.segments.push({ startedAt: new Date(session.segmentStart).toISOString(), endedAt: new Date(endedAt).toISOString() });
    }
    session.segmentStart = null;
  }

  function pauseTimer() {
    if (timer.mode === "focus") closeActiveSegment(timer.timerMode === "countup" ? sessionTime() : Math.min(sessionTime(), state.activeFocus.expectedEndAt));
    timer.pause();
  }

  function saveFocusCompletion(completion, endedAt) {
    const session = state.activeFocus;
    const durationSeconds = Math.round(completion.minutes * LIMITS.secondsPerMinute);
    const { taskId, taskTitle, focusItemId, focusItemTitle, category, timerMode, targetMinutes, startedAt, segments } = session;
    const record = { id: `session-${state.nextRecordId++}`, taskId, taskTitle, focusItemId, focusItemTitle, category, timerMode,
      targetMinutes, startedAt, endedAt: new Date(endedAt).toISOString(), segments,
      durationSeconds, durationMinutes: durationSeconds / LIMITS.secondsPerMinute, source: "session" };
    state.records.push(record);
    state.activeFocus = null;
    renderFocusOverview();
    renderProjectList();
    if (!$("page-statistics").hidden) renderStatistics();
    state.pendingProgressPrompts.push(record.id);
    queueMicrotask(pumpProgressPrompts);
  }

  function finishCountup() {
    if (timer.mode !== "focus" || timer.timerMode !== "countup" || timer.completed || !state.activeFocus) return;
    const wasRunning = timer.running;
    const endedAt = sessionTime();
    const completion = timer.finish();
    if (!completion) return toast("至少专注 1 秒后再记录。");
    if (wasRunning) closeActiveSegment(endedAt);
    saveFocusCompletion(completion, endedAt);
    renderTimer();
    toast("本次正计时已记录，暂停时间不计入专注。");
  }

  function tickTimer() {
    const completion = timer.tick();
    if (completion) {
      if (completion.mode === "focus") {
        closeActiveSegment(state.activeFocus.expectedEndAt);
        saveFocusCompletion(completion, state.activeFocus.expectedEndAt);
      }
      toast(`${MODE_NAMES[completion.mode]}结束。${completion.mode === "focus" ? "这一段已记录，休息一下吧。" : "准备好后，再开始下一段专注。"}`);
    }
    if (timer.mode === "focus" && timer.targetReached && state.activeFocus && !state.activeFocus.targetNotified) {
      state.activeFocus.targetNotified = true;
      toast("目标已达成！计时将继续，完成后点击「结束并记录」。");
    }
    renderTimer();
  }

  function guardedTimerChange(action, message, onCancel = null) {
    tickTimer();
    if (!timer.completed && timer.hasProgress) {
      const advice = timer.mode === "focus" && timer.timerMode === "countup"
        ? "如需保留本次正计时，请先取消，再点击「结束并记录」。"
        : timer.mode === "focus" ? "未完成的倒计时不会计入统计。" : "休息不计入专注统计。";
      confirmAction("放弃当前这段计时？", `${message} ${advice} 确认后会放弃未保存的进度。`, action, "放弃并继续", onCancel);
    } else action();
  }

  function field(form, label, name, value, options = {}) {
    const wrapper = element("label", "field", label);
    const input = element(options.choices ? "select" : options.multiline ? "textarea" : "input");
    input.name = name;
    if (options.choices) options.choices.forEach(choice => {
      const option = element("option", "", choice);
      option.value = choice;
      input.append(option);
    });
    else {
      if (options.multiline) input.rows = 5;
      else input.type = options.type || "text";
      if (options.min !== undefined) input.min = options.min;
      if (options.max !== undefined) input.max = options.max;
      if (options.maxLength) input.maxLength = options.maxLength;
      if (input.type === "number") input.step = "1";
    }
    input.required = true;
    input.value = value;
    wrapper.append(input);
    form.append(wrapper);
    return input;
  }

  function latestRecordProgress(recordId) {
    return state.progressEntries.filter(entry => entry.recordId === recordId).at(-1) || null;
  }

  function currentProjectProgress(focusItemId) {
    const entered = state.progressEntries.filter(entry => entry.focusItemId === focusItemId && !entry.sample);
    return latestProjectProgress(entered.length ? entered : state.progressEntries, focusItemId);
  }

  function progressHistory(focusItemId) {
    return state.progressEntries.filter(entry => entry.focusItemId === focusItemId).reverse()
      .sort((left, right) => Date.parse(right.recordEndedAt) - Date.parse(left.recordEndedAt) || Date.parse(right.updatedAt) - Date.parse(left.updatedAt));
  }

  function localTimestamp(value) {
    const date = new Date(value);
    return `${localDateKey(date)} ${date.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit", hour12: false })}`;
  }

  function renderHomeTimerStatus() {
    $("resume-session").hidden = timer.completed || (!timer.running && !timer.hasProgress);
    const title = `${timer.running ? "正在" : "继续"}${MODE_NAMES[timer.mode]} · ${currentFocusItem().title}`;
    const caption = `${timer.running ? "计时进行中" : "已暂停"} · 点击回到计时页面`;
    if ($("resume-session-title").textContent !== title) $("resume-session-title").textContent = title;
    if ($("resume-session-caption").textContent !== caption) $("resume-session-caption").textContent = caption;
  }

  function openProject(item) {
    const open = () => {
      if (item.id !== state.selectedFocusItem) {
        state.linkedTask = null;
        state.linkedStep = null;
        applyFocusItem(item);
        updateLinkedTask();
      }
      navigate("timer");
    };
    if (item.id === state.selectedFocusItem) open();
    else guardedTimerChange(open, "切换学习项目会重置当前计时。");
  }

  function renderProjectList() {
    $("project-list").replaceChildren(...state.focusItems.map((item, index) => {
      const records = state.records.filter(record => record.focusItemId === item.id);
      const summary = focusSummary(records);
      const latest = currentProjectProgress(item.id);
      const pending = records.filter(record => record.source === "session" && !latestRecordProgress(record.id));
      const card = element("article", "project-card");
      card.setAttribute("role", "listitem");
      card.dataset.projectId = item.id;
      const row = element("div", "project-row");
      const main = element("button", "project-main");
      main.setAttribute("aria-label", `进入项目：${item.title}`);
      main.append(element("strong", "project-title", item.title), element("span", "project-meta", itemDescription(item)),
        element("span", "project-meta", `累计 ${numberLabel(summary.minutes)} 分钟 · ${summary.count} 次专注${latest?.percent != null ? ` · 进度 ${latest.percent}%` : ""}${pending.length ? ` · ${pending.length} 条待补写` : ""}`));
      main.addEventListener("click", () => openProject(item));
      const expanded = state.openProjects.has(item.id);
      const expand = element("button", "project-expand");
      expand.id = `project-expand-${index}`;
      expand.setAttribute("aria-label", `展开项目详情：${item.title}`);
      expand.setAttribute("aria-expanded", String(expanded));
      expand.setAttribute("aria-controls", `project-details-${index}`);
      expand.append(icon("arrow"));
      expand.addEventListener("click", () => {
        if (expanded) state.openProjects.delete(item.id);
        else state.openProjects.add(item.id);
        renderProjectList();
        document.getElementById(`project-expand-${index}`).focus({ preventScroll: true });
      });
      row.append(main, expand);
      const details = element("div", "project-details");
      details.id = `project-details-${index}`;
      details.hidden = !expanded;
      const heading = element("div", "project-progress-heading");
      heading.append(element("h3", "", "当前学习进度"), element("span", "sample-tag", latest?.percent != null ? `${latest.percent}%` : "未填写完成度"));
      details.append(heading);
      if (latest?.percent != null) {
        const progress = element("progress", "project-progress-bar");
        progress.max = 100;
        progress.value = latest.percent;
        progress.setAttribute("aria-label", `${item.title}完成度 ${latest.percent}%`);
        details.append(progress);
      }
      details.append(element("p", "project-progress-note", latest?.note || "还没有进度记录。完成一次专注后，记下学到了哪里、接下来做什么。"));
      if (latest) details.append(element("p", "project-progress-meta", `${latest.sample ? "示例进度 · " : ""}专注结束：${localTimestamp(latest.recordEndedAt)}\n更新：${localTimestamp(latest.updatedAt)}`));
      pending.forEach(record => {
        const fill = element("button", "project-pending-button", `补写进度 · ${localTimestamp(record.endedAt)} · ${formatTime(record.durationSeconds)}`);
        fill.dataset.recordId = record.id;
        fill.addEventListener("click", () => progressEditor(record.id));
        details.append(fill);
      });
      const actions = element("div", "project-detail-actions");
      const history = element("button", "text-button", `进度历史（${progressHistory(item.id).length}）`);
      history.addEventListener("click", () => showProgressHistory(item));
      const settings = element("button", "text-button", "项目设置");
      settings.setAttribute("aria-label", `编辑项目：${item.title}`);
      settings.addEventListener("click", () => focusItemEditor(item.id));
      actions.append(history, settings);
      if (latest) {
        const edit = element("button", "text-button", "编辑本次进度");
        edit.addEventListener("click", () => progressEditor(latest.recordId));
        actions.append(edit);
      }
      details.append(actions);
      card.append(row, details);
      return card;
    }));
    renderHomeTimerStatus();
  }

  function showProgressHistory(item) {
    const content = element("div");
    const list = element("ol", "project-history");
    progressHistory(item.id).forEach(entry => {
      const row = element("li", "project-history-entry");
      row.append(element("strong", "", entry.percent == null ? "未填写完成度" : `完成度 ${entry.percent}%`),
        element("p", "project-progress-note", entry.note),
        element("p", "project-history-meta", `${entry.sample ? "示例 · " : ""}专注：${localTimestamp(entry.recordEndedAt)}\n更新：${localTimestamp(entry.updatedAt)}`));
      list.append(row);
    });
    if (!list.children.length) content.append(element("p", "data-note", "尚无进度历史。结束专注后可填写，也可以稍后补写。"));
    content.append(list);
    showModal(`${item.title} · 进度历史`, content);
  }

  function pumpProgressPrompts() {
    if (document.hidden || $("modal").open) return;
    while (state.pendingProgressPrompts.length) {
      const id = state.pendingProgressPrompts.shift();
      if (!latestRecordProgress(id)) {
        progressEditor(id, true);
        return;
      }
    }
  }

  function progressEditor(recordId, afterSession = false) {
    const record = state.records.find(entry => entry.id === recordId);
    if (!record) return;
    state.pendingProgressPrompts = state.pendingProgressPrompts.filter(id => id !== recordId);
    const previous = latestRecordProgress(recordId) || currentProjectProgress(record.focusItemId);
    const draft = state.progressDrafts.get(recordId);
    const form = element("form", "progress-form");
    form.dataset.recordId = recordId;
    form.append(element("p", "progress-context", `${record.focusItemTitle}\n${TIMING_NAMES[record.timerMode]} · 有效专注 ${formatTime(record.durationSeconds)}\n专注时长已保存，填写进度不会影响统计。`));
    const validation = element("div", "modal-notice");
    validation.hidden = true;
    validation.setAttribute("role", "alert");
    form.append(validation);
    const note = field(form, "目前学到哪里了？", "note", draft?.note ?? previous?.note ?? "", { multiline: true, maxLength: LIMITS.maxProgressNote });
    note.placeholder = "例如：完成登录接口和参数校验；下一步接入 JWT，并补充测试。";
    const percent = field(form, "项目完成度（0–100%，可选）", "percent", draft?.percent ?? previous?.percent ?? "", { type: "number", min: 0, max: 100 });
    percent.required = false;
    form.append(element("p", "progress-help", "每次保存保留历史版本。补写较早的专注不会覆盖较新一次的当前进度；未保存的草稿仅在本次页面中保留。"));
    form.addEventListener("input", () => {
      validation.hidden = true;
      state.progressDrafts.set(recordId, { note: note.value, percent: percent.value });
    });
    const actions = element("div", "modal-actions");
    const skip = element("button", "secondary-button", "稍后再写");
    skip.type = "button";
    skip.addEventListener("click", closeModal);
    const save = element("button", "primary-button", afterSession ? "保存进度并返回项目" : "保存进度");
    save.type = "submit";
    actions.append(skip, save);
    form.append(actions);
    form.addEventListener("submit", event => {
      event.preventDefault();
      let progress;
      try { progress = validateProgress({ note: note.value, percent: percent.value }); }
      catch (error) { validation.textContent = error.message; validation.hidden = false; return; }
      const previousRevision = latestRecordProgress(recordId);
      const updatedAt = Math.max(Date.now(), previousRevision && !previousRevision.sample ? Date.parse(previousRevision.updatedAt) : 0);
      const entry = { ...progress, id: `progress-${state.nextProgressId++}`, recordId, focusItemId: record.focusItemId,
        recordEndedAt: record.endedAt, updatedAt: new Date(updatedAt).toISOString(), sample: false };
      state.progressEntries.push(entry);
      record.progress = { ...progress, updatedAt: entry.updatedAt };
      state.progressDrafts.delete(recordId);
      state.openProjects.add(record.focusItemId);
      renderProjectList();
      if (!$("page-statistics").hidden) renderStatistics();
      closeModal();
      toast("学习进度已保存，历史版本保留。");
    });
    showModal(afterSession ? "这一段，推进了什么？" : "编辑学习进度", form);
    state.progressDialogRecord = recordId;
    state.progressReturnHome = afterSession;
  }

  function focusItemEditor(id) {
    const item = state.focusItems.find(entry => entry.id === id);
    if (!item && state.focusItems.length >= LIMITS.maxFocusItems) return toast(`最多支持 ${LIMITS.maxFocusItems} 个专注项。`);
    const form = element("form");
    const validation = element("div", "modal-notice");
    validation.setAttribute("role", "alert");
    validation.hidden = true;
    form.append(validation);
    field(form, "专注项名称", "title", item?.title || "", { maxLength: LIMITS.maxTaskTitle });
    field(form, "分类", "category", item?.category || CATEGORIES[0], { choices: CATEGORIES });
    const modeInput = field(form, "计时模式", "timerMode", TIMING_NAMES[item?.timerMode || "countdown"], { choices: Object.values(TIMING_NAMES) });
    const durationInput = field(form, "时长（分钟）", "durationMinutes", item?.durationMinutes || timer.durations.focus, { type: "number", min: LIMITS.minTimerMinutes, max: LIMITS.maxTimerMinutes });
    const presets = element("div", "duration-presets");
    DURATION_PRESETS.forEach(minutes => {
      const preset = element("button", "secondary-button", `${minutes} 分钟`);
      preset.type = "button";
      preset.dataset.minutes = minutes;
      preset.addEventListener("click", () => { durationInput.value = minutes; updatePresets(); });
      presets.append(preset);
    });
    const updatePresets = () => presets.querySelectorAll("button").forEach(button => {
      const selected = Number(button.dataset.minutes) === Number(durationInput.value);
      button.classList.toggle("selected", selected);
      button.setAttribute("aria-pressed", String(selected));
    });
    durationInput.addEventListener("input", updatePresets);
    updatePresets();
    form.append(presets);
    const hint = element("p", "data-note");
    const updateHint = () => {
      const countup = modeInput.value === TIMING_NAMES.countup;
      durationInput.parentElement.firstChild.textContent = countup ? "目标时长（分钟）" : "倒计时时长（分钟）";
      hint.textContent = countup ? "从 00:00 累加，达到目标提示后继续。点击「结束并记录」保存实际专注时长，暂停不计时。"
        : "从设定时长递减，到零后自动完成并记录。可自定义 1–120 分钟。";
    };
    updateHint();
    modeInput.addEventListener("change", updateHint);
    form.addEventListener("input", () => { validation.hidden = true; });
    form.append(hint);
    const actions = element("div", "modal-actions");
    if (item) {
      const remove = element("button", "secondary-button danger-button", "删除专注项");
      remove.type = "button";
      remove.disabled = state.focusItems.length === 1;
      remove.title = remove.disabled ? "至少保留一个专注项" : "删除不会影响已保存的专注记录";
      remove.addEventListener("click", () => confirmAction("删除这个专注项？", `删除「${item.title}」不会影响历史记录。若正在使用此项，当前未保存的计时也会放弃。`, () => {
        state.focusItems = state.focusItems.filter(entry => entry.id !== item.id);
        state.openProjects.delete(item.id);
        if (state.selectedFocusItem === item.id) applyFocusItem(state.focusItems[0]);
        navigate("focus");
        toast("项目已删除，专注记录和进度历史保留。");
      }, "确认删除"));
      actions.append(remove);
    }
    const save = element("button", "primary-button", item ? "保存专注项" : "添加项目");
    save.type = "submit";
    actions.append(save);
    form.append(actions);
    form.addEventListener("submit", event => {
      event.preventDefault();
      const data = Object.fromEntries(new FormData(form));
      let validated;
      try {
        validated = validateFocusItem({ ...data, timerMode: data.timerMode === TIMING_NAMES.countup ? "countup" : "countdown" });
      } catch (error) {
        validation.textContent = error.message;
        validation.hidden = false;
        return;
      }
      const saveItem = () => {
        if (item) Object.assign(item, validated);
        else state.focusItems.push({ ...validated, id: `focus-custom-${state.nextFocusItemId++}` });
        if (item?.id === state.selectedFocusItem) applyFocusItem(item);
        renderProjectList();
        if (!item) navigate("focus");
        toast("项目配置已保存，点击项目进入计时。");
      };
      closeModal();
      if (item?.id === state.selectedFocusItem) guardedTimerChange(saveItem, "修改当前项目配置会重置当前计时。", () => showModal("项目设置", form));
      else saveItem();
    });
    showModal(item ? "项目设置" : "新建学习项目", form);
  }

  function timerSettings() {
    const form = element("form");
    form.append(element("div", "modal-notice", "专注时长在各专注项中设置；这里仅配置休息时长。休息不会计入专注统计。"));
    ["short", "long"].forEach(key => field(form, `${MODE_NAMES[key]}（分钟）`, key, timer.durations[key], { type: "number", min: LIMITS.minTimerMinutes, max: LIMITS.maxTimerMinutes }));
    const save = element("button", "primary-button", "保存偏好");
    save.type = "submit";
    form.append(save);
    form.addEventListener("submit", event => {
      event.preventDefault();
      const durations = { ...timer.durations, ...Object.fromEntries(new FormData(form)) };
      const saveDurations = () => {
        timer.setDurations(durations);
        state.activeFocus = null;
        $("preferences-caption").textContent = `短休息 ${timer.durations.short} 分钟 / 长休息 ${timer.durations.long} 分钟`;
        renderTimer();
        toast("休息偏好已用于本次演示。");
      };
      closeModal();
      guardedTimerChange(saveDurations, "保存休息偏好会重置当前计时。", () => showModal("休息偏好", form));
    });
    showModal("休息偏好", form);
  }

  /** 小步编辑：加一步、改名、删一步。用 DOM 节点直接承载，不拼 HTML 字符串。 */
  function buildStepEditor(draftSteps, onChange) {
    const wrapper = element("div", "step-editor");
    wrapper.append(element("p", "step-editor-title", "拆成几小步（可不填）"));
    const list = element("div", "step-editor-list");
    const add = element("button", "add-step", "＋ 加一步");
    add.type = "button";

    const draw = () => {
      const rows = draftSteps.map((step, index) => {
        const row = element("div", "step-editor-row");
        const input = element("input");
        input.type = "text";
        input.value = step.title;
        input.maxLength = LIMITS.maxTaskStepTitle;
        input.setAttribute("aria-label", `第 ${index + 1} 步`);
        input.addEventListener("input", () => { step.title = input.value; onChange(); });
        const remove = element("button", "step-remove", "×");
        remove.type = "button";
        remove.setAttribute("aria-label", `删掉第 ${index + 1} 步`);
        remove.addEventListener("click", () => { draftSteps.splice(index, 1); draw(); onChange(); });
        row.append(input, remove);
        return row;
      });
      list.replaceChildren(...rows);
      add.disabled = draftSteps.length >= LIMITS.maxTaskSteps;
      add.textContent = add.disabled ? `最多 ${LIMITS.maxTaskSteps} 步` : "＋ 加一步";
    };

    add.addEventListener("click", () => {
      if (draftSteps.length >= LIMITS.maxTaskSteps) return;
      draftSteps.push({ id: `step-new-${state.nextStepId++}`, title: "", done: false });
      draw();
      list.querySelector(".step-editor-row:last-child input")?.focus();
      onChange();
    });

    draw();
    wrapper.append(list, add);
    return wrapper;
  }

  function taskEditor(id) {
    const task = state.tasks.find(item => item.id === id);
    if (!task && state.tasks.length >= LIMITS.maxTasks) return toast(`演示最多支持 ${LIMITS.maxTasks} 个任务。`);
    const form = element("form");
    const validation = element("div", "modal-notice");
    validation.id = "task-validation-error";
    validation.setAttribute("role", "alert");
    validation.hidden = true;
    form.append(validation);
    const titleInput = field(form, "想完成什么？", "title", task?.title || "", { maxLength: LIMITS.maxTaskTitle });
    titleInput.setAttribute("aria-describedby", validation.id);
    form.addEventListener("input", () => { validation.hidden = true; });
    field(form, "分类", "category", task?.category || CATEGORIES[0], { choices: CATEGORIES });
    field(form, "优先级", "priority", task?.important ? "重要" : "普通", { choices: ["普通", "重要"] });
    // M：小步是可选的。草稿只在弹窗生命周期内，取消即丢弃。
    const draftSteps = (task?.steps || []).map(step => ({ ...step }));
    form.append(buildStepEditor(draftSteps, () => { validation.hidden = true; }));
    const actions = element("div", "modal-actions");
    if (task?.archived) {
      const restore = element("button", "secondary-button", "找回");
      restore.type = "button";
      restore.addEventListener("click", () => {
        task.archived = false;
        task.createdAt = FocusModel.localDateKey(new Date());
        state.filter = "all";
        closeModal();
        renderTasks();
        toast(`「${task.title}」回到清单。`);
      });
      actions.append(restore);
    }
    if (task) {
      const remove = element("button", "secondary-button danger-button", "删除");
      remove.type = "button";
      remove.addEventListener("click", () => confirmAction("删除这个待办？", `将从本次演示中移除「${task.title}」。`, () => {
        state.tasks = state.tasks.filter(item => item.id !== task.id);
        if (state.linkedTask === task.id) { state.linkedTask = null; state.linkedStep = null; }
        renderTasks();
        updateLinkedTask();
        toast("待办已删除。");
      }, "确认删除"));
      actions.append(remove);
    }
    const submit = element("button", "primary-button", task ? "保存修改" : "添加待办");
    submit.type = "submit";
    actions.append(submit);
    form.append(actions);
    form.addEventListener("submit", event => {
      event.preventDefault();
      const data = Object.fromEntries(new FormData(form));
      try {
        const validated = validateTask({ ...data, important: data.priority === "重要", steps: draftSteps });
        if (task) {
          Object.assign(task, validated);
          // 小步被删光时不该把已完成的事回退成未完成。
          if (validated.steps.length) task.done = validated.steps.every(step => step.done);
        } else {
          state.tasks.push({ ...validated, id: `new-${state.nextTaskId++}`, done: false,
            createdAt: FocusModel.localDateKey(new Date()), archived: false });
        }
        state.filter = "all";
        closeModal();
        renderTasks();
        updateLinkedTask();
        toast(task ? "待办已更新。" : "小目标已记下，现在就可以开始。");
      } catch (error) {
        validation.textContent = error.message;
        validation.hidden = false;
      }
    });
    showModal(task ? "编辑这个小目标" : "记下一个小目标", form);
  }

  function focusOnTask(task, step = null) {
    const action = () => {
      state.linkedTask = task.id;
      // 子步骤不单独入账：记录仍挂在父待办上，只有标题快照记成「父任务 · 这一步」。
      state.linkedStep = step ? step.id : null;
      applyFocusItem(currentFocusItem());
      updateLinkedTask();
      navigate("timer");
      const what = step ? `这一步「${step.title}」` : "待办";
      toast(`${what}已关联，使用「${currentFocusItem().title}」的${itemDescription(currentFocusItem())}。`);
    };
    guardedTimerChange(action, "更换专注任务会重置当前计时，是否继续？");
  }

  function linkedStepOf(task) {
    if (!task || !state.linkedStep) return null;
    return (task.steps || []).find(step => step.id === state.linkedStep) || null;
  }

  function renderTasks() {
    const summary = taskSummary(activeTasks());
    $("task-summary").textContent = `已完成 ${summary.done} / ${summary.total} 件`;
    $("completion-percent").replaceChildren(document.createTextNode(summary.percent), element("span", "", "%"));
    $("completion-bar").style.width = `${summary.percent}%`;
    $("all-count").textContent = summary.total;
    document.querySelectorAll("[data-filter]").forEach(button => {
      const selected = state.filter === button.dataset.filter;
      button.classList.toggle("selected", selected);
      button.setAttribute("aria-pressed", String(selected));
    });
    const today = FocusModel.localDateKey(new Date());
    const visible = state.tasks.filter(task => {
      if (state.filter === "archived") return task.archived;
      if (task.archived) return false;
      if (state.filter === "done") return task.done;
      if (state.filter === "pending") return !task.done;
      return true;
    });
    const groups = FocusModel.groupTasks(visible).map(group => buildTaskGroup(group, today));
    $("task-list").replaceChildren(...(groups.length ? groups : [element("div", "empty-state", emptyTaskMessage())]));
  }

  /** 重要的事自成一组排在最前，其余按分类分开，每组一个小标题。 */
  function buildTaskGroup(group, today) {
    const section = element("div", `task-group${group.key === "important" ? " important" : ""}`);
    section.setAttribute("role", "group");
    const heading = element("div", "task-group-heading");
    heading.append(element("span", "task-group-label", group.label), element("span", "task-group-count", String(group.tasks.length)));
    const list = element("div", "task-group-list");
    list.setAttribute("role", "list");
    list.append(...group.tasks.map(task => buildTaskCard(task, today, group.key)));
    section.append(heading, list);
    section.setAttribute("aria-label", `${group.label}：${group.tasks.length} 件`);
    return section;
  }

  function activeTasks() {
    return state.tasks.filter(task => !task.archived);
  }

  function emptyTaskMessage() {
    if (state.filter === "done") return "还没有已完成的任务，慢慢来。";
    if (state.filter === "archived") return "还没有放下的事。放下不是删除，是承认它这阵子不重要。";
    return "这里空空的，给今天留一点自由。";
  }

  /** L 的发酵状态 + M 的小步路径，都在这张卡片上。 */
  function buildTaskCard(task, today, groupKey = "") {
    const age = FocusModel.taskAge(task, today);
    const progress = FocusModel.taskProgress(task);
    const card = element("div", `task-card${task.done ? " done" : ""}${task.archived ? " archived" : ""} age-${age.stage}`);
    card.setAttribute("role", "listitem");

    const check = element("button", "task-checkbox");
    check.setAttribute("aria-label", `${task.done ? "重新打开" : "完成"}任务：${task.title}`);
    check.setAttribute("aria-pressed", String(task.done));
    if (task.done) check.append(icon("check"));
    check.addEventListener("click", () => { toggleTaskDone(task); });

    const info = element("button", "task-info");
    info.setAttribute("aria-label", `编辑任务：${task.title}`);
    const meta = element("div", "task-meta");
    // 组标题已经说明了重要或分类，卡片上不再重复同一个标签。
    if (task.important && groupKey !== "important") meta.append(element("span", "task-tag important", "重要"));
    if (groupKey === "important" || groupKey !== `category:${task.category}`) meta.append(element("span", "task-tag", task.category));
    if (!task.done && !task.archived) meta.append(element("span", "task-age", age.label));
    if (task.archived) meta.append(element("span", "task-age", "已放下"));
    info.append(element("div", "task-title", task.title), meta);
    info.addEventListener("click", () => taskEditor(task.id));

    const body = element("div", "task-body");
    body.append(info);
    if (progress.total) body.append(buildStepPath(task, progress));
    if (!task.done && !task.archived && age.stage !== "fresh") body.append(buildAgeActions(task));

    card.append(check, body);
    if (!task.done && !task.archived) {
      const start = element("button", "task-start");
      const step = FocusModel.nextStep(task);
      start.setAttribute("aria-label", step ? `专注于这一步：${step.title}` : `专注于：${task.title}`);
      start.append(icon("focus"));
      start.addEventListener("click", () => focusOnTask(task, step));
      card.append(start);
    }
    return card;
  }

  /** M：一条横向的小路，走过的点是实心的。点一个点就把那一步标为走过或退回。 */
  function buildStepPath(task, progress) {
    const path = element("div", "step-path");
    path.setAttribute("role", "group");
    path.setAttribute("aria-label", `${task.title}：${progress.walked} / ${progress.total} 步`);
    const track = element("div", "step-track");
    task.steps.forEach((step, index) => {
      if (index > 0) track.append(element("span", `step-link${step.done ? " walked" : ""}`));
      const dot = element("button", `step-dot${step.done ? " walked" : ""}`);
      dot.setAttribute("aria-label", `${step.done ? "已走过" : "还没走"}：${step.title}`);
      dot.setAttribute("aria-pressed", String(step.done));
      dot.addEventListener("click", () => toggleStep(task, step));
      track.append(dot);
    });
    const next = FocusModel.nextStep(task);
    path.append(track, element("p", "step-caption", next ? `下一步 · ${next.title}` : `${progress.total} 步都走完了`));
    return path;
  }

  /** L：躺久了的事给两个出口——续一天，或放下。 */
  function buildAgeActions(task) {
    const row = element("div", "age-actions");
    const renew = element("button", "age-action", "续一天");
    renew.setAttribute("aria-label", `把「${task.title}」重新放到今天`);
    renew.addEventListener("click", () => {
      task.createdAt = FocusModel.localDateKey(new Date());
      renderTasks();
      toast(`「${task.title}」回到今天。`);
    });
    const release = element("button", "age-action quiet", "放下");
    release.setAttribute("aria-label", `放下「${task.title}」`);
    release.addEventListener("click", () => {
      task.archived = true;
      if (state.linkedTask === task.id) { state.linkedTask = null; state.linkedStep = null; }
      renderTasks();
      updateLinkedTask();
      toast(`「${task.title}」已放下，可以在「放下的」里找回。`);
    });
    row.append(renew, release);
    return row;
  }

  function toggleTaskDone(task) {
    task.done = !task.done;
    // 勾掉父任务时把没走完的小步一并算走过，避免进度和状态互相矛盾。
    if (task.done && Array.isArray(task.steps)) task.steps.forEach(step => { step.done = true; });
    renderTasks();
    updateLinkedTask();
  }

  function toggleStep(task, step) {
    step.done = !step.done;
    // 小步全部走完，父任务随之完成；有任何一步回退，父任务也回到未完成。
    task.done = task.steps.length > 0 && task.steps.every(item => item.done);
    renderTasks();
    updateLinkedTask();
  }

  function chooseTask() {
    const content = element("div");
    const choices = [{ id: null, title: "不关联待办" }, ...state.tasks.filter(task => !task.done)];
    choices.forEach(task => {
      const button = element("button", "task-choice", task.title);
      button.addEventListener("click", () => {
        closeModal();
        if (state.linkedTask !== task.id) guardedTimerChange(() => {
          state.linkedTask = task.id;
          state.linkedStep = null;
          resetTimer();
          updateLinkedTask();
          renderTimer();
        }, "更换关联任务会重置当前计时，是否继续？");
      });
      content.append(button);
    });
    showModal("这一段，想做什么？", content);
  }

  function durationLabel(minutes) {
    const hours = Math.floor(minutes / LIMITS.secondsPerMinute);
    const remainder = minutes % LIMITS.secondsPerMinute;
    return [hours ? `${hours} 小时` : "", remainder ? `${remainder} 分钟` : ""].filter(Boolean).join(" ") || "0 分钟";
  }

  function sourceRecords() {
    return state.includeSamples ? state.records : state.records.filter(record => record.source !== "sample");
  }

  function totalDurationLabel(minutes) {
    const seconds = Math.round(minutes * LIMITS.secondsPerMinute);
    const hours = Math.floor(seconds / (LIMITS.secondsPerMinute * LIMITS.secondsPerMinute));
    const remainder = Math.floor(seconds / LIMITS.secondsPerMinute) % LIMITS.secondsPerMinute;
    const finalSeconds = seconds % LIMITS.secondsPerMinute;
    return [hours ? `${hours} 小时` : "", remainder ? `${remainder} 分钟` : "", finalSeconds ? `${finalSeconds} 秒` : ""].filter(Boolean).join(" ") || "0 分钟";
  }

  function renderAllTimeSummary() {
    const summary = allTimeFocusSummary(sourceRecords());
    $("alltime-duration").textContent = totalDurationLabel(summary.minutes);
    $("alltime-duration").dataset.minutes = summary.minutes;
    $("alltime-calendar-average").textContent = numberLabel(summary.calendarAverageMinutes);
    $("alltime-active-average").textContent = numberLabel(summary.activeAverageMinutes);
  }

  function showAllTimeHelp() {
    const summary = allTimeFocusSummary(sourceRecords());
    const today = localDateKey(new Date());
    information("累计统计说明", [
      summary.firstDate ? `统计范围：${summary.firstDate} 至 ${today} · ${summary.count} 次专注。` : `统计范围：截至 ${today} 暂无记录 · ${summary.count} 次专注。完成一次专注后开始积累。`,
      `累计总时长：${totalDurationLabel(summary.minutes)}。汇总当前来源中截至今天已完成的全部专注，不含暂停、休息和放弃的计时；有效时长精确到秒。`,
      `自然日均 = 累计专注分钟数 ÷ 自然日天数。分母：${summary.calendarDays} 个自然日，从首次完成日至今天，首尾均计入，包含没有专注的空白日。当前自然日均：${numberLabel(summary.calendarAverageMinutes)} 分钟。`,
      `活跃日均 = 累计专注分钟数 ÷ 有专注记录的天数。分母：${summary.activeDays} 个活跃日，只计有完成记录的日期。当前活跃日均：${numberLabel(summary.activeAverageMinutes)} 分钟。`,
      "日期按本地完成日期计算，自然日天数按日历差计算；日均分钟数四舍五入至两位小数，没有记录时三项指标均为 0。",
      `当前数据来源：${state.includeSamples ? "示例 + 本次计时（含演示记录）" : "仅本次计时（不含示例）"}。原型数据仅驻留内存，刷新恢复示例，不代表真实设备使用数据。`,
      "累计总览遵循数据来源筛选，不受下方日、周、月、年周期和所选日期影响。",
    ]);
  }

  function selectedRecords() {
    return selectFocusRecords(state.records, { period: state.statsPeriod, anchor: state.statsAnchor, includeSamples: state.includeSamples });
  }

  function rangeLabel() {
    const { start, end } = periodRange(state.statsPeriod, state.statsAnchor);
    const lastDay = new Date(end);
    lastDay.setDate(lastDay.getDate() - 1);
    return state.statsPeriod === "day" ? localDateKey(start) : `${localDateKey(start)} — ${localDateKey(lastDay)}`;
  }

  function changeStatsPeriod(period) {
    state.statsPeriod = period;
    state.recordLimit = UI.recordPageSize;
    renderStatistics();
  }

  function shiftStatsPeriod(direction) {
    const { start } = periodRange(state.statsPeriod, state.statsAnchor);
    if (state.statsPeriod === "day") start.setDate(start.getDate() + direction);
    if (state.statsPeriod === "week") start.setDate(start.getDate() + direction * UI.weekDays);
    if (state.statsPeriod === "month") start.setMonth(start.getMonth() + direction);
    if (state.statsPeriod === "year") start.setFullYear(start.getFullYear() + direction);
    if (start.getFullYear() < UI.minYear || start.getFullYear() > UI.maxYear) return toast("日期范围为 1900–2100 年。");
    state.statsAnchor = start;
    state.recordLimit = UI.recordPageSize;
    renderStatistics();
  }

  function renderStatistics() {
    renderAllTimeSummary();
    const records = selectedRecords();
    const summary = focusSummary(records);
    document.querySelectorAll("[data-stat-period]").forEach(button => {
      const selected = button.dataset.statPeriod === state.statsPeriod;
      button.classList.toggle("selected", selected);
      button.setAttribute("aria-pressed", String(selected));
    });
    $("statistics-anchor").value = localDateKey(state.statsAnchor);
    $("statistics-source").value = state.includeSamples ? "all" : "session";
    $("statistics-range").textContent = rangeLabel();
    $("statistics-minutes").textContent = numberLabel(summary.minutes);
    $("statistics-count").textContent = summary.count;
    $("statistics-days").textContent = summary.activeDays;
    $("statistics-average").textContent = numberLabel(summary.averageMinutes);
    const sampleCount = records.filter(record => record.source === "sample").length;
    $("statistics-source-note").textContent = `示例 ${sampleCount} 条 / 本次计时 ${records.length - sampleCount} 条 · 刷新重置`;
    $("export-statistics").disabled = records.length === 0;
    renderFocusTrend(records);
    renderHourDistribution(records);
    renderFocusCategories(records, summary.minutes);
    renderFocusCalendar();
    renderFocusRanking(records);
    renderFocusRecords(records);
  }

  function renderFocusTrend(records) {
    const trend = focusTrend(records, state.statsPeriod, state.statsAnchor);
    const peak = Math.max(0, ...trend.map(bin => bin.minutes));
    const total = trend.reduce((sum, bin) => sum + bin.minutes, 0);
    $("statistics-trend-section").hidden = state.statsPeriod === "day";
    $("statistics-trend-title").textContent = "专注时长趋势";
    $("statistics-trend").replaceChildren(...trend.map(bin => {
      const column = element("div", "trend-column");
      const bar = element("div", `trend-bar${bin.minutes === 0 ? " empty" : bin.minutes === peak ? " peak" : ""}`);
      bar.style.height = `${peak ? bin.minutes / peak * 100 : 0}%`;
      bar.title = `${bin.label}：${numberLabel(bin.minutes)} 分钟`;
      column.append(bar);
      return column;
    }));
    $("statistics-trend").setAttribute("aria-label", trend.map(bin => `${bin.label} ${numberLabel(bin.minutes)} 分钟`).join("；"));
    const labels = trend.length <= UI.weekDays ? trend : Array.from({ length: UI.chartAxisLabels }, (_, index) => trend[Math.round(index * (trend.length - 1) / (UI.chartAxisLabels - 1))]);
    $("statistics-axis").replaceChildren(...labels.map(bin => element("span", "", bin.label)));
    $("statistics-trend-note").textContent = total ? `峰值 ${numberLabel(peak)} 分钟 · 按专注结束时刻汇总` : "这个周期还没有已完成记录。开始一段专注，让这里慢慢充实。";
  }

  function renderHourDistribution(records) {
    const bins = focusHourDistribution(records);
    const peak = Math.max(0, ...bins.map(bin => bin.minutes));
    $("statistics-hours").replaceChildren(...bins.map(bin => {
      const column = element("div", "trend-column");
      const bar = element("div", `trend-bar hour-bar${bin.minutes === 0 ? " empty" : bin.minutes === peak ? " peak" : ""}`);
      bar.style.height = `${peak ? bin.minutes / peak * 100 : 0}%`;
      bar.title = `${bin.label}：${numberLabel(bin.minutes)} 分钟`;
      bar.dataset.minutes = bin.minutes;
      column.append(bar);
      return column;
    }));
    $("statistics-hours").setAttribute("aria-label", `当前周期专注时段分布：${bins.map(bin => `${bin.label} ${numberLabel(bin.minutes)} 分钟`).join("；")}`);
    $("statistics-hours-note").textContent = records.length ? "汇总所选周期完成记录的实际专注区间，按小时拆分，暂停时间不计入。" : "当前周期暂无已完成的专注时段。";
  }

  function renderFocusCategories(records, total) {
    const categories = focusBreakdown(records, "category");
    let angle = 0;
    const segments = categories.map((category, index) => {
      const start = angle;
      angle += category.minutes / total * UI.fullCircle;
      return `${CATEGORY_COLORS[index % CATEGORY_COLORS.length]} ${start}deg ${angle}deg`;
    });
    $("statistics-donut").style.background = segments.length ? `conic-gradient(${segments.join(",")})` : "#f2f2f2";
    $("statistics-donut").setAttribute("aria-label", categories.length ? categories.map(item => `${item.name} ${item.minutes} 分钟`).join("；") : "暂无分类统计");
    $("statistics-categories").replaceChildren(...categories.map((category, index) => {
      const item = element("div", "category-item");
      const heading = element("div");
      const dot = element("i");
      dot.style.background = CATEGORY_COLORS[index % CATEGORY_COLORS.length];
      heading.append(dot, element("span", "", category.name));
      item.append(heading, element("small", "", `${numberLabel(category.minutes)} 分钟 · ${Math.round(category.minutes / total * 100)}%`));
      return item;
    }));
    if (!categories.length) $("statistics-categories").append(element("p", "data-note", "完成专注后，查看你把时间留给了什么。"));
  }

  function renderFocusCalendar() {
    const sourceRecords = state.includeSamples ? state.records : state.records.filter(record => record.source !== "sample");
    const days = monthActivity(sourceRecords, state.statsAnchor);
    const first = new Date(state.statsAnchor.getFullYear(), state.statsAnchor.getMonth(), 1);
    const offset = (first.getDay() + UI.weekDays - 1) % UI.weekDays;
    const peak = Math.max(0, ...days.map(day => day.minutes));
    const cells = Array.from({ length: offset }, () => element("span"));
    $("statistics-calendar-title").textContent = `${first.getFullYear()} 年 ${first.getMonth() + 1} 月`;
    days.forEach((day, index) => {
      const cell = element("button", "calendar-day", index + 1);
      cell.dataset.level = day.minutes ? Math.max(1, Math.ceil(day.minutes / peak * UI.heatLevels)) : 0;
      cell.setAttribute("aria-label", `${day.date}，专注 ${numberLabel(day.minutes)} 分钟，${day.count} 次专注`);
      cell.setAttribute("aria-pressed", String(day.date === localDateKey(state.statsAnchor)));
      cell.title = `${day.date} · ${day.minutes} 分钟`;
      cell.addEventListener("click", () => {
        state.statsAnchor = new Date(first.getFullYear(), first.getMonth(), index + 1);
        changeStatsPeriod("day");
        $("phone-content").scrollTop = 0;
        $("statistics-heading").focus({ preventScroll: true });
      });
      cells.push(cell);
    });
    $("statistics-calendar").replaceChildren(...cells);
  }

  function renderFocusRanking(records) {
    const ranking = focusBreakdown(records.map(record => ({ ...record, taskTitle: record.focusItemTitle || record.taskTitle })), "taskTitle");
    const maximum = ranking[0]?.minutes || 1;
    $("statistics-ranking").replaceChildren(...ranking.map((task, index) => {
      const row = element("li");
      const detail = element("div", "rank-detail");
      const heading = element("div", "rank-heading");
      heading.append(element("span", "", task.name), element("small", "", `${numberLabel(task.minutes)} 分钟 / ${task.count} 次`));
      const track = element("div", "rank-track");
      const fill = element("div");
      fill.style.width = `${task.minutes / maximum * 100}%`;
      track.append(fill);
      detail.append(heading, track);
      row.append(element("span", "rank-number", String(index + 1).padStart(2, "0")), detail);
      return row;
    }));
    if (!ranking.length) $("statistics-ranking").append(element("li", "data-note", "暂无任务投入记录。"));
  }

  function renderFocusRecords(records) {
    $("statistics-record-count").textContent = `${records.length} 条记录`;
    $("statistics-records").replaceChildren(...records.slice(0, state.recordLimit).map(record => {
      const row = element("li");
      const heading = element("div", "record-heading");
      const ended = new Date(record.endedAt);
      const started = new Date(record.startedAt);
      const timeOptions = { hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false };
      const startDate = localDateKey(started) === localDateKey(ended) ? "" : `${localDateKey(started)} `;
      const seconds = record.durationSeconds ?? Math.round(record.durationMinutes * LIMITS.secondsPerMinute);
      heading.append(element("strong", "", record.focusItemTitle || record.taskTitle), element("span", "", formatTime(seconds)));
      row.append(heading, element("div", "record-meta", `${localDateKey(ended)} · ${startDate}${started.toLocaleTimeString("zh-CN", timeOptions)} — ${ended.toLocaleTimeString("zh-CN", timeOptions)}\n${TIMING_NAMES[record.timerMode || "countdown"]} · ${record.category} · ${record.source === "sample" ? "示例记录" : "本次计时"}${record.taskId ? `\n关联待办：${record.taskTitle}` : ""}`));
      if (record.progress) row.append(element("p", "project-progress-note", `${record.progress.percent == null ? "" : `${record.progress.percent}% · `}${record.progress.note}`));
      const progress = element("button", "text-button record-progress-button", record.progress ? "编辑进度" : "补写进度");
      progress.dataset.recordId = record.id;
      progress.addEventListener("click", () => progressEditor(record.id));
      row.append(progress);
      return row;
    }));
    if (!records.length) $("statistics-records").append(element("li", "data-note", "暂无记录；可切换日期或选择包含示例数据。"));
    $("statistics-more").hidden = records.length <= state.recordLimit;
    $("statistics-more").textContent = `查看更多记录（已显示 ${Math.min(records.length, state.recordLimit)} / ${records.length}）`;
  }

  function exportStatistics() {
    tickTimer();
    const records = selectedRecords();
    if (!records.length) return toast("当前范围没有可导出的专注记录。");
    const range = periodRange(state.statsPeriod, state.statsAnchor);
    const summary = focusSummary(records);
    const sampleCount = records.filter(record => record.source === "sample").length;
    const form = element("form");
    form.append(element("p", "export-summary", `${rangeLabel()}\n${records.length} 条记录，共 ${numberLabel(summary.minutes)} 分钟。`));
    form.append(element("div", "modal-notice", `本次导出含 ${sampleCount} 条示例、${records.length - sampleCount} 条本次计时记录。文件会标注来源；如只需自己的计时，请先将数据范围切换为「仅本次计时」。`));
    field(form, "文件格式", "format", "CSV（Excel 可打开）", { choices: ["CSV（Excel 可打开）", "JSON（明细与汇总）"] });
    form.append(element("p", "data-note", "点击下载时重新汇总最新完成记录，不受明细分页影响。CSV 使用 UTF-8 编码及本地时区；JSON 包含统计口径与范围，不是完整 APP 备份。"));
    const button = element("button", "primary-button", "下载统计文件");
    button.type = "submit";
    form.append(button);
    form.addEventListener("submit", event => {
      event.preventDefault();
      tickTimer();
      const records = selectedRecords();
      const summary = focusSummary(records);
      const sampleCount = records.filter(record => record.source === "sample").length;
      const isCsv = new FormData(form).get("format").startsWith("CSV");
      const payload = isCsv ? exportFocusCsv(records) : JSON.stringify({ schemaVersion: 1, kind: "focus-statistics",
        exportedAt: new Date().toISOString(), period: state.statsPeriod, range: { start: range.start.toISOString(), endExclusive: range.end.toISOString() },
        timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone, grouping: "local-completion-date", activeDayAverage: true,
        includesSamples: sampleCount > 0, summary, allTimeSummary: allTimeFocusSummary(sourceRecords()),
        allTimeScope: "all-records-matching-source-filter-through-today", hourDistribution: focusHourDistribution(records),
        hourDistributionBasis: "completed-records-active-segments-local-hour", records,
        progressHistory: state.progressEntries.filter(entry => records.some(record => record.id === entry.recordId)) }, null, UI.jsonIndent);
      const blob = new Blob([payload], { type: isCsv ? "text/csv;charset=utf-8" : "application/json;charset=utf-8" });
      const url = URL.createObjectURL(blob);
      const link = element("a");
      link.href = url;
      link.download = `专注统计_${localDateKey(range.start)}_${state.statsPeriod}_${sampleCount ? "含示例" : "本次计时"}.${isCsv ? "csv" : "json"}`;
      document.body.append(link);
      link.click();
      link.remove();
      setTimeout(() => URL.revokeObjectURL(url), UI.exportReleaseMs);
      closeModal();
      toast("已发起统计文件下载，请在浏览器下载列表查看。");
    });
    showModal("导出专注统计", form);
  }

  function renderUsage() {
    const data = USAGE[state.period];
    const isToday = state.period === "today";
    $("usage-total-label").textContent = isToday ? "今日屏幕使用" : "本周屏幕使用";
    $("usage-total").replaceChildren(element("strong", "", Math.floor(data.total / LIMITS.secondsPerMinute)), document.createTextNode(" 小时 "), element("strong", "", data.total % LIMITS.secondsPerMinute), document.createTextNode(" 分钟"));
    $("usage-comparison").textContent = isToday ? "比昨日少 36 分钟，继续保持" : "日均约 3 小时 26 分钟 · 示例统计";
    document.querySelectorAll("[data-period]").forEach(button => {
      const selected = button.dataset.period === state.period;
      button.classList.toggle("selected", selected);
      button.setAttribute("aria-pressed", String(selected));
    });
    const maximum = Math.max(...data.bars);
    const highlight = isToday ? data.bars.indexOf(maximum) : data.bars.length - 1;
    $("usage-chart").replaceChildren(...data.bars.map((value, index) => {
      const column = element("div", "bar-column");
      const bar = element("div", `chart-bar${index === highlight ? " highlight" : ""}`);
      bar.style.height = `${value / maximum * 100}%`;
      bar.title = `${value} 分钟`;
      column.append(bar);
      return column;
    }));
    $("usage-chart").setAttribute("aria-label", `示例${isToday ? "今日各时段" : "本周每日"}使用分钟数：${data.bars.join("、")}`);
    $("chart-labels").replaceChildren(...data.labels.map(label => element("span", "", label)));
    $("edit-goal").replaceChildren(document.createTextNode(durationLabel(state.goal)), icon("arrow"));
    $("goal-progress").style.width = `${Math.min(100, USAGE.today.total / state.goal * 100)}%`;
    const remaining = state.goal - USAGE.today.total;
    $("goal-summary").textContent = remaining > 0 ? `今天还剩 ${durationLabel(remaining)}，留一点时间给生活。` : remaining === 0 ? "今天已达到目标时长，休息一下吧。" : `今天已超出目标 ${durationLabel(-remaining)}，给眼睛一点休息。`;
    $("usage-apps").replaceChildren(...data.apps.map(app => {
      const row = element("div", "app-row");
      const avatar = element("div", "app-avatar", app.glyph);
      avatar.style.background = app.color;
      avatar.style.color = app.ink;
      const details = element("div", "app-details");
      const heading = element("div");
      heading.append(element("span", "", app.name), element("small", "", durationLabel(app.minutes)));
      const track = element("div", "app-track");
      const fill = element("div");
      fill.style.width = `${app.minutes / data.total * 100}%`;
      track.append(fill);
      details.append(heading, track);
      row.append(avatar, details);
      return row;
    }));
  }

  function goalSettings() {
    const form = element("form");
    form.append(element("div", "modal-notice", "使用固定的示例数据演示目标进度，不会限制任何应用。"));
    field(form, "每日目标（分钟）", "goal", state.goal, { type: "number", min: LIMITS.minGoalMinutes, max: LIMITS.maxGoalMinutes });
    const save = element("button", "primary-button", "保存目标");
    save.type = "submit";
    form.append(save);
    form.addEventListener("submit", event => {
      event.preventDefault();
      const goal = new FormData(form).get("goal");
      if (!integerInRange(goal, LIMITS.minGoalMinutes, LIMITS.maxGoalMinutes)) return toast("请输入 30–1440 的整数分钟。");
      state.goal = Number(goal);
      closeModal();
      renderUsage();
      toast("目标已更新，仅用于本次演示。");
    });
    showModal("给屏幕时间一点边界", form);
  }

  $("close-modal").addEventListener("click", closeModal);
  $("modal").addEventListener("close", () => {
    if (!$("modal").open) finishModalClose();
  });
  $("modal").addEventListener("click", event => {
    if (event.target !== $("modal")) return;
    const box = $("modal").getBoundingClientRect();
    if (event.clientX < box.left || event.clientX > box.right || event.clientY < box.top || event.clientY > box.bottom) closeModal();
  });
  document.querySelectorAll("[data-page]").forEach(button => button.addEventListener("click", () => navigate(button.dataset.page)));
  window.addEventListener("hashchange", () => navigate(location.hash.slice(1), false));
  document.querySelectorAll("[data-mode]").forEach(button => button.addEventListener("click", () => {
    if (button.dataset.mode === timer.mode) return;
    guardedTimerChange(() => {
      timer.selectMode(button.dataset.mode);
      state.activeFocus = null;
      renderTimer();
    }, "切换专注或休息会重置当前计时。");
  }));
  document.querySelectorAll("[data-timing-mode]").forEach(button => button.addEventListener("click", () => {
    if (button.dataset.timingMode === timer.timerMode && timer.mode === "focus") return;
    guardedTimerChange(() => {
      const item = currentFocusItem();
      item.timerMode = button.dataset.timingMode;
      applyFocusItem(item);
    }, "更换计时方向会重置当前计时，并保存为此专注项的默认模式。");
  }));
  document.querySelectorAll("[data-filter]").forEach(button => button.addEventListener("click", () => { state.filter = button.dataset.filter; renderTasks(); }));
  document.querySelectorAll("[data-period]").forEach(button => button.addEventListener("click", () => { state.period = button.dataset.period; renderUsage(); }));
  $("timer-toggle").addEventListener("click", () => {
    const wasRunning = timer.running;
    tickTimer();
    if (wasRunning && timer.completed) return;
    if (timer.running) pauseTimer();
    else { if (timer.completed) resetTimer(); startTimer(); }
    renderTimer();
  });
  $("timer-finish").addEventListener("click", finishCountup);
  $("timer-reset").addEventListener("click", () => guardedTimerChange(() => { resetTimer(); renderTimer(); }, "重置会放弃当前进度。"));
  $("add-focus-item").addEventListener("click", () => focusItemEditor());
  $("select-focus-item").addEventListener("click", () => navigate("focus"));
  $("timer-back").addEventListener("click", () => navigate("focus"));
  $("resume-session").addEventListener("click", () => navigate("timer"));
  $("timer-statistics").addEventListener("click", () => navigate("statistics"));
  $("open-statistics").addEventListener("click", () => navigate("statistics"));
  $("statistics-detail").addEventListener("click", () => navigate("statistics"));
  $("statistics-back").addEventListener("click", () => navigate("focus"));
  $("export-statistics").addEventListener("click", exportStatistics);
  $("alltime-help").addEventListener("click", showAllTimeHelp);
  $("statistics-previous").addEventListener("click", () => shiftStatsPeriod(-1));
  $("statistics-next").addEventListener("click", () => shiftStatsPeriod(1));
  $("statistics-today").addEventListener("click", () => { state.statsAnchor = new Date(); changeStatsPeriod("day"); });
  $("statistics-source").addEventListener("change", event => {
    state.includeSamples = event.target.value === "all";
    state.recordLimit = UI.recordPageSize;
    renderStatistics();
  });
  $("statistics-anchor").addEventListener("change", event => {
    const [year, month, day] = event.target.value.split("-").map(Number);
    if (!event.target.validity.valid || !year || year < UI.minYear || year > UI.maxYear) {
      event.target.value = localDateKey(state.statsAnchor);
      return toast("请选择 1900–2100 年内的有效日期。");
    }
    state.statsAnchor = new Date(year, month - 1, day);
    state.recordLimit = UI.recordPageSize;
    renderStatistics();
  });
  document.querySelectorAll("[data-stat-period]").forEach(button => button.addEventListener("click", () => changeStatsPeriod(button.dataset.statPeriod)));
  $("statistics-more").addEventListener("click", () => {
    state.recordLimit += UI.recordPageSize;
    renderFocusRecords(selectedRecords());
  });
  $("timer-settings").addEventListener("click", () => focusItemEditor(state.selectedFocusItem));
  $("preferences-button").addEventListener("click", timerSettings);
  $("link-task").addEventListener("click", chooseTask);
  $("add-task").addEventListener("click", () => taskEditor());
  $("add-task-inline").addEventListener("click", () => taskEditor());
  $("edit-goal").addEventListener("click", goalSettings);
  $("reminder-switch").addEventListener("click", () => {
    state.reminders = !state.reminders;
    $("reminder-switch").setAttribute("aria-checked", String(state.reminders));
    $("reminder-caption").textContent = state.reminders ? "演示已开启 · 不发送通知，不后台检测" : "仅演示开关，不发送系统通知";
    toast(state.reminders ? "演示已开启。正式版需通知授权，提醒及时性受系统省电策略影响。" : "已关闭提醒演示。");
  });
  const permissions = () => information("权限由你掌控", [
    "使用情况访问权限：正式版需在 Android 系统设置中手动授权，才能统计各应用的使用时长。",
    "通知权限：正式版用于计时结束和超时提醒；是否及时送达受系统后台与省电策略影响。",
    "本原型不会申请上述权限，也不需要无障碍、root 或读取其他应用内容。",
  ]);
  $("usage-permission").addEventListener("click", permissions);
  $("permissions-button").addEventListener("click", permissions);
  $("backup-button").addEventListener("click", () => information("备份与恢复 · 流程预览", [
    "导出：选择保存位置，将待办、专注记录和个人偏好写入 JSON 文件。",
    "恢复：选择备份文件，校验格式和版本，预览数据数量，再确认恢复。正式版会在覆盖前提示风险。",
    "这里仅展示计划中的流程，不读写文件。本原型的改动只保留在当前页面，刷新后恢复示例。",
  ]));
  $("automation-button").addEventListener("click", () => information("自动化，留待下一步", [
    "这里是未来的脚本扩展入口，目前没有接入任何脚本引擎或自动化工具。",
    "后续会根据你的具体场景，确定支持的手机端工具、参数和权限。不会默认获得控制其他应用的能力。",
    "计划提供动作列表、执行前确认和结果记录。此原型不会下载、执行脚本或发出手机控制指令。",
  ]));
  document.addEventListener("visibilitychange", () => {
    if (!document.hidden) { tickTimer(); queueMicrotask(pumpProgressPrompts); }
  });
  window.addEventListener("pagehide", () => {
    clearTimeout(toastTimeout);
    $("toast").classList.remove("visible");
    $("toast").textContent = "";
  });
  renderTasks();
  renderUsage();
  applyFocusItem(currentFocusItem());
  updateLinkedTask();
  navigate(location.hash.slice(1), false);
  setInterval(tickTimer, UI.tickMs);
})();
