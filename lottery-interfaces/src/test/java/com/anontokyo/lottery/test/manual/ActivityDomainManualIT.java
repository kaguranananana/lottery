package com.anontokyo.lottery.test.manual;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.activity.model.aggregates.ActivityConfigRich;
import com.anontokyo.lottery.domain.activity.model.req.ActivityConfigReq;
import com.anontokyo.lottery.domain.activity.model.req.PartakeReq;
import com.anontokyo.lottery.domain.activity.model.res.PartakeResult;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityVO;
import com.anontokyo.lottery.domain.activity.model.vo.AwardVO;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityPrizeVO;
import com.anontokyo.lottery.domain.activity.service.deploy.IActivityDeploy;
import com.anontokyo.lottery.domain.activity.service.partake.IActivityPartake;
import com.alibaba.fastjson.JSON;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * @description: 活动领域测试
 * @author：anontokyo
 * @date: 2026/1/12
 */
@RunWith(SpringRunner.class)
@SpringBootTest
public class ActivityDomainManualIT {

    private Logger logger = LoggerFactory.getLogger(ActivityDomainManualIT.class);

    @Resource
    private IActivityDeploy activityDeploy;

    private ActivityConfigRich activityConfigRich;

    @Resource
    private IActivityPartake activityPartake;
    /**
     * TODO：后面编写ID生成策略
     */
    private Long activityId = 120981321L;

    @Before
    public void init() {

        ActivityVO activity = new ActivityVO();
        activity.setActivityId(activityId);
        activity.setActivityName("测试活动");
        activity.setActivityDesc("测试活动描述");
        activity.setBeginDateTime(new Date());
        activity.setEndDateTime(new Date());
        activity.setTakeCount(10);
        activity.setState(Constants.ActivityState.DRAFT.getCode());
        activity.setCreator("xiaofuge");


        ActivityPrizeVO activityPrize_01 = new ActivityPrizeVO();
        activityPrize_01.setAwardId("101");
        activityPrize_01.setAwardCount(10);
        activityPrize_01.setAwardSurplusCount(10);
        activityPrize_01.setAwardRate(new BigDecimal("0.05"));

        ActivityPrizeVO activityPrize_02 = new ActivityPrizeVO();
        activityPrize_02.setAwardId("102");
        activityPrize_02.setAwardCount(20);
        activityPrize_02.setAwardSurplusCount(20);
        activityPrize_02.setAwardRate(new BigDecimal("0.15"));

        ActivityPrizeVO activityPrize_03 = new ActivityPrizeVO();
        activityPrize_03.setAwardId("103");
        activityPrize_03.setAwardCount(50);
        activityPrize_03.setAwardSurplusCount(50);
        activityPrize_03.setAwardRate(new BigDecimal("0.20"));

        ActivityPrizeVO activityPrize_04 = new ActivityPrizeVO();
        activityPrize_04.setAwardId("104");
        activityPrize_04.setAwardCount(100);
        activityPrize_04.setAwardSurplusCount(100);
        activityPrize_04.setAwardRate(new BigDecimal("0.25"));

        ActivityPrizeVO activityPrize_05 = new ActivityPrizeVO();
        activityPrize_05.setAwardId("105");
        activityPrize_05.setAwardCount(500);
        activityPrize_05.setAwardSurplusCount(500);
        activityPrize_05.setAwardRate(new BigDecimal("0.35"));

        List<ActivityPrizeVO> activityPrizeList = new ArrayList<>();
        activityPrizeList.add(activityPrize_01);
        activityPrizeList.add(activityPrize_02);
        activityPrizeList.add(activityPrize_03);
        activityPrizeList.add(activityPrize_04);
        activityPrizeList.add(activityPrize_05);


        AwardVO award_01 = new AwardVO();
        award_01.setAwardId("101");
        award_01.setAwardType(Constants.AwardType.DESC.getCode());
        award_01.setAwardName("电脑");
        award_01.setAwardContent("请联系活动组织者 fustack");

        AwardVO award_02 = new AwardVO();
        award_02.setAwardId("102");
        award_02.setAwardType(Constants.AwardType.DESC.getCode());
        award_02.setAwardName("手机");
        award_02.setAwardContent("请联系活动组织者 fustack");

        AwardVO award_03 = new AwardVO();
        award_03.setAwardId("103");
        award_03.setAwardType(Constants.AwardType.DESC.getCode());
        award_03.setAwardName("平板");
        award_03.setAwardContent("请联系活动组织者 fustack");

        AwardVO award_04 = new AwardVO();
        award_04.setAwardId("104");
        award_04.setAwardType(Constants.AwardType.DESC.getCode());
        award_04.setAwardName("耳机");
        award_04.setAwardContent("请联系活动组织者 fustack");

        AwardVO award_05 = new AwardVO();
        award_05.setAwardId("105");
        award_05.setAwardType(Constants.AwardType.DESC.getCode());
        award_05.setAwardName("数据线");
        award_05.setAwardContent("请联系活动组织者 fustack");

        List<AwardVO> awardList = new ArrayList<>();
        awardList.add(award_01);
        awardList.add(award_02);
        awardList.add(award_03);
        awardList.add(award_04);
        awardList.add(award_05);

        activityConfigRich = new ActivityConfigRich(activity, activityPrizeList, awardList);
    }

    @Test
    public void test_createActivity() {
        activityDeploy.createActivity(new ActivityConfigReq(activityId, activityConfigRich));
    }

    @Test
    public void test_activityPartake() {
        // 手工恢复同一次参与时保留请求号；新一轮应改为新的请求号。
        PartakeReq req = new PartakeReq("Uhdgkw766120d", 100001L, "manual-partake-A");
        PartakeResult res = activityPartake.doPartake(req);
        logger.info("请求参数：{}", JSON.toJSONString(req));
        logger.info("测试结果：{}", JSON.toJSONString(res));
    }

}