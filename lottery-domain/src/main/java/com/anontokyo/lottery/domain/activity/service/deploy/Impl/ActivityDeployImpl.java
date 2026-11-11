package com.anontokyo.lottery.domain.activity.service.deploy.Impl;

import com.anontokyo.lottery.domain.activity.model.aggregates.ActivityConfigRich;
import com.anontokyo.lottery.domain.activity.model.aggregates.ActivityInfoLimitPageRich;
import com.anontokyo.lottery.domain.activity.model.req.ActivityConfigReq;
import com.anontokyo.lottery.domain.activity.model.req.ActivityInfoLimitPageReq;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityVO;
import com.anontokyo.lottery.domain.activity.model.vo.AwardVO;
import com.anontokyo.lottery.domain.activity.model.vo.DrawConfigPrizeVO;
import com.anontokyo.lottery.domain.activity.model.vo.DrawConfigVO;
import com.anontokyo.lottery.domain.activity.repository.IActivityRepository;
import com.anontokyo.lottery.domain.activity.service.deploy.IActivityDeploy;
import com.alibaba.fastjson.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
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
            // 添加活动配置
            ActivityVO activity = activityConfigRich.getActivity();
            // 活动、策略和抽奖配置奖品来自同一个新建配置，避免活动关联空策略。
            DrawConfigVO drawConfig = activityConfigRich.getDrawConfig();
            // 聚合中的策略号是创建活动关联的唯一来源。
            activity.setDrawConfigId(drawConfig.getDrawConfigId());
            // 新建策略的明细必须属于该策略，初始剩余库存等于配置库存。
            for (DrawConfigPrizeVO detail : drawConfig.getDrawConfigPrizeList()) {
                // 将明细关联到本次创建的策略，避免插入不可达的奖品配置。
                detail.setDrawConfigId(drawConfig.getDrawConfigId());
                // 新建库存没有预扣或结算历史，剩余量从配置总量初始化。
                detail.setAwardSurplusCount(detail.getAwardCount());
            }
            activityRepository.addActivity(activity);

            // 添加奖品信息
            List<AwardVO> awardList = activityConfigRich.getAwardList();
            activityRepository.addAward(awardList);

            // 添加抽奖配置
            activityRepository.addDrawConfig(drawConfig);

            // 添加抽奖配置奖品配置
            List<DrawConfigPrizeVO> drawConfigPrizeList = drawConfig.getDrawConfigPrizeList();
            activityRepository.addDrawConfigPrizeList(drawConfigPrizeList);

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
