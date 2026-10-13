# 资源停止与扫描计划新证据

基线 main 34cd882。专用栈起初全部停止，五个背景实例运行且无 CPU/内存上限，禁止修改。两段约 60 秒空闲 Swap-in 33.58 / 81.75 MiB；只启动专用 MySQL 的只读观察 Swap-in/out 597 / 178.45 MiB。共享 CPU/Swap 条件变化不可控制，不进行容量与 A/B 认证。

精确历史量：341311 订单/唯一账本、381439 参与、2424009 次数行。MySQL 8.0.46，2 CPU/2 GiB，128 MiB buffer pool，RR，持久参数 1/1。两个读取批次数据一致，次数消耗等于参与，无状态未完成、BLOCKED、负库存、负次数或订单关联缺失。仅 MySQL，不含 Redis/Kafka。

生产 scanInvoiceMqState 八表实际使用 idx_stock_mq_state_create_time 定位 stock_mq_state=1，再过滤 grant_state!=1。现存所有订单为 stock/mq/grant=1/1/1，因此每轮扫描全部 341311 行后返回零；LIMIT 100 不限制底层空扫描量。库存 Outbox 在无积压时读零行。两次采集各三次 ANALYZE，工作量结果一致；耗时受缓存/顺序与资源影响，不作优化比例。没有部署旧 mq/create 索引或新索引。

消费者两次读订单、账本持久事务、完成状态写入、发送回调数据库 CAS、claim/enqueue 的真实职责保留。下一轮可信环境先80 RPS原版复测，再单变量验证补偿定位改进及状态维护写成本。不能拿只读零锁等待推断压力下无锁竞争；没有新阶段耗时、新稳定容量或业务优化。

正式交付见 optimization-report.md 与 20261013-rediagnosis 证据索引。原始文件/ZIP 本地保留，摘要与工具提交 Git，任务日志不自动提交。
