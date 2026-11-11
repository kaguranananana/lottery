package com.anontokyo.lottery.infrastructure.dao;

import cn.bugstack.middleware.db.router.annotation.DBRouter;
import cn.bugstack.middleware.db.router.annotation.DBRouterStrategy;
import com.anontokyo.lottery.infrastructure.po.DrawParticipation;
import org.apache.ibatis.annotations.Mapper;

/**
 * User take-activity DAO.
 */
@Mapper
@DBRouterStrategy(splitTable = false)
public interface IDrawParticipationDao {

    @DBRouter(key = "uId")
    void insert(DrawParticipation drawParticipation);

    @DBRouter(key = "uId")
    DrawParticipation queryUnfinishedParticipation(DrawParticipation drawParticipation);

    @DBRouter(key = "uId")
    DrawParticipation queryParticipationByRequestId(DrawParticipation drawParticipation);

    int consumeParticipation(DrawParticipation drawParticipation);
}
