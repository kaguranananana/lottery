package com.anontokyo.lottery.application.process.draw;

import com.anontokyo.lottery.application.process.draw.req.DrawProcessReq;
import com.anontokyo.lottery.application.process.draw.res.DrawProcessResult;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityVO;

import java.util.List;

/**
 * @description: 活动抽奖流程编排接口
 * @author：anontokyo
 * @date：2026/1/22
 */
public interface IActivityDrawProcess {

    /**
     * 执行抽奖流程
     *
     * @param req 抽奖请求
     * @return 抽奖结果
     */
    DrawProcessResult doDrawProcess(DrawProcessReq req);

    /**
     * 查询运行中的活动列表
     *
     * @return 活动列表
     */
    List<ActivityVO> queryRunningActivityList();
}
