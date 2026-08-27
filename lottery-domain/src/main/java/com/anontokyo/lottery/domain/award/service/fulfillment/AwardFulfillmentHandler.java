package com.anontokyo.lottery.domain.award.service.fulfillment;

import com.anontokyo.lottery.domain.award.model.vo.InvoiceVO;

/**
 * 发奖渠道处理器。不同奖品类型（优惠券 / 兑换码 / 实物 / 虚拟权益）的差异是真实业务差异，
 * 因此保留该抽象，但只保留一层：Spring 注入全部实现后按 supportType 构建注册表即可。
 *
 * 业务幂等约定：
 *  - fulfill 必须以 orderId 作为发奖业务幂等号（未来对接真实优惠券系统时对应 idempotencyKey）；
 *  - 本地以 user_strategy_export.grant_state 的 CAS 更新兜底：重复调用不会二次标记；
 *  - Redis 处理锁只用于并发防抖，不构成 exactly-once 保证。
 */
public interface AwardFulfillmentHandler {

    /** 支持的奖品类型，见 Constants.AwardType */
    int supportType();

    /**
     * 执行发奖。返回结果 code 需符合 Constants.AwardState。
     */
    AwardFulfillmentResult fulfill(InvoiceVO order);
}
