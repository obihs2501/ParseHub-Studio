package io.github.obihs2501.parsehubstudio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.obihs2501.parsehubstudio.model.*
import io.github.obihs2501.parsehubstudio.sidecar.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 导航页面。 */
enum class Screen(val title: String, val subtitle: String) {
    PARSE("解析工作台", "粘贴链接，留住喜欢的内容"),
    TASKS("下载管理", "本次运行的任务与进度"),
    HISTORY("历史记录", "按平台与作者归档"),
    COOKIES("Cookie 池", "按平台轮换账户"),
    BOOKMARKS("解析网站收藏", "手动使用的第三方工具"),
    SETTINGS("设置", "偏好与本地环境"),
}

/** 应用级状态（Compose 单例）。 */
class AppState(
    val api: ApiClient,
    private val scope: CoroutineScope,
) {
    var screen by mutableStateOf(Screen.PARSE)
        private set

    var connected by mutableStateOf(false)
        private set
    var version by mutableStateOf("")
        private set

    var input by mutableStateOf("")
    var quality by mutableStateOf("origin")
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

    fun navigate(target: Screen) {
        screen = target
        when (target) {
            Screen.TASKS -> refreshTasks()
            Screen.HISTORY -> refreshHistory()
            Screen.COOKIES, Screen.BOOKMARKS, Screen.SETTINGS -> refreshPlatforms()
            else -> {}
        }
    }

    fun showToast(message: String) {
        toast = message
        scope.launch {
            kotlinx.coroutines.delay(1800)
            if (toast == message) toast = null
        }
    }

    fun bootstrap() {
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
        runCatching { api.tasks() }.onSuccess {
            tasks.clear(); tasks.addAll(it)
        }
    }

    fun refreshHistory() = scope.launch {
        runCatching { api.history() }.onSuccess {
            history.clear(); history.addAll(it)
        }
    }

    fun parse() {
        val text = input.trim()
        if (text.isEmpty()) { showToast("请先粘贴链接"); return }
        parsing = true
        error = null
        scope.launch {
            runCatching { api.parse(text, useSavedConfig) }
                .onSuccess { result = it; refreshHistory() }
                .onFailure { error = it.message; showToast(it.message ?: "解析失败") }
            parsing = false
        }
    }

    fun downloadAll() {
        val text = input.trim().ifEmpty { result?.rawUrl.orEmpty() }
        if (text.isEmpty()) { showToast("请先粘贴链接"); return }
        scope.launch {
            runCatching { api.createTask(text, quality = quality) }
                .onSuccess { showToast("已加入下载队列"); refreshTasks() }
                .onFailure { showToast(it.message ?: "创建任务失败") }
        }
    }

    fun downloadMedia(index: Int) {
        val text = input.trim().ifEmpty { result?.rawUrl.orEmpty() }
        if (text.isEmpty()) return
        scope.launch {
            runCatching { api.createTask(text, mediaIndex = index, quality = quality) }
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

    fun openUrl(url: String) {
        if (url.isBlank()) return
        scope.launch { runCatching { api.openUrl(url) }.onFailure { showToast("打开失败") } }
    }

    fun reveal(path: String) {
        if (path.isBlank()) return
        scope.launch { runCatching { api.reveal(path) }.onFailure { showToast("打开目录失败") } }
    }

    fun cancelTask(id: String) = scope.launch {
        runCatching { api.cancelTask(id) }.onSuccess { refreshTasks() }
    }

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

    fun deleteHistory(id: String) = scope.launch {
        runCatching { api.deleteHistory(id) }.onSuccess { refreshHistory() }
    }

    fun clearHistory() = scope.launch {
        runCatching { api.clearHistory() }.onSuccess { refreshHistory() }
    }

    fun saveSettings(patch: Map<String, kotlinx.serialization.json.JsonElement>) = scope.launch {
        runCatching {
            api.updateSettings(kotlinx.serialization.json.JsonObject(patch))
        }.onSuccess { settings = it }.onFailure { showToast(it.message ?: "保存失败") }
    }

    /** 媒体代理 URL（图片加载走本地代理）。 */
    fun mediaUrl(src: String): String = api.mediaUrl(src)

    suspend fun namingPreview(folder: String, file: String) = withContext(Dispatchers.IO) {
        runCatching { api.previewNaming(folder, file) }.getOrNull()
    }
}
