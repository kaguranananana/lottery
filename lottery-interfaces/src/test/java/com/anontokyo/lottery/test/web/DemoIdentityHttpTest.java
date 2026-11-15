package com.anontokyo.lottery.test.web;

import com.anontokyo.lottery.domain.activity.model.vo.ActivityQuotaVO;
import com.anontokyo.lottery.domain.activity.repository.IActivityRepository;
import com.anontokyo.lottery.interfaces.web.ActivityDetailsController;
import com.anontokyo.lottery.interfaces.web.DrawAdmission;
import com.anontokyo.lottery.interfaces.web.DrawIdentity;
import com.anontokyo.lottery.interfaces.web.LotteryController;
import org.junit.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 验证 Servlet 会话边界以及新额度接口的身份来源。 */
public class DemoIdentityHttpTest {
    private final DrawAdmission admission = mock(DrawAdmission.class);
    private final IActivityRepository activities = mock(IActivityRepository.class);

    @Test
    public void sessionSwitchIsWhitelistedAndIndependent() throws Exception {
        MockMvc mvc = mvc(true, "");
        // 默认账户读取不创建会话，两个浏览器后续选择各自保存。
        assertNull(mvc.perform(get("/api/v1/draw/identity"))
                .andExpect(jsonPath("$.userId").value("demo-a"))
                .andExpect(jsonPath("$.demoAccounts[2]").value("demo-c"))
                .andReturn().getRequest().getSession(false));

        MockHttpSession first = (MockHttpSession) mvc.perform(change("demo-b"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value("demo-b"))
                .andReturn().getRequest().getSession(false);
        MockHttpSession second = (MockHttpSession) mvc.perform(change("demo-c"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);

        mvc.perform(change("arbitrary-user").session(first)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/draw/identity").session(first))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.userId").value("demo-b"));
        mvc.perform(get("/api/v1/draw/identity").session(second))
                .andExpect(jsonPath("$.userId").value("demo-c"));
    }

    @Test
    public void drawAndQuotaIgnoreClientIdentityAndScopeToSession() throws Exception {
        MockMvc mvc = mvc(true, "");
        MockHttpSession session = new MockHttpSession();
        mvc.perform(change("demo-b").session(session)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/draw").session(session).contentType(MediaType.APPLICATION_JSON)
                .header("X-User-Id", "demo-a").header("X-Perf-Principal", "demo-a")
                .content("{\"activityId\":1,\"requestId\":\"original\",\"userId\":\"demo-a\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("A002"));
        verifyZeroInteractions(admission);

        when(activities.queryEffectiveQuota("demo-b", 1L)).thenReturn(new ActivityQuotaVO("demo-b", 4, 1));
        mvc.perform(get("/api/v1/activities/1/quota?userId=demo-a").session(session).header("X-User-Id", "demo-a"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.userId").value("demo-b"))
                .andExpect(jsonPath("$.totalCount").value(4))
                .andExpect(jsonPath("$.usedCount").value(3))
                .andExpect(jsonPath("$.remainingCount").value(1));
        verify(activities).queryEffectiveQuota("demo-b", 1L);
        verifyNoMoreInteractions(activities);
    }

    @Test
    public void principalTakesPriorityAndSwitchingRequiresLocalOptIn() throws Exception {
        MockMvc enabled = mvc(true, "");
        MockHttpSession demo = new MockHttpSession();
        enabled.perform(change("demo-b").session(demo)).andExpect(status().isOk());
        enabled.perform(get("/api/v1/draw/identity").session(demo).principal(() -> "member"))
                .andExpect(jsonPath("$.userId").value("member"))
                .andExpect(jsonPath("$.demoAccounts").doesNotExist());
        enabled.perform(change("demo-a").session(demo).principal(() -> "member"))
                .andExpect(status().isNotFound());
        enabled.perform(change("demo-a").with(request -> {
            request.setLocalAddr("192.0.2.1");
            return request;
        })).andExpect(status().isNotFound());
        enabled.perform(change("demo-a").with(request -> {
            request.setServerName("example.com");
            return request;
        })).andExpect(status().isNotFound());

        MockMvc disabled = mvc(false, "");
        disabled.perform(change("demo-a").session(demo)).andExpect(status().isNotFound());
        disabled.perform(get("/api/v1/draw/identity").session(demo)).andExpect(status().isUnauthorized());
        disabled.perform(get("/api/v1/activities/1/quota").header("X-User-Id", "demo-a"))
                .andExpect(status().isUnauthorized());
        verifyZeroInteractions(activities);
        assertThrows(IllegalStateException.class, () -> new DrawIdentity(true, "", "0.0.0.0"));
        assertThrows(IllegalStateException.class, () -> new DrawIdentity(false, "legacy", ""));
    }

    @Test
    public void originalFixedAccountRemainsUsableWithoutSwitcher() throws Exception {
        MockMvc mvc = mvc(false, "legacy");
        mvc.perform(get("/api/v1/draw/identity"))
                .andExpect(jsonPath("$.userId").value("legacy"))
                .andExpect(jsonPath("$.demoAccounts").doesNotExist());
        mvc.perform(change("demo-a")).andExpect(status().isNotFound());
    }

    private MockMvc mvc(boolean enabled, String fixedUser) {
        DrawIdentity identity = new DrawIdentity(enabled, fixedUser, "127.0.0.1");
        LotteryController draw = new LotteryController();
        ReflectionTestUtils.setField(draw, "drawIdentity", identity);
        ReflectionTestUtils.setField(draw, "drawAdmission", admission);
        return MockMvcBuilders.standaloneSetup(draw,
                new ActivityDetailsController(identity, activities)).build();
    }

    private MockHttpServletRequestBuilder change(String userId) {
        return put("/api/v1/draw/identity").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + userId + "\"}");
    }
}
