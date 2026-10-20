# 验证、构建与发布

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
- 浏览器小步圆点用 `span` + `::before` 仅作装饰；交互由 `.step-row` 的整行复选按钮承担，不再给小圆点绑定点击。手势测试覆盖真实鼠标拖动、CDP 触屏滑动后立即点删除、删除区纵拖/取消不删除及键盘操作。移动浏览器可能吞掉滑动后紧接的原生 click，删除按钮用受位移阈值和 touchcancel 约束的 touchend 处理短点击，并阻止后续合成点击；不能把任意 touchend 当作删除。截图验证与缩小视口测试不等同于真机软键盘验证。
- 浏览器模拟时钟的下载后续计时场景使用独立页面隔离，避免测试中的 Blob 下载清理定时器干扰时间推进。
- 浏览器截图输出到系统临时目录，具体位置由测试输出。
- `pyee` 和 `greenlet` 显式固定为上述已验证版本，避免临时验证依赖漂移。

## Android 构建

- 工程未配置固定签名，debug 包用各电脑自己的 `~/.android/debug.keystore`，换电脑打包会与手机上已装版本签名冲突。此时不要让用户卸载（会丢本机数据，日记不在完整备份内），改用 `verify.ps1 assemblePreview`：预览版包名 `com.focusassistant.app.preview`、桌面名「专注助手 预览」（`src/preview/res`），与正式包并存、数据独立。
- 本机已有校验通过的 Gradle 8.11.1。Wrapper 可能重复联网下载，本机路径不写进脚本：每台电脑把 `android/.env.example` 复制为 `android/.env`（已被 `.gitignore` 忽略）并填写 `JAVA_HOME`（可选 `ANDROID_HOME`、`GRADLE_USER_HOME`，相对路径按 `android/` 解析）；`bootstrap.ps1` 与 `verify.ps1` 都通过 `android/local-env.ps1` 读取，缺文件或路径无效时给出修改提示，`-JdkPath` 可临时覆盖，首次 `bootstrap.ps1 -JdkPath ...` 会自动生成 `.env`。新增本机相关路径时加到 `.env.example`，不要写死在脚本里。`android/verify.ps1` 固定调用已安装版本，并集中 `JAVA_HOME` / `GRADLE_USER_HOME` / `ANDROID_HOME` 三个变量，缺工具链时提示先跑 `bootstrap.ps1`。在 `android/` 目录运行：

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

- 产物为 `android/app/build/reports/screenshots/*.png`，助手可以直接读 PNG 核对布局。构建耗时约 30 秒，按 AGENTS.md「协作方式」交给用户跑。
- 只断言画面非空白，不做像素比对：基准图会因字体与渲染版本漂移而假警报。截图是给人看的，断言只保证图有效。
- Robolectric 自带下载器绕过 `settings.gradle.kts` 的仓库配置，会长时间挂住。改由 Gradle 用 `robolectricRuntime` configuration 解析 `android-all-instrumented`，`prepareRobolectricJars` 拷到固定目录，再用 `robolectric.offline=true` 离线复用。这个 jar 有 150 MB，`settings.gradle.kts` 里只给 `org.robolectric` 这一个 group 走阿里云镜像，其余依赖仍走 google() / mavenCentral()。
- jar 坐标必须和 Robolectric 内置清单完全一致，否则报 `Path is not a file`，报错里的文件名就是它要的版本。SDK 版本三处联动，改一处要同步全部：`build.gradle.kts` 的 `robolectricRuntime` 坐标、`src/test/resources/robolectric.properties` 的 `sdk`、测试类的 `@Config(sdk = [...])`。每个 SDK 一个 150 MB jar，因此统一固定 SDK 35。
- `androidx.compose.ui:ui-test-manifest` 必须放 `debugImplementation`。它靠清单合并注入 `ComponentActivity`，放 `testImplementation` 清单不参与合并，会报 `Unable to resolve activity for Intent`。
- 不要用 `onRoot().captureToImage()`：它走窗口级 PixelCopy 抓屏，在 Robolectric 里等不到重绘回调，固定抛 `ComposeTimeoutException`。用 `createAndroidComposeRule<ComponentActivity>()`，再 `view.draw(Canvas(bitmap))` 让根视图自己软件绘制。底部面板和弹窗要捕获 `ShadowDialog.getLatestDialog().window.decorView`，不能只截背后的 Activity；待办截图另有系统临时目录副本，路径由测试输出。
- `ModalBottomSheet` 输入框自动聚焦的 `LaunchedEffect` 必须放在面板内容的组合生命周期中，不能在外部简单等一帧再调用 `FocusRequester.requestFocus()`；取消后重新打开时内部输入框可能尚未挂载，会抛 `FocusRequester is not initialized`。
- 截图与单测通过仍不等于真机验证通过：软件绘制不覆盖真实渲染、触摸、权限和后台行为。
- `fillMaxWidth()` / `fillMaxHeight()` 用在 `Row`/`Column` 子项里要警觉，它填的是剩余空间而不是和兄弟等分；多子项场景应改用 `weight(1f)` 或 `IntrinsicSize`。页签行就因此出过一次故障：选中项吃光宽度、其余页签被压成零宽文字竖排，而编译、单测和 lint 全是绿的。

## GitHub Pages

- `.github/workflows/pages.yml` 在 `main` 推送（仅原型相关文件变化）或手动触发时运行：先跑 `node --check` 与 `node --test model.test.cjs`，再只把 `index.html`、`styles.css`、`model.js`、`app.js` 拷到 `_site` 发布，`android/`、`AGENTS.md`、测试脚本不上站。
- 仓库 Settings → Pages → Source 须选「GitHub Actions」。站点地址 `https://tuhardy.github.io/myApp/`。原型新增运行时文件时要同步加进工作流的 `cp` 与 `paths`。
- 未装 `uv` 时可用 `python -m http.server 5173 --bind 127.0.0.1` 起本机预览。
