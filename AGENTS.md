# 项目约定

- 用户已确认原型与原生开发范围（含日记模块，见 `rules/diary.md`）。根目录保留「专注助手」浏览器原型，`android/` 是 Kotlin + Compose 原生工程；下文涉及 DOM 和内存示例数据的规则仅适用于浏览器原型。Android 使用本机真实数据，不得用示例数据替代未授权或缺失记录。
- 浏览器原型与 Android 原生页面必须保持功能、信息层级、交互方式和关键状态同步；改动任一端时要对照另一端并完成同步。用户明确要求先改一端并等待确认时，该轮可暂不同步，但确认后须补齐另一端，不能将阶段性单端实现视为完成。
- 运行界面没有第三方依赖、远程资源或构建步骤。使用现代 Edge / Chrome，直接打开 `index.html` 即可。
- `model.js` 是可独立测试的计时与待办模型；`app.js` 负责 DOM 交互；`styles.css` 负责响应式界面。
- 所有原型数据仅驻留内存，刷新恢复示例。备份、权限、系统通知、用时统计与自动化执行不得描述为已接入真实手机。
- 动态用户文本使用 `textContent`，不得使用 HTML 字符串注入。浏览器计时使用可注入的单调时钟，不把原型计时当成 Android 后台服务。
- 用户选定白底暖橙与中性灰，不使用绿色主题；CSS 兼容变量 `--green` 等只是旧名称，实际值应引用暖橙强调色。
- 当前工作目录已初始化 Git，主分支为 `main`。`docs/`、`.tools/`、构建产物和本地配置不提交；文档仅留在本机，需要另行备份。
- 没有连接设备时，构建与单元测试通过不等于真机 UI、权限和后台行为验证通过。

## 模块规则（按需读取）

根文件只放全局硬规则。动手前只读与任务相关的模块文件，不必通读全部：

| 任务涉及 | 读取 |
| --- | --- |
| 专注首页、计时页、项目编辑、专注记录与进度 | `rules/focus.md` |
| 待办（发酵、小步、已完成、搜索、编辑面板、左滑删除） | `rules/todo.md` |
| 专注统计页与导出 | `rules/statistics.md` |
| 用时页（浏览器 + Android） | `rules/usage.md` |
| 日记（浏览器 + Android） | `rules/diary.md` |
| 我的页面（资产快照、心愿、生活概览） | `rules/profile.md` |
| 求职辅助（BOSS 自动投递无障碍服务、本地职位草稿） | `rules/automation.md` |
| 验证命令、Android 构建与签名、截图测试、GitHub Pages | `rules/verify.md` |

新增或修改某模块的约定时写进对应模块文件；只有跨模块的硬规则才写回本文件。

## 协作方式

- **需要联网下载、或耗时超过一两分钟的构建与测试，给出纯 PowerShell 命令让用户执行并贴回输出，不要自己反复起构建干等。** 助手沙箱没有外网（`curl` 直接 exit 35），用户本机有；长构建期间助手既看不到进度也做不了别的事。助手负责改代码和读报错，用户负责跑。
- **助手自己执行的命令一律限时 2 分钟（Bash 工具 `timeout: 120000`，或命令前加 `timeout 120`）。超时就放弃，不重试、不改长时限，直接把等价的 PowerShell 命令交给用户执行。** Gradle 任务（含 `compileDebugKotlin`、`testDebugUnitTest`、`lintDebug`、截图测试）和完整浏览器回归 `browser_check.py` 默认都按「可能超时」处理，直接给命令，不先自己试跑。另外 `android/verify.ps1` 在助手的 bash 里结束时会因 `SetConsoleWindowTitle` 报错，且 daemon 占着句柄让命令迟迟不返回，这也是不要由助手执行它的原因。
- 给用户的命令必须能直接粘贴：用 `;` 分隔而不是 `&&`（PowerShell 5.1 不支持 `&&`），不带 `!` 前缀（那是 Claude Code 的输入前缀，不是 shell 语法）。
- 测试失败时，Gradle 控制台只给异常类名和行号，没有原因。真正的报错在 `android/app/build/reports/tests/testDebugUnitTest/classes/*.html`，先读这个文件再动手改。
- 改 Android 代码时同一轮就改掉 `versionCode` / `versionName`，不要等构建成功后再补，否则白跑一遍完整构建。
- 不要凭「目录里还没出现文件」判断下载是否停滞，Gradle 只在下载完成后才落盘。
- 省上下文的工作方式：先用 Grep 定位符号再按行段读取，不整文件通读大文件（`app.js`、`styles.css`、`browser_check.py`、`Screens.kt`）；互不依赖的搜索与读取放在同一轮并行发出；自己只跑两分钟内的快速检查（`node --check`、`node --test`、grep），长验证按上条交给用户。
- 用户贴回结果时，成功只需末尾几行；失败贴异常类、文件行号，或直接让助手读 HTML 测试报告，不必贴完整 Gradle 日志。
- 跨两端的改动先在一端定好纯函数规则与单测，再按同一规则移植另一端；每完成一个逻辑小块就跑对应的快速检查，不攒成一个大补丁。
- 回复里不复述大段代码或本文件已有的规则，改动总结按「文件 · 符号 · 意图」列出即可。

## 代码位置

- 规则与纯函数集中在 `android/app/src/main/java/com/focusassistant/app/domain/`：`Todos.kt`（发酵分档与分组）、`TimerEngine.kt`（计时）、`Statistics.kt`（统计口径）、`Models.kt`（数据类与 `Validation`）。改规则优先改这里，单测也挂在这一层。
- 界面在 `ui/`：`Screens.kt` 是各屏正文，`UiSupport.kt` 是弹窗、主题色与共用组件，`FocusApp.kt` 负责导航接线和 `when (ui.dialog)` 的弹窗分发。改一个功能通常要同时动这三个文件。
- 持久化在 `data/`：`FocusRepository.kt` 是唯一写入口，Room 定义在 `FocusDatabase.kt`，备份编解码在 `BackupCodec.kt`。
- 系统能力在 `platform/`：前台服务、通知、闹钟、用时读取。
- 浏览器原型只有根目录的 `model.js`（可测纯模型）、`app.js`（DOM）、`styles.css`。
- 搜索时排除 `.tools/tutorial-check/`，那是无关的教学样例，里面另有一套 `TodoStore.kt` 等同名文件，只会干扰定位。
- 浏览器各模块定位：用时页 `renderUsage` / `usageRibbonDetail` / `goalSettings`，日记 `state.diaryView` 相关函数，统计 `#statistics` 相关渲染；先 Grep 函数名再读附近行。Android 各模块界面文件：`UsageScreens.kt`、`StatisticsScreens.kt`、`DiaryScreens.kt`、`FocusVisuals.kt`（共用刻度组件）。
