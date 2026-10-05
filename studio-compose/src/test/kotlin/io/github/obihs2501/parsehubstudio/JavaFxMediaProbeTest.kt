package io.github.obihs2501.parsehubstudio

import io.github.obihs2501.parsehubstudio.ui.videoMediaUri
import javafx.application.Platform
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 内置播放器（JavaFX Media）的回归测试：确认本机能解析本地 mp4（native 解码器可用）。
 *
 * 只验证「媒体能加载、能读出时长」，不渲染画面（不需要窗口）。
 * 测试素材 `%TEMP%/parsehub-play-test.mp4` 由 ffmpeg 生成（见 DEV_NOTES §7.6）；
 * 没有素材或没有图形环境（CI）时直接跳过，避免误报。
 *
 * 注意：**必须自己持有 Media / MediaPlayer 的强引用** —— 媒体引擎对它们是弱引用，
 * 只在 runLater 块里用局部变量的话，可能在 READY 之前就被 GC，表现为「一直等不到 onReady」。
 */
class JavaFxMediaProbeTest {

    @Test
    fun mediaPlayerLoadsLocalMp4() {
        val dir = System.getenv("TEMP")?.let(::File) ?: File(System.getProperty("java.io.tmpdir"))
        val file = File(dir, "parsehub-play-test.mp4")
        if (!file.isFile) return

        // 初始化 JavaFX 工具箱（headless / CI 上会抛异常，直接跳过）
        val toolkitReady = runCatching { Platform.startup {} }.isSuccess ||
            runCatching { Platform.runLater {} }.isSuccess
        if (!toolkitReady) return

        val latch = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        val durationMs = AtomicReference(0.0)
        val mediaRef = AtomicReference<Media?>(null)
        val playerRef = AtomicReference<MediaPlayer?>(null)

        Platform.runLater {
            runCatching {
                val uri = videoMediaUri(file)
                println("[probe] media uri = $uri")
                val media = Media(uri)
                val player = MediaPlayer(media)
                mediaRef.set(media)
                playerRef.set(player)
                player.statusProperty().addListener { _, _, value -> println("[probe] status = $value") }
                player.setOnReady {
                    println("[probe] ready, duration = ${media.duration}")
                    durationMs.set(media.duration?.toMillis() ?: 0.0)
                    latch.countDown()
                }
                media.setOnError {
                    println("[probe] media error = ${media.error}")
                    failure.set(media.error?.message ?: "media error")
                    latch.countDown()
                }
                player.setOnError {
                    println("[probe] player error = ${player.error}")
                    failure.set(player.error?.message ?: "player error")
                    latch.countDown()
                }
            }.onFailure {
                println("[probe] construction failed: $it")
                failure.set(it.message ?: it.toString())
                latch.countDown()
            }
        }

        val ok = latch.await(30, TimeUnit.SECONDS)
        println("[probe] ok=$ok failure=${failure.get()} duration=${durationMs.get()}")

        assertTrue(ok, "JavaFX Media 初始化超时")
        assertTrue(failure.get() == null, "JavaFX Media 加载失败：${failure.get()}")
        assertTrue(durationMs.get() > 0.0, "JavaFX Media 未解析出视频时长")
    }
}
