package io.github.obihs2501.parsehubstudio

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 回归测试：本地文件必须以 `java.io.File` 作为 Coil model 才能加载。
 *
 * 背景：`File.toURI()` 会生成 `file:/C:/…`（单斜杠）形式，Coil 的 Uri 解析会吃掉盘符，
 * 导致「无法加载该媒体」。这里顺带覆盖中文 / 空格 / 方括号文件名。
 */
class LocalImageLoadTest {

    @Test
    fun localFileLoadsWithFileModel() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "parsehub-img-test").apply { mkdirs() }
        val file = File(dir, "测试 图片[1].png")
        ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB), "png", file)

        val loader = ImageLoader.Builder(PlatformContext.INSTANCE).build()
        try {
            val result = loader.execute(
                ImageRequest.Builder(PlatformContext.INSTANCE).data(file).build(),
            )
            assertTrue(result is SuccessResult, "File model 必须能加载本地图片，实际=$result")
        } finally {
            loader.shutdown()
            file.delete()
            dir.delete()
        }
    }
}
