# 实施顺序

1. 建立隔离 MySQL 回归用例，先复现 COMPLETE 被覆盖与发奖补偿状态不一致。
2. 添加条件 UPDATE、消费失败独立入口，修复发奖消费收口与发送成功语义。
3. 覆盖状态矩阵、乱序 Future、真实 SQL 并发、超时扫描及重复消息。
4. 运行定向测试、mvn clean test 与 mvn package，检查 diff 和现有未提交改动边界。
5. 更新消息状态规范及结果记录，归档任务；不自动提交 Git。

回滚以本任务 diff 为边界，保持已有用户修改；数据库无结构变更。

## 完成

上述五步均完成；具体验证见 validation.md。规范记录 CAS 事件矩阵、旧发奖 COMPLETE 恢复和真实 SQL/Mock 边界。
