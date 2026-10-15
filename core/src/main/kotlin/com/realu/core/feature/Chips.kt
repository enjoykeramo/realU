package com.realu.core.feature

import com.realu.core.data.BarSeries
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * 筹码分布(成本分布)近似,思路与通达信 CYQ 相同:
 * 每天先按换手率 τ 让旧筹码"换手"衰减(× (1−τ)),再把当天成交的 τ 份筹码按三角分布摊到 [最低价, 最高价],
 * 峰值在 (高+低+2×收)/4。
 *
 * 只有日K、没有流通股本时,换手率用 τ = 平均换手率 × 当日量 / 近250日均量 估算(相对换手);
 * 提供流通股本(data.floatSharesCsv)时用真实换手率。
 * 价格网格为固定的对数刻度(每格 1%),与数据从哪天开始无关;APP 端只要传入足够长的历史(≥500 根)即可得到相同结果。
 */
object ChipDistribution {
    private const val BIN = 0.01
    private const val OFFSET = 1200
    private const val SIZE = 2400
    /** 无股本数据时假设的平均日换手率。属于特征定义的一部分,修改需提升 FeatureDefs.VERSION。 */
    const val AVG_TURNOVER = 0.02
    private const val WARMUP = 60

    val NAMES = listOf("chipWinner", "chipAvgDev", "chipConc90", "chipConc70", "chipOverhead10", "chipSupport10", "chipWinnerChg5")

    private fun binOf(p: Double): Int = (ln(p) / BIN).let { kotlin.math.floor(it).toInt() + OFFSET }.coerceIn(0, SIZE - 1)
    private fun center(b: Int): Double = (b - OFFSET + 0.5) * BIN

    /** 返回与 [NAMES] 对应的 7 列,每列长度 = K 线数。 */
    fun compute(s: BarSeries, turnoverPct: DoubleArray?): Array<FloatArray> {
        val n = s.size
        val out = Array(NAMES.size) { FloatArray(n) { Float.NaN } }
        if (n == 0) return out
        val m = DoubleArray(SIZE)
        var scale = 1.0
        var lo = SIZE
        var hi = -1
        val w = DoubleArray(SIZE)
        var vSum = 0.0
        val ln11 = ln(1.1)

        for (i in 0 until n) {
            val h = s.high[i]; val l = s.low[i]; val c = s.close[i]
            vSum += s.volume[i]
            if (i >= 250) vSum -= s.volume[i - 250]
            if (h <= 0 || l <= 0 || c <= 0) continue
            val vbar = vSum / min(i + 1, 250)
            val tau = if (i == 0) 1.0 else {
                val real = turnoverPct?.get(i)?.takeIf { it.isFinite() && it > 0 }?.div(100)
                (real ?: (AVG_TURNOVER * s.volume[i] / max(vbar, 1.0))).coerceIn(0.0005, if (real != null) 0.9 else 0.5)
            }
            // 1) 旧筹码衰减
            scale *= 1 - tau
            if (scale < 1e-150) {
                for (b in lo..hi) m[b] *= scale
                scale = 1.0
            }
            // 2) 新筹码三角分布
            val bl = binOf(l); val bh = binOf(h)
            lo = min(lo, bl); hi = max(hi, bh)
            if (bl == bh) {
                m[bl] += tau / scale
            } else {
                val lnL = ln(l); val lnH = ln(h); val lnT = ln((h + l + 2 * c) / 4).coerceIn(lnL, lnH)
                var ws = 0.0
                for (b in bl..bh) {
                    val x = center(b).coerceIn(lnL, lnH)
                    val tri = when {
                        x <= lnT -> if (lnT > lnL) (x - lnL) / (lnT - lnL) else 1.0
                        else -> if (lnH > lnT) (lnH - x) / (lnH - lnT) else 1.0
                    }
                    w[b] = tri + 0.05
                    ws += w[b]
                }
                val k = tau / scale / ws
                for (b in bl..bh) m[b] += w[b] * k
            }
            // 3) 定期收缩有效区间(远离现价、已几乎换手完毕的筹码)
            if (i % 20 == 0) {
                while (lo < hi && m[lo] * scale < 1e-7) { m[lo] = 0.0; lo++ }
                while (hi > lo && m[hi] * scale < 1e-7) { m[hi] = 0.0; hi-- }
            }
            if (i < WARMUP) continue

            // 4) 统计
            var total = 0.0; var costSum = 0.0
            for (b in lo..hi) { val v = m[b]; total += v; costSum += v * exp(center(b)) }
            if (total <= 0) continue
            val lnC = ln(c)
            val bc = binOf(c)
            var win = 0.0; var over = 0.0; var sup = 0.0
            val upper = lnC + ln11; val lower = lnC - ln11
            // 一次遍历同时得到获利盘、上方套牢盘、下方支撑与 4 个分位点
            val qs = doubleArrayOf(0.05, 0.15, 0.85, 0.95)
            val qv = DoubleArray(4) { exp(center(hi)) }
            var qk = 0
            var acc = 0.0
            for (b in lo..hi) {
                val v = m[b]
                if (v == 0.0) continue
                val x = center(b)
                when {
                    b < bc -> { win += v; if (x >= lower) sup += v }
                    b == bc -> { win += v * 0.5; sup += v * 0.5; over += v * 0.5 }
                    else -> if (x <= upper) over += v
                }
                acc += v
                while (qk < 4 && acc >= total * qs[qk]) { qv[qk] = exp(x); qk++ }
            }
            val p5 = qv[0]; val p15 = qv[1]; val p85 = qv[2]; val p95 = qv[3]
            out[0][i] = (win / total).toFloat()
            out[1][i] = (c / (costSum / total) - 1).toFloat()
            out[2][i] = ((p95 - p5) / (p95 + p5)).toFloat()
            out[3][i] = ((p85 - p15) / (p85 + p15)).toFloat()
            out[4][i] = (over / total).toFloat()
            out[5][i] = (sup / total).toFloat()
            if (i >= WARMUP + 5) out[6][i] = out[0][i] - out[0][i - 5]
        }
        return out
    }

}
