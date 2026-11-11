package com.anontokyo.lottery.test.dao;

import com.anontokyo.lottery.LotteryApplication;
import com.anontokyo.lottery.infrastructure.dao.IDrawParticipationDao;
import com.anontokyo.lottery.infrastructure.po.DrawParticipation;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;
import java.util.Date;

/**
 * @description: 测试用户领取活动表
 * @author: 小傅哥，微信：fustack
 * @date: 2021/9/22
 * @github: https://github.com/fuzhengwei
 * @Copyright: 公众号：bugstack虫洞栈 | 博客：https://bugstack.cn - 沉淀、分享、成长，让自己和他人都能有所收获！
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = LotteryApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class DrawParticipationDaoTest {

    private Logger logger = LoggerFactory.getLogger(DrawParticipationDaoTest.class);

    @Resource
    private IDrawParticipationDao drawParticipationDao;

    @Test
    public void test_insert() {
        logger.info("开始测试用户领取活动数据插入");
        DrawParticipation drawParticipation = new DrawParticipation();
        drawParticipation.setuId("Uhdgkw766120d"); // 1库：Ukdli109op89oi 2库：Ukdli109op811d
        drawParticipation.setTakeId(121019889410L);
        drawParticipation.setActivityId(100001L);

        drawParticipation.setParticipatedAt(new Date());
        drawParticipationDao.insert(drawParticipation);
        logger.info("用户领取活动数据插入完成");
    }

}
