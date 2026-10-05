package io.github.obihs2501.parsehubstudio.ui

import io.github.obihs2501.parsehubstudio.ui.AppFont

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.size.Size as CoilSize
import io.github.obihs2501.parsehubstudio.AppState
import io.github.obihs2501.parsehubstudio.library.ImageInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 灯箱条目：统一描述「远程媒体」（解析结果）与「本地文件」（本地图库）。
 *
 * [previewModel] / [originalModel] 是直接交给 Coil 的 model：
 * 远程是 sidecar 的 /api/media 代理地址（String），本地是 `java.io.File`。
 * 视频两项都不放原文件（Coil 解不了 mp4），只放首帧缩略图。
 */
data class ViewerItem(
    val title: String,
    val isVideo: Boolean,
    val previewModel: Any? = null,
    val originalModel: Any? = null,
    val meta: String = "",
    /** 本地文件路径：可直接用系统程序打开 / 在资源管理器中定位。 */
    val localPath: String? = null,
    /** 远程媒体的原始地址与上下文（打开原文件 / 保存到下载目录）。 */
    val rawUrl: String? = null,
    val remote: RemoteRef? = null,
    /** 原图像素尺寸（本地读文件头 / 远程来自解析结果）；0 = 未知，回退到解码尺寸。 */
    val width: Int = 0,
    val height: Int = 0,
) {
    data class RemoteRef(val referer: String?, val mediaIndex: Int)

    val thumbModel: Any? get() = previewModel ?: originalModel
}

data class LightboxState(val items: List<ViewerItem>, val index: Int)

/**
 * 全屏媒体查看器（miuix 风格，组件颜色全部走 MiuixTheme，深浅色自动适配）。
 *
 * 渲染：**两级解码** —— 先用 2048 预览层秒出画面，再在后台解原尺寸叠上去（同一宽高比，无跳动），
 * 所以既没有打开延迟，放大后也是原图像素；1:1 与缩放百分比按原图真实像素换算。
 *
 * 交互：滚轮缩放 / 拖动平移（限制在图片边界内，不会拖丢）/ 双击复位 /
 * ← → 切换 / Esc 关闭；缩放上限统一为「显示比例 400%」。
 * 本地视频用内置播放器（JavaFX Media）在应用内播放，它放不了的编码可交给系统播放器；
 * 远程视频只有首帧缩略图（要先保存到下载目录）。底部缩略图条可一键隐藏。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LightboxOverlay(state: AppState) {
    val lightbox = state.lightbox ?: return
    val item = lightbox.items.getOrNull(lightbox.index) ?: return
    val loader = LocalImageLoader.current
    val isVideo = item.isVideo

    var showOriginal by remember(item) { mutableStateOf(item.originalModel != null && !isVideo) }
    var fullReady by remember(item, showOriginal) { mutableStateOf(false) }
    var loadError by remember(item) { mutableStateOf(false) }
    var zoom by remember(item) { mutableFloatStateOf(1f) }
    var rotation by remember(item) { mutableFloatStateOf(0f) }
    var offset by remember(item) { mutableStateOf(Offset.Zero) }
    /** 舞台可用区域（px），用于 1:1 与平移边界。 */
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    /** 已解码位图尺寸（px），拿不到原图尺寸时当参考。 */
    var imageSize by remember(item) { mutableStateOf(Size.Zero) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(lightbox.index) { runCatching { focus.requestFocus() } }

    // 预取相邻图片（2048，和预览层同尺寸，切换时直接命中内存缓存）
    LaunchedEffect(lightbox.index) {
        val target = loader ?: return@LaunchedEffect
        listOf(lightbox.index - 1, lightbox.index + 1).forEach { i ->
            val neighbor = lightbox.items.getOrNull(i) ?: return@forEach
            if (neighbor.isVideo) return@forEach
            val model = neighbor.originalModel ?: neighbor.previewModel ?: return@forEach
            runCatching {
                target.enqueue(
                    coil3.request.ImageRequest.Builder(coil3.PlatformContext.INSTANCE)
                        .data(model)
                        .size(2048, 2048)
                        .build(),
                )
            }
        }
    }

    // 两级解码：预览层（快）+ 原图层（清晰）
    val baseModel = when {
        isVideo -> null
        showOriginal -> item.originalModel ?: item.previewModel
        else -> item.previewModel ?: item.originalModel
    }
    val fullModel = if (isVideo || !showOriginal) null else baseModel
    val previewRequest = remember(baseModel) {
        baseModel?.let {
            coil3.request.ImageRequest.Builder(coil3.PlatformContext.INSTANCE)
                .data(it)
                .size(2048, 2048)
                .build()
        }
    }
    // 原图层按原尺寸解码（本地文件解码 40MP 约 1~2s，叠在预览层上完成，用户无感）
    val fullRequest = remember(fullModel) {
        fullModel?.let {
            coil3.request.ImageRequest.Builder(coil3.PlatformContext.INSTANCE)
                .data(it)
                .size(CoilSize.ORIGINAL)
                .build()
        }
    }

    // 参考尺寸：优先原图像素尺寸 —— 进查看器后按当前项懒加载（打开瞬间不等 IO）
    var refDims by remember(item) { mutableStateOf(item.width to item.height) }
    LaunchedEffect(item) {
        if (item.isVideo || item.width > 0 || refDims.first > 0) return@LaunchedEffect
        val path = item.localPath ?: return@LaunchedEffect
        val dims = withContext(Dispatchers.IO) { ImageInfo.dimensions(java.io.File(path)) } ?: return@LaunchedEffect
        refDims = dims
    }
    val refSize = if (refDims.first > 0 && refDims.second > 0) {
        Size(refDims.first.toFloat(), refDims.second.toFloat())
    } else {
        imageSize
    }

    // Fit 之后的像素缩放比：显示百分比 / 1:1 / 平移边界都靠它换算
    val fitScale = if (refSize.width > 0f && refSize.height > 0f && viewSize.width > 0 && viewSize.height > 0) {
        minOf(viewSize.width / refSize.width, viewSize.height / refSize.height)
    } else 1f

    // 缩放上限按「显示比例」算（400%），任何图片都保证能到 1:1，不再出现 169% / 324% 这种随图变化的怪上限
    val maxZoom = (4f / fitScale).coerceAtLeast(4f)

    /** 平移限制：缩放后图片边缘不允许被拖进舞台内部（拖到边界就停）。 */
    fun clampOffset(candidate: Offset, scale: Float): Offset {
        val maxX = ((refSize.width * fitScale * scale - viewSize.width) / 2f).coerceAtLeast(0f)
        val maxY = ((refSize.height * fitScale * scale - viewSize.height) / 2f).coerceAtLeast(0f)
        return Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY))
    }

    fun applyZoom(target: Float) {
        zoom = target.coerceIn(0.2f, maxZoom)
        offset = clampOffset(offset, zoom)
    }

    val scrimInteraction = remember { MutableInteractionSource() }

    Box(
        Modifier
            .fillMaxSize()
            // 跟随主题：浅色模式白底、深色模式黑底，组件颜色全部取自 MiuixTheme
            .background(MiuixTheme.colorScheme.background)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.Escape -> { state.closeLightbox(); true }
                    Key.DirectionLeft -> { state.moveLightbox(-1); true }
                    Key.DirectionRight -> { state.moveLightbox(1); true }
                    Key.Equals, Key.Plus -> { applyZoom(zoom * 1.2f); true }
                    Key.Minus -> { applyZoom(zoom / 1.2f); true }
                    Key.Zero -> { zoom = 1f; rotation = 0f; offset = Offset.Zero; true }
                    else -> false
                }
            },
    ) {
        // 背景点击关闭
        Box(Modifier.fillMaxSize().clickable(scrimInteraction, indication = null) { state.closeLightbox() })

        Column(Modifier.fillMaxSize()) {
            // ---- 顶栏（文件名 / 分辨率 / 大小时间）
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        item.title.ifBlank { "媒体预览" },
                        fontSize = MiuixTheme.textStyles.body1.fontSize, fontFamily = AppFont.family,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(
                            "${lightbox.index + 1} / ${lightbox.items.size}",
                            if (refSize.width > 0f && refSize.height > 0f) {
                                "${refSize.width.toInt()} × ${refSize.height.toInt()}"
                            } else null,
                            item.meta.ifBlank { null },
                            if (item.previewModel != null && item.originalModel != null) {
                                if (showOriginal) "原文件" else "预览图"
                            } else null,
                        ).joinToString(" · "),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                IconButton(onClick = { state.closeLightbox() }, backgroundColor = MiuixTheme.colorScheme.secondaryVariant) {
                    Icon(MiuixIcons.Regular.Close, "关闭", Modifier.size(20.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantActions)
                }
            }

            // ---- 舞台
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .then(
                        if (isVideo) Modifier else Modifier
                            .onPointerEvent(PointerEventType.Scroll) { event ->
                                val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                if (delta != 0f) applyZoom(zoom * if (delta < 0) 1.12f else 1f / 1.12f)
                            }
                            .pointerInput(item) {
                                detectDragGestures { change, drag ->
                                    change.consume()
                                    offset = clampOffset(offset + drag, zoom)
                                }
                            }
                            .pointerInput(item) {
                                detectTapGestures(onDoubleTap = {
                                    if (zoom > 1.001f) {
                                        zoom = 1f
                                        offset = Offset.Zero
                                    } else {
                                        applyZoom(2f)
                                    }
                                })
                            },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 56.dp)
                        .onSizeChanged { viewSize = it },
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        // 本地视频：用 VLC 播放（内置播放器已按反馈移除，省掉 ~35MB native）
                        isVideo && item.localPath != null -> {
                            val path = item.localPath
                            if (VlcSupport.available) {
                                VlcVideoPlayer(
                                    file = java.io.File(path),
                                    modifier = Modifier.fillMaxSize(),
                                    onFallback = { state.playVideo(path) },
                                )
                            } else {
                                VideoUnavailableNotice(
                                    onPlayExternal = { state.playVideo(path) },
                                    onGetVlc = { state.openUrl("https://www.videolan.org/vlc/") },
                                )
                            }
                        }
                        // 远程视频：只显示首帧 + 提示（需要先保存到下载目录）
                        isVideo -> VideoStage(item = item, model = item.previewModel)
                        loadError -> Text(
                            "无法加载该媒体",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        loader == null || previewRequest == null -> Text(
                            "无法加载该媒体",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        else -> Box(Modifier.fillMaxSize()) {
                            // 1) 预览层：2048 快速解码，先把画面放出来
                            AsyncImage(
                                model = previewRequest,
                                imageLoader = loader,
                                contentDescription = item.title,
                                contentScale = ContentScale.Fit,
                                onSuccess = {
                                    if (!fullReady) imageSize = it.painter.intrinsicSize
                                    loadError = false
                                },
                                onError = {
                                    if (showOriginal && item.previewModel != null) {
                                        // 原文件拿不到（常见于历史里的过期直链）→ 回退预览图
                                        showOriginal = false
                                    } else {
                                        loadError = true
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer(
                                        scaleX = zoom,
                                        scaleY = zoom,
                                        rotationZ = rotation,
                                        translationX = offset.x,
                                        translationY = offset.y,
                                    ),
                            )
                            // 2) 原图层：后台解原尺寸，好了叠上去（宽高比一致，切换无跳动）
                            if (fullRequest != null) {
                                AsyncImage(
                                    model = fullRequest,
                                    imageLoader = loader,
                                    contentDescription = null,
                                    contentScale = ContentScale.Fit,
                                    alpha = if (fullReady) 1f else 0f,
                                    onSuccess = {
                                        imageSize = it.painter.intrinsicSize
                                        fullReady = true
                                    },
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer(
                                            scaleX = zoom,
                                            scaleY = zoom,
                                            rotationZ = rotation,
                                            translationX = offset.x,
                                            translationY = offset.y,
                                        ),
                                )
                            }
                        }
                    }
                }

                if (!isVideo && lightbox.items.size > 1) {
                    NavArrow(
                        icon = MiuixIcons.Regular.ChevronBackward,
                        description = "上一个",
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 10.dp),
                        onClick = { state.moveLightbox(-1) },
                    )
                    NavArrow(
                        icon = MiuixIcons.Regular.ChevronForward,
                        description = "下一个",
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp),
                        onClick = { state.moveLightbox(1) },
                    )
                }
            }

            // ---- 底部控制条
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!isVideo) {
                        ToolButton("−") { applyZoom(zoom / 1.2f) }
                        Text(
                            // 显示真实比例：100% = 一个原图像素对一个屏幕像素
                            "${((zoom * fitScale) * 100f).toInt()}%",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        ToolButton("＋") { applyZoom(zoom * 1.2f) }
                        ToolButton("1:1") { applyZoom(1f / fitScale) }
                        ToolButton("适应") { zoom = 1f; offset = Offset.Zero }
                        ToolButton("旋转") { rotation = (rotation + 90f) % 360f; offset = Offset.Zero }
                        if (!showOriginal && item.previewModel != null) {
                            ToolButton("加载原文件", primary = true) { showOriginal = true }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (lightbox.items.size > 1) {
                        ToolButton(if (state.showLightboxStrip) "隐藏缩略图" else "显示缩略图") { state.toggleLightboxStrip() }
                    }
                    if (item.localPath != null) {
                        if (isVideo) {
                            // 内置播放器放不了的编码（HEVC / 独立 .ts）走系统播放器
                            ToolButton("用系统播放器打开") { state.playVideo(item.localPath) }
                        } else {
                            ToolButton("用系统程序打开") { state.openLocalFile(item.localPath) }
                        }
                        ToolButton("在资源管理器中显示") { state.reveal(item.localPath) }
                    } else {
                        if (item.rawUrl != null) {
                            ToolButton("打开原文件") { state.openUrl(item.rawUrl) }
                        }
                        item.remote?.referer?.takeIf { it.isNotBlank() }?.let { referer ->
                            ToolButton("打开原帖") { state.openUrl(referer) }
                        }
                        item.remote?.let { ref ->
                            ToolButton("保存到下载目录", primary = true) { state.downloadMedia(ref.mediaIndex, ref.referer) }
                        }
                    }
                }

                if (lightbox.items.size > 1 && state.showLightboxStrip) {
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(lightbox.items) { index, entry ->
                            val selected = index == lightbox.index
                            Box(Modifier.size(if (selected) 58.dp else 48.dp)) {
                                RemoteThumb(
                                    model = entry.thumbModel,
                                    modifier = Modifier.fillMaxSize(),
                                    radius = 12.dp,
                                    contentDescription = entry.title,
                                    fallbackIcon = if (entry.isVideo) MiuixIcons.Regular.Play else MiuixIcons.Regular.Folder,
                                    size = 256,
                                )
                                if (selected) {
                                    Box(Modifier.fillMaxSize().squircleSurface(MiuixTheme.colorScheme.primary.copy(alpha = 0.25f), 12.dp))
                                }
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .clickable(remember { MutableInteractionSource() }, indication = null) { state.selectLightbox(index) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 视频占位（远程视频）：首帧缩略图 + 提示。本地视频走 VLC 播放器。 */
@Composable
private fun VideoStage(item: ViewerItem, model: Any?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (model != null) {
            RemoteThumb(
                model = model,
                modifier = Modifier.fillMaxSize(),
                radius = 16.dp,
                contentDescription = item.title,
                contentScale = ContentScale.Fit,
                fallbackIcon = MiuixIcons.Regular.Play,
            )
        }
        Box(
            Modifier
                .squircleSurface(MiuixTheme.colorScheme.surfaceContainerHighest, 12.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("视频不在应用内预览", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceContainerHighest)
                Spacer(Modifier.height(2.dp))
                Text(
                    "保存到下载目录后可在应用内播放",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
private fun NavArrow(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = modifier, backgroundColor = MiuixTheme.colorScheme.secondaryVariant) {
        Icon(icon, description, Modifier.size(22.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantActions)
    }
}

@Composable
private fun ToolButton(text: String, primary: Boolean = false, onClick: () -> Unit) {
    if (primary) {
        Button(onClick = onClick, colors = ButtonDefaults.buttonColorsPrimary()) {
            Text(text, fontSize = MiuixTheme.textStyles.footnote1.fontSize, fontFamily = AppFont.family, color = MiuixTheme.colorScheme.onPrimary)
        }
    } else {
        val interaction = remember { MutableInteractionSource() }
        Box(
            Modifier
                .squircleSurface(MiuixTheme.colorScheme.secondaryVariant, 10.dp)
                .clickable(interaction, indication = null, onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(
                text,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                maxLines = 1,
            )
        }
    }
}
