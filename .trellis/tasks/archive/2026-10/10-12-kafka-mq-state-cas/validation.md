# 完成记录

## 根因复现
- 修复前 MqCallbackStateTest：5 个用例，5 个失败，证明发奖补偿与消费收口语义错误。
- 修复前 MqStateCasManualIT：1 个用例失败，真实 MySQL COMPLETE=1 被迟到发送成功改为 SENT=3。

## 实际验证
- Java：Amazon Corretto 8；MySQL：隔离实例 9.6/InnoDB，端口 13308。
- 定向：mvn test -Dtest=MqCallbackStateTest,MqStateCasManualIT -Dsurefire.failIfNoSpecifiedTests=false -Dlottery.it.mysql=jdbc:mysql://127.0.0.1:13308/；20 个用例通过（7 应用 + 13 MySQL），无失败、错误、跳过。
- 默认门禁：mvn clean test；33 个用例通过，无失败、错误、跳过。
- 打包：mvn package；全 reactor 成功，生成 Lottery.war，再次执行 33 个默认单测通过。
- 并发 SQL：20 轮 × 12 个任务，独立连接竞争同一订单的两条消息状态，终态始终 COMPLETE。
- git diff --check 与本次规范本地链接检查通过。

## 边界和风险
真实数据库验证使用生产 Mapper、Repository，未装配分片插件。Kafka 可控 Future、Redis guard、库存结算为 Mock，发奖使用现有模拟渠道并写真实本地状态；不证明真实中间件端到端或八分表路由。未在 MySQL 8 复验。

所有验收项完成，详见 docs/kafka-mq-state-cas.md。没有结构迁移，没有 Maven 依赖变动，没有自动 Git 提交；用户已有抽奖请求幂等改动保留。旧实例混跑仍能执行旧无条件写入；外部发奖恰好一次未由本修复实现。
