package io.github.obihs2501.parsehubstudio

import java.awt.Font
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 字体回归测试：内置的 MiSans ttf 必须是有效的 TrueType 字体、族名为 MiSans。
 * 覆盖「资源没打进包 / 文件损坏 / 放错目录」这类只在运行时才暴露的问题。
 */
class FontLoadTest {

    private val bundled = listOf(
        "fonts/MiSans-Regular.ttf",
        "fonts/MiSans-Medium.ttf",
        "fonts/MiSans-Demibold.ttf",
    )

    @Test
    fun bundledMiSansLoads() {
        bundled.forEach { path ->
            val stream = javaClass.classLoader?.getResourceAsStream(path)
            assertTrue(stream != null, "找不到内置字体资源：$path")
            val font = stream!!.use { Font.createFont(Font.TRUETYPE_FONT, it) }
            assertTrue(
                font.family.contains("MiSans", ignoreCase = true),
                "字体族名不是 MiSans：$path → ${font.family}",
            )
        }
    }
}
