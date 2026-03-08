package com.anontokyo.lottery.domain.strategy.service.draw.impl;
import com.alibaba.fastjson.JSON;

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
    protected String drawAlgorithm(Long strategyId, IDrawAlgorithm drawAlgorithm, List<String> excludeAwardIds) {
        // 执行抽奖
        String awardId = drawAlgorithm.randomDraw(strategyId, excludeAwardIds);

        // 判断抽奖结果
        if (awardId == null) {
            return null;
        }
        /*
        扣减库存, 暂时使用数据库行级锁的方式进行扣减库存, 后续优化为Redis 分布式锁
         */
        boolean isSuccess = strategyRepository.deductStock(strategyId, awardId);

        // 扣减成功返回奖品id, 否则返回NULL
        return isSuccess ? awardId : null;


    }

}
