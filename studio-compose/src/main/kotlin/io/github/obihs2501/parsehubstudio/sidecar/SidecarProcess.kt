package io.github.obihs2501.parsehubstudio.sidecar

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * sidecar 的一种启动方式：
 *  - 打包后：直接运行 `parsehub-sidecar.exe`
 *  - 开发时：用 studio/.venv 的 python 运行 `-m parsehub_studio.sidecar`（无需先打 exe）
 */
data class SidecarLaunch(
    val command: List<String>,
    val workDir: File,
    val label: String,
    val pythonPath: String? = null,
)

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
    private val launch: SidecarLaunch,
    val token: String = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""),
) {
    private var process: Process? = null
    private val json = Json { ignoreUnknownKeys = true }

    /** stderr 最后 40 行：启动失败时直接摆到错误信息里，不用再去翻日志。 */
    private val stderrTail = ArrayDeque<String>()
    private val stderrLock = Any()
    private var stderrThread: Thread? = null

    var port: Int = 0
        private set

    val baseUrl: String get() = "http://127.0.0.1:$port"

    suspend fun start(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val proc = ProcessBuilder(launch.command)
                .directory(launch.workDir)
                .apply {
                    environment()["PARSEHUB_SESSION_TOKEN"] = token
                    environment()["PYTHONUTF8"] = "1"
                    environment()["PYTHONIOENCODING"] = "utf-8"
                    environment()["PYTHONUNBUFFERED"] = "1"
                    launch.pythonPath?.let { environment()["PYTHONPATH"] = it }
                }
                .redirectErrorStream(false)
                .start()
            process = proc
            // stderr 必须一直有人读：管道写满（约 64KB）子进程会永久阻塞，表现和
            // 「启动超时」一模一样；崩溃原因（如 ImportError: DLL load failed）也只有这里能看到。
            // 每次启动清空上一轮输出，避免重试失败时拿旧进程的报错去诊断。
            synchronized(stderrLock) { stderrTail.clear() }
            pumpStderr(proc)

            // 读取 stdout 直到 ready 事件（最多 120 秒）
            val reader = proc.inputStream.bufferedReader()
            val ready = readReady(reader, 120_000)
            if (ready == null) {
                val exit = runCatching { proc.exitValue() }.getOrNull()
                proc.destroyForcibly()
                // 给 stderr 线程一点时间把管道里最后几行读完（进程已死，很快到 EOF）
                runCatching { stderrThread?.join(500) }
                error(
                    buildString {
                        append("Python 服务启动失败或超时")
                        append(if (exit != null) "（进程已退出，退出码 $exit）" else "（120 秒内没有就绪）")
                        val tail = stderrTailText()
                        if (tail.isNotBlank()) append("\n\nsidecar 输出：\n").append(tail)
                        append("\n\n完整日志：").append(logFile().absolutePath)
                    },
                )
            }
            port = ready
        }
    }

    /** 后台线程持续读 stderr：追加到日志文件，并在内存里保留最后若干行。 */
    private fun pumpStderr(proc: Process) {
        stderrThread = Thread({
            // 日志开不了也要把 stderr 读干（否则管道写满会卡死子进程）
            val writer = runCatching {
                val log = logFile()
                log.parentFile?.mkdirs()
                FileOutputStream(log, true).bufferedWriter()
            }.getOrNull()
            try {
                writer?.write("\n===== ${LocalDateTime.now()} 启动 sidecar：${launch.label} =====\n")
                proc.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(stderrLock) {
                            stderrTail.addLast(line)
                            while (stderrTail.size > 40) stderrTail.removeFirst()
                        }
                        if (writer != null) {
                            runCatching {
                                writer.write(line)
                                writer.newLine()
                            }
                        }
                    }
                }
            } finally {
                runCatching { writer?.close() }
            }
        }, "sidecar-stderr").apply { isDaemon = true; start() }
    }

    private fun stderrTailText(): String = synchronized(stderrLock) { stderrTail.joinToString("\n") }

    /** 与界面偏好（ui-prefs.json）同一目录，方便用户/CI 直接收集。 */
    private fun logFile(): File = File(
        System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"),
        "ParseHubStudio/sidecar.log",
    )

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
        private val isWindows: Boolean
            get() = System.getProperty("os.name").lowercase().contains("win")

        private val exeName: String
            get() = if (isWindows) "parsehub-sidecar.exe" else "parsehub-sidecar"

        /** 定位 sidecar：优先打包后的可执行文件，其次开发用的 python 环境。 */
        fun locate(): SidecarLaunch? = locateExecutable()?.let {
            SidecarLaunch(listOf(it.absolutePath), it.parentFile ?: File("."), it.name)
        } ?: locateDevPython()

        /** 定位 sidecar 可执行文件：打包后与主程序同目录，其次开发目录。 */
        private fun locateExecutable(): File? {
            val candidates = mutableListOf<File>()

            // 1) 与当前 classpath 同目录及其子目录（jpackage 把 jar 放在 app/，
            //    appResourcesRootDir 的内容落在 app/resources/）
            runCatching {
                val self = File(SidecarProcess::class.java.protectionDomain.codeSource.location.toURI())
                val appDir = self.parentFile
                candidates += File(appDir, "resources/$exeName")
                candidates += File(appDir, exeName)
                appDir?.parentFile?.let {
                    candidates += File(it, exeName)
                    candidates += File(it, "resources/$exeName")
                }
            }
            // 2) 启动器所在目录（jpackage 会设置 jpackage.app-path）
            System.getProperty("jpackage.app-path")?.let {
                candidates += File(File(it).parentFile, exeName)
            }
            // 3) 开发目录：studio/dist/parsehub-sidecar.exe
            val cwd = File(System.getProperty("user.dir"))
            candidates += File(cwd, exeName)
            candidates += File(cwd, "dist/$exeName")
            candidates += File(cwd, "../studio/dist/$exeName")
            candidates += File(cwd, "studio/dist/$exeName")

            return candidates.firstOrNull { it.isFile }
        }

        /**
         * 开发模式回退：不依赖打包好的 exe，直接用 studio/.venv 的 python
         * 运行 `-m parsehub_studio.sidecar`（venv 里 parsehub_studio 是 editable 安装）。
         *
         * 可用 PARSEHUB_SIDECAR_PYTHON 显式指定解释器（配合 PARSEHUB_SIDECAR_CWD / PARSEHUB_SIDECAR_SRC）。
         */
        private fun locateDevPython(): SidecarLaunch? {
            System.getenv("PARSEHUB_SIDECAR_PYTHON")?.takeIf { it.isNotBlank() }?.let { python ->
                val cwd = System.getenv("PARSEHUB_SIDECAR_CWD")?.takeIf { it.isNotBlank() }?.let(::File)
                    ?: File(System.getProperty("user.dir"))
                return SidecarLaunch(
                    command = listOf(python, "-m", "parsehub_studio.sidecar"),
                    workDir = cwd,
                    label = "python -m parsehub_studio.sidecar",
                    pythonPath = System.getenv("PARSEHUB_SIDECAR_SRC")?.takeIf { it.isNotBlank() },
                )
            }

            val cwd = File(System.getProperty("user.dir"))
            val relativeVenvs = listOf(
                "../studio/.venv",
                "studio/.venv",
                ".venv",
            )
            val scripts = if (isWindows) "Scripts/python.exe" else "bin/python"
            for (relative in relativeVenvs) {
                val venv = File(cwd, relative)
                val python = File(venv, scripts)
                if (!python.isFile) continue
                val studioDir = venv.parentFile ?: continue
                val source = File(studioDir, "src/parsehub_studio/sidecar.py")
                if (!source.isFile) continue
                return SidecarLaunch(
                    command = listOf(python.absolutePath, "-m", "parsehub_studio.sidecar"),
                    workDir = studioDir,
                    label = "python -m parsehub_studio.sidecar (${venv.name})",
                    pythonPath = File(studioDir, "src").absolutePath,
                )
            }
            return null
        }
    }
}
