package com.anontokyo.lottery.application.process.draw.impl;

import com.anontokyo.lottery.application.mq.producer.KafkaProducer;
import com.anontokyo.lottery.application.process.draw.IActivityDrawProcess;
import com.anontokyo.lottery.application.process.draw.req.DrawProcessReq;
import com.anontokyo.lottery.application.process.draw.res.DrawProcessResult;
import com.anontokyo.lottery.application.process.draw.res.RuleQuantificationCrowdResult;
import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.common.Result;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityPartakeRecordVO;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityVO;
import com.anontokyo.lottery.domain.activity.model.vo.DrawOrderVO;
import com.anontokyo.lottery.domain.activity.model.req.PartakeReq;
import com.anontokyo.lottery.domain.activity.model.res.PartakeResult;
import com.anontokyo.lottery.domain.activity.model.vo.InvoiceVO;
import com.anontokyo.lottery.domain.activity.repository.IActivityRepository;
import com.anontokyo.lottery.domain.activity.service.partake.IActivityPartake;
import com.anontokyo.lottery.domain.rule.model.req.DecisionMatterReq;
import com.anontokyo.lottery.domain.rule.model.res.EngineResult;
import com.anontokyo.lottery.domain.rule.model.vo.RuleCriteriaVO;
import com.anontokyo.lottery.domain.rule.model.vo.TreeRootVO;
import com.anontokyo.lottery.domain.rule.repository.IRuleRepository;
import com.anontokyo.lottery.domain.rule.service.engine.EngineFilter;
import com.anontokyo.lottery.domain.strategy.repository.IStrategyRepository;
import com.anontokyo.lottery.domain.strategy.model.req.DrawReq;
import com.anontokyo.lottery.domain.strategy.model.res.DrawResult;
import com.anontokyo.lottery.domain.strategy.model.vo.DrawAwardInfo;
import com.anontokyo.lottery.domain.strategy.service.draw.IDrawExec;
import com.anontokyo.lottery.domain.support.ids.IIdGenerator;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.ListenableFutureCallback;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;

/**
 * @description: 活动抽奖流程编排
 * @author：anontokyo
 * @date: 2026/1/22
 */
@Service
public class ActivityProcessImpl implements IActivityDrawProcess {

    @Resource
    private IActivityPartake activityPartake;

    @Resource
    private IDrawExec drawExec;

    @Resource
    private Map<Constants.Ids, IIdGenerator> idGeneratorMap;

    @Resource(name = "ruleEngineHandle")
    private EngineFilter engineFilter;

    @Resource
    private KafkaProducer kafkaProducer;

    @Resource
    private IActivityRepository activityRepository;

    @Resource
    private IRuleRepository ruleRepository;

    @Resource
    private IStrategyRepository strategyRepository;

    @Override
    public DrawProcessResult doDrawProcess(DrawProcessReq req) {

        // Step 1: 先领取活动资格（会校验活动状态/时间/次数，并处理未消费领取单复用）。
        PartakeResult partakeResult = activityPartake.doPartake(new PartakeReq(req.getuId(), req.getActivityId()));
        // 只有“领取成功”或“存在未消费领取单”两种情况才继续往下走。
        if (!Constants.ResponseCode.SUCCESS.getCode().equals(partakeResult.getCode())
                && !Constants.ResponseCode.NOT_CONSUMED_TAKE.getCode().equals(partakeResult.getCode())) {
            return new DrawProcessResult(partakeResult.getCode(), partakeResult.getInfo());
        }

        // Step 2: 首次领取成功时，异步通知活动库存落库（Redis预扣成功后再由MQ异步更新DB库存）。
        if (Constants.ResponseCode.SUCCESS.getCode().equals(partakeResult.getCode())) {
            ActivityPartakeRecordVO activityPartakeRecord = new ActivityPartakeRecordVO();
            activityPartakeRecord.setuId(req.getuId());
            activityPartakeRecord.setActivityId(req.getActivityId());
            activityPartakeRecord.setStockCount(partakeResult.getStockCount());
            activityPartakeRecord.setStockSurplusCount(partakeResult.getStockSurplusCount());

            kafkaProducer.sendLotteryActivityPartakeRecord(activityPartakeRecord);
        }

        Long strategyId = partakeResult.getStrategyId();
        Long takeId = partakeResult.getTakeId();

        // Step 3: 执行抽奖算法（基于策略计算是否中奖、命中哪个奖品）。
        DrawResult drawResult = drawExec.doDrawExec(new DrawReq(req.getuId(), strategyId, String.valueOf(takeId)));
        if (Constants.DrawState.FAIL.getCode().equals(drawResult.getDrawState())) {
            // 未中奖也要把领取单置为已消费，避免同一领取单被重复使用。
            Result consumeTakeResult = activityPartake.consumeTakeActivity(req.getuId(), req.getActivityId(), takeId);
            if (!Constants.ResponseCode.SUCCESS.getCode().equals(consumeTakeResult.getCode())
                    && !Constants.ResponseCode.NO_UPDATE.getCode().equals(consumeTakeResult.getCode())) {
                return new DrawProcessResult(consumeTakeResult.getCode(), consumeTakeResult.getInfo());
            }
            return new DrawProcessResult(Constants.ResponseCode.LOSING_DRAW.getCode(),
                    Constants.ResponseCode.LOSING_DRAW.getInfo());
        }
        DrawAwardInfo drawAwardInfo = drawResult.getDrawAwardInfo();

        // Step 4: 中奖后先扣减策略奖品DB库存；失败则直接返回，避免生成无库存订单。
        boolean deductSuccess = strategyRepository.deductStock(strategyId, drawAwardInfo.getAwardId());
        if (!deductSuccess) {
            // 回收Redis侧奖品库存占位，避免缓存与DB库存长期不一致。
            strategyRepository.recoverStrategyAwardStockByRedis(strategyId, drawAwardInfo.getAwardId());
            return new DrawProcessResult(Constants.ResponseCode.UN_ERROR.getCode(), "奖品库存扣减失败");
        }

        // Step 5: 构建并落库中奖订单（与领取单状态变更在同一事务中处理）。
        DrawOrderVO drawOrderVO = buildDrawOrderVO(req, strategyId, takeId, drawAwardInfo);
        Result recordResult;
        try {
            recordResult = activityPartake.recordDrawOrder(drawOrderVO);
        } catch (Exception e) {
            // 落库异常时做库存补偿，尽量把DB与Redis状态回滚到一致。
            strategyRepository.recoverStock(strategyId, drawAwardInfo.getAwardId());
            strategyRepository.recoverStrategyAwardStockByRedis(strategyId, drawAwardInfo.getAwardId());
            throw e;
        }
        if (!Constants.ResponseCode.SUCCESS.getCode().equals(recordResult.getCode())) {
            // 业务失败同样补偿库存，避免“订单没落成但库存已扣”。
            strategyRepository.recoverStock(strategyId, drawAwardInfo.getAwardId());
            strategyRepository.recoverStrategyAwardStockByRedis(strategyId, drawAwardInfo.getAwardId());
            return new DrawProcessResult(recordResult.getCode(), recordResult.getInfo());
        }

        // Step 6: 发送发奖MQ，交由异步消费者执行发货；并在回调中更新mq_state。
        InvoiceVO invoiceVO = buildInvoice(drawOrderVO);
        sendInvoiceMessage(invoiceVO);

        return new DrawProcessResult(Constants.ResponseCode.SUCCESS.getCode(), Constants.ResponseCode.SUCCESS.getInfo(),
                drawAwardInfo);
    }

    private InvoiceVO buildInvoice(DrawOrderVO drawOrderVO) {
        InvoiceVO invoiceVO = new InvoiceVO();
        invoiceVO.setuId(drawOrderVO.getuId());
        invoiceVO.setOrderId(drawOrderVO.getOrderId());
        invoiceVO.setAwardId(drawOrderVO.getAwardId());
        invoiceVO.setAwardType(drawOrderVO.getAwardType());
        invoiceVO.setAwardName(drawOrderVO.getAwardName());
        invoiceVO.setAwardContent(drawOrderVO.getAwardContent());
        return invoiceVO;
    }

    private void sendInvoiceMessage(InvoiceVO invoiceVO) {
        try {
            ListenableFuture<SendResult<String, Object>> future = kafkaProducer.sendLotteryInvoice(invoiceVO);
            future.addCallback(new ListenableFutureCallback<SendResult<String, Object>>() {

                @Override
                public void onSuccess(SendResult<String, Object> stringObjectSendResult) {
                    activityPartake.updateInvoiceMqState(invoiceVO.getuId(), invoiceVO.getOrderId(),
                            Constants.MQState.COMPLETE.getCode());
                }

                @Override
                public void onFailure(Throwable throwable) {
                    activityPartake.updateInvoiceMqState(invoiceVO.getuId(), invoiceVO.getOrderId(),
                            Constants.MQState.FAIL.getCode());
                }
            });
        } catch (Exception e) {
            activityPartake.updateInvoiceMqState(invoiceVO.getuId(), invoiceVO.getOrderId(),
                    Constants.MQState.FAIL.getCode());
        }
    }

    @Override
    public RuleQuantificationCrowdResult doRuleQuantificationCrowd(DecisionMatterReq req) {
        // 调用规则引擎进行人群决策
        EngineResult engineResult = engineFilter.process(req);

        // 判断决策结果
        if (!engineResult.isSuccess()) {
            return new RuleQuantificationCrowdResult(
                    Constants.ResponseCode.UN_ERROR.getCode(),
                    Constants.ResponseCode.UN_ERROR.getInfo());
        }

        // 返回决策的活动ID
        return new RuleQuantificationCrowdResult(
                Constants.ResponseCode.SUCCESS.getCode(),
                Constants.ResponseCode.SUCCESS.getInfo(),
                Long.valueOf(engineResult.getNodeValue()));
    }

    @Override
    public List<ActivityVO> queryRunningActivityList() {
        return activityRepository.queryRunningActivityList();
    }

    @Override
    public List<TreeRootVO> queryRuleTreeList() {
        return ruleRepository.queryRuleTreeList();
    }

    @Override
    public List<RuleCriteriaVO> queryRuleCriteria() {
        return ruleRepository.queryRuleCriteria();
    }

    private DrawOrderVO buildDrawOrderVO(DrawProcessReq req, Long strategyId, Long takeId,
            DrawAwardInfo drawAwardInfo) {
        long orderId = idGeneratorMap.get(Constants.Ids.SnowFlake).nextId();
        DrawOrderVO drawOrderVO = new DrawOrderVO();
        drawOrderVO.setuId(req.getuId());
        drawOrderVO.setTakeId(takeId);
        drawOrderVO.setActivityId(req.getActivityId());
        drawOrderVO.setOrderId(orderId);
        drawOrderVO.setStrategyId(strategyId);
        drawOrderVO.setStrategyMode(drawAwardInfo.getStrategyMode());
        drawOrderVO.setGrantType(drawAwardInfo.getGrantType());
        drawOrderVO.setGrantDate(drawAwardInfo.getGrantDate());
        drawOrderVO.setGrantState(Constants.GrantState.INIT.getCode());
        drawOrderVO.setAwardId(drawAwardInfo.getAwardId());
        drawOrderVO.setAwardType(drawAwardInfo.getAwardType());
        drawOrderVO.setAwardName(drawAwardInfo.getAwardName());
        drawOrderVO.setAwardContent(drawAwardInfo.getAwardContent());
        drawOrderVO.setMqState(Constants.MQState.INIT.getCode());
        return drawOrderVO;
    }
}
