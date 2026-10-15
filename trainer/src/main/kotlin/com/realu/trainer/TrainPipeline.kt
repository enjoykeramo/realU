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

/** 一个特征方案的训练结果(特征组筛选)。 */
class VariantResult(val name: String, val excludedGroups: Set<FeatureGroup>, val aucValid: Double, val bestRound: Int)

/** 滚动重训的一年。 */
class WalkYear(val year: Int, val trainRows: Int, val thresholds: DoubleArray, val stats: TradeStats)

/** 报告所需的全部结果。 */
class EvalSummary(
    val baseRate: Double,
    val aucValid: Double,
    val aucTest: Double,
    val rules: List<ExitRule>,
    val selection: Selection,
    val validCandidates: List<Candidate>,
    val validSignals: List<Candidate>,
    val testSignals: List<Candidate>,
    /** 测试期候选只算了选定的卖出规则,规则下标与验证期不同。 */
    val testRuleIdx: Int,
    val testStats: TradeStats,
    val portfolio: PortfolioResult,
    val testFrom: Int,
    val testTo: Int,
    val verdict: String,
    val passed: Boolean,
    val marketSnapshot: Map<String, Float>,
    val sectorSource: String,
    val variants: List<VariantResult>,
    val chosenVariant: VariantResult,
    val walkYears: List<WalkYear>,
    val exportNote: String,
    val dateBalance: String,
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

        // ---------- 训练:按交易日平衡样本权重 + 特征组筛选 ----------
        val weights = dateWeights(ds, cfg.dateBalance)
        val yValid = IntArray(validIdx.size) { ds.label[validIdx[it]].toInt() }
        val yTest = IntArray(testIdx.size) { ds.label[testIdx[it]].toInt() }
        val marketWide = setOf(FeatureGroup.MARKET, FeatureGroup.SENTIMENT)
        val plans = buildList {
            add("全部特征" to emptySet<FeatureGroup>())
            if (cfg.groupSelect) {
                add("去掉全市场类(市场环境+市场情绪)" to marketWide)
                if (sectors?.hasConcepts == true) add("去掉全市场类与概念" to marketWide + FeatureGroup.CONCEPT)
            }
        }
        val variants = ArrayList<VariantResult>()
        var best: Triple<VariantResult, TrainResult, Pair<DoubleArray, DoubleArray>>? = null
        var bestMask = BooleanArray(FeatureDefs.COUNT)
        for ((name, groups) in plans) {
            Console.info("—— 特征方案:$name ——")
            val mask = BooleanArray(FeatureDefs.COUNT) { FeatureDefs.ALL[it].group in groups }
            val trainer = GbdtTrainer(ds, binner, cfg.gbdt.copy(excluded = mask), cfg.threads, weights)
            val tr = try {
                trainer.train(trainIdx, validIdx) { Console.info(it) }
            } catch (e: OutOfMemoryError) {
                trainer.close()
                throw IllegalStateException("训练时内存不足:请调大 -Xmx,或调小 sample.maxRows / sample.negativeKeep / gbdt.maxBins", e)
            }
            val pv = trainer.predict(validIdx)
            val pt = trainer.predict(testIdx)
            trainer.close()
            val auc = Metrics.auc(pv, yValid)
            val v = VariantResult(name, groups, auc, tr.bestRound)
            variants += v
            Console.info("特征方案「$name」验证 AUC ${"%.4f".format(auc)}(${tr.bestRound} 棵树)")
            if (best == null || auc > best.first.aucValid) { best = Triple(v, tr, pv to pt); bestMask = mask }
        }
        val (chosen, tr, preds) = best!!
        val (pValid, pTest) = preds
        val params = cfg.gbdt.copy(excluded = bestMask)
        val aucValid = chosen.aucValid
        val aucTest = if (testIdx.isNotEmpty()) Metrics.auc(pTest, yTest) else Double.NaN
        Console.info("选用特征方案「${chosen.name}」;AUC:验证 ${"%.4f".format(aucValid)},测试(同一模型)${"%.4f".format(aucTest)}")
        if (aucValid < 0.56) Console.warn("验证 AUC 很低(${"%.3f".format(aucValid)}):现有特征对该目标的预测力很弱,95% 准确率不现实,建议调整目标或候选条件")

        // ---------- 验证期:扫描候选,同时选卖出规则与各类型阈值 ----------
        val rules = if (cfg.exitGrid) CandidateScanner.grid(label, cfg.exit) else listOf(cfg.exit)
        val minProb = Metrics.quantile(pValid, 1 - cfg.dbl("eval.candidateTop", 0.15))
        val validFrom = Dates.plusDays(cfg.trainEnd, 1)
        val testFrom = Dates.plusDays(cfg.validEnd, 1)
        val validCands = CandidateScanner(source, stocks, index, market, cfg.gate, rules, cfg.threads)
            .run(tr.model, minProb, validFrom, cfg.validEnd) { Console.info(it) }
        Console.info("验证期候选信号 ${validCands.size}(概率 ≥ ${"%.4f".format(minProb)},${rules.size} 组卖出规则)")
        val cooldown = cfg.int("backtest.cooldownDays", 20)
        val objective = cfg.str("exit.objective", "precision").lowercase()
        val sel = Selector.select(validCands, rules, label, cfg.targetPrecision, cfg.minValidSignals, cfg.minPatternSignals,
            cooldown, objective, cfg.validEnd, cfg.perPatternThreshold)
        val rule = rules[sel.ruleIdx]
        Console.info("验证期选定卖出规则:${rule.describe()}")
        for (p in Pattern.entries) {
            val st = sel.perPattern[p]
            Console.info("  ${p.label}:" + if (!sel.enabled(p)) "不启用" else "阈值 ${"%.4f".format(sel.threshold(p))},信号 ${st?.n},准确率 ${pct(st?.precision ?: 0.0)}")
        }
        Console.info("验证期合计:信号 ${sel.stats.n},成功 ${sel.stats.hits},准确率 ${pct(sel.stats.precision)}(95%下限 ${pct(sel.stats.lower95)})" +
            if (sel.achieved) ",达到目标" else ",未达到目标 ${pct(cfg.targetPrecision)}")
        val validPatterns = ByteArray(validIdx.size) { ds.pattern[validIdx[it]] }
        val fractions = ThresholdMapper.fractions(sel, pValid, validPatterns)
        val rounds = maxOf(50, (tr.bestRound * 1.1).toInt())
        val lastDate = maxOf(index?.series?.date?.last() ?: 0, ds.date.maxOrNull() ?: 0)
        val testTo0 = minOf(cfg.testEnd, lastDate)

        // ---------- 测试期:按年滚动重训(或沿用验证模型) ----------
        val testScanner = CandidateScanner(source, stocks, index, market, cfg.gate, listOf(rule), cfg.threads)
        val walkYears = ArrayList<WalkYear>()
        val testSignals: List<Candidate>
        if (cfg.walkForward && testFrom <= testTo0) {
            val segs = ArrayList<CandidateScanner.Segment>()
            val yearTh = ArrayList<Pair<Int, DoubleArray>>()
            val yearRows = HashMap<Int, Int>()
            for (y in testFrom / 10000..testTo0 / 10000) {
                val y0 = y * 10000 + 101
                val trainY = ds.date.indices.filter { ds.endDate[it] < y0 }.toIntArray()
                val refY = ds.date.indices.filter { ds.date[it] >= y0 - 10000 && ds.date[it] < y0 }.toIntArray()
                Console.info("滚动重训 $y 年:训练样本 ${trainY.size}(标签在 $y 年之前结束),$rounds 棵树")
                val (mY, pRef) = fitFixed(ds, binner, params, weights, trainY, refY, rounds)
                val th = ThresholdMapper.map(fractions, pRef, ByteArray(refY.size) { ds.pattern[refY[it]] })
                segs += CandidateScanner.Segment(mY, th.minOrNull() ?: 1.0, maxOf(y0, testFrom), minOf(y * 10000 + 1231, testTo0))
                yearTh += y to th
                yearRows[y] = trainY.size
            }
            val cands = testScanner.run(segs) { Console.info(it) }
            val thOf = { c: Candidate -> yearTh.first { it.first == c.date / 10000 }.second[c.pattern.ordinal] }
            testSignals = Selector.dedupe(cands, thOf, cooldown)
            for ((y, th) in yearTh) walkYears += WalkYear(y, yearRows.getValue(y), th, TradeStats(testSignals.filter { it.date / 10000 == y }, 0, label))
        } else {
            val cands = testScanner.run(tr.model, sel.minThreshold, testFrom, cfg.testEnd) { Console.info(it) }
            testSignals = Selector.dedupe(cands, { c -> sel.threshold(c.pattern) }, cooldown)
        }
        val validSignals = Selector.dedupe(validCands, sel, cooldown)
        val ts = TradeStats(testSignals, 0, label)
        val testTo = testSignals.maxOfOrNull { it.date } ?: testFrom
        val portfolio = Portfolio.simulate(testSignals, 0, cfg.maxPositions, cfg.costPerTrade, testFrom)
        val passed = sel.achieved && ts.n >= cfg.minTestSignals && ts.precision >= cfg.targetPrecision
        val verdict = when {
            passed -> "达标"
            !sel.achieved -> "未达标:验证期在信号数足够的前提下,准确率达不到 ${pct(cfg.targetPrecision)}(最优配置 ${pct(sel.stats.precision)},95%下限 ${pct(sel.stats.lower95)})"
            ts.n < cfg.minTestSignals -> "未达标:测试期已完成信号 ${ts.n} 个 < ${cfg.minTestSignals},统计上不可信"
            else -> "未达标:测试期准确率 ${pct(ts.precision)} < 目标 ${pct(cfg.targetPrecision)}"
        }

        // ---------- 导出模型:用全部数据重训 ----------
        var exportModel = tr.model
        var exportTh = sel.thresholds
        var importance = tr.gainImportance
        val exportNote: String
        if (cfg.refitAll) {
            val all = ds.date.indices.toList().toIntArray()
            val ref = ds.date.indices.filter { ds.date[it] >= lastDate - 10000 }.toIntArray()
            Console.info("导出模型:用全部 ${all.size} 个样本重训($rounds 棵树)")
            val trainer = GbdtTrainer(ds, binner, params.copy(rounds = rounds), cfg.threads, weights)
            val trF = trainer.train(all, IntArray(0)) { Console.info(it) }
            val pRef = trainer.predict(ref)
            trainer.close()
            exportModel = trF.model
            importance = trF.gainImportance
            exportTh = ThresholdMapper.map(fractions, pRef, ByteArray(ref.size) { ds.pattern[ref[it]] })
            exportNote = "导出模型用全部已有数据(${Dates.format(cfg.startDate)} ~ ${Dates.format(lastDate)},${all.size} 个样本)重训,阈值按最近一年候选的分位数迁移"
        } else {
            exportNote = "导出模型即验证期模型(训练数据截至 ${Dates.format(cfg.trainEnd)})"
        }
        val lastDateMkt = index?.series?.date?.last() ?: testTo
        val summary = EvalSummary(baseRate, aucValid, aucTest, rules, sel, validCands, validSignals, testSignals, 0, ts, portfolio,
            testFrom, testTo, verdict, passed, market.marketAt(lastDateMkt), sectors?.source ?: "none",
            variants, chosen, walkYears, exportNote, cfg.dateBalance)

        val weightsByGroup = factorWeights(importance)
        val metrics = linkedMapOf<String, Any?>(
            "verdict" to verdict,
            "definition" to "准确率 = 已完成交易周期中,按卖出规则卖出时(收盘价)扣成本盈利 ≥ ${pct(label.target, 0)} 的比例;同股 $cooldown 日内只计一次",
            "target" to label.describe(),
            "baseRate" to baseRate,
            "aucValid" to aucValid, "aucTest" to aucTest,
            "featurePlan" to chosen.name,
            "valid" to statsMap(sel.stats),
            "test" to statsMap(ts) + mapOf("from" to testFrom, "to" to testTo, "walkForward" to cfg.walkForward),
            "portfolio" to linkedMapOf("slots" to cfg.maxPositions, "finalEquity" to portfolio.finalEquity,
                "annualized" to portfolio.annualized, "maxDrawdown" to portfolio.maxDrawdown, "trades" to portfolio.taken),
            "byPattern" to Pattern.entries.associate { p ->
                val st = TradeStats(testSignals.filter { it.pattern == p }, 0, label)
                p.label to linkedMapOf("enabled" to sel.enabled(p), "signals" to st.n, "precision" to st.precision)
            },
            "export" to exportNote,
        )
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
        val enabledTh = exportTh.filter { it <= 1.0 }
        val model = RealuModel(
            name = cfg.modelName, strategyId = cfg.strategyId,
            createdAt = LocalDateTime.now().toString(),
            featureVersion = FeatureDefs.VERSION, featureNames = FeatureDefs.NAMES,
            gate = cfg.gate, label = label, exit = rule, gbdt = exportModel,
            threshold = enabledTh.minOrNull() ?: Selector.DISABLED, passed = passed, metrics = metrics, factorWeights = weightsByGroup,
            dataInfo = linkedMapOf(
                "source" to cfg.dataSource, "stocks" to stats.stocksOk, "samples" to ds.size,
                "startDate" to cfg.startDate, "trainEnd" to cfg.trainEnd, "validEnd" to cfg.validEnd, "lastDate" to lastDate,
                "sectorSource" to (sectors?.source ?: "none"), "concepts" to (sectors?.conceptNames?.size ?: 0),
                "floatShares" to source.hasFloatShares, "index" to cfg.indexSymbol, "trees" to exportModel.trees.size,
                "featurePlan" to chosen.name, "refitAll" to cfg.refitAll,
            ),
            sectors = sectors?.latest(),
            patternThresholds = Pattern.entries.associate { it.name to exportTh[it.ordinal] },
        )
        val modelFile = File(runDir, "model-$stamp" + (if (passed) "" else "-NOT-PASSED") + ".json") // 文件名只用 ASCII,避免系统编码不一致时出现乱码文件名
        modelFile.writeText(model.toJson(), Charsets.UTF_8)
        // 自检:导出的文件能被 APP 端同一套代码读回
        RealuModel.fromJson(modelFile.readText(Charsets.UTF_8)).checkCompatible()

        writeSignals(File(runDir, "signals_valid.csv"), validSignals, sel.ruleIdx)
        writeSignals(File(runDir, "signals_test.csv"), testSignals, 0)
        writeImportance(File(runDir, "feature_importance.csv"), importance)
        Report.write(File(runDir, "report.md"), cfg, model, summary)

        Console.raw("")
        Console.raw("================ 结果 ================")
        Console.raw("目标:${label.describe()}")
        Console.raw("特征方案:${chosen.name};验证 AUC ${"%.4f".format(aucValid)}")
        Console.raw("测试期 ${Dates.format(testFrom)} ~ ${Dates.format(testTo)}(" + (if (cfg.walkForward) "按年滚动重训," else "") + "从未参与任何选择)")
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

    /** 固定轮数训练(滚动重训、最终重训用),返回模型与参考样本的预测。 */
    private fun fitFixed(ds: Dataset, binner: Binner, params: GbdtParams, weights: FloatArray?, rows: IntArray, ref: IntArray,
                         rounds: Int): Pair<com.realu.core.model.GbdtModel, DoubleArray> {
        val trainer = GbdtTrainer(ds, binner, params.copy(rounds = rounds), cfg.threads, weights)
        try {
            val t = trainer.train(rows, IntArray(0)) { Console.info(it) }
            return t.model to trainer.predict(ref)
        } finally {
            trainer.close()
        }
    }

    /** 按交易日平衡的样本权重,平均值归一为 1。 */
    private fun dateWeights(ds: Dataset, mode: String): FloatArray? {
        if (mode == "none") return null
        val count = HashMap<Int, Int>()
        for (d in ds.date) count.merge(d, 1, Int::plus)
        val w = FloatArray(ds.size) { val c = count.getValue(ds.date[it]).toDouble(); (if (mode == "equal") 1 / c else 1 / Math.sqrt(c)).toFloat() }
        val mean = w.average().toFloat()
        for (i in w.indices) w[i] /= mean
        Console.info("样本权重按交易日平衡($mode):单日候选最多 ${count.values.maxOrNull()} 个、中位数 ${count.values.sorted()[count.size / 2]} 个")
        return w
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
