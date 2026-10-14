package com.realu.core.tdx

import com.realu.core.data.BarSeries
import com.realu.core.data.Dates
import com.realu.core.data.StockCode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** 解析结果:数据 + 过程中发现的问题(坏记录、乱序等),便于"数据体检"报告。 */
class ParseResult(val series: BarSeries?, val warnings: List<String>)

/**
 * 通达信 vipdoc/{sh,sz,bj}/lday 目录下的 .day 二进制日线。
 *
 * 每条记录 32 字节,小端:
 *   int 日期(yyyyMMdd) | int 开 | int 高 | int 低 | int 收(价格×100) | float 成交额(元) | int 成交量 | int 保留
 * 注意:.day 是**不复权**数据,除权缺口需另行处理(见 [ExRightsAdjuster])。
 */
object TdxDayReader {
    const val RECORD_SIZE = 32

    fun parse(bytes: ByteArray, code: StockCode, name: String = "", priceDivisor: Double = 100.0): ParseResult {
        val warnings = ArrayList<String>()
        if (bytes.size % RECORD_SIZE != 0) {
            warnings += "文件长度 ${bytes.size} 不是 32 的整数倍,末尾 ${bytes.size % RECORD_SIZE} 字节被忽略(可能正在被通达信写入或文件损坏)"
        }
        val n = bytes.size / RECORD_SIZE
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val date = IntArray(n); val open = DoubleArray(n); val high = DoubleArray(n); val low = DoubleArray(n)
        val close = DoubleArray(n); val vol = DoubleArray(n); val amt = DoubleArray(n)
        var k = 0
        var bad = 0
        var lastDate = 0
        for (r in 0 until n) {
            val p = r * RECORD_SIZE
            val d = buf.getInt(p)
            val o = buf.getInt(p + 4) / priceDivisor
            val h = buf.getInt(p + 8) / priceDivisor
            val l = buf.getInt(p + 12) / priceDivisor
            val c = buf.getInt(p + 16) / priceDivisor
            val a = buf.getFloat(p + 20).toDouble()
            val v = buf.getInt(p + 24).toLong().and(0xFFFFFFFFL).toDouble()
            if (!Dates.isValid(d) || d <= lastDate || !validBar(o, h, l, c)) {
                bad++
                continue
            }
            lastDate = d
            date[k] = d; open[k] = o; high[k] = h; low[k] = l; close[k] = c
            vol[k] = v; amt[k] = if (a.isFinite() && a >= 0) a else c * v
            k++
        }
        if (bad > 0) warnings += "跳过 $bad 条无效/重复/乱序记录"
        if (k == 0) return ParseResult(null, warnings + "没有有效记录")
        return ParseResult(
            BarSeries(code, name, date.copyOf(k), open.copyOf(k), high.copyOf(k), low.copyOf(k),
                close.copyOf(k), vol.copyOf(k), amt.copyOf(k)),
            warnings,
        )
    }

    /** 股票/指数价格精度 0.01;ETF、基金、债券为 0.001。本项目只用股票与指数。 */
    fun priceDivisorFor(code: StockCode): Double {
        val c = code.code
        val isFundLike = (code.market == "sh" && (c.startsWith("5") || c.startsWith("1"))) ||
            (code.market == "sz" && (c.startsWith("15") || c.startsWith("16") || c.startsWith("18") || c.startsWith("12")))
        return if (isFundLike) 1000.0 else 100.0
    }

    internal fun validBar(o: Double, h: Double, l: Double, c: Double): Boolean {
        if (o <= 0 || h <= 0 || l <= 0 || c <= 0) return false
        if (h < l) return false
        // 允许极小误差;明显越界视为坏数据
        val tol = h * 0.001
        return o <= h + tol && o >= l - tol && c <= h + tol && c >= l - tol
    }
}

/**
 * 通达信"数据导出"生成的文本文件(推荐导出为 **前复权**):
 * ```
 * 600000 浦发银行 日线 前复权
 *       日期	    开盘	    最高	    最低	    收盘	    成交量	    成交额
 * 2020/01/02	12.47	12.64	12.42	12.47	79740123	1010000000.00
 * ...
 * 数据来源:通达信
 * ```
 * 通达信导出的文件是 **GBK 编码**,直接按 UTF-8 读会乱码;这里先严格按 UTF-8 解码,失败再按 GBK。
 */
object TdxTxtReader {
    val GBK: Charset = Charset.forName("GBK")

    fun decode(bytes: ByteArray): String {
        var start = 0
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) start = 3
        val slice = java.nio.ByteBuffer.wrap(bytes, start, bytes.size - start)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(slice).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, start, bytes.size - start, GBK)
        }
    }

    fun parse(bytes: ByteArray, fileName: String): ParseResult {
        val warnings = ArrayList<String>()
        val text = decode(bytes)
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return ParseResult(null, listOf("空文件"))

        var code = StockCode.parse(fileName)
        var name = ""
        val head = lines.first().split(Regex("\\s+"))
        if (head.isNotEmpty() && head[0].length == 6 && head[0].all { it.isDigit() }) {
            if (code == null) code = StockCode.parse(head[0])
            if (head.size > 1 && head[1].none { it.isDigit() } && Dates.parse(head[1]) == null) name = head[1]
        }
        if (code == null) return ParseResult(null, listOf("无法从文件名或首行识别股票代码"))
        if (lines.first().contains("不复权") || lines.first().contains("后复权")) {
            warnings += "该文件不是前复权数据(${lines.first()}),除权缺口会被当作暴跌,建议重新导出为前复权"
        }

        val sep = Regex("[\\t,;\\s]+")
        val date = ArrayList<Int>(4096)
        val cols = Array(6) { ArrayList<Double>(4096) }
        var bad = 0
        var lastDate = 0
        for (line in lines) {
            val t = line.split(sep)
            if (t.size < 6) continue
            val d = Dates.parse(t[0]) ?: continue // 表头、"数据来源"等非数据行
            val nums = DoubleArray(6) { j -> t.getOrNull(j + 1)?.toDoubleOrNull() ?: Double.NaN }
            if (nums[5].isNaN()) nums[5] = nums[3] * nums[4] // 没有成交额列时用 收盘×成交量 估算
            if (d <= lastDate || nums.take(5).any { it.isNaN() } || !TdxDayReader.validBar(nums[0], nums[1], nums[2], nums[3])) {
                bad++
                continue
            }
            lastDate = d
            date += d
            for (j in 0 until 6) cols[j] += nums[j]
        }
        if (bad > 0) warnings += "跳过 $bad 条无效/重复/乱序记录"
        if (date.isEmpty()) return ParseResult(null, warnings + "没有有效数据行")
        return ParseResult(
            BarSeries(code, name, date.toIntArray(), cols[0].toDoubleArray(), cols[1].toDoubleArray(),
                cols[2].toDoubleArray(), cols[3].toDoubleArray(), cols[4].toDoubleArray(), cols[5].toDoubleArray()),
            warnings,
        )
    }
}

/**
 * .day 为不复权数据。没有除权信息(gbbq)时,用"开盘跳空超过跌停幅度"识别除权日并做前复权估计:
 * A 股正常交易日开盘不可能低于跌停价,超出部分只能来自除权除息。
 * 小额现金分红(<涨跌停幅度)识别不了,影响很小;精确复权请改用通达信导出的前复权 txt。
 */
object ExRightsAdjuster {
    fun forwardAdjust(s: BarSeries): Int {
        var events = 0
        for (i in s.size - 1 downTo 1) {
            val limit = s.limitPct(i)
            val gap = s.open[i] / s.close[i - 1] - 1.0
            // 停牌超过 1 年后复牌、新股等极端情况也可能出现大缺口,只处理向下缺口
            if (gap < -(limit + 0.01)) {
                val ratio = s.open[i] / s.close[i - 1]
                for (j in 0 until i) {
                    s.open[j] *= ratio; s.high[j] *= ratio; s.low[j] *= ratio; s.close[j] *= ratio
                    s.volume[j] /= ratio
                }
                events++
            }
        }
        return events
    }
}
