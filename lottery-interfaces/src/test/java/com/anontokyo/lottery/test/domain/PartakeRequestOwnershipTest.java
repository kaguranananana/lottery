package com.anontokyo.lottery.test.domain;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.activity.model.req.PartakeReq;
import com.anontokyo.lottery.domain.activity.model.res.PartakeResult;
import com.anontokyo.lottery.domain.activity.model.vo.UserTakeActivityVO;
import com.anontokyo.lottery.domain.activity.repository.IActivityRepository;
import com.anontokyo.lottery.domain.activity.repository.IUserTakeActivityRepository;
import com.anontokyo.lottery.domain.activity.service.partake.impl.ActivityPartakeImpl;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** 领域复用规则：缓存存在与否都不能改变请求归属。 */
public class PartakeRequestOwnershipTest {
    private IUserTakeActivityRepository repository;
    private IActivityRepository activities;
    private ActivityPartakeImpl partake;

    @Before
    public void setUp() {
        repository = mock(IUserTakeActivityRepository.class);
        activities = mock(IActivityRepository.class);
        partake = new ActivityPartakeImpl();
        ReflectionTestUtils.setField(partake, "userTakeActivityRepository", repository);
        ReflectionTestUtils.setField(partake, "activityRepository", activities);
    }

    @Test
    public void sameRequestResumesWithoutQuotaOrStock() {
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
        when(repository.queryNoConsumedTakeActivityOrder(1L, "u1"))
                .thenThrow(new IllegalStateException("database unavailable"));
        try {
            partake.doPartake(new PartakeReq("u1", 1L, "B"));
            fail("必须传播数据库异常");
        } catch (IllegalStateException expected) {
            assertEquals("database unavailable", expected.getMessage());
        }
        verifyZeroInteractions(activities);
    }

    private void unfinished(String requestId) {
        UserTakeActivityVO take = new UserTakeActivityVO();
        take.setRequestId(requestId);
        take.setTakeId(7L);
        take.setStrategyId(8L);
        take.setState(Constants.TaskState.NO_USED.getCode());
        when(repository.queryNoConsumedTakeActivityOrder(1L, "u1")).thenReturn(take);
    }
}
