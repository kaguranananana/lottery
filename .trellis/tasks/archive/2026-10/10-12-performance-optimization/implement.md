# 执行顺序

- [x] 保存 Git/环境与 schema 证据，启动原有专用栈。
- [x] 装配测试阶段计时、锁关系及换页采样，执行未优化基线。
- [x] 按实际证据选择一个主要 SQL/锁瓶颈，先复现后修改并测试。
- [x] 同负载复测，对账通过后独立提交，再决定下一项。
- [x] 单独复现并修复 awardCount 字段持久化缺陷。
- [x] 运行默认单元测试及真实分片/账本/Kafka 正确性验证。
- [x] 执行持续负载与既定稳定性判断，资源不足时保留限制结论。
- [x] 写优化报告与面试素材，更新规范，记录会话并归档。

验证入口：mvn -q test；doc/performance/run.py、diagnostics.py、check_stability.py、semantics.py；新增 SQL 锁复现只操作专用 fixture，不修改原始结果。
