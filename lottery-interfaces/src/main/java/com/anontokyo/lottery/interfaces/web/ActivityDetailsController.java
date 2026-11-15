package com.anontokyo.lottery.interfaces.web;

import com.anontokyo.lottery.infrastructure.dao.IActivityPrizeDao;
import com.anontokyo.lottery.infrastructure.dao.IUserActivityQuotaDao;
import com.anontokyo.lottery.infrastructure.po.Award;
import com.anontokyo.lottery.infrastructure.po.UserActivityQuota;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;

/** 为演示页读取活动配置与当前账户额度。 */
@RestController
@RequestMapping("/api/v1/activities/{activityId}")
public class ActivityDetailsController {
    private final DrawIdentity identity;
    private final IUserActivityQuotaDao quotaDao;
    private final IActivityPrizeDao prizeDao;

    public ActivityDetailsController(DrawIdentity identity, IUserActivityQuotaDao quotaDao, IActivityPrizeDao prizeDao) {
        this.identity = identity;
        this.quotaDao = quotaDao;
        this.prizeDao = prizeDao;
    }

    @GetMapping("/quota")
    public ResponseEntity<Quota> quota(@PathVariable Long activityId, HttpServletRequest request) {
        String userId = identity.currentUser(request);
        if (userId.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        // 查询范围由服务端身份与活动决定，首次查询只读取初始额度。
        UserActivityQuota scope = new UserActivityQuota();
        scope.setuId(userId);
        scope.setActivityId(activityId);
        UserActivityQuota quota = quotaDao.queryEffectiveQuota(scope);
        if (quota == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Quota(userId, quota));
    }

    @GetMapping("/prizes")
    public List<Prize> prizes(@PathVariable Long activityId) {
        // 只投影前端展示字段，不序列化数据库对象。
        List<Award> awards = prizeDao.queryPublicPrizes(activityId);
        List<Prize> items = new ArrayList<>(awards.size());
        for (Award award : awards) {
            items.add(new Prize(award));
        }
        return items;
    }

    public static class Quota {
        public final String userId;
        public final int totalCount;
        public final int usedCount;
        public final int remainingCount;

        private Quota(String userId, UserActivityQuota quota) {
            this.userId = userId;
            this.totalCount = quota.getTotalCount();
            this.remainingCount = quota.getLeftCount();
            this.usedCount = totalCount - remainingCount;
        }
    }

    public static class Prize {
        public final String awardId;
        public final String awardName;
        public final Integer awardType;
        public final String description;

        private Prize(Award award) {
            this.awardId = award.getAwardId();
            this.awardName = award.getAwardName();
            this.awardType = award.getAwardType();
            this.description = award.getAwardContent();
        }
    }
}
