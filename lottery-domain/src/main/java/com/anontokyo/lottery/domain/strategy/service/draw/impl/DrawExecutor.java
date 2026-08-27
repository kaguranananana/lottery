package com.anontokyo.lottery.domain.strategy.service.draw.impl;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.strategy.model.aggregates.StrategyRich;
import com.anontokyo.lottery.domain.strategy.model.req.DrawReq;
import com.anontokyo.lottery.domain.strategy.model.res.DrawResult;
import com.anontokyo.lottery.domain.strategy.model.snapshot.StrategySnapshot;
import com.anontokyo.lottery.domain.strategy.model.vo.AwardBriefVO;
import com.anontokyo.lottery.domain.strategy.model.vo.DrawAwardInfo;
import com.anontokyo.lottery.domain.strategy.model.vo.StrategyAwardStockDeductResultVO;
import com.anontokyo.lottery.domain.strategy.repository.IStrategyRepository;
import com.anontokyo.lottery.domain.strategy.service.draw.IDrawExec;
import com.anontokyo.lottery.domain.strategy.service.draw.WeightedRandomSelector;
import com.anontokyo.lottery.domain.strategy.service.snapshot.StrategySnapshotFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * 抽奖执行：加载策略快照 → 权重随机 → Redis 扣减奖品库存。
 * 快照中原始概率语义保持不变；命中已售罄奖品直接视为未中奖，不做重新归一化。
 */
@Service("drawExec")
public class DrawExecutor implements IDrawExec {

    private final Logger logger = LoggerFactory.getLogger(DrawExecutor.class);

    @Resource
    private IStrategyRepository strategyRepository;

    @Override
    public DrawResult doDrawExec(DrawReq req) {
        StrategyRich rich = strategyRepository.queryStrategyRich(req.getStrategyId());

        StrategySnapshotFactory.AwardInfoResolver resolver =
                awardId -> strategyRepository.queryAwardInfo(awardId);
        StrategySnapshot snapshot = new StrategySnapshotFactory().build(rich, resolver);

        String awardId = WeightedRandomSelector.select(snapshot);
        if (awardId == null) {
            return new DrawResult(req.getuId(), req.getStrategyId(), Constants.DrawState.FAIL.getCode());
        }

        StrategyAwardStockDeductResultVO deductResult = strategyRepository.deductStockByRedis(
                req.getStrategyId(), awardId);
        if (!deductResult.isSuccess()) {
            // 售罄 ⇒ 本次未中奖。禁止把该奖品的权重重新分配给其他奖品。
            logger.warn("award sold out treated as no prize uId:{} strategyId:{} awardId:{}",
                    req.getuId(), req.getStrategyId(), awardId);
            return new DrawResult(req.getuId(), req.getStrategyId(), Constants.DrawState.FAIL.getCode());
        }

        AwardBriefVO award = snapshot.getAwardInfos().get(awardId);
        DrawAwardInfo drawAwardInfo = new DrawAwardInfo(
                award.getAwardId(),
                award.getAwardType(),
                award.getAwardName(),
                award.getAwardContent());
        drawAwardInfo.setStrategyMode(snapshot.getStrategyMode());
        drawAwardInfo.setGrantType(snapshot.getGrantType());
        drawAwardInfo.setGrantDate(snapshot.getGrantDate());

        return new DrawResult(req.getuId(), req.getStrategyId(), Constants.DrawState.SUCCESS.getCode(), drawAwardInfo);
    }

    @Override
    public void recoverDrawAwardStock(Long strategyId, String awardId) {
        strategyRepository.recoverStrategyAwardStockByRedis(strategyId, awardId);
    }
}
