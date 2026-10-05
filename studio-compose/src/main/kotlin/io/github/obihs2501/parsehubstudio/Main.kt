package io.github.obihs2501.parsehubstudio

import androidx.compose.runtime.*
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.obihs2501.parsehubstudio.sidecar.ApiClient
import io.github.obihs2501.parsehubstudio.sidecar.SidecarProcess
import io.github.obihs2501.parsehubstudio.sidecar.WindowsMica
import io.github.obihs2501.parsehubstudio.ui.AppFont
import io.github.obihs2501.parsehubstudio.ui.AppRoot
import io.github.obihs2501.parsehubstudio.ui.LocalImageLoader
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.awt.Dimension

fun main() = application {
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    var sidecar by remember { mutableStateOf<SidecarProcess?>(null) }
    var api by remember { mutableStateOf<ApiClient?>(null) }
    var startupError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val launch = SidecarProcess.locate()
        if (launch == null) {
            startupError = "未找到 parsehub-sidecar 可执行文件，也没有可用的开发环境。\n" +
                "打包运行请先构建：studio/dist/parsehub-sidecar.exe\n" +
                "开发模式请在 studio/ 执行 uv sync --locked 后重试，或用 PARSEHUB_SIDECAR_PYTHON 指定解释器。"
            return@LaunchedEffect
        }
        val proc = SidecarProcess(launch)
        proc.start()
            .onSuccess {
                sidecar = proc
                api = ApiClient(proc)
            }
            .onFailure { startupError = it.message ?: "Python 服务启动失败" }
    }

    val state = remember(api) { api?.let { AppState(it, scope) } }
    LaunchedEffect(state) { state?.bootstrap() }

    val windowState = rememberWindowState(size = DpSize(1180.dp, 800.dp))
    val dark = (state?.settings?.theme ?: "dark") != "light"

    Window(
        onCloseRequest = {
            sidecar?.stop()
            exitApplication()
        },
        state = windowState,
        title = "ParseHub Studio",
    ) {
        window.minimumSize = Dimension(960, 640)

        // Windows 11 Mica 材质（跟随主题）
        LaunchedEffect(dark) {
            if (WindowsMica.isWindows) {
                runCatching { WindowsMica.apply(window.windowHandle, isDark = dark) }
            }
        }

        when {
            startupError != null -> StartupErrorScreen(startupError!!)
            state == null -> LoadingScreen("正在启动本地解析服务…")
            else -> {
                // 界面字体：整套 miuix textStyles 换成 PingFang（本机有）或内置 MiSans
                val baseStyles = MiuixTheme.textStyles
                val styles = remember { AppFont.textStyles(baseStyles) }
                MiuixTheme(
                    colors = if (dark) darkColorScheme() else lightColorScheme(),
                    textStyles = styles,
                ) {
                    CompositionLocalProvider(LocalImageLoader provides state.imageLoader) {
                        AppRoot(state)
                    }
                }
            }
        }
    }
}
