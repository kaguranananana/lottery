package com.anontokyo.lottery.infrastructure.dao;

import com.anontokyo.lottery.infrastructure.po.ActivityPrize;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/12
 */

@Mapper
public interface IActivityPrizeDao {

    List<ActivityPrize> queryActivityPrizeList(Long activityId);


    /** 只在同一活动的库存仍大于零时扣减。 */
    int deductStock(ActivityPrize req);

    void insertList(List<ActivityPrize> activityPrizeList);
}
