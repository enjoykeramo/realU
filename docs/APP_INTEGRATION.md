# APP 导入与使用模型

训练产出的 `model-*.json` 是一个自包含的策略文件:候选条件 + GBDT 模型 + 阈值 + 卖出规则 + 历史检验指标。
APP 端与训练器**共用 `core` 模块**计算特征和推理,保证口径一致。

## 1. 把 core 模块加入 Android 工程

`core` 是纯 Kotlin 模块,没有第三方依赖。两种方式任选:

- **源码引入**:把 `core/` 目录复制到 Android 工程,`settings.gradle.kts` 中 `include(":core")`,app 模块 `implementation(project(":core"))`。
- **jar 引入**:`gradlew :core:jar` 生成 `core/build/libs/core-1.0.0.jar`,放入 `app/libs/`。

要求:`minSdk ≥ 26`(用到 `java.time`),或开启 core library desugaring。

## 2. 读取通达信数据(与原型"设置 → 数据源"对应)

原型中用户通过 USB 把 `vipdoc` 拷到手机并选择目录。用 SAF 拿到的是 `Uri`,读成字节数组后交给同一个解析器:

```kotlin
val bytes = contentResolver.openInputStream(dayFileUri)!!.use { it.readBytes() }
val code = StockCode.parse(fileName) ?: return          // 如 "sh600000.day"
val series = TdxDayReader.parse(bytes, code, name, TdxDayReader.priceDivisorFor(code)).series ?: return
ExRightsAdjuster.forwardAdjust(series)                    // .day 为不复权数据,与训练时 data.adjustExRights 保持一致
```

指数(默认 `sh000001.day`)同样读取后构造 `IndexContext(indexSeries)`,其交易日作为日历 `TradingCalendar(indexSeries.date)`。

**请传入完整历史**(至少 `FeatureEngine.MIN_HISTORY_BARS` = 500 根):均线、MACD 等需要预热,"上市满 120 日"也按序列长度判断。

## 3. 导入模型并选股

```kotlin
val model = RealuModel.fromJson(jsonText)   // 格式错误会抛异常,提示用户
model.checkCompatible()                     // 特征版本不一致时抛异常:提示"请用新版训练器重新训练"
if (!model.passed) showWarning("该模型历史检验未达标:${model.metrics["verdict"]}")

val engine = SignalEngine(model)
// 每个交易日先用全市场股票构建一次截面数据:市场情绪、板块/概念强度、个股板块内排名
// 板块/概念归属来自模型文件(训练时的最新划分),APP 不需要 hq_cache
val market = engine.buildMarket(TradingCalendar(indexSeries.date), allSeries.asSequence())
val signal = engine.evaluate(series, indexContext, market = market)   // 默认评估最后一个交易日
if (signal != null && signal.triggered) {
    // signal.score       0–100 分,阈值映射为 60 分(与原型"入选线 ≥ 60"一致)
    // signal.pattern     箱体突破 / 超跌反弹 / 趋势波段 / 其他
    // signal.reasons     影响最大的 8 个特征:label、group、value、impact(>0 加分,<0 减分)
    // signal.stopPrice   以收盘价估算的止损价(实际按次日买入价重算)
}
```

全市场扫描:`engine.scan(allSeries.asSequence(), indexContext, market)` 返回按概率降序的触发信号。
构建 `market` 需要遍历一次全市场(每只股票只保留对齐后的收盘价与状态位,5000 只 × 500 日约 15MB),之后逐只评估。
**不传 `market` 时情绪/板块/概念特征为缺失值,与训练口径不一致,评分会失真,务必传入。**
最新交易日的市场情绪可以直接展示在盯盘页:`market.marketAt(date)`(涨停家数占比、炸板率、连板高度、晋级率、涨停溢价等)。

## 4. 与原型页面的对应关系

| 原型页面 | 使用模型 JSON 中的字段 |
|---|---|
| O · 选股(策略卡片) | 新增策略卡片:`name`(主升初期)、`strategyId`(i),副标题可用 `display.hardConditions` 前两项;命中数 = 当日 `triggered` 信号数 |
| O · 规则页 · 硬条件 | `display.hardConditions[]`(label/value);数值来自 `gate`,与训练一致,**不建议在 APP 里改**(改了就不是训练时的口径) |
| O · 规则页 · 评分因子 | `display.scoreFactors[]`(name/weight,合计 100,由特征重要性汇总);入选线 = `scoreLine`(60) |
| O · 选股结果 | `score`、`pattern.label`、`reasons`(impact>0 显示 ✓,<0 显示 ×),AI 复核可把 `reasons` 作为上下文 |
| T · 建仓计划 · 风控与退出 | `exit`(按收盘价判断):stopLoss / takeProfit / trailingActivate / trailingDrawdown / profitFloor / maxHoldDays;文案用 `display.exitText` |
| 策略说明 | `label.text`:目标定义(次日开盘买入,允许回撤但收盘不破 −15%,60 日内收盘净赚 30%) |
| S · 盯盘 | `market.marketAt(最新日期)`:市场情绪 |
| 策略详情 / 模型信息 | `metrics`:verdict、test.precision、test.precisionLower95、test.avgReturn、portfolio.maxDrawdown;`data`:训练区间、是否接入大单数据 |

## 5. 模型 JSON 结构

```jsonc
{
  "format": "realu-model", "formatVersion": 2,
  "name": "主升初期", "strategyId": "i", "createdAt": "...",
  "passed": true,                       // 历史检验是否达标
  "threshold": 0.487,                   // 概率阈值
  "scoreLine": 60,
  "gate":  { "minListedDays": 120, "minAmount5": 3e7, "minRet1": 0.03, "minVolRatio": 1.8,
             "maxPriorRise20": 0.25, "requireUpClose": true, "excludeSt": true, "resumeCooldown": 5 },
  "label": { "horizon": 60, "target": 0.3, "maxDrawdown": 0.15, "cost": 0.003, "text": "次日开盘买入后 60 个交易日内……" },
  "exit":  { "basis": "close", "stopLoss": 0.15, "takeProfit": 0.303, "trailingActivate": 0, "trailingDrawdown": 0,
             "profitFloor": 0, "maxHoldDays": 60 },
  "sectors": { "source": "cluster",          // industry / cluster / none
               "groupNames": [...],          // 行业名称(聚类时为空)
               "groups": { "sh600000": 12, ... },        // 代码 → 板块号
               "conceptNames": ["概念A", ...],
               "concepts": { "sh600000": [3, 17], ... } }, // 代码 → 概念号列表
  "display": { "hardConditions": [{"label": "...", "value": "..."}],
               "scoreFactors":   [{"name": "量能", "weight": 31}],
               "exitText": "止损 −8% · ..." },
  "metrics": { "verdict": "达标", "definition": "...", "test": { "signals": 292, "precision": 0.952, ... }, ... },
  "data":    { "source": "day", "trainEnd": 20191231, "bigOrderData": false, ... },
  "features": { "version": 2, "names": ["ret1", "ret3", ...] },   // 99 个
  "model": { "type": "gbdt-binary-logistic", "baseScore": -1.6,
             "trees": [ { "f": [...], "t": [...], "m": [...], "l": [...], "r": [...], "v": [...] } ] }
}
```

树节点数组含义:`f` 分裂特征下标(<0 为叶子)、`t` 阈值(x ≤ t 走左)、`m` 缺失值是否走左、`l`/`r` 子节点下标、`v` 节点输出值。
概率 = sigmoid(baseScore + Σ 每棵树叶子的 v)。

## 6. 运行在 APP 中的注意事项

- 每日收盘后(15:00 以后、数据更新完)运行一次;盘中数据不完整,不要用来判定"启动日"。
- 聚类板块是训练时最后一年的划分;离训练时间越久越不准,建议每季度重训。
- 信号是"次日开盘买入"的口径:若次日一字涨停买不进,该信号作废;若次日高开过多(例如 > 5%),实际成本偏离回测假设,可以在 APP 中加一条"高开超过 X% 不追"的提示。
- 模型会随市场风格衰减,建议每季度用最新数据重训,并对比新旧 `report.md`。
