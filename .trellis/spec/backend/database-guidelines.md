# 数据库、事务与分片

## 持久化链路

domain 定义 Repository，infrastructure 实现 VO/PO 转换与 DAO 调用；MyBatis XML 在 interfaces 的 resources/mybatis/mapper。[application.yml](../../../lottery-interfaces/src/main/resources/application.yml) 配置 Mapper 路径、PO 别名包与下划线转驼峰。DAO 的方法名对应 XML id，namespace 对应 DAO 全限定名；参数使用既有 PO 字段或 @Param。

新增字段须同步核对表结构、PO、resultMap/SELECT/INSERT、Repository 映射与用例结果。例如 take_id 从订单表映射到 PO，再由 [OrderRepository.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/OrderRepository.java) 的 toInvoice 提供回放；只增加 Java 字段不会自动补齐查询。

## 实际物理布局

| 数据 | 位置 | 依据 |
| --- | --- | --- |
| activity、strategy、strategy_detail、award、award_stock_settlement | 全局库 lottery | [StrategyRepository.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/StrategyRepository.java)、[lottery.sql](../../../doc/sql/lottery.sql)、[V2__architecture_simplification.sql](../../../docs/sql/V2__architecture_simplification.sql) |
| user_take_activity、user_take_activity_count | lottery_01/02，每库一张同名表 | [IUserTakeActivityDao.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/dao/IUserTakeActivityDao.java) 的 splitTable=false、[IUserTakeActivityCountDao.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/dao/IUserTakeActivityCountDao.java)、[lottery_01.sql](../../../doc/sql/lottery_01.sql) |
| user_strategy_export_000..003 | lottery_01/02，每库四张订单表 | [IUserStrategyExportDao.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/dao/IUserStrategyExportDao.java) 的 splitTable=true、[lottery_01.sql](../../../doc/sql/lottery_01.sql) |

路由键为 uId。配置为 dbCount=2、tbCount=4；涉及路由分布或分表 SQL 的改动先核对实际路由组件与接口，不把普通单库查询推导成支持跨分片聚合。已有 [architecture.md](../../../docs/architecture.md) 记录其 SQL 表名替换仅处理首个匹配，不支持任意 JOIN/子查询的多表路由；新查询保持当前单表访问边界，复杂需求另立任务。

## 事务与幂等

[UserTakeActivityRepository.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/UserTakeActivityRepository.java) 的 takeParticipation 在用户路由事务内扣剩余次数并插入 take；先普通读取确认次数行，只有不存在才 initializeCount，再使用 left_count>0 的条件 UPDATE，零影响行数与唯一冲突会回滚。普通读取仅判断存在性，不将读取的余量用于覆盖写回。无变化 upsert 在 MySQL 8.0.46/RR 可使不同已有用户竞争 PRIMARY supremum；真实复现与测试见[独立优化契约](./performance-optimization.md)。[OrderRepository.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/OrderRepository.java) 的 saveDrawOrderWithTakeLock 在同一用户路由事务内用 state=0 条件消费 take，然后插入订单和初始 Outbox 状态。手动路由均须在 finally 清理，避免线程复用时串库。

[StrategyRepository.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/StrategyRepository.java) 的 settleAwardStock 在全局库本地事务中插入 UNIQUE(order_id) 的账本并条件扣减策略库存；重复账本返回已处理，库存不足回滚本次账本插入。[StrategyDetail_Mapper.xml](../../../lottery-interfaces/src/main/resources/mybatis/mapper/StrategyDetail_Mapper.xml) 的扣减条件包含 award_surplus_count > 0，不能改成查询后无条件写回。

requestId 用于参与记录查询与唯一约束，takeId 关联抽奖结果，orderId 用于库存结算和发奖关联。Redis marker 不能替代数据库约束。DuplicateKeyException 只在已有契约的唯一冲突分支处理，不能把全部 SQL 异常解释成重复成功。

## SQL 迁移现状

基线在 doc/sql；[20260315_async_award_stock_outbox.sql](../../../doc/sql/20260315_async_award_stock_outbox.sql) 为订单表增加库存消息状态与索引。[V2__architecture_simplification.sql](../../../docs/sql/V2__architecture_simplification.sql) 实际执行的是全局库账本建表，request_id、take_id 的 ALTER 只是注释示例，不能认为执行文件即部署了全部新字段。

该 V2 文件的参与表分表注释与当前 DAO/基线不一致：当前应核对两库实际 user_take_activity，而不是凭注释寻找八张参与分表。现有基线也不能证明目标环境已具有 request_id 唯一约束。部署和迁移任务应读取目标 schema、制定明确迁移，再验证字段与唯一语义；本次初始化不执行或改写 SQL。

订单查询与更新携带 uId，分片扫描先明确设置库/表位置再执行 LIMIT 查询。任何新增 DAO 方法都须说明由注解还是仓储手动路由提供上下文；例如 queryUserStrategyExportByTakeId 现已标 @DBRouter(key="uId")；DrawRequestOwnershipManualIT 使用真实两库八分表验证回放路由。

## 抽奖归属约束

[V3__draw_request_ownership.sql](../../../docs/sql/V3__draw_request_ownership.sql) 是可执行的两库参与表及八张订单表迁移。request_id 按字节比较，参与表 UNIQUE(u_id,activity_id,request_id) 保留请求身份；生成列 UNIQUE(u_id,unfinished_activity_id) 限制每用户活动最多一条 state=0 记录。迁移遇到历史冲突显式失败，不能自动丢弃机会。数据库用例不证明目标生产环境已经迁移。

## 活动创建与启动持久化契约

创建聚合由 StrategyVO.strategyId 绑定 ActivityVO.strategyId 及明细所属策略；新活动 stock_surplus_count 从 stock_count 初始化，新策略明细剩余库存从 award_count 初始化。Activity_Mapper INSERT 显式携带 strategy_id 与 stock_surplus_count。scanToDoActivityList 使用自增 id 的十条游标分页，SELECT 必须返回 id。启动使用全局 TransactionTemplate + queryActivityForUpdate 的 FOR UPDATE，锁内预热后 CAS DOING；不要只在 worker 改调用顺序而允许其它入口绕过。回归见 FinalBusinessE2eManualIT。

StrategyDetailVO、StrategyDetail PO 与 StrategyDetailBriefVO 的 awardCount 均为 Integer，与 MySQL INT 一致；不要再依赖 BeanUtils 进行 Integer→String 隐式转换。旧压测活动的 NULL 总库存保留为历史事实，不能用剩余库存反推回填。真实创建 Mapper 回归见 PerformanceSqlManualIT。

## JDBC 会话状态与驱动修复

1. **范围**：改动 Connector/J 版本、会话缓存或事务连接状态时阅读。实际 8.0.28 的 `useLocalSessionState=true` 在新 Hikari 连接上漏发关闭自动提交，Spring 回滚后行仍存在；只读计数通过不能替代事务门禁。
2. **签名**：根 Maven 使用 mysql:mysql-connector-java:8.0.29，这是官方 #106435 的首个修复版本。`JdbcLocalSessionStateManualIT` 通过 `-Dlottery.perf.mysql=jdbc:mysql://127.0.0.1:13329/` 运行，只建立自有 schema。
3. **契约**：会话状态经 JDBC `setAutoCommit/setReadOnly/setTransactionIsolation` 管理，Hikari 归还时重置；禁止直接 SQL SET 绕过本地状态。启用缓存须至少使用已回归的驱动版本，保留 readOnlyPropagatesToServer、useLocalTransactionState 默认值及数据库持久化1/1。业务事务仍真实 commit/rollback。
4. **异常**：真实事务回归不通过时不得启用性能参数；SQL/驱动错误显式传播。诊断仅在专用实例且应用停止时短暂启用 General Log，保存原值并恢复，不将其开销混入压测。
5. **场景**：新池第一次直接进入 Spring 事务也必须发送 SET autocommit=0；归还未提交连接须回滚并恢复默认状态；缓存只读检查不增加服务器 SELECT，但 JDBC 只读设置仍反映到数据库。
6. **断言**：false/true 两模式同测试验证十次 isReadOnly 的服务器查询计数、同一物理连接的只读/自动提交/隔离级别重置，以及 Spring 真提交/真回滚。8.0.28 的失败与服务器线缆日志保留，8.0.29 原断言通过；版本和开关的性能对照分别测试。
7. **错误与正确**：错误是用 SELECT 预热或额外强制 SET 掩盖首次事务缓存错误。正确是定位驱动状态分支、升级最小官方修复版本、保持断言，并验证真实持久化行为后再做容量实验。

官方依据：[缺陷 #106435](https://bugs.mysql.com/bug.php?id=106435)、[8.0.29 修复说明](https://dev.mysql.com/doc/relnotes/connector-j/en/news-8-0-29.html)。实际复现与驱动测试见性能目录本轮记录。
