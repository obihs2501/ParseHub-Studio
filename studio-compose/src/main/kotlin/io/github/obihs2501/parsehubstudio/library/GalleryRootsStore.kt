package io.github.obihs2501.parsehubstudio.library

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 本地图库的「额外根目录」列表（下载目录始终可用，这里只存用户额外添加的文件夹）。
 *
 * 后端设置接口不认未知字段，所以单独存一个小 JSON，不污染 settings.json。
 */
object GalleryRootsStore {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private fun file(): File = File(
        System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"),
        "ParseHubStudio/gallery-roots.json",
    )

    private val serializer = ListSerializer(String.serializer())

    fun load(): List<String> = runCatching {
        val target = file()
        if (!target.isFile) return emptyList()
        json.decodeFromString(serializer, target.readText())
    }.getOrDefault(emptyList())

    fun save(paths: List<String>) {
        runCatching {
            val target = file()
            target.parentFile?.mkdirs()
            target.writeText(json.encodeToString(serializer, paths))
        }
    }
}
