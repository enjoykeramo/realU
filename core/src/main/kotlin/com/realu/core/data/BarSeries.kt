package com.realu.core.data

/** 交易板块。决定涨跌停幅度,也作为一个模型特征。 */
enum class Board(val label: String, val code: Int) {
    SH_MAIN("沪主板", 0),
    SZ_MAIN("深主板", 0),
    CHINEXT("创业板", 1),
    STAR("科创板", 2),
    BJ("北交所", 3),
    INDEX("指数", -1),
    OTHER("其他", -1),
}

data class StockCode(val market: String, val code: String) {
    val symbol: String get() = market + code

    val board: Board = classify(market, code)

    val isAStock: Boolean get() = board != Board.INDEX && board != Board.OTHER

    /** 某交易日的涨跌停幅度(小数)。名称未知时无法判断 ST,按非 ST 处理。 */
    fun limitPct(date: Int, isSt: Boolean = false): Double = when (board) {
        Board.CHINEXT -> if (date >= CHINEXT_REFORM_DATE) 0.20 else if (isSt) 0.05 else 0.10
        Board.STAR -> 0.20
        Board.BJ -> 0.30
        Board.SH_MAIN, Board.SZ_MAIN -> if (isSt) 0.05 else 0.10
        else -> 0.10
    }

    override fun toString(): String = symbol

    companion object {
        /** 创业板注册制改革,涨跌幅 10% → 20%。 */
        const val CHINEXT_REFORM_DATE = 20200824

        private val SUFFIX_FORM = Regex("^(\\d{6})\\.(sh|sz|bj)$")

        private fun classify(market: String, code: String): Board {
            if (code.length != 6) return Board.OTHER
            return when (market) {
                "sh" -> when {
                    code.startsWith("000") -> Board.INDEX
                    code.startsWith("600") || code.startsWith("601") ||
                        code.startsWith("603") || code.startsWith("605") -> Board.SH_MAIN
                    code.startsWith("688") || code.startsWith("689") -> Board.STAR
                    else -> Board.OTHER
                }
                "sz" -> when {
                    code.startsWith("399") -> Board.INDEX
                    code.startsWith("000") || code.startsWith("001") || code.startsWith("002") ||
                        code.startsWith("003") || code.startsWith("004") -> Board.SZ_MAIN
                    code.startsWith("300") || code.startsWith("301") || code.startsWith("302") -> Board.CHINEXT
                    else -> Board.OTHER
                }
                "bj" -> when {
                    code.startsWith("899") -> Board.INDEX
                    code.startsWith("43") || code.startsWith("83") || code.startsWith("87") ||
                        code.startsWith("88") || code.startsWith("92") -> Board.BJ
                    else -> Board.OTHER
                }
                else -> Board.OTHER
            }
        }

        /**
         * 支持:sh600000、SH600000、sh600000.day、SH#600000.txt、600000.SH、600000(按代码段推断市场)。
         */
        fun parse(text: String): StockCode? {
            val raw = text.trim().substringAfterLast('/').substringAfterLast('\\').lowercase()
            SUFFIX_FORM.matchEntire(raw)?.let { return fromParts(it.groupValues[2], it.groupValues[1]) }
            val s = raw.substringBefore('.').replace("#", "").replace("_", "")
            if (s.length == 8 && s.substring(0, 2) in setOf("sh", "sz", "bj")) return fromParts(s.substring(0, 2), s.substring(2))
            if (s.length == 6 && s.all { it.isDigit() }) return fromParts(guessMarket(s), s)
            return null
        }

        private fun fromParts(market: String, code: String): StockCode? =
            if (code.length == 6 && code.all { it.isDigit() }) StockCode(market, code) else null

        fun guessMarket(code: String): String = when {
            code.startsWith("6") || code.startsWith("9") && !code.startsWith("92") -> "sh"
            code.startsWith("4") || code.startsWith("8") || code.startsWith("92") -> "bj"
            else -> "sz"
        }
    }
}

/**
 * 单只股票的日K序列,列式存储(每个字段一个数组),省内存也方便指标计算。
 * date 为 yyyyMMdd 整数;volume 单位随数据源(股或手),模型只使用其比值;amount 单位为元。
 */
class BarSeries(
    val code: StockCode,
    var name: String,
    val date: IntArray,
    val open: DoubleArray,
    val high: DoubleArray,
    val low: DoubleArray,
    val close: DoubleArray,
    val volume: DoubleArray,
    val amount: DoubleArray,
) {
    val size: Int get() = date.size

    init {
        require(open.size == size && high.size == size && low.size == size && close.size == size &&
            volume.size == size && amount.size == size) { "BarSeries 各列长度不一致" }
    }

    val isSt: Boolean get() = name.contains("ST", ignoreCase = true) || name.contains("退")

    fun limitPct(i: Int): Double = code.limitPct(date[i], isSt)

    /** 按日期找下标,不存在返回 -(插入点)-1。 */
    fun indexOf(d: Int): Int = java.util.Arrays.binarySearch(date, d)

    /** 截取 [from, to) 区间。 */
    fun slice(from: Int, to: Int): BarSeries = BarSeries(
        code, name,
        date.copyOfRange(from, to), open.copyOfRange(from, to), high.copyOfRange(from, to),
        low.copyOfRange(from, to), close.copyOfRange(from, to), volume.copyOfRange(from, to),
        amount.copyOfRange(from, to),
    )

    /** 当日是否涨停(收盘)。前复权价格存在小数误差,留 0.3% 容差。 */
    fun isLimitUp(i: Int): Boolean =
        i > 0 && close[i] / close[i - 1] - 1.0 >= limitPct(i) - 0.003

    fun isLimitDown(i: Int): Boolean =
        i > 0 && close[i] / close[i - 1] - 1.0 <= -limitPct(i) + 0.003

    /** 一字涨停:开高低收相同且涨停,散户基本买不到。 */
    fun isOneWordLimitUp(i: Int): Boolean =
        isLimitUp(i) && high[i] - low[i] <= high[i] * 1e-4

    fun isOneWordLimitDown(i: Int): Boolean =
        isLimitDown(i) && high[i] - low[i] <= high[i] * 1e-4
}

/** 日期工具(yyyyMMdd 整数)。 */
object Dates {
    fun isValid(d: Int): Boolean {
        val y = d / 10000
        val m = d / 100 % 100
        val day = d % 100
        return y in 1990..2100 && m in 1..12 && day in 1..31
    }

    fun toEpochDay(d: Int): Long =
        java.time.LocalDate.of(d / 10000, d / 100 % 100, d % 100).toEpochDay()

    fun daysBetween(a: Int, b: Int): Long = toEpochDay(b) - toEpochDay(a)

    fun plusDays(d: Int, n: Long): Int {
        val ld = java.time.LocalDate.ofEpochDay(toEpochDay(d) + n)
        return ld.year * 10000 + ld.monthValue * 100 + ld.dayOfMonth
    }

    fun format(d: Int): String = "%04d-%02d-%02d".format(d / 10000, d / 100 % 100, d % 100)

    /** 解析 2020/01/02、2020-01-02、20200102。 */
    fun parse(s: String): Int? {
        val t = s.trim()
        val digits = t.filter { it.isDigit() }
        val parts = t.split('/', '-', '.')
        val d = if (parts.size == 3) {
            val y = parts[0].toIntOrNull() ?: return null
            val m = parts[1].toIntOrNull() ?: return null
            val dd = parts[2].toIntOrNull() ?: return null
            y * 10000 + m * 100 + dd
        } else if (digits.length == 8 && digits.length == t.length) {
            digits.toInt()
        } else return null
        return if (isValid(d)) d else null
    }
}
