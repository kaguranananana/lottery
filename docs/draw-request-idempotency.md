# 抽奖请求幂等与未完成请求恢复

## 接口契约

`POST /api/v1/draw` 请求结构保持不变：

```json
{"userId":"u1","activityId":100001,"requestId":"A"}
```

`requestId` 是客户端一次逻辑抽奖操作的标识，最长 64 字符，区分大小写及尾部空格。服务端按 `userId + activityId + requestId` 查询并建立唯一约束；客户端应为每轮生成独立 ID（例如 UUID），不要人为复用旧 ID。

- 新一轮抽奖：生成并持久保存新的 `requestId`，再发送请求。
- 网络超时、请求异常、服务端错误、结果未知：继续使用原 `requestId`，不要换号。
- `0000`（中奖，下单完成）或 `D001`（未中奖）是本轮确定的最终抽奖结果，本轮结束。中奖结果不表示异步发奖已经完成。
- `D006`：当前用户活动仍有请求在处理，稍后用原 ID 再查。
- `D007`：存在另一条未完成请求，本次请求没有扣次数、没有取得原 take 的消费资格。保存 `pendingRequestId`，用它重新调用同一接口恢复原操作。
- `D008`：记录归属或状态异常。保留原请求信息，联系服务端核查；更换 ID 不能解决该状态。
- 其他非最终结果不能据此判断已经完成抽奖；已创建的参与记录继续保留，用原 ID 恢复或回放。

B 遇到未完成 A 时响应示例：

```json
{
  "code":"D007",
  "info":"存在未完成抽奖，请恢复原请求",
  "requestId":"B",
  "pendingRequestId":"A",
  "takeId":null,
  "orderId":null,
  "win":false,
  "award":null
}
```

`requestId` 始终回显本次调用的 ID；`pendingRequestId` 单独指向需要恢复的原操作。`win=false` 配合 `D007` 不能解释成抽奖未中。

## 客户端丢失原请求号

在原来的用户、活动维度提交一个新 ID。如果数据库里存在未完成的 A，服务端返回 `D007 + pendingRequestId=A`；客户端再用 A 调用抽奖接口。即使 A 在两次调用间完成，提交 A 也会回放结果，不重复扣次数。

这是一种通过现有抽奖接口恢复的机制，**不是只读探测接口**：如果没有未完成记录，新 ID 会正常开始下一轮。客户端只想查询历史结果时，仍须保存原 ID；当前没有按用户查询最近已完成结果的接口。正在执行且短锁未释放时可能先得到 D006，等待后继续同一个新 ID 可取得 D007 或正常进入新一轮。

对于历史 `request_id IS NULL` 的未完成记录，服务端不能凭空推断原请求号，返回 D008，保留 take 和已扣次数。运维须依据真实请求日志核实归属后回填该记录的请求号，再让客户端恢复；没有可靠证据时须人工决定恢复/结算该旧记录，禁止删除记录、直接标记已完成或把它自动交给 B。

## 服务端处理流程

1. 校验请求号并取得现有用户活动短锁。锁冲突返回 D006，锁获取失败中断。
2. 从数据库按原请求号查询 take。查询异常记录请求关联日志并抛出，HTTP 沿用错误响应，不进入参与创建。
3. A 已消费：从正确用户分片查询原 take 的订单，有订单回放中奖结果及原订单号，无订单回放未中奖。
4. A 未消费：直接使用原 take 继续抽奖，不再扣参与次数，也不再创建参与记录。
5. 未查到该请求：领域服务从数据库查询同用户活动未完成记录。只有归属相同才能复用；归属不同返回 D007，归属缺失返回 D008。Redis 未消费记录缓存不再读写，过期、缺字段、陈旧缓存都不影响该判断；旧 key 可以自然过期。
6. 确认没有未完成记录后，沿用活动检查与 Redis 活动库存预扣，在同一个用户分片事务中初始化次数行、条件扣减次数、插入 take。
7. 唯一冲突回滚本事务次数扣减，按既有逻辑回补该线程预扣的活动库存，再查询记录归属。不同请求受阻；相同请求继续原 take，若已经消费则回放。
8. 中奖沿用同事务消费 take 与创建订单，未中奖沿用 CAS 消费 take。锁过期导致消费竞争时，零更新者回读已提交结果，不能直接把零更新解释为未中奖。下单零更新者按原逻辑回补自己的奖品预扣后回放。
9. A 完成后，生成列释放“未完成”唯一位置，B 可以扣下一次参与次数、创建新的 take。A 的请求唯一位置永久保留，以后提交 A 仍回放 A。

唯一约束分别负责：

- `uk_user_activity_request(u_id, activity_id, request_id)`：同一逻辑请求最多一条参与记录，完成后仍保留。
- `uk_user_unfinished_activity(u_id, unfinished_activity_id)`：`state=0` 时生成活动号；完成时生成 NULL。每用户活动最多一条未完成记录，完成后的历史多轮不互相冲突。
- 现有次数表 `idx_uId_activityId(u_id, activity_id)`：初始化或锁定同一个次数行；条件 UPDATE 保证次数不为负。
- 现有参与表 `idx_take_id(take_id)` 与订单消费 CAS：保留原参与身份和同事务单订单语义。

## 部署与迁移

必须先完成数据库迁移，再发布应用；仅发布 Java 代码不能保证锁过期时不同请求的并发隔离。停止抽奖写入并备份，使用有 ALTER/CREATE ROUTINE 权限的账号执行：

```bash
mysql --host=127.0.0.1 --port=13306 --user=root --password < docs/sql/V3__draw_request_ownership.sql
```

不要使用 `--force` 忽略 SQL 失败。脚本对应真实布局：两库各一张 `user_take_activity`，两库各四张 `user_strategy_export_000..003`。它支持基线缺失 request_id/take_id 以及此前已手动执行 V2 字段/索引的环境。请求号用 VARBINARY(256) 保存 64 个 UTF-8 字符，按字节比较，与 Java equals 保持一致。

脚本会拒绝同用户活动有多条未完成记录、未知状态/空身份，以及请求唯一键的历史冲突。先根据真实业务证据处理冲突再重新执行，不自动选最新记录丢弃其他记录。旧记录无 requestId 时保留 NULL，运行期按 D008 处理。

迁移 DDL 会自动提交：第二库失败时第一库可能已升级，须保持停止写入，核查错误、处理历史问题后重新执行，确认两库全部完成才发布。可重复执行本脚本；回滚旧应用会恢复原归属缺陷，须保持停止抽奖写入直到修正版重新发布；数据库保留参与历史及约束，不删除已创建记录。滚动发布期间不可混用新旧版本处理抽奖。

发布前在 **lottery_01 与 lottery_02 都检查**：

```sql
SHOW CREATE TABLE user_take_activity;
SHOW CREATE TABLE user_take_activity_count;
SHOW INDEX FROM user_take_activity;
SHOW INDEX FROM user_take_activity_count;
-- 四张订单表均执行，核对 take_id 与 idx_u_id_take_id。
SHOW CREATE TABLE user_strategy_export_000;
```

核对两个参与唯一键确实为 UNIQUE、列顺序正确、生成列表达式正确，次数表仍具有用户活动唯一键。订单 Outbox 所需字段仍依赖原异步结算迁移，本次不修改结算或发奖。

## 自动验证与边界

```bash
mvn clean test

# 仅在专用空 MySQL 实例执行，会建立 lottery/lottery_01/lottery_02。
# 示例实例 root 密码为空，URL 末尾为 /，不要指向业务实例。
mvn test -Dtest=DrawRequestOwnershipManualIT \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dlottery.it.mysql=jdbc:mysql://127.0.0.1:23306/
```

默认门禁包括领域归属、编排幂等、HTTP 校验/序列化及既有测试。数据库用例直接执行交付迁移、生产 MyBatis XML、路由切面、生产 Repository 与 Spring 事务；同时验证两库八分表回放、并发唯一冲突次数回滚、次数耗尽、异常后恢复、真实 SQL 错误。测试 Future 传播线程错误，不吞异常。

2026-10-12 实际验证：`mvn clean test` 运行 26 个默认测试，失败/错误/跳过均为 0；专用 MySQL 9.6.0 集成用例运行 9 个测试，失败/错误/跳过均为 0。SQL 客户端重复执行迁移成功，information_schema 确认两库的两个参与唯一键及次数唯一键均为 NON_UNIQUE=0。详细命令亦记录于任务验证记录。数据库用例的 Redis 活动库存、抽样和短锁使用 Mock，所有线程允许进入以模拟锁失效；没有启动真实 Redis、Kafka、XXL-Job 或完整 HTTP 服务。这证明测试环境的数据库约束与事务行为，不证明生产环境已经迁移，也不证明断电、连接在 COMMIT 响应阶段中断等故障注入结果。数据库崩溃恢复、生产 MySQL 版本、真实多实例锁过期仍需部署环境验证。

本修复不改变抽奖算法、Kafka 异步结算及发奖逻辑。未完成参与在恢复时继续执行现有抽样；只有已提交的最终消费/订单结果会被回放。现有跨 Redis/MySQL 的库存一致性与提交结果未知时的库存补偿边界仍保留，不能据本次测试宣称跨系统副作用恰好一次。

## 本次修改文件

| 文件 | 原因 |
| --- | --- |
| ActivityProcessImpl.java | 幂等查询失败中断；归属/状态核对；参与冲突或消费 CAS 竞争后回放已提交结果 |
| BaseActivityPartake.java | 数据库未完成记录检查；同请求复用，其他请求受阻，无归属显式错误 |
| ActivityPartakeImpl.java、IUserTakeActivityRepository.java | 移除不再使用的未消费缓存查询接口，使用现有数据库查询 |
| UserTakeActivityVO.java、UserTakeActivityRepository.java | 贯通 requestId 映射；移除未消费缓存读写；参与事务初始化与条件扣次数 |
| UserTakeActivity_Mapper.xml | 两个参与查询使用已包含 request_id 的显式 resultMap |
| IUserTakeActivityCountDao.java、UserTakeActivityCount_Mapper.xml | 分开次数初始化与有剩余次数的 UPDATE，避免匹配行数导致耗尽后继续参与 |
| IUserStrategyExportDao.java | 为 take 订单回放补齐用户路由注解 |
| Constants.java | 新增 D007/D008，明确 D006 用原请求号重试 |
| PartakeResult.java、DrawProcessResult.java、DrawResponse.java | 贯通 pendingRequestId 恢复字段 |
| LotteryController.java | 映射 pendingRequestId，维持 requestId 回显与既有错误响应 |
| V3__draw_request_ownership.sql | 实际两库参与约束及八张订单表 take 关联迁移 |
| DrawRequestIdempotencyTest.java | SQL 查询失败、A/B 阻塞字段及未中奖 CAS 竞争回放中奖结果的回归测试 |
| PartakeRequestOwnershipTest.java | 领域同/不同请求、归属缺失、查询失败的规则测试 |
| DrawHttpOwnershipTest.java | HTTP 校验、恢复字段序列化和异常时请求号回显 |
| ActivityDomainManualIT.java、ActivityProcessManualIT.java | 手工调用示例补齐原请求号契约，保留同操作重试语义；本次未运行这些全环境用例 |
| DrawRequestOwnershipManualIT.java | 真实 MySQL、生产 Mapper/Repository/路由/事务的九个自动化集成用例 |
| README.md、architecture.md、interview-architecture.md、本说明 | 接口契约、恢复规则、迁移入口及同步架构描述 |
| Trellis 相关规范与任务记录 | 同步事实来源、错误透明、数据库约束与验证证据 |
