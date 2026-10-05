package io.github.obihs2501.parsehubstudio.ui.screens

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.obihs2501.parsehubstudio.AppState
import io.github.obihs2501.parsehubstudio.ui.*
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SettingsScreen(state: AppState) {
    PageHero("设置", "偏好与本地环境")
    val s = state.settings
    if (s == null) {
        SectionCard { EmptyState("正在加载设置…", Modifier.height(200.dp)) }
        return
    }

    SectionTitle("下载")
    SectionCard {
        ValueRow("下载目录", s.defaultSaveDir.ifBlank { "未设置" }) {
            state.openDialog(Dialog.PickDir("default_save_dir", "选择下载目录", s.defaultSaveDir))
        }
        RowDivider()
        ValueRow("多连接分片下载", "当前 ${s.connections} 路（1–32）") {
            state.openDialog(Dialog.NumberEdit("connections", "分片连接数", "设为 1 可禁用分片下载", s.connections, 1..32))
        }
        RowDivider()
        PrefRow("保存元数据", "写入标题、作者、作品 ID") {
            Switch(s.saveMetadata, onCheckedChange = { state.applyBool("save_metadata", it) })
        }
        RowDivider()
        PrefRow("下载规格", if (s.downloadQuality == "origin") "原图 / 原视频" else "预览图（缩略图）") {
            Switch(s.downloadQuality == "origin", onCheckedChange = { state.applyText("download_quality", if (it) "origin" else "thumb") })
        }
        RowDivider()
        PrefRow("下载完成后打开目录", "任务成功结束时自动打开保存位置") {
            Switch(s.openAfterDownload, onCheckedChange = { state.applyBool("open_after_download", it) })
        }
    }

    SectionTitle("外观与预览")
    SectionCard {
        PrefRow("深色主题", "关闭则使用浅色") {
            Switch(s.theme == "dark", onCheckedChange = { state.applyText("theme", if (it) "dark" else "light") })
        }
        RowDivider()
        PrefRow("高清预览", "网格使用高清预览；取消则直接加载原图") {
            Switch(s.previewMode, onCheckedChange = { state.applyBool("preview_mode", it) })
        }
    }

    SectionTitle("网络")
    SectionCard {
        ValueRow("全局代理模式", when (s.proxyMode) {
            "system" -> "跟随系统"
            "custom" -> "自定义"
            else -> "直连"
        }) {
            state.openDialog(
                Dialog.PickOption(
                    "proxy_mode", "全局代理模式",
                    listOf("system" to "跟随系统代理", "custom" to "自定义代理", "none" to "直连，不使用代理"),
                    s.proxyMode,
                ),
            )
        }
        RowDivider()
        ValueRow("自定义代理地址", s.proxyUrl.ifBlank { "未填写" }) {
            state.openDialog(Dialog.TextEdit("proxy_url", "自定义代理地址", "解析、下载与媒体预览都会优先使用它", s.proxyUrl, "http://127.0.0.1:7890"))
        }
        RowDivider()
        ValueRow("本机 FFmpeg 目录", s.ffmpegDir.ifBlank { "留空则从系统 PATH 查找" }) {
            state.openDialog(Dialog.PickDir("ffmpeg_dir", "选择 FFmpeg bin 目录", s.ffmpegDir))
        }
    }

    SectionTitle("文件命名")
    SectionCard {
        ValueRow("文件夹模板", s.folderTemplate) {
            state.openDialog(Dialog.TextEdit("folder_template", "文件夹模板", "决定作品保存到哪个子目录", s.folderTemplate, templatePreview = true))
        }
        RowDivider()
        ValueRow("文件名模板", s.fileTemplate) {
            state.openDialog(Dialog.TextEdit("file_template", "文件名模板", "决定每个媒体文件的命名", s.fileTemplate, templatePreview = true))
        }
        RowDivider()
        PrefRow("合并同名作者目录", "多次下载进入同一作者目录") {
            Switch(s.mergeAuthorFolders, onCheckedChange = { state.applyBool("merge_author_folders", it) })
        }
        s.authorAliases.forEachIndexed { index, alias ->
            RowDivider()
            PrefRow(
                title = "${alias.account} → ${alias.creator}",
                summary = "${alias.platform} · 按${if (alias.matchBy == "id") "作者 ID" else "昵称"}匹配",
                trailing = { TextButton("删除", onClick = { state.deleteAlias(index) }) },
            )
        }
        RowDivider()
        PrefRow("添加作者别名", "把不同账号下的同一作者合并到同一个目录", onClick = { state.openDialog(Dialog.AliasEdit()) }) {
            Icon(MiuixIcons.Regular.Add, null, Modifier.height(20.dp), tint = MiuixTheme.colorScheme.primary)
        }
    }

    SectionTitle("数据")
    SectionCard {
        PrefRow(
            "导出解析记录 CSV",
            "写到 <下载目录>/parsehub-history.csv，Excel 可直接打开；每次解析成功也会自动追加",
            onClick = { state.exportHistoryCsv() },
        ) {
            Icon(MiuixIcons.Regular.ChevronForward, null, Modifier.height(20.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantActions)
        }
        RowDivider()
        PrefRow("解析记录", "当前 ${state.history.size} 条（保存在本地）")
    }

    SectionTitle("关于")
    SectionCard {
        PrefRow("ParseHub Studio", "版本 ${state.version.ifBlank { "未知" }}")
        RowDivider()
        PrefRow("本地解析", "文件留在你的电脑，不上传")
    }
}

/** 可点击的设置行（右侧显示当前值 + 箭头）。 */
@Composable
private fun ValueRow(title: String, value: String, onClick: () -> Unit) {
    PrefRow(title, value, onClick = onClick) {
        Icon(MiuixIcons.Regular.ChevronForward, null, Modifier.height(20.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantActions)
    }
}
