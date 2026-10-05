package io.github.obihs2501.parsehubstudio

import io.github.obihs2501.parsehubstudio.ui.VlcSupport
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * VLC（vlcj）探针：确认「本机装了 VLC 时，ParseHub 真的能调用它解码视频」。
 *
 * 没装 VLC（或没有测试素材）就跳过 —— CI 上不会因为缺少 VLC 而失败。
 * 测试素材 `%TEMP%/parsehub-play-test.mp4` 由 ffmpeg 生成（见 DEV_NOTES）。
 */
class VlcProbeTest {

    @Test
    fun vlcPlaysLocalMp4() {
        if (!VlcSupport.available) return

        val dir = System.getenv("TEMP")?.let(::File) ?: File(System.getProperty("java.io.tmpdir"))
        val file = File(dir, "parsehub-play-test.mp4")
        if (!file.isFile) return

        val factory = MediaPlayerFactory()
        val player = factory.mediaPlayers().newMediaPlayer()
        try {
            val latch = CountDownLatch(1)
            val lengthMs = AtomicLong(0L)
            val failed = AtomicBoolean(false)
            player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
                    lengthMs.set(newLength)
                    latch.countDown()
                }
                override fun playing(mediaPlayer: MediaPlayer) = latch.countDown()
                override fun error(mediaPlayer: MediaPlayer) {
                    failed.set(true)
                    latch.countDown()
                }
            })
            player.media().play(file.absolutePath)
            latch.await(25, TimeUnit.SECONDS)
            assertTrue(!failed.get(), "VLC 播放失败（libvlc 报错）")
            assertTrue(lengthMs.get() > 0L, "VLC 未解析出视频长度")
        } finally {
            runCatching { player.release() }
            runCatching { factory.release() }
        }
    }
}
