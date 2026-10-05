package io.github.obihs2501.parsehubstudio.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * 本地图库的数据源：直接扫描下载目录（不经过后端）。
 *
 * 目录结构约定（与下载模板一致）：
 *  - 保存目录下的一级文件夹 = 一个作者 / 一个作品
 *  - 文件夹里直接放媒体文件，也可能有更深的子目录
 */
object LocalLibrary {

    val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic", "jfif")
    val VIDEO_EXTS = setOf("mp4", "mov", "webm", "mkv", "m4v", "avi", "ts", "flv", "wmv", "mpg")

    data class Folder(
        val name: String,
        val path: String,
        val modifiedAt: Long = 0L,
        val mediaCount: Int = 0,
    )

    data class MediaFile(
        val name: String,
        val path: String,
        val isVideo: Boolean,
        val sizeBytes: Long = 0L,
        val modifiedAt: Long = 0L,
    ) {
        val file: File get() = File(path)
    }

    data class FolderContent(val medias: List<MediaFile>, val subfolders: List<Folder>)

    fun isImage(name: String) = name.substringAfterLast('.', "").lowercase() in IMAGE_EXTS
    fun isVideo(name: String) = name.substringAfterLast('.', "").lowercase() in VIDEO_EXTS
    fun isMedia(name: String) = isImage(name) || isVideo(name)

    /** 扫描根目录下的一级文件夹（含媒体数量统计，最多向下两层）。 */
    fun scanRoot(root: String): List<Folder> {
        val base = File(root)
        if (!base.isDirectory) return emptyList()
        return base.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.map { dir ->
                Folder(
                    name = dir.name,
                    path = dir.absolutePath,
                    modifiedAt = dir.lastModified(),
                    mediaCount = countMedia(dir, depth = 2),
                )
            }
            ?.sortedByDescending { it.modifiedAt }
            .orEmpty()
    }

    private fun countMedia(dir: File, depth: Int): Int {
        if (depth < 0) return 0
        val children = dir.listFiles() ?: return 0
        var count = 0
        for (child in children) {
            when {
                child.isFile && isMedia(child.name) -> count++
                child.isDirectory && !child.name.startsWith(".") -> count += countMedia(child, depth - 1)
            }
        }
        return count
    }

    /** 扫描单个文件夹：本层媒体文件 + 本层子文件夹。 */
    fun scanFolder(path: String): FolderContent {
        val dir = File(path)
        val children = dir.listFiles() ?: return FolderContent(emptyList(), emptyList())
        val medias = mutableListOf<MediaFile>()
        val subfolders = mutableListOf<Folder>()
        for (child in children) {
            if (child.name.startsWith(".")) continue
            when {
                child.isDirectory -> subfolders += Folder(
                    name = child.name,
                    path = child.absolutePath,
                    modifiedAt = child.lastModified(),
                    mediaCount = countMedia(child, depth = 1),
                )
                child.isFile && isMedia(child.name) -> medias += MediaFile(
                    name = child.name,
                    path = child.absolutePath,
                    isVideo = isVideo(child.name),
                    sizeBytes = child.length(),
                    modifiedAt = child.lastModified(),
                )
            }
        }
        return FolderContent(medias, subfolders.sortedByDescending { it.modifiedAt })
    }
}

/**
 * 视频首帧缩略图：用本机 ffmpeg 抽一帧缓存成 jpg（图库里视频卡片才有画面）。
 * 找不到 ffmpeg 时返回 null，调用方回退到占位图。
 */
object VideoThumbs {
    private val semaphore = Semaphore(2)

    private fun cacheDir(): File = File(
        System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"),
        "ParseHubStudio/videothumbs",
    )

    /** ffmpeg 可执行文件：优先用设置里的目录，其次 PATH。 */
    fun resolveFfmpeg(configuredDir: String): String? {
        val dir = configuredDir.trim()
        if (dir.isNotEmpty()) {
            val candidate = File(dir, "ffmpeg.exe")
            if (candidate.isFile) return candidate.absolutePath
            val plain = File(dir, "ffmpeg")
            if (plain.isFile) return plain.absolutePath
        }
        return runCatching {
            val probe = ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start()
            probe.inputStream.readBytes()
            probe.waitFor(4, TimeUnit.SECONDS)
            if (probe.exitValue() == 0) "ffmpeg" else null
        }.getOrNull()
    }

    /**
     * 取（或生成）视频首帧缩略图；失败返回 null。
     *
     * 参考 x-spider：卡片进视口才排队生成、离开视口就取消 —— 协程被取消时**立刻销毁 ffmpeg**，
     * 不让它继续跑完（滚动图库时白占 CPU 就是这么来的）。
     */
    suspend fun ensure(video: File, ffmpeg: String): File? = withContext(Dispatchers.IO) {
        if (!video.isFile) return@withContext null
        val dir = cacheDir()
        if (!dir.isDirectory && !dir.mkdirs()) return@withContext null
        val key = "${Integer.toHexString(video.absolutePath.hashCode())}-${video.lastModified()}-${video.length()}"
        val target = File(dir, "$key.jpg")
        if (target.isFile && target.length() > 0) return@withContext target
        semaphore.withPermit {
            if (target.isFile && target.length() > 0) return@withPermit target
            val process = runCatching {
                ProcessBuilder(
                    ffmpeg, "-y", "-loglevel", "error",
                    "-i", video.absolutePath,
                    "-frames:v", "1",
                    "-vf", "scale=640:-2",
                    "-q:v", "4",
                    target.absolutePath,
                )
                    // 丢弃输出：不占管道、也不会因为没读管道把 ffmpeg 卡住
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            }.getOrNull() ?: return@withPermit null

            runCatching {
                val deadline = System.currentTimeMillis() + 30_000
                while (process.isAlive && System.currentTimeMillis() < deadline) {
                    if (!coroutineContext.isActive) {
                        process.destroy()
                        return@withPermit null
                    }
                    process.waitFor(120, TimeUnit.MILLISECONDS)
                }
                if (target.isFile && target.length() > 0) target else null
            }.getOrNull()
        }
    }
}
