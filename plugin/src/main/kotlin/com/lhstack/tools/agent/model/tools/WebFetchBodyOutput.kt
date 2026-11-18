package com.lhstack.tools.agent.model.tools

import java.io.RandomAccessFile
import java.nio.file.Path

/** 文件保存原始字节；仅给模型的超限预览以 UTF-8 文本显示。 */
internal object WebFetchBodyOutput {
    fun preview(path: Path, size: Long, limit: Int): String = RandomAccessFile(path.toFile(), "r").use { file ->
        val head = ByteArray(limit / 2 + 1).also(file::readFully)
        var headEnd = head.lastIndex
        while (headEnd > 0 && head[headEnd].isContinuation()) headEnd--
        val tail = ByteArray(limit - limit / 2)
        file.seek(size - tail.size)
        file.readFully(tail)
        val tailStart = tail.indexOfFirst { !it.isContinuation() }.let { if (it < 0) tail.size else it }
        listOf(
            String(head, 0, headEnd, Charsets.UTF_8),
            "[响应正文超过 $limit 字节，已接收 $size 字节；仅展示首尾摘录，完整正文见文件。]",
            String(tail, tailStart, tail.size - tailStart, Charsets.UTF_8),
        ).filter(String::isNotEmpty).joinToString("\n\n")
    }

    private fun Byte.isContinuation(): Boolean = toInt() and 0xc0 == 0x80
}
