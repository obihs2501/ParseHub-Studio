package io.github.obihs2501.parsehubstudio.sidecar

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 启动并管理 Python sidecar（与 Tauri 版使用完全相同的协议）。
 *
 * 协议（语言无关，见 studio/src/parsehub_studio/sidecar.py）：
 *  - 通过环境变量 PARSEHUB_SESSION_TOKEN 传入随机 token
 *  - sidecar 在 stdout 打印一行 {"event":"ready","port":N}
 *  - 之后所有请求走 http://127.0.0.1:N，携带 token cookie 或 Bearer
 *  - 关闭 stdin 即通知 sidecar 退出
 */
class SidecarProcess(
    private val executable: File,
    val token: String = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""),
) {
    private var process: Process? = null
    private val json = Json { ignoreUnknownKeys = true }

    var port: Int = 0
        private set

    val baseUrl: String get() = "http://127.0.0.1:$port"

    suspend fun start(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val workDir = executable.parentFile ?: File(".")
            val proc = ProcessBuilder(executable.absolutePath)
                .directory(workDir)
                .apply {
                    environment()["PARSEHUB_SESSION_TOKEN"] = token
                    environment()["PYTHONUTF8"] = "1"
                    environment()["PYTHONIOENCODING"] = "utf-8"
                }
                .redirectErrorStream(false)
                .start()
            process = proc

            // 读取 stdout 直到 ready 事件（最多 120 秒）
            val reader = proc.inputStream.bufferedReader()
            val ready = readReady(reader, 120_000)
            if (ready == null) {
                proc.destroyForcibly()
                error("Python 服务启动失败或超时")
            }
            port = ready
        }
    }

    private fun readReady(reader: BufferedReader, timeoutMs: Long): Int? {
        val deadline = System.currentTimeMillis() + timeoutMs
        val line = StringBuilder()
        while (System.currentTimeMillis() < deadline) {
            if (reader.ready()) {
                val c = reader.read()
                if (c == -1) break
                if (c == '\n'.code) {
                    val text = line.toString().trim()
                    line.clear()
                    if (text.isNotEmpty()) {
                        runCatching {
                            val obj = json.parseToJsonElement(text).jsonObject
                            if (obj["event"]?.jsonPrimitive?.content == "ready") {
                                return obj["port"]?.jsonPrimitive?.content?.toIntOrNull()
                            }
                        }
                    }
                } else {
                    line.append(c.toChar())
                }
            } else {
                if (!process!!.isAlive) return null
                Thread.sleep(50)
            }
        }
        return null
    }

    fun stop() {
        val proc = process ?: return
        runCatching { proc.outputStream.close() }
        if (!proc.waitFor(8, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
        }
        process = null
    }

    fun isAlive(): Boolean = process?.isAlive == true

    companion object {
        /** 定位 sidecar 可执行文件：优先与主程序同目录，其次开发目录。 */
        fun locate(): File? {
            val exeName = if (System.getProperty("os.name").lowercase().contains("win")) {
                "parsehub-sidecar.exe"
            } else {
                "parsehub-sidecar"
            }
            val candidates = mutableListOf<File>()

            // 1) 与当前可执行文件同目录（打包后）
            runCatching {
                val self = File(SidecarProcess::class.java.protectionDomain.codeSource.location.toURI())
                candidates += File(self.parentFile, exeName)
            }
            // 2) 开发目录：studio/dist/parsehub-sidecar.exe
            val cwd = File(System.getProperty("user.dir"))
            candidates += File(cwd, "dist/$exeName")
            candidates += File(cwd, "../studio/dist/$exeName")
            candidates += File(cwd, "studio/dist/$exeName")

            return candidates.firstOrNull { it.isFile }
        }
    }
}
