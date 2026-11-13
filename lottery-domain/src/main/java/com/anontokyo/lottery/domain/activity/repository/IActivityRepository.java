package com.anontokyo.lottery.domain.activity.repository;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.activity.model.aggregates.ActivityInfoLimitPageRich;
import com.anontokyo.lottery.domain.activity.model.req.ActivityInfoLimitPageReq;
import com.anontokyo.lottery.domain.activity.model.req.PartakeReq;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityBillVO;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityVO;
import com.anontokyo.lottery.domain.activity.model.vo.AwardVO;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityPrizeVO;

import java.util.List;

public interface IActivityRepository {

    void addActivity(ActivityVO activity);

    void addAward(List<AwardVO> awardList);


    void addActivityPrizeList(List<ActivityPrizeVO> activityPrizeList);

    boolean alterStatus(Long activityId, Constants.ActivityState beforeState, Constants.ActivityState afterState);

    ActivityBillVO queryActivityBill(PartakeReq req);

    List<ActivityVO> scanToDoActivityList(Long id);

    /**
     * 仅 READY 活动可预热本奖池；活动行锁内执行 SETNX，保留已有值。
     * 已投入使用的奖池禁止按滞后 MySQL 库存回填。
     * @return 实际初始化的奖品 Key 数量
     */
    int warmupAwardStockForActivity(Long activityId);

    ActivityInfoLimitPageRich queryActivityInfoLimitPage(ActivityInfoLimitPageReq req);

    List<ActivityVO> queryRunningActivityList();
}
