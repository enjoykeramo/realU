package com.realu.trainer

import com.realu.core.strategy.ExitRule
import com.realu.core.strategy.GateParams
import com.realu.core.strategy.LabelParams
import com.realu.core.tdx.TdxTxtReader
import java.io.File
import java.io.StringReader
import java.util.Properties

/**
 * 训练配置(config.properties)。用记事本保存成 ANSI(GBK)或 UTF-8 都能正确读取。
 * 所有键见 scripts/config.example.properties。
 */
class Config(private val props: Properties, val file: File?) {

    fun str(key: String, def: String): String = props.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() } ?: def
    fun strOrNull(key: String): String? = props.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
    fun int(key: String, def: Int): Int = parse(key, def) { it.toInt() }
    fun dbl(key: String, def: Double): Double = parse(key, def) { it.toDouble() }
    fun bool(key: String, def: Boolean): Boolean = parse(key, def) {
        when (it.lowercase()) { "true", "yes", "1", "是" -> true; "false", "no", "0", "否" -> false; else -> error("") }
    }
    fun list(key: String, def: List<String>): List<String> =
        strOrNull(key)?.split(',', ';', ',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: def

    private fun <T> parse(key: String, def: T, f: (String) -> T): T {
        val raw = strOrNull(key) ?: return def
        return try { f(raw) } catch (e: Exception) { throw IllegalArgumentException("配置项 $key 的值 \"$raw\" 格式不正确") }
    }

    // ---- 数据 ----
    val vipdoc: String? get() = strOrNull("tdx.vipdoc")
    val dataSource: String get() = str("data.source", "day").lowercase()
    val txtDir: String? get() = strOrNull("data.txtDir")
    val markets: List<String> get() = list("data.markets", listOf("sh", "sz", "bj")).map { it.lowercase() }
    val adjust: Boolean get() = bool("data.adjustExRights", true)
    val startDate: Int get() = int("data.startDate", 20100101)
    val indexSymbol: String get() = str("data.index", "sh000001")
    val namesCsv: String? get() = strOrNull("data.namesCsv")
    val floatSharesCsv: String? get() = strOrNull("data.floatSharesCsv")
    val bigOrderCsv: String? get() = strOrNull("data.bigOrderCsv")
    /** 通达信安装目录(含 T0002/hq_cache);留空时自动取 vipdoc 的上一级目录。 */
    val tdxRoot: String? get() = strOrNull("data.tdxRoot")

    // ---- 板块 ----
    /** auto:有 hq_cache/tdxhy.cfg 用通达信行业,否则用收益相关性聚类;industry / cluster / none。 */
    val sectorSource: String get() = str("sector.source", "auto").lowercase()
    val sectorClusters: Int get() = int("sector.clusters", 40)
    /** 通达信行业层级:1 = 一级行业,2 = 二级行业,3 = 三级行业。 */
    val industryLevel: Int get() = int("sector.industryLevel", 2)
    /** 使用概念板块(hq_cache/block_gn.dat)。注意:概念成分是"现在"的名单,用于历史回测有未来函数。 */
    val useConcepts: Boolean get() = bool("sector.useConcepts", true)

    // ---- 时间切分 ----
    val trainEnd: Int get() = int("split.trainEnd", 20191231)
    val validEnd: Int get() = int("split.validEnd", 20221231)
    val testEnd: Int get() = int("split.testEnd", 99991231)

    // ---- 标签/候选/卖出 ----
    val label: LabelParams
        get() = LabelParams(
            horizon = int("label.horizon", 60),
            target = dbl("label.target", 0.30),
            maxDrawdown = dbl("label.maxDrawdown", 0.15),
            cost = costPerTrade,
        )

    val gate: GateParams
        get() = GateParams(
            minListedDays = int("gate.minListedDays", 120),
            minAmount5 = dbl("gate.minAmount5", 3e7),
            minRet1 = dbl("gate.minRet1", 0.03),
            minVolRatio = dbl("gate.minVolRatio", 1.8),
            maxPriorRise20 = dbl("gate.maxPriorRise20", 0.25),
            requireUpClose = bool("gate.requireUpClose", true),
            excludeSt = bool("gate.excludeSt", true),
            resumeCooldown = int("gate.resumeCooldown", 5),
        )

    val exit: ExitRule
        get() = label.let { lb ->
            ExitRule(
                stopLoss = dbl("exit.stopLoss", lb.maxDrawdown),
                takeProfit = dbl("exit.takeProfit", lb.target + lb.cost),
                trailingActivate = dbl("exit.trailingActivate", 0.0),
                trailingDrawdown = dbl("exit.trailingDrawdown", 0.0),
                profitFloor = dbl("exit.profitFloor", 0.0),
                maxHoldDays = int("exit.maxHoldDays", lb.horizon),
            )
        }

    /** 是否在验证集上网格搜索最优卖出参数(只在验证集选,测试集只用来检验)。 */
    val exitGrid: Boolean get() = bool("exit.gridSearch", true)

    // ---- 采样与内存 ----
    val negativeKeep: Double get() = dbl("sample.negativeKeep", 1.0)
    val maxRows: Int get() = int("sample.maxRows", 6_000_000)
    val memoryLimit: Double get() = dbl("memory.maxUsage", 0.80)
    val threads: Int get() = int("threads", 0).let { if (it <= 0) Runtime.getRuntime().availableProcessors() else it }
    val seed: Long get() = int("seed", 20240601).toLong()

    // ---- GBDT ----
    val gbdt: GbdtParams
        get() = GbdtParams(
            rounds = int("gbdt.rounds", 1000),
            learningRate = dbl("gbdt.learningRate", 0.03),
            maxLeaves = int("gbdt.maxLeaves", 15),
            maxDepth = int("gbdt.maxDepth", 5),
            minDataInLeaf = int("gbdt.minDataInLeaf", 500),
            minSumHessian = dbl("gbdt.minSumHessian", 1e-3),
            lambdaL2 = dbl("gbdt.lambdaL2", 5.0),
            rowSample = dbl("gbdt.rowSample", 0.7),
            featureSample = dbl("gbdt.featureSample", 0.6),
            earlyStopping = int("gbdt.earlyStopping", 100),
            maxBins = int("gbdt.maxBins", 64).coerceIn(4, 250),
            positiveWeight = dbl("gbdt.positiveWeight", 1.0),
            seed = seed,
            earlyStopMetric = str("gbdt.earlyStopMetric", "auc"),
            evalEvery = int("gbdt.evalEvery", 5).coerceAtLeast(1),
        )

    /** 按交易日平衡样本权重:sqrt(默认,权重 ∝ 1/√当日样本数)/ equal(每日总权重相同)/ none。 */
    val dateBalance: String get() = str("gbdt.dateBalance", "sqrt").lowercase()
    /** 自动比较"全部特征"与"去掉全市场类特征"等方案,按验证集 AUC 选优。 */
    val groupSelect: Boolean get() = bool("gbdt.groupSelect", true)
    /** 测试期按年滚动重训(每年用之前的全部数据训练),更接近实盘的定期重训。 */
    val walkForward: Boolean get() = bool("eval.walkForward", true)
    /** 导出前用全部已有数据(含验证、测试期)重训一次,APP 拿到的是"见过最新行情"的模型。 */
    val refitAll: Boolean get() = bool("model.refitAll", true)
    /** 每种启动类型单独选阈值。 */
    val perPatternThreshold: Boolean get() = bool("eval.perPatternThreshold", true)

    // ---- 达标线 ----
    val targetPrecision: Double get() = dbl("target.precision", 0.95)
    val minValidSignals: Int get() = int("target.minValidSignals", 30)
    val minTestSignals: Int get() = int("target.minTestSignals", 30)
    /** 每种启动类型在验证期至少要有的信号数。 */
    val minPatternSignals: Int get() = int("target.minPatternSignals", 15)

    // ---- 回测 ----
    val maxPositions: Int get() = int("backtest.maxPositions", 5)
    val costPerTrade: Double get() = dbl("backtest.cost", 0.003)

    // ---- 输出 ----
    val outputDir: File get() = File(str("output.dir", "out"))
    val modelName: String get() = str("model.name", "主升初期")
    val strategyId: String get() = str("model.strategyId", "i")
    val csvEncoding: String get() = str("output.csvEncoding", "UTF-8-BOM")
    val consoleEncoding: String get() = str("console.encoding", "auto")

    companion object {
        fun load(file: File?): Config {
            val props = Properties()
            if (file != null) {
                require(file.isFile) { "找不到配置文件 ${file.absolutePath}" }
                // 先按 UTF-8 严格解码,失败再按 GBK(记事本"ANSI"),避免中文路径乱码
                props.load(StringReader(TdxTxtReader.decode(file.readBytes()).replace("\\", "/")))
            }
            return Config(props, file)
        }
    }
}
