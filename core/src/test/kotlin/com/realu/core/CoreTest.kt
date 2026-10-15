package com.realu.core

import com.realu.core.data.BarSeries
import com.realu.core.data.Board
import com.realu.core.data.Dates
import com.realu.core.data.StockCode
import com.realu.core.feature.ChipDistribution
import com.realu.core.feature.FeatureDefs
import com.realu.core.feature.FeatureEngine
import com.realu.core.indicator.Ind
import com.realu.core.market.MarketContext
import com.realu.core.market.SectorMap
import com.realu.core.market.TradingCalendar
import com.realu.core.model.GbdtModel
import com.realu.core.model.Tree
import com.realu.core.strategy.ExitReason
import com.realu.core.strategy.ExitRule
import com.realu.core.strategy.LabelOutcome
import com.realu.core.strategy.LabelParams
import com.realu.core.strategy.Labeler
import com.realu.core.strategy.TradeSimulator
import com.realu.core.tdx.ExRightsAdjuster
import com.realu.core.tdx.TdxDayReader
import com.realu.core.tdx.TdxTxtReader
import com.realu.core.util.Json
import com.realu.core.util.asJsonMap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTest {

    private fun series(close: DoubleArray, open: DoubleArray = close, high: DoubleArray? = null, low: DoubleArray? = null,
                       code: StockCode = StockCode("sh", "600000")): BarSeries {
        val n = close.size
        val h = high ?: DoubleArray(n) { maxOf(open[it], close[it]) }
        val l = low ?: DoubleArray(n) { minOf(open[it], close[it]) }
        val dates = IntArray(n) { Dates.plusDays(20200101, it.toLong()) }
        return BarSeries(code, "", dates, open, h, l, close, DoubleArray(n) { 1e6 }, DoubleArray(n) { 1e6 * close[it] })
    }

    @Test
    fun stockCodeParsing() {
        assertEquals(StockCode("sh", "600000"), StockCode.parse("sh600000.day"))
        assertEquals(StockCode("sz", "300750"), StockCode.parse("SZ#300750.txt"))
        assertEquals(StockCode("sz", "000001"), StockCode.parse("000001.SZ"))
        assertEquals(StockCode("bj", "830799"), StockCode.parse("830799"))
        assertEquals(Board.CHINEXT, StockCode("sz", "300750").board)
        assertEquals(Board.INDEX, StockCode("sh", "000001").board)
        assertEquals(0.10, StockCode("sz", "300750").limitPct(20200821))
        assertEquals(0.20, StockCode("sz", "300750").limitPct(20200824))
        assertNull(StockCode.parse("readme.txt"))
    }

    @Test
    fun dayFileRoundTripAndBadRecords() {
        val buf = ByteBuffer.allocate(32 * 3 + 5).order(ByteOrder.LITTLE_ENDIAN)
        fun rec(d: Int, o: Int, h: Int, l: Int, c: Int) {
            buf.putInt(d); buf.putInt(o); buf.putInt(h); buf.putInt(l); buf.putInt(c)
            buf.putFloat(1.5e8f); buf.putInt(123456); buf.putInt(0)
        }
        rec(20240102, 1000, 1100, 990, 1050)
        rec(20240101, 1000, 1100, 990, 1050) // 乱序,应跳过
        rec(20240103, 1050, 1200, 1040, 1150)
        val r = TdxDayReader.parse(buf.array(), StockCode("sh", "600000"))
        val s = assertNotNull(r.series)
        assertEquals(2, s.size)
        assertEquals(10.5, s.close[0], 1e-9)
        assertEquals(11.5, s.close[1], 1e-9)
        assertEquals(123456.0, s.volume[1])
        assertTrue(r.warnings.any { it.contains("32") })
        assertTrue(r.warnings.any { it.contains("跳过 1") })
    }

    @Test
    fun txtExportInGbk() {
        val text = "600000 浦发银行 日线 前复权\r\n      日期\t开盘\t最高\t最低\t收盘\t成交量\t成交额\r\n" +
            "2024/01/02\t10.00\t10.50\t9.90\t10.20\t1000000\t10200000.00\r\n" +
            "2024/01/03\t10.20\t10.80\t10.10\t10.70\t1500000\t16050000.00\r\n数据来源:通达信\r\n"
        val r = TdxTxtReader.parse(text.toByteArray(TdxTxtReader.GBK), "SH#600000.txt")
        val s = assertNotNull(r.series)
        assertEquals("浦发银行", s.name)
        assertEquals(2, s.size)
        assertEquals(20240103, s.date[1])
        assertEquals(10.70, s.close[1], 1e-9)
        // UTF-8 文件也能读
        assertEquals("浦发银行", TdxTxtReader.parse(text.toByteArray(Charsets.UTF_8), "SH#600000.txt").series!!.name)
    }

    @Test
    fun exRightsHeuristic() {
        // 第 3 天 10 送 10:开盘价腰斩
        // 前面补 6 根:新股前 5 日无涨跌幅,不做除权识别
        val close = DoubleArray(6) { 20.0 } + doubleArrayOf(20.4, 10.3, 10.5)
        val open = DoubleArray(6) { 20.0 } + doubleArrayOf(20.0, 10.2, 10.3)
        val s = series(close, open)
        assertEquals(1, ExRightsAdjuster.forwardAdjust(s))
        assertEquals(20.4 * 10.2 / 20.4, s.close[6], 1e-9)
        assertTrue(abs(s.close[6] - s.open[7]) < 1e-9)
    }

    @Test
    fun indicators() {
        val x = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0)
        assertTrue(Ind.ma(x, 3)[1].isNaN())
        assertEquals(4.0, Ind.ma(x, 3)[4])
        assertEquals(5.0, Ind.hhv(x, 2)[4])
        assertEquals(4.0, Ind.llv(x, 2)[4])
        val up = DoubleArray(30) { 10.0 + it }
        assertEquals(100.0, Ind.rsi(up, 6)[29], 1e-9)
    }

    @Test
    fun closeBasedStopRespectsTPlusOne() {
        // 信号日 0,买入日 1 开盘 10 元;买入日收盘 8.0 已破 −15% 线但 T+1 不能卖;次日收盘 8.4 仍在线下 → 止损
        val close = doubleArrayOf(10.0, 9.6, 9.2, 8.4, 9.0)
        val open = doubleArrayOf(10.0, 10.0, 9.6, 9.2, 8.4)
        val low = doubleArrayOf(9.9, 8.0, 7.9, 8.3, 8.4) // 盘中跌破不触发,只看收盘
        val high = doubleArrayOf(10.1, 10.1, 9.7, 9.3, 9.1)
        val s = series(close, open, high, low)
        val t = assertNotNull(TradeSimulator.simulate(s, 0, ExitRule(stopLoss = 0.15, takeProfit = 0.30, maxHoldDays = 10)))
        assertEquals(ExitReason.STOP_LOSS, t.reason)
        assertEquals(3, t.exitIndex)
        assertEquals(8.4, t.exitPrice, 1e-9)
    }

    private fun path(vararg closes: Double): BarSeries {
        val c = closes
        return series(c, DoubleArray(c.size) { if (it == 0) c[0] else c[it - 1] }, DoubleArray(c.size) { c[it] * 1.01 }, DoubleArray(c.size) { c[it] * 0.99 })
    }

    @Test
    fun targetAllowsDrawdownThenThirtyPercent() {
        val lp = LabelParams(horizon = 60, target = 0.30, maxDrawdown = 0.15, cost = 0.003)
        // 买入 10(次日开盘 = 前收 10),先回撤到 8.8(−12%,在容忍范围内),再涨到 13.1(+31%)→ 成功
        val up = mutableListOf(10.0, 10.0, 9.5, 9.0, 8.8)
        while (up.last() < 13.1) up += up.last() * 1.05
        val hit = Labeler.label(path(*up.toDoubleArray()), 0, lp)
        assertEquals(LabelOutcome.HIT, hit.outcome)
        // 回撤到 8.4(−16%)→ 失败,即使之后涨上去
        val down = mutableListOf(10.0, 10.0, 9.5, 9.0, 8.4)
        while (down.last() < 14.0) down += down.last() * 1.05
        assertEquals(LabelOutcome.STOPPED, Labeler.label(path(*down.toDoubleArray()), 0, lp).outcome)
        // 不设回撤上限时同一路径成功
        assertEquals(LabelOutcome.HIT, Labeler.label(path(*down.toDoubleArray()), 0, lp.copy(maxDrawdown = 0.0)).outcome)
        // 60 日内没涨到 30% → 失败;数据不足 60 日且未达标 → 未完成,不作为样本
        val flat = DoubleArray(80) { 10.0 + (it % 3) * 0.1 }
        assertEquals(LabelOutcome.EXPIRED, Labeler.label(path(*flat), 0, lp).outcome)
        assertEquals(LabelOutcome.NO_DATA, Labeler.label(path(*flat.copyOf(30)), 0, lp).outcome)
    }

    @Test
    fun trailingStopUsesPreviousPeak() {
        val n = 12
        val close = DoubleArray(n) { if (it <= 6) 10.0 * (1 + 0.05 * it) else 13.0 - (it - 6) * 0.5 }
        val s = series(close, close.copyOf())
        val t = assertNotNull(TradeSimulator.simulate(s, 0, ExitRule(stopLoss = 0.08, takeProfit = 0.0, trailingActivate = 0.15, trailingDrawdown = 0.10, maxHoldDays = 30)))
        assertEquals(ExitReason.TRAILING, t.reason)
        // 买入 10.5,最高收盘 13.0,回撤 10% 线 11.7;第 9 天收在 11.5 触发,按收盘 11.5 成交
        assertEquals(9, t.exitIndex)
        assertEquals(11.5 / 10.5 - 1, t.ret, 1e-9)
    }

    @Test
    fun chipDistributionBehaves() {
        val n = 400
        val c = DoubleArray(n) { if (it < 300) 10.0 + (it % 5) * 0.05 else 10.0 * Math.pow(1.01, (it - 300).toDouble()) }
        val s = series(c, c.copyOf(), DoubleArray(n) { c[it] * 1.01 }, DoubleArray(n) { c[it] * 0.99 })
        val ch = ChipDistribution.compute(s, null)
        val winner = ch[0]
        assertTrue(winner[299] in 0f..1f)
        assertTrue(winner[399] > 0.9f, "持续上涨后获利盘应接近 100%:${winner[399]}")
        assertTrue(ch[2][299] < 0.05f, "横盘后筹码应高度集中:${ch[2][299]}")
        assertTrue(ch[2][399] > ch[2][299], "上涨后筹码发散")
    }

    @Test
    fun marketSentimentAndSectorStats() {
        val cal = TradingCalendar(IntArray(40) { Dates.plusDays(20200101, it.toLong()) })
        fun mk(code: String, luDays: Set<Int>): BarSeries {
            val c = DoubleArray(40); var p = 10.0
            for (i in 0 until 40) { p *= if (i in luDays) 1.10 else 1.001; c[i] = Math.round(p * 100) / 100.0 }
            return series(c, c.copyOf(), DoubleArray(40) { c[it] * 1.0 }, DoubleArray(40) { c[it] * 0.98 }, StockCode("sh", code))
        }
        val a = mk("600001", setOf(30, 31)); val b = mk("600002", setOf(31)); val d = mk("600003", emptySet())
        val sectors = SectorMap.static("industry", mapOf("sh600001" to 0, "sh600002" to 0, "sh600003" to 0), emptyList(), emptyMap())
        val ctx = MarketContext.fromSeries(cal, sequenceOf(a, b, d), sectors)
        val m = ctx.marketAt(cal.dates[31])
        assertEquals(200f / 3, m.getValue("mktLimitUpPct"), 1e-3f)   // 3 只中 2 只涨停
        assertEquals(2f, m.getValue("mktMaxStreak"))                   // 600001 两连板
        assertEquals(1f, m.getValue("mktPromote"))                     // 昨日 1 只涨停,今日晋级
        val v = ctx.view(a)
        assertEquals(2f, v.getValue("secLimitUps")[31])
        assertEquals(1f, v.getValue("stkSecRank20")[35])              // 600001 板块内涨幅第一
        // 截面数据只用当日及以前:截断后重算,第 31 天的值不变
        val cut = MarketContext.fromSeries(TradingCalendar(cal.dates.copyOf(32)), sequenceOf(a.slice(0, 32), b.slice(0, 32), d.slice(0, 32)), sectors)
        assertEquals(m, cut.marketAt(cal.dates[31]))
    }

    @Test
    fun oneWordLimitUpIsNotBuyable() {
        val pre = DoubleArray(6) { 10.0 }
        val close = pre + doubleArrayOf(11.0, 11.5)
        val open = pre + doubleArrayOf(11.0, 11.0)
        val s = series(close, open, pre + doubleArrayOf(11.0, 11.6), pre + doubleArrayOf(11.0, 11.0))
        assertTrue(s.isOneWordLimitUp(6))
        assertNull(TradeSimulator.simulate(s, 5, ExitRule()))
        // 新股上市前 5 日的大涨不算涨停
        assertFalse(series(doubleArrayOf(10.0, 14.4), doubleArrayOf(10.0, 14.4)).isLimitUp(1))
    }

    @Test
    fun featuresHaveExpectedShapeAndNoFutureLeak() {
        val n = 400
        val rnd = java.util.Random(1)
        val close = DoubleArray(n).also { var p = 10.0; for (i in 0 until n) { p *= 1 + rnd.nextGaussian() * 0.02; it[i] = p } }
        val s = series(close, close.copyOf())
        val full = FeatureEngine.compute(s)
        assertEquals(FeatureDefs.COUNT, full.values.size)
        // 截断到第 300 天重新计算,第 299 天的特征必须完全相同(不使用未来数据)
        val cut = FeatureEngine.compute(s.slice(0, 300))
        for (f in 0 until FeatureDefs.COUNT) {
            val a = full.values[f][299]; val b = cut.values[f][299]
            assertTrue((a.isNaN() && b.isNaN()) || a == b, "特征 ${FeatureDefs.NAMES[f]} 使用了未来数据:$a vs $b")
        }
    }

    @Test
    fun jsonAndTreeRoundTrip() {
        val tree = Tree(intArrayOf(0, -1, -1), doubleArrayOf(0.5, 0.0, 0.0), booleanArrayOf(true, false, false),
            intArrayOf(1, -1, -1), intArrayOf(2, -1, -1), doubleArrayOf(0.0, -0.3, 0.4))
        val text = Json.write(tree.toJson())
        val back = Tree.fromJson(Json.parse(text).asJsonMap())
        val m = GbdtModel(0.1, listOf(back))
        val x = FloatArray(FeatureDefs.COUNT)
        x[0] = 0.4f; assertEquals(0.1 - 0.3, m.rawScore(x), 1e-6)
        x[0] = 0.6f; assertEquals(0.1 + 0.4, m.rawScore(x), 1e-6)
        x[0] = Float.NaN; assertEquals(0.1 - 0.3, m.rawScore(x), 1e-6) // 缺失值走左
        val (bias, contrib) = m.contributions(floatArrayOf(0.6f) + FloatArray(FeatureDefs.COUNT - 1))
        assertEquals(m.rawScore(floatArrayOf(0.6f) + FloatArray(FeatureDefs.COUNT - 1)), bias + contrib.sum(), 1e-9)
        assertFalse(Json.write(mapOf("a" to Double.NaN)).contains("NaN"))
        assertEquals("中文\n\"x\"", (Json.parse(Json.write(mapOf("s" to "中文\n\"x\""))).asJsonMap())["s"])
    }
}
