package com.anontokyo.lottery.infrastructure.repository;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.award.model.vo.DrawOrderVO;
import com.anontokyo.lottery.domain.award.model.vo.InvoiceVO;
import com.anontokyo.lottery.domain.award.repository.IOrderRepository;
import com.anontokyo.lottery.infrastructure.dao.IUserStrategyExportDao;
import com.anontokyo.lottery.infrastructure.po.UserStrategyExport;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * @description: 奖品表仓储服务
 * @author：anontokyo
 * @date: 2026/1/10
 */

@Component
public class OrderRepository implements IOrderRepository {

    @Resource
    private IUserStrategyExportDao userStrategyExportDao;

    @Override
    public void updateUserAwardState(String uId, Long orderId, String awardId, Integer grantState) {
        UserStrategyExport userStrategyExport = new UserStrategyExport();
        userStrategyExport.setuId(uId);
        userStrategyExport.setOrderId(orderId);
        userStrategyExport.setAwardId(awardId);
        userStrategyExport.setGrantState(grantState);
        userStrategyExportDao.updateUserAwardState(userStrategyExport);
    }

    @Override
    public void saveDrawOrder(DrawOrderVO drawOrder) {
        UserStrategyExport userStrategyExport = new UserStrategyExport();
        userStrategyExport.setuId(drawOrder.getuId());
        userStrategyExport.setActivityId(drawOrder.getActivityId());
        userStrategyExport.setOrderId(drawOrder.getOrderId());
        userStrategyExport.setStrategyId(drawOrder.getStrategyId());
        userStrategyExport.setStrategyMode(drawOrder.getStrategyMode());
        userStrategyExport.setGrantType(drawOrder.getGrantType());
        userStrategyExport.setGrantDate(drawOrder.getGrantDate());
        userStrategyExport.setGrantState(drawOrder.getGrantState());
        userStrategyExport.setAwardId(drawOrder.getAwardId());
        userStrategyExport.setAwardType(drawOrder.getAwardType());
        userStrategyExport.setAwardName(drawOrder.getAwardName());
        userStrategyExport.setAwardContent(drawOrder.getAwardContent());
        userStrategyExport.setUuid(String.valueOf(drawOrder.getOrderId()));
        userStrategyExport.setMqState(drawOrder.getMqState());
        userStrategyExportDao.insert(userStrategyExport);
    }

    @Override
    public void updateInvoiceMqState(String uId, Long orderId, Integer mqState) {
        UserStrategyExport userStrategyExport = new UserStrategyExport();
        userStrategyExport.setuId(uId);
        userStrategyExport.setOrderId(orderId);
        userStrategyExport.setMqState(mqState);
        userStrategyExportDao.updateInvoiceMqState(userStrategyExport);
    }

    @Override
    public List<InvoiceVO> scanInvoiceMqState() {
        List<UserStrategyExport> userStrategyExportList = userStrategyExportDao.scanInvoiceMqState();
        List<InvoiceVO> invoiceVOList = new ArrayList<>(userStrategyExportList.size());
        for (UserStrategyExport userStrategyExport : userStrategyExportList) {
            InvoiceVO invoiceVO = new InvoiceVO();
            invoiceVO.setuId(userStrategyExport.getuId());
            invoiceVO.setOrderId(userStrategyExport.getOrderId());
            invoiceVO.setAwardId(userStrategyExport.getAwardId());
            invoiceVO.setAwardType(userStrategyExport.getAwardType());
            invoiceVO.setAwardName(userStrategyExport.getAwardName());
            invoiceVO.setAwardContent(userStrategyExport.getAwardContent());
            invoiceVOList.add(invoiceVO);
        }
        return invoiceVOList;
    }

    @Override
    public boolean isAwardCompleted(String uId, Long orderId) {
        UserStrategyExport req = new UserStrategyExport();
        req.setuId(uId);
        req.setUuid(String.valueOf(orderId));
        UserStrategyExport userStrategyExport = userStrategyExportDao.queryUserStrategyExportByUuid(req);
        return userStrategyExport != null
                && Constants.GrantState.COMPLETE.getCode().equals(userStrategyExport.getGrantState());
    }
}
