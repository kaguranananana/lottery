package com.anontokyo.lottery.infrastructure.repository;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.award.model.vo.AwardStockSettlementVO;
import com.anontokyo.lottery.domain.award.model.vo.DrawOrderVO;
import com.anontokyo.lottery.domain.draw.repository.IActivityPrizeRepository;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;
import javax.annotation.Resource;
import com.anontokyo.lottery.infrastructure.dao.IDrawParticipationDao;
import com.anontokyo.lottery.infrastructure.po.DrawParticipation;
import com.anontokyo.lottery.domain.award.model.vo.AwardFulfillmentEvent;
import com.anontokyo.lottery.domain.award.repository.IOrderRepository;
import com.anontokyo.lottery.infrastructure.dao.IDrawOrderDao;
import com.anontokyo.lottery.infrastructure.po.DrawOrder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class OrderRepository implements IOrderRepository {

    @Resource
    private IDrawOrderDao drawOrderDao;

    @Resource
    private IDrawParticipationDao drawParticipationDao;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Resource
    private IActivityPrizeRepository activityPrizeRepository;

    @Autowired
    private ApplicationEventPublisher events;

    @Override
    public Constants.ResponseCode saveDrawOrderWithTakeLock(DrawOrderVO drawOrder) {
        // TX2 将参与记录消费与携带 Outbox 状态的订单写入同一事务。
        return StockReservationTransactions.execute(transactionTemplate, status -> {
            DrawParticipation lockReq = new DrawParticipation();
            lockReq.setuId(drawOrder.getuId());
            lockReq.setActivityId(drawOrder.getActivityId());
            lockReq.setTakeId(drawOrder.getTakeId());
            int lockCount = drawParticipationDao.consumeParticipation(lockReq);
            if (lockCount == 0) {
                status.setRollbackOnly();
                return Constants.ResponseCode.NO_UPDATE;
            }

            try {
                insertDrawOrder(drawOrder);
                // 事务内发布事件。监听器只在真实提交后安排发送。
                AwardStockSettlementVO settlement = new AwardStockSettlementVO();
                BeanUtils.copyProperties(drawOrder, settlement);
                events.publishEvent(settlement);
            } catch (DuplicateKeyException e) {
                status.setRollbackOnly();
                return Constants.ResponseCode.INDEX_DUP;
            }

            return Constants.ResponseCode.SUCCESS;
        }, () -> activityPrizeRepository.recoverActivityPrizeStockByRedis(
                drawOrder.getActivityId(), drawOrder.getAwardId()),
                "award uId=" + drawOrder.getuId() + " takeId=" + drawOrder.getTakeId()
                        + " orderId=" + drawOrder.getOrderId());
    }

    @Override
    public boolean markUserAwardGranted(String uId, Long orderId, String awardId) {
        DrawOrder drawOrderRow = new DrawOrder();
        drawOrderRow.setuId(uId);
        drawOrderRow.setOrderId(orderId);
        drawOrderRow.setAwardId(awardId);
        drawOrderRow.setAwardFulfillmentStatus(Constants.AwardFulfillmentStatus.COMPLETE.getCode());
        if (drawOrderDao.markUserAwardGranted(drawOrderRow) > 0) {
            return true;
        }
        // 零行只有在能读取到已完成发奖事实时才是幂等成功。
        if (!isAwardCompleted(uId, orderId)) {
            throw new IllegalStateException("发奖事实未持久化，orderId=" + orderId);
        }
        return false;
    }

    private void insertDrawOrder(DrawOrderVO drawOrder) {
        DrawOrder drawOrderRow = new DrawOrder();
        drawOrderRow.setuId(drawOrder.getuId());
        drawOrderRow.setActivityId(drawOrder.getActivityId());
        drawOrderRow.setOrderId(drawOrder.getOrderId());
        drawOrderRow.setTakeId(drawOrder.getTakeId());
        drawOrderRow.setAwardFulfillmentStatus(drawOrder.getAwardFulfillmentStatus());
        drawOrderRow.setAwardId(drawOrder.getAwardId());
        drawOrderRow.setAwardType(drawOrder.getAwardType());
        drawOrderRow.setAwardName(drawOrder.getAwardName());
        drawOrderRow.setAwardContent(drawOrder.getAwardContent());
        drawOrderRow.setFulfillmentEventStatus(drawOrder.getFulfillmentEventStatus());
        drawOrderRow.setStockSettlementEventStatus(drawOrder.getStockSettlementEventStatus());
        drawOrderDao.insert(drawOrderRow);
    }

    @Override
    public void updateFulfillmentEventStatus(String uId, Long orderId, Integer fulfillmentEventStatus) {
        DrawOrder drawOrderRow = new DrawOrder();
        drawOrderRow.setuId(uId);
        drawOrderRow.setOrderId(orderId);
        drawOrderRow.setFulfillmentEventStatus(fulfillmentEventStatus);
        drawOrderDao.updateFulfillmentEventStatus(drawOrderRow);
    }

    @Override
    public boolean updateStockSettlementEventStatus(String uId, Long orderId, Integer stockSettlementEventStatus) {
        DrawOrder drawOrderRow = new DrawOrder();
        drawOrderRow.setuId(uId);
        drawOrderRow.setOrderId(orderId);
        drawOrderRow.setStockSettlementEventStatus(stockSettlementEventStatus);
        return drawOrderDao.updateStockSettlementEventStatus(drawOrderRow) == 1;
    }

    @Override
    public void markStockSettlementFailed(String uId, Long orderId) {
        // 按用户与订单定位结算状态。
        DrawOrder drawOrderRow = new DrawOrder();
        drawOrderRow.setuId(uId);
        // 按订单限定本次结算失败的更新范围。
        drawOrderRow.setOrderId(orderId);
        // 只有持久化成功才允许消费者 ACK，数据库异常原样传播。
        if (drawOrderDao.markStockSettlementFailed(drawOrderRow) > 0) {
            return;
        }
        // 零行可能是并发结算成功或重复 BLOCKED；必须回读事实而非默认成功。
        DrawOrder current = queryOrder(uId, orderId);
        if (current == null || (!Constants.StockSettlementEventStatus.COMPLETE.getCode().equals(current.getStockSettlementEventStatus())
                && !Constants.StockSettlementEventStatus.BLOCKED.getCode().equals(current.getStockSettlementEventStatus()))) {
            throw new IllegalStateException("库存分歧未持久化，uId=" + uId + " orderId=" + orderId);
        }
    }

    @Override
    public List<DrawOrderVO> scanOutbox(OutboxKind kind, int limit) {
        List<DrawOrder> rows = kind == OutboxKind.STOCK
                ? drawOrderDao.scanStockSettlementEventStatus(limit)
                : drawOrderDao.scanFulfillmentEventStatus(limit);
        List<DrawOrderVO> candidates = new ArrayList<>(rows.size());
        for (DrawOrder row : rows) {
            DrawOrderVO candidate = new DrawOrderVO();
            BeanUtils.copyProperties(row, candidate);
            candidates.add(candidate);
        }
        return candidates;
    }

    @Override
    public boolean claimOutbox(OutboxKind kind, long orderId, int attempt, int leaseSeconds) {
        return drawOrderDao.claimOutbox(kind, orderId, attempt, leaseSeconds) == 1;
    }

    @Override
    public void finishOutbox(OutboxKind kind, long orderId, int attempt, boolean sent, int delaySeconds) {
        drawOrderDao.finishOutbox(kind, orderId, attempt, sent, delaySeconds);
    }

    @Override
    public DrawOrderVO findOrder(String uId, Long orderId) {
        DrawOrder row = queryOrder(uId, orderId);
        if (row == null) {
            return null;
        }
        DrawOrderVO order = new DrawOrderVO();
        BeanUtils.copyProperties(row, order);
        return order;
    }

    @Override
    public boolean isAwardCompleted(String uId, Long orderId) {
        DrawOrder drawOrderRow = queryOrder(uId, orderId);
        return drawOrderRow != null
                && Constants.AwardFulfillmentStatus.COMPLETE.getCode().equals(drawOrderRow.getAwardFulfillmentStatus());
    }

    @Override
    public AwardFulfillmentEvent queryFulfillmentEventByOrderId(String uId, Long orderId) {
        DrawOrder drawOrderRow = queryOrder(uId, orderId);
        if (drawOrderRow == null) {
            return null;
        }
        return toFulfillmentEvent(drawOrderRow);
    }

    @Override
    public DrawOrderVO queryOrderByTakeId(String uId, Long takeId) {
        DrawOrder req = new DrawOrder();
        req.setuId(uId);
        req.setTakeId(takeId);
        DrawOrder drawOrderRow = drawOrderDao.queryDrawOrderByTakeId(req);
        if (drawOrderRow == null) {
            return null;
        }
        // 结果回放读取订单快照，同时保留计划时间和实际发放时间。
        DrawOrderVO order = new DrawOrderVO();
        BeanUtils.copyProperties(drawOrderRow, order);
        return order;
    }

    @Override
    public boolean isStockSettlementComplete(String uId, Long orderId) {
        DrawOrder drawOrderRow = queryOrder(uId, orderId);
        return drawOrderRow != null
                && Constants.StockSettlementEventStatus.COMPLETE.getCode().equals(drawOrderRow.getStockSettlementEventStatus());
    }

    private DrawOrder queryOrder(String uId, Long orderId) {
        DrawOrder req = new DrawOrder();
        req.setuId(uId);
        req.setOrderId(orderId);
        return drawOrderDao.queryDrawOrderByOrderId(req);
    }

    private AwardFulfillmentEvent toFulfillmentEvent(DrawOrder drawOrderRow) {
        AwardFulfillmentEvent fulfillmentEvent = new AwardFulfillmentEvent();
        fulfillmentEvent.setuId(drawOrderRow.getuId());
        fulfillmentEvent.setOrderId(drawOrderRow.getOrderId());
        fulfillmentEvent.setTakeId(drawOrderRow.getTakeId());
        fulfillmentEvent.setAwardId(drawOrderRow.getAwardId());
        fulfillmentEvent.setAwardType(drawOrderRow.getAwardType());
        fulfillmentEvent.setAwardName(drawOrderRow.getAwardName());
        fulfillmentEvent.setAwardContent(drawOrderRow.getAwardContent());
        return fulfillmentEvent;
    }
}
