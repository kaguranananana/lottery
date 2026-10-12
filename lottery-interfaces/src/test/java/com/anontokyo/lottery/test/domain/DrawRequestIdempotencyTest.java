package com.anontokyo.lottery.test.domain;

import com.anontokyo.lottery.application.process.draw.impl.ActivityProcessImpl;
import com.anontokyo.lottery.application.process.draw.req.DrawProcessReq;
import com.anontokyo.lottery.application.process.draw.res.DrawProcessResult;
import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.activity.model.res.PartakeResult;
import com.anontokyo.lottery.domain.activity.model.vo.UserTakeActivityVO;
import com.anontokyo.lottery.domain.activity.repository.IActivityRepository;
import com.anontokyo.lottery.domain.activity.repository.IUserTakeActivityRepository;
import com.anontokyo.lottery.domain.activity.service.partake.IActivityPartake;
import com.anontokyo.lottery.domain.award.model.vo.InvoiceVO;
import com.anontokyo.lottery.domain.award.repository.IOrderRepository;
import com.anontokyo.lottery.domain.award.service.order.IAwardOrder;
import com.anontokyo.lottery.domain.support.lock.IActivityDrawProcessGuard;
import com.anontokyo.lottery.domain.strategy.model.req.DrawReq;
import com.anontokyo.lottery.domain.strategy.model.res.DrawResult;
import com.anontokyo.lottery.domain.strategy.model.vo.DrawAwardInfo;
import com.anontokyo.lottery.domain.strategy.service.draw.IDrawExec;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;


import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 场景 E：同 requestId 重复请求的编排级单测（不依赖外部环境）。
 * 必须保证：不重复消耗参与次数（doPartake 只一次）、不生成两个订单、结果一致。
 */
public class DrawRequestIdempotencyTest {

    private IActivityDrawProcessGuard guard;
    private IUserTakeActivityRepository userTakeActivityRepository;
    private IActivityPartake activityPartake;
    private IDrawExec drawExec;
    private IAwardOrder awardOrder;
    private IOrderRepository orderRepository;

    private ActivityProcessImpl process;

    @Before
    public void setUp() {
        guard = mock(IActivityDrawProcessGuard.class);
        userTakeActivityRepository = mock(IUserTakeActivityRepository.class);
        activityPartake = mock(IActivityPartake.class);
        drawExec = mock(IDrawExec.class);
        awardOrder = mock(IAwardOrder.class);
        orderRepository = mock(IOrderRepository.class);

        process = new ActivityProcessImpl();
        ReflectionTestUtils.setField(process, "activityDrawProcessGuard", guard);
        ReflectionTestUtils.setField(process, "userTakeActivityRepository", userTakeActivityRepository);
        ReflectionTestUtils.setField(process, "orderRepository", orderRepository);
        ReflectionTestUtils.setField(process, "activityPartake", activityPartake);
        ReflectionTestUtils.setField(process, "awardOrder", awardOrder);
        ReflectionTestUtils.setField(process, "drawExec", drawExec);
        ReflectionTestUtils.setField(process, "activityRepository", mock(IActivityRepository.class));

        when(guard.tryClaim(anyLong(), anyString(), anyString())).thenReturn(true);
    }

    @Test
    public void firstRequestRunsFullPipeline() {
        when(userTakeActivityRepository.queryTakeActivityByRequestId(100001L, "u1", "req-1")).thenReturn(null);
        when(activityPartake.doPartake(any())).thenReturn(partakeResult());
        when(drawExec.doDrawExec(any(DrawReq.class))).thenReturn(winResult());

        InvoiceVO invoice = new InvoiceVO();
        invoice.setOrderId(999L);
        AwardOrderResultHolder holder = new AwardOrderResultHolder(invoice);
        when(awardOrder.placeOrder(any())).thenAnswer(inv -> holder.result);

        DrawProcessResult result = process.doDrawProcess(new DrawProcessReq("u1", 100001L, "req-1"));

        assertEquals(Constants.ResponseCode.SUCCESS.getCode(), result.getCode());
        assertEquals(Long.valueOf(777L), result.getTakeId());
        assertEquals(Long.valueOf(999L), result.getOrderId());
        verify(activityPartake, times(1)).doPartake(any());
        verify(drawExec, times(1)).doDrawExec(any(DrawReq.class));
        verify(awardOrder, times(1)).placeOrder(any());
    }

    @Test
    public void retryWithSameRequestIdReplaysConsumedTakeWithoutSideEffects() {
        // 已消费的 take + 已存在的订单：第二次请求必须直接回放
        UserTakeActivityVO consumed = new UserTakeActivityVO();
        consumed.setRequestId("req-1");
        consumed.setTakeId(777L);
        consumed.setState(Constants.TaskState.USED.getCode());
        when(userTakeActivityRepository.queryTakeActivityByRequestId(100001L, "u1", "req-1")).thenReturn(consumed);

        InvoiceVO order = new InvoiceVO();
        order.setOrderId(999L);
        order.setAwardId("3");
        order.setAwardType(3);
        order.setAwardName("优惠券");
        when(orderRepository.queryOrderByTakeId("u1", 777L)).thenReturn(order);

        DrawProcessResult result = process.doDrawProcess(new DrawProcessReq("u1", 100001L, "req-1"));

        assertEquals(Constants.ResponseCode.SUCCESS.getCode(), result.getCode());
        assertEquals("3", result.getDrawAwardInfo().getAwardId());
        assertEquals(Long.valueOf(777L), result.getTakeId());
        assertEquals(Long.valueOf(999L), result.getOrderId());

        verify(activityPartake, never()).doPartake(any());
        verify(drawExec, never()).doDrawExec(any(DrawReq.class));
        verify(awardOrder, never()).placeOrder(any());
    }

    @Test
    public void interruptedTakeIsReusedNotDuplicated() {
        // 上次中断遗留的未消费 take：复用继续抽奖，不允许新建 take / 再次消耗次数
        UserTakeActivityVO unfinished = new UserTakeActivityVO();
        unfinished.setRequestId("req-1");
        unfinished.setTakeId(777L);
        unfinished.setStrategyId(10001L);
        unfinished.setState(Constants.TaskState.NO_USED.getCode());
        when(userTakeActivityRepository.queryTakeActivityByRequestId(100001L, "u1", "req-1")).thenReturn(unfinished);
        when(drawExec.doDrawExec(any(DrawReq.class))).thenReturn(
                new DrawResult("u1", 10001L, Constants.DrawState.FAIL.getCode()));

        when(activityPartake.consumeTakeActivity("u1", 100001L, 777L))
                .thenReturn(ResultStub.success());

        DrawProcessResult result = process.doDrawProcess(new DrawProcessReq("u1", 100001L, "req-1"));

        assertEquals(Constants.ResponseCode.LOSING_DRAW.getCode(), result.getCode());
        assertEquals(Long.valueOf(777L), result.getTakeId());
        verify(activityPartake, never()).doPartake(any());
        verify(activityPartake).consumeTakeActivity(eq("u1"), eq(100001L), eq(777L));
        ArgumentCaptor<DrawReq> captor = ArgumentCaptor.forClass(DrawReq.class);
        verify(drawExec).doDrawExec(captor.capture());
        assertEquals(String.valueOf(777L), captor.getValue().getUuid());
    }

    @Test
    public void guardIsAlwaysReleasedWithOwnerToken() {
        when(userTakeActivityRepository.queryTakeActivityByRequestId(anyLong(), anyString(), anyString()))
                .thenReturn(null);
        when(activityPartake.doPartake(any()))
                .thenThrow(new RuntimeException("boom"));

        try {
            process.doDrawProcess(new DrawProcessReq("u1", 100001L, "req-1"));
            org.junit.Assert.fail("必须传播参与异常");
        } catch (RuntimeException expected) {
            assertEquals("boom", expected.getMessage());
            // 传播异常也必须释放锁
        }
        verify(guard).release(eq(100001L), eq("u1"), any());
    }

    @Test
    public void queryFailureStopsBeforeAnySideEffects() {
        when(userTakeActivityRepository.queryTakeActivityByRequestId(100001L, "u1", "req-1"))
                .thenThrow(new IllegalStateException("query failed"));
        try {
            process.doDrawProcess(new DrawProcessReq("u1", 100001L, "req-1"));
            org.junit.Assert.fail("查询失败不能继续抽奖");
        } catch (IllegalStateException expected) {
            assertEquals("query failed", expected.getMessage());
        }
        verify(activityPartake, never()).doPartake(any());
        verify(drawExec, never()).doDrawExec(any());
        verify(guard).release(eq(100001L), eq("u1"), anyString());
    }

    @Test
    public void unfinishedConflictIsForwardedWithoutDrawing() {
        PartakeResult conflict = new PartakeResult(Constants.ResponseCode.UNFINISHED_DRAW.getCode(),
                Constants.ResponseCode.UNFINISHED_DRAW.getInfo());
        conflict.setPendingRequestId("A");
        when(activityPartake.doPartake(any())).thenReturn(conflict);
        DrawProcessResult result = process.doDrawProcess(new DrawProcessReq("u1", 100001L, "B"));
        assertEquals(Constants.ResponseCode.UNFINISHED_DRAW.getCode(), result.getCode());
        assertEquals("A", result.getPendingRequestId());
        org.junit.Assert.assertNull(result.getTakeId());
        verify(drawExec, never()).doDrawExec(any());
    }

    @Test
    public void losingCasConflictReplaysConcurrentWinningOrder() {
        UserTakeActivityVO take = new UserTakeActivityVO();
        take.setRequestId("req-1");
        take.setTakeId(777L);
        take.setStrategyId(10001L);
        take.setState(Constants.TaskState.NO_USED.getCode());
        UserTakeActivityVO completed = new UserTakeActivityVO();
        completed.setRequestId("req-1");
        completed.setTakeId(777L);
        completed.setState(Constants.TaskState.USED.getCode());
        when(userTakeActivityRepository.queryTakeActivityByRequestId(100001L, "u1", "req-1"))
                .thenReturn(take, completed);
        when(drawExec.doDrawExec(any())).thenReturn(new DrawResult("u1", 10001L, Constants.DrawState.FAIL.getCode()));
        when(activityPartake.consumeTakeActivity("u1", 100001L, 777L))
                .thenReturn(com.anontokyo.lottery.common.Result.buildResult(Constants.ResponseCode.NO_UPDATE));
        InvoiceVO invoice = new InvoiceVO();
        invoice.setOrderId(999L);
        when(orderRepository.queryOrderByTakeId("u1", 777L)).thenReturn(invoice);
        DrawProcessResult result = process.doDrawProcess(new DrawProcessReq("u1", 100001L, "req-1"));
        assertEquals(Constants.ResponseCode.SUCCESS.getCode(), result.getCode());
        assertEquals(Long.valueOf(999L), result.getOrderId());
    }

    private PartakeResult partakeResult() {
        PartakeResult partakeResult = new PartakeResult(Constants.ResponseCode.SUCCESS.getCode(),
                Constants.ResponseCode.SUCCESS.getInfo());
        partakeResult.setStrategyId(10001L);
        partakeResult.setTakeId(777L);
        return partakeResult;
    }

    private DrawResult winResult() {
        DrawAwardInfo awardInfo = new DrawAwardInfo("3", 3, "优惠券", "30元");
        return new DrawResult("u1", 10001L, Constants.DrawState.SUCCESS.getCode(), awardInfo);
    }

    /** 避免与 common.Result 命名冲突的辅助类 */
    private static class ResultStub {
        private static com.anontokyo.lottery.common.Result success() {
            return com.anontokyo.lottery.common.Result.buildSuccessResult();
        }
    }

    private static class AwardOrderResultHolder {
        private final com.anontokyo.lottery.domain.award.model.res.AwardOrderResult result;

        private AwardOrderResultHolder(InvoiceVO invoice) {
            this.result = new com.anontokyo.lottery.domain.award.model.res.AwardOrderResult(
                    Constants.ResponseCode.SUCCESS.getCode(),
                    Constants.ResponseCode.SUCCESS.getInfo(),
                    invoice);
        }
    }
}
