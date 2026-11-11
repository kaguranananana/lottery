package com.anontokyo.lottery.infrastructure.dao;

import com.anontokyo.lottery.domain.activity.model.req.ActivityInfoLimitPageReq;
import com.anontokyo.lottery.domain.activity.model.vo.AlterStateVO;
import com.anontokyo.lottery.infrastructure.po.Activity;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/12
 */
@Mapper
public interface IActivityDao {

    void insert(Activity req);

    Activity queryActivityById(Long activityId);

    /** 在全局库本地事务中锁定活动启动状态及关联策略。 */
    Activity queryActivityForUpdate(Long activityId);

    /** 策略行锁内检查共享奖池是否已被运行或关闭的活动使用。 */
    int countPublishedActivitiesByDrawConfig(Long drawConfigId);

    /**
     * 活动状态变更（乐观更新：必须匹配变更前状态）。
     */
    int alterState(AlterStateVO alterStateVO);

    List<Activity> scanToDoActivityList(Long id);

    Long queryActivityInfoCount(ActivityInfoLimitPageReq req);

    List<Activity> queryActivityInfoList(ActivityInfoLimitPageReq req);

    /**
     * 查询运行中的活动列表（state=5 且在有效期内）
     * 
     * @return 运行中的活动列表
     */
    List<Activity> queryRunningActivityList();

}
