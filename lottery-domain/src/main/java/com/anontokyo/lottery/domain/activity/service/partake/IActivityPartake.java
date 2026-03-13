package com.anontokyo.lottery.domain.activity.service.partake;

import com.anontokyo.lottery.common.Result;
import com.anontokyo.lottery.domain.activity.model.req.PartakeReq;
import com.anontokyo.lottery.domain.activity.model.res.PartakeResult;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityPartakeRecordVO;
import com.anontokyo.lottery.domain.activity.model.vo.DrawOrderVO;
import com.anontokyo.lottery.domain.activity.model.vo.InvoiceVO;

import java.util.List;

/**
 * @description: 抽奖活动参与接口
 * @author：anontokyo
 * @date: 2026/1/12
 */
public interface IActivityPartake {

    /**
     * 参与活动
     * @param req
     * @return
     */
    PartakeResult doPartake(PartakeReq req);

    /**
     * 保存奖品单
     * @param drawOrderVO
     * @return
     */
    Result recordDrawOrder(DrawOrderVO drawOrderVO);

    /**
     * Consume the take-activity order without persisting a draw award order.
     */
    Result consumeTakeActivity(String uId, Long activityId, Long takeId);

    /**
     * 更新发货单MQ消息
     * @param uId
     * @param orderId
     * @param code
     */
    void updateInvoiceMqState(String uId, Long orderId, Integer code);

    /**
     * 扫描发货单MQ状态, 把未发送的扫描出来做补偿
     * @param dbCount
     * @param tbCount
     * @return
     */
    List<InvoiceVO> scanInvoiceMqState(int dbCount, int tbCount);

    /**
     * 更新活动库存
     * @param activityPartakeRecordVO
     */
    void updateActivityStock(ActivityPartakeRecordVO activityPartakeRecordVO);
}
