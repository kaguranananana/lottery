package com.anontokyo.lottery.domain.award.service.factory;

import com.anontokyo.lottery.domain.award.service.goods.IDistributionGoods;
import org.springframework.stereotype.Service;

/**
 * @description: 配送商品的简单工厂
 * @author：anontokyo
 * @date: 2026/1/10
 */

@Service
public class DistributionGoodsFactory extends GoodsConfig{
    public IDistributionGoods getDistributionGoodsService(Integer awardType) {
        return goodsMap.get(awardType);
    }
}
