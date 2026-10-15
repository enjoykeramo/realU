package com.realu.trainer

import com.realu.core.feature.IndexContext
import com.realu.core.market.MarketContext
import com.realu.core.market.MarketContextBuilder
import com.realu.core.market.SectorMap
import com.realu.core.market.TradingCalendar
import java.util.TreeSet
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * 全市场预扫描:读一遍所有股票,构建市场情绪、板块/概念强度、个股板块内排名。
 * 板块归属:训练时按配置决定(行业 / 聚类 / 无);回测、选股时用模型文件自带的归属。
 */
object MarketPrep {
    fun build(cfg: Config, source: DataSource, stocks: List<StockFile>, index: IndexContext?,
              fixedSectors: SectorMap? = null, resolveSectors: Boolean = true): Pair<MarketContext, SectorMap?> {
        val t0 = System.currentTimeMillis()
        val calendar = TradingCalendar(index?.series?.date ?: unionDates(source, stocks, cfg.threads))
        val builder = MarketContextBuilder(calendar)
        parallelLoad(source, stocks, cfg.threads) { s -> builder.add(s) }
        Console.info("全市场预扫描:${builder.size} 只股票,交易日 ${calendar.size} 个,${MemoryGuard.describe()}")
        val sectors = fixedSectors ?: if (resolveSectors) Sectors.resolve(cfg, source, builder, calendar) else null
        val ctx = builder.build(sectors)
        Console.info("市场情绪/板块数据就绪(${(System.currentTimeMillis() - t0) / 1000} 秒)")
        return ctx to sectors
    }

    private fun unionDates(source: DataSource, stocks: List<StockFile>, threads: Int): IntArray {
        Console.warn("缺少大盘指数,用所有股票交易日的并集作为交易日历")
        val set = TreeSet<Int>()
        parallelLoad(source, stocks, threads) { s -> synchronized(set) { s.date.forEach { set += it } } }
        return set.toIntArray()
    }

    fun parallelLoad(source: DataSource, stocks: List<StockFile>, threads: Int, use: (com.realu.core.data.BarSeries) -> Unit) {
        val pool = Executors.newFixedThreadPool(threads)
        try {
            for (batch in stocks.chunked(threads * 8)) {
                pool.invokeAll(batch.map { sf -> Callable { runCatching { source.load(sf).series?.let(use) } } }).forEach { it.get() }
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
