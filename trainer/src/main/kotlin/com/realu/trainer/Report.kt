package com.realu.trainer

import com.realu.core.data.Dates
import com.realu.core.model.RealuModel
import com.realu.core.strategy.ExitRule
import com.realu.core.strategy.Pattern
import java.io.File

/** 生成 Markdown 检验报告(UTF-8,可用浏览器/VS Code/Typora 打开)。 */
object Report {
    fun write(
        file: File, cfg: Config, model: RealuModel, metrics: Map<String, Any?>,
        validStats: TradeStats, testStats: TradeStats, pf: PortfolioResult, choice: Metrics.ThresholdChoice,
        testSignals: List<SignalRecord>, ruleIdx: Int, rules: List<ExitRule>, validSignals: List<SignalRecord>,
    ) {
        val sb = StringBuilder()
        fun line(s: String = "") = sb.append(s).append('\n')
        line("# ${model.name} 模型检验报告")
        line()
        line("- 生成时间:${model.createdAt}")
        line("- 结论:**${metrics["verdict"]}**")
        line("- ${metrics["definition"]}")
        line("- 时间切分:训练 ${Dates.format(cfg.startDate)} ~ ${Dates.format(cfg.trainEnd)} | 验证 ~ ${Dates.format(cfg.validEnd)} | 测试 之后至数据末尾")
        line("- 基准命中率(候选启动日不经模型筛选):${pct(metrics["baseRate"] as Double)}")
        line("- AUC:验证 ${"%.4f".format(metrics["aucValid"] as Double)} / 测试 ${"%.4f".format(metrics["aucTest"] as Double)}")
        line("- 阈值(验证集选定):${"%.4f".format(choice.threshold)},验证集样本口径 ${choice.signals} 个信号,准确率 ${pct(choice.precision)}")
        line()
        line("## 候选条件(硬条件)")
        model.gate.describe().forEach { line("- ${it.first} ${it.second}") }
        line()
        line("## 卖出规则(验证期选定)")
        line(model.exit.describe())
        line()
        line("验证期表现最好的 5 组卖出参数(扣费后平均收益):")
        line()
        line("| 规则 | 交易数 | 平均收益 | 胜率 |")
        line("|---|---:|---:|---:|")
        rules.indices.map { k -> k to TradeStats(validSignals.mapNotNull { it.trades[k] }, cfg.costPerTrade) }
            .sortedByDescending { it.second.mean }.take(5)
            .forEach { (k, st) -> line("| ${rules[k].describe()} | ${st.n} | ${pct(st.mean)} | ${pct(st.winRate)} |") }
        line()
        line("## 验证期 vs 测试期(信号已按同股 ${cfg.int("backtest.cooldownDays", 20)} 日冷却去重)")
        line()
        line("| 期间 | 信号 | 准确率(先涨${pct(cfg.label.target, 0)}) | 平均收益 | 中位收益 | 胜率 | 收益≥30%占比 | 最差一笔 | 平均持有 |")
        line("|---|---:|---:|---:|---:|---:|---:|---:|---:|")
        val vPrec = if (validSignals.isEmpty()) 0.0 else validSignals.count { it.hit }.toDouble() / validSignals.size
        val tPrec = if (testSignals.isEmpty()) 0.0 else testSignals.count { it.hit }.toDouble() / testSignals.size
        line("| 验证 | ${validSignals.size} | ${pct(vPrec)} | ${pct(validStats.mean)} | ${pct(validStats.median)} | ${pct(validStats.winRate)} | ${pct(validStats.gain30Rate)} | ${pct(validStats.worst)} | ${"%.1f".format(validStats.avgHold)} |")
        line("| 测试 | ${testSignals.size} | ${pct(tPrec)} | ${pct(testStats.mean)} | ${pct(testStats.median)} | ${pct(testStats.winRate)} | ${pct(testStats.gain30Rate)} | ${pct(testStats.worst)} | ${"%.1f".format(testStats.avgHold)} |")
        line()
        line("测试期准确率 95% 置信下限(Wilson):${pct(Metrics.wilsonLower(testSignals.count { it.hit }, testSignals.size))}。信号数越少,这个下限离点估计越远。")
        line()
        line("## 测试期按年份")
        line()
        line("| 年份 | 信号 | 准确率 | 平均收益 |")
        line("|---|---:|---:|---:|")
        testSignals.groupBy { it.date / 10000 }.toSortedMap().forEach { (y, l) ->
            val st = TradeStats(l.mapNotNull { it.trades[ruleIdx] }, cfg.costPerTrade)
            line("| $y | ${l.size} | ${pct(l.count { it.hit }.toDouble() / l.size)} | ${pct(st.mean)} |")
        }
        line()
        line("## 测试期按启动类型")
        line()
        line("| 类型 | 信号 | 准确率 | 平均收益 |")
        line("|---|---:|---:|---:|")
        for (p in Pattern.entries) {
            val l = testSignals.filter { it.pattern == p }
            if (l.isEmpty()) continue
            val st = TradeStats(l.mapNotNull { it.trades[ruleIdx] }, cfg.costPerTrade)
            line("| ${p.label} | ${l.size} | ${pct(l.count { it.hit }.toDouble() / l.size)} | ${pct(st.mean)} |")
        }
        line()
        line("## 卖出原因分布(测试期)")
        line()
        testStats.reasons.forEach { (r, c) -> line("- ${r.label}:$c") }
        line()
        line("## 组合模拟(测试期,${cfg.maxPositions} 仓等权,单笔成本 ${pct(cfg.costPerTrade, 2)})")
        line()
        line("- 期末净值 ${"%.3f".format(pf.finalEquity)},年化 ${pct(pf.annualized)},最大回撤(逐日盯市)${pct(pf.maxDrawdown)}")
        line("- 成交 ${pf.taken} 笔,因仓位已满或同股持仓跳过 ${pf.skipped} 个信号")
        line()
        line("## 评分因子权重(特征重要性按组汇总)")
        line()
        model.factorWeights.forEach { (k, v) -> line("- $k:$v") }
        line()
        line("## 注意")
        line()
        line("- 回测假设次日开盘价成交、一字涨停买不进、一字跌停卖不出;未计冲击成本,真实收益通常更低。")
        line("- 测试期只用于检验,从未参与训练、早停、阈值和卖出参数的选择;但如果你根据本报告反复改参数再训练,测试期也会被\"间接拟合\",请保留一段最新数据做最终验收。")
        line("- 历史表现不代表未来;市场风格切换时模型可能失效,建议每季度用最新数据重训并对比报告。")
        file.writeText(sb.toString(), Charsets.UTF_8)
    }
}
