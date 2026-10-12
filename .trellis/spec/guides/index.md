# 跨层开发指南

| 指南 | 何时阅读 |
| --- | --- |
| [跨层字段与事实来源](./cross-layer-thinking-guide.md) | 新增字段，调整幂等、订单、库存或消息状态 |
| [现有能力复用](./code-reuse-thinking-guide.md) | 新增 helper、常量、Repository 方法、发奖类型或调度任务 |

指南依据 Lottery 当前的调用链与模块边界。只核对任务真正影响的接口、数据流和失败路径，避免为了形式遍历无关的极端场景。
