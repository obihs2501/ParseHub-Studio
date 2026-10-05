package io.github.obihs2501.parsehubstudio.sidecar

import java.io.File

/**
 * 用 Chromium 内核浏览器以「应用窗口」模式打开网页 / 播放本地视频。
 *
 * 目的：第三方解析工具（如 KuKuTool）需要一个独立窗口，且不与默认浏览器共享
 * Cookie —— 因此使用 `--app=` 模式 + 独立 `--user-data-dir`。
 * 视频同理：借系统已装的 Edge/Chrome 播放，零额外依赖，不往软件里塞播放器内核。
 * 找不到 Edge / Chrome 时返回 false，由调用方回退到系统默认浏览器 / 播放器。
 */
object WebAppWindow {
    private val candidates: List<String> = buildList {
        val local = System.getenv("LOCALAPPDATA")
        val programFiles = System.getenv("ProgramFiles")
        val programFilesX86 = System.getenv("ProgramFiles(x86)")
        listOfNotNull(programFilesX86, programFiles).forEach { base ->
            add("$base\\Microsoft\\Edge\\Application\\msedge.exe")
            add("$base\\Google\\Chrome\\Application\\chrome.exe")
        }
        local?.let {
            add("$it\\Microsoft\\Edge\\Application\\msedge.exe")
            add("$it\\Google\\Chrome\\Application\\chrome.exe")
        }
    }

    private fun browser(): File? = candidates.asSequence().map(::File).firstOrNull { it.isFile }

    private fun profile(): File {
        val dir = File(
            System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"),
            "ParseHubStudio/webapp",
        )
        runCatching { dir.mkdirs() }
        return dir
    }

    /** 以应用窗口打开网页；找不到 Chromium 浏览器时返回 false。 */
    fun open(url: String): Boolean {
        if (url.isBlank()) return false
        val exe = browser() ?: return false
        ProcessBuilder(
            exe.absolutePath,
            "--app=$url",
            "--user-data-dir=${profile().absolutePath}",
            "--no-first-run",
            "--no-default-browser-check",
            "--window-size=1120,840",
        ).start()
        return true
    }

    /**
     * 用应用窗口播放本地视频（Chromium 自带播放器，支持 mp4/webm 等）。
     * 找不到 Chromium 浏览器时返回 false。
     */
    fun playFile(file: File): Boolean {
        if (!file.isFile) return false
        val exe = browser() ?: return false
        // Chromium 要 "file:///C:/…" 三斜杠形式；File.toURI() 给的是单斜杠，会被当成路径吃掉盘符
        val url = "file:///" + file.absolutePath.replace('\\', '/')
        ProcessBuilder(
            exe.absolutePath,
            "--app=$url",
            "--user-data-dir=${profile().absolutePath}",
            "--no-first-run",
            "--no-default-browser-check",
            // 打开即播，不需要先点一下
            "--autoplay-policy=no-user-gesture-required",
            "--window-size=1280,800",
        ).start()
        return true
    }
}
