package com.realu.trainer

import com.realu.core.data.Dates
import com.realu.core.data.StockCode
import com.realu.core.market.MarketContextBuilder
import com.realu.core.market.SectorMap
import com.realu.core.market.TradingCalendar
import com.realu.core.tdx.TdxTxtReader
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import java.util.stream.IntStream
import kotlin.math.sqrt

/**
 * 通达信 T0002/hq_cache 下的板块文件(通达信客户端自带,不需要 Level-2):
 * - tdxhy.cfg:个股所属通达信行业,每行 `市场|代码|行业代码|...`,市场 0=深 1=沪 2=北;
 *   行业代码形如 T1001 / T100101,每两位一级;
 * - tdxzs.cfg:板块指数与行业名称,`名称|指数代码|类型|...|...|行业代码`(用于显示名称,可缺失);
 * - block_gn.dat:概念板块,二进制:384 字节头 + uint16 板块数,每块 9 字节 GBK 名称 + uint16 股票数 + uint16 类型
 *   + 400×7 字节代码(共 2813 字节)。
 */
object TdxSectorFiles {

    fun readIndustry(file: File, level: Int): Map<String, String> {
        val len = 1 + 2 * level.coerceIn(1, 3)
        val out = HashMap<String, String>()
        TdxTxtReader.decode(file.readBytes()).lineSequence().forEach { line ->
            val t = line.trim().split('|')
            if (t.size < 3) return@forEach
            val market = when (t[0].trim()) { "0" -> "sz"; "1" -> "sh"; "2" -> "bj"; else -> return@forEach }
            val code = t[1].trim()
            val ind = t[2].trim()
            if (code.length != 6 || !ind.startsWith("T")) return@forEach
            out["$market$code"] = if (ind.length >= len) ind.substring(0, len) else ind
        }
        return out
    }

    fun readIndustryNames(file: File): Map<String, String> {
        if (!file.isFile) return emptyMap()
        val out = HashMap<String, String>()
        TdxTxtReader.decode(file.readBytes()).lineSequence().forEach { line ->
            val t = line.trim().split('|')
            val ind = t.lastOrNull { it.trim().matches(Regex("T\\d+")) }?.trim() ?: return@forEach
            if (t.isNotEmpty() && t[0].isNotBlank()) out.putIfAbsent(ind, t[0].trim())
        }
        return out
    }

    fun readBlocks(file: File): List<Pair<String, List<String>>> {
        val data = file.readBytes()
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        if (data.size < 386) return emptyList()
        val num = buf.getShort(384).toInt() and 0xFFFF
        var pos = 386
        val out = ArrayList<Pair<String, List<String>>>()
        for (k in 0 until num) {
            if (pos + 13 > data.size) break
            val name = String(data, pos, 9, TdxTxtReader.GBK).trimEnd('\u0000').trim()
            val count = buf.getShort(pos + 9).toInt() and 0xFFFF
            var p = pos + 13
            val codes = ArrayList<String>()
            for (i in 0 until minOf(count, 400)) {
                if (p + 7 > data.size) break
                val code = String(data, p, 6, Charsets.US_ASCII)
                StockCode.parse(code)?.let { if (it.isAStock) codes += it.symbol }
                p += 7
            }
            if (name.isNotEmpty() && codes.isNotEmpty()) out += name to codes
            pos += 2813
        }
        return out
    }
}

/**
 * 只有 vipdoc 时的"数据驱动板块":按过去 250 个交易日的日收益相关性做 k-means 聚类(余弦距离)。
 * 每个自然年重算一次,第 Y 年的归属只用 Y 年之前的数据计算 —— 没有未来函数。
 * 同一组内的股票走势高度同步,通常对应同一行业或同一题材。
 */
object SectorClustering {
    fun fit(builder: MarketContextBuilder, calendar: TradingCalendar, k: Int, seed: Long,
            window: Int = 250, minDays: Int = 200): Triple<IntArray, Map<String, IntArray>, IntArray> {
        val starts = ArrayList<Int>()
        var lastYear = -1
        for (ci in calendar.dates.indices) {
            val y = calendar.dates[ci] / 10000
            if (y != lastYear) {
                lastYear = y
                if (ci >= window) starts += ci
            }
        }
        val symbols = builder.symbols()
        val groups = HashMap<String, IntArray>()
        for (s in symbols) groups[s] = IntArray(starts.size) { -1 }
        val rnd = Random(seed)
        for ((p, ci0) in starts.withIndex()) {
            val from = ci0 - window
            val vecs = ArrayList<FloatArray>()
            val owners = ArrayList<String>()
            for (sym in symbols) {
                val a = builder.aligned(sym) ?: continue
                if (a.first > from) continue
                val v = FloatArray(window)
                var traded = 0
                for (t in 0 until window) {
                    val ci = from + t
                    if (a.traded(ci)) { traded++; val r = a.ret(ci); if (!r.isNaN()) v[t] = r.coerceIn(-0.2f, 0.2f) }
                }
                if (traded < minDays || !normalize(v)) continue
                vecs += v; owners += sym
            }
            if (vecs.size < k * 5) continue
            val assign = kmeans(vecs, k, rnd)
            for (i in owners.indices) groups.getValue(owners[i])[p] = p * k + assign[i]
            Console.info("板块聚类 ${Dates.format(calendar.dates[ci0])} 起:${vecs.size} 只股票 → $k 组")
        }
        val periodStarts = IntArray(starts.size) { calendar.dates[starts[it]] }
        val groupPeriod = IntArray(starts.size * k) { it / k }
        return Triple(periodStarts, groups, groupPeriod)
    }

    private fun normalize(v: FloatArray): Boolean {
        val mean = v.average().toFloat()
        var ss = 0.0
        for (i in v.indices) { v[i] -= mean; ss += v[i] * v[i] }
        if (ss <= 1e-12) return false
        val inv = (1 / sqrt(ss)).toFloat()
        for (i in v.indices) v[i] *= inv
        return true
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    /** 球面 k-means(k-means++ 初始化),返回每个向量的组号。 */
    fun kmeans(x: List<FloatArray>, k: Int, rnd: Random, iters: Int = 15): IntArray {
        val n = x.size
        val dim = x[0].size
        val cents = ArrayList<FloatArray>()
        cents += x[rnd.nextInt(n)].copyOf()
        val d2 = DoubleArray(n) { Double.MAX_VALUE }
        while (cents.size < k) {
            val c = cents.last()
            var sum = 0.0
            for (i in 0 until n) { val d = 1.0 - dot(x[i], c); if (d < d2[i]) d2[i] = d; sum += maxOf(d2[i], 0.0) }
            var r = rnd.nextDouble() * sum
            var pick = n - 1
            for (i in 0 until n) { r -= maxOf(d2[i], 0.0); if (r <= 0) { pick = i; break } }
            cents += x[pick].copyOf()
        }
        val assign = IntArray(n)
        repeat(iters) {
            IntStream.range(0, n).parallel().forEach { i ->
                var best = 0; var bv = -2f
                for (c in 0 until k) { val v = dot(x[i], cents[c]); if (v > bv) { bv = v; best = c } }
                assign[i] = best
            }
            val sums = Array(k) { FloatArray(dim) }
            val cnt = IntArray(k)
            for (i in 0 until n) { val s = sums[assign[i]]; val xi = x[i]; for (d in 0 until dim) s[d] += xi[d]; cnt[assign[i]]++ }
            for (c in 0 until k) {
                if (cnt[c] == 0) { cents[c] = x[rnd.nextInt(n)].copyOf(); continue }
                if (normalize(sums[c])) cents[c] = sums[c]
            }
        }
        return assign
    }
}

/** 根据配置决定板块来源,返回 null 表示不使用板块特征。 */
object Sectors {
    fun resolve(cfg: Config, source: DataSource, builder: MarketContextBuilder, calendar: TradingCalendar): SectorMap? {
        val hq = source.hqCacheDir()
        val hyFile = hq?.let { File(it, "tdxhy.cfg") }?.takeIf { it.isFile }
        val mode = when (cfg.sectorSource) {
            "auto" -> if (hyFile != null) "industry" else "cluster"
            else -> cfg.sectorSource
        }
        // 概念
        var conceptNames = emptyList<String>()
        var concepts = emptyMap<String, IntArray>()
        val gnFile = hq?.let { File(it, "block_gn.dat") }?.takeIf { it.isFile }
        if (cfg.useConcepts && gnFile != null) {
            val blocks = try { TdxSectorFiles.readBlocks(gnFile) } catch (e: Exception) {
                Console.warn("读取概念板块失败:${e.message}"); emptyList()
            }
            conceptNames = blocks.map { it.first }
            val tmp = HashMap<String, MutableList<Int>>()
            blocks.forEachIndexed { id, (_, codes) -> codes.forEach { tmp.getOrPut(it) { ArrayList() } += id } }
            concepts = tmp.mapValues { it.value.toIntArray() }
            Console.info("概念板块 ${conceptNames.size} 个,覆盖 ${concepts.size} 只股票(${gnFile.path})")
            Console.warn("概念成分是当前名单,历史回测中存在\"事后归入概念\"的未来函数,会高估回测准确率;" +
                "可设 sector.useConcepts=false 训练一次做对比")
        } else if (cfg.useConcepts) {
            Console.warn("未找到 hq_cache/block_gn.dat,概念强度特征为空(只拷贝了 vipdoc 时属正常;可设置 data.tdxRoot 指向通达信安装目录)")
        }

        return when (mode) {
            "industry" -> {
                val f = hyFile ?: error("sector.source=industry 但找不到 tdxhy.cfg,请设置 data.tdxRoot")
                val ind = TdxSectorFiles.readIndustry(f, cfg.industryLevel)
                val names = TdxSectorFiles.readIndustryNames(File(f.parentFile, "tdxzs.cfg"))
                val codes = ind.values.distinct().sorted()
                val id = codes.withIndex().associate { it.value to it.index }
                Console.info("通达信行业(${cfg.industryLevel}级)${codes.size} 个,覆盖 ${ind.size} 只股票")
                SectorMap("industry", intArrayOf(0), ind.mapValues { intArrayOf(id.getValue(it.value)) }, IntArray(codes.size),
                    conceptNames, concepts, codes.map { names[it] ?: it })
            }
            "cluster" -> {
                Console.info("使用收益相关性聚类板块(每年重算,k=${cfg.sectorClusters})")
                val (starts, groups, gp) = SectorClustering.fit(builder, calendar, cfg.sectorClusters, cfg.seed)
                if (starts.isEmpty()) { Console.warn("历史数据不足 250 个交易日,无法聚类板块"); null }
                else SectorMap("cluster", starts, groups, gp, conceptNames, concepts)
            }
            "none" -> if (conceptNames.isEmpty()) null else SectorMap("none", intArrayOf(0), emptyMap(), IntArray(0), conceptNames, concepts)
            else -> error("sector.source 只能是 auto / industry / cluster / none")
        }
    }
}
