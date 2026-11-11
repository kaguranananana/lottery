package com.anontokyo.lottery.domain.draw.service.draw.impl;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.draw.model.req.DrawReq;
import com.anontokyo.lottery.domain.draw.model.res.DrawResult;
import com.anontokyo.lottery.domain.draw.model.snapshot.DrawConfigSnapshot;
import com.anontokyo.lottery.domain.draw.model.vo.AwardBriefVO;
import com.anontokyo.lottery.domain.draw.model.vo.DrawAwardInfo;
import com.anontokyo.lottery.domain.draw.model.vo.DrawConfigPrizeStockDeductResultVO;
import com.anontokyo.lottery.domain.draw.repository.IDrawConfigRepository;
import com.anontokyo.lottery.domain.draw.service.draw.IDrawExec;
import com.anontokyo.lottery.domain.draw.service.draw.WeightedRandomSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * 抽奖执行：读取抽奖配置快照 → 权重随机选择 → Redis 扣减奖品库存。
 * 快照中原始概率语义保持不变；命中已售罄奖品直接视为未中奖，不做重新归一化。
 */
@Service("drawExec")
public class DrawExecutor implements IDrawExec {

    private final Logger logger = LoggerFactory.getLogger(DrawExecutor.class);

    @Resource
    private IDrawConfigRepository drawConfigRepository;

    @Override
    public DrawResult doDrawExec(DrawReq req) {
        DrawConfigSnapshot snapshot = drawConfigRepository.queryDrawConfigSnapshot(req.getDrawConfigId());

        String awardId = WeightedRandomSelector.select(snapshot);
        if (awardId == null) {
            return new DrawResult(req.getuId(), req.getDrawConfigId(), Constants.DrawState.FAIL.getCode());
        }

        DrawConfigPrizeStockDeductResultVO deductResult =
                drawConfigRepository.deductStockByRedis(req.getDrawConfigId(), awardId);
        if (!deductResult.isSuccess()) {
            // 售罄 ⇒ 本次未中奖。禁止把该奖品的权重重新分配给其他奖品。
            logger.warn("award sold out treated as no prize uId:{} drawConfigId:{} awardId:{}",
                    req.getuId(), req.getDrawConfigId(), awardId);
            return new DrawResult(req.getuId(), req.getDrawConfigId(), Constants.DrawState.FAIL.getCode());
        }

        AwardBriefVO award = snapshot.getAwardInfos().get(awardId);
        DrawAwardInfo drawAwardInfo = new DrawAwardInfo(
                award.getAwardId(),
                award.getAwardType(),
                award.getAwardName(),
                award.getAwardContent());
        drawAwardInfo.setGrantType(snapshot.getGrantType());
        drawAwardInfo.setScheduledAt(snapshot.getScheduledAt());

        return new DrawResult(req.getuId(), req.getDrawConfigId(),
                Constants.DrawState.SUCCESS.getCode(), drawAwardInfo);
    }

    @Override
    public void recoverDrawAwardStock(Long drawConfigId, String awardId) {
        drawConfigRepository.recoverDrawConfigPrizeStockByRedis(drawConfigId, awardId);
    }
}
