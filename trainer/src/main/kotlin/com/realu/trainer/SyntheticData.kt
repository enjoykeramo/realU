package com.realu.trainer

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Random
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 生成模拟的通达信 vipdoc 目录(sh/sz lday *.day),用于验证安装与端到端流程。
 * 随机游走中埋入"箱体整理 → 放量启动 → 主升"的事件(部分为假突破),
 * 模型应能学到"整理期缩量 + 启动放量"等规律。数据纯属虚构,结果没有任何投资意义。
 */
object SyntheticData {

    fun tradingDays(start: LocalDate, n: Int): IntArray {
        val out = IntArray(n)
        var d = start
        var k = 0
        while (k < n) {
            if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) {
                out[k++] = d.year * 10000 + d.monthValue * 100 + d.dayOfMonth
            }
            d = d.plusDays(1)
        }
        return out
    }

    class Bars(val open: DoubleArray, val high: DoubleArray, val low: DoubleArray, val close: DoubleArray, val vol: DoubleArray)

    fun generate(days: Int, rnd: Random, limit: Double = 0.10): Bars {
        val o = DoubleArray(days); val h = DoubleArray(days); val l = DoubleArray(days); val c = DoubleArray(days); val v = DoubleArray(days)
        var price = 5.0 + rnd.nextDouble() * 30
        val baseVol = 2e6 + rnd.nextDouble() * 2e7
        var i = 0
        var regimeDrift = 0.0
        while (i < days) {
            // 事件:箱体 40 日 → 启动 → 主升 20 日(65%)/ 假突破回落(35%)
            if (i > 200 && i < days - 100 && rnd.nextDouble() < 0.006) {
                val boxTop = price * 1.06
                val boxBot = price * 0.94
                val genuine = rnd.nextDouble() < 0.65
                for (k in 0 until 40) {
                    if (i >= days) break
                    val prev = price
                    price = (price * (1 + rnd.nextGaussian() * 0.012)).coerceIn(boxBot, boxTop)
                    bar(o, h, l, c, v, i, prev, price, baseVol * (0.45 + rnd.nextDouble() * 0.2), rnd, limit); i++
                }
                if (i >= days) break
                // 启动日:放量大阳(真突破量更大、收得更强)
                val prev = price
                price *= 1 + if (genuine) 0.065 + rnd.nextDouble() * 0.03 else 0.035 + rnd.nextDouble() * 0.03
                bar(o, h, l, c, v, i, prev, price, baseVol * (if (genuine) 3.2 else 1.9) * (0.8 + rnd.nextDouble() * 0.4), rnd, limit, strongClose = genuine); i++
                val len = 22
                for (k in 0 until len) {
                    if (i >= days) break
                    val p0 = price
                    val drift = if (genuine) 0.024 else -0.008
                    price *= 1 + drift + rnd.nextGaussian() * 0.015
                    bar(o, h, l, c, v, i, p0, price, baseVol * (if (genuine) 1.8 else 0.9), rnd, limit); i++
                }
                continue
            }
            if (rnd.nextDouble() < 0.01) regimeDrift = rnd.nextGaussian() * 0.002
            val prev = price
            price *= 1 + regimeDrift + rnd.nextGaussian() * 0.022
            price = max(price, 1.0)
            bar(o, h, l, c, v, i, prev, price, baseVol * exp(rnd.nextGaussian() * 0.35), rnd, limit)
            i++
        }
        return Bars(o, h, l, c, v)
    }

    private fun bar(o: DoubleArray, h: DoubleArray, l: DoubleArray, c: DoubleArray, v: DoubleArray, i: Int,
                    prev: Double, target: Double, vol: Double, rnd: Random, limit: Double, strongClose: Boolean = false) {
        val cl = target.coerceIn(prev * (1 - limit), prev * (1 + limit))
        val op = (prev * (1 + rnd.nextGaussian() * 0.006)).coerceIn(prev * (1 - limit), prev * (1 + limit))
        val wig = if (strongClose) 0.002 else 0.012
        o[i] = op; c[i] = cl
        h[i] = min(prev * (1 + limit), max(op, cl) * (1 + rnd.nextDouble() * wig))
        l[i] = max(prev * (1 - limit), min(op, cl) * (1 - rnd.nextDouble() * 0.012))
        v[i] = vol
    }

    fun writeDay(file: File, dates: IntArray, b: Bars) {
        val buf = ByteBuffer.allocate(dates.size * 32).order(ByteOrder.LITTLE_ENDIAN)
        for (i in dates.indices) {
            val c = (b.close[i] * 100).roundToInt()
            buf.putInt(dates[i])
            buf.putInt((b.open[i] * 100).roundToInt())
            buf.putInt(max((b.high[i] * 100).roundToInt(), c))
            buf.putInt(min((b.low[i] * 100).roundToInt(), c))
            buf.putInt(c)
            buf.putFloat((b.vol[i] * b.close[i]).toFloat())
            buf.putInt(b.vol[i].toInt())
            buf.putInt(0)
        }
        file.parentFile.mkdirs()
        file.writeBytes(buf.array())
    }

    /** 返回写出的文件数。 */
    fun writeVipdoc(root: File, stocks: Int, days: Int, seed: Long): Int {
        val rnd = Random(seed)
        val dates = tradingDays(LocalDate.of(2012, 1, 4), days)
        var count = 0
        // 指数
        val idx = generate(days, Random(seed + 1), limit = 0.10)
        writeDay(File(root, "sh/lday/sh000001.day"), dates, idx); count++
        for (k in 0 until stocks) {
            val (market, code) = if (k % 2 == 0) "sh" to "60%04d".format(k) else "sz" to "00%04d".format(k)
            val listedAt = if (rnd.nextDouble() < 0.2) rnd.nextInt(days / 2) else 0
            val b = generate(days - listedAt, Random(rnd.nextLong()))
            writeDay(File(root, "$market/lday/$market$code.day"), dates.copyOfRange(listedAt, days), b)
            count++
        }
        return count
    }
}
