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
    /** AUC = P(正样本得分 > 负样本得分),并列计 0.5。只用原始类型数组排序,50 万样本约几十毫秒。 */
    fun auc(scores: DoubleArray, labels: IntArray): Double {
        var np = 0
        for (l in labels) if (l == 1) np++
        val nn = labels.size - np
        if (np == 0 || nn == 0) return Double.NaN
        val pos = DoubleArray(np); val neg = DoubleArray(nn)
        var a = 0; var b = 0
        for (i in scores.indices) if (labels[i] == 1) pos[a++] = scores[i] else neg[b++] = scores[i]
        pos.sort(); neg.sort()
        var lo = 0; var hi = 0
        var sum = 0.0
        for (x in pos) {
            while (lo < nn && neg[lo] < x) lo++
            if (hi < lo) hi = lo
            while (hi < nn && neg[hi] <= x) hi++
            sum += lo + (hi - lo) * 0.5
        }
        return sum / (np.toDouble() * nn)
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

    /** 一段扫描:日期区间 [from, to] 用 model 评分,只保留概率 ≥ minProb 的候选。 */
    class Segment(val model: GbdtModel, val minProb: Double, val from: Int, val to: Int)

    fun run(model: GbdtModel, minProb: Double, from: Int, to: Int, progress: (String) -> Unit): List<Candidate> =
        run(listOf(Segment(model, minProb, from, to)), progress)

    /** 多段扫描(例如滚动重训的每年一个模型)共用一次读数据和特征计算。 */
    fun run(segments: List<Segment>, progress: (String) -> Unit): List<Candidate> {
        val pool = Executors.newFixedThreadPool(threads)
        val from = segments.minOf { it.from }; val to = segments.maxOf { it.to }
        try {
            val out = ArrayList<Candidate>()
            var done = 0
            for (batch in stocks.indices.chunked(threads * 4)) {
                for (f in pool.invokeAll(batch.map { k -> Callable { scanStock(k, segments, from) } })) out += f.get()
                done += batch.size
                if (done % 500 < threads * 4 || done == stocks.size) progress("扫描 $from–$to:$done/${stocks.size} 只,候选 ${out.size},${MemoryGuard.describe()}")
            }
            out.sortWith(compareBy<Candidate> { it.date }.thenByDescending { it.prob })
            return out
        } finally {
            pool.shutdownNow()
        }
    }

    private fun scanStock(k: Int, segments: List<Segment>, from: Int): List<Candidate> {
        val sf = stocks[k]
        val s = try { source.load(sf).series } catch (e: Exception) { null } ?: return emptyList()
        if (s.size < gate.minListedDays + 2) return emptyList()
        val first = s.indexOf(from).let { if (it >= 0) it else -it - 1 }
        if (first >= s.size) return emptyList()
        val f = try { FeatureEngine.compute(s, index, source.extrasFor(sf.code), market) } catch (e: Exception) { return emptyList() }
        val out = ArrayList<Candidate>()
        val row = FloatArray(f.values.size)
        for (i in max(first, 1) until s.size) {
            val seg = segments.firstOrNull { s.date[i] >= it.from && s.date[i] <= it.to } ?: continue
            if (!gate.passes(s, f, i)) continue
            f.row(i, row)
            val p = seg.model.probability(row)
            if (p < seg.minProb) continue
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

/** 选出的配置:卖出规则 + 每种启动类型各自的概率阈值(> 1 表示该类型不发信号)。 */
class Selection(
    val ruleIdx: Int,
    val thresholds: DoubleArray,
    val stats: TradeStats,
    val achieved: Boolean,
    val perPattern: Map<Pattern, TradeStats>,
) {
    fun threshold(p: Pattern): Double = thresholds[p.ordinal]
    fun enabled(p: Pattern): Boolean = thresholds[p.ordinal] <= 1.0
    /** 所有启用类型中最低的阈值(扫描候选时的下限)。 */
    val minThreshold: Double get() = thresholds.filter { it <= 1.0 }.minOrNull() ?: 1.0
}

/**
 * 在验证期**同时**选择卖出规则与每种启动类型的概率阈值:
 * - 同一只股票 cooldown 个交易日内只取第一个信号(买不进的不占冷却期);
 * - 不同启动类型(箱体突破 / 超跌反弹 / 趋势波段 / 其他)成功率差异很大,各自选阈值;
 * - 有类型能达到目标准确率时:只启用达标的类型,objective=precision 取信号最多、objective=return 取平均收益最高;
 * - 都达不到时:每种类型取"95% 置信下限"最高的阈值,再按下限从高到低逐个加入,只要整体下限还在提高 ——
 *   避免为了凑信号数选出大量低质量信号,也避免只剩几个信号的偶然结果。
 */
object Selector {
    const val DISABLED = 1.01

    /** cands 须已按(日期升序、概率降序)排好,[CandidateScanner.run] 的输出即如此。 */
    fun dedupe(cands: List<Candidate>, threshold: Double, cooldown: Int): List<Candidate> = dedupe(cands, { threshold }, cooldown)

    fun dedupe(cands: List<Candidate>, threshold: (Candidate) -> Double, cooldown: Int): List<Candidate> {
        val out = ArrayList<Candidate>()
        val nextAllowed = HashMap<Int, Int>()
        for (c in cands) {
            if (c.prob < threshold(c)) continue
            if (c.bar < (nextAllowed[c.stock] ?: Int.MIN_VALUE)) continue
            out += c
            if (c.tradable) nextAllowed[c.stock] = c.bar + cooldown
        }
        return out
    }

    fun dedupe(cands: List<Candidate>, sel: Selection, cooldown: Int): List<Candidate> =
        dedupe(cands, { sel.threshold(it.pattern) }, cooldown)

    private class Cell(val th: Double, val cnt: IntArray, val hit: IntArray, val sum: DoubleArray)

    /** cutoff:验证期结束日。卖出日落在测试期内的交易视为未完成,避免测试期价格参与选择。 */
    fun select(valid: List<Candidate>, rules: List<ExitRule>, label: LabelParams, target: Double, minSignals: Int,
               minPerPattern: Int, cooldown: Int, objective: String, cutoff: Int, perPattern: Boolean = true): Selection {
        val nr = rules.size
        // 分组:每种启动类型一组;不分类型时所有候选一组、共用一个阈值
        val groups: List<List<Pattern>> = if (perPattern) Pattern.entries.map { listOf(it) } else listOf(Pattern.entries.toList())
        val pats = groups
        // 每组、每个候选阈值、每组规则下的(信号数, 成功数, 收益和)
        val cells = groups.map { g ->
            val cp = valid.filter { it.pattern in g }
            val probs = cp.map { it.prob }.distinct().sortedDescending()
            val n = probs.size
            val ths = if (n == 0) emptyList() else (0 until 200).map { probs[((n - 1).toLong() * it * it / (199L * 199L)).toInt()] }.distinct()
            ths.map { th ->
                val cnt = IntArray(nr); val hit = IntArray(nr); val sum = DoubleArray(nr)
                for (c in dedupe(cp, th, cooldown)) for (r in 0 until nr) {
                    if (!c.completed(r) || c.exitDate(r) > cutoff) continue
                    val net = c.ret[r] - label.cost
                    cnt[r]++; sum[r] += net
                    if (label.isSuccess(net.toDouble())) hit[r]++
                }
                Cell(th, cnt, hit, sum)
            }
        }

        var bestRule = 0
        var bestTh = DoubleArray(groups.size) { DISABLED }
        var bestScore = Double.NEGATIVE_INFINITY
        var bestAchieved = false
        for (r in 0 until nr) {
            // 1) 能达标的类型
            val ok = pats.indices.mapNotNull { pi ->
                cells[pi].filter { it.cnt[r] >= minPerPattern && it.hit[r].toDouble() / it.cnt[r] >= target }
                    .maxByOrNull { if (objective == "return") it.sum[r] / it.cnt[r] else it.cnt[r] + it.sum[r] / it.cnt[r] / 1e3 }
                    ?.let { pi to it }
            }
            val okCnt = ok.sumOf { it.second.cnt[r] }
            val th = DoubleArray(pats.size) { DISABLED }
            val achieved: Boolean
            val score: Double
            if (ok.isNotEmpty() && okCnt >= minSignals) {
                achieved = true
                ok.forEach { (pi, c) -> th[pi] = c.th }
                score = 1e9 + if (objective == "return") ok.sumOf { it.second.sum[r] } / okCnt else okCnt.toDouble()
            } else {
                achieved = false
                // 2) 达不到:按置信下限贪心组合
                val bestPer = pats.indices.mapNotNull { pi ->
                    cells[pi].filter { it.cnt[r] >= minPerPattern }
                        .maxByOrNull { Metrics.wilsonLower(it.hit[r], it.cnt[r]) }?.let { pi to it }
                }.sortedByDescending { (_, c) -> Metrics.wilsonLower(c.hit[r], c.cnt[r]) }
                var cnt = 0; var hit = 0; var lb = -1.0
                for ((pi, c) in bestPer) {
                    val nl = Metrics.wilsonLower(hit + c.hit[r], cnt + c.cnt[r])
                    if (cnt + c.cnt[r] >= minSignals && nl <= lb) continue
                    cnt += c.cnt[r]; hit += c.hit[r]; th[pi] = c.th
                    lb = Metrics.wilsonLower(hit, cnt)
                }
                if (cnt < minSignals) continue
                score = lb
            }
            if (achieved && !bestAchieved) { bestAchieved = true; bestScore = Double.NEGATIVE_INFINITY }
            if (bestAchieved && !achieved) continue
            if (score > bestScore) { bestScore = score; bestRule = r; bestTh = th }
        }
        val perPat = DoubleArray(Pattern.entries.size) { DISABLED }
        groups.forEachIndexed { gi, g -> g.forEach { perPat[it.ordinal] = bestTh[gi] } }
        if (bestAchieved && perPattern) {
            // 各类型是分别去重选的阈值;合并后同一只股票的信号会互相占用冷却期,个别类型的准确率可能掉到目标以下。
            // 逐个检查:不达标的类型提高阈值,仍不行就不启用。
            val thsOf = cells.map { list -> list.map { it.th }.sorted() }
            for (iter in 0 until 20) {
                val signals = dedupe(valid, { perPat[it.pattern.ordinal] }, cooldown)
                fun ok(p: Pattern, sig: List<Candidate>): Boolean {
                    val st = TradeStats(sig.filter { it.pattern == p }, bestRule, label, cutoff)
                    return st.n >= minPerPattern && st.precision >= target
                }
                val bad = Pattern.entries.firstOrNull { perPat[it.ordinal] <= 1.0 && !ok(it, signals) } ?: break
                var fixed = false
                for (t in thsOf[bad.ordinal].filter { it > perPat[bad.ordinal] }) {
                    val trial = perPat.copyOf().also { it[bad.ordinal] = t }
                    if (ok(bad, dedupe(valid, { c -> trial[c.pattern.ordinal] }, cooldown))) { perPat[bad.ordinal] = t; fixed = true; break }
                }
                if (!fixed) perPat[bad.ordinal] = DISABLED
            }
        }
        val sel = finish(valid, Selection(bestRule, perPat, TradeStats(emptyList(), bestRule, label, cutoff), bestAchieved, emptyMap()),
            label, cooldown, cutoff)
        // 修正后再核对整体是否仍达标
        val stillOk = bestAchieved && sel.stats.n >= minSignals && sel.stats.precision >= target
        return Selection(sel.ruleIdx, sel.thresholds, sel.stats, stillOk, sel.perPattern)
    }

    /** 用选定阈值重新统计整体与各类型表现。 */
    fun finish(cands: List<Candidate>, sel: Selection, label: LabelParams, cooldown: Int, cutoff: Int = Int.MAX_VALUE): Selection {
        val signals = dedupe(cands, sel, cooldown)
        val per = Pattern.entries.filter { sel.enabled(it) }
            .associateWith { p -> TradeStats(signals.filter { it.pattern == p }, sel.ruleIdx, label, cutoff) }
        return Selection(sel.ruleIdx, sel.thresholds, TradeStats(signals, sel.ruleIdx, label, cutoff), sel.achieved, per)
    }
}

/**
 * 不同模型(验证用模型 / 每年重训的模型 / 最终导出模型)的概率刻度不同,阈值不能直接照搬。
 * 按"分位数"迁移:在验证期,阈值 th 对应"该类型候选中前 q 比例";新模型在参考期(最近一年)候选上取同样的前 q 比例。
 */
object ThresholdMapper {
    fun fractions(sel: Selection, probs: DoubleArray, patterns: ByteArray): DoubleArray =
        DoubleArray(Pattern.entries.size) { pi ->
            if (sel.thresholds[pi] > 1.0) return@DoubleArray -1.0
            var n = 0; var above = 0
            for (k in probs.indices) if (patterns[k].toInt() == pi) { n++; if (probs[k] >= sel.thresholds[pi]) above++ }
            if (n == 0) -1.0 else above.toDouble() / n
        }

    fun map(fractions: DoubleArray, probs: DoubleArray, patterns: ByteArray): DoubleArray =
        DoubleArray(fractions.size) { pi ->
            val q = fractions[pi]
            if (q < 0) return@DoubleArray Selector.DISABLED
            val vals = probs.indices.filter { patterns[it].toInt() == pi }.map { probs[it] }.sortedDescending()
            if (vals.isEmpty()) Selector.DISABLED
            else vals[((vals.size * q).toInt() - 1).coerceIn(0, vals.size - 1)]
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
