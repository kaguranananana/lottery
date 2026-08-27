package com.anontokyo.lottery.infrastructure.repository;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.award.model.vo.AwardStockSettlementVO;
import com.anontokyo.lottery.domain.award.model.vo.DrawOrderVO;
import com.anontokyo.lottery.domain.award.model.vo.InvoiceVO;
import com.anontokyo.lottery.domain.award.repository.IOrderRepository;
import com.anontokyo.lottery.infrastructure.dao.IUserStrategyExportDao;
import com.anontokyo.lottery.infrastructure.po.UserStrategyExport;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

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
        userStrategyExport.setTakeId(drawOrder.getTakeId());
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
        userStrategyExport.setStockMqState(drawOrder.getStockMqState());
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
    public void updateStockMqState(String uId, Long orderId, Integer stockMqState) {
        UserStrategyExport userStrategyExport = new UserStrategyExport();
        userStrategyExport.setuId(uId);
        userStrategyExport.setOrderId(orderId);
        userStrategyExport.setStockMqState(stockMqState);
        userStrategyExportDao.updateStockMqState(userStrategyExport);
    }

    @Override
    public List<InvoiceVO> scanInvoiceMqState() {
        List<UserStrategyExport> userStrategyExportList = userStrategyExportDao.scanInvoiceMqState();
        List<InvoiceVO> invoiceVOList = new ArrayList<>(userStrategyExportList.size());
        for (UserStrategyExport userStrategyExport : userStrategyExportList) {
            invoiceVOList.add(toInvoice(userStrategyExport));
        }
        return invoiceVOList;
    }

    @Override
    public List<AwardStockSettlementVO> scanAwardStockMqState(int limit) {
        List<UserStrategyExport> userStrategyExportList = userStrategyExportDao.scanAwardStockMqState(limit);
        List<AwardStockSettlementVO> settlementVOList = new ArrayList<>(userStrategyExportList.size());
        for (UserStrategyExport userStrategyExport : userStrategyExportList) {
            AwardStockSettlementVO settlementVO = new AwardStockSettlementVO();
            settlementVO.setuId(userStrategyExport.getuId());
            settlementVO.setOrderId(userStrategyExport.getOrderId());
            settlementVO.setStrategyId(userStrategyExport.getStrategyId());
            settlementVO.setAwardId(userStrategyExport.getAwardId());
            settlementVOList.add(settlementVO);
        }
        return settlementVOList;
    }

    @Override
    public boolean isAwardCompleted(String uId, Long orderId) {
        UserStrategyExport userStrategyExport = queryOrder(uId, orderId);
        return userStrategyExport != null
                && Constants.GrantState.COMPLETE.getCode().equals(userStrategyExport.getGrantState());
    }

    @Override
    public InvoiceVO queryInvoiceByOrderId(String uId, Long orderId) {
        UserStrategyExport userStrategyExport = queryOrder(uId, orderId);
        if (userStrategyExport == null) {
            return null;
        }
        return toInvoice(userStrategyExport);
    }

    @Override
    public InvoiceVO queryOrderByTakeId(String uId, Long takeId) {
        UserStrategyExport req = new UserStrategyExport();
        req.setuId(uId);
        req.setTakeId(takeId);
        UserStrategyExport order = userStrategyExportDao.queryUserStrategyExportByTakeId(req);
        return order == null ? null : toInvoice(order);
    }

    @Override
    public boolean isStockMqCompleted(String uId, Long orderId) {
        UserStrategyExport userStrategyExport = queryOrder(uId, orderId);
        return userStrategyExport != null
                && Constants.MQState.COMPLETE.getCode().equals(userStrategyExport.getStockMqState());
    }

    private UserStrategyExport queryOrder(String uId, Long orderId) {
        UserStrategyExport req = new UserStrategyExport();
        req.setuId(uId);
        req.setUuid(String.valueOf(orderId));
        return userStrategyExportDao.queryUserStrategyExportByUuid(req);
    }

    private InvoiceVO toInvoice(UserStrategyExport userStrategyExport) {
        InvoiceVO invoiceVO = new InvoiceVO();
        invoiceVO.setuId(userStrategyExport.getuId());
        invoiceVO.setOrderId(userStrategyExport.getOrderId());
        invoiceVO.setTakeId(userStrategyExport.getTakeId());
        invoiceVO.setGrantType(userStrategyExport.getGrantType());
        invoiceVO.setGrantDate(userStrategyExport.getGrantDate());
        invoiceVO.setAwardId(userStrategyExport.getAwardId());
        invoiceVO.setAwardType(userStrategyExport.getAwardType());
        invoiceVO.setAwardName(userStrategyExport.getAwardName());
        invoiceVO.setAwardContent(userStrategyExport.getAwardContent());
        return invoiceVO;
    }
}
