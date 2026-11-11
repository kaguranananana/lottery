# 本地演示幸运转盘

如果后端已启动、库里有处于运行状态且尚未结束的活动，直接打开 [http://localhost:8080/](http://localhost:8080/)。选择活动后可以沿用默认游客 ID，点击「开始抽奖」。前端随 `Lottery.war` 打包，与 `/api/v1/activities` 和 `/api/v1/draw` 同源，不需要 Node 或单独的前端服务。

## 从空环境准备一场演示

下列步骤复用仓库已有的独立压测环境，应用和中间件只绑定本机。需要 Docker Compose、JDK 17、Maven 和 Python 3；Maven 路由依赖见 [主 README](../README.md#环境依赖)。普通本地服务用 `8080`，演示环境用 `18090`。

在仓库根目录执行：

```bash
python3 doc/performance/environment.py up
# 只在首次启动的空库执行；已初始化的演示环境跳过 init。
python3 doc/performance/environment.py init
bash doc/performance/build.sh
bash doc/performance/start.sh /tmp/lottery-web-demo
```

应用启动后，在另一个终端创建一场活动。每位游客有 8 次机会；五种模拟券的总中奖概率为 70%。命令会调用现有的活动创建与启动服务，在活动进入运行状态前预热 Redis 奖品库存。

```bash
curl --fail-with-body http://127.0.0.1:18090/__perf/setup \
  --data 'id=720101&stock=30&quota=8&users=0&prefix=guest-&awardRate=0.14' \
  --data-urlencode 'name=好运体验场' \
  --data-urlencode 'description=每位游客有 8 次机会，奖品为模拟券。'
```

打开 [http://127.0.0.1:18090/](http://127.0.0.1:18090/)，点击「开始抽奖」。活动持续 24 小时；到期后可用尚未占用的新 `id` 创建另一场。`/__perf/setup` 仅在独立演示启动器中提供。结束演示时，在运行应用的终端按 `Ctrl+C`，再运行 `python3 doc/performance/environment.py stop`，下次可保留数据继续使用。

## 页面上的结果

- 中奖表示已创建中奖订单；模拟发奖由后台处理。
- 请求超时或结果未知时，页面会提示「恢复抽奖」。同一标签页刷新后仍会保留原用户、活动和请求号，点击恢复不会开启新一轮。
- 「暂无活动」表示当前没有可参与的活动；「活动加载失败」表示暂时取不到列表。两种情况都可点击「刷新活动」。
