package io.github.obihs2501.parsehubstudio.ui.screens

import io.github.obihs2501.parsehubstudio.ui.AppFont

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.obihs2501.parsehubstudio.AppState
import io.github.obihs2501.parsehubstudio.model.Task
import io.github.obihs2501.parsehubstudio.ui.*
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun TasksScreen(state: AppState) {
    PageHero("下载管理", "本次运行的任务与进度")

    val active = state.tasks.filter { it.status == "running" || it.status == "pending" }
    val finished = state.tasks.filter { it.status != "running" && it.status != "pending" }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when {
                active.isNotEmpty() -> "进行中 ${active.size} · 已结束 ${finished.size}"
                state.tasks.isEmpty() -> "暂无任务"
                else -> "已结束 ${finished.size} 个任务"
            },
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.weight(1f))
        TextButton("刷新", onClick = { state.refreshTasks() })
        if (active.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            TextButton("全部取消", onClick = { state.cancelAllTasks() })
        }
        if (finished.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            TextButton("清除已完成", onClick = { state.openDialog(Dialog.ConfirmClearTasks) })
        }
    }

    if (state.tasks.isEmpty()) {
        SectionCard { EmptyState("还没有下载任务", Modifier.height(240.dp)) }
        return
    }

    if (active.isNotEmpty()) {
        SectionTitle("进行中 · ${active.size}")
        SectionCard {
            active.forEachIndexed { index, task ->
                if (index > 0) RowDivider()
                TaskRow(state, task)
            }
        }
    }

    if (finished.isNotEmpty()) {
        SectionTitle("已结束 · ${finished.size}")
        SectionCard {
            finished.forEachIndexed { index, task ->
                if (index > 0) RowDivider()
                TaskRow(state, task)
            }
        }
    }
}

@Composable
private fun TaskRow(state: AppState, task: Task) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RemoteThumb(
                model = state.mediaUrl(task.thumbnail, referer = task.rawUrl.ifBlank { null }),
                modifier = Modifier.size(52.dp),
                radius = 12.dp,
                fallbackIcon = MiuixIcons.Regular.Folder,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    task.title ?: task.text,
                    fontSize = MiuixTheme.textStyles.headline1.fontSize, fontFamily = AppFont.family,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    listOfNotNull(
                        task.platform ?: "待识别平台",
                        task.stage.ifBlank { null },
                        if (task.total > 0) "${task.current}/${task.total}${task.unit}" else null,
                        task.finishedAt?.let { formatTimestamp(it) },
                    ).joinToString(" · "),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                statusLabel(task.status),
                style = MiuixTheme.textStyles.footnote2,
                fontWeight = FontWeight.Bold,
                color = statusColor(task.status),
            )
        }

        if (task.status == "running" || task.status == "pending") {
            Spacer(Modifier.height(10.dp))
            ProgressBar(task.progress.toFloat())
        } else {
            Spacer(Modifier.height(10.dp))
            ProgressBar(task.progress.toFloat(), done = task.status == "success")
        }

        task.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }

        if (task.files.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "${task.files.size} 个文件已保存",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (task.outputDir != null && task.status in setOf("success", "partial")) {
                TextButton("打开目录", onClick = { state.reveal(task.outputDir) })
            }
            if (task.status in setOf("running", "pending")) {
                TextButton("取消", onClick = { state.cancelTask(task.id) })
            } else {
                TextButton("重新下载", onClick = { state.downloadUrl(task.rawUrl.ifBlank { task.text }) })
            }
            if (isLikelyPostUrl(task.rawUrl)) {
                TextButton("打开原帖", onClick = { state.openUrl(task.rawUrl) })
            }
        }
    }
}
