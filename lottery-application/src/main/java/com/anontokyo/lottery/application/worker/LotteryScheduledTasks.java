package com.anontokyo.lottery.application.worker;

import com.anontokyo.lottery.application.mq.producer.OutboxDispatcher;
import com.anontokyo.lottery.common.Constants.ActivityState;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityVO;
import com.anontokyo.lottery.domain.activity.service.deploy.IActivityDeploy;
import com.anontokyo.lottery.domain.activity.service.lifecycle.ActivityLifecycleService;
import com.anontokyo.lottery.domain.award.repository.IOrderRepository;
import com.anontokyo.lottery.domain.award.repository.IOrderRepository.OutboxKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

@Component
public class LotteryScheduledTasks {
    private final Logger logger = LoggerFactory.getLogger(LotteryScheduledTasks.class);
    private final IActivityDeploy activities;
    private final ActivityLifecycleService lifecycle;
    private final OutboxDispatcher outbox;
    private final IOrderRepository orders;

    public LotteryScheduledTasks(IActivityDeploy activities, ActivityLifecycleService lifecycle,
                                 OutboxDispatcher outbox, IOrderRepository orders) {
        this.activities = activities;
        this.lifecycle = lifecycle;
        this.outbox = outbox;
        this.orders = orders;
    }

    @Scheduled(fixedDelayString = "${lottery.scheduling.activity-delay-ms:10000}")
    public void advanceActivities() {
        long cursor = 0;
        List<ActivityVO> page = activities.scanToDoActivityList(cursor);
        while (!page.isEmpty()) {
            for (ActivityVO activity : page) {
                try {
                    Date now = new Date();
                    if (ActivityState.READY.getCode().equals(activity.getState())) {
                        ActivityState target = activity.getEndDateTime().after(now)
                                ? ActivityState.RUNNING : ActivityState.CLOSED;
                        // 生命周期服务在行锁内复核时间并预热，不复制原状态机。
                        lifecycle.transition(activity.getActivityId(), ActivityState.READY, target);
                    } else if (!activity.getEndDateTime().after(now)) {
                        lifecycle.transition(activity.getActivityId(), ActivityState.RUNNING, ActivityState.CLOSED);
                    }
                } catch (RuntimeException e) {
                    logger.error("scheduled activity transition failed activityId:{}", activity.getActivityId(), e);
                }
            }
            cursor = page.get(page.size() - 1).getId();
            page = activities.scanToDoActivityList(cursor);
        }
    }

    @Scheduled(fixedDelayString = "${lottery.scheduling.stock-delay-ms:10000}")
    public void recoverStock() {
        outbox.recover(OutboxKind.STOCK);
        orders.recoverStockReservations(100);
    }

    @Scheduled(fixedDelayString = "${lottery.scheduling.fulfillment-delay-ms:10000}")
    public void recoverFulfillment() {
        outbox.recover(OutboxKind.FULFILLMENT);
    }
}
