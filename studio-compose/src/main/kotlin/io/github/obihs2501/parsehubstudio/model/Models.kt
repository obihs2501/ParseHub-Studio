package io.github.obihs2501.parsehubstudio.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 平台信息（/api/platforms、/api/config）。 */
@Serializable
data class Platform(
    val id: String,
    val name: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("supported_type") val supportedType: List<String> = emptyList(),
    @SerialName("parse_proxy") val parseProxy: String? = null,
    @SerialName("download_proxy") val downloadProxy: String? = null,
    @SerialName("cookie_set") val cookieSet: Boolean = false,
    @SerialName("cookie_hint") val cookieHint: String? = null,
    @SerialName("cookie_length") val cookieLength: Int = 0,
    @SerialName("cookie_pool") val cookiePool: List<CookieEntry> = emptyList(),
) {
    val label: String get() = displayName.ifBlank { name.ifBlank { id } }
}

@Serializable
data class CookieEntry(
    val id: String,
    val name: String = "",
    val enabled: Boolean = true,
    val hint: String? = null,
)

/** 单条媒体（/api/parse 与任务 payload）。 */
@Serializable
data class Media(
    val url: String = "",
    val kind: String = "unknown",
    @SerialName("kind_label") val kindLabel: String = "媒体",
    @SerialName("thumb_url") val thumbUrl: String? = null,
    @SerialName("preview_url") val previewUrl: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val duration: Int = 0,
    val ext: String = "",
    @SerialName("video_url") val videoUrl: String? = null,
)

/** 解析结果（/api/parse、批次 item.result、任务 payload）。 */
@Serializable
data class ParseResult(
    val platform: PlatformRef? = null,
    val type: String = "unknown",
    @SerialName("type_label") val typeLabel: String = "未知",
    val title: String = "",
    val content: String = "",
    @SerialName("raw_url") val rawUrl: String = "",
    val name: String = "",
    val author: String = "",
    @SerialName("author_id") val authorId: String = "",
    @SerialName("published_at") val publishedAt: String = "",
    @SerialName("post_id") val postId: String = "",
    val media: List<Media> = emptyList(),
    @SerialName("media_count") val mediaCount: Int = 0,
    val markdown: String = "",
    val html: String = "",
)

@Serializable
data class PlatformRef(val id: String = "", val name: String = "")

/** 下载任务（/api/tasks）。 */
@Serializable
data class Task(
    val id: String,
    val text: String = "",
    val status: String = "pending",
    val stage: String = "",
    val progress: Double = 0.0,
    val current: Long = 0,
    val total: Long = 0,
    val unit: String = "",
    val error: String? = null,
    @SerialName("output_dir") val outputDir: String? = null,
    val files: List<String> = emptyList(),
    val platform: String? = null,
    val title: String? = null,
    val thumbnail: String? = null,
    @SerialName("thumbnail_original") val thumbnailOriginal: String? = null,
    val author: String = "",
    @SerialName("post_id") val postId: String = "",
    @SerialName("raw_url") val rawUrl: String = "",
    @SerialName("published_at") val publishedAt: String = "",
    @SerialName("created_at") val createdAt: Double = 0.0,
    @SerialName("finished_at") val finishedAt: Double? = null,
)

/** 历史记录条目。 */
@Serializable
data class HistoryEntry(
    val id: String,
    val platform: PlatformRef? = null,
    val title: String = "",
    val content: String = "",
    val author: String = "",
    @SerialName("post_id") val postId: String = "",
    @SerialName("raw_url") val rawUrl: String = "",
    val media: List<Media> = emptyList(),
    @SerialName("media_count") val mediaCount: Int = 0,
    val time: Double = 0.0,
)

/** 批次。 */
@Serializable
data class Batch(
    val id: String,
    val status: String = "running",
    @SerialName("created_at") val createdAt: Double = 0.0,
    val items: List<BatchItem> = emptyList(),
)

@Serializable
data class BatchItem(
    val id: String,
    val url: String = "",
    val status: String = "pending",
    val result: ParseResult? = null,
    val error: String? = null,
    @SerialName("download_id") val downloadId: String? = null,
)

/** 应用设置（/api/settings）。 */
@Serializable
data class Settings(
    @SerialName("default_save_dir") val defaultSaveDir: String = "",
    val connections: Int = 4,
    @SerialName("save_metadata") val saveMetadata: Boolean = false,
    val theme: String = "dark",
    @SerialName("open_after_download") val openAfterDownload: Boolean = false,
    @SerialName("preview_mode") val previewMode: Boolean = true,
    @SerialName("download_quality") val downloadQuality: String = "origin",
    @SerialName("proxy_mode") val proxyMode: String = "system",
    @SerialName("proxy_url") val proxyUrl: String = "",
    @SerialName("ffmpeg_dir") val ffmpegDir: String = "",
    @SerialName("folder_template") val folderTemplate: String = "{platform}_{title}_{id}",
    @SerialName("file_template") val fileTemplate: String = "{index:03d}_{title}",
    @SerialName("merge_author_folders") val mergeAuthorFolders: Boolean = true,
    @SerialName("author_aliases") val authorAliases: List<AuthorAlias> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
)

@Serializable
data class AuthorAlias(
    val platform: String = "",
    val account: String = "",
    val creator: String = "",
    @SerialName("match_by") val matchBy: String = "id",
)

@Serializable
data class Bookmark(
    val name: String = "",
    val url: String = "",
    val group: String = "通用",
)
