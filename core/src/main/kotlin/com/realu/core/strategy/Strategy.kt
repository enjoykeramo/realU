package com.realu.core.strategy

import com.realu.core.data.BarSeries
import com.realu.core.feature.FeatureFrame
import kotlin.math.max
import kotlin.math.min

/**
 * 候选"启动日"硬条件(全部满足才进入模型评分)。
 * 主升浪的第一根 K 线几乎都伴随明显的价或量异动;先用规则把每天 5000 只缩到几十~几百只,
 * 既让模型专注于"启动日之后会不会走成主升",也把训练数据量降低一个数量级(内存友好)。
 */
data class GateParams(
    /** 上市满多少个交易日(剔除次新股的无涨跌停/高波动阶段)。 */
    val minListedDays: Int = 120,
    /** 5 日平均成交额下限(元),剔除流动性差、容易被操纵的标的。 */
    val minAmount5: Double = 3e7,
    /** 当日涨幅下限;与量比条件"或"关系。 */
    val minRet1: Double = 0.03,
    /** 当日量比(对前 5 日均量)下限;与涨幅条件"或"关系。 */
    val minVolRatio: Double = 1.8,
    /** 启动前 20 日涨幅上限:已经涨多的不算"初期"。 */
    val maxPriorRise20: Double = 0.25,
    /** 当日必须收阳(收盘 > 开盘 或 涨停)。 */
    val requireUpClose: Boolean = true,
    /** 剔除 ST(仅当股票名称可知时生效)。 */
    val excludeSt: Boolean = true,
    /** 长期停牌复牌后若干交易日内不发信号。 */
    val resumeCooldown: Int = 5,
) {
    fun passes(s: BarSeries, f: FeatureFrame, i: Int): Boolean {
        if (i < max(minListedDays, 21) || i >= s.size) return false
        if (excludeSt && s.isSt) return false
        if (f.barsSinceResume[i] < resumeCooldown) return false
        var amt = 0.0
        for (j in i - 4..i) amt += s.amount[j]
        if (amt / 5 < minAmount5) return false
        val ret1 = s.close[i] / s.close[i - 1] - 1
        val volRatio = f["volRatio5", i]
        if (!(ret1 >= minRet1 || (volRatio.isFinite() && volRatio >= minVolRatio && ret1 > 0))) return false
        if (requireUpClose && !(s.close[i] > s.open[i] || s.isLimitUp(i))) return false
        val prior20 = s.close[i - 1] / s.close[i - 21] - 1
        return prior20 <= maxPriorRise20
    }

    fun describe(): List<Pair<String, String>> = listOf(
        "上市满" to "$minListedDays 个交易日",
        "5日均成交额 ≥" to "${fmtYi(minAmount5)}",
        "当日涨幅 ≥ 或 量比 ≥" to "${pct(minRet1)} / $minVolRatio",
        "启动前20日涨幅 ≤" to pct(maxPriorRise20),
        "当日收阳" to if (requireUpClose) "是" else "否",
        "剔除 ST" to if (excludeSt) "是" else "否",
    )

    private fun fmtYi(v: Double) = if (v >= 1e8) "%.1f亿".format(v / 1e8) else "%.0f万".format(v / 1e4)
}

internal fun pct(v: Double) = "%.0f%%".format(v * 100)

/**
 * 标签定义("三重壁垒"法):信号日 t 收盘发出,t+1 开盘买入;
 * 在 [horizon] 个交易日内,**先**触及 +target 记为正样本,先触及 −stop 或到期未达标记为负样本。
 * 这就是报告里"准确率"的含义:信号发出后,先涨 30% 而不是先跌 8% 的概率。
 */
data class LabelParams(
    val horizon: Int = 40,
    val target: Double = 0.30,
    val stop: Double = 0.08,
)

enum class LabelOutcome { HIT, STOPPED, EXPIRED, UNTRADABLE, NO_DATA }

class LabelResult(val outcome: LabelOutcome, val maxGain: Double, val endIndex: Int) {
    val positive: Boolean get() = outcome == LabelOutcome.HIT
    val usable: Boolean get() = outcome != LabelOutcome.UNTRADABLE && outcome != LabelOutcome.NO_DATA
}

object Labeler {
    fun label(s: BarSeries, t: Int, p: LabelParams): LabelResult {
        val e = t + 1
        if (e >= s.size) return LabelResult(LabelOutcome.NO_DATA, Double.NaN, s.size - 1)
        // 一字涨停开盘买不进,不作为样本
        if (s.isOneWordLimitUp(e)) return LabelResult(LabelOutcome.UNTRADABLE, Double.NaN, e)
        val entry = s.open[e]
        val target = entry * (1 + p.target)
        val stop = entry * (1 - p.stop)
        val last = e + p.horizon - 1
        if (last >= s.size) return LabelResult(LabelOutcome.NO_DATA, Double.NaN, s.size - 1)
        var maxGain = 0.0
        for (d in e..last) {
            // T+1:买入当日不能卖出,止损从次日起算;同一根 K 线同时触及两边时保守地认为先止损
            if (d > e && s.low[d] <= stop) return LabelResult(LabelOutcome.STOPPED, maxGain, d)
            maxGain = max(maxGain, s.high[d] / entry - 1)
            if (s.high[d] >= target) return LabelResult(LabelOutcome.HIT, maxGain, d)
        }
        return LabelResult(LabelOutcome.EXPIRED, maxGain, last)
    }
}

/**
 * 卖出规则(APP 的"风控与退出"):
 * - 固定止损 stopLoss;
 * - 固定止盈 takeProfit(≤0 表示不用);
 * - 动态止盈:最高浮盈达到 trailingActivate 后,从最高点回撤 trailingDrawdown 卖出;
 * - 最长持有 maxHoldDays 个交易日。
 */
data class ExitRule(
    val stopLoss: Double = 0.08,
    val takeProfit: Double = 0.0,
    val trailingActivate: Double = 0.15,
    val trailingDrawdown: Double = 0.10,
    val maxHoldDays: Int = 60,
) {
    fun describe(): String = buildString {
        append("止损 −${pct(stopLoss)}")
        if (takeProfit > 0) append(" · 止盈 +${pct(takeProfit)}")
        if (trailingDrawdown > 0) append(" · 浮盈≥${pct(trailingActivate)} 后自最高点回撤 ${pct(trailingDrawdown)} 止盈")
        append(" · 最长持有 $maxHoldDays 日")
    }
}

enum class ExitReason(val label: String) {
    STOP_LOSS("止损"), TAKE_PROFIT("固定止盈"), TRAILING("动态止盈"), MAX_HOLD("到期"), DATA_END("数据结束(未平仓)")
}

class Trade(
    val entryIndex: Int,
    val exitIndex: Int,
    val entryDate: Int,
    val exitDate: Int,
    val entryPrice: Double,
    val exitPrice: Double,
    val maxGain: Double,
    val reason: ExitReason,
) {
    val ret: Double get() = exitPrice / entryPrice - 1
    val holdDays: Int get() = exitIndex - entryIndex
}

object TradeSimulator {
    /**
     * 信号日 t 收盘发出 → t+1 开盘买入。返回 null 表示买不进(一字涨停 / 没有下一根 K 线)。
     * 执行假设(偏保守):同一根 K 线先检查止损/回撤,再检查止盈;跳空低开按开盘价成交;
     * 一字跌停当日卖不出,顺延。不计手续费与滑点,报告中另行扣除。
     */
    fun simulate(s: BarSeries, t: Int, rule: ExitRule): Trade? {
        val e = t + 1
        if (e >= s.size || s.isOneWordLimitUp(e)) return null
        val entry = s.open[e]
        val stopPrice = entry * (1 - rule.stopLoss)
        val tpPrice = if (rule.takeProfit > 0) entry * (1 + rule.takeProfit) else Double.MAX_VALUE
        var peak = s.high[e]
        val last = min(s.size - 1, e + rule.maxHoldDays)
        for (d in e + 1..last) {
            if (s.isOneWordLimitDown(d)) { peak = max(peak, s.high[d]); continue }
            val o = s.open[d]
            // 1) 止损
            if (s.low[d] <= stopPrice) return make(s, e, d, entry, min(o, stopPrice), peak, ExitReason.STOP_LOSS)
            // 2) 动态止盈(用前一日为止的最高价,避免"先见到今天高点")
            if (rule.trailingDrawdown > 0 && peak >= entry * (1 + rule.trailingActivate)) {
                val trail = peak * (1 - rule.trailingDrawdown)
                if (s.low[d] <= trail) return make(s, e, d, entry, min(o, trail), peak, ExitReason.TRAILING)
            }
            // 3) 固定止盈
            if (s.high[d] >= tpPrice) return make(s, e, d, entry, max(o, tpPrice), max(peak, s.high[d]), ExitReason.TAKE_PROFIT)
            peak = max(peak, s.high[d])
            if (d == e + rule.maxHoldDays) return make(s, e, d, entry, s.close[d], peak, ExitReason.MAX_HOLD)
        }
        return make(s, e, last, entry, s.close[last], peak, if (last == e + rule.maxHoldDays) ExitReason.MAX_HOLD else ExitReason.DATA_END)
    }

    private fun make(s: BarSeries, e: Int, d: Int, entry: Double, px: Double, peak: Double, r: ExitReason) =
        Trade(e, d, s.date[e], s.date[d], entry, px, peak / entry - 1, r)
}

/** 主升启动的类型,用于分类统计与在 APP 中展示。 */
enum class Pattern(val label: String) {
    BOX_BREAKOUT("箱体突破"),
    OVERSOLD_REBOUND("超跌反弹"),
    TREND_WAVE("趋势波段"),
    OTHER("其他"),
}

object PatternTagger {
    fun tag(f: FeatureFrame, i: Int): Pattern {
        val box60 = f["boxRange60", i]; val brk60 = f["boxBreak60", i]
        val box30 = f["boxRange30", i]; val brk30 = f["boxBreak30", i]
        if ((box60 <= 0.35f && brk60 > 0f) || (box30 <= 0.20f && brk30 > 0f)) return Pattern.BOX_BREAKOUT
        val dd = f["dd250", i]; val r60 = f["ret60", i]; val rsiMin = f["rsi6Min10", i]
        if (dd <= -0.30f && (r60 <= -0.10f || rsiMin <= 20f)) return Pattern.OVERSOLD_REBOUND
        if (f["ma60Slope", i] > 0f && f["maDev60", i] > 0f && f["maAlign", i] >= 2f) return Pattern.TREND_WAVE
        return Pattern.OTHER
    }
}
