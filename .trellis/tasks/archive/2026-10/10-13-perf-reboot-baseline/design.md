# 方案

复用 preflight.sample/collect、environment.verify/sql、build/start/run/analyze/diagnostics/stages/check_stability。Docker 尚未运行时只采 macOS；Docker启动后保存全部实例身份及资源，不主动启动其它实例。只启动经过项目/service/端口确认的 lottery-perf 旧四容器，保留原 Volume 与数据。

JMeter /tmp 已丢失，寻找既有 5.6.3 包/安装位置，必要时恢复同版本工具，不升级。固定 JDK17、原应用/JMeter 堆、4/2 可用处理器、原数据库/消费者/调度配置。重新构建 b198ed6 的应用，构建与正式测量不重叠。

采用分阶段资源复核，应用空闲稳定后再 80 RPS。保存初始库存和实际数据量，每轮新活动和足量用户，不清库；前轮完整排空才下一轮。仅同速率复核，无业务修改和索引试验。出现持续换页时保存完整已接受业务并等待原链路排空，结束测试。

最终停止本轮启动的应用与专用四容器，60秒正常 MySQL 关闭，不改变其它实例及 Docker 设置。

收尾实际失败：stop.py要求comm以/java结尾，本轮macOS返回java，真实专用进程被拒绝。只修复工具文件的可执行文件名解析，保留PID和PerfHarness主类校验；真实进程旧guard失败/新guard通过，正常关闭后非专用进程仍拒绝。该修改发生于测量之后，不改变业务基线；单独提交工具修复与基线证据。
