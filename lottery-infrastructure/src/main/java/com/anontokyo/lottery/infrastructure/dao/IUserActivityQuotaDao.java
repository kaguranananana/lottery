package com.anontokyo.lottery.infrastructure.dao;

import cn.bugstack.middleware.db.router.annotation.DBRouter;
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
    @DBRouter
    UserActivityQuota queryUserActivityQuota(UserActivityQuota userActivityQuotaReq);

    /** 在参与事务中初始化或锁定用户活动次数行。 */
    void initializeCount(UserActivityQuota userActivityQuota);

    int subtractionLeftCount(UserActivityQuota userActivityQuota);
}
