package com.anontokyo.lottery.domain.activity.service.lifecycle;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.activity.repository.IActivityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 活动状态机。合法转换显式列出，非法转换直接拒绝；
 * 落库使用 compare-and-set（UPDATE ... WHERE state = expected），以影响行数判断是否成功。
 *
 * 合法转换：
 * EDIT(1)  -> PASS(4)   运营确认就绪
 * PASS(4)  -> DOING(5)  调度进入运行
 * PASS(4)  -> CLOSE(7)  未开始即下线
 * DOING(5) -> CLOSE(7)  到期或人工关闭
 */
@Service
public class ActivityLifecycleService {

    private final Logger logger = LoggerFactory.getLogger(ActivityLifecycleService.class);

    private static final Map<Constants.ActivityState, Set<Constants.ActivityState>> ALLOWED_TRANSITIONS;

    static {
        Map<Constants.ActivityState, Set<Constants.ActivityState>> transitions = new EnumMap<>(Constants.ActivityState.class);
        transitions.put(Constants.ActivityState.EDIT, EnumSet.of(Constants.ActivityState.PASS));
        transitions.put(Constants.ActivityState.PASS, EnumSet.of(Constants.ActivityState.DOING, Constants.ActivityState.CLOSE));
        transitions.put(Constants.ActivityState.DOING, EnumSet.of(Constants.ActivityState.CLOSE));
        transitions.put(Constants.ActivityState.CLOSE, Collections.emptySet());
        ALLOWED_TRANSITIONS = Collections.unmodifiableMap(transitions);
    }

    @Resource
    private IActivityRepository activityRepository;

    /**
     * @return true 表示状态成功变更为 target；false 表示转换被拒绝（不合法或 CAS 失败）
     */
    public boolean transition(Long activityId, Constants.ActivityState expected, Constants.ActivityState target) {
        if (!ALLOWED_TRANSITIONS.getOrDefault(expected, Collections.emptySet()).contains(target)) {
            logger.warn("illegal activity transition rejected activityId:{} {} -> {}",
                    activityId, expected, target);
            return false;
        }
        boolean updated = activityRepository.alterStatus(activityId, expected, target);
        if (!updated) {
            logger.info("activity transition cas failed activityId:{} expected:{} target:{}",
                    activityId, expected, target);
        }
        return updated;
    }
}
