# 责任边界与取舍

瓶颈候选落在Connector/J额外会话状态往返及单producer网络线程同步回调，不直接改变回调执行模型。最小差距：相同JDBC管理的会话状态重复向MySQL查询。官方属性说明支持缓存JDBC API维护的状态，本地8.0.28 ConnectionImpl.isReadOnly(boolean)分支明确在useLocalSessionState=false时queryServerVariable。

只在application.yml三条业务连接URL与application-perf.yml三条覆盖URL加useLocalSessionState=true；新增JdbcLocalSessionStateManualIT在13329自建专属schema，分别对false/true测试数据库SELECT计数、只读/自动提交/隔离状态、Hikari归还重用以及Spring事务提交/回滚。主代码rg未发现直接SQL SET SESSION/SET TRANSACTION；未来必须经JDBC管理，不能绕过缓存。此缓存不缓存业务数据，不关闭数据库约束、commit或持久化。

风险是未来SQL直接改变会话状态会使缓存不一致，因此契约写入database规范。readOnlyPropagatesToServer保持默认true，useLocalTransactionState保持默认false，不顺带加其它驱动参数。

实验保持索引可见、堆/池/线程/全中奖模型不变。原200RPS失败轮作为诊断基线；新200同负载复测，必要时仅临时改perf覆盖URL为false做无线程探针反向，结束恢复true，保存每轮生效配置。数据逐轮增长，不清库，不用旧数据比例包装吞吐倍数。

事务/池回归通过后串行构建与压测。最高候选300秒；原幂等/超时/D007/真实Kafka重投和全局审计。没有进一步最小可靠优化证据时停止代码迭代，报告残余网络回调、消费者/commit/CPU边界。

## 实测失败后的必要前置修复
真实门禁在8.0.28/false通过、8.0.28/true回滚失败；General Log证明后者漏发SET autocommit=0，错误写入仅在自有fixture。原判断误称false失败，日志复核已纠正。官方#106435及8.0.29发布说明与字节码isAutocommit/isAutoCommit分支完全一致。
新增最小依赖修复：只从8.0.28升至首个修复8.0.29，框架/JDK/其它主动依赖版本不变；原测试断言与流程保持不变。驱动修复独立提交，再以8.0.29/false作为新200RPS基线与8.0.29/true单变量对照。不能把两项变量混为一个配置收益。
