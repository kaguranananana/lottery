"""按已核对的源码生成 Archify 规格；只写本图解目录，不修改业务文件。"""
import json
import subprocess
import sys
from pathlib import Path

# 以脚本所在位置定位资源目录，避免依赖执行者的当前工作目录。
OUT = Path(__file__).resolve().parent
# 项目根目录用于定位源码以及读取 Git 的版本身份。
ROOT = OUT.parent.parent
REV = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
ORIGIN = subprocess.check_output(["git", "remote", "get-url", "origin"], cwd=ROOT, text=True).strip()

# 名称映射只覆盖已经阅读的源码，每条图解引用仍指定真实行号。
FILES = {
    p.name: str(p.relative_to(ROOT))
    for p in ROOT.glob("lottery-*/src/**/*") if p.is_file()
}
FILES.update({
    "root-pom": "pom.xml", "interface-pom": "lottery-interfaces/pom.xml",
    "domain-pom": "lottery-domain/pom.xml", "architecture": "docs/architecture.md",
    "ownership-sql": "docs/sql/V3__draw_request_ownership.sql",
    "ledger-sql": "docs/sql/V2__architecture_simplification.sql",
    "final-report": "docs/final-correctness-e2e.md",
})

def source(name, start, end, label=None):
    """保存相对路径和行号，交给 Archify 核对版本内的源文件。"""
    return {"path": FILES[name], "line": start, "end_line": end,
            "label": label or (name if name.endswith((".java", ".xml", ".yml")) else Path(FILES[name]).name)}

def meta(slug, title):
    """每幅图独立保存；SSH 来源使用本地证据模式。"""
    return {"title": title, "locale": "zh-CN", "quality_profile": "showcase",
            "output": f"docs/visual-guide/diagrams/{slug}/{slug}.html",
            "repository": {"url": ORIGIN, "revision": REV, "link_mode": "local-only"}}

def card(title, *items, dot="cyan"):
    return {"title": title, "dot": dot, "items": list(items)}

def node(nid, label, sub, x, y, sources, typ="backend", width=250):
    return {"id": nid, "type": typ, "label": label, "sublabel": sub,
            "pos": [x, y], "size": [width, 78], "sources": sources}

def edge(a, b, label, variant="default", **kwargs):
    return {"id": f"{a}-to-{b}", "from": a, "to": b, "label": label,
            "variant": variant, **kwargs}

def participant(pid, label, sub, refs, typ="backend"):
    return {"id": pid, "type": typ, "label": label, "sublabel": sub, "sources": refs}

def sequence(slug, title, participants, messages, cards):
    """纵向编号表达先后，箭头类型区分调用、读取、写入、消息和返回。"""
    seq_meta = meta(slug, title)
    seq_meta["legend"] = {"entries": {
        "default": {"label": "方法调用"}, "security": {"label": "数据写入 / 并发锁"},
        "emphasis": {"label": "数据读取"}, "dashed": {"label": "消息发送 / 投递"},
        "return": {"label": "结果返回 / ACK"},
    }}
    # 按已声明的存储/消息角色区分写入和投递，避免同一种线型承载两种副作用。
    store_ids = {item["id"] for item in participants if item["type"] == "database"}
    bus_ids = {item["id"] for item in participants if item["type"] == "messagebus"}
    compiled_messages = []
    for i, (mid, sender, receiver, label, variant) in enumerate(messages):
        # 写入存储使用写入色；Kafka 发送继续使用独立虚线。
        if variant == "dashed" and receiver in store_ids:
            variant = "security"
        # Broker 交付消息是异步投递，与普通 Java 方法调用分开显示。
        if variant == "default" and sender in bus_ids:
            variant = "dashed"
        # 保持所有消息的编号、端点、顺序与源码意义不变。
        compiled_messages.append({"id": mid, "from": sender, "to": receiver, "y": 170 + i * 46,
                                  "label": label, "variant": variant})
    return {"schema_version": 1, "diagram_type": "sequence", "meta": seq_meta,
            "participants": participants,
            "messages": compiled_messages,
            "cards": cards}

def wfnode(nid, lane, col, label, sub, refs, typ="backend"):
    return {"id": nid, "lane": lane, "col": col, "type": typ, "label": label,
            "sublabel": sub, "width": 150, "height": 66, "sources": refs}

def workflow(slug, title, lanes, nodes, edges, main, cards):
    workflow_meta = meta(slug, title)
    # 图例使用抽奖业务职责，不沿用工具调用示例中的 Agent / Context 名称。
    workflow_meta["legend"] = {"entries": {
        "backend": {"label": "业务处理"}, "database": {"label": "数据存储"},
        "messagebus": {"label": "消息处理"}, "external": {"label": "进程中断 / 人工"}
    }}
    return {"schema_version": 2, "diagram_type": "workflow", "meta": workflow_meta,
            "lanes": [{"id": lid, "label": label} for lid, label in lanes],
            "nodes": nodes, "edges": edges, "mainPath": main, "cards": cards}

# 图 1 仅表达代码模块与依赖方向，存储和运行组件留给图 2。
diagrams = []
diagrams.append({"schema_version": 1, "diagram_type": "architecture",
    "meta": meta("01-layers", "01 · 代码分层：谁编排，谁决定，谁落地"),
    "components": [
        node("interfaces", "lottery-interfaces", "HTTP 校验、DTO 转换、启动装配", 80, 100,
             [source("LotteryController.java", 24, 53), source("interface-pom", 19, 70)]),
        node("application", "lottery-application", "抽奖用例、Kafka 收发、XXL-Job", 550, 100,
             [source("ActivityProcessImpl.java", 62, 180), source("LotteryXxlJob.java", 193, 229)]),
        node("infrastructure", "lottery-infrastructure", "Repository 实现、事务、路由、缓存", 80, 370,
             [source("OrderRepository.java", 38, 65), source("StrategyRepository.java", 67, 145)]),
        node("domain", "lottery-domain", "资格、抽样、订单规则、仓储接口", 550, 370,
             [source("BaseActivityPartake.java", 23, 72), source("DrawExecutor.java", 31, 61),
              source("AwardOrderImpl.java", 38, 58)]),
        node("common", "lottery-common", "业务码、状态枚举、RedisKey", 550, 620,
             [source("Constants.java", 10, 23), source("Constants.java", 297, 307)]),
    ],
    "connections": [edge("interfaces", "application", "调用用例", "emphasis"),
        edge("application", "domain", "编排领域服务"),
        edge("interfaces", "infrastructure", "装配实现", "dashed"),
        edge("infrastructure", "domain", "实现领域端口"),
        edge("domain", "common", "复用值类型")],
    "boundaries": [{"kind": "region", "label": "代码模块 · 同一 Lottery 应用内的职责划分",
                    "wraps": ["interfaces", "application", "domain", "infrastructure", "common"], "pad": 28}],
    "cards": [card("依赖与执行方向", "箭头表达主要代码关系。领域层定义 Repository 接口，基础设施实现接口。",
                    "运行时领域调用注入的仓储对象，执行会进入 infrastructure。interfaces 的 pom 也直接依赖 domain。"),
              card("当前源码的边界", "五个 Maven 模块不是五个微服务。启动模块将它们装入同一应用。",
                   "Mapper XML 物理存放在 interfaces 资源目录，仍承担持久化 SQL 职责。",
                   "domain 仍有 Spring 注解、Redis 配置及路由 Maven 依赖，不能说完全脱离框架。", dot="amber")]
})

# 图 2 展示运行中的参与者；库内表的具体归属由侧栏展开。
diagrams.append({"schema_version": 1, "diagram_type": "architecture",
    "meta": meta("02-runtime", "02 · 运行组件：一个应用与外部服务"),
    "components": [
        node("client", "客户端", "调用 HTTP 抽奖接口", 60, 300,
             [source("LotteryController.java", 27, 44)], "external", 190),
        node("lottery", "Lottery 应用", "HTTP / Job / Producer / Listener", 480, 300,
             [source("LotteryController.java", 27, 44), source("LotteryXxlJob.java", 193, 229),
              source("LotteryInvoiceListener.java", 57, 107)], width=280),
        node("redis", "Redis", "实时库存、配置缓存、短期锁", 480, 90,
             [source("ActivityRepository.java", 190, 225), source("RedisActivityDrawProcessGuard.java", 24, 45)], "database", 280),
        node("mysql", "MySQL", "db00 配置/账本；db01/02 用户数据", 980, 300,
             [source("OrderRepository.java", 38, 65), source("StrategyRepository.java", 87, 110),
              source("IUserStrategyExportDao.java", 16, 24)], "database", 280),
        node("kafka", "Kafka", "库存结算与发奖两个 topic", 480, 600,
             [source("KafkaProducer.java", 18, 41), source("LotteryStrategyAwardStockListener.java", 50, 53)], "messagebus", 280),
        node("xxl", "XXL-Job 调度中心", "触发应用内三类 Job Handler", 980, 600,
             [source("LotteryXxlJob.java", 61, 62), source("LotteryXxlJob.java", 115, 116),
              source("LotteryXxlJob.java", 193, 194)], "external", 280),
    ],
    "connections": [edge("client", "lottery", "HTTP 请求", "emphasis"),
        edge("lottery", "redis", "GET / DECR / SETNX"),
        edge("lottery", "mysql", "经仓储执行 SQL"),
        edge("xxl", "lottery", "触发 Job"),
        edge("lottery", "kafka", "生产消息", "dashed"),
        edge("kafka", "lottery", "订阅消费", "dashed")],
    "cards": [card("进程内的组件", "Caffeine 仅保存 StrategySnapshot 的本机 L1；不是独立缓存服务。",
                    "dbRouterForGem 是 JDBC/MyBatis 路由依赖，不是网络网关或数据库。路由细节在仓储层。"),
              card("存储的两类事实", "Redis 的库存数控制运行期可售数量。MySQL 用户库保存参与、次数与中奖订单。",
                   "MySQL 全局库保存配置、strategy_detail 剩余奖品库存和 award_stock_settlement。", dot="emerald"),
              card("已确认的边界", "当前应用没有同步发 Kafka；创建订单后的 Outbox 由 Job 驱动。",
                   "真实网关与部署栈在相邻仓库，本图从当前 Controller 开始。发奖渠道仍为模拟。", dot="amber")]
})

# 参与链路以缓存命中的正常请求为例；缓存未命中条件在说明中展开。
diagrams.append(sequence("03-participation", "03 · 同步上半程：从 HTTP 到一条参与记录", [
    participant("client", "客户端", "外部请求", [source("DrawRequest.java", 11, 23)], "external"),
    participant("http", "HTTP 入口", "interfaces", [source("LotteryController.java", 37, 53)]),
    participant("app", "用例编排", "application", [source("ActivityProcessImpl.java", 62, 127)]),
    participant("partake", "参与规则", "domain", [source("BaseActivityPartake.java", 23, 72)]),
    participant("repo", "参与仓储", "infrastructure", [source("UserTakeActivityRepository.java", 38, 118), source("ActivityRepository.java", 150, 225)]),
    participant("redis", "Redis", "库存 / 配置", [source("ActivityRepository.java", 150, 225)], "database"),
    participant("userdb", "用户库", "db01 或 db02", [source("UserTakeActivity_Mapper.xml", 19, 47), source("UserTakeActivityCount_Mapper.xml", 13, 32)], "database"),
], [
    ("s01", "client", "http", "01 POST /draw 三个字段", "default"),
    ("s02", "http", "app", "02 userId → uId；requestId 保留", "default"),
    ("s03", "app", "redis", "03 经 Guard：SET NX token，3s", "security"),
    ("s04", "app", "repo", "04 查 uId + activityId + requestId", "emphasis"),
    ("s04sql", "repo", "userdb", "SELECT request_id 对应 take", "emphasis"),
    ("s04return", "userdb", "repo", "首次请求：无记录", "return"),
    ("s05", "app", "partake", "05 doPartake(PartakeReq)", "default"),
    ("s05pending", "partake", "repo", "查该用户活动的未消费 take", "emphasis"),
    ("s05sql", "repo", "userdb", "SELECT state=0；正常无记录", "emphasis"),
    ("s06", "partake", "repo", "06 活动单据 / 资格校验", "default"),
    ("s06cache", "repo", "redis", "GET 活动配置缓存", "emphasis"),
    ("s06back", "redis", "repo", "活动配置缓存命中", "return"),
    ("s06domain", "repo", "partake", "返回状态、时间、策略、次数", "return"),
    ("s07", "partake", "repo", "07 预扣活动库存", "default"),
    ("s07redis", "repo", "redis", "GET 存在且 >0 → DECR 1", "dashed"),
    ("s08", "partake", "repo", "08 Snowflake takeId + requestId", "default"),
    ("s08tx", "repo", "userdb", "TX1：次数-1 + INSERT take(state=0)", "dashed"),
    ("s08commit", "userdb", "repo", "TX1 提交；失败整体回滚", "return"),
    ("s08back", "partake", "app", "strategyId / takeId / 活动剩余量", "return"),
], [card("教学前提", "这是一条首次请求且最终中奖的正常路径：活动 DOING、时间有效、库存已预热、配置缓存命中。",
             "缓存未命中可从 MySQL 加载配置；运行期库存 Key 丢失则拒绝，二者不是同一种 miss。"),
    card("TX1 的作用", "次数扣减和 take 创建在同一用户分库事务内。Redis DECR 在事务外。",
         "创建失败回补已预扣的活动库存；回补错误仅记日志，不能宣称 Redis/MySQL 全链路原子。", dot="amber")]))

diagrams.append(sequence("04-draw-order", "04 · 同步下半程：选奖品、预扣、建订单、返回", [
    participant("http", "HTTP 入口", "interfaces", [source("LotteryController.java", 72, 96)]),
    participant("app", "用例编排", "application", [source("ActivityProcessImpl.java", 129, 179)]),
    participant("draw", "抽奖规则", "domain", [source("DrawExecutor.java", 31, 65)]),
    participant("strategy", "策略仓储", "infrastructure", [source("StrategyRepository.java", 67, 84), source("StrategyRepository.java", 117, 145)]),
    participant("redis", "Redis", "奖品实时库存", [source("StrategyRepository.java", 117, 145)], "database"),
    participant("order", "订单规则", "domain", [source("AwardOrderImpl.java", 38, 58), source("AwardOrderImpl.java", 107, 124)]),
    participant("repo", "订单仓储", "infrastructure", [source("OrderRepository.java", 38, 65)]),
    participant("userdb", "用户库", "订单分表 / take", [source("UserStrategyExport_Mapper.xml", 27, 35), source("UserTakeActivity_Mapper.xml", 28, 30)], "database"),
], [
    ("s09", "app", "draw", "09 DrawReq{uId,strategyId,uuid=takeId}", "default"),
    ("s09snapshot", "draw", "strategy", "queryStrategySnapshot(strategyId)", "emphasis"),
    ("s09back", "strategy", "draw", "快照：权重前缀和 + 奖品详情", "return"),
    ("s10", "draw", "strategy", "10 采样命中 awardId → 预扣", "default"),
    ("s10redis", "strategy", "redis", "GET 检查 → DECR 奖品库存 1", "dashed"),
    ("s10back", "draw", "app", "DrawAwardInfo{awardId,type,name,content}", "return"),
    ("s11", "app", "order", "11 AwardOrderReq 带 takeId + award", "default"),
    ("s11repo", "order", "repo", "Snowflake orderId；三个状态置 0", "default"),
    ("s12", "repo", "userdb", "12 TX2：CAS take 0→1 + INSERT 订单", "dashed"),
    ("s12back", "userdb", "repo", "TX2 COMMIT；Outbox 随订单持久化", "return"),
    ("s12result", "order", "app", "InvoiceVO 含 orderId / takeId / award", "return"),
    ("s13", "app", "redis", "13 finally：经 Guard 校验 token 后释放", "security"),
    ("s14", "app", "http", "14 takeId / orderId / award", "return"),
], [card("响应边界", "Controller 把 DrawProcessResult 转为 DrawResponse，再返回 HTTP 客户端。",
             "同步成功 code=0000、win=true；grant_state / mq_state / stock_mq_state 都仍为 INIT(0)。",
             "此时 MySQL strategy_detail 尚未扣减，真实发奖也没有发生。"),
    card("未中奖支路", "NO_PRIZE 或命中的奖品不可售：consumeTake CAS 0→1，返回 D001、win=false。",
         "活动库存和参与次数已消耗；不建中奖订单、不产生库存结算消息、不重摇其他奖品。", dot="amber"),
    card("快照来源", "正常图取 L1 命中。未命中时 Caffeine → Redis L2 → MySQL strategy/detail/award → 编译快照。",
         "取样先使用固定概率。库存不会重新归一化概率。", dot="emerald")]))

diagrams.append(sequence("05-outbox-dispatch", "05 · 异步起点：XXL-Job 把订单 Outbox 发到 Kafka", [
    participant("job", "库存 Job", "application", [source("LotteryXxlJob.java", 193, 271)]),
    participant("repo", "订单仓储", "infrastructure", [source("OrderRepository.java", 155, 189)]),
    participant("db", "用户库", "持久 Outbox", [source("UserStrategyExport_Mapper.xml", 112, 120)], "database"),
    participant("redis", "Redis", "短期投递标记", [source("RedisStrategyAwardStockMqGuard.java", 55, 83)], "database"),
    participant("kafka", "Kafka", "stock topic", [source("KafkaProducer.java", 34, 41)], "messagebus"),
], [
    ("a01", "job", "repo", "A01 按 dbIdx × tbIdx 扫描", "emphasis"),
    ("a01sql", "repo", "db", "INIT / FAIL / SENT 超时10分钟；LIMIT 200", "emphasis"),
    ("a01back", "db", "job", "经仓储返回{uId,orderId,strategyId,awardId}", "return"),
    ("a02", "job", "redis", "A02 SETNX DISPATCHING(orderId)，60s", "security"),
    ("a03", "job", "kafka", "A03 经 KafkaProducer 发 JSON，key=orderId", "dashed"),
    ("a03callback", "kafka", "job", "Future 发送成功回调（非消费确认）", "return"),
    ("a04", "job", "repo", "A04 发送成功推进 SENT(3)", "default"),
    ("a04sql", "repo", "db", "CAS：stock_mq_state 0/2 → 3", "dashed"),
    ("a04clear", "job", "redis", "DEL DISPATCHING；失败也清理", "dashed"),
], [card("Outbox 在哪里", "没有单独 outbox 表：user_strategy_export 的 stock_mq_state 与订单字段共同承担 Outbox。",
             "同一 TX2 写订单和待发送事实，避免订单已提交但发送责任丢失。"),
    card("调度条件", "Handler 在代码中注册；实际频率、分片参数需在 XXL-Job admin 配置。源码没有内置每5秒扫描。",
         "参数 1 或 1,2 指定用户库，每库遍历4张订单表。配置批量200。", dot="amber"),
    card("发送与消费可以交错", "消费者可能比发送回调更快。迟到成功或失败回调不能覆盖 COMPLETE / BLOCKED。",
         "发送失败只有 INIT→FAIL；SENT 保留时钟，到期仍可重投。", dot="emerald")]))

diagrams.append(sequence("06-stock-settlement", "06 · 库存消费：账本与奖品 MySQL 库存同事务", [
    participant("kafka", "Kafka", "库存 / 发奖", [source("LotteryStrategyAwardStockListener.java", 50, 61)], "messagebus"),
    participant("listener", "库存消费", "application", [source("LotteryStrategyAwardStockListener.java", 62, 101)]),
    participant("strategy", "结算仓储", "infrastructure", [source("StrategyRepository.java", 87, 110)]),
    participant("global", "全局库", "db00 / lottery", [source("ledger-sql", 14, 29), source("StrategyDetail_Mapper.xml", 43, 48)], "database"),
    participant("order", "订单仓储", "infrastructure", [source("OrderRepository.java", 118, 143), source("OrderRepository.java", 218, 228)]),
    participant("userdb", "用户库", "stock_mq_state", [source("UserStrategyExport_Mapper.xml", 52, 67)], "database"),
], [
    ("a05", "kafka", "listener", "A05 AwardStockSettlementVO", "default"),
    ("a05read", "listener", "order", "DB 检查 COMPLETE / 订单存在", "emphasis"),
    ("a05sql", "order", "userdb", "按 uId 路由查 orderId", "emphasis"),
    ("a06", "listener", "strategy", "A06 settleAwardStock(orderId,...)", "default"),
    ("a06tx", "strategy", "global", "TX3：INSERT 唯一账本 + 奖品库存-1", "dashed"),
    ("a06back", "global", "strategy", "COMMIT；重复 orderId 不再扣减", "return"),
    ("a07", "listener", "order", "A07 写 stock_mq_state=COMPLETE(1)", "default"),
    ("a07sql", "order", "userdb", "独立 CAS：0/2/3/4 → 1", "dashed"),
    ("a08", "listener", "kafka", "A08 发 invoice；key=orderId", "dashed"),
    ("a09", "listener", "kafka", "A09 ack 库存消息位点", "return"),
], [card("TX3 能保证什么", "award_stock_settlement 与 strategy_detail 同在 db00。本地事务将 INSERT 和条件扣减绑定。",
             "UNIQUE(order_id) 冲突说明本次已结算，返回成功并继续收口；奖品只扣一次。"),
    card("跨库没有一个大事务", "stock_mq_state 在用户库，不能与 db00 账本组成同一本地事务。",
         "中间崩溃由消息重放和账本补齐。当前库存消费者不使用 Redis PROCESSING / DONE 锁。", dot="amber"),
    card("扣减失败", "UPDATE 要求 award_surplus_count>0。失败回滚账本，订单写 BLOCKED(4)，持久化成功后 ack。",
         "BLOCKED 排除自动扫描；需要人工对账修复并显式释放原订单。", dot="rose")]))

diagrams.append(sequence("07-fulfillment", "07 · 发奖消费：模拟渠道与本地发奖状态", [
    participant("kafka", "Kafka", "invoice topic", [source("LotteryInvoiceListener.java", 57, 69)], "messagebus"),
    participant("listener", "发奖消费", "application", [source("LotteryInvoiceListener.java", 71, 107)]),
    participant("guard", "Redis 锁", "PROCESSING", [source("RedisInvoiceMqProcessGuard.java", 24, 50)], "database"),
    participant("handler", "发奖处理器", "domain / 模拟", [source("AbstractAwardFulfillmentHandler.java", 24, 39)]),
    participant("repo", "订单仓储", "infrastructure", [source("OrderRepository.java", 78, 84), source("OrderRepository.java", 108, 115)]),
    participant("db", "用户库", "grant / mq 状态", [source("UserStrategyExport_Mapper.xml", 37, 50)], "database"),
], [
    ("a10", "kafka", "listener", "A10 InvoiceVO 发奖数据", "default"),
    ("a10read", "listener", "repo", "读 grant_state；已完成进入收口", "emphasis"),
    ("a11", "listener", "guard", "A11 SETNX PROCESSING(orderId)，300s", "security"),
    ("a12", "listener", "handler", "A12 按 awardType 选择 handler.fulfill", "default"),
    ("a12repo", "handler", "repo", "模拟渠道日志 → markUserAwardGranted", "default"),
    ("a12sql", "repo", "db", "CAS grant_state !=1 →1；grant_date=now", "dashed"),
    ("a12back", "handler", "listener", "AwardFulfillmentResult SUCCESS", "return"),
    ("a13", "listener", "guard", "A13 成功后释放 PROCESSING", "dashed"),
    ("a14", "listener", "repo", "A14 收口 mq_state=COMPLETE", "default"),
    ("a14sql", "repo", "db", "grant=1 且 mq=0/2/3 才更新为1", "dashed"),
    ("a15", "listener", "kafka", "A15 先持久化再 ack", "return"),
], [card("渠道的实现状态", "四类处理器：文字、兑换码、优惠券、实物。当前均复用日志 + 本地 CAS 的模拟实现。",
             "没有调用物流、券平台或第三方支付接口。未来接入需将 orderId 作为外部幂等键。", dot="amber"),
    card("两个状态不能混淆", "grant_state=1 表示当前本地模拟发奖完成；mq_state=3 只表示发奖消息发送成功。",
         "消费收口 mq_state=1 要求 grant_state=1。grant_state 的 FAIL 枚举当前没有这条主链路的写入点。"),
    card("完成判定", "库存账本存在、stock_mq_state=1、grant_state=1、mq_state=1 且消费者已 ack：本地异步链闭环。",
         "HTTP 的 win=true 早于这些事件，不证明用户已实际收到外部奖品。", dot="emerald")]))

def state(sid, typ, label, sub, lane, col, refs):
    return {"id": sid, "type": typ, "label": label, "sublabel": sub, "lane": lane,
            "col": col, "width": 156, "height": 70, "sources": refs}

mq_refs = [source("UserStrategyExport_Mapper.xml", 52, 67), source("Constants.java", 297, 307)]
diagrams.append({"schema_version": 2, "diagram_type": "lifecycle",
    "meta": meta("08-stock-states", "08 · stock_mq_state：发送失败与库存分歧分开处理"),
    "lanes": [{"id": "main", "label": "库存消息状态 · 存在于用户中奖订单"},
              {"id": "recovery", "label": "故障状态 · FAIL 可重投，BLOCKED 等人工对账"}],
    "states": [
        state("init", "start", "INIT · 0", "订单事务创建", "main", 0, mq_refs),
        state("sent", "waiting", "SENT · 3", "发送成功等消费", "main", 2, mq_refs),
        state("done", "success", "COMPLETE · 1", "库存消费完成", "main", 4, mq_refs),
        state("fail", "failure", "FAIL · 2", "投递失败可重试", "recovery", 0, mq_refs),
        state("blocked", "waiting", "BLOCKED · 4", "库存分歧待对账", "recovery", 2,
              [source("LotteryStrategyAwardStockListener.java", 81, 95), source("OrderRepository.java", 126, 142)]),
    ],
    "transitions": [edge("init", "sent", "发送成功"), edge("sent", "done", "结算成功"),
        edge("init", "fail", "发送失败", "security"), edge("fail", "sent", "重发成功"),
        edge("sent", "blocked", "库存分歧", "security"),
        edge("init", "done", "消费先完成", "dashed", route="top-channel"),
        edge("fail", "done", "消费结算完成", "dashed", route="bottom-channel"),
        edge("blocked", "done", "原消息结算成功", "dashed"),
    ],
    "cards": [card("状态规则", "消费成功允许0/2/3/4→1；库存分歧从0/2/3→4，图中以SENT分支表示典型路径。",
                    "发送成功只允许0/2→3；发送失败只允许0→2。终态1以及待对账4不被发送回调覆盖。"),
        card("重投与人工恢复", "Job 扫0/2以及update_time超过10分钟的3；对已有3重复发送成功不刷新时钟。",
             "BLOCKED 没有自动恢复边。人工核实并修复后，按正确用户分片CAS 4→0，再由Job投递原订单。", dot="amber")]
})

invoice_refs = [source("UserStrategyExport_Mapper.xml", 42, 50), source("LotteryInvoiceListener.java", 71, 107)]
diagrams.append({"schema_version": 2, "diagram_type": "lifecycle",
    "meta": meta("09-invoice-states", "09 · mq_state：发送成功还需要消费确认"),
    "lanes": [{"id": "main", "label": "发奖消息状态 · mq_state"}, {"id": "recovery", "label": "发送失败及超时恢复"}],
    "states": [state("init", "start", "INIT · 0", "库存完成后待发送", "main", 0, invoice_refs),
        state("sent", "waiting", "SENT · 3", "消息已发送", "main", 2, invoice_refs),
        state("done", "success", "COMPLETE · 1", "要求 grant_state=1", "main", 4, invoice_refs),
        state("fail", "failure", "FAIL · 2", "发送失败待补偿", "recovery", 0, invoice_refs)],
    "transitions": [edge("init", "sent", "发送成功"), edge("sent", "done", "发奖完成确认"),
        edge("init", "fail", "发送失败", "security"), edge("fail", "sent", "补偿成功"),
        edge("init", "done", "消费先完成", "dashed", route="top-channel"),
        edge("fail", "done", "发奖已完成", "dashed", route="bottom-channel")],
    "cards": [card("补偿筛选", "必须stock_mq_state=1且grant_state!=1。FAIL直接扫；INIT创建超过30分钟才扫。",
                    "SENT更新超过10分钟可补偿；历史mq=1但grant!=1也按10分钟恢复。每张表LIMIT100。"),
              card("本地发奖状态", "grant_state 由handler CAS 0→1；失败时消费者抛异常，没有主链路写grant=2。",
                   "消息状态COMPLETE只能在grant=1时写入。示意消费顺序并不要求发送回调先抵达。", dot="amber")]
})

apprefs = [source("ActivityProcessImpl.java", 82, 127), source("ActivityProcessImpl.java", 208, 229)]
diagrams.append(workflow("10-request-retry", "10 · 重复请求：恢复原请求，回放持久结果", [
    ("request", "请求与应用层"), ("fact", "MySQL 参与 / 订单事实"), ("outcome", "处理结果与客户端恢复")], [
    wfnode("retry", "request", 0, "重复抽奖请求", "原 requestId", apprefs),
    wfnode("lookup", "fact", 1, "精确查询 take", "uId + activityId + 请求号", apprefs, "database"),
    wfnode("used", "fact", 2, "take 已消费", "state=1", apprefs, "database"),
    wfnode("replay", "outcome", 3, "回放原结果", "有订单中奖 / 无订单D001", apprefs),
    wfnode("pending", "fact", 3, "take 未消费", "state=0 / 归属当前请求", apprefs, "database"),
    wfnode("resume", "outcome", 4, "继续原 take", "不再扣活动库存或次数", apprefs),
    wfnode("foreign", "request", 4, "新请求遇到旧 take", "B 不能消费 A 的机会", [source("BaseActivityPartake.java", 74, 90)]),
    wfnode("reject", "outcome", 5, "D007 恢复原请求", "pendingRequestId=A", [source("BaseActivityPartake.java", 81, 90)]),
], [edge("retry", "lookup", "获得短锁后查询"), edge("lookup", "used", "state=1"),
    edge("used", "replay", "按takeId查订单"), edge("lookup", "pending", "state=0", "dashed", role="branch"),
    edge("pending", "resume", "同请求恢复"), edge("foreign", "reject", "归属不同", "security", role="error")],
    ["retry", "lookup", "used", "replay"], [card("并发与持久幂等", "同用户活动已有短锁返回D006；3秒token锁只减少并发。",
         "最终保护是参与请求唯一键、未完成参与唯一键和消费CAS，锁过期也不能把A的take交给B。"),
        card("当前约束", "V3 为UNIQUE(u_id,activity_id,request_id)，不是旧V2的全表request_id唯一。",
             "state=0的生成列配合唯一键限制每用户活动仅一条未完成记录。查询异常直接中断，不当作未找到。", dot="amber")]))

diagrams.append(workflow("11-stock-key-missing", "11 · Redis 库存丢失：保留现场，停止不可确认的销售", [
    ("activity", "活动库存 Key · 参与前"), ("award", "奖品库存 Key · take 已创建"), ("ops", "恢复边界 · 当前需人工核实")], [
    wfnode("activitykey", "activity", 0, "GET 活动库存", "运行期 Key 丢失", [source("ActivityRepository.java", 195, 204)], "database"),
    wfnode("activityerror", "activity", 2, "记录 error → D005", "不 DECR，不创建 take", [source("ActivityRepository.java", 198, 224)]),
    wfnode("awardkey", "award", 0, "GET 奖品库存", "采样已命中奖品", [source("StrategyRepository.java", 118, 132)], "database"),
    wfnode("soldout", "award", 2, "视为不可售", "error 日志；不重摇", [source("StrategyRepository.java", 122, 126), source("DrawExecutor.java", 40, 46)]),
    wfnode("losing", "award", 4, "消费 take → D001", "无订单，活动/次数已消耗", [source("ActivityProcessImpl.java", 132, 150)]),
    wfnode("manual", "ops", 2, "人工对账核实", "无运行期自动修复入口", [source("ActivityRepository.java", 243, 253), source("StrategyRepository.java", 113, 126)], "external"),
], [edge("activitykey", "activityerror", "Key 不存在", "security", role="error"),
    edge("awardkey", "soldout", "Key 不存在", "security", role="error"),
    edge("soldout", "losing", "DrawState.FAIL"),
    edge("activityerror", "manual", "日志提示核实", "dashed", role="branch")],
    ["awardkey", "soldout", "losing"], [card("为什么不能回填", "奖品MySQL结算可能滞后，活动MySQL也未随每次参与扣减。用这些值覆盖运行库存会让已售库存复活。",
         "warmupStockForActivity只允许PASS，DOING不能靠启动预热恢复；没有实现运行期对账算法。", dot="amber"),
        card("Key 缺失与连接异常", "奖品Key缺失返回soldOut并落D001；读Redis抛异常则向上抛IllegalStateException。",
             "后者保留未消费take，客户端用原requestId恢复。不能把所有Redis异常都画成未中奖。")]))

diagrams.append(workflow("12-consumer-crash", "12 · 库存消费者宕机：账本跨越已扣减但未收口的窗口", [
    ("consumer", "application · 同一 orderId 的多次消息"),
    ("database", "infrastructure / db00 · 唯一账本"),
    ("failure", "故障与继续执行")], [
    wfnode("first", "consumer", 0, "首次库存消费", "orderId=同一订单", [source("LotteryStrategyAwardStockListener.java", 61, 81)]),
    wfnode("commit", "database", 1, "TX3 已提交", "账本 + MySQL库存-1", [source("StrategyRepository.java", 87, 110)], "database"),
    wfnode("crash", "failure", 1, "此时进程宕机", "订单stock状态仍未完成", [source("LotteryStrategyAwardStockListener.java", 81, 99)], "external"),
    wfnode("redelivery", "consumer", 2, "相同订单重投", "未ack / SENT超时恢复", [source("LotteryStrategyAwardStockListener.java", 98, 101), source("UserStrategyExport_Mapper.xml", 112, 120)], "messagebus"),
    wfnode("duplicate", "database", 3, "账本唯一键冲突", "直接成功；库存不再-1", [source("StrategyRepository.java", 95, 110)], "database"),
    wfnode("continue", "consumer", 4, "完成库存并发 invoice", "stock_mq_state →1", [source("LotteryStrategyAwardStockListener.java", 94, 97)]),
    wfnode("ack", "consumer", 5, "ack 库存位点", "后续由发奖消费者继续", [source("LotteryStrategyAwardStockListener.java", 94, 101)], "messagebus"),
], [edge("first", "commit", "settleAwardStock"), edge("commit", "crash", "提交后中断", "security", role="error"),
    edge("crash", "redelivery", "重启后处理", "dashed", role="async"),
    edge("redelivery", "duplicate", "重放同orderId"), edge("duplicate", "continue", "幂等成功"),
    edge("continue", "ack", "消息责任已交接")],
    ["first", "commit", "crash", "redelivery", "duplicate", "continue", "ack"],
    [card("宕机时点决定恢复", "TX3未提交：事务回滚，重投首次结算。TX3已提交但库存状态未完成：账本阻止二次扣减。",
         "库存状态已完成但invoice未发：库存重放先短路，发奖补偿Job负责缺失invoice。"),
     card("恢复能力的边界", "当前配置关闭自动位点提交并使用manual_immediate；代码异常不ack。",
          "未ack重放与XXL-Job超时扫描共同提供恢复机会。没有配置专用死信队列或无限重试保证。", dot="amber")]))

diagrams.append(workflow("13-blocked-reconcile", "13 · 库存分歧：BLOCKED 留下待处理订单", [
    ("database", "db00 · 结算事务"), ("consumer", "application / 用户库 · 消费处理"),
    ("manual", "人工处置 · 非自动修复程序")], [
    wfnode("gap", "database", 0, "条件扣减失败", "award_surplus_count<=0", [source("StrategyDetail_Mapper.xml", 43, 48)], "database"),
    wfnode("rollback", "database", 1, "TX3 回滚账本", "本次没有结算成功事实", [source("StrategyRepository.java", 103, 110)], "database"),
    wfnode("block", "consumer", 2, "持久化 BLOCKED=4", "消费失败独立CAS", [source("OrderRepository.java", 126, 142), source("UserStrategyExport_Mapper.xml", 62, 67)]),
    wfnode("ack", "consumer", 3, "记录对账日志 + ack", "Outbox 扫描排除4", [source("LotteryStrategyAwardStockListener.java", 81, 91)]),
    wfnode("repair", "manual", 3, "人工核实并修复", "保持原orderId / 不盲目加库存", [source("architecture", 44, 48)], "external"),
    wfnode("release", "manual", 4, "按订单 CAS 4→0", "正确用户库与订单分表", [source("architecture", 44, 48)], "external"),
    wfnode("dispatch", "consumer", 5, "Job 重投原订单", "结算成功后继续发奖", [source("UserStrategyExport_Mapper.xml", 112, 120), source("LotteryXxlJob.java", 217, 225)]),
], [edge("gap", "rollback", "扣减0行", "security", role="error"), edge("rollback", "block", "返回false"),
    edge("block", "ack", "持久化成功才ack"), edge("ack", "repair", "等待人工处理", "dashed", role="branch"),
    edge("repair", "release", "核实修复后"), edge("release", "dispatch", "原Outbox重新可扫", "dashed", role="async")],
    ["gap", "rollback", "block", "ack", "repair", "release", "dispatch"],
    [card("对账与重试的区别", "FAIL=2是发送失败，下轮Job可重试。BLOCKED=4是Redis/MySQL业务分歧，需要处理真实库存事实。",
         "若BLOCKED写入异常或零行且无法确认已BLOCKED/COMPLETE，仓储抛异常，消费者不ack。"),
     card("人工步骤的实现范围", "4→0是现有运维契约，不存在自动对账Job、管理Controller或通用库存修复脚本。",
          "图中人工节点是操作边界，不表示自动功能已落地。", dot="amber")]))

# 未中奖是独立结束路径，避免在中奖时序中把无订单分支误画成订单创建。
diagrams.append(sequence("14-no-prize", "14 · 未中奖：消费参与机会，返回后结束", [
    participant("draw", "抽奖规则", "domain", [source("DrawExecutor.java", 32, 46)]),
    participant("app", "用例编排", "application", [source("ActivityProcessImpl.java", 132, 150)]),
    participant("partake", "参与规则", "domain", [source("ActivityPartakeImpl.java", 58, 65)]),
    participant("repo", "参与仓储", "infrastructure", [source("UserTakeActivityRepository.java", 120, 128)]),
    participant("db", "用户库", "参与state", [source("UserTakeActivity_Mapper.xml", 28, 30)], "database"),
    participant("redis", "Redis", "原请求token锁", [source("RedisActivityDrawProcessGuard.java", 39, 49)], "database"),
    participant("http", "HTTP 入口", "interfaces", [source("LotteryController.java", 72, 95)]),
], [
    ("l09", "draw", "app", "09 DrawState.FAIL", "return"),
    ("l10", "app", "partake", "10 consumeTakeActivity(uId,act,takeId)", "default"),
    ("l10repo", "partake", "repo", "consumeTake：按uId路由", "default"),
    ("l10sql", "repo", "db", "单条CAS take 0→1", "dashed"),
    ("l10back", "partake", "app", "消费成功；零行须回读", "return"),
    ("l11", "app", "redis", "11 finally 校验token后释放", "security"),
    ("l12", "app", "http", "12 D001 / takeId / win=false", "return"),
], [card("本次流程结束", "NO_PRIZE或命中的奖品不可售：不重摇，消费take并返回D001。",
         "活动库存与次数已消耗。无中奖订单，所以没有本次Outbox、账本、invoice。"),
    card("锁释放的实际位置", "finally由应用调用Redis Guard，只有原token仍匹配时删除短锁。",
         "同requestId重试：已消费take无订单，回放D001，不再采样。", dot="amber")]))

# 保留完整数据语义，仅用列宽与节点宽度承接已测得的布局空间需求。
written_count = 0
for diagram in diagrams:
    slug = Path(diagram["meta"]["output"]).stem
    if slug in ["05-outbox-dispatch", "06-stock-settlement", "07-fulfillment", "14-no-prize"]:
        diagram["meta"]["column_fit"] = "spread"
    if slug == "11-stock-key-missing":
        for current_node in diagram["nodes"]:
            current_node["width"] = 180

# 默认构建全部图；指定图名只更新该图，便于逐图重新核对而不覆盖其它候选。
for diagram in diagrams:
    slug = Path(diagram["meta"]["output"]).stem
    if len(sys.argv) > 1 and slug not in sys.argv[1:]:
        continue
    folder = OUT / "diagrams" / slug
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "candidate.json").write_text(json.dumps(diagram, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    written_count += 1
print(f"已写入 {written_count} 幅完整 Archify 规格。")
