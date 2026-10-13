# 独立性能优化契约

## 1. 范围与触发
改动参与次数 SQL、库存条件索引、库存 Outbox 投递、发奖补偿索引或对应诊断工具时阅读。2026-10-12～13 实测见 [optimization-report.md](../../../doc/performance/optimization-report.md)。

## 2. 方法与命令
`subtractionLeftCount(ActivityBillVO)` 保持原签名，在原参与事务内 queryUserTakeActivityCount→仅缺失时 initializeCount→条件扣减。`environment.py stock-index` 复用既有脚本的库存部分。`lock_probe.py <输出.json>` 创建独立 fixture 并复现两用户、两奖品锁范围。`stages.py <轮次目录...>` 汇总累计计时、锁观察与换页增量。

`20261013_invoice_compensation_index.sql` 在两库八表增加单列非唯一 `idx_invoice_grant_state(grant_state)`。`invoice_scan_compare.py <新目录> [--repeats 6]` 提取生产补偿 Mapper，在同一份八表数据上交替使用正常计划与仅忽略本次索引的诊断计划。执行前停止专用应用、排空义务，部署并核实索引；比较工具只读，不执行 DDL。初始化新性能环境会执行这份迁移；已有实例须显式迁移并核实 Schema。

## 3. 数据与消息契约
次数存在性查询不代替 `left_count>0` 原子 UPDATE；首次初始化的唯一约束与参与记录回滚不变。库存使用 `(strategy_id,award_id)` 条件索引，账本与扣减同一事务。库存扫描排除 COMPLETE，投递跳过逐条预读，claim/回调 CAS/消费者/账本仍保护重投。awardCount 的创建 VO、PO、快照为 Integer，不修改旧 NULL。

发奖补偿保持 `stock_mq_state=1 AND grant_state!=1`、FAIL/超时 INIT、旧 COMPLETE/超时 SENT 的原谓词与投影，保留 NULL 的 SQL 三值逻辑；只改变访问路径。单列键避免 stock 状态多次推进时维护额外组合列，但仍增加插入/发奖状态更新和空间成本，须同负载验证。单次空扫描读零行只代表无候选时；有积压时读取未发奖义务，不保证任意积压规模下恒定成本。

## 4. 校验与异常
SQL 错误直接传播；首次并发创建保留原 upsert，不使用 INSERT IGNORE 吞错。索引已存在相同前导列时不重复增加。监控失败、任务异常、库存 BLOCKED 或审计不通过须保存证据并失败；资源不可比时保留指标，禁止认证整体提升比例。

发奖索引迁移重复执行或目标 Schema 不符显式失败，不删除既有索引或业务数据。对照目录已存在、八表索引定义不符、实际计划无执行行数、行数/状态分布在实验中变化时直接报错并保留已完成文件。反向实验使用 MySQL 索引不可见性时，写入仍维护该索引，必须标明它不是完全移除索引的旧版本；实验后恢复可见并核实八表。

JMeter 预处理器的普通异常不会自动停止后续 HTTP；用户耗尽须显式 stop 线程/引擎并输出 PERF_USER_EXHAUSTED，run.py 检测后非零退出。该规则由真实数据耗尽失败触发；一个用户的实际运行验证它，不重用旧请求变量。PerfTiming 的原生 acknowledge/nack 完整委托，按 topic/任务/生产者回调标注 SQL，区分同步位点提交与数据库事务。

## 5. 正常与失败案例
已有不同用户：普通读+条件扣减不会等待无关 upsert 的 PRIMARY supremum。首次同用户并发：只有一条未完成 take，冲突事务回滚次数。库存 A/B：索引使不同记录独立，实际同奖品库存竞争仍须数据库串行化。扫描后完成：可能重投，迟到 SENT/FAIL 不覆盖 COMPLETE。新建总库存 37：数据库总量/余量均为 37，旧 NULL 不回填。

补偿空扫描：原 stock 状态索引命中所有已结算历史，LIMIT 不限制零候选时底层读取量；grant 前导键排除已发奖历史。存在 FAIL、超时 INIT/SENT 或旧 COMPLETE 未发奖时仍返回原候选集合，真实 grant_state=2 也在未完成范围内，不能把 `!=1` 改成 `=0`；库存未结算、BLOCKED、已发奖及 NULL 原排除语义不变。

## 6. 验证断言
PerformanceSqlManualIT 运行于明确指定 13329 的专属 schema，验证锁、首次/耗尽/并发/幂等、原子账本与字段创建。MqCallbackStateTest 证明扫描投递不预读且仍推进 SENT；MqStateCasManualIT 用真实 Mapper 验证迟到通知和重投。HTTP 正确性再核对两库八表、库存、take、order、ledger 和 Kafka 位点。Mock 结果不能替代真实链路。

InvoiceCompensationIndexManualIT 在同一专用端口自建 schema，使用生产 Mapper/BoundSql 和迁移定义，验证五千已完成订单空扫描原读取≥5000、新索引读取0；混合补偿候选集合不变、底层读取≤10，表行数不变。断言实际读取量与访问键，不写不稳定的毫秒门槛。真实八表对照保留全部执行树、交替顺序、初始计数/状态及原始换页端点；阶段/SQL digest、同负载全链路审计与反向读路径实验分别报告。

## 7. 错误与正确写法
错误：每次已有次数行都做无变化 upsert；扫描后预读 COMPLETE 作为异步状态保护；漏部署已有索引后再写重复迁移；将 355 RPS 入口与大量积压称为稳定容量。

正确：存在性普通读后仍原子条件扣减；状态保护落在原子 CAS 与唯一账本；先核对脚本/Schema/EXPLAIN；同负载对照并报告换页及数据库体量边界，稳定候选按完整链路阈值独立验证。

错误：把补偿 SQL 的局部毫秒变化写成整个服务的吞吐提升倍数，或把一次换页突发等同持续资源恶化。正确：区分突发与连续区间，必要时复核；同数据计划证明读取机制，真实同负载与反向实验验证实际阶段成本，容量另按原标准验收。
