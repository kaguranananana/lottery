# 验证记录（2026-10-12）

## 实际执行
- `mvn clean test`：26 tests，0 failures，0 errors，0 skipped，BUILD SUCCESS。
- `mvn test -Dtest=DrawRequestOwnershipManualIT -Dsurefire.failIfNoSpecifiedTests=false -Dlottery.it.mysql=jdbc:mysql://127.0.0.1:23306/`：9 tests，0 failures，0 errors，0 skipped，BUILD SUCCESS。
- `mvn test -Dtest='DrawRequestOwnershipManualIT,*Request*Test,DrawHttpOwnershipTest' -Dsurefire.failIfNoSpecifiedTests=false -Dlottery.it.mysql=jdbc:mysql://127.0.0.1:23306/`：23 tests，0 failures/errors/skipped；该组包含默认测试的子集，不叠加计算为独立总数。
- MySQL CLI 重复执行 V3 迁移成功；information_schema.statistics 确认 lottery_01/02 两个参与唯一键、次数用户活动唯一键均为 NON_UNIQUE=0，列顺序正确。
- `git diff --check`：通过。

## 环境与证据边界
专用本机 MySQL 9.6.0（隔离端口 23306），用原 doc/sql 基线 CREATE TABLE 建立两库参与表和八张订单表，执行交付迁移。测试使用生产 Repository、MyBatis XML、路由切面和事务；活动库存、抽样和短锁用 Mock，刻意允许全部线程进入，验证 DB 约束而非锁。没有启动业务 MySQL 13306、真实 Redis、Kafka 或 XXL-Job。

覆盖首次 A、重复回放（take/order 不变）、A 中断恢复、未完成 A 阻塞 B、A 完成后 B 新建、同请求八线程一 take/一订单、不同请求八线程仅一次扣减、请求唯一键在 A 完成后仍约束重复插入、次数耗尽的 matched-row 场景、两库八订单表回放路由、真实查询 SQL 异常、订单 INSERT 异常事务回滚、历史归属缺失拒绝、大小写/尾部空格/64中文字符契约。未消费记录缓存不再依赖 Redis，测试没有注入 Redis 客户端。

初次集成运行有测试装配失败：缺 MyBatis 类型别名、参与支持类的独立活动仓储字段未注入，以及 Mockito 覆盖抛异常桩时再次触发异常。均定位为隔离测试装配问题并修正，未用降级/重试/特殊业务分支掩盖失败。真实 SQL 故障用例中的错误日志为预期故障注入，最终门禁无失败。

未验证生产 MySQL 版本、COMMIT 回包中断/断电、真实多实例/Redis TTL 全链路、Kafka 或发奖。历史未知归属须依据日志人工核查。业务库未迁移，需停写、处理历史冲突、完成 V3 后协调发布新版本。库存跨 Redis/MySQL 的既有补偿边界未改变。

## 范围审查
未改抽奖算法、Kafka、库存异步结算、奖品发放和其他审计问题。改动留在工作区供 review；未创建 Git 提交，保留原有未跟踪项目配置。
