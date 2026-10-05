package io.github.obihs2501.parsehubstudio.ui

import io.github.obihs2501.parsehubstudio.ui.AppFont

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.obihs2501.parsehubstudio.AppState
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NumberPicker
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 应用内对话框（自绘 overlay，跟随 miuix 配色与 squircle 圆角）。 */
sealed interface Dialog {
    data class AddCookie(val platformId: String, val platformName: String) : Dialog

    /** 单行 / 多行文本设置项；[templatePreview] 打开文件命名模板的实时预览。 */
    data class TextEdit(
        val key: String,
        val title: String,
        val summary: String? = null,
        val initial: String = "",
        val placeholder: String = "",
        val multiline: Boolean = false,
        val templatePreview: Boolean = false,
    ) : Dialog

    /** 目录选择（走 /api/fs/list）。 */
    data class PickDir(val key: String, val title: String, val initial: String = "") : Dialog

    /** 给本地图库添加一个根目录（可多个，持久化）。 */
    data class AddGalleryRoot(val title: String = "添加图库文件夹", val initial: String = "") : Dialog

    data class PickOption(val key: String, val title: String, val options: List<Pair<String, String>>, val current: String) : Dialog

    data class NumberEdit(val key: String, val title: String, val summary: String? = null, val initial: Int, val range: IntRange) : Dialog

    data class BookmarkEdit(val index: Int?, val name: String = "", val url: String = "", val group: String = "通用") : Dialog

    data class AliasEdit(val platform: String = "", val account: String = "", val creator: String = "", val matchBy: String = "id") : Dialog

    /** 平台主 Cookie（/api/config/cookie）。 */
    data class SetMainCookie(val platformId: String, val platformName: String, val currentHint: String?) : Dialog

    /** 平台代理（/api/config/proxy），target = parse / download / all。 */
    data class PlatformProxy(val platformId: String, val platformName: String, val target: String, val current: String?) : Dialog

    data object ConfirmClearHistory : Dialog
    data object ConfirmClearTasks : Dialog

    /** 从图库移除额外添加的文件夹（只从列表移除，不删磁盘文件）。 */
    data class ConfirmRemoveRoot(val path: String, val name: String) : Dialog
}

@Composable
fun DialogHost(state: AppState) {
    val dialog = state.dialog ?: return
    when (dialog) {
        is Dialog.AddCookie -> AddCookieDialog(state, dialog)
        is Dialog.TextEdit -> TextEditDialog(state, dialog)
        is Dialog.PickDir -> DirPickerDialog(state, dialog) { path ->
            state.applyText(dialog.key, path)
            state.dismissDialog()
        }
        is Dialog.AddGalleryRoot -> DirPickerDialog(
            state,
            Dialog.PickDir("", dialog.title, dialog.initial),
        ) { path ->
            state.addGalleryRoot(path)
            state.dismissDialog()
        }
        is Dialog.PickOption -> OptionPickerDialog(state, dialog)
        is Dialog.NumberEdit -> NumberEditDialog(state, dialog)
        is Dialog.BookmarkEdit -> BookmarkEditDialog(state, dialog)
        is Dialog.AliasEdit -> AliasEditDialog(state, dialog)
        is Dialog.SetMainCookie -> SetMainCookieDialog(state, dialog)
        is Dialog.PlatformProxy -> PlatformProxyDialog(state, dialog)
        Dialog.ConfirmClearHistory -> ConfirmDialog(state, "清空历史记录", "将删除全部解析历史，无法恢复。", "清空") {
            state.clearHistory(); state.dismissDialog()
        }
        Dialog.ConfirmClearTasks -> ConfirmDialog(state, "清除已完成任务", "只清除已结束的任务，进行中的任务保留。", "清除") {
            state.clearFinishedTasks(); state.dismissDialog()
        }
        is Dialog.ConfirmRemoveRoot -> ConfirmDialog(
            state,
            "移除文件夹",
            "把「${dialog.name}」从图库列表中移除（不会删除磁盘上的文件）。",
            "移除",
        ) {
            state.removeGalleryRoot(dialog.path); state.dismissDialog()
        }
    }
}

/* ------------------------------------------------------------------ 骨架 */

/** 当前对话框的关闭回调（DialogScaffold 提供，「取消」按钮直接使用）。 */
private val LocalDialogDismiss = staticCompositionLocalOf<() -> Unit> { {} }

@Composable
private fun DialogScaffold(
    title: String,
    summary: String? = null,
    onDismiss: () -> Unit,
    maxWidth: Dp = 460.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrimInteraction = remember { MutableInteractionSource() }
    val cardInteraction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(scrimInteraction, indication = null) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = maxWidth)
                .padding(24.dp)
                .squircleSurface(MiuixTheme.colorScheme.surfaceContainerHighest, 24.dp)
                .clickable(cardInteraction, indication = null) {}
                .padding(20.dp),
        ) {
            Text(
                title,
                fontSize = MiuixTheme.textStyles.title4.fontSize, fontFamily = AppFont.family,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceContainerHighest,
            )
            if (summary != null) {
                Spacer(Modifier.height(6.dp))
                Text(summary, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Spacer(Modifier.height(16.dp))
            CompositionLocalProvider(LocalDialogDismiss provides onDismiss) {
                content()
            }
        }
    }
}

@Composable
private fun DialogActions(
    confirmText: String = "确定",
    confirmEnabled: Boolean = true,
    onConfirm: () -> Unit,
) {
    val dismiss = LocalDialogDismiss.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        TextButton("取消", onClick = dismiss)
        Spacer(Modifier.width(8.dp))
        Button(onClick = onConfirm, enabled = confirmEnabled, colors = ButtonDefaults.buttonColorsPrimary()) {
            Text(confirmText, fontSize = MiuixTheme.textStyles.button.fontSize, fontFamily = AppFont.family, color = MiuixTheme.colorScheme.onPrimary)
        }
    }
}

/* ------------------------------------------------------------------ 各对话框 */

@Composable
private fun AddCookieDialog(state: AppState, dialog: Dialog.AddCookie) {
    var name by remember { mutableStateOf("") }
    var cookie by remember { mutableStateOf("") }
    DialogScaffold(
        title = "添加账户 · ${dialog.platformName}",
        summary = "Cookie 只保存在本机，解析时按平台轮换。",
        onDismiss = state::dismissDialog,
    ) {
        TextField(name, { name = it }, label = "备注名", singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        TextField(
            cookie, { cookie = it },
            label = "Cookie",
            minLines = 4,
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
        )
        Spacer(Modifier.height(16.dp))
        DialogActions(confirmText = "添加", confirmEnabled = cookie.isNotBlank()) {
            state.addCookie(dialog.platformId, cookie.trim(), name.trim())
            state.dismissDialog()
        }
    }
}

@Composable
private fun TextEditDialog(state: AppState, dialog: Dialog.TextEdit) {
    var value by remember { mutableStateOf(dialog.initial) }
    var preview by remember { mutableStateOf<String?>(null) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var tokens by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    if (dialog.templatePreview) {
        LaunchedEffect(value) {
            val settings = state.settings
            val folder = if (dialog.key == "folder_template") value else settings?.folderTemplate.orEmpty()
            val file = if (dialog.key == "file_template") value else settings?.fileTemplate.orEmpty()
            val res = state.namingPreview(folder, file)
            preview = res?.let { obj ->
                val f = obj["folder"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                val n = obj["file"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                if (f != null && n != null) "$f\\$n" else null
            }
            previewError = if (preview == null) "模板无法渲染，请检查变量名" else null
            if (tokens.isEmpty()) {
                tokens = res?.get("tokens")?.jsonObject?.entries
                    ?.map { (key, label) -> key to label.jsonPrimitive.content }
                    .orEmpty()
            }
        }
    }

    DialogScaffold(
        title = dialog.title,
        summary = dialog.summary,
        onDismiss = state::dismissDialog,
        maxWidth = 520.dp,
    ) {
        TextField(
            value, { value = it },
            label = dialog.placeholder,
            singleLine = !dialog.multiline,
            minLines = if (dialog.multiline) 4 else 1,
            modifier = Modifier.fillMaxWidth().heightIn(min = if (dialog.multiline) 96.dp else 56.dp),
        )
        if (dialog.templatePreview) {
            Spacer(Modifier.height(10.dp))
            Text(
                previewError ?: "预览：$preview",
                style = MiuixTheme.textStyles.footnote1,
                color = if (previewError != null) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (dialog.key == "folder_template" && !value.contains("{id}")) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "⚠ 模板不含 {id}：同一作者的不同作品会存进同一目录，重名文件会自动改名（xxx_2）" +
                        if (state.settings?.saveMetadata == true) "，metadata.json 会变成 metadata_2.json" else "",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.error,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                TextButton("追加 {id} 到末尾", onClick = { value += "_{id}" })
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "点击下面的元素插入到末尾：",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
            Spacer(Modifier.height(4.dp))
            Column(Modifier.fillMaxWidth().heightIn(max = 208.dp).verticalScroll(rememberScrollState())) {
                tokens.forEach { (token, label) ->
                    val interaction = remember(token) { MutableInteractionSource() }
                    val text = if (token == "index") "{index:03d}" else "{$token}"
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(interaction, indication = null) { value += text }
                            .padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .squircleSurface(MiuixTheme.colorScheme.secondaryVariant, 8.dp)
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(text, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(label, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        DialogActions(confirmText = "保存", confirmEnabled = value.isNotBlank() || dialog.multiline) {
            state.applyText(dialog.key, value.trim())
            state.dismissDialog()
        }
    }
}

@Composable
private fun DirPickerDialog(state: AppState, dialog: Dialog.PickDir, onPicked: (String) -> Unit) {
    LaunchedEffect(dialog) { state.browse(dialog.initial.ifBlank { null }) }
    val home = System.getProperty("user.home").orEmpty()
    val shortcuts = listOf(
        "此电脑" to null,
        "用户目录" to home,
        "下载" to "$home\\Downloads",
        "桌面" to "$home\\Desktop",
        "图片" to "$home\\Pictures",
    )
    DialogScaffold(
        title = dialog.title,
        summary = "先选一个常用位置，或逐级进入目标目录，然后点「使用此目录」。",
        onDismiss = state::dismissDialog,
        maxWidth = 560.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            shortcuts.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { (label, path) ->
                        val interaction = remember(label) { MutableInteractionSource() }
                        Box(
                            Modifier
                                .squircleSurface(MiuixTheme.colorScheme.secondaryVariant, 10.dp)
                                .clickable(interaction, indication = null) { state.browse(path) }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(label, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onBackground)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            state.fsPath.ifBlank { "此电脑" },
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 300.dp)) {
            val parent = state.fsParent
            if (parent != null) {
                item {
                    PrefRow("上一级", parent, onClick = { state.browse(parent) })
                    RowDivider()
                }
            }
            if (state.fsDirs.isEmpty()) {
                item {
                    Text(
                        "此目录下没有子目录，可直接点「使用此目录」。",
                        modifier = Modifier.padding(16.dp),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            items(state.fsDirs) { dir ->
                PrefRow(dir.name, dir.path, onClick = { state.browse(dir.path) })
                RowDivider()
            }
        }
        Spacer(Modifier.height(16.dp))
        DialogActions(confirmText = "使用此目录", confirmEnabled = state.fsPath.isNotBlank()) {
            onPicked(state.fsPath)
        }
    }
}

@Composable
private fun OptionPickerDialog(state: AppState, dialog: Dialog.PickOption) {
    DialogScaffold(title = dialog.title, onDismiss = state::dismissDialog) {
        dialog.options.forEachIndexed { index, (value, label) ->
            if (index > 0) RowDivider()
            PrefRow(label, null, onClick = {
                state.applyText(dialog.key, value)
                state.dismissDialog()
            }) {
                if (value == dialog.current) {
                    Icon(MiuixIcons.Regular.Ok, null, Modifier.width(20.dp).height(20.dp), tint = MiuixTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun NumberEditDialog(state: AppState, dialog: Dialog.NumberEdit) {
    var value by remember { mutableIntStateOf(dialog.initial) }
    DialogScaffold(title = dialog.title, summary = dialog.summary, onDismiss = state::dismissDialog) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            NumberPicker(value = value, onValueChange = { value = it }, range = dialog.range, label = { "$it 路" })
        }
        Spacer(Modifier.height(16.dp))
        DialogActions {
            state.applyNumber(dialog.key, value)
            state.dismissDialog()
        }
    }
}

@Composable
private fun BookmarkEditDialog(state: AppState, dialog: Dialog.BookmarkEdit) {
    var name by remember { mutableStateOf(dialog.name) }
    var url by remember { mutableStateOf(dialog.url) }
    var group by remember { mutableStateOf(dialog.group) }
    DialogScaffold(
        title = if (dialog.index == null) "添加解析网站" else "编辑解析网站",
        summary = "收藏第三方解析工具，在「解析网站收藏」里一键打开。",
        onDismiss = state::dismissDialog,
    ) {
        TextField(name, { name = it }, label = "名称", singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        TextField(url, { url = it }, label = "网址（https://…）", singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        TextField(group, { group = it }, label = "分组", singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        DialogActions(confirmText = "保存", confirmEnabled = name.isNotBlank() && url.startsWith("http")) {
            state.saveBookmark(dialog.index, name, url, group)
            state.dismissDialog()
        }
    }
}

@Composable
private fun AliasEditDialog(state: AppState, dialog: Dialog.AliasEdit) {
    var platform by remember { mutableStateOf(dialog.platform.ifBlank { state.platforms.firstOrNull()?.id.orEmpty() }) }
    var account by remember { mutableStateOf(dialog.account) }
    var creator by remember { mutableStateOf(dialog.creator) }
    var byId by remember { mutableStateOf(dialog.matchBy != "name") }
    DialogScaffold(
        title = "添加作者别名",
        summary = "把不同账号下的同一作者合并到同一个目录。",
        onDismiss = state::dismissDialog,
        maxWidth = 520.dp,
    ) {
        Text("平台", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Spacer(Modifier.height(8.dp))
        if (state.platforms.isEmpty()) {
            Text("正在加载平台…", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantActions)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.platforms.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { item ->
                            val selected = item.id == platform
                            val interaction = remember(item.id) { MutableInteractionSource() }
                            Box(
                                Modifier
                                    .squircleSurface(
                                        if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.secondaryVariant,
                                        10.dp,
                                    )
                                    .clickable(interaction, indication = null) { platform = item.id }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    item.label,
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = if (selected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onBackground,
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        TextField(account, { account = it }, label = "账号 / 作者 ID", singleLine = true, modifier = Modifier.fillMaxWidth())
        InlinePicker("从本地已有作者中选择", state.localAuthorNames()) { account = it }
        Spacer(Modifier.height(12.dp))
        TextField(creator, { creator = it }, label = "归档目录名", singleLine = true, modifier = Modifier.fillMaxWidth())
        InlinePicker("从本地已有文件夹中选择", state.localFolderNames()) { creator = it }
        Spacer(Modifier.height(12.dp))
        PrefRow("按作者 ID 匹配", if (byId) "推荐：作者改名也能合并" else "按作者昵称匹配") {
            top.yukonga.miuix.kmp.basic.Switch(byId, onCheckedChange = { byId = it })
        }
        Spacer(Modifier.height(16.dp))
        DialogActions(confirmText = "添加", confirmEnabled = platform.isNotBlank() && account.isNotBlank() && creator.isNotBlank()) {
            state.saveAlias(platform.trim(), account.trim(), creator.trim(), if (byId) "id" else "name")
            state.dismissDialog()
        }
    }
}

@Composable
private fun ConfirmDialog(state: AppState, title: String, summary: String, confirmText: String, onConfirm: () -> Unit) {
    DialogScaffold(title = title, summary = summary, onDismiss = state::dismissDialog) {
        DialogActions(confirmText = confirmText, onConfirm = onConfirm)
    }
}

/**
 * 内联下拉选择：点一下展开候选列表，整行显示完整名称（长名字换行、不截断），选中后自动收起。
 *
 * 早先用「胶囊」排布，名字长 / 候选多时会显示不全（例如「麻薯」被裁掉、末尾整块被切）。
 */
@Composable
private fun InlinePicker(label: String, items: List<String>, onPick: (String) -> Unit) {
    if (items.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    Spacer(Modifier.height(6.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .squircleSurface(MiuixTheme.colorScheme.secondaryVariant, 10.dp)
            .clickable(interaction, indication = null) { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        Text(
            "${items.size} 个",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            if (expanded) MiuixIcons.Regular.ExpandLess else MiuixIcons.Regular.ExpandMore,
            contentDescription = if (expanded) "收起" else "展开",
            modifier = Modifier.size(16.dp),
            tint = MiuixTheme.colorScheme.primary,
        )
    }
    if (expanded) {
        Spacer(Modifier.height(4.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 168.dp)
                .squircleSurface(MiuixTheme.colorScheme.secondaryVariant.copy(alpha = 0.45f), 10.dp)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
        ) {
            items.forEach { item ->
                val itemInteraction = remember(item) { MutableInteractionSource() }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(itemInteraction, indication = null) {
                            onPick(item)
                            expanded = false
                        }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        item,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onBackground,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

@Composable
private fun SetMainCookieDialog(state: AppState, dialog: Dialog.SetMainCookie) {
    var cookie by remember { mutableStateOf("") }
    DialogScaffold(
        title = "主 Cookie · ${dialog.platformName}",
        summary = dialog.currentHint?.let { "当前：$it（保存后覆盖）" } ?: "未设置；Cookie 池为空时用它兜底。",
        onDismiss = state::dismissDialog,
    ) {
        TextField(
            cookie, { cookie = it },
            label = "Cookie",
            minLines = 4,
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
        )
        Spacer(Modifier.height(16.dp))
        DialogActions(confirmText = "保存", confirmEnabled = cookie.isNotBlank()) {
            state.setMainCookie(dialog.platformId, cookie.trim())
            state.dismissDialog()
        }
    }
}

@Composable
private fun PlatformProxyDialog(state: AppState, dialog: Dialog.PlatformProxy) {
    var proxy by remember { mutableStateOf(dialog.current.orEmpty()) }
    val scopeLabel = when (dialog.target) {
        "parse" -> "解析代理"
        "download" -> "下载代理"
        else -> "解析与下载代理"
    }
    DialogScaffold(
        title = "$scopeLabel · ${dialog.platformName}",
        summary = "留空表示不覆盖全局代理；形如 http://127.0.0.1:7890",
        onDismiss = state::dismissDialog,
    ) {
        TextField(proxy, { proxy = it }, label = "代理地址", singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        DialogActions(confirmText = "保存", confirmEnabled = proxy.isNotBlank()) {
            state.setPlatformProxy(dialog.platformId, proxy.trim(), dialog.target)
            state.dismissDialog()
        }
    }
}
