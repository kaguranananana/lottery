# 领域规则

## 参与与订单

[ActivityPartakeImpl.java](../../../lottery-domain/src/main/java/com/anontokyo/lottery/domain/activity/service/partake/impl/ActivityPartakeImpl.java) 检查活动为 DOING、参与时间在活动区间、活动库存有剩余。[BaseActivityPartake.java](../../../lottery-domain/src/main/java/com/anontokyo/lottery/domain/activity/service/partake/BaseActivityPartake.java) 从数据库查询未消费 take，仅同 requestId 能复用；其他请求返回 D007 与 pendingRequestId，归属缺失返回 D008。数据库获取资格失败时回补已预扣活动库存，并在唯一冲突场景重新核对归属。

[AwardOrderImpl.java](../../../lottery-domain/src/main/java/com/anontokyo/lottery/domain/award/service/order/impl/AwardOrderImpl.java) 使用 IdGenerator 创建订单，委托 saveDrawOrderWithTakeLock 同事务消费 take、写订单；返回 InvoiceVO 与结算数据。不要把路由及 MyBatis 细节移入领域服务，也不要将订单创建与消费 take 拆成两个独立提交。

## 概率模型

[StrategySnapshotFactory.java](../../../lottery-domain/src/main/java/com/anontokyo/lottery/domain/strategy/service/snapshot/StrategySnapshotFactory.java) 将配置概率乘以 1,000,000 并向下取整为整数权重，编译奖品数组与前缀和数组。负概率、缺失必要配置、总权重超过 1,000,000 显式抛异常；零权重奖品不进入采样数组。

不足 100% 的余量形成一个 awardId=null 的 NO_PRIZE 槽位。例如奖品概率 50% 时，前 500,000 个采样点命中奖品，后 500,000 个命中 null，不能把余量归一化到真实奖品。

[WeightedRandomSelector.java](../../../lottery-domain/src/main/java/com/anontokyo/lottery/domain/strategy/service/draw/WeightedRandomSelector.java) 用随机整数与前缀和二分采样。快照构建后按只读对象使用；[DrawExecutor.java](../../../lottery-domain/src/main/java/com/anontokyo/lottery/domain/strategy/service/draw/impl/DrawExecutor.java) 命中售罄奖品时告警并视为未中奖，不重新抽奖，不重新分配概率。strategyMode 目前作为数据保留，不选择多个算法。

边界实例由 [WeightedRandomSelectorTest.java](../../../lottery-interfaces/src/test/java/com/anontokyo/lottery/test/domain/WeightedRandomSelectorTest.java) 的微精度、50% 分界、全 NO_PRIZE、负概率与超过 100% 用例提供。新增算法须有真实需求，不能为模板完整性引入 Alias Method 等额外实现。

## 活动生命周期

[ActivityLifecycleService.java](../../../lottery-domain/src/main/java/com/anontokyo/lottery/domain/activity/service/lifecycle/ActivityLifecycleService.java) 的合法转换为 EDIT(1)→PASS(4)、PASS(4)→DOING(5)、PASS(4)→CLOSE(7)、DOING(5)→CLOSE(7)。仓储使用 expected state 的 CAS，影响行数决定是否成功。PASS→DOING 仓储在活动行锁内校验时间并先完成 SETNX 预热，Redis 失败保持 PASS；历史状态值不作为新增业务规则；不能绕过转换矩阵直接覆盖状态。

## 发奖扩展

[AwardFulfillmentHandler.java](../../../lottery-domain/src/main/java/com/anontokyo/lottery/domain/award/service/fulfillment/AwardFulfillmentHandler.java) 通过 supportType() 与 fulfill(InvoiceVO) 扩展。[AbstractAwardFulfillmentHandler.java](../../../lottery-domain/src/main/java/com/anontokyo/lottery/domain/award/service/fulfillment/impl/AbstractAwardFulfillmentHandler.java) 当前只模拟发奖并写本地 CAS 状态；不能把重复返回 SUCCESS 推导为真实渠道恰好一次。

真实外部渠道接入是独立任务，须定义 orderId 幂等键、外部成功但本地状态未更新时的处理与有效验证。当前注册表拒绝重复类型和缺失处理器；扩展新类型沿用接口，不再增加无必要的 Config/Factory 包装层。
