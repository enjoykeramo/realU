package com.realu.trainer

import com.realu.core.data.Dates
import com.realu.core.feature.FeatureDefs
import com.realu.core.model.RealuModel
import com.realu.core.strategy.Pattern
import java.io.File

/** 生成 Markdown 检验报告(UTF-8,可用浏览器/VS Code/Typora 打开)。 */
object Report {
    fun write(file: File, cfg: Config, model: RealuModel, s: EvalSummary) {
        val label = model.label
        val sel = s.selection
        val r = sel.ruleIdx
        val cooldown = cfg.int("backtest.cooldownDays", 20)
        val sb = StringBuilder()
        fun line(t: String = "") = sb.append(t).append('\n')
        fun row(name: String, st: TradeStats) =
            line("| $name | ${st.n} | ${st.hits} | ${pct(st.precision)} | ${pct(st.lower95)} | ${pct(st.mean)} | ${pct(st.median)} | ${pct(st.winRate)} | ${pct(st.worst)} | ${"%.1f".format(st.avgHold)} | ${st.incomplete} |")
        val head = "| 期间 | 已完成信号 | 成功 | 准确率 | 95%下限 | 平均收益 | 中位收益 | 胜率 | 最差一笔 | 平均持有 | 未完成 |\n|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|"

        line("# ${model.name} 模型检验报告")
        line()
        line("- 生成时间:${model.createdAt}")
        line("- 结论:**${s.verdict}**")
        line("- 目标:${label.describe()}")
        line("- 准确率:已完成的交易周期中,按选定卖出规则卖出时(收盘价)扣成本 ${pct(label.cost, 1)} 后盈利 ≥ ${pct(label.target, 0)} 的比例;同一只股票 $cooldown 个交易日内只计第一次信号;数据末尾尚未卖出的周期单独列为\"未完成\",不计入")
        line("- 时间切分:训练 ${Dates.format(cfg.startDate)} ~ ${Dates.format(cfg.trainEnd)} | 验证 ~ ${Dates.format(cfg.validEnd)} | 测试 ${Dates.format(s.testFrom)} ~ ${Dates.format(s.testTo)}")
        line("- 基准成功率(候选启动日不经模型筛选):${pct(s.baseRate)}")
        line("- AUC:验证 ${"%.4f".format(s.aucValid)} / 测试 ${"%.4f".format(s.aucTest)}")
        line("- 板块来源:${sectorText(s.sectorSource)};概念板块 ${model.sectors?.conceptNames?.size ?: 0} 个")
        line()
        line("## 验证期 vs 测试期")
        line()
        line(head)
        row("验证", sel.stats)
        row("测试", s.testStats)
        line()
        line("阈值 ${"%.4f".format(sel.threshold)}(APP 评分 60 分)。测试期 95% 下限为 Wilson 置信区间下限:信号越少,下限离点估计越远。")
        line()
        line("## 卖出规则")
        line()
        line("选定:**${model.exit.describe()}**(验证期按 `exit.objective=${cfg.str("exit.objective", "precision")}` 与阈值同时选出)")
        line()
        line("同一阈值下各规则在验证期 / 测试期的表现(按验证期准确率排序,前 8):")
        line()
        line("| 规则 | 验证准确率 | 验证平均收益 | 测试准确率 | 测试平均收益 |")
        line("|---|---:|---:|---:|---:|")
        s.rules.indices.map { k -> Triple(k, TradeStats(s.validSignals, k, label, cfg.validEnd), TradeStats(s.testSignals, k, label)) }
            .sortedWith(compareByDescending<Triple<Int, TradeStats, TradeStats>> { it.second.precision }.thenByDescending { it.second.mean })
            .take(8)
            .forEach { (k, v, t) -> line("| ${s.rules[k].describe()}${if (k == r) " ✔" else ""} | ${pct(v.precision)} | ${pct(v.mean)} | ${pct(t.precision)} | ${pct(t.mean)} |") }
        line()
        line("> 固定止盈(收盘达 +30% 即卖)直接对应\"一个周期盈利 ≥ 30%\",准确率最高;动态止盈让利润奔跑、平均收益可能更高,但回落卖出时可能低于 30%。")
        line()
        line("## 测试期按年份")
        line()
        line("| 年份 | 已完成信号 | 准确率 | 平均收益 |")
        line("|---|---:|---:|---:|")
        s.testSignals.groupBy { it.date / 10000 }.toSortedMap().forEach { (y, l) ->
            val st = TradeStats(l, r, label)
            line("| $y | ${st.n} | ${pct(st.precision)} | ${pct(st.mean)} |")
        }
        line()
        line("## 测试期按启动类型")
        line()
        line("| 类型 | 已完成信号 | 准确率 | 平均收益 |")
        line("|---|---:|---:|---:|")
        for (p in Pattern.entries) {
            val st = TradeStats(s.testSignals.filter { it.pattern == p }, r, label)
            if (st.n > 0) line("| ${p.label} | ${st.n} | ${pct(st.precision)} | ${pct(st.mean)} |")
        }
        line()
        line("## 卖出原因分布(测试期)")
        line()
        s.testStats.reasons.forEach { (k, c) -> line("- ${k.label}:$c") }
        if (s.testStats.untradable > 0) line("- 买不进(次日一字涨停):${s.testStats.untradable}")
        line()
        line("## 组合模拟(测试期,${cfg.maxPositions} 仓等权,单笔成本 ${pct(cfg.costPerTrade, 2)})")
        line()
        line("- 期末净值 ${"%.3f".format(s.portfolio.finalEquity)},年化 ${pct(s.portfolio.annualized)},最大回撤(逐日盯市)${pct(s.portfolio.maxDrawdown)}")
        line("- 成交 ${s.portfolio.taken} 笔,因仓位已满或同股持仓跳过 ${s.portfolio.skipped} 个信号")
        line()
        line("## 评分因子权重(特征重要性按组汇总)")
        line()
        model.factorWeights.forEach { (k, v) -> line("- $k:$v") }
        line()
        line("## 最新交易日市场情绪")
        line()
        line("| 指标 | 数值 |")
        line("|---|---:|")
        for ((k, v) in s.marketSnapshot) {
            val lbl = FeatureDefs.ALL.firstOrNull { it.name == k }?.label ?: k
            line("| $lbl | ${if (v.isNaN()) "-" else "%.3f".format(v)} |")
        }
        line()
        line("## 注意")
        line()
        line("- 回测假设:次日开盘价买入,卖出按收盘价成交(尾盘卖出);一字涨停买不进,一字跌停卖不出;已扣单笔成本 ${pct(cfg.costPerTrade, 2)}。")
        line("- 卖出条件按收盘价判断,盘中跌破止损线不会卖出;若收盘大幅跳水,实际亏损可能超过容忍回撤。")
        if ((model.sectors?.conceptNames?.size ?: 0) > 0) {
            line("- **概念板块用的是当前成分名单**,历史上\"涨起来之后才被归入某概念\"的股票会让回测偏乐观。建议设 `sector.useConcepts=false` 再训练一次对比。")
        }
        if (s.sectorSource == "cluster") line("- 板块为收益相关性聚类(每年用之前 250 日数据重算),组号没有行业名称,但同组股票走势高度同步。")
        line("- 测试期只用于检验,从未参与训练、早停、阈值和卖出参数的选择;但如果你根据本报告反复改参数再训练,测试期也会被\"间接拟合\",请保留一段最新数据做最终验收。")
        line("- 历史表现不代表未来;市场风格切换时模型可能失效,建议每季度用最新数据重训并对比报告。")
        file.writeText(sb.toString(), Charsets.UTF_8)
    }

    private fun sectorText(src: String) = when (src) {
        "industry" -> "通达信行业(hq_cache/tdxhy.cfg)"
        "cluster" -> "收益相关性聚类(仅 vipdoc)"
        else -> "未使用"
    }
}
