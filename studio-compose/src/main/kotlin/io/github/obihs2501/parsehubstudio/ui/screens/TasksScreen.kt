package io.github.obihs2501.parsehubstudio.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.obihs2501.parsehubstudio.AppState
import io.github.obihs2501.parsehubstudio.ui.*
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun TasksScreen(state: AppState) {
    PageHero("下载管理", "本次运行的任务与进度")
    if (state.tasks.isEmpty()) {
        SectionCard { EmptyState("还没有下载任务", Modifier.height(240.dp)) }
        return
    }
    SectionTitle("下载队列 · ${state.tasks.size} 个任务")
    SectionCard {
        state.tasks.forEachIndexed { index, task ->
            if (index > 0) RowDivider()
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        task.title ?: task.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.onSurfaceContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        statusLabel(task.status),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor(task.status),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "${task.platform ?: "待识别平台"} · ${task.stage}",
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
                Spacer(Modifier.height(10.dp))
                ProgressBar(task.progress.toFloat(), done = task.status == "success")
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (task.outputDir != null && task.status in setOf("success", "partial")) {
                        TextButton("打开目录", onClick = { state.reveal(task.outputDir!!) })
                    }
                    if (task.status in setOf("running", "pending")) {
                        TextButton("取消", onClick = { state.cancelTask(task.id) })
                    }
                }
            }
        }
    }
}

private fun statusLabel(status: String) = when (status) {
    "pending" -> "排队中"; "running" -> "进行中"; "success" -> "已完成"
    "partial" -> "部分完成"; "error" -> "失败"; "cancelled" -> "已取消"; else -> status
}

@Composable
private fun statusColor(status: String) = when (status) {
    "running" -> MiuixTheme.colorScheme.primary
    "success" -> androidx.compose.ui.graphics.Color(0xFF34C759)
    "partial" -> androidx.compose.ui.graphics.Color(0xFFC99A3F)
    "error" -> MiuixTheme.colorScheme.error
    else -> MiuixTheme.colorScheme.onBackgroundVariant
}
