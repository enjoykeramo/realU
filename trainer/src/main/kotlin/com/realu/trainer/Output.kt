package com.realu.trainer

import java.io.File
import java.nio.charset.Charset

/**
 * CSV 输出。Excel 双击打开 CSV 时,没有 BOM 的 UTF-8 会被当成 GBK → 中文乱码。
 * 默认写 "UTF-8 + BOM"(Excel、WPS 均正确识别);也可在配置中改为 GBK。
 */
class CsvWriter(file: File, encoding: String) : AutoCloseable {
    private val out = run {
        file.parentFile?.mkdirs()
        val bom = encoding.equals("UTF-8-BOM", true)
        val cs: Charset = if (bom) Charsets.UTF_8 else Charset.forName(encoding)
        val stream = file.outputStream().buffered()
        if (bom) stream.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        stream.writer(cs).buffered()
    }

    fun row(vararg cells: Any?) {
        out.write(cells.joinToString(",") { c ->
            val s = when (c) {
                null -> ""
                is Double -> if (c.isNaN()) "" else "%.4f".format(c)
                is Float -> if (c.isNaN()) "" else "%.4f".format(c)
                else -> c.toString()
            }
            if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
        })
        out.write("\r\n")
    }

    override fun close() = out.close()
}

fun pct(v: Double, digits: Int = 1): String = if (v.isNaN()) "-" else "%.${digits}f%%".format(v * 100)
