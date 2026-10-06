package io.github.obihs2501.parsehubstudio.ui.screens

import io.github.obihs2501.parsehubstudio.ui.AppFont

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.obihs2501.parsehubstudio.AppState
import io.github.obihs2501.parsehubstudio.model.BatchItem
import io.github.obihs2501.parsehubstudio.ui.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ParseScreen(state: AppState) {
    PageHero("解析工作台", "粘贴链接，留住喜欢的内容")

    // 输入框（SearchBar 风格）
    Column(Modifier.padding(horizontal = 12.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MiuixTheme.colorScheme.secondaryVariant)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            if (state.input.isEmpty()) {
                Text(
                    "粘贴分享文案或多个链接，每行一条",
                    style = MiuixTheme.textStyles.main,
                    color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            }
            BasicTextField(
                value = state.input,
                onValueChange = { state.input = it },
                textStyle = TextStyle(
                    fontSize = MiuixTheme.textStyles.main.fontSize, fontFamily = AppFont.family,
                    color = MiuixTheme.colorScheme.onBackground,
                ),
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "最多 50 条 · 自动去重 · 3 路并发",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.weight(1f))
            TextButton("清空", onClick = { state.clearParse() })
            Spacer(Modifier.width(6.dp))
            TextButton("清洗链接", onClick = { state.cleanInput() })
            Spacer(Modifier.width(6.dp))
            Button(onClick = { state.startBatch(download = true) }, enabled = !state.batchCreating) {
                Text(if (state.batchCreating) "提交中…" else "批量解析并下载", style = MiuixTheme.textStyles.button)
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { state.parse() }, colors = ButtonDefaults.buttonColorsPrimary()) {
                Text(
                    if (state.parsing) "解析中…" else "开始解析",
                    style = MiuixTheme.textStyles.button,
                    color = MiuixTheme.colorScheme.onPrimary,
                )
            }
        }
    }

    // 解析选项收进可折叠分组（默认收起），不占用一级界面
    var optionsExpanded by remember { mutableStateOf(false) }
    SectionCard {
        GroupHeader("解析选项", expanded = optionsExpanded, onToggle = { optionsExpanded = !optionsExpanded })
        if (optionsExpanded) {
            RowDivider()
            PrefRow("使用已保存的代理 / Cookie", "按平台轮换 Cookie 池，显式配置优先") {
                Switch(state.useSavedConfig, onCheckedChange = { state.useSavedConfig = it })
            }
            RowDivider()
            PrefRow("同名文件夹合并归档", "多次下载进入同一作者目录，已有文件不覆盖") {
                Switch(state.settings?.mergeAuthorFolders ?: true, onCheckedChange = { state.applyBool("merge_author_folders", it) })
            }
            RowDivider()
            PrefRow("打开 KuKuTool 解析网页", "独立窗口运行（系统 Edge/Chrome），不传送本地 Cookie", onClick = {
                val url = state.settings?.bookmarks?.firstOrNull()?.url
                if (url.isNullOrBlank()) state.showToast("还没有收藏解析网站") else state.openWebApp(url)
            }) {
                Icon(MiuixIcons.Regular.ChevronForward, null, Modifier.size(20.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantActions)
            }
        }
    }

    // ---- 批次面板（按平台分组，与 Tauri 版一致）
    state.batch?.let { batch ->
        val finished = batch.items.count { it.status != "pending" && it.status != "running" }
        val failed = batch.items.count { it.status == "error" }
        SectionTitle("本次解析 · $finished / ${batch.items.size}" + if (failed > 0) " · $failed 条失败" else "")
        SectionCard {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    statusLabel(batch.status) + if (batch.status == "running") "…" else "",
                    fontSize = MiuixTheme.textStyles.body1.fontSize, fontFamily = AppFont.family,
                    fontWeight = FontWeight.Medium,
                    color = statusColor(batch.status),
                )
                Spacer(Modifier.weight(1f))
                if (batch.status == "running") {
                    TextButton("取消未完成", onClick = { state.cancelBatch() })
                } else if (failed > 0) {
                    TextButton("重试失败项", onClick = { state.retryFailed() })
                }
                Spacer(Modifier.width(6.dp))
                TextButton("收起", onClick = { state.dismissBatch() })
            }
            batch.items.forEach { item ->
                RowDivider()
                BatchItemRow(state, item)
            }
        }
    }

    state.error?.let {
        SectionTitle("解析失败")
        SectionCard {
            Text(
                it,
                Modifier.padding(16.dp),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.error,
            )
        }
    }

    state.result?.let { result ->
        val cover = result.media.firstOrNull()?.let { state.coverSrc(it) }
        SectionTitle("解析结果")
        SectionCard {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    RemoteThumb(
                        model = state.mediaUrl(cover, referer = result.rawUrl),
                        modifier = Modifier
                            .size(76.dp)
                            .clickable(enabled = result.media.isNotEmpty()) { state.openLightbox(result.media, 0, result.rawUrl, result.title) },
                        radius = 14.dp,
                        contentDescription = result.title,
                        fallbackIcon = MiuixIcons.Regular.Search,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            result.title.ifBlank { "无标题作品" },
                            fontSize = MiuixTheme.textStyles.title4.fontSize, fontFamily = AppFont.family,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onBackground,
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            result.platform?.let { Pill(it.name) }
                            Pill(result.typeLabel)
                            Pill("${result.mediaCount} 个媒体")
                        }
                        if (result.author.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(result.author, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                }
                if (result.content.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        result.content,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 画质选择：抖音会给出多档码流（原画 4K / 1440P / 1080P / 720P…），按体积标注
                val variants = result.media.firstOrNull()?.variants.orEmpty()
                if (variants.size > 1) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "画质",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        variants.forEachIndexed { index, variant ->
                            QualityChip(
                                label = variant.label,
                                selected = index == state.selectedVariant,
                                onClick = { state.selectedVariant = index },
                            )
                        }
                    }
                    if (result.platform?.id == "douyin" && variants.first().height in 1..1079) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "只拿到 ${variants.first().height}P：在「账户与代理」里配置抖音 Cookie 后重新解析，可拿 4K 原画",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { state.downloadCurrent() }, colors = ButtonDefaults.buttonColorsPrimary()) {
                        Text("下载本作品", style = MiuixTheme.textStyles.button, color = MiuixTheme.colorScheme.onPrimary)
                    }
                    if (result.media.isNotEmpty()) {
                        TextButton("查看大图", onClick = { state.openLightbox(result.media, 0, result.rawUrl, result.title) })
                    }
                    if (isLikelyPostUrl(result.rawUrl)) {
                        TextButton("打开原帖", onClick = { state.openUrl(result.rawUrl) })
                    }
                }
            }
        }

        if (result.media.isNotEmpty()) {
            SectionTitle("媒体 · ${result.media.size}")
            SectionCard {
                Column(Modifier.padding(vertical = 12.dp)) {
                    MediaGrid(
                        items = result.media,
                        srcOf = { state.mediaUrl(state.gridSrc(it), referer = result.rawUrl) },
                        onClick = { state.openLightbox(result.media, it, result.rawUrl, result.title) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BatchItemRow(state: AppState, item: BatchItem) {
    val selected = state.selectedBatchItem == item.id
    val title = item.result?.title?.takeIf { it.isNotBlank() } ?: item.url
    val summary = item.error
        ?: item.result?.let { "${it.platform?.name ?: "未知平台"} · ${it.mediaCount} 个媒体" }
        ?: statusLabel(item.status)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = item.result != null) { state.selectBatchItem(item.id) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteThumb(
            model = state.mediaUrl(item.result?.media?.firstOrNull()?.let { state.coverSrc(it) }),
            modifier = Modifier.size(44.dp),
            radius = 10.dp,
            fallbackIcon = if (item.status == "running") MiuixIcons.Regular.Refresh else MiuixIcons.Regular.Link,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = MiuixTheme.textStyles.body1.fontSize, fontFamily = AppFont.family,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                summary,
                style = MiuixTheme.textStyles.footnote1,
                color = if (item.error != null) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Icon(MiuixIcons.Regular.Ok, "已选中", Modifier.size(18.dp), tint = MiuixTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            statusLabel(item.status),
            style = MiuixTheme.textStyles.footnote2,
            fontWeight = FontWeight.Bold,
            color = statusColor(item.status),
        )
        if (item.status == "success") {
            Spacer(Modifier.width(8.dp))
            TextButton("下载", onClick = { state.downloadUrl(item.url) })
        }
    }
}

/** 画质芯片（抖音多档位选择用）。 */
@Composable
private fun QualityChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .squircleSurface(
                if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.secondaryVariant,
                10.dp,
            )
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            style = MiuixTheme.textStyles.footnote2,
            color = if (selected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurfaceVariantActions,
            maxLines = 1,
        )
    }
}
