package com.anontokyo.lottery.domain.activity.service.partake;

import com.anontokyo.lottery.common.Result;
import com.anontokyo.lottery.domain.activity.model.req.PartakeReq;
import com.anontokyo.lottery.domain.activity.model.res.PartakeResult;

/**
 * Activity participation domain service.
 */
public interface IActivityPartake {

    PartakeResult doPartake(PartakeReq req);

    Result consumeTakeActivity(String uId, Long activityId, Long takeId);
}
