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
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme

/*
 * 播放器共用组件（VLC 播放器用；内置 JavaFX 播放器已按反馈移除，省掉 ~35 MB native）。
 */

/** 手绘暂停图标（miuix 图标集里没有 Pause）。 */
@Composable
internal fun PauseGlyph(tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.width(4.dp).height(16.dp).squircleBackground(tint, 1.dp))
        Box(Modifier.width(4.dp).height(16.dp).squircleBackground(tint, 1.dp))
    }
}

/** 进度条：点 / 拖定位（自绘，避免依赖 miuix Slider 的版本差异）。 */
@Composable
internal fun SeekBar(fraction: Double, modifier: Modifier = Modifier, onSeek: (Double) -> Unit) {
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

/** 小胶囊按钮。 */
@Composable
internal fun SimpleChip(text: String, primary: Boolean = false, onClick: () -> Unit) {
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

/**
 * 没装 VLC 时的占位：「用系统播放器打开」+「下载 VLC」。
 *
 * 应用内播放依赖本机 VLC（用户已确认可以装），因此不再内置播放器内核。
 */
@Composable
internal fun VideoUnavailableNotice(onPlayExternal: () -> Unit, onGetVlc: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .squircleBackground(MiuixTheme.colorScheme.surfaceContainerHighest, 14.dp)
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "未检测到 VLC，应用内暂时无法播放视频",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceContainerHighest,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "装好 VLC 后重启应用即可（HEVC / .ts 也能放）",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SimpleChip("下载 VLC", primary = true, onClick = onGetVlc)
                    SimpleChip("用系统播放器打开", onClick = onPlayExternal)
                }
            }
        }
    }
}
