package cn.itedus.lottery.domain.activity.repository;

import cn.itedus.lottery.common.Constants;
import cn.itedus.lottery.domain.activity.model.req.PartakeReq;
import cn.itedus.lottery.domain.activity.model.vo.*;

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

    /**
     * 变更活动状态（状态流转核心能力）。
     *
     * @param activityId  活动ID
     * @param beforeState 变更前状态
     * @param afterState  变更后状态
     */
    boolean alterStatus(Long activityId, Enum<Constants.ActivityState> beforeState, Constants.ActivityState afterState);

    ActivityBillVO queryActivityBill(PartakeReq req);

    int substractionActivityStock(Long activityId);

    /**
     * 扫描待处理的活动列表
     * @param id
     * @return
     */
    List<ActivityVO> scanToDoActivityList(Long id);
}
