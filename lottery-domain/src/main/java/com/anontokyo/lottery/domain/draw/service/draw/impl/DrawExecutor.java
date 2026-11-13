package com.anontokyo.lottery.domain.draw.service.draw.impl;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.draw.model.req.DrawReq;
import com.anontokyo.lottery.domain.draw.model.res.DrawResult;
import com.anontokyo.lottery.domain.draw.model.snapshot.ActivityPrizeSnapshot;
import com.anontokyo.lottery.domain.draw.model.vo.AwardBriefVO;
import com.anontokyo.lottery.domain.draw.model.vo.DrawAwardInfo;
import com.anontokyo.lottery.domain.draw.model.vo.ActivityPrizeStockDeductResultVO;
import com.anontokyo.lottery.domain.draw.repository.IActivityPrizeRepository;
import com.anontokyo.lottery.domain.draw.service.draw.IDrawExec;
import com.anontokyo.lottery.domain.draw.service.draw.WeightedRandomSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * 抽奖执行：读取活动奖池快照 → 权重随机选择 → Redis 扣减奖品库存。
 * 快照中原始概率语义保持不变；命中已售罄奖品直接视为未中奖，不做重新归一化。
 */
@Service("drawExec")
public class DrawExecutor implements IDrawExec {

    private final Logger logger = LoggerFactory.getLogger(DrawExecutor.class);

    @Resource
    private IActivityPrizeRepository activityPrizeRepository;

    @Override
    public DrawResult doDrawExec(DrawReq req) {
        ActivityPrizeSnapshot snapshot = activityPrizeRepository.queryActivityPrizeSnapshot(req.getActivityId());

        String awardId = WeightedRandomSelector.select(snapshot);
        if (awardId == null) {
            return new DrawResult(req.getuId(), req.getActivityId(), Constants.DrawState.FAIL.getCode());
        }

        ActivityPrizeStockDeductResultVO deductResult =
                activityPrizeRepository.deductStockByRedis(req.getActivityId(), awardId);
        if (!deductResult.isSuccess()) {
            // 售罄 ⇒ 本次未中奖。禁止把该奖品的权重重新分配给其他奖品。
            logger.warn("award sold out treated as no prize uId:{} activityId:{} awardId:{}",
                    req.getuId(), req.getActivityId(), awardId);
            return new DrawResult(req.getuId(), req.getActivityId(), Constants.DrawState.FAIL.getCode());
        }

        AwardBriefVO award = snapshot.getAwardInfos().get(awardId);
        // 订单、结算和回补使用同一个被预扣的奖池奖品标识。
        DrawAwardInfo drawAwardInfo = new DrawAwardInfo(
                awardId,
                award.getAwardType(),
                award.getAwardName(),
                award.getAwardContent());

        return new DrawResult(req.getuId(), req.getActivityId(),
                Constants.DrawState.SUCCESS.getCode(), drawAwardInfo);
    }

    @Override
    public void recoverDrawAwardStock(Long activityId, String awardId) {
        activityPrizeRepository.recoverActivityPrizeStockByRedis(activityId, awardId);
    }
}
