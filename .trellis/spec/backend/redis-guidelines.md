# Redis、库存与缓存

## key 与实现位置

业务 key 统一复用 [Constants.java](../../../lottery-common/src/main/java/com/anontokyo/lottery/common/Constants.java) 的 RedisKey 工厂方法；技术操作经 [RedisUtil.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/utils/RedisUtil.java)、Repository 或 Guard 实现。业务层通过 domain Guard/Repository 接口使用 Redis，不再拼接另一套相同含义的 key。

## 库存扣减与预热

[ActivityRepository.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/ActivityRepository.java) 对运行期活动库存 key 缺失返回 STOCK_STATE_ERROR 并记录 error；库存为零返回 OUT_OF_STOCK。[StrategyRepository.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/StrategyRepository.java) 对奖品库存 key 缺失记录 error 并按售罄处理，读取异常抛 IllegalStateException。两者都是 Redis 预扣，最终策略奖品库存结算在数据库。

当前操作读取 key 后执行原子 DECR，减成负数则 INCR 回补；不是把整个读、扣、回补过程包成一个 Lua 脚本。新改动不得将缺 key 改为热路径按 MySQL 剩余值回填，异步结算滞后的值会恢复已预扣库存。

warmupStockForActivity 由活动 CAS 启动成功后调用，用 SETNX 初始化活动与奖品库存，已存在的 key 不覆盖。运行期丢失 key 后恢复仍需要对账决策；SETNX 本身不能证明 MySQL 数值已反映全部在途订单。[BaseActivityPartake.java](../../../lottery-domain/src/main/java/com/anontokyo/lottery/domain/activity/service/partake/BaseActivityPartake.java) 的旧「首次 miss 回填」注释与当前仓储不一致，以实际方法为准。

## 并发锁

[RedisActivityDrawProcessGuard.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/lock/RedisActivityDrawProcessGuard.java) 使用活动与用户维度的 SETNX+TTL，锁值为调用方 UUID token，默认 3 秒。[RedisUtil.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/utils/RedisUtil.java) 的 deleteIfValueEquals 使用 Lua 比较 token 后删除；释放必须携带原 token，不能直接 DEL。TTL 控制并发窗口，不承担持久幂等。

获取抽奖锁异常须中断业务；当前释放失败记录 warn。该锁的 key 冲突、token 与 Lua 释放由 [RedisActivityDrawProcessGuardTest.java](../../../lottery-interfaces/src/test/java/com/anontokyo/lottery/test/infrastructure/lock/RedisActivityDrawProcessGuardTest.java) 使用 Mockito 验证。不要由该单测推导所有 MQ processing 锁也具有 token 所有权语义。

## 缓存范围与配置

[TwoLevelCacheService.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/cache/TwoLevelCacheService.java) 只给 StrategySnapshot 使用 Caffeine L1 与 Redis L2；活动配置是 Redis 单级。空查询使用短时 __NULL__ 标记。快照 loader 在本实例 synchronized 区域内加载；这不是分布式回源锁。

[application.yml](../../../lottery-interfaces/src/main/resources/application.yml) 的实际配置：快照 L1 60 秒、最大 10,000 条，L2 600 秒加 0..120 秒抖动，空值 60 秒；活动 300 秒加 0..60 秒抖动，空值 30 秒；未消费 take 1800 秒。配置以运行时绑定值为准，不照抄 [CacheConfigProperties.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/cache/CacheConfigProperties.java) 中未生效的默认值作为环境事实。

缓存失效只删除当前实例 L1 和共享 Redis，不广播给其它实例，因此使用短 TTL 最终一致。现有缓存读取异常与回写异常处理不同：Redis 读/JSON 解析会抛出，回写或失效失败记录日志后继续。不能声称所有 Redis 缓存失败均自动回源；库存 key 也不能套用配置缓存的 miss 策略。

## 未完成参与记录

未消费 take 不再缓存读写，归属与是否允许新建一律查询 MySQL。旧 Redis key/TTL 配置可自然留存，缓存过期、缺字段或陈旧数据不能改变请求归属。活动库存与抽奖短锁仍按原逻辑使用 Redis。
