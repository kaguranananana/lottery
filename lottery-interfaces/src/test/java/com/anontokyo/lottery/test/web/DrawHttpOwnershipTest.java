package com.anontokyo.lottery.test.web;

import com.anontokyo.lottery.application.process.draw.IActivityDrawProcess;
import com.anontokyo.lottery.application.process.draw.res.DrawProcessResult;
import com.anontokyo.lottery.common.Constants;
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

    @Before
    public void setUp() {
        process = mock(IActivityDrawProcess.class);
        LotteryController controller = new LotteryController();
        ReflectionTestUtils.setField(controller, "activityDrawProcess", process);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    public void blockedBReturnsAThroughSeparateRecoveryField() throws Exception {
        DrawProcessResult blocked = new DrawProcessResult(Constants.ResponseCode.UNFINISHED_DRAW.getCode(), "存在未完成抽奖");
        blocked.setPendingRequestId("A");
        when(process.doDrawProcess(any())).thenReturn(blocked);
        mvc.perform(post("/api/v1/draw").contentType(MediaType.APPLICATION_JSON)
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
        mvc.perform(post("/api/v1/draw").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"u1\",\"activityId\":1,\"requestId\":\"A\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0001"))
                .andExpect(jsonPath("$.requestId").value("A"));
    }

    @Test
    public void missingRequestIdCannotEnterApplication() throws Exception {
        mvc.perform(post("/api/v1/draw").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"u1\",\"activityId\":1}"))
                .andExpect(status().isBadRequest());
        verifyZeroInteractions(process);
    }
}
