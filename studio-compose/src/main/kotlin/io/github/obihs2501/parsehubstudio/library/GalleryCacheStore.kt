package io.github.obihs2501.parsehubstudio.library

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** 图库扫描结果的持久化快照：启动后先用它铺界面，再按根目录签名决定是否重扫。 */
@Serializable
data class CachedGallery(
    val roots: List<CachedRoot> = emptyList(),
)

@Serializable
data class CachedRoot(
    val path: String,
    val signature: Long = 0L,
    val folders: List<CachedFolder> = emptyList(),
)

@Serializable
data class CachedFolder(
    val name: String,
    val path: String,
    val modifiedAt: Long = 0L,
    val mediaCount: Int = 0,
)

object GalleryCacheStore {

    private val json = Json { ignoreUnknownKeys = true }

    private fun file(): File = File(
        System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"),
        "ParseHubStudio/gallery-cache.json",
    )

    fun load(): CachedGallery = runCatching {
        val target = file()
        if (!target.isFile) return CachedGallery()
        json.decodeFromString(CachedGallery.serializer(), target.readText())
    }.getOrDefault(CachedGallery())

    fun save(cache: CachedGallery) {
        runCatching {
            val target = file()
            target.parentFile?.mkdirs()
            target.writeText(json.encodeToString(CachedGallery.serializer(), cache))
        }
    }
}
