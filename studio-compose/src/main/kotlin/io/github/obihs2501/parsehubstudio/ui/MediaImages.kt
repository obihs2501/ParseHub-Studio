package io.github.obihs2501.parsehubstudio.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.AsyncImage
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import coil3.util.DebugLogger
import coil3.util.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Coil 图片加载器（携带 sidecar Bearer 鉴权），由 Main 注入。 */
val LocalImageLoader = staticCompositionLocalOf<ImageLoader?> { null }

object MediaImages {
    /**
     * 图片一律走 sidecar 的 /api/media 代理：绕开防盗链 / 跨域，
     * 并且复用同一个 session token（/api/media 同样受 security 中间件保护）。
     */
    fun build(token: String): ImageLoader {
        val client = HttpClient(CIO) {
            defaultRequest { header("Authorization", "Bearer $token") }
            // 原图（尤其微博 /large/）动辄几 MB，走本地代理转发，默认超时太短会直接失败
            install(HttpTimeout) {
                requestTimeoutMillis = 120_000
                socketTimeoutMillis = 120_000
                connectTimeoutMillis = 15_000
            }
        }
        return ImageLoader.Builder(PlatformContext.INSTANCE)
            .components { add(KtorNetworkFetcherFactory(httpClient = { client })) }
            // 默认静默（失败在界面上表现为占位图）；排查图片问题时用 PARSEHUB_IMAGE_DEBUG=1 打开全量日志
            .logger(if (System.getenv("PARSEHUB_IMAGE_DEBUG") != null) DebugLogger(Logger.Level.Debug) else null)
            .crossfade(true)
            .build()
    }
}

/**
 * 远程 / 本地图片缩略图：squircle 圆角 + 占位底色，加载失败回退到图标。
 *
 * [model] 直接交给 Coil：远程是 /api/media 代理地址（String），本地是 `java.io.File`
 * —— 注意不要用 `File.toURI()`（`file:/C:/...` 这种单斜杠形式 Coil 解析不出来，实测会失败）。
 */
@Composable
fun RemoteThumb(
    model: Any?,
    modifier: Modifier = Modifier,
    radius: Dp = 14.dp,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    fallbackIcon: ImageVector = MiuixIcons.Regular.Image,
) {
    val loader = LocalImageLoader.current
    var failed by remember(model) { mutableStateOf(false) }
    Box(
        modifier.squircleBackground(MiuixTheme.colorScheme.secondaryVariant, radius),
        contentAlignment = Alignment.Center,
    ) {
        if (model == null || loader == null || failed) {
            Icon(
                fallbackIcon,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        } else {
            AsyncImage(
                model = model,
                imageLoader = loader,
                contentDescription = contentDescription,
                contentScale = contentScale,
                onSuccess = { failed = false },
                onError = { failed = true },
                modifier = Modifier.fillMaxSize().squircleClip(radius),
            )
        }
    }
}
