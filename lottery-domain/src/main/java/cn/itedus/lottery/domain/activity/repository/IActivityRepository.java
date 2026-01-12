package cn.itedus.lottery.domain.activity.repository;

import cn.itedus.lottery.domain.activity.model.vo.ActivityVO;
import cn.itedus.lottery.domain.activity.model.vo.AwardVO;
import cn.itedus.lottery.domain.activity.model.vo.StrategyDetailVO;
import cn.itedus.lottery.domain.activity.model.vo.StrategyVO;

import java.util.List;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/12
 */
public interface IActivityRepository {

    /**
     * 添加活动配置
     * @param activity
     */
    void addActivity(ActivityVO activity);

    /**
     * 添加奖品配置信息
     * @param awardList
     */
    void addAward(List<AwardVO> awardList);


    /**
     * 添加策略配置
     * @param strategy
     */
    void addStrategy(StrategyVO strategy);

    /**
     * 添加策略明细配置
     * @param strategyDetailList
     */
    void addStrategyDetailList(List<StrategyDetailVO> strategyDetailList);

}
