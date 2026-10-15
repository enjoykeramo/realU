package com.realu.trainer

import com.realu.core.data.Dates
import com.realu.core.feature.FeatureDefs
import com.realu.core.feature.FeatureGroup
import com.realu.core.model.RealuModel
import com.realu.core.strategy.ExitRule
import com.realu.core.strategy.Pattern
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Random
import kotlin.math.roundToInt

/** 报告所需的全部结果。 */
class EvalSummary(
    val baseRate: Double,
    val aucValid: Double,
    val aucTest: Double,
    val rules: List<ExitRule>,
    val selection: Selection,
    val validSignals: List<Candidate>,
    val testSignals: List<Candidate>,
    val testStats: TradeStats,
    val portfolio: PortfolioResult,
    val testFrom: Int,
    val testTo: Int,
    val verdict: String,
    val passed: Boolean,
    val marketSnapshot: Map<String, Float>,
    val sectorSource: String,
)

/**
 * 完整流程:读数据 → 全市场预扫描(情绪/板块/概念) → 候选样本 + 标签 → 分箱 → GBDT 训练(验证集早停)
 * → 一次扫描验证期 + 测试期,收集候选信号在每组卖出规则下的结果
 * → 验证期同时选卖出规则与阈值 → 测试期(从未参与任何选择)检验 → 判定是否达标 → 导出模型与报告。
 *
 * 时间切分(严格按时间,禁止随机打乱,避免未来数据泄漏):
 *   训练:data.startDate ~ split.trainEnd(且标签周期在 trainEnd 前结束)
 *   验证:trainEnd 之后 ~ split.validEnd(且卖出日在 validEnd 前)
 *   测试:validEnd 之后 ~ split.testEnd
 */
class TrainPipeline(private val cfg: Config, private val runDir: File) {

    fun run(): Boolean {
        val t0 = System.currentTimeMillis()
        val label = cfg.label
        Console.info("目标:${label.describe()}")
        val source = DataSource(cfg)
        val stocks = source.listStocks().shuffled(Random(cfg.seed)) // 打乱顺序:万一内存不足截断,也不会只剩某个板块
        require(stocks.isNotEmpty()) { "没有找到任何 A 股数据文件,请检查配置" }
        Console.info("找到 ${stocks.size} 只 A 股数据文件(${stocks.groupingBy { it.code.board.label }.eachCount()})")
        if (!source.hasBigOrder) Console.info("未提供大单数据:通达信离线日线不含逐笔/大单,资金特征使用 K 线估算的主力净额")
        if (!source.hasFloatShares) Console.info("未提供流通股本:换手率与筹码分布使用相对换手(量/250日均量)估算")
        val index = source.loadIndex()
        if (index != null) Console.info("大盘指数 ${cfg.indexSymbol}:${index.series.size} 根K线,${index.series.date.first()}~${index.series.date.last()}")

        // ---------- 全市场截面:市场情绪、板块、概念 ----------
        val (market, sectors) = MarketPrep.build(cfg, source, stocks, index)

        // ---------- 样本 ----------
        val builder = DatasetBuilder(cfg, source, stocks, index, market)
        val binner = builder.fitBinner()
        val (ds, stats) = builder.build(binner)
        Console.info("样本构建完成:成功 ${stats.stocksOk} 只,失败 ${stats.stocksFailed} 只;候选日 ${stats.candidates}," +
            "买不进/未完成 ${stats.untradable},训练期负样本降采样丢弃 ${stats.droppedNegatives};样本 ${ds.size},正样本 ${ds.positives}")
        stats.warnings.take(20).forEach { Console.warn("数据:$it") }
        if (stats.warnings.size > 20) Console.warn("……其余 ${stats.warnings.size - 20} 条数据警告见 data_warnings.txt")
        File(runDir, "data_warnings.txt").writeText(stats.warnings.joinToString("\n"), Charsets.UTF_8)

        val trainIdx = ds.date.indices.filter { ds.endDate[it] <= cfg.trainEnd }.toIntArray()
        val validIdx = ds.date.indices.filter { ds.date[it] > cfg.trainEnd && ds.endDate[it] <= cfg.validEnd }.toIntArray()
        val testIdx = ds.date.indices.filter { ds.date[it] > cfg.validEnd && ds.date[it] <= cfg.testEnd }.toIntArray()
        Console.info("时间切分:训练 ${trainIdx.size}(正 ${trainIdx.count { ds.label[it] > 0 }}),验证 ${validIdx.size}(正 ${validIdx.count { ds.label[it] > 0 }})," +
            "测试 ${testIdx.size}(正 ${testIdx.count { ds.label[it] > 0 }})")
        require(trainIdx.size >= 5000 && validIdx.size >= 1000) {
            "训练/验证样本过少,请检查 split.trainEnd / split.validEnd 是否落在数据区间内"
        }
        val baseRate = trainIdx.count { ds.label[it] > 0 }.toDouble() / trainIdx.size
        Console.info("基准成功率(候选启动日不经模型筛选):${pct(baseRate)} —— 模型的价值在于把它提高到多少")

        // ---------- 训练 ----------
        val trainer = GbdtTrainer(ds, binner, cfg.gbdt, cfg.threads)
        val tr = try {
            trainer.train(trainIdx, validIdx) { Console.info(it) }
        } catch (e: OutOfMemoryError) {
            throw IllegalStateException("训练时内存不足:请调大 -Xmx,或调小 sample.maxRows / sample.negativeKeep / gbdt.maxBins", e)
        }
        val pValid = trainer.predict(validIdx)
        val pTest = trainer.predict(testIdx)
        trainer.close()
        val aucValid = Metrics.auc(pValid, IntArray(validIdx.size) { ds.label[validIdx[it]].toInt() })
        val aucTest = if (testIdx.isNotEmpty()) Metrics.auc(pTest, IntArray(testIdx.size) { ds.label[testIdx[it]].toInt() }) else Double.NaN
        Console.info("AUC:验证 ${"%.4f".format(aucValid)},测试 ${"%.4f".format(aucTest)}")

        // ---------- 一次扫描验证期 + 测试期 ----------
        val rules = if (cfg.exitGrid) CandidateScanner.grid(label, cfg.exit) else listOf(cfg.exit)
        val minProb = Metrics.quantile(pValid, 1 - cfg.dbl("eval.candidateTop", 0.15))
        val scanner = CandidateScanner(source, stocks, index, market, cfg.gate, rules, cfg.threads)
        val validFrom = Dates.plusDays(cfg.trainEnd, 1)
        val testFrom = Dates.plusDays(cfg.validEnd, 1)
        val all = scanner.run(tr.model, minProb, validFrom, cfg.testEnd) { Console.info(it) }
        val validCands = all.filter { it.date <= cfg.validEnd }
        val testCands = all.filter { it.date > cfg.validEnd }
        Console.info("候选信号:验证期 ${validCands.size},测试期 ${testCands.size}(概率 ≥ ${"%.4f".format(minProb)},${rules.size} 组卖出规则)")

        // ---------- 验证期:同时选卖出规则与阈值 ----------
        val cooldown = cfg.int("backtest.cooldownDays", 20)
        val objective = cfg.str("exit.objective", "precision").lowercase()
        val sel = Selector.select(validCands, rules, label, cfg.targetPrecision, cfg.minValidSignals, cooldown, objective, cfg.validEnd)
        val rule = rules[sel.ruleIdx]
        Console.info("验证期选定:阈值 ${"%.4f".format(sel.threshold)};卖出规则 ${rule.describe()}")
        Console.info("验证期:信号 ${sel.stats.n},成功 ${sel.stats.hits},准确率 ${pct(sel.stats.precision)}" +
            if (sel.achieved) "(达到目标 ${pct(cfg.targetPrecision)})" else "(未达到目标 ${pct(cfg.targetPrecision)},取验证期最高准确率)")

        // ---------- 测试期检验 ----------
        val validSignals = Selector.dedupe(validCands, sel.threshold, cooldown)
        val testSignals = Selector.dedupe(testCands, sel.threshold, cooldown)
        val ts = TradeStats(testSignals, sel.ruleIdx, label)
        val testTo = testSignals.maxOfOrNull { it.date } ?: testFrom
        val portfolio = Portfolio.simulate(testSignals, sel.ruleIdx, cfg.maxPositions, cfg.costPerTrade, testFrom)
        val passed = sel.achieved && ts.n >= cfg.minTestSignals && ts.precision >= cfg.targetPrecision
        val verdict = when {
            passed -> "达标"
            !sel.achieved -> "未达标:验证期在信号数 ≥ ${cfg.minValidSignals} 的前提下最高准确率只有 ${pct(sel.stats.precision)}"
            ts.n < cfg.minTestSignals -> "未达标:测试期已完成信号 ${ts.n} 个 < ${cfg.minTestSignals},统计上不可信"
            else -> "未达标:测试期准确率 ${pct(ts.precision)} < 目标 ${pct(cfg.targetPrecision)}"
        }
        val lastDate = index?.series?.date?.last() ?: testTo
        val summary = EvalSummary(baseRate, aucValid, aucTest, rules, sel, validSignals, testSignals, ts, portfolio,
            testFrom, testTo, verdict, passed, market.marketAt(lastDate), sectors?.source ?: "none")

        // ---------- 导出 ----------
        val weights = factorWeights(tr.gainImportance)
        val metrics = linkedMapOf<String, Any?>(
            "verdict" to verdict,
            "definition" to "准确率 = 已完成交易周期中,按卖出规则卖出时(收盘价)扣成本盈利 ≥ ${pct(label.target, 0)} 的比例;同股 $cooldown 日内只计一次",
            "target" to label.describe(),
            "baseRate" to baseRate,
            "aucValid" to aucValid, "aucTest" to aucTest,
            "valid" to statsMap(sel.stats),
            "test" to statsMap(ts) + mapOf("from" to testFrom, "to" to testTo),
            "portfolio" to linkedMapOf("slots" to cfg.maxPositions, "finalEquity" to portfolio.finalEquity,
                "annualized" to portfolio.annualized, "maxDrawdown" to portfolio.maxDrawdown, "trades" to portfolio.taken),
            "byPattern" to Pattern.entries.associate { p ->
                val st = TradeStats(testSignals.filter { it.pattern == p }, sel.ruleIdx, label)
                p.label to linkedMapOf("signals" to st.n, "precision" to st.precision)
            },
        )
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
        val model = RealuModel(
            name = cfg.modelName, strategyId = cfg.strategyId,
            createdAt = LocalDateTime.now().toString(),
            featureVersion = FeatureDefs.VERSION, featureNames = FeatureDefs.NAMES,
            gate = cfg.gate, label = label, exit = rule, gbdt = tr.model,
            threshold = sel.threshold, passed = passed, metrics = metrics, factorWeights = weights,
            dataInfo = linkedMapOf(
                "source" to cfg.dataSource, "stocks" to stats.stocksOk, "samples" to ds.size,
                "startDate" to cfg.startDate, "trainEnd" to cfg.trainEnd, "validEnd" to cfg.validEnd,
                "sectorSource" to (sectors?.source ?: "none"), "concepts" to (sectors?.conceptNames?.size ?: 0),
                "floatShares" to source.hasFloatShares, "index" to cfg.indexSymbol, "trees" to tr.model.trees.size,
            ),
            sectors = sectors?.latest(),
        )
        val modelFile = File(runDir, "model-$stamp" + (if (passed) "" else "-NOT-PASSED") + ".json") // 文件名只用 ASCII,避免系统编码不一致时出现乱码文件名
        modelFile.writeText(model.toJson(), Charsets.UTF_8)
        // 自检:导出的文件能被 APP 端同一套代码读回
        RealuModel.fromJson(modelFile.readText(Charsets.UTF_8)).checkCompatible()

        writeSignals(File(runDir, "signals_valid.csv"), validSignals, sel.ruleIdx)
        writeSignals(File(runDir, "signals_test.csv"), testSignals, sel.ruleIdx)
        writeImportance(File(runDir, "feature_importance.csv"), tr.gainImportance)
        Report.write(File(runDir, "report.md"), cfg, model, summary)

        Console.raw("")
        Console.raw("================ 结果 ================")
        Console.raw("目标:${label.describe()}")
        Console.raw("测试期 ${Dates.format(testFrom)} ~ ${Dates.format(testTo)}(训练与调参从未使用)")
        Console.raw("已完成信号 ${ts.n} 个,成功 ${ts.hits} 个,准确率 ${pct(ts.precision)}(95%置信下限 ${pct(ts.lower95)});持仓中未完成 ${ts.incomplete} 个")
        Console.raw("卖出规则:${rule.describe()}")
        Console.raw("平均收益 ${pct(ts.mean)},中位数 ${pct(ts.median)},胜率 ${pct(ts.winRate)},最差 ${pct(ts.worst)}")
        Console.raw("组合(${cfg.maxPositions}仓):年化 ${pct(portfolio.annualized)},最大回撤 ${pct(portfolio.maxDrawdown)}")
        Console.raw("结论:$verdict")
        Console.raw("模型:${modelFile.absolutePath}")
        Console.raw("报告:${File(runDir, "report.md").absolutePath}")
        Console.raw("耗时 ${(System.currentTimeMillis() - t0) / 1000} 秒")
        return passed
    }

    private fun statsMap(st: TradeStats) = linkedMapOf<String, Any?>(
        "signals" to st.n, "hits" to st.hits, "precision" to st.precision, "precisionLower95" to st.lower95,
        "avgReturn" to st.mean, "medianReturn" to st.median, "winRate" to st.winRate, "worstTrade" to st.worst,
        "avgHoldDays" to st.avgHold, "incomplete" to st.incomplete,
    )

    private fun factorWeights(imp: DoubleArray): Map<String, Int> {
        val total = imp.sum().takeIf { it > 0 } ?: return emptyMap()
        val byGroup = FeatureGroup.entries.associateWith { g ->
            FeatureDefs.ALL.indices.filter { FeatureDefs.ALL[it].group == g }.sumOf { imp[it] } / total
        }.filterValues { it > 0.0 }
        val raw = byGroup.mapValues { (it.value * 100).roundToInt() }.toMutableMap()
        // 四舍五入后补差,保证合计 100
        val diff = 100 - raw.values.sum()
        raw.maxByOrNull { it.value }?.let { raw[it.key] = it.value + diff }
        return raw.entries.filter { it.value > 0 }.sortedByDescending { it.value }.associate { it.key.label to it.value }
    }

    private fun writeSignals(file: File, list: List<Candidate>, r: Int) {
        CsvWriter(file, cfg.csvEncoding).use { w ->
            w.row("信号日", "代码", "名称", "概率", "类型", "买入日", "买入价", "卖出日", "卖出价", "收益(扣成本)", "是否成功", "持有天数", "卖出原因")
            for (c in list) {
                if (!c.tradable) { w.row(c.date, c.symbol, c.name, c.prob, c.pattern.label, "", "", "", "", "", "", "", "买不进(一字涨停)"); continue }
                val net = c.ret[r] - cfg.costPerTrade
                val exitPx = c.entryPrice * (1 + c.ret[r])
                val reason = com.realu.core.strategy.ExitReason.entries[c.reason[r].toInt()]
                w.row(c.date, c.symbol, c.name, c.prob, c.pattern.label, c.entryDate, c.entryPrice, c.exitDate(r), exitPx, net,
                    if (!c.completed(r)) "未完成" else if (cfg.label.isSuccess(net.toDouble())) "是" else "否", c.hold[r], reason.label)
            }
        }
    }

    private fun writeImportance(file: File, imp: DoubleArray) {
        val total = imp.sum().takeIf { it > 0 } ?: 1.0
        CsvWriter(file, cfg.csvEncoding).use { w ->
            w.row("特征", "说明", "分组", "重要性占比")
            for (i in imp.indices.sortedByDescending { imp[it] }) {
                val d = FeatureDefs.ALL[i]
                w.row(d.name, d.label, d.group.label, imp[i] / total)
            }
        }
    }
}
