package cn.itedus.lottery.infrastructure.dao;

import cn.itedus.lottery.domain.activity.model.req.ActivityInfoLimitPageReq;
import cn.itedus.lottery.domain.activity.model.vo.AlterStateVO;
import cn.itedus.lottery.infrastructure.po.Activity;
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

    /**
     * 活动状态变更（乐观更新：必须匹配变更前状态）。
     */
    int alterState(AlterStateVO alterStateVO);

    int substractionActivityStock(Long activityId);

    List<Activity> scanToDoActivityList(Long id);

    void updateActivityStock(Activity activity);

    Long queryActivityInfoCount(ActivityInfoLimitPageReq req);

    List<Activity> queryActivityInfoList(ActivityInfoLimitPageReq req);

    /**
     * 查询运行中的活动列表（state=5 且在有效期内）
     * 
     * @return 运行中的活动列表
     */
    List<Activity> queryRunningActivityList();

}
