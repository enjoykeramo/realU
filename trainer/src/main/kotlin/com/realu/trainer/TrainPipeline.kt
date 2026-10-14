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

/**
 * 完整流程:读数据 → 候选样本 + 标签 → 分箱 → GBDT 训练(验证集早停) → 验证集选阈值、选卖出参数
 * → 测试集(从未参与任何选择的时间段)检验 → 判定是否达标 → 导出模型与报告。
 *
 * 时间切分(严格按时间,禁止随机打乱,避免未来数据泄漏):
 *   训练:data.startDate ~ split.trainEnd(且标签窗口必须在 trainEnd 前结束)
 *   验证:trainEnd 之后 ~ split.validEnd(且标签窗口在 validEnd 前结束)
 *   测试:validEnd 之后 ~ split.testEnd
 */
class TrainPipeline(private val cfg: Config, private val runDir: File) {

    fun run(): Boolean {
        val t0 = System.currentTimeMillis()
        val source = DataSource(cfg)
        val stocks = source.listStocks().shuffled(Random(cfg.seed)) // 打乱顺序:万一内存不足截断,也不会只剩某个板块
        require(stocks.isNotEmpty()) { "没有找到任何 A 股数据文件,请检查配置" }
        Console.info("找到 ${stocks.size} 只 A 股数据文件(${stocks.groupingBy { it.code.board.label }.eachCount()})")
        if (!source.hasBigOrder) Console.warn("未提供大单净量数据(data.bigOrderCsv):通达信离线日线不含逐笔/大单数据,将使用 K 线估算的资金流替代特征")
        if (!source.hasFloatShares) Console.warn("未提供流通股本(data.floatSharesCsv):真实换手率特征为空,使用相对换手(量/250日均量)替代")
        val index = source.loadIndex()
        if (index != null) Console.info("大盘指数 ${cfg.indexSymbol}:${index.series.size} 根K线,${index.series.date.first()}~${index.series.date.last()}")

        // ---------- 样本 ----------
        val builder = DatasetBuilder(cfg, source, stocks, index)
        val binner = builder.fitBinner()
        val (ds, stats) = builder.build(binner)
        Console.info("样本构建完成:成功 ${stats.stocksOk} 只,失败 ${stats.stocksFailed} 只;候选日 ${stats.candidates}," +
            "买不进(一字板)${stats.untradable},训练期负样本降采样丢弃 ${stats.droppedNegatives};样本 ${ds.size},正样本 ${ds.positives}")
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
        Console.info("基准命中率(候选日不经模型筛选):${pct(baseRate)} —— 模型的价值在于把它提高到多少")

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
        val yValid = IntArray(validIdx.size) { ds.label[validIdx[it]].toInt() }
        val yTest = IntArray(testIdx.size) { ds.label[testIdx[it]].toInt() }
        val aucValid = Metrics.auc(pValid, yValid)
        val aucTest = if (testIdx.isNotEmpty()) Metrics.auc(pTest, yTest) else Double.NaN
        Console.info("AUC:验证 ${"%.4f".format(aucValid)},测试 ${"%.4f".format(aucTest)}")

        // ---------- 阈值 ----------
        val cooldown = cfg.int("backtest.cooldownDays", 20)
        val choice = Metrics.chooseThresholdDedup(
            pValid, yValid, IntArray(validIdx.size) { ds.stock[validIdx[it]] }, IntArray(validIdx.size) { ds.bar[validIdx[it]] },
            cooldown, cfg.targetPrecision, cfg.minValidSignals,
        )
        Console.info("验证集选定阈值 ${"%.4f".format(choice.threshold)}(同股 $cooldown 日去重):信号 ${choice.signals},命中 ${choice.hits}," +
            "准确率 ${pct(choice.precision)}" + if (choice.achieved) "(达到目标 ${pct(cfg.targetPrecision)})" else "(未达到目标 ${pct(cfg.targetPrecision)},取验证集最高准确率)")
        var testHits = 0; var testSignals = 0
        for (k in testIdx.indices) if (pTest[k] >= choice.threshold) { testSignals++; testHits += yTest[k] }
        Console.info("测试集(样本口径,未去重):信号 $testSignals,命中 $testHits,准确率 ${pct(if (testSignals > 0) testHits.toDouble() / testSignals else Double.NaN)}")

        // ---------- 流式回测 + 卖出参数选择 ----------
        val rules = if (cfg.exitGrid) Backtester.grid(cfg.exit) else listOf(cfg.exit)
        val bt = Backtester(source, stocks, index, cfg.gate, cfg.label, rules, cfg.threads, cooldownDays = cooldown)
        val validFrom = Dates.plusDays(cfg.trainEnd, 1)
        val testFrom = Dates.plusDays(cfg.validEnd, 1)
        val validSignals = bt.run(tr.model, choice.threshold, validFrom, cfg.validEnd) { Console.info(it) }
        val testSignalsList = bt.run(tr.model, choice.threshold, testFrom, cfg.testEnd) { Console.info(it) }

        val ruleIdx = pickExitRule(validSignals, rules)
        val chosenRule = rules[ruleIdx]
        Console.info("卖出规则(验证期选定):${chosenRule.describe()}")

        val validStats = TradeStats(validSignals.mapNotNull { it.trades[ruleIdx] }, cfg.costPerTrade)
        val testStats = TradeStats(testSignalsList.mapNotNull { it.trades[ruleIdx] }, cfg.costPerTrade)
        val vHit = validSignals.count { it.hit }
        val tHit = testSignalsList.count { it.hit }
        val tPrec = if (testSignalsList.isEmpty()) 0.0 else tHit.toDouble() / testSignalsList.size
        val tWilson = Metrics.wilsonLower(tHit, testSignalsList.size)
        val testEndActual = testSignalsList.maxOfOrNull { it.date } ?: testFrom
        val portfolio = Portfolio.simulate(testSignalsList, ruleIdx, cfg.maxPositions, cfg.costPerTrade, testFrom, testEndActual)

        val passed = choice.achieved && testSignalsList.size >= cfg.minTestSignals && tPrec >= cfg.targetPrecision
        val verdict = when {
            passed -> "达标"
            testSignalsList.size < cfg.minTestSignals -> "未达标:测试期信号数 ${testSignalsList.size} < ${cfg.minTestSignals},统计上不可信"
            else -> "未达标:测试期准确率 ${pct(tPrec)} < 目标 ${pct(cfg.targetPrecision)}"
        }

        // ---------- 导出 ----------
        val weights = factorWeights(tr.gainImportance)
        val metrics = linkedMapOf<String, Any?>(
            "verdict" to verdict,
            "definition" to "准确率 = 信号发出后 ${cfg.label.horizon} 个交易日内先涨 ${pct(cfg.label.target, 0)}、而不是先跌 ${pct(cfg.label.stop, 0)} 的比例(次日开盘价买入)",
            "baseRate" to baseRate,
            "aucValid" to aucValid, "aucTest" to aucTest,
            "valid" to linkedMapOf("signals" to validSignals.size, "hits" to vHit, "precision" to if (validSignals.isEmpty()) 0.0 else vHit.toDouble() / validSignals.size,
                "avgReturn" to validStats.mean, "winRate" to validStats.winRate),
            "test" to linkedMapOf("from" to testFrom, "to" to testEndActual, "signals" to testSignalsList.size, "hits" to tHit, "precision" to tPrec,
                "precisionLower95" to tWilson, "avgReturn" to testStats.mean, "medianReturn" to testStats.median,
                "winRate" to testStats.winRate, "gain30Rate" to testStats.gain30Rate, "worstTrade" to testStats.worst,
                "avgHoldDays" to testStats.avgHold),
            "portfolio" to linkedMapOf("slots" to cfg.maxPositions, "finalEquity" to portfolio.finalEquity,
                "annualized" to portfolio.annualized, "maxDrawdown" to portfolio.maxDrawdown, "trades" to portfolio.taken),
            "byPattern" to Pattern.entries.associate { p ->
                val list = testSignalsList.filter { it.pattern == p }
                p.label to linkedMapOf("signals" to list.size, "precision" to if (list.isEmpty()) 0.0 else list.count { it.hit }.toDouble() / list.size)
            },
        )
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
        val model = RealuModel(
            name = cfg.modelName, strategyId = cfg.strategyId,
            createdAt = LocalDateTime.now().toString(),
            featureVersion = FeatureDefs.VERSION, featureNames = FeatureDefs.NAMES,
            gate = cfg.gate, label = cfg.label, exit = chosenRule, gbdt = tr.model,
            threshold = choice.threshold, passed = passed, metrics = metrics, factorWeights = weights,
            dataInfo = linkedMapOf(
                "source" to cfg.dataSource, "stocks" to stats.stocksOk, "samples" to ds.size,
                "startDate" to cfg.startDate, "trainEnd" to cfg.trainEnd, "validEnd" to cfg.validEnd,
                "bigOrderData" to source.hasBigOrder, "floatShares" to source.hasFloatShares, "index" to cfg.indexSymbol,
                "trees" to tr.model.trees.size,
            ),
        )
        val modelFile = File(runDir, "model-$stamp" + (if (passed) "" else "-NOT-PASSED") + ".json") // 文件名只用 ASCII,避免系统编码不一致时出现乱码文件名
        modelFile.writeText(model.toJson(), Charsets.UTF_8)
        // 自检:导出的文件能被 APP 端同一套代码读回
        RealuModel.fromJson(modelFile.readText(Charsets.UTF_8)).checkCompatible()

        writeSignals(File(runDir, "signals_valid.csv"), validSignals, ruleIdx)
        writeSignals(File(runDir, "signals_test.csv"), testSignalsList, ruleIdx)
        writeImportance(File(runDir, "feature_importance.csv"), tr.gainImportance)
        Report.write(File(runDir, "report.md"), cfg, model, metrics, validStats, testStats, portfolio, choice, testSignalsList, ruleIdx, rules, validSignals)

        Console.raw("")
        Console.raw("================ 结果 ================")
        Console.raw("测试期 ${Dates.format(testFrom)} ~ ${Dates.format(testEndActual)}(训练与调参从未使用)")
        Console.raw("信号 ${testSignalsList.size} 个,命中(先涨${pct(cfg.label.target, 0)})${tHit} 个,准确率 ${pct(tPrec)}(95%置信下限 ${pct(tWilson)})")
        Console.raw("按卖出规则:平均收益 ${pct(testStats.mean)},中位数 ${pct(testStats.median)},胜率 ${pct(testStats.winRate)},收益≥30% 占比 ${pct(testStats.gain30Rate)}")
        Console.raw("组合(${cfg.maxPositions}仓):年化 ${pct(portfolio.annualized)},最大回撤 ${pct(portfolio.maxDrawdown)}")
        Console.raw("结论:$verdict")
        Console.raw("模型:${modelFile.absolutePath}")
        Console.raw("报告:${File(runDir, "report.md").absolutePath}")
        Console.raw("耗时 ${(System.currentTimeMillis() - t0) / 1000} 秒")
        return passed
    }

    /** 在验证期按"扣费后平均收益"挑选卖出规则;交易数太少时保留配置的默认规则。 */
    private fun pickExitRule(signals: List<SignalRecord>, rules: List<ExitRule>): Int {
        if (rules.size == 1 || signals.size < 20) return 0
        var best = 0
        var bestMean = Double.NEGATIVE_INFINITY
        for (k in rules.indices) {
            val st = TradeStats(signals.mapNotNull { it.trades[k] }, cfg.costPerTrade)
            if (st.n >= 20 && st.mean > bestMean) { bestMean = st.mean; best = k }
        }
        return best
    }

    private fun factorWeights(imp: DoubleArray): Map<String, Int> {
        val total = imp.sum().takeIf { it > 0 } ?: return emptyMap()
        val byGroup = FeatureGroup.entries.associateWith { g ->
            FeatureDefs.ALL.indices.filter { FeatureDefs.ALL[it].group == g }.sumOf { imp[it] } / total
        }.filterValues { it > 0.0 }
        val raw = byGroup.mapValues { (it.value * 100).roundToInt() }.toMutableMap()
        // 四舍五入后补差,保证合计 100
        val diff = 100 - raw.values.sum()
        raw.maxByOrNull { it.value }?.let { raw[it.key] = it.value + diff }
        return raw.entries.sortedByDescending { it.value }.associate { it.key.label to it.value }
    }

    private fun writeSignals(file: File, list: List<SignalRecord>, ruleIdx: Int) {
        CsvWriter(file, cfg.csvEncoding).use { w ->
            w.row("日期", "代码", "名称", "概率", "类型", "标签结果", "窗口内最大涨幅", "买入日", "买入价", "卖出日", "卖出价", "收益", "持有天数", "卖出原因")
            for (s in list) {
                val t = s.trades[ruleIdx]
                w.row(s.date, s.symbol, s.name, s.probability, s.pattern.label, s.outcome.name, s.labelMaxGain,
                    t?.entryDate, t?.entryPrice, t?.exitDate, t?.exitPrice, t?.ret, t?.holdDays, t?.reason?.label ?: "买不进")
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
