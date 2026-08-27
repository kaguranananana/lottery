package com.anontokyo.lottery.infrastructure.dao;

import cn.bugstack.middleware.db.router.annotation.DBRouter;
import cn.bugstack.middleware.db.router.annotation.DBRouterStrategy;
import com.anontokyo.lottery.infrastructure.po.UserTakeActivity;
import org.apache.ibatis.annotations.Mapper;

/**
 * User take-activity DAO.
 */
@Mapper
@DBRouterStrategy(splitTable = false)
public interface IUserTakeActivityDao {

    @DBRouter(key = "uId")
    void insert(UserTakeActivity userTakeActivity);

    @DBRouter(key = "uId")
    UserTakeActivity queryNoConsumedTakeActivityOrder(UserTakeActivity userTakeActivity);

    @DBRouter(key = "uId")
    UserTakeActivity queryTakeActivityByRequestId(UserTakeActivity userTakeActivity);

    int lockTakeActivity(UserTakeActivity userTakeActivity);
}
