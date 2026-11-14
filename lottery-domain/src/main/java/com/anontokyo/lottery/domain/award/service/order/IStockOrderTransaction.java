package com.anontokyo.lottery.domain.award.service.order;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.award.model.vo.DrawOrderVO;

/** 下单事务及其库存结算投递的协调入口。 */
public interface IStockOrderTransaction {
    Constants.ResponseCode place(DrawOrderVO order);
}
