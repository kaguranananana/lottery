package cn.itedus.lottery.domain.activity.service.partake;

import cn.itedus.lottery.common.Result;
import cn.itedus.lottery.domain.activity.model.req.PartakeReq;
import cn.itedus.lottery.domain.activity.model.res.PartakeResult;
import cn.itedus.lottery.domain.activity.model.vo.DrawOrderVO;
import cn.itedus.lottery.domain.activity.model.vo.InvoiceVO;

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
}
