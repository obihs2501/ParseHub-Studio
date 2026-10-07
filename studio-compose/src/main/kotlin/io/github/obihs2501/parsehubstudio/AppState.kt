package io.github.obihs2501.parsehubstudio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.ImageRequest
import io.github.obihs2501.parsehubstudio.library.CachedFolder
import io.github.obihs2501.parsehubstudio.library.CachedGallery
import io.github.obihs2501.parsehubstudio.library.CachedRoot
import io.github.obihs2501.parsehubstudio.library.GalleryCacheStore
import io.github.obihs2501.parsehubstudio.library.GalleryRootsStore
import io.github.obihs2501.parsehubstudio.library.ImageInfo
import io.github.obihs2501.parsehubstudio.library.LocalLibrary
import io.github.obihs2501.parsehubstudio.library.UiPrefs
import io.github.obihs2501.parsehubstudio.library.UiPrefsStore
import io.github.obihs2501.parsehubstudio.library.VideoThumbs
import io.github.obihs2501.parsehubstudio.model.*
import io.github.obihs2501.parsehubstudio.sidecar.ApiClient
import io.github.obihs2501.parsehubstudio.sidecar.WebAppWindow
import io.github.obihs2501.parsehubstudio.ui.Dialog
import io.github.obihs2501.parsehubstudio.ui.LightboxState
import io.github.obihs2501.parsehubstudio.ui.MediaImages
import io.github.obihs2501.parsehubstudio.ui.ViewerItem
import io.github.obihs2501.parsehubstudio.ui.formatBytes
import io.github.obihs2501.parsehubstudio.ui.formatDuration
import io.github.obihs2501.parsehubstudio.ui.formatTimestamp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 导航页面。 */
enum class Screen(val title: String, val subtitle: String) {
    PARSE("解析工作台", "粘贴链接，留住喜欢的内容"),
    TASKS("下载管理", "本次运行的任务与进度"),
    GALLERY("本地图库", "直接浏览已下载的图片与视频"),
    COOKIES("账户与代理", "按平台轮换 Cookie、配置代理"),
    BOOKMARKS("解析网站收藏", "手动使用的第三方工具"),
    SETTINGS("设置", "偏好与本地环境"),
}

/** 应用级状态（Compose 单例）。 */
class AppState(
    val api: ApiClient,
    private val scope: CoroutineScope,
) {
    /** Coil 图片加载器（复用 sidecar 鉴权）。 */
    val imageLoader: ImageLoader = MediaImages.build(api.token)

    var screen by mutableStateOf(Screen.PARSE)
        private set

    var connected by mutableStateOf(false)
        private set
    var version by mutableStateOf("")
        private set

    var input by mutableStateOf("")
    var quality by mutableStateOf("origin")
    /** 抖音等多档位平台的画质选择（下标进 media.variants，0 = 最清晰）。 */
    var selectedVariant by mutableStateOf(0)
    var previewMode by mutableStateOf(true)
    var useSavedConfig by mutableStateOf(true)

    var settings by mutableStateOf<Settings?>(null)
        private set
    val platforms = mutableStateListOf<Platform>()
    val tasks = mutableStateListOf<Task>()
    val history = mutableStateListOf<HistoryEntry>()

    var result by mutableStateOf<ParseResult?>(null)
        private set
    var parsing by mutableStateOf(false)
        private set
    var toast by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** 批次解析（/api/batches）。 */
    var batch by mutableStateOf<Batch?>(null)
        private set
    var batchCreating by mutableStateOf(false)
        private set
    var selectedBatchItem by mutableStateOf<String?>(null)
        private set

    /** 灯箱（全屏媒体查看器）。 */
    var lightbox by mutableStateOf<LightboxState?>(null)
        private set

    /** 当前对话框。 */
    var dialog by mutableStateOf<Dialog?>(null)
        private set

    /** 目录浏览（/api/fs/list）。 */
    var fsPath by mutableStateOf("")
        private set
    var fsParent by mutableStateOf<String?>(null)
        private set
    val fsDirs = mutableStateListOf<FsDir>()

    private var taskPoll: Job? = null
    private var batchPoll: Job? = null

    /* ------------------------------------------------------------ 导航与基础 */

    fun navigate(target: Screen) {
        screen = target
        when (target) {
            Screen.TASKS -> {
                refreshTasks()
                startTaskPolling()
            }
            Screen.GALLERY -> loadGallery()
            Screen.COOKIES, Screen.BOOKMARKS, Screen.SETTINGS -> refreshPlatforms()
            else -> {}
        }
        if (target != Screen.TASKS) stopTaskPolling()
    }

    /* ------------------------------------------------------------ 界面栏宽 */

    private var uiPrefsLoaded = false

    /** 左侧导航栏宽度（可拖动调整，重启后保持）。 */
    var navWidth by mutableStateOf(230f)
        private set

    /** 图库左侧文件夹栏宽度（可拖动调整，重启后保持）。 */
    var gallerySidebarWidth by mutableStateOf(252f)
        private set

    /** 查看器底部缩略图条是否显示（看图时可一键隐藏）。 */
    var showLightboxStrip by mutableStateOf(true)
        private set

    private fun ensureUiPrefs() {
        if (uiPrefsLoaded) return
        uiPrefsLoaded = true
        val prefs = UiPrefsStore.load()
        navWidth = prefs.navWidth.coerceIn(180f, 360f)
        gallerySidebarWidth = prefs.galleryWidth.coerceIn(200f, 560f)
        showLightboxStrip = prefs.showStrip
    }

    fun resizeNavWidth(width: Float) {
        ensureUiPrefs()
        navWidth = width.coerceIn(180f, 360f)
    }

    fun resizeGallerySidebar(width: Float) {
        ensureUiPrefs()
        gallerySidebarWidth = width.coerceIn(200f, 560f)
    }

    /** 拖动结束后落盘（拖动过程中不写文件，避免卡顿）。 */
    fun persistUiPrefs() {
        UiPrefsStore.save(
            UiPrefs(navWidth = navWidth, galleryWidth = gallerySidebarWidth, showStrip = showLightboxStrip),
        )
    }

    /** 查看器底部缩略图条显示 / 隐藏。 */
    fun toggleLightboxStrip() {
        ensureUiPrefs()
        showLightboxStrip = !showLightboxStrip
        persistUiPrefs()
    }

    fun showToast(message: String) {
        toast = message
        scope.launch {
            delay(1800)
            if (toast == message) toast = null
        }
    }

    fun bootstrap() {
        ensureUiPrefs()
        scope.launch {
            runCatching {
                val health = api.health()
                version = health["version"]?.toString()?.trim('"') ?: ""
                connected = true
            }.onFailure { connected = false }
            refreshPlatforms()
            refreshSettings()
            refreshTasks()
            refreshHistory()
        }
    }

    fun refreshSettings() = scope.launch {
        runCatching { api.settings() }.onSuccess {
            settings = it
            quality = it.downloadQuality
            previewMode = it.previewMode
        }
    }

    fun refreshPlatforms() = scope.launch {
        runCatching { api.platforms() }.onSuccess {
            platforms.clear(); platforms.addAll(it)
        }
    }

    fun refreshTasks() = scope.launch {
        runCatching { api.tasks() }.onSuccess { list ->
            tasks.clear(); tasks.addAll(list)
            prefetch(list.map { task -> task.thumbnail?.let { t -> mediaUrl(t, referer = task.rawUrl.takeIf { r -> r.isNotBlank() }) } })
        }
    }

    fun refreshHistory() = scope.launch {
        runCatching { api.history() }.onSuccess { entries ->
            history.clear(); history.addAll(entries)
            prefetch(entries.map { entry -> coverUrl(entry.media.firstOrNull(), entry.rawUrl) })
        }
    }

    /**
     * 任务进度轮询：仅在下载页、且有未结束任务时按 1.2s 轮询；
     * 全部结束后退化为 5s 心跳，避免空转。
     */
    private fun startTaskPolling() {
        if (taskPoll?.isActive == true) return
        taskPoll = scope.launch {
            while (isActive) {
                val active = tasks.any { it.status == "running" || it.status == "pending" }
                delay(if (active) 1200 else 5000)
                refreshTasks()
            }
        }
    }

    private fun stopTaskPolling() {
        taskPoll?.cancel()
        taskPoll = null
    }

    /* ------------------------------------------------------------ 解析与下载 */

    /** 「清空」：输入框与下方解析结果一起清掉（批次面板有自己的取消/重试/清空，不动它）。 */
    fun clearParse() {
        input = ""
        result = null
        selectedBatchItem = null
        error = null
    }

    fun parse() {
        val text = input.trim()
        if (text.isEmpty()) { showToast("请先粘贴链接"); return }
        parsing = true
        error = null
        scope.launch {
            runCatching { api.parse(text, useSavedConfig) }
                .onSuccess {
                    result = it
                    selectedBatchItem = null
                    selectedVariant = defaultVariantIndex(it.media.firstOrNull()?.variants.orEmpty())
                    refreshHistory()
                    appendParseCsv(it)
                }
                .onFailure { error = it.message; showToast(it.message ?: "解析失败") }
            parsing = false
        }
    }

    private fun currentText(): String = input.trim().ifEmpty { result?.rawUrl.orEmpty() }

    /**
     * 默认选中的档位：跳过原画。
     *
     * 原画是未转码的原始档（可能几 GB），而「下载本作品」会直接把选中的档位发下去 ——
     * 默认停在原画上等于让用户点一下「下载」就静默拉一份上 GB 的文件。原画仍是 0 号芯片，
     * 想拿点一下就行。
     */
    private fun defaultVariantIndex(variants: List<MediaVariant>): Int =
        variants.indexOfFirst { !it.origin }.takeIf { it >= 0 } ?: 0

    /** 有码流列表时用选中的那一档（"vN"），否则沿用设置里的下载规格（origin / thumb）。 */
    private fun downloadQuality(): String {
        val variants = result?.media?.firstOrNull()?.variants.orEmpty()
        return if (variants.size > 1) "v${selectedVariant.coerceIn(0, variants.size - 1)}" else quality
    }

    /** 下载当前作品（单个任务）。 */
    fun downloadCurrent() {
        val text = currentText()
        if (text.isEmpty()) { showToast("请先粘贴链接"); return }
        scope.launch {
            runCatching { api.createTask(text, quality = downloadQuality()) }
                .onSuccess { showToast("已加入下载队列"); refreshTasks() }
                .onFailure { showToast(it.message ?: "创建任务失败") }
        }
    }

    /**
     * 下载当前作品的某个媒体。
     *
     * [sourceUrl] 用于灯箱等「非当前输入框」的场景（历史 / 批次）：后端会用它重新解析，
     * 因此即使历史里存的是过期直链也能拿到新鲜的媒体地址。
     */
    fun downloadMedia(index: Int, sourceUrl: String? = null) {
        val text = sourceUrl?.takeIf { it.isNotBlank() } ?: currentText()
        if (text.isEmpty()) return
        // 来自灯箱/历史（带了 sourceUrl）的下载不一定对应当前解析结果，别套用它的档位选择
        val chosen = if (sourceUrl.isNullOrBlank()) downloadQuality() else quality
        scope.launch {
            runCatching { api.createTask(text, mediaIndex = index, quality = chosen) }
                .onSuccess { showToast("已加入下载队列"); refreshTasks() }
                .onFailure { showToast(it.message ?: "创建任务失败") }
        }
    }

    /** 按链接下载（批次项 / 历史记录里的单条）。 */
    fun downloadUrl(url: String) {
        if (url.isBlank()) return
        scope.launch {
            runCatching { api.createTask(url, quality = quality) }
                .onSuccess { showToast("已加入下载队列"); refreshTasks() }
                .onFailure { showToast(it.message ?: "创建任务失败") }
        }
    }

    fun cleanInput() {
        val text = input.trim()
        if (text.isEmpty()) return
        scope.launch {
            runCatching { api.clean(text, useSavedConfig) }
                .onSuccess { input = it["url"]?.toString()?.trim('"') ?: text }
                .onFailure { showToast(it.message ?: "清洗失败") }
        }
    }

    /* ------------------------------------------------------------ 批次解析 */

    fun startBatch(download: Boolean) {
        val text = input.trim()
        if (text.isEmpty()) { showToast("请先粘贴链接"); return }
        if (batch?.status == "running") { showToast("当前批次仍在解析，请先取消"); return }
        batchCreating = true
        scope.launch {
            runCatching { api.createBatch(text, download, useSavedConfig) }
                .onSuccess {
                    batch = it
                    selectedBatchItem = null
                    pollBatch(it.id)
                }
                .onFailure { showToast(it.message ?: "创建批次失败") }
            batchCreating = false
        }
    }

    private fun pollBatch(id: String) {
        batchPoll?.cancel()
        batchPoll = scope.launch {
            while (isActive) {
                delay(1200)
                val updated = runCatching { api.getBatch(id) }.getOrNull() ?: break
                batch = updated
                if (updated.status != "running") {
                    refreshHistory()
                    refreshTasks()
                    break
                }
            }
        }
    }

    fun cancelBatch() {
        val id = batch?.id ?: return
        scope.launch {
            runCatching { api.cancelBatch(id) }.onSuccess { showToast("已取消未完成项") }
            batch = runCatching { api.getBatch(id) }.getOrNull() ?: batch
        }
    }

    fun retryFailed() {
        val urls = batch?.items?.filter { it.status == "error" }?.map { it.url }.orEmpty()
        if (urls.isEmpty()) return
        input = urls.joinToString("\n")
        startBatch(download = false)
    }

    fun selectBatchItem(id: String) {
        selectedBatchItem = id
        batch?.items?.firstOrNull { it.id == id }?.result?.let { result = it }
    }

    fun dismissBatch() {
        batchPoll?.cancel()
        batchPoll = null
        batch = null
        selectedBatchItem = null
    }

    /* ------------------------------------------------------------ 任务与历史 */

    fun cancelTask(id: String) = scope.launch {
        runCatching { api.cancelTask(id) }.onSuccess { refreshTasks() }
    }

    fun clearFinishedTasks() = scope.launch {
        runCatching { api.clearTasks() }.onSuccess { refreshTasks() }
    }

    fun deleteHistory(id: String) = scope.launch {
        runCatching { api.deleteHistory(id) }.onSuccess { refreshHistory() }
    }

    fun clearHistory() = scope.launch {
        runCatching { api.clearHistory() }.onSuccess { refreshHistory() }
    }

    /* ------------------------------------------------------------ Cookie / 代理 */

    fun toggleCookie(platformId: String, entryId: String, enabled: Boolean) = scope.launch {
        runCatching { api.updateCookiePool(platformId, entryId = entryId, enabled = enabled) }
            .onSuccess { refreshPlatforms() }
            .onFailure { showToast(it.message ?: "更新失败") }
    }

    fun addCookie(platformId: String, cookie: String, name: String) = scope.launch {
        runCatching { api.updateCookiePool(platformId, cookie = cookie, name = name) }
            .onSuccess { refreshPlatforms(); showToast("已添加账户") }
            .onFailure { showToast(it.message ?: "添加失败") }
    }

    /** 删除 Cookie 池条目：/api/config/cookie-pool 只传 entry_id（不带 enabled）即删除。 */
    fun deleteCookie(platformId: String, entryId: String) = scope.launch {
        runCatching { api.updateCookiePool(platformId, entryId = entryId) }
            .onSuccess { refreshPlatforms(); showToast("已删除账户") }
            .onFailure { showToast(it.message ?: "删除失败") }
    }

    /** 平台主 Cookie（/api/config/cookie）。 */
    fun setMainCookie(platformId: String, cookie: String) = scope.launch {
        runCatching { api.setCookie(platformId, cookie) }
            .onSuccess { refreshPlatforms(); showToast("已保存主 Cookie") }
            .onFailure { showToast(it.message ?: "保存失败") }
    }

    fun clearMainCookie(platformId: String) = scope.launch {
        runCatching { api.clearCookie(platformId) }
            .onSuccess { refreshPlatforms(); showToast("已清除主 Cookie") }
            .onFailure { showToast(it.message ?: "清除失败") }
    }

    /** 平台代理（/api/config/proxy）。target = parse / download / all */
    fun setPlatformProxy(platformId: String, proxy: String, target: String = "all") = scope.launch {
        runCatching { api.setProxy(platformId, proxy, target) }
            .onSuccess { refreshPlatforms(); showToast("已保存平台代理") }
            .onFailure { showToast(it.message ?: "保存失败") }
    }

    fun clearPlatformProxy(platformId: String, target: String = "all") = scope.launch {
        runCatching { api.clearProxy(platformId, target) }
            .onSuccess { refreshPlatforms(); showToast("已清除平台代理") }
            .onFailure { showToast(it.message ?: "清除失败") }
    }

    /* ------------------------------------------------------------ 设置编辑 */

    fun saveSettings(patch: Map<String, JsonElement>) = scope.launch {
        runCatching { api.updateSettings(JsonObject(patch)) }
            .onSuccess { settings = it; quality = it.downloadQuality; previewMode = it.previewMode }
            .onFailure { showToast(it.message ?: "保存失败") }
    }

    fun applyText(key: String, value: String) = saveSettings(mapOf(key to JsonPrimitive(value)))
    fun applyNumber(key: String, value: Int) = saveSettings(mapOf(key to JsonPrimitive(value)))
    fun applyBool(key: String, value: Boolean) = saveSettings(mapOf(key to JsonPrimitive(value)))

    fun saveBookmark(index: Int?, name: String, url: String, group: String) {
        val current = settings?.bookmarks.orEmpty().toMutableList()
        val entry = Bookmark(name.trim(), url.trim(), group.trim().ifBlank { "通用" })
        if (index != null && index in current.indices) current[index] = entry else current.add(entry)
        saveSettings(mapOf("bookmarks" to bookmarksJson(current)))
    }

    fun deleteBookmark(index: Int) {
        val current = settings?.bookmarks.orEmpty().toMutableList()
        if (index !in current.indices) return
        current.removeAt(index)
        saveSettings(mapOf("bookmarks" to bookmarksJson(current)))
    }

    fun saveAlias(platform: String, account: String, creator: String, matchBy: String) {
        val current = settings?.authorAliases.orEmpty().toMutableList()
        current.add(AuthorAlias(platform = platform, account = account, creator = creator, matchBy = matchBy))
        saveSettings(mapOf("author_aliases" to aliasesJson(current)))
    }

    fun deleteAlias(index: Int) {
        val current = settings?.authorAliases.orEmpty().toMutableList()
        if (index !in current.indices) return
        current.removeAt(index)
        saveSettings(mapOf("author_aliases" to aliasesJson(current)))
    }

    /** 本地已有的作者名（来自解析记录），别名对话框里可直接点选。 */
    fun localAuthorNames(): List<String> =
        history.mapNotNull { it.author.trim().takeIf { name -> name.isNotBlank() } }.distinct().sorted()

    /** 本地已有的归档文件夹名（图库一级目录），别名对话框里可直接点选。 */
    fun localFolderNames(): List<String> =
        galleryRoots.map { it.name }.distinct().sorted()

    private fun bookmarksJson(list: List<Bookmark>) = JsonArray(
        list.map { b -> buildJsonObject { put("name", b.name); put("url", b.url); put("group", b.group) } },
    )

    private fun aliasesJson(list: List<AuthorAlias>) = JsonArray(
        list.map { a ->
            buildJsonObject {
                put("platform", a.platform); put("account", a.account)
                put("creator", a.creator); put("match_by", a.matchBy)
            }
        },
    )

    /* ------------------------------------------------------------ CSV 记录 */

    /** CSV 落在下载目录下，Excel 可直接打开（带 UTF-8 BOM）。 */
    private fun csvFile(): java.io.File {
        val dir = settings?.defaultSaveDir?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home")
        return java.io.File(dir, "parsehub-history.csv")
    }

    private fun csvRow(entry: HistoryEntry): String = listOf(
        formatTimestamp(entry.time),
        entry.platform?.name.orEmpty(),
        entry.author,
        entry.title,
        entry.postId,
        entry.mediaCount.toString(),
        entry.rawUrl,
        entry.content.replace('\n', ' ').take(200),
    ).joinToString(",") { cell ->
        if (cell.any { it == ',' || it == '"' || it == '\n' }) "\"" + cell.replace("\"", "\"\"") + "\"" else cell
    }

    private fun writeCsv(rows: List<HistoryEntry>, append: Boolean) {
        runCatching {
            val file = csvFile()
            file.parentFile?.mkdirs()
            if (append && file.isFile) {
                file.appendText(rows.joinToString("") { csvRow(it) + "\n" })
            } else {
                file.writeText(
                    "﻿时间,平台,作者,标题,作品ID,媒体数,原链接,内容摘要\n" +
                        rows.joinToString("") { csvRow(it) + "\n" },
                )
            }
            file.absolutePath
        }
            .onSuccess { showToast(if (append) "已追加到 CSV" else "已导出 ${rows.size} 条到 CSV") }
            .onFailure { showToast("写 CSV 失败：${it.message}") }
    }

    /** 手动导出全部历史。 */
    fun exportHistoryCsv() {
        val rows = history.toList()
        if (rows.isEmpty()) { showToast("还没有历史记录"); return }
        scope.launch(Dispatchers.IO) { writeCsv(rows, append = false) }
    }

    /** 每次解析成功后追加一行 —— 比 metadata.json 可靠（后者会因重名被改成 metadata_2.json）。 */
    private fun appendParseCsv(result: ParseResult) {
        val entry = HistoryEntry(
            id = "",
            platform = result.platform,
            title = result.title,
            content = result.content,
            author = result.author,
            postId = result.postId,
            rawUrl = result.rawUrl,
            mediaCount = result.mediaCount,
            time = System.currentTimeMillis() / 1000.0,
        )
        scope.launch(Dispatchers.IO) { writeCsv(listOf(entry), append = true) }
    }

    /* ------------------------------------------------------------ 对话框 */

    fun openDialog(target: Dialog) {
        dialog = target
    }

    fun dismissDialog() {
        dialog = null
    }

    fun browse(path: String?): Job = scope.launch {
        runCatching { api.listDirs(path) }
            .onSuccess {
                fsPath = it.path
                fsParent = it.parent
                fsDirs.clear(); fsDirs.addAll(it.dirs)
            }
            .onFailure {
                // 目录不存在（例如还没创建过的下载目录）时退回「此电脑」，别让选择器看起来是坏的
                if (!path.isNullOrBlank()) {
                    showToast("无法打开该目录，已回到此电脑")
                    browse(null)
                } else {
                    showToast(it.message ?: "无法打开目录")
                }
            }
    }

    /* ------------------------------------------------------------ 灯箱 */

    /** 远程媒体（解析结果 / 历史记录）→ 灯箱条目。 */
    fun openLightbox(media: List<Media>, index: Int, referer: String? = null, title: String = "") {
        if (media.isEmpty()) return
        val items = media.mapIndexed { i, m ->
            ViewerItem(
                title = title.ifBlank { "媒体预览" },
                isVideo = m.kind == "video",
                previewModel = mediaUrl(m.thumbUrl ?: m.previewUrl ?: m.url.ifBlank { null }, referer),
                originalModel = mediaUrl(m.url.ifBlank { null }, referer),
                meta = listOfNotNull(
                    m.kindLabel,
                    if (m.width > 0 && m.height > 0) "${m.width}×${m.height}" else null,
                    if (m.duration > 0) formatDuration(m.duration) else null,
                ).joinToString(" · "),
                rawUrl = m.url.ifBlank { null },
                remote = ViewerItem.RemoteRef(referer, i),
                width = m.width,
                height = m.height,
            )
        }
        lightbox = LightboxState(items, index.coerceIn(0, items.size - 1))
    }

    /**
     * 本地文件（图库）→ 灯箱条目。
     *
     * **同步构造、立即显示**：早先这里对整卷文件逐个读文件头 / 抽视频首帧，
     * 几百张的文件夹点开后要等好几秒才弹出查看器。
     * 分辨率与视频首帧改成进查看器后按当前项懒加载（见 LightboxOverlay）；
     * 视频只给播放器（Coil 解不了 mp4）。
     */
    fun openLocalLightbox(files: List<LocalLibrary.MediaFile>, index: Int) {
        if (files.isEmpty()) return
        val items = files.map { f ->
            ViewerItem(
                title = f.name,
                isVideo = f.isVideo,
                // 直接把 File 交给 Coil：File.toURI() 的 "file:/C:/…" 形式 Coil 解析不出来（实测失败）
                originalModel = if (f.isVideo) null else f.file,
                meta = listOfNotNull(formatBytes(f.sizeBytes), formatTimestamp(f.modifiedAt / 1000.0)).joinToString(" · "),
                localPath = f.path,
            )
        }
        lightbox = LightboxState(items, index.coerceIn(0, items.size - 1))
    }

    fun closeLightbox() {
        lightbox = null
    }

    fun moveLightbox(delta: Int) {
        lightbox = lightbox?.let { it.copy(index = (it.index + delta).coerceIn(0, it.items.size - 1)) }
    }

    fun selectLightbox(index: Int) {
        lightbox = lightbox?.let { it.copy(index = index.coerceIn(0, it.items.size - 1)) }
    }

    /** 用系统默认程序打开本地文件（图片 / 视频播放器）。 */
    fun openLocalFile(path: String) {
        if (path.isBlank()) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                if (!java.awt.Desktop.isDesktopSupported()) error("当前系统不支持")
                java.awt.Desktop.getDesktop().open(java.io.File(path))
            }.onFailure { showToast("打开失败：${it.message}") }
        }
    }

    /**
     * 播放本地视频：交给系统默认播放器（兼容性最好，用户装了什么解码器就用什么，
     * HEVC / .ts 这类 Edge 放不了的也能放）；系统没有关联程序时才退回 Edge/Chrome 应用窗口。
     */
    fun playVideo(path: String) {
        if (path.isBlank()) return
        scope.launch {
            val opened = withContext(Dispatchers.IO) {
                runCatching {
                    if (!java.awt.Desktop.isDesktopSupported()) error("当前系统不支持")
                    java.awt.Desktop.getDesktop().open(java.io.File(path))
                }.isSuccess
            }
            if (!opened) {
                val fallback = withContext(Dispatchers.IO) {
                    runCatching { WebAppWindow.playFile(java.io.File(path)) }.getOrDefault(false)
                }
                if (!fallback) showToast("没有找到可用的视频播放器")
            }
        }
    }

    /* ------------------------------------------------------------ 本地图库 */

    val galleryRoots = mutableStateListOf<LocalLibrary.Folder>()
    val galleryStack = mutableStateListOf<LocalLibrary.Folder>()
    val galleryMedias = mutableStateListOf<LocalLibrary.MediaFile>()
    val gallerySubfolders = mutableStateListOf<LocalLibrary.Folder>()

    /** 图库根目录分组：默认下载目录 + 用户添加的文件夹。 */
    data class GalleryRootGroup(
        val path: String,
        val name: String,
        val removable: Boolean,
        val folders: List<LocalLibrary.Folder>,
    )

    val galleryRootGroups = mutableStateListOf<GalleryRootGroup>()
    private val extraRoots = mutableStateListOf<String>()
    private var rootsLoaded = false

    private fun ensureRootsLoaded() {
        if (rootsLoaded) return
        rootsLoaded = true
        val stored = GalleryRootsStore.load()
        if (stored.isNotEmpty()) {
            extraRoots.clear()
            extraRoots.addAll(stored)
        }
    }

    /** 所有生效的根目录：下载目录始终在最前。 */
    fun galleryRootPaths(): List<String> {
        ensureRootsLoaded()
        val defaultDir = settings?.defaultSaveDir.orEmpty()
        return (listOfNotNull(defaultDir.takeIf { it.isNotBlank() }) + extraRoots.toList()).distinct()
    }

    fun addGalleryRoot(path: String) {
        val clean = path.trim()
        if (clean.isBlank()) return
        if (clean in galleryRootPaths()) { showToast("该文件夹已在图库中"); return }
        extraRoots.add(clean)
        GalleryRootsStore.save(extraRoots.toList())
        loadGallery(force = true)
    }

    fun removeGalleryRoot(path: String) {
        if (extraRoots.remove(path)) {
            GalleryRootsStore.save(extraRoots.toList())
            loadGallery(force = true)
        }
    }

    /** 根目录「签名」（目录最后修改时间）：用来判断是否需要重扫。 */
    private var rootSignatures: List<Long> = emptyList()

    private fun signatureOf(path: String): Long =
        runCatching { java.io.File(path).lastModified() }.getOrDefault(0L)

    private fun rootNameOf(path: String): String {
        val defaultDir = settings?.defaultSaveDir.orEmpty()
        return if (path == defaultDir) "下载目录" else java.io.File(path).name.ifBlank { path }
    }

    private fun applyRootGroups(groups: List<GalleryRootGroup>) {
        galleryRootGroups.clear(); galleryRootGroups.addAll(groups)
        galleryRoots.clear(); galleryRoots.addAll(groups.flatMap { it.folders })
    }

    /**
     * 扫描所有根目录（下载目录 + 额外添加的文件夹）。
     *
     * 「每次打开都重新扫描」的修复（参考 x-spider 的 scannedAt 做法）：
     * 1. 根目录签名（mtime）没变 → 直接用内存结果，不重扫；
     * 2. 启动后第一次进图库 → 先用持久化快照铺上界面（不用等扫描），签名一致就直接结束；
     * 3. 只有签名变化、或点「重新扫描」才真正扫盘。
     */
    fun loadGallery(force: Boolean = false) {
        ensureRootsLoaded()
        val roots = galleryRootPaths()
        if (roots.isEmpty()) { showToast("请先在设置里配置下载目录"); return }
        val defaultDir = settings?.defaultSaveDir.orEmpty()
        val signatures = roots.map(::signatureOf)

        if (!force && galleryRootGroups.size == roots.size && signatures == rootSignatures) return

        if (!force && galleryRootGroups.isEmpty()) {
            val cached = GalleryCacheStore.load()
            if (cached.roots.map { it.path } == roots) {
                applyRootGroups(
                    cached.roots.map { r ->
                        GalleryRootGroup(
                            path = r.path,
                            name = rootNameOf(r.path),
                            removable = r.path != defaultDir,
                            folders = r.folders.map { LocalLibrary.Folder(it.name, it.path, it.modifiedAt, it.mediaCount) },
                        )
                    },
                )
                rootSignatures = cached.roots.map { it.signature }
                if (signatures == rootSignatures) return // 快照仍然有效，不用扫
            }
        }

        if (force) galleryCache.clear()
        scope.launch {
            galleryScanning = true
            val groups = withContext(Dispatchers.IO) {
                roots.map { root ->
                    GalleryRootGroup(
                        path = root,
                        name = rootNameOf(root),
                        removable = root != defaultDir,
                        folders = runCatching { LocalLibrary.scanRoot(root) }.getOrDefault(emptyList()),
                    )
                }
            }
            applyRootGroups(groups)
            rootSignatures = signatures
            GalleryCacheStore.save(
                CachedGallery(
                    roots = groups.mapIndexed { index, group ->
                        CachedRoot(
                            path = group.path,
                            signature = signatures.getOrElse(index) { 0L },
                            folders = group.folders.map { CachedFolder(it.name, it.path, it.modifiedAt, it.mediaCount) },
                        )
                    },
                ),
            )
            galleryScanning = false
        }
    }

    var galleryScanning by mutableStateOf(false)
        private set
    var galleryTypeFilter by mutableStateOf("all")
    var gallerySearch by mutableStateOf("")
    var galleryFolderSearch by mutableStateOf("")
    var gallerySortBy by mutableStateOf("name")
    var gallerySortDesc by mutableStateOf(false)

    /** 未打开文件夹时「两列文件夹视图」的排序：name / time / count。 */
    var galleryFolderSort by mutableStateOf("name")
    var galleryFolderSortDesc by mutableStateOf(false)
    var galleryColumns by mutableStateOf(5)
    var galleryCoverMode by mutableStateOf(true)
    var galleryVideoThumbs by mutableStateOf(true)
    var galleryVisibleCount by mutableStateOf(120)
        private set

    private val galleryCache = mutableMapOf<String, Pair<Long, LocalLibrary.FolderContent>>()
    private var ffmpegPath: String? = null
    private var ffmpegProbed = false

    /** 扫描保存目录下的一级文件夹（每个文件夹 = 一个作者 / 作品）。 */
    fun loadGalleryRootsOnly(force: Boolean = false) {
        loadGallery(force)
    }

    fun openGalleryFolder(
        folder: LocalLibrary.Folder,
        stack: List<LocalLibrary.Folder>? = null,
        force: Boolean = false,
    ) {
        val nextStack = stack ?: (galleryStack.toList() + folder)
        galleryStack.clear(); galleryStack.addAll(nextStack)
        gallerySearch = ""
        galleryVisibleCount = 120
        scope.launch {
            galleryScanning = true
            val cached = galleryCache[folder.path]
            val content = if (!force && cached != null && cached.first == folder.modifiedAt) {
                cached.second
            } else {
                val scanned = withContext(Dispatchers.IO) {
                    runCatching { LocalLibrary.scanFolder(folder.path) }
                        .getOrDefault(LocalLibrary.FolderContent(emptyList(), emptyList()))
                }
                galleryCache[folder.path] = folder.modifiedAt to scanned
                scanned
            }
            galleryMedias.clear(); galleryMedias.addAll(content.medias)
            gallerySubfolders.clear(); gallerySubfolders.addAll(content.subfolders)
            galleryScanning = false
            // 预取前几张预览图（2048，和查看器的预览层同尺寸）：第一次点开图片不用等
            prefetch(content.medias.filter { !it.isVideo }.take(4).map { it.file }, size = 2048)
        }
    }

    fun galleryGoBack() {
        val stack = galleryStack.toList()
        if (stack.size <= 1) {
            galleryStack.clear(); galleryMedias.clear(); gallerySubfolders.clear()
            return
        }
        openGalleryFolder(stack[stack.size - 2], stack.dropLast(1))
    }

    fun galleryShowMore() {
        galleryVisibleCount += 120
    }

    private suspend fun ffmpeg(): String? {
        if (!ffmpegProbed) {
            ffmpegPath = withContext(Dispatchers.IO) { VideoThumbs.resolveFfmpeg(settings?.ffmpegDir.orEmpty()) }
            ffmpegProbed = true
        }
        return ffmpegPath
    }

    /** 视频首帧缩略图（File 直接交给 Coil），不可用时返回 null。 */
    suspend fun videoThumb(file: LocalLibrary.MediaFile): java.io.File? {
        if (!galleryVideoThumbs || !file.isVideo) return null
        val binary = ffmpeg() ?: return null
        return VideoThumbs.ensure(file.file, binary)
    }

    /* ------------------------------------------------------------ 媒体地址 */

    /** 网格缩略图：视频用封面，图片按「高清预览」开关选预览图或原图。 */
    fun gridSrc(media: Media): String? = when {
        media.kind == "video" -> media.thumbUrl
        previewMode -> media.previewUrl ?: media.thumbUrl ?: media.url.ifBlank { null }
        else -> media.url.ifBlank { null }
    }

    /** 封面（历史记录 / 任务 / 批次项）。 */
    fun coverSrc(media: Media): String? =
        media.previewUrl ?: media.thumbUrl ?: if (media.kind != "video") media.url.ifBlank { null } else null

    /** 媒体代理 URL（图片加载走本地代理）。 */
    fun mediaUrl(src: String?, referer: String? = null): String? =
        src?.takeIf { it.isNotBlank() }?.let { api.mediaUrl(it, referer = referer) }

    fun openUrl(url: String) {
        if (url.isBlank()) return
        scope.launch { runCatching { api.openUrl(url) }.onFailure { showToast("打开失败") } }
    }

    fun reveal(path: String) {
        if (path.isBlank()) return
        scope.launch { runCatching { api.reveal(path) }.onFailure { showToast("打开目录失败") } }
    }

    suspend fun namingPreview(folder: String, file: String) = withContext(Dispatchers.IO) {
        runCatching { api.previewNaming(folder, file) }.getOrNull()
    }

    /* ------------------------------------------------------------ 分组折叠 */

    /** 分组折叠状态（key = "scope/名称"，与 Tauri 版的 localStorage 行为一致）。 */
    private val collapsedGroups = mutableStateMapOf<String, Boolean>()

    fun isCollapsed(key: String): Boolean = collapsedGroups[key] == true

    fun toggleGroup(key: String) {
        collapsedGroups[key] = !(collapsedGroups[key] ?: false)
    }

    fun setCollapsed(key: String, value: Boolean) {
        collapsedGroups[key] = value
    }

    /** 默认收起的分组（账户与代理：折叠时只显示平台名，点开才看配置）。 */
    private val expandedGroups = mutableStateMapOf<String, Boolean>()

    fun isExpanded(key: String): Boolean = expandedGroups[key] == true

    fun toggleExpanded(key: String) {
        expandedGroups[key] = !(expandedGroups[key] ?: false)
    }

    fun setExpanded(key: String, value: Boolean) {
        expandedGroups[key] = value
    }

    /* ------------------------------------------------------------ 缩略图预取 */

    /** 预取封面 / 预览图，避免进入页面或点开图片后出现几秒空白。 */
    private fun prefetch(models: List<Any?>, size: Int = 512) {
        val targets = models.filterNotNull().distinct().take(60)
        if (targets.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            targets.forEach { model ->
                runCatching {
                    imageLoader.enqueue(
                        ImageRequest.Builder(PlatformContext.INSTANCE)
                            .data(model)
                            // 只解到指定尺寸，避免把 40MP 原图整个解进内存
                            .size(size, size)
                            .build(),
                    )
                }
            }
        }
    }

    private fun coverUrl(media: Media?, referer: String?): String? {
        val src = media?.let { coverSrc(it) } ?: return null
        return mediaUrl(src, referer = referer?.takeIf { it.isNotBlank() })
    }

    /* ------------------------------------------------------------ 历史维护 */

    /** 重新解析历史条目：刷新作品信息与已过期的媒体直链。 */
    fun refreshHistoryEntry(entry: HistoryEntry) {
        val url = entry.rawUrl
        if (url.isBlank()) { showToast("该记录没有原链接"); return }
        scope.launch {
            runCatching { api.parse(url, useSavedConfig) }
                .onSuccess { refreshHistory(); showToast("作品信息已更新") }
                .onFailure { showToast(it.message ?: "更新失败") }
        }
    }

    /** 取消全部未结束任务。 */
    fun cancelAllTasks() {
        val active = tasks.filter { it.status == "running" || it.status == "pending" }
        if (active.isEmpty()) return
        scope.launch {
            active.forEach { runCatching { api.cancelTask(it.id) } }
            refreshTasks()
            showToast("已取消 ${active.size} 个任务")
        }
    }

    /* ------------------------------------------------------------ 第三方解析网页 */

    /**
     * 用独立应用窗口打开第三方解析网页（Edge/Chrome `--app=` + 独立 user-data-dir），
     * 找不到 Chromium 浏览器时回退到系统默认浏览器。
     */
    fun openWebApp(url: String) {
        if (url.isBlank()) return
        scope.launch {
            val opened = withContext(Dispatchers.IO) { runCatching { WebAppWindow.open(url) }.getOrDefault(false) }
            if (!opened) {
                runCatching { api.openUrl(url) }.onFailure { showToast("打开失败") }
            }
        }
    }
}
