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
      nightMinutes: 185,
      bars: [210, 180, 192, 198, 216, 240, 204],
      labels: ["一", "二", "三", "四", "五", "六", "日"],
      apps: [
        { name: "微信", glyph: "聊", minutes: 480, color: "#f5e8dc", ink: "#b8753e" },
        { name: "哔哩哔哩", glyph: "哔", minutes: 390, color: "#f4e5e7", ink: "#bd8d9a" },
        { name: "浏览器", glyph: "览", minutes: 330, color: "#e2eaf0", ink: "#7594ac" },
        { name: "其他应用", glyph: "其", minutes: 240, color: "#ebe9df", ink: "#9d9981" },
      ],
    },
    night: { startHour: 23, endHour: 5 },
  });
  /**
   * 今日示例用屏片段 [开始分钟, 时长, 应用]，相对今天 0 点；负数表示昨晚。
   * 每个应用的片段之和、每两小时之和分别与 USAGE.today.apps / bars 对齐，单测校验。
   */
  const SAMPLE_USAGE = Object.freeze([
    [-35, 25, "微信"],
    [10, 4, "哔哩哔哩"], [150, 2, "其他应用"], [300, 3, "浏览器"],
    [400, 8, "微信"], [560, 12, "浏览器"], [640, 19, "微信"],
    [760, 20, "哔哩哔哩"], [800, 12, "微信"], [880, 26, "浏览器"], [930, 20, "哔哩哔哩"],
    [1000, 14, "其他应用"], [1030, 19, "微信"], [1100, 14, "哔哩哔哩"], [1130, 11, "微信"],
    [1230, 9, "其他应用"], [1260, 5, "浏览器"], [1340, 3, "微信"], [1380, 3, "其他应用"],
  ]);

  /**
   * 示例待办。createdAt 为「放进清单的那天」，用于计算停留天数；
   * steps 是可选的一串小步，走过的步数即父任务进度。
   */
  function initialTasks(now = new Date()) {
    const dayBefore = offsetDays => localDateKey(addDays(now, -offsetDays));
    return [
      { id: "sample-reading", title: "阅读《原子习惯》", category: "个人成长", important: false, done: false,
        createdAt: dayBefore(2), completedAt: null, steps: [] },
      { id: "sample-planning", title: "梳理个人 APP 的想法", category: "工作", important: true, done: false,
        createdAt: dayBefore(9), completedAt: null, steps: [
          { id: "step-plan-1", title: "写下想解决的问题", done: true },
          { id: "step-plan-2", title: "画三张草图", done: true },
          { id: "step-plan-3", title: "选一个先做", done: false },
        ] },
      { id: "sample-walk", title: "傍晚出去走一走", category: "生活", important: false, done: false,
        createdAt: dayBefore(0), completedAt: null, steps: [] },
      { id: "sample-desk", title: "整理书桌，清空杂念", category: "生活", important: false, done: true,
        createdAt: dayBefore(4), completedAt: addDays(now, -1).toISOString(), steps: [] },
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
    const year = Number(parts[1]);
    const month = Number(parts[2]);
    const day = Number(parts[3]);
    const date = new Date(year, month - 1, day);
    return !year || month < 1 || month > 12 || day < 1 || Number.isNaN(date.getTime())
      || date.getFullYear() !== year || date.getMonth() !== month - 1 || date.getDate() !== day ? null : date;
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

  /** 下一步未走的小步，用于显示待办的小路提示。 */
  function nextStep(task) {
    return (Array.isArray(task?.steps) ? task.steps : []).find(step => !step.done) || null;
  }

  /**
   * 待办分组：标为重要的事跨分类自成一组排在最前，其余按 CATEGORIES 顺序分组。
   * 已完成的事沉到各组末尾，同组内保持原有先后顺序。空组不返回。
   */
  function groupTasks(tasks) {
    const list = Array.isArray(tasks) ? tasks : [];
    const buckets = [{ key: "important", label: "重要", tasks: [] }]
      .concat(CATEGORIES.map(name => ({ key: `category:${name}`, label: name, tasks: [] })));
    const byKey = new Map(buckets.map(bucket => [bucket.key, bucket]));
    list.forEach(task => {
      const bucket = task?.important ? byKey.get("important") : byKey.get(`category:${task?.category}`);
      // 分类被改坏的事不能凭空消失，归到最后一组兜底。
      (bucket || buckets[buckets.length - 1]).tasks.push(task);
    });
    buckets.forEach(bucket => {
      bucket.tasks.sort((left, right) => Number(Boolean(left.done)) - Number(Boolean(right.done)));
    });
    return buckets.filter(bucket => bucket.tasks.length > 0);
  }

  function completeTask(task, now = new Date()) {
    if (!task.done) requireDate(now);
    const steps = (Array.isArray(task.steps) ? task.steps : []).map(step => ({ ...step, done: true }));
    return { ...task, done: true, completedAt: task.done ? task.completedAt : now.toISOString(), steps };
  }

  function setTaskStep(task, stepId, done, now = new Date()) {
    if (task.done) throw new Error("请先重新打开已完成的事项。");
    const list = Array.isArray(task.steps) ? task.steps : [];
    if (!list.some(step => step.id === stepId)) throw new Error("请选择有效的小步。");
    const steps = list.map(step => ({ ...step, done: step.id === stepId ? Boolean(done) : step.done }));
    const completed = steps.every(step => step.done);
    if (completed) requireDate(now);
    return { ...task, steps, done: completed, completedAt: completed ? now.toISOString() : null };
  }

  function reopenTask(task, redoStepIds = []) {
    const list = Array.isArray(task.steps) ? task.steps : [];
    const redo = new Set(Array.isArray(redoStepIds) ? redoStepIds : []);
    if (task.done && list.length && !list.some(step => redo.has(step.id))) {
      throw new Error("请至少选择一个需要重做的小步。");
    }
    const steps = list.map(step => ({ ...step, done: redo.has(step.id) ? false : step.done }));
    return { ...task, done: false, completedAt: null, steps };
  }

  function completionDate(value) {
    if (typeof value !== "string") return null;
    const parts = /^(\d{4})-(\d{2})-(\d{2})T(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.exec(value);
    if (!parts) return null;
    const calendar = new Date(0);
    calendar.setUTCFullYear(Number(parts[1]), Number(parts[2]) - 1, Number(parts[3]));
    if (calendar.getUTCFullYear() !== Number(parts[1]) || calendar.getUTCMonth() + 1 !== Number(parts[2]) || calendar.getUTCDate() !== Number(parts[3])) return null;
    const date = new Date(value);
    return Number.isFinite(date.getTime()) ? date : null;
  }

  function groupCompletedTasks(tasks, today = new Date()) {
    const todayKey = localDateKey(today);
    const yesterdayKey = localDateKey(addDays(today, -1));
    const list = (Array.isArray(tasks) ? tasks : []).filter(task => task?.done && !task.archived).map(task => {
      const date = completionDate(task.completedAt);
      return { task, date: date && date <= today ? date : null };
    });
    list.sort((left, right) => (right.date?.getTime() ?? -Infinity) - (left.date?.getTime() ?? -Infinity));
    const groups = new Map();
    for (const { task, date } of list) {
      const day = date ? localDateKey(date) : null;
      const key = !day ? "unknown" : day === todayKey ? "today" : day === yesterdayKey ? "yesterday" : day.slice(0, 7);
      const label = key === "unknown" ? "完成时间未记录" : key === "today" ? "今天" : key === "yesterday" ? "昨天" : `${date.getFullYear()}年${date.getMonth() + 1}月`;
      if (!groups.has(key)) groups.set(key, { key, label, tasks: [] });
      groups.get(key).tasks.push(task);
    }
    return Array.from(groups.values());
  }

  function completedTaskDate(task, now = new Date()) {
    requireDate(now);
    const date = completionDate(task?.completedAt);
    return date && date <= now ? date : null;
  }

  const COMPLETED_PAGE_SIZE = 20;

  function selectCompletedTasks(tasks, { month, query = "", undated = false, limit = COMPLETED_PAGE_SIZE, now = new Date() } = {}) {
    const todayKey = localDateKey(now);
    const currentMonth = todayKey.slice(0, 7);
    const selectedMonth = month === undefined ? currentMonth : month;
    if (typeof selectedMonth !== "string" || !/^\d{4}-(?:0[1-9]|1[0-2])$/.test(selectedMonth) || selectedMonth > currentMonth) {
      throw new Error("请选择有效且不晚于当月的月份。");
    }
    if (!Number.isInteger(limit) || limit <= 0) throw new Error("加载条数必须为正整数。");
    const search = String(query ?? "").trim().toLowerCase();
    const list = (Array.isArray(tasks) ? tasks : []).filter(task => task?.done && !task.archived).map(task => {
      const date = completedTaskDate(task, now);
      return { task, date, day: date ? localDateKey(date) : null };
    });
    const monthCounts = new Map();
    let undatedCount = 0;
    for (const { day } of list) {
      if (!day) undatedCount += 1;
      else {
        const key = day.slice(0, 7);
        monthCounts.set(key, (monthCounts.get(key) || 0) + 1);
      }
    }
    const months = Array.from(monthCounts, ([key, count]) => ({ key, count })).sort((left, right) => right.key.localeCompare(left.key));
    const filtered = list.filter(({ task, day }) => {
      if (search) {
        const titles = (Array.isArray(task.steps) ? task.steps : []).map(step => step?.title);
        return [task.title, task.category, ...titles].some(value => String(value ?? "").toLowerCase().includes(search));
      }
      return undated ? !day : day?.slice(0, 7) === selectedMonth;
    });
    filtered.sort((left, right) => (right.date?.getTime() ?? -Infinity) - (left.date?.getTime() ?? -Infinity));
    const loaded = filtered.slice(0, limit);
    const yesterdayKey = localDateKey(addDays(now, -1));
    const groups = new Map();
    for (const { task, date, day } of loaded) {
      const key = day || "unknown";
      if (!groups.has(key)) {
        const label = !date ? "完成时间未记录" : day === todayKey ? "今天" : day === yesterdayKey ? "昨天"
          : `${search ? `${date.getFullYear()}年` : ""}${date.getMonth() + 1}月${date.getDate()}日`;
        groups.set(key, { key, label, tasks: [] });
      }
      groups.get(key).tasks.push(task);
    }
    return { groups: Array.from(groups.values()), total: filtered.length, shown: loaded.length, months, undatedCount, latestMonth: months[0]?.key ?? null };
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
    const focusItems = initialFocusItems();
    const records = [];
    for (let offset = 0; offset < SAMPLE_HISTORY_DAYS; offset += 1) {
      if (offset > 0 && offset % DAYS_PER_WEEK === 0) continue;
      const day = new Date(start);
      day.setDate(day.getDate() - offset);
      const sessions = offset === 0 ? LIMITS.sampleSessions : offset % SAMPLE_START_HOURS.length + 1;
      for (let session = 0; session < sessions; session += 1) {
        const focusItem = focusItems[(offset + session) % focusItems.length];
        const durationMinutes = offset === 0 ? MODES.focus : SAMPLE_DURATIONS[(offset + session) % SAMPLE_DURATIONS.length];
        const startedAt = new Date(day);
        startedAt.setHours(SAMPLE_START_HOURS[session], 0, 0, 0);
        const endedAt = new Date(startedAt.getTime() + durationMinutes * MILLISECONDS_PER_MINUTE);
        records.push({
          id: `sample-focus-${localDateKey(day)}-${session + 1}`,
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

  function sampleUsageSegments(now = new Date()) {
    const { start } = periodRange("day", now);
    return SAMPLE_USAGE.map(([minute, minutes, app]) => {
      const startedAt = new Date(start);
      startedAt.setMinutes(minute);
      const endedAt = new Date(startedAt);
      endedAt.setMinutes(endedAt.getMinutes() + minutes);
      return { app, startedAt: startedAt.toISOString(), endedAt: endedAt.toISOString() };
    });
  }

  /** 专注记录按真实活动片段裁剪到 [start, end)，返回按开始时间排序的 [from, to] 毫秒区间。 */
  function focusSpans(records, start, end) {
    return records.flatMap(record => (record.segments?.length ? record.segments : [record])
      .map(segment => [Math.max(start.getTime(), Date.parse(segment.startedAt)), Math.min(end.getTime(), Date.parse(segment.endedAt))])
      .filter(([from, to]) => to > from))
      .sort((left, right) => left[0] - right[0]);
  }

  /**
   * 一天的用屏汇总：总时长与应用分项按本地日历日裁剪；夜间足迹取前一晚 23 点至当天 5 点；
   * overlapCount 是与专注片段有重叠的用屏片段数，即「专注时拿起手机」的次数。
   */
  function usageDaySummary(segments, focus, anchor) {
    const { start, end } = periodRange("day", anchor);
    const nightStart = new Date(start);
    nightStart.setDate(nightStart.getDate() - 1);
    nightStart.setHours(USAGE.night.startHour);
    const nightEnd = new Date(start);
    nightEnd.setHours(USAGE.night.endHour);
    const clip = (from, to) => segments
      .map(segment => ({ app: segment.app, from: Math.max(from.getTime(), Date.parse(segment.startedAt)), to: Math.min(to.getTime(), Date.parse(segment.endedAt)) }))
      .filter(span => span.to > span.from)
      .sort((left, right) => left.from - right.from);
    const minutesOf = spans => Math.round(spans.reduce((sum, span) => sum + span.to - span.from, 0) / MILLISECONDS_PER_MINUTE);
    const spans = clip(start, end);
    const night = clip(nightStart, nightEnd);
    const apps = [...new Set(spans.map(span => span.app))]
      .map(name => ({ name, minutes: minutesOf(spans.filter(span => span.app === name)) }))
      .sort((left, right) => right.minutes - left.minutes);
    return {
      spans, apps, total: minutesOf(spans), nightMinutes: minutesOf(night),
      lastNightUse: night.length ? new Date(Math.max(...night.map(span => span.to))) : null,
      overlapCount: spans.filter(span => focus.some(([from, to]) => span.from < to && from < span.to)).length,
    };
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

  function allTimeRecords(records, now = new Date()) {
    const today = calendarOrdinal(now);
    return records.filter(record => calendarOrdinal(new Date(record.endedAt)) <= today)
      .sort((left, right) => Date.parse(right.endedAt) - Date.parse(left.endedAt));
  }

  function allTimeRange(records, now = new Date()) {
    const completed = allTimeRecords(records, now);
    if (!completed.length) return null;
    return { start: periodRange("day", new Date(completed.at(-1).endedAt)).start, end: periodRange("day", now).end };
  }

  function focusMonthlyTrend(records, now = new Date()) {
    const range = allTimeRange(records, now);
    if (!range) return [];
    const bins = [];
    for (const date = new Date(range.start.getFullYear(), range.start.getMonth(), 1); date < range.end; date.setMonth(date.getMonth() + 1)) {
      const key = trendKey(date, "year");
      bins.push({ key, label: date.getFullYear() === now.getFullYear() ? `${date.getMonth() + 1}月` : `${String(date.getFullYear()).slice(-2)}年${date.getMonth() + 1}月`, minutes: 0 });
    }
    const byKey = new Map(bins.map(bin => [bin.key, bin]));
    for (const record of allTimeRecords(records, now)) byKey.get(trendKey(new Date(record.endedAt), "year")).minutes += recordMinutes(record);
    return bins.map(bin => ({ ...bin, minutes: roundMinutes(bin.minutes) }));
  }

  function shiftPeriodAnchor(period, anchor, direction) {
    const { start } = periodRange(period, anchor);
    if (period === "day") start.setDate(start.getDate() + direction);
    if (period === "week") start.setDate(start.getDate() + direction * DAYS_PER_WEEK);
    if (period === "month") start.setMonth(start.getMonth() + direction);
    if (period === "year") start.setFullYear(start.getFullYear() + direction);
    return start;
  }

  function comparePeriods(records, { period, anchor, includeSamples = true }) {
    const current = focusSummary(selectFocusRecords(records, { period, anchor, includeSamples })).minutes;
    const previous = focusSummary(selectFocusRecords(records, { period, anchor: shiftPeriodAnchor(period, anchor, -1), includeSamples })).minutes;
    return { current, previous, difference: roundMinutes(current - previous) };
  }

  function allTimeFocusSummary(records, now = new Date()) {
    const today = calendarOrdinal(now);
    const completed = allTimeRecords(records, now);
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
    if (!["category", "focusItemTitle"].includes(field)) throw new Error("请选择分类或专注项分组。");
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
    const rows = [["日期", "分类", "开始时间", "结束时间", "时长（分钟）", "来源", "计时模式", "专注项", "目标时长（分钟）", "时长（秒）", "学习进度", "完成度（%）", "进度更新时间"]];
    for (const record of records) {
      const endedAt = new Date(record.endedAt);
      rows.push([
        localDateKey(endedAt), record.category,
        localDateTime(new Date(record.startedAt)), localDateTime(endedAt), record.durationMinutes,
        record.source === "sample" ? "示例" : "专注计时",
        record.timerMode === "countup" ? "正计时" : "倒计时", record.focusItemTitle,
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

  /* 日记：随手记，一天可多条。原型的照片与录音只是占位描述，不含真实文件。 */
  const DIARY_WEEKDAYS = Object.freeze(["周日", "周一", "周二", "周三", "周四", "周五", "周六"]);
  const MONTH_KEY_PATTERN = /^\d{4}-(?:0[1-9]|1[0-2])$/;

  function initialDiaryEntries(now = new Date()) {
    const minutesAgo = minutes => new Date(now.getTime() - minutes * MILLISECONDS_PER_MINUTE).toISOString();
    const at = (daysAgo, hour, minute) => {
      const date = addDays(now, -daysAgo);
      date.setHours(hour, minute, 0, 0);
      return date.toISOString();
    };
    const photos = (id, labels) => labels.map((label, index) => ({ id: `${id}-photo-${index + 1}`, label }));
    const entry = (id, occurredAt, fields) => ({ id, title: "", text: "", photos: [], audios: [], occurredAt,
      createdAt: occurredAt, updatedAt: occurredAt, deletedAt: null, ...fields });
    return [
      entry("diary-sample-sunset", minutesAgo(35), { text: "下班路上看到很好看的晚霞，停下来拍了几张。最近总是低头赶路，偶尔抬头看看天也挺好。",
        photos: photos("diary-sample-sunset", ["路口的晚霞", "天桥上", "回家的路"]) }),
      entry("diary-sample-voice", minutesAgo(300), { title: "午休时的碎碎念", audios: [{ id: "diary-sample-voice-audio", seconds: 42 }] }),
      entry("diary-sample-review", at(1, 22, 10), { title: "第一次独立做完需求评审",
        text: "准备了两天，真正讲的时候比想象中顺利。被问到边界情况时卡了一下，但没有慌，先记下来会后补充。\n\n下次可以提前把异常流程画出来，自己先过一遍。整体来说，比上个月的自己进步了一点。" }),
      entry("diary-sample-run", at(1, 7, 30), { text: "早起跑了 3 公里，比上周轻松。" }),
      entry("diary-sample-weekend", at(3, 20, 5), { title: "周末去了植物园",
        text: "人不多，银杏开始变黄了。带了一本书，在长椅上读了半小时。", photos: photos("diary-sample-weekend", ["入口", "银杏", "长椅", "湖边", "书页"]) }),
      entry("diary-sample-talk", at(6, 21, 45), { text: "和老朋友打了很久的电话，聊到各自这一年的变化。录下了一段想对自己说的话。",
        audios: [{ id: "diary-sample-talk-audio", seconds: 95 }] }),
      entry("diary-sample-cook", at(12, 19, 20), { text: "第一次自己做红烧肉，糖炒得有点过，但味道还不错。", photos: photos("diary-sample-cook", ["出锅"]) }),
      entry("diary-sample-rain", at(33, 16, 0), { title: "下雨天", text: "在咖啡店躲雨，把拖了很久的读书笔记整理完了。" }),
      entry("diary-sample-move", at(41, 10, 15), { text: "换了新的书桌位置，靠窗之后心情明亮很多。", photos: photos("diary-sample-move", ["新书桌", "窗外"]) }),
      entry("diary-sample-trash", at(9, 23, 30), { text: "有点累，随便记两句。", deletedAt: at(2, 9, 0) }),
    ];
  }

  function diaryEntryDate(entry) {
    const date = new Date(entry?.occurredAt ?? "");
    return Number.isFinite(date.getTime()) ? date : null;
  }

  function diaryDayLabel(date, now) {
    const key = localDateKey(date);
    const suffix = key === localDateKey(now) ? " · 今天" : key === localDateKey(addDays(now, -1)) ? " · 昨天" : "";
    const year = date.getFullYear() === now.getFullYear() ? "" : `${date.getFullYear()}年`;
    return `${year}${date.getMonth() + 1}月${date.getDate()}日 ${DIARY_WEEKDAYS[date.getDay()]}${suffix}`;
  }

  const DIARY_LIMITS = Object.freeze({ maxTitle: 50, maxText: 20000, minAudioSeconds: 1, leapYear: 2000 });
  const DIARY_DATE_PATTERNS = Object.freeze([
    { pattern: /^(\d{4})(\d{2})(\d{2})$/, parts: ["year", "month", "day"] },
    { pattern: /^(\d{4})[-/.年](\d{1,2})[-/.月](\d{1,2})日?$/, parts: ["year", "month", "day"] },
    { pattern: /^(\d{4})[-/.年](\d{1,2})月?$/, parts: ["year", "month"] },
    { pattern: /^(\d{1,2})[-/.月](\d{1,2})日?$/, parts: ["month", "day"] },
    { pattern: /^(\d{1,2})月$/, parts: ["month"] },
  ]);
  const DIARY_RELATIVE_DAYS = Object.freeze({ 今天: 0, 昨天: 1, 前天: 2 });

  /** 把搜索词解析成日期条件；年份或日期可省略。不是合法日期时返回 null，只按文字搜索。 */
  function parseDiaryDateQuery(query, now = new Date()) {
    const text = String(query ?? "").replace(/\s+/g, "");
    if (Object.hasOwn(DIARY_RELATIVE_DAYS, text)) {
      const date = addDays(now, -DIARY_RELATIVE_DAYS[text]);
      return { year: date.getFullYear(), month: date.getMonth() + 1, day: date.getDate(), label: text };
    }
    for (const { pattern, parts } of DIARY_DATE_PATTERNS) {
      const match = pattern.exec(text);
      if (!match) continue;
      const value = Object.fromEntries(parts.map((name, index) => [name, Number(match[index + 1])]));
      const { year = null, month, day = null } = value;
      if (month < 1 || month > 12) return null;
      if (day !== null) {
        const probe = new Date(year ?? DIARY_LIMITS.leapYear, month - 1, day);
        if (day < 1 || probe.getMonth() !== month - 1) return null;
      }
      return { year, month, day, label: `${year === null ? "" : `${year}年`}${month}月${day === null ? "" : `${day}日`}` };
    }
    return null;
  }

  function diaryDateMatches(date, filter) {
    return !!date && (filter.year === null || date.getFullYear() === filter.year) && date.getMonth() + 1 === filter.month
      && (filter.day === null || date.getDate() === filter.day);
  }

  /** 保存前校验：标题可选，至少有文字、照片或录音之一；记录时间可补写过去，不能晚于现在。 */
  function validateDiaryEntry(input, now = new Date()) {
    const title = String(input?.title ?? "").trim();
    const text = String(input?.text ?? "").trim();
    if (title.length > DIARY_LIMITS.maxTitle) throw new Error(`标题最多 ${DIARY_LIMITS.maxTitle} 字。`);
    if (text.length > DIARY_LIMITS.maxText) throw new Error(`正文最多 ${DIARY_LIMITS.maxText} 字。`);
    const photos = (Array.isArray(input?.photos) ? input.photos : []).map(photo => ({ id: String(photo?.id ?? ""), label: String(photo?.label ?? "") }));
    const audios = (Array.isArray(input?.audios) ? input.audios : []).map(audio => {
      if (!Number.isInteger(audio?.seconds) || audio.seconds < DIARY_LIMITS.minAudioSeconds) throw new Error("录音时长无效。");
      return { id: String(audio.id ?? ""), seconds: audio.seconds };
    });
    if (photos.some(photo => !photo.id) || audios.some(audio => !audio.id)) throw new Error("附件缺少标识。");
    if (!title && !text && !photos.length && !audios.length) throw new Error("写点什么，或添加照片、录音后再保存。");
    const occurred = input?.occurredAt instanceof Date ? input.occurredAt : new Date(input?.occurredAt ?? "");
    if (!Number.isFinite(occurred.getTime())) throw new Error("请选择有效的记录时间。");
    if (occurred > now) throw new Error("记录时间不能晚于现在。");
    return { title, text, photos, audios, occurredAt: occurred.toISOString() };
  }

  /** 首页选择：未删除的日记按月查看；搜索覆盖全部时间，匹配标题、正文或日期，同日多条按时间倒序。 */
  function selectDiaryEntries(entries, { month, query = "", now = new Date() } = {}) {
    const currentMonth = localDateKey(now).slice(0, 7);
    const selectedMonth = month === undefined ? currentMonth : month;
    if (typeof selectedMonth !== "string" || !MONTH_KEY_PATTERN.test(selectedMonth) || selectedMonth > currentMonth) {
      throw new Error("请选择有效且不晚于当月的月份。");
    }
    const keyword = String(query ?? "").trim().toLowerCase();
    const alive = (Array.isArray(entries) ? entries : []).filter(entry => entry && !entry.deletedAt)
      .map(entry => ({ entry, date: diaryEntryDate(entry) }))
      .sort((a, b) => (b.date?.getTime() ?? -Infinity) - (a.date?.getTime() ?? -Infinity));
    const monthCounts = new Map();
    alive.forEach(({ date }) => {
      if (!date) return;
      const key = localDateKey(date).slice(0, 7);
      monthCounts.set(key, (monthCounts.get(key) || 0) + 1);
    });
    const dateFilter = keyword ? parseDiaryDateQuery(keyword, now) : null;
    const matches = keyword
      ? alive.filter(({ entry, date }) => (dateFilter && diaryDateMatches(date, dateFilter))
        || `${entry.title ?? ""}\n${entry.text ?? ""}`.toLowerCase().includes(keyword))
      : alive.filter(({ date }) => date && localDateKey(date).slice(0, 7) === selectedMonth);
    const groups = new Map();
    matches.forEach(({ entry, date }) => {
      const key = date ? localDateKey(date) : "unknown";
      if (!groups.has(key)) groups.set(key, { key, label: date ? diaryDayLabel(date, now) : "时间未记录", entries: [] });
      groups.get(key).entries.push(entry);
    });
    const months = Array.from(monthCounts, ([key, count]) => ({ key, count })).sort((a, b) => b.key.localeCompare(a.key));
    return { groups: Array.from(groups.values()), total: matches.length, months, latestMonth: months[0]?.key ?? null,
      dateLabel: dateFilter?.label ?? null };
  }

  /** 回收站按移入时间倒序；不自动清空。 */
  function trashedDiaryEntries(entries) {
    return (Array.isArray(entries) ? entries : []).filter(entry => entry?.deletedAt)
      .sort((a, b) => String(b.deletedAt).localeCompare(String(a.deletedAt)));
  }

  /**
   * 草稿列表：按最后编辑倒序。新日记草稿可以有多份，修改已有日记每条最多一份；
   * 原日记已删除或在回收站时标为 orphan，保存时另存为新日记，不覆盖回收站里的记录。
   */
  function listDiaryDrafts(drafts, entries) {
    const alive = new Map((Array.isArray(entries) ? entries : []).filter(entry => entry && !entry.deletedAt).map(entry => [entry.id, entry]));
    return (Array.isArray(drafts) ? drafts : []).map(draft => {
      const entry = draft.entryId ? alive.get(draft.entryId) ?? null : null;
      return { draft, entry, kind: !draft.entryId ? "new" : entry ? "edit" : "orphan" };
    }).sort((a, b) => String(b.draft.updatedAt).localeCompare(String(a.draft.updatedAt)));
  }

  function moveDiaryToTrash(entry, now = new Date()) {
    return entry.deletedAt ? entry : { ...entry, deletedAt: now.toISOString() };
  }

  function restoreDiaryEntry(entry) {
    return { ...entry, deletedAt: null };
  }

  /** 资产按账户记余额快照；负债也记正数，汇总时计为负值。 */
  const ASSET_KINDS = Object.freeze([
    { id: "cash", label: "现金" }, { id: "bank", label: "储蓄卡" }, { id: "fund", label: "理财 / 基金" },
    { id: "stock", label: "股票" }, { id: "property", label: "房产车辆" }, { id: "other", label: "其他资产" },
    { id: "debt", label: "负债", liability: true },
  ]);
  const ASSET_LIMITS = Object.freeze({ maxName: 16, maxNote: 40, maxAmount: 1e10, maxAccounts: 20, maxWishes: 3,
    maxWishName: 16, trendMonths: 6 });

  function assetKind(id) {
    return ASSET_KINDS.find(kind => kind.id === id) ?? null;
  }

  function validateAssetAccount(input) {
    const name = String(input?.name ?? "").trim();
    if (!name || name.length > ASSET_LIMITS.maxName) throw new Error(`账户名称需为 1–${ASSET_LIMITS.maxName} 个字符。`);
    if (!assetKind(input?.kind)) throw new Error("请选择有效的账户类型。");
    return { name, kind: input.kind };
  }

  function validateAmount(value, label) {
    const text = String(value ?? "").trim();
    const amount = Number(text);
    if (!text || !Number.isFinite(amount) || amount < 0 || amount > ASSET_LIMITS.maxAmount) {
      throw new Error(`${label}需为 0–100 亿之间的数字。`);
    }
    if (Math.abs(amount * 100 - Math.round(amount * 100)) > 1e-6) {
      throw new Error(`${label}最多保留两位小数。`);
    }
    return Math.round(amount * 100) / 100;
  }

  function validateAssetSnapshot(input, now = new Date()) {
    const amount = validateAmount(input?.amount, "余额");
    const date = String(input?.date ?? "");
    if (!parseDateKey(date)) throw new Error("请选择有效的日期。");
    if (date > localDateKey(now)) throw new Error("日期不能晚于今天。");
    const note = String(input?.note ?? "").trim();
    if (note.length > ASSET_LIMITS.maxNote) throw new Error(`备注最多 ${ASSET_LIMITS.maxNote} 字。`);
    return { amount, date, note };
  }

  function validateWish(input) {
    const name = String(input?.name ?? "").trim();
    if (!name || name.length > ASSET_LIMITS.maxWishName) throw new Error(`心愿名称需为 1–${ASSET_LIMITS.maxWishName} 个字符。`);
    const target = validateAmount(input?.target, "目标金额");
    if (target <= 0) throw new Error("目标金额需大于 0。");
    const deadline = String(input?.deadline ?? "").trim() || null;
    if (deadline && !parseDateKey(deadline)) throw new Error("请选择有效的截止日期。");
    const scope = input?.scope === "all" ? "all" : "account";
    const accountId = String(input?.accountId ?? "").trim();
    if (scope === "account" && !accountId) throw new Error("请选择用来攒钱的账户。");
    return { name, target, deadline, scope, accountId: scope === "all" ? null : accountId };
  }

  /** 同一天多条快照时取最后记录的一条（数组顺序靠后者）。 */
  function latestSnapshot(snapshots, accountId, atKey) {
    let latest = null;
    (Array.isArray(snapshots) ? snapshots : []).forEach(snapshot => {
      if (snapshot.accountId !== accountId || (atKey && snapshot.date > atKey)) return;
      if (!latest || snapshot.date >= latest.date) latest = snapshot;
    });
    return latest;
  }

  function latestBalance(snapshots, accountId, atKey) {
    return latestSnapshot(snapshots, accountId, atKey)?.amount ?? 0;
  }

  function roundMoney(value) {
    return Math.round(value * 100) / 100;
  }

  function netWorthAt(accounts, snapshots, atKey) {
    let assets = 0;
    let debts = 0;
    let known = false;
    (Array.isArray(accounts) ? accounts : []).forEach(account => {
      const snapshot = latestSnapshot(snapshots, account.id, atKey);
      if (!snapshot) return;
      known = true;
      if (assetKind(account.kind)?.liability) debts += snapshot.amount;
      else assets += snapshot.amount;
    });
    return { assets: roundMoney(assets), debts: roundMoney(debts), net: roundMoney(assets - debts), known };
  }

  function monthEndKey(now, monthsAgo) {
    return localDateKey(new Date(now.getFullYear(), now.getMonth() - monthsAgo + 1, 0));
  }

  /** 本月变化对比上月月末；上月月末前还没有任何快照时百分比为 null。 */
  function assetSummary(accounts, snapshots, now = new Date()) {
    const current = netWorthAt(accounts, snapshots, localDateKey(now));
    const previous = netWorthAt(accounts, snapshots, monthEndKey(now, 1));
    const monthChange = previous.known ? roundMoney(current.net - previous.net) : null;
    const monthPct = previous.known && previous.net !== 0 ? monthChange / Math.abs(previous.net) : null;
    return { assets: current.assets, debts: current.debts, net: current.net, monthChange, monthPct };
  }

  /** 每月取月末净值（本月取今天），各账户沿用该时点前最近一次快照；附当月带备注的快照。 */
  function netWorthSeries(accounts, snapshots, months = ASSET_LIMITS.trendMonths, now = new Date()) {
    const todayKey = localDateKey(now);
    return Array.from({ length: months }, (_, index) => {
      const monthsAgo = months - 1 - index;
      const endKey = monthsAgo === 0 ? todayKey : monthEndKey(now, monthsAgo);
      const month = endKey.slice(0, 7);
      const point = netWorthAt(accounts, snapshots, endKey);
      const notes = (Array.isArray(snapshots) ? snapshots : [])
        .filter(snapshot => snapshot.note && snapshot.date.slice(0, 7) === month && snapshot.date <= todayKey)
        .sort((a, b) => a.date.localeCompare(b.date));
      return { month, net: point.net, known: point.known, notes };
    });
  }

  function netWorthYearSummary(accounts, snapshots, year, now = new Date()) {
    if (!Number.isInteger(Number(year)) || Number(year) < 1 || Number(year) > now.getFullYear()) throw new Error("请选择有效的统计年份。");
    year = Number(year);
    const points = Array.from({ length: 12 }, (_, index) => {
      const month = `${year}-${String(index + 1).padStart(2, "0")}`;
      const monthDate = new Date(Number(year), index, 1);
      if (monthDate > new Date(now.getFullYear(), now.getMonth(), 1)) return { month, net: 0, known: false, notes: [] };
      const end = index === now.getMonth() && year === now.getFullYear()
        ? localDateKey(now)
        : localDateKey(new Date(Number(year), index + 1, 0));
      const point = netWorthAt(accounts, snapshots, end);
      const notes = (Array.isArray(snapshots) ? snapshots : [])
        .filter(snapshot => snapshot.note && snapshot.date.slice(0, 7) === month && snapshot.date <= localDateKey(now))
        .sort((a, b) => a.date.localeCompare(b.date));
      return { month, net: point.net, known: point.known, notes };
    });
    const current = year === now.getFullYear() ? netWorthAt(accounts, snapshots, localDateKey(now)) : netWorthAt(accounts, snapshots, `${year}-12-31`);
    const baseline = netWorthAt(accounts, snapshots, localDateKey(new Date(Number(year), 0, 0)));
    return { year, points, net: current.net, known: current.known, yearChange: baseline.known && current.known ? roundMoney(current.net - baseline.net) : null };
  }

  function assetBalanceAt(accounts, snapshots, atKey) {
    return roundMoney((Array.isArray(accounts) ? accounts : []).reduce((sum, account) =>
      assetKind(account.kind)?.liability ? sum : sum + (latestSnapshot(snapshots, account.id, atKey)?.amount ?? 0), 0));
  }

  function wishProgress(wish, snapshots, now = new Date(), accounts = []) {
    const todayKey = localDateKey(now);
    const saved = wish.scope === "all"
      ? assetBalanceAt(accounts, snapshots, todayKey)
      : latestBalance(snapshots, wish.accountId, todayKey);
    const remaining = roundMoney(Math.max(0, wish.target - saved));
    const reached = remaining === 0;
    const daysLeft = wish.deadline ? daysBetween(todayKey, wish.deadline) : null;
    const monthsLeft = daysLeft === null || daysLeft < 0 ? null : Math.max(1, Math.ceil(daysLeft / 30));
    return { saved, percent: Math.min(1, saved / wish.target), remaining, reached, daysLeft,
      overdue: !reached && daysLeft !== null && daysLeft < 0,
      monthlyNeeded: reached || monthsLeft === null ? null : Math.ceil(remaining / monthsLeft) };
  }

  /** 连续写日记天数：今天写了从今天数，没写从昨天数；回收站里的不算。 */
  function diaryStreak(entries, now = new Date()) {
    const days = new Set((Array.isArray(entries) ? entries : []).filter(entry => entry && !entry.deletedAt)
      .map(diaryEntryDate).filter(Boolean).map(localDateKey));
    let cursor = days.has(localDateKey(now)) ? now : addDays(now, -1);
    let streak = 0;
    while (days.has(localDateKey(cursor))) {
      streak += 1;
      cursor = addDays(cursor, -1);
    }
    return streak;
  }

  function initialAssetAccounts() {
    return [
      { id: "asset-sample-bank", name: "工资卡", kind: "bank" },
      { id: "asset-sample-cash", name: "零钱", kind: "cash" },
      { id: "asset-sample-fund", name: "指数基金", kind: "fund" },
      { id: "asset-sample-trip", name: "旅行基金", kind: "bank" },
      { id: "asset-sample-card", name: "信用卡", kind: "debt" },
    ];
  }

  function initialAssetSnapshots(now = new Date()) {
    const day = (monthsAgo, date) => {
      const last = new Date(now.getFullYear(), now.getMonth() - monthsAgo + 1, 0).getDate();
      const key = localDateKey(new Date(now.getFullYear(), now.getMonth() - monthsAgo, Math.min(date, last)));
      const today = localDateKey(now);
      return key > today ? today : key;
    };
    const rows = [
      ["bank", 7, 10, 18200, ""], ["cash", 7, 10, 800, ""], ["fund", 7, 10, 30000, ""], ["trip", 7, 10, 2000, ""], ["card", 7, 10, 3200, ""],
      ["bank", 6, 10, 24600, "发工资"], ["card", 6, 25, 1800, "还信用卡"],
      ["bank", 5, 10, 21500, "买基金"], ["fund", 5, 10, 36100, "买基金"], ["trip", 5, 15, 3500, ""],
      ["bank", 4, 10, 27800, "发工资"], ["fund", 4, 28, 35200, "市场回调"], ["card", 4, 25, 4100, ""],
      ["bank", 3, 10, 30100, "发工资"], ["trip", 3, 15, 5000, ""], ["cash", 3, 20, 600, ""],
      ["bank", 2, 10, 26300, "交房租"], ["fund", 2, 28, 38900, ""], ["card", 2, 25, 2600, "还信用卡"],
      ["bank", 1, 10, 32400, "发工资"], ["trip", 1, 15, 6500, ""], ["fund", 1, 28, 40200, ""],
      ["bank", 0, 1, 33800, "年终奖"], ["card", 0, 1, 2900, ""],
    ];
    return rows.map(([account, monthsAgo, date, amount, note], index) => ({
      id: `asset-sample-snap-${index + 1}`, accountId: `asset-sample-${account}`, date: day(monthsAgo, date), amount, note,
    }));
  }

  function initialWishes(now = new Date()) {
    const deadline = localDateKey(new Date(now.getFullYear(), now.getMonth() + 4, 1));
    return [{ id: "wish-sample-trip", name: "去云南旅行", target: 12000, deadline, accountId: "asset-sample-trip", archived: false }];
  }

  const JOB_ASSIST_LIMITS = Object.freeze({ maxCompany: 100, maxPosition: 120, maxDescription: 10000, maxMessage: 3000, maxJobs: 500, maxNote: 200, maxSalary: 40 });
  const JOB_ASSIST_STATUS = Object.freeze({ draft: "draft", copied: "copied", appliedSelfReported: "applied-self-reported", autoContacted: "auto-contacted", autoResumeSent: "auto-resume-sent" });
  /** 用户手动标记的结果；与自动推进的 status 分开存，自动记录不会覆盖，清除后回到自动阶段。 */
  const JOB_OUTCOMES = Object.freeze(["interview", "offer", "closed"]);
  const JOB_STAGES = Object.freeze([
    { id: "prep", label: "准备中" }, { id: "contacted", label: "已沟通" }, { id: "resume", label: "已发简历" },
    { id: "interview", label: "面试" }, { id: "offer", label: "Offer" }, { id: "closed", label: "不合适" },
  ]);

  function validateJobOpportunity(input) {
    const company = String(input?.company ?? "").trim();
    const position = String(input?.position ?? "").trim();
    const description = String(input?.description ?? "").trim();
    const message = String(input?.message ?? "").trim();
    if (!company || company.length > JOB_ASSIST_LIMITS.maxCompany) throw new Error(`公司名称须为 1–${JOB_ASSIST_LIMITS.maxCompany} 个字符。`);
    if (!position || position.length > JOB_ASSIST_LIMITS.maxPosition) throw new Error(`岗位名称须为 1–${JOB_ASSIST_LIMITS.maxPosition} 个字符。`);
    if (description.length > JOB_ASSIST_LIMITS.maxDescription) throw new Error(`职位描述最多 ${JOB_ASSIST_LIMITS.maxDescription} 个字符。`);
    if (message.length > JOB_ASSIST_LIMITS.maxMessage) throw new Error(`沟通文案最多 ${JOB_ASSIST_LIMITS.maxMessage} 个字符。`);
    const id = String(input?.id ?? "");
    if (!id || id.length > 128) throw new Error("职位标识无效。");
    const createdAt = String(input?.createdAt ?? "");
    const updatedAt = String(input?.updatedAt ?? createdAt);
    if (!Number.isFinite(Date.parse(createdAt)) || !Number.isFinite(Date.parse(updatedAt)) || updatedAt < createdAt) throw new Error("职位记录时间无效。");
    const status = Object.values(JOB_ASSIST_STATUS).includes(input?.status) ? input.status : JOB_ASSIST_STATUS.draft;
    const note = String(input?.note ?? "").trim();
    if (note.length > JOB_ASSIST_LIMITS.maxNote) throw new Error(`备注最多 ${JOB_ASSIST_LIMITS.maxNote} 个字符。`);
    const salary = String(input?.salary ?? "").trim().slice(0, JOB_ASSIST_LIMITS.maxSalary);
    const source = input?.source === "auto" ? "auto" : "manual";
    const preset = String(input?.preset ?? "").trim().slice(0, AUTO_PRESET_NAME_MAX);
    const outcome = JOB_OUTCOMES.includes(input?.outcome) ? input.outcome : null;
    return { id, company, position, description, message, status, createdAt, updatedAt, note, salary, source, preset, outcome };
  }

  /** 进度看板阶段：手动结果优先，否则按自动推进的 status 归档。 */
  function jobStage(job) {
    if (job.outcome) return job.outcome;
    const STATUS = JOB_ASSIST_STATUS;
    if (job.status === STATUS.autoResumeSent) return "resume";
    if (job.status === STATUS.autoContacted || job.status === STATUS.appliedSelfReported) return "contacted";
    return "prep";
  }

  /** outcome 为 null 时清除手动结果，回到自动阶段。 */
  function setJobOutcome(job, outcome, note, now = new Date()) {
    if (outcome !== null && !JOB_OUTCOMES.includes(outcome)) throw new Error("阶段无效。");
    const at = now.toISOString();
    return validateJobOpportunity({ ...job, outcome, note, updatedAt: at > job.updatedAt ? at : job.updatedAt });
  }

  function sameJob(job, record) {
    const same = (a, b) => String(a).trim().toLowerCase() === String(b).trim().toLowerCase();
    return same(job.company, record.company) && same(job.position, record.position);
  }

  /**
   * 自动记录同步进度：已有同公司同岗位的职位就推进 status（已发简历不退回已沟通），没有则新建 source=auto 的职位。
   * 只代表界面上发出，不代表 HR 已读；手动结果 outcome 不受影响。职位已满 500 条时不新建。
   */
  function upsertJobFromRecord(jobs, record, newId, now = new Date()) {
    const status = record.result === "contacted" ? JOB_ASSIST_STATUS.autoContacted : record.result === "resume-sent" ? JOB_ASSIST_STATUS.autoResumeSent : null;
    if (!status) return jobs;
    const at = new Date(record.at ?? now).toISOString();
    if (jobs.some(job => sameJob(job, record))) {
      return jobs.map(job => sameJob(job, record) && !(status === JOB_ASSIST_STATUS.autoContacted && job.status === JOB_ASSIST_STATUS.autoResumeSent)
        ? { ...job, status, updatedAt: at > job.updatedAt ? at : job.updatedAt } : job);
    }
    if (jobs.length >= JOB_ASSIST_LIMITS.maxJobs) return jobs;
    return [validateJobOpportunity({ id: newId, company: record.company, position: record.position, salary: record.salary, preset: record.preset, source: "auto", status, createdAt: at, updatedAt: at }), ...jobs];
  }

  // 自动投递规则与 Android domain/AutoApply.kt 同口径；原型只用来模拟试运行，不操作任何 App。
  const AUTO_PRESET_NAME_MAX = 20;
  const AUTO_APPLY_LIMITS = Object.freeze({ maxPresets: 10, maxPresetName: AUTO_PRESET_NAME_MAX,  defaultDailyLimit: 30, maxDailyLimit: 100, defaultStepDelaySeconds: 1, minStepDelaySeconds: 1, maxStepDelaySeconds: 30, maxKeywords: 30, maxKeyword: 30, maxCity: 20, maxGreeting: 300, maxSalaryK: 1000, maxResumes: 10, maxResumeName: 80 });
  const AUTO_APPLY_SALARY = /^\s*(\d+(?:\.\d+)?)\s*(?:[-~]\s*(\d+(?:\.\d+)?))?\s*([Kk]|万|元\/(?:天|时|小时|月))/;

  function defaultAutoApplySettings() {
    return { includeKeywords: [], excludeKeywords: [], minSalaryK: null, city: "", companyBlacklist: [], dailyLimit: AUTO_APPLY_LIMITS.defaultDailyLimit, stepDelaySeconds: AUTO_APPLY_LIMITS.defaultStepDelaySeconds, greeting: "", riskAccepted: false, resumes: [], presets: [], activePresetId: null };
  }

  /** 方案只包含这些筛选字段；黑名单、每日上限、间隔与简历是全局设置，不随方案切换。 */
  const AUTO_PRESET_FIELDS = Object.freeze(["includeKeywords", "excludeKeywords", "minSalaryK", "city", "greeting"]);
  const presetKey = name => String(name ?? "").trim().toLowerCase();

  function activePresetName(settings) {
    return settings.presets.find(item => item.id === settings.activePresetId)?.name ?? "";
  }

  /** 切换方案：把方案的筛选字段写回顶层，引擎仍只读顶层字段。 */
  function applyAutoApplyPreset(settings, id) {
    const preset = settings.presets.find(item => item.id === id);
    if (!preset) throw new Error("方案不存在。");
    return { ...settings, ...Object.fromEntries(AUTO_PRESET_FIELDS.map(key => [key, preset[key]])), activePresetId: id };
  }

  /** 把当前筛选字段存为方案：同名覆盖，否则新建（newId）；名称为空时只取消当前方案。 */
  function saveAutoApplyPreset(settings, name, newId) {
    const trimmed = String(name ?? "").trim();
    if (!trimmed) return { ...settings, activePresetId: null };
    if (trimmed.length > AUTO_PRESET_NAME_MAX) throw new Error(`方案名称最多 ${AUTO_PRESET_NAME_MAX} 个字符。`);
    const existing = settings.presets.find(item => presetKey(item.name) === presetKey(trimmed));
    if (!existing && settings.presets.length >= AUTO_APPLY_LIMITS.maxPresets) throw new Error(`最多保存 ${AUTO_APPLY_LIMITS.maxPresets} 套方案。`);
    const preset = { id: existing?.id ?? newId, name: existing?.name ?? trimmed, ...Object.fromEntries(AUTO_PRESET_FIELDS.map(key => [key, settings[key]])) };
    const presets = existing ? settings.presets.map(item => item.id === existing.id ? preset : item) : [...settings.presets, preset];
    return { ...settings, presets, activePresetId: preset.id };
  }

  /** 删除方案不改顶层筛选字段；删的是当前方案时只取消选中。 */
  function deleteAutoApplyPreset(settings, id) {
    return { ...settings, presets: settings.presets.filter(item => item.id !== id), activePresetId: settings.activePresetId === id ? null : settings.activePresetId };
  }

  function parseKeywordList(text) {
    return [...new Set(String(text ?? "").split(/[,，、;；\n]/).map(item => item.trim()).filter(Boolean))];
  }

  function salaryLowerK(text) {
    const match = AUTO_APPLY_SALARY.exec(String(text ?? ""));
    if (!match) return null;
    const low = Number(match[1]);
    if (match[3] === "K" || match[3] === "k") return Math.trunc(low);
    if (match[3] === "万") return Math.trunc(low * 10);
    if (match[3] === "元/月") return Math.trunc(low / 1000);
    return null;
  }

  function autoApplyJobKey(card) {
    return [card.company, card.position, card.salary].map(item => String(item ?? "").trim().toLowerCase()).join("|");
  }

  function validateAutoApplySettings(input) {
    const value = { ...defaultAutoApplySettings(), ...input };
    const words = (list, label) => {
      if (!Array.isArray(list) || list.length > AUTO_APPLY_LIMITS.maxKeywords) throw new Error(`${label}最多 ${AUTO_APPLY_LIMITS.maxKeywords} 个。`);
      if (list.some(item => !String(item).trim() || String(item).length > AUTO_APPLY_LIMITS.maxKeyword)) throw new Error(`${label}每个最多 ${AUTO_APPLY_LIMITS.maxKeyword} 个字符。`);
    };
    words(value.includeKeywords, "关键词");
    words(value.excludeKeywords, "排除词");
    words(value.companyBlacklist, "公司黑名单");
    if (value.minSalaryK !== null && !(Number.isInteger(value.minSalaryK) && value.minSalaryK >= 1 && value.minSalaryK <= AUTO_APPLY_LIMITS.maxSalaryK)) throw new Error(`最低薪资须为 1–${AUTO_APPLY_LIMITS.maxSalaryK} K。`);
    if (String(value.city).length > AUTO_APPLY_LIMITS.maxCity) throw new Error(`城市最多 ${AUTO_APPLY_LIMITS.maxCity} 个字符。`);
    if (!Number.isInteger(value.dailyLimit) || value.dailyLimit < 1 || value.dailyLimit > AUTO_APPLY_LIMITS.maxDailyLimit) throw new Error(`每日上限须为 1–${AUTO_APPLY_LIMITS.maxDailyLimit}。`);
    if (!Number.isInteger(value.stepDelaySeconds) || value.stepDelaySeconds < AUTO_APPLY_LIMITS.minStepDelaySeconds || value.stepDelaySeconds > AUTO_APPLY_LIMITS.maxStepDelaySeconds) throw new Error(`步骤间隔须为 ${AUTO_APPLY_LIMITS.minStepDelaySeconds}–${AUTO_APPLY_LIMITS.maxStepDelaySeconds} 秒。`);
    if (String(value.greeting).length > AUTO_APPLY_LIMITS.maxGreeting) throw new Error(`招呼语最多 ${AUTO_APPLY_LIMITS.maxGreeting} 个字符。`);
    if (!Array.isArray(value.resumes) || value.resumes.length > AUTO_APPLY_LIMITS.maxResumes) throw new Error(`简历最多 ${AUTO_APPLY_LIMITS.maxResumes} 份。`);
    if (value.resumes.filter(item => item.isDefault).length > 1) throw new Error("只能有一份默认简历。");
    if (new Set(value.resumes.map(item => bareResumeName(item.name))).size !== value.resumes.length) throw new Error("简历名称不能重复。");
    value.resumes.forEach(resume => {
      if (!String(resume?.id ?? "").trim() || String(resume.id).length > 64) throw new Error("简历标识无效。");
      const name = String(resume.name ?? "").trim();
      if (!name || name.length > AUTO_APPLY_LIMITS.maxResumeName) throw new Error(`简历名称须为 1–${AUTO_APPLY_LIMITS.maxResumeName} 个字符。`);
      if (!Array.isArray(resume.keywords)) throw new Error("简历关键词无效。");
      if (!resume.isDefault && !resume.keywords.length) throw new Error(`「${name}」至少要有一个岗位关键词，或设为默认简历。`);
      words(resume.keywords, "简历关键词");
    });
    if (!Array.isArray(value.presets) || value.presets.length > AUTO_APPLY_LIMITS.maxPresets) throw new Error(`最多保存 ${AUTO_APPLY_LIMITS.maxPresets} 套方案。`);
    if (new Set(value.presets.map(item => presetKey(item.name))).size !== value.presets.length) throw new Error("方案名称不能重复。");
    value.presets.forEach(preset => {
      if (!String(preset?.id ?? "").trim() || String(preset.id).length > 64) throw new Error("方案标识无效。");
      const name = String(preset.name ?? "").trim();
      if (!name || name.length > AUTO_PRESET_NAME_MAX) throw new Error(`方案名称须为 1–${AUTO_PRESET_NAME_MAX} 个字符。`);
      words(preset.includeKeywords, "方案关键词");
      words(preset.excludeKeywords, "方案排除词");
    });
    if (value.activePresetId !== null && !value.presets.some(item => item.id === value.activePresetId)) throw new Error("当前方案不存在。");
    return { ...value, city: String(value.city).trim(), greeting: String(value.greeting).trim(), riskAccepted: Boolean(value.riskAccepted) };
  }

  /** records: [{ jobKey, company, day: "YYYY-MM-DD", result: "contacted" | "already" | "failed" }] */
  function autoApplyDecision(card, settings, records, day) {
    const includes = (text, word) => String(text).toLowerCase().includes(String(word).toLowerCase());
    if (!String(card.position ?? "").trim() || !String(card.company ?? "").trim()) return { apply: false, reason: "未识别出职位或公司" };
    const key = autoApplyJobKey(card);
    if (records.some(item => item.jobKey === key && item.result !== "failed")) return { apply: false, reason: "已沟通过" };
    const company = String(card.company).trim().toLowerCase();
    if (records.some(item => item.result === "contacted" && String(item.company).trim().toLowerCase() === company && item.day === day)) return { apply: false, reason: "今天已联系过这家公司" };
    const black = settings.companyBlacklist.find(word => includes(card.company, word));
    if (black) return { apply: false, reason: `公司在黑名单「${black}」` };
    const excluded = settings.excludeKeywords.find(word => includes(card.position, word));
    if (excluded) return { apply: false, reason: `包含排除词「${excluded}」` };
    if (settings.includeKeywords.length && !settings.includeKeywords.some(word => includes(card.position, word))) return { apply: false, reason: "岗位不含关键词" };
    if (settings.city && card.city && !String(card.city).includes(settings.city)) return { apply: false, reason: "城市不符" };
    if (settings.minSalaryK !== null) {
      const low = salaryLowerK(card.salary);
      if (low === null) return { apply: false, reason: "薪资无法识别" };
      if (low < settings.minSalaryK) return { apply: false, reason: `薪资低于 ${settings.minSalaryK}K` };
    }
    return { apply: true, reason: "" };
  }

  function autoApplyContactedOn(records, day) {
    return records.filter(item => item.result === "contacted" && item.day === day).length;
  }

  /** 模拟一次运行：按顺序决定，达到每日上限即停止；dryRun 时不产生记录。 */
  function simulateAutoApply(cards, settings, records, day, dryRun) {
    const preview = [];
    const added = [];
    let stopReason = null;
    for (const card of cards) {
      const all = records.concat(added);
      if (!dryRun && autoApplyContactedOn(all, day) >= settings.dailyLimit) { stopReason = `已达到今日上限 ${settings.dailyLimit} 条，已停止`; break; }
      const decision = autoApplyDecision(card, settings, all, day);
      preview.push({ card, ...decision });
      if (!dryRun && decision.apply) added.push({ jobKey: autoApplyJobKey(card), company: card.company, position: card.position, salary: card.salary, day, result: "contacted", preset: activePresetName(settings) });
    }
    const applying = preview.filter(item => item.apply).length;
    return { preview, records: added, stopReason: stopReason ?? (dryRun ? `试运行完成：识别 ${preview.length} 个职位，其中 ${applying} 个符合条件；未点击任何按钮` : "列表没有更多新职位，已停止") };
  }

  /** 先按列表顺序找关键词命中的简历，再退回默认简历；都没有时返回 null（跳过）。 */
  function pickResume(position, resumes) {
    const text = String(position ?? "").toLowerCase();
    const hit = resumes.find(resume => resume.keywords.some(word => text.includes(String(word).toLowerCase()))) ?? resumes.find(resume => resume.isDefault);
    return hit?.name ?? null;
  }

  /** 保存一份简历：同 id 覆盖，否则追加；设为默认时取消其他简历的默认。 */
  function upsertResume(resumes, value) {
    const cleaned = { ...value, name: String(value.name ?? "").trim(), keywords: [...new Set(value.keywords.map(word => String(word).trim()).filter(Boolean))], isDefault: Boolean(value.isDefault) };
    const next = resumes.some(item => item.id === cleaned.id) ? resumes.map(item => item.id === cleaned.id ? cleaned : item) : [...resumes, cleaned];
    return cleaned.isDefault ? next.map(item => item.id !== cleaned.id && item.isDefault ? { ...item, isDefault: false } : item) : next;
  }

  /** 调整优先级：up 为 true 时与上一份交换。 */
  function moveResume(resumes, id, up) {
    const index = resumes.findIndex(item => item.id === id);
    const other = up ? index - 1 : index + 1;
    if (index < 0 || other < 0 || other >= resumes.length) return resumes;
    const next = [...resumes];
    [next[index], next[other]] = [next[other], next[index]];
    return next;
  }

  function bareResumeName(text) {
    return String(text ?? "").trim().replace(/\.(pdf|docx?|jpe?g|png)$/i, "").trim().toLowerCase();
  }

  /** 简历只按整名比较（忽略大小写和扩展名）。 */
  function resumeNameMatches(option, name) {
    return Boolean(bareResumeName(name)) && bareResumeName(option) === bareResumeName(name);
  }

  /** 公司按整名比较（去掉空白和括号内容），避免「华为」误配「华为云计算」。 */
  function sameCompany(a, b) {
    const key = text => String(text ?? "").replace(/[(（[【][^)）\]】]*[)）\]】]/g, "").replace(/\s/g, "").toLowerCase();
    return Boolean(key(a)) && key(a) === key(b);
  }

  /** 已自动发起沟通、还没发过简历的职位，每个职位一条。 */
  function awaitingResume(records) {
    const sent = new Set(records.filter(item => item.result === "resume-sent").map(item => item.jobKey));
    const seen = new Set();
    return records.filter(item => {
      if (item.result !== "contacted" || sent.has(item.jobKey) || seen.has(item.jobKey)) return false;
      seen.add(item.jobKey);
      return true;
    });
  }

  function resumesSentOn(records, day) {
    return records.filter(item => item.result === "resume-sent" && item.day === day).length;
  }

  /** 模拟发简历：conversations 为 [{ company, unread }]，只处理有未读、且自动沟通过的公司；不涉及消息内容。 */
  function simulateResumeRun(conversations, settings, records, day, dryRun) {
    const preview = [];
    const added = [];
    let stopReason = null;
    for (const chat of conversations) {
      const all = records.concat(added);
      const target = chat.unread ? awaitingResume(all).find(item => sameCompany(item.company, chat.company)) : null;
      if (!target) continue;
      if (!dryRun && resumesSentOn(all, day) >= settings.dailyLimit) { stopReason = `已达到今日发简历上限 ${settings.dailyLimit} 份，已停止`; break; }
      const resumeName = pickResume(target.position, settings.resumes);
      preview.push({ company: chat.company, position: target.position, resumeName, reason: resumeName ? "" : "没有匹配的简历" });
      if (!dryRun && resumeName) added.push({ jobKey: target.jobKey, company: target.company, position: target.position, salary: target.salary, day, result: "resume-sent", note: `简历：${resumeName}` });
    }
    const sending = preview.filter(item => item.resumeName).length;
    return { preview, records: added, stopReason: stopReason ?? (dryRun ? `发简历试运行完成：识别 ${conversations.length} 个会话，其中 ${sending} 个会发简历；未点击任何按钮` : "没有更多待发简历的新回复，已停止") };
  }

  /** 「我的」入口卡与全局状态条共用：task 为 null / "contact" / "resume"，只汇总已有计数，不新增口径。 */
  function autoApplyEntry({ task = null, paused = false, stopReason = null, contacted = 0, resumes = 0, awaiting = 0, limit }) {
    const state = task ? (paused ? "paused" : "running") : stopReason ? "stopped" : "idle";
    const label = { running: task === "resume" ? "自动发简历中" : "自动沟通中", paused: "已暂停", stopped: "已停止", idle: "空闲" }[state];
    return { state, label, summary: `沟通 ${contacted}/${limit} · 简历 ${resumes}/${limit} · 待回复 ${awaiting}` };
  }

  /**
   * 复盘：近 days 天（含今天）的自动沟通记录。漏斗以职位为单位：沟通 → 其中已发简历 → 其中手动标为面试/Offer；
   * 已发简历只说明对方回复过，不等于 HR 已查看。dailyContacted 按日期从早到晚，不含未来日期。
   */
  function jobReviewSummary(records, jobs, today, days) {
    const keys = Array.from({ length: days }, (_, index) => localDateKey(new Date(today.getFullYear(), today.getMonth(), today.getDate() - (days - 1 - index))));
    const inRange = new Set(keys);
    const contacted = records.filter(item => item.result === "contacted" && inRange.has(item.day));
    const sent = new Set(records.filter(item => item.result === "resume-sent").map(item => item.jobKey));
    const first = new Map();
    contacted.forEach(item => { if (!first.has(item.jobKey)) first.set(item.jobKey, item); });
    const contactedKeys = [...first.keys()];
    const stages = contactedKeys.map(key => { const job = jobs.find(item => sameJob(item, first.get(key))); return job ? jobStage(job) : null; });
    const byPreset = new Map();
    contactedKeys.forEach(key => {
      const name = first.get(key).preset || "";
      const row = byPreset.get(name) ?? { preset: name, contacted: 0, resumes: 0 };
      row.contacted++;
      if (sent.has(key)) row.resumes++;
      byPreset.set(name, row);
    });
    return {
      days: keys,
      funnel: {
        contacted: contactedKeys.length,
        resumes: contactedKeys.filter(key => sent.has(key)).length,
        interviews: stages.filter(stage => stage === "interview" || stage === "offer").length,
        offers: stages.filter(stage => stage === "offer").length,
        closed: stages.filter(stage => stage === "closed").length,
      },
      dailyContacted: keys.map(key => contacted.filter(item => item.day === key).length),
      byPreset: [...byPreset.values()].sort((a, b) => b.contacted - a.contacted || a.preset.localeCompare(b.preset)),
    };
  }

  const api = Object.freeze({
    LIMITS, MODES, CATEGORIES, USAGE, sampleUsageSegments, focusSpans, usageDaySummary, initialTasks, initialFocusItems, integerInRange, validateTask, validateSteps, validateFocusItem, taskSummary, groupTasks, taskAge, taskProgress, nextStep, daysBetween, formatTime, Timer,
    localDateKey, periodRange, initialFocusRecords, selectFocusRecords, focusSummary, focusTrend, focusHourDistribution, focusBreakdown, monthActivity, exportFocusCsv,
    validateProgress, initialProgressEntries, latestProjectProgress, allTimeFocusSummary,
    allTimeRecords, allTimeRange, focusMonthlyTrend, shiftPeriodAnchor, comparePeriods,
    completeTask, setTaskStep, reopenTask, groupCompletedTasks, completedTaskDate, selectCompletedTasks,
    DIARY_LIMITS, initialDiaryEntries, diaryEntryDate, parseDiaryDateQuery, validateDiaryEntry, selectDiaryEntries, trashedDiaryEntries, listDiaryDrafts, moveDiaryToTrash, restoreDiaryEntry,
    diaryStreak, JOB_ASSIST_LIMITS, JOB_ASSIST_STATUS, validateJobOpportunity,
    AUTO_APPLY_LIMITS, defaultAutoApplySettings, parseKeywordList, salaryLowerK, autoApplyJobKey, validateAutoApplySettings, autoApplyDecision, autoApplyContactedOn, simulateAutoApply,
    pickResume, upsertResume, moveResume, resumeNameMatches, sameCompany, awaitingResume, resumesSentOn, simulateResumeRun, autoApplyEntry,
    JOB_OUTCOMES, JOB_STAGES, jobStage, setJobOutcome, upsertJobFromRecord, jobReviewSummary,
    activePresetName, applyAutoApplyPreset, saveAutoApplyPreset, deleteAutoApplyPreset,
    ASSET_KINDS, ASSET_LIMITS, assetKind, validateAssetAccount, validateAssetSnapshot, validateWish,
    latestSnapshot, latestBalance, assetSummary, netWorthSeries, netWorthYearSummary, assetBalanceAt, wishProgress, initialAssetAccounts, initialAssetSnapshots, initialWishes,
  });
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.FocusModel = api;
})(globalThis);
