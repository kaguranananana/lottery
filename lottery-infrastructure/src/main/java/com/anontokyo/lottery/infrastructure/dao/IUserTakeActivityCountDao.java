package com.anontokyo.lottery.infrastructure.dao;

import cn.bugstack.middleware.db.router.annotation.DBRouter;
import com.anontokyo.lottery.infrastructure.po.UserTakeActivityCount;
import org.apache.ibatis.annotations.Mapper;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/17
 */
@Mapper
public interface IUserTakeActivityCountDao {
    /**
     * 查询用户领奖次数信息
     * @param userTakeActivityCountReq
     * @return
     */
    @DBRouter
    UserTakeActivityCount queryUserTakeActivityCount(UserTakeActivityCount userTakeActivityCountReq);

    /** 在参与事务中初始化或锁定用户活动次数行。 */
    void initializeCount(UserTakeActivityCount userTakeActivityCount);

    int subtractionLeftCount(UserTakeActivityCount userTakeActivityCount);
}
