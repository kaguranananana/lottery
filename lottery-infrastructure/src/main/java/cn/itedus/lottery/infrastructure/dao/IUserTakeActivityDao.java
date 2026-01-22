package cn.itedus.lottery.infrastructure.dao;

import cn.bugstack.middleware.db.router.annotation.DBRouter;
import cn.itedus.lottery.infrastructure.po.UserTakeActivity;
import org.apache.ibatis.annotations.Mapper;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/16
 */
@Mapper
public interface IUserTakeActivityDao {
    /**
     * 插入用户领取活动信息
     *
     * @param userTakeActivity 入参
     */
    @DBRouter(key = "uId")
    void insert(UserTakeActivity userTakeActivity);

    @DBRouter(key = "uId")
    UserTakeActivity queryNoConsumedTakeActivityOrder(UserTakeActivity userTakeActivity);
}
