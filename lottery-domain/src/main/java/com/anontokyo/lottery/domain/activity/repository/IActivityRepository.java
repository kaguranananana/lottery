package com.anontokyo.lottery.domain.activity.repository;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.activity.model.aggregates.ActivityInfoLimitPageRich;
import com.anontokyo.lottery.domain.activity.model.req.ActivityInfoLimitPageReq;
import com.anontokyo.lottery.domain.activity.model.req.PartakeReq;
import com.anontokyo.lottery.domain.activity.model.res.StockResult;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityBillVO;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityVO;
import com.anontokyo.lottery.domain.activity.model.vo.AwardVO;
import com.anontokyo.lottery.domain.activity.model.vo.StrategyDetailVO;
import com.anontokyo.lottery.domain.activity.model.vo.StrategyVO;

import java.util.List;

public interface IActivityRepository {

    void addActivity(ActivityVO activity);

    void addAward(List<AwardVO> awardList);

    void addStrategy(StrategyVO strategy);

    void addStrategyDetailList(List<StrategyDetailVO> strategyDetailList);

    boolean alterStatus(Long activityId, Enum<Constants.ActivityState> beforeState, Constants.ActivityState afterState);

    ActivityBillVO queryActivityBill(PartakeReq req);

    int substractionActivityStock(Long activityId);

    List<ActivityVO> scanToDoActivityList(Long id);

    StockResult substractionActivityStockByRedis(String uId, Long activityId);

    void recoverActivityStockByRedis(Long activityId);

    ActivityInfoLimitPageRich queryActivityInfoLimitPage(ActivityInfoLimitPageReq req);

    List<ActivityVO> queryRunningActivityList();
}
