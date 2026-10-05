package io.github.obihs2501.parsehubstudio.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.obihs2501.parsehubstudio.AppState
import io.github.obihs2501.parsehubstudio.ui.*
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun HistoryScreen(state: AppState) {
    PageHero("历史记录", "按平台与作者归档")
    if (state.history.isEmpty()) {
        SectionCard { EmptyState("暂时没有历史记录", Modifier.height(240.dp)) }
        return
    }
    val grouped = state.history.groupBy { it.platform?.name ?: "未知平台" }
    grouped.forEach { (platform, entries) ->
        SectionTitle("$platform · ${entries.size} 条")
        SectionCard {
            entries.forEachIndexed { index, entry ->
                if (index > 0) RowDivider()
                PrefRow(
                    title = entry.title.ifBlank { "无标题作品" },
                    summary = "${entry.author.ifBlank { "未知作者" }} · ${entry.mediaCount} 个媒体",
                    trailing = {
                        TextButton("删除", onClick = { state.deleteHistory(entry.id) })
                    },
                )
            }
        }
    }
}
