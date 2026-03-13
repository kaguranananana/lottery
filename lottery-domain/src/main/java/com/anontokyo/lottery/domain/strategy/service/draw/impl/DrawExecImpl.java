package com.anontokyo.lottery.domain.strategy.service.draw.impl;
import com.alibaba.fastjson.JSON;

import com.anontokyo.lottery.domain.strategy.model.vo.StrategyDetailBriefVO;
import com.anontokyo.lottery.domain.strategy.model.vo.StrategyAwardStockDeductResultVO;
import com.anontokyo.lottery.domain.strategy.service.algorithm.IDrawAlgorithm;
import com.anontokyo.lottery.domain.strategy.service.draw.AbstractDrawBase;

import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;


import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/12
 */


@Service("drawExec")
public class DrawExecImpl extends AbstractDrawBase{

    private Logger logger = LoggerFactory.getLogger(DrawExecImpl.class);

    @Override
    protected String drawAlgorithm(Long strategyId, IDrawAlgorithm drawAlgorithm,
            List<StrategyDetailBriefVO> strategyDetailList) {
        // 已售罄奖品仅从 Redis 读取，不再回源 DB 查询排除列表。
        List<String> initialExclude = strategyRepository.queryNoStockStrategyAwardList(strategyId);
        List<String> excludeAwardIds = new ArrayList<>(initialExclude);
        Set<String> excludeAwardIdSet = new HashSet<>(initialExclude);

        logger.info("执行抽奖策略:strategyId:{}, 初始无库存排除奖品列表:{}", strategyId, JSON.toJSONString(initialExclude));

        // 失败重试：抽中售罄奖品时动态加入排除集，避免同一请求内重复命中无效奖品。
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
