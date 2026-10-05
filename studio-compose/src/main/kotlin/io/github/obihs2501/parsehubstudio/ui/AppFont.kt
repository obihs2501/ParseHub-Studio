package io.github.obihs2501.parsehubstudio.ui

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font as platformFont
import top.yukonga.miuix.kmp.theme.TextStyles
import java.io.File

/**
 * 界面字体。
 *
 * 优先级：**本机安装的 PingFang SC**（用户偏好；Apple 字体不随包分发，只在本机存在时加载）
 * → **内置 MiSans**（官方 MiSans.zip 的 Regular / Medium / Demibold，随 app 打包，
 * 任何机器都有一致的字形；MiSans 授权允许免费商用与随软件分发）。
 *
 * 生效方式（Compose 的 `FontFamily.Resolver` 是 sealed，无法自定义解析器，所以走两条路）：
 * 1. [textStyles] 把 miuix 的整套 `textStyles` 换成带字体族的版本 —— 覆盖所有
 *    `style = MiuixTheme.textStyles.X` 的地方（见 Main.kt 注入 `MiuixTheme(textStyles = …)`）；
 * 2. 少数直接写 `fontSize` 的 `Text` 显式带上 `fontFamily = AppFont.family`。
 */
object AppFont {

    private const val DIR = "fonts/"

    /** 内置 MiSans 字重（官方 MiSans.zip → MiSans/ttf/）。 */
    private val bundled = listOf(
        "MiSans-Regular.ttf" to FontWeight.Normal,
        "MiSans-Medium.ttf" to FontWeight.Medium,
        "MiSans-Demibold.ttf" to FontWeight.Bold,
    )

    /** 界面字体族（懒加载：首次用到时读资源 / 落临时文件）。 */
    val family: FontFamily by lazy { systemPingFang() ?: bundledMiSans() ?: FontFamily.Default }

    /** miuix 全套 textStyles → 换成界面字体族。 */
    fun textStyles(base: TextStyles): TextStyles = base.copy(
        main = base.main.copy(fontFamily = family),
        paragraph = base.paragraph.copy(fontFamily = family),
        body1 = base.body1.copy(fontFamily = family),
        body2 = base.body2.copy(fontFamily = family),
        button = base.button.copy(fontFamily = family),
        footnote1 = base.footnote1.copy(fontFamily = family),
        footnote2 = base.footnote2.copy(fontFamily = family),
        headline1 = base.headline1.copy(fontFamily = family),
        headline2 = base.headline2.copy(fontFamily = family),
        subtitle = base.subtitle.copy(fontFamily = family),
        title1 = base.title1.copy(fontFamily = family),
        title2 = base.title2.copy(fontFamily = family),
        title3 = base.title3.copy(fontFamily = family),
        title4 = base.title4.copy(fontFamily = family),
    )

    /** 本机安装的 PingFang SC（用户级字体目录或系统字体目录）。 */
    private fun systemPingFang(): FontFamily? {
        val dirs = listOfNotNull(
            System.getenv("LOCALAPPDATA")?.let { File(it, "Microsoft/Windows/Fonts") },
            System.getenv("WINDIR")?.let { File(it, "Fonts") },
        ).filter { it.isDirectory }
        val regular = dirs.firstNotNullOfOrNull { File(it, "PingFangSC-Regular.otf").takeIf(File::isFile) } ?: return null
        val medium = dirs.firstNotNullOfOrNull { File(it, "PingFangSC-Medium.otf").takeIf(File::isFile) }
        return FontFamily(
            buildList {
                add(platformFont(regular, FontWeight.Normal))
                medium?.let { add(platformFont(it, FontWeight.Medium)) }
            },
        )
    }

    /** 内置 MiSans：resources 里的 ttf 先落到临时文件（Compose 的 `Font(File)` 只吃文件）。 */
    private fun bundledMiSans(): FontFamily? {
        val fonts = bundled.mapNotNull { (name, weight) ->
            materialize(DIR + name)?.let { platformFont(it, weight) }
        }
        return if (fonts.isEmpty()) null else FontFamily(fonts)
    }

    private fun materialize(resource: String): File? {
        val target = File(System.getProperty("java.io.tmpdir"), "parsehub-fonts/" + resource.substringAfterLast('/'))
        if (target.isFile && target.length() > 0L) return target
        return runCatching {
            val bytes = AppFont::class.java.classLoader?.getResourceAsStream(resource)?.use { it.readBytes() }
                ?: return@runCatching null
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            target
        }.getOrNull()
    }
}
