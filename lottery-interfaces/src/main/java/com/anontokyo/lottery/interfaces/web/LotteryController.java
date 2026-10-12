package com.anontokyo.lottery.interfaces.web;

import com.anontokyo.lottery.application.process.draw.IActivityDrawProcess;
import com.anontokyo.lottery.application.process.draw.req.DrawProcessReq;
import com.anontokyo.lottery.application.process.draw.res.DrawProcessResult;
import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.common.Result;
import com.anontokyo.lottery.domain.strategy.model.vo.DrawAwardInfo;
import com.anontokyo.lottery.interfaces.web.dto.DrawRequest;
import com.anontokyo.lottery.interfaces.web.dto.DrawResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
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

    @PostMapping("/draw")
    public DrawResponse draw(@Validated @RequestBody DrawRequest request) {
        logger.info("http draw requestId:{} userId:{} activityId:{}",
                request.getRequestId(), request.getUserId(), request.getActivityId());
        try {
            DrawProcessResult result = activityDrawProcess.doDrawProcess(
                    new DrawProcessReq(request.getUserId(), request.getActivityId(), request.getRequestId()));
            return toResponse(result, request);
        } catch (Exception e) {
            logger.error("http draw exception requestId:{} userId:{} activityId:{}",
                    request.getRequestId(), request.getUserId(), request.getActivityId(), e);
            DrawResponse response = new DrawResponse();
            response.setRequestId(request.getRequestId());
            fillError(response, "System error: " + e.getClass().getSimpleName());
            return response;
        }
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
            award.setGrantType(awardInfo.getGrantType());
            award.setGrantDate(awardInfo.getGrantDate() == null ? null : awardInfo.getGrantDate().toString());
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
