package com.anontokyo.lottery.domain.activity.service.partake;

import com.anontokyo.lottery.common.Result;
import com.anontokyo.lottery.domain.activity.model.req.PartakeReq;
import com.anontokyo.lottery.domain.activity.model.res.PartakeResult;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityPartakeRecordVO;

/**
 * @description: 抽奖活动参与接口
 * @author：anontokyo
 * @date: 2026/1/12
 */
public interface IActivityPartake {

    /**
     * 参与活动
     * @param req
     * @return
     */
    PartakeResult doPartake(PartakeReq req);

    /**
     * Consume the take-activity order without persisting a draw award order.
     */
    Result consumeTakeActivity(String uId, Long activityId, Long takeId);

    /**
     * 更新活动库存
     * @param activityPartakeRecordVO
     */
    void updateActivityStock(ActivityPartakeRecordVO activityPartakeRecordVO);
}
