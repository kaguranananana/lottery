package cn.itedus.lottery.domain.activity.service.deploy;

import cn.itedus.lottery.domain.activity.model.req.ActivityConfigReq;
import cn.itedus.lottery.domain.activity.model.vo.ActivityVO;

import java.util.List;

/**
 * @description: 部署活动配置接口
 * @author：anontokyo
 * @date: 2026/1/12
 */
public interface IActivityDeploy {

    /**
     * 创建活动信息
     * @param req 活动配置信息
     */
    void createActivity(ActivityConfigReq req);


    /**
     * 修改活动信息
     * @param req
     */
    void updateActivity(ActivityConfigReq req);

    /**
     * 扫描待处理的活动列表
     * @param id
     * @return
     */
    List<ActivityVO> scanToDoActivityList(Long id);
}
