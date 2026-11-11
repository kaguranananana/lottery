package com.anontokyo.lottery.application.worker;

import com.anontokyo.lottery.application.mq.producer.KafkaProducer;
import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityVO;
import com.anontokyo.lottery.domain.activity.service.deploy.IActivityDeploy;
import com.anontokyo.lottery.domain.activity.service.lifecycle.ActivityLifecycleService;
import com.anontokyo.lottery.domain.award.model.vo.AwardStockSettlementVO;
import com.anontokyo.lottery.domain.award.model.vo.AwardFulfillmentEvent;
import com.anontokyo.lottery.domain.award.service.order.IAwardOrder;
import com.anontokyo.lottery.domain.support.lock.IStockSettlementGuard;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.ListenableFutureCallback;

import javax.annotation.Resource;
import java.util.Date;
import java.util.List;


/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/29
 */
@Component
public class LotteryXxlJob {

    /** 补偿扫描单次批量上限，避免大表全量扫描 */
    private static final int INVOICE_COMPENSATION_BATCH_LIMIT = 100;
    private Logger logger = LoggerFactory.getLogger(LotteryXxlJob.class);

    @Resource
    private IActivityDeploy activityDeploy;

    @Resource
    private ActivityLifecycleService activityLifecycleService;

    @Resource
    private IAwardOrder awardOrder;

    @Resource
    private KafkaProducer kafkaProducer;

    @Resource
    private IStockSettlementGuard stockSettlementGuard;

    @Value("${lottery.kafka.dispatcher.strategy-award-stock-batch-size:200}")
    private int stockSettlementDispatchBatchSize;

    @Value("${lottery.kafka.invoice.enabled:true}")
    private boolean invoiceMqEnabled;

    @XxlJob("lotteryActivityStateJobHandler")
    public void lotteryActivityStateJobHandler() throws Exception {
        logger.info("扫描活动状态 Begin");

        List<ActivityVO> activityVOList = activityDeploy.scanToDoActivityList(0L);
        if (activityVOList.isEmpty()){
            logger.info("扫描活动状态 End 暂无符合需要扫描的活动列表");
            return;
        }

        while (!activityVOList.isEmpty()) {
            for (ActivityVO activityVO : activityVOList) {
                Integer state = activityVO.getState();
                switch (state) {
                    // 通过的活动先检查时间，仓储在行锁内再次检查并完成预热后才发布运行状态。
                    case 4:
                        Date now = new Date();
                        // 已经过期但尚未启动的活动直接关闭，无须创建库存 Key。
                        if (!activityVO.getEndDateTime().after(now)) {
                            activityLifecycleService.transition(activityVO.getActivityId(),
                                    Constants.ActivityState.PASS, Constants.ActivityState.CLOSE);
                            break;
                        }
                        // 未到开始时间留待下一轮扫描，不提前预热或运行。
                        if (activityVO.getBeginDateTime().after(now)) {
                            break;
                        }
                        boolean started = activityLifecycleService.transition(activityVO.getActivityId(),
                                Constants.ActivityState.PASS, Constants.ActivityState.DOING);
                        logger.info("scan activity start transition result:{} activityId:{} activityName:{} creator:{}",
                                started, activityVO.getActivityId(), activityVO.getActivityName(), activityVO.getCreator());
                        break;
                    // 5 运行中(RUNNING)：扫描到已过期则关闭。CAS 保证并发安全，重复执行幂等。
                    case 5:
                        if (activityVO.getEndDateTime().before(new Date())) {
                            boolean closed = activityLifecycleService.transition(activityVO.getActivityId(),
                                    Constants.ActivityState.DOING, Constants.ActivityState.CLOSE);
                            logger.info("scan activity close transition result:{} activityId:{} creator:{}",
                                    closed, activityVO.getActivityId(), activityVO.getCreator());
                        }
                        break;
                    default:
                        break;
                }
            }

            // 获取集合中最后一条记录，继续扫描后面10条记录
            ActivityVO activityVO = activityVOList.get(activityVOList.size() - 1);
            activityVOList = activityDeploy.scanToDoActivityList(activityVO.getId());
        }

        logger.info("扫描活动状态 End");

    }
    @XxlJob("lotteryOrderMQStateJobHandler")
    public void lotteryOrderMQStateJobHandler() {
        if (!invoiceMqEnabled) {
            logger.warn("skip award fulfillment compensation because invoice mq is disabled");
            return;
        }
        // 单表按批扫描，沿用原状态机与发送回调。
        for (AwardFulfillmentEvent fulfillmentEvent : awardOrder.scanFulfillmentEventStatus(INVOICE_COMPENSATION_BATCH_LIMIT)) {
        ListenableFuture<SendResult<String, Object>> future = kafkaProducer.sendAwardFulfillment(fulfillmentEvent);
        future.addCallback(new ListenableFutureCallback<SendResult<String, Object>>() {

            @Override
            public void onSuccess(SendResult<String, Object> result) {
                // 发送成功只推进 SENT；COMPLETE 由发奖消费者确认，CAS 保护迟到回调。
                awardOrder.updateFulfillmentEventStatus(fulfillmentEvent.getuId(), fulfillmentEvent.getOrderId(), Constants.FulfillmentEventStatus.SENT.getCode());
            }

            @Override
            public void onFailure(Throwable throwable) {
                // 仅未发送状态可进入 FAIL，迟到失败不能覆盖已发送或已完成。
                awardOrder.updateFulfillmentEventStatus(fulfillmentEvent.getuId(), fulfillmentEvent.getOrderId(), Constants.FulfillmentEventStatus.FAIL.getCode());
                // 保留失败 cause 和订单标识，便于定位补偿发送异常。
                logger.error("award fulfillment compensation send failed uId:{} orderId:{}",
                        fulfillmentEvent.getuId(), fulfillmentEvent.getOrderId(), throwable);
            }

        });
        }
    }

    /** 库存结算 Outbox 单表扫描。重复投递仍由结算账本和状态 CAS 处理。 */
    @XxlJob("awardStockSettlementOutboxJobHandler")
    public void awardStockSettlementOutboxJobHandler() {
        List<AwardStockSettlementVO> pending = awardOrder.scanStockSettlementEventStatus(stockSettlementDispatchBatchSize);
        if (!pending.isEmpty()) {
            logger.info("scan award stock outbox size:{}", pending.size());
        }
        for (AwardStockSettlementVO settlement : pending) {
            dispatchAwardStockSettlement(settlement);
        }
    }

    private void dispatchAwardStockSettlement(AwardStockSettlementVO settlementVO) {
        if (settlementVO == null || settlementVO.getOrderId() == null) {
            return;
        }
        Long orderId = settlementVO.getOrderId();
        // 扫描 SQL 已排除 COMPLETE；逐条预读既重复访问数据库，也不能阻止读后的消费竞争。
        // 并发完成后的重复消息由消费者和账本去重，迟到发送通知仍由数据库 CAS 保护终态。
        // DISPATCHING 标记用于防止同一轮内多 worker 对同一条记录并发重复投递
        if (!stockSettlementGuard.tryClaimDispatch(orderId)) {
            return;
        }

        try {
            ListenableFuture<SendResult<String, Object>> future = kafkaProducer.sendStockSettlement(settlementVO);
            future.addCallback(new ListenableFutureCallback<SendResult<String, Object>>() {

                @Override
                public void onSuccess(SendResult<String, Object> result) {
                    awardOrder.updateStockSettlementEventStatus(settlementVO.getuId(), orderId,
                            Constants.StockSettlementEventStatus.SENT.getCode());
                    stockSettlementGuard.clearDispatchMarker(orderId);
                    if (logger.isDebugEnabled()) {
                        logger.debug("dispatch award stock settlement mq success uId:{} orderId:{}",
                                settlementVO.getuId(), orderId);
                    }
                }

                @Override
                public void onFailure(Throwable throwable) {
                    awardOrder.updateStockSettlementEventStatus(settlementVO.getuId(), orderId,
                            Constants.StockSettlementEventStatus.FAIL.getCode());
                    stockSettlementGuard.clearDispatchMarker(orderId);
                    logger.error("dispatch award stock settlement mq failed uId:{} orderId:{}",
                            settlementVO.getuId(), orderId, throwable);
                }
            });
        } catch (Exception e) {
            awardOrder.updateStockSettlementEventStatus(settlementVO.getuId(), orderId, Constants.StockSettlementEventStatus.FAIL.getCode());
            stockSettlementGuard.clearDispatchMarker(orderId);
            logger.error("dispatch award stock settlement mq exception uId:{} orderId:{}",
                    settlementVO.getuId(), orderId, e);
        }
    }
}
