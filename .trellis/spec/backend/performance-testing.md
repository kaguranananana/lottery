# 真实链路性能压测契约

## 1. 范围与触发

新增/修改性能脚本时阅读。实现位于doc/performance；直接执行生产HTTP、Redis、MySQL路由/事务、Outbox与Kafka组件，不把Mock或历史JMX作为实测证据。报告见[性能报告](../../../doc/performance/report.md)。

## 2. 命令与接口

`environment.py up|init|stop|clean`管理lottery-perf。`run.py --session --name --id --threads --duration --warmup --target-rps --jmeter`执行独立轮次，`analyze.py`核对业务关联，`diagnostics.py`计算资源/SQL增量，`check_stability.py`判断持续测试，`semantics.py --session --name --id`验证幂等。`stop.py <结果目录>`按保存PID核对进程后停止应用。

`preflight.py <新目录> [--seconds 60 --interval 5]` 只读记录宿主与容器资源。`mysql_diagnosis.py <新目录>` 对已启动且通过原身份校验的专用 MySQL 提取生产扫描 SQL，保存精确体量、八表计划、三次 ANALYZE 与 MySQL 事实核对。两者不自动启停/清理实例。

生产请求仍为`POST /api/v1/draw {userId,activityId,requestId}`；测试控制面`/__perf/setup`、`/__perf/pending`、`/__perf/metrics`、`/__perf/lag`只在独立测试启动器装配，不能纳入生产Controller。

## 3. 数据与环境契约

MySQL/Redis/Kafka专用端口13329/17379/29092；应用18090绑定127.0.0.1。init只接受空schema，实际执行Outbox/V2/V3迁移；清理须核实Docker项目/service/端口。通过生产活动创建与PASS→DOING初始化Redis，不直接写DOING或回填运行期库存。用户按生产路由放入一个正确分库，每次新操作用新requestId。

XXL handler由一秒固定延迟触发，消费者真实运行；该方式不证明admin调度。本地模拟发奖不证明外部副作用。RPS按完成时间计算，业务码与HTTP状态分开，异步时间戳只有秒精度。原始JTL、参数、执行JMX、指标和ID关联必须保留。

预检保存 vm_stat 原文并从输出读取页大小，计算连续 Swap-in/out 增量；保存背景实例限制与端口，不记录环境变量凭据。mysql_diagnosis 的耗时来自 MySQL 8 执行树根节点，最深层索引迭代器行数不是 SQL digest 的 rows_examined；逐表重复的耗时不得换算为完整链路收益。MySQL 核对明确不包含 Redis/Kafka。

## 4. 校验与错误

| 条件 | 行为 |
|---|---|
| 专用实例身份不符/非空初始化 | 明确失败，不删数据 |
| 预检时长/间隔非正值或输出目录已存在 | 参数报错或目录异常，禁止覆盖旧证据 |
| 资源采集或 SQL 诊断命令失败 | 原始已完成文件保留，异常直接传播，不改写成通过 |
| 应用端口占用/Spring启动失败 | 不建立第二个消费者实例；失败进程退出 |
| 正常基准D004/D006/D007 | 单列拒绝并说明原因，不计新抽奖成功 |
| 实际线程数不符/请求或订单关联不符 | 分析失败，不进入正式曲线 |
| 采样/任务失败或异步未收敛 | 保存错误/堆栈/积压，非零退出 |
| 分库没有次数行 | SQL聚合COALESCE(SUM(...),0)，事实为零，不让NULL污染指标 |

`stop.py` 从保存PID读取macOS `ps comm,args`，按可执行文件名等于java且参数含专用PerfHarness主类核对身份后才SIGTERM。真实macOS案例中comm只返回java，不能要求固定的/java后缀；进程名解析不得取消主类校验或允许任意PID停止。

## 5. 正常、基线与失败场景

基线：同配置先预热，再采集固定测量窗口，上一轮全部收敛后再开下一轮。正常：新请求对应一条take、中奖对应一条order及一条账本。幂等：D006/D007及原ID恢复，重放不多扣次数。有限库存：活动D004与奖品D001分开，不把拒绝RPS当容量。失败：速率未达到或积压超阈值，仍保留完整实际数字，不改标准让结果通过。

环境停止案例：专用栈停止时仍主动换页，且无法在授权范围消除共享负载，则保留资源观察并停止容量/A/B 实验。停止前未运行的基线/升压标记未执行，不能写成业务稳定性失败或通过。2026-10-13 当前补偿空扫描实际读取全部 341,311 历史订单后返回零行；优化方案尚未验证，见新一轮报告。

## 6. 验证断言

逐ID核对HTTP→take→order→ledger；参与数等于次数消耗与Redis活动消耗，各奖品Redis/MySQL消耗等于中奖订单与账本。检查重复订单、重复take订单、未消费记录、负库存、BLOCKED、最终消息/发奖状态及Kafka八分区Lag。稳定标准在持续测试前写定，入口峰值和最大通过的端到端速率分别报告。

只读工具验证检查：Swap 汇总等于首末原始计数增量；精确订单数量与实际扫描行数可核对；计划 SQL 与 Mapper 条件一致；持久化与业务计数前后一致。仅 MySQL 核对不得标记完整链路通过。

首次压测创建缺陷：StrategyDetailVO.awardCount为Integer、PO为String，BeanUtils不复制，总库存列NULL；剩余库存正常初始化。独立优化任务已统一创建/PO/快照总库存为Integer，并验证总/剩余字段；原始压测数据没有回填。活动MySQL剩余库存未实时扣减，消耗需由Redis与take事实解释。

后续定向诊断与源码优化见[独立优化契约](./performance-optimization.md)和[优化报告](../../../doc/performance/optimization-report.md)。测试启动器增加包含式阶段和客户端 SQL 累计计时，资源采样同时保存实际锁索引/数据与主动换页；不能将嵌套阶段、数据库 CPU、锁累计时间相加解释为墙钟耗时。

## 7. 错误与正确示例

错误：`HTTP 200数量/秒=成功抽奖容量`，或者Redis库存缺失时只改MySQL并直接DOING。

正确：`0000/D001的唯一(userId,requestId)`为新抽奖；运行前由生产生命周期SETNX预热，结束后按ID与库存/账本对账。若最高通过档位与上方失败档位有间隔，给出区间，不声称精确生产容量。

错误：把重复只读扫描的缓存差异写成优化收益，或因本轮未能测试而拿旧 80 RPS 记录认证新的最大吞吐。正确：保存真实计划/换页窗口，区分历史候选、新诊断和未执行项；先在可信环境验证修复，再报告容量边界。
