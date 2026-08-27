package com.anontokyo.lottery.domain.award.service.fulfillment.impl;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.award.model.vo.InvoiceVO;
import com.anontokyo.lottery.domain.award.repository.IOrderRepository;
import com.anontokyo.lottery.domain.award.service.fulfillment.AwardFulfillmentHandler;
import com.anontokyo.lottery.domain.award.service.fulfillment.AwardFulfillmentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Resource;

/**
 * 发奖处理器公共逻辑：以 orderId 为幂等键做 CAS 标记（grant_state -> SUCCESS）。
 * CAS 影响行数为 0 表示该订单此前已发奖成功——结果仍视为成功（重复投递安全）。
 */
public abstract class AbstractAwardFulfillmentHandler implements AwardFulfillmentHandler {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    @Resource
    private IOrderRepository orderRepository;

    @Override
    public AwardFulfillmentResult fulfill(InvoiceVO order) {
        // TODO: 真实第三方渠道接入时，把 orderId 作为 idempotencyKey 调用外部接口，
        //  成功后再执行下方本地标记；当前为 adapter stub，模拟不同发奖渠道。
        logger.info("fulfill award via {} uId:{} orderId:{} awardId:{} awardContent:{}",
                getClass().getSimpleName(), order.getuId(), order.getOrderId(), order.getAwardId(),
                order.getAwardContent());

        boolean first = orderRepository.markUserAwardGranted(
                order.getuId(), order.getOrderId(), order.getAwardId());
        if (!first) {
            logger.info("award already granted (idempotent skip) uId:{} orderId:{}", order.getuId(),
                    order.getOrderId());
        }
        return new AwardFulfillmentResult(order.getuId(), Constants.AwardState.SUCCESS,
                Constants.AwardState.SUCCESS.getInfo(), first);
    }
}
