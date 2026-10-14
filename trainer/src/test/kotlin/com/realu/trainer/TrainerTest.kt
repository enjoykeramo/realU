package com.realu.trainer

import com.realu.core.feature.FeatureDefs
import com.realu.core.model.RealuModel
import com.realu.core.strategy.ExitRule
import com.realu.core.strategy.GateParams
import com.realu.core.strategy.LabelParams
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

    @Test
    fun dedupThresholdCountsOnePerLaunch() {
        // 同一只股票连续 5 天高分(同一波启动),标签全为 1;另一只股票 1 天高分,标签 0
        val scores = doubleArrayOf(0.9, 0.9, 0.9, 0.9, 0.9, 0.95)
        val labels = intArrayOf(1, 1, 1, 1, 1, 0)
        val stock = intArrayOf(0, 0, 0, 0, 0, 1)
        val bar = intArrayOf(100, 101, 102, 103, 104, 50)
        val c = Metrics.chooseThresholdDedup(scores, labels, stock, bar, 20, 0.95, 1)
        // 去重后最多 2 个信号:不去重会算成 5/6=83%,去重后是 1/2=50%
        assertTrue(c.signals <= 2)
        assertEquals(0.9, c.threshold)
    }

    @Test
    fun syntheticEndToEndShapes() {
        val bars = SyntheticData.generate(600, Random(5))
        for (i in 0 until 600) {
            assertTrue(bars.high[i] >= bars.close[i] - 1e-9 && bars.low[i] <= bars.close[i] + 1e-9)
        }
    }
}
