# Connector/J 8.0.28 会话缓存回归的真实复现与最小修复

200RPS瓶颈诊断显示单一producer网络线程在同步MySQL状态回调等待，单轮437733次额外transaction_read_only SELECT、39971新订单，约10.951次/单。尝试会话缓存前先加真实门禁，及时发现8.0.28/true在新Hikari连接上回滚不生效，8.0.28/false正常。

服务器General Log证明true模式缺少SET autocommit=0，INSERT已自动提交，随后rollback无法撤销。原断言未改，错误仅写入测试自有schema；当时专用应用停止，业务三库未受影响。短暂General Log已恢复OFF，原失败日志/线缆日志本地保留。初步模式判断曾误称false失败，按两段日志顺序核实后纠正，不能将该初步说法写成最终事实。

本地8.0.28字节码在setAutoCommit比较isAutocommit协议状态，而getAutoCommit使用另一JDBC缓存字段；官方缺陷#106435及8.0.29发布说明完全对应。本任务只把驱动升至首个修复8.0.29，框架/JDK不升级，不增加预热SELECT、强制SET或事务兼容分支。

原3项会话/池/Spring事务测试未改断言，新驱动下false/true均通过；原库存/次数/创建6项、补偿索引2项和默认35项也通过，共46项。十次isReadOnly仍为false模式10次服务器SELECT、true模式0次，客户端和数据库的只读/自动提交/隔离级别均验证，Hikari复用同一物理连接、未提交归还实际回滚、Spring提交持久化正确。

这是驱动正确性修复，尚不是会话缓存的完整性能收益结论。后续必须用8.0.29/false和8.0.29/true同负载对照，不能把旧8.0.28失败轮直接与新驱动/新参数混为单变量收益。useLocalTransactionState及readOnlyPropagatesToServer默认值保留，commit和持久化1/1不取消。

依据：[官方缺陷记录](https://bugs.mysql.com/bug.php?id=106435)、[8.0.29发布说明](https://dev.mysql.com/doc/relnotes/connector-j/en/news-8-0-29.html)、[会话缓存属性](https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-performance-extensions.html)。属性文档为当前文档，实际行为以本地8.0.28/8.0.29字节码和真实测试为准。
