package com.realu.trainer

import com.realu.core.feature.FeatureDefs
import com.realu.core.feature.FeatureEngine
import com.realu.core.feature.IndexContext
import com.realu.core.market.MarketContext
import com.realu.core.strategy.Labeler
import com.realu.core.strategy.PatternTagger
import java.util.Random
import java.util.concurrent.Future
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * 内存监控。训练数据按"分箱后的字节"存储(每个特征 1 字节),
 * 800 万样本 × 70 特征 ≈ 560MB;接近上限时自动加大训练集负样本的降采样,仍不够就停止追加并告警,
 * 而不是让 JVM 抛 OutOfMemoryError 前功尽弃。
 */
object MemoryGuard {
    private val rt = Runtime.getRuntime()

    fun usedRatio(): Double = (rt.totalMemory() - rt.freeMemory()).toDouble() / rt.maxMemory()

    fun usedMb(): Long = (rt.totalMemory() - rt.freeMemory()) / (1 shl 20)

    fun maxMb(): Long = rt.maxMemory() / (1 shl 20)

    fun describe(): String = "内存 ${usedMb()}MB / ${maxMb()}MB"

    /** 先尝试 GC,再返回使用率。 */
    fun ratioAfterGc(): Double {
        val r = usedRatio()
        if (r < 0.6) return r
        System.gc()
        return usedRatio()
    }
}

/**
 * 分箱器:把连续特征按分位数切成最多 maxBins 段。bin 0 留给缺失值(NaN)。
 * 值 v 落在 bin b(≥1)表示 edges[b-2] < v <= edges[b-1]。
 * 分裂"bin <= b"等价于"v <= edges[b-1]",因此导出的阈值就是 edges 中的 float 值,训练与推理逐位一致。
 */
class Binner(val edges: Array<FloatArray>) {
    val nBins: IntArray = IntArray(edges.size) { edges[it].size + 2 }

    fun bin(f: Int, v: Float): Int {
        if (v.isNaN()) return 0
        val e = edges[f]
        var lo = 0
        var hi = e.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (e[mid] < v) lo = mid + 1 else hi = mid
        }
        return lo + 1
    }

    /** 分裂阈值:左子树为 bin ∈ [1, b]。 */
    fun threshold(f: Int, b: Int): Double = edges[f][b - 1].toDouble()

    companion object {
        fun fit(sample: List<FloatArray>, maxBins: Int): Binner {
            val nf = FeatureDefs.COUNT
            val mb = maxBins.coerceIn(4, 250)
            val edges = Array(nf) { f ->
                val vals = sample.mapNotNull { r -> r[f].takeIf { !it.isNaN() } }.toFloatArray()
                vals.sort()
                if (vals.isEmpty()) FloatArray(0) else {
                    val set = java.util.TreeSet<Float>()
                    for (k in 1 until mb) set += vals[min(vals.size - 1, (k.toLong() * vals.size / mb).toInt())]
                    // 最大值不作为边界(否则右侧箱为空)
                    set.remove(vals.last())
                    set.toFloatArray()
                }
            }
            return Binner(edges)
        }
    }
}

/** 一个样本 = 某只股票某个候选启动日。 */
class Dataset(
    val bins: Array<ByteArray>,
    val label: ByteArray,
    val date: IntArray,
    /** 标签窗口结束日期:用于切分时"清洗"(purge),避免训练样本的标签窗口伸进验证期。 */
    val endDate: IntArray,
    val stock: IntArray,
    val pattern: ByteArray,
    val maxGain: FloatArray,
    /** 样本在该股票 K 线序列中的下标,用于按交易日做同股冷却去重。 */
    val bar: IntArray,
) {
    val size: Int get() = label.size
    val positives: Int get() = label.count { it.toInt() == 1 }
}

class DatasetStats {
    var stocksOk = 0
    var stocksFailed = 0
    var candidates = 0L
    var untradable = 0L
    var droppedNegatives = 0L
    var truncated = false
    val warnings = ArrayList<String>()
}

class DatasetBuilder(
    private val cfg: Config,
    private val source: DataSource,
    private val stocks: List<StockFile>,
    private val index: IndexContext?,
    private val market: MarketContext?,
) {
    private val nf = FeatureDefs.COUNT
    private val gate = cfg.gate
    private val labelParams = cfg.label

    /** 第一遍:随机抽一部分股票估计分位数,得到分箱边界。 */
    fun fitBinner(): Binner {
        val rnd = Random(cfg.seed)
        val pick = stocks.shuffled(rnd).take(min(stocks.size, 400))
        val perStock = 2000
        val bySymbol = java.util.concurrent.ConcurrentHashMap<String, List<FloatArray>>()
        // 并行计算抽样股票的特征;每只股票最多取 perStock 个候选样本,保证各股票权重相近
        MarketPrep.parallelLoad(source, pick, cfg.threads) { s ->
            val f = FeatureEngine.compute(s, index, source.extrasFor(s.code), market)
            val mine = ArrayList<FloatArray>()
            for (i in 0 until s.size) {
                if (s.date[i] < cfg.startDate || s.date[i] > cfg.trainEnd) continue
                if (gate.passes(s, f, i)) mine += f.row(i)
            }
            val local = Random(cfg.seed + s.code.symbol.hashCode())
            bySymbol[s.code.symbol] = if (mine.size > perStock) mine.shuffled(local).take(perStock) else mine
        }
        // 按代码排序后合并:多线程完成顺序不同,也保证每次训练结果可复现
        val rows = bySymbol.toSortedMap().values.flatten()
        val reservoir = if (rows.size > 300_000) rows.shuffled(rnd).take(300_000) else rows.toList()
        require(reservoir.size >= 1000) {
            "训练期内候选样本太少(${reservoir.size}),请检查数据目录、split.trainEnd、data.startDate 或放宽 gate.* 条件"
        }
        Console.info("分箱:抽样 ${pick.size} 只股票,${reservoir.size} 个候选样本")
        return Binner.fit(reservoir, cfg.gbdt.maxBins)
    }

    private class Chunk(
        val stockIdx: Int,
        val rows: ByteArray,
        val label: ByteArray,
        val date: IntArray,
        val endDate: IntArray,
        val pattern: ByteArray,
        val maxGain: FloatArray,
        val bar: IntArray,
        val count: Int,
        val candidates: Int,
        val untradable: Int,
        val dropped: Int,
        val warnings: List<String>,
        val failed: Boolean,
    )

    fun build(binner: Binner): Pair<Dataset, DatasetStats> {
        val stats = DatasetStats()
        val chunkRows = 1 shl 20
        val colChunks = Array(nf) { ArrayList<ByteArray>() }
        val labels = GrowBytes(); val patterns = GrowBytes()
        val dates = GrowInts(); val ends = GrowInts(); val stockIds = GrowInts(); val gains = GrowFloats(); val barIdx = GrowInts()
        var total = 0
        var negKeep = cfg.negativeKeep
        var stop = false

        val pool = Executors.newFixedThreadPool(cfg.threads)
        // 按提交顺序取结果(滑动窗口并行):样本顺序固定,训练结果可复现
        val queue = java.util.ArrayDeque<Future<Chunk>>()
        val inFlight = cfg.threads * 3
        var submitted = 0
        var done = 0
        try {
            fun submitNext() {
                val idx = submitted++
                val keep = negKeep
                queue.addLast(pool.submit<Chunk> { processStock(idx, binner, keep) })
            }
            while (submitted < stocks.size && submitted < inFlight) submitNext()
            while (done < submitted) {
                val ch = queue.removeFirst().get()
                done++
                if (!stop && submitted < stocks.size) submitNext()
                if (ch.failed) {
                    stats.stocksFailed++
                    if (stats.warnings.size < 200) stats.warnings += "${stocks[ch.stockIdx].code}: ${ch.warnings.joinToString(";")}"
                    continue
                }
                stats.stocksOk++
                stats.candidates += ch.candidates
                stats.untradable += ch.untradable
                stats.droppedNegatives += ch.dropped
                if (ch.warnings.isNotEmpty() && stats.warnings.size < 200) stats.warnings += "${stocks[ch.stockIdx].code}: ${ch.warnings.joinToString(";")}"
                if (stop) continue

                // 追加到列式存储
                for (r in 0 until ch.count) {
                    val pos = total + r
                    val ci = pos / chunkRows
                    val off = pos % chunkRows
                    for (f in 0 until nf) {
                        val list = colChunks[f]
                        if (list.size <= ci) list += ByteArray(chunkRows)
                        list[ci][off] = ch.rows[r * nf + f]
                    }
                }
                total += ch.count
                labels.addAll(ch.label, ch.count); patterns.addAll(ch.pattern, ch.count)
                dates.addAll(ch.date, ch.count); ends.addAll(ch.endDate, ch.count)
                gains.addAll(ch.maxGain, ch.count)
                barIdx.addAll(ch.bar, ch.count)
                repeat(ch.count) { stockIds.add(ch.stockIdx) }

                if (done % 200 == 0 || done == stocks.size) {
                    Console.info("构建样本 $done/${stocks.size} 只,样本 $total,${MemoryGuard.describe()}")
                }
                // ---- 内存与规模保护 ----
                if (total >= cfg.maxRows) {
                    stop = true; stats.truncated = true
                    Console.warn("样本数达到上限 sample.maxRows=${cfg.maxRows},停止追加(剩余股票不再纳入)。可调大该值或减小 sample.negativeKeep")
                } else if (done % 50 == 0) {
                    val r = MemoryGuard.ratioAfterGc()
                    if (r > cfg.memoryLimit + 0.08) {
                        stop = true; stats.truncated = true
                        Console.warn("内存占用 ${"%.0f".format(r * 100)}% 过高,停止追加样本。请在 train.bat 中调大 -Xmx 或减小 sample.negativeKeep")
                    } else if (r > cfg.memoryLimit && negKeep > 0.05) {
                        negKeep /= 2
                        Console.warn("内存占用 ${"%.0f".format(r * 100)}%,训练期负样本保留比例降为 ${"%.3f".format(negKeep)}")
                    }
                }
            }
        } finally {
            pool.shutdownNow()
        }

        // 合并为连续数组(逐列进行,峰值只多出一列)
        val bins = Array(nf) { f ->
            val arr = ByteArray(total)
            var copied = 0
            for (c in colChunks[f]) {
                val n = min(chunkRows, total - copied)
                if (n <= 0) break
                System.arraycopy(c, 0, arr, copied, n)
                copied += n
            }
            colChunks[f].clear()
            arr
        }
        val ds = Dataset(bins, labels.toArray(), dates.toArray(), ends.toArray(), stockIds.toArray(), patterns.toArray(), gains.toArray(), barIdx.toArray())
        return ds to stats
    }

    private fun processStock(idx: Int, binner: Binner, negKeep: Double): Chunk {
        val sf = stocks[idx]
        val res = try {
            source.load(sf)
        } catch (e: Exception) {
            return failed(idx, "读取异常:${e.message}")
        }
        val s = res.series ?: return failed(idx, res.warnings.joinToString(";"))
        if (s.size < gate.minListedDays + labelParams.horizon) {
            return Chunk(idx, ByteArray(0), ByteArray(0), IntArray(0), IntArray(0), ByteArray(0), FloatArray(0), IntArray(0), 0, 0, 0, 0, res.warnings, false)
        }
        return try {
            val f = FeatureEngine.compute(s, index, source.extrasFor(sf.code), market)
            val rnd = Random(cfg.seed * 31 + sf.code.symbol.hashCode())
            val rows = GrowBytes(); val lab = GrowBytes(); val pat = GrowBytes()
            val dt = GrowInts(); val ed = GrowInts(); val mg = GrowFloats(); val br = GrowInts()
            val row = FloatArray(nf)
            var cand = 0; var untr = 0; var dropped = 0; var count = 0
            for (i in 0 until s.size) {
                if (s.date[i] < cfg.startDate || s.date[i] > cfg.testEnd) continue
                if (!gate.passes(s, f, i)) continue
                cand++
                val lr = Labeler.label(s, i, labelParams)
                if (!lr.usable) { untr++; continue }
                // 只对训练期负样本降采样;验证/测试期保持原始分布,否则准确率会被高估
                if (!lr.positive && s.date[i] <= cfg.trainEnd && negKeep < 1.0 && rnd.nextDouble() > negKeep) { dropped++; continue }
                f.row(i, row)
                for (k in 0 until nf) rows.add(binner.bin(k, row[k]).toByte())
                lab.add(if (lr.positive) 1 else 0)
                pat.add(PatternTagger.tag(f, i).ordinal.toByte())
                dt.add(s.date[i]); ed.add(s.date[lr.endIndex]); mg.add(lr.maxGain.toFloat()); br.add(i)
                count++
            }
            Chunk(idx, rows.toArray(), lab.toArray(), dt.toArray(), ed.toArray(), pat.toArray(), mg.toArray(), br.toArray(), count, cand, untr, dropped, res.warnings, false)
        } catch (e: Exception) {
            failed(idx, "计算异常:${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun failed(idx: Int, msg: String) =
        Chunk(idx, ByteArray(0), ByteArray(0), IntArray(0), IntArray(0), ByteArray(0), FloatArray(0), IntArray(0), 0, 0, 0, 0, listOf(msg), true)
}

// ---- 原始类型可增长数组(避免装箱,省内存)----

class GrowBytes(cap: Int = 1024) {
    private var a = ByteArray(cap)
    var size = 0
        private set
    fun add(v: Byte) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
    fun addAll(src: ByteArray, n: Int) { ensure(size + n); System.arraycopy(src, 0, a, size, n); size += n }
    private fun ensure(n: Int) { if (n > a.size) a = a.copyOf(maxOf(n, a.size * 2)) }
    fun toArray(): ByteArray = a.copyOf(size)
}

class GrowInts(cap: Int = 1024) {
    private var a = IntArray(cap)
    var size = 0
        private set
    fun add(v: Int) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
    fun addAll(src: IntArray, n: Int) { ensure(size + n); System.arraycopy(src, 0, a, size, n); size += n }
    private fun ensure(n: Int) { if (n > a.size) a = a.copyOf(maxOf(n, a.size * 2)) }
    fun toArray(): IntArray = a.copyOf(size)
}

class GrowFloats(cap: Int = 1024) {
    private var a = FloatArray(cap)
    var size = 0
        private set
    fun add(v: Float) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
    fun addAll(src: FloatArray, n: Int) { ensure(size + n); System.arraycopy(src, 0, a, size, n); size += n }
    private fun ensure(n: Int) { if (n > a.size) a = a.copyOf(maxOf(n, a.size * 2)) }
    fun toArray(): FloatArray = a.copyOf(size)
}
