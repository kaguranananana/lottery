package cn.itedus.lottery.domain.award.service.goods;


import cn.itedus.lottery.domain.award.repository.IAwardRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Resource;

/**
 * @description: 配送货物基础公共类
 * @author：anontokyo
 * @date: 2026/1/10
 */

public class DistributionBase {

    protected Logger logger = LoggerFactory.getLogger(DistributionBase.class);


    @Resource
    private IAwardRepository awardRepository;

    protected void updateUserAwardState(String uId, String orderId, String awardId, Integer awardState, String awardStateInfo) {
        // TODO 后期添加更新分库分表中
        logger.info(" TODO 后期添加更新分库分表中, userId: {}", uId);
    }


}
