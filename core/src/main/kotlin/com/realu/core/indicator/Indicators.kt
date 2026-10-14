package com.realu.core.indicator

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 常用技术指标,口径与通达信公式一致(SMA(X,N,M) 为通达信的加权移动平均)。
 * 预热期不足的位置返回 NaN,模型对缺失值有专门处理。
 */
object Ind {
    const val NaN = Double.NaN

    /** 简单移动平均 MA(X,N)。 */
    fun ma(x: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(x.size) { NaN }
        var sum = 0.0
        for (i in x.indices) {
            sum += x[i]
            if (i >= n) sum -= x[i - n]
            if (i >= n - 1) out[i] = sum / n
        }
        return out
    }

    /** EMA(X,N),首值取 X[0](与通达信一致)。 */
    fun ema(x: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(x.size)
        if (x.isEmpty()) return out
        val a = 2.0 / (n + 1)
        out[0] = x[0]
        for (i in 1 until x.size) out[i] = a * x[i] + (1 - a) * out[i - 1]
        return out
    }

    /** 通达信 SMA(X,N,M):Y = (M*X + (N-M)*Y') / N。NaN 输入时沿用前值。 */
    fun sma(x: DoubleArray, n: Int, m: Int): DoubleArray {
        val out = DoubleArray(x.size) { NaN }
        var y = NaN
        for (i in x.indices) {
            val v = x[i]
            y = when {
                v.isNaN() -> y
                y.isNaN() -> v
                else -> (m * v + (n - m) * y) / n
            }
            out[i] = y
        }
        return out
    }

    fun rollingStd(x: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(x.size) { NaN }
        var s = 0.0
        var s2 = 0.0
        var cnt = 0
        for (i in x.indices) {
            val v = x[i]
            if (!v.isNaN()) { s += v; s2 += v * v; cnt++ }
            if (i >= n) {
                val o = x[i - n]
                if (!o.isNaN()) { s -= o; s2 -= o * o; cnt-- }
            }
            if (i >= n - 1 && cnt >= 2) {
                val mean = s / cnt
                out[i] = sqrt(max(0.0, s2 / cnt - mean * mean))
            }
        }
        return out
    }

    /** 区间 [i-n+1, i] 最大值(单调队列,O(N))。 */
    fun hhv(x: DoubleArray, n: Int): DoubleArray = rollingExtreme(x, n, true)

    fun llv(x: DoubleArray, n: Int): DoubleArray = rollingExtreme(x, n, false)

    private fun rollingExtreme(x: DoubleArray, n: Int, isMax: Boolean): DoubleArray {
        val out = DoubleArray(x.size) { NaN }
        val dq = IntArray(x.size)
        var head = 0
        var tail = 0
        for (i in x.indices) {
            while (tail > head && (if (isMax) x[dq[tail - 1]] <= x[i] else x[dq[tail - 1]] >= x[i])) tail--
            dq[tail++] = i
            if (dq[head] <= i - n) head++
            if (i >= n - 1) out[i] = x[dq[head]]
        }
        return out
    }

    /** 引用 N 日前的值 REF(X,N)。 */
    fun ref(x: DoubleArray, n: Int): DoubleArray = DoubleArray(x.size) { if (it >= n) x[it - n] else NaN }

    fun rsi(close: DoubleArray, n: Int): DoubleArray {
        val up = DoubleArray(close.size)
        val abs = DoubleArray(close.size)
        for (i in 1 until close.size) {
            val d = close[i] - close[i - 1]
            up[i] = max(d, 0.0)
            abs[i] = abs(d)
        }
        val a = sma(up, n, 1)
        val b = sma(abs, n, 1)
        return DoubleArray(close.size) { if (it < n || b[it] == 0.0) NaN else a[it] / b[it] * 100 }
    }

    class Macd(val dif: DoubleArray, val dea: DoubleArray, val hist: DoubleArray)

    fun macd(close: DoubleArray, short: Int = 12, long: Int = 26, mid: Int = 9): Macd {
        val e1 = ema(close, short)
        val e2 = ema(close, long)
        val dif = DoubleArray(close.size) { e1[it] - e2[it] }
        val dea = ema(dif, mid)
        val hist = DoubleArray(close.size) { 2 * (dif[it] - dea[it]) }
        return Macd(dif, dea, hist)
    }

    class Kdj(val k: DoubleArray, val d: DoubleArray, val j: DoubleArray)

    fun kdj(high: DoubleArray, low: DoubleArray, close: DoubleArray, n: Int = 9, m1: Int = 3, m2: Int = 3): Kdj {
        val hh = hhv(high, n)
        val ll = llv(low, n)
        val rsv = DoubleArray(close.size) {
            val r = hh[it] - ll[it]
            if (hh[it].isNaN()) NaN else if (r <= 0) 50.0 else (close[it] - ll[it]) / r * 100
        }
        val k = sma(rsv, m1, 1)
        val d = sma(k, m2, 1)
        val j = DoubleArray(close.size) { 3 * k[it] - 2 * d[it] }
        return Kdj(k, d, j)
    }

    class Boll(val mid: DoubleArray, val upper: DoubleArray, val lower: DoubleArray)

    fun boll(close: DoubleArray, n: Int = 20, k: Double = 2.0): Boll {
        val mid = ma(close, n)
        val sd = rollingStd(close, n)
        return Boll(mid, DoubleArray(close.size) { mid[it] + k * sd[it] }, DoubleArray(close.size) { mid[it] - k * sd[it] })
    }

    fun atr(high: DoubleArray, low: DoubleArray, close: DoubleArray, n: Int = 14): DoubleArray {
        val tr = DoubleArray(close.size)
        for (i in close.indices) {
            tr[i] = if (i == 0) high[i] - low[i]
            else max(high[i] - low[i], max(abs(high[i] - close[i - 1]), abs(low[i] - close[i - 1])))
        }
        return ma(tr, n)
    }

    /** 区间 [i-n+1, i] 的皮尔逊相关系数。 */
    fun rollingCorr(a: DoubleArray, b: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(a.size) { NaN }
        for (i in n - 1 until a.size) {
            var sa = 0.0; var sb = 0.0; var saa = 0.0; var sbb = 0.0; var sab = 0.0; var c = 0
            for (j in i - n + 1..i) {
                val x = a[j]; val y = b[j]
                if (x.isNaN() || y.isNaN()) continue
                sa += x; sb += y; saa += x * x; sbb += y * y; sab += x * y; c++
            }
            if (c < n / 2) continue
            val cov = sab / c - sa / c * sb / c
            val va = saa / c - (sa / c) * (sa / c)
            val vb = sbb / c - (sb / c) * (sb / c)
            if (va > 1e-18 && vb > 1e-18) out[i] = (cov / sqrt(va * vb)).coerceIn(-1.0, 1.0)
        }
        return out
    }

    fun clamp(v: Double, lo: Double, hi: Double): Double = if (v.isNaN()) v else min(hi, max(lo, v))
}
