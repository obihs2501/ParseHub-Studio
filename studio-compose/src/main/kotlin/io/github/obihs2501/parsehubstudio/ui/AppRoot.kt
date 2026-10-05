package io.github.obihs2501.parsehubstudio.ui

import io.github.obihs2501.parsehubstudio.ui.AppFont

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.obihs2501.parsehubstudio.AppState
import io.github.obihs2501.parsehubstudio.Screen
import io.github.obihs2501.parsehubstudio.ui.screens.*
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 应用外壳：侧栏 + 内容区 + 覆盖层（Toast / 灯箱 / 对话框）。 */
@Composable
fun AppRoot(state: AppState) {
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
            Sidebar(state)
            ResizableDivider(
                onDelta = { state.resizeNavWidth(state.navWidth + it) },
                onFinished = { state.persistUiPrefs() },
            )
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (state.screen == Screen.GALLERY) {
                    // 图库自带滚动区域（左列表 / 右网格），不套外层滚动
                    GalleryScreen(state)
                } else {
                    // key(screen)：每个页面各自持有滚动位置，切页时从顶部开始
                    // （否则会继承上一页的滚动位置，出现「进历史记录先看到半截列表」）
                    key(state.screen) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            when (state.screen) {
                                Screen.PARSE -> ParseScreen(state)
                                Screen.TASKS -> TasksScreen(state)
                                Screen.COOKIES -> CookiesScreen(state)
                                Screen.BOOKMARKS -> BookmarksScreen(state)
                                Screen.SETTINGS -> SettingsScreen(state)
                                Screen.GALLERY -> Unit
                            }
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                }
            }
        }
        state.toast?.let { Toast(it) }
        LightboxOverlay(state)
        DialogHost(state)
    }
}

@Composable
private fun Sidebar(state: AppState) {
    Column(
        Modifier
            .width(state.navWidth.dp)
            .fillMaxHeight()
            .background(MiuixTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 20.dp),
    ) {
        Row(Modifier.padding(start = 14.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("ParseHub", style = MiuixTheme.textStyles.title3, color = MiuixTheme.colorScheme.onBackground)
            Spacer(Modifier.width(6.dp))
            Text("Studio", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }

        NavItem(state, Screen.PARSE, MiuixIcons.Regular.Search)
        NavItem(state, Screen.TASKS, MiuixIcons.Regular.Download, badge = state.tasks.count { it.status == "running" || it.status == "pending" })
        NavItem(state, Screen.GALLERY, MiuixIcons.Regular.GridView)

        Spacer(Modifier.height(8.dp))

        NavItem(state, Screen.COOKIES, MiuixIcons.Regular.BankCards)
        NavItem(state, Screen.BOOKMARKS, MiuixIcons.Regular.Favorites)

        Spacer(Modifier.weight(1f))

        Box(Modifier.fillMaxWidth().height(1.dp).background(MiuixTheme.colorScheme.dividerLine))
        Spacer(Modifier.height(8.dp))
        NavItem(state, Screen.SETTINGS, MiuixIcons.Regular.Settings)

        Spacer(Modifier.height(10.dp))
        Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (state.connected) androidx.compose.ui.graphics.Color(0xFF34C759) else MiuixTheme.colorScheme.error),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (state.connected) "本地服务 v${state.version}" else "服务未连接",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun NavItem(state: AppState, target: Screen, icon: ImageVector, badge: Int = 0) {
    val selected = state.screen == target
    val interaction = remember { MutableInteractionSource() }
    val bg = if (selected) MiuixTheme.colorScheme.tertiaryContainer else androidx.compose.ui.graphics.Color.Transparent
    val fg = if (selected) MiuixTheme.colorScheme.onTertiaryContainer else MiuixTheme.colorScheme.onBackground
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(interaction, null) { state.navigate(target) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = target.title, modifier = Modifier.size(22.dp), tint = if (selected) fg else MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Spacer(Modifier.width(14.dp))
        Text(
            target.title,
            fontSize = MiuixTheme.textStyles.main.fontSize, fontFamily = AppFont.family,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = fg,
            modifier = Modifier.weight(1f),
        )
        if (badge > 0) {
            Box(Modifier.clip(RoundedCornerShape(3.dp)).background(MiuixTheme.colorScheme.error).padding(horizontal = 6.dp, vertical = 1.dp)) {
                Text(
                    badge.toString(),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = androidx.compose.ui.graphics.Color.White,
                )
            }
        }
    }
}

@Composable
private fun Toast(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier
                .padding(bottom = 32.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(MiuixTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text(message, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceContainerHighest)
        }
    }
}
