package com.anontokyo.lottery.domain.activity.repository;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.activity.model.aggregates.ActivityInfoLimitPageRich;
import com.anontokyo.lottery.domain.activity.model.req.ActivityInfoLimitPageReq;
import com.anontokyo.lottery.domain.activity.model.req.PartakeReq;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityBillVO;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityVO;
import com.anontokyo.lottery.domain.activity.model.vo.AwardVO;
import com.anontokyo.lottery.domain.activity.model.vo.DrawConfigPrizeVO;
import com.anontokyo.lottery.domain.activity.model.vo.DrawConfigVO;

import java.util.List;

public interface IActivityRepository {

    void addActivity(ActivityVO activity);

    void addAward(List<AwardVO> awardList);

    void addDrawConfig(DrawConfigVO drawConfig);

    void addDrawConfigPrizeList(List<DrawConfigPrizeVO> drawConfigPrizeList);

    boolean alterStatus(Long activityId, Constants.ActivityState beforeState, Constants.ActivityState afterState);

    ActivityBillVO queryActivityBill(PartakeReq req);

    List<ActivityVO> scanToDoActivityList(Long id);

    /**
     * 仅 PASS 活动可预热配置奖品库存；共享抽奖配置串行检查，SETNX 保留已有值。
     * 已投入使用的共享奖池缺失 Key 时显式报错，禁止按滞后 MySQL 库存回填。
     * @return 实际初始化的奖品 Key 数量
     */
    int warmupAwardStockForActivity(Long activityId);

    ActivityInfoLimitPageRich queryActivityInfoLimitPage(ActivityInfoLimitPageReq req);

    List<ActivityVO> queryRunningActivityList();
}
