package com.anontokyo.lottery.application.worker;

import cn.bugstack.middleware.db.router.strategy.IDBRouterStrategy;
import com.anontokyo.lottery.application.mq.producer.KafkaProducer;
import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityVO;
import com.anontokyo.lottery.domain.activity.repository.IActivityRepository;
import com.anontokyo.lottery.domain.activity.service.deploy.IActivityDeploy;
import com.anontokyo.lottery.domain.activity.service.lifecycle.ActivityLifecycleService;
import com.anontokyo.lottery.domain.award.model.vo.AwardStockSettlementVO;
import com.anontokyo.lottery.domain.award.model.vo.InvoiceVO;
import com.anontokyo.lottery.domain.award.service.order.IAwardOrder;
import com.anontokyo.lottery.domain.support.lock.IStrategyAwardStockMqGuard;
import com.alibaba.fastjson.JSON;
import com.xxl.job.core.context.XxlJobHelper;
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
    private IActivityRepository activityRepository;

    @Resource
    private IDBRouterStrategy dbRouter;

    @Resource
    private IAwardOrder awardOrder;

    @Resource
    private KafkaProducer kafkaProducer;

    @Resource
    private IStrategyAwardStockMqGuard strategyAwardStockMqGuard;

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
                    // 4 通过(READY)：临近活动开启时间由调度切换为运行中；CAS 成功后预热 Redis 库存。
                    case 4:
                        boolean started = activityLifecycleService.transition(activityVO.getActivityId(),
                                Constants.ActivityState.PASS, Constants.ActivityState.DOING);
                        logger.info("scan activity start transition result:{} activityId:{} activityName:{} creator:{}",
                                started, activityVO.getActivityId(), activityVO.getActivityName(),
                                activityVO.getCreator());
                        if (started) {
                            int warmedKeys = activityRepository.warmupStockForActivity(activityVO.getActivityId());
                            logger.info("activity stock warmed up after start activityId:{} keys:{}", activityVO.getActivityId(), warmedKeys);
                        }
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
            logger.warn("skip invoice mq compensation because invoice mq is disabled");
            return;
        }
        // 验证参数
        String jobParam = XxlJobHelper.getJobParam();
        if (jobParam == null) {
            logger.info("扫描用户抽奖奖品发放MQ状态[Table = 2*4] 错误 params is null");
            return;
        }

        String[] params = jobParam.split(",");
        logger.info("扫描用户抽奖奖品发放MQ状态[Table = 2*4] 开始 params：{}", JSON.toJSONString(params));

        if (params.length == 0) {
            logger.info("扫描用户抽奖奖品发放MQ状态[Table = 2*4] 结束 params is null");
            return;
        }

        // 获取分库分表配置下的分表数
        int tbCount = dbRouter.tbCount();

        // 循环获取指定扫描库
        for (String param : params) {
            // 获取当前任务扫描的指定分库
            int dbCount = Integer.parseInt(param);

            if (dbCount > dbRouter.dbCount()) {
                logger.info("扫描用户抽奖奖品发放MQ状态[Table = 2*4] 结束 dbCount not exist");
                continue;
            }

            // 循环扫描对应表
            for (int i = 0; i < tbCount; i++) {

                //  扫描表数据
                List<InvoiceVO> invoiceVOList = awardOrder.scanInvoiceMqState(dbCount, i, INVOICE_COMPENSATION_BATCH_LIMIT);
                logger.info("扫描用户抽奖奖品发放MQ状态[Table = 2*4] 扫描库：{} 扫描表：{} 扫描数：{}", dbCount, i, invoiceVOList.size());

                // 补偿MQ消息
                for (InvoiceVO invoiceVO : invoiceVOList) {

                    ListenableFuture<SendResult<String, Object>> future = kafkaProducer.sendLotteryInvoice(invoiceVO);
                    future.addCallback(new ListenableFutureCallback<SendResult<String, Object>>() {

                        @Override
                        public void onSuccess(SendResult<String, Object> stringObjectSendResult) {
                            // MQ 消息发送完成，更新数据库表 user_strategy_export.mq_state = 1
                            awardOrder.updateInvoiceMqState(invoiceVO.getuId(), invoiceVO.getOrderId(), Constants.MQState.COMPLETE.getCode());
                        }

                        @Override
                        public void onFailure(Throwable throwable) {
                            // MQ 消息发送失败，更新数据库表 user_strategy_export.mq_state = 2 【等待定时任务扫码补偿MQ消息】
                            awardOrder.updateInvoiceMqState(invoiceVO.getuId(), invoiceVO.getOrderId(), Constants.MQState.FAIL.getCode());
                        }

                    });
                }

            }

        }
        logger.info("扫描用户抽奖奖品发放MQ状态[Table = 2*4] 完成 param：{}", JSON.toJSONString(params));
    }

    /**
     * 库存结算 Outbox 扫描投递任务（原 @Scheduled dispatcher 迁移至此）。
     * 任务参数指定本 worker 负责的分库编号，逗号分隔，如 "1" 或 "1,2"：
     * 多实例部署时在 XXL-Job 中配置不同分片参数即可，无需每个实例扫描全部分库。
     * 状态机：INIT(0)/FAIL(2) 扫描 → 投递成功落 SENT(3) → 消费完成 COMPLETE(1)。
     * crash 在发送与落状态之间最多造成一次重复投递，由 settlement 账本兜底幂等。
     */
    @XxlJob("awardStockSettlementOutboxJobHandler")
    public void awardStockSettlementOutboxJobHandler() {
        String jobParam = XxlJobHelper.getJobParam();
        if (jobParam == null || jobParam.trim().isEmpty()) {
            logger.warn("award stock outbox job requires db index param, e.g. \"1\"");
            return;
        }

        int totalDbCount = dbRouter.dbCount();
        int tbCount = dbRouter.tbCount();

        for (String part : jobParam.split(",")) {
            int dbIdx;
            try {
                dbIdx = Integer.parseInt(part.trim());
            } catch (NumberFormatException e) {
                logger.warn("invalid db index param:{}", part);
                continue;
            }
            if (dbIdx < 1 || dbIdx > totalDbCount) {
                logger.warn("db index out of range:{} totalDbCount:{}", dbIdx, totalDbCount);
                continue;
            }

            for (int tbIdx = 0; tbIdx < tbCount; tbIdx++) {
                List<AwardStockSettlementVO> pendingList =
                        awardOrder.scanAwardStockMqState(dbIdx, tbIdx, stockSettlementDispatchBatchSize);
                if (pendingList.isEmpty()) {
                    continue;
                }
                logger.info("scan award stock outbox dbIdx:{} tbIdx:{} size:{}", dbIdx, tbIdx, pendingList.size());
                for (AwardStockSettlementVO settlementVO : pendingList) {
                    dispatchAwardStockSettlement(settlementVO);
                }
            }
        }
    }

    private void dispatchAwardStockSettlement(AwardStockSettlementVO settlementVO) {
        if (settlementVO == null || settlementVO.getOrderId() == null) {
            return;
        }
        Long orderId = settlementVO.getOrderId();
        if (awardOrder.isStockMqCompleted(settlementVO.getuId(), orderId)) {
            strategyAwardStockMqGuard.clearDispatchMarker(orderId);
            return;
        }
        // DISPATCHING 标记用于防止同一轮内多 worker 对同一条记录并发重复投递
        if (!strategyAwardStockMqGuard.tryClaimDispatch(orderId)) {
            return;
        }

        try {
            ListenableFuture<SendResult<String, Object>> future = kafkaProducer.sendStrategyAwardStock(settlementVO);
            future.addCallback(new ListenableFutureCallback<SendResult<String, Object>>() {

                @Override
                public void onSuccess(SendResult<String, Object> result) {
                    awardOrder.updateStockMqState(settlementVO.getuId(), orderId,
                            Constants.MQState.SENT.getCode());
                    strategyAwardStockMqGuard.clearDispatchMarker(orderId);
                    if (logger.isDebugEnabled()) {
                        logger.debug("dispatch award stock settlement mq success uId:{} orderId:{}",
                                settlementVO.getuId(), orderId);
                    }
                }

                @Override
                public void onFailure(Throwable throwable) {
                    awardOrder.updateStockMqState(settlementVO.getuId(), orderId,
                            Constants.MQState.FAIL.getCode());
                    strategyAwardStockMqGuard.clearDispatchMarker(orderId);
                    logger.error("dispatch award stock settlement mq failed uId:{} orderId:{}",
                            settlementVO.getuId(), orderId, throwable);
                }
            });
        } catch (Exception e) {
            awardOrder.updateStockMqState(settlementVO.getuId(), orderId, Constants.MQState.FAIL.getCode());
            strategyAwardStockMqGuard.clearDispatchMarker(orderId);
            logger.error("dispatch award stock settlement mq exception uId:{} orderId:{}",
                    settlementVO.getuId(), orderId, e);
        }
    }
}
