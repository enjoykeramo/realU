package com.realu.trainer

import com.realu.core.data.Dates
import com.realu.core.model.RealuModel
import com.realu.core.strategy.SignalEngine
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

private const val USAGE = """
realU 主升初期模型训练器

用法:
  realu-trainer version                                   显示版本与构建时间
  realu-trainer train    [config.properties]              训练 + 历史检验 + 导出模型(默认读当前目录的 config.properties)
  realu-trainer check    <config.properties>              数据体检(文件数、日期范围、坏数据)
  realu-trainer backtest <config.properties> <model.json> [起始yyyyMMdd] [结束yyyyMMdd]
  realu-trainer predict  <config.properties> <model.json> [日期yyyyMMdd]   列出某日信号
  realu-trainer demo     <输出目录> [--vipdoc-only] [--stocks N] [--days N]   生成模拟通达信数据,用于验证安装
"""

fun main(args: Array<String>) {
    val cmd = args.getOrNull(0)?.lowercase()
    if (cmd == null || cmd in setOf("-h", "--help", "help")) {
        Console.init()
        Console.raw(USAGE)
        return
    }
    val code = try {
        when (cmd) {
            "demo" -> { Console.init(); demo(args); 0 }
            "version", "-v", "--version" -> { Console.init(); Console.raw(versionText()); 0 }
            else -> {
                // 不写配置文件时使用当前目录下的 config.properties
                Console.init() // 先初始化,读配置出错时的提示也能正常显示中文
                val cfg = Config.load(File(args.getOrNull(1) ?: "config.properties"))
                if (!cfg.consoleEncoding.equals("auto", true)) Console.init(cfg.consoleEncoding)
                when (cmd) {
                    "train" -> train(cfg)
                    "check" -> { check(cfg); 0 }
                    "backtest" -> { backtest(cfg, args); 0 }
                    "predict" -> { predict(cfg, args); 0 }
                    else -> { Console.raw(USAGE); 2 }
                }
            }
        }
    } catch (e: OutOfMemoryError) {
        Console.error("内存不足(OutOfMemoryError)。请在 train.bat 中把 -Xmx 调大(建议物理内存的 60%~70%)," +
            "或在配置中调小 sample.maxRows、sample.negativeKeep、gbdt.maxBins、threads")
        3
    } catch (e: IllegalArgumentException) {
        Console.error(e.message ?: e.toString())
        if (System.getenv("REALU_DEBUG") != null) e.printStackTrace()
        2
    } catch (e: IllegalStateException) {
        Console.error(e.message ?: e.toString())
        e.cause?.let { Console.error("原因:$it") }
        if (System.getenv("REALU_DEBUG") != null) e.printStackTrace()
        2
    } catch (e: Exception) {
        Console.error("未预期的异常:$e")
        e.printStackTrace()
        1
    } finally {
        Console.closeLog()
    }
    exitProcess(code)
}

private fun runDir(cfg: Config, tag: String): File {
    val dir = File(cfg.outputDir, "$tag-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")))
    dir.mkdirs()
    Console.openLog(File(dir, "$tag.log"))
    return dir
}

private fun banner(cfg: Config) {
    Console.info(versionText())
    Console.info("配置文件:${cfg.file?.absolutePath}")
    Console.info("JVM 最大内存 ${MemoryGuard.maxMb()}MB,线程 ${cfg.threads},控制台编码 ${Console.charset}")
    if (MemoryGuard.maxMb() < 2048) Console.warn("JVM 最大内存不足 2GB,全市场训练可能内存不足,建议 -Xmx6g 以上")
}

private fun train(cfg: Config): Int {
    val dir = runDir(cfg, "train")
    banner(cfg)
    val passed = TrainPipeline(cfg, dir).run()
    return if (passed) 0 else 10
}

private fun check(cfg: Config) {
    val dir = runDir(cfg, "check")
    banner(cfg)
    val source = DataSource(cfg)
    val stocks = source.listStocks()
    Console.info("A 股文件 ${stocks.size} 个")
    var ok = 0; var bad = 0; var adjusted = 0
    val lastDates = HashMap<Int, Int>()
    var minDate = Int.MAX_VALUE; var maxDate = 0; var bars = 0L
    CsvWriter(File(dir, "data_check.csv"), cfg.csvEncoding).use { w ->
        w.row("代码", "名称", "K线数", "起始", "结束", "问题")
        stocks.forEachIndexed { i, sf ->
            val r = source.load(sf)
            val s = r.series
            if (s == null) bad++ else {
                ok++
                bars += s.size
                minDate = minOf(minDate, s.date.first()); maxDate = maxOf(maxDate, s.date.last())
                lastDates.merge(s.date.last(), 1, Int::plus)
                if (r.warnings.any { it.contains("除权") }) adjusted++
            }
            if (s == null || r.warnings.isNotEmpty()) w.row(sf.code, s?.name, s?.size, s?.date?.first(), s?.date?.last(), r.warnings.joinToString(";"))
            if ((i + 1) % 1000 == 0) Console.info("已检查 ${i + 1}/${stocks.size}")
        }
    }
    Console.info("可用 $ok,不可用 $bad,K线总数 $bars,日期范围 ${if (ok > 0) "${Dates.format(minDate)} ~ ${Dates.format(maxDate)}" else "-"}")
    Console.info("最常见的最后交易日:" + lastDates.entries.sortedByDescending { it.value }.take(3).joinToString { "${it.key}(${it.value}只)" })
    if (cfg.dataSource == "day") Console.info("启发式除权处理涉及 $adjusted 只股票(.day 为不复权数据;精确复权请用前复权 txt 导出)")
    if (source.loadIndex() == null) Console.warn("缺少大盘指数数据")
    Console.info("明细:${File(dir, "data_check.csv").absolutePath}")
}

private fun loadModel(path: String?): RealuModel {
    val f = File(path ?: error("缺少模型文件参数"))
    require(f.isFile) { "找不到模型文件 ${f.absolutePath}" }
    return RealuModel.fromJson(f.readText(Charsets.UTF_8)).also { it.checkCompatible() }
}

private fun backtest(cfg: Config, args: Array<String>) {
    val dir = runDir(cfg, "backtest")
    banner(cfg)
    val model = loadModel(args.getOrNull(2))
    val from = args.getOrNull(3)?.toIntOrNull() ?: Dates.plusDays(cfg.validEnd, 1)
    val to = args.getOrNull(4)?.toIntOrNull() ?: 99991231
    val source = DataSource(cfg)
    val stocks = source.listStocks()
    val index = source.loadIndex()
    if (model.sectors?.source == "cluster") Console.warn("聚类板块使用模型中的最新归属,对很早的历史区间并不严格(当时的板块划分可能不同)")
    val (market, _) = MarketPrep.build(cfg, source, stocks, index, fixedSectors = model.sectors, resolveSectors = false)
    val scanner = CandidateScanner(source, stocks, index, market, model.gate, listOf(model.exit), cfg.threads)
    val cands = scanner.run(model.gbdt, model.threshold, from, to) { Console.info(it) }
    val sig = Selector.dedupe(cands, { c -> model.thresholdFor(c.pattern.name) }, cfg.int("backtest.cooldownDays", 20))
    val st = TradeStats(sig, 0, model.label)
    val pf = Portfolio.simulate(sig, 0, cfg.maxPositions, cfg.costPerTrade, from)
    CsvWriter(File(dir, "signals.csv"), cfg.csvEncoding).use { w ->
        w.row("信号日", "代码", "名称", "概率", "类型", "收益(扣成本)", "是否成功", "卖出原因")
        sig.forEach { c ->
            if (!c.tradable) { w.row(c.date, c.symbol, c.name, c.prob, c.pattern.label, "", "", "买不进"); return@forEach }
            val net = c.ret[0] - model.label.cost
            w.row(c.date, c.symbol, c.name, c.prob, c.pattern.label, net,
                if (!c.completed(0)) "未完成" else if (model.label.isSuccess(net.toDouble())) "是" else "否",
                com.realu.core.strategy.ExitReason.entries[c.reason[0].toInt()].label)
        }
    }
    Console.raw("目标:${model.label.describe()};卖出:${model.exit.describe()}")
    Console.raw("区间 $from ~ ${sig.maxOfOrNull { it.date } ?: from}:已完成信号 ${st.n},准确率 ${pct(st.precision)}" +
        "(95%下限 ${pct(st.lower95)}),平均收益 ${pct(st.mean)},胜率 ${pct(st.winRate)},未完成 ${st.incomplete}")
    Console.raw("组合:年化 ${pct(pf.annualized)},最大回撤 ${pct(pf.maxDrawdown)};明细 ${File(dir, "signals.csv").absolutePath}")
}

private fun predict(cfg: Config, args: Array<String>) {
    val dir = runDir(cfg, "predict")
    val model = loadModel(args.getOrNull(2))
    val day = args.getOrNull(3)?.toIntOrNull()
    val source = DataSource(cfg)
    val stocks = source.listStocks()
    val index = source.loadIndex()
    val engine = SignalEngine(model)
    if (!model.passed) Console.warn("该模型历史检验未达标:${model.metrics["verdict"]}")
    val (market, _) = MarketPrep.build(cfg, source, stocks, index, fixedSectors = model.sectors, resolveSectors = false)
    val out = java.util.Collections.synchronizedList(ArrayList<com.realu.core.strategy.Signal>())
    MarketPrep.parallelLoad(source, stocks, cfg.threads) { s ->
        val at = if (day == null) s.size - 1 else s.indexOf(day)
        if (at >= 0) {
            val sig = runCatching { engine.evaluate(s, index, source.extrasFor(s.code), at, market = market) }.getOrNull()
            if (sig != null && sig.triggered) out += sig
        }
    }
    val list = out.sortedByDescending { it.probability }
    CsvWriter(File(dir, "signals.csv"), cfg.csvEncoding).use { w ->
        w.row("日期", "代码", "名称", "评分", "概率", "类型", "收盘", "止损价", "主要理由")
        list.forEach { s ->
            w.row(s.date, s.symbol, s.name, s.score, s.probability, s.pattern.label, s.close, s.stopPrice,
                s.reasons.take(4).joinToString(" | ") { "${it.label}${if (it.impact > 0) "↑" else "↓"}" })
        }
    }
    Console.raw("信号 ${list.size} 个:")
    list.take(30).forEach { Console.raw("  ${it.date} ${it.symbol} ${it.name} 评分${it.score} ${it.pattern.label} 收盘${"%.2f".format(it.close)}") }
    Console.raw("明细:${File(dir, "signals.csv").absolutePath}")
}

private fun demo(args: Array<String>) {
    val dir = File(args.getOrNull(1) ?: "demo-data")
    val withHq = !args.contains("--vipdoc-only")
    fun opt(name: String, def: Int) = args.indexOf(name).let { if (it >= 0) args.getOrNull(it + 1)?.toIntOrNull() ?: def else def }
    val n = SyntheticData.writeTdx(dir, stocks = opt("--stocks", 300), days = opt("--days", 2600), seed = 7, withHqCache = withHq)
    Console.info("已在 ${dir.absolutePath} 生成 $n 个模拟 .day 文件(含指数 sh000001)" + if (withHq) "与 T0002/hq_cache 行业/概念文件。" else "。")
    Console.info("把配置中的 tdx.vipdoc 指向 ${File(dir, "vipdoc").absolutePath} 即可试跑 train,用于验证环境;模拟数据的结果没有任何投资意义。")
}

fun versionText(): String {
    val p = java.util.Properties()
    object {}.javaClass.getResourceAsStream("/realu-build.properties")?.use { p.load(it) }
    return "realU trainer ${p.getProperty("version", "dev")}  build ${p.getProperty("buildTime", "unknown")}  " +
        "Java ${System.getProperty("java.version")}"
}
