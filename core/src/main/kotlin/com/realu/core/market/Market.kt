package com.realu.core.market

import com.realu.core.data.BarSeries
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/** 全市场交易日历(通常取上证指数的交易日)。 */
class TradingCalendar(val dates: IntArray) {
    val size: Int get() = dates.size

    fun indexOf(d: Int): Int = java.util.Arrays.binarySearch(dates, d).let { if (it >= 0) it else -1 }

    /** 第一个 ≥ d 的下标(都小于 d 时返回 size)。 */
    fun ceilIndex(d: Int): Int = java.util.Arrays.binarySearch(dates, d).let { if (it >= 0) it else -it - 1 }
}

/**
 * 板块归属。
 * - 行业(industry):来自通达信 hq_cache/tdxhy.cfg,静态,只有一段;
 * - 聚类板块(cluster):只有 vipdoc 时,按过去 250 个交易日的收益相关性把股票聚成若干组,每年重算一次,
 *   第 p 段从 periodStarts[p] 起生效,只用该日期之前的数据计算,无未来函数;
 * - 概念(concept):来自 hq_cache/block_gn.dat,静态多归属。
 * groupPeriod[g] 给出组号 g 属于哪一段,不同段的组号互不重复。
 */
class SectorMap(
    val source: String,
    val periodStarts: IntArray,
    private val groups: Map<String, IntArray>,
    val groupPeriod: IntArray,
    val conceptNames: List<String> = emptyList(),
    private val concepts: Map<String, IntArray> = emptyMap(),
    val groupNames: List<String> = emptyList(),
) {
    val groupCount: Int get() = groupPeriod.size
    val hasConcepts: Boolean get() = conceptNames.isNotEmpty()

    fun period(date: Int): Int {
        var p = java.util.Arrays.binarySearch(periodStarts, date)
        if (p < 0) p = -p - 2
        return p
    }

    fun groupOf(symbol: String, date: Int): Int {
        val p = period(date)
        if (p < 0) return -1
        val arr = groups[symbol] ?: return -1
        return if (p < arr.size) arr[p] else -1
    }

    fun groupInPeriod(symbol: String, p: Int): Int = groups[symbol]?.getOrNull(p) ?: -1

    fun conceptsOf(symbol: String): IntArray = concepts[symbol] ?: EMPTY

    fun symbols(): Set<String> = groups.keys + concepts.keys

    /** 最后一段的归属(导出给 APP:APP 只需要当前的板块划分)。 */
    fun latest(): SectorMap {
        val p = periodStarts.size - 1
        val ids = HashMap<Int, Int>()
        val g = HashMap<String, IntArray>()
        for ((sym, arr) in groups) {
            val old = arr.getOrNull(p) ?: -1
            if (old < 0) continue
            g[sym] = intArrayOf(ids.getOrPut(old) { ids.size })
        }
        val names = if (groupNames.isEmpty()) emptyList() else {
            val n = Array(ids.size) { "" }
            for ((old, new) in ids) n[new] = groupNames.getOrElse(old) { "" }
            n.toList()
        }
        return SectorMap(source, intArrayOf(0), g, IntArray(ids.size), conceptNames, concepts, names)
    }

    fun groupsTable(): Map<String, Int> = groups.mapValues { it.value.lastOrNull() ?: -1 }.filterValues { it >= 0 }
    fun conceptsTable(): Map<String, IntArray> = concepts

    companion object {
        private val EMPTY = IntArray(0)

        /** 由 APP 导入的"代码 → 组号""代码 → 概念号列表"重建(单段、静态)。 */
        fun static(source: String, groups: Map<String, Int>, conceptNames: List<String>, concepts: Map<String, IntArray>,
                   groupNames: List<String> = emptyList()): SectorMap {
            val n = (groups.values.maxOrNull() ?: -1) + 1
            return SectorMap(source, intArrayOf(0), groups.mapValues { intArrayOf(it.value) }, IntArray(n), conceptNames, concepts, groupNames)
        }
    }
}

/**
 * 逐只股票累积全市场截面数据(线程安全,可并行 add),最后 [build] 出 [MarketContext]。
 * 每只股票只保留对齐到日历的收盘价(float)与状态位,5000 只 × 4000 日约 120MB;build 之后即可释放。
 */
class MarketContextBuilder(val calendar: TradingCalendar) {

    class Aligned(val symbol: String, val first: Int, val close: FloatArray, val flags: ByteArray, val streak: ByteArray) {
        val end: Int get() = first + close.size
        fun traded(ci: Int) = ci in first until end && flags[ci - first].toInt() and TRADED != 0
        fun ret(ci: Int): Float = if (ci > first && ci < end) close[ci - first] / close[ci - first - 1] - 1f else Float.NaN
        fun retN(ci: Int, n: Int): Float = if (ci - n >= first && ci < end) close[ci - first] / close[ci - first - n] - 1f else Float.NaN
    }

    private val stocks = ConcurrentHashMap<String, Aligned>()

    fun add(s: BarSeries) {
        if (!s.code.isAStock || s.size < 2) return
        var first = -1
        for (i in 0 until s.size) { val ci = calendar.indexOf(s.date[i]); if (ci >= 0) { first = ci; break } }
        if (first < 0) return
        val len = calendar.size - first
        val close = FloatArray(len)
        val flags = ByteArray(len)
        val streak = ByteArray(len)
        // 自身 K 线上的 MA20 与 60 日最高收盘
        val n = s.size
        val ma20 = DoubleArray(n) { Double.NaN }
        var sum = 0.0
        for (i in 0 until n) { sum += s.close[i]; if (i >= 20) sum -= s.close[i - 20]; if (i >= 19) ma20[i] = sum / 20 }
        val prevHigh60 = com.realu.core.indicator.Ind.ref(com.realu.core.indicator.Ind.hhv(s.close, 60), 1)
        var run = 0
        var last = Float.NaN
        var bi = 0
        for (k in 0 until len) {
            val ci = first + k
            while (bi < n && s.date[bi] < calendar.dates[ci]) bi++
            if (bi < n && s.date[bi] == calendar.dates[ci]) {
                val i = bi
                last = s.close[i].toFloat()
                var f = TRADED
                val lu = s.isLimitUp(i)
                if (lu) { f = f or LIMIT_UP; run = min(run + 1, 127) } else run = 0
                if (s.isLimitDown(i)) f = f or LIMIT_DOWN
                if (s.isBrokenLimitUp(i)) f = f or BROKEN
                if (!ma20[i].isNaN() && s.close[i] > ma20[i]) f = f or ABOVE_MA20
                if (i >= 60 && s.close[i] >= prevHigh60[i]) f = f or NEW_HIGH
                flags[k] = f.toByte()
                streak[k] = run.toByte()
            }
            close[k] = last // 停牌日沿用前收盘
        }
        stocks[s.code.symbol] = Aligned(s.code.symbol, first, close, flags, streak)
    }

    val size: Int get() = stocks.size
    fun symbols(): List<String> = stocks.keys.sorted()
    fun aligned(symbol: String): Aligned? = stocks[symbol]

    fun build(sectors: SectorMap?, minMembers: Int = 3): MarketContext {
        val nCal = calendar.size
        val all = stocks.values.sortedBy { it.symbol }

        // ---------- 1. 全市场情绪 ----------
        val traded = IntArray(nCal); val lu = IntArray(nCal); val ld = IntArray(nCal); val broken = IntArray(nCal)
        val maxStreak = IntArray(nCal); val prevLu = IntArray(nCal); val promote = IntArray(nCal)
        val premium = DoubleArray(nCal); val up = IntArray(nCal); val above = IntArray(nCal); val newHigh = IntArray(nCal)
        for (a in all) {
            for (k in a.close.indices) {
                val f = a.flags[k].toInt()
                if (f and TRADED == 0) continue
                val ci = a.first + k
                traded[ci]++
                if (f and LIMIT_UP != 0) { lu[ci]++; maxStreak[ci] = max(maxStreak[ci], a.streak[k].toInt()) }
                if (f and LIMIT_DOWN != 0) ld[ci]++
                if (f and BROKEN != 0) broken[ci]++
                if (f and ABOVE_MA20 != 0) above[ci]++
                if (f and NEW_HIGH != 0) newHigh[ci]++
                if (k > 0) {
                    val r = a.close[k] / a.close[k - 1] - 1f
                    if (r > 0) up[ci]++
                    if (a.flags[k - 1].toInt() and LIMIT_UP != 0) {
                        prevLu[ci]++
                        premium[ci] += r.toDouble()
                        if (f and LIMIT_UP != 0) promote[ci]++
                    }
                }
            }
        }
        fun ratio(a: IntArray, b: IntArray) = FloatArray(nCal) { if (b[it] > 0) a[it].toFloat() / b[it] else Float.NaN }
        val luPct = FloatArray(nCal) { if (traded[it] > 0) lu[it] * 100f / traded[it] else Float.NaN }
        val market = linkedMapOf(
            "mktLimitUpPct" to luPct,
            "mktLimitDownPct" to FloatArray(nCal) { if (traded[it] > 0) ld[it] * 100f / traded[it] else Float.NaN },
            "mktLimitUp5" to FloatArray(nCal) { if (it < 4) Float.NaN else (it - 4..it).map { j -> luPct[j] }.average().toFloat() },
            "mktBrokenRate" to FloatArray(nCal) { val d = lu[it] + broken[it]; if (d > 0) broken[it].toFloat() / d else Float.NaN },
            "mktMaxStreak" to FloatArray(nCal) { if (traded[it] > 0) maxStreak[it].toFloat() else Float.NaN },
            "mktPromote" to ratio(promote, prevLu),
            "mktPremium" to FloatArray(nCal) { if (prevLu[it] > 0) (premium[it] / prevLu[it]).toFloat() else Float.NaN },
            "mktAdvRatio" to ratio(up, traded),
            "mktAboveMa20" to ratio(above, traded),
            "mktNewHighPct" to FloatArray(nCal) { if (traded[it] > 0) newHigh[it] * 100f / traded[it] else Float.NaN },
        )

        // ---------- 2. 板块(行业/聚类)与概念 ----------
        var groupStats: Array<GroupStats?> = emptyArray()
        var conceptStats: Array<GroupStats?> = emptyArray()
        val stockRank = HashMap<String, ByteArray>()
        if (sectors != null && sectors.groupCount > 0) {
            val nP = sectors.periodStarts.size
            val pStart = IntArray(nP) { calendar.ceilIndex(sectors.periodStarts[it]) }
            val pEnd = IntArray(nP) { if (it + 1 < nP) pStart[it + 1] else nCal }
            val members = Array(sectors.groupCount) { ArrayList<Aligned>() }
            for (a in all) for (p in 0 until nP) {
                val g = sectors.groupInPeriod(a.symbol, p)
                if (g in members.indices) members[g] += a
            }
            groupStats = Array(sectors.groupCount) { g ->
                val p = sectors.groupPeriod[g]
                if (p !in 0 until nP || members[g].size < minMembers || pStart[p] >= pEnd[p]) null
                else GroupStats.compute(members[g], max(0, pStart[p] - LOOKBACK), pEnd[p], minMembers)
            }
            rankAcross(groupStats, nCal) { g -> sectors.groupPeriod[g].let { p -> if (p in 0 until nP) pStart[p] until pEnd[p] else IntRange.EMPTY } }

            // 个股在本板块内的 20 日涨幅排名(龙头程度)
            for (a in all) stockRank[a.symbol] = ByteArray(a.close.size) { NA_RANK }
            for (p in 0 until nP) for (g in members.indices) {
                if (sectors.groupPeriod[g] != p || groupStats[g] == null) continue
                val mem = members[g]
                val vals = FloatArray(mem.size)
                val order = arrayOfNulls<Int>(mem.size)
                for (ci in pStart[p] until pEnd[p]) {
                    var cnt = 0
                    for ((m, a) in mem.withIndex()) {
                        val v = if (a.traded(ci)) a.retN(ci, 20) else Float.NaN
                        vals[m] = v
                        if (!v.isNaN()) order[cnt++] = m
                    }
                    if (cnt < minMembers) continue
                    val sorted = order.copyOf(cnt).sortedBy { vals[it!!] }
                    for ((r, m) in sorted.withIndex()) {
                        val a = mem[m!!]
                        stockRank.getValue(a.symbol)[ci - a.first] = (r * 200 / max(1, cnt - 1)).toByte()
                    }
                }
            }
        }
        if (sectors != null && sectors.hasConcepts) {
            val members = Array(sectors.conceptNames.size) { ArrayList<Aligned>() }
            for (a in all) for (c in sectors.conceptsOf(a.symbol)) if (c in members.indices) members[c] += a
            conceptStats = Array(members.size) { c -> if (members[c].size < minMembers) null else GroupStats.compute(members[c], 0, nCal, minMembers) }
            rankAcross(conceptStats, nCal) { 0 until nCal }
        }
        return MarketContext(calendar, sectors, market, groupStats, conceptStats, stockRank)
    }

    /** 每个交易日,对当日处于有效期内的各组按 5 日涨幅排百分位。 */
    private fun rankAcross(stats: Array<GroupStats?>, nCal: Int, active: (Int) -> IntRange) {
        val ranges = stats.indices.map { if (stats[it] == null) IntRange.EMPTY else active(it) }
        val vals = ArrayList<Pair<Int, Float>>()
        for (ci in 0 until nCal) {
            vals.clear()
            for (g in stats.indices) {
                val st = stats[g] ?: continue
                if (ci !in ranges[g]) continue
                val v = st.ret5At(ci)
                if (!v.isNaN()) vals += g to v
            }
            if (vals.size < 2) continue
            vals.sortBy { it.second }
            for ((r, gv) in vals.withIndex()) stats[gv.first]!!.setRank5(ci, r.toFloat() / (vals.size - 1))
        }
    }

    companion object {
        const val TRADED = 1
        const val LIMIT_UP = 2
        const val LIMIT_DOWN = 4
        const val BROKEN = 8
        const val ABOVE_MA20 = 16
        const val NEW_HIGH = 32
        /** 板块指数在有效期开始前多算的天数,保证期初就有 5/20 日涨幅。 */
        const val LOOKBACK = 25
        const val NA_RANK: Byte = -1
    }
}

/** 一个板块/概念在 [start, end) 日历区间内的等权指数统计。 */
class GroupStats(
    val start: Int,
    val ret5: FloatArray,
    val ret20: FloatArray,
    val limitUps: FloatArray,
    val limitUps5: FloatArray,
    val aboveMa20: FloatArray,
    private val rank5: FloatArray,
) {
    private fun at(arr: FloatArray, ci: Int) = (ci - start).let { if (it in arr.indices) arr[it] else Float.NaN }
    fun ret5At(ci: Int) = at(ret5, ci)
    fun ret20At(ci: Int) = at(ret20, ci)
    fun limitUpsAt(ci: Int) = at(limitUps, ci)
    fun limitUps5At(ci: Int) = at(limitUps5, ci)
    fun aboveAt(ci: Int) = at(aboveMa20, ci)
    fun rank5At(ci: Int) = at(rank5, ci)
    internal fun setRank5(ci: Int, v: Float) { val k = ci - start; if (k in rank5.indices) rank5[k] = v }

    companion object {
        fun compute(members: List<MarketContextBuilder.Aligned>, start: Int, end: Int, minMembers: Int): GroupStats {
            val len = end - start
            val idx = DoubleArray(len)
            val lu = FloatArray(len); val ab = FloatArray(len)
            val valid = BooleanArray(len)
            var level = 1.0
            for (k in 0 until len) {
                val ci = start + k
                var sum = 0.0; var cnt = 0; var l = 0; var a = 0
                for (m in members) {
                    if (!m.traded(ci)) continue
                    val f = m.flags[ci - m.first].toInt()
                    if (f and MarketContextBuilder.LIMIT_UP != 0) l++
                    if (f and MarketContextBuilder.ABOVE_MA20 != 0) a++
                    val r = m.ret(ci)
                    if (!r.isNaN()) { sum += r.coerceIn(-0.35f, 0.35f); cnt++ }
                }
                if (cnt >= minMembers) { level *= 1 + sum / cnt; valid[k] = true }
                idx[k] = level
                lu[k] = l.toFloat()
                ab[k] = if (cnt > 0) a.toFloat() / cnt else Float.NaN
            }
            fun retN(n: Int) = FloatArray(len) { k -> if (k >= n && valid[k]) (idx[k] / idx[k - n] - 1).toFloat() else Float.NaN }
            val lu5 = FloatArray(len) { k -> if (k < 4) Float.NaN else (k - 4..k).sumOf { lu[it].toDouble() }.toFloat() }
            return GroupStats(start, retN(5), retN(20), lu, lu5, ab, FloatArray(len) { Float.NaN })
        }
    }
}

/**
 * 全市场截面数据:市场情绪(按日)、板块/概念强度(按组按日)、个股板块内排名。
 * [view] 把这些数据对齐到某只股票的 K 线上,供 FeatureEngine 使用。
 */
class MarketContext(
    val calendar: TradingCalendar,
    val sectors: SectorMap?,
    private val market: Map<String, FloatArray>,
    private val groups: Array<GroupStats?>,
    private val concepts: Array<GroupStats?>,
    private val stockRank: Map<String, ByteArray>,
) {
    /** 某个日期全市场情绪指标(展示用)。 */
    fun marketAt(date: Int): Map<String, Float> {
        val ci = calendar.indexOf(date)
        return market.mapValues { if (ci >= 0) it.value[ci] else Float.NaN }
    }

    fun view(s: BarSeries): Map<String, FloatArray> {
        val n = s.size
        val out = LinkedHashMap<String, FloatArray>()
        val ci = IntArray(n) { calendar.indexOf(s.date[it]) }
        for ((name, arr) in market) out[name] = FloatArray(n) { if (ci[it] >= 0) arr[ci[it]] else Float.NaN }

        val sym = s.code.symbol
        val sec = sectors
        if (sec != null && groups.isNotEmpty()) {
            val names = listOf("secRet5", "secRet20", "secRank5", "secLimitUps", "secLimitUps5", "secAboveMa20", "stkSecRank20")
            val cols = names.map { FloatArray(n) { Float.NaN } }
            val rank = stockRank[sym]
            val first = rank?.let { calendar.size - it.size }
            for (i in 0 until n) {
                val c = ci[i]
                if (c < 0) continue
                val g = sec.groupOf(sym, s.date[i])
                val st = groups.getOrNull(g) ?: continue
                cols[0][i] = st.ret5At(c); cols[1][i] = st.ret20At(c); cols[2][i] = st.rank5At(c)
                cols[3][i] = st.limitUpsAt(c); cols[4][i] = st.limitUps5At(c); cols[5][i] = st.aboveAt(c)
                if (rank != null && first != null && c >= first) {
                    val r = rank[c - first].toInt() and 0xFF // 0..200 按无符号读取,255 = 缺失
                    if (r != 255) cols[6][i] = r / 200f
                }
            }
            names.forEachIndexed { k, nm -> out[nm] = cols[k] }
        }
        if (sec != null && concepts.isNotEmpty()) {
            val mine = sec.conceptsOf(sym).filter { it in concepts.indices && concepts[it] != null }
            val maxRet = FloatArray(n) { Float.NaN }; val hot = FloatArray(n) { Float.NaN }; val maxLu = FloatArray(n) { Float.NaN }
            if (mine.isNotEmpty()) for (i in 0 until n) {
                val c = ci[i]
                if (c < 0) continue
                var mr = Float.NaN; var h = 0; var ml = Float.NaN; var any = false
                for (k in mine) {
                    val st = concepts[k]!!
                    val r = st.ret5At(c)
                    if (!r.isNaN()) { any = true; mr = if (mr.isNaN()) r else max(mr, r) }
                    if (st.rank5At(c) >= 0.9f) h++
                    val l = st.limitUpsAt(c)
                    if (!l.isNaN()) ml = if (ml.isNaN()) l else max(ml, l)
                }
                if (any) { maxRet[i] = mr; hot[i] = h.toFloat(); maxLu[i] = ml }
            }
            out["conMaxRet5"] = maxRet; out["conHotCount"] = hot; out["conMaxLimitUps"] = maxLu
        }
        return out
    }

    companion object {
        /** APP 端便捷入口:日历取指数交易日,逐只加入股票后构建。 */
        fun fromSeries(calendar: TradingCalendar, all: Sequence<BarSeries>, sectors: SectorMap?): MarketContext {
            val b = MarketContextBuilder(calendar)
            for (s in all) b.add(s)
            return b.build(sectors)
        }
    }
}
