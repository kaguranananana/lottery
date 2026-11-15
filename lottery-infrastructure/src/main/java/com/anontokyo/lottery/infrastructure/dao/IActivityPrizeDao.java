package com.anontokyo.lottery.infrastructure.dao;

import com.anontokyo.lottery.infrastructure.po.ActivityPrize;
import com.anontokyo.lottery.infrastructure.po.Award;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/12
 */

@Mapper
public interface IActivityPrizeDao {

    List<ActivityPrize> queryActivityPrizeList(Long activityId);


    /** 读取活动对应的公开奖品信息。 */
    List<Award> queryPublicPrizes(Long activityId);

    /** 只在同一活动的库存仍大于零时扣减。 */
    int deductStock(ActivityPrize req);

    /** 结算时读取持久化的奖品库存模式；不依赖进程级临时开关。 */
    Integer queryStockBucketCount(@Param("activityId") Long activityId,
                                  @Param("awardId") String awardId);

    /** READY 活动启动前验证全部库存桶已创建、初始库存总和正确。 */
    int countInvalidBucketLayouts(Long activityId);

    void insertList(List<ActivityPrize> activityPrizeList);
}
