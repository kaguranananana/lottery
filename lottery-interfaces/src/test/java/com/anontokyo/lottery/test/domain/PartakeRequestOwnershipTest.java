package com.anontokyo.lottery.test.domain;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.activity.model.req.PartakeReq;
import com.anontokyo.lottery.domain.activity.model.res.PartakeResult;
import com.anontokyo.lottery.domain.activity.model.vo.DrawParticipationVO;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityBillVO;
import com.anontokyo.lottery.domain.activity.repository.IActivityRepository;
import com.anontokyo.lottery.domain.activity.repository.IDrawParticipationRepository;
import com.anontokyo.lottery.domain.activity.service.partake.impl.ActivityPartakeImpl;
import com.anontokyo.lottery.domain.support.ids.IdGenerator;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** 领域复用规则：缓存存在与否都不能改变请求归属。 */
public class PartakeRequestOwnershipTest {
    private IDrawParticipationRepository repository;
    private IActivityRepository activities;
    private ActivityPartakeImpl partake;

    @Before
    public void setUp() {
        repository = mock(IDrawParticipationRepository.class);
        activities = mock(IActivityRepository.class);
        partake = new ActivityPartakeImpl();
        ReflectionTestUtils.setField(partake, "drawParticipationRepository", repository);
        ReflectionTestUtils.setField(partake, "activityRepository", activities);
    }

    @Test
    public void sameRequestResumesWithoutAnotherQuotaDeduction() {
        unfinished("A");
        PartakeResult result = partake.doPartake(new PartakeReq("u1", 1L, "A"));
        assertEquals(Constants.ResponseCode.NOT_CONSUMED_TAKE.getCode(), result.getCode());
        assertEquals(Long.valueOf(7), result.getTakeId());
        verifyZeroInteractions(activities);
    }

    @Test
    public void differentRequestIsBlockedAndCanRecoverOriginalId() {
        unfinished("A");
        PartakeResult result = partake.doPartake(new PartakeReq("u1", 1L, "B"));
        assertEquals(Constants.ResponseCode.UNFINISHED_DRAW.getCode(), result.getCode());
        assertEquals("A", result.getPendingRequestId());
        assertNull(result.getTakeId());
        verifyZeroInteractions(activities);
    }

    @Test
    public void missingOwnershipIsExplicitError() {
        unfinished(null);
        assertEquals(Constants.ResponseCode.TAKE_STATE_ERROR.getCode(),
                partake.doPartake(new PartakeReq("u1", 1L, "B")).getCode());
        verifyZeroInteractions(activities);
    }

    @Test
    public void databaseFailureMustNotCreateParticipation() {
        when(repository.queryUnfinishedParticipation(1L, "u1"))
                .thenThrow(new IllegalStateException("database unavailable"));
        try {
            partake.doPartake(new PartakeReq("u1", 1L, "B"));
            fail("必须传播数据库异常");
        } catch (IllegalStateException expected) {
            assertEquals("database unavailable", expected.getMessage());
        }
        verifyZeroInteractions(activities);
    }

    @Test
    public void runningActivityAcquiresOnlyPersonalQuota() {
        ActivityBillVO bill = runningBill();
        when(repository.takeParticipation(eq(bill), any(), eq(9L), eq("A")))
                .thenReturn(Constants.ResponseCode.SUCCESS);
        PartakeResult result = partake.doPartake(new PartakeReq("u1", 1L, "A"));
        assertEquals(Constants.ResponseCode.SUCCESS.getCode(), result.getCode());
        assertEquals(Long.valueOf(9), result.getTakeId());
        verify(activities).queryActivityBill(any());
        verifyNoMoreInteractions(activities);
    }

    @Test
    public void exhaustedPersonalQuotaRejectsNewParticipation() {
        ActivityBillVO bill = runningBill();
        when(repository.takeParticipation(eq(bill), any(), eq(9L), eq("A")))
                .thenReturn(Constants.ResponseCode.NO_UPDATE);
        PartakeResult result = partake.doPartake(new PartakeReq("u1", 1L, "A"));
        assertEquals(Constants.ResponseCode.NO_UPDATE.getCode(), result.getCode());
        assertNull(result.getTakeId());
    }

    @Test
    public void closedOrFutureActivityDoesNotReachQuotaTransaction() {
        ActivityBillVO bill = runningBill();
        bill.setState(Constants.ActivityState.CLOSED.getCode());
        assertEquals(Constants.ResponseCode.UN_ERROR.getCode(),
                partake.doPartake(new PartakeReq("u1", 1L, "A")).getCode());
        bill.setState(Constants.ActivityState.RUNNING.getCode());
        bill.setBeginDateTime(new java.util.Date(Long.MAX_VALUE));
        assertEquals(Constants.ResponseCode.UN_ERROR.getCode(),
                partake.doPartake(new PartakeReq("u1", 1L, "B")).getCode());
        verify(repository, never()).takeParticipation(any(), any(), anyLong(), anyString());
    }

    private ActivityBillVO runningBill() {
        // 配置仅含活动时间、状态、策略和个人次数，不再构造活动库存。
        ActivityBillVO bill = new ActivityBillVO();
        bill.setuId("u1");
        bill.setActivityId(1L);
        bill.setTakeCount(3);
        bill.setState(Constants.ActivityState.RUNNING.getCode());
        bill.setBeginDateTime(new java.util.Date(0));
        bill.setEndDateTime(new java.util.Date(Long.MAX_VALUE));
        when(activities.queryActivityBill(any())).thenReturn(bill);
        ReflectionTestUtils.setField(partake, "idGenerator", (IdGenerator) () -> 9L);
        return bill;
    }

    private void unfinished(String requestId) {
        DrawParticipationVO take = new DrawParticipationVO();
        take.setRequestId(requestId);
        take.setTakeId(7L);
        take.setState(Constants.TaskState.NO_USED.getCode());
        when(repository.queryUnfinishedParticipation(1L, "u1")).thenReturn(take);
    }
}
