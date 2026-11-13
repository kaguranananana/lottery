package com.anontokyo.lottery.interfaces.web;

import com.anontokyo.lottery.application.process.draw.IActivityDrawProcess;
import com.anontokyo.lottery.application.process.draw.req.DrawProcessReq;
import com.anontokyo.lottery.application.process.draw.res.DrawProcessResult;
import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.common.Result;
import com.anontokyo.lottery.domain.draw.model.vo.DrawAwardInfo;
import com.anontokyo.lottery.interfaces.web.dto.DrawRequest;
import com.anontokyo.lottery.interfaces.web.dto.DrawResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.security.Principal;
import java.util.Collections;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP 入口。只做参数校验、DTO 转换和 use case 调用，业务规则都在 application/domain。
 */
@RestController
@RequestMapping("/api/v1")
@Validated
public class LotteryController {

    private final Logger logger = LoggerFactory.getLogger(LotteryController.class);

    @Resource
    private IActivityDrawProcess activityDrawProcess;

    @Resource
    private DrawAdmission drawAdmission;

    @Value("${lottery.draw.local-demo-user:}")
    private String localDemoUser = "";

    @Value("${server.address:}")
    private String serverAddress = "";

    @PostConstruct
    void validateDemoBinding() {
        if (!localDemoUser.isEmpty() && !"127.0.0.1".equals(serverAddress)) {
            throw new IllegalStateException("抽奖演示账户要求应用显式绑定 127.0.0.1");
        }
    }

    @GetMapping("/draw/identity")
    public ResponseEntity<Map<String, String>> drawIdentity(Principal principal) {
        String userId = currentUser(principal);
        if (userId.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(Collections.singletonMap("userId", userId));
    }

    @PostMapping("/draw")
    public ResponseEntity<DrawResponse> draw(@Validated @RequestBody DrawRequest request, Principal principal) {
        // 保留请求字段用于身份匹配，实际业务只使用已认证身份或固定的本机演示账户。
        String userId = currentUser(principal);
        if (userId.isEmpty()) {
            return reject(request, HttpStatus.UNAUTHORIZED, "A001", "请先登录");
        }
        if (!userId.equals(request.getUserId())) {
            return reject(request, HttpStatus.FORBIDDEN, "A002", "抽奖用户与当前身份不一致");
        }
        if (!drawAdmission.tryAcquire()) {
            return reject(request, HttpStatus.TOO_MANY_REQUESTS, "L003", "抽奖并发已满，请稍后使用原请求号重试");
        }
        try {
            DrawAdmission.Decision decision;
            try {
                decision = drawAdmission.checkBuckets(userId, request.getActivityId());
            } catch (RuntimeException e) {
                logger.error("draw admission unavailable requestId:{} userId:{} activityId:{}",
                        request.getRequestId(), userId, request.getActivityId(), e);
                return reject(request, HttpStatus.SERVICE_UNAVAILABLE, "L004", "抽奖入口暂不可用，请稍后使用原请求号重试");
            }
            if (decision == DrawAdmission.Decision.USER_LIMIT) {
                return reject(request, HttpStatus.TOO_MANY_REQUESTS, "L002", "当前活动抽奖过于频繁，请稍后使用原请求号重试");
            }
            if (decision == DrawAdmission.Decision.GLOBAL_LIMIT) {
                return reject(request, HttpStatus.TOO_MANY_REQUESTS, "L001", "抽奖入口繁忙，请稍后使用原请求号重试");
            }

            // 获得两种令牌后才调用原业务，数据库幂等和同步返回语义保持一致。
            logger.debug("http draw requestId:{} userId:{} activityId:{}",
                    request.getRequestId(), userId, request.getActivityId());
            DrawProcessResult result = activityDrawProcess.doDrawProcess(
                    new DrawProcessReq(userId, request.getActivityId(), request.getRequestId()));
            return ResponseEntity.ok(toResponse(result, request));
        } catch (Exception e) {
            logger.error("http draw exception requestId:{} userId:{} activityId:{}",
                    request.getRequestId(), userId, request.getActivityId(), e);
            DrawResponse response = new DrawResponse();
            response.setRequestId(request.getRequestId());
            fillError(response, "System error: " + e.getClass().getSimpleName());
            return ResponseEntity.ok(response);
        } finally {
            // 业务异常、限流拒绝及 Redis 故障都归还本机许可。
            drawAdmission.release();
        }
    }

    private String currentUser(Principal principal) {
        return principal == null ? localDemoUser : principal.getName();
    }

    private ResponseEntity<DrawResponse> reject(DrawRequest request, HttpStatus status, String code, String info) {
        DrawResponse response = new DrawResponse();
        response.setRequestId(request.getRequestId());
        response.setCode(code);
        response.setInfo(info);
        return ResponseEntity.status(status).body(response);
    }

    @GetMapping("/activities")
    public List<ActivityItem> queryRunningActivityList() {
        List<com.anontokyo.lottery.domain.activity.model.vo.ActivityVO> list =
                activityDrawProcess.queryRunningActivityList();
        List<ActivityItem> items = new ArrayList<>(list.size());
        for (com.anontokyo.lottery.domain.activity.model.vo.ActivityVO vo : list) {
            ActivityItem item = new ActivityItem();
            item.setActivityId(vo.getActivityId());
            item.setActivityName(vo.getActivityName());
            item.setActivityDesc(vo.getActivityDesc());
            item.setBeginDateTime(vo.getBeginDateTime());
            item.setEndDateTime(vo.getEndDateTime());
            items.add(item);
        }
        return items;
    }

    private DrawResponse toResponse(DrawProcessResult result, DrawRequest request) {
        DrawResponse response = new DrawResponse();
        response.setCode(result.getCode());
        response.setInfo(result.getInfo());
        response.setRequestId(request.getRequestId());
        response.setTakeId(result.getTakeId());
        // 原请求号通过独立字段返回，避免客户端误以为 B 已绑定 A。
        response.setPendingRequestId(result.getPendingRequestId());
        response.setOrderId(result.getOrderId());

        boolean success = Constants.ResponseCode.SUCCESS.getCode().equals(result.getCode());
        response.setWin(success && result.getDrawAwardInfo() != null);

        if (success && result.getDrawAwardInfo() != null) {
            DrawAwardInfo awardInfo = result.getDrawAwardInfo();
            DrawResponse.AwardVO award = new DrawResponse.AwardVO();
            award.setAwardId(awardInfo.getAwardId());
            award.setAwardType(awardInfo.getAwardType());
            award.setAwardName(awardInfo.getAwardName());
            award.setAwardContent(awardInfo.getAwardContent());
            // 保留旧客户端的 grantDate 字段，只表示已确认的实际发奖时间。
            java.util.Date fulfilledAt = awardInfo.getFulfilledAt();
            award.setGrantDate(fulfilledAt == null ? null : fulfilledAt.toString());
            response.setAward(award);
        }
        return response;
    }

    private void fillError(DrawResponse response, String info) {
        Result error = Result.buildErrorResult();
        response.setCode(error.getCode());
        response.setInfo(info == null ? error.getInfo() : info);
    }

    public static class ActivityItem {
        private Long activityId;
        private String activityName;
        private String activityDesc;
        private java.util.Date beginDateTime;
        private java.util.Date endDateTime;

        public Long getActivityId() { return activityId; }
        public void setActivityId(Long activityId) { this.activityId = activityId; }
        public String getActivityName() { return activityName; }
        public void setActivityName(String activityName) { this.activityName = activityName; }
        public String getActivityDesc() { return activityDesc; }
        public void setActivityDesc(String activityDesc) { this.activityDesc = activityDesc; }
        public java.util.Date getBeginDateTime() { return beginDateTime; }
        public void setBeginDateTime(java.util.Date beginDateTime) { this.beginDateTime = beginDateTime; }
        public java.util.Date getEndDateTime() { return endDateTime; }
        public void setEndDateTime(java.util.Date endDateTime) { this.endDateTime = endDateTime; }
    }
}
