# 2026-10-13 JDBC 会话缓存与持续容量复核

本轮承接[补偿索引实验](../20261013-capacity-scan/README.md)，保留全部历史数据、原四容器和持久化参数。先定位生产者回调里的额外会话查询，再通过真实事务门禁发现并修复驱动缺陷，最后用相同驱动做参数对照。最高候选150RPS已经完成两次连续300秒通过，首轮150及更高失败轮次全部保留；最终正确性验收与收尾另见下文。

## 根因、修改与取舍

旧8.0.28/索引版本200RPS失败轮中，三个线程现场均显示唯一producer网络线程在同步MySQL回调等待，其中一次进入`ConnectionImpl.isReadOnly→queryServerVariable`。SQL计数为437733次额外`SELECT @@session.transaction_read_only`、39971个新订单，约10.95次/单。

本项仅在`application.yml`与`application-perf.yml`的三个连接URL增加`useLocalSessionState=true`。它缓存JDBC维护的会话状态，不缓存业务数据；没有增加线程、池、消费并发或跳过SENT/FAIL/COMPLETE CAS。只读设置仍传播给服务器，`useLocalTransactionState`保持默认false，commit、唯一账本和MySQL持久化1/1保留。

风险是SQL直接修改会话状态会绕过客户端缓存，因此主链路先检索确认没有`SET SESSION/SET TRANSACTION`，以后经JDBC API管理。真实门禁覆盖只读、自动提交、隔离级别、同一物理连接的Hikari归还重置、未提交归还回滚及新池首次Spring提交/回滚。

真实测试先发现8.0.28/true首次Spring回滚后INSERT仍存在；服务器日志证明漏发`SET autocommit=0`。8.0.28/false正常。保持测试断言，单独升级到首个官方修复8.0.29，前置46项回归通过；没有预热SELECT、强制SET或兼容分支。该正确性修复独立提交`94b790e`，详见[失败与修复记录](driver-fix.md)。业务应用从未在8.0.28/true下启动。

依据：[官方缺陷#106435](https://bugs.mysql.com/bug.php?id=106435)、[8.0.29发布说明](https://dev.mysql.com/doc/relnotes/connector-j/en/news-8-0-29.html)、[会话缓存属性](https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-performance-extensions.html)。当前官方文档与本地8.0.28/8.0.29字节码、真实回归分别使用，不拿旧驱动失败轮冒充新参数单变量对照。

## 同驱动、同200RPS的参数对照

两侧均为Connector/J8.0.29、补偿索引可见、30线程、20秒预热与180秒测量、10万预置用户、五奖品等概率且100%中奖。应用各自启动，三池实际URL见[生效配置](effective-profiles.json)。不同活动使用相同用户前缀，逐轮排空并审计，无并行构建/测试、线程探针或其它高负载。

| 指标 | false：jdbcstate200-before | true：jdbcstate200-after |
|---|---:|---:|
| 实际唯一新抽奖RPS | 199.794 | 199.811 |
| 全轮订单（含预热） | 39967 | 39971 |
| HTTP P95/P99 ms | 27/33 | 27/33 |
| 异步P95上界 ms | 3228 | 3094 |
| 发奖采样积压峰值 | 493 | 448 |
| 首尾平均积压增长 | 48.00 | 157.29 |
| 排空秒 | 0.539 | 0.682 |
| 额外只读状态SELECT | 437907 | 0 |
| 显式COMMIT | 119742 | 119699 |
| producer线程SQL累计 ms | 169151.42 | 148230.48 |
| MySQL平均CPU（单核100%） | 124.63% | 101.63% |
| 库存Outbox handler均值 ms | 128.81 | 134.97 |
| Swap-in/out MiB | 0.125/0 | 1.297/0 |
| 原稳定条件/逐ID业务审计 | 通过/通过 | 通过/通过 |

可确定的是每单约10.957次会话查询消失，真实COMMIT保留；独立API探针十次`isReadOnly`为10→0服务器SELECT。CPU和累计回调时间更低是本机观测，历史数据增加39967单、执行顺序未随机化、I/O/缓存状态仍有波动，不能认证精确CPU收益比例。HTTP P99完全相同、Outbox均值反而略高，两侧都通过180秒，因此不能写成“让200RPS从失败到稳定”或吞吐提升倍数。后续300秒200RPS失败也原样保留。

## 持续容量：完整异步链路与HTTP入口分开

| 轮次 | 测量秒 | 新抽奖RPS | HTTP P95/P99 ms | 异步P95上界 ms | 发奖积压峰值 | 首尾平均增长 | 排空秒 | 原稳定判定 |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| jdbcstate200-before | 180 | 199.794 | 27/33 | 3228 | 493 | 48.00 | 0.539 | 通过 |
| jdbcstate200-after | 180 | 199.811 | 27/33 | 3094 | 448 | 157.29 | 0.682 | 通过 |
| jdbcstate250-probe | 180 | 249.706 | 62/234 | 61366 | 12525 | 9635.29 | 60.581 | 未通过 |
| jdbcstate225-probe | 180 | 224.756 | 31/41 | 8566 | 1769 | 721.43 | 5.861 | 未通过 |
| jdbcstate200-confirm | 300 | 199.627 | 34/111 | 5648 | 1423 | 392.29 | 6.299 | 未通过 |
| jdbcstate180-confirm | 300 | 179.907 | 55/196 | 32694 | 5917 | 5085.14 | 24.424 | 未通过 |
| jdbcstate150-confirm | 300 | 149.930 | 88/228 | 4161 | 559 | 247.14 | 3.604 | 未通过 |
| jdbcstate150-repeat | 300 | 149.937 | 19/23 | 2061 | 163 | 8.57 | 0.830 | 通过 |
| jdbcstate150-repeat2 | 300 | 149.940 | 58/254 | 2980 | 391 | 128.00 | 0.691 | 通过 |

所有正常基准均为独立新请求、100%中奖、HTTP/系统错误率0，最终逐ID/库存/账本/状态/Kafka审计通过；审计通过只证明最后完成，没有将失败的持续测试改成通过。原稳定标准未改，异步时间为秒精度数据库时间的一秒宽上界，积压为约9秒采样值。

200RPS两侧180秒均通过，300秒却增长392.29单；225RPS虽异步P95仅8.566秒，积压增长721.43单仍失败。250/180长测四项异步阈值失败。首轮150长测只有增长247.14单超150失败，经过只读诊断、应用停止/恢复后的同配置150两次连续300秒均通过，增长8.57/128单。没有改变broker/数据库/堆/池/并发，也没有把应用恢复包装成已证明的因果优化。

**最高已实测通过的300秒负载是150RPS，并完成两次连续通过；150也曾失败，故不保证长时间和任意资源状态都稳定。** 180及200的300秒未通过，150～180可作为当前继续复核的区间，不是已经证明单调的精确容量夹逼。历史数据、I/O和缓存状态不同，不能声称精确最大值、生产容量或所有低于150的未测速率。保留原80RPS历史300秒通过和本轮100/150RPS索引阶段180秒结果。

本阶段初始520912单，容量九轮累计440294新订单；最后一轮前已有913220单。每轮新增10万活动次数fixture，未清理历史。实际参数/生效URL、资源与SQL增量见[比较汇总](comparison-summary.json)和[容量汇总](capacity-summary.json)。

两次通过的150长测HTTP P99为23/254ms、异步P95上界2061/2980ms，明显波动必须一起引用；不能只挑23ms做“稳定延迟”宣传。

## 资源与残余瓶颈

Mac仍为10 CPU/16GiB，Docker VM约7.65GiB。MySQL8.0.46为2CPU/2GiB、128MiB buffer pool、RR、持久化1/1；Kafka2.8.1为2CPU/1GiB、256/512MiB堆、单broker、acks=1、amd64镜像在本机运行；两组各8分区、各4消费者。Redis和ZooKeeper限制不变。应用512/1024MiB堆、4处理器，JMeter256/512MiB堆、2处理器。原五个其它容器一直停止，未启动或清理它们。

首轮150RPS300秒虽仅积压增长失败，末段成本明显增加：[首尾阶段增量](late-stage-comparison.json)记录回调SQL均值1.398→3.080ms、库存消费10.034→21.944ms、发奖消费8.161→18.395ms；阶段有嵌套，不能相加当CPU占比。库存消费者两次订单读取仍存在，250RPS轮每次约0.9ms，状态写入约6ms；重复读成本已观察，但没有分离它对容量的贡献，未顺带修改服务接口或消息字段。

180RPS后段及排空/审计/空闲混合的183.799秒[资源窗口](residual-window-delta.json)中，MySQL/Kafka分别有1508/234次memory.max事件、0 OOM；CPU限流约1.887/0.588秒。InnoDB data file读约3.277GB，日志misc累计115.822秒、binlog misc94.841秒，fsync增量84364、log_waits=0。misc不等于全部都是fsync，多线程计时会重叠；该窗口不是完整测量期，不能量化唯一根因或CPU贡献比例。它支持将持久日志I/O与容器资源约束列为后续重点，不能只凭平均CPU认定两核算力不足。

broker现有GC日志按九轮真实首末时间匹配：无Full GC，最大暂停197.966ms；两次通过150长测分别累计暂停158.945/182.383ms、最大47.684/29.205ms，见[GC核对](broker-gc-summary.json)。应用无Old GC、池无等待/超时。这些证据不足以解释几十秒积压为GC故障。停止专用应用后brokerCPU约1.58%、内存787MiB，恢复应用前后30秒空闲均无Swap-in/out；没有发现额外Maven/JMeter/Java进程需要杀掉。没有为了得到更好的数据重启broker、扩内存、改持久化或清数据。

补充线程采样实际发生在180RPS入口停止并排空后，producer处于网络poll，不能证明高负载时没有回调阻塞；[时间与摘录](late-thread-observation.json)保留这一范围。容器JRE没有ps/jcmd/jstat，未安装工具改变环境；现有GC日志提供了可用证据。取线程现场的SIGQUIT没有产生可用broker栈，不把空输出写成成功诊断。

## 最终正确性与资源收尾

[最终测试](final-test-validation.json)：8.0.29下默认35项单测已执行并通过，最后在应用/JMeter停止时重新运行真实MySQL会话/池3项、补偿索引2项、次数/库存/映射6项。最后6项使用当前true连接参数；共46项通过、0失败/错误，不将历史13项CAS测试算成本次执行。业务Java与状态Mapper未修改，原断言保持，参数风险另由真实提交/回滚测试覆盖。

`semantics`活动742900：不同/相同requestId热点各1次0000、49次D006；客户端真实发生1ms读取超时后，用原ID恢复仍只有一单；D007返回原request归属并恢复原take。四用户各一单、最终审计通过。`message-replay`真实重投2条库存和2条发奖消息，两组位点实际增长、Lag归零，库存/次数/订单/参与/账本/状态均未改变。

有限活动742901：3973请求中100次0000、3873次D004，只生成100订单/账本。有限奖品742902：3976新抽奖中50次0000、3926次D001，五奖品各10份、Redis/MySQL最终均售罄，只生成50订单/账本。拒绝/未中奖吞吐不计正常容量，未对有限测试套稳定性标准。

[全库审计](final-audit-summary.json)：961360个唯一订单、take关联及唯一账本，1005414条已消费参与，4144017条次数行。次数消耗502620+502794=1005414；无重复order/take、关联缺失、负次数/奖品库存、BLOCKED或未完成订单。最终诊断期间计数不变，961360历史订单时补偿与库存空扫描均读0行；该无积压计划不保证高积压成本恒定。

整个本轮从382935订单、423063参与增加578425订单、582351参与；本目录JDBC阶段新增440448订单，索引阶段137977订单分别记录，不将全部96万单冒充本轮新增。全库MySQL与本轮逐活动HTTP/Redis/Kafka审计范围分开，历史请求未重放。未覆盖真实第三方渠道、broker HA、多实例和XXL admin。

[关闭核对](shutdown-audit.json)：专用应用、JMeter及四容器已停止，MySQL/Redis退出0，Kafka/ZooKeeper退出143，均无OOM；MySQL与Kafka日志确认关闭完成，60秒数据库宽限，挂载、限制、端口和其它五实例原状态不变，数据卷全部保留。初次核对错误要求所有退出0，按实际Java信号协议与关闭日志纠正，未重启/修改业务来凑退出码。143来自SIGTERM的128+15，依据[OpenJDK11终止处理](https://raw.githubusercontent.com/openjdk/jdk11u/master/src/java.base/unix/classes/java/lang/Terminator.java)。

## 已证实收益、未证实部分与停止理由

索引阶段已有同数据六轮交替及80RPS反向实验：43.1万完成历史空扫描→0行，真实handler约249→6.15→249ms。当前JDBC参数又确定一轮约4万订单对应的437907次额外只读状态查询→0，真实业务提交/回滚及账本语义不变。这是可复现的操作/访问路径收益。

同200负载CPU124.63→101.63%、累计回调169.15→148.23秒是观察，不作为精确因果比例。150同配置首轮失败、后来连续两轮通过，第二轮P99仍显著更高；没有证明仅由重启、GC、数据增长、容器回收或单一I/O来源导致。最高通过150与较高失败180/200分开，不把同步249.7RPS、排空后审计通过或两轮通过写成无条件生产容量。

剩余同步回调、消费者持久状态推进、真实同奖品行锁和日志I/O都有成本；SQL读取、CPU配额、容器内存回收与共享存储各自的容量贡献未完全分离。消除重复消费者读取的整体收益尚未受控验证；异步/批量回调会涉及失败窗口和CAS顺序，当前没有必要借此扩大设计。停止代码迭代是因为没有新的最小修改收益证据，保持事务、重投和持久化语义，后续应先控制I/O及资源状态再验证候选。

## 简历与面试可用事实

可表述：沿真实HTTP→Redis→两库八表→Outbox→Kafka→MySQL唯一结算账本→模拟发奖进行持续压测；用执行树和反向实验将43.1万历史订单补偿空扫描降为0行，handler约249ms降至6ms；定位生产者线程的驱动会话往返，在同驱动200RPS对照中消除43.79万次只读查询，保留真实提交与状态CAS；测试先复现驱动缓存破坏首次事务回滚，再用官方最小补丁修复。150RPS完成两次300秒验收，同时保留150/180/200长测失败与资源边界。

面试追问应能解释：LIMIT为何不限制底层空扫描量；单列grant索引如何保留`!=1`与NULL语义及写维护成本；为什么回调SQL会阻塞producer网络线程；会话缓存为何先验证Hikari重置和首次Spring回滚；HTTP249.7RPS为何不能称完整链路稳定吞吐。不要写“服务提升几十倍”“生产150QPS稳定”“零换页全程”“GC问题已解决”或“关闭持久提交”。

## 复现与证据范围

先沿用[性能README](../../README.md)核实并恢复专用实例，不对已有数据执行init/clean。当前驱动和六条URL必须一起使用；false对照只在停止应用后改性能覆盖URL、保存配置并重新启动，不能沿用仍存活的连接池。实验后恢复true并核对三池URL。

```bash
# 所有SQL用例只操作13329的自有schema；不得替换成其它环境端口。
mvn test -Dtest=JdbcLocalSessionStateManualIT,InvoiceCompensationIndexManualIT,PerformanceSqlManualIT -Dsurefire.failIfNoSpecifiedTests=false -Dlottery.perf.mysql=jdbc:mysql://127.0.0.1:13329/
bash doc/performance/build.sh
# 另一个终端启动专用PerfHarness；JMETER_BIN指向本机5.6.3。
python3 doc/performance/run.py --session new-jdbc-run --name rate150 --id 743150 --threads 30 --duration 320 --warmup 20 --target-rps 150 --jmeter "$JMETER_BIN"
python3 doc/performance/diagnostics.py doc/performance/results/new-jdbc-run/rate150
python3 doc/performance/stages.py doc/performance/results/new-jdbc-run/rate150
python3 doc/performance/check_stability.py doc/performance/results/new-jdbc-run/rate150
python3 doc/performance/semantics.py --session new-jdbc-run --name semantics --id 743900
python3 doc/performance/message_replay.py --session new-jdbc-run --semantics semantics
```

ID须尚未使用，结果目录必须全新。`check_stability.py`目前输出JSON判定，即使stable=false也返回0，复核必须阅读判定。完整原始JTL、逐ID导出、SQL计数器、资源、线程和运行日志本地保留；Git保存参数、汇总、实际计划、审计与必要摘录。汇总不替代原始逐请求分位数重算，也不把最终对账通过当持续稳定性通过。

本地完整原始包为`raw-results-20261013-capacity-scan.zip`与`raw-results-20261013-jdbc-session.zip`，位于上一级results目录。使用新建模式写入，保留两个未压缩原目录；没有覆盖旧基准包。文件数与原始/压缩字节数见[归档记录](../raw-20261013-archive-summary.json)。原始包不提交Git，从克隆仓库能复核汇总，逐请求重算须使用本地原始包或重新执行。
