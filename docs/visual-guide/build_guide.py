"""构建离线教学内容与带实际行号的源码摘录，不读取运行数据库。"""
import html
import json
import re
from pathlib import Path

OUT = Path(__file__).resolve().parent
ROOT = OUT.parent.parent
# 根据真实源码位置建立文件名映射，教学内容只引用已经阅读的文件。
FILES = {p.name: str(p.relative_to(ROOT)) for p in ROOT.glob("lottery-*/src/**/*") if p.is_file()}
FILES.update({"ownership": "docs/sql/V3__draw_request_ownership.sql", "ledger": "docs/sql/V2__architecture_simplification.sql",
              "architecture": "docs/architecture.md", "root-pom": "pom.xml", "interface-pom": "lottery-interfaces/pom.xml"})

def ref(name, start, end):
    """同一份引用用于步骤面板、离线摘录和实际文件链接。"""
    return {"path": FILES[name], "line": start, "end_line": end}

def step(sid, title, layer, method, inputs, action, outputs, why, next_to, refs, diagram, focus, changes=None):
    """逐步说明与状态变化共同描述一条执行链，变化值只是教学示例。"""
    return {"id": sid, "title": title, "layer": layer, "method": method, "inputs": inputs,
            "action": action, "outputs": outputs, "why": why, "next": next_to, "sources": refs,
            "diagram": diagram, "relation": focus, "changes": changes or {}}

R = ref
sync = [
    step("s01", "HTTP 接收三个关键字段", "interfaces", "LotteryController.draw / DrawRequest",
         "requestId=learn-req-001；userId=u1；activityId=100001",
         "@Validated 校验请求号非空且≤64字符、用户号非空且≤32字符、活动号非null；日志记录三个关联字段。",
         "DrawRequest表示客户端这一次逻辑抽奖。",
         "请求号让网络超时重试可被识别为同一次抽奖。HTTP校验不负责库存与抽样。",
         "Controller → IActivityDrawProcess.doDrawProcess",
         [R("LotteryController.java", 37, 51), R("DrawRequest.java", 11, 23)], "03-participation", "s01"),
    step("s02", "将外部 DTO 转为应用请求", "interfaces → application", "LotteryController.draw → DrawProcessReq",
         "HTTP 的 userId / activityId / requestId",
         "new DrawProcessReq(userId, activityId, requestId)。外部userId在内部叫uId，值未改变。",
         "DrawProcessReq{uId,activityId,requestId}",
         "外部HTTP契约与内部用例分开变化；Controller保持薄层。",
         "ActivityProcessImpl.doDrawProcess", [R("LotteryController.java", 41, 44)], "03-participation", "s02"),
    step("s03", "获得用户与活动维度的短锁", "application → infrastructure → Redis", "IActivityDrawProcessGuard.tryClaim",
         "activityId + uId；本次执行新生成的UUID token",
         "SETNX lottery_draw_inflight_100001_u1，写token并设3秒TTL；占用时返回D006。",
         "本线程暂时获得进入主链路的资格；token不是requestId。",
         "短锁减少并发重复执行，会过期；最终幂等由数据库约束和CAS保证。",
         "查询原请求的take", [R("ActivityProcessImpl.java", 69, 75), R("RedisActivityDrawProcessGuard.java", 24, 45)],
         "03-participation", "s03", {"lock": "token 持有（TTL 3秒）"}),
    step("s04", "先查询 requestId 的持久结果", "application → infrastructure → 用户 MySQL", "findTakeByRequestId → queryTakeActivityByRequestId",
         "uId=u1 + activityId=100001 + requestId=learn-req-001",
         "按uId路由，精确查询user_take_activity。正常首次请求得到null。SQL异常直接传播，不等价于无记录。",
         "无记录才新参与；state=1回放；state=0继续原take。",
         "避免超时重试重复扣活动库存和次数；唯一键仍负责并发竞争。",
         "IActivityPartake.doPartake", [R("ActivityProcessImpl.java", 82, 127), R("UserTakeActivity_Mapper.xml", 42, 47), R("ownership", 35, 61)],
         "03-participation", "s04sql"),
    step("s05", "核对其他未完成参与的归属", "domain → infrastructure → 用户 MySQL", "BaseActivityPartake.doPartake / resolveUnfinishedTake",
         "当前uId + activityId + requestId",
         "查用户活动state=0的take。原requestId才能继续；B遇到A的take返回D007与pendingRequestId=A。",
         "本例无未完成记录，可以检查活动。未知请求归属的旧take返回D008。",
         "take代表已经消耗的机会，不能让后来的不同请求接管。",
         "queryActivityBill", [R("BaseActivityPartake.java", 29, 39), R("BaseActivityPartake.java", 74, 90)],
         "03-participation", "s05pending"),
    step("s06", "读取活动单据并检查资格", "domain → infrastructure → Redis / MySQL", "queryActivityBill → ActivityPartakeImpl.checkActivityBill",
         "activityId；PartakeReq中的参与时间",
         "读取活动Redis配置缓存，miss时读MySQL activity；检查DOING(5)、开始结束时间、单据库存>0。",
         "ActivityBillVO含strategyId=10001、takeCount、库存、状态、时间窗和uId。",
         "活动配置决定是否允许参与；配置库存不替代下一步Redis实时库存检查。用户次数由TX1条件UPDATE校验。",
         "substractionActivityStockByRedis", [R("ActivityRepository.java", 150, 169), R("ActivityPartakeImpl.java", 29, 46)],
         "03-participation", "s06cache"),
    step("s07", "预扣一份活动库存", "domain → infrastructure → Redis", "substractionActivityStockByRedis",
         "lottery_activity_stock_count_100001=100（教学值）",
         "GET确认Key存在且>0，再DECR 1。并发扣成负数则INCR回补并返回D004；Key缺失或操作异常返回D005。",
         "Redis活动剩余100→99；MySQL activity仍为100。",
         "Redis原子扣减控制可创建的参与机会。运行期不能用滞后数据库值回填。",
         "生成takeId并调用参与仓储", [R("ActivityRepository.java", 190, 225), R("BaseActivityPartake.java", 42, 52)],
         "03-participation", "s07redis", {"activityRedis": 99}),
    step("s08", "TX1 扣次数并创建 take", "domain → infrastructure → 用户 MySQL", "IdGenerator.nextId → takeParticipation",
         "ActivityBillVO + requestId + Snowflake takeId=T1；用户余次数3",
         "按uId选库再开本地事务；必要时初始化次数行，条件扣left_count，再INSERT take(state=0,request_id)。finally清路由。",
         "次数3→2；持久take T1关联用户、活动、策略和requestId。返回PartakeResult。",
         "次数与take同事务。失败回滚TX1并回补活动Redis；回补错误只记日志，不能宣称补偿必达。",
         "应用拿到strategyId / takeId → DrawExecutor", [R("BaseActivityPartake.java", 49, 71), R("UserTakeActivityRepository.java", 88, 117), R("UserTakeActivityCount_Mapper.xml", 13, 25)],
         "03-participation", "s08tx", {"quota": 2, "take": "T1 · 未消费(0)"}),
    step("s09", "策略快照与固定概率采样", "application → domain → infrastructure", "DrawExecutor.doDrawExec / WeightedRandomSelector.select",
         "DrawReq{uId=u1,strategyId=10001,uuid=String.valueOf(T1)}",
         "快照来源Caffeine L1 → Redis L2 → MySQL。概率×1,000,000转整数前缀和，随机点二分采样；差额为NO_PRIZE。",
         "本例命中awardId=3。这里DrawReq.uuid是takeId字符串，订单uuid稍后才产生。",
         "编译快照避免热路径反复查详情和算概率。售罄不重摇、不把概率分给其他奖品。",
         "StrategyRepository.deductStockByRedis", [R("DrawExecutor.java", 31, 46), R("StrategySnapshotFactory.java", 36, 84), R("WeightedRandomSelector.java", 19, 38)],
         "04-draw-order", "s09snapshot"),
    step("s10", "预扣命中奖品的 Redis 库存", "domain → infrastructure → Redis", "StrategyRepository.deductStockByRedis",
         "lottery_strategy_award_stock_count_10001_3=5（教学值）",
         "GET存在且>0后DECR 1；负数回补并判不可售。Key缺失记error并不可售；读取Redis异常向上抛出，保留未完成take。",
         "奖品Redis 5→4；DrawAwardInfo含奖品详情与发奖配置；MySQL奖品仍为5。",
         "概率命中不等于库存足够，Redis控制实时销售，MySQL库存后续异步结算。",
         "应用层 → AwardOrderImpl.placeOrder", [R("StrategyRepository.java", 117, 145), R("DrawExecutor.java", 49, 60)],
         "04-draw-order", "s10redis", {"prizeRedis": 4, "award": "awardId=3 · 优惠券类型3"}),
    step("s11", "生成中奖订单标识", "application → domain", "buildAwardOrderReq → AwardOrderImpl.buildDrawOrder",
         "uId + activityId + strategyId + takeId=T1 + DrawAwardInfo",
         "应用组AwardOrderReq；订单领域服务生成新的Snowflake orderId=O1，三个状态grant/mq/stock_mq都置0。",
         "DrawOrderVO带takeId和奖品快照；orderId与takeId是两个独立ID。",
         "take证明参与机会，order证明中奖履约责任；未中奖有take但无订单。",
         "OrderRepository.saveDrawOrderWithTakeLock", [R("ActivityProcessImpl.java", 237, 250), R("AwardOrderImpl.java", 107, 124)],
         "04-draw-order", "s11repo", {"orderDraft": "O1 · 尚未提交"}),
    step("s12", "TX2 消费 take 并提交订单 Outbox", "infrastructure → 用户 MySQL", "OrderRepository.saveDrawOrderWithTakeLock",
         "DrawOrderVO{T1,O1,uId,活动,策略,奖品,三个状态}",
         "同一用户库事务：CAS take 0→1，再INSERT中奖订单分表；uuid=String(orderId)。任一业务失败整体回滚。",
         "take已消费；O1落库；stock_mq_state=0同时留下待发送责任。",
         "take消费与订单提交原子完成。Outbox是订单字段，不是另一张表；HTTP链路不直接发Kafka。",
         "返回InvoiceVO；XXL-Job后续扫描原订单", [R("OrderRepository.java", 38, 65), R("OrderRepository.java", 87, 105), R("UserStrategyExport_Mapper.xml", 27, 35)],
         "04-draw-order", "s12", {"take": "T1 · 已消费(1)", "order": "O1 · 已提交", "orderDraft": None, "stockState": "INIT(0)", "invoiceState": "INIT(0)", "grantState": "INIT(0)"}),
    step("s13", "释放本请求的短锁", "application → infrastructure → Redis", "finally → RedisActivityDrawProcessGuard.release",
         "原activityId + uId + token", "Lua比较原token，相同才删除；过期或已归新线程时不删除对方锁。",
         "当前请求结束，锁释放或自然过期。", "异常也走finally。属主校验避免慢线程删除后来请求的锁；MySQL保留结果。",
         "Controller.toResponse", [R("ActivityProcessImpl.java", 169, 180), R("RedisActivityDrawProcessGuard.java", 39, 49)],
         "04-draw-order", "s13", {"lock": "已释放 / 已过期"}),
    step("s14", "HTTP 返回中奖，异步继续", "application → interfaces → 客户端", "LotteryController.toResponse",
         "DrawProcessResult{code=0000,takeId=T1,orderId=O1,DrawAwardInfo}",
         "回带requestId、takeId、orderId；仅code成功且有奖品时win=true。返回不等待库存结算和发奖。",
         "客户端收到中奖结果；三个订单状态仍是INIT(0)。",
         "减少请求延迟；网络超时用原requestId回放。win=true不能证明用户已收到奖品。",
         "原订单Outbox → 下一轮已配置的XXL-Job", [R("LotteryController.java", 72, 95), R("ActivityProcessImpl.java", 169, 177)],
         "04-draw-order", "s14", {"response": "0000 · win=true；异步尚未闭环"}),
]

losing = sync[:8] + [
    step("l09", "NO_PRIZE：正常未中奖", "domain → application", "WeightedRandomSelector.select → DrawExecutor.doDrawExec",
         "T1与同一策略快照；本次随机命中NO_PRIZE",
         "返回DrawState.FAIL；命中奖品售罄或Key缺失也走FAIL，不重摇。",
         "本例无有效奖品、无奖品Redis预扣。", "保留原概率与未中奖语义，不转移售罄奖品的概率。",
         "IActivityPartake.consumeTakeActivity", [R("DrawExecutor.java", 32, 46), R("StrategySnapshotFactory.java", 69, 84)], "14-no-prize", "l09"),
    step("l10", "消费本次 take", "application → domain → infrastructure → 用户 MySQL", "consumeTakeActivity → consumeTake → lockTakeActivity",
         "uId + activityId + T1；state=0",
         "单条CAS UPDATE state=1 WHERE ... state=0。零行说明消费权被其他线程取得，必须回读最终结果。",
         "take 0→1；次数与活动库存保持已消耗；没有中奖订单。",
         "不消费take会让同requestId下次恢复再次抽样，改变已完成结果。",
         "返回D001，并finally释放锁", [R("ActivityProcessImpl.java", 132, 150), R("UserTakeActivityRepository.java", 120, 128), R("UserTakeActivity_Mapper.xml", 28, 30)],
         "14-no-prize", "l10sql", {"take": "T1 · 已消费(1)"}),
    step("l11", "结束请求并释放短锁", "application → infrastructure → Redis", "finally → release(activityId,uId,token)",
         "原短锁token", "只删除属主匹配的锁。", "本次调用结束。", "正常和异常结束都要释放并发控制责任。",
         "Controller.toResponse", [R("ActivityProcessImpl.java", 178, 180)], "14-no-prize", "l11", {"lock": "已释放 / 已过期"}),
    step("l12", "返回未中奖，无异步订单", "interfaces → 客户端", "LotteryController.toResponse",
         "code=D001,takeId=T1，无订单无奖品", "win=false；orderId和award为空。同requestId重试：take已消费且无订单，回放D001。",
         "活动Redis99、次数2、take已消费；奖品Redis/MySQL仍5；无Outbox、账本与发奖。",
         "参与记录与中奖订单分开建模才能表达这条结束路径。",
         "本逻辑请求结束", [R("ActivityProcessImpl.java", 208, 217), R("LotteryController.java", 72, 95)], "14-no-prize", "l12", {"response": "D001 · win=false；流程结束"}),
]

async_steps = [
    step("a01", "扫描库存 Outbox", "application → infrastructure → 用户 MySQL", "awardStockSettlementOutboxJobHandler → scanAwardStockMqStateByShard",
         "已提交O1；XXL-Job参数1或1,2指定库号",
         "逐库逐订单分表扫stock=0/2，以及update_time超10分钟的3；配置每表最多200条，按create_time排序。",
         "AwardStockSettlementVO{uId,orderId,strategyId,awardId}，不带requestId或takeId。",
         "应用退出后仍能从数据库发现未完成工作。实际触发频率由admin配置。",
         "领取DISPATCHING标记", [R("LotteryXxlJob.java", 193, 225), R("OrderRepository.java", 155, 189), R("UserStrategyExport_Mapper.xml", 112, 120)], "05-outbox-dispatch", "a01sql"),
    step("a02", "减少同轮重复投递", "application → infrastructure → Redis", "tryClaimDispatch(orderId)",
         "orderId=O1", "SETNX lottery_strategy_award_stock_mq_dispatching_O1，TTL60秒；未领取则留给后续任务。",
         "短期投递标记，不表示结算完成。", "多worker可能扫到同订单；标记减少重复发送，账本负责最终安全。",
         "KafkaProducer.sendStrategyAwardStock", [R("LotteryXxlJob.java", 231, 244), R("RedisStrategyAwardStockMqGuard.java", 55, 83)],
         "05-outbox-dispatch", "a02", {"dispatchLock": "O1 · DISPATCHING"}),
    step("a03", "发送库存结算消息", "application → Kafka", "KafkaProducer.sendStrategyAwardStock",
         "库存VO{uId=u1,orderId=O1,strategyId=10001,awardId=3}",
         "Fastjson序列化字符串，发送topic=lottery_strategy_award_stock，key=String(orderId)，返回Future。",
         "Kafka收到消息不是业务消费完成。",
         "结算脱离请求线程；orderId关联消息和账本，uId仍用于查订单路由。",
         "回调与消费者可交错执行", [R("KafkaProducer.java", 34, 41), R("LotteryXxlJob.java", 243, 266)],
         "05-outbox-dispatch", "a03", {"stockMessage": "已发送，等待回调/消费"}),
    step("a04", "发送回调推进 SENT", "application → infrastructure → 用户 MySQL / Redis", "Future.onSuccess → updateStockMqState",
         "uId + O1；发送成功回调",
         "仅INIT/FAIL→SENT(3)，清投递标记。发送失败仅INIT→FAIL(2)，不覆盖SENT/COMPLETE/BLOCKED。",
         "本例stock=3；消费者可能先完成，SQL保护终态。",
         "发送成功不是消费完成；迟到回调不能回退，重复SENT不刷新超时时钟。",
         "LotteryStrategyAwardStockListener", [R("LotteryXxlJob.java", 247, 264), R("UserStrategyExport_Mapper.xml", 52, 60)],
         "05-outbox-dispatch", "a04sql", {"stockState": "SENT(3)", "dispatchLock": "已清理"}),
    step("a05", "库存消费者先查订单事实", "application → domain → infrastructure → 用户 MySQL", "LotteryStrategyAwardStockListener.onMessage",
         "库存消息 + 手动Acknowledgment",
         "反序列化VO，查询stock是否COMPLETE；已完成直接ack。再确认订单存在，才进入结算；不存在的旧消息记warn并ack。",
         "有效O1进入settleAwardStock。",
         "避免重复主流程；当前库存消费者没有调用Redis PROCESSING或DONE锁。",
         "StrategyRepository.settleAwardStock", [R("LotteryStrategyAwardStockListener.java", 50, 81), R("OrderRepository.java", 218, 228)], "06-stock-settlement", "a05read"),
    step("a06", "TX3：唯一账本与奖品库存", "infrastructure → 全局 MySQL db00", "StrategyRepository.settleAwardStock",
         "库存VO，O1；strategy_detail剩余5",
         "事务先INSERT award_stock_settlement（order_id唯一），再条件UPDATE库存-1。唯一冲突直接成功；扣减0行回滚账本。",
         "账本新增O1；MySQL奖品5→4。Redis保持已预扣的4。",
         "同库原子提交跨越扣减后宕机的窗口；同O1重放不再扣库存。",
         "独立更新用户库stock状态", [R("StrategyRepository.java", 87, 110), R("ledger", 14, 29), R("StrategyDetail_Mapper.xml", 43, 48)],
         "06-stock-settlement", "a06tx", {"prizeMysql": 4, "ledger": "O1 · 已结算（1行）"}),
    step("a07", "确认库存结算完成", "application → domain → infrastructure → 用户 MySQL", "updateStockMqState(...,COMPLETE)",
         "TX3成功或账本证明早已成功",
         "订单分表独立CAS stock从0/2/3/4→1；不是TX3的一部分，不存在跨库大事务。",
         "stock_mq_state=COMPLETE(1)。",
         "账本防二次扣减，重放负责跨两个数据库提交补齐状态。",
         "sendInvoiceMessage", [R("LotteryStrategyAwardStockListener.java", 94, 97), R("UserStrategyExport_Mapper.xml", 52, 60)],
         "06-stock-settlement", "a07sql", {"stockState": "COMPLETE(1)"}),
    step("a08", "投递发奖消息与记录发送结果", "application → Kafka / 用户 MySQL", "sendInvoiceMessage → sendLotteryInvoice",
         "订单InvoiceVO：uId、O1、T1、奖品和grant配置",
         "发lottery_invoice，key=O1字符串。成功回调mq=3，失败mq=2。发送开关关闭也置FAIL，补偿Job同时跳过。",
         "本例发奖消息成功、mq=SENT(3)，这里示意成功回调已到达。",
         "结算和发奖分成两个责任；失败保存为可补偿状态，下一轮Job重发同订单。",
         "ack库存消息；发奖独立接收", [R("LotteryStrategyAwardStockListener.java", 105, 139), R("KafkaProducer.java", 26, 31)],
         "06-stock-settlement", "a08", {"invoiceState": "SENT(3)", "invoiceMessage": "已发送（示意成功回调已到达）"}),
    step("a09", "提交库存消费位点", "application → Kafka", "Acknowledgment.acknowledge",
         "库存已完成，invoice发送已发起或失败状态已记录",
         "manual_immediate，先持久化业务，再ack库存位点；异常不ack并抛出。发奖回调不必先完成。",
         "库存消息在本消费者组已确认。",
         "stock已完成的重放直接跳过；invoice缺失由发奖补偿修复。",
         "LotteryInvoiceListener.onMessage", [R("LotteryStrategyAwardStockListener.java", 94, 101), R("application.yml", 47, 70)],
         "06-stock-settlement", "a09", {"stockAck": "已 ack"}),
    step("a10", "发奖消费者查询 grant 状态", "application → infrastructure → 用户 MySQL", "LotteryInvoiceListener / isAwardCompleted",
         "invoice topic中的InvoiceVO",
         "先查grant。已是1则只收口mq=1后ack，不执行handler；未完成继续领取PROCESSING。",
         "本例grant=0，进入发奖。",
         "Redis临时标记不是发奖事实；重放可以修复grant完成而mq未收口的窗口。",
         "IInvoiceMqProcessGuard.tryClaimProcessing", [R("LotteryInvoiceListener.java", 68, 81)], "07-fulfillment", "a10read"),
    step("a11", "发奖 PROCESSING 短标记", "application → infrastructure → Redis", "RedisInvoiceMqProcessGuard.tryClaimProcessing",
         "O1", "SETNX lottery_invoice_mq_processing_O1，TTL300秒。未取得锁再查grant，仍未完成则抛异常、不ack。",
         "本例获得标记；宕机遗留锁等TTL到期。",
         "减少并发handler调用；最终完成仍看数据库。",
         "handlerRegistry按awardType选处理器", [R("LotteryInvoiceListener.java", 81, 101), R("RedisInvoiceMqProcessGuard.java", 24, 50)],
         "07-fulfillment", "a11", {"invoiceLock": "O1 · PROCESSING"}),
    step("a12", "模拟渠道与本地发奖 CAS", "application → domain → infrastructure → 用户 MySQL", "CouponFulfillmentHandler → AbstractAwardFulfillmentHandler.fulfill",
         "awardType=3，O1、awardId=3、awardContent",
         "四类handler按supportType注册；当前只记模拟日志，再CAS grant!=1→1，grant_date=now；无第三方调用。",
         "grant_state=COMPLETE(1)；返回AwardFulfillmentResult。",
         "统一不同奖品类型入口；未来需orderId外部幂等契约，本地CAS不证明外部奖品已交付。",
         "释放标记并收口消息", [R("LotteryInvoiceListener.java", 45, 54), R("AbstractAwardFulfillmentHandler.java", 24, 39), R("UserStrategyExport_Mapper.xml", 37, 40)],
         "07-fulfillment", "a12sql", {"grantState": "COMPLETE(1) · 本地模拟", "grantDate": "消费时刻 now()"}),
    step("a13", "释放发奖标记", "application → infrastructure → Redis", "invoiceMqProcessGuard.releaseProcessing",
         "O1；handler成功",
         "成功后删除PROCESSING；途中异常不走成功释放，锁由TTL过期。",
         "短期发奖锁释放。",
         "持久grant=1判定重复消息，临时标记只减少并发。",
         "updateInvoiceMqState(...,COMPLETE)", [R("LotteryInvoiceListener.java", 88, 107), R("RedisInvoiceMqProcessGuard.java", 38, 49)],
         "07-fulfillment", "a13", {"invoiceLock": "已释放"}),
    step("a14", "收口发奖消息", "application → infrastructure → 用户 MySQL", "OrderRepository.updateInvoiceMqState",
         "grant=1、mq=3；uId + O1",
         "SQL仅grant=1且mq=0/2/3时写COMPLETE(1)，迟到回调不能回退。",
         "mq_state=COMPLETE(1)。",
         "区别发送成功、发奖事实与消费完成；补偿发送不能冒充已发奖。",
         "ack invoice位点", [R("LotteryInvoiceListener.java", 103, 107), R("UserStrategyExport_Mapper.xml", 42, 50)],
         "07-fulfillment", "a14sql", {"invoiceState": "COMPLETE(1)"}),
    step("a15", "本地异步流程闭环", "application → Kafka", "LotteryInvoiceListener → ack",
         "账本O1存在；stock=1、grant=1、mq=1",
         "消息状态持久化返回后再ack；grant已更新后崩溃，重放按grant=1修复mq并ack。",
         "本地实现闭环；真实外部奖品交付尚未实现。",
         "完成判定晚于HTTP中奖响应，需要结合账本、订单状态和消费位点。",
         "结束；同requestId仍回放原O1", [R("LotteryInvoiceListener.java", 71, 78), R("LotteryInvoiceListener.java", 103, 110)],
         "07-fulfillment", "a15", {"invoiceAck": "已 ack", "response": "本地异步闭环；渠道为模拟"}),
]

def fact(title, body, why, refs):
    return {"title": title, "body": body, "why": why, "sources": refs}

def view(chapter, vid, title, summary, facts=None):
    return {"chapter": chapter, "id": vid, "title": title, "summary": summary, "facts": facts or []}

views = [
    view("overview", "01-layers", "代码分层", "先看每层负责什么。箭头展示主要代码关系；运行时调用在后续时序图展开。", [
        fact("interfaces：请求翻译与启动装配", "校验HTTP、转换DTO；启动模块装入其他模块。Mapper XML物理存放于interfaces资源目录，职责仍是持久化SQL。", "让外部契约与业务规则分开变化。", [R("LotteryController.java", 24, 44), R("interface-pom", 19, 70)]),
        fact("application：安排先后与结果", "串联请求幂等、参与、抽奖、下单、回放；Kafka收发与XXL Job也位于应用层。", "多领域服务完成同一个用例，需要统一编排。", [R("ActivityProcessImpl.java", 62, 180), R("LotteryXxlJob.java", 193, 229)]),
        fact("domain：定义业务规则与端口", "参与资格、概率、订单、活动状态、发奖抽象和Repository接口。仍使用Spring注解及部分框架依赖。", "注入的仓储实现执行存储动作，业务规则不必拼SQL。", [R("BaseActivityPartake.java", 23, 72), R("DrawExecutor.java", 31, 61)]),
        fact("infrastructure：执行副作用", "Repository、DAO/PO、事务、路由、RedisUtil、Guard与缓存实现。", "将领域端口落实为SQL/Redis动作，封装分片上下文。", [R("OrderRepository.java", 38, 65), R("StrategyRepository.java", 87, 110)]),
        fact("common：共同值与枚举", "Result、业务码、状态、RedisKey；五个Maven模块组装成同一Lottery应用。", "统一跨层语义；五模块并非五个微服务。", [R("Constants.java", 10, 23), R("root-pom", 12, 18)]),
    ]),
    view("overview", "02-runtime", "运行组件与存储", "Redis/MySQL/Kafka是外部服务；Caffeine和dbRouter是应用内依赖。运行边界与代码分层分别阅读。", [
        fact("Redis：实时销售、缓存和短锁", "活动/奖品库存预热后不设缓存TTL；配置有TTL；并发锁、投递标记、发奖标记分别3/60/300秒。", "DECR承接竞争；缓存减少读取，锁不记录最终完成。", [R("ActivityRepository.java", 195, 218), R("ActivityRepository.java", 264, 285), R("Constants.java", 335, 398)]),
        fact("MySQL：全局事实与用户事实", "db00保存activity/strategy/detail/award和库存账本。lottery_01/02保存参与、次数、中奖订单。", "唯一键与事务形成最终幂等事实。", [R("StrategyRepository.java", 75, 110), R("UserTakeActivityRepository.java", 93, 117)]),
        fact("dbRouterForGem：选库与订单表", "外部starter按uId选库；参与/次数只分库，订单每库4张_000..003。后台显式遍历dbIdx/tbIdx。", "同用户数据同库可开本地事务；它不是跨库事务协调器或网络网关。", [R("IUserTakeActivityDao.java", 11, 24), R("IUserStrategyExportDao.java", 16, 24), R("OrderRepository.java", 171, 188)]),
        fact("Kafka：两个异步topic", "lottery_strategy_award_stock和lottery_invoice，消息key都是orderId，不同topic位点独立。", "脱离请求线程；允许重复投递，数据库保证重复执行安全。", [R("KafkaProducer.java", 18, 41), R("application.yml", 47, 70)]),
        fact("XXL-Job：三类任务", "活动状态/预热、库存Outbox投递、发奖补偿；执行器在应用内，admin在外部。触发计划需在调度中心配置。", "代码注册不代表环境已经调度；持久责任需要任务实际执行。", [R("LotteryXxlJob.java", 61, 114), R("LotteryXxlJob.java", 115, 184), R("LotteryXxlJob.java", 193, 229)]),
        fact("Caffeine：只缓存策略快照", "L1默认60秒/10000项，Redis L2=600秒+0~120秒；活动只用单级Redis。", "高频配置编译结果复用；没有跨实例广播，TTL内可能读到旧配置。", [R("TwoLevelCacheService.java", 17, 22), R("TwoLevelCacheService.java", 39, 90), R("application.yml", 96, 108)]),
    ]),
    view("draw", "03-participation", "参与与资格", "首次请求：活动100份、优惠券5份、用户3次机会。T1/O1是实际Long类型Snowflake ID的教学代号。"),
    view("draw", "04-draw-order", "中奖与订单", "沿T1继续：策略采样、奖品预扣、TX2与HTTP返回。同步成功仍未完成MySQL奖品结算或发奖。"),
    view("draw", "14-no-prize", "未中奖分支", "未中奖消费take并返回D001；活动机会与次数消耗，但不产生中奖订单或异步消息。"),
    view("async", "05-outbox-dispatch", "Outbox 投递", "从正常中奖订单O1继续。Job扫用户库后发送库存消息，HTTP没有直接发送Kafka。"),
    view("async", "06-stock-settlement", "库存账本结算", "全局库事务绑定唯一账本与奖品库存；用户订单状态独立提交，由重放衔接。"),
    view("async", "07-fulfillment", "发奖与闭环", "四类handler当前都是模拟渠道。本地grant与mq可以收口，外部实际交付尚未实现。"),
    view("async", "08-stock-states", "库存消息状态", "INIT、SENT、COMPLETE、FAIL与BLOCKED表达不同责任。", [
        fact("发送和消费可以交错", "消费从0/2/3/4→1；迟到发送通知不能覆盖1或4。发送成功0/2→3，失败仅0→2。", "数据库业务完成事实优先于迟到回调。", [R("UserStrategyExport_Mapper.xml", 52, 67)]),
        fact("SENT 超时恢复", "Job扫描共享update_time超10分钟的SENT；已有SENT重复回调不刷新时钟。", "可能重复发送；库存安全仍来自唯一账本。", [R("UserStrategyExport_Mapper.xml", 112, 120), R("StrategyRepository.java", 95, 110)]),
    ]),
    view("async", "09-invoice-states", "发奖消息状态", "mq_state负责消息，grant_state负责本地发奖事实。SENT不能当作发奖完成。", [
        fact("补偿同时看三列", "stock=1且grant!=1；FAIL立即扫，INIT创建超30分钟，SENT更新超10分钟；历史mq=1但grant!=1也按10分钟恢复。每表LIMIT100。", "库存未结算不进入发奖；历史误写消息完成不能漏掉未发奖订单。", [R("UserStrategyExport_Mapper.xml", 100, 110), R("LotteryXxlJob.java", 149, 174)]),
        fact("grant 已完成而 mq 未收口", "重放先查grant=1，写mq=1后ack；当前主链路没有写grant=FAIL(2)。", "修复发奖与位点之间的空隙；本地CAS不证明外部恰好一次。", [R("LotteryInvoiceListener.java", 71, 107)]),
    ]),
    view("recovery", "10-request-retry", "重复与中断请求", "区分并发D006、原请求回放/继续、不同请求遇旧take的D007。", [
        fact("同请求已完成", "take=1，有订单回放同O1和奖品；无订单回放D001，不重复扣库存或次数。", "网络超时后取回原结果。", [R("ActivityProcessImpl.java", 208, 229)]),
        fact("同请求未完成", "take=0，继续原strategyId/takeId；尚未持久完成的采样可能再次执行。", "不重复创建机会，但不保证重放尚未落库的随机结果。", [R("ActivityProcessImpl.java", 98, 105), R("ActivityProcessImpl.java", 129, 130)]),
        fact("不同请求不能接管", "B遇A返回D007和pendingRequestId=A。无归属旧take返回D008核实。", "保护机会归属。", [R("BaseActivityPartake.java", 74, 90)]),
        fact("唯一键和消费CAS", "V3为UNIQUE(u_id,activity_id,request_id)；生成列唯一限制一条未完成参与。锁过期竞争用take CAS决定消费权。", "不能只依赖3秒短锁。查询异常直接中断，不当作无记录。", [R("ownership", 35, 61), R("UserTakeActivity_Mapper.xml", 28, 30), R("ActivityProcessImpl.java", 183, 201)]),
    ]),
    view("recovery", "11-stock-key-missing", "Redis 库存缺失", "活动Key在take前检查；奖品Key在take创建后检查。运行期不以MySQL自动回填。", [
        fact("活动 Key 缺失", "D005 + error，不建新take、不扣用户次数。activity数据库不随每次请求实时扣减。", "不能把已消耗活动库存重新上架。", [R("ActivityRepository.java", 195, 224), R("Activity_Mapper.xml", 44, 49)]),
        fact("奖品 Key 缺失与连接异常", "Key缺失soldOut→FAIL→消费take→D001；读连接异常抛出，保留未消费take由原requestId恢复。", "两种现有分支不能混画成自动补库存。", [R("StrategyRepository.java", 122, 142), R("ActivityProcessImpl.java", 132, 150)]),
        fact("启动能补齐，运行恢复未实现", "PASS活动行锁内SETNX全部库存，再CAS DOING；失败保持PASS，已写Key保留，下轮补齐。DOING不能再次预热。", "运行期有未结算责任，需人工核实；没有自动对账算法。", [R("ActivityRepository.java", 113, 133), R("ActivityRepository.java", 243, 285)]),
    ]),
    view("recovery", "12-consumer-crash", "重复消息与宕机", "同orderId崩溃前后靠持久账本接续，不依赖Redis DONE。", [
        fact("TX3 提交后状态没完成", "INSERT相同orderId唯一冲突，成功短路不再扣减；继续stock=1、invoice、ack。", "覆盖库存已扣但应用退出的窗口。", [R("StrategyRepository.java", 87, 110), R("LotteryStrategyAwardStockListener.java", 81, 101)]),
        fact("stock=1 后 invoice 没发", "库存消息重放直接ack；发奖补偿要求stock=1、grant!=1，FAIL或超时INIT等可恢复。", "订单继续保留发奖责任。", [R("LotteryStrategyAwardStockListener.java", 63, 67), R("UserStrategyExport_Mapper.xml", 100, 110)]),
        fact("Kafka 发送失败", "INIT→FAIL，下轮Job重发；已有SENT不被失败回调回退，10分钟可再投。发送成功但落状态前宕机可产生重复消息。", "Outbox解决漏发责任，账本解决重复执行。", [R("LotteryXxlJob.java", 247, 271), R("UserStrategyExport_Mapper.xml", 52, 60)]),
        fact("部署前提", "手动位点提交、异常不ack；没有配置专用死信队列或永久无限重试保证。超时扫描依赖Job真实运行。", "源码恢复路径不等价于未经验证的部署可靠性承诺。", [R("application.yml", 47, 70), R("LotteryInvoiceListener.java", 103, 110)]),
    ]),
    view("recovery", "13-blocked-reconcile", "库存分歧与人工对账", "扣减失败是业务分歧：回滚账本、持久BLOCKED、停止自动扫描，等待人工核实。", [
        fact("先持久化事实才 ack", "独立CAS写4；零行回读确认BLOCKED/COMPLETE，否则抛异常、不ack。", "避免应结算订单没有任何待处理事实。", [R("OrderRepository.java", 126, 142), R("LotteryStrategyAwardStockListener.java", 81, 91)]),
        fact("对账后释放原 Outbox", "人工修复后在正确用户分片按同orderId CAS 4→0，由Job重投。没有自动对账接口或运行期通用修复脚本。", "先修复原因，保持同一个账本幂等键；盲目重试不修复库存事实。", [R("architecture", 44, 48), R("UserStrategyExport_Mapper.xml", 112, 120)]),
    ]),
]

data = {"views": views, "sync": sync, "losing": losing, "async": async_steps,
        "initial": {"activityRedis": 100, "activityMysql": 100, "prizeRedis": 5, "prizeMysql": 5, "quota": 3,
                    "take": "尚未创建", "order": "尚未创建", "stockState": "—", "invoiceState": "—",
                    "grantState": "—", "ledger": "无结算账本", "lock": "无", "response": "尚未返回",
                    "dispatchLock": "无", "invoiceLock": "无", "stockAck": "未确认", "invoiceAck": "未确认"}}

# 合并教学面板与 Archify 规格的源码引用；只导出所引用的范围，不复制配置凭据。
all_refs = [R("ActivityLifecycleService.java", 48, 59), R("SnowflakeIdGenerator.java", 31, 34)]
for entries in [sync, losing, async_steps]:
    for entry in entries: all_refs.extend(entry["sources"])
for current_view in views:
    for item in current_view["facts"]: all_refs.extend(item["sources"])
for candidate in OUT.glob("diagrams/*/candidate.json"):
    spec = json.loads(candidate.read_text())
    for collection in ["components", "participants", "nodes", "states"]:
        for item in spec.get(collection, []): all_refs.extend(item.get("sources", []))

def source_id(path):
    return re.sub(r"[^A-Za-z0-9_-]", "_", path)

ranges = {}
for reference in all_refs:
    path = reference["path"]
    first = reference.get("line", 1)
    last = reference.get("end_line", first)
    ranges.setdefault(path, set()).update(range(first, last + 1))
sources, blocks = {}, []
for path, numbers in sorted(ranges.items()):
    sid, name = source_id(path), Path(path).name
    if name == "pom.xml" and path != name: name = str(Path(path).parent / name)
    sources[path] = {"id": sid, "label": name}
    lines = (ROOT / path).read_text().splitlines()
    chunks, previous = [], 0
    for number in sorted(numbers):
        if number > len(lines): raise ValueError(f"源码引用越界：{name}:{number}")
        if previous and number > previous + 1: chunks.append('<span class="omission">··· 未引用部分省略 ···</span>')
        chunks.append(f'<span class="source-line" id="{sid}-L{number}"><a href="#{sid}-L{number}" class="ln">{number}</a><code>{html.escape(lines[number-1])}</code></span>')
        previous = number
    blocks.append(f'<section class="source-block" id="{sid}"><h2>{html.escape(name)}</h2><a class="original-file" href="../../{html.escape(path, quote=True)}" target="_blank" rel="noopener">本地文件模式：打开原文件</a><pre>{"".join(chunks)}</pre></section>')

data["sources"] = sources
(OUT / "guide-data.js").write_text("// 当前源码教学内容；T1/O1与库存数值均为教学示例。\nwindow.LOTTERY_GUIDE = " + json.dumps(data, ensure_ascii=False, indent=2) + ";\n")

# 使用普通行锚点定位，直接双击 HTML 也可离线阅读。
source_html = '''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Lottery · 源码证据</title><link rel="stylesheet" href="guide.css"><body class="source-page"><header><a href="index.html">← 回到图解</a><h1>沿着图，读到实际代码</h1><p>摘录来自本次读取的当前源码，行号对应实际文件；未引用部分省略，业务代码更新后需重新生成。</p><p id="source-mode"></p><input id="source-search" aria-label="按文件名筛选" placeholder="搜索文件名，例如 OrderRepository"></header><main>''' + "".join(blocks) + '''</main><script>
// 本机HTTP预览只服务图解目录，原项目文件不属于其URL范围。
const localFileMode = location.protocol === 'file:';
// 说明当前可访问范围，保留完整源码摘录与行号定位。
document.querySelector('#source-mode').textContent = localFileMode
  ? '本地文件模式可使用相对链接打开项目原文件。'
  : '当前HTTP预览提供源码摘录；继续阅读原文件时，请在项目目录按文件名打开。';
// 仅本地文件模式展示原文件链接，HTTP模式避免提供无效的跨目录URL。
document.querySelectorAll('.original-file').forEach(link => { link.hidden = !localFileMode; });
// 文件名筛选只隐藏无关摘录，不改变代码内容与行号。
document.querySelector('#source-search').addEventListener('input', function (event) {
  // 当前关键词匹配每个文件的标题，空关键词显示所有文件。
  const query = event.target.value.toLowerCase();
  // 单独切换文件区块的可见性，保持源码锚点稳定。
  document.querySelectorAll('.source-block').forEach(function (section) {
    section.hidden = !section.querySelector('h2').textContent.toLowerCase().includes(query);
  });
});
</script></body></html>'''
(OUT / "sources.html").write_text(source_html)
print(f"已生成 {len(sync)} 个中奖步骤、{len(losing)} 个未中奖步骤、{len(async_steps)} 个异步步骤和 {len(sources)} 个源码文件摘录。")
