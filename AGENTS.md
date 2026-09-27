# 项目约定

- 用户已确认原型与原生开发范围。根目录保留「专注助手」浏览器原型，`android/` 是 Kotlin + Compose 原生工程；下文涉及 DOM 和内存示例数据的规则仅适用于浏览器原型。Android 使用本机真实数据，不得用示例数据替代未授权或缺失记录。
- 运行界面没有第三方依赖、远程资源或构建步骤。使用现代 Edge / Chrome，直接打开 `index.html` 即可。
- `model.js` 是可独立测试的计时与待办模型；`app.js` 负责 DOM 交互；`styles.css` 负责响应式界面。
- 所有原型数据仅驻留内存，刷新恢复示例。备份、权限、系统通知、用时统计与自动化执行不得描述为已接入真实手机。
- 动态用户文本使用 `textContent`，不得使用 HTML 字符串注入。浏览器计时使用可注入的单调时钟，不把原型计时当成 Android 后台服务。
- 用户选定白底暖橙与中性灰，不使用绿色主题；CSS 兼容变量 `--green` 等只是旧名称，实际值应引用暖橙强调色。
- 专注首页 `#focus` 展示可反复使用的学习项目，与待办完全独立，专注记录不记待办；待办卡片不提供专注入口，计时页不提供关联待办入口。点击项目主体进入独立计时页 `#timer`，右侧展开按钮只展示项目进度。返回首页不暂停或重置计时，同一项目可继续，切换不同项目需确认放弃未保存进度。
- 每个项目保存名称、分类、倒计时或正计时模式、时长。浏览器原型仍为 1–120 分钟；Android 已改为小时+分钟（1 分钟至 23 小时 59 分），倒计时必须有时长，正计时目标可为空表示不限时。倒计时到时完成；正计时目标只提示不停止，不限时永不自动完成，手动结束至少 1 秒后记录实际整秒数，暂停时间不计入。
- Android 倒计时可以提前结束：需用户在确认框同意，按已专注的实际整秒保存，暂停时间不计入；直接「放弃」仍不生成记录。到零由计时循环自动保存，自动路径不接受未到零。浏览器原型未改，仍只能到零或放弃。
- Android 计时页只在未开始计时时显示模式切换与「调整/设置」时长入口，二者都直接写回项目，没有仅本次生效的临时覆盖。「调整」打开的是同一个底部面板的只调时长模式，隐藏名称、分类与模式，保存后留在计时页。
- 待办页用「发酵 + 小路」两条规则（浏览器原型与 Android 同规则，Android 侧在 `domain/Todos.kt` 的 `TodoAging`），没有截止日期和预估时长：每件事记 `createdAt`，按日历天差（`daysBetween`，不按 24 小时整除，避免夏令时偏差）分 fresh / resting（3 天）/ stale（7 天）三档，卡片越躺越浅；已完成的事不参与发酵，未来日期按 0 天处理。
- 浏览器原型待办页按组查看：标为重要的事跨分类自成一组置顶，其余按 `CATEGORIES`（个人成长 / 工作 / 生活）顺序分组，组标题带件数，空组不显示。已完成的事沉到各组末尾，同组内保持原有先后。组标题已说明重要或分类时，卡片上不重复同一个标签。筛选页签与分组叠加生效。Android 同规则，实现在 `TodoGrouping.group`，分类顺序由它统一提供（`UiLimits.CATEGORIES` 引用同一份），已完成的事不再单独分「待完成 / 已完成」两段，而是沉到各组末尾。
- 躺久了的事只给「续一天」和「放下」两个出口。「续一天」把 `createdAt` 重置为今天，「放下」只置 `archived` 不删除，在「放下的」页签可以找回。
- 一件事可拆最多 8 个小步，每步 1–40 字，横向小路上走过的点是实心的。点圆点切换该步走过/退回；全部走完父任务自动完成，勾掉父任务会把剩余小步一并算走过，任一步回退父任务也回到未完成。
- 小步不是独立实体，只影响待办自身进度，不产生专注记录。Android `FocusSession` / `ActiveTimer` 不再有 `taskId`、`taskTitle`，`Todo` 不再有 `estimate`；旧备份与旧库 JSON 中这些字段忽略读入，新写出数据不包含这些字段。Room 表结构不变，不需要迁移。
- Android 的 `Todo` 带 `createdAt`（本地 `YYYY-MM-DD`）、`steps`、`archived`。旧备份缺这些字段时按「放入日期为空、没有小步、未放下」读入，不臆造历史；`createdAt` 为空按 0 天处理，保存新待办时才填今天。
- 专注结束立即保存时长，再提示填写文字进度和可选 0–100% 完成度；允许跳过，之后在项目详情或专注记录中补写。已有弹窗不应被结束提示覆盖，须排队等待关闭。进度草稿和记录一样仅驻留内存。
- 每次进度保存追加历史版本；当前进度按专注结束日期选最新，补写旧记录不覆盖较新记录。用户填写的进度优先于演示进度，删除项目不删除历史专注与进度记录。
- 专注统计位于 `#statistics`，顶部累计总览不随周期和日期筛选变化，但遵循来源筛选。自然日均按首次完成日至今天（含空闲日）计算，活跃日均只按有记录日计算；空数据均显示 0。自然日天数使用日历差，不按 24 小时除法，以免夏令时偏差。
- 下方周期统计按本地结束日期筛选，周一开周，周期日均仍按活跃日计算。不计休息、暂停和被放弃的计时。
- 日、周、月、年都有 24 小时专注时段分布；对筛选出的完成记录按实际活动片段拆分小时，不再次按日期裁剪片段。周、月、年另外保留完成时长趋势图。
- 专注记录保留开始时的专注项名称、模式、目标及分类快照，后续编辑或删除事项不追改历史记录；活动片段与有效整秒用于分布和导出，示例与本次计时使用不同来源标记。
- 专注统计可实际下载 CSV 或 JSON。CSV 使用 UTF-8 BOM、CRLF 和字段转义，防止用户文本触发电子表格公式；附带每次专注最新的进度文字、完成度和更新时间。JSON 另含对应进度历史、时段分布与来源筛选后的累计总览，不是完整 APP 备份。明细与进度历史遵循当前日期及来源筛选，包含未在分页中展示的记录。CSV 已删除「任务」列，列数已变，旧导出文件与新表头不兼容，不能直接按相同列位置拼接。

## 协作方式

- **需要联网下载、或耗时超过一两分钟的构建与测试，给出纯 PowerShell 命令让用户执行并贴回输出，不要自己反复起构建干等。** 助手沙箱没有外网（`curl` 直接 exit 35），用户本机有；长构建期间助手既看不到进度也做不了别的事。助手负责改代码和读报错，用户负责跑。
- 给用户的命令必须能直接粘贴：用 `;` 分隔而不是 `&&`（PowerShell 5.1 不支持 `&&`），不带 `!` 前缀（那是 Claude Code 的输入前缀，不是 shell 语法）。
- 测试失败时，Gradle 控制台只给异常类名和行号，没有原因。真正的报错在 `android/app/build/reports/tests/testDebugUnitTest/classes/*.html`，先读这个文件再动手改。
- 改 Android 代码时同一轮就改掉 `versionCode` / `versionName`，不要等构建成功后再补，否则白跑一遍完整构建。
- 不要凭「目录里还没出现文件」判断下载是否停滞，Gradle 只在下载完成后才落盘。

## 代码位置

- 规则与纯函数集中在 `android/app/src/main/java/com/focusassistant/app/domain/`：`Todos.kt`（发酵分档与分组）、`TimerEngine.kt`（计时）、`Statistics.kt`（统计口径）、`Models.kt`（数据类与 `Validation`）。改规则优先改这里，单测也挂在这一层。
- 界面在 `ui/`：`Screens.kt` 是各屏正文，`UiSupport.kt` 是弹窗、主题色与共用组件，`FocusApp.kt` 负责导航接线和 `when (ui.dialog)` 的弹窗分发。改一个功能通常要同时动这三个文件。
- 持久化在 `data/`：`FocusRepository.kt` 是唯一写入口，Room 定义在 `FocusDatabase.kt`，备份编解码在 `BackupCodec.kt`。
- 系统能力在 `platform/`：前台服务、通知、闹钟、用时读取。
- 浏览器原型只有根目录的 `model.js`（可测纯模型）、`app.js`（DOM）、`styles.css`。
- 搜索时排除 `.tools/tutorial-check/`，那是无关的教学样例，里面另有一套 `TodoStore.kt` 等同名文件，只会干扰定位。

## Windows 验证命令

验证入口已固化成脚本，不必每次重拼环境变量。两个脚本都必须保留 UTF-8 BOM，否则 PowerShell 5.1 会按 GBK 读中文注释并报语法错误。

浏览器原型，在项目根目录运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\verify.ps1
```

等价于下面三条，仍可单独执行：

```powershell
node --check app.js
node --check model.js
node --test model.test.cjs
```

启动仅本机可访问的预览服务：

```powershell
uv run --no-project --python 3.12 python -m http.server 5173 --bind 127.0.0.1
```

浏览器回归需要已安装 Microsoft Edge，以及正在运行的上述预览服务。在另一终端运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\verify.ps1 -Browser
```

`-Browser` 在语法检查与单测之后追加下面这条：

```powershell
uv run --no-project --python 3.12 --with playwright==1.55.0 --with pyee==13.0.0 --with greenlet==3.2.4 python browser_check.py
```

- 测试覆盖计时、待办 CRUD 与重要置顶分类分组、文本安全、使用目标、弹窗、刷新重置、手机和平板布局、横屏表单及直接打开文件；也覆盖统计日期与来源筛选、真实 CSV/JSON 下载内容、分页完整导出、跨午夜和暂停时长、弹窗打开期间新增完成记录。
- 小步圆点用 `::before` 画，按钮本体保留可点面积，否则会被全局 `button { min-width/min-height: var(--touch-target) }` 撑成大色块。
- 浏览器模拟时钟的下载后续计时场景使用独立页面隔离，避免测试中的 Blob 下载清理定时器干扰时间推进。
- 浏览器截图输出到系统临时目录，具体位置由测试输出。
- `pyee` 和 `greenlet` 显式固定为上述已验证版本，避免临时验证依赖漂移。

## Android 验证与用时页约定

- 原生用时页按浏览器原型「时间足迹」还原白底暖橙布局，但不使用原型的演示总量、昨日对比或应用名称。
- 今日图表为 12 个本地两小时时段，本周图表为周一至周日 7 天。应用汇总和图表必须来自同一批有效前台片段；本周视图的每日目标仍仅使用今日数据。
- 未确认数据与未来时段显示未知占位，不冒充真实零用时；桶内有事件只表明存在系统记录，不保证整段历史完整。跨午夜和夏令时使用本地日历边界。
- 当前工作目录已初始化 Git，主分支为 `main`。`docs/`、`.tools/`、构建产物和本地配置不提交；文档仅留在本机，需要另行备份。
- 没有连接设备时，构建与单元测试通过不等于真机 UI、权限和后台行为验证通过。
- 本机已有校验通过的 Gradle 8.11.1。Wrapper 可能重复联网下载，`android/verify.ps1` 固定调用已安装版本，并集中 `JAVA_HOME` / `GRADLE_USER_HOME` / `ANDROID_HOME` 三个变量，缺工具链时提示先跑 `bootstrap.ps1`。在 `android/` 目录运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\verify.ps1
powershell -ExecutionPolicy Bypass -File .\verify.ps1 assembleDebug
```

不传任务即 `testDebugUnitTest assembleDebug lintDebug`；传入的参数原样交给 Gradle。脚本结束时打印 APK 路径、大小和时间，但 APK 只在 assemble 任务后才更新。

默认复用 Gradle daemon，省掉每次冷启动 JVM（冷启动约 2 分钟，daemon 复用后增量构建几秒）；怀疑 daemon 状态导致构建结果异常时加 `-NoDaemon` 排除。

用 daemon 时不要把输出管道接给 `tail` 之类的命令：daemon 会继承 stdout 句柄，管道读不到 EOF，命令看起来一直不返回，实际构建早已结束。改成重定向到文件再读：

```powershell
powershell -ExecutionPolicy Bypass -File .\verify.ps1 > build.log 2>&1
```

默认 `--console=plain` 会隐藏 Gradle 的下载进度条，大文件下载时容易误判成卡死。人工盯进度时加 `-Rich`。另外 Gradle 只在下载完成后才把文件落盘到缓存，所以「缓存目录里还没出现 jar」不能用来判断下载是否停滞。

- APK 输出为 `android/app/build/outputs/apk/debug/app-debug.apk`。保留本机调试签名和 applicationId，递增版本号便于覆盖升级；不要通过卸载来更新，否则会丢失本机数据。

## Android 截图测试

改 Compose UI 后用它核对渲染结果，不必连真机。截图默认跳过，只在显式传 `-PwithScreenshots` 时执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\verify.ps1 -Rich testDebugUnitTest -PwithScreenshots
```

- 产物为 `android/app/build/reports/screenshots/*.png`，助手可以直接读 PNG 核对布局。构建耗时约 30 秒，按上面「协作方式」交给用户跑。
- 只断言画面非空白，不做像素比对：基准图会因字体与渲染版本漂移而假警报。截图是给人看的，断言只保证图有效。
- Robolectric 自带下载器绕过 `settings.gradle.kts` 的仓库配置，会长时间挂住。改由 Gradle 用 `robolectricRuntime` configuration 解析 `android-all-instrumented`，`prepareRobolectricJars` 拷到固定目录，再用 `robolectric.offline=true` 离线复用。这个 jar 有 150 MB，`settings.gradle.kts` 里只给 `org.robolectric` 这一个 group 走阿里云镜像，其余依赖仍走 google() / mavenCentral()。
- jar 坐标必须和 Robolectric 内置清单完全一致，否则报 `Path is not a file`，报错里的文件名就是它要的版本。SDK 版本三处联动，改一处要同步全部：`build.gradle.kts` 的 `robolectricRuntime` 坐标、`src/test/resources/robolectric.properties` 的 `sdk`、测试类的 `@Config(sdk = [...])`。每个 SDK 一个 150 MB jar，因此统一固定 SDK 35。
- `androidx.compose.ui:ui-test-manifest` 必须放 `debugImplementation`。它靠清单合并注入 `ComponentActivity`，放 `testImplementation` 清单不参与合并，会报 `Unable to resolve activity for Intent`。
- 不要用 `onRoot().captureToImage()`：它走窗口级 PixelCopy 抓屏，在 Robolectric 里等不到重绘回调，固定抛 `ComposeTimeoutException`。用 `createAndroidComposeRule<ComponentActivity>()`，再 `view.draw(Canvas(bitmap))` 让根视图自己软件绘制。
- 截图与单测通过仍不等于真机验证通过：软件绘制不覆盖真实渲染、触摸、权限和后台行为。
- `fillMaxWidth()` / `fillMaxHeight()` 用在 `Row`/`Column` 子项里要警觉，它填的是剩余空间而不是和兄弟等分；多子项场景应改用 `weight(1f)` 或 `IntrinsicSize`。页签行就因此出过一次故障：选中项吃光宽度、其余页签被压成零宽文字竖排，而编译、单测和 lint 全是绿的。

## 独立专注设计稿

- `design/focus/index.html` 是专注首页与计时页的独立设计预览，可以直接用 Edge/Chrome 打开。宽屏并排展示，窄屏切换页面；计时为静态状态示意，数据仅在内存中，不能描述为真实计时或持久化。
- 用户要求先确认这份可视化方案，再修改 Android UI；原有根目录原型与 Android 工程不随设计稿自动改变。本次专注/待办解耦已获明确确认，设计稿同步移除关联待办入口。
- 设计稿新建/编辑采用小时与分钟输入，小时 0–23、分钟 0–59；倒计时合计至少 1 分钟。正计时目标可留空（`minutes: null`），新建正计时默认不设目标；不能把空目标转换为零或默认 25 分钟。无目标不展示目标进度环，超过一小时时间显示为 HH:MM:SS。模式切换保留各模式的设置；这些新增规则已迁移到 Android（`targetMinutes: Int?`，上限 23 小时 59 分），但仍未迁移到根目录浏览器原型的 `model.js`。
- Android 已按设计稿还原首页与计时页：首页含今日汇总行、分类字形、进度预览与「打开」箭头；计时页用自绘 `Canvas` 圆环，不限时不画进度环，无计时才显示模式切换。新建/编辑改为 `ModalBottomSheet`，时长用 LazyColumn 自绘滚轮（吸附滚动，小时 0–23、分钟 0–59，无法输入非法值）；草稿按项目保存在内存 `ProjectDrafts`，保存成功或恢复备份时清除，进程重启即丢弃。
- 新建/编辑项目采用独立底部面板 `#project-sheet`，以文字单选按钮切换计时模式，不使用下拉框。下划线先向新位置拉伸再收拢（320ms），快速反向从当前位置接续，减少动画模式直接切换。正计时默认显示不限时，按需展开目标输入；各模式、各项目草稿独立保留，关闭/Esc/遮罩/下拖不丢草稿，保存成功后清除对应草稿，刷新仍重置。
- 若需本机服务，在 `design/focus/` 目录运行 `uv run --no-project --python 3.12 python -m http.server 5174 --bind 127.0.0.1`。
