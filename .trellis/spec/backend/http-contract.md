# HTTP 契约

## 现有端点

[LotteryController.java](../../../lottery-interfaces/src/main/java/com/anontokyo/lottery/interfaces/web/LotteryController.java) 暴露 POST /api/v1/draw 与 GET /api/v1/activities。活动列表返回当前运行中的活动摘要；本仓库没有创建活动的 HTTP 管理入口。

[DrawRequest.java](../../../lottery-interfaces/src/main/java/com/anontokyo/lottery/interfaces/web/dto/DrawRequest.java) 约束：requestId 非空且最长 64，userId 非空且最长 32，activityId 非 null。通过 @Validated @RequestBody 校验，在 adapter 中转为 DrawProcessReq(userId, activityId, requestId)。内部字段为 uId，边界转换不能丢失 requestId。

## 响应语义

[DrawResponse.java](../../../lottery-interfaces/src/main/java/com/anontokyo/lottery/interfaces/web/dto/DrawResponse.java) 返回业务 code/info、requestId、可用的 takeId/orderId、win 与奖品详情。win 仅在业务成功且存在奖品详情时为 true。未中奖使用 LOSING_DRAW 业务码，不是系统异常；SUCCESS 表示同步抽奖下单成功，不代表已完成异步发奖。

业务码由 [Constants.java](../../../lottery-common/src/main/java/com/anontokyo/lottery/common/Constants.java) 定义。沿用当前 DTO 结构；新增字段须跟踪用例结果到 toResponse 的映射，避免直接返回基础设施 PO。

## 错误与验证边界

当前 draw 捕获用例异常、记录请求标识与堆栈并返回错误业务码；没有显式设置 HTTP 错误状态。Bean Validation 在方法执行前失败，当前没有项目级统一异常处理器，因此不能声称它返回相同的抽奖 DTO。改变状态码或错误结构需要明确接口契约任务。

[DrawRequestIdempotencyTest.java](../../../lottery-interfaces/src/test/java/com/anontokyo/lottery/test/domain/DrawRequestIdempotencyTest.java) 验证编排回放，不覆盖 HTTP 校验、序列化或错误状态码。[ApiManualIT.java](../../../lottery-interfaces/src/test/java/com/anontokyo/lottery/test/manual/ApiManualIT.java) 属手工环境验证，不能将编排单测视为 HTTP 端到端验证。

## 请求恢复

接口增加 pendingRequestId。D007 表示其他请求仍未完成，requestId 仍回显本次 ID，客户端使用 pendingRequestId 恢复原操作；D008 表示归属不明并保留原机会。客户端未知结果必须继续原 ID，丢失 ID 可通过提交新 ID 获取未完成请求号，但没有未完成记录时会正常开始新一轮。详细契约见 [draw-request-idempotency.md](../../../docs/draw-request-idempotency.md)。
