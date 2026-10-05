package io.github.obihs2501.parsehubstudio.library

import java.io.File
import java.io.RandomAccessFile

/**
 * 只读文件头拿图片的**原始分辨率**（不解码、不加载像素）。
 *
 * 用途：查看器要在标题栏显示分辨率，并且 1:1 / 缩放百分比要按真实像素换算；
 * 预览层只有 2048，用解码尺寸会算错也会显示错。
 *
 * 支持 JPEG / PNG / GIF / WebP / BMP；解析失败返回 null（调用方回退到解码尺寸）。
 */
object ImageInfo {

    fun dimensions(file: File): Pair<Int, Int>? = runCatching {
        if (!file.isFile) return null
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(16)
            if (raf.read(head) < 4) return null
            when {
                head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> jpeg(raf)
                head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() -> png(raf)
                head[0] == 'G'.code.toByte() && head[1] == 'I'.code.toByte() -> gif(raf)
                head[0] == 'R'.code.toByte() && head[1] == 'I'.code.toByte() -> webp(raf)
                head[0] == 'B'.code.toByte() && head[1] == 'M'.code.toByte() -> bmp(raf)
                else -> null
            }
        }
    }.getOrNull()

    private fun jpeg(raf: RandomAccessFile): Pair<Int, Int>? {
        raf.seek(2)
        val seg = ByteArray(5)
        while (raf.filePointer < raf.length()) {
            if (raf.readUnsignedByte() != 0xFF) continue
            var code = raf.readUnsignedByte()
            while (code == 0xFF) code = raf.readUnsignedByte()
            when {
                // 无长度字段的标记
                code == 0xD8 || code == 0x01 || code in 0xD0..0xD7 -> Unit
                code == 0xD9 -> return null
                // SOF0..SOF15（跳过 DHT/JPG/DAC）
                code in 0xC0..0xCF && code != 0xC4 && code != 0xC8 && code != 0xCC -> {
                    raf.skipBytes(2)
                    if (raf.read(seg) < 5) return null
                    val h = ((seg[1].toInt() and 0xFF) shl 8) or (seg[2].toInt() and 0xFF)
                    val w = ((seg[3].toInt() and 0xFF) shl 8) or (seg[4].toInt() and 0xFF)
                    return if (w > 0 && h > 0) w to h else null
                }
                else -> {
                    val len = (raf.readUnsignedByte() shl 8) or raf.readUnsignedByte()
                    if (len < 2) return null
                    raf.skipBytes(len - 2)
                }
            }
        }
        return null
    }

    private fun png(raf: RandomAccessFile): Pair<Int, Int>? {
        raf.seek(16) // 8 字节签名 + 4 长度 + "IHDR"
        val b = ByteArray(8)
        if (raf.read(b) < 8) return null
        fun be(i: Int) = ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or
            ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
        val w = be(0)
        val h = be(4)
        return if (w > 0 && h > 0) w to h else null
    }

    private fun gif(raf: RandomAccessFile): Pair<Int, Int>? {
        raf.seek(6)
        val b = ByteArray(4)
        if (raf.read(b) < 4) return null
        fun le(i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
        val w = le(0)
        val h = le(2)
        return if (w > 0 && h > 0) w to h else null
    }

    private fun bmp(raf: RandomAccessFile): Pair<Int, Int>? {
        raf.seek(18)
        val b = ByteArray(8)
        if (raf.read(b) < 8) return null
        fun le32(i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
            ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)
        val w = le32(0)
        val h = kotlin.math.abs(le32(4))
        return if (w > 0 && h > 0) w to h else null
    }

    private fun webp(raf: RandomAccessFile): Pair<Int, Int>? {
        raf.seek(0)
        val b = ByteArray(30)
        if (raf.read(b) < 30) return null
        fun u8(i: Int) = b[i].toInt() and 0xFF
        fun le16(i: Int) = u8(i) or (u8(i + 1) shl 8)
        fun le24(i: Int) = u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16)
        return when (String(b, 12, 4, Charsets.US_ASCII)) {
            "VP8 " -> {
                val w = le16(26) and 0x3FFF
                val h = le16(28) and 0x3FFF
                if (w > 0 && h > 0) w to h else null
            }
            "VP8L" -> {
                val bits = u8(21) or (u8(22) shl 8) or (u8(23) shl 16) or (u8(24) shl 24)
                val w = (bits and 0x3FFF) + 1
                val h = ((bits shr 14) and 0x3FFF) + 1
                if (w > 0 && h > 0) w to h else null
            }
            "VP8X" -> {
                val w = le24(24) + 1
                val h = le24(27) + 1
                if (w > 0 && h > 0) w to h else null
            }
            else -> null
        }
    }
}
