# 日记（浏览器原型 + Android）

- Android 已迁移（0.6.0 / versionCode 13）：规则在 `domain/Diary.kt` 的 `DiaryRules`（校验、`parseDateQuery`、`select`、回收站、`unreferenced`），界面在 `ui/DiaryScreens.kt`（`DiaryUiState` 四视图、`DiaryDrafts`、首页/详情/编辑/回收站），附件存储在 `data/DiaryFiles.kt`，录音/播放/缩略图在 `platform/DiaryMedia.kt`，单测 `DiaryTest.kt`。
- Room 当前 v3：`MIGRATION_1_2` 新增 `diary` 表，`MIGRATION_2_3` 新增 `diary_drafts` 表（都与其他表同为 id/payload/position），覆盖升级保留旧数据；以后改表必须继续写迁移，不能靠破坏性重建。
- 日记与草稿不进入现有完整备份：`restoreBackup` 必须带上 `current.diaries` 和 `current.diaryDrafts`，否则 `persist` 的差量同步会把它们全部删掉。备份与恢复文案已写明「暂不含日记 / 不会改动日记」。
- Android 草稿（0.6.1 / versionCode 14）：领域类型 `DiaryDraft`（key 为 `new-UUID` 或 `edit:日记ID`）与 `DiaryRules.freshDraft / draftChanged / validateDraft / listDrafts` 在 `domain/Diary.kt`；仓库 `saveDiaryDraft / removeDiaryDraft`，`saveDiary(entry, existing, draftKey)` 在同一事务里移出草稿。编辑中的工作副本是 `DiaryUiState.editor`（`DiaryEditorSession`，在 ViewModel 里，旋转不丢）；编辑页 `ON_STOP` 先停录音再 `persistDraft()`，点返回同样持久化，没改动则移出。附件清理的保护集合 = 所有日记 + 已存草稿 + 当前工作副本（`ui.diary.editorFiles()`），启动清理也算上已存草稿。
- Android 页签重选：`NavigationBarItem` 在已处于 `Screen.DIARY` 时调用 `DiaryUiState.reselect()`，二级页回首页，首页则 `homeList.animateScrollToItem(0)`；编辑页不显示导航栏。
- 照片用系统照片选择器（`PickMultipleVisualMedia`，无需存储权限），复制到 `filesDir/diary/`，数据库只存文件名（`DiaryRules.isSafeFileName` 防路径穿越）；BitmapFactory 无法解码的格式拒绝导入。录音用 `MediaRecorder`（AAC/m4a），`RECORD_AUDIO` 首次点击才申请，拒绝后再点只引导去系统设置；编辑页 `ON_STOP`、离开编辑页、选图、返回、保存前都先停止并把录音放进草稿，不足 1 秒或设备中断时不保留并提示。
- 启动时 `DiaryFiles.cleanOrphans` 删除既不属于日记也不属于已存草稿的附件（此时不存在未落盘的工作副本）；保存/永久删除/删除草稿后只删除不再被任何一方引用的文件。
- 写日记（编辑页）时隐藏底部导航，也不弹专注进度提示。

- 需求见根目录 `日记功能需求文档.md`（`docs/` 被忽略规则挡住，助手读不到）。导航顺序「专注、待办、日记、用时、我的」，`#diary` 页内含首页 / 回收站 / 详情 / 全屏编辑四个子视图，由 `state.diaryView` 切换；编辑页隐藏底部导航（`.phone.diary-editing`）。
- 纯函数在 `model.js`：`selectDiaryEntries`（按月或全部时间搜索，匹配标题、正文或日期）、`parseDiaryDateQuery`（`2026-09-27`、`2026/9/27`、`9月27日`、`9-27`、`9.27`、`20260927`、`2026年9月`、`9月`、今天/昨天/前天，年份可省略）、`validateDiaryEntry`（至少有文字/照片/录音之一，时间可补写不可在未来）、回收站 `moveDiaryToTrash` / `restoreDiaryEntry` / `trashedDiaryEntries`。
- 删除只进回收站，不加撤销条；回收站不自动清空，永久删除需确认。日记的删除配色为淡红（`--danger*` 变量），仅作用于日记，待办删除不变。
- 原型照片为占位色块（模拟系统选图，含取消），录音为模拟（首次模拟授权、拒绝后不反复弹出；点一次开始再点结束；离开日记、切出页面、选图、返回、保存前都先停止并保留到草稿；不足 1 秒不保留）。不得描述为已读取相册或麦克风。
- 草稿箱（原型与 Android 均已做）：编辑页改的是工作副本 `diaryEditor.draft`，点返回、离开日记页、退到后台（`visibilitychange`）时有改动就存入 `diaryDrafts`（`suspendDiaryEditor` / `storeDiaryDraft`），改回原样则移出；退到后台时编辑页保持打开。新日记每次都是独立草稿（`new-N`，点「+」总是空白），修改已有日记每条最多一份（`edit:ID`，再次编辑自动接上）。入口在「更多 · 草稿」，列表规则在 `listDiaryDrafts`（按最后编辑倒序，原日记已删除或在回收站时为 orphan，保存时另存为新日记）。草稿可继续写或确认后删除，保存成功后移出草稿箱并进入详情。原型草稿只在内存，Android 持久化到本机数据库。
- 页签重选（原型与 Android 均已做）：已在日记页时再点底部「日记」，草稿箱 / 回收站 / 详情直接回日记首页（恢复首页阅读位置），已在首页则回到顶部；从别的页签切回日记仍回到离开时的页面。编辑页保持全屏、不显示导航栏、不另加按钮，只靠「返回」；若经侧栏等入口在编辑中回首页，先停录音并存草稿（`reselectDiaryTab`）。编辑页的录音条、附件区、错误提示是 `index.html` 里的静态节点，因为单测要求 `app.js` 中 `$("id")` 引用的 ID 必须存在于 HTML。
