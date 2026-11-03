package com.focusassistant.app.data

import com.focusassistant.app.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** ZIP 全量备份中的 manifest.json 编解码；附件本体由 FullBackupArchive 流式读写。 */
object FullBackupCodec {
    const val KIND = "focus-assistant-full-backup"
    const val VERSION = 2
    const val MAX_FILES = 20_000
    const val MAX_FILE_BYTES = 1024L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 4L * 1024 * 1024 * 1024
    private const val MAX_DIARIES = 10_000
    private const val MAX_DRAFTS = 10_000
    private const val MAX_TRACKS = 10_000
    private val SHA256 = Regex("^[0-9a-f]{64}$")
    private val TRACK_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")

    fun expectedPaths(state: AppState): List<String> = buildSet {
        state.diaries.forEach { entry -> entry.files.forEach { add("diary/$it") } }
        state.diaryDrafts.forEach { draft -> draft.files.forEach { add("diary/$it") } }
        state.musicTracks.forEach { track ->
            add("music/${track.audioFile}")
            if (track.coverFile.isNotBlank()) add("music/${track.coverFile}")
        }
    }.sorted()

    fun encodeManifest(state: AppState, files: List<FullBackupFile>): String {
        val normalized = state.copy(timer = null, loading = false)
        validate(FullBackupData(normalized, files))
        return JSONObject().put("kind", KIND).put("version", VERSION)
            .put("projects", JSONArray(normalized.projects.map(JsonCodec::project)))
            .put("todos", JSONArray(normalized.todos.map(JsonCodec::todo)))
            .put("sessions", JSONArray(normalized.sessions.map(JsonCodec::session)))
            .put("progress", JSONArray(normalized.progress.map(JsonCodec::progress)))
            .put("settings", JsonCodec.settings(normalized.settings))
            .put("diaries", JSONArray(normalized.diaries.map(JsonCodec::diary)))
            .put("diaryDrafts", JSONArray(normalized.diaryDrafts.map(JsonCodec::diaryDraft)))
            .put("assetAccounts", JSONArray(normalized.assets.accounts.map(JsonCodec::assetAccount)))
            .put("assetSnapshots", JSONArray(normalized.assets.snapshots.map(JsonCodec::assetSnapshot)))
            .put("wishes", JSONArray(normalized.assets.wishes.map(JsonCodec::wish)))
            .put("jobOpportunities", JSONArray(normalized.jobOpportunities.map(JsonCodec::jobOpportunity)))
            .put("autoApplySettings", JsonCodec.autoApplySettings(normalized.autoApply.settings))
            .put("autoApplyRecords", JSONArray(normalized.autoApply.records.map(JsonCodec::autoApplyRecord)))
            .put("musicTracks", JSONArray(normalized.musicTracks.map(JsonCodec::musicTrack)))
            .put("files", JSONArray(files.map { JSONObject().put("path", it.path).put("bytes", it.bytes).put("sha256", it.sha256) }))
            .toString().also { require(it.toByteArray(Charsets.UTF_8).size <= BackupCodec.MAX_BYTES) { "备份清单不可超过 20 MiB" } }
    }

    fun decodeManifest(text: String): FullBackupData {
        require(text.length <= BackupCodec.MAX_BYTES && text.toByteArray(Charsets.UTF_8).size <= BackupCodec.MAX_BYTES) { "备份清单不可超过 20 MiB" }
        val tokener = JSONTokener(text.removePrefix("﻿"))
        val root = tokener.nextValue() as? JSONObject ?: error("备份清单必须为 JSON 对象")
        require(tokener.nextClean() == '\u0000') { "备份清单包含多余内容" }
        require(root.string("kind") == KIND && root.integer("version") == VERSION) { "不是受支持的全量备份" }
        val state = AppState(
            projects = root.objects("projects", BackupCodec.MAX_PROJECTS).map(JsonCodec::readProject),
            todos = root.objects("todos", BackupCodec.MAX_TODOS).map(JsonCodec::readTodo),
            sessions = root.objects("sessions", BackupCodec.MAX_SESSIONS).map(JsonCodec::readSession),
            progress = root.objects("progress", BackupCodec.MAX_PROGRESS).map(JsonCodec::readProgress),
            settings = JsonCodec.readSettings(root.getJSONObject("settings")), loading = false,
            diaries = root.objects("diaries", MAX_DIARIES).map(JsonCodec::readDiary),
            diaryDrafts = root.objects("diaryDrafts", MAX_DRAFTS).map(JsonCodec::readDiaryDraft),
            assets = AssetBook(
                root.objects("assetAccounts", AssetRules.MAX_ACCOUNTS).map(JsonCodec::readAssetAccount),
                root.objects("assetSnapshots", AssetRules.MAX_SNAPSHOTS).map(JsonCodec::readAssetSnapshot),
                root.objects("wishes", AssetRules.MAX_WISHES * 1000).map(JsonCodec::readWish)),
            jobOpportunities = root.objects("jobOpportunities", JobAssistRules.MAX_JOBS).map(JsonCodec::readJobOpportunity),
            autoApply = AutoApplyBook(
                JsonCodec.readAutoApplySettings(root.getJSONObject("autoApplySettings")),
                root.objects("autoApplyRecords", AutoApplyRules.MAX_RECORDS).map(JsonCodec::readAutoApplyRecord)),
            musicTracks = root.objects("musicTracks", MAX_TRACKS).map(JsonCodec::readMusicTrack))
        val files = root.objects("files", MAX_FILES).map {
            FullBackupFile(it.string("path"), it.long("bytes"), it.string("sha256"))
        }
        return FullBackupData(state, files).also(::validate)
    }

    fun validate(data: FullBackupData) {
        val state = data.state
        BackupCodec.validate(BackupData(state.projects, state.todos, state.sessions, state.progress, state.settings))
        require(state.timer == null) { "全量备份不包含活动计时" }
        require(state.diaries.size <= MAX_DIARIES && state.diaryDrafts.size <= MAX_DRAFTS) { "日记记录数超出限制" }
        require(state.diaries.map { it.id }.let { it.toSet().size == it.size }) { "日记标识重复" }
        require(state.diaryDrafts.map { it.key }.let { it.toSet().size == it.size }) { "日记草稿标识重复" }
        state.diaries.forEach { DiaryRules.validate(it) }
        state.diaryDrafts.forEach(DiaryRules::validateDraft)
        AssetRules.validateBook(state.assets)
        JobAssistRules.validateAll(state.jobOpportunities)
        AutoApplyRules.validateSettings(state.autoApply.settings)
        require(state.autoApply.records.size <= AutoApplyRules.MAX_RECORDS) { "自动投递记录过多" }
        require(state.autoApply.records.map { it.id }.let { it.toSet().size == it.size }) { "自动投递记录标识重复" }
        state.autoApply.records.forEach(AutoApplyRules::validateRecord)
        validateTracks(state.musicTracks)
        require(data.files.size <= MAX_FILES && data.files.map { it.path }.toSet().size == data.files.size) { "附件数量过多或路径重复" }
        require(data.files.map { it.path }.toSet() == expectedPaths(state).toSet()) { "附件清单与数据引用不一致" }
        var total = 0L
        data.files.forEach { file ->
            require(file.bytes in 1..MAX_FILE_BYTES) { "附件大小无效" }
            require(SHA256.matches(file.sha256)) { "附件校验值无效" }
            total = try { Math.addExact(total, file.bytes) } catch (_: ArithmeticException) { throw IllegalArgumentException("备份附件总大小无效") }
        }
        require(total <= MAX_TOTAL_BYTES) { "备份附件总大小不可超过 4 GiB" }
        require(encodeUnchecked(state, data.files).toByteArray(Charsets.UTF_8).size <= BackupCodec.MAX_BYTES) { "备份清单不可超过 20 MiB" }
    }

    private fun validateTracks(tracks: List<MusicTrack>) {
        require(tracks.size <= MAX_TRACKS && tracks.map { it.id }.toSet().size == tracks.size) { "曲库标识缺失、重复或记录过多" }
        tracks.forEach { track ->
            require(TRACK_ID.matches(track.id) && ".." !in track.id) { "音乐标识无效" }
            require(track.title.isNotBlank() && track.title.length <= Validation.MAX_TITLE && track.artist.length <= Validation.MAX_TITLE) { "音乐标题或歌手无效" }
            require(track.durationSeconds in 0..(Validation.MAX_TIMESTAMP_MS / 1000)) { "音乐时长无效" }
            require(track.audioFile == "${track.id}/audio.m4s") { "音乐音频路径无效" }
            require(track.coverFile.isEmpty() || track.coverFile == "${track.id}/cover.jpg") { "音乐封面路径无效" }
            require(SHA256.matches(track.contentHash) && track.fileBytes in 1..MAX_FILE_BYTES) { "音乐文件信息无效" }
            require(track.importedAt in 0..Validation.MAX_TIMESTAMP_MS && track.sourceCid >= 0 && track.sourceBvid.length <= 32) { "音乐来源信息无效" }
        }
    }

    /** validate 中只检查序列化尺寸，避免与 encodeManifest 相互递归。 */
    private fun encodeUnchecked(state: AppState, files: List<FullBackupFile>): String = JSONObject()
        .put("projects", JSONArray(state.projects.map(JsonCodec::project))).put("todos", JSONArray(state.todos.map(JsonCodec::todo)))
        .put("sessions", JSONArray(state.sessions.map(JsonCodec::session))).put("progress", JSONArray(state.progress.map(JsonCodec::progress)))
        .put("settings", JsonCodec.settings(state.settings)).put("diaries", JSONArray(state.diaries.map(JsonCodec::diary)))
        .put("diaryDrafts", JSONArray(state.diaryDrafts.map(JsonCodec::diaryDraft)))
        .put("assetAccounts", JSONArray(state.assets.accounts.map(JsonCodec::assetAccount)))
        .put("assetSnapshots", JSONArray(state.assets.snapshots.map(JsonCodec::assetSnapshot)))
        .put("wishes", JSONArray(state.assets.wishes.map(JsonCodec::wish)))
        .put("jobOpportunities", JSONArray(state.jobOpportunities.map(JsonCodec::jobOpportunity)))
        .put("autoApplySettings", JsonCodec.autoApplySettings(state.autoApply.settings))
        .put("autoApplyRecords", JSONArray(state.autoApply.records.map(JsonCodec::autoApplyRecord)))
        .put("musicTracks", JSONArray(state.musicTracks.map(JsonCodec::musicTrack)))
        .put("files", JSONArray(files.map { JSONObject().put("path", it.path).put("bytes", it.bytes).put("sha256", it.sha256) })).toString()
}
