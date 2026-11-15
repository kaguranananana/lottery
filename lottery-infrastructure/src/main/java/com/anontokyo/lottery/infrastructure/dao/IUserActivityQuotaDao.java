package com.anontokyo.lottery.infrastructure.dao;

import com.anontokyo.lottery.infrastructure.po.UserActivityQuota;
import org.apache.ibatis.annotations.Mapper;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/17
 */
@Mapper
public interface IUserActivityQuotaDao {
    /**
     * 查询用户领奖次数信息
     * @param userActivityQuotaReq
     * @return
     */
    UserActivityQuota queryUserActivityQuota(UserActivityQuota userActivityQuotaReq);

    /** 读取已有额度或活动初始额度，不写入额度行。 */
    UserActivityQuota queryEffectiveQuota(UserActivityQuota scope);

    /** 在参与事务中初始化或锁定用户活动次数行。 */
    void initializeCount(UserActivityQuota userActivityQuota);

    int subtractionLeftCount(UserActivityQuota userActivityQuota);
}
