package com.jhh.myapptools.gif

import java.io.File
import java.io.InputStream

/**
 * GIF 文件结构校验器（只读）。
 * 解析逻辑屏幕描述符与图像块，统计帧数/尺寸/循环次数，
 * 用于验证"输出文件真实有效"，而不是仅凭编码器返回码宣称成功。
 */
object GifInspector {

    data class Info(
        val width: Int,
        val height: Int,
        val frameCount: Int,
        /** 0 = 无限循环；-1 = 未声明 */
        val loopCount: Int,
    )

    fun inspect(file: File): Info? {
        if (!file.exists() || file.length() < 13L) return null
        return try {
            file.inputStream().buffered().use { parse(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun parse(input: InputStream): Info? {
        val header = ByteArray(6)
        if (readN(input, header) != 6) return null
        if (header[0] != 'G'.code.toByte() ||
            header[1] != 'I'.code.toByte() ||
            header[2] != 'F'.code.toByte()
        ) {
            return null
        }

        // Logical Screen Descriptor: width(2) height(2) packed(1) bg(1) aspect(1)
        val lsd = ByteArray(7)
        if (readN(input, lsd) != 7) return null
        val width = le16(lsd, 0)
        val height = le16(lsd, 2)
        val packed = lsd[4].toInt() and 0xFF
        if (packed and 0x80 != 0) {
            val entries = 1 shl ((packed and 0x07) + 1)
            skipFully(input, entries * 3L)
        }

        var frameCount = 0
        var loopCount = -1

        while (true) {
            val blockType = input.read()
            if (blockType < 0) break
            when (blockType) {
                0x2C -> {
                    // Image Descriptor: left(2) top(2) w(2) h(2) packed(1)
                    val descriptor = ByteArray(9)
                    if (readN(input, descriptor) != 9) break
                    val localPacked = descriptor[8].toInt() and 0xFF
                    if (localPacked and 0x80 != 0) {
                        val entries = 1 shl ((localPacked and 0x07) + 1)
                        skipFully(input, entries * 3L)
                    }
                    skipFully(input, 1L) // LZW minimum code size
                    skipSubBlocks(input)
                    frameCount++
                }

                0x21 -> {
                    val label = input.read()
                    if (label < 0) break
                    if (label == 0xFF) {
                        // Application Extension：检查 NETSCAPE2.0 循环块
                        val blockSize = input.read()
                        if (blockSize < 0) break
                        val appName = ByteArray(blockSize)
                        if (readN(input, appName) != blockSize) break
                        if (String(appName, Charsets.US_ASCII).startsWith("NETSCAPE")) {
                            val subSize = input.read()
                            if (subSize >= 3) {
                                val data = ByteArray(subSize)
                                if (readN(input, data) == subSize && data[0] == 1.toByte()) {
                                    loopCount = (data[1].toInt() and 0xFF) or
                                        ((data[2].toInt() and 0xFF) shl 8)
                                }
                            } else if (subSize > 0) {
                                skipFully(input, subSize.toLong())
                            }
                        }
                    }
                    skipSubBlocks(input)
                }

                0x3B -> break // GIF Trailer，正常结束
                else -> break // 结构损坏，停止解析
            }
        }

        if (frameCount == 0) return null
        return Info(width, height, frameCount, loopCount)
    }

    private fun skipSubBlocks(input: InputStream) {
        while (true) {
            val size = input.read()
            if (size <= 0) break
            skipFully(input, size.toLong())
        }
    }

    /** InputStream.skip 可能少跳，必须循环补足 */
    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        val scratch = ByteArray(4096)
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else if (input.read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt()) >= 0) {
                remaining -= minOf(remaining, scratch.size.toLong())
            } else {
                break
            }
        }
    }

    private fun readN(input: InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) break
            offset += read
        }
        return offset
    }

    private fun le16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
}
