# Lottery 开发记录

记录有实质内容的开发、调试和评估进展。每条写明实际改动、相关文件、实际执行的检查及结果、重要决定和后续事项；不记录普通问答或无实质内容的小改动。历史 Trellis 会话日志保留在 `.trellis/workspace/`。

## 2026-10-24 · 精简 Trellis 开发入口

- 将日常开发指引改为轻量方式，保留项目规范、历史任务和旧 journal；新增 `project-memory`，后续实质进展记录在 `docs/PROJECT_STATUS.md` 与本文件。
- 保留 `trellis-session-insight`、`trellis-spec-bootstrap`、`trellis-update-spec` 三个有明确用途的 Trellis 技能；移除强制阶段、重复入口及多代理流程技能，并停用自动注入阶段提示的 Codex hooks。
- 调整后端规范中关于 PRD 和纯 Trellis 配置验证的旧要求，使其符合轻量流程。
- 检查技能清单、活动指引与保留的历史资料引用；`git diff --check` 通过。未运行业务测试，因为本次仅调整开发文档、规范与本地 Agent 配置。

## 2026-10-24 · 故障评估后直接修复库存与事务链路

- 先阅读当前状态、近期记录及相关规范，保留工作区既有流程文档/配置改动。按用户授权直接修复确认的问题，未创建 Trellis 任务、未自动派发代理、未提交或推送。
- RedisUtil 增加单 Key Lua 条件扣减及仅现存 Key 回补；StrategyRepository 缺失库存不再返回售罄，ActivityRepository/StrategyRepository 回补异常继续传播。缺失奖品库存时保留原 take 与请求归属。
- StockReservationTransactions 观察 Spring afterCompletion，参与/订单异常仅在事务未开始或确认回滚时回补；回补失败附加到原异常，提交/回滚结果未知明确记录对账日志。BaseActivityPartake 将 ID 生成移到预扣前，业务拒绝回补保持原入口。
- SnowflakeIdGenerator 改为显式 worker/datacenter 配置，application.yml 暴露对应环境变量；TwoLevelCacheService 将事务内配置失效移到 afterCommit。更新相关规范、README、架构说明和本轮评估报告。
- 修复前实际运行：真实 Redis 6 项中 5 失败；真实 HTTP 新增 2 项均失败（缺失库存返回 D001、订单回滚后库存 9 而非 10）；节点配置 1 项失败；缓存事务时序 3 项均失败。活动名称筛选经真实 SQL 证实正常，撤回疑点且未修改 Mapper。
- 修复后实际运行：Java 8 `mvn clean package` 通过（45 项单元）；独立测试栈上的 MqStateCasManualIT 13、DrawRequestOwnershipManualIT 9、FinalBusinessE2eManualIT 17、MqMiddlewareE2eManualIT 9、RedisStockFailureManualIT 6 全部通过；合计 99 项，0 失败/错误/跳过。保留旧断言，SQL 专项补齐新仓储依赖并新增一次回补断言。
- 尚未解决：预扣后 JVM 崩溃的持久恢复、事务未知/回补失败的自动对账、真实外部发奖与高可用部署。不能把当前回补日志或本地 CAS 描述成跨存储恰好一次。报告及脱敏测试摘要见 `docs/failure-review-20261024.md`、`docs/validation/failure-review-20261024.json`。
- 收尾检查：`git diff --check`、本轮相关文档的本地链接检查通过；实际 Surefire 运行 JDK 为 1.8.0_482。已停止本轮 E2E 四项服务及 SQL 专项容器，未删除容器/数据卷，`docker ps` 无遗留运行容器。

## 2026-11-01 · 按优化后源码更新交互图解

- 保留四章与原入口，更新14幅Archify图及生成器，新增15-stock-recovery，解释参与/订单异常按Spring实际完成状态决定回补。修正奖品Key缺失被画成D001的旧结论，改为抛出并保留原take/requestId；同步Lua预扣/现存Key回补、takeId生成顺序、缓存afterCommit及Snowflake显式节点配置。
- 更新guide-data.js、sources.html、reference.html、README、验证记录和资源版本；共41个路径步骤、40个源码文件摘录。prepare_source_evidence.py将当前工作区复制到独立临时Git仓库冻结，覆盖尚未提交的优化，不修改项目Git历史或远端。
- 实际检查：15幅图完整finalize均通过，每幅9/9、0错误/警告且浏览器门禁通过；validate_resources.py为15幅/41步/2061链接通过；guide.js语法、Python脚本语法、15份候选再生成一致及30个图内引用文件与工作区逐字节一致通过。CUA核对导航、三条路径、Lua关系聚焦、深链、主题与新增源码锚点；02/08/15截图抽查通过，非全部图人工视觉审阅。
- 仅调整图解和文档，未改业务实现、SQL、配置或测试，未运行业务测试。保留预扣后JVM退出、事务结果未知、人工对账和模拟渠道边界；匿名浏览器宿主观察器日志与图解验证分开记录，细节见docs/visual-guide/verification.md。
- 收尾：guide-data.js语法通过；全量git diff --check仅报告Lifecycle生成的08/09 HTML各一行缩进空白，其余改动通过。已定位为模板无初始装饰时的空行，保留生成字节与回执一致，未手改产物、未放宽检查配置。

## 2026-11-02 · 取消活动总库存，保留奖品主链路

- 在既有未提交故障修复和图解改动上继续，保留原成果；没有重置、提交、推送或修改其它仓库。全局审计区分活动与奖品库存后，删除活动PO/VO/结果/Mapper字段、StockResult、Key、扣减/回补及专用响应码；BaseActivityPartake直达用户TX1，参与仓储解除活动库存异常回补耦合。
- ActivityRepository启动只预热奖品；活动行锁内校验状态/时间，再锁共享策略，SETNX保留实时余额；已有DOING/CLOSE共享池缺Key不自动回填，明确抛出并保持PASS。DrawExecutor、订单/策略仓储、Lua、MQ及关键Mapper等14文件与任务开始时逐字节一致，TX2/TX3、Outbox、账本与CAS保留。
- 新库基线四条活动种子改显式列并从PASS启动，压测种子/入口/JMeter/审计移除活动库存；V4仅删除旧库activity两列，不涉及用户分片、订单或账本。更新README、architecture、interview-architecture、请求幂等和长期规范；旧报告与11月1日图解保留为历史。未对业务库执行迁移。
- 实际执行：Corretto1.8.0_482下mvn compile及clean package，48项默认单元通过；ActivitySchemaMigrationManualIT 1、DrawRequestOwnershipManualIT 10、FinalBusinessE2eManualIT 22、MqStateCasManualIT 13、MqMiddlewareE2eManualIT 9、RedisStockFailureManualIT 6全通过，共109项，0失败/错误/跳过。真实MySQL/Redis/Kafka覆盖两库八表、个人次数竞争/回滚、幂等/归属、奖品售罄仍扣次数、共享池、账本/Outbox/迟到通知与故障恢复。活动种子最后调整为PASS后又定向执行迁移1项通过，不重复累计。首轮testCompile发现两处过期活动Key断言，按新语义改为奖品Key后完整构建通过；没有禁用或放宽测试。
- 另验证压测Java入口/计时类的Java8编译、7个Python文件语法、3个JMX及2个Mapper解析、guide-data.js语法、163处本地文档链接与本任务新增行空白。全量git diff --check保留任务前已有08/09生成HTML各一处尾随空白，其它路径通过；未手改产物破坏原回执。完整命令、摘要及剩余边界见docs/activity-stock-removal.md和验证JSON。
- 后续：JVM预扣崩溃、事务未知/回补失败与BLOCKED仍需持久预留或人工对账；外部渠道/调度/高可用和当前版本容量未验证。旧图解未重绘。测试栈使用专用13309/16379/19092及SQL13319；收尾停止本轮启动的容器，不删除容器或数据卷。

## 2026-11-02 · 用户授权自动提交与推送

- 用户在真实链路验证后明确授权本次及后续已验证业务链路默认提交、推送，已在AGENTS.md持久记录；无需重复确认，不强推，仍保留无关本地修改。
- 提交范围包含本次活动总库存重构及109项验证依赖的业务前置修复/测试，保留当前远端历史。原有Agent配置清理和图解重绘不纳入；图解只发布历史版本提示，远端保留原有快照，本地15幅优化版仍待单独交付。
- 本阶段只变更交付偏好、说明和Git提交范围，未新增业务行为，未重复启动中间件或重跑已通过的109项测试。
- 交付结果：相关业务提交已通过HTTPS推送GitHub origin/main，未强推；已验证业务源码、配置、测试和SQL与提交一致。SSH通道超时后改用同仓库HTTPS，未修改origin配置。

## 2026-11-11 · 可点击的抽奖网页

- `static/index.html` 与 `static/draw/` 随 Spring Boot WAR 提供首页。转盘只负责揭晓，活动和结果来自现有 API；页面在发送前保存请求号，支持丢失响应、D007 和活动退出列表后的原请求恢复。中奖显示奖品名称和订单已生成状态。
- `PerfHarness.java` 的隔离活动创建入口可接收演示文案和单奖品概率，原压测默认值保持不变。用生产活动服务创建、预热并启动「好运体验场」；新增 `docs/web-demo.md` 启动步骤和 README 实拍预览。未操作现有业务数据库。
- 实际执行 Java 8 `mvn clean package` 和更新静态样式后的 `mvn package`，各 48 项默认测试通过；检查 WAR 资源并实际启动 WAR 访问首页与活动列表。JS 和两个 SVG 语法检查通过。
- Chrome 对真实 MySQL/Redis/Kafka 测试栈验证了活动空态/加载失败/刷新、中奖、未中奖、机会用完、响应丢失后刷新回放、D007 和已退出列表活动恢复；三笔中奖订单的账本各一条，库存、消息与模拟发奖状态已完成。验证 390×844 手机首屏按钮、键盘触发及减少动态效果设置。临时核验活动已到期，本机演示服务继续运行。
