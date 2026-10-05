package io.github.obihs2501.parsehubstudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.obihs2501.parsehubstudio.AppState
import io.github.obihs2501.parsehubstudio.Screen
import io.github.obihs2501.parsehubstudio.ui.screens.*
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 应用外壳：侧栏 + 内容区。 */
@Composable
fun AppRoot(state: AppState) {
    Row(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
        Sidebar(state)
        Box(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                when (state.screen) {
                    Screen.PARSE -> ParseScreen(state)
                    Screen.TASKS -> TasksScreen(state)
                    Screen.HISTORY -> HistoryScreen(state)
                    Screen.COOKIES -> CookiesScreen(state)
                    Screen.BOOKMARKS -> BookmarksScreen(state)
                    Screen.SETTINGS -> SettingsScreen(state)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
        state.toast?.let { Toast(it) }
    }
}

@Composable
private fun Sidebar(state: AppState) {
    Column(
        Modifier
            .width(230.dp)
            .fillMaxHeight()
            .background(MiuixTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 20.dp),
    ) {
        Row(Modifier.padding(start = 14.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("ParseHub", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onBackground)
            Spacer(Modifier.width(6.dp))
            Text("Studio", fontSize = 14.sp, color = MiuixTheme.colorScheme.onBackgroundVariant)
        }

        NavItem(state, Screen.PARSE, MiuixIcons.Regular.Search)
        NavItem(state, Screen.TASKS, MiuixIcons.Regular.Download, badge = state.tasks.count { it.status == "running" || it.status == "pending" })
        NavItem(state, Screen.HISTORY, MiuixIcons.Regular.Recent)

        Spacer(Modifier.height(8.dp))

        NavItem(state, Screen.COOKIES, MiuixIcons.Regular.BankCards)
        NavItem(state, Screen.BOOKMARKS, MiuixIcons.Regular.Favorites)

        Spacer(Modifier.weight(1f))

        Box(Modifier.fillMaxWidth().height(1.dp).background(MiuixTheme.colorScheme.dividerLine))
        Spacer(Modifier.height(8.dp))
        NavItem(state, Screen.SETTINGS, MiuixIcons.Regular.Settings)
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
        Icon(icon, contentDescription = target.title, modifier = Modifier.size(22.dp), tint = if (selected) fg else MiuixTheme.colorScheme.onBackgroundVariant)
        Spacer(Modifier.width(14.dp))
        Text(target.title, fontSize = 16.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = fg, modifier = Modifier.weight(1f))
        if (badge > 0) {
            Box(Modifier.clip(RoundedCornerShape(3.dp)).background(MiuixTheme.colorScheme.error).padding(horizontal = 6.dp, vertical = 1.dp)) {
                Text(badge.toString(), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color.White)
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
            Text(message, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceContainerHighest)
        }
    }
}
