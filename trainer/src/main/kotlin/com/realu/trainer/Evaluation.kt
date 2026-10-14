package com.realu.trainer

import com.realu.core.feature.FeatureEngine
import com.realu.core.feature.IndexContext
import com.realu.core.model.GbdtModel
import com.realu.core.strategy.ExitReason
import com.realu.core.strategy.ExitRule
import com.realu.core.strategy.GateParams
import com.realu.core.strategy.LabelOutcome
import com.realu.core.strategy.LabelParams
import com.realu.core.strategy.Labeler
import com.realu.core.strategy.Pattern
import com.realu.core.strategy.PatternTagger
import com.realu.core.strategy.Trade
import com.realu.core.strategy.TradeSimulator
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.sqrt

object Metrics {
    fun auc(scores: DoubleArray, labels: IntArray): Double {
        val n = scores.size
        val order = (0 until n).sortedBy { scores[it] }
        var rankSum = 0.0
        var pos = 0L
        var i = 0
        while (i < n) {
            var j = i
            while (j + 1 < n && scores[order[j + 1]] == scores[order[i]]) j++
            val avgRank = (i + j) / 2.0 + 1
            for (k in i..j) if (labels[order[k]] == 1) { rankSum += avgRank; pos++ }
            i = j + 1
        }
        val neg = n - pos
        if (pos == 0L || neg == 0L) return Double.NaN
        return (rankSum - pos * (pos + 1) / 2.0) / (pos * neg)
    }

    /** Wilson 区间下限(95%):样本少时,"10 个信号中 10 个命中"并不代表真实准确率 100%。 */
    fun wilsonLower(hits: Int, n: Int, z: Double = 1.96): Double {
        if (n == 0) return 0.0
        val ph = hits.toDouble() / n
        val d = 1 + z * z / n
        val c = ph + z * z / (2 * n)
        val r = z * sqrt(ph * (1 - ph) / n + z * z / (4.0 * n * n))
        return (c - r) / d
    }

    class ThresholdChoice(val threshold: Double, val precision: Double, val signals: Int, val hits: Int, val achieved: Boolean)

    /**
     * 在验证集上选阈值:在"信号数 ≥ minSignals"的前提下,
     * 找精确率 ≥ target 的最低阈值(信号最多);达不到时取精确率最高的阈值并标记未达标。
     */
    fun chooseThreshold(scores: DoubleArray, labels: IntArray, target: Double, minSignals: Int): ThresholdChoice {
        val order = scores.indices.sortedByDescending { scores[it] }
        var hits = 0
        var bestK = -1; var bestPrec = -1.0
        var okK = -1
        for (k in order.indices) {
            hits += labels[order[k]]
            val n = k + 1
            // 同分样本必须一起取,阈值只能落在分数变化处
            if (k + 1 < order.size && scores[order[k + 1]] == scores[order[k]]) continue
            val prec = hits.toDouble() / n
            if (n >= minSignals) {
                if (prec >= target) okK = k
                if (prec > bestPrec) { bestPrec = prec; bestK = k }
            }
        }
        val k = if (okK >= 0) okK else bestK
        if (k < 0) return ThresholdChoice(1.0, 0.0, 0, 0, false)
        val th = scores[order[k]]
        var h = 0
        for (i in 0..k) h += labels[order[i]]
        return ThresholdChoice(th, h.toDouble() / (k + 1), k + 1, h, okK >= 0)
    }

    /**
     * 与回测口径一致的阈值选择:同一只股票在冷却期(cooldown 个交易日)内只算第一次信号。
     * 同一波启动往往连续几天都满足条件,不去重会把准确率算高。
     * 候选阈值取验证集概率的分位点,逐一模拟去重后的信号数与命中数。
     */
    fun chooseThresholdDedup(
        scores: DoubleArray, labels: IntArray, stock: IntArray, bar: IntArray,
        cooldown: Int, target: Double, minSignals: Int,
    ): ThresholdChoice {
        val n = scores.size
        if (n == 0) return ThresholdChoice(1.0, 0.0, 0, 0, false)
        val order = (0 until n).sortedWith(compareBy<Int> { stock[it] }.thenBy { bar[it] })
        val sorted = scores.sortedArray()
        val candidates = (0 until 400).map { sorted[(n - 1) - ((n - 1).toLong() * it * it / (399L * 399L)).toInt()] }
            .distinct().sortedDescending()
        var okTh = Double.NaN; var okSig = 0; var okHit = 0
        var bestTh = Double.NaN; var bestPrec = -1.0; var bestSig = 0; var bestHit = 0
        for (th in candidates) {
            var sig = 0; var hit = 0
            var lastStock = -1; var nextAllowed = Int.MIN_VALUE
            for (k in order) {
                if (stock[k] != lastStock) { lastStock = stock[k]; nextAllowed = Int.MIN_VALUE }
                if (bar[k] < nextAllowed || scores[k] < th) continue
                sig++; hit += labels[k]
                nextAllowed = bar[k] + cooldown
            }
            if (sig < minSignals) continue
            val prec = hit.toDouble() / sig
            // 候选阈值从高到低遍历:满足目标的最后一个即信号最多者
            if (prec >= target) { okTh = th; okSig = sig; okHit = hit }
            if (prec > bestPrec) { bestPrec = prec; bestTh = th; bestSig = sig; bestHit = hit }
        }
        return when {
            !okTh.isNaN() -> ThresholdChoice(okTh, okHit.toDouble() / okSig, okSig, okHit, true)
            !bestTh.isNaN() -> ThresholdChoice(bestTh, bestPrec, bestSig, bestHit, false)
            else -> ThresholdChoice(1.0, 0.0, 0, 0, false)
        }
    }
}

/** 回测中的一个信号(已做同股冷却去重)。 */
class SignalRecord(
    val symbol: String,
    val name: String,
    val date: Int,
    val probability: Double,
    val pattern: Pattern,
    val outcome: LabelOutcome,
    val labelMaxGain: Double,
    /** 与 [Backtester.rules] 一一对应,null 表示买不进。 */
    val trades: Array<Trade?>,
    /** 买入日起的每日日期与收盘价,用于组合逐日盯市。 */
    val pathDates: IntArray = IntArray(0),
    val pathClose: FloatArray = FloatArray(0),
) {
    val hit: Boolean get() = outcome == LabelOutcome.HIT
}

/**
 * 逐只股票流式回测:不把全市场数据放进内存,单只股票处理完即释放。
 */
class Backtester(
    private val source: DataSource,
    private val stocks: List<StockFile>,
    private val index: IndexContext?,
    private val gate: GateParams,
    private val label: LabelParams,
    val rules: List<ExitRule>,
    private val threads: Int,
    private val cooldownDays: Int,
) {
    private val maxHold = rules.maxOf { it.maxHoldDays }

    fun run(model: GbdtModel, threshold: Double, from: Int, to: Int, progress: (String) -> Unit): List<SignalRecord> {
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val tasks = stocks.map { sf -> Callable { scanStock(sf, model, threshold, from, to) } }
            val out = ArrayList<SignalRecord>()
            var done = 0
            // 分批提交,控制同时驻留内存的股票数
            for (batch in tasks.chunked(threads * 4)) {
                for (f in pool.invokeAll(batch)) {
                    out += f.get()
                    done++
                }
                if (done % 500 < threads * 4 || done == tasks.size) progress("回测 $from–$to:$done/${tasks.size} 只,信号 ${out.size},${MemoryGuard.describe()}")
            }
            out.sortWith(compareBy<SignalRecord> { it.date }.thenByDescending { it.probability })
            return out
        } finally {
            pool.shutdownNow()
        }
    }

    private fun scanStock(sf: StockFile, model: GbdtModel, threshold: Double, from: Int, to: Int): List<SignalRecord> {
        val s = try { source.load(sf).series } catch (e: Exception) { null } ?: return emptyList()
        if (s.size < gate.minListedDays + 2) return emptyList()
        val first = s.indexOf(from).let { if (it >= 0) it else -it - 1 }
        if (first >= s.size) return emptyList()
        val f = try { FeatureEngine.compute(s, index, source.extrasFor(sf.code)) } catch (e: Exception) { return emptyList() }
        val out = ArrayList<SignalRecord>()
        val row = FloatArray(f.values.size)
        var nextAllowed = 0
        for (i in max(first, 1) until s.size) {
            if (s.date[i] > to) break
            if (i < nextAllowed || !gate.passes(s, f, i)) continue
            f.row(i, row)
            val p = model.probability(row)
            if (p < threshold) continue
            val lr = Labeler.label(s, i, label)
            val trades = Array(rules.size) { TradeSimulator.simulate(s, i, rules[it]) }
            val e = i + 1
            val last = minOf(s.size - 1, e + maxHold)
            val pd = if (e <= last) s.date.copyOfRange(e, last + 1) else IntArray(0)
            val pc = if (e <= last) FloatArray(last - e + 1) { s.close[e + it].toFloat() } else FloatArray(0)
            out += SignalRecord(s.code.symbol, s.name, s.date[i], p, PatternTagger.tag(f, i), lr.outcome, lr.maxGain, trades, pd, pc)
            nextAllowed = i + cooldownDays
        }
        return out
    }

    companion object {
        /** 卖出参数网格(只在验证期挑选)。 */
        fun grid(base: ExitRule): List<ExitRule> {
            val out = LinkedHashSet<ExitRule>()
            out += base
            for (sl in listOf(0.06, 0.08, 0.10))
                for (act in listOf(0.10, 0.15, 0.20, 0.30))
                    for (dd in listOf(0.08, 0.10, 0.15))
                        out += ExitRule(sl, 0.0, act, dd, base.maxHoldDays)
            for (sl in listOf(0.06, 0.08, 0.10)) out += ExitRule(sl, 0.30, 0.0, 0.0, base.maxHoldDays)
            return out.toList()
        }
    }
}

/** 交易统计。 */
class TradeStats(trades: List<Trade>, cost: Double) {
    val n = trades.size
    val rets = trades.map { it.ret - cost }
    val winRate = if (n == 0) 0.0 else rets.count { it > 0 }.toDouble() / n
    val gain30Rate = if (n == 0) 0.0 else rets.count { it >= 0.30 }.toDouble() / n
    val mean = if (n == 0) 0.0 else rets.average()
    val median = if (n == 0) 0.0 else rets.sorted()[n / 2]
    val worst = rets.minOrNull() ?: 0.0
    val best = rets.maxOrNull() ?: 0.0
    val avgHold = if (n == 0) 0.0 else trades.map { it.holdDays }.average()
    val reasons: Map<ExitReason, Int> = trades.groupingBy { it.reason }.eachCount()
}

/**
 * 组合回测:资金均分 maxPositions 份,按信号日期顺序、同日按概率高低占用空闲仓位,
 * 平仓后资金回到该仓位继续复利。净值按收盘价逐日盯市,最大回撤包含持仓中的浮亏。
 */
class PortfolioResult(val finalEquity: Double, val annualized: Double, val maxDrawdown: Double, val taken: Int, val skipped: Int,
                      val curve: List<Pair<Int, Double>>)

object Portfolio {
    private class Held(val slot: Int, val base: Double, val trade: Trade, val sig: SignalRecord, val cost: Double)

    fun simulate(signals: List<SignalRecord>, ruleIdx: Int, slots: Int, cost: Double, from: Int, to: Int): PortfolioResult {
        val slotFree = IntArray(slots) // 该日期之后可再开仓
        val slotEq = DoubleArray(slots) { 1.0 / slots }
        val held = ArrayList<Held>()
        var skipped = 0
        val heldUntil = HashMap<String, Int>()
        for (s in signals) {
            val t = s.trades[ruleIdx] ?: continue
            if ((heldUntil[s.symbol] ?: 0) >= t.entryDate) { skipped++; continue }
            var slot = -1
            for (k in 0 until slots) if (slotFree[k] < t.entryDate) { slot = k; break }
            if (slot < 0) { skipped++; continue }
            held += Held(slot, slotEq[slot], t, s, cost)
            slotEq[slot] *= 1 + t.ret - cost
            slotFree[slot] = t.exitDate
            heldUntil[s.symbol] = t.exitDate
        }
        if (held.isEmpty()) return PortfolioResult(1.0, 0.0, 0.0, 0, skipped, emptyList())

        // 逐日盯市:每个仓位在任一日期的价值 = 已实现部分 + 持仓浮动部分
        val dates = java.util.TreeSet<Int>()
        for (h in held) for (d in h.sig.pathDates) if (d <= h.trade.exitDate) dates += d
        val bySlot = held.groupBy { it.slot }.mapValues { (_, l) -> l.sortedBy { it.trade.entryDate } }
        val ptr = IntArray(slots)
        val curve = ArrayList<Pair<Int, Double>>()
        var peak = 1.0; var mdd = 0.0
        for (d in dates) {
            var eq = 0.0
            for (k in 0 until slots) {
                val list = bySlot[k]
                if (list == null) { eq += 1.0 / slots; continue }
                while (ptr[k] < list.size && list[ptr[k]].trade.exitDate < d) ptr[k]++
                eq += if (ptr[k] >= list.size) {
                    val last = list.last(); last.base * (1 + last.trade.ret - last.cost)
                } else {
                    val h = list[ptr[k]]
                    when {
                        d < h.trade.entryDate -> h.base // 尚未买入:现金
                        d == h.trade.exitDate -> h.base * (1 + h.trade.ret - h.cost)
                        else -> {
                            val p = java.util.Arrays.binarySearch(h.sig.pathDates, d)
                            val px = if (p >= 0) h.sig.pathClose[p].toDouble() else h.trade.entryPrice
                            h.base * (px / h.trade.entryPrice)
                        }
                    }
                }
            }
            peak = max(peak, eq)
            mdd = max(mdd, 1 - eq / peak)
            curve += d to eq
        }
        val eq = slotEq.sum()
        val lastDate = minOf(to, curve.last().first)
        val years = max(1.0 / 12, com.realu.core.data.Dates.daysBetween(from, maxOf(from, lastDate)) / 365.25)
        val ann = if (eq > 0) Math.pow(eq, 1 / years) - 1 else -1.0
        return PortfolioResult(eq, ann, mdd, held.size, skipped, curve)
    }
}
