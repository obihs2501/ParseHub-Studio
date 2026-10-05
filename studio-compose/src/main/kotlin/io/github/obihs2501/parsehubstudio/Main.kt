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
import io.github.obihs2501.parsehubstudio.ui.AppRoot
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
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
        val exe = SidecarProcess.locate()
        if (exe == null) {
            startupError = "未找到 parsehub-sidecar 可执行文件。\n开发模式请先构建：studio/dist/parsehub-sidecar.exe"
            return@LaunchedEffect
        }
        val proc = SidecarProcess(exe)
        proc.start()
            .onSuccess {
                sidecar = proc
                api = ApiClient(proc)
            }
            .onFailure { startupError = it.message ?: "Python 服务启动失败" }
    }

    val state = remember(api) { api?.let { AppState(it, scope) } }
    LaunchedEffect(state) { state?.bootstrap() }

    val windowState = rememberWindowState(size = DpSize(1120.dp, 760.dp))

    Window(
        onCloseRequest = {
            sidecar?.stop()
            exitApplication()
        },
        state = windowState,
        title = "ParseHub Studio",
    ) {
        window.minimumSize = Dimension(900, 600)

        // Windows 11 Mica 材质
        var micaApplied by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            if (WindowsMica.isWindows) {
                micaApplied = runCatching {
                    WindowsMica.apply(window.windowHandle, isDark = true)
                }.getOrDefault(false)
            }
        }

        when {
            startupError != null -> StartupErrorScreen(startupError!!)
            state == null -> LoadingScreen("正在启动本地解析服务…")
            else -> MiuixTheme(colors = darkColorScheme()) { AppRoot(state) }
        }
    }
}
