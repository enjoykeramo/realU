package com.realu.core.strategy

import com.realu.core.data.BarSeries
import com.realu.core.feature.FeatureDefs
import com.realu.core.feature.FeatureEngine
import com.realu.core.feature.FeatureFrame
import com.realu.core.feature.IndexContext
import com.realu.core.feature.StockExtras
import com.realu.core.model.RealuModel

/** 某个特征对评分的影响,用于 APP 结果页的 ✓/× 和理由展示。 */
class Reason(val feature: String, val label: String, val group: String, val value: Float, val impact: Double)

class Signal(
    val symbol: String,
    val name: String,
    val date: Int,
    val close: Double,
    val probability: Double,
    val score: Int,
    /** 是否达到发信号阈值。 */
    val triggered: Boolean,
    val pattern: Pattern,
    val reasons: List<Reason>,
    /** 以收盘价估算的执行计划(实际以次日开盘价为成本重新计算)。 */
    val stopPrice: Double,
    val takeProfitPrice: Double?,
    val trailingText: String,
)

/**
 * APP 端使用入口:导入模型 → 对每只股票的日K调用 [evaluate]。
 * ```
 * val model = RealuModel.fromJson(jsonText).also { it.checkCompatible() }
 * val engine = SignalEngine(model)
 * val sig = engine.evaluate(series, indexContext)   // 默认评估最后一个交易日
 * if (sig?.triggered == true) ...
 * ```
 * series 需要尽量完整(≥ [FeatureEngine.MIN_HISTORY_BARS] 根),否则均线/MACD 等与训练口径不一致。
 */
class SignalEngine(val model: RealuModel) {

    init {
        model.checkCompatible()
    }

    /** 只通过候选条件不代表信号;返回 null 表示当日不满足候选条件。 */
    fun evaluate(
        series: BarSeries,
        index: IndexContext?,
        extras: StockExtras? = null,
        at: Int = series.size - 1,
        frame: FeatureFrame? = null,
    ): Signal? {
        if (at < 0 || at >= series.size) return null
        val f = frame ?: FeatureEngine.compute(series, index, extras)
        if (!model.gate.passes(series, f, at)) return null
        val x = f.row(at)
        val p = model.gbdt.probability(x)
        val (_, contrib) = model.gbdt.contributions(x)
        val reasons = contrib.indices
            .filter { contrib[it] != 0.0 }
            .sortedByDescending { kotlin.math.abs(contrib[it]) }
            .take(8)
            .map {
                val d = FeatureDefs.ALL[it]
                Reason(d.name, d.label, d.group.label, x[it], contrib[it])
            }
        val c = series.close[at]
        val ex = model.exit
        return Signal(
            symbol = series.code.symbol,
            name = series.name,
            date = series.date[at],
            close = c,
            probability = p,
            score = model.score(p),
            triggered = p >= model.threshold,
            pattern = PatternTagger.tag(f, at),
            reasons = reasons,
            stopPrice = c * (1 - ex.stopLoss),
            takeProfitPrice = if (ex.takeProfit > 0) c * (1 + ex.takeProfit) else null,
            trailingText = ex.describe(),
        )
    }

    /** 批量扫描(例如全市场每日收盘后),按评分降序返回触发的信号。 */
    fun scan(all: Sequence<BarSeries>, index: IndexContext?, extras: (BarSeries) -> StockExtras? = { null }): List<Signal> =
        all.mapNotNull { s -> runCatching { evaluate(s, index, extras(s)) }.getOrNull() }
            .filter { it.triggered }
            .sortedByDescending { it.probability }
            .toList()
}
