package com.anontokyo.lottery.domain.strategy.service.draw;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.strategy.model.aggregates.StrategyRich;
import com.anontokyo.lottery.domain.strategy.model.req.DrawReq;
import com.anontokyo.lottery.domain.strategy.model.res.DrawResult;
import com.anontokyo.lottery.domain.strategy.model.vo.AwardBriefVO;
import com.anontokyo.lottery.domain.strategy.model.vo.AwardRateInfo;
import com.anontokyo.lottery.domain.strategy.model.vo.DrawAwardInfo;
import com.anontokyo.lottery.domain.strategy.model.vo.StrategyBriefVO;
import com.anontokyo.lottery.domain.strategy.model.vo.StrategyDetailBriefVO;
import com.anontokyo.lottery.domain.strategy.service.algorithm.IDrawAlgorithm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public abstract class AbstractDrawBase extends DrawStrategySupport implements IDrawExec {

    private final Logger logger = LoggerFactory.getLogger(AbstractDrawBase.class);

    @Override
    public DrawResult doDrawExec(DrawReq req) {
        StrategyRich strategyRich = super.queryStrategyRich(req.getStrategyId());
        StrategyBriefVO strategy = strategyRich.getStrategy();

        this.checkAndInitRateData(strategy.getStrategyId(), strategy.getStrategyMode(),
                strategyRich.getStrategyDetailList());

        String awardId = this.drawAlgorithm(req.getStrategyId(), drawAlgorithmGroup.get(strategy.getStrategyMode()),
                strategyRich.getStrategyDetailList());

        return buildDrawResult(req.getuId(), req.getStrategyId(), awardId, strategy);
    }

    @Override
    public void recoverDrawAwardStock(Long strategyId, String awardId) {
        super.recoverAwardStockByRedis(strategyId, awardId);
    }

    public void checkAndInitRateData(Long strategyId, Integer strategyMode,
            List<StrategyDetailBriefVO> strategyDetailList) {
        IDrawAlgorithm drawAlgorithm = drawAlgorithmGroup.get(strategyMode);
        if (drawAlgorithm.isInitialized(strategyId)) {
            return;
        }

        synchronized (drawAlgorithm) {
            if (drawAlgorithm.isInitialized(strategyId)) {
                return;
            }

            List<AwardRateInfo> awardRateInfoList = new ArrayList<>(strategyDetailList.size());
            for (StrategyDetailBriefVO strategyDetail : strategyDetailList) {
                awardRateInfoList.add(new AwardRateInfo(strategyDetail.getAwardId(), strategyDetail.getAwardRate()));
            }

            drawAlgorithm.initRateData(strategyId, awardRateInfoList);
        }
    }

    protected abstract String drawAlgorithm(Long strategyId, IDrawAlgorithm drawAlgorithm,
            List<StrategyDetailBriefVO> strategyDetailList);

    private DrawResult buildDrawResult(String uId, Long strategyId, String awardId, StrategyBriefVO strategy) {
        if (awardId == null) {
            if (logger.isDebugEnabled()) {
                logger.debug("draw miss uId:{} strategyId:{}", uId, strategyId);
            }
            return new DrawResult(uId, strategyId, Constants.DrawState.FAIL.getCode());
        }

        AwardBriefVO award = super.queryAwardInfoById(awardId);
        DrawAwardInfo drawAwardInfo = new DrawAwardInfo(award.getAwardId(), award.getAwardType(), award.getAwardName(),
                award.getAwardContent());
        drawAwardInfo.setStrategyMode(strategy.getStrategyMode());
        drawAwardInfo.setGrantType(strategy.getGrantType());
        drawAwardInfo.setGrantDate(strategy.getGrantDate());

        if (logger.isDebugEnabled()) {
            logger.debug("draw hit uId:{} strategyId:{} awardId:{} awardName:{}", uId, strategyId, awardId,
                    award.getAwardName());
        }

        return new DrawResult(uId, strategyId, Constants.DrawState.SUCCESS.getCode(), drawAwardInfo);
    }
}
