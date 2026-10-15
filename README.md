# realU · A 股主升初期识别

用通达信离线日 K 线训练"主升初期"识别模型,在 Windows 上训练与历史检验,导出 JSON 模型供 APP 导入使用。全部代码为 Kotlin。

- `core/`:纯 Kotlin 公共模块(无第三方依赖),**训练器和 Android APP 共用**:通达信数据解析、技术指标、99 个特征(含市场情绪、行业/概念板块强度、筹码分布)、候选条件、目标定义、交易模拟、GBDT 推理、模型文件读写、信号引擎。
- `trainer/`:Windows 命令行训练器:样本构建、GBDT 训练、阈值与卖出参数选择、流式回测、报告。
- `scripts/`:Windows 启动脚本与配置模板。
- `docs/ALGORITHM.md`:算法设计(准确率口径、特征说明、内存与乱码处理)。
- `docs/APP_INTEGRATION.md`:APP 导入模型、与原型页面的对应关系、模型 JSON 结构。

## 在 Windows 上使用

1. 安装 Java 17 或更高版本(如 [Adoptium Temurin](https://adoptium.net/)),确认 `java -version` 可用。
2. 构建分发包(或直接使用别人构建好的 zip):
   ```bat
   gradlew.bat :trainer:winDist
   ```
   得到 `trainer\build\distributions\realu-trainer-win.zip`,解压到任意目录(路径可含中文)。
3. 在通达信中下载完整的盘后日线数据("系统 → 盘后数据下载 → 日线和实时行情数据",起始日期尽量早)。
   行业/概念板块:通达信目录下的 `T0002\hq_cache` 会被自动找到(就在 `vipdoc` 旁边);只有 vipdoc 时自动改用"收益相关性聚类"板块。
4. 复制 `config.example.properties` 为 `config.properties`,至少修改:
   - `tdx.vipdoc=` 通达信安装目录下的 `vipdoc`(如 `C:\new_tdx\vipdoc`)
   - `split.trainEnd` / `split.validEnd`:训练、验证、测试三段时间的分界
5. 双击 `check.bat` 做数据体检,再双击 `train.bat` 开始训练。
6. 结果在 `out\train-时间戳\`:
   | 文件 | 内容 |
   |---|---|
   | `model-*.json` | 导入 APP 的模型(未达标时文件名带 `-NOT-PASSED`) |
   | `report.md` | 检验报告:准确率、收益、按年份/类型、组合回撤 |
   | `signals_valid.csv` / `signals_test.csv` | 每个信号的买卖明细(Excel 直接打开不乱码) |
   | `feature_importance.csv` | 特征重要性 |
   | `train.log` / `data_warnings.txt` | 日志与数据问题 |

其他命令(`realu.bat <命令>`):

```bat
realu.bat backtest out\train-xxx\model-xxx.json 20230101 20241231   :: 用指定区间回测已有模型
realu.bat predict  out\train-xxx\model-xxx.json 20241008            :: 列出某日信号(不写日期=最新一天)
realu.bat demo demo-data                                             :: 生成模拟数据(含 hq_cache),验证环境(结果无投资意义)
```

内存:默认使用物理内存的 65%,可在运行前 `set REALU_HEAP=-Xmx8g` 指定。全市场 2010 年至今约需 3–6GB。

退出码:0 达标;10 已导出但未达标;2 配置/数据错误;3 内存不足;1 其他异常。

## 关于 95% 准确率

目标定义:**信号次日开盘买入,之后允许回撤(收盘不跌破 −15%),60 个交易日内某日收盘扣成本净赚 ≥ 30%**,全部按收盘价判断。准确率 = 已完成周期中达到这一目标的比例,同一只股票 20 个交易日内只计一次。阈值、卖出规则只在验证期选择,测试期只用来检验;测试期准确率 ≥ 95% 且信号数足够,才判定"达标"。

这是很高的要求,真实数据上未必达得到,程序会如实报告实际数字和置信下限,而不会为了凑数在测试期上调参。详见 `docs/ALGORITHM.md` 第 1 节。

> 本项目仅用于研究学习。历史回测不代表未来收益,据此交易风险自负。

## 开发

```bash
./gradlew test               # 单元测试(含"训练预测 = 导出模型预测"一致性校验、未来函数校验)
./gradlew :trainer:fatJar    # 生成 trainer/build/libs/realu-trainer.jar
```
