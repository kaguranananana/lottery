package com.anontokyo.lottery.infrastructure.repository;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.award.model.vo.DrawOrderVO;
import com.anontokyo.lottery.infrastructure.utils.RedisStockReservations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.beans.BeanUtils;
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
    private RedisStockReservations reservations;

    private static final Logger logger = LoggerFactory.getLogger(OrderRepository.class);

    @Override
    public Constants.ResponseCode saveDrawOrderWithTakeLock(DrawOrderVO order, long deadline) {
        // TX2 必须独立提交，否则本地回调可能在外层事务提交前通知 Broker。
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("库存消息回调禁止加入外层事务，orderId=" + order.getOrderId());
        }
        String marker = order.getOrderId() + ":" + order.getTakeId() + ":" + order.getActivityId() + ":" + order.getAwardId();
        boolean[] reservationAttempted = {false};
        Constants.ResponseCode result;
        try {
            result = transactionTemplate.execute(status -> {
                DrawParticipation take = new DrawParticipation();
                take.setuId(order.getuId());
                take.setActivityId(order.getActivityId());
                take.setTakeId(order.getTakeId());
                // 先 CAS 获取参与行锁。回查及回补使用相同的行锁判定事务是否结束。
                if (drawParticipationDao.consumeParticipation(take) == 0) {
                    return Constants.ResponseCode.NO_UPDATE;
                }
                // 必须在取得行锁后检查截止时间。回查做出回滚决定后，迟到的回调不能下单。
                if (drawParticipationDao.databaseTimeMillis() >= deadline) {
                    throw new IllegalStateException("库存事务已过期，orderId=" + order.getOrderId());
                }
                // CAS 成功表示此前的 TX2 没有消费此参与。重试先回收进程退出留下的预扣，
                // 避免一件库存被上次同请求占住，导致这次误判售罄。
                String previous = reservations.findByTake(order.getTakeId());
                if (previous != null) {
                    resolveUnderTakeLock(previous);
                }
                // Lua 可能已执行但响应丢失，先记录调用意图，依靠待对账记录判断是否实际预扣。
                reservationAttempted[0] = true;
                if (!reservations.reserve(marker, order.getTakeId(), order.getActivityId(), order.getAwardId(), deadline)) {
                    return Constants.ResponseCode.AWARD_SOLD_OUT;
                }
                insertDrawOrder(order);
                return Constants.ResponseCode.SUCCESS;
            });
        } catch (RuntimeException failure) {
            if (reservationAttempted[0]) {
                try {
                    reconcileReservation(marker);
                } catch (RuntimeException recoveryFailure) {
                    // 提交结果未知或 Redis 不可用时保留预扣记录，定时任务在真实状态可查后处理。
                    failure.addSuppressed(recoveryFailure);
                }
            }
            if (failure instanceof DuplicateKeyException) {
                return Constants.ResponseCode.INDEX_DUP;
            }
            throw failure;
        }
        if (result == Constants.ResponseCode.SUCCESS) {
            try {
                reservations.resolve(marker, order.getTakeId(), order.getActivityId(), order.getAwardId(), true);
            } catch (RuntimeException e) {
                // 订单已提交，照常通知 Broker；过期预扣对账会按订单事实清理索引。
                logger.error("已提交订单的预扣记录清理失败 marker:{}", marker, e);
            }
        }
        return result;
    }

    @Override
    public StockTransactionState checkStockTransaction(String uId, long orderId, long takeId, long deadline) {
        if (findOrder(uId, orderId) != null) {
            return StockTransactionState.COMMITTED;
        }
        if (drawParticipationDao.databaseTimeMillis() < deadline) {
            return StockTransactionState.UNKNOWN;
        }
        try {
            return transactionTemplate.execute(status -> {
                // NOWAIT 区分执行中和已结束。锁在手时重新读取，避免以旧快照判定回滚。
                drawParticipationDao.lockTakeNowait(takeId);
                return findOrder(uId, orderId) != null
                        ? StockTransactionState.COMMITTED : StockTransactionState.ROLLED_BACK;
            });
        } catch (PessimisticLockingFailureException busy) {
            return StockTransactionState.UNKNOWN;
        }
    }

    @Override
    public void recoverStockReservations(int limit) {
        long now = System.currentTimeMillis();
        for (String marker : reservations.expired(now, limit)) {
            try {
                reconcileReservation(marker);
            } catch (RuntimeException e) {
                logger.error("库存预扣对账未完成 marker:{}", marker, e);
                // 保留原预扣记录，下轮扫描继续检查。
            }
        }
    }

    private void reconcileReservation(String marker) {
        long takeId = Long.parseLong(marker.split(":", 4)[1]);
        transactionTemplate.executeWithoutResult(status -> {
            drawParticipationDao.lockTakeNowait(takeId);
            resolveUnderTakeLock(marker);
        });
    }

    private void resolveUnderTakeLock(String marker) {
        // 奖品号可包含冒号，所以只分隔前三个字段。
        String[] identity = marker.split(":", 4);
        long orderId = Long.parseLong(identity[0]);
        long takeId = Long.parseLong(identity[1]);
        long activityId = Long.parseLong(identity[2]);
        // 回补前在参与锁内检查订单。提交结果未知也走同一条路径。
        boolean committed = drawOrderDao.hasOrderForTake(orderId, takeId);
        reservations.resolve(marker, takeId, activityId, identity[3], committed);
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
    public boolean claimOutbox(OutboxKind kind, long orderId, int leaseSeconds) {
        return drawOrderDao.claimOutbox(kind, orderId, leaseSeconds) == 1;
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
