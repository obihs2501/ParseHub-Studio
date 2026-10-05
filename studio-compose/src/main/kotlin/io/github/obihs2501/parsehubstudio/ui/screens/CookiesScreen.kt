package io.github.obihs2501.parsehubstudio.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.obihs2501.parsehubstudio.AppState
import io.github.obihs2501.parsehubstudio.ui.*
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun CookiesScreen(state: AppState) {
    PageHero("Cookie 池", "按平台轮换账户")
    state.platforms.forEach { platform ->
        SectionTitle("${platform.label} · ${platform.cookiePool.size} 个账户")
        SectionCard {
            if (platform.cookiePool.isEmpty()) {
                PrefRow("暂无账户", "在下方添加，或使用 CLI 默认 Cookie")
            } else {
                platform.cookiePool.forEachIndexed { index, entry ->
                    if (index > 0) RowDivider()
                    PrefRow(
                        title = entry.name.ifBlank { "账户 ${index + 1}" },
                        summary = if (entry.enabled) "已启用 · 每次解析轮换" else "已停用",
                        trailing = {
                            Switch(entry.enabled) { enabled ->
                                state.toggleCookie(platform.id, entry.id, enabled)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun BookmarksScreen(state: AppState) {
    PageHero("解析网站收藏", "手动使用的第三方工具")
    SectionTitle("收藏")
    SectionCard {
        val bookmarks = state.settings?.bookmarks.orEmpty()
        if (bookmarks.isEmpty()) {
            PrefRow("暂无收藏", "在设置中添加")
        } else {
            bookmarks.forEachIndexed { index, bookmark ->
                if (index > 0) RowDivider()
                PrefRow(
                    title = bookmark.name,
                    summary = "${bookmark.group} · ${bookmark.url}",
                    onClick = { state.openUrl(bookmark.url) },
                )
            }
        }
    }
}
