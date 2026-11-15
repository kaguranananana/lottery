package com.anontokyo.lottery.test.manual;

import com.anontokyo.lottery.LotteryApplication;
import com.anontokyo.lottery.application.mq.consumer.RocketMqConsumers;
import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.activity.model.aggregates.ActivityConfigRich;
import com.anontokyo.lottery.domain.activity.model.req.ActivityConfigReq;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityPrizeVO;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityVO;
import com.anontokyo.lottery.domain.activity.model.vo.AwardVO;
import com.anontokyo.lottery.domain.activity.service.deploy.IActivityDeploy;
import com.anontokyo.lottery.domain.activity.service.lifecycle.ActivityLifecycleService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** 用真实 Cookie、MySQL、Redis 限流和 RocketMQ 验证演示会话与活动读取。 */
@RunWith(SpringRunner.class)
@ActiveProfiles("mq-e2e")
@SpringBootTest(classes = LotteryApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "lottery.draw.local-demo-enabled=true", "lottery.stock.bucket-count=8"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class DemoAccountsManualIT {
    @Autowired private IActivityDeploy deploy;
    @Autowired private ActivityLifecycleService lifecycle;
    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RocketMqConsumers consumers;
    @Autowired private StringRedisTemplate redis;

    @BeforeClass
    public static void schema() throws Exception {
        MqMiddlewareE2eManualIT.createSchema();
    }

    @Test
    public void sessionsKeepIndependentQuotaAndReplayWhileCatalogOnlyReadsItsOwnPool() throws Exception {
        consumers.start();
        long first = 500000000L + System.currentTimeMillis() % 100000000L;
        long second = first + 1;
        createActivity(first, 1, "文字纪念奖", "活动专属纪念奖", 1);
        createActivity(second, 2, "兑换奖品", "PRIVATE-REDEEM-CODE", 4);

        String sessionA = cookie(call(HttpMethod.PUT, "/draw/identity", "{\"userId\":\"demo-a\"}", null));
        String sessionB = cookie(call(HttpMethod.PUT, "/draw/identity", "{\"userId\":\"demo-b\"}", null));
        assertNotEquals(sessionA, sessionB);
        assertQuota(first, sessionA, "demo-a", 1, 0, 1);
        assertQuota(first, sessionB, "demo-b", 1, 0, 1);
        assertQuota(second, sessionA, "demo-a", 4, 0, 4);

        // 测试数据有不同活动奖池，只有文字描述可以公开。
        List<Long> before = activityState(first);
        List<Long> beforeSecond = activityState(second);
        JsonNode prizesA = get("/activities/" + first + "/prizes", null);
        assertEquals(1, prizesA.size());
        assertEquals("prize-" + first, prizesA.get(0).path("awardId").asText());
        assertEquals("文字纪念奖", prizesA.get(0).path("awardName").asText());
        assertEquals(1, prizesA.get(0).path("awardType").asInt());
        assertEquals("活动专属纪念奖", prizesA.get(0).path("description").asText());
        assertEquals(4, prizesA.get(0).size());

        JsonNode prizesB = get("/activities/" + second + "/prizes", null);
        assertEquals(1, prizesB.size());
        assertEquals("prize-" + second, prizesB.get(0).path("awardId").asText());
        assertEquals("兑换奖品", prizesB.get(0).path("awardName").asText());
        assertEquals(2, prizesB.get(0).path("awardType").asInt());
        assertTrue(prizesB.get(0).path("description").isNull());
        assertEquals(4, prizesB.get(0).size());
        assertEquals(0, get("/activities/0/prizes", null).size());
        assertEquals(before, activityState(first));
        assertEquals(beforeSecond, activityState(second));
        assertEquals(0, count("SELECT COUNT(*) FROM user_activity_quota WHERE activity_id IN (?, ?)", first, second));
        assertEquals(0, count("SELECT COUNT(*) FROM draw_participation WHERE activity_id IN (?, ?)", first, second));
        assertEquals(0, count("SELECT COUNT(*) FROM draw_order WHERE activity_id IN (?, ?)", first, second));
        assertEquals(0, count("SELECT COUNT(*) FROM award_stock_settlement WHERE activity_id IN (?, ?)", first, second));
        assertEquals(20, count("SELECT SUM(award_surplus_count) FROM activity_prize WHERE activity_id IN (?, ?)", first, second));
        assertEquals("10", redis.opsForValue().get(Constants.RedisKey.KEY_LOTTERY_ACTIVITY_PRIZE_STOCK_COUNT(first, "prize-" + first)));
        assertEquals("10", redis.opsForValue().get(Constants.RedisKey.KEY_LOTTERY_ACTIVITY_PRIZE_STOCK_COUNT(second, "prize-" + second)));

        JsonNode resultA = draw(first, sessionA, "demo-a", "same-request");
        assertEquals("0000", resultA.path("code").asText());
        assertQuota(first, sessionA, "demo-a", 1, 1, 0);
        assertQuota(first, sessionB, "demo-b", 1, 0, 1);
        assertEquals("0004", draw(first, sessionA, "demo-a", "beyond-quota").path("code").asText());

        JsonNode resultB = draw(first, sessionB, "demo-b", "same-request");
        assertEquals("0000", resultB.path("code").asText());
        assertNotEquals(resultA.path("takeId"), resultB.path("takeId"));
        assertNotEquals(resultA.path("orderId"), resultB.path("orderId"));
        assertQuota(first, sessionB, "demo-b", 1, 1, 0);

        assertEquals(HttpStatus.OK, call(HttpMethod.PUT, "/draw/identity", "{\"userId\":\"demo-c\"}", sessionA).getStatusCode());
        assertQuota(first, sessionA, "demo-c", 1, 0, 1);
        assertQuota(first, sessionB, "demo-b", 1, 1, 0);
        assertEquals(HttpStatus.OK, call(HttpMethod.PUT, "/draw/identity", "{\"userId\":\"demo-a\"}", sessionA).getStatusCode());
        assertQuota(first, sessionA, "demo-a", 1, 1, 0);
        JsonNode replay = draw(first, sessionA, "demo-a", "same-request");
        assertEquals(resultA.path("takeId"), replay.path("takeId"));
        assertEquals(resultA.path("orderId"), replay.path("orderId"));
        assertQuota(first, sessionA, "demo-a", 1, 1, 0);
        assertEquals(2, count("SELECT COUNT(*) FROM draw_participation WHERE activity_id=?", first));
        assertEquals(2, count("SELECT COUNT(*) FROM draw_order WHERE activity_id=?", first));

        // 实际发奖完成并写入唯一结算账本，检查重复抽奖没有多结算库存。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (count("SELECT COUNT(*) FROM draw_order WHERE activity_id=? AND fulfilled_at IS NOT NULL", first) < 2
                && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertEquals(2, count("SELECT COUNT(*) FROM draw_order WHERE activity_id=? AND fulfilled_at IS NOT NULL", first));
        assertEquals(2, count("SELECT COUNT(*) FROM award_stock_settlement WHERE activity_id=?", first));
        assertEquals(8, count("SELECT SUM(remaining_count) FROM award_stock_bucket WHERE activity_id=?", first));
    }

    private List<Long> activityState(long id) {
        return jdbc.queryForList("SELECT remaining_count FROM award_stock_bucket WHERE activity_id=? ORDER BY award_id,bucket_id",
                Long.class, id);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private void createActivity(long id, int awardType, String name, String content, int quota) {
        Date now = new Date();
        ActivityVO activity = new ActivityVO();
        activity.setActivityId(id);
        activity.setActivityName(name + "体验场");
        activity.setBeginDateTime(new Date(now.getTime() - 1000));
        activity.setEndDateTime(new Date(now.getTime() + 3600000));
        activity.setTakeCount(quota);
        activity.setState(Constants.ActivityState.READY.getCode());
        activity.setCreator("demo-test");

        AwardVO award = new AwardVO();
        award.setAwardId("prize-" + id);
        award.setAwardName(name);
        award.setAwardType(awardType);
        award.setAwardContent(content);

        ActivityPrizeVO prize = new ActivityPrizeVO();
        prize.setAwardId(award.getAwardId());
        prize.setAwardCount(10);
        prize.setAwardRate(BigDecimal.ONE);

        deploy.createActivity(new ActivityConfigReq(id, new ActivityConfigRich(activity,
                Collections.singletonList(prize), Collections.singletonList(award))));
        assertTrue(lifecycle.transition(id, Constants.ActivityState.READY, Constants.ActivityState.RUNNING));
    }

    private void assertQuota(long activity, String session, String user, int total, int used, int left) {
        JsonNode quota = get("/activities/" + activity + "/quota", session);
        assertEquals(user, quota.path("userId").asText());
        assertEquals(total, quota.path("totalCount").asInt());
        assertEquals(used, quota.path("usedCount").asInt());
        assertEquals(left, quota.path("remainingCount").asInt());
    }

    private JsonNode draw(long activity, String session, String user, String requestId) {
        ResponseEntity<JsonNode> response = call(HttpMethod.POST, "/draw", String.format(
                "{\"activityId\":%d,\"userId\":\"%s\",\"requestId\":\"%s\"}", activity, user, requestId), session);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        return response.getBody();
    }

    private JsonNode get(String path, String session) {
        ResponseEntity<JsonNode> response = call(HttpMethod.GET, path, null, session);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        return response.getBody();
    }

    private String cookie(ResponseEntity<JsonNode> response) {
        assertEquals(HttpStatus.OK, response.getStatusCode());
        String cookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertNotNull(cookie);
        return cookie.split(";", 2)[0];
    }

    private ResponseEntity<JsonNode> call(HttpMethod method, String path, String body, String session) {
        HttpHeaders headers = new HttpHeaders();
        if (body != null) headers.setContentType(MediaType.APPLICATION_JSON);
        if (session != null) headers.set(HttpHeaders.COOKIE, session);
        return http.exchange("/api/v1" + path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }
}
