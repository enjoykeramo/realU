package com.realu.trainer

import com.realu.core.data.Dates
import com.realu.core.feature.FeatureEngine
import com.realu.core.feature.IndexContext
import com.realu.core.market.MarketContext
import com.realu.core.model.GbdtModel
import com.realu.core.strategy.ExitReason
import com.realu.core.strategy.ExitRule
import com.realu.core.strategy.GateParams
import com.realu.core.strategy.LabelParams
import com.realu.core.strategy.Pattern
import com.realu.core.strategy.PatternTagger
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

    fun quantile(x: DoubleArray, q: Double): Double {
        if (x.isEmpty()) return 0.0
        val s = x.sortedArray()
        return s[((s.size - 1) * q).toInt().coerceIn(0, s.size - 1)]
    }
}

/**
 * 一个候选信号(信号日通过硬条件且概率 ≥ 下限),附带在每组卖出规则下的交易结果。
 * 用紧凑数组存储:约 1KB/个,十万个候选约 100MB。
 */
class Candidate(
    val stock: Int,
    val symbol: String,
    val name: String,
    val date: Int,
    val bar: Int,
    val prob: Double,
    val pattern: Pattern,
    /** 次日一字涨停等原因买不进。 */
    val tradable: Boolean,
    val entryPrice: Float,
    /** 每组规则的毛收益。 */
    val ret: FloatArray,
    /** 每组规则的持有天数(卖出日 = pathDates[hold])。 */
    val hold: ShortArray,
    val reason: ByteArray,
    /** 买入日起的每日日期与收盘价,用于组合逐日盯市。 */
    val pathDates: IntArray,
    val pathClose: FloatArray,
) {
    fun completed(r: Int) = tradable && reason[r].toInt() != ExitReason.DATA_END.ordinal
    fun exitDate(r: Int) = pathDates[hold[r].toInt().coerceAtMost(pathDates.size - 1)]
    val entryDate: Int get() = pathDates.firstOrNull() ?: date
}

/**
 * 逐只股票流式扫描验证期 + 测试期(一次读完),收集候选信号。不把全市场数据放进内存。
 */
class CandidateScanner(
    private val source: DataSource,
    private val stocks: List<StockFile>,
    private val index: IndexContext?,
    private val market: MarketContext?,
    private val gate: GateParams,
    val rules: List<ExitRule>,
    private val threads: Int,
) {
    private val maxHold = rules.maxOf { it.maxHoldDays }

    fun run(model: GbdtModel, minProb: Double, from: Int, to: Int, progress: (String) -> Unit): List<Candidate> {
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val out = ArrayList<Candidate>()
            var done = 0
            for (batch in stocks.indices.chunked(threads * 4)) {
                for (f in pool.invokeAll(batch.map { k -> Callable { scanStock(k, model, minProb, from, to) } })) out += f.get()
                done += batch.size
                if (done % 500 < threads * 4 || done == stocks.size) progress("扫描 $from–$to:$done/${stocks.size} 只,候选 ${out.size},${MemoryGuard.describe()}")
            }
            out.sortWith(compareBy<Candidate> { it.date }.thenByDescending { it.prob })
            return out
        } finally {
            pool.shutdownNow()
        }
    }

    private fun scanStock(k: Int, model: GbdtModel, minProb: Double, from: Int, to: Int): List<Candidate> {
        val sf = stocks[k]
        val s = try { source.load(sf).series } catch (e: Exception) { null } ?: return emptyList()
        if (s.size < gate.minListedDays + 2) return emptyList()
        val first = s.indexOf(from).let { if (it >= 0) it else -it - 1 }
        if (first >= s.size) return emptyList()
        val f = try { FeatureEngine.compute(s, index, source.extrasFor(sf.code), market) } catch (e: Exception) { return emptyList() }
        val out = ArrayList<Candidate>()
        val row = FloatArray(f.values.size)
        for (i in max(first, 1) until s.size) {
            if (s.date[i] > to) break
            if (!gate.passes(s, f, i)) continue
            f.row(i, row)
            val p = model.probability(row)
            if (p < minProb) continue
            val nr = rules.size
            val ret = FloatArray(nr) { Float.NaN }
            val hold = ShortArray(nr)
            val reason = ByteArray(nr) { ExitReason.DATA_END.ordinal.toByte() }
            var tradable = false
            var entry = Float.NaN
            for (r in 0 until nr) {
                val t = TradeSimulator.simulate(s, i, rules[r]) ?: break
                tradable = true
                entry = t.entryPrice.toFloat()
                ret[r] = t.ret.toFloat(); hold[r] = t.holdDays.toShort(); reason[r] = t.reason.ordinal.toByte()
            }
            val e = i + 1
            val last = minOf(s.size - 1, e + maxHold)
            val pd = if (tradable) s.date.copyOfRange(e, last + 1) else IntArray(0)
            val pc = if (tradable) FloatArray(last - e + 1) { s.close[e + it].toFloat() } else FloatArray(0)
            out += Candidate(k, s.code.symbol, s.name, s.date[i], i, p, PatternTagger.tag(f, i), tradable, entry, ret, hold, reason, pd, pc)
        }
        return out
    }

    companion object {
        /**
         * 卖出规则网格(收盘价口径):固定止盈 = 目标收益;动态止盈 = 激活线 × 回撤幅度 × 是否锁定目标利润;
         * 每种再配 4 档容忍回撤(10% / 15% / 20% / 不设)。
         */
        fun grid(label: LabelParams, base: ExitRule): List<ExitRule> {
            val out = LinkedHashSet<ExitRule>()
            out += base
            val tp = label.target + label.cost
            val h = label.horizon
            for (sl in listOf(0.10, 0.15, 0.20, 0.0)) {
                out += ExitRule(sl, tp, 0.0, 0.0, 0.0, h)
                for (act in listOf(0.30, 0.40, 0.50))
                    for (dd in listOf(0.10, 0.15, 0.20))
                        for (floor in listOf(0.0, tp))
                            out += ExitRule(sl, 0.0, act, dd, floor, h)
            }
            return out.toList()
        }
    }
}

/** 某组规则下一批信号的统计(只统计已完成的周期)。 */
class TradeStats(signals: List<Candidate>, r: Int, private val label: LabelParams, cutoff: Int = Int.MAX_VALUE) {
    private val done = signals.filter { it.completed(r) && it.exitDate(r) <= cutoff }
    val incomplete = signals.count { it.tradable && !(it.completed(r) && it.exitDate(r) <= cutoff) }
    val untradable = signals.count { !it.tradable }
    val n = done.size
    val rets = done.map { it.ret[r] - label.cost }
    val hits = rets.count { label.isSuccess(it.toDouble()) }
    val precision = if (n == 0) 0.0 else hits.toDouble() / n
    val lower95 = Metrics.wilsonLower(hits, n)
    val winRate = if (n == 0) 0.0 else rets.count { it > 0 }.toDouble() / n
    val mean = if (n == 0) 0.0 else rets.average()
    val median = if (n == 0) 0.0 else rets.sorted()[n / 2].toDouble()
    val worst = rets.minOrNull()?.toDouble() ?: 0.0
    val best = rets.maxOrNull()?.toDouble() ?: 0.0
    val avgHold = if (n == 0) 0.0 else done.map { it.hold[r].toInt() }.average()
    val reasons: Map<ExitReason, Int> = done.groupingBy { ExitReason.entries[it.reason[r].toInt()] }.eachCount()
}

class Selection(
    val ruleIdx: Int,
    val threshold: Double,
    val stats: TradeStats,
    val achieved: Boolean,
)

/**
 * 在验证期**同时**选择卖出规则与概率阈值:
 * - 同一只股票 cooldown 个交易日内只取第一个信号(买不进的不占冷却期);
 * - objective=precision:准确率 ≥ 目标的组合中,信号最多者;objective=return:其中扣费后平均收益最高者;
 * - 都达不到目标时,取信号数 ≥ minSignals 中准确率最高者,并标记未达标。
 */
object Selector {
    /** cands 须已按(日期升序、概率降序)排好,[CandidateScanner.run] 的输出即如此。 */
    fun dedupe(cands: List<Candidate>, threshold: Double, cooldown: Int): List<Candidate> {
        val out = ArrayList<Candidate>()
        val nextAllowed = HashMap<Int, Int>()
        for (c in cands) {
            if (c.prob < threshold) continue
            if (c.bar < (nextAllowed[c.stock] ?: Int.MIN_VALUE)) continue
            out += c
            if (c.tradable) nextAllowed[c.stock] = c.bar + cooldown
        }
        return out
    }

    /** cutoff:验证期结束日。卖出日落在测试期内的交易视为未完成,避免测试期价格参与选择。 */
    fun select(valid: List<Candidate>, rules: List<ExitRule>, label: LabelParams, target: Double, minSignals: Int,
               cooldown: Int, objective: String, cutoff: Int): Selection {
        val probs = valid.map { it.prob }.distinct().sortedDescending()
        if (probs.isEmpty()) return Selection(0, 1.0, TradeStats(emptyList(), 0, label, cutoff), false)
        val n = probs.size
        val ths = (0 until 300).map { probs[((n - 1).toLong() * it * it / (299L * 299L)).toInt()] }.distinct()
        var best: Triple<Int, Double, Double>? = null // rule, threshold, score
        var bestAchieved = false
        for (th in ths) {
            val sel = dedupe(valid, th, cooldown)
            for (r in rules.indices) {
                var cnt = 0; var hit = 0; var sum = 0.0
                for (c in sel) {
                    if (!c.completed(r) || c.exitDate(r) > cutoff) continue
                    val net = c.ret[r] - label.cost
                    cnt++; sum += net
                    if (label.isSuccess(net.toDouble())) hit++
                }
                if (cnt < minSignals) continue
                val prec = hit.toDouble() / cnt
                val ok = prec >= target
                val score = when {
                    ok && objective == "return" -> 1e6 + sum / cnt
                    ok -> 1e6 + cnt + sum / cnt / 1e3
                    else -> prec + cnt * 1e-9
                }
                if (ok && !bestAchieved) { bestAchieved = true; best = null }
                if (bestAchieved && !ok) continue
                if (best == null || score > best.third) best = Triple(r, th, score)
            }
        }
        val b = best ?: return Selection(0, probs.first(), TradeStats(dedupe(valid, probs.first(), cooldown), 0, label, cutoff), false)
        return Selection(b.first, b.second, TradeStats(dedupe(valid, b.second, cooldown), b.first, label, cutoff), bestAchieved)
    }
}

/**
 * 组合回测:资金均分 maxPositions 份,按信号先后与概率高低占用空闲仓位,平仓后资金回到该仓位继续复利。
 * 净值按收盘价逐日盯市,最大回撤包含持仓中的浮亏。未完成的交易按最新收盘价计入。
 */
class PortfolioResult(val finalEquity: Double, val annualized: Double, val maxDrawdown: Double, val taken: Int, val skipped: Int,
                      val curve: List<Pair<Int, Double>>)

object Portfolio {
    private class Held(val slot: Int, val base: Double, val c: Candidate, val ret: Double, val exitDate: Int)

    fun simulate(signals: List<Candidate>, r: Int, slots: Int, cost: Double, from: Int): PortfolioResult {
        val slotFree = IntArray(slots)
        val slotEq = DoubleArray(slots) { 1.0 / slots }
        val held = ArrayList<Held>()
        var skipped = 0
        val heldUntil = HashMap<String, Int>()
        for (c in signals) {
            if (!c.tradable) continue
            val entry = c.entryDate
            if ((heldUntil[c.symbol] ?: 0) >= entry) { skipped++; continue }
            var slot = -1
            for (k in 0 until slots) if (slotFree[k] < entry) { slot = k; break }
            if (slot < 0) { skipped++; continue }
            val exit = c.exitDate(r)
            val ret = c.ret[r] - cost
            held += Held(slot, slotEq[slot], c, ret, exit)
            slotEq[slot] *= 1 + ret
            slotFree[slot] = exit
            heldUntil[c.symbol] = exit
        }
        if (held.isEmpty()) return PortfolioResult(1.0, 0.0, 0.0, 0, skipped, emptyList())
        val dates = java.util.TreeSet<Int>()
        for (h in held) for (d in h.c.pathDates) if (d <= h.exitDate) dates += d
        val bySlot = held.groupBy { it.slot }.mapValues { (_, l) -> l.sortedBy { it.c.entryDate } }
        val ptr = IntArray(slots)
        val curve = ArrayList<Pair<Int, Double>>()
        var peak = 1.0; var mdd = 0.0
        for (d in dates) {
            var eq = 0.0
            for (k in 0 until slots) {
                val list = bySlot[k]
                if (list == null) { eq += 1.0 / slots; continue }
                while (ptr[k] < list.size && list[ptr[k]].exitDate < d) ptr[k]++
                eq += if (ptr[k] >= list.size) {
                    val last = list.last(); last.base * (1 + last.ret)
                } else {
                    val h = list[ptr[k]]
                    when {
                        d < h.c.entryDate -> h.base
                        d == h.exitDate -> h.base * (1 + h.ret)
                        else -> {
                            val p = java.util.Arrays.binarySearch(h.c.pathDates, d)
                            val px = if (p >= 0) h.c.pathClose[p].toDouble() else h.c.entryPrice.toDouble()
                            h.base * (px / h.c.entryPrice)
                        }
                    }
                }
            }
            peak = max(peak, eq)
            mdd = max(mdd, 1 - eq / peak)
            curve += d to eq
        }
        val eq = slotEq.sum()
        val years = max(1.0 / 12, Dates.daysBetween(from, maxOf(from, curve.last().first)) / 365.25)
        val ann = if (eq > 0) Math.pow(eq, 1 / years) - 1 else -1.0
        return PortfolioResult(eq, ann, mdd, held.size, skipped, curve)
    }
}
