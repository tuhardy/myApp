"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { Timer, LIMITS, MODES, USAGE, initialTasks, taskSummary, validateTask, validateSteps, taskAge, taskProgress, nextStep, stepFocusTitle, daysBetween, formatTime } = require("./model.js");
const { localDateKey, periodRange, initialFocusRecords, selectFocusRecords, focusSummary, focusTrend, focusBreakdown, monthActivity, exportFocusCsv } = require("./model.js");
const { execFileSync } = require("node:child_process");
const { initialFocusItems, validateFocusItem, focusHourDistribution, validateProgress, initialProgressEntries, latestProjectProgress, allTimeFocusSummary } = require("./model.js");

test("进度校验清理必填笔记，完成度可选且仅允许 0–100 整数", () => {
  assert.equal(LIMITS.maxProgressNote, 2000);
  const input = Object.freeze({ note: '  <img src=x onerror=alert(1)>\n学习 JWT  ', percent: "0", ignored: true });
  assert.deepEqual(validateProgress(input), { note: '<img src=x onerror=alert(1)>\n学习 JWT', percent: 0 });
  for (const percent of [undefined, null, "", " \t\n"]) assert.deepEqual(validateProgress({ note: "计划", percent }), { note: "计划", percent: null });
  for (const percent of [0, 100, "100", " 25 "]) assert.equal(validateProgress({ note: "计划", percent }).percent, Number(percent));
  assert.equal(validateProgress({ note: "字".repeat(2000) }).note.length, 2000);
  for (const note of [undefined, null, "", " \n ", "字".repeat(2001)]) assert.throws(() => validateProgress({ note, percent: 20 }));
  for (const percent of [-1, 101, 0.5, "0.5", "bad", NaN, Infinity, true, false, [], {}, [1]]) assert.throws(() => validateProgress({ note: "计划", percent }));
});

test("项目示例进度关联最新完成示例，生成结果确定且互相独立", () => {
  const records = initialFocusRecords(new Date(2024, 5, 15));
  const entries = initialProgressEntries(records);
  assert.equal(entries.length, 3);
  assert.deepEqual(entries, initialProgressEntries(records));
  assert.deepEqual(entries, initialProgressEntries([...records].reverse()));
  assert.equal(new Set(entries.map(entry => entry.id)).size, 3);
  for (const entry of entries) {
    const record = records.filter(record => record.focusItemId === entry.focusItemId).sort((a, b) => Date.parse(b.endedAt) - Date.parse(a.endedAt))[0];
    assert.deepEqual(Object.keys(entry).sort(), ["id", "recordId", "focusItemId", "recordEndedAt", "note", "percent", "updatedAt"].sort());
    assert.equal(entry.recordId, record.id);
    assert.equal(entry.recordEndedAt, record.endedAt);
    assert.equal(entry.updatedAt, record.endedAt);
    assert.deepEqual(validateProgress(entry), { note: entry.note, percent: entry.percent });
    assert.equal(latestProjectProgress(entries, entry.focusItemId), entry);
  }
  const snapshot = JSON.stringify(records);
  entries[0].note = "修改";
  assert.notEqual(initialProgressEntries(records)[0].note, "修改");
  assert.equal(JSON.stringify(records), snapshot);
  assert.deepEqual(initialProgressEntries([]), []);
  const session = { ...records[0], id: "real", source: "session", endedAt: new Date(2025, 0, 1).toISOString() };
  assert.deepEqual(initialProgressEntries([...records, session]), initialProgressEntries(records));
});

test("项目最新进度优先完成日期，旧记录补填不会覆盖新记录，同次修改按更新时间及插入顺序", () => {
  const make = (id, recordEndedAt, updatedAt, focusItemId = "project") => Object.freeze({ id, recordId: id, focusItemId, recordEndedAt, updatedAt, note: id, percent: null });
  const current = make("current", "2024-06-02T10:00:00Z", "2024-06-02T11:00:00Z");
  const backfill = make("backfill", "2024-06-01T10:00:00Z", "2024-06-10T11:00:00Z");
  const revision = Object.freeze({ ...make("revision", current.recordEndedAt, "2024-06-03T11:00:00Z"), recordId: current.recordId });
  const tied = Object.freeze({ ...make("tied", revision.recordEndedAt, revision.updatedAt), recordId: current.recordId });
  const other = make("other", "2025-01-01T00:00:00Z", "2025-01-01T00:00:00Z", "other");
  const entries = Object.freeze([revision, current, backfill, other, tied]);
  const snapshot = JSON.stringify(entries);
  assert.equal(latestProjectProgress([], "project"), null);
  assert.equal(latestProjectProgress(entries, "missing"), null);
  assert.equal(latestProjectProgress([current, backfill], "project"), current);
  assert.equal(latestProjectProgress([revision, current], "project"), revision);
  assert.equal(latestProjectProgress(entries, "project"), tied);
  assert.equal(JSON.stringify(entries), snapshot);
});

test("累计统计覆盖所有历史，自然日含空闲直至今天，活跃日均与周期 API 兼容", () => {
  const now = new Date(2024, 1, 5, 9);
  const records = Object.freeze([
    focusRecord("old", new Date(2023, 11, 31, 23), 30),
    focusRecord("a", new Date(2024, 1, 1, 9), 25),
    focusRecord("b", new Date(2024, 1, 1, 12), 35, { source: "sample" }),
    focusRecord("c", new Date(2024, 1, 3, 12), 30),
  ].map(Object.freeze));
  const snapshot = JSON.stringify(records);
  assert.deepEqual(allTimeFocusSummary(records, now), {
    minutes: 120, count: 4, activeDays: 3, calendarDays: 37,
    calendarAverageMinutes: 3.243243243, activeAverageMinutes: 40, firstDate: "2023-12-31", lastDate: "2024-02-03",
  });
  const filtered = records.filter(record => record.source !== "sample");
  assert.equal(allTimeFocusSummary(filtered, now).minutes, 85);
  assert.equal(allTimeFocusSummary(filtered, now).count, 3);
  assert.equal(focusSummary(records).averageMinutes, 40);
  assert.equal(JSON.stringify(records), snapshot);
  assert.equal(now.getTime(), new Date(2024, 1, 5, 9).getTime());
});

test("累计统计空数据为零，按本地完成日排除未来日期但允许今天稍后示例", () => {
  const now = new Date(2024, 1, 29, 0, 1);
  const empty = { minutes: 0, count: 0, activeDays: 0, calendarDays: 0, calendarAverageMinutes: 0, activeAverageMinutes: 0, firstDate: null, lastDate: null };
  assert.deepEqual(allTimeFocusSummary([], now), empty);
  assert.deepEqual(allTimeFocusSummary([]), empty);
  const future = focusRecord("future", new Date(2024, 2, 1), 100);
  assert.deepEqual(allTimeFocusSummary([future], now), empty);
  const record = focusRecord("today", new Date(2024, 1, 29, 23), 999, { durationSeconds: 61, source: "sample" });
  const summary = allTimeFocusSummary([future, record], now);
  assert.equal(summary.count, 1);
  assert.equal(summary.calendarDays, 1);
  assert.equal(summary.activeDays, 1);
  assert.equal(summary.firstDate, "2024-02-29");
  assert.equal(summary.lastDate, "2024-02-29");
  closeMinutes(summary.minutes, 61 / 60);
  closeMinutes(summary.calendarAverageMinutes, 61 / 60);
  closeMinutes(summary.activeAverageMinutes, 61 / 60);
  assert.throws(() => allTimeFocusSummary([], new Date(NaN)));
});

test("累计自然日使用日历序数，跨时区及夏令时开始结束不会偏差", () => {
  const script = `
    const assert = require("node:assert/strict");
    const { allTimeFocusSummary } = require("./model.js");
    for (const [month, day] of [[2, 10], [10, 3]]) {
      const start = new Date(2024, month, day - 1, 23);
      const now = new Date(2024, month, day + 1, 0);
      const summary = allTimeFocusSummary([{ endedAt: start.toISOString(), durationMinutes: 30 }], now);
      assert.equal(summary.calendarDays, 3);
      assert.equal(summary.calendarAverageMinutes, 10);
      assert.equal(summary.activeAverageMinutes, 30);
    }
    const instant = new Date("2024-01-01T01:00:00Z");
    const summary = allTimeFocusSummary([{ endedAt: instant.toISOString(), durationMinutes: 25 }], instant);
    assert.equal(summary.firstDate, process.env.EXPECTED_DAY);
    assert.equal(summary.lastDate, process.env.EXPECTED_DAY);
    assert.equal(summary.calendarDays, 1);
  `;
  for (const [TZ, EXPECTED_DAY] of [["America/New_York", "2023-12-31"], ["Asia/Shanghai", "2024-01-01"]]) {
    execFileSync(process.execPath, ["-e", script], { cwd: __dirname, env: { ...process.env, TZ, EXPECTED_DAY } });
  }
});

test("CSV 十四列附加当前进度快照，保留零完成度和更新时间并转义多行公式", () => {
  const record = focusRecord("progress-csv", new Date(2024, 1, 29, 12), 25, {
    focusItemTitle: "历史项目名称", progress: Object.freeze({ note: '=学习,"JWT"\r\n下一步', percent: 0, updatedAt: "2024-03-01T10:00:00Z" }),
  });
  Object.freeze(record);
  const snapshot = JSON.stringify(record);
  const csv = exportFocusCsv([record]);
  assert.equal(csv.split("\r\n")[0].split(",").length, 14);
  assert.ok(csv.endsWith(`,"历史项目名称","25","1500","'=学习,""JWT""\r\n下一步","0","2024-03-01T10:00:00Z"\r\n`));
  for (const note of ["+1", "-1", "@SUM(A1)", " \t=1", "\r\n+1", "<script>alert(1)</script>"]) {
    const escaped = /^[\s]*[=+\-@]/.test(note) ? `'${note}` : note;
    assert.ok(exportFocusCsv([{ ...record, progress: { note, percent: null } }]).endsWith(`,"${escaped}","",""\r\n`));
  }
  assert.equal(JSON.stringify(record), snapshot);
});

function fixture() {
  let now = 0;
  return { timer: new Timer(() => now), advance: milliseconds => { now += milliseconds; } };
}

test("默认计时为 25 分钟且不自动开始", () => {
  const { timer } = fixture();
  assert.equal(timer.remainingSeconds, MODES.focus * LIMITS.secondsPerMinute);
  assert.equal(timer.running, false);
  assert.equal(timer.completed, false);
});

test("系统墙钟跳变不会影响默认计时源", context => {
  let wallNow = 0;
  context.mock.method(Date, "now", () => wallNow);
  const timer = new Timer();
  timer.start();
  wallNow = 86400000;
  assert.equal(timer.remainingSeconds, MODES.focus * LIMITS.secondsPerMinute);
  assert.equal(timer.tick(), null);
});

test("暂停后保留毫秒精度，恢复后不计算暂停时间", () => {
  const { timer, advance } = fixture();
  timer.start();
  advance(1500);
  timer.pause();
  assert.equal(timer.remainingMs, 1498500);
  advance(60000);
  assert.equal(timer.remainingSeconds, 1499);
  timer.start();
  advance(500);
  assert.equal(timer.remainingSeconds, 1498);
});

test("重复开始不会改变计时结束时间", () => {
  const { timer, advance } = fixture();
  timer.start();
  advance(10000);
  assert.equal(timer.start(), false);
  assert.equal(timer.remainingSeconds, 1490);
});

test("后台延迟唤醒后按结束时间结算且只记账一次", () => {
  const { timer, advance } = fixture();
  timer.start();
  advance(timer.totalMs + 900000);
  assert.equal(timer.remainingSeconds, 0);
  assert.deepEqual(timer.tick(), { mode: "focus", minutes: 25 });
  assert.equal(timer.completed, true);
  assert.equal(timer.running, false);
  assert.equal(timer.tick(), null);
  assert.equal(timer.start(), false);
  timer.reset();
  assert.equal(timer.start(), true);
});

test("休息返回独立模式，便于 UI 排除专注记账", () => {
  const { timer, advance } = fixture();
  timer.selectMode("short");
  timer.start();
  advance(timer.totalMs);
  assert.deepEqual(timer.tick(), { mode: "short", minutes: 5 });
});

test("切换模式重置状态，但重复选择同一模式保留进度", () => {
  const { timer, advance } = fixture();
  timer.start();
  advance(10000);
  timer.selectMode("focus");
  assert.equal(timer.remainingSeconds, 1490);
  timer.selectMode("long");
  assert.equal(timer.remainingSeconds, 900);
  assert.equal(timer.running, false);
  assert.equal(timer.deadline, null);
  assert.throws(() => timer.selectMode("invalid"));
});

test("更新时长原子校验且只允许整数范围", () => {
  const { timer } = fixture();
  for (const invalid of [0, -1, 121, 1.5, "", "abc", Infinity]) {
    assert.throws(() => timer.setDurations({ focus: invalid, short: 5, long: 15 }));
    assert.deepEqual(timer.durations, MODES);
  }
  timer.setDurations({ focus: "1", short: "2", long: "3" });
  assert.equal(timer.remainingSeconds, 60);
  assert.deepEqual(timer.durations, { focus: 1, short: 2, long: 3 });
});

test("计时格式向上取整，不显示负值，支持超过一小时", () => {
  assert.equal(formatTime(61), "01:01");
  assert.equal(formatTime(0.5), "00:01");
  assert.equal(formatTime(-10), "00:00");
  assert.equal(formatTime(7200), "120:00");
});

test("待办输入清理和边界校验", () => {
  const valid = { title: "  阅读  ", category: "个人成长", important: false };
  assert.deepEqual(validateTask(valid), { title: "阅读", category: "个人成长", important: false, steps: [] });
  for (const title of ["", "   ", "a".repeat(LIMITS.maxTaskTitle + 1)]) assert.throws(() => validateTask({ ...valid, title }));
  assert.throws(() => validateTask({ ...valid, category: "未知分类" }));
});

test("小步校验：可以没有，有则要有名字且不超上限", () => {
  assert.deepEqual(validateSteps(undefined), []);
  assert.deepEqual(validateSteps([{ id: "a", title: "  写提纲  ", done: true }]), [{ id: "a", title: "写提纲", done: true }]);
  assert.throws(() => validateSteps([{ id: "a", title: "   " }]));
  assert.throws(() => validateSteps([{ id: "a", title: "x".repeat(LIMITS.maxTaskStepTitle + 1) }]));
  assert.throws(() => validateSteps(Array.from({ length: LIMITS.maxTaskSteps + 1 }, (_, i) => ({ id: `s${i}`, title: "步" }))));
});

test("L：躺得越久阶段越靠后，完成的事不发酵", () => {
  const today = "2026-03-20";
  assert.equal(taskAge({ createdAt: today, done: false }, today).stage, "fresh");
  assert.equal(taskAge({ createdAt: "2026-03-19", done: false }, today).stage, "fresh");
  assert.equal(taskAge({ createdAt: "2026-03-17", done: false }, today).stage, "resting");
  assert.equal(taskAge({ createdAt: "2026-03-10", done: false }, today).stage, "stale");
  assert.equal(taskAge({ createdAt: "2026-03-10", done: true }, today).stage, "done");
  // 未来日期不应产生负数天。
  assert.equal(taskAge({ createdAt: "2026-04-01", done: false }, today).days, 0);
});

test("L：天数按日历差计算，跨月与跨夏令时不出偏差", () => {
  assert.equal(daysBetween("2026-02-26", "2026-03-02"), 4);
  assert.equal(daysBetween("2026-03-07", "2026-03-09"), 2);
  assert.equal(daysBetween("bad", "2026-03-09"), 0);
});

test("M：走过的小步即进度，没有小步只有两种状态", () => {
  const task = { done: false, steps: [{ id: "a", title: "一", done: true }, { id: "b", title: "二", done: false }] };
  assert.deepEqual(taskProgress(task), { total: 2, walked: 1, percent: 50 });
  assert.equal(nextStep(task).id, "b");
  assert.deepEqual(taskProgress({ done: false, steps: [] }), { total: 0, walked: 0, percent: 0 });
  assert.deepEqual(taskProgress({ done: true, steps: [] }), { total: 0, walked: 0, percent: 100 });
  assert.equal(nextStep({ steps: [] }), null);
});

test("M：专注记录的标题快照记成「父任务 · 这一步」", () => {
  const task = { title: "梳理想法", steps: [{ id: "a", title: "画草图", done: false }] };
  assert.equal(stepFocusTitle(task, task.steps[0]), "梳理想法 · 画草图");
  assert.equal(stepFocusTitle(task, null), "梳理想法");
});

test("示例待办自带放入日期与小步，且实例相互隔离", () => {
  const today = localDateKey(new Date());
  const tasks = initialTasks();
  assert.ok(tasks.every(task => daysBetween(task.createdAt, today) >= 0));
  const planning = tasks.find(task => task.id === "sample-planning");
  assert.equal(planning.steps.length, 3);
  planning.steps[2].done = true;
  assert.equal(initialTasks().find(task => task.id === "sample-planning").steps[2].done, false);
});

test("待办列表隔离实例，空列表进度不产生 NaN", () => {
  assert.deepEqual(taskSummary([]), { total: 0, done: 0, percent: 0 });
  const tasks = initialTasks();
  assert.deepEqual(taskSummary(tasks), { total: 4, done: 1, percent: 25 });
  tasks[0].done = true;
  assert.equal(taskSummary(tasks).percent, 50);
  assert.equal(taskSummary(initialTasks()).percent, 25);
});

test("示例统计的总时长、图表与应用分项保持一致", () => {
  for (const data of Object.values(USAGE)) {
    assert.equal(data.apps.reduce((sum, app) => sum + app.minutes, 0), data.total);
    assert.equal(data.bars.reduce((sum, value) => sum + value, 0), data.total);
  }
  assert.equal(USAGE.week.bars.at(-1), USAGE.today.total);
});

test("HTML ID 唯一且 app.js 的固定 ID 引用存在", () => {
  const html = fs.readFileSync(path.join(__dirname, "index.html"), "utf8");
  const script = fs.readFileSync(path.join(__dirname, "app.js"), "utf8");
  const ids = Array.from(html.matchAll(/\bid="([^"]+)"/g), match => match[1]);
  assert.equal(ids.length, new Set(ids).size);
  for (const match of script.matchAll(/\$\("([^"]+)"\)/g)) assert.ok(ids.includes(match[1]), `缺失元素 ${match[1]}`);
});

test("原型不含外部资源、动态 HTML 注入或危险脚本执行", () => {
  const html = fs.readFileSync(path.join(__dirname, "index.html"), "utf8");
  const script = fs.readFileSync(path.join(__dirname, "app.js"), "utf8");
  const css = fs.readFileSync(path.join(__dirname, "styles.css"), "utf8");
  assert.doesNotMatch(html, /(?:src|href)="https?:\/\//);
  assert.doesNotMatch(css, /@import|url\(/);
  assert.doesNotMatch(script, /innerHTML|outerHTML|insertAdjacentHTML|\beval\s*\(|new Function\s*\(/);
});

test("新增纯接口同时通过 CommonJS 和浏览器 FocusModel 导出", () => {
  const vm = require("node:vm");
  const browser = vm.createContext({});
  vm.runInContext(fs.readFileSync(path.join(__dirname, "model.js"), "utf8"), browser);
  const common = require("./model.js");
  assert.deepEqual(Object.keys(browser.FocusModel), Object.keys(common));
  assert.equal(Object.isFrozen(browser.FocusModel), true);
  for (const name of ["localDateKey", "periodRange", "initialFocusRecords", "selectFocusRecords", "focusSummary", "focusTrend", "focusBreakdown", "monthActivity", "exportFocusCsv", "initialFocusItems", "validateFocusItem", "focusHourDistribution", "validateProgress", "initialProgressEntries", "latestProjectProgress", "allTimeFocusSummary"]) {
    assert.equal(typeof browser.FocusModel[name], "function");
    assert.equal(typeof common[name], "function");
  }
});

function focusRecord(id, endedAt, durationMinutes = 25, extra = {}) {
  return {
    id, taskId: null, taskTitle: "阅读", category: "个人成长",
    startedAt: new Date(endedAt.getTime() - durationMinutes * 60000).toISOString(),
    endedAt: endedAt.toISOString(), durationMinutes, source: "session", ...extra,
  };
}

test("本地日期及日周月年区间使用包含起点、不含终点的日历边界", () => {
  const anchor = new Date(2024, 1, 29, 19, 45);
  const snapshot = anchor.getTime();
  assert.equal(localDateKey(anchor), "2024-02-29");
  const expected = {
    day: ["2024-02-29", "2024-03-01"],
    week: ["2024-02-26", "2024-03-04"],
    month: ["2024-02-01", "2024-03-01"],
    year: ["2024-01-01", "2025-01-01"],
  };
  for (const [period, keys] of Object.entries(expected)) {
    const { start, end } = periodRange(period, anchor);
    assert.deepEqual([localDateKey(start), localDateKey(end)], keys);
    assert.equal(start.getHours(), 0);
    assert.equal(end.getHours(), 0);
    const records = [
      focusRecord("before", new Date(start.getTime() - 1)),
      focusRecord("start", start),
      focusRecord("last", new Date(end.getTime() - 1)),
      focusRecord("end", end),
    ];
    assert.deepEqual(selectFocusRecords(records, { period, anchor }).map(record => record.id), ["last", "start"]);
  }
  assert.equal(anchor.getTime(), snapshot);
  for (const period of ["", "today", "quarter", null]) assert.throws(() => periodRange(period, anchor));
  for (const invalid of [new Date(NaN), "2024-02-29", null]) {
    assert.throws(() => periodRange("day", invalid));
    assert.throws(() => localDateKey(invalid));
  }
});

test("周一开周覆盖跨年周和周日，月份和年份向后进位", () => {
  for (const anchor of [new Date(2024, 11, 30), new Date(2025, 0, 5)]) {
    const { start, end } = periodRange("week", anchor);
    assert.deepEqual([localDateKey(start), localDateKey(end)], ["2024-12-30", "2025-01-06"]);
  }
  assert.equal(localDateKey(periodRange("week", new Date(2025, 0, 6)).start), "2025-01-06");
  assert.equal(localDateKey(periodRange("month", new Date(2024, 11, 31)).end), "2025-01-01");
  assert.equal(monthActivity([], new Date(2023, 1, 1)).length, 28);
  assert.equal(monthActivity([], new Date(2024, 1, 1)).length, 29);
  assert.equal(monthActivity([], new Date(2024, 3, 1)).length, 30);
  assert.equal(monthActivity([], new Date(2024, 0, 1)).length, 31);
});

test("跨时区测试使用本地完成日，夏令时日历步进保持午夜边界", () => {
  const script = `
    const assert = require("node:assert/strict");
    const model = require("./model.js");
    const instant = new Date("2024-01-01T01:00:00.000Z");
    assert.equal(model.localDateKey(instant), process.env.EXPECTED_DAY);
    const record = { endedAt: instant.toISOString(), durationMinutes: 25, source: "session" };
    assert.equal(model.selectFocusRecords([record], { period: "day", anchor: instant }).length, 1);
    assert.equal(model.focusSummary([record]).activeDays, 1);
    assert.equal(model.focusTrend([record], "day", instant)[instant.getHours()].minutes, 25);
    assert.equal(model.monthActivity([record], instant).find(day => day.date === process.env.EXPECTED_DAY).minutes, 25);
    if (process.env.TZ === "America/New_York") {
      for (const [anchor, hours, weekHours] of [[new Date(2024, 2, 10, 12), 23, 167], [new Date(2024, 10, 3, 12), 25, 169]]) {
        const range = model.periodRange("day", anchor);
        assert.equal((range.end - range.start) / 3600000, hours);
        assert.equal(range.end.getHours(), 0);
        const week = model.periodRange("week", anchor);
        assert.equal((week.end - week.start) / 3600000, weekHours);
        assert.equal(model.focusTrend([], "day", anchor).length, 24);
        assert.equal(model.focusTrend([], "week", anchor).length, 7);
      }
      const repeated = ["2024-11-03T05:30:00Z", "2024-11-03T06:30:00Z"].map(endedAt => ({ endedAt, durationMinutes: 25 }));
      assert.equal(model.focusTrend(repeated, "day", new Date(2024, 10, 3))[1].minutes, 50);
    }
  `;
  for (const [timezone, day] of [["America/New_York", "2023-12-31"], ["Asia/Shanghai", "2024-01-01"]]) {
    execFileSync(process.execPath, ["-e", script], { cwd: __dirname, env: { ...process.env, TZ: timezone, EXPECTED_DAY: day } });
  }
});

test("示例记录确定且独立，今天恰好三次共 75 分钟并覆盖近 60 天", () => {
  const now = new Date(2024, 5, 15, 0, 1);
  const records = initialFocusRecords(now);
  assert.deepEqual(records, initialFocusRecords(now));
  assert.equal(new Set(records.map(record => record.id)).size, records.length);
  assert.equal(records.length, 132);
  assert.equal(new Set(records.map(record => localDateKey(new Date(record.endedAt)))).size, 52);
  assert.equal(allTimeFocusSummary(records, now).calendarDays, 60);
  assert.ok(new Set(records.map(record => record.durationMinutes)).size > 1);
  assert.ok(new Set(records.map(record => record.category)).size > 1);
  const today = selectFocusRecords(records, { period: "day", anchor: now });
  assert.deepEqual(focusSummary(today), { minutes: 75, count: 3, activeDays: 1, averageMinutes: 75 });
  assert.ok(today.every(record => record.durationMinutes === 25));
  assert.equal(selectFocusRecords(records, { period: "year", anchor: now, includeSamples: false }).length, 0);
  const oldest = new Date(Math.min(...records.map(record => Date.parse(record.endedAt))));
  assert.ok(localDateKey(oldest) <= "2024-04-17");
  for (const record of records) {
    assert.deepEqual(Object.keys(record).sort(), ["id", "taskId", "taskTitle", "category", "startedAt", "endedAt", "durationMinutes", "source", "timerMode", "focusItemTitle", "focusItemId", "targetMinutes", "durationSeconds", "segments"].sort());
    assert.equal(record.timerMode, "countdown");
    const project = initialFocusItems().find(item => item.id === record.focusItemId);
    assert.ok(project);
    assert.equal(record.focusItemTitle, project.title);
    assert.equal(record.category, project.category);
    assert.equal(record.targetMinutes, record.durationMinutes);
    assert.equal(record.durationSeconds, record.durationMinutes * 60);
    assert.deepEqual(record.segments, [{ startedAt: record.startedAt, endedAt: record.endedAt }]);
    assert.equal(record.source, "sample");
    assert.ok(record.taskId === null || typeof record.taskId === "string");
    assert.equal(Date.parse(record.endedAt) - Date.parse(record.startedAt), record.durationMinutes * 60000);
    assert.ok(localDateKey(new Date(record.endedAt)) <= localDateKey(now));
  }
  records[0].taskTitle = "已修改";
  assert.notEqual(initialFocusRecords(now)[0].taskTitle, "已修改");
  assert.equal(now.getTime(), new Date(2024, 5, 15, 0, 1).getTime());
});

test("筛选排除示例并按完成时间降序，汇总按活跃日平均", () => {
  const anchor = new Date(2024, 1, 15);
  const records = [
    focusRecord("a", new Date(2024, 1, 1, 9), 25),
    focusRecord("b", new Date(2024, 1, 1, 12), 35, { source: "sample" }),
    focusRecord("c", new Date(2024, 1, 3, 12), 30),
  ];
  assert.deepEqual(selectFocusRecords(records, { period: "month", anchor }).map(record => record.id), ["c", "b", "a"]);
  assert.deepEqual(selectFocusRecords(records, { period: "month", anchor, includeSamples: false }).map(record => record.id), ["c", "a"]);
  assert.deepEqual(focusSummary(records), { minutes: 90, count: 3, activeDays: 2, averageMinutes: 45 });
  assert.deepEqual(focusSummary([]), { minutes: 0, count: 0, activeDays: 0, averageMinutes: 0 });
});

test("趋势以完成时间整笔入箱，按小时、日期和月份补齐零值", () => {
  const anchor = new Date(2024, 1, 29);
  const record = focusRecord("cross", new Date(2024, 1, 29, 0, 10), 25);
  const records = [record, focusRecord("outside", new Date(2024, 2, 1), 40)];
  const day = focusTrend(records, "day", anchor);
  assert.equal(day.length, 24);
  assert.deepEqual(day[0], { key: "00", label: "00:00", minutes: 25 });
  assert.deepEqual(day[23], { key: "23", label: "23:00", minutes: 0 });
  assert.equal(day.reduce((sum, item) => sum + item.minutes, 0), 25);
  const week = focusTrend([record], "week", anchor);
  assert.equal(week.length, 7);
  assert.equal(week[0].key, "2024-02-26");
  assert.equal(week[0].label, "02-26");
  const month = focusTrend(records, "month", anchor);
  assert.equal(month.length, 29);
  assert.deepEqual(month[28], { key: "2024-02-29", label: "29", minutes: 25 });
  const year = focusTrend(records, "year", anchor);
  assert.equal(year.length, 12);
  assert.deepEqual(year[1], { key: "2024-02", label: "02", minutes: 25 });
  assert.deepEqual(year[2], { key: "2024-03", label: "03", minutes: 40 });
  for (const period of ["day", "week", "month", "year"]) {
    assert.ok(focusTrend([], period, anchor).every(item => item.minutes === 0));
  }
  assert.throws(() => focusTrend([], "invalid", anchor));
});

test("分类和任务分组按分钟降序，平手维持首次出现顺序，支持特殊名称", () => {
  const records = [
    focusRecord("a", new Date(2024, 1, 1), 25, { category: "工作", taskTitle: "__proto__" }),
    focusRecord("b", new Date(2024, 1, 2), 50, { category: "生活", taskTitle: "第二项" }),
    focusRecord("c", new Date(2024, 1, 3), 25, { category: "工作", taskTitle: "__proto__" }),
    focusRecord("d", new Date(2024, 1, 4), 10, { category: "个人成长", taskTitle: "第三项" }),
  ];
  assert.deepEqual(focusBreakdown(records, "category"), [
    { name: "工作", minutes: 50, count: 2 }, { name: "生活", minutes: 50, count: 1 }, { name: "个人成长", minutes: 10, count: 1 },
  ]);
  assert.deepEqual(focusBreakdown(records, "taskTitle")[0], { name: "__proto__", minutes: 50, count: 2 });
  assert.deepEqual(focusBreakdown([], "category"), []);
  assert.throws(() => focusBreakdown(records, "source"));
});

test("汇总、趋势、分类和月日历总和一致，所有 API 均不修改输入", () => {
  const anchor = new Date(2024, 1, 29, 12);
  const records = Object.freeze(initialFocusRecords(anchor).map(Object.freeze));
  const snapshot = JSON.stringify(records);
  const anchorTime = anchor.getTime();
  for (const period of ["day", "week", "month", "year"]) {
    const selected = selectFocusRecords(records, { period, anchor });
    const summary = focusSummary(selected);
    assert.equal(focusTrend(records, period, anchor).reduce((sum, item) => sum + item.minutes, 0), summary.minutes);
    for (const field of ["category", "taskTitle"]) {
      const breakdown = focusBreakdown(selected, field);
      assert.equal(breakdown.reduce((sum, item) => sum + item.minutes, 0), summary.minutes);
      assert.equal(breakdown.reduce((sum, item) => sum + item.count, 0), summary.count);
    }
  }
  const calendar = monthActivity(records, anchor);
  const summary = focusSummary(selectFocusRecords(records, { period: "month", anchor }));
  assert.equal(calendar.reduce((sum, day) => sum + day.minutes, 0), summary.minutes);
  assert.equal(calendar.reduce((sum, day) => sum + day.count, 0), summary.count);
  assert.deepEqual(monthActivity([], anchor)[0], { date: "2024-02-01", minutes: 0, count: 0 });
  assert.equal(calendar.at(-1).date, "2024-02-29");
  exportFocusCsv(records);
  assert.equal(JSON.stringify(records), snapshot);
  assert.equal(anchor.getTime(), anchorTime);
});

test("CSV 使用 BOM、中文表头、CRLF 和双引号转义，并阻止用户文本公式注入", () => {
  const record = focusRecord("csv", new Date(2024, 1, 29, 12, 30), 25, {
    taskTitle: '读书,"笔记"\r\n下一行', category: "个人成长", source: "sample",
  });
  const header = '\uFEFF"日期","任务","分类","开始时间","结束时间","时长（分钟）","来源","计时模式","专注项","目标时长（分钟）","时长（秒）","学习进度","完成度（%）","进度更新时间"\r\n';
  assert.equal(exportFocusCsv([]), header);
  assert.equal(exportFocusCsv([record]), header + `"2024-02-29","读书,""笔记""\r\n下一行","个人成长","2024-02-29 12:05:00","2024-02-29 12:30:00","25","示例","倒计时","读书,""笔记""\r\n下一行","25","1500","","",""\r\n`);
  for (const text of ['=1+1', '+SUM(A1)', '-1+2', '@SUM(A1)', '  =1+1', '\t=1+1', '\r\n+1', '\u00a0@SUM(A1)', '="引号,换行\n"']) {
    const csv = exportFocusCsv([{ ...record, taskTitle: text, category: text, focusItemTitle: text, source: "session" }]);
    const escaped = `"'${text.replaceAll('"', '""')}"`;
    assert.ok(csv.includes(`${escaped},${escaped},`));
    assert.ok(csv.endsWith(`,"专注计时","倒计时",${escaped},"25","1500","","",""\r\n`));
  }
});

test("专注项独立可复用，输入清理且不携带待办字段", () => {
  const items = initialFocusItems();
  assert.equal(items.length, 3);
  assert.deepEqual(items[0], { id: "focus-reading", title: "Spring Boot 实战", category: "个人成长", timerMode: "countdown", durationMinutes: 25 });
  assert.deepEqual(items[1], { id: "focus-coding", title: "个人 APP 开发", category: "工作", timerMode: "countup", durationMinutes: 50 });
  assert.deepEqual(items[2], { id: "focus-exercise", title: "算法与数据结构", category: "个人成长", timerMode: "countdown", durationMinutes: 15 });
  assert.ok(LIMITS.maxFocusItems >= items.length);
  items[0].title = "修改";
  assert.equal(initialFocusItems()[0].title, "Spring Boot 实战");
  assert.equal(initialTasks()[0].title, "阅读《原子习惯》");
  const valid = { id: "ignored", title: "  阅读  ", category: "个人成长", timerMode: "countup", durationMinutes: "120", done: true };
  assert.deepEqual(validateFocusItem(valid), { title: "阅读", category: "个人成长", timerMode: "countup", durationMinutes: 120 });
  for (const title of ["", "  ", "a".repeat(81)]) assert.throws(() => validateFocusItem({ ...valid, title }));
  assert.equal(validateFocusItem({ ...valid, title: "a".repeat(80), durationMinutes: 1 }).title.length, 80);
  for (const durationMinutes of [0, -1, 121, 1.5, "", "bad", Infinity, null]) assert.throws(() => validateFocusItem({ ...valid, durationMinutes }));
  for (const timerMode of ["focus", "short", "", null]) assert.throws(() => validateFocusItem({ ...valid, timerMode }));
  assert.throws(() => validateFocusItem({ ...valid, category: "未知" }));
});

test("正计时达到目标继续运行，实际秒数向下取整且只手动完成一次", () => {
  const { timer, advance } = fixture();
  assert.equal(timer.timerMode, "countdown");
  timer.configureFocus({ timerMode: "countup", durationMinutes: 1 });
  assert.equal(timer.hasProgress, false);
  assert.equal(timer.elapsedSeconds, 0);
  assert.equal(timer.finish(), null);
  timer.start();
  advance(999);
  assert.equal(timer.elapsedMs, 999);
  assert.equal(timer.elapsedSeconds, 0);
  assert.equal(timer.hasProgress, true);
  assert.equal(timer.finish(), null);
  assert.equal(timer.running, true);
  assert.equal(timer.completed, false);
  advance(59001);
  assert.equal(timer.targetReached, true);
  assert.equal(timer.remainingMs, 0);
  assert.equal(timer.tick(), null);
  assert.equal(timer.running, true);
  assert.equal(timer.completed, false);
  advance(2499);
  assert.equal(timer.elapsedMs, 62499);
  assert.equal(timer.elapsedSeconds, 62);
  assert.deepEqual(timer.finish(), { mode: "focus", minutes: 62 / 60 });
  assert.equal(timer.running, false);
  assert.equal(timer.deadline, null);
  assert.equal(timer.completed, true);
  advance(10000);
  assert.equal(timer.elapsedMs, 62499);
  assert.equal(timer.finish(), null);
  assert.equal(timer.tick(), null);
  assert.equal(timer.start(), false);
});

test("正计时暂停排除等待，暂停状态可提前完成，重置彻底清空进度", () => {
  const { timer, advance } = fixture();
  timer.configureFocus({ timerMode: "countup", durationMinutes: 50 });
  timer.start();
  advance(750);
  timer.pause();
  advance(900000);
  assert.equal(timer.elapsedMs, 750);
  assert.equal(timer.finish(), null);
  assert.equal(timer.running, false);
  timer.start();
  advance(500);
  timer.pause();
  assert.equal(timer.elapsedMs, 1250);
  assert.equal(timer.targetReached, false);
  assert.deepEqual(timer.finish(), { mode: "focus", minutes: 1 / 60 });
  timer.reset();
  assert.equal(timer.elapsedMs, 0);
  assert.equal(timer.hasProgress, false);
  assert.equal(timer.targetReached, false);
  assert.equal(timer.completed, false);
  assert.equal(timer.remainingMs, 3000000);
  assert.equal(timer.timerMode, "countup");
  assert.equal(timer.start(), true);
  advance(1000);
  assert.deepEqual(timer.finish(), { mode: "focus", minutes: 1 / 60 });
});

test("配置专注项原子校验并回到专注模式，休息仍然倒计时且限制进度", () => {
  const { timer, advance } = fixture();
  timer.selectMode("long");
  timer.start();
  advance(1500);
  const before = { durations: { ...timer.durations }, mode: timer.mode, timerMode: timer.timerMode, deadline: timer.deadline };
  for (const config of [{ timerMode: "bad", durationMinutes: 1 }, ...[0, 121, 1.5, "", NaN].map(durationMinutes => ({ timerMode: "countup", durationMinutes }))]) {
    assert.throws(() => timer.configureFocus(config));
    assert.deepEqual({ durations: timer.durations, mode: timer.mode, timerMode: timer.timerMode, deadline: timer.deadline }, before);
    assert.equal(timer.running, true);
    assert.equal(timer.elapsedMs, 1500);
  }
  timer.configureFocus({ timerMode: "countup", durationMinutes: "120" });
  assert.equal(timer.mode, "focus");
  assert.equal(timer.totalMs, 7200000);
  assert.equal(timer.running, false);
  assert.equal(timer.elapsedMs, 0);
  assert.equal(timer.durations.long, 15);
  for (const mode of ["short", "long"]) {
    timer.selectMode(mode);
    timer.start();
    advance(timer.totalMs + 1000);
    assert.equal(timer.elapsedMs, timer.totalMs);
    assert.equal(timer.targetReached, false);
    assert.equal(timer.finish(), null);
    assert.deepEqual(timer.tick(), { mode, minutes: MODES[mode] });
  }
  timer.configureFocus({ timerMode: "countdown", durationMinutes: 1 });
  timer.start();
  advance(70000);
  assert.equal(timer.elapsedSeconds, 60);
  assert.equal(timer.finish(), null);
  assert.deepEqual(timer.tick(), { mode: "focus", minutes: 1 });
  timer.configureFocus({ timerMode: "countup", durationMinutes: 1 });
  assert.equal(timer.completed, false);
  assert.equal(timer.hasProgress, false);
});

function activeRecord(id, segments, durationSeconds, extra = {}) {
  return focusRecord(id, new Date(segments.at(-1).endedAt), durationSeconds / 60, {
    startedAt: segments[0].startedAt, durationSeconds, segments, timerMode: "countup", ...extra,
  });
}

function localSegment(year, month, day, hour, minute, durationMs) {
  const start = new Date(year, month, day, hour, minute);
  return { startedAt: start.toISOString(), endedAt: new Date(start.getTime() + durationMs).toISOString() };
}

function closeMinutes(actual, expected) {
  assert.ok(Math.abs(actual - expected) < 1e-8, `${actual} != ${expected}`);
}

test("小时分布按活动片段跨小时和午夜分摊，不把暂停计入且不再裁剪日期", () => {
  const segments = [localSegment(2024, 1, 28, 23, 50, 20 * 60000), localSegment(2024, 1, 29, 1, 50, 20 * 60000)];
  const record = activeRecord("paused", segments, 2400);
  const snapshot = JSON.stringify(record);
  const selected = selectFocusRecords([record], { period: "day", anchor: new Date(2024, 1, 29) });
  const bins = focusHourDistribution(selected);
  assert.equal(bins.length, 24);
  for (let hour = 0; hour < 24; hour += 1) {
    const key = String(hour).padStart(2, "0");
    assert.deepEqual(bins[hour], { key, label: `${key}:00`, minutes: [0, 1, 2, 23].includes(hour) ? 10 : 0 });
  }
  assert.equal(bins.reduce((sum, bin) => sum + bin.minutes, 0), 40);
  assert.equal(focusTrend([record], "day", new Date(2024, 1, 29))[2].minutes, 40);
  assert.ok(focusHourDistribution([]).every(bin => bin.minutes === 0));
  assert.equal(JSON.stringify(record), snapshot);
});

test("秒级统计不丢失小数，片段毫秒差异按记录秒数归一化", () => {
  const anchor = new Date(2024, 1, 29, 12);
  const records = Array.from({ length: 60 }, (_, index) => activeRecord(String(index), [localSegment(2024, 1, 29, 9, 0, 1000)], 1));
  assert.equal(focusSummary(records).minutes, 1);
  assert.equal(focusSummary(records).averageMinutes, 1);
  assert.equal(focusTrend(records, "day", anchor)[9].minutes, 1);
  assert.equal(focusBreakdown(records, "taskTitle")[0].minutes, 1);
  assert.equal(monthActivity(records, anchor).at(-1).minutes, 1);
  assert.equal(focusHourDistribution(records)[9].minutes, 1);
  for (const apiMinutes of [focusSummary(records.slice(0, 1)).minutes, focusHourDistribution(records.slice(0, 1))[9].minutes]) closeMinutes(apiMinutes, 1 / 60);
  const segment = { startedAt: new Date(2024, 1, 29, 9, 59, 59, 500).toISOString(), endedAt: new Date(2024, 1, 29, 10, 0, 0, 750).toISOString() };
  const normalized = focusHourDistribution([activeRecord("fraction", [segment], 1)]);
  closeMinutes(normalized[9].minutes, 0.4 / 60);
  closeMinutes(normalized[10].minutes, 0.6 / 60);
  closeMinutes(normalized.reduce((sum, bin) => sum + bin.minutes, 0), 1 / 60);
  assert.equal(focusSummary([focusRecord("a", anchor, 0.1), focusRecord("b", anchor, 0.2)]).minutes, 0.3);
});

test("旧记录按起止跨度比例分摊，跨度无效则使用开始时间加实际时长", () => {
  const record = focusRecord("legacy", new Date(2024, 1, 29, 11), 30, { startedAt: new Date(2024, 1, 29, 9).toISOString() });
  const bins = focusHourDistribution([record]);
  assert.equal(bins[9].minutes, 15);
  assert.equal(bins[10].minutes, 15);
  const fallback = focusHourDistribution([{ ...record, endedAt: record.startedAt }]);
  assert.equal(fallback[9].minutes, 30);
});

test("周月年小时分布使用前置完成日期及来源筛选且与汇总一致", () => {
  const anchor = new Date(2024, 5, 15);
  const records = [...initialFocusRecords(anchor), activeRecord("real", [localSegment(2024, 5, 15, 8, 59, 61000)], 61)];
  for (const period of ["week", "month", "year"]) {
    for (const includeSamples of [true, false]) {
      const selected = selectFocusRecords(records, { period, anchor, includeSamples });
      const total = focusHourDistribution(selected).reduce((sum, bin) => sum + bin.minutes, 0);
      closeMinutes(total, focusSummary(selected).minutes);
    }
  }
});

test("小时分布按真实流逝时间处理夏令时跳过、重复及半小时转换", () => {
  const script = `
    const assert = require("node:assert/strict");
    const { focusHourDistribution } = require("./model.js");
    const cases = process.env.TZ === "America/New_York" ? [
      ["2024-03-10T06:30:00Z", "2024-03-10T07:30:00Z", { 1: 30, 2: 0, 3: 30 }],
      ["2024-11-03T04:30:00Z", "2024-11-03T07:30:00Z", { 0: 30, 1: 120, 2: 30 }],
      ["2024-11-03T05:45:00Z", "2024-11-03T06:15:00Z", { 1: 30 }],
    ] : [
      ["2024-04-06T14:30:00Z", "2024-04-06T16:00:00Z", { 1: 60, 2: 30 }],
      ["2024-10-05T15:00:00Z", "2024-10-05T16:00:00Z", { 1: 30, 2: 30 }],
    ];
    for (const [startedAt, endedAt, expected] of cases) {
      const durationSeconds = (Date.parse(endedAt) - Date.parse(startedAt)) / 1000;
      const bins = focusHourDistribution([{ startedAt, endedAt, durationSeconds, durationMinutes: durationSeconds / 60, segments: [{ startedAt, endedAt }] }]);
      for (const [hour, minutes] of Object.entries(expected)) assert.equal(bins[hour].minutes, minutes);
      assert.equal(bins.reduce((sum, bin) => sum + bin.minutes, 0), durationSeconds / 60);
    }
  `;
  for (const TZ of ["America/New_York", "Australia/Lord_Howe"]) execFileSync(process.execPath, ["-e", script], { cwd: __dirname, env: { ...process.env, TZ } });
});

test("小时分布所有小时的小数舍入后仍保持总秒数", () => {
  const segments = Array.from({ length: 24 }, (_, hour) => localSegment(2024, 1, 29, hour, 0, 1001));
  const bins = focusHourDistribution([activeRecord("rounding", segments, 24)]);
  assert.ok(Math.abs(bins.reduce((sum, bin) => sum + bin.minutes, 0) - 24 / 60) < 1e-9);
});

test("计时进度读取不倒退，旧剩余毫秒写入和时长重设保持兼容", () => {
  const { timer, advance } = fixture();
  timer.configureFocus({ timerMode: "countup", durationMinutes: 1 });
  timer.start();
  advance(2000);
  assert.equal(timer.elapsedMs, 2000);
  advance(-500);
  assert.equal(timer.elapsedMs, 2000);
  timer.pause();
  assert.equal(timer.elapsedMs, 2000);
  timer.start();
  advance(500);
  assert.equal(timer.elapsedMs, 2500);
  timer.setDurations({ focus: 2, short: 3, long: 4 });
  assert.equal(timer.timerMode, "countup");
  assert.equal(timer.hasProgress, false);
  assert.equal(timer.remainingMs, 120000);
  timer.configureFocus({ timerMode: "countdown", durationMinutes: 1 });
  timer.remainingMs = 1000;
  timer.start();
  advance(1000);
  assert.deepEqual(timer.tick(), { mode: "focus", minutes: 1 });
});

test("CSV 在原有七列后追加模式、独立专注项、目标和实际整秒", () => {
  const record = focusRecord("csv-seconds", new Date(2024, 1, 29, 12), 61 / 60, {
    timerMode: "countup", focusItemTitle: "编程", targetMinutes: 50, durationSeconds: 61,
  });
  assert.ok(exportFocusCsv([record]).endsWith(',"专注计时","正计时","编程","50","61","","",""\r\n'));
  assert.ok(exportFocusCsv([{ ...record, timerMode: "countdown", durationMinutes: 25, durationSeconds: 1500 }]).endsWith(',"专注计时","倒计时","编程","50","1500","","",""\r\n'));
});
