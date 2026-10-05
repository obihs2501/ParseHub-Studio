package io.github.obihs2501.parsehubstudio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent
import java.io.File

/**
 * 本机是否装了 VLC（libvlc）：只探测一次，探测结果决定用哪个播放器。
 *
 * 装了 VLC → 用 VLC 播放（HEVC / .ts / MKV / AV1 都能放）；
 * 没装 → 回退到内置 JavaFX 播放器（H.264/AAC MP4），再不行交给系统播放器。
 */
object VlcSupport {
    val available: Boolean by lazy {
        runCatching { NativeDiscovery().discover() }.getOrDefault(false)
    }
}

/**
 * VLC 播放器（vlcj + `EmbeddedMediaPlayerComponent` 嵌进 Compose `SwingPanel`）。
 *
 * 为什么首选 VLC：编解码覆盖最全（用户的库里大量 HEVC / .ts 是 JavaFX 放不了的），
 * 代价是需要本机安装 VLC（不随包分发）。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun VlcVideoPlayer(
    file: File,
    modifier: Modifier = Modifier,
    onFallback: (() -> Unit)? = null,
) {
    val component = remember(file) { runCatching { EmbeddedMediaPlayerComponent() }.getOrNull() }
    var error by remember(file) { mutableStateOf<String?>(null) }
    var playing by remember(file) { mutableStateOf(false) }
    var lengthMs by remember(file) { mutableLongStateOf(0L) }
    var timeMs by remember(file) { mutableLongStateOf(0L) }
    var muted by remember(file) { mutableStateOf(false) }

    LaunchedEffect(component, file) {
        val player = component?.mediaPlayer()
        if (player == null) {
            error = "VLC 初始化失败"
            return@LaunchedEffect
        }
        runCatching {
            player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) { timeMs = newTime }
                override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) { lengthMs = newLength }
                override fun playing(mediaPlayer: MediaPlayer) { playing = true }
                override fun paused(mediaPlayer: MediaPlayer) { playing = false }
                override fun stopped(mediaPlayer: MediaPlayer) { playing = false }
                override fun finished(mediaPlayer: MediaPlayer) { playing = false }
                override fun error(mediaPlayer: MediaPlayer) { error = "VLC 无法播放该视频" }
            })
            player.media().play(file.absolutePath)
        }.onFailure { error = it.message ?: "VLC 播放失败" }
    }

    DisposableEffect(component) {
        onDispose { runCatching { component?.release() } }
    }

    Column(modifier) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            if (error != null || component == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .squircleBackground(MiuixTheme.colorScheme.surfaceContainerHighest, 12.dp)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("VLC 放不了这个视频", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceContainerHighest)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                error ?: "",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                maxLines = 2,
                            )
                            if (onFallback != null) {
                                Spacer(Modifier.height(8.dp))
                                SimpleChip("用系统播放器打开", primary = true, onClick = onFallback)
                            }
                        }
                    }
                }
            } else {
                SwingPanel(
                    background = Color.Black,
                    factory = { component },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // ---- 播放控制（Compose 侧，跟随 miuix 主题）
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IconButton(
                onClick = {
                    component?.mediaPlayer()?.controls()?.let { controls ->
                        if (playing) controls.pause() else controls.play()
                    }
                },
                minHeight = 36.dp,
                minWidth = 36.dp,
                backgroundColor = MiuixTheme.colorScheme.secondaryVariant,
            ) {
                if (playing) {
                    PauseGlyph(MiuixTheme.colorScheme.onSurfaceVariantActions)
                } else {
                    Icon(MiuixIcons.Regular.Play, "播放", Modifier.size(18.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantActions)
                }
            }
            Text(
                formatDuration((timeMs / 1000).toInt()),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            SeekBar(
                fraction = if (lengthMs > 0) timeMs.toDouble() / lengthMs else 0.0,
                modifier = Modifier.weight(1f),
                onSeek = { fraction -> component?.mediaPlayer()?.controls()?.setPosition(fraction.toFloat()) },
            )
            Text(
                formatDuration((lengthMs / 1000).toInt()),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            SimpleChip(if (muted) "取消静音" else "静音") {
                muted = !muted
                component?.mediaPlayer()?.audio()?.setMute(muted)
            }
        }
    }
}
