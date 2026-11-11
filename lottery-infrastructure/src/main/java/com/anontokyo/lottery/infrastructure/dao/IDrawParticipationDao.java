package com.anontokyo.lottery.infrastructure.dao;

import com.anontokyo.lottery.infrastructure.po.DrawParticipation;
import org.apache.ibatis.annotations.Mapper;

/**
 * User take-activity DAO.
 */
@Mapper
public interface IDrawParticipationDao {
    void insert(DrawParticipation drawParticipation);
    DrawParticipation queryUnfinishedParticipation(DrawParticipation drawParticipation);
    DrawParticipation queryParticipationByRequestId(DrawParticipation drawParticipation);

    int consumeParticipation(DrawParticipation drawParticipation);
}
