package io.github.obihs2501.parsehubstudio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.obihs2501.parsehubstudio.AppState
import io.github.obihs2501.parsehubstudio.ui.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Search
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
                Text("粘贴分享文案或多个链接，每行一条", fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurfaceVariantActions)
            }
            BasicTextField(
                value = state.input,
                onValueChange = { state.input = it },
                textStyle = TextStyle(fontSize = 16.sp, color = MiuixTheme.colorScheme.onBackground),
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("最多 50 条 · 自动去重 · 3 路并发", fontSize = 13.sp, color = MiuixTheme.colorScheme.onBackgroundVariant)
            Spacer(Modifier.weight(1f))
            TextButton("清空", onClick = { state.input = "" })
            Spacer(Modifier.width(8.dp))
            Button(onClick = { state.downloadAll() }) { Text("批量下载", fontSize = 16.sp) }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { state.parse() }, colors = ButtonDefaults.buttonColorsPrimary()) {
                Text(if (state.parsing) "解析中…" else "开始解析", fontSize = 16.sp, color = MiuixTheme.colorScheme.onPrimary)
            }
        }
    }

    SectionTitle("解析选项")
    SectionCard {
        PrefRow("使用已保存的代理 / Cookie", "按平台轮换 Cookie 池，显式配置优先") {
            Switch(state.useSavedConfig, onCheckedChange = { state.useSavedConfig = it })
        }
        RowDivider()
        PrefRow("同名文件夹合并归档", "多次下载进入同一作者目录，已有文件不覆盖") {
            Switch(state.settings?.mergeAuthorFolders ?: true, onCheckedChange = { v ->
                state.saveSettings(mapOf("merge_author_folders" to kotlinx.serialization.json.JsonPrimitive(v)))
            })
        }
        RowDivider()
        PrefRow("打开 KuKuTool 解析网页", "独立窗口运行，不传送本地 Cookie", onClick = {
            state.settings?.bookmarks?.firstOrNull()?.let { state.openUrl(it.url) }
        }) {
            Icon(MiuixIcons.Regular.ChevronForward, null, Modifier.size(20.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantActions)
        }
    }

    state.error?.let {
        SectionTitle("解析失败")
        SectionCard {
            Text(it, Modifier.padding(16.dp), fontSize = 14.sp, color = MiuixTheme.colorScheme.error)
        }
    }

    state.result?.let { result ->
        SectionTitle("解析结果")
        SectionCard {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier
                            .size(76.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MiuixTheme.colorScheme.secondaryVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(MiuixIcons.Regular.Search, null, Modifier.size(26.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantActions)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(result.title.ifBlank { "无标题作品" }, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.onSurfaceContainer)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            result.platform?.let { Pill(it.name, accent = false) }
                            Pill(result.typeLabel)
                            Pill("${result.mediaCount} 个媒体")
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { state.downloadAll() }, colors = ButtonDefaults.buttonColorsPrimary()) {
                        Text("下载本作品", fontSize = 16.sp, color = MiuixTheme.colorScheme.onPrimary)
                    }
                    if (result.rawUrl.isNotBlank()) {
                        TextButton("打开原帖", onClick = { state.openUrl(result.rawUrl) })
                    }
                }
            }
        }
    }
}
