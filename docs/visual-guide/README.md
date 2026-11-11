# Lottery 交互式架构与业务数据流图解

> 历史快照：本目录15幅图、教学数据、生成器和源码行号适用于2026-11-01。活动总库存已于2026-11-02移除；本轮保留既有图解与验证证据，未重绘。当前流程、SQL与测试见 [业务简化记录](../activity-stock-removal.md)。

入口为 [index.html](index.html)。本目录包含全部必需资源，没有 CDN、构建步骤或业务服务依赖。可以用普通浏览器打开入口；若浏览器限制本地文件，使用本机预览：

~~~bash
python3 -m http.server 8768 --bind 127.0.0.1 --directory docs/visual-guide
~~~

然后访问 [本机入口](http://127.0.0.1:8768/index.html)。Codex 内置浏览器只接受 HTTP/HTTPS，本次入口交互通过本机预览验证，没有绕过其本地文件协议限制。

## 2026-11-01 更新

以当前工作区为准，包含尚未提交的库存、事务、缓存与 ID 配置优化。原有14幅图同步刷新，新增加第15幅“事务异常与回补”；41个路径步骤与40个源码文件摘录重新生成。

- `RedisUtil.java`：单Key Lua检查并条件扣减；回补只修改现存Key，缺失明确报错。
- `StrategyRepository.java`：奖品库存Key缺失抛出，保留原take及requestId；正常未中奖图只包含NO_PRIZE与真实售罄。
- `BaseActivityPartake.java`、`UserTakeActivityRepository.java`、`OrderRepository.java`、`StockReservationTransactions.java`：takeId先于活动预扣生成，异常回补依据真实事务结果；业务拒绝保持原回补入口。
- `TwoLevelCacheService.java`：事务提交后失效、回滚保留缓存、无事务即时失效；跨实例L1仍依赖TTL。
- `SnowflakeIdGenerator.java`、`application.yml`：显式配置节点号，多实例部署需分配唯一workerId。

当前源码中仍存在的JVM崩溃预留窗口、事务结果未知、人工库存恢复和模拟发奖边界继续如实保留。

## 建议阅读顺序

1. **系统全貌**：代码分层 → 运行组件。五个 Maven 模块构成同一应用；组件位置与数据存储分别解释。
2. **一次抽奖**：正常中奖 14 步 → 正常未中奖 12 步。观察 requestId、takeId、orderId 与各类库存。
3. **请求之后**：沿同一中奖订单继续 15 个异步步骤，再单独阅读库存/发奖消息状态。
4. **失败与恢复**：重复请求、Redis 库存缺失、重复消息/消费者宕机、BLOCKED 对账、事务异常与回补。发送失败与超时恢复在状态图和说明中展开。

编号、上一步/下一步、左右方向键控制步骤。数据面板显示本步之后的教学状态，改变的字段同时显示前后值。箭头通过 Archify 公开关系深链高亮；图内支持聚焦、上下游、搜索、路径探索、主题和导出。单独打开完整图可以获得更大画布。

源码按钮进入 [sources.html](sources.html)，离线定位实际行号。本地文件模式展示原文件相对链接；HTTP预览只服务图解目录，原文件请在项目目录按文件名打开。[reference.html](reference.html) 汇总字段、库存时间线、事务、状态、表/Key/topic、实现边界与讲解练习。

## 独立图解

| 章节 | HTML | 类型 |
| --- | --- | --- |
| 代码分层 | [01-layers.html](diagrams/01-layers/01-layers.html) | architecture |
| 运行组件 | [02-runtime.html](diagrams/02-runtime/02-runtime.html) | architecture |
| 参与资格 | [03-participation.html](diagrams/03-participation/03-participation.html) | sequence |
| 中奖订单 | [04-draw-order.html](diagrams/04-draw-order/04-draw-order.html) | sequence |
| Outbox投递 | [05-outbox-dispatch.html](diagrams/05-outbox-dispatch/05-outbox-dispatch.html) | sequence |
| 库存账本 | [06-stock-settlement.html](diagrams/06-stock-settlement/06-stock-settlement.html) | sequence |
| 模拟发奖 | [07-fulfillment.html](diagrams/07-fulfillment/07-fulfillment.html) | sequence |
| 库存消息状态 | [08-stock-states.html](diagrams/08-stock-states/08-stock-states.html) | lifecycle |
| 发奖消息状态 | [09-invoice-states.html](diagrams/09-invoice-states/09-invoice-states.html) | lifecycle |
| 请求重试 | [10-request-retry.html](diagrams/10-request-retry/10-request-retry.html) | workflow |
| 库存Key缺失 | [11-stock-key-missing.html](diagrams/11-stock-key-missing/11-stock-key-missing.html) | workflow |
| 消费者宕机 | [12-consumer-crash.html](diagrams/12-consumer-crash/12-consumer-crash.html) | workflow |
| BLOCKED对账 | [13-blocked-reconcile.html](diagrams/13-blocked-reconcile/13-blocked-reconcile.html) | workflow |
| 未中奖结束 | [14-no-prize.html](diagrams/14-no-prize/14-no-prize.html) | sequence |
| 事务异常与回补 | [15-stock-recovery.html](diagrams/15-stock-recovery/15-stock-recovery.html) | workflow |

## 源码事实与实现范围

- 依据当前 HTTP、应用编排、领域、仓储、Mapper、Kafka 与 XXL-Job 源码；参考 README、architecture.md、refactor-audit.md、V2/V3 迁移及最新接口/消息说明。
- refactor-audit.md 是重构前审计；旧 Dubbo、规则树、算法、活动 Kafka、无账本结算和 Scheduled dispatcher 不作为现行链路。
- 库存消费者不调用保留接口中的 Redis PROCESSING/DONE；库存幂等靠全局库唯一账本。Outbox 是订单状态与业务字段。
- 当前请求唯一键是 V3 用户/活动/请求号组合，不是旧 V2 全表 request_id 唯一；参与/次数只分库，订单分库分表。
- 活动 MySQL 库存不逐次扣减，关闭时没有自动回写 Redis 剩余量；运行期没有自动库存对账/恢复入口。
- 发奖处理器是模拟渠道，本地 grant=1 不证明外部奖品实际交付。
- T1/O1 是实际 Snowflake Long ID 的教学代号；数值和快照是教学示例，不是运行数据。异步采用一种成功时序，代码允许回调与消费交错。
- 本次没有启动 Lottery、执行 SQL、发送真实 Kafka 或运行业务集成测试；图解校验不等于业务端到端证据。

## 资源与再生成

guide-data.js保存步骤/职责/源码位置；guide.js管理本地阅读；guide.css为入口与摘录样式。每张图保留candidate.json、产物与回执，本轮回执位于各图的update-20261101目录，历史回执保留。

Archify按提交校验源码证据。为覆盖尚未提交的优化，prepare_source_evidence.py只复制当前源码到系统临时目录并在独立本地Git仓库冻结，再用该快照核对；不提交项目、不改暂存区、不推送。快照不在HTTP预览目录，也不上传。临时目录清理后，重新生成快照并重新生成候选即可。

~~~bash
# 冻结本次当前工作区；后续生成规格和校验必须使用同一份证据。
SOURCE_REPO=$(python3 docs/visual-guide/prepare_source_evidence.py)

# 可在命令末尾指定一张图名，只刷新那一张规格。
ARCHIFY_SOURCE_REPO="$SOURCE_REPO" python3 docs/visual-guide/build_diagrams.py

# 使用已安装 Archify；每个规格都需要完整 finalize。
ARCHIFY_CLI="$HOME/.agents/skills/archify/bin/archify.mjs"
node "$ARCHIFY_CLI" finalize sequence \
  docs/visual-guide/diagrams/03-participation/candidate.json \
  docs/visual-guide/diagrams/03-participation/03-participation.html \
  --repo-root "$SOURCE_REPO" --quality showcase \
  --out-dir docs/visual-guide/diagrams/03-participation/new-review --json

# 刷新教学数据和摘录，核对链接、源码锚点与步骤关系。
python3 docs/visual-guide/build_guide.py
python3 docs/visual-guide/validate_resources.py
node --check docs/visual-guide/guide.js
~~~

生成器保留本次手工核对的业务事实，不会自动理解后续源码改动。更新业务源码后须重新追踪，更新说明和行号。修改已生成图时使用新回执目录保留上一版浏览器证据。

结果见 [verification.md](verification.md) 与 [validation.json](validation.json)。业务代码、SQL、配置和既有测试没有修改。
