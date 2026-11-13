# 数据库与事务

## 持久化链路

domain 定义 Repository，infrastructure 实现 VO/PO 转换与 DAO 调用；MyBatis XML 在 interfaces 的 resources/mybatis/mapper。[application.yml](../../../lottery-interfaces/src/main/resources/application.yml) 配置 Mapper 路径、PO 别名包与下划线转驼峰。DAO 的方法名对应 XML id，namespace 对应 DAO 全限定名；参数使用既有 PO 字段或 @Param。

新增字段须同步核对表结构、PO、resultMap/SELECT/INSERT、Repository 映射与用例结果。例如 take_id 从订单表映射到 PO，再由 [OrderRepository.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/OrderRepository.java) 的 toFulfillmentEvent 提供回放；只增加 Java 字段不会自动补齐查询。

## 实际物理布局

活动、奖品、活动奖品、额度、参与、订单和结算账本共用单一 MySQL 库。Spring Boot 装配一个 Hikari 数据源供 MyBatis 和事务管理使用。表结构见 [完整 Schema](../../../docs/sql/lottery-schema.sql)。

## 事务与幂等

[DrawParticipationRepository.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/DrawParticipationRepository.java) 的 takeParticipation 在 TX1 本地事务内扣剩余次数并插入 take；先普通读取确认次数行，只有不存在才 initializeCount，再使用 left_count>0 的条件 UPDATE，零影响行数与唯一冲突会回滚。普通读取仅判断存在性，不将读取的余量用于覆盖写回。无变化 upsert 在 MySQL 8.0.46/RR 可使不同已有用户竞争 PRIMARY supremum；真实复现与测试见[独立优化契约](./performance-optimization.md)。[OrderRepository.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/OrderRepository.java) 的 saveDrawOrderWithTakeLock 在 TX2 本地事务内用 state=0 条件消费 take，然后插入订单和初始 Outbox 状态。

[ActivityPrizeRepository.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/ActivityPrizeRepository.java) 的 settleAwardStock 在 TX3 本地事务中插入 UNIQUE(order_id) 的账本并条件扣减活动奖品库存；重复账本返回已处理，库存不足回滚本次账本插入。[ActivityPrize_Mapper.xml](../../../lottery-interfaces/src/main/resources/mybatis/mapper/ActivityPrize_Mapper.xml) 的扣减条件包含 award_surplus_count > 0，不能改成查询后无条件写回。

requestId 用于参与记录查询与唯一约束，takeId 关联抽奖结果，orderId 用于库存结算和发奖关联。Redis marker 不能替代数据库约束。DuplicateKeyException 只在已有契约的唯一冲突分支处理，不能把全部 SQL 异常解释成重复成功。

订单仓储经 StockReservationTransactions 观察实际事务结束状态，处理 SQL 异常时的 Redis 预扣回补；不能凭异常类型猜测提交是否成功。正常非成功业务码仍由原调用方回补，避免重复归还。触发条件、错误矩阵与集成断言见[错误处理](./error-handling.md)。

## SQL 迁移现状

[lottery-schema.sql](../../../docs/sql/lottery-schema.sql) 从空实例创建单库七表。旧库完成 V5、V6、V7 后按 [V8 迁移](../../../docs/activity-prize-pool.md) 旁路升级；账本通过订单获取活动归属，禁止拆分共享奖池。

参与表按字节比较 `request_id`，`UNIQUE(u_id,activity_id,request_id)` 保留请求身份。生成列 `UNIQUE(u_id,unfinished_activity_id)` 限制每用户活动最多一条 state=0 记录。订单和账本分别保留各自的 `order_id` 唯一键，订单非空 `take_id` 全库唯一。已完成发奖的时间写 `fulfilled_at`。

## 活动创建与启动持久化契约

创建聚合以 ActivityVO.activityId 绑定明细，初始余额等于配置库存。奖池明细以 UNIQUE(activity_id,award_id) 定位，并用外键关联活动和奖品。活动行锁串行化首次预热与启动；Redis SETNX 成功后才 CAS RUNNING。回归见 FinalBusinessE2eManualIT。

ActivityPrizeVO、ActivityPrize PO 与 ActivityPrizeBriefVO 的 awardCount 均为 Integer，与 MySQL INT 一致；不要再依赖 BeanUtils 进行 Integer→String 隐式转换。真实创建 Mapper 回归见 PerformanceSqlManualIT。

## JDBC 会话状态与驱动修复

1. **范围**：改动 Connector/J 版本、会话缓存或事务连接状态时阅读。实际 8.0.28 的 `useLocalSessionState=true` 在新 Hikari 连接上漏发关闭自动提交，Spring 回滚后行仍存在；只读计数通过不能替代事务门禁。
2. **签名**：根 Maven 使用 mysql:mysql-connector-java:8.0.29，这是官方 #106435 的首个修复版本。`JdbcLocalSessionStateManualIT` 通过 `-Dlottery.perf.mysql=jdbc:mysql://127.0.0.1:<专用端口>/` 运行，只建立自有 schema。
3. **契约**：业务 `application.yml` 和性能覆盖 `application-perf.yml` 均使用 `useLocalSessionState=true`，须同时核对实际池 URL，避免覆盖文件使实验失效。会话状态经 JDBC `setAutoCommit/setReadOnly/setTransactionIsolation` 管理，Hikari 归还时重置；禁止直接 SQL SET 绕过本地状态。启用缓存须至少使用已回归的驱动版本，保留 readOnlyPropagatesToServer、useLocalTransactionState 默认值及数据库持久化1/1。业务事务仍真实 commit/rollback。
4. **异常**：真实事务回归不通过时不得启用性能参数；SQL/驱动错误显式传播。诊断仅在专用实例且应用停止时短暂启用 General Log，保存原值并恢复，不将其开销混入压测。
5. **场景**：新池第一次直接进入 Spring 事务也必须发送 SET autocommit=0；归还未提交连接须回滚并恢复默认状态；缓存只读检查不增加服务器 SELECT，但 JDBC 只读设置仍反映到数据库。
6. **断言**：false/true 两模式同测试验证十次 isReadOnly 的服务器查询计数、同一物理连接的只读/自动提交/隔离级别重置，以及 Spring 真提交/真回滚。8.0.28 的失败与服务器线缆日志保留，8.0.29 原断言通过；版本和开关的性能对照分别测试。
7. **错误与正确**：错误是用 SELECT 预热或额外强制 SET 掩盖首次事务缓存错误。正确是定位驱动状态分支、升级最小官方修复版本、保持断言，并验证真实持久化行为后再做容量实验。

官方依据：[缺陷 #106435](https://bugs.mysql.com/bug.php?id=106435)、[8.0.29 修复说明](https://dev.mysql.com/doc/relnotes/connector-j/en/news-8-0-29.html)。实际复现与驱动测试见性能目录本轮记录。

## 取消活动总库存

V4__remove_activity_stock.sql 是旧全局库删除活动总库存的迁移。仍有这两列的旧库在 V5 之前执行 V4；完整新库 Schema 已包含最终结构。详情见 [业务简化记录](../../../docs/activity-stock-removal.md)。
