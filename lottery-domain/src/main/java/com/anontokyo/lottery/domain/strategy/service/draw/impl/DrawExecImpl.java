package com.anontokyo.lottery.domain.strategy.service.draw.impl;

import com.alibaba.fastjson.JSON;
import com.anontokyo.lottery.domain.strategy.model.vo.StrategyAwardStockDeductResultVO;
import com.anontokyo.lottery.domain.strategy.model.vo.StrategyDetailBriefVO;
import com.anontokyo.lottery.domain.strategy.service.algorithm.IDrawAlgorithm;
import com.anontokyo.lottery.domain.strategy.service.draw.AbstractDrawBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service("drawExec")
public class DrawExecImpl extends AbstractDrawBase {

    private final Logger logger = LoggerFactory.getLogger(DrawExecImpl.class);

    @Override
    protected String drawAlgorithm(Long strategyId, IDrawAlgorithm drawAlgorithm,
            List<StrategyDetailBriefVO> strategyDetailList) {
        List<String> initialExclude = strategyRepository.queryNoStockStrategyAwardList(strategyId);
        List<String> excludeAwardIds = new ArrayList<>(initialExclude);
        Set<String> excludeAwardIdSet = new HashSet<>(initialExclude);

        if (logger.isDebugEnabled()) {
            logger.debug("draw algorithm strategyId:{} initialExcludeAwardIds:{}", strategyId,
                    JSON.toJSONString(initialExclude));
        }

        int maxRetryTimes = Math.max(3, strategyDetailList.size() * 2);
        for (int i = 0; i < maxRetryTimes; i++) {
            String awardId = drawAlgorithm.randomDraw(strategyId, excludeAwardIds);
            if (awardId == null) {
                if (excludeAwardIdSet.size() >= strategyDetailList.size()) {
                    return null;
                }
                continue;
            }

            StrategyAwardStockDeductResultVO deductResult = strategyRepository.deductStockByRedis(strategyId, awardId);
            if (deductResult.isSuccess()) {
                return awardId;
            }

            if (deductResult.isSoldOut() && excludeAwardIdSet.add(awardId)) {
                excludeAwardIds.add(awardId);
                if (excludeAwardIdSet.size() >= strategyDetailList.size()) {
                    return null;
                }
            }
        }

        return null;
    }
}
