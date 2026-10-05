package io.github.obihs2501.parsehubstudio.sidecar

import io.github.obihs2501.parsehubstudio.model.*
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 调用 Python sidecar 的 HTTP 客户端（对应 28 个 /api 路由）。 */
class ApiClient(private val sidecar: SidecarProcess) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val client = HttpClient {
        install(ContentNegotiation) { json(json) }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.auth() {
        header("Authorization", "Bearer ${sidecar.token}")
    }

    private suspend inline fun <reified T> HttpResponse.decode(): T = body()

    private suspend fun check(response: HttpResponse) {
        if (response.status.value >= 400) {
            val text = runCatching { response.bodyAsText() }.getOrDefault("")
            val detail = runCatching {
                json.parseToJsonElement(text).let {
                    (it as? JsonObject)?.get("detail")?.toString()?.trim('"')
                }
            }.getOrNull()
            throw ApiException(detail ?: "请求失败 (${response.status.value})")
        }
    }

    // ---------------------------------------------------------------- 基础
    suspend fun health(): JsonObject =
        client.get("${sidecar.baseUrl}/api/health") { auth() }.also { check(it) }.body()

    suspend fun platforms(): List<Platform> {
        val res = client.get("${sidecar.baseUrl}/api/platforms") { auth() }
        check(res)
        return json.parseToJsonElement(res.bodyAsText()).let { root ->
            val arr = (root as JsonObject)["platforms"] ?: return emptyList()
            json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(Platform.serializer()), arr)
        }
    }

    suspend fun detect(text: String): JsonObject =
        client.post("${sidecar.baseUrl}/api/detect") {
            auth(); contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("text", text) }.toString())
        }.also { check(it) }.body()

    suspend fun clean(text: String, useSavedConfig: Boolean = true): JsonObject =
        client.post("${sidecar.baseUrl}/api/clean") {
            auth(); contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("text", text); put("use_saved_config", useSavedConfig) }.toString())
        }.also { check(it) }.body()

    // ---------------------------------------------------------------- 解析
    suspend fun parse(text: String, useSavedConfig: Boolean = true): ParseResult {
        val res = client.post("${sidecar.baseUrl}/api/parse") {
            auth(); contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("text", text); put("use_saved_config", useSavedConfig) }.toString())
        }
        check(res)
        return json.decodeFromString(ParseResult.serializer(), res.bodyAsText())
    }

    // ---------------------------------------------------------------- 批次
    suspend fun createBatch(text: String, download: Boolean, useSavedConfig: Boolean = true): Batch {
        val res = client.post("${sidecar.baseUrl}/api/batches") {
            auth(); contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("text", text); put("download", download); put("use_saved_config", useSavedConfig)
            }.toString())
        }
        check(res)
        return json.decodeFromString(Batch.serializer(), res.bodyAsText())
    }

    suspend fun getBatch(id: String): Batch {
        val res = client.get("${sidecar.baseUrl}/api/batches/$id") { auth() }
        check(res)
        return json.decodeFromString(Batch.serializer(), res.bodyAsText())
    }

    suspend fun cancelBatch(id: String) {
        val res = client.post("${sidecar.baseUrl}/api/batches/$id/cancel") { auth() }
        check(res)
    }

    suspend fun previewNaming(folder: String, file: String): JsonObject =
        client.post("${sidecar.baseUrl}/api/naming/preview") {
            auth(); contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("folder_template", folder); put("file_template", file) }.toString())
        }.also { check(it) }.body()

    // ---------------------------------------------------------------- 任务
    suspend fun tasks(): List<Task> {
        val res = client.get("${sidecar.baseUrl}/api/tasks") { auth() }
        check(res)
        return json.parseToJsonElement(res.bodyAsText()).let { root ->
            val arr = (root as JsonObject)["tasks"] ?: return emptyList()
            json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(Task.serializer()), arr)
        }
    }

    suspend fun createTask(text: String, mediaIndex: Int? = null, quality: String = "origin"): Task {
        val res = client.post("${sidecar.baseUrl}/api/tasks") {
            auth(); contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("text", text); put("quality", quality)
                if (mediaIndex != null) put("media_index", mediaIndex)
            }.toString())
        }
        check(res)
        return json.decodeFromString(Task.serializer(), res.bodyAsText())
    }

    suspend fun cancelTask(id: String) {
        val res = client.post("${sidecar.baseUrl}/api/tasks/$id/cancel") { auth() }
        check(res)
    }

    suspend fun clearTasks() {
        val res = client.post("${sidecar.baseUrl}/api/tasks/clear") { auth() }
        check(res)
    }

    // ---------------------------------------------------------------- 配置
    suspend fun getConfig(): JsonObject =
        client.get("${sidecar.baseUrl}/api/config") { auth() }.also { check(it) }.body()

    suspend fun setProxy(platform: String, proxy: String, target: String = "all"): Platform {
        val res = client.post("${sidecar.baseUrl}/api/config/proxy") {
            auth(); contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("platform", platform); put("proxy", proxy); put("target", target) }.toString())
        }
        check(res)
        return json.decodeFromString(Platform.serializer(), res.bodyAsText())
    }

    suspend fun clearProxy(platform: String, target: String = "all"): Platform {
        val res = client.post("${sidecar.baseUrl}/api/config/proxy/clear") {
            auth(); contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("platform", platform); put("target", target) }.toString())
        }
        check(res)
        return json.decodeFromString(Platform.serializer(), res.bodyAsText())
    }

    suspend fun setCookie(platform: String, cookie: String): Platform {
        val res = client.post("${sidecar.baseUrl}/api/config/cookie") {
            auth(); contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("platform", platform); put("cookie", cookie) }.toString())
        }
        check(res)
        return json.decodeFromString(Platform.serializer(), res.bodyAsText())
    }

    suspend fun clearCookie(platform: String): Platform {
        val res = client.post("${sidecar.baseUrl}/api/config/cookie/clear") {
            auth(); contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("platform", platform); put("cookie", "") }.toString())
        }
        check(res)
        return json.decodeFromString(Platform.serializer(), res.bodyAsText())
    }

    suspend fun updateCookiePool(
        platform: String, cookie: String = "", name: String = "", entryId: String = "", enabled: Boolean? = null,
    ): Platform {
        val res = client.post("${sidecar.baseUrl}/api/config/cookie-pool") {
            auth(); contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("platform", platform); put("cookie", cookie); put("name", name); put("entry_id", entryId)
                if (enabled != null) put("enabled", enabled)
            }.toString())
        }
        check(res)
        return json.decodeFromString(Platform.serializer(), res.bodyAsText())
    }

    // ---------------------------------------------------------------- 设置
    suspend fun settings(): Settings {
        val res = client.get("${sidecar.baseUrl}/api/settings") { auth() }
        check(res)
        return json.decodeFromString(Settings.serializer(), res.bodyAsText())
    }

    suspend fun updateSettings(patch: JsonObject): Settings {
        val res = client.post("${sidecar.baseUrl}/api/settings") {
            auth(); contentType(ContentType.Application.Json)
            setBody(patch.toString())
        }
        check(res)
        return json.decodeFromString(Settings.serializer(), res.bodyAsText())
    }

    // ---------------------------------------------------------------- 历史
    suspend fun history(): List<HistoryEntry> {
        val res = client.get("${sidecar.baseUrl}/api/history") { auth() }
        check(res)
        return json.parseToJsonElement(res.bodyAsText()).let { root ->
            val arr = (root as JsonObject)["entries"] ?: return emptyList()
            json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(HistoryEntry.serializer()), arr)
        }
    }

    suspend fun deleteHistory(id: String) {
        val res = client.delete("${sidecar.baseUrl}/api/history/$id") { auth() }
        check(res)
    }

    suspend fun clearHistory() {
        val res = client.post("${sidecar.baseUrl}/api/history/clear") { auth() }
        check(res)
    }

    // ---------------------------------------------------------------- 文件系统
    suspend fun listDirs(path: String? = null): FsListing {
        val url = buildString {
            append("${sidecar.baseUrl}/api/fs/list")
            if (!path.isNullOrBlank()) append("?path=${java.net.URLEncoder.encode(path, "UTF-8")}")
        }
        val res = client.get(url) { auth() }
        check(res)
        return json.decodeFromString(FsListing.serializer(), res.bodyAsText())
    }

    suspend fun reveal(path: String) {
        val res = client.post("${sidecar.baseUrl}/api/reveal") {
            auth(); contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("path", path) }.toString())
        }
        check(res)
    }

    suspend fun openUrl(url: String) {
        val res = client.post("${sidecar.baseUrl}/api/open-url") {
            auth(); contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("path", url) }.toString())
        }
        check(res)
    }

    /** 媒体代理 URL（用于 Compose 加载图片，绕过 Referer/CORS）。 */
    fun mediaUrl(src: String, download: Boolean = false, filename: String? = null, referer: String? = null): String {
        val encoded = java.net.URLEncoder.encode(src, "UTF-8")
        return buildString {
            append("${sidecar.baseUrl}/api/media?src=$encoded")
            if (download) append("&download=1")
            if (!filename.isNullOrBlank()) append("&filename=${java.net.URLEncoder.encode(filename, "UTF-8")}")
            if (!referer.isNullOrBlank()) append("&referer=${java.net.URLEncoder.encode(referer, "UTF-8")}")
        }
    }

    /** sidecar 地址与 token（供图片加载器复用同一鉴权）。 */
    val baseUrl: String get() = sidecar.baseUrl
    val token: String get() = sidecar.token

    fun close() = client.close()
}

class ApiException(message: String) : Exception(message)
