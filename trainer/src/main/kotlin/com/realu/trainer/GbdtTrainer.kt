package com.realu.trainer

import com.realu.core.model.GbdtModel
import com.realu.core.model.Tree
import java.util.PriorityQueue
import java.util.Random
import java.util.concurrent.ForkJoinPool
import java.util.stream.IntStream
import kotlin.math.ln
import kotlin.math.max

data class GbdtParams(
    val rounds: Int = 600,
    val learningRate: Double = 0.05,
    val maxLeaves: Int = 31,
    val maxDepth: Int = 6,
    val minDataInLeaf: Int = 200,
    val minSumHessian: Double = 1e-3,
    val lambdaL2: Double = 1.0,
    val rowSample: Double = 0.8,
    val featureSample: Double = 0.8,
    val earlyStopping: Int = 50,
    val maxBins: Int = 64,
    /** 正样本权重(类别不平衡时可调大;阈值在验证集上另行选定,不影响准确率口径)。 */
    val positiveWeight: Double = 1.0,
    val seed: Long = 1,
    /** 早停指标:auc(默认,不受不同时期成功率高低的影响)或 logloss。 */
    val earlyStopMetric: String = "auc",
    /** 每多少轮评估一次验证集。 */
    val evalEvery: Int = 5,
    /** 不参与训练的特征(特征组筛选用);模型输入维度不变,APP 无需改动。 */
    val excluded: BooleanArray = BooleanArray(0),
)

class TrainResult(
    val model: GbdtModel,
    val bestRound: Int,
    /** 验证集最优指标(AUC 或 logloss);没有验证集时为 NaN。 */
    val bestValidScore: Double,
    /** 每个特征的累计分裂增益。 */
    val gainImportance: DoubleArray,
    val history: List<Pair<Double, Double>>,
)

/**
 * 直方图梯度提升树(与 LightGBM 同类算法),纯 Kotlin 实现:
 * - 按叶子优先(leaf-wise)生长,限制最大叶子数与深度;
 * - 直方图做差加速(小子节点建直方图,大子节点 = 父 − 小);
 * - 缺失值单独成箱,分裂时自动学习缺失值走向;
 * - 行/列采样 + 验证集早停,防止过拟合;
 * - 特征维度上并行构建直方图(ForkJoin);
 * - 样本权重 rowWeight(按交易日平衡,防止某几个火爆交易日的成千上万个样本主导模型,让模型"记住行情阶段")。
 */
class GbdtTrainer(
    private val ds: Dataset,
    private val binner: Binner,
    private val p: GbdtParams,
    threads: Int,
    private val rowWeight: FloatArray? = null,
) {
    private val nf = ds.bins.size
    private val pool = ForkJoinPool(threads)
    private val offsets = IntArray(nf + 1).also { for (f in 0 until nf) it[f + 1] = it[f] + binner.nBins[f] }
    private val totalBins = offsets[nf]

    private class Hist(n: Int) {
        val g = DoubleArray(n); val h = DoubleArray(n); val c = IntArray(n)
        fun subtract(a: Hist, b: Hist) {
            for (i in g.indices) { g[i] = a.g[i] - b.g[i]; h[i] = a.h[i] - b.h[i]; c[i] = a.c[i] - b.c[i] }
        }
    }

    private class Split(val feature: Int, val bin: Int, val missingLeft: Boolean, val gain: Double,
                        val gl: Double, val hl: Double, val gr: Double, val hr: Double)

    private class Leaf(val start: Int, val end: Int, val depth: Int, val g: Double, val h: Double,
                       val hist: Hist, var split: Split?, val nodeId: Int)

    // 树构建中的临时节点
    private class NodeBuf {
        val feature = ArrayList<Int>(); val bin = ArrayList<Int>(); val missLeft = ArrayList<Boolean>()
        val left = ArrayList<Int>(); val right = ArrayList<Int>(); val value = ArrayList<Double>()
        fun add(v: Double): Int { feature += -1; bin += 0; missLeft += false; left += -1; right += -1; value += v; return value.size - 1 }
    }

    fun train(trainIdx: IntArray, validIdx: IntArray, log: (String) -> Unit): TrainResult {
        val rnd = Random(p.seed)
        val y = ds.label
        var pos = 0.0; var wsum = 0.0
        for (r in trainIdx) { val w = weight(r); wsum += w; if (y[r].toInt() == 1) pos += w }
        val prior = (pos / wsum).coerceIn(1e-6, 1 - 1e-6)
        val base = ln(prior / (1 - prior))
        log("训练样本 ${trainIdx.size}(正样本加权占比 ${"%.2f".format(prior * 100)}%),验证样本 ${validIdx.size}")

        val fTrain = DoubleArray(trainIdx.size) { base }
        val fValid = DoubleArray(validIdx.size) { base }
        val grad = DoubleArray(trainIdx.size)
        val hess = DoubleArray(trainIdx.size)
        val trees = ArrayList<Tree>()
        val binTrees = ArrayList<NodeBuf>()
        val importance = DoubleArray(nf)
        val history = ArrayList<Pair<Double, Double>>()
        val useAuc = p.earlyStopMetric.equals("auc", true)
        val hasValid = validIdx.isNotEmpty()
        var best = if (useAuc) Double.NEGATIVE_INFINITY else Double.MAX_VALUE
        var bestRound = if (hasValid) 0 else p.rounds
        val usable = BooleanArray(nf) { binner.nBins[it] > 2 && !(p.excluded.getOrNull(it) ?: false) }
        val rows = IntArray(trainIdx.size) // 存放 trainIdx 的位置(0 until n),便于对齐 grad
        val sampledFeatures = BooleanArray(nf)

        for (round in 0 until p.rounds) {
            // 梯度
            for (k in trainIdx.indices) {
                val pr = GbdtModel.sigmoid(fTrain[k])
                val w = weight(trainIdx[k])
                grad[k] = (pr - y[trainIdx[k]]) * w
                hess[k] = max(pr * (1 - pr), 1e-12) * w
            }
            // 行采样
            var n = 0
            for (k in trainIdx.indices) if (p.rowSample >= 1.0 || rnd.nextDouble() < p.rowSample) rows[n++] = k
            // 列采样
            var anyF = false
            for (f in 0 until nf) { sampledFeatures[f] = usable[f] && (p.featureSample >= 1.0 || rnd.nextDouble() < p.featureSample); anyF = anyF || sampledFeatures[f] }
            if (!anyF) for (f in 0 until nf) sampledFeatures[f] = usable[f]

            val buf = buildTree(rows, n, trainIdx, grad, hess, sampledFeatures, importance)
            binTrees += buf
            // 更新预测值
            updateScores(buf, trainIdx, fTrain)
            if (hasValid) updateScores(buf, validIdx, fValid)
            trees += toTree(buf)

            if (!hasValid) {
                if ((round + 1) % 100 == 0) log("第 ${round + 1}/${p.rounds} 轮  ${MemoryGuard.describe()}")
                continue
            }
            if ((round + 1) % p.evalEvery != 0 && round != 0) continue
            val validLoss = logLoss(validIdx, fValid)
            val validAuc = if (useAuc) Metrics.auc(DoubleArray(validIdx.size) { fValid[it] }, IntArray(validIdx.size) { y[validIdx[it]].toInt() }) else Double.NaN
            history += validLoss to validAuc
            val score = if (useAuc) validAuc else validLoss
            val improved = if (useAuc) score > best + 1e-5 else score < best - 1e-7
            if (improved) { best = score; bestRound = round + 1 }
            if ((round + 1) % 50 == 0 || round == 0) {
                log("第 ${round + 1} 轮  验证 logloss ${"%.5f".format(validLoss)}" + (if (useAuc) "  AUC ${"%.4f".format(validAuc)}" else "") + "  ${MemoryGuard.describe()}")
            }
            if (round + 1 - bestRound >= p.earlyStopping) {
                log("验证集连续 ${p.earlyStopping} 轮未改善,早停于第 ${round + 1} 轮,采用第 $bestRound 轮(验证${if (useAuc) " AUC" else " logloss"} ${"%.4f".format(best)})")
                break
            }
        }
        val keep = max(1, bestRound)
        finalBinTrees = binTrees.subList(0, keep).toList()
        baseScore = base
        return TrainResult(GbdtModel(base, trees.subList(0, keep).toList()), keep, if (hasValid) best else Double.NaN, importance, history)
    }

    private var finalBinTrees: List<NodeBuf> = emptyList()
    private var baseScore = 0.0

    /** 用分箱数据预测概率(与导出的浮点阈值模型等价,训练器内部评估用)。 */
    fun predict(idx: IntArray): DoubleArray {
        val f = DoubleArray(idx.size) { baseScore }
        for (t in finalBinTrees) updateScores(t, idx, f)
        for (k in f.indices) f[k] = GbdtModel.sigmoid(f[k])
        return f
    }

    fun close() = pool.shutdown()

    private fun weight(r: Int): Double =
        (rowWeight?.get(r)?.toDouble() ?: 1.0) * (if (ds.label[r].toInt() == 1) p.positiveWeight else 1.0)

    private fun logLoss(idx: IntArray, f: DoubleArray): Double {
        if (idx.isEmpty()) return Double.NaN
        var s = 0.0
        for (k in idx.indices) {
            val pr = GbdtModel.sigmoid(f[k]).coerceIn(1e-12, 1 - 1e-12)
            s -= if (ds.label[idx[k]].toInt() == 1) ln(pr) else ln(1 - pr)
        }
        return s / idx.size
    }

    private fun updateScores(buf: NodeBuf, idx: IntArray, f: DoubleArray) {
        val feat = buf.feature.toIntArray(); val bin = buf.bin.toIntArray(); val ml = buf.missLeft.toBooleanArray()
        val l = buf.left.toIntArray(); val r = buf.right.toIntArray(); val v = buf.value.toDoubleArray()
        pool.submit {
            IntStream.range(0, idx.size).parallel().forEach { k ->
                val row = idx[k]
                var node = 0
                while (feat[node] >= 0) {
                    val b = ds.bins[feat[node]][row].toInt() and 0xFF
                    node = if (b == 0) (if (ml[node]) l[node] else r[node]) else if (b <= bin[node]) l[node] else r[node]
                }
                f[k] += v[node]
            }
        }.get()
    }

    private fun toTree(buf: NodeBuf): Tree {
        val n = buf.value.size
        val feat = buf.feature.toIntArray()
        return Tree(
            feat,
            DoubleArray(n) { if (feat[it] >= 0) binner.threshold(feat[it], buf.bin[it]) else 0.0 },
            buf.missLeft.toBooleanArray(),
            buf.left.toIntArray(), buf.right.toIntArray(), buf.value.toDoubleArray(),
        )
    }

    private fun leafValue(g: Double, h: Double) = -g / (h + p.lambdaL2) * p.learningRate

    private fun buildTree(rows: IntArray, n: Int, trainIdx: IntArray, grad: DoubleArray, hess: DoubleArray,
                          feats: BooleanArray, importance: DoubleArray): NodeBuf {
        val buf = NodeBuf()
        var g0 = 0.0; var h0 = 0.0
        for (i in 0 until n) { g0 += grad[rows[i]]; h0 += hess[rows[i]] }
        val rootHist = buildHist(rows, 0, n, trainIdx, grad, hess, feats)
        val root = Leaf(0, n, 0, g0, h0, rootHist, null, buf.add(leafValue(g0, h0)))
        root.split = findSplit(root, feats)
        val queue = PriorityQueue<Leaf>(compareByDescending { it.split?.gain ?: -1.0 })
        queue += root
        var leaves = 1
        while (leaves < p.maxLeaves && queue.isNotEmpty()) {
            val leaf = queue.poll()
            val sp = leaf.split ?: break
            if (sp.gain <= 0) break
            // 划分行
            val col = ds.bins[sp.feature]
            var i = leaf.start
            var j = leaf.end - 1
            while (i <= j) {
                val b = col[trainIdx[rows[i]]].toInt() and 0xFF
                val goLeft = if (b == 0) sp.missingLeft else b <= sp.bin
                if (goLeft) i++ else { val t = rows[i]; rows[i] = rows[j]; rows[j] = t; j-- }
            }
            val mid = i
            importance[sp.feature] += sp.gain
            val lId = buf.add(leafValue(sp.gl, sp.hl))
            val rId = buf.add(leafValue(sp.gr, sp.hr))
            buf.feature[leaf.nodeId] = sp.feature
            buf.bin[leaf.nodeId] = sp.bin
            buf.missLeft[leaf.nodeId] = sp.missingLeft
            buf.left[leaf.nodeId] = lId
            buf.right[leaf.nodeId] = rId
            leaves++

            val leftSmaller = mid - leaf.start <= leaf.end - mid
            val small = if (leftSmaller) buildHist(rows, leaf.start, mid, trainIdx, grad, hess, feats)
            else buildHist(rows, mid, leaf.end, trainIdx, grad, hess, feats)
            val large = leaf.hist.also { it.subtract(it, small) } // 复用父节点内存
            val (hl, hr) = if (leftSmaller) small to large else large to small
            val lLeaf = Leaf(leaf.start, mid, leaf.depth + 1, sp.gl, sp.hl, hl, null, lId)
            val rLeaf = Leaf(mid, leaf.end, leaf.depth + 1, sp.gr, sp.hr, hr, null, rId)
            for (c in listOf(lLeaf, rLeaf)) {
                if (c.depth < p.maxDepth && c.end - c.start >= 2 * p.minDataInLeaf) {
                    c.split = findSplit(c, feats)
                    if (c.split != null) queue += c
                }
            }
        }
        return buf
    }

    private fun buildHist(rows: IntArray, from: Int, to: Int, trainIdx: IntArray, grad: DoubleArray, hess: DoubleArray,
                          feats: BooleanArray): Hist {
        val hist = Hist(totalBins)
        pool.submit {
            IntStream.range(0, nf).parallel().forEach { f ->
                if (!feats[f]) return@forEach
                val col = ds.bins[f]
                val off = offsets[f]
                val hg = hist.g; val hh = hist.h; val hc = hist.c
                for (i in from until to) {
                    val k = rows[i]
                    val b = off + (col[trainIdx[k]].toInt() and 0xFF)
                    hg[b] += grad[k]; hh[b] += hess[k]; hc[b]++
                }
            }
        }.get()
        return hist
    }

    private fun findSplit(leaf: Leaf, feats: BooleanArray): Split? {
        val lam = p.lambdaL2
        val parentScore = leaf.g * leaf.g / (leaf.h + lam)
        var best: Split? = null
        val total = leaf.end - leaf.start
        for (f in 0 until nf) {
            if (!feats[f]) continue
            val off = offsets[f]
            val nb = binner.nBins[f]
            val h = leaf.hist
            val gNa = h.g[off]; val hNa = h.h[off]; val cNa = h.c[off]
            var gl = 0.0; var hl = 0.0; var cl = 0
            for (b in 1 until nb - 1) {
                gl += h.g[off + b]; hl += h.h[off + b]; cl += h.c[off + b]
                // 缺失值分别尝试归左/归右;没有缺失值时两种走向等价,只算一次
                val directions = if (cNa > 0) 2 else 1
                for (d in 0 until directions) {
                    val missLeft = d == 1
                    val gL = if (missLeft) gl + gNa else gl
                    val hL = if (missLeft) hl + hNa else hl
                    val cL = if (missLeft) cl + cNa else cl
                    val cR = total - cL
                    if (cL < p.minDataInLeaf || cR < p.minDataInLeaf) continue
                    val gR = leaf.g - gL
                    val hR = leaf.h - hL
                    if (hL < p.minSumHessian || hR < p.minSumHessian) continue
                    val gain = gL * gL / (hL + lam) + gR * gR / (hR + lam) - parentScore
                    if (gain > (best?.gain ?: 1e-9)) best = Split(f, b, missLeft, gain, gL, hL, gR, hR)
                }
            }
        }
        return best
    }
}
