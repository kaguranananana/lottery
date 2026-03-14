package com.anontokyo.lottery.domain.strategy.service.draw;

import com.anontokyo.lottery.domain.strategy.model.req.DrawReq;
import com.anontokyo.lottery.domain.strategy.model.res.DrawResult;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/12
 */

public interface IDrawExec {

    DrawResult doDrawExec(DrawReq req);

    /**
     * Recover the Redis-reserved award stock when downstream order creation fails.
     */
    void recoverDrawAwardStock(Long strategyId, String awardId);
}
