(function (root) {
  "use strict";

  const LIMITS = Object.freeze({
    secondsPerMinute: 60,
    millisecondsPerSecond: 1000,
    maxTaskTitle: 80,
    maxTasks: 100,
    maxFocusItems: 50,
    maxProgressNote: 2000,
    minTimerMinutes: 1,
    maxTimerMinutes: 120,
    minGoalMinutes: 30,
    maxGoalMinutes: 1440,
    maxEstimatedSessions: 12,
    maxTaskSteps: 8,
    maxTaskStepTitle: 40,
    restDays: 3,
    staleDays: 7,

    sampleFocusMinutes: 75,
    sampleSessions: 3,
  });
  const MODES = Object.freeze({ focus: 25, short: 5, long: 15 });
  const CATEGORIES = Object.freeze(["个人成长", "工作", "生活"]);
  const USAGE = Object.freeze({
    today: {
      total: 204,
      bars: [4, 2, 3, 8, 12, 19, 32, 46, 33, 25, 14, 6],
      labels: ["00:00", "06:00", "12:00", "18:00", "24:00"],
      apps: [
        { name: "微信", glyph: "聊", minutes: 72, color: "#f5e8dc", ink: "#b8753e" },
        { name: "哔哩哔哩", glyph: "哔", minutes: 58, color: "#f4e5e7", ink: "#bd8d9a" },
        { name: "浏览器", glyph: "览", minutes: 46, color: "#e2eaf0", ink: "#7594ac" },
        { name: "其他应用", glyph: "其", minutes: 28, color: "#ebe9df", ink: "#9d9981" },
      ],
    },
    week: {
      total: 1440,
      bars: [210, 180, 192, 198, 216, 240, 204],
      labels: ["一", "二", "三", "四", "五", "六", "日"],
      apps: [
        { name: "微信", glyph: "聊", minutes: 480, color: "#f5e8dc", ink: "#b8753e" },
        { name: "哔哩哔哩", glyph: "哔", minutes: 390, color: "#f4e5e7", ink: "#bd8d9a" },
        { name: "浏览器", glyph: "览", minutes: 330, color: "#e2eaf0", ink: "#7594ac" },
        { name: "其他应用", glyph: "其", minutes: 240, color: "#ebe9df", ink: "#9d9981" },
      ],
    },
  });

  /**
   * 示例待办。createdAt 为「放进清单的那天」，用于计算停留天数；
   * steps 是可选的一串小步，走过的步数即父任务进度。
   */
  function initialTasks(now = new Date()) {
    const dayBefore = offsetDays => localDateKey(addDays(now, -offsetDays));
    return [
      { id: "sample-reading", title: "阅读《原子习惯》", category: "个人成长", important: false, done: false,
        createdAt: dayBefore(2), steps: [] },
      { id: "sample-planning", title: "梳理个人 APP 的想法", category: "工作", important: true, done: false,
        createdAt: dayBefore(9), steps: [
          { id: "step-plan-1", title: "写下想解决的问题", done: true },
          { id: "step-plan-2", title: "画三张草图", done: true },
          { id: "step-plan-3", title: "选一个先做", done: false },
        ] },
      { id: "sample-walk", title: "傍晚出去走一走", category: "生活", important: false, done: false,
        createdAt: dayBefore(0), steps: [] },
      { id: "sample-desk", title: "整理书桌，清空杂念", category: "生活", important: false, done: true,
        createdAt: dayBefore(4), steps: [] },
    ];
  }

  function addDays(date, days) {
    const moved = new Date(date);
    moved.setDate(moved.getDate() + days);
    return moved;
  }

  /** 两个本地日期键相差的日历天数，不按 24 小时整除，避免夏令时偏差。 */
  function daysBetween(fromKey, toKey) {
    const from = parseDateKey(fromKey);
    const to = parseDateKey(toKey);
    if (!from || !to) return 0;
    return Math.round((to - from) / MILLISECONDS_PER_DAY);
  }

  function parseDateKey(key) {
    const parts = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(key ?? ""));
    if (!parts) return null;
    const date = new Date(Number(parts[1]), Number(parts[2]) - 1, Number(parts[3]));
    return Number.isNaN(date.getTime()) ? null : date;
  }

  function initialFocusItems() {
    return [
      { id: "focus-reading", title: "Spring Boot 实战", category: "个人成长", timerMode: "countdown", durationMinutes: 25 },
      { id: "focus-coding", title: "个人 APP 开发", category: "工作", timerMode: "countup", durationMinutes: 50 },
      { id: "focus-exercise", title: "算法与数据结构", category: "个人成长", timerMode: "countdown", durationMinutes: 15 },
    ];
  }

  function validateFocusConfiguration({ timerMode, durationMinutes }) {
    if (!["countdown", "countup"].includes(timerMode)) throw new Error("请选择有效的计时方式。");
    if (!integerInRange(durationMinutes, LIMITS.minTimerMinutes, LIMITS.maxTimerMinutes)) {
      throw new Error("每段时长需为 1–120 的整数分钟。");
    }
    return { timerMode, durationMinutes: Number(durationMinutes) };
  }

  function validateFocusItem(input) {
    const title = String(input.title ?? "").trim();
    if (!title || title.length > LIMITS.maxTaskTitle) throw new Error(`专注项名称需为 1–${LIMITS.maxTaskTitle} 个字符。`);
    if (!CATEGORIES.includes(input.category)) throw new Error("请选择有效的专注分类。");
    return { title, category: input.category, ...validateFocusConfiguration(input) };
  }

  function validateProgress({ note, percent }) {
    const trimmedNote = String(note ?? "").trim();
    if (!trimmedNote || trimmedNote.length > LIMITS.maxProgressNote) throw new Error(`学习进度需为 1–${LIMITS.maxProgressNote} 个字符。`);
    const emptyPercent = percent == null || (typeof percent === "string" && percent.trim() === "");
    if (!emptyPercent && (!["number", "string"].includes(typeof percent) || !integerInRange(percent, 0, 100))) {
      throw new Error("完成度需为 0–100 的整数，或留空。");
    }
    return { note: trimmedNote, percent: emptyPercent ? null : Number(percent) };
  }

  function integerInRange(value, min, max) {
    const number = Number(value);
    return Number.isInteger(number) && number >= min && number <= max;
  }

  function validateTask(input) {
    const title = String(input.title ?? "").trim();
    if (!title || title.length > LIMITS.maxTaskTitle) throw new Error(`任务名称需为 1–${LIMITS.maxTaskTitle} 个字符。`);
    if (!CATEGORIES.includes(input.category)) throw new Error("请选择有效的任务分类。");
    return { title, category: input.category, important: Boolean(input.important), steps: validateSteps(input.steps) };
  }

  /** 小步可以一个都没有；有则每步要有名字，且不超过上限。 */
  function validateSteps(input) {
    const list = Array.isArray(input) ? input : [];
    if (list.length > LIMITS.maxTaskSteps) throw new Error(`每件事最多 ${LIMITS.maxTaskSteps} 个小步。`);
    return list.map(step => {
      const title = String(step?.title ?? "").trim();
      if (!title || title.length > LIMITS.maxTaskStepTitle) {
        throw new Error(`小步名称需为 1–${LIMITS.maxTaskStepTitle} 个字符。`);
      }
      return { id: String(step?.id ?? ""), title, done: Boolean(step?.done) };
    });
  }

  /**
   * L：一件事在清单里待了多久。fresh 今天放进来，resting 还新鲜，
   * stale 躺得久了。已完成的事不参与发酵。
   */
  function taskAge(task, today = localDateKey(new Date())) {
    const days = Math.max(0, daysBetween(task?.createdAt, today));
    if (task?.done) return { days, stage: "done", label: "已完成" };
    if (days <= 0) return { days, stage: "fresh", label: "今天放进来的" };
    if (days < LIMITS.restDays) return { days, stage: "fresh", label: `躺了 ${days} 天` };
    if (days < LIMITS.staleDays) return { days, stage: "resting", label: `躺了 ${days} 天` };
    return { days, stage: "stale", label: `躺了 ${days} 天` };
  }

  /** M：走过的小步即进度。没有小步的事只有做完与没做完两种状态。 */
  function taskProgress(task) {
    const steps = Array.isArray(task?.steps) ? task.steps : [];
    const total = steps.length;
    const walked = steps.filter(step => step.done).length;
    if (!total) return { total: 0, walked: 0, percent: task?.done ? 100 : 0 };
    return { total, walked, percent: Math.round(walked / total * 100) };
  }

  /** 下一步未走的小步，用于「从这一步开始专注」。 */
  function nextStep(task) {
    return (Array.isArray(task?.steps) ? task.steps : []).find(step => !step.done) || null;
  }

  /** 专注记录里保留的是「父任务 · 这一步」，子步骤不单独入账。 */
  function stepFocusTitle(task, step) {
    const parent = String(task?.title ?? "").trim();
    const child = String(step?.title ?? "").trim();
    return child ? `${parent} · ${child}` : parent;
  }

  function taskSummary(tasks) {
    const total = tasks.length;
    const done = tasks.filter(task => task.done).length;
    return { total, done, percent: total ? Math.round(done / total * 100) : 0 };
  }

  function formatTime(seconds) {
    const safeSeconds = Math.max(0, Math.ceil(seconds));
    const minutes = Math.floor(safeSeconds / LIMITS.secondsPerMinute);
    return `${String(minutes).padStart(2, "0")}:${String(safeSeconds % LIMITS.secondsPerMinute).padStart(2, "0")}`;
  }

  const DAYS_PER_WEEK = 7;
  const HOURS_PER_DAY = 24;
  const MONTHS_PER_YEAR = 12;
  const SAMPLE_HISTORY_DAYS = 60;
  const SAMPLE_START_HOURS = Object.freeze([9, 12, 15, 18]);
  const SAMPLE_DURATIONS = Object.freeze([15, 25, 35, 45]);
  const MILLISECONDS_PER_MINUTE = LIMITS.secondsPerMinute * LIMITS.millisecondsPerSecond;
  const MILLISECONDS_PER_DAY = HOURS_PER_DAY * LIMITS.secondsPerMinute * MILLISECONDS_PER_MINUTE;
  const MINUTE_PRECISION = 1e9;

  function roundMinutes(minutes) {
    return Math.round(minutes * MINUTE_PRECISION) / MINUTE_PRECISION;
  }

  function recordSeconds(record) {
    return Number.isFinite(record.durationSeconds) ? record.durationSeconds : record.durationMinutes * LIMITS.secondsPerMinute;
  }

  function recordMinutes(record) {
    return recordSeconds(record) / LIMITS.secondsPerMinute;
  }

  function padDatePart(value) {
    return String(value).padStart(2, "0");
  }

  function requireDate(date) {
    if (!(date instanceof Date) || !Number.isFinite(date.getTime())) throw new Error("请选择有效的日期。");
  }

  function localDateKey(date) {
    requireDate(date);
    return `${String(date.getFullYear()).padStart(4, "0")}-${padDatePart(date.getMonth() + 1)}-${padDatePart(date.getDate())}`;
  }

  function periodRange(period, anchor) {
    requireDate(anchor);
    if (!["day", "week", "month", "year"].includes(period)) throw new Error("请选择有效的统计周期。");
    const start = new Date(anchor);
    start.setHours(0, 0, 0, 0);
    if (period === "week") start.setDate(start.getDate() - (start.getDay() + DAYS_PER_WEEK - 1) % DAYS_PER_WEEK);
    if (period === "month" || period === "year") start.setDate(1);
    if (period === "year") start.setMonth(0);
    const end = new Date(start);
    if (period === "day") end.setDate(end.getDate() + 1);
    if (period === "week") end.setDate(end.getDate() + DAYS_PER_WEEK);
    if (period === "month") end.setMonth(end.getMonth() + 1);
    if (period === "year") end.setFullYear(end.getFullYear() + 1);
    return { start, end };
  }

  function initialFocusRecords(now = new Date()) {
    const { start } = periodRange("day", now);
    const tasks = initialTasks();
    const focusItems = initialFocusItems();
    const records = [];
    for (let offset = 0; offset < SAMPLE_HISTORY_DAYS; offset += 1) {
      if (offset > 0 && offset % DAYS_PER_WEEK === 0) continue;
      const day = new Date(start);
      day.setDate(day.getDate() - offset);
      const sessions = offset === 0 ? LIMITS.sampleSessions : offset % SAMPLE_START_HOURS.length + 1;
      for (let session = 0; session < sessions; session += 1) {
        const task = tasks[(offset + session) % tasks.length];
        const focusItem = focusItems[(offset + session) % focusItems.length];
        const durationMinutes = offset === 0 ? MODES.focus : SAMPLE_DURATIONS[(offset + session) % SAMPLE_DURATIONS.length];
        const startedAt = new Date(day);
        startedAt.setHours(SAMPLE_START_HOURS[session], 0, 0, 0);
        const endedAt = new Date(startedAt.getTime() + durationMinutes * MILLISECONDS_PER_MINUTE);
        records.push({
          id: `sample-focus-${localDateKey(day)}-${session + 1}`,
          taskId: task.id,
          taskTitle: task.title,
          category: focusItem.category,
          startedAt: startedAt.toISOString(),
          endedAt: endedAt.toISOString(),
          durationMinutes,
          source: "sample",
          timerMode: "countdown",
          focusItemTitle: focusItem.title,
          focusItemId: focusItem.id,
          targetMinutes: durationMinutes,
          durationSeconds: durationMinutes * LIMITS.secondsPerMinute,
          segments: [{ startedAt: startedAt.toISOString(), endedAt: endedAt.toISOString() }],
        });
      }
    }
    return records;
  }

  function initialProgressEntries(records) {
    const samples = [
      { focusItemId: "focus-reading", note: "完成登录接口练习，理解 JWT 签发与校验，下一步接入权限控制。", percent: 35 },
      { focusItemId: "focus-coding", note: "整理项目首页与专注记录的 UI 规划，下一步实现进度编辑。", percent: 20 },
      { focusItemId: "focus-exercise", note: "完成数组与链表基础练习，复盘双指针算法，下一步学习栈与队列。", percent: 15 },
    ];
    return samples.flatMap(sample => {
      const record = records.filter(record => record.source === "sample" && record.focusItemId === sample.focusItemId)
        .reduce((latest, record) => !latest || Date.parse(record.endedAt) > Date.parse(latest.endedAt) ? record : latest, null);
      return record ? [{
        id: `sample-progress-${sample.focusItemId}`, recordId: record.id, focusItemId: sample.focusItemId,
        recordEndedAt: record.endedAt, note: sample.note, percent: sample.percent, updatedAt: record.endedAt,
      }] : [];
    });
  }

  function latestProjectProgress(entries, focusItemId) {
    return entries.reduce((latest, entry) => {
      if (entry.focusItemId !== focusItemId) return latest;
      if (!latest) return entry;
      const completionDifference = Date.parse(entry.recordEndedAt) - Date.parse(latest.recordEndedAt);
      return completionDifference > 0 || (completionDifference === 0 && Date.parse(entry.updatedAt) >= Date.parse(latest.updatedAt)) ? entry : latest;
    }, null);
  }

  function selectFocusRecords(records, { period, anchor, includeSamples = true }) {
    const { start, end } = periodRange(period, anchor);
    return records.filter(record => {
      const completedAt = new Date(record.endedAt);
      return (includeSamples || record.source !== "sample") && completedAt >= start && completedAt < end;
    }).sort((left, right) => Date.parse(right.endedAt) - Date.parse(left.endedAt));
  }

  function focusSummary(records) {
    const minutes = roundMinutes(records.reduce((sum, record) => sum + recordSeconds(record), 0) / LIMITS.secondsPerMinute);
    const activeDays = new Set(records.map(record => localDateKey(new Date(record.endedAt)))).size;
    return { minutes, count: records.length, activeDays, averageMinutes: activeDays ? roundMinutes(minutes / activeDays) : 0 };
  }

  function calendarOrdinal(date) {
    requireDate(date);
    return Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()) / MILLISECONDS_PER_DAY;
  }

  function allTimeFocusSummary(records, now = new Date()) {
    const today = calendarOrdinal(now);
    const completed = records.filter(record => calendarOrdinal(new Date(record.endedAt)) <= today);
    const { minutes, count, activeDays, averageMinutes } = focusSummary(completed);
    let first = null;
    let last = null;
    for (const record of completed) {
      const endedAt = new Date(record.endedAt);
      if (!first || endedAt < first) first = endedAt;
      if (!last || endedAt > last) last = endedAt;
    }
    const calendarDays = first ? today - calendarOrdinal(first) + 1 : 0;
    return {
      minutes, count, activeDays, calendarDays,
      calendarAverageMinutes: calendarDays ? roundMinutes(minutes / calendarDays) : 0,
      activeAverageMinutes: averageMinutes,
      firstDate: first ? localDateKey(first) : null, lastDate: last ? localDateKey(last) : null,
    };
  }

  function trendKey(date, period) {
    if (period === "day") return padDatePart(date.getHours());
    const key = localDateKey(date);
    return period === "year" ? key.slice(0, 7) : key;
  }

  function focusTrend(records, period, anchor) {
    const { start, end } = periodRange(period, anchor);
    const bins = [];
    if (period === "day") {
      for (let hour = 0; hour < HOURS_PER_DAY; hour += 1) {
        const key = padDatePart(hour);
        bins.push({ key, label: `${key}:00`, minutes: 0 });
      }
    } else if (period === "year") {
      for (let month = 0; month < MONTHS_PER_YEAR; month += 1) {
        const date = new Date(start);
        date.setMonth(month);
        bins.push({ key: trendKey(date, period), label: padDatePart(month + 1), minutes: 0 });
      }
    } else {
      for (const date = new Date(start); date < end; date.setDate(date.getDate() + 1)) {
        const key = localDateKey(date);
        bins.push({ key, label: period === "week" ? key.slice(5) : padDatePart(date.getDate()), minutes: 0 });
      }
    }
    const byKey = new Map(bins.map(bin => [bin.key, bin]));
    for (const record of selectFocusRecords(records, { period, anchor })) {
      byKey.get(trendKey(new Date(record.endedAt), period)).minutes += recordMinutes(record);
    }
    return bins.map(bin => ({ ...bin, minutes: roundMinutes(bin.minutes) }));
  }

  function activeSegments(record) {
    const segments = Array.isArray(record.segments) ? record.segments.map(segment => ({
      start: Date.parse(segment.startedAt), end: Date.parse(segment.endedAt),
    })).filter(segment => Number.isFinite(segment.start) && Number.isFinite(segment.end) && segment.end > segment.start) : [];
    if (segments.length) return segments;
    const start = Date.parse(record.startedAt);
    const end = Date.parse(record.endedAt);
    const durationMs = recordSeconds(record) * LIMITS.millisecondsPerSecond;
    if (Number.isFinite(start)) return [{ start, end: Number.isFinite(end) && end > start ? end : start + durationMs }];
    if (Number.isFinite(end)) return [{ start: end - durationMs, end }];
    return [];
  }

  function focusHourDistribution(records) {
    const bins = Array.from({ length: HOURS_PER_DAY }, (_, hour) => {
      const key = padDatePart(hour);
      return { key, label: `${key}:00`, minutes: 0 };
    });
    for (const record of records) {
      const minutes = recordMinutes(record);
      if (!Number.isFinite(minutes) || minutes <= 0) continue;
      const segments = activeSegments(record);
      const activeMs = segments.reduce((sum, segment) => sum + segment.end - segment.start, 0);
      if (!activeMs) continue;
      for (const segment of segments) {
        for (let cursor = segment.start; cursor < segment.end;) {
          const date = new Date(cursor);
          const nextMinute = cursor + MILLISECONDS_PER_MINUTE - date.getSeconds() * LIMITS.millisecondsPerSecond - date.getMilliseconds();
          const end = Math.min(segment.end, nextMinute);
          bins[date.getHours()].minutes += (end - cursor) / activeMs * minutes;
          cursor = end;
        }
      }
    }
    const total = roundMinutes(bins.reduce((sum, bin) => sum + bin.minutes, 0));
    const rounded = bins.map(bin => ({ ...bin, minutes: roundMinutes(bin.minutes) }));
    const largest = rounded.reduce((left, right) => left.minutes >= right.minutes ? left : right);
    largest.minutes = roundMinutes(largest.minutes + total - rounded.reduce((sum, bin) => sum + bin.minutes, 0));
    return rounded;
  }

  function focusBreakdown(records, field) {
    if (!["category", "taskTitle"].includes(field)) throw new Error("请选择分类或任务分组。");
    const groups = new Map();
    for (const record of records) {
      const name = record[field];
      if (!groups.has(name)) groups.set(name, { name, minutes: 0, count: 0 });
      const group = groups.get(name);
      group.minutes += recordMinutes(record);
      group.count += 1;
    }
    return Array.from(groups.values(), group => ({ ...group, minutes: roundMinutes(group.minutes) })).sort((left, right) => right.minutes - left.minutes);
  }

  function monthActivity(records, anchor) {
    const { start, end } = periodRange("month", anchor);
    const days = [];
    for (const date = new Date(start); date < end; date.setDate(date.getDate() + 1)) {
      days.push({ date: localDateKey(date), minutes: 0, count: 0 });
    }
    const byDate = new Map(days.map(day => [day.date, day]));
    for (const record of selectFocusRecords(records, { period: "month", anchor })) {
      const day = byDate.get(localDateKey(new Date(record.endedAt)));
      day.minutes += recordMinutes(record);
      day.count += 1;
    }
    return days.map(day => ({ ...day, minutes: roundMinutes(day.minutes) }));
  }

  function csvField(value) {
    let text = String(value ?? "");
    if (/^[\s\u0000-\u001f]*[=+\-@]/.test(text)) text = `'${text}`;
    return `"${text.replaceAll('"', '""')}"`;
  }

  function localDateTime(date) {
    return `${localDateKey(date)} ${padDatePart(date.getHours())}:${padDatePart(date.getMinutes())}:${padDatePart(date.getSeconds())}`;
  }

  function exportFocusCsv(records) {
    const rows = [["日期", "任务", "分类", "开始时间", "结束时间", "时长（分钟）", "来源", "计时模式", "专注项", "目标时长（分钟）", "时长（秒）", "学习进度", "完成度（%）", "进度更新时间"]];
    for (const record of records) {
      const endedAt = new Date(record.endedAt);
      rows.push([
        localDateKey(endedAt), record.taskTitle, record.category,
        localDateTime(new Date(record.startedAt)), localDateTime(endedAt), record.durationMinutes,
        record.source === "sample" ? "示例" : "专注计时",
        record.timerMode === "countup" ? "正计时" : "倒计时", record.focusItemTitle ?? record.taskTitle,
        record.targetMinutes ?? record.durationMinutes, recordSeconds(record),
        record.progress?.note, record.progress?.percent, record.progress?.updatedAt,
      ]);
    }
    return `\uFEFF${rows.map(row => row.map(csvField).join(",")).join("\r\n")}\r\n`;
  }

  class Timer {
    constructor(now = () => performance.now()) {
      this.now = now;
      this.durations = { ...MODES };
      this.mode = "focus";
      this.timerMode = "countdown";
      this.reset();
    }

    get totalMs() {
      return this.durations[this.mode] * MILLISECONDS_PER_MINUTE;
    }

    get isCountupFocus() {
      return this.mode === "focus" && this.timerMode === "countup";
    }

    get elapsedMs() {
      const elapsed = this._elapsedMs + (this.running ? Math.max(0, this.now() - this._startedAt) : 0);
      this._lastElapsedMs = Math.max(this._lastElapsedMs, this.isCountupFocus ? elapsed : Math.min(this.totalMs, elapsed));
      return this._lastElapsedMs;
    }

    get elapsedSeconds() {
      return Math.floor(this.elapsedMs / LIMITS.millisecondsPerSecond);
    }

    get hasProgress() {
      return this.elapsedMs > 0;
    }

    get targetReached() {
      return this.isCountupFocus && this.elapsedMs >= this.totalMs;
    }

    get remainingMs() {
      return Math.max(0, this.totalMs - this.elapsedMs);
    }

    set remainingMs(value) {
      this._elapsedMs = Math.max(0, Math.min(this.totalMs, this.totalMs - value));
      this._lastElapsedMs = this._elapsedMs;
      if (this.running) {
        this._startedAt = this.now();
        this.deadline = this._startedAt + Math.max(0, value);
      }
    }

    get remainingSeconds() {
      return Math.ceil(this.remainingMs / LIMITS.millisecondsPerSecond);
    }

    start() {
      if (this.running || this.completed) return false;
      this._startedAt = this.now();
      this.deadline = this._startedAt + this.remainingMs;
      this.running = true;
      return true;
    }

    pause() {
      if (!this.running) return;
      this._elapsedMs = this.elapsedMs;
      this._startedAt = null;
      this.deadline = null;
      this.running = false;
    }

    tick() {
      if (!this.running || this.isCountupFocus || this.elapsedMs < this.totalMs) return null;
      const result = { mode: this.mode, minutes: this.durations[this.mode] };
      this.pause();
      this.completed = true;
      return result;
    }

    finish() {
      if (!this.isCountupFocus || this.completed || this.elapsedSeconds < 1) return null;
      this.pause();
      this.completed = true;
      return { mode: "focus", minutes: this.elapsedSeconds / LIMITS.secondsPerMinute };
    }

    reset() {
      this.running = false;
      this.deadline = null;
      this._elapsedMs = 0;
      this._lastElapsedMs = 0;
      this._startedAt = null;
      this.completed = false;
    }

    configureFocus(input) {
      const { timerMode, durationMinutes } = validateFocusConfiguration(input);
      this.durations.focus = durationMinutes;
      this.timerMode = timerMode;
      this.mode = "focus";
      this.reset();
    }

    selectMode(mode) {
      if (!Object.hasOwn(this.durations, mode)) throw new Error("未知计时模式。");
      if (this.mode === mode) return;
      this.mode = mode;
      this.reset();
    }

    setDurations(durations) {
      for (const key of Object.keys(MODES)) {
        if (!integerInRange(durations[key], LIMITS.minTimerMinutes, LIMITS.maxTimerMinutes)) {
          throw new Error("每段时长需为 1–120 的整数分钟。");
        }
      }
      this.durations = Object.fromEntries(Object.keys(MODES).map(key => [key, Number(durations[key])]));
      this.reset();
    }
  }

  const api = Object.freeze({
    LIMITS, MODES, CATEGORIES, USAGE, initialTasks, initialFocusItems, integerInRange, validateTask, validateSteps, validateFocusItem, taskSummary, taskAge, taskProgress, nextStep, stepFocusTitle, daysBetween, formatTime, Timer,
    localDateKey, periodRange, initialFocusRecords, selectFocusRecords, focusSummary, focusTrend, focusHourDistribution, focusBreakdown, monthActivity, exportFocusCsv,
    validateProgress, initialProgressEntries, latestProjectProgress, allTimeFocusSummary,
  });
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.FocusModel = api;
})(globalThis);
