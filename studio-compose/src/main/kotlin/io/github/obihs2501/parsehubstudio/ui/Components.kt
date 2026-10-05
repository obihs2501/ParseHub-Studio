package io.github.obihs2501.parsehubstudio.ui

import io.github.obihs2501.parsehubstudio.ui.AppFont

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.obihs2501.parsehubstudio.model.Media
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.awt.Cursor

/*
 * 排版一律走 MiuixTheme.textStyles（miuix 主题不提供 LocalTextStyle，
 * 每个 Text 必须显式带样式）：
 *   title1 32 / title2 24 / title3 20 / title4 18
 *   headline1 17 Medium（偏好行标题）/ main 17 / body1 16 / body2 14
 *   subtitle 14 Bold（分组小标题）/ footnote1 13 / footnote2 11
 * 次级文字用 onSurfaceVariantSummary（浅色 60% 黑），
 * onBackgroundVariant（#8C93B0）只用于极弱提示。
 */

/** 大标题页头（对应 miuix TopAppBar 的 title1 大标题，不加粗）。 */
@Composable
fun PageHero(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 10.dp, bottom = 6.dp)) {
        Text(title, style = MiuixTheme.textStyles.title1, color = MiuixTheme.colorScheme.onBackground)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

/** 分组标题（等同 miuix SmallTitle：subtitle 14 Bold，inside margin 28/8）。 */
@Composable
fun SectionTitle(text: String) {
    SmallTitle(text)
}

/** miuix 风格偏好行（标题 headline1 17 Medium，摘要 body2 14，inside margin 16）。 */
@Composable
fun PrefRow(
    title: String,
    summary: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(interaction, null, onClick = onClick) else Modifier)
            .padding(BasicComponentDefaults.InsideMargin),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = MiuixTheme.textStyles.headline1.fontSize, fontFamily = AppFont.family,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onBackground,
            )
            if (summary != null) {
                Spacer(Modifier.height(2.dp))
                Text(summary, fontSize = MiuixTheme.textStyles.body2.fontSize, fontFamily = AppFont.family, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** 可折叠分组头（账户与代理按平台折叠、解析选项折叠…）。 */
@Composable
fun GroupHeader(
    title: String,
    count: Int? = null,
    summary: String? = null,
    expanded: Boolean,
    onToggle: () -> Unit,
    /** 传入后在最右侧显示一个小的「移除」按钮（用于额外添加的图库文件夹，避免误触）。 */
    onRemove: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            // 有底色 + 高亮箭头，和普通标题行区分开，一眼看出可点
            .squircleSurface(MiuixTheme.colorScheme.secondaryVariant, 12.dp)
            .clickable(interaction, null, onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = MiuixTheme.textStyles.headline1.fontSize, fontFamily = AppFont.family,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    summary,
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize, fontFamily = AppFont.family,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (count != null) {
            Text("$count", fontSize = MiuixTheme.textStyles.footnote1.fontSize, fontFamily = AppFont.family, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Spacer(Modifier.width(6.dp))
        }
        if (onRemove != null) {
            val removeInteraction = remember { MutableInteractionSource() }
            val removeHovered by removeInteraction.collectIsHoveredAsState()
            Box(
                Modifier
                    .size(28.dp)
                    .hoverable(removeInteraction)
                    // 无底色的小叉：深浅色模式下都干净；悬停才变红，避免误触
                    .squircleSurface(
                        if (removeHovered) MiuixTheme.colorScheme.error.copy(alpha = 0.14f) else Color.Transparent,
                        9.dp,
                    )
                    .clickable(removeInteraction, indication = null, onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    MiuixIcons.Regular.Close,
                    "从图库移除该文件夹",
                    Modifier.size(15.dp),
                    tint = if (removeHovered) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        Icon(
            if (expanded) MiuixIcons.Regular.ExpandLess else MiuixIcons.Regular.ExpandMore,
            contentDescription = if (expanded) "收起" else "展开",
            modifier = Modifier.size(22.dp),
            tint = MiuixTheme.colorScheme.primary,
        )
    }
}

/**
 * 可拖动的分栏把手：左右拖动调整相邻两栏宽度（悬停变手型 + 高亮）。
 *
 * [onDelta] 拖动增量（dp）；[onFinished] 松手时触发（用于落盘）。
 */
@Composable
fun ResizableDivider(onDelta: (Float) -> Unit, onFinished: () -> Unit = {}) {
    val density = LocalDensity.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier
            .fillMaxHeight()
            .width(10.dp)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta -> onDelta(with(density) { delta.toDp() }.value) },
                interactionSource = remember { MutableInteractionSource() },
                onDragStopped = { onFinished() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .width(if (hovered) 3.dp else 1.dp)
                .background(if (hovered) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.dividerLine),
        )
    }
}

/** 卡片容器（miuix Card：圆角 16，间距 12）。 */
@Composable
fun SectionCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
        insideMargin = PaddingValues(0.dp),
    ) {
        content()
    }
}

/** 行分隔线。 */
@Composable
fun RowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .height(1.dp)
            .background(MiuixTheme.colorScheme.dividerLine),
    )
}

/** 空状态。 */
@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, fontSize = MiuixTheme.textStyles.body2.fontSize, fontFamily = AppFont.family, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

/** 药丸标签。 */
@Composable
fun Pill(text: String, accent: Boolean = false) {
    val bg = if (accent) MiuixTheme.colorScheme.tertiaryContainer else MiuixTheme.colorScheme.secondaryVariant
    val fg = if (accent) MiuixTheme.colorScheme.onTertiaryContainer else MiuixTheme.colorScheme.onSurfaceVariantSummary
    Box(Modifier.background(bg).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(text, fontSize = MiuixTheme.textStyles.footnote2.fontSize, fontFamily = AppFont.family, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 进度条。 */
@Composable
fun ProgressBar(fraction: Float, done: Boolean = false) {
    Box(Modifier.fillMaxWidth().height(6.dp).background(MiuixTheme.colorScheme.secondaryVariant)) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .background(if (done) Color(0xFF34C759) else MiuixTheme.colorScheme.primary),
        )
    }
}

/** 媒体网格：等宽方格，点击查看大图。 */
@Composable
fun MediaGrid(
    items: List<Media>,
    srcOf: (Media) -> String?,
    onClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 4,
) {
    Column(modifier.fillMaxWidth()) {
        items.chunked(columns).forEachIndexed { rowIndex, row ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEachIndexed { columnIndex, media ->
                    val index = rowIndex * columns + columnIndex
                    MediaCell(
                        media = media,
                        url = srcOf(media),
                        modifier = Modifier.weight(1f),
                        onClick = { onClick(index) },
                    )
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun MediaCell(media: Media, url: String?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(modifier.aspectRatio(1f)) {
        RemoteThumb(
            model = url,
            modifier = Modifier.fillMaxSize(),
            radius = 12.dp,
            contentDescription = media.kindLabel,
        )
        val badge = when (media.kind) {
            "video" -> "视频"
            "livephoto" -> "实况"
            "ani" -> "动图"
            else -> null
        }
        if (badge != null) {
            Box(Modifier.align(Alignment.BottomStart).padding(6.dp)) {
                Pill(badge)
            }
        }
        Box(Modifier.fillMaxSize().clickable(interaction, indication = null, onClick = onClick))
    }
}

/** 时间戳（秒）→ yyyy-MM-dd HH:mm。 */
fun formatTimestamp(seconds: Double): String {
    if (seconds <= 0.0) return ""
    return runCatching {
        java.time.Instant.ofEpochSecond(seconds.toLong())
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    }.getOrDefault("")
}

/** 毫秒时间戳 → 相对时间（今天 HH:mm / 今年 MM-DD / 其他 yyyy-MM-DD）。 */
fun formatRelativeTime(millis: Long): String {
    if (millis <= 0L) return ""
    return runCatching {
        val zone = java.time.ZoneId.systemDefault()
        val time = java.time.Instant.ofEpochMilli(millis).atZone(zone)
        val now = java.time.ZonedDateTime.now(zone)
        when {
            time.toLocalDate() == now.toLocalDate() -> time.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
            time.year == now.year -> time.format(java.time.format.DateTimeFormatter.ofPattern("MM-dd"))
            else -> time.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        }
    }.getOrDefault("")
}

/** 字节数 → 人类可读（1.2 MB）。 */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return ""
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.size - 1) {
        value /= 1024
        unit++
    }
    return if (value < 10 && unit > 0) "%.1f %s".format(value, units[unit]) else "${Math.round(value)} ${units[unit]}"
}

/** 秒 → m:ss / h:mm:ss。 */
fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return ""
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** 任务 / 批次状态文案。 */
fun statusLabel(status: String): String = when (status) {
    "pending" -> "排队中"
    "running" -> "进行中"
    "success" -> "已完成"
    "partial" -> "部分完成"
    "error" -> "失败"
    "cancelled" -> "已取消"
    "complete" -> "已完成"
    else -> status
}

/** 任务 / 批次状态颜色。 */
@Composable
fun statusColor(status: String): Color = when (status) {
    "running" -> MiuixTheme.colorScheme.primary
    "success", "complete" -> Color(0xFF34C759)
    "partial" -> Color(0xFFC99A3F)
    "error" -> MiuixTheme.colorScheme.error
    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
}

private val MEDIA_EXTENSIONS = setOf(
    "jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic",
    "mp4", "mov", "m4a", "mp3", "webm", "m3u8", "ts",
)

/**
 * 判断链接是否像「作品页」而不是临时媒体直链。
 *
 * 后端保存的 raw_url 是清理掉解析参数后的作品链接，但部分平台的字段可能落到
 * CDN 直链（会过期），这类链接不适合作为「打开原帖」的目标。
 */
fun isLikelyPostUrl(url: String): Boolean {
    if (!url.startsWith("http://") && !url.startsWith("https://")) return false
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    val ext = path.substringAfterLast('.', "")
    return ext !in MEDIA_EXTENSIONS
}
