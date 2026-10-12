# 独立性能优化契约

## 1. 范围与触发
改动参与次数 SQL、库存条件索引、库存 Outbox 投递或对应诊断工具时阅读。2026-10-12～13 实测见 [optimization-report.md](../../../doc/performance/optimization-report.md)。

## 2. 方法与命令
`subtractionLeftCount(ActivityBillVO)` 保持原签名，在原参与事务内 queryUserTakeActivityCount→仅缺失时 initializeCount→条件扣减。`environment.py stock-index` 复用既有脚本的库存部分。`lock_probe.py <输出.json>` 创建独立 fixture 并复现两用户、两奖品锁范围。`stages.py <轮次目录...>` 汇总累计计时、锁观察与换页增量。

## 3. 数据与消息契约
次数存在性查询不代替 `left_count>0` 原子 UPDATE；首次初始化的唯一约束与参与记录回滚不变。库存使用 `(strategy_id,award_id)` 条件索引，账本与扣减同一事务。库存扫描排除 COMPLETE，投递跳过逐条预读，claim/回调 CAS/消费者/账本仍保护重投。awardCount 的创建 VO、PO、快照为 Integer，不修改旧 NULL。

## 4. 校验与异常
SQL 错误直接传播；首次并发创建保留原 upsert，不使用 INSERT IGNORE 吞错。索引已存在相同前导列时不重复增加。监控失败、任务异常、库存 BLOCKED 或审计不通过须保存证据并失败；资源不可比时保留指标，禁止认证整体提升比例。

JMeter 预处理器的普通异常不会自动停止后续 HTTP；用户耗尽须显式 stop 线程/引擎并输出 PERF_USER_EXHAUSTED，run.py 检测后非零退出。该规则由真实数据耗尽失败触发；一个用户的实际运行验证它，不重用旧请求变量。PerfTiming 的原生 acknowledge/nack 完整委托，按 topic/任务/生产者回调标注 SQL，区分同步位点提交与数据库事务。

## 5. 正常与失败案例
已有不同用户：普通读+条件扣减不会等待无关 upsert 的 PRIMARY supremum。首次同用户并发：只有一条未完成 take，冲突事务回滚次数。库存 A/B：索引使不同记录独立，实际同奖品库存竞争仍须数据库串行化。扫描后完成：可能重投，迟到 SENT/FAIL 不覆盖 COMPLETE。新建总库存 37：数据库总量/余量均为 37，旧 NULL 不回填。

## 6. 验证断言
PerformanceSqlManualIT 运行于明确指定 13329 的专属 schema，验证锁、首次/耗尽/并发/幂等、原子账本与字段创建。MqCallbackStateTest 证明扫描投递不预读且仍推进 SENT；MqStateCasManualIT 用真实 Mapper 验证迟到通知和重投。HTTP 正确性再核对两库八表、库存、take、order、ledger 和 Kafka 位点。Mock 结果不能替代真实链路。

## 7. 错误与正确写法
错误：每次已有次数行都做无变化 upsert；扫描后预读 COMPLETE 作为异步状态保护；漏部署已有索引后再写重复迁移；将 355 RPS 入口与大量积压称为稳定容量。

正确：存在性普通读后仍原子条件扣减；状态保护落在原子 CAS 与唯一账本；先核对脚本/Schema/EXPLAIN；同负载对照并报告换页及数据库体量边界，稳定候选按完整链路阈值独立验证。
