# 2026-10-13 持续容量探索与补偿索引证据

源码基线4b93c95，保留原lottery-perf四容器及历史数据，原五背景容器始终停止。原稳定标准、持久化1/1、128MiB buffer pool、堆/池/消费并发/调度均未调整。本目录完整原始采样本地保留；本轮尚有后续驱动诊断，不能把180秒候选称为已完成300秒最高容量确认。

## 实际负载结果

同为30线程、20秒预热、180秒测量、10万预置用户、全中奖与原库存fixture。每轮全部审计并排空才下一轮，HTTP失败不等同容量失败；表内稳定列为原check_stability.py九项判断。

| 轮次 | 新抽奖RPS | HTTP P95/P99 ms | 异步P95上界 ms | 发奖采样积压峰值 | 排空秒 | 稳定 |
|---|---:|---:|---:|---:|---:|---|
| capacity0080-base | 80.006 | 23/29 | 2219 | 101 | 0.316 | 通过 |
| capacity0080-index | 80.000 | 21/26 | 1924 | 85 | 0.302 | 通过 |
| capacity0080-reverse | 80.000 | 19/23 | 2165 | 102 | 0.383 | 通过 |
| capacity0100-index | 99.994 | 18/21 | 1919 | 109 | 0.408 | 通过 |
| capacity0150-index | 149.906 | 34/117 | 2998 | 336 | 0.400 | 通过 |
| capacity0200-index | 199.828 | 39/50 | 23891 | 4440 | 19.020 | 未通过 |

三轮80RPS依次为：原版无新增索引、idx_invoice_grant_state可见、同一索引不可见但仍被维护。用户前缀相同、每活动独立次数；初始订单382935，随后398941/414946/430953，不删除历史。新索引从第二轮开始存在，反向轮验证读路径，不是完全移除索引的旧实现。没有据此声称HTTP延迟或整体吞吐提升倍数。

## 可核验的局部因果收益

- `scan-comparison-final`：应用停止后，相同430953订单、计数及状态分布，六轮交替原条件，原路径每轮读430953行、返回0，新路径读0、返回0。八表根节点合计原191.3~201.5ms、新0.0952~0.1015ms；原始Swap端点增量0/0。这是SQL局部对照，不是handler/HTTP/CPU提升倍数。
- `index-comparison-summary.json`：80RPS补偿handler均值248.87→6.15→249.56ms；SQL digest全轮examined行62528773→15360→68075415。正常负载仍读取未发奖义务，不保证任意积压规模恒定成本。
- MySQL CPU均值55.06→33.51→50.94%（单核100%）。全轮Swap-in9.625/7.438/27.578MiB，均无Swap-out；历史增长、环境波动如实记录，不将精确CPU比例归为纯代码效应。
- 八表单列非唯一索引初始合计776个16KiB分配页，约12.125MiB；这是磁盘分配页，不是进程内存。只索引grant_state，避免stock状态多次推进额外维护组合列，插入和发奖仍有维护成本。

## 正确性与范围

`index-validation.json`记录默认35项、真实索引扫描2项、真实次数/库存/映射6项，共43项通过。新测试使用生产Mapper/BoundSql与迁移DDL，验证5000已完成订单空扫描，以及INIT、FAIL、SENT、旧COMPLETE超时、grant_state=2、库存未结算/BLOCKED、已发奖与NULL；不以毫秒门槛替代读取量断言。

六个基准轮每轮逐ID核对HTTP、take、order、唯一账本、次数、Redis/MySQL奖品消耗、COMPLETE/本地发奖与Kafka八分区位点，审计均通过。200RPS异步稳定性未通过，最终39971新订单一致性仍通过；stock实际消费40041包含70条安全重投，不把位点当唯一业务数。第三方渠道、broker HA和XXL admin调度未覆盖。

## 200 RPS 后续瓶颈诊断

同步199.828RPS、P99 50ms；发奖积压峰值4440、首尾增长3491.57单、异步P95上界23891ms、排空19.020秒，四项异步阈值失败。Swap-in仅0.5625MiB、无Swap-out，连接池无等待/超时，不能把这次失败归给宿主机换页。

三个jcmd线程快照均显示唯一Kafka producer网络线程执行状态回调，在MySQL套接字读取；其中一次明确处于ConnectionImpl.isReadOnly→queryServerVariable。诊断发生在积压已超过阈值后，可能带来短暂停顿；后续受控复测须使用无线程探针轮次。回调SQL累计约186.9秒（包含预热/排空），不等于CPU占比。MySQL均值139.08%、Kafka87.49%，尚不能仅凭均值认定CPU配额是唯一根因。8.0.28本地字节码、只读状态查询digest和后续会话缓存实验分开保存，当前没有驱动配置优化收益结论。

## 资源复查与失败事件

初始空闲1MiB/0。恢复后34.25/0和342.59/34.19两窗口，后者339.52MiB集中前5秒。初步停止后用户要求继续复核；重启专用应用后60秒6.55/0，压力level1、可用读数71%，随后正式测量没有持续Swap-out。不能写成全程零换页，也不能把一次突发直接等同持续资源恶化。

用户授权结束明确多余进程；检查仅有本轮PerfHarness，无残留Maven/JMeter，ChatGPT是当前Codex宿主及前台应用，Docker VM是必需负载，因此没有无依据终止其它进程。原五容器身份/状态/挂载/限制保持原样。

新测试初次编译因SqlSessionTemplate导入包名错误失败，修复后实际SQL门禁通过，原日志保留。首批SQL对照与单元门禁有短暂重叠，耗时排除；使用无重叠confirm与final批次。没有把失败记录改成通过，也没有改稳定阈值。

## 复现索引实验

先按performance README核实专用实例并保留数据，停止应用且排空义务。已有实例在核实无同名索引后，使用environment.sql执行doc/sql/20261013_invoice_compensation_index.sql；新空环境init已包括该迁移，不对已有库执行init/clean。

```bash
mvn test -Dtest=InvoiceCompensationIndexManualIT,PerformanceSqlManualIT -Dsurefire.failIfNoSpecifiedTests=false -Dlottery.perf.mysql=jdbc:mysql://127.0.0.1:13329/
python3 doc/performance/invoice_scan_compare.py doc/performance/results/new-repeat/sql --repeats 6
# 同负载run/analyze/diagnostics/stages/check_stability沿用performance README。
```

反向实验只在已排空且应用停止时将八表新增索引ALTER INDEX INVISIBLE，之后必须恢复VISIBLE并核实八表。idx、唯一约束和业务行不删除。原始SQL/执行树、JTL/日志/逐ID导出/资源/线程快照保留本地；Git仅保存参数、汇总、计划、审计与必要摘录。
