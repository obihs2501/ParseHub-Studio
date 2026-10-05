package io.github.obihs2501.parsehubstudio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme

/** 启动加载页。 */
@Composable
fun LoadingScreen(message: String) {
    MiuixTheme(colors = darkColorScheme()) {
        Box(
            Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("ParseHub", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onBackground)
                Spacer(Modifier.height(8.dp))
                Text("Studio", fontSize = 16.sp, color = MiuixTheme.colorScheme.onBackgroundVariant)
                Spacer(Modifier.height(28.dp))
                Text(message, fontSize = 14.sp, color = MiuixTheme.colorScheme.onBackgroundVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(
                    "首次启动需要解压 Python 环境，请稍候",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            }
        }
    }
}

/** 启动失败页。 */
@Composable
fun StartupErrorScreen(message: String) {
    MiuixTheme(colors = darkColorScheme()) {
        Box(
            Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.widthIn(max = 520.dp).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("无法启动本地解析服务", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.error)
                Spacer(Modifier.height(16.dp))
                Text(message, fontSize = 14.sp, color = MiuixTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                Text(
                    "请关闭应用后重试；如仍无法启动，请检查 Python 服务是否可用。",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
