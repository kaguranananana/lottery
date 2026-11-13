# Redis、库存与缓存

## key 与实现位置

业务 key 统一复用 [Constants.java](../../../lottery-common/src/main/java/com/anontokyo/lottery/common/Constants.java) 的 RedisKey 工厂方法；技术操作经 [RedisUtil.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/utils/RedisUtil.java)、Repository 或 Guard 实现。业务层通过 domain Guard/Repository 接口使用 Redis，不再拼接另一套相同含义的 key。

## 库存扣减与预热

活动总库存及对应 Key、预扣、回补和响应码已移除。[ActivityPrizeRepository.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/ActivityPrizeRepository.java) 只预扣活动奖品库存。奖品 Key 缺失或 Redis 异常抛 IllegalStateException 并保留 cause，不能转换为未中奖并消费 take；只有真实售罄才返回 soldOut。最终奖品库存通过唯一账本在 MySQL 结算。

[RedisUtil.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/utils/RedisUtil.java) 的 `long deductStock(String key)` 在一个 Lua 脚本中读取、检查存在性及余量，再 DECR：非负结果是剩余量，-2 为售罄，缺失或命令错误抛出。`void recoverStock(String key)` 仅对现存 Key 原子 INCR，缺失时不创建 Key 并抛出；仓储回补日志后继续传播异常。不能恢复为 GET→DECR→负数 INCR 的分步实现，也不能在热路径按 MySQL 剩余值回填，异步结算滞后的值会恢复已预扣库存。真实 Redis 覆盖见 RedisStockFailureManualIT。

warmupAwardStockForActivity 仅允许 READY 活动，与启动共用活动行锁。READY→RUNNING 锁内检查 begin <= now < end，只以本活动 MySQL 奖品余额 SETNX。RUNNING/CLOSED 禁止预热，缺键必须对账；一个活动的库存缺键不影响其他奖池。部分预热成功可在 READY 重试，保留现存余额。见 [V8 契约](../../../docs/activity-prize-pool.md)。

## 并发锁

[RedisActivityDrawProcessGuard.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/lock/RedisActivityDrawProcessGuard.java) 使用活动与用户维度的 SETNX+TTL，锁值为调用方 UUID token，默认 3 秒。[RedisUtil.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/utils/RedisUtil.java) 的 deleteIfValueEquals 使用 Lua 比较 token 后删除；释放必须携带原 token，不能直接 DEL。TTL 控制并发窗口，不承担持久幂等。

获取抽奖锁异常须中断业务；当前释放失败记录 warn。该锁的 key 冲突、token 与 Lua 释放由 [RedisActivityDrawProcessGuardTest.java](../../../lottery-interfaces/src/test/java/com/anontokyo/lottery/test/infrastructure/lock/RedisActivityDrawProcessGuardTest.java) 使用 Mockito 验证。不要由该单测推导所有 MQ processing 锁也具有 token 所有权语义。

## 缓存范围与配置

[TwoLevelCacheService.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/cache/TwoLevelCacheService.java) 只给 ActivityPrizeSnapshot 使用 Caffeine L1 与 Redis L2；活动配置是 Redis 单级。空查询使用短时 __NULL__ 标记。快照 loader 在本实例 synchronized 区域内加载；这不是分布式回源锁。

[application.yml](../../../lottery-interfaces/src/main/resources/application.yml) 的实际配置：快照 L1 60 秒、最大 10,000 条，L2 600 秒加 0..120 秒抖动，空值 60 秒；活动 300 秒加 0..60 秒抖动，空值 30 秒；未消费 take 1800 秒。配置以运行时绑定值为准，不照抄 [CacheConfigProperties.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/cache/CacheConfigProperties.java) 中未生效的默认值作为环境事实。

配置写入的缓存失效在事务 afterCommit 执行；事务回滚不失效，没有外层事务则即时失效。不能在提交前删除缓存，否则其它请求可能回源旧的已提交行并缓存到 TTL 到期。CacheTransactionInvalidationTest 验证活动、快照的提交顺序与回滚行为。

失效只删除当前实例 L1 和共享 Redis，不广播给其它实例，因此仍使用短 TTL 最终一致，也不消除已经在途的旧版本加载。现有缓存读取异常与回写异常处理不同：Redis 读/JSON 解析会抛出，回写或失效失败记录日志后继续。不能声称所有 Redis 缓存失败均自动回源；库存 key 也不能套用配置缓存的 miss 策略。

## 未完成参与记录

未消费 take 不再缓存读写，归属与是否允许新建一律查询 MySQL。旧 Redis key/TTL 配置可自然留存，缓存过期、缺字段或陈旧数据不能改变请求归属。奖品库存和抽奖短锁仍使用 Redis，参与事务只扣个人次数。
