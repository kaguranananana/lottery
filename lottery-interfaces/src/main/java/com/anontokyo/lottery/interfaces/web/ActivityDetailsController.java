package com.anontokyo.lottery.interfaces.web;

import com.anontokyo.lottery.domain.activity.model.vo.ActivityQuotaVO;
import com.anontokyo.lottery.domain.activity.model.vo.PublicActivityPrizeVO;
import com.anontokyo.lottery.domain.activity.repository.IActivityRepository;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

/** 为演示页读取活动配置与当前账户额度。 */
@RestController
@RequestMapping("/api/v1/activities/{activityId}")
public class ActivityDetailsController {
    private final DrawIdentity identity;
    private final IActivityRepository activities;

    public ActivityDetailsController(DrawIdentity identity, IActivityRepository activities) {
        this.identity = identity;
        this.activities = activities;
    }

    @GetMapping("/quota")
    public ResponseEntity<ActivityQuotaVO> quota(@PathVariable Long activityId, HttpServletRequest request) {
        String userId = identity.currentUser(request);
        if (userId.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        ActivityQuotaVO quota = activities.queryEffectiveQuota(userId, activityId);
        if (quota == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(quota);
    }

    @GetMapping("/prizes")
    public List<PublicActivityPrizeVO> prizes(@PathVariable Long activityId) {
        return activities.queryPublicPrizes(activityId);
    }
}
