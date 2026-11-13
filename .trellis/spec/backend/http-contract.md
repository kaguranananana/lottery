# HTTP 契约

## 现有端点

[LotteryController.java](../../../lottery-interfaces/src/main/java/com/anontokyo/lottery/interfaces/web/LotteryController.java) 暴露 POST /api/v1/draw、GET /api/v1/draw/identity 与 GET /api/v1/activities。活动列表返回当前运行中的活动摘要；本仓库没有创建活动的 HTTP 管理入口。

[DrawRequest.java](../../../lottery-interfaces/src/main/java/com/anontokyo/lottery/interfaces/web/dto/DrawRequest.java) 约束：requestId 非空且最长 64，userId 非空且最长 32，activityId 非 null。通过 @Validated @RequestBody 校验。Controller 读取 Servlet Principal，核对请求体 userId 与其名称一致，再使用认证身份构造 DrawProcessReq。内部字段为 uId，边界转换不能丢失 requestId。

## 响应语义

[DrawResponse.java](../../../lottery-interfaces/src/main/java/com/anontokyo/lottery/interfaces/web/dto/DrawResponse.java) 返回业务 code/info、requestId、可用的 takeId/orderId、win 与奖品详情。win 仅在业务成功且存在奖品详情时为 true。未中奖使用 LOSING_DRAW 业务码，不是系统异常；SUCCESS 表示同步抽奖下单成功，不代表已完成异步发奖。

业务码由 [Constants.java](../../../lottery-common/src/main/java/com/anontokyo/lottery/common/Constants.java) 定义。沿用当前 DTO 结构；新增字段须跟踪用例结果到 toResponse 的映射，避免直接返回基础设施 PO。

## 错误与验证边界

业务调用仍按原 DTO 同步返回。业务异常记录请求标识与堆栈，并按现有契约返回 HTTP 200 和业务码 0001。Bean Validation 在方法执行前返回 400。

| HTTP | 业务码 | 触发条件 |
| --- | --- | --- |
| 401 | A001 | 请求无已认证身份 |
| 403 | A002 | 请求体与认证身份不一致 |
| 429 | L001 | 全局桶耗尽 |
| 429 | L002 | 用户活动桶耗尽 |
| 429 | L003 | 本机并发许可已用完 |
| 503 | L004 | Redis 准入检查失败 |

生产部署需要身份集成建立可信 Servlet Principal。接口不读取客户端身份头。身份查询返回当前用户 ID，本机演示必须显式绑定 127.0.0.1 并配置固定账户。通过身份校验后，依次尝试 Semaphore 与单次 Redis Lua。任一桶耗尽时两个桶都不扣令牌，拒绝在 MySQL 查询之前返回。本机许可在 finally 归还。三个拒绝分支可按 HTTP 业务码及累计计数区分。限流开关、全局与用户速率/容量、单机许可均由 `lottery.draw.admission` 配置，关闭限流仍要求有效身份。

429、503 会回显请求号，客户端等候后继续同一请求号。原请求可能已在更早的一次调用中完成，客户端不得以临时拒绝推断该逻辑抽奖从未发生。

[DrawRequestIdempotencyTest.java](../../../lottery-interfaces/src/test/java/com/anontokyo/lottery/test/domain/DrawRequestIdempotencyTest.java) 验证编排回放，不覆盖 HTTP 校验、序列化或错误状态码。[ApiManualIT.java](../../../lottery-interfaces/src/test/java/com/anontokyo/lottery/test/manual/ApiManualIT.java) 属手工环境验证，不能将编排单测视为 HTTP 端到端验证。

## 请求恢复

接口增加 pendingRequestId。D007 表示其他请求仍未完成，requestId 仍回显本次 ID，客户端使用 pendingRequestId 恢复原操作；D008 表示归属不明并保留原机会。客户端未知结果必须继续原 ID，丢失 ID 可通过提交新 ID 获取未完成请求号，但没有未完成记录时会正常开始新一轮。详细契约见 [draw-request-idempotency.md](../../../docs/draw-request-idempotency.md)。

中奖响应保留订单快照和原请求、参与、订单身份。`award.grantDate` 仅表示实际发奖完成时间，完成前为 null；V8 移除 `award.grantType` 与计划时间透传。
