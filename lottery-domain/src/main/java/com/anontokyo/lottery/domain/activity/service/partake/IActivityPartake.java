package com.anontokyo.lottery.domain.activity.service.partake;

import com.anontokyo.lottery.common.Result;
import com.anontokyo.lottery.domain.activity.model.req.PartakeReq;
import com.anontokyo.lottery.domain.activity.model.res.PartakeResult;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityPartakeRecordVO;

/**
 * Activity participation domain service.
 */
public interface IActivityPartake {

    PartakeResult doPartake(PartakeReq req);

    Result consumeTakeActivity(String uId, Long activityId, Long takeId);

    void updateActivityStock(ActivityPartakeRecordVO activityPartakeRecordVO);
}
