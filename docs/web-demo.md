# 本地演示幸运转盘

已有 MySQL、Redis、RocketMQ 和运行中的活动时，在仓库根目录执行：

```bash
mvn package
java -jar lottery-interfaces/target/Lottery.war --server.address=127.0.0.1 --lottery.draw.local-demo-enabled=true
```

打开 [http://127.0.0.1:8080/](http://127.0.0.1:8080/)，默认账户为 `demo-a`。在「当前账户」下拉框选择 `demo-b` 或 `demo-c`。同一浏览器中的标签页共享会话，隐身窗口或独立浏览器配置可以同时演示另一账户。

演示模式只能在明确绑定 `127.0.0.1` 的应用中使用。只设置 `--lottery.draw.local-demo-user=demo` 和本机绑定时，页面沿用固定账户。两项一起设置时使用多账户模式。正式部署的身份由 Servlet Principal 提供。保持演示开关和固定账户参数关闭，并且不要把本机演示端口转发给外部访问者。

## 从空环境准备活动

下列步骤复用仓库的独立压测栈。需要 Docker Compose、JDK 17、Maven 和 Python 3。应用监听 `127.0.0.1:18190`。

```bash
python3 doc/performance/environment.py up
bash lottery-interfaces/src/test/resources/mq-e2e/create-topics.sh lottery-rocketmq-perf-broker-1 20911
# 仅首次启动的空库执行，从当前 Schema 初始化。
python3 doc/performance/environment.py init
bash doc/performance/build.sh
bash doc/performance/start.sh /tmp/lottery-web-demo --lottery.draw.local-demo-enabled=true
```

本功能不需要数据库迁移。若已有环境仍在 V8 Schema，启动当前库存分桶版本前应按 [V9 迁移说明](stock-bucket-rollout-20261115.md) 升级一次。

另开一个终端，创建两个独立奖池的活动。命令使用现有活动服务在启动活动时预热奖品库存。

```bash
curl --fail-with-body http://127.0.0.1:18190/__perf/setup \
  --data 'id=720101&stock=30&quota=2&users=0&prefix=demo-&awardRate=0.14' \
  --data-urlencode 'name=好运体验场'
curl --fail-with-body http://127.0.0.1:18190/__perf/setup \
  --data 'id=720102&stock=30&quota=4&users=0&prefix=demo-&awardKinds=2&awardRate=0.30' \
  --data-urlencode 'name=周末体验场'
```

打开 [http://127.0.0.1:18190/](http://127.0.0.1:18190/)。先用 `demo-a` 在好运体验场抽两次，再切到 `demo-b` 查看其完整额度。切回 `demo-a` 后，页面会显示已用完。切换到周末体验场，可看到另一组奖品和独立的活动额度。

活动持续 24 小时。需要新活动时换一个未使用的 `id`。结束演示时，在应用终端按 `Ctrl+C`，再执行 `python3 doc/performance/environment.py stop`。

## 页面上的结果

- 奖品卡显示所选活动的奖品名称、类型和可公开的描述。兑换码或券码作为发奖内容保存。转盘只用于揭晓，实际结果由抽奖接口决定。
- 中奖表示已创建订单，模拟发奖由后台处理。参与成功扣减次数后，即使结果还在处理，已使用次数也会反映这次参与。
- 请求超时或结果未知时，页面提示「恢复抽奖」。同一标签页刷新或切换回原账户和原活动后，会继续使用原请求号。
