package io.github.obihs2501.parsehubstudio.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * 「字符串或数字」都接受，统一按字符串读取。
 *
 * 后端的 `published_at` 是 `int | str`：抖音给的是 create_time（epoch 秒，JSON 里是数字），
 * 微博给的是 ISO 日期串。模型写死成 String 时，抖音解析结果会直接抛
 * `Expected quotation mark '', but had '1' instead at path: $.published_at`，
 * 整个解析工作台不可用 —— 这里与 Tauri 前端（naming.publication_date）一样兼容两种形态。
 */
object StringOrNumberSerializer : KSerializer<String> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("StringOrNumber", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeString()
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonNull -> ""
            is JsonPrimitive -> element.content
            else -> element.toString()
        }
    }

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

/** 平台信息（/api/platforms、/api/config）。 */
@Serializable
data class Platform(
    val id: String,
    val name: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("supported_types") val supportedTypes: List<String> = emptyList(),
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
    /** 可选码流（抖音等多档位平台，按清晰度从高到低；空表示只有一条流）。 */
    val variants: List<MediaVariant> = emptyList(),
)

/** 一条可选码流（/api/parse 的 media[].variants）。 */
@Serializable
data class MediaVariant(
    val url: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val size: Long = 0,
    val bitrate: Int = 0,
    val gear: String = "",
    val codec: String = "",
) {
    /** 短边像素：竖屏视频（2160×3840）也叫 2160P，与抖音自己的档位命名一致。 */
    val shortSide: Int
        get() = listOf(width, height).filter { it > 0 }.minOrNull() ?: 0

    /** 芯片文字，如「2160P · 13.7 MB」。 */
    val label: String
        get() = buildString {
            val side = shortSide
            append(if (side > 0) "${side}P" else "${width}×${height}")
            if (size > 0) append(" · ").append(String.format(java.util.Locale.US, "%.1f MB", size / 1024.0 / 1024.0))
        }
}

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
    /** 抖音是 epoch 秒（数字），微博是 ISO 日期串 —— 两种都要能解，见 [StringOrNumberSerializer]。 */
    @SerialName("published_at")
    @Serializable(with = StringOrNumberSerializer::class)
    val publishedAt: String = "",
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
    /** 同 [ParseResult.publishedAt]：数字 / 字符串都能解。 */
    @SerialName("published_at")
    @Serializable(with = StringOrNumberSerializer::class)
    val publishedAt: String = "",
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

/** 目录浏览（/api/fs/list）。 */
@Serializable
data class FsListing(
    val path: String = "",
    val parent: String? = null,
    val dirs: List<FsDir> = emptyList(),
)

@Serializable
data class FsDir(val name: String = "", val path: String = "")
