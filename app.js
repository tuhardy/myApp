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
  const TODO_UI = Object.freeze({ swipeWidth: 88, swipeSlop: 10, swipeRatio: 1.25, swipeThreshold: 36,
    finishHoldMs: 680, finishCollapseMs: 240, historyPage: 20, newDraft: "new" });
  const DURATION_PRESETS = [15, 25, 45, 60];
  const PAGES = ["focus", "timer", "statistics", "tasks", "diary", "usage", "profile"];
  const DIARY_UI = Object.freeze({ visiblePhotos: 3, photoTones: 3, nameLength: 16, pickCounts: [1, 3] });
  const DIARY_VIEWS = ["home", "trash", "drafts", "detail", "editor"];
  const MIC = Object.freeze({ prompt: "prompt", granted: "granted", denied: "denied" });
  const MODE_NAMES = { focus: "专注", short: "短休息", long: "长休息" };
  const TIMING_NAMES = { countdown: "倒计时", countup: "正计时" };
  const timer = new Timer();
  const state = {
    tasks: initialTasks(), filter: "pending", period: "today", goal: UI.initialGoalMinutes,
    taskDrafts: new Map(), taskEditorId: null, taskEditorReturn: null, taskExpanded: new Set(), taskSearch: "",
    historyMonth: localDateKey(new Date()).slice(0, 7), historyUndated: false, historyViews: new Map(),
    finishingTasks: new Map(), taskUndo: [],
    records: initialFocusRecords(), activeFocus: null, overviewDay: "",
    statsPeriod: "day", statsAnchor: new Date(), includeSamples: true, recordLimit: UI.recordPageSize,
    focusItems: initialFocusItems(), selectedFocusItem: "focus-reading", nextFocusItemId: 1,
    reminders: false, nextTaskId: 1, nextRecordId: 1, nextStepId: 1,
    openProjects: new Set(), progressEntries: [], progressDrafts: new Map(), nextProgressId: 1,
    pendingProgressPrompts: [], progressDialogRecord: null, progressReturnHome: false,
    diaryEntries: FocusModel.initialDiaryEntries(), diaryMonth: localDateKey(new Date()).slice(0, 7), diarySearch: "",
    diaryView: "home", diaryHomeScroll: 0, diaryDetailId: null, diaryEditor: null, diaryDrafts: new Map(),
    diaryRecording: null, micPermission: MIC.prompt, nextDiaryId: 1, nextDiaryAttachmentId: 1, nextDiaryDraftId: 1,
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

  function showModal(title, content, variant = "") {
    $("modal").classList.toggle("todo-guide-sheet", variant === "todo-guide-sheet");
    state.progressDialogRecord = null;
    state.progressReturnHome = false;
    $("modal-title").textContent = title;
    $("modal-content").replaceChildren(content);
    if (!$("modal").open) $("modal").showModal();
    if (variant === "todo-guide-sheet") {
      $("close-modal").focus({ preventScroll: true });
      $("modal").scrollTop = 0;
    } else $("modal-content").querySelector("input, textarea, select, button")?.focus();
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
    rememberTaskHistoryPosition();
    if (safePage !== "diary" && !$("page-diary").hidden) suspendDiaryEditor("离开日记时");
    PAGES.forEach(name => { $(`page-${name}`).hidden = name !== safePage; });
    syncDiaryChrome();
    if (safePage === "statistics") renderStatistics();
    if (safePage === "focus") renderProjectList();
    if (safePage === "tasks") renderTasks();
    if (safePage === "diary") renderDiary();
    closeSwipes();
    showTaskUndo();
    document.querySelectorAll("[data-page]").forEach(button => {
      const active = button.dataset.page === (["statistics", "timer"].includes(safePage) ? "focus" : safePage);
      button.classList.toggle("active", active);
      if (active) button.setAttribute("aria-current", "page");
      else button.removeAttribute("aria-current");
    });
    $("phone-content").scrollTop = safePage === "tasks" && state.filter === "done" ? taskHistoryView().scrollTop : 0;
    if (updateHash && location.hash !== `#${safePage}`) location.hash = safePage;
  }

  function currentFocusItem() {
    return state.focusItems.find(item => item.id === state.selectedFocusItem);
  }

  function numberLabel(value) { return Number(value.toFixed(2)); }

  function itemDescription(item) {
    return `${TIMING_NAMES[item.timerMode]} · ${item.timerMode === "countup" ? "目标 " : ""}${item.durationMinutes} 分钟`;
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
      const item = currentFocusItem();
      if (!state.activeFocus) {
        state.activeFocus = { focusItemId: item.id, focusItemTitle: item.title, category: item.category, timerMode: item.timerMode,
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
    const { focusItemId, focusItemTitle, category, timerMode, targetMinutes, startedAt, segments } = session;
    const record = { id: `session-${state.nextRecordId++}`, focusItemId, focusItemTitle, category, timerMode,
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
      if (item.id !== state.selectedFocusItem) applyFocusItem(item);
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
    if (document.hidden || $("modal").open || $("todo-sheet").open) return;
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

  function taskButton(label, className, action) {
    const button = element("button", className, label);
    button.type = "button";
    button.addEventListener("click", action);
    return button;
  }

  /** 小步编辑：加一步、改名、删一步。用 DOM 节点直接承载，不拼 HTML 字符串。 */
  function buildStepEditor(draft, locked, onChange) {
    const wrapper = element("details", "step-editor");
    wrapper.open = draft.stepsOpen ?? draft.steps.length > 0;
    const summary = element("summary", "step-editor-title", "拆成小步 · 可选");
    const list = element("div", "step-editor-list");
    const add = taskButton("＋ 加一步", "add-step", () => {
      if (draft.steps.length >= LIMITS.maxTaskSteps) return;
      draft.steps.push({ id: `step-new-${state.nextStepId++}`, title: "", done: false });
      draw();
      list.lastElementChild?.querySelector("input")?.focus();
      onChange();
    });
    const draw = () => {
      list.replaceChildren(...draft.steps.map((step, index) => {
        const row = element("div", "step-editor-row");
        const input = element("input");
        input.type = "text";
        input.value = step.title;
        input.maxLength = LIMITS.maxTaskStepTitle;
        input.enterKeyHint = "next";
        input.setAttribute("aria-label", `第 ${index + 1} 步`);
        input.addEventListener("input", () => { step.title = input.value; onChange(); });
        input.addEventListener("keydown", event => {
          if (event.key !== "Enter" || event.isComposing) return;
          event.preventDefault();
          const next = row.nextElementSibling?.querySelector("input");
          if (next) next.focus();
          else if (!add.disabled && !locked) add.click();
        });
        row.append(input);
        if (!locked) {
          const remove = taskButton("×", "step-remove", () => {
            draft.steps.splice(index, 1);
            draw();
            (list.children[Math.min(index, list.children.length - 1)]?.querySelector("input") || add).focus();
            onChange();
          });
          remove.setAttribute("aria-label", `删掉第 ${index + 1} 步`);
          row.append(remove);
        }
        return row;
      }));
      add.disabled = draft.steps.length >= LIMITS.maxTaskSteps;
      add.textContent = add.disabled ? `最多 ${LIMITS.maxTaskSteps} 步` : "＋ 加一步";
      summary.textContent = `拆成小步${draft.steps.length ? ` · ${draft.steps.length} 步` : " · 可选"}`;
    };
    wrapper.addEventListener("toggle", () => { draft.stepsOpen = wrapper.open; });
    draw();
    wrapper.append(summary, list);
    if (!locked) wrapper.append(add);
    else wrapper.append(element("p", "data-note", "已完成的步骤仅可改名。需要重做时，请在详情中选择「重新打开」。"));
    return wrapper;
  }

  function closeTaskSheet() {
    $("todo-sheet").close();
    queueMicrotask(pumpProgressPrompts);
  }

  function taskEditor(id) {
    const task = state.tasks.find(item => item.id === id);
    if (id && !task) return;
    if (!task && state.tasks.length >= LIMITS.maxTasks) return toast(`演示最多支持 ${LIMITS.maxTasks} 个待办。`);
    closeSwipes();
    if ($("modal").open) closeModal();
    state.taskEditorReturn = document.activeElement;
    state.taskEditorId = id || TODO_UI.newDraft;
    // M：小步是可选的。草稿按待办保留在本页内存中，保存后清除，刷新重置。
    if (!state.taskDrafts.has(state.taskEditorId)) state.taskDrafts.set(state.taskEditorId, {
      title: task?.title || "", category: task?.category || CATEGORIES[0], important: task?.important || false,
      steps: (task?.steps || []).map(step => ({ ...step })), stepsOpen: !!task?.steps?.length,
    });
    const draft = state.taskDrafts.get(state.taskEditorId);
    if (task?.done) draft.steps = (task.steps || []).map(step => ({ ...step, title: draft.steps.find(value => value.id === step.id)?.title ?? step.title }));
    else if (task) draft.steps = draft.steps.map(step => ({ ...step, done: task.steps?.find(value => value.id === step.id)?.done ?? step.done }));
    const body = $("todo-sheet-body");
    body.replaceChildren();
    $("todo-sheet-title").textContent = task ? "编辑待办" : "记下一件事";
    $("save-todo-sheet").textContent = task ? "保存修改" : "添加待办";
    const validation = element("div", "modal-notice");
    validation.id = "task-validation-error";
    validation.setAttribute("role", "alert");
    validation.hidden = true;
    const onChange = () => { validation.hidden = true; };
    const titleInput = field(body, "想做什么？", "title", draft.title, { maxLength: LIMITS.maxTaskTitle });
    titleInput.parentElement.classList.add("todo-title-field");
    titleInput.placeholder = "从一件具体的小事开始";
    titleInput.setAttribute("aria-describedby", validation.id);
    titleInput.addEventListener("input", () => { draft.title = titleInput.value; onChange(); });
    const categories = element("div", "todo-category-choices");
    categories.setAttribute("role", "group");
    categories.setAttribute("aria-label", "待办分类");
    CATEGORIES.forEach(category => {
      const choice = taskButton(category, `category-choice${category === draft.category ? " selected" : ""}`, () => {
        draft.category = category;
        [...categories.children].forEach(button => {
          const selected = button.textContent === category;
          button.classList.toggle("selected", selected);
          button.setAttribute("aria-pressed", String(selected));
        });
      });
      choice.setAttribute("aria-pressed", String(category === draft.category));
      categories.append(choice);
    });
    const important = element("label", "todo-important");
    const checkbox = element("input");
    checkbox.type = "checkbox";
    checkbox.name = "important";
    checkbox.checked = draft.important;
    checkbox.addEventListener("change", () => { draft.important = checkbox.checked; });
    important.append(checkbox, document.createTextNode("标为重要，放在最前面"));
    body.append(categories, important, buildStepEditor(draft, !!task?.done, onChange), validation);
    if (task) {
      const actions = element("div", "todo-editor-actions");
      actions.append(taskButton("删除待办", "text-button danger-button", () => { closeTaskSheet(); deleteTask(task.id); }));
      body.append(actions);
    }
    $("todo-form").onsubmit = event => {
      event.preventDefault();
      try {
        const current = state.tasks.find(item => item.id === id);
        if (id && !current) throw new Error("这件待办已被删除，请关闭面板。");
        const validated = validateTask(draft);
        if (current) {
          const next = { ...current, ...validated };
          // 小步被删光时不该把已完成的事回退成未完成。
          if (!current.done && next.steps.length && next.steps.every(step => step.done)) {
            Object.assign(next, FocusModel.completeTask(next));
          }
          state.tasks = state.tasks.map(item => item.id === id ? next : item);
          state.filter = next.archived ? "archived" : next.done ? "done" : "pending";
        } else {
          if (state.tasks.length >= LIMITS.maxTasks) throw new Error(`演示最多支持 ${LIMITS.maxTasks} 个待办。`);
          state.tasks.push({ ...validated, id: `new-${state.nextTaskId++}`, done: false, completedAt: null,
            createdAt: localDateKey(new Date()), archived: false });
          state.filter = "pending";
        }
        state.taskDrafts.delete(state.taskEditorId);
        state.taskSearch = "";
        $("task-search").value = "";
        closeTaskSheet();
        renderTasks();
        toast(task ? "修改已保存。" : "记下了，从第一步开始。" );
      } catch (error) {
        validation.textContent = error.message;
        validation.hidden = false;
        validation.scrollIntoView({ block: "nearest" });
      }
    };
    syncTaskViewport();
    $("todo-sheet").showModal();
    titleInput.focus();
  }

  function syncTaskViewport() {
    const viewport = window.visualViewport;
    $("todo-sheet").style.setProperty("--todo-viewport-height", `${viewport?.height || window.innerHeight}px`);
    $("todo-sheet").style.setProperty("--todo-viewport-top", `${viewport?.offsetTop || 0}px`);
  }

  function taskHistoryView() {
    const key = state.taskSearch ? "search" : state.historyUndated ? "undated" : state.historyMonth;
    if (!state.historyViews.has(key)) state.historyViews.set(key, { limit: TODO_UI.historyPage, scrollTop: 0 });
    return state.historyViews.get(key);
  }

  function rememberTaskHistoryPosition() {
    if (state.filter === "done" && !$("page-tasks").hidden) taskHistoryView().scrollTop = $("phone-content").scrollTop;
  }

  function changeTaskHistory(action) {
    rememberTaskHistoryPosition();
    action();
    closeSwipes();
    renderTasks();
    $("phone-content").scrollTop = state.filter === "done" ? taskHistoryView().scrollTop : 0;
  }

  function taskHistorySelection() {
    return FocusModel.selectCompletedTasks(state.tasks, { month: state.historyMonth, query: state.taskSearch,
      undated: state.historyUndated, limit: taskHistoryView().limit });
  }

  function taskMonthLabel(month) {
    const [year, number] = month.split("-").map(Number);
    return `${year} 年 ${number} 月`;
  }

  function setTaskHistoryMonth(month) {
    const current = localDateKey(new Date()).slice(0, 7);
    if (month > current || month < `${UI.minYear}-01`) return;
    changeTaskHistory(() => {
      state.historyMonth = month;
      state.historyUndated = false;
      state.taskSearch = "";
      $("task-search").value = "";
    });
  }

  function shiftTaskHistoryMonth(offset) {
    const [year, month] = state.historyMonth.split("-").map(Number);
    setTaskHistoryMonth(localDateKey(new Date(year, month - 1 + offset, 1)).slice(0, 7));
  }

  /** 年月面板：待办历史与日记共用，不允许选择未来月份。 */
  function monthPicker({ selected, months, unit, describe, onPick }) {
    const current = new Date();
    const currentMonth = localDateKey(current).slice(0, 7);
    const form = element("div", "task-month-form");
    const years = Array.from({ length: current.getFullYear() - UI.minYear + 1 }, (_, index) => String(current.getFullYear() - index));
    const year = field(form, "选择年份", "historyYear", selected.slice(0, 4), { choices: years });
    const counts = new Map(months.map(month => [month.key, month.count]));
    const grid = element("div", "month-grid");
    const draw = () => {
      grid.replaceChildren(...Array.from({ length: 12 }, (_, index) => {
        const key = `${year.value}-${String(index + 1).padStart(2, "0")}`;
        const button = taskButton("", "month-choice", () => { closeModal(); onPick(key); });
        button.dataset.month = key;
        button.disabled = key > currentMonth;
        button.setAttribute("aria-pressed", String(key === selected));
        button.setAttribute("aria-label", `${year.value}年${index + 1}月${button.disabled ? "，尚未到来" : `，${describe(counts.get(key) || 0)}`}`);
        button.append(element("span", "month-name", `${index + 1} 月`), element("small", "month-count", button.disabled ? "—" : counts.has(key) ? `${counts.get(key)} ${unit}` : "暂无记录"));
        return button;
      }));
    };
    year.addEventListener("change", draw);
    draw();
    const actions = element("div", "month-picker-actions");
    actions.append(taskButton("回到本月", "secondary-button", () => { closeModal(); onPick(currentMonth); }));
    form.append(grid, actions);
    showModal("翻到哪一月？", form);
  }

  function taskMonthPicker() {
    monthPicker({ selected: state.historyMonth, months: taskHistorySelection().months, unit: "件",
      describe: count => `完成 ${count} 件`, onPick: setTaskHistoryMonth });
  }

  function renderTaskHistoryControls(result) {
    const searching = !!state.taskSearch;
    const currentMonth = localDateKey(new Date()).slice(0, 7);
    $("task-month-nav").hidden = searching || state.historyUndated;
    $("task-month-label").textContent = taskMonthLabel(state.historyMonth);
    $("task-month-prev").disabled = state.historyMonth <= `${UI.minYear}-01`;
    $("task-month-next").disabled = state.historyMonth >= currentMonth;
    $("task-month-current").hidden = searching || state.historyUndated || state.historyMonth === currentMonth;
    const [year, month] = state.historyMonth.split("-").map(Number);
    $("task-history-summary").textContent = searching ? `全部时间 · 找到 ${result.total} 件`
      : state.historyUndated ? `完成时间未记录 · ${result.total} 件`
      : `${year}年${month}月 · 完成 ${result.total} 件`;
    $("task-undated").hidden = searching || state.historyUndated || !result.undatedCount;
    $("task-undated").textContent = `时间未记录 · ${result.undatedCount} 件`;
    $("task-known-history").hidden = searching || !state.historyUndated;
    $("task-history-footer").hidden = !result.total;
    $("task-history-more").hidden = result.shown >= result.total;
    $("task-history-count").textContent = `已显示 ${result.shown} / ${result.total} 件`;
  }

  function renderTasks() {
    const focusKey = document.activeElement?.dataset.taskFocus;
    const summary = taskSummary(activeTasks());
    $("pending-count").textContent = summary.total - summary.done;
    $("done-count").textContent = summary.done;
    $("archived-count").textContent = state.tasks.filter(task => task.archived).length;
    $("task-list-heading").textContent = state.filter === "done" ? "走过的路，都算数" : state.filter === "archived" ? "暂时放下，也没关系" : "从眼前的一步开始";
    $("task-search-wrap").hidden = state.filter !== "done";
    $("task-history-controls").hidden = state.filter !== "done";
    $("task-history-footer").hidden = state.filter !== "done";
    $("task-list-intro").hidden = state.filter === "done";
    $("task-list").classList.toggle("history-list", state.filter === "done");
    document.querySelectorAll("[data-filter]").forEach(button => {
      const selected = state.filter === button.dataset.filter;
      button.classList.toggle("selected", selected);
      button.setAttribute("aria-pressed", String(selected));
    });
    const today = localDateKey(new Date());
    const history = state.filter === "done" ? taskHistorySelection() : null;
    const visible = state.tasks.filter(task => state.filter === "archived" ? task.archived : !task.archived && (!task.done || state.finishingTasks.has(task.id)));
    const groups = history ? history.groups : FocusModel.groupTasks(visible);
    if (history) renderTaskHistoryControls(history);
    $("task-list").replaceChildren(...groups.map(group => buildTaskGroup(group, today)));
    if (!groups.length) {
      const empty = element("div", "empty-state");
      empty.append(element("strong", "", state.taskSearch && history ? "没有找到这件事" : state.filter === "pending" ? "给下一步，留一点空间" : history ? state.historyUndated ? "没有时间未记录的事项" : "这一月，还没有完成记录" : "这里暂时是空的"),
        element("p", "", emptyTaskMessage()));
      if (history?.latestMonth && !state.taskSearch && !state.historyUndated && history.latestMonth !== state.historyMonth) {
        empty.append(taskButton("查看最近有记录的月份", "history-latest text-button", () => setTaskHistoryMonth(history.latestMonth)));
      }
      $("task-list").append(empty);
    }
    $("add-task-inline").hidden = groups.length > 0 || state.filter !== "pending";
    if (focusKey) {
      const target = [...$("task-list").querySelectorAll("[data-task-focus]")].find(node => node.dataset.taskFocus === focusKey);
      (target || document.querySelector(`[data-filter="${state.filter}"]`))?.focus({ preventScroll: true });
    }
  }

  /** 重要的事自成一组排在最前，其余按分类分开；已完成的事按日期分隔，不再逐日折叠。 */
  function buildTaskGroup(group, today) {
    const history = state.filter === "done";
    const section = element("div", `task-group${group.key === "important" ? " important" : ""}`);
    section.setAttribute("role", "group");
    const heading = element("div", `task-group-heading${history ? " history-heading" : ""}`);
    heading.append(element("span", "task-group-label", group.label), element("span", "task-group-count", history ? `${group.tasks.length} 件已展示` : `${group.tasks.length}`));
    const list = element("div", "task-group-list");
    list.setAttribute("role", "list");
    list.id = `todo-group-${group.key}`;
    list.append(...group.tasks.map(task => buildTaskCard(task, today, group.key)));
    section.append(heading, list);
    section.setAttribute("aria-label", `${group.label}：${group.tasks.length} 件`);
    return section;
  }

  function activeTasks() {
    return state.tasks.filter(task => !task.archived);
  }

  function emptyTaskMessage() {
    if (state.filter === "done") return state.taskSearch ? "搜索范围是全部时间，换个名称、分类或小步关键词试试。" : state.historyUndated ? "有明确完成时间的事都在对应月份里。" : "可以切换月份回看，或搜索全部时间的完成记录。";
    if (state.filter === "archived") return "放下不是删除，需要时还可以找回。";
    return "记下一件想做的事，也可以把它拆成几个小步。";
  }

  /** L 的发酵状态 + M 的小步路径，都在这张卡片上。 */
  function buildTaskCard(task, today, groupKey = "") {
    const finishing = state.filter === "pending" ? state.finishingTasks.get(task.id) : null;
    if (finishing?.node?.isConnected) return finishing.node;
    const age = FocusModel.taskAge(task, today);
    const progress = FocusModel.taskProgress(task);
    const wrapper = element("div", "task-swipe");
    wrapper.dataset.taskId = task.id;
    wrapper.setAttribute("role", "listitem");
    const remove = taskButton("删除", "task-delete", () => deleteTask(task.id));
    remove.setAttribute("aria-label", `删除待办：${task.title}`);
    bindTouchDelete(remove);
    remove.inert = true;
    remove.setAttribute("aria-hidden", "true");
    const card = element("article", `task-card${task.done ? " done" : ""}${task.archived ? " archived" : ""} age-${age.stage}`);
    const main = element("div", "task-main");
    const check = task.done || task.archived ? element("span", "task-checkbox task-check-static")
      : taskButton("", "task-checkbox", () => toggleTaskDone(task));
    check.setAttribute("aria-label", `${task.done ? "已完成" : task.archived ? "已放下" : "完成待办"}：${task.title}`);
    if (!task.done && !task.archived) {
      check.setAttribute("aria-pressed", "false");
      check.dataset.taskFocus = `complete-${task.id}`;
    }
    if (task.done) check.append(icon("check"));
    const info = taskButton("", "task-info", () => task.done || task.archived ? taskDetails(task.id) : taskEditor(task.id));
    info.setAttribute("aria-label", `${task.done || task.archived ? "查看" : "编辑"}待办：${task.title}`);
    info.dataset.taskFocus = `info-${task.id}`;
    const meta = element("div", "task-meta");
    // 组标题已经说明了重要或分类，卡片上不再重复同一个标签。
    if (task.important && groupKey !== "important") meta.append(element("span", "task-tag important", "重要"));
    if (groupKey === "important" || groupKey !== `category:${task.category}`) meta.append(element("span", "task-tag", task.category));
    if (!task.done && !task.archived) meta.append(element("span", "task-age", age.label));
    if (task.archived) meta.append(element("span", "task-age", "已放下"));
    if (task.done && !task.archived) {
      const completed = FocusModel.completedTaskDate(task);
      meta.append(element("span", "task-age", completed ? `${localDateKey(completed)} 完成` : "完成时间未记录"));
      if (task.steps?.length) meta.append(element("span", "task-step-summary", `${task.steps.length} 个小步已走完`));
    }
    info.append(element("span", "task-title", task.title), meta);
    const body = element("div", "task-body");
    body.append(info);
    const menu = taskButton("···", "task-menu", () => taskDetails(task.id));
    menu.setAttribute("aria-label", `待办详情：${task.title}`);
    menu.dataset.taskFocus = `menu-${task.id}`;
    main.append(check, body, menu);
    card.append(main);
    if (progress.total && (!task.done || (state.filter === "pending" && state.finishingTasks.has(task.id))) && !task.archived) card.append(buildStepPath(task, progress));
    if (!task.done && !task.archived && age.stage !== "fresh") card.append(buildAgeActions(task));
    if (task.archived) {
      const actions = element("div", "age-actions");
      actions.append(taskButton("找回", "age-action", () => restoreTask(task)));
      card.append(actions);
    }
    if (finishing) {
      finishing.node = wrapper;
      card.classList.add("celebrating");
      const note = element("div", "task-finish-note");
      note.setAttribute("role", "status");
      const mark = element("span", "task-finish-mark");
      mark.append(icon("check"));
      const copy = element("span", "task-finish-copy");
      copy.append(element("strong", "", "又走完一件事"), element("small", "", "已收进完成记录，随时可以回看"));
      note.append(mark, copy);
      main.replaceChildren(note);
      wrapper.classList.add("finishing");
      wrapper.style.setProperty("--finish-height", `${finishing.height}px`);
      wrapper.style.setProperty("--todo-finish-delay", `${-Math.max(0, performance.now() - finishing.startedAt)}ms`);
      if (finishing.phase === "exit") wrapper.classList.add("finishing-out");
      wrapper.inert = true;
      card.inert = true;
    }
    wrapper.append(remove, card);
    attachTaskSwipe(wrapper, card, remove);
    return wrapper;
  }

  function setSwipe(wrapper, open) {
    if (open) closeSwipes(wrapper);
    wrapper.classList.toggle("swipe-open", open);
    wrapper.style.setProperty("--swipe-x", `${open ? -TODO_UI.swipeWidth : 0}px`);
    const button = wrapper.querySelector(".task-delete");
    button.inert = !open;
    button.setAttribute("aria-hidden", String(!open));
  }

  function closeSwipes(except = null) {
    document.querySelectorAll(".task-swipe.swipe-open").forEach(wrapper => { if (wrapper !== except) setSwipe(wrapper, false); });
  }

  function bindTouchDelete(button) {
    let start = null;
    button.addEventListener("touchstart", event => {
      start = event.touches.length === 1 ? { x: event.touches[0].clientX, y: event.touches[0].clientY } : null;
    }, { passive: true });
    button.addEventListener("touchmove", event => {
      const touch = event.touches[0];
      if (!start || !touch || event.touches.length !== 1) { start = null; return; }
      if (Math.abs(touch.clientX - start.x) > TODO_UI.swipeSlop || Math.abs(touch.clientY - start.y) > TODO_UI.swipeSlop) start = null;
    }, { passive: true });
    button.addEventListener("touchcancel", () => { start = null; });
    button.addEventListener("touchend", event => {
      const touch = event.changedTouches[0];
      const tapped = start && touch && event.touches.length === 0 && Math.abs(touch.clientX - start.x) <= TODO_UI.swipeSlop && Math.abs(touch.clientY - start.y) <= TODO_UI.swipeSlop;
      start = null;
      if (!tapped || button.inert) return;
      event.preventDefault();
      button.click();
    }, { passive: false });
  }

  function attachTaskSwipe(wrapper, card, remove) {
    let gesture = null;
    let suppressClick = false;
    card.addEventListener("pointerdown", event => {
      if (!event.isPrimary || event.button !== 0 || state.finishingTasks.has(wrapper.dataset.taskId)) return;
      suppressClick = false;
      gesture = { id: event.pointerId, x: event.clientX, y: event.clientY, open: wrapper.classList.contains("swipe-open"), locked: false, offset: 0 };
    });
    card.addEventListener("pointermove", event => {
      if (!gesture || gesture.id !== event.pointerId) return;
      const dx = event.clientX - gesture.x;
      const dy = event.clientY - gesture.y;
      if (!gesture.locked) {
        if (Math.abs(dy) > TODO_UI.swipeSlop && Math.abs(dy) >= Math.abs(dx)) { gesture = null; return; }
        if (Math.abs(dx) < TODO_UI.swipeSlop || Math.abs(dx) < Math.abs(dy) * TODO_UI.swipeRatio) return;
        gesture.locked = true;
        closeSwipes(wrapper);
        card.setPointerCapture(event.pointerId);
        card.classList.add("swiping");
      }
      event.preventDefault();
      gesture.offset = Math.max(-TODO_UI.swipeWidth, Math.min(0, (gesture.open ? -TODO_UI.swipeWidth : 0) + dx));
      wrapper.style.setProperty("--swipe-x", `${gesture.offset}px`);
    });
    const end = (event, cancelled) => {
      if (!gesture || gesture.id !== event.pointerId) return;
      if (gesture.locked) {
        suppressClick = true;
        card.classList.remove("swiping");
        setSwipe(wrapper, cancelled ? gesture.open : gesture.offset <= -TODO_UI.swipeThreshold);
        if (card.hasPointerCapture(event.pointerId)) card.releasePointerCapture(event.pointerId);
      }
      gesture = null;
    };
    card.addEventListener("pointerup", event => end(event, false));
    card.addEventListener("pointercancel", event => end(event, true));
    card.addEventListener("click", event => {
      if (suppressClick || wrapper.classList.contains("swipe-open")) {
        event.preventDefault();
        event.stopImmediatePropagation();
        if (!suppressClick) setSwipe(wrapper, false);
        suppressClick = false;
      }
    }, true);
    card.addEventListener("keydown", event => {
      if (event.key === "ArrowLeft") { event.preventDefault(); setSwipe(wrapper, true); remove.focus(); }
    });
    wrapper.addEventListener("keydown", event => {
      if (event.key === "Escape" || event.key === "ArrowRight") {
        if (!wrapper.classList.contains("swipe-open")) return;
        event.preventDefault();
        setSwipe(wrapper, false);
        card.querySelector(".task-info, .diary-open").focus();
      }
    });
  }

  /** M：一条横向的小路，走过的点是实心的。路径只展示进展，展开后整行操作小步。 */
  function buildStepPath(task, progress) {
    const path = element("div", "step-path");
    const expanded = state.taskExpanded.has(task.id);
    const list = element("div", "step-list");
    list.id = `todo-steps-${task.id}`;
    list.hidden = !expanded;
    const expand = taskButton("", "step-expand", () => {
      if (state.taskExpanded.has(task.id)) state.taskExpanded.delete(task.id);
      else state.taskExpanded.add(task.id);
      renderTasks();
    });
    expand.setAttribute("aria-expanded", String(expanded));
    expand.setAttribute("aria-controls", list.id);
    expand.dataset.taskFocus = `expand-${task.id}`;
    const next = FocusModel.nextStep(task);
    expand.append(element("span", "step-count", `小步 ${progress.walked} / ${progress.total}`),
      element("span", "step-caption", next ? `下一步 · ${next.title}` : "每一步都走完了"), element("span", "step-toggle-label", expanded ? "收起" : "展开"));
    const track = element("div", "step-track");
    track.setAttribute("aria-hidden", "true");
    task.steps.forEach((step, index) => {
      if (index > 0) track.append(element("span", `step-link${step.done && task.steps[index - 1].done ? " walked" : ""}`));
      track.append(element("span", `step-dot${step.done ? " walked" : ""}`));
      const row = taskButton("", `step-row${step.done ? " walked" : ""}`, () => toggleStep(task, step));
      row.setAttribute("role", "checkbox");
      row.setAttribute("aria-checked", String(step.done));
      row.setAttribute("aria-label", step.title);
      row.dataset.taskFocus = `step-${task.id}-${step.id}`;
      const check = element("span", "step-check", step.done ? "✓" : "");
      check.setAttribute("aria-hidden", "true");
      row.append(check, element("span", "step-label", step.title));
      list.append(row);
    });
    path.append(expand, track, list);
    return path;
  }

  /** L：躺久了的事给两个出口——续一天，或放下。 */
  function buildAgeActions(task) {
    const row = element("div", "age-actions");
    const renew = taskButton("续一天", "age-action", () => {
      changeTask(task, { ...task, createdAt: localDateKey(new Date()) }, "已续到今天");
    });
    renew.setAttribute("aria-label", `把「${task.title}」重新放到今天`);
    const release = taskButton("放下", "age-action quiet", () => changeTask(task, { ...task, archived: true }, "已放下"));
    release.setAttribute("aria-label", `放下「${task.title}」`);
    row.append(renew, release);
    return row;
  }

  /* 日记：首页（按月回看、搜索文字或日期、左滑删除进回收站）、详情页、全屏编辑页与回收站。 */
  function diarySelection() {
    return FocusModel.selectDiaryEntries(state.diaryEntries, { month: state.diaryMonth, query: state.diarySearch });
  }

  function diaryTime(entry) {
    const date = FocusModel.diaryEntryDate(entry);
    return date ? clockLabel(date) : "时间未记录";
  }

  function diaryName(entry) {
    const text = (entry.title || entry.text || "").trim().replace(/\s+/g, " ");
    if (text) return text.length > DIARY_UI.nameLength ? `${text.slice(0, DIARY_UI.nameLength)}…` : text;
    return entry.audios.length ? "一段语音" : entry.photos.length ? "照片日记" : "日记";
  }

  function diaryAttachments(entry) {
    return [entry.photos.length && `${entry.photos.length} 张照片`, entry.audios.length && `${entry.audios.length} 段语音`].filter(Boolean).join(" · ");
  }

  function syncDiaryChrome() {
    DIARY_VIEWS.forEach(name => { $(`diary-${name}`).hidden = name !== state.diaryView; });
    document.querySelector(".phone").classList.toggle("diary-editing", !$("page-diary").hidden && state.diaryView === "editor");
  }

  function showDiaryView(view) {
    if (state.diaryView === "home" && view !== "home") state.diaryHomeScroll = $("phone-content").scrollTop;
    state.diaryView = view;
    closeSwipes();
    syncDiaryChrome();
  }

  /** 编辑页只在打开时构建，重新渲染不会打断正在输入的内容。 */
  function renderDiary() {
    syncDiaryChrome();
    if (state.diaryView === "trash") renderDiaryTrash();
    else if (state.diaryView === "drafts") renderDiaryDrafts();
    else if (state.diaryView === "detail") renderDiaryDetail();
    else if (state.diaryView === "home") renderDiaryHome();
  }

  function renderDiaryHome() {
    const result = diarySelection();
    const searching = !!state.diarySearch.trim();
    const currentMonth = localDateKey(new Date()).slice(0, 7);
    const [year, month] = state.diaryMonth.split("-").map(Number);
    $("diary-month-nav").hidden = searching;
    $("diary-month-label").textContent = taskMonthLabel(state.diaryMonth);
    $("diary-month-prev").disabled = state.diaryMonth <= `${UI.minYear}-01`;
    $("diary-month-next").disabled = state.diaryMonth >= currentMonth;
    $("diary-month-current").hidden = searching || state.diaryMonth === currentMonth;
    $("diary-summary").textContent = !searching ? `${year}年${month}月 · ${result.total} 条日记`
      : result.dateLabel ? `按日期「${result.dateLabel}」或文字 · 找到 ${result.total} 条` : `全部时间 · 找到 ${result.total} 条`;
    $("diary-list").replaceChildren(...result.groups.map(buildDiaryGroup));
    if (result.groups.length) return;
    const empty = element("div", "empty-state diary-empty");
    if (searching) empty.append(element("strong", "", "没有找到相关日记"), element("p", "", "可以搜索标题、正文或日期（如 9月27日、2026-09-27、昨天），不包含语音与照片内容。"));
    else if (!result.months.length) {
      empty.append(element("strong", "", "还没有日记"), element("p", "", "记下今天发生的一件小事，文字、照片或一段语音都可以。"),
        taskButton("记一条", "history-latest", startDiaryEntry));
    } else {
      empty.append(element("strong", "", "这一月还没有日记"), element("p", "", "可以切换月份回看，或搜索全部时间的文字。"));
      if (result.latestMonth && result.latestMonth !== state.diaryMonth) {
        empty.append(taskButton("查看最近有日记的月份", "history-latest", () => setDiaryMonth(result.latestMonth)));
      }
    }
    $("diary-list").append(empty);
  }

  function buildDiaryGroup(group) {
    const section = element("section", "diary-group");
    section.setAttribute("aria-label", `${group.label}：${group.entries.length} 条`);
    const heading = element("div", "diary-day");
    heading.append(element("h3", "", group.label), element("small", "", `${group.entries.length} 条`));
    const list = element("div", "diary-group-list");
    list.setAttribute("role", "list");
    list.append(...group.entries.map(buildDiaryCard));
    section.append(heading, list);
    return section;
  }

  function buildDiaryCard(entry) {
    const wrapper = element("div", "task-swipe diary-swipe");
    wrapper.dataset.diaryId = entry.id;
    wrapper.setAttribute("role", "listitem");
    const remove = taskButton("删除", "task-delete", () => trashDiary(entry.id));
    remove.setAttribute("aria-label", `删除日记：${diaryName(entry)}（移入回收站）`);
    bindTouchDelete(remove);
    remove.inert = true;
    remove.setAttribute("aria-hidden", "true");
    const card = element("article", "diary-card");
    const open = taskButton("", "diary-open", () => openDiaryDetail(entry.id));
    const attachments = diaryAttachments(entry);
    open.setAttribute("aria-label", `查看日记：${diaryTime(entry)} ${diaryName(entry)}${attachments ? `，${attachments}` : ""}`);
    open.append(element("span", "diary-time", diaryTime(entry)));
    if (entry.title) open.append(element("strong", "diary-title", entry.title));
    if (entry.text) open.append(element("span", "diary-text", entry.text));
    if (entry.photos.length) {
      const photos = element("span", "diary-photos");
      photos.setAttribute("aria-hidden", "true");
      entry.photos.slice(0, DIARY_UI.visiblePhotos).forEach((photo, index) => {
        const tile = element("span", `diary-photo tone-${index % DIARY_UI.photoTones}`);
        const extra = entry.photos.length - DIARY_UI.visiblePhotos;
        if (index === DIARY_UI.visiblePhotos - 1 && extra > 0) tile.append(element("span", "diary-photo-more", `+${extra}`));
        photos.append(tile);
      });
      open.append(photos);
    }
    card.append(open, ...entry.audios.map(audio => buildVoiceButton(audio)));
    wrapper.append(remove, card);
    attachTaskSwipe(wrapper, card, remove);
    return wrapper;
  }

  function buildVoiceButton(audio) {
    const voice = taskButton("", "diary-voice", () => toast("原型未接入录音文件，正式版在此回放原声"));
    voice.setAttribute("aria-label", `回放原声，时长 ${formatTime(audio.seconds)}`);
    const play = element("span", "diary-voice-play");
    play.append(icon("play"));
    voice.append(play, element("span", "diary-voice-label", "原声"), element("span", "diary-voice-wave"), element("span", "diary-voice-time", formatTime(audio.seconds)));
    return voice;
  }

  function findDiaryEntry(id) {
    return state.diaryEntries.find(entry => entry.id === id && !entry.deletedAt) ?? null;
  }

  function clockLabel(date) {
    return `${String(date.getHours()).padStart(2, "0")}:${String(date.getMinutes()).padStart(2, "0")}`;
  }

  function stampLabel(value) {
    const date = new Date(value ?? "");
    return Number.isFinite(date.getTime()) ? `${localDateKey(date)} ${clockLabel(date)}` : "未记录";
  }

  function localDateTimeValue(date) {
    return `${localDateKey(date)}T${clockLabel(date)}`;
  }

  /* 详情页：阅读全文、看照片、回放原声；编辑与删除入口。 */
  function openDiaryDetail(id) {
    state.diaryDetailId = id;
    showDiaryView("detail");
    renderDiaryDetail();
    $("phone-content").scrollTop = 0;
    $("diary-detail-heading").focus({ preventScroll: true });
  }

  /**
   * 已在日记页时再点「日记」：草稿箱、回收站、详情等二级页直接回首页，编辑中的内容先存入草稿；已在首页则回到顶部。
   * 从别的页签切回日记时不走这里，仍回到离开时的页面。
   */
  function reselectDiaryTab() {
    if (state.diaryView === "home") {
      $("phone-content").scrollTop = 0;
      return;
    }
    if (state.diaryEditor) {
      stopDiaryRecording("回到首页前已停止录音，已录内容保留在草稿中");
      const kept = storeDiaryDraft();
      state.diaryEditor = null;
      if (kept) toast("已存入草稿，可在「更多 · 草稿」继续写");
    }
    backToDiaryHome();
  }

  function backToDiaryHome(focusId = null) {
    showDiaryView("home");
    renderDiaryHome();
    $("phone-content").scrollTop = state.diaryHomeScroll;
    const card = [...document.querySelectorAll(".diary-swipe")].find(node => node.dataset.diaryId === focusId);
    (card?.querySelector(".diary-open") || $("add-diary")).focus({ preventScroll: true });
  }

  function renderDiaryDetail() {
    const entry = findDiaryEntry(state.diaryDetailId);
    if (!entry) { backToDiaryHome(); return; }
    const date = FocusModel.diaryEntryDate(entry);
    $("diary-detail-heading").textContent = date ? `${date.getMonth() + 1}月${date.getDate()}日 ${date.toLocaleDateString("zh-CN", { weekday: "short" })}` : "时间未记录";
    const nodes = [element("p", "diary-detail-meta", date ? `${date.getFullYear()}年 · ${clockLabel(date)}` : "记录时间未知")];
    if (entry.title) nodes.push(element("h3", "diary-detail-title", entry.title));
    if (entry.text) nodes.push(element("p", "diary-detail-text", entry.text));
    if (entry.photos.length) {
      const grid = element("div", "diary-photo-grid");
      entry.photos.forEach((photo, index) => {
        const tile = taskButton("", `diary-photo tone-${index % DIARY_UI.photoTones}`, () => diaryPhotoViewer(entry.photos, index));
        tile.setAttribute("aria-label", `查看照片 ${index + 1} / ${entry.photos.length}${photo.label ? `：${photo.label}` : ""}`);
        grid.append(tile);
      });
      nodes.push(element("h4", "diary-section-title", `照片 · ${entry.photos.length} 张`), grid);
    }
    if (entry.audios.length) {
      const list = element("div", "diary-audio-list");
      list.append(...entry.audios.map(audio => buildVoiceButton(audio)));
      nodes.push(element("h4", "diary-section-title", `原声 · ${entry.audios.length} 段`), list);
    }
    const edited = entry.updatedAt && entry.updatedAt !== entry.createdAt;
    nodes.push(element("p", "diary-detail-foot", `写于 ${stampLabel(entry.createdAt)}${edited ? ` · 最后修改 ${stampLabel(entry.updatedAt)}` : ""}`));
    $("diary-detail-body").replaceChildren(...nodes);
  }

  function diaryPhotoViewer(photos, index) {
    const photo = photos[index];
    const content = element("div", "diary-viewer");
    const nav = element("div", "modal-actions");
    const previous = taskButton("上一张", "secondary-button", () => diaryPhotoViewer(photos, index - 1));
    const next = taskButton("下一张", "secondary-button", () => diaryPhotoViewer(photos, index + 1));
    previous.disabled = index === 0;
    next.disabled = index === photos.length - 1;
    nav.append(previous, next);
    content.append(element("div", `diary-photo diary-viewer-photo tone-${index % DIARY_UI.photoTones}`),
      element("p", "diary-viewer-caption", `${photo.label || "照片"} · 第 ${index + 1} / ${photos.length} 张 · 原型占位图`), nav);
    showModal("查看照片", content);
  }

  function trashDiaryFromDetail() {
    const id = state.diaryDetailId;
    state.diaryEntries = state.diaryEntries.map(entry => entry.id === id ? FocusModel.moveDiaryToTrash(entry) : entry);
    backToDiaryHome();
    toast("已移入回收站，可在「更多 · 回收站」恢复");
  }

  /*
   * 全屏编辑页：编辑的是工作副本 editor.draft；返回、切到其他页面或退到后台时，有改动就存入草稿箱。
   * 新日记每次都是独立草稿（new-N），修改已有日记每条最多一份（edit:ID）。保存成功后移出草稿箱。
   */
  function diaryDraftFrom(entry) {
    const date = entry ? FocusModel.diaryEntryDate(entry) : null;
    return { title: entry?.title ?? "", text: entry?.text ?? "", occurredAt: localDateTimeValue(date ?? new Date()),
      photos: (entry?.photos ?? []).map(photo => ({ ...photo })), audios: (entry?.audios ?? []).map(audio => ({ ...audio })) };
  }

  function cloneDiaryDraft(draft) {
    return { title: draft.title, text: draft.text, occurredAt: draft.occurredAt,
      photos: draft.photos.map(photo => ({ ...photo })), audios: draft.audios.map(audio => ({ ...audio })) };
  }

  function currentDiaryDraft() {
    return state.diaryEditor?.draft ?? null;
  }

  function diaryDraftChanged(editor, draft) {
    const base = diaryDraftFrom(editor.id ? findDiaryEntry(editor.id) : null);
    if (!editor.id) base.occurredAt = draft.occurredAt;
    return JSON.stringify(base) !== JSON.stringify(cloneDiaryDraft(draft));
  }

  /** 有改动就写入草稿箱，没改动（或改回原样）就移出草稿箱。返回是否存为草稿。 */
  function storeDiaryDraft() {
    const editor = state.diaryEditor;
    if (!editor) return false;
    if (!diaryDraftChanged(editor, editor.draft)) {
      state.diaryDrafts.delete(editor.key);
      return false;
    }
    editor.savedAt = new Date().toISOString();
    state.diaryDrafts.set(editor.key, { ...cloneDiaryDraft(editor.draft), key: editor.key, entryId: editor.id, updatedAt: editor.savedAt });
    return true;
  }

  /** 离开日记页或退到后台：先停录音，再把没写完的内容存入草稿；编辑页保持打开，回来可以接着写。 */
  function suspendDiaryEditor(reason) {
    if (!state.diaryEditor) return;
    stopDiaryRecording(`${reason}已停止录音，已录内容保留在草稿中`);
    if (storeDiaryDraft()) toast(`${reason}已自动存入草稿`);
  }

  function startDiaryEntry() {
    openDiaryEditor(null);
  }

  function beginDiaryEditor(editor) {
    state.diaryEditor = editor;
    showDiaryView("editor");
    renderDiaryEditor();
    $("phone-content").scrollTop = 0;
    $("diary-editor-title").focus({ preventScroll: true });
  }

  function openDiaryEditor(id) {
    const key = id ? `edit:${id}` : `new-${state.nextDiaryDraftId++}`;
    const stored = id ? state.diaryDrafts.get(key) : null;
    beginDiaryEditor({ key, id, returnTo: state.diaryView === "detail" && id ? "detail" : "home", restored: !!stored,
      savedAt: stored?.updatedAt ?? null, orphan: false, draft: stored ? cloneDiaryDraft(stored) : diaryDraftFrom(id ? findDiaryEntry(id) : null) });
  }

  function openDiaryDraft(key) {
    const item = FocusModel.listDiaryDrafts([...state.diaryDrafts.values()], state.diaryEntries).find(value => value.draft.key === key);
    if (!item) return;
    beginDiaryEditor({ key, id: item.entry?.id ?? null, returnTo: "drafts", restored: true, savedAt: item.draft.updatedAt,
      orphan: item.kind === "orphan", draft: cloneDiaryDraft(item.draft) });
  }

  function renderDiaryEditor() {
    const editor = state.diaryEditor;
    const draft = currentDiaryDraft();
    $("diary-editor-title").textContent = editor.id ? "编辑日记" : "记一条";
    const nodes = [];
    if (editor.restored) {
      const banner = element("div", "diary-draft-banner");
      banner.setAttribute("role", "status");
      banner.append(element("span", "", editor.orphan ? "原日记已删除，保存时将另存为新日记" : `继续草稿 · 最后编辑 ${stampLabel(editor.savedAt)}`),
        taskButton("放弃草稿", "text-button", discardDiaryDraft));
      nodes.push(banner);
    }
    const bind = (node, key) => node.addEventListener("input", () => { draft[key] = node.value; hideDiaryError(); });
    const when = element("label", "diary-when");
    const whenInput = element("input");
    whenInput.type = "datetime-local";
    whenInput.id = "diary-occurred-at";
    whenInput.value = draft.occurredAt;
    whenInput.max = localDateTimeValue(new Date());
    bind(whenInput, "occurredAt");
    when.append(element("span", "", "记录时间"), whenInput, element("small", "", "可补写过去的经历"));
    const title = element("input", "diary-title-input");
    title.id = "diary-title-input";
    title.placeholder = "标题（可选）";
    title.setAttribute("aria-label", "标题（可选）");
    title.maxLength = FocusModel.DIARY_LIMITS.maxTitle;
    title.value = draft.title;
    bind(title, "title");
    const text = element("textarea", "diary-text-input");
    text.id = "diary-text-input";
    text.placeholder = "今天发生了什么？有什么感受？";
    text.setAttribute("aria-label", "正文");
    text.maxLength = FocusModel.DIARY_LIMITS.maxText;
    text.value = draft.text;
    bind(text, "text");
    nodes.push(when, title, text);
    $("diary-editor-body").replaceChildren(...nodes);
    hideDiaryError();
    renderDiaryAttachments();
    renderDiaryRecording();
  }

  function renderDiaryAttachments() {
    const box = $("diary-editor-attachments");
    const draft = currentDiaryDraft();
    if (!draft) return;
    const saved = new Set((state.diaryEditor.id ? findDiaryEntry(state.diaryEditor.id)?.audios ?? [] : []).map(audio => audio.id));
    const nodes = [];
    if (draft.photos.length) {
      const grid = element("div", "diary-photo-grid");
      draft.photos.forEach((photo, index) => {
        const tile = element("div", `diary-photo tone-${index % DIARY_UI.photoTones}`);
        const remove = taskButton("×", "diary-photo-remove", () => { draft.photos.splice(index, 1); renderDiaryAttachments(); });
        remove.setAttribute("aria-label", `移除第 ${index + 1} 张照片`);
        tile.append(remove);
        grid.append(tile);
      });
      nodes.push(element("h4", "diary-section-title", `照片 · ${draft.photos.length} 张`), grid);
    }
    if (draft.audios.length) {
      const list = element("div", "diary-audio-list");
      draft.audios.forEach((audio, index) => {
        const row = element("div", "diary-audio-row");
        const remove = taskButton("移除", "diary-audio-remove", () => { draft.audios.splice(index, 1); renderDiaryAttachments(); });
        remove.setAttribute("aria-label", `移除第 ${index + 1} 段原声`);
        row.append(buildVoiceButton(audio), remove);
        list.append(row);
      });
      nodes.push(element("h4", "diary-section-title", `原声 · ${draft.audios.length} 段`), list);
      if (draft.audios.some(audio => !saved.has(audio.id))) nodes.push(element("p", "diary-draft-hint", "新录音已停止，目前在草稿中；保存日记后才成为正式记录。"));
    }
    box.replaceChildren(...nodes);
  }

  function showDiaryError(message) {
    $("diary-editor-error").textContent = message;
    $("diary-editor-error").hidden = false;
    $("diary-editor-error").scrollIntoView({ block: "nearest" });
  }

  function hideDiaryError() {
    $("diary-editor-error").hidden = true;
  }

  function discardDiaryDraft() {
    confirmAction("放弃这份草稿？", "未保存的修改会被丢弃，已保存的日记不受影响。", () => {
      const editor = state.diaryEditor;
      cancelDiaryRecording();
      state.diaryDrafts.delete(editor.key);
      editor.draft = diaryDraftFrom(editor.id ? findDiaryEntry(editor.id) : null);
      editor.restored = false;
      editor.orphan = false;
      renderDiaryEditor();
    }, "放弃草稿");
  }

  /** 有目标日记时回到它的详情页；从草稿箱进入的回草稿箱；否则回首页并恢复阅读位置。 */
  function leaveDiaryEditor(targetId, returnTo = "home") {
    state.diaryEditor = null;
    if (!targetId && returnTo === "drafts") openDiaryDrafts();
    else if (targetId && findDiaryEntry(targetId)) {
      state.diaryDetailId = targetId;
      showDiaryView("detail");
      renderDiaryDetail();
      $("phone-content").scrollTop = 0;
      $("diary-detail-heading").focus({ preventScroll: true });
    } else backToDiaryHome();
  }

  function closeDiaryEditor() {
    stopDiaryRecording("返回前已停止录音，已录内容保留在草稿中");
    const editor = state.diaryEditor;
    const kept = storeDiaryDraft();
    leaveDiaryEditor(editor.returnTo === "detail" ? editor.id : null, editor.returnTo);
    if (kept) toast("已存入草稿，可在「更多 · 草稿」继续写");
  }

  function saveDiaryEditor() {
    stopDiaryRecording("录音已停止");
    const editor = state.diaryEditor;
    const draft = currentDiaryDraft();
    const original = editor.id ? findDiaryEntry(editor.id) : null;
    const originalDate = original && FocusModel.diaryEntryDate(original);
    const keepTime = originalDate && localDateTimeValue(originalDate) === draft.occurredAt;
    let valid;
    try {
      if (editor.id && !original) throw new Error("这条日记已不在列表中，无法保存修改。");
      valid = FocusModel.validateDiaryEntry({ ...draft, occurredAt: keepTime ? original.occurredAt : new Date(draft.occurredAt) });
    } catch (error) {
      showDiaryError(error.message);
      return;
    }
    const now = new Date().toISOString();
    const saved = original ? { ...original, ...valid, updatedAt: now }
      : { id: `diary-${state.nextDiaryId++}`, ...valid, createdAt: now, updatedAt: now, deletedAt: null };
    state.diaryEntries = original ? state.diaryEntries.map(entry => entry.id === saved.id ? saved : entry) : [saved, ...state.diaryEntries];
    if (!original && !state.diarySearch.trim()) state.diaryMonth = localDateKey(new Date(saved.occurredAt)).slice(0, 7);
    state.diaryDrafts.delete(editor.key);
    leaveDiaryEditor(saved.id);
    toast(original ? "已保存修改" : "已保存");
  }

  function pickDiaryPhotos() {
    stopDiaryRecording("选图前已停止录音，已录内容保留在草稿中");
    const content = element("div", "modal-copy");
    content.append(element("p", "", "原型不读取相册，用占位图模拟系统选图的结果。正式版只读取你主动选中的照片。"));
    const actions = element("div", "modal-actions");
    actions.append(...DIARY_UI.pickCounts.map(count => taskButton(`选择 ${count} 张`, "secondary-button", () => {
      closeModal();
      const draft = currentDiaryDraft();
      if (!draft) return;
      for (let index = 0; index < count; index += 1) draft.photos.push({ id: `diary-photo-${state.nextDiaryAttachmentId++}`, label: `新照片 ${draft.photos.length + 1}` });
      hideDiaryError();
      renderDiaryAttachments();
    })));
    const cancel = taskButton("取消选择", "text-button", () => { closeModal(); toast("未选择照片，已输入的内容都还在"); });
    content.append(actions, cancel);
    showModal("模拟系统选图", content);
  }

  /* 模拟录音：点一次开始、再点一次结束；切出、离开、选图、返回都会先停止并保留。 */
  function requestDiaryRecording() {
    if (state.diaryRecording) { stopDiaryRecording(); return; }
    const grant = () => { state.micPermission = MIC.granted; beginDiaryRecording(); };
    if (state.micPermission === MIC.granted) grant();
    else if (state.micPermission === MIC.denied) {
      confirmAction("麦克风权限未开启", "正式版会引导你到系统设置中开启，不会反复弹出授权。文字和照片不受影响。", grant, "模拟已在设置中开启");
    } else {
      confirmAction("允许录音？", "模拟系统授权：只在你点击录音时使用麦克风，离开日记或锁屏就停止，不在后台录音。", grant, "允许", () => {
        state.micPermission = MIC.denied;
        toast("未获得麦克风权限，文字和照片仍可使用");
      });
    }
  }

  function recordingSeconds() {
    return Math.floor((performance.now() - state.diaryRecording.startedAt) / LIMITS.millisecondsPerSecond);
  }

  function beginDiaryRecording() {
    if (state.diaryRecording || !state.diaryEditor || $("page-diary").hidden) return;
    const time = element("span", "diary-recording-time", formatTime(0));
    state.diaryRecording = { startedAt: performance.now(), interval: setInterval(() => { time.textContent = formatTime(recordingSeconds()); }, UI.tickMs) };
    const stop = taskButton("停止", "diary-recording-stop", () => stopDiaryRecording());
    $("diary-recording-bar").replaceChildren(element("span", "diary-recording-dot"), element("strong", "", "正在录音"), time, stop);
    renderDiaryRecording();
  }

  function cancelDiaryRecording() {
    if (!state.diaryRecording) return;
    clearInterval(state.diaryRecording.interval);
    state.diaryRecording = null;
    renderDiaryRecording();
  }

  function stopDiaryRecording(message = "录音已停止，已放入草稿") {
    if (!state.diaryRecording) return false;
    const seconds = recordingSeconds();
    cancelDiaryRecording();
    const draft = currentDiaryDraft();
    if (!draft || seconds < FocusModel.DIARY_LIMITS.minAudioSeconds) toast("录音不足 1 秒，未保留");
    else {
      draft.audios.push({ id: `diary-audio-${state.nextDiaryAttachmentId++}`, seconds });
      hideDiaryError();
      toast(message);
    }
    renderDiaryAttachments();
    return true;
  }

  function renderDiaryRecording() {
    const recording = !!state.diaryRecording;
    $("diary-record").setAttribute("aria-pressed", String(recording));
    $("diary-record").classList.toggle("recording", recording);
    $("diary-record-label").textContent = recording ? "停止录音" : "录音";
    $("diary-recording-bar").hidden = !recording;
  }

  function keepDiaryScroll(action) {
    const scroll = $("phone-content").scrollTop;
    action();
    closeSwipes();
    renderDiary();
    $("phone-content").scrollTop = scroll;
  }

  function trashDiary(id) {
    keepDiaryScroll(() => {
      state.diaryEntries = state.diaryEntries.map(entry => entry.id === id ? FocusModel.moveDiaryToTrash(entry) : entry);
    });
    toast("已移入回收站，可在「更多 · 回收站」恢复");
  }

  function setDiaryMonth(month) {
    const current = localDateKey(new Date()).slice(0, 7);
    if (month > current || month < `${UI.minYear}-01`) return;
    state.diaryMonth = month;
    state.diarySearch = "";
    $("diary-search").value = "";
    closeSwipes();
    renderDiary();
    $("phone-content").scrollTop = 0;
  }

  function shiftDiaryMonth(offset) {
    const [year, month] = state.diaryMonth.split("-").map(Number);
    setDiaryMonth(localDateKey(new Date(year, month - 1 + offset, 1)).slice(0, 7));
  }

  function diaryMonthPicker() {
    monthPicker({ selected: state.diaryMonth, months: diarySelection().months, unit: "条",
      describe: count => `${count} 条日记`, onPick: setDiaryMonth });
  }

  function diaryMoreRow(iconName, title, caption, action) {
    const row = taskButton("", "settings-row", () => { closeModal(); action(); });
    const iconWrap = element("span", "setting-icon");
    iconWrap.append(icon(iconName));
    const copy = element("div");
    copy.append(element("strong", "", title), element("small", "", caption));
    const arrow = icon("arrow");
    arrow.classList.add("arrow");
    row.append(iconWrap, copy, arrow);
    return row;
  }

  function diaryMore() {
    const content = element("div", "modal-copy");
    const group = element("div", "settings-group");
    group.append(
      diaryMoreRow("edit", "草稿", `${state.diaryDrafts.size} 份 · 没写完离开时自动保存`, openDiaryDrafts),
      diaryMoreRow("trash", "回收站", `${FocusModel.trashedDiaryEntries(state.diaryEntries).length} 条 · 不会自动清空`, openDiaryTrash));
    content.append(group, element("p", "", "首版不设日记独立锁；语音转文字与日记导出尚未接入，录音只保存原声。"));
    showModal("更多", content);
  }

  function openDiaryDrafts() {
    showDiaryView("drafts");
    renderDiary();
    $("phone-content").scrollTop = 0;
    $("diary-drafts-heading").focus({ preventScroll: true });
  }

  function renderDiaryDrafts() {
    const items = FocusModel.listDiaryDrafts([...state.diaryDrafts.values()], state.diaryEntries);
    $("diary-drafts-list").replaceChildren(...items.map(({ draft, entry, kind }) => {
      const item = element("article", "diary-trash-item diary-draft-item");
      item.setAttribute("role", "listitem");
      const label = kind === "new" ? "新日记" : kind === "edit" ? `修改：${diaryName(entry)}` : "原日记已删除 · 将另存为新日记";
      const attachments = diaryAttachments({ photos: draft.photos, audios: draft.audios });
      const summary = draft.title.trim() || draft.text.trim() || attachments || "空白草稿";
      const open = taskButton("", "diary-draft-open", () => openDiaryDraft(draft.key));
      open.setAttribute("aria-label", `继续写草稿：${label}，${summary}`);
      open.append(element("span", `diary-draft-kind ${kind}`, label),
        element("span", "diary-time", `最后编辑 ${stampLabel(draft.updatedAt)} · 记录时间 ${draft.occurredAt.replace("T", " ")}`));
      if (draft.title.trim()) open.append(element("strong", "diary-title", draft.title.trim()));
      open.append(element("span", "diary-text", draft.text.trim() || (draft.title.trim() ? "" : summary)));
      if (attachments) open.append(element("span", "diary-trash-attachments", attachments));
      const actions = element("div", "diary-trash-actions");
      const remove = taskButton("删除草稿", "age-action danger", () => confirmAction("删除这份草稿？",
        `${label}的草稿会被删除${attachments ? `，其中新添加的照片与录音也会一并删除` : ""}；已保存的日记不受影响。`, () => {
          state.diaryDrafts.delete(draft.key);
          renderDiary();
          toast("已删除草稿");
        }, "删除草稿"));
      remove.setAttribute("aria-label", `删除草稿：${summary}`);
      actions.append(taskButton("继续写", "age-action", () => openDiaryDraft(draft.key)), remove);
      item.append(open, actions);
      return item;
    }));
    if (!items.length) {
      const empty = element("div", "empty-state");
      empty.append(element("strong", "", "没有草稿"), element("p", "", "没写完就离开时，日记会自动存到这里。"));
      $("diary-drafts-list").append(empty);
    }
  }

  function openDiaryTrash() {
    showDiaryView("trash");
    renderDiary();
    $("phone-content").scrollTop = 0;
    $("diary-trash-heading").focus({ preventScroll: true });
  }

  function closeDiaryTrash() {
    backToDiaryHome();
    $("diary-more").focus({ preventScroll: true });
  }

  function renderDiaryTrash() {
    const entries = FocusModel.trashedDiaryEntries(state.diaryEntries);
    $("diary-trash-list").replaceChildren(...entries.map(entry => {
      const item = element("article", "diary-trash-item");
      item.setAttribute("role", "listitem");
      const date = FocusModel.diaryEntryDate(entry);
      const deleted = new Date(entry.deletedAt);
      item.append(element("span", "diary-time", `${date ? `${localDateKey(date)} ${diaryTime(entry)}` : "时间未记录"} · ${Number.isFinite(deleted.getTime()) ? `${localDateKey(deleted)} 删除` : "删除时间未记录"}`));
      if (entry.title) item.append(element("strong", "diary-title", entry.title));
      item.append(element("span", "diary-text", entry.text || diaryName(entry)));
      const attachments = diaryAttachments(entry);
      if (attachments) item.append(element("span", "diary-trash-attachments", attachments));
      const actions = element("div", "diary-trash-actions");
      const restore = taskButton("恢复", "age-action", () => {
        state.diaryEntries = state.diaryEntries.map(current => current.id === entry.id ? FocusModel.restoreDiaryEntry(current) : current);
        renderDiary();
        toast(`已恢复到 ${date ? localDateKey(date) : "原日期"}`);
      });
      restore.setAttribute("aria-label", `恢复日记：${diaryName(entry)}`);
      const destroy = taskButton("永久删除", "age-action danger", () => confirmAction("永久删除这条日记？",
        `「${diaryName(entry)}」${attachments ? `及其 ${attachments}` : ""}将被永久删除，无法恢复。`, () => {
          state.diaryEntries = state.diaryEntries.filter(current => current.id !== entry.id);
          renderDiary();
          toast("已永久删除");
        }, "永久删除"));
      destroy.setAttribute("aria-label", `永久删除日记：${diaryName(entry)}`);
      actions.append(restore, destroy);
      item.append(actions);
      return item;
    }));
    if (!entries.length) {
      const empty = element("div", "empty-state");
      empty.append(element("strong", "", "回收站是空的"), element("p", "", "删除的日记会先放到这里，需要时可以恢复。"));
      $("diary-trash-list").append(empty);
    }
  }

  function showTaskUndo() {
    const last = state.taskUndo.at(-1);
    $("task-undo").hidden = !last || $("page-tasks").hidden;
    $("task-undo-message").textContent = last ? `${last.label} · ${last.task.title}${state.taskUndo.length > 1 ? `（可依次撤销 ${state.taskUndo.length} 次）` : ""}` : "";
  }

  function cancelTaskFinish(id) {
    const animation = state.finishingTasks.get(id);
    if (!animation) return;
    clearTimeout(animation.holdTimer);
    clearTimeout(animation.exitTimer);
    state.finishingTasks.delete(id);
  }

  function taskHistorySnapshot() {
    if (state.filter !== "done") return null;
    return { month: state.historyMonth, undated: state.historyUndated, query: state.taskSearch,
      limit: taskHistoryView().limit, scrollTop: $("phone-content").scrollTop };
  }

  function changeTask(task, next, label, celebrate = false, history = taskHistorySnapshot()) {
    if (!state.tasks.includes(task)) return;
    const source = [...$("task-list").querySelectorAll(".task-swipe")].find(node => node.dataset.taskId === task.id);
    const height = source?.getBoundingClientRect().height;
    cancelTaskFinish(task.id);
    const index = state.tasks.indexOf(task);
    state.tasks[index] = next;
    state.taskUndo.push({ task, next, index, label, filter: task.archived ? "archived" : task.done ? "done" : "pending", history });
    const motion = celebrate && height && state.filter === "pending" && !$("page-tasks").hidden && !window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    const animation = motion ? { height, startedAt: performance.now(), phase: "hold", holdTimer: null, exitTimer: null } : null;
    if (animation) state.finishingTasks.set(task.id, animation);
    renderTasks();
    showTaskUndo();
    if (!animation) return;
    $("done-count").classList.remove("pulse");
    void $("done-count").offsetWidth;
    $("done-count").classList.add("pulse");
    animation.holdTimer = setTimeout(() => {
      if (state.finishingTasks.get(task.id) !== animation) return;
      animation.phase = "exit";
      const wrapper = [...$("task-list").querySelectorAll(".task-swipe.finishing")].find(node => node.dataset.taskId === task.id);
      if (wrapper) { void wrapper.offsetHeight; wrapper.classList.add("finishing-out"); }
    }, TODO_UI.finishHoldMs);
    animation.exitTimer = setTimeout(() => {
      if (state.finishingTasks.get(task.id) !== animation) return;
      state.finishingTasks.delete(task.id);
      renderTasks();
    }, TODO_UI.finishHoldMs + TODO_UI.finishCollapseMs);
  }

  function deleteTask(id) {
    const index = state.tasks.findIndex(task => task.id === id);
    if (index < 0) return;
    const [task] = state.tasks.splice(index, 1);
    cancelTaskFinish(id);
    state.taskUndo.push({ task, next: null, index, label: "已删除", filter: state.filter, history: taskHistorySnapshot() });
    renderTasks();
    showTaskUndo();
    $("undo-task").focus({ preventScroll: true });
  }

  function undoTaskAction() {
    const last = state.taskUndo.at(-1);
    if (!last) return;
    const current = state.tasks.find(task => task.id === last.task.id);
    if ((last.next && current !== last.next) || (!last.next && current)) {
      state.taskUndo.pop();
      showTaskUndo();
      return toast("这件事之后已有修改，为避免覆盖，未撤销该操作。");
    }
    if (!last.next && state.tasks.length >= LIMITS.maxTasks) return toast("清单已满，腾出一个位置后可撤销删除。");
    if (last.next) state.tasks = state.tasks.map(task => task.id === last.task.id ? last.task : task);
    else state.tasks.splice(Math.min(last.index, state.tasks.length), 0, last.task);
    state.taskUndo.pop();
    cancelTaskFinish(last.task.id);
    state.filter = last.filter;
    if (state.filter === "done") {
      if (last.history) {
        state.historyMonth = last.history.month;
        state.historyUndated = last.history.undated;
        state.taskSearch = last.history.query;
        Object.assign(taskHistoryView(), { limit: last.history.limit, scrollTop: last.history.scrollTop });
      } else {
        const date = FocusModel.completedTaskDate(last.task);
        state.taskSearch = "";
        state.historyUndated = !date;
        if (date) state.historyMonth = localDateKey(date).slice(0, 7);
      }
      $("task-search").value = state.taskSearch;
    }
    renderTasks();
    showTaskUndo();
    if (state.filter === "done") $("phone-content").scrollTop = taskHistoryView().scrollTop;
    const wrapper = [...$("task-list").querySelectorAll(".task-swipe")].find(node => node.dataset.taskId === last.task.id);
    (wrapper?.querySelector(".task-info") || document.querySelector(`[data-filter="${state.filter}"]`)).focus({ preventScroll: true });
  }

  function toggleTaskDone(task) {
    if (task.done || task.archived) return;
    // 勾掉父任务时把没走完的小步一并算走过，避免进度和状态互相矛盾。
    changeTask(task, FocusModel.completeTask(task), "已完成", true);
  }

  function toggleStep(task, step) {
    if (task.done || task.archived) return;
    // 小步全部走完，父任务随之完成；已完成的事须显式重新打开后才允许回退。
    const next = FocusModel.setTaskStep(task, step.id, !step.done);
    changeTask(task, next, next.done ? "已完成" : step.done ? "已退回一步" : "走过了一步", next.done);
    if (!step.done && !next.done) {
      const wrapper = [...$("task-list").querySelectorAll(".task-swipe")].find(node => node.dataset.taskId === task.id);
      const index = task.steps.findIndex(item => item.id === step.id);
      wrapper?.querySelectorAll(".step-dot")[index]?.classList.add("just-walked");
      wrapper?.querySelectorAll(".step-row")[index]?.classList.add("just-walked");
    }
  }

  function restoreTask(task) {
    state.filter = task.done ? "done" : "pending";
    changeTask(task, { ...task, archived: false, createdAt: localDateKey(new Date()) }, "已找回");
  }

  function taskDetails(id) {
    const task = state.tasks.find(item => item.id === id);
    if (!task) return;
    closeSwipes();
    const content = element("div", "task-detail");
    content.append(element("p", "data-note", `${task.category} · ${task.archived ? "已放下" : task.done ? "已完成" : "待完成"}`));
    if (task.done) content.append(element("p", "data-note", FocusModel.completedTaskDate(task) ? `完成于 ${localTimestamp(task.completedAt)}` : "这条历史没有记录完成时间。"));
    if (task.steps?.length) {
      const steps = element("ul", "task-detail-steps");
      task.steps.forEach(step => steps.append(element("li", "", `${step.done ? "已走过" : "还没走"} · ${step.title}`)));
      content.append(steps);
    }
    const actions = element("div", "task-detail-actions");
    actions.append(taskButton("编辑待办", "secondary-button", () => taskEditor(id)));
    if (task.archived) actions.append(taskButton("找回", "primary-button", () => { closeModal(); restoreTask(task); }));
    else if (task.done) actions.append(taskButton("重新打开", "secondary-button", () => reopenTaskDialog(task)));
    else {
      actions.append(taskButton(task.important ? "取消重要" : "标为重要", "secondary-button", () => { closeModal(); changeTask(task, { ...task, important: !task.important }, "已更新重要标记"); }));
      actions.append(taskButton("放下", "secondary-button", () => { closeModal(); changeTask(task, { ...task, archived: true }, "已放下"); }));
    }
    actions.append(taskButton("删除待办", "secondary-button danger-button", () => { closeModal(); deleteTask(id); }));
    content.append(actions, element("p", "data-note", "也可以左滑卡片，再点击删除。删除后可在底部撤销；关闭提示或刷新后不再保留撤销记录。"));
    showModal(task.title, content);
  }

  function reopenTaskDialog(task) {
    const form = element("form", "reopen-task-form");
    const selected = new Set();
    form.append(element("p", "modal-copy", task.steps?.length ? "选择要重做的小步，其余已走过的进度会保留。" : "确认后，这件事会回到待完成清单。"));
    (task.steps || []).forEach(step => {
      const row = element("label", "redo-step-row");
      const input = element("input");
      input.type = "checkbox";
      input.addEventListener("change", () => { if (input.checked) selected.add(step.id); else selected.delete(step.id); });
      row.append(input, document.createTextNode(step.title));
      form.append(row);
    });
    const error = element("p", "modal-notice");
    error.setAttribute("role", "alert");
    error.hidden = true;
    const actions = element("div", "modal-actions");
    actions.append(taskButton("取消", "secondary-button", () => taskDetails(task.id)));
    const confirm = element("button", "primary-button", "确认重新打开");
    confirm.type = "submit";
    actions.append(confirm);
    form.append(error, actions);
    form.addEventListener("submit", event => {
      event.preventDefault();
      try {
        const current = state.tasks.find(item => item.id === task.id);
        if (!current) throw new Error("这件待办已被删除，无法重新打开。");
        const next = FocusModel.reopenTask(current, [...selected]);
        const history = taskHistorySnapshot();
        closeModal();
        state.filter = "pending";
        state.taskExpanded.add(task.id);
        changeTask(current, next, "已重新打开", false, history);
      } catch (exception) { error.textContent = exception.message; error.hidden = false; }
    });
    showModal("重新打开这件事？", form);
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
    const ranking = focusBreakdown(records, "focusItemTitle");
    const maximum = ranking[0]?.minutes || 1;
    $("statistics-ranking").replaceChildren(...ranking.map((item, index) => {
      const row = element("li");
      const detail = element("div", "rank-detail");
      const heading = element("div", "rank-heading");
      heading.append(element("span", "", item.name), element("small", "", `${numberLabel(item.minutes)} 分钟 / ${item.count} 次`));
      const track = element("div", "rank-track");
      const fill = element("div");
      fill.style.width = `${item.minutes / maximum * 100}%`;
      track.append(fill);
      detail.append(heading, track);
      row.append(element("span", "rank-number", String(index + 1).padStart(2, "0")), detail);
      return row;
    }));
    if (!ranking.length) $("statistics-ranking").append(element("li", "data-note", "暂无专注项投入记录。"));
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
      heading.append(element("strong", "", record.focusItemTitle), element("span", "", formatTime(seconds)));
      row.append(heading, element("div", "record-meta", `${localDateKey(ended)} · ${startDate}${started.toLocaleTimeString("zh-CN", timeOptions)} — ${ended.toLocaleTimeString("zh-CN", timeOptions)}\n${TIMING_NAMES[record.timerMode || "countdown"]} · ${record.category} · ${record.source === "sample" ? "示例记录" : "本次计时"}`));
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

  document.documentElement.style.setProperty("--todo-finish-hold", `${TODO_UI.finishHoldMs}ms`);
  document.documentElement.style.setProperty("--todo-finish-collapse", `${TODO_UI.finishCollapseMs}ms`);
  window.matchMedia("(prefers-reduced-motion: reduce)").addEventListener("change", event => {
    if (!event.matches) return;
    [...state.finishingTasks.keys()].forEach(cancelTaskFinish);
    $("done-count").classList.remove("pulse");
    renderTasks();
  });
  $("done-count").addEventListener("animationend", () => $("done-count").classList.remove("pulse"));
  $("close-modal").addEventListener("click", closeModal);
  $("modal").addEventListener("close", () => {
    if (!$("modal").open) finishModalClose();
  });
  $("modal").addEventListener("click", event => {
    if (event.target !== $("modal")) return;
    const box = $("modal").getBoundingClientRect();
    if (event.clientX < box.left || event.clientX > box.right || event.clientY < box.top || event.clientY > box.bottom) closeModal();
  });
  document.querySelectorAll("[data-page]").forEach(button => button.addEventListener("click", () => {
    if (button.dataset.page === "diary" && !$("page-diary").hidden) reselectDiaryTab();
    else navigate(button.dataset.page);
  }));
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
  document.querySelectorAll("[data-filter]").forEach(button => button.addEventListener("click", () => changeTaskHistory(() => { state.filter = button.dataset.filter; })));
  $("phone-content").addEventListener("scroll", rememberTaskHistoryPosition, { passive: true });
  $("task-search").addEventListener("input", event => changeTaskHistory(() => {
    state.taskSearch = event.target.value.trim();
    state.historyViews.delete("search");
  }));
  $("diary-month-prev").addEventListener("click", () => shiftDiaryMonth(-1));
  $("diary-month-next").addEventListener("click", () => shiftDiaryMonth(1));
  $("diary-month-picker").addEventListener("click", diaryMonthPicker);
  $("diary-month-current").addEventListener("click", () => setDiaryMonth(localDateKey(new Date()).slice(0, 7)));
  $("diary-search").addEventListener("input", event => { state.diarySearch = event.target.value; closeSwipes(); renderDiary(); });
  $("diary-more").addEventListener("click", diaryMore);
  $("add-diary").addEventListener("click", startDiaryEntry);
  $("diary-trash-back").addEventListener("click", closeDiaryTrash);
  $("diary-detail-back").addEventListener("click", () => backToDiaryHome(state.diaryDetailId));
  $("diary-detail-edit").addEventListener("click", () => openDiaryEditor(state.diaryDetailId));
  $("diary-detail-delete").addEventListener("click", trashDiaryFromDetail);
  $("diary-editor-cancel").addEventListener("click", closeDiaryEditor);
  $("diary-editor-save").addEventListener("click", saveDiaryEditor);
  $("diary-add-photo").addEventListener("click", pickDiaryPhotos);
  $("diary-record").addEventListener("click", requestDiaryRecording);
  document.addEventListener("visibilitychange", () => { if (document.hidden && !$("page-diary").hidden) suspendDiaryEditor("退到后台时"); });
  $("diary-drafts-back").addEventListener("click", () => { backToDiaryHome(); $("diary-more").focus({ preventScroll: true }); });
  $("task-month-prev").addEventListener("click", () => shiftTaskHistoryMonth(-1));
  $("task-month-next").addEventListener("click", () => shiftTaskHistoryMonth(1));
  $("task-month-picker").addEventListener("click", taskMonthPicker);
  $("task-month-current").addEventListener("click", () => setTaskHistoryMonth(localDateKey(new Date()).slice(0, 7)));
  $("task-undated").addEventListener("click", () => changeTaskHistory(() => { state.historyUndated = true; }));
  $("task-known-history").addEventListener("click", () => changeTaskHistory(() => { state.historyUndated = false; }));
  $("task-history-more").addEventListener("click", () => {
    const scroll = $("phone-content").scrollTop;
    taskHistoryView().limit += TODO_UI.historyPage;
    renderTasks();
    $("phone-content").scrollTop = scroll;
  });
  $("task-help").addEventListener("click", () => {
    const content = element("div", "todo-guide-content");
    [
      ["拆成小步", "点进度展开，每一步整行都能勾选。"],
      ["删除与撤销", "左滑后点删除，可在底部撤销；关闭撤销提示后失效。"],
      ["回看与重做", "已完成按月查看，搜索覆盖全部时间；重做请到详情中「重新打开」。"],
      ["暂时放下", "放下不是删除，需要时可以找回。"],
    ].forEach(([title, description]) => {
      const section = element("section");
      section.append(element("h3", "", title), element("p", "", description));
      content.append(section);
    });
    content.append(taskButton("知道了", "primary-button", closeModal));
    showModal("待办怎么用", content, "todo-guide-sheet");
  });
  $("close-todo-sheet").addEventListener("click", closeTaskSheet);
  $("todo-sheet").addEventListener("cancel", event => { event.preventDefault(); closeTaskSheet(); });
  $("todo-sheet").addEventListener("close", () => {
    if ($("todo-sheet").open) return;
    if (!$("modal").open && !$("page-tasks").hidden) {
      const returnTo = state.taskEditorReturn;
      if (returnTo?.isConnected && returnTo.getClientRects().length) returnTo.focus({ preventScroll: true });
      else $("add-task").focus({ preventScroll: true });
    }
    queueMicrotask(pumpProgressPrompts);
  });
  $("todo-sheet").addEventListener("click", event => {
    if (event.target !== $("todo-sheet")) return;
    const bounds = $("todo-sheet").getBoundingClientRect();
    if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) closeTaskSheet();
  });
  window.visualViewport?.addEventListener("resize", syncTaskViewport);
  window.visualViewport?.addEventListener("scroll", syncTaskViewport);
  window.addEventListener("resize", syncTaskViewport);
  $("undo-task").addEventListener("click", undoTaskAction);
  $("dismiss-task-undo").addEventListener("click", () => {
    state.taskUndo = [];
    state.taskDrafts.forEach((draft, id) => { if (id !== TODO_UI.newDraft && !state.tasks.some(task => task.id === id)) state.taskDrafts.delete(id); });
    showTaskUndo();
    document.querySelector(`[data-filter="${state.filter}"]`).focus();
  });
  document.addEventListener("pointerdown", event => { if (!event.target.closest(".task-swipe.swipe-open")) closeSwipes(); });
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
  navigate(location.hash.slice(1), false);
  setInterval(tickTimer, UI.tickMs);
})();
