package io.github.obihs2501.parsehubstudio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.scene.media.MediaView
import javafx.util.Duration
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/**
 * JavaFX Media 的媒体地址：必须是 `file:///C:/…` 三斜杠 + 百分号编码的形式。
 *
 * `File.toURI()` 给的是 `file:/C:/…`（单斜杠），部分 JavaFX 版本会解析失败；
 * 中文 / 空格文件名必须走 URI 编码，所以先取 ASCII 形式再补斜杠。
 */
internal fun videoMediaUri(file: File): String {
    val uri = file.toURI().toASCIIString()
    return if (Regex("^file:/[A-Za-z]:/").containsMatchIn(uri)) "file:///" + uri.removePrefix("file:/") else uri
}

/**
 * 内置视频播放器：JavaFX Media（自带 H.264/AAC 解码器）+ JFXPanel 嵌进 Compose。
 *
 * 为什么不用外部播放器：用户要「应用内播放」；为什么不用 VLCJ：它依赖本机装 VLC。
 * JavaFX Media 的 native 随 jar 打包（约 35 MB），HLS/MP4 可用；
 * HEVC / 独立 .ts 这类它放不了，走 [onFallback] 交给系统播放器。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun EmbeddedVideoPlayer(
    file: File,
    modifier: Modifier = Modifier,
    onFallback: (() -> Unit)? = null,
) {
    val panel = remember(file) { JFXPanel() }
    val holder = remember(file) { PlayerHolder() }
    var error by remember(file) { mutableStateOf<String?>(null) }
    var playing by remember(file) { mutableStateOf(false) }
    var durationMs by remember(file) { mutableStateOf(0.0) }
    var positionMs by remember(file) { mutableStateOf(0.0) }

    LaunchedEffect(file) {
        Platform.runLater {
            runCatching {
                val media = Media(videoMediaUri(file))
                val player = MediaPlayer(media)
                val view = MediaView(player)
                view.isPreserveRatio = true
                val root = StackPane(view)
                root.style = "-fx-background-color: black;"
                view.fitWidthProperty().bind(root.widthProperty())
                view.fitHeightProperty().bind(root.heightProperty())
                panel.scene = Scene(root).apply { fill = javafx.scene.paint.Color.BLACK }

                player.setOnReady {
                    durationMs = media.duration?.toMillis() ?: 0.0
                    player.play()
                }
                player.currentTimeProperty().addListener { _, _, value -> positionMs = value?.toMillis() ?: 0.0 }
                player.statusProperty().addListener { _, _, value -> playing = value == MediaPlayer.Status.PLAYING }
                player.setOnEndOfMedia {
                    player.seek(Duration.ZERO)
                    player.pause()
                }
                player.setOnError { error = player.error?.message ?: "播放失败" }
                media.setOnError { error = media.error?.message ?: "无法解析该视频" }
                holder.player = player
            }.onFailure { error = it.message ?: "无法播放该视频" }
        }
    }

    DisposableEffect(file) {
        onDispose {
            Platform.runLater {
                runCatching { holder.player?.stop() }
                runCatching { holder.player?.dispose() }
                holder.player = null
                runCatching { panel.scene = null }
            }
        }
    }

    Column(modifier) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            if (error != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .squircleBackground(MiuixTheme.colorScheme.surfaceContainerHighest, 12.dp)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("内置播放器放不了这个视频", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceContainerHighest)
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
                    factory = { panel },
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
                    holder.player?.let { player ->
                        Platform.runLater {
                            if (player.status == MediaPlayer.Status.PLAYING) player.pause() else player.play()
                        }
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
                formatDuration((positionMs / 1000).toInt()),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            SeekBar(
                fraction = if (durationMs > 0) positionMs / durationMs else 0.0,
                modifier = Modifier.weight(1f),
                onSeek = { fraction ->
                    holder.player?.let { player ->
                        Platform.runLater { player.seek(Duration.millis(fraction * durationMs)) }
                    }
                },
            )
            Text(
                formatDuration((durationMs / 1000).toInt()),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

private class PlayerHolder {
    var player: MediaPlayer? = null
}

/** 手绘暂停图标（miuix 图标集里没有 Pause）。 */
@Composable
private fun PauseGlyph(tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.width(4.dp).height(16.dp).squircleBackground(tint, 1.dp))
        Box(Modifier.width(4.dp).height(16.dp).squircleBackground(tint, 1.dp))
    }
}

/** 进度条：点 / 拖定位（自绘，避免依赖 miuix Slider 的版本差异）。 */
@Composable
private fun SeekBar(fraction: Double, modifier: Modifier = Modifier, onSeek: (Double) -> Unit) {
    var widthPx by remember { mutableStateOf(1) }
    Box(
        modifier
            .height(20.dp)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) {
                detectTapGestures { offset -> onSeek((offset.x / widthPx).toDouble().coerceIn(0.0, 1.0)) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onSeek((change.position.x / widthPx).toDouble().coerceIn(0.0, 1.0))
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).squircleBackground(MiuixTheme.colorScheme.secondaryVariant, 2.dp))
        Box(
            Modifier
                .fillMaxWidth(fraction.toFloat().coerceIn(0f, 1f))
                .height(4.dp)
                .squircleBackground(MiuixTheme.colorScheme.primary, 2.dp),
        )
    }
}

/** 小胶囊按钮（播放器兜底操作用）。 */
@Composable
private fun SimpleChip(text: String, primary: Boolean = false, onClick: () -> Unit) {
    val bg = if (primary) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.secondaryVariant
    val fg = if (primary) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurfaceVariantActions
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .squircleBackground(bg, 10.dp)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, style = MiuixTheme.textStyles.footnote1, color = fg)
    }
}
