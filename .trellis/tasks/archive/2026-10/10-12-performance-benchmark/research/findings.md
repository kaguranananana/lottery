# 源码与实测观察
- 旧 JMX 为网关 /api/lottery/draw、uId、无requestId，与本服务/api/v1/draw不符。旧SQL在两库写同用户并直接DOING，不执行。
- 独立栈与E2E原栈分开，身份通过Docker标签与127.0.0.1:13329确认；测试启动器调用生产创建/生命周期/XXL handler，无Mock。
- 初始化过程中修复启动器Hikari名称在首次连接才赋值、控制器缺参数名、Spring启动失败遗留消费者三个问题。无效结果保留在setup-diagnostics与20261012目录；干净重建后才开始20261012-final。
- c50首试使用零速率ConstantThroughputTimer导致实际49线程，保留诊断而不进入正式阶梯；c50-confirm为完整50线程。无等待与定速计划分离，定速改用单调时钟绝对时隙；每轮保存执行JMX。
- StrategyDetailVO.awardCount为Integer，PO字段为String，BeanUtils.copyProperties跳过，创建总库存NULL，剩余库存已正确初始化。只记录缺陷，不改生产代码。
- MySQL活动剩余库存当前未参与运行期扣减，须用Redis活动库存与持久参与数解释差异；奖品以Redis/MySQL/账本/订单逐项对账。
- 同机Docker MySQL2CPU，多schema仍共享同一引擎。50线程328.9新抽奖/s是入口短时均值，异步尾部136.5s，不能叫稳定全链路容量。
- Hikari无等待/超时，GC<1%，Redis CPU与执行时间低。MySQL接近2CPU配额、行锁等待增长；COMMIT和initializeCount耗时突出。strategy_detail无策略/奖品组合索引，UPDATE扫描PRIMARY；应在独立优化任务做索引与事务对照，不擅自放松持久化或关闭binlog。
