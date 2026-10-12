package com.anontokyo.lottery.infrastructure.repository;

import com.anontokyo.lottery.common.Constants;
import cn.bugstack.middleware.db.router.strategy.IDBRouterStrategy;
import com.anontokyo.lottery.domain.award.model.vo.AwardStockSettlementVO;
import com.anontokyo.lottery.domain.award.model.vo.DrawOrderVO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;
import javax.annotation.Resource;
import com.anontokyo.lottery.infrastructure.dao.IUserTakeActivityDao;
import com.anontokyo.lottery.infrastructure.po.UserTakeActivity;
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

    @Resource
    private IUserTakeActivityDao userTakeActivityDao;

    @Resource
    private IDBRouterStrategy dbRouter;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Override
    public Constants.ResponseCode saveDrawOrderWithTakeLock(DrawOrderVO drawOrder) {
        try {
            dbRouter.doRouter(drawOrder.getuId());
            return transactionTemplate.execute(status -> {
                UserTakeActivity lockReq = new UserTakeActivity();
                lockReq.setuId(drawOrder.getuId());
                lockReq.setActivityId(drawOrder.getActivityId());
                lockReq.setTakeId(drawOrder.getTakeId());
                int lockCount = userTakeActivityDao.lockTakeActivity(lockReq);
                if (lockCount == 0) {
                    status.setRollbackOnly();
                    return Constants.ResponseCode.NO_UPDATE;
                }

                try {
                    insertDrawOrder(drawOrder);
                } catch (DuplicateKeyException e) {
                    status.setRollbackOnly();
                    return Constants.ResponseCode.INDEX_DUP;
                }

                return Constants.ResponseCode.SUCCESS;
            });
        } finally {
            dbRouter.clear();
        }
    }

    @Override
    public int shardingDbCount() {
        return dbRouter.dbCount();
    }

    @Override
    public int shardingTableCount() {
        return dbRouter.tbCount();
    }

    @Override
    public boolean markUserAwardGranted(String uId, Long orderId, String awardId) {
        UserStrategyExport userStrategyExport = new UserStrategyExport();
        userStrategyExport.setuId(uId);
        userStrategyExport.setOrderId(orderId);
        userStrategyExport.setAwardId(awardId);
        userStrategyExport.setGrantState(Constants.GrantState.COMPLETE.getCode());
        return userStrategyExportDao.markUserAwardGranted(userStrategyExport) > 0;
    }

    private void insertDrawOrder(DrawOrderVO drawOrder) {
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
    public void markStockSettlementFailed(String uId, Long orderId) {
        // 用户标识提供分库分表路由键。
        UserStrategyExport userStrategyExport = new UserStrategyExport();
        userStrategyExport.setuId(uId);
        // 按订单限定本次结算失败的更新范围。
        userStrategyExport.setOrderId(orderId);
        // SQL 条件决定是否更新；零行是终态保护或重复失败，SQL 异常仍向上传播。
        userStrategyExportDao.markStockSettlementFailed(userStrategyExport);
    }

    @Override
    public List<InvoiceVO> scanInvoiceMqState(int limit) {
        List<UserStrategyExport> userStrategyExportList = userStrategyExportDao.scanInvoiceMqState(limit);
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
    public List<InvoiceVO> scanInvoiceMqStateByShard(int dbIdx, int tbIdx, int limit) {
        try {
            dbRouter.setDBKey(dbIdx);
            dbRouter.setTBKey(tbIdx);
            return scanInvoiceMqState(limit);
        } finally {
            dbRouter.clear();
        }
    }

    @Override
    public List<AwardStockSettlementVO> scanAwardStockMqStateByShard(int dbIdx, int tbIdx, int limit) {
        try {
            dbRouter.setDBKey(dbIdx);
            dbRouter.setTBKey(tbIdx);
            return scanAwardStockMqState(limit);
        } finally {
            dbRouter.clear();
        }
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
