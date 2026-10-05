package io.github.obihs2501.parsehubstudio.library

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 界面偏好（可拖动栏宽等）。
 *
 * 后端 settings 接口不认未知字段，所以单独存一个小 JSON，不污染 settings.json。
 */
@Serializable
data class UiPrefs(
    val navWidth: Float = 230f,
    val galleryWidth: Float = 252f,
    /** 查看器底部缩略图条是否显示（图库看图时可隐藏）。 */
    val showStrip: Boolean = true,
)

object UiPrefsStore {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private fun file(): File = File(
        System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"),
        "ParseHubStudio/ui-prefs.json",
    )

    fun load(): UiPrefs = runCatching {
        val target = file()
        if (!target.isFile) return UiPrefs()
        json.decodeFromString(UiPrefs.serializer(), target.readText())
    }.getOrDefault(UiPrefs())

    fun save(prefs: UiPrefs) {
        runCatching {
            val target = file()
            target.parentFile?.mkdirs()
            target.writeText(json.encodeToString(UiPrefs.serializer(), prefs))
        }
    }
}
