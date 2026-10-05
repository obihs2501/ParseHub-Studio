package io.github.obihs2501.parsehubstudio.ui.screens

import io.github.obihs2501.parsehubstudio.ui.AppFont

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.obihs2501.parsehubstudio.AppState
import io.github.obihs2501.parsehubstudio.library.LocalLibrary
import io.github.obihs2501.parsehubstudio.ui.*
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 本地图库：直接浏览本机已下载的图片 / 视频。
 *
 * 支持**多个根目录**（默认下载目录 + 用户添加的文件夹，持久化在 gallery-roots.json），
 * 左侧按根目录分组列出一级文件夹，右侧是媒体网格。新下载仍然保存到设置的下载目录。
 */
@Composable
fun GalleryScreen(state: AppState) {
    LaunchedEffect(Unit) {
        if (state.galleryRootGroups.isEmpty()) state.loadGallery()
    }

    Row(Modifier.fillMaxSize()) {
        GallerySidebar(state)
        ResizableDivider(
            onDelta = { state.resizeGallerySidebar(state.gallerySidebarWidth + it) },
            onFinished = { state.persistUiPrefs() },
        )
        Box(Modifier.weight(1f).fillMaxHeight()) {
            GalleryContent(state)
        }
    }
}

/* ------------------------------------------------------------------ 左侧 */

@Composable
private fun GallerySidebar(state: AppState) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier
            .width(state.gallerySidebarWidth.dp)
            .fillMaxHeight()
            .background(MiuixTheme.colorScheme.surface)
            .padding(horizontal = 10.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("本地图库", style = MiuixTheme.textStyles.title4, color = MiuixTheme.colorScheme.onBackground)
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = { state.loadGallery(force = true) },
                minHeight = 35.dp,
                minWidth = 35.dp,
                backgroundColor = MiuixTheme.colorScheme.secondaryVariant,
            ) {
                Icon(MiuixIcons.Regular.Refresh, "重新扫描", Modifier.size(16.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        Spacer(Modifier.height(10.dp))
        TextField(
            state.galleryFolderSearch,
            { state.galleryFolderSearch = it },
            label = "搜索文件夹",
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            val keyword = state.galleryFolderSearch.trim().lowercase()
            state.galleryRootGroups.forEach { group ->
                val key = "gallery-root/${group.path}"
                val expanded = !state.isCollapsed(key)
                val folders = group.folders.filter { keyword.isEmpty() || it.name.lowercase().contains(keyword) }
                GroupHeader(
                    title = group.name,
                    count = group.folders.size,
                    summary = if (group.removable) group.path else "默认下载目录",
                    expanded = expanded,
                    onToggle = { state.toggleGroup(key) },
                    // 移除入口收进分组标题行右侧的小叉，且要二次确认，避免误触
                    onRemove = if (group.removable) {
                        { state.openDialog(Dialog.ConfirmRemoveRoot(group.path, group.name)) }
                    } else null,
                )

                if (expanded) {
                    if (folders.isEmpty()) {
                        Text(
                            if (state.galleryScanning) "正在扫描…" else "（没有文件夹）",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    folders.forEach { folder ->
                        SidebarRow(
                            title = folder.name,
                            summary = listOfNotNull(
                                if (folder.mediaCount > 0) "${folder.mediaCount} 个媒体" else null,
                                formatRelativeTime(folder.modifiedAt).ifBlank { null },
                            ).joinToString(" · "),
                            selected = state.galleryStack.firstOrNull()?.path == folder.path,
                            onClick = {
                                if (state.galleryStack.firstOrNull()?.path == folder.path) {
                                    state.galleryStack.clear()
                                    state.galleryMedias.clear()
                                    state.gallerySubfolders.clear()
                                } else {
                                    state.openGalleryFolder(folder, listOf(folder))
                                }
                            },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .squircleSurface(MiuixTheme.colorScheme.secondaryVariant, 12.dp)
                    .clickable(interaction, null) {
                        state.openDialog(Dialog.AddGalleryRoot(initial = state.settings?.defaultSaveDir.orEmpty()))
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(MiuixIcons.Regular.Add, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("添加文件夹…", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onBackground)
            }
        }
    }
}

@Composable
private fun SidebarRow(title: String, summary: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .squircleSurface(
                if (selected) MiuixTheme.colorScheme.tertiaryContainer else Color.Transparent,
                12.dp,
            )
            .clickable(interaction, null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            MiuixIcons.Regular.Folder,
            null,
            Modifier.size(17.dp),
            tint = if (selected) MiuixTheme.colorScheme.onTertiaryContainer else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = MiuixTheme.textStyles.body2.fontSize, fontFamily = AppFont.family,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color = if (selected) MiuixTheme.colorScheme.onTertiaryContainer else MiuixTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary.isNotBlank()) {
                Text(
                    summary,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/* ------------------------------------------------------------------ 右侧 */

@Composable
private fun GalleryContent(state: AppState) {
    val current = state.galleryStack.lastOrNull()
    if (current == null) {
        // 未打开文件夹：铺满窗口的两列文件夹视图（原来只有一句「从左侧选择…」的空状态）
        FolderOverview(state)
        return
    }

    var showDisplay by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        // ---- 标题行
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.galleryStack.size > 1) {
                IconButton(
                    onClick = { state.galleryGoBack() },
                    minHeight = 35.dp,
                    minWidth = 35.dp,
                    backgroundColor = MiuixTheme.colorScheme.secondaryVariant,
                ) {
                    Icon(MiuixIcons.Regular.ChevronBackward, "上一级", Modifier.size(16.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    current.name,
                    fontSize = MiuixTheme.textStyles.title4.fontSize, fontFamily = AppFont.family,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    state.galleryStack.joinToString(" / ") { it.name },
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton("重新扫描", onClick = { state.openGalleryFolder(current, state.galleryStack.toList(), force = true) })
            TextButton("打开目录", onClick = { state.reveal(current.path) })
        }

        // ---- 工具栏（第一行：筛选 + 搜索 + 显示设置开关）
        val images = state.galleryMedias.count { !it.isVideo }
        val videos = state.galleryMedias.count { it.isVideo }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterPill("全部 ${state.galleryMedias.size}", state.galleryTypeFilter == "all") { state.galleryTypeFilter = "all" }
            FilterPill("图片 $images", state.galleryTypeFilter == "image") { state.galleryTypeFilter = "image" }
            FilterPill("视频 $videos", state.galleryTypeFilter == "video") { state.galleryTypeFilter = "video" }
            Spacer(Modifier.width(4.dp))
            Box(Modifier.width(200.dp)) {
                TextField(
                    state.gallerySearch,
                    { state.gallerySearch = it },
                    label = "筛选文件名",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.weight(1f))
            FilterPill(if (showDisplay) "收起显示设置" else "显示设置", showDisplay) { showDisplay = !showDisplay }
        }

        // ---- 工具栏（第二行：排序常显；列数 / 显示方式收在「显示设置」里）
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("排序", style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            FilterPill("名称", state.gallerySortBy == "name") { state.gallerySortBy = "name" }
            FilterPill("时间", state.gallerySortBy == "time") { state.gallerySortBy = "time" }
            FilterPill("大小", state.gallerySortBy == "size") { state.gallerySortBy = "size" }
            FilterPill(if (state.gallerySortDesc) "↓ 降序" else "↑ 升序") { state.gallerySortDesc = !state.gallerySortDesc }
            if (showDisplay) {
                Spacer(Modifier.width(10.dp))
                Text("列数", style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                FilterPill("−") { state.galleryColumns = (state.galleryColumns - 1).coerceAtLeast(2) }
                Text("${state.galleryColumns}", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                FilterPill("＋") { state.galleryColumns = (state.galleryColumns + 1).coerceAtMost(10) }
                Spacer(Modifier.weight(1f))
                FilterPill(if (state.galleryCoverMode) "裁剪填充" else "完整显示") { state.galleryCoverMode = !state.galleryCoverMode }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("视频首帧", style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Spacer(Modifier.width(6.dp))
                    Switch(state.galleryVideoThumbs, onCheckedChange = { state.galleryVideoThumbs = it })
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(8.dp))

        // ---- 内容
        val keyword = state.gallerySearch.trim().lowercase()
        val filtered = state.galleryMedias.filter { file ->
            when (state.galleryTypeFilter) {
                "image" -> !file.isVideo
                "video" -> file.isVideo
                else -> true
            } && (keyword.isEmpty() || file.name.lowercase().contains(keyword))
        }
        val sorted = when (state.gallerySortBy) {
            "time" -> filtered.sortedBy { it.modifiedAt }
            "size" -> filtered.sortedBy { it.sizeBytes }
            else -> filtered.sortedBy { it.name.lowercase() }
        }.let { if (state.gallerySortDesc) it.reversed() else it }

        LazyColumn(Modifier.fillMaxSize()) {
            if (state.gallerySubfolders.isNotEmpty()) {
                item {
                    Text(
                        "子文件夹 · ${state.gallerySubfolders.size}",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
                items(state.gallerySubfolders.chunked(3).size) { rowIndex ->
                    val row = state.gallerySubfolders.chunked(3)[rowIndex]
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { folder ->
                            SubfolderCard(folder = folder, modifier = Modifier.weight(1f), onClick = { state.openGalleryFolder(folder) })
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }

            item {
                Text(
                    "共 ${sorted.size} 个文件 · $images 张图片 · $videos 个视频" +
                        if (state.galleryScanning) " · 正在扫描…" else "",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }

            if (sorted.isEmpty()) {
                item {
                    Text(
                        if (state.galleryMedias.isEmpty()) "该文件夹内没有媒体文件" else "没有符合筛选条件的文件",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }

            val columns = state.galleryColumns
            val visible = sorted.take(state.galleryVisibleCount)
            items(visible.chunked(columns).size) { rowIndex ->
                val row = visible.chunked(columns)[rowIndex]
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEachIndexed { columnIndex, file ->
                        val index = rowIndex * columns + columnIndex
                        MediaTile(
                            state = state,
                            file = file,
                            modifier = Modifier.weight(1f),
                            onClick = { state.openLocalLightbox(sorted, index) },
                        )
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            if (visible.size < sorted.size) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        TextButton("加载更多（已显示 ${visible.size} / ${sorted.size}）", onClick = { state.galleryShowMore() })
                    }
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun FilterPill(text: String, selected: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .squircleSurface(
                if (selected) MiuixTheme.colorScheme.tertiaryContainer else MiuixTheme.colorScheme.secondaryVariant,
                10.dp,
            )
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text,
            style = MiuixTheme.textStyles.footnote1,
            color = if (selected) MiuixTheme.colorScheme.onTertiaryContainer else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
        )
    }
}

/* --------------------------------------------- 未打开文件夹：两列铺满 */

/**
 * 未打开文件夹时的首页：把每个图库位置下的一级文件夹**铺满窗口**（两列卡片），
 * 排序可调（名称 / 时间 / 数量，升 / 降序）。打开某个文件夹后回到按列数可调的媒体网格。
 */
@Composable
private fun FolderOverview(state: AppState) {
    val keyword = state.galleryFolderSearch.trim().lowercase()
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "全部文件夹",
                    style = MiuixTheme.textStyles.title4,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onBackground,
                )
                Text(
                    "共 ${state.galleryRootGroups.sumOf { it.folders.size }} 个文件夹 · ${state.galleryRootGroups.size} 个图库位置" +
                        if (state.galleryScanning) " · 正在扫描…" else "",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Text("排序", style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Spacer(Modifier.width(6.dp))
            FilterPill("名称", state.galleryFolderSort == "name") { state.galleryFolderSort = "name" }
            FilterPill("时间", state.galleryFolderSort == "time") { state.galleryFolderSort = "time" }
            FilterPill("数量", state.galleryFolderSort == "count") { state.galleryFolderSort = "count" }
            FilterPill(if (state.galleryFolderSortDesc) "↓ 降序" else "↑ 升序") { state.galleryFolderSortDesc = !state.galleryFolderSortDesc }
            Spacer(Modifier.width(8.dp))
            TextButton("重新扫描", onClick = { state.loadGallery(force = true) })
        }

        LazyColumn(Modifier.fillMaxSize()) {
            state.galleryRootGroups.forEach { group ->
                val folders = group.folders.filter { keyword.isEmpty() || it.name.lowercase().contains(keyword) }
                if (keyword.isNotEmpty() && folders.isEmpty()) return@forEach
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            group.name,
                            style = MiuixTheme.textStyles.headline1,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onBackground,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("${folders.size}", style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (group.removable) group.path else "默认下载目录",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                val sorted = when (state.galleryFolderSort) {
                    "time" -> folders.sortedBy { it.modifiedAt }
                    "count" -> folders.sortedBy { it.mediaCount }
                    else -> folders.sortedBy { it.name.lowercase() }
                }.let { if (state.galleryFolderSortDesc) it.reversed() else it }
                val rows = sorted.chunked(2)
                items(rows.size) { rowIndex ->
                    val row = rows[rowIndex]
                    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { folder ->
                            FolderCard(
                                folder = folder,
                                modifier = Modifier.weight(1f),
                                onClick = { state.openGalleryFolder(folder, listOf(folder)) },
                            )
                        }
                        repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }

            if (state.galleryRootGroups.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (state.galleryScanning) "正在扫描…" else "还没有添加任何文件夹",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(10.dp))
                        TextButton("添加文件夹…", onClick = {
                            state.openDialog(Dialog.AddGalleryRoot(initial = state.settings?.defaultSaveDir.orEmpty()))
                        })
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** 两列视图里的文件夹卡片（名称可换行，不截断）。 */
@Composable
private fun FolderCard(folder: LocalLibrary.Folder, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .squircleSurface(MiuixTheme.colorScheme.secondaryVariant, 14.dp)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).squircleSurface(MiuixTheme.colorScheme.surface, 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(MiuixIcons.Regular.Folder, null, Modifier.size(22.dp), tint = MiuixTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                folder.name,
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                listOfNotNull(
                    if (folder.mediaCount > 0) "${folder.mediaCount} 个媒体" else null,
                    formatRelativeTime(folder.modifiedAt).ifBlank { null },
                ).joinToString(" · "),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SubfolderCard(folder: LocalLibrary.Folder, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .squircleSurface(MiuixTheme.colorScheme.secondaryVariant, 12.dp)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(MiuixIcons.Regular.Folder, null, Modifier.size(20.dp), tint = MiuixTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                folder.name,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    if (folder.mediaCount > 0) "${folder.mediaCount} 个媒体" else null,
                    formatRelativeTime(folder.modifiedAt).ifBlank { null },
                ).joinToString(" · "),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MediaTile(
    state: AppState,
    file: LocalLibrary.MediaFile,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var thumb by remember(file.path) { mutableStateOf<Any?>(null) }
    LaunchedEffect(file.path, state.galleryVideoThumbs) {
        thumb = if (file.isVideo) state.videoThumb(file) else file.file
    }
    val interaction = remember { MutableInteractionSource() }

    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f)
                .clickable(interaction, indication = null, onClick = onClick),
        ) {
            RemoteThumb(
                model = thumb,
                modifier = Modifier.fillMaxSize(),
                radius = 12.dp,
                contentDescription = file.name,
                contentScale = if (state.galleryCoverMode) ContentScale.Crop else ContentScale.Fit,
                fallbackIcon = if (file.isVideo) MiuixIcons.Regular.Play else MiuixIcons.Regular.Folder,
                // 按 512px 解码：高 DPI 屏上比按布局尺寸解码清晰得多
                size = 512,
            )
            if (file.isVideo) {
                Box(Modifier.align(Alignment.Center)) {
                    // 只是个「这是视频」的标记：点整块都进查看器，视频在应用内直接播放
                    Box(
                        Modifier
                            .squircleSurface(MiuixTheme.colorScheme.primary.copy(alpha = 0.92f), 20.dp)
                            .padding(8.dp),
                    ) {
                        Icon(MiuixIcons.Regular.Play, null, Modifier.size(20.dp), tint = MiuixTheme.colorScheme.onPrimary)
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            file.name,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
