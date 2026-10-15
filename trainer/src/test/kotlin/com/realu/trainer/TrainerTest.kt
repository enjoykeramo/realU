package com.realu.trainer

import com.realu.core.feature.FeatureDefs
import com.realu.core.model.RealuModel
import com.realu.core.strategy.ExitRule
import com.realu.core.strategy.GateParams
import com.realu.core.strategy.LabelParams
import java.io.File
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrainerTest {

    /** 训练用的分箱预测 与 导出后按浮点阈值的预测 必须一致(APP 与训练器口径一致的关键)。 */
    @Test
    fun binnedPredictionMatchesExportedModel() {
        val nf = FeatureDefs.COUNT
        val rnd = Random(3)
        val n = 20000
        val raw = List(n) { FloatArray(nf) { f -> if (f == 2 && rnd.nextDouble() < 0.2) Float.NaN else rnd.nextGaussian().toFloat() } }
        val label = ByteArray(n) { i ->
            val x = raw[i]
            val z = 1.5 * x[0] - x[1] + (if (x[2].isNaN()) 1.0 else 0.3 * x[2]) + rnd.nextGaussian() * 0.5
            if (z > 1.0) 1 else 0
        }
        val binner = Binner.fit(raw.take(5000), 32)
        val bins = Array(nf) { f -> ByteArray(n) { i -> binner.bin(f, raw[i][f]).toByte() } }
        val ds = Dataset(bins, label, IntArray(n) { 20200101 }, IntArray(n) { 20200101 }, IntArray(n) { it / 10 },
            ByteArray(n), FloatArray(n), IntArray(n) { it })
        val trainIdx = (0 until 15000).toList().toIntArray()
        val validIdx = (15000 until n).toList().toIntArray()
        val trainer = GbdtTrainer(ds, binner, GbdtParams(rounds = 60, minDataInLeaf = 50, earlyStopping = 100), 2)
        val tr = trainer.train(trainIdx, validIdx) {}
        val pBins = trainer.predict(validIdx)
        trainer.close()

        // 经 JSON 导出 → 读回(模拟 APP 导入)
        val model = RealuModel("t", "i", "now", FeatureDefs.VERSION, FeatureDefs.NAMES, GateParams(), LabelParams(), ExitRule(),
            tr.model, 0.5, true, emptyMap(), mapOf("K线组合" to 100), emptyMap())
        val loaded = RealuModel.fromJson(model.toJson())
        for (k in validIdx.indices) {
            val p = loaded.gbdt.probability(raw[validIdx[k]])
            assertEquals(pBins[k], p, 1e-5, "第 $k 个验证样本预测不一致")
        }
        val y = IntArray(validIdx.size) { label[validIdx[it]].toInt() }
        assertTrue(Metrics.auc(pBins, y) > 0.85, "模型应学到可分的规律")
        assertTrue(tr.gainImportance[0] > tr.gainImportance[10], "x0 应比无关特征更重要")
    }

    @Test
    fun metrics() {
        assertEquals(1.0, Metrics.auc(doubleArrayOf(0.1, 0.2, 0.8, 0.9), intArrayOf(0, 0, 1, 1)))
        assertEquals(0.5, Metrics.auc(doubleArrayOf(0.5, 0.5), intArrayOf(0, 1)))
        assertTrue(Metrics.wilsonLower(10, 10) < 0.75) // 10 中 10,置信下限远低于 100%
        assertTrue(Metrics.wilsonLower(950, 1000) > 0.93)
    }

    private fun cand(stock: Int, bar: Int, prob: Double, ret: Float, date: Int = 20200101 + bar) = Candidate(
        stock, "s$stock", "", date, bar, prob, com.realu.core.strategy.Pattern.OTHER, true, 10f,
        floatArrayOf(ret), shortArrayOf(5), byteArrayOf(com.realu.core.strategy.ExitReason.TAKE_PROFIT.ordinal.toByte()),
        IntArray(10) { date + 1 + it }, FloatArray(10) { 10f })

    @Test
    fun selectorCountsOneSignalPerLaunch() {
        // 同一只股票连续 5 天高分(同一波启动)且都成功;另一只分数略低且失败
        val cands = (0 until 5).map { cand(0, 100 + it, 0.9, 0.35f) } + cand(1, 50, 0.85, -0.1f)
        val sorted = cands.sortedWith(compareBy<Candidate> { it.date }.thenByDescending { it.prob })
        val sel = Selector.dedupe(sorted, 0.85, 20)
        assertEquals(2, sel.size) // 不去重会算成 5/6,去重后 1/2
        val lp = com.realu.core.strategy.LabelParams()
        val st = TradeStats(sel, 0, lp)
        assertEquals(0.5, st.precision)
        val choice = Selector.select(sorted, listOf(com.realu.core.strategy.ExitRule()), lp, 0.95, 1, 1, 20, "precision", 99991231)
        assertTrue(choice.achieved)
        // 阈值 0.9 排除失败的那只:1/1 达标;0.85 时只有 1/2;其他类型没有候选 → 不启用
        assertEquals(0.9, choice.threshold(com.realu.core.strategy.Pattern.OTHER))
        assertTrue(!choice.enabled(com.realu.core.strategy.Pattern.BOX_BREAKOUT))
    }

    @Test
    fun perPatternThresholdsAndFallback() {
        val P = com.realu.core.strategy.Pattern.entries
        // 超跌反弹 8 成功 / 2 失败;箱体突破 2 成功 / 8 失败:各自选阈值,弱的类型不应拖低整体
        var bar = 0
        fun mk(stock: Int, pat: com.realu.core.strategy.Pattern, prob: Double, ok: Boolean) = Candidate(
            stock, "s$stock", "", 20200101 + bar, bar++, prob, pat, true, 10f, floatArrayOf(if (ok) 0.35f else -0.1f), shortArrayOf(5),
            byteArrayOf(com.realu.core.strategy.ExitReason.TAKE_PROFIT.ordinal.toByte()), IntArray(10) { 20200102 + it }, FloatArray(10) { 10f })
        val c = (0 until 10).map { mk(it, P[1], 0.6 + it * 0.01, it >= 2) } + (0 until 10).map { mk(100 + it, P[0], 0.7 + it * 0.01, it >= 8) }
        val sorted = c.sortedWith(compareBy<Candidate> { it.date }.thenByDescending { it.prob })
        val lp = com.realu.core.strategy.LabelParams()
        val sel = Selector.select(sorted, listOf(com.realu.core.strategy.ExitRule()), lp, 0.95, 5, 3, 20, "precision", 99991231)
        // 超跌反弹最高的 8 个全部成功 → 达标;箱体突破最多 2/2 不够 3 个信号 → 不启用
        assertTrue(sel.achieved)
        assertTrue(sel.enabled(P[1]))
        assertTrue(!sel.enabled(P[0]))
        assertEquals(1.0, sel.stats.precision)
        // 分位数迁移:新模型概率整体变小,也能选出同样比例
        val probs = doubleArrayOf(0.1, 0.2, 0.3, 0.4); val pats = ByteArray(4) { 1 }
        val fr = doubleArrayOf(-1.0, 0.5, -1.0, -1.0)
        assertEquals(0.3, ThresholdMapper.map(fr, probs, pats)[1])
        assertTrue(ThresholdMapper.map(fr, probs, pats)[0] > 1.0)
    }

    @Test
    fun tdxBlockFilesRoundTrip() {
        val dir = kotlin.io.path.createTempDirectory("realu").toFile()
        try {
            SyntheticData.writeTdx(dir, stocks = 24, days = 300, seed = 1, sectors = 4)
            val hq = File(dir, "T0002/hq_cache")
            val ind = TdxSectorFiles.readIndustry(File(hq, "tdxhy.cfg"), 2)
            assertEquals(24, ind.size)
            assertEquals(4, ind.values.distinct().size)
            val blocks = TdxSectorFiles.readBlocks(File(hq, "block_gn.dat"))
            assertEquals(5, blocks.size)
            assertEquals("概念00", blocks[0].first)
            assertEquals(6, blocks[0].second.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun kmeansSeparatesCorrelatedGroups() {
        val rnd = Random(2)
        val f = Array(3) { FloatArray(250) { rnd.nextGaussian().toFloat() } }
        val x = (0 until 60).map { i ->
            val v = FloatArray(250) { t -> f[i % 3][t] + 0.3f * rnd.nextGaussian().toFloat() }
            val m = v.average().toFloat(); for (t in v.indices) v[t] -= m
            val norm = kotlin.math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat(); for (t in v.indices) v[t] /= norm
            v
        }
        val a = SectorClustering.kmeans(x, 3, Random(1))
        for (i in 0 until 60) assertEquals(a[i % 3], a[i], "同一因子的股票应在同一组")
        assertEquals(3, a.toSet().size)
    }

    @Test
    fun syntheticEndToEndShapes() {
        val bars = SyntheticData.generate(600, Random(5))
        for (i in 0 until 600) {
            assertTrue(bars.high[i] >= bars.close[i] - 1e-9 && bars.low[i] <= bars.close[i] + 1e-9)
        }
    }
}
