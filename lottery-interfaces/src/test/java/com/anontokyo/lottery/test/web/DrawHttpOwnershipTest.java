package com.anontokyo.lottery.test.web;

import com.anontokyo.lottery.application.process.draw.IActivityDrawProcess;
import com.anontokyo.lottery.application.process.draw.res.DrawProcessResult;
import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.interfaces.web.DrawAdmission;
import com.anontokyo.lottery.interfaces.web.LotteryController;
import org.junit.Before;
import org.junit.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 验证 HTTP 归属字段、参数校验与未知结果下的请求号回显。 */
public class DrawHttpOwnershipTest {
    private IActivityDrawProcess process;
    private MockMvc mvc;
    private DrawAdmission admission;

    @Before
    public void setUp() {
        process = mock(IActivityDrawProcess.class);
        LotteryController controller = new LotteryController();
        ReflectionTestUtils.setField(controller, "activityDrawProcess", process);
        admission = mock(DrawAdmission.class);
        when(admission.tryAcquire()).thenReturn(true);
        when(admission.checkBuckets("u1", 1L)).thenReturn(DrawAdmission.Decision.ADMITTED);
        ReflectionTestUtils.setField(controller, "drawAdmission", admission);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    public void blockedBReturnsAThroughSeparateRecoveryField() throws Exception {
        DrawProcessResult blocked = new DrawProcessResult(Constants.ResponseCode.UNFINISHED_DRAW.getCode(), "存在未完成抽奖");
        blocked.setPendingRequestId("A");
        when(process.doDrawProcess(any())).thenReturn(blocked);
        mvc.perform(post("/api/v1/draw").principal(() -> "u1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"u1\",\"activityId\":1,\"requestId\":\"B\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("D007"))
                .andExpect(jsonPath("$.requestId").value("B"))
                .andExpect(jsonPath("$.pendingRequestId").value("A"))
                .andExpect(jsonPath("$.takeId").doesNotExist());
    }

    @Test
    public void unknownResultRetainsOriginalRequestId() throws Exception {
        when(process.doDrawProcess(any())).thenThrow(new IllegalStateException("database unavailable"));
        mvc.perform(post("/api/v1/draw").principal(() -> "u1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"u1\",\"activityId\":1,\"requestId\":\"A\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0001"))
                .andExpect(jsonPath("$.requestId").value("A"));
        verify(admission).release();
    }

    @Test
    public void unauthenticatedAndSpoofedUserCannotReachLimiterOrBusiness() throws Exception {
        String body = "{\"userId\":\"u1\",\"activityId\":1,\"requestId\":\"A\"}";
        mvc.perform(post("/api/v1/draw").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/draw").principal(() -> "u2").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        verifyZeroInteractions(admission, process);
    }

    @Test
    public void concurrencyRejectionDoesNotReadRedisOrReleaseUnownedPermit() throws Exception {
        when(admission.tryAcquire()).thenReturn(false);
        mvc.perform(post("/api/v1/draw").principal(() -> "u1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"u1\",\"activityId\":1,\"requestId\":\"A\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("L003"));
        verify(admission, never()).checkBuckets(anyString(), anyLong());
        verify(admission, never()).release();
        verifyZeroInteractions(process);
    }

    @Test
    public void limiterErrorReturnsUnavailableAndReleasesPermit() throws Exception {
        when(admission.checkBuckets("u1", 1L)).thenThrow(new IllegalStateException("Redis unavailable"));
        mvc.perform(post("/api/v1/draw").principal(() -> "u1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"u1\",\"activityId\":1,\"requestId\":\"A\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("L004"))
                .andExpect(jsonPath("$.requestId").value("A"));
        verify(admission).release();
        verifyZeroInteractions(process);
    }

    @Test
    public void missingRequestIdCannotEnterApplication() throws Exception {
        mvc.perform(post("/api/v1/draw").principal(() -> "u1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"u1\",\"activityId\":1}"))
                .andExpect(status().isBadRequest());
        verifyZeroInteractions(process);
    }
}
