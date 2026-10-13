# 边界与设计
历史 JMX 路径、uId 字段、缺 requestId 与当前接口不符，旧 SQL 双库重复用户且直接 DOING，无预热，不执行。
最小缺口位于压测基础设施：doc/performance 新增 Docker compose、schema 提取脚本、独立 Java 测试启动器、JMeter 与自动采样分析。
启动器仅额外装配测试控制面（loopback），调用现有活动创建/生命周期服务；预置用户通过实际 IDBRouterStrategy 路由。现有 XXL handler 周期调用，保留生产实现与参数；消费者真实运行。不改变业务代码、池大小、概率算法或缓存默认设置。
独立活动每轮100万库存、100万奖品库存，五种模拟券概率合计100%，用户每请求独立；预热/测量分期。有限库存另建活动。原始样本含业务码和 take/order。
固定 -Xms512m -Xmx1024m、ActiveProcessorCount=4；JMeter 与应用运行宿主机，中间件独立Docker，报告同机资源争用与旧容器背景负载。
