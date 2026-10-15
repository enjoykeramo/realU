package com.realu.core.strategy

import com.realu.core.data.BarSeries
import com.realu.core.feature.FeatureDefs
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
        val volRatio = f.values[VOL_RATIO5][i]
        if (!(ret1 >= minRet1 || (volRatio.isFinite() && volRatio >= minVolRatio && ret1 > 0))) return false
        if (requireUpClose && !(s.close[i] > s.open[i] || s.isLimitUp(i))) return false
        val prior20 = s.close[i - 1] / s.close[i - 21] - 1
        return prior20 <= maxPriorRise20
    }

    fun describe(): List<Pair<String, String>> = listOf(
        "上市满" to "$minListedDays 个交易日",
        "5日均成交额 ≥" to fmtYi(minAmount5),
        "当日涨幅 ≥ 或 量比 ≥" to "${pct(minRet1)} / $minVolRatio",
        "启动前20日涨幅 ≤" to pct(maxPriorRise20),
        "当日收阳" to if (requireUpClose) "是" else "否",
        "剔除 ST" to if (excludeSt) "是" else "否",
    )

    private fun fmtYi(v: Double) = if (v >= 1e8) "%.1f亿".format(v / 1e8) else "%.0f万".format(v / 1e4)

    private companion object {
        val VOL_RATIO5 = FeatureDefs.INDEX.getValue("volRatio5")
    }
}

internal fun pct(v: Double) = "%.0f%%".format(v * 100)

/**
 * 卖出规则。**全部按收盘价判断、按当日收盘价成交**(尾盘卖出),盘中波动不会触发卖出:
 * - 容忍回撤 stopLoss:收盘 ≤ 买入价×(1−stopLoss) 卖出;0 表示不设(只靠到期);
 * - 固定止盈 takeProfit:收盘 ≥ 买入价×(1+takeProfit) 卖出;0 表示不用;
 * - 动态止盈:最高收盘价达到 买入价×(1+trailingActivate) 后,收盘 ≤ 最高收盘×(1−trailingDrawdown) 卖出;
 *   profitFloor > 0 时,激活后收盘跌回 买入价×(1+profitFloor) 也卖出(锁定利润);
 * - 最长持有 maxHoldDays 个交易日,到期按收盘卖出。
 */
data class ExitRule(
    val stopLoss: Double = 0.15,
    val takeProfit: Double = 0.30,
    val trailingActivate: Double = 0.0,
    val trailingDrawdown: Double = 0.0,
    val profitFloor: Double = 0.0,
    val maxHoldDays: Int = 60,
) {
    val hasTrailing: Boolean get() = trailingDrawdown > 0

    fun describe(): String = buildString {
        append(if (stopLoss > 0) "收盘回撤 −${pct(stopLoss)} 止损" else "不设止损")
        if (takeProfit > 0) append(" · 收盘达 +${pct(takeProfit)} 止盈")
        if (hasTrailing) {
            append(" · 收盘浮盈≥${pct(trailingActivate)} 后自最高收盘回撤 ${pct(trailingDrawdown)} 止盈")
            if (profitFloor > 0) append("(跌回 +${pct(profitFloor)} 也卖)")
        }
        append(" · 最长持有 $maxHoldDays 日")
    }
}

enum class ExitReason(val label: String) {
    STOP_LOSS("止损"), TAKE_PROFIT("固定止盈"), TRAILING("动态止盈"), PROFIT_FLOOR("利润保护"),
    MAX_HOLD("到期"), DATA_END("未完成(数据结束)")
}

class Trade(
    val entryIndex: Int,
    val exitIndex: Int,
    val entryDate: Int,
    val exitDate: Int,
    val entryPrice: Double,
    val exitPrice: Double,
    /** 持有期间最高收盘价相对买入价的涨幅。 */
    val maxGain: Double,
    /** 持有期间最低收盘价相对买入价的跌幅(负数)。 */
    val maxDrawdown: Double,
    val reason: ExitReason,
) {
    val ret: Double get() = exitPrice / entryPrice - 1
    val holdDays: Int get() = exitIndex - entryIndex
    /** 数据结束时仍未卖出的交易,收益未定,不计入准确率统计。 */
    val completed: Boolean get() = reason != ExitReason.DATA_END
}

object TradeSimulator {
    /**
     * 信号日 t 收盘发出 → t+1 开盘买入。返回 null 表示买不进(一字涨停 / 没有下一根 K 线)。
     * T+1:买入当日不能卖;一字跌停卖不出,顺延。成本不在这里扣除,由统计环节统一扣。
     */
    fun simulate(s: BarSeries, t: Int, rule: ExitRule): Trade? {
        val e = t + 1
        if (e >= s.size || s.isOneWordLimitUp(e)) return null
        val entry = s.open[e]
        val stopPrice = if (rule.stopLoss > 0) entry * (1 - rule.stopLoss) else -1.0
        val tpPrice = if (rule.takeProfit > 0) entry * (1 + rule.takeProfit) else Double.MAX_VALUE
        val activate = entry * (1 + rule.trailingActivate)
        val floor = entry * (1 + rule.profitFloor)
        var peak = s.close[e]
        var trough = s.close[e]
        val last = min(s.size - 1, e + rule.maxHoldDays)
        for (d in e + 1..last) {
            val c = s.close[d]
            if (s.isOneWordLimitDown(d)) { trough = min(trough, c); continue }
            trough = min(trough, c)
            if (c <= stopPrice) return make(s, e, d, entry, c, peak, trough, ExitReason.STOP_LOSS)
            if (c >= tpPrice) return make(s, e, d, entry, c, max(peak, c), trough, ExitReason.TAKE_PROFIT)
            if (rule.hasTrailing && peak >= activate) {
                if (c <= peak * (1 - rule.trailingDrawdown)) return make(s, e, d, entry, c, peak, trough, ExitReason.TRAILING)
                if (rule.profitFloor > 0 && c <= floor) return make(s, e, d, entry, c, peak, trough, ExitReason.PROFIT_FLOOR)
            }
            peak = max(peak, c)
            if (d == e + rule.maxHoldDays) return make(s, e, d, entry, c, peak, trough, ExitReason.MAX_HOLD)
        }
        return make(s, e, last, entry, s.close[last], peak, trough,
            if (last == e + rule.maxHoldDays) ExitReason.MAX_HOLD else ExitReason.DATA_END)
    }

    private fun make(s: BarSeries, e: Int, d: Int, entry: Double, px: Double, peak: Double, trough: Double, r: ExitReason) =
        Trade(e, d, s.date[e], s.date[d], entry, px, peak / entry - 1, trough / entry - 1, r)
}

/**
 * 目标("主升"的定义)。信号日 t 收盘发出,t+1 开盘买入;之后允许回撤,
 * 但收盘价不能跌破 买入价×(1−maxDrawdown)(容忍的最大回撤,0 = 不限);
 * 在 [horizon] 个交易日内,**某日收盘相对买入价的盈利(扣除成本后)≥ target**,即为成功。
 *
 * 等价于用"固定止盈 target + 收盘止损 maxDrawdown + 持有 horizon 日"去做一次交易,
 * 看这一个周期的实际盈利是否 ≥ target。报告中的"准确率"= 成功周期数 / 已完成周期数。
 */
data class LabelParams(
    val horizon: Int = 60,
    val target: Double = 0.30,
    val maxDrawdown: Double = 0.15,
    /** 单笔往返成本(佣金 + 印花税 + 滑点),判断是否达标时扣除。 */
    val cost: Double = 0.003,
) {
    /** 标签使用的交易规则。 */
    val rule: ExitRule get() = ExitRule(stopLoss = maxDrawdown, takeProfit = target + cost, maxHoldDays = horizon)

    fun isSuccess(netReturn: Double): Boolean = netReturn >= target - 1e-9

    fun describe(): String =
        "次日开盘买入后 $horizon 个交易日内,收盘盈利(扣成本)达到 ${pct(target)};" +
            (if (maxDrawdown > 0) "期间允许回撤,但收盘跌破买入价 −${pct(maxDrawdown)} 即判失败" else "期间回撤不限")
}

enum class LabelOutcome { HIT, STOPPED, EXPIRED, UNTRADABLE, NO_DATA }

class LabelResult(val outcome: LabelOutcome, val maxGain: Double, val endIndex: Int) {
    val positive: Boolean get() = outcome == LabelOutcome.HIT
    val usable: Boolean get() = outcome != LabelOutcome.UNTRADABLE && outcome != LabelOutcome.NO_DATA
}

object Labeler {
    fun label(s: BarSeries, t: Int, p: LabelParams): LabelResult {
        val tr = TradeSimulator.simulate(s, t, p.rule)
            ?: return LabelResult(if (t + 1 >= s.size) LabelOutcome.NO_DATA else LabelOutcome.UNTRADABLE, Double.NaN, min(t + 1, s.size - 1))
        val outcome = when {
            p.isSuccess(tr.ret - p.cost) -> LabelOutcome.HIT
            !tr.completed -> LabelOutcome.NO_DATA
            tr.reason == ExitReason.STOP_LOSS -> LabelOutcome.STOPPED
            else -> LabelOutcome.EXPIRED
        }
        return LabelResult(outcome, tr.maxGain, tr.exitIndex)
    }
}

/** 主升启动的类型,用于分类统计与在 APP 中展示。 */
enum class Pattern(val label: String) {
    BOX_BREAKOUT("箱体突破"),
    OVERSOLD_REBOUND("超跌反弹"),
    TREND_WAVE("趋势波段"),
    OTHER("其他"),
}

object PatternTagger {
    private fun ix(n: String) = FeatureDefs.INDEX.getValue(n)
    private val BOX60 = ix("boxRange60"); private val BRK60 = ix("boxBreak60")
    private val BOX30 = ix("boxRange30"); private val BRK30 = ix("boxBreak30")
    private val DD250 = ix("dd250"); private val RET60 = ix("ret60"); private val RSIMIN = ix("rsi6Min10")
    private val MA60S = ix("ma60Slope"); private val MADEV60 = ix("maDev60"); private val ALIGN = ix("maAlign")

    fun tag(f: FeatureFrame, i: Int): Pattern {
        val v = f.values
        if ((v[BOX60][i] <= 0.35f && v[BRK60][i] > 0f) || (v[BOX30][i] <= 0.20f && v[BRK30][i] > 0f)) return Pattern.BOX_BREAKOUT
        if (v[DD250][i] <= -0.30f && (v[RET60][i] <= -0.10f || v[RSIMIN][i] <= 20f)) return Pattern.OVERSOLD_REBOUND
        if (v[MA60S][i] > 0f && v[MADEV60][i] > 0f && v[ALIGN][i] >= 2f) return Pattern.TREND_WAVE
        return Pattern.OTHER
    }
}
