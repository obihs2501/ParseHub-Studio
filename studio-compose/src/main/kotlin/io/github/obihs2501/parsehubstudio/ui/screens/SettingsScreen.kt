package io.github.obihs2501.parsehubstudio.ui.screens

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.obihs2501.parsehubstudio.AppState
import io.github.obihs2501.parsehubstudio.ui.*
import kotlinx.serialization.json.JsonPrimitive
import top.yukonga.miuix.kmp.basic.Switch
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
        PrefRow("下载目录", s.defaultSaveDir)
        RowDivider()
        PrefRow("多连接分片下载", "当前 ${s.connections} 路（1–32）")
        RowDivider()
        PrefRow("保存元数据", "写入标题、作者、作品 ID") {
            Switch(s.saveMetadata, onCheckedChange = { v -> state.saveSettings(mapOf("save_metadata" to JsonPrimitive(v))) })
        }
        RowDivider()
        PrefRow("下载规格", if (s.downloadQuality == "origin") "原图 / 原视频" else "预览图（缩略图）") {
            Switch(s.downloadQuality == "origin", onCheckedChange = { v ->
                state.saveSettings(mapOf("download_quality" to JsonPrimitive(if (v) "origin" else "thumb")))
            }
        }
    }

    SectionTitle("外观与预览")
    SectionCard {
        PrefRow("深色主题", "关闭则使用浅色") {
            Switch(s.theme == "dark", onCheckedChange = { v ->
                state.saveSettings(mapOf("theme" to JsonPrimitive(if (v) "dark" else "light")))
            }
        }
        RowDivider()
        PrefRow("高清预览", "网格使用高清预览；取消则直接加载原图") {
            Switch(s.previewMode, onCheckedChange = { v -> state.saveSettings(mapOf("preview_mode" to JsonPrimitive(v))) })
        }
    }

    SectionTitle("网络")
    SectionCard {
        PrefRow("代理模式", when (s.proxyMode) {
            "system" -> "跟随系统"
            "custom" -> "自定义：${s.proxyUrl}"
            else -> "直连"
        })
    }

    SectionTitle("文件命名")
    SectionCard {
        PrefRow("文件夹模板", s.folderTemplate)
        RowDivider()
        PrefRow("文件名模板", s.fileTemplate)
        RowDivider()
        PrefRow("合并同名作者目录", "多次下载进入同一作者目录") {
            Switch(s.mergeAuthorFolders, onCheckedChange = { v ->
                state.saveSettings(mapOf("merge_author_folders" to JsonPrimitive(v)))
            }
        }
    }

    SectionTitle("关于")
    SectionCard {
        PrefRow("ParseHub Studio", "版本 ${state.version}")
        RowDivider()
        PrefRow("本地解析", "文件留在你的电脑，不上传")
    }
}
