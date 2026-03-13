package com.anontokyo.lottery.domain.strategy.service.draw.impl;
import com.alibaba.fastjson.JSON;

import com.anontokyo.lottery.domain.strategy.model.vo.StrategyDetailBriefVO;
import com.anontokyo.lottery.domain.strategy.service.algorithm.IDrawAlgorithm;
import com.anontokyo.lottery.domain.strategy.service.draw.AbstractDrawBase;

import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;


import java.util.List;
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
    protected List<String> queryExcludeAwardIds(Long strategyId) {
        List<String> awardList = strategyRepository.queryNoStockStrategyAwardList(strategyId);
        logger.info("执行抽奖策略:strategyId:{}, 无库存排除奖品列表ID集合 awardList:{}", strategyId, JSON.toJSONString(awardList));
        return awardList;
    }

    @Override
    protected String drawAlgorithm(Long strategyId, IDrawAlgorithm drawAlgorithm, List<String> excludeAwardIds,
            List<StrategyDetailBriefVO> strategyDetailList) {
        // 执行抽奖
        String awardId = drawAlgorithm.randomDraw(strategyId, excludeAwardIds);

        // 判断抽奖结果
        if (awardId == null) {
            return null;
        }

        // 从策略明细中查找该奖品的总库存
        Integer awardCount = null;
        for (StrategyDetailBriefVO detail : strategyDetailList) {
            if (detail.getAwardId().equals(awardId)) {
                awardCount = Integer.parseInt(detail.getAwardCount());
                break;
            }
        }

        // 扣减库存, 通过Redis分布式锁方式扣减（替代原有的数据库行级锁方式）
        boolean isSuccess = strategyRepository.deductStockByRedis(strategyId, awardId, awardCount);

        // 扣减成功返回奖品id, 否则返回NULL（库存不足/锁竞争失败）
        return isSuccess ? awardId : null;

    }

}
