package com.realu.core.feature

import com.realu.core.data.BarSeries
import com.realu.core.data.Dates
import com.realu.core.indicator.Ind
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

enum class FeatureGroup(val label: String) {
    KLINE("K线组合"),
    TREND("趋势位置"),
    PATTERN("形态指标"),
    VOLUME("量能"),
    TURNOVER("换手率"),
    FLOW("资金/大单"),
    MARKET("市场环境"),
    META("板块属性"),
}

class FeatureDef(val name: String, val group: FeatureGroup, val label: String)

/**
 * 特征定义。**顺序即模型输入顺序**,改动任何一项都必须提升 [VERSION],
 * APP 加载模型时会校验版本与名称,防止训练/推理口径不一致。
 */
object FeatureDefs {
    const val VERSION = 1

    val ALL: List<FeatureDef> = listOf(
        // K线组合
        FeatureDef("ret1", FeatureGroup.KLINE, "当日涨幅"),
        FeatureDef("ret3", FeatureGroup.KLINE, "3日涨幅"),
        FeatureDef("ret5", FeatureGroup.KLINE, "5日涨幅"),
        FeatureDef("ret10", FeatureGroup.KLINE, "10日涨幅"),
        FeatureDef("ret20", FeatureGroup.KLINE, "20日涨幅"),
        FeatureDef("ret60", FeatureGroup.KLINE, "60日涨幅"),
        FeatureDef("body", FeatureGroup.KLINE, "实体占振幅比(阳正阴负)"),
        FeatureDef("upShadow", FeatureGroup.KLINE, "上影线占比"),
        FeatureDef("lowShadow", FeatureGroup.KLINE, "下影线占比"),
        FeatureDef("gap", FeatureGroup.KLINE, "开盘跳空"),
        FeatureDef("upDays", FeatureGroup.KLINE, "连涨天数"),
        FeatureDef("limitUp", FeatureGroup.KLINE, "当日涨停"),
        FeatureDef("limitUps10", FeatureGroup.KLINE, "近10日涨停数"),
        FeatureDef("limitUps60", FeatureGroup.KLINE, "近60日涨停数"),
        FeatureDef("engulf", FeatureGroup.KLINE, "阳包阴/反包"),
        FeatureDef("bigYang5", FeatureGroup.KLINE, "近5日大阳(>5%)数"),
        // 趋势位置
        FeatureDef("pos60", FeatureGroup.TREND, "60日区间位置"),
        FeatureDef("pos250", FeatureGroup.TREND, "250日区间位置"),
        FeatureDef("dd250", FeatureGroup.TREND, "距250日高点回撤"),
        FeatureDef("rise250", FeatureGroup.TREND, "距250日低点涨幅"),
        FeatureDef("maDev5", FeatureGroup.TREND, "偏离MA5"),
        FeatureDef("maDev20", FeatureGroup.TREND, "偏离MA20"),
        FeatureDef("maDev60", FeatureGroup.TREND, "偏离MA60"),
        FeatureDef("maDev120", FeatureGroup.TREND, "偏离MA120"),
        FeatureDef("ma20Slope", FeatureGroup.TREND, "MA20 5日斜率"),
        FeatureDef("ma60Slope", FeatureGroup.TREND, "MA60 10日斜率"),
        FeatureDef("maAlign", FeatureGroup.TREND, "均线多头排列度"),
        FeatureDef("maSpread", FeatureGroup.TREND, "均线粘合度"),
        // 形态指标
        FeatureDef("boxRange30", FeatureGroup.PATTERN, "30日箱体振幅"),
        FeatureDef("boxBreak30", FeatureGroup.PATTERN, "突破30日高点幅度"),
        FeatureDef("boxRange60", FeatureGroup.PATTERN, "60日箱体振幅"),
        FeatureDef("boxBreak60", FeatureGroup.PATTERN, "突破60日高点幅度"),
        FeatureDef("bollPctB", FeatureGroup.PATTERN, "布林%b"),
        FeatureDef("bollWidth", FeatureGroup.PATTERN, "布林带宽"),
        FeatureDef("bollWidthChg", FeatureGroup.PATTERN, "布林带宽10日变化"),
        FeatureDef("macdDif", FeatureGroup.PATTERN, "MACD DIF/价"),
        FeatureDef("macdHist", FeatureGroup.PATTERN, "MACD 柱/价"),
        FeatureDef("macdHistSlope", FeatureGroup.PATTERN, "MACD 柱3日变化"),
        FeatureDef("goldenDays", FeatureGroup.PATTERN, "MACD 金叉后天数"),
        FeatureDef("kdjJ", FeatureGroup.PATTERN, "KDJ-J"),
        FeatureDef("rsi6", FeatureGroup.PATTERN, "RSI6"),
        FeatureDef("rsi14", FeatureGroup.PATTERN, "RSI14"),
        FeatureDef("rsi6Min10", FeatureGroup.PATTERN, "近10日RSI6最低"),
        FeatureDef("atr14", FeatureGroup.PATTERN, "ATR14/价"),
        FeatureDef("volat20", FeatureGroup.PATTERN, "20日波动率"),
        // 量能
        FeatureDef("volRatio5", FeatureGroup.VOLUME, "量比(对前5日)"),
        FeatureDef("volRatio20", FeatureGroup.VOLUME, "量比(对前20日)"),
        FeatureDef("volMa5to60", FeatureGroup.VOLUME, "5日均量/60日均量"),
        FeatureDef("volQuiet", FeatureGroup.VOLUME, "启动前10日缩量程度"),
        FeatureDef("amountLog", FeatureGroup.VOLUME, "成交额(对数)"),
        FeatureDef("upDownVol20", FeatureGroup.VOLUME, "20日涨跌量比"),
        FeatureDef("pvCorr20", FeatureGroup.VOLUME, "20日量价相关"),
        FeatureDef("obvSlope20", FeatureGroup.VOLUME, "OBV 20日斜率"),
        FeatureDef("volMax60", FeatureGroup.VOLUME, "量/前60日最大量"),
        // 换手率
        FeatureDef("turnover", FeatureGroup.TURNOVER, "换手率%"),
        FeatureDef("turnover5", FeatureGroup.TURNOVER, "5日平均换手率%"),
        FeatureDef("turnRel250", FeatureGroup.TURNOVER, "相对换手(量/250日均量)"),
        // 资金/大单
        FeatureDef("mfProxy1", FeatureGroup.FLOW, "估算主力净额/均额(当日)"),
        FeatureDef("mfProxy5", FeatureGroup.FLOW, "估算主力净额占比(5日)"),
        FeatureDef("mfProxy20", FeatureGroup.FLOW, "估算主力净额占比(20日)"),
        FeatureDef("bigNet1", FeatureGroup.FLOW, "大单净量(当日,外部)"),
        FeatureDef("bigNet5", FeatureGroup.FLOW, "大单净量(5日累计,外部)"),
        // 市场环境
        FeatureDef("idxRet5", FeatureGroup.MARKET, "大盘5日涨幅"),
        FeatureDef("idxRet20", FeatureGroup.MARKET, "大盘20日涨幅"),
        FeatureDef("idxMaDev20", FeatureGroup.MARKET, "大盘偏离MA20"),
        FeatureDef("rs20", FeatureGroup.MARKET, "20日相对大盘强度"),
        FeatureDef("rs60", FeatureGroup.MARKET, "60日相对大盘强度"),
        // 板块属性
        FeatureDef("board", FeatureGroup.META, "板块(0主板1创业2科创3北交)"),
        FeatureDef("limitPct", FeatureGroup.META, "涨跌停幅度"),
        FeatureDef("listedLog", FeatureGroup.META, "上市天数(对数)"),
    )

    val NAMES: List<String> = ALL.map { it.name }
    val INDEX: Map<String, Int> = NAMES.withIndex().associate { it.value to it.index }
    val COUNT: Int = ALL.size
}

/** 可选外部数据:流通股本(用于真实换手率)、大单净量(Level-2 数据,通达信离线日线里没有)。 */
class StockExtras(
    /** 流通股本(股),按日期升序的阶梯函数。 */
    val floatDates: IntArray = IntArray(0),
    val floatShares: DoubleArray = DoubleArray(0),
    /** 大单净量(%),按日期升序。 */
    val bigNetDates: IntArray = IntArray(0),
    val bigNetValues: DoubleArray = DoubleArray(0),
) {
    fun floatSharesAt(date: Int): Double {
        if (floatDates.isEmpty()) return Double.NaN
        var p = java.util.Arrays.binarySearch(floatDates, date)
        if (p < 0) p = -p - 2
        return if (p >= 0) floatShares[p] else floatShares[0]
    }

    fun bigNetAt(date: Int): Double {
        val p = java.util.Arrays.binarySearch(bigNetDates, date)
        return if (p >= 0) bigNetValues[p] else Double.NaN
    }

    val hasBigNet: Boolean get() = bigNetDates.isNotEmpty()
}

/** 大盘指数预计算,按日期对齐到个股。 */
class IndexContext(val series: BarSeries) {
    private val c = series.close
    val ret5 = DoubleArray(c.size) { if (it >= 5) c[it] / c[it - 5] - 1 else Double.NaN }
    val ret20 = DoubleArray(c.size) { if (it >= 20) c[it] / c[it - 20] - 1 else Double.NaN }
    val ret60 = DoubleArray(c.size) { if (it >= 60) c[it] / c[it - 60] - 1 else Double.NaN }
    private val ma20 = Ind.ma(c, 20)
    val maDev20 = DoubleArray(c.size) { c[it] / ma20[it] - 1 }

    /** 个股日期对应的指数下标;个股交易而指数缺失时取之前最近一天。 */
    fun locate(date: Int): Int {
        val p = series.indexOf(date)
        return if (p >= 0) p else -p - 2
    }
}

/**
 * 一只股票在所有交易日上的特征矩阵,values[特征][日]。
 * 使用 Float 存储,单只股票 5000 根 K 线约 1.4MB。
 */
class FeatureFrame(val series: BarSeries, val values: Array<FloatArray>) {
    val size: Int get() = series.size

    fun row(i: Int, out: FloatArray = FloatArray(FeatureDefs.COUNT)): FloatArray {
        for (f in values.indices) out[f] = values[f][i]
        return out
    }

    operator fun get(name: String, i: Int): Float = values[FeatureDefs.INDEX.getValue(name)][i]

    /** 上次复牌后经过的交易日数(停牌 >20 个自然日视为长期停牌)。 */
    val barsSinceResume: IntArray = IntArray(series.size).also { arr ->
        var last = 0
        for (i in 1 until series.size) {
            if (Dates.daysBetween(series.date[i - 1], series.date[i]) > 20) last = i
            arr[i] = i - last
        }
        if (series.size > 0) arr[0] = 0
    }
}

object FeatureEngine {
    const val MAX_LISTED_BARS = 750

    /** EMA/SMA 类指标需要足够历史才能收敛;APP 端计算时至少传入这么多根 K 线。 */
    const val MIN_HISTORY_BARS = 500

    fun compute(s: BarSeries, index: IndexContext? = null, extras: StockExtras? = null): FeatureFrame {
        val n = s.size
        val o = s.open; val h = s.high; val l = s.low; val c = s.close; val v = s.volume; val a = s.amount
        val out = Array(FeatureDefs.COUNT) { FloatArray(n) { Float.NaN } }
        fun put(name: String, block: (Int) -> Double) {
            val col = out[FeatureDefs.INDEX[name] ?: error("未定义的特征 $name")]
            for (i in 0 until n) {
                val x = block(i)
                col[i] = if (x.isFinite()) x.toFloat() else Float.NaN
            }
        }
        fun ret(i: Int, k: Int) = if (i >= k) c[i] / c[i - k] - 1 else Double.NaN
        fun div(x: Double, y: Double) = if (y == 0.0 || y.isNaN()) Double.NaN else x / y

        val limitUp = BooleanArray(n) { s.isLimitUp(it) }
        val ma5 = Ind.ma(c, 5); val ma10 = Ind.ma(c, 10); val ma20 = Ind.ma(c, 20)
        val ma60 = Ind.ma(c, 60); val ma120 = Ind.ma(c, 120)
        val hh60 = Ind.hhv(h, 60); val ll60 = Ind.llv(l, 60)
        val hh250 = Ind.hhv(h, 250); val ll250 = Ind.llv(l, 250)
        val prevH30 = Ind.ref(Ind.hhv(h, 30), 1); val prevL30 = Ind.ref(Ind.llv(l, 30), 1)
        val prevH60 = Ind.ref(Ind.hhv(h, 60), 1); val prevL60 = Ind.ref(Ind.llv(l, 60), 1)
        val boll = Ind.boll(c)
        val macd = Ind.macd(c)
        val kdj = Ind.kdj(h, l, c)
        val rsi6 = Ind.rsi(c, 6); val rsi14 = Ind.rsi(c, 14)
        val rsi6Min10 = Ind.llv(DoubleArray(n) { if (rsi6[it].isNaN()) 50.0 else rsi6[it] }, 10)
        val atr = Ind.atr(h, l, c)
        val r1 = DoubleArray(n) { ret(it, 1) }
        val volat = Ind.rollingStd(r1, 20)
        val vMa5Prev = Ind.ref(Ind.ma(v, 5), 1); val vMa20Prev = Ind.ref(Ind.ma(v, 20), 1)
        val vMa5 = Ind.ma(v, 5); val vMa60 = Ind.ma(v, 60); val vMa250 = Ind.ma(v, 250)
        val vMa10Prev = Ind.ref(Ind.ma(v, 10), 1); val vMa60Prev = Ind.ref(Ind.ma(v, 60), 1)
        val vMax60Prev = Ind.ref(Ind.hhv(v, 60), 1)
        val aMa20 = Ind.ma(a, 20)
        val dv = DoubleArray(n) { if (it > 0 && v[it - 1] > 0) ln(max(v[it], 1.0) / max(v[it - 1], 1.0)) else Double.NaN }
        val pvCorr = Ind.rollingCorr(r1, dv, 20)
        val obv = DoubleArray(n).also { for (i in 1 until n) it[i] = it[i - 1] + if (c[i] > c[i - 1]) v[i] else if (c[i] < c[i - 1]) -v[i] else 0.0 }
        val vSum20 = Ind.ma(v, 20)
        // CLV 资金流估算:收盘在当日振幅中的位置 × 成交额,近似主力净额(无 Level-2 数据时的替代)
        val mf = DoubleArray(n) { val r = h[it] - l[it]; if (r <= 0) 0.0 else a[it] * ((c[it] - l[it]) - (h[it] - c[it])) / r }
        val mfSum5 = Ind.ma(mf, 5); val aSum5 = Ind.ma(a, 5); val mfSum20 = Ind.ma(mf, 20)

        put("ret1") { ret(it, 1) }
        put("ret3") { ret(it, 3) }
        put("ret5") { ret(it, 5) }
        put("ret10") { ret(it, 10) }
        put("ret20") { ret(it, 20) }
        put("ret60") { ret(it, 60) }
        put("body") { val r = h[it] - l[it]; if (r <= 0) 0.0 else (c[it] - o[it]) / r }
        put("upShadow") { val r = h[it] - l[it]; if (r <= 0) 0.0 else (h[it] - max(o[it], c[it])) / r }
        put("lowShadow") { val r = h[it] - l[it]; if (r <= 0) 0.0 else (min(o[it], c[it]) - l[it]) / r }
        put("gap") { if (it > 0) o[it] / c[it - 1] - 1 else Double.NaN }
        val upDays = IntArray(n).also { for (i in 1 until n) it[i] = if (c[i] > c[i - 1]) min(it[i - 1] + 1, 10) else 0 }
        put("upDays") { upDays[it].toDouble() }
        put("limitUp") { if (limitUp[it]) 1.0 else 0.0 }
        val luCum = IntArray(n + 1).also { for (i in 0 until n) it[i + 1] = it[i] + if (limitUp[i]) 1 else 0 }
        put("limitUps10") { (luCum[it + 1] - luCum[max(0, it - 9)]).toDouble() }
        put("limitUps60") { (luCum[it + 1] - luCum[max(0, it - 59)]).toDouble() }
        put("engulf") {
            if (it == 0) Double.NaN
            else if (c[it - 1] < o[it - 1] && c[it] > o[it] && c[it] >= o[it - 1] && o[it] <= c[it - 1]) 1.0 else 0.0
        }
        val bigYang = IntArray(n + 1).also { for (i in 0 until n) it[i + 1] = it[i] + if (r1[i] > 0.05) 1 else 0 }
        put("bigYang5") { (bigYang[it + 1] - bigYang[max(0, it - 4)]).toDouble() }

        put("pos60") { div(c[it] - ll60[it], hh60[it] - ll60[it]) }
        put("pos250") { div(c[it] - ll250[it], hh250[it] - ll250[it]) }
        put("dd250") { c[it] / hh250[it] - 1 }
        put("rise250") { c[it] / ll250[it] - 1 }
        put("maDev5") { c[it] / ma5[it] - 1 }
        put("maDev20") { c[it] / ma20[it] - 1 }
        put("maDev60") { c[it] / ma60[it] - 1 }
        put("maDev120") { c[it] / ma120[it] - 1 }
        put("ma20Slope") { if (it >= 5) ma20[it] / ma20[it - 5] - 1 else Double.NaN }
        put("ma60Slope") { if (it >= 10) ma60[it] / ma60[it - 10] - 1 else Double.NaN }
        put("maAlign") {
            if (ma120[it].isNaN()) Double.NaN
            else listOf(ma5[it] > ma10[it], ma10[it] > ma20[it], ma20[it] > ma60[it], ma60[it] > ma120[it]).count { b -> b }.toDouble()
        }
        put("maSpread") {
            val m = doubleArrayOf(ma5[it], ma10[it], ma20[it], ma60[it])
            if (m.any { x -> x.isNaN() }) Double.NaN else (m.max() - m.min()) / c[it]
        }

        put("boxRange30") { prevH30[it] / prevL30[it] - 1 }
        put("boxBreak30") { c[it] / prevH30[it] - 1 }
        put("boxRange60") { prevH60[it] / prevL60[it] - 1 }
        put("boxBreak60") { c[it] / prevH60[it] - 1 }
        put("bollPctB") { div(c[it] - boll.lower[it], boll.upper[it] - boll.lower[it]) }
        val bw = DoubleArray(n) { div(boll.upper[it] - boll.lower[it], boll.mid[it]) }
        put("bollWidth") { bw[it] }
        put("bollWidthChg") { if (it >= 10) div(bw[it], bw[it - 10]) else Double.NaN }
        put("macdDif") { if (it >= 33) macd.dif[it] / c[it] else Double.NaN }
        put("macdHist") { if (it >= 33) macd.hist[it] / c[it] else Double.NaN }
        put("macdHistSlope") { if (it >= 36) (macd.hist[it] - macd.hist[it - 3]) / c[it] else Double.NaN }
        val golden = IntArray(n).also {
            var last = -1000
            for (i in 1 until n) {
                if (macd.dif[i] > macd.dea[i] && macd.dif[i - 1] <= macd.dea[i - 1]) last = i
                it[i] = min(i - last, 60)
            }
        }
        put("goldenDays") { if (it >= 33) golden[it].toDouble() else Double.NaN }
        put("kdjJ") { if (it >= 9) kdj.j[it] else Double.NaN }
        put("rsi6") { rsi6[it] }
        put("rsi14") { rsi14[it] }
        put("rsi6Min10") { if (it >= 15) rsi6Min10[it] else Double.NaN }
        put("atr14") { atr[it] / c[it] }
        put("volat20") { volat[it] }

        put("volRatio5") { div(v[it], vMa5Prev[it]) }
        put("volRatio20") { div(v[it], vMa20Prev[it]) }
        put("volMa5to60") { div(vMa5[it], vMa60[it]) }
        put("volQuiet") { div(vMa10Prev[it], vMa60Prev[it]) }
        put("amountLog") { if (a[it] > 0) log10(a[it]) else Double.NaN }
        put("upDownVol20") {
            if (it < 20) Double.NaN else {
                var up = 0.0; var dn = 0.0
                for (j in it - 19..it) if (c[j] > c[j - 1]) up += v[j] else if (c[j] < c[j - 1]) dn += v[j]
                ln((up + 1) / (dn + 1))
            }
        }
        put("pvCorr20") { pvCorr[it] }
        put("obvSlope20") { if (it >= 20) div(obv[it] - obv[it - 20], vSum20[it] * 20) else Double.NaN }
        put("volMax60") { div(v[it], vMax60Prev[it]) }

        val turnover = DoubleArray(n) {
            val fs = extras?.floatSharesAt(s.date[it]) ?: Double.NaN
            if (fs.isNaN() || fs <= 0) Double.NaN else a[it] / c[it] / fs * 100
        }
        put("turnover") { turnover[it] }
        put("turnover5") { if (it >= 4) (it - 4..it).sumOf { j -> turnover[j] } / 5 else Double.NaN }
        put("turnRel250") { div(v[it], vMa250[it]) }

        put("mfProxy1") { div(mf[it], aMa20[it]) }
        put("mfProxy5") { div(mfSum5[it], aSum5[it]) }
        put("mfProxy20") { div(mfSum20[it], aMa20[it]) }
        if (extras != null && extras.hasBigNet) {
            val big = DoubleArray(n) { extras.bigNetAt(s.date[it]) }
            put("bigNet1") { big[it] }
            put("bigNet5") {
                if (it < 4) Double.NaN else {
                    var sum = 0.0
                    for (j in it - 4..it) { if (big[j].isNaN()) return@put Double.NaN; sum += big[j] }
                    sum
                }
            }
        }

        if (index != null) {
            val pos = IntArray(n) { index.locate(s.date[it]) }
            fun at(arr: DoubleArray, i: Int) = if (pos[i] >= 0) arr[pos[i]] else Double.NaN
            put("idxRet5") { at(index.ret5, it) }
            put("idxRet20") { at(index.ret20, it) }
            put("idxMaDev20") { at(index.maDev20, it) }
            put("rs20") { ret(it, 20) - at(index.ret20, it) }
            put("rs60") { ret(it, 60) - at(index.ret60, it) }
        }

        put("board") { s.code.board.code.toDouble() }
        put("limitPct") { s.limitPct(it) }
        // 封顶 750 根:APP 只加载最近若干年数据时,也能得到与训练相同的值
        put("listedLog") { ln(min(it + 1, MAX_LISTED_BARS).toDouble()) }

        return FeatureFrame(s, out)
    }
}
