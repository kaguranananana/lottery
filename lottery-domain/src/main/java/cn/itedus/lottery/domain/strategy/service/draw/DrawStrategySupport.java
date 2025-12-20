package cn.itedus.lottery.domain.strategy.service.draw;

import cn.itedus.lottery.domain.strategy.model.aggregates.StrategyRich;
import cn.itedus.lottery.domain.strategy.repository.IStrategyRepository;
import cn.itedus.lottery.infrastructure.po.Award;

import javax.annotation.Resource;

/**
 * @description: 抽奖策略数据支撑, 一些通用的数据服务, 封装了对Repository的调用, 如果未来的数据源从MySQ变成Redis缓存, 或者增加了多级缓存逻辑
 * 只需要修改Support类这一处, 所有子类都自动升级, 无需改动任何业务逻辑
 * @author：anontokyo
 * @date: 2025/12/20
 */
public class DrawStrategySupport extends DrawConfig {

    @Resource
    protected IStrategyRepository strategyRepository;

    /**
     * 查询策略配置信息
     * @param strategyId
     * @return
     */
    protected StrategyRich queryStrategyRich(Long strategyId) {
        return strategyRepository.queryStrategyRich(strategyId);
    }

    /**
     * 查询奖品详情信息
     * @param awardId
     * @return
     */
    protected Award queryAwardInfoById(String awardId) {
        return strategyRepository.queryAwardInfo(awardId);
    }


}
