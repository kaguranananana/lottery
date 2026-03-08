package com.anontokyo.lottery.domain.award.service.goods.Impl;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.award.model.req.GoodsReq;
import com.anontokyo.lottery.domain.award.model.res.DistributionRes;
import com.anontokyo.lottery.domain.award.service.goods.DistributionBase;
import com.anontokyo.lottery.domain.award.service.goods.IDistributionGoods;
import org.springframework.stereotype.Component;

/**
 * @description: 优惠券商品
 * @author：anontokyo
 * @date: 2026/1/10
 */
@Component
public class CouponGoods extends DistributionBase implements IDistributionGoods {
    @Override
    public DistributionRes doDistribution(GoodsReq req) {
        // 模拟调用优惠券发放接口
        logger.info("模拟调用优惠券发放接口, uId : {}, awardContent : {}", req.getuId(), req.getAwardContent());

        // 更新用户领奖结果
        super.updateUserAwardState(req.getuId(), req.getOrderId(), req.getAwardId(), Constants.AwardState.SUCCESS.getCode());

        return new DistributionRes(req.getuId(), Constants.AwardState.SUCCESS.getCode(), Constants.AwardState.SUCCESS.getInfo());

    }


}
