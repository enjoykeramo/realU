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
  realu-trainer train    <config.properties>              训练 + 历史检验 + 导出模型
  realu-trainer check    <config.properties>              数据体检(文件数、日期范围、坏数据)
  realu-trainer backtest <config.properties> <model.json> [起始yyyyMMdd] [结束yyyyMMdd]
  realu-trainer predict  <config.properties> <model.json> [日期yyyyMMdd]   列出某日信号
  realu-trainer demo     <输出目录>                        生成模拟 vipdoc 数据,用于验证安装
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
            else -> {
                val cfg = Config.load(File(args.getOrNull(1) ?: error("缺少配置文件参数")))
                Console.init(cfg.consoleEncoding)
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
    val bt = Backtester(source, stocks, index, model.gate, model.label, listOf(model.exit), cfg.threads, cfg.int("backtest.cooldownDays", 20))
    val sig = bt.run(model.gbdt, model.threshold, from, to) { Console.info(it) }
    val st = TradeStats(sig.mapNotNull { it.trades[0] }, cfg.costPerTrade)
    val hits = sig.count { it.hit }
    val end = sig.maxOfOrNull { it.date } ?: from
    val pf = Portfolio.simulate(sig, 0, cfg.maxPositions, cfg.costPerTrade, from, end)
    CsvWriter(File(dir, "signals.csv"), cfg.csvEncoding).use { w ->
        w.row("日期", "代码", "名称", "概率", "类型", "标签结果", "收益", "卖出原因")
        sig.forEach { s -> val t = s.trades[0]; w.row(s.date, s.symbol, s.name, s.probability, s.pattern.label, s.outcome.name, t?.ret, t?.reason?.label ?: "买不进") }
    }
    Console.raw("区间 $from ~ $end:信号 ${sig.size},准确率 ${pct(if (sig.isEmpty()) 0.0 else hits.toDouble() / sig.size)}" +
        "(95%下限 ${pct(Metrics.wilsonLower(hits, sig.size))}),平均收益 ${pct(st.mean)},胜率 ${pct(st.winRate)}")
    Console.raw("组合:年化 ${pct(pf.annualized)},最大回撤 ${pct(pf.maxDrawdown)};明细 ${File(dir, "signals.csv").absolutePath}")
}

private fun predict(cfg: Config, args: Array<String>) {
    val dir = runDir(cfg, "predict")
    val model = loadModel(args.getOrNull(2))
    val day = args.getOrNull(3)?.toIntOrNull()
    val source = DataSource(cfg)
    val index = source.loadIndex()
    val engine = SignalEngine(model)
    if (!model.passed) Console.warn("该模型历史检验未达标:${model.metrics["verdict"]}")
    val out = ArrayList<com.realu.core.strategy.Signal>()
    for (sf in source.listStocks()) {
        val s = source.load(sf).series ?: continue
        val at = if (day == null) s.size - 1 else s.indexOf(day).takeIf { it >= 0 } ?: continue
        val sig = runCatching { engine.evaluate(s, index, source.extrasFor(sf.code), at) }.getOrNull() ?: continue
        if (sig.triggered) out += sig
    }
    out.sortByDescending { it.probability }
    CsvWriter(File(dir, "signals.csv"), cfg.csvEncoding).use { w ->
        w.row("日期", "代码", "名称", "评分", "概率", "类型", "收盘", "止损价", "主要理由")
        out.forEach { s ->
            w.row(s.date, s.symbol, s.name, s.score, s.probability, s.pattern.label, s.close, s.stopPrice,
                s.reasons.take(4).joinToString(" | ") { "${it.label}${if (it.impact > 0) "↑" else "↓"}" })
        }
    }
    Console.raw("信号 ${out.size} 个:")
    out.take(30).forEach { Console.raw("  ${it.date} ${it.symbol} ${it.name} 评分${it.score} ${it.pattern.label} 收盘${"%.2f".format(it.close)}") }
    Console.raw("明细:${File(dir, "signals.csv").absolutePath}")
}

private fun demo(args: Array<String>) {
    val dir = File(args.getOrNull(1) ?: "demo-data")
    val n = SyntheticData.writeVipdoc(dir, stocks = 300, days = 2600, seed = 7)
    Console.info("已在 ${dir.absolutePath} 生成 $n 个模拟 .day 文件(含指数 sh000001)。")
    Console.info("把配置中的 tdx.vipdoc 指向该目录即可试跑 train,用于验证环境;模拟数据的结果没有任何投资意义。")
}
