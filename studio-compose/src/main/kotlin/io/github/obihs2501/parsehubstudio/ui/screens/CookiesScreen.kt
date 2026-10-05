package io.github.obihs2501.parsehubstudio.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.obihs2501.parsehubstudio.AppState
import io.github.obihs2501.parsehubstudio.model.Platform
import io.github.obihs2501.parsehubstudio.ui.*
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 账户与代理：按平台折叠（默认收起），折叠时只显示平台名 + 配置摘要，
 * 避免为了找一个平台一直往下滑。
 */
@Composable
fun CookiesScreen(state: AppState) {
    PageHero("账户与代理", "按平台轮换 Cookie、配置代理")

    if (state.platforms.isEmpty()) {
        SectionCard { EmptyState("正在加载平台…", Modifier.height(200.dp)) }
        return
    }

    val keys = state.platforms.map { "cookies/${it.id}" }
    val anyExpanded = keys.any { state.isExpanded(it) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "共 ${state.platforms.size} 个平台 · 点平台名展开",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.weight(1f))
        TextButton(if (anyExpanded) "全部收起" else "全部展开", onClick = {
            keys.forEach { state.setExpanded(it, !anyExpanded) }
        })
    }

    state.platforms.forEach { platform ->
        val key = "cookies/${platform.id}"
        val expanded = state.isExpanded(key)
        SectionCard {
            GroupHeader(
                title = platform.label,
                count = platform.cookiePool.size,
                summary = platformSummary(platform),
                expanded = expanded,
                onToggle = { state.toggleExpanded(key) },
            )

            if (expanded) {
                RowDivider()
                PrefRow(
                    title = "主 Cookie",
                    summary = if (platform.cookieSet) {
                        "已保存 ${platform.cookieHint ?: ""}（${platform.cookieLength} 字符）"
                    } else {
                        "未设置；Cookie 池为空时用它兜底"
                    },
                    onClick = { state.openDialog(Dialog.SetMainCookie(platform.id, platform.label, platform.cookieHint)) },
                    trailing = {
                        if (platform.cookieSet) {
                            TextButton("清除", onClick = { state.clearMainCookie(platform.id) })
                        } else {
                            Icon(MiuixIcons.Regular.ChevronForward, null, Modifier.size(20.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantActions)
                        }
                    },
                )
                RowDivider()
                PrefRow(
                    title = "解析代理",
                    summary = platform.parseProxy?.takeIf { it.isNotBlank() } ?: "跟随全局代理",
                    onClick = { state.openDialog(Dialog.PlatformProxy(platform.id, platform.label, "parse", platform.parseProxy)) },
                    trailing = {
                        if (!platform.parseProxy.isNullOrBlank()) {
                            TextButton("清除", onClick = { state.clearPlatformProxy(platform.id, "parse") })
                        } else {
                            Icon(MiuixIcons.Regular.ChevronForward, null, Modifier.size(20.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantActions)
                        }
                    },
                )
                RowDivider()
                PrefRow(
                    title = "下载代理",
                    summary = platform.downloadProxy?.takeIf { it.isNotBlank() } ?: "跟随全局代理",
                    onClick = { state.openDialog(Dialog.PlatformProxy(platform.id, platform.label, "download", platform.downloadProxy)) },
                    trailing = {
                        if (!platform.downloadProxy.isNullOrBlank()) {
                            TextButton("清除", onClick = { state.clearPlatformProxy(platform.id, "download") })
                        } else {
                            Icon(MiuixIcons.Regular.ChevronForward, null, Modifier.size(20.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantActions)
                        }
                    },
                )
                RowDivider()
                PrefRow("添加账户", "Cookie 池：解析时按平台轮换使用", onClick = {
                    state.openDialog(Dialog.AddCookie(platform.id, platform.label))
                }) {
                    Icon(MiuixIcons.Regular.Add, null, Modifier.size(20.dp), tint = MiuixTheme.colorScheme.primary)
                }
                platform.cookiePool.forEachIndexed { index, entry ->
                    RowDivider()
                    PrefRow(
                        title = entry.name.ifBlank { "账户 ${index + 1}" },
                        summary = buildString {
                            append(if (entry.enabled) "已启用 · 每次解析轮换" else "已停用")
                            entry.hint?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                        },
                        trailing = {
                            Switch(entry.enabled, onCheckedChange = { enabled ->
                                state.toggleCookie(platform.id, entry.id, enabled)
                            })
                        },
                    )
                }
            }
        }
    }
}

/** 折叠状态下的一行摘要。 */
private fun platformSummary(platform: Platform): String = buildList {
    add(if (platform.cookieSet) "主 Cookie 已设置" else "主 Cookie 未设置")
    if (platform.cookiePool.isNotEmpty()) {
        add("${platform.cookiePool.count { it.enabled }} 个账户启用")
    }
    add(
        when {
            platform.parseProxy.isNullOrBlank() && platform.downloadProxy.isNullOrBlank() -> "代理跟随全局"
            platform.parseProxy.isNullOrBlank() -> "下载代理已设置"
            platform.downloadProxy.isNullOrBlank() -> "解析代理已设置"
            else -> "解析 / 下载代理已设置"
        },
    )
}.joinToString(" · ")

@Composable
fun BookmarksScreen(state: AppState) {
    PageHero("解析网站收藏", "手动使用的第三方工具")
    val bookmarks = state.settings?.bookmarks.orEmpty()

    SectionTitle("收藏 · ${bookmarks.size}")
    SectionCard {
        if (bookmarks.isEmpty()) {
            PrefRow("暂无收藏", "添加常用的第三方解析工具，一键打开")
        } else {
            bookmarks.forEachIndexed { index, bookmark ->
                if (index > 0) RowDivider()
                PrefRow(
                    title = bookmark.name,
                    summary = "${bookmark.group} · ${bookmark.url}",
                    onClick = { state.openWebApp(bookmark.url) },
                    trailing = {
                        Row {
                            IconButton(
                                onClick = { state.openDialog(Dialog.BookmarkEdit(index, bookmark.name, bookmark.url, bookmark.group)) },
                                minHeight = 35.dp,
                                minWidth = 35.dp,
                                backgroundColor = MiuixTheme.colorScheme.secondaryVariant,
                            ) {
                                Icon(MiuixIcons.Regular.Edit, "编辑", Modifier.size(16.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                            Spacer(Modifier.width(8.dp))
                            IconButton(
                                onClick = { state.deleteBookmark(index) },
                                minHeight = 35.dp,
                                minWidth = 35.dp,
                                backgroundColor = MiuixTheme.colorScheme.secondaryVariant,
                            ) {
                                Icon(MiuixIcons.Regular.Delete, "删除", Modifier.size(16.dp), tint = MiuixTheme.colorScheme.error)
                            }
                        }
                    },
                )
            }
        }
        RowDivider()
        PrefRow("添加解析网站", "名称 + 网址 + 分组", onClick = { state.openDialog(Dialog.BookmarkEdit(null)) }) {
            Icon(MiuixIcons.Regular.Add, null, Modifier.size(20.dp), tint = MiuixTheme.colorScheme.primary)
        }
    }

    Text(
        "收藏条目会在「解析工作台 · 打开 KuKuTool 解析网页」中使用第一个条目，",
        modifier = Modifier.padding(horizontal = 28.dp),
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        "以独立应用窗口打开（系统 Edge/Chrome，独立配置目录，不共享 Cookie）。",
        modifier = Modifier.padding(horizontal = 28.dp),
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}
