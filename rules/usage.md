# 用时页

## 浏览器原型用时页（刻度版）

- `#page-usage` 采用与专注首页一致的刻度视觉，Android 已同步（0.8.0，见下一节）：暖橙主卡左侧是总时长，右侧是 40 格目标刻度环 `#edit-goal`（点开 `goalSettings`，超出目标的格子换成深色）；周期为今日 / 本周 / 本月 / 全年（`state.period`，纯函数 `usagePeriodSummary`）。今日视图是双轨 24 小时刻度尺 `#usage-ribbon`（上轨灰色为示例用屏，下轨暖橙为 `state.records` 中真实的专注片段，23–5 点铺石板灰底）和图例「专注时拿起手机 N 次」；本周 7 天、本月按日、全年 12 个月均为「用屏灰 / 专注橙」并排柱，当前档深灰高亮，专注为 0 的档不画专注柱。周/月/年柱图轴上必须标出每一档日期（周一至周日、1–月末日、1月–12月），不得用空标签或间隔点代替；本月可横滑。默认选中当天/当月，点柱后 `#chart-caption` 显示「周X / X日 / X月 · 用屏 … · 专注 …」。石板灰「夜间足迹」卡 `#night-card` 今日显示前一晚 23 点至当天 5 点的用屏时长与最晚放下手机的时刻，其它周期显示该段示例合计。应用列表与对比文案标明示例。目标环仍只用今日时长。今日刻度尺与放大面板不受柱图改动影响。
- 点刻度尺打开 `usageRibbonDetail`（`#modal` 的 `usage-zoom-sheet` 底部面板变体）：放大时间轴每小时 56px，可左右滚动，打开时滚到当前时刻附近；下方按时间列出每段用屏与专注，与专注重叠的用屏标「专注中」，点轴上片段会高亮并滚到对应行。目标设置 `goalSettings` 用两列 scroll-snap 滚轮（`wheelColumn`，小时 0–24、分钟按 5 分钟一档，`role="listbox"`，支持方向键 / PageUp / PageDown），不在 30 分钟–24 小时范围内时保存按钮禁用；操作栏「取消」在左、「保存目标」在右。
- 示例用屏片段在 `model.js` 的 `SAMPLE_USAGE` / `sampleUsageSegments(now)`，各应用之和、每两小时之和必须与 `USAGE.today.apps` / `bars` 一致（有单测）。其它日历日由 `sampleUsageSegmentsForDate` 按 `USAGE.week.bars` 的星期模式缩放，每天单独生成以免昨夜片段计入前一日总量。`usageDaySummary` 按日历日裁剪片段，计算夜间足迹与专注重叠次数；`usagePeriodSummary` 聚合今日/本周/本月/全年；`focusSpans` 由首页 `renderTodayRibbon` 与用时页共用。用屏仍是示例，不得描述为读取了真实手机。

## Android 验证与用时页约定

- 原生用时页按浏览器原型「时间足迹」还原白底暖橙布局，但不使用原型的演示总量、昨日对比或应用名称。
- 刻度版（0.8.0 / versionCode 19）界面在 `ui/UsageScreens.kt`（`UsageScreen`、放大面板 `UsageZoomSheet`、目标滚轮 `GoalWheelSheet`，弹窗 key 为 `usage-zoom` / `usage-goal`），规则在 `domain/UsageInsights.kt`（`nightWindow` / `night` / `union` / `compact` / `focusSpans` / `overlapCount`），分桶在 `domain/UsageAggregation.kt`（`UsagePeriod`：`TODAY` / `WEEK` / `MONTH` / `YEAR`），单测 `UsageInsightsTest` / `UsageAggregationTest`，截图 `UsageScreenshotTest`。`UsageReport.spans` 是前台片段，`UsageAggregation` 的 `spanStart` 把收集起点提前到首日前一晚 23 点，只供夜间足迹使用，不计入总量与分桶。刻度尺用屏取多窗口并集；放大面板把同一应用间隔不足 1 分钟的片段合并（`compact`），仅用于展示。「专注时打开手机」按用屏并集中与专注片段相交的段数计。目标滚轮小时 0–24、分钟 5 分钟一档，旧目标取最近一档，30 分钟到 24 小时之外禁用保存；`Validation` 仍接受 1–1440。首页 `TodayPanel` 与用时页共用 `UsageInsights.focusSpans`。
- 周期为今日 / 本周 / 本月 / 全年，查询真实系统前台事件，不用示例数据。今日仍为 12 个本地两小时时段；本周为周一至周日 7 天；本月按当月日历日（28–31 桶，可横滑）；全年 12 个自然月。周/月/年图表每天或每月「用屏灰 / 专注橙」并排，当前桶深灰高亮。轴标签与浏览器同口径：周一至周日用「一」…「日」，本月用日期数字，全年用「1月」…「12月」，全部可见；默认选中当天/当月，点柱后正文显示「周X / X日 / X月 · 用屏 … · 专注 …」（无数据为「暂无数据或尚未开始」）。应用汇总和图表必须来自同一批有效前台片段。目标刻度环与目标文案在所有周期仍只用 `todayMillis`（今日数据），不把周/月/年总量当成每日目标进度。
- 未确认数据与未来时段显示未知占位，不冒充真实零用时；桶内有事件只表明存在系统记录，不保证整段历史完整。系统保留期通常短于月/年，这些视图里大量「—」是预期。跨午夜和夏令时使用本地日历边界。夜间足迹：今日看前一晚 23 点至当天 5 点，周/月/年从该周期首日累加到今天。
