package io.github.obihs2501.parsehubstudio

import io.github.obihs2501.parsehubstudio.model.ParseResult
import io.github.obihs2501.parsehubstudio.model.Task
import io.github.obihs2501.parsehubstudio.sidecar.apiJson
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 回归测试：`published_at` 在后端是 `int | str` —— 抖音给 create_time（epoch 秒，JSON 里是数字），
 * 微博给 ISO 日期串。早先模型写死 String，抖音解析结果一返回就抛
 *
 *     Expected quotation mark '', but had '1' instead at path: $.published_at
 *
 * 解析工作台整页不可用（2026-10-05 用户报障）。
 */
class ModelDecodeTest {

    /** 与 ApiClient 完全一致的解码配置（coerceInputValues 兜住 `null` 脏值）。 */
    private val json = apiJson

    /** 严格配置（无 coerceInputValues）：单独证明 StringOrNumberSerializer 本身就能修好 published_at。 */
    private val strict = Json { ignoreUnknownKeys = true }

    /** 报障里那条抖音分享链接的解析结果（字段按 /api/parse 实际返回，published_at 是数字）。 */
    private val douyinPayload = """
        {
          "platform": {"id": "douyin", "name": "抖音"},
          "type": "video",
          "type_label": "视频",
          "title": "少女贤君的登基之路",
          "content": "少女贤君的登基之路 #帝政裙 #变装",
          "raw_url": "https://v.douyin.com/Xj7PiTrMa6o/",
          "name": "少女贤君的登基之路",
          "author": "莹宝",
          "author_id": "9861933175031",
          "published_at": 1790257398,
          "post_id": "7689096921072222271",
          "media": [
            {"url": "https://example.com/video.mp4", "kind": "video", "kind_label": "视频",
             "thumb_url": null, "preview_url": null, "width": 1080, "height": 1920,
             "duration": 12, "ext": null, "video_url": null}
          ],
          "media_count": 1
        }
    """.trimIndent()

    @Test
    fun douyinPublishedAtNumberDecodes() {
        val result = json.decodeFromString(ParseResult.serializer(), douyinPayload)
        assertEquals("1790257398", result.publishedAt)
        assertEquals("7689096921072222271", result.postId)
        assertEquals("9861933175031", result.authorId)
        assertEquals(1, result.media.size)
    }

    @Test
    fun publishedAtNumberDecodesEvenWithoutCoercion() {
        // 单独验证自定义序列化器：即使没有 coerceInputValues（ext 不是 null），数字也能解
        val payload = douyinPayload.replace("\"ext\": null", "\"ext\": \"mp4\"")
        val result = strict.decodeFromString(ParseResult.serializer(), payload)
        assertEquals("1790257398", result.publishedAt)
    }

    @Test
    fun nullMediaFieldsFallBackToDefaults() {
        val result = json.decodeFromString(ParseResult.serializer(), douyinPayload)
        assertEquals("", result.media.first().ext)
    }

    @Test
    fun weiboPublishedAtStringIsPreserved() {
        val payload = douyinPayload.replace("1790257398", "\"2026-09-28T01:00:00+08:00\"")
        val result = json.decodeFromString(ParseResult.serializer(), payload)
        assertEquals("2026-09-28T01:00:00+08:00", result.publishedAt)
    }

    @Test
    fun missingPublishedAtDefaultsToEmpty() {
        val payload = douyinPayload.replace("\"published_at\": 1790257398,", "")
        val result = json.decodeFromString(ParseResult.serializer(), payload)
        assertEquals("", result.publishedAt)
    }

    /** 任务列表（/api/tasks）里的 published_at 同样是数字（tasks.py 直接透传解析 payload）。 */
    @Test
    fun taskPublishedAtNumberDecodes() {
        val payload = """
            {"id": "t1", "text": "https://v.douyin.com/Xj7PiTrMa6o/", "status": "running",
             "progress": 0.5, "current": 3, "total": 6, "unit": "个", "published_at": 1790257398,
             "created_at": 1790257400.5, "finished_at": null}
        """.trimIndent()
        val task = json.decodeFromString(Task.serializer(), payload)
        assertEquals("1790257398", task.publishedAt)
    }
}
