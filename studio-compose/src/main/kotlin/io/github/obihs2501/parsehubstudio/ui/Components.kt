package io.github.obihs2501.parsehubstudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 大标题页头。 */
@Composable
fun PageHero(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 8.dp, bottom = 4.dp)) {
        Text(title, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onBackground)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, fontSize = 14.sp, color = MiuixTheme.colorScheme.onBackgroundVariant)
    }
}

/** SmallTitle 分组标题（inside margin 28/8）。 */
@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 14.dp, bottom = 4.dp),
        fontSize = 14.sp,
        color = MiuixTheme.colorScheme.onBackgroundVariant,
    )
}

/** miuix 风格偏好行。 */
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
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurfaceContainer)
            if (summary != null) {
                Spacer(Modifier.height(2.dp))
                Text(summary, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** 卡片容器（miuix Card，圆角 16，间距 12）。 */
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
        Text(text, fontSize = 14.sp, color = MiuixTheme.colorScheme.onBackgroundVariant)
    }
}

/** 药丸标签。 */
@Composable
fun Pill(text: String, accent: Boolean = false) {
    val bg = if (accent) MiuixTheme.colorScheme.tertiaryContainer else MiuixTheme.colorScheme.secondaryVariant
    val fg = if (accent) MiuixTheme.colorScheme.onTertiaryContainer else MiuixTheme.colorScheme.onSurfaceVariantSummary
    Box(Modifier.background(bg).padding(horizontal = 10.dp, vertical = 3.dp)) {
        Text(text, fontSize = 11.sp, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
