package com.anontokyo.lottery.domain.award.service.goods;

import com.anontokyo.lottery.domain.award.model.req.GoodsReq;
import com.anontokyo.lottery.domain.award.model.res.DistributionRes;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/10
 */
public interface IDistributionGoods {
    /**
     * 奖品配送接口,
     * @param req
     * @return
     */
    DistributionRes doDistribution(GoodsReq req);

}
