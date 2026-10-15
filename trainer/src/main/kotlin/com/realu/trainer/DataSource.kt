package com.realu.trainer

import com.realu.core.data.BarSeries
import com.realu.core.data.Dates
import com.realu.core.data.StockCode
import com.realu.core.feature.IndexContext
import com.realu.core.feature.StockExtras
import com.realu.core.tdx.ExRightsAdjuster
import com.realu.core.tdx.ParseResult
import com.realu.core.tdx.TdxDayReader
import com.realu.core.tdx.TdxTxtReader
import java.io.File

class StockFile(val code: StockCode, val file: File)

/**
 * 读取通达信离线数据:
 * - data.source=day:直接读 vipdoc/{sh,sz,bj}/lday 下的 .day 文件(不复权,自动启发式除权处理);
 * - data.source=txt:读"盘后数据导出"的 txt 文件(推荐前复权,GBK 编码自动识别)。
 */
class DataSource(private val cfg: Config) {
    val names: Map<String, String> = cfg.namesCsv?.let { loadNames(File(it)) } ?: emptyMap()
    private val floatShares: Map<String, Pair<IntArray, DoubleArray>> =
        cfg.floatSharesCsv?.let { loadSeriesCsv(File(it), allowNoDate = true) } ?: emptyMap()
    private val bigOrder: Map<String, Pair<IntArray, DoubleArray>> =
        cfg.bigOrderCsv?.let { loadSeriesCsv(File(it), allowNoDate = false) } ?: emptyMap()

    val hasFloatShares: Boolean get() = floatShares.isNotEmpty()
    val hasBigOrder: Boolean get() = bigOrder.isNotEmpty()

    fun listStocks(): List<StockFile> {
        val out = ArrayList<StockFile>()
        when (cfg.dataSource) {
            "day" -> {
                val root = File(cfg.vipdoc ?: error("请在配置中设置 tdx.vipdoc(例如 C:/new_tdx/vipdoc)"))
                require(root.isDirectory) { "vipdoc 目录不存在:${root.absolutePath}" }
                for (m in cfg.markets) {
                    val dir = File(root, "$m/lday")
                    if (!dir.isDirectory) { Console.warn("未找到 ${dir.path},跳过市场 $m"); continue }
                    dir.listFiles { f -> f.isFile && f.name.endsWith(".day", true) }?.forEach { f ->
                        val code = StockCode.parse(f.name)
                        if (code != null && code.isAStock) out += StockFile(code, f)
                    }
                }
            }
            "txt" -> {
                val dir = File(cfg.txtDir ?: error("请在配置中设置 data.txtDir"))
                require(dir.isDirectory) { "txt 目录不存在:${dir.absolutePath}" }
                dir.walkTopDown().filter { it.isFile && it.name.endsWith(".txt", true) }.forEach { f ->
                    val code = StockCode.parse(f.name)
                    if (code != null && code.isAStock && code.market in cfg.markets) out += StockFile(code, f)
                }
            }
            else -> error("data.source 只能是 day 或 txt")
        }
        out.sortBy { it.code.symbol }
        return out
    }

    /** 读取并清洗单只股票;返回 null 表示文件不可用(原因在 warnings 中)。 */
    fun load(sf: StockFile): ParseResult {
        val bytes = try {
            sf.file.readBytes()
        } catch (e: Exception) {
            // 通达信运行中盘后下载可能锁文件,或文件被杀毒软件占用
            return ParseResult(null, listOf("读取失败:${e.message}"))
        }
        val res = when (cfg.dataSource) {
            "day" -> TdxDayReader.parse(bytes, sf.code, names[sf.code.symbol].orEmpty(), TdxDayReader.priceDivisorFor(sf.code))
            else -> TdxTxtReader.parse(bytes, sf.file.name)
        }
        val s = res.series ?: return res
        if (s.name.isEmpty()) names[s.code.symbol]?.let { s.name = it }
        val warnings = res.warnings.toMutableList()
        if (cfg.dataSource == "day" && cfg.adjust) {
            val n = ExRightsAdjuster.forwardAdjust(s)
            if (n > 0) warnings += "启发式除权处理 $n 次"
        }
        return ParseResult(s, warnings)
    }

    /** 通达信 T0002/hq_cache 目录(行业、概念板块文件所在);vipdoc 通常就在通达信安装目录下。 */
    fun hqCacheDir(): File? {
        val roots = listOfNotNull(
            cfg.tdxRoot?.let { File(it) },
            cfg.vipdoc?.let { File(it).absoluteFile.parentFile },
            cfg.txtDir?.let { File(it).absoluteFile.parentFile?.parentFile },
        )
        return roots.map { File(it, "T0002/hq_cache") }.firstOrNull { it.isDirectory }
    }

    fun extrasFor(code: StockCode): StockExtras? {
        val fs = floatShares[code.symbol]
        val bo = bigOrder[code.symbol]
        if (fs == null && bo == null) return null
        return StockExtras(
            fs?.first ?: IntArray(0), fs?.second ?: DoubleArray(0),
            bo?.first ?: IntArray(0), bo?.second ?: DoubleArray(0),
        )
    }

    fun loadIndex(): IndexContext? {
        val code = StockCode.parse(cfg.indexSymbol) ?: return null.also { Console.warn("无法识别指数代码 ${cfg.indexSymbol}") }
        val file = when (cfg.dataSource) {
            "day" -> File(cfg.vipdoc.orEmpty(), "${code.market}/lday/${code.symbol}.day")
            else -> File(cfg.txtDir.orEmpty()).walkTopDown().firstOrNull { StockCode.parse(it.name) == code && it.isFile }
        }
        if (file == null || !file.isFile) {
            Console.warn("未找到大盘指数文件(${cfg.indexSymbol}),市场环境特征将为空。txt 模式请一并导出指数。")
            return null
        }
        val bytes = file.readBytes()
        val res = if (cfg.dataSource == "day") TdxDayReader.parse(bytes, code) else TdxTxtReader.parse(bytes, file.name)
        return res.series?.let { IndexContext(it) }
    }

    companion object {
        /** 代码,名称 —— 例如从通达信"导出全部A股列表"得到,GBK/UTF-8 均可。 */
        fun loadNames(file: File): Map<String, String> {
            val out = HashMap<String, String>()
            TdxTxtReader.decode(file.readBytes()).lineSequence().forEach { line ->
                val t = line.split(',', '\t').map { it.trim() }
                if (t.size >= 2) StockCode.parse(t[0])?.let { out[it.symbol] = t[1] }
            }
            Console.info("读取股票名称 ${out.size} 条")
            return out
        }

        /**
         * 通用"代码,日期,数值"CSV(首行可为表头)。流通股本文件允许省略日期列(代码,股本),表示常数。
         */
        fun loadSeriesCsv(file: File, allowNoDate: Boolean): Map<String, Pair<IntArray, DoubleArray>> {
            require(file.isFile) { "找不到文件 ${file.absolutePath}" }
            val tmp = HashMap<String, MutableList<Pair<Int, Double>>>()
            var bad = 0
            TdxTxtReader.decode(file.readBytes()).lineSequence().forEach { line ->
                val t = line.split(',', '\t', ';').map { it.trim() }
                if (t.size < 2) return@forEach
                val code = StockCode.parse(t[0]) ?: run { bad++; return@forEach }
                val (d, v) = if (t.size >= 3) {
                    (Dates.parse(t[1]) ?: run { bad++; return@forEach }) to (t[2].toDoubleOrNull() ?: run { bad++; return@forEach })
                } else if (allowNoDate) {
                    19900101 to (t[1].toDoubleOrNull() ?: run { bad++; return@forEach })
                } else { bad++; return@forEach }
                tmp.getOrPut(code.symbol) { ArrayList() } += d to v
            }
            if (bad > 1) Console.warn("${file.name}:${bad - 1} 行无法解析(首行表头已计入)")
            Console.info("读取 ${file.name}:${tmp.size} 只股票")
            return tmp.mapValues { (_, l) ->
                val sorted = l.sortedBy { it.first }.distinctBy { it.first }
                IntArray(sorted.size) { sorted[it].first } to DoubleArray(sorted.size) { sorted[it].second }
            }
        }
    }
}
