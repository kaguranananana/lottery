package com.anontokyo.lottery.domain.activity.service.deploy.Impl;

import com.anontokyo.lottery.common.Constants.ActivityState;
import com.anontokyo.lottery.domain.activity.model.aggregates.ActivityConfigRich;
import com.anontokyo.lottery.domain.activity.model.aggregates.ActivityInfoLimitPageRich;
import com.anontokyo.lottery.domain.activity.model.req.ActivityConfigReq;
import com.anontokyo.lottery.domain.activity.model.req.ActivityInfoLimitPageReq;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityVO;
import com.anontokyo.lottery.domain.activity.model.vo.AwardVO;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityPrizeVO;
import com.anontokyo.lottery.domain.activity.repository.IActivityRepository;
import com.anontokyo.lottery.domain.activity.service.deploy.IActivityDeploy;
import com.alibaba.fastjson.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.List;


/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/12
 */
@Service
public class ActivityDeployImpl implements IActivityDeploy {

    private Logger logger = LoggerFactory.getLogger(ActivityDeployImpl.class);

    @Resource
    private IActivityRepository activityRepository;

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void createActivity(ActivityConfigReq req) {
        logger.info("创建活动配置开始 ,activityId : {}", req.getActivityId());
        ActivityConfigRich activityConfigRich = req.getActivityConfigRich();
        try {
            ActivityVO activity = activityConfigRich.getActivity();
            // 只能通过生命周期启动活动，创建入口不得绕过 Redis 预热。
            if (!ActivityState.DRAFT.getCode().equals(activity.getState())
                    && !ActivityState.READY.getCode().equals(activity.getState())) {
                throw new IllegalArgumentException("新活动必须为 DRAFT 或 READY，activityId=" + activity.getActivityId());
            }
            List<ActivityPrizeVO> prizes = activityConfigRich.getActivityPrizeList();
            if (prizes.isEmpty()) {
                throw new IllegalArgumentException("活动奖池不能为空，activityId=" + activity.getActivityId());
            }
            BigDecimal totalRate = BigDecimal.ZERO;
            // 在持久化前验证整池概率；单条库存、概率和奖品引用由数据库约束。
            for (ActivityPrizeVO prize : prizes) {
                BigDecimal rate = prize.getAwardRate();
                if (rate == null || rate.stripTrailingZeros().scale() > 6) {
                    throw new IllegalArgumentException("奖品概率须配置且最多六位小数，awardId=" + prize.getAwardId());
                }
                // 持久化精度与采样精度一致，避免数据库逐项舍入后总概率超过 100%。
                totalRate = totalRate.add(rate);

                // 每条明细归属本活动；剩余库存从初始数量建立。
                prize.setActivityId(activity.getActivityId());
                prize.setAwardSurplusCount(prize.getAwardCount());
            }
            if (totalRate.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("活动奖池总概率超过 100%，activityId=" + activity.getActivityId());
            }
            activityRepository.addActivity(activity);
            // 已有全局奖品直接引用，无需重复创建或覆盖奖品目录。
            List<AwardVO> awards = activityConfigRich.getAwardList();
            if (!awards.isEmpty()) {
                activityRepository.addAward(awards);
            }
            activityRepository.addActivityPrizeList(prizes);

            logger.info("创建活动配置完成，activityId：{}", req.getActivityId());
        } catch (DuplicateKeyException e) {
            logger.error("创建活动配置失败，唯一索引冲突 activityId：{} reqJson：{}", req.getActivityId(), JSON.toJSONString(req), e);
            throw e;
        }

    }

    @Override
    public void updateActivity(ActivityConfigReq req) {
        // TODO: 非核心功能, 后续补充

    }

    @Override
    public List<ActivityVO> scanToDoActivityList(Long id) {
        return activityRepository.scanToDoActivityList(id);
    }

    @Override
    public ActivityInfoLimitPageRich queryActivityInfoLimitPage(ActivityInfoLimitPageReq req) {
        return activityRepository.queryActivityInfoLimitPage(req);
    }
}
