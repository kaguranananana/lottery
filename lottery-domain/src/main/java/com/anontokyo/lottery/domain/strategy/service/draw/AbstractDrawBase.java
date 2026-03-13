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
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/12
 */

public abstract class AbstractDrawBase extends DrawStrategySupport implements IDrawExec {

    private Logger logger = LoggerFactory.getLogger(AbstractDrawBase.class);

    @Override
    public DrawResult doDrawExec(DrawReq req) {
        // 获取抽奖策略
        StrategyRich strategyRich = super.queryStrategyRich(req.getStrategyId());
        StrategyBriefVO strategy = strategyRich.getStrategy();

        // 校验抽奖策略是否已经初始化到内存
        this.checkAndInitRateData(strategy.getStrategyId(), strategy.getStrategyMode(),
                strategyRich.getStrategyDetailList());

        // 执行抽奖算法
        String awardId = this.drawAlgorithm(req.getStrategyId(), drawAlgorithmGroup.get(strategy.getStrategyMode()),
                strategyRich.getStrategyDetailList());

        // 包装中奖结果
        return buildDrawResult(req.getuId(), req.getStrategyId(), awardId, strategy);
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
            logger.info("执行策略抽奖完成【未中奖】，用户：{} 策略ID：{}", uId, strategyId);
            return new DrawResult(uId, strategyId, Constants.DrawState.FAIL.getCode());
        }

        AwardBriefVO award = super.queryAwardInfoById(awardId);
        DrawAwardInfo drawAwardInfo = new DrawAwardInfo(award.getAwardId(), award.getAwardType(), award.getAwardName(),
                award.getAwardContent());

        // 设置策略相关字段
        drawAwardInfo.setStrategyMode(strategy.getStrategyMode());
        drawAwardInfo.setGrantType(strategy.getGrantType());
        drawAwardInfo.setGrantDate(strategy.getGrantDate());

        logger.info("执行策略抽奖完成【已中奖】，用户：{} 策略ID：{} 奖品ID：{} 奖品名称：{}", uId, strategyId, awardId, award.getAwardName());

        return new DrawResult(uId, strategyId, Constants.DrawState.SUCCESS.getCode(), drawAwardInfo);
    }

}
