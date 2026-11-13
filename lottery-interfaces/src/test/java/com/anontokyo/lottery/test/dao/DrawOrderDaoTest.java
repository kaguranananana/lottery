package com.anontokyo.lottery.test.dao;

import com.anontokyo.lottery.LotteryApplication;
import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.support.ids.IdGenerator;
import com.anontokyo.lottery.infrastructure.dao.IDrawOrderDao;
import com.anontokyo.lottery.infrastructure.po.DrawOrder;
import com.alibaba.fastjson.JSON;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;
import java.util.Date;

/**
 * @description: 用户策略计算结果表Dao测试
 * @author: 小傅哥，微信：fustack
 * @date: 2021/9/30
 * @github: https://github.com/fuzhengwei
 * @Copyright: 公众号：bugstack虫洞栈 | 博客：https://bugstack.cn - 沉淀、分享、成长，让自己和他人都能有所收获！
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = LotteryApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class DrawOrderDaoTest {

    private Logger logger = LoggerFactory.getLogger(DrawOrderDaoTest.class);

    @Resource
    private IDrawOrderDao drawOrderDao;

    @Resource
    private IdGenerator idGenerator;

    @Test
    public void test_insert() {
        DrawOrder drawOrderRow = new DrawOrder();
        drawOrderRow.setuId("Uhdgkw766120d");
        drawOrderRow.setActivityId(100001L);
        drawOrderRow.setOrderId(idGenerator.nextId());


        drawOrderRow.setAwardFulfillmentStatus(1);
        drawOrderRow.setAwardId("1");
        drawOrderRow.setAwardType(Constants.AwardType.DESC.getCode());
        drawOrderRow.setAwardName("IMac");
        drawOrderRow.setAwardContent("奖品描述");

        drawOrderDao.insert(drawOrderRow);
    }

    @Test
    public void test_select() {
        DrawOrder drawOrderRow = drawOrderDao.queryDrawOrderByUId("Uhdgkw766120d");
        logger.info("测试结果：{}", JSON.toJSONString(drawOrderRow));
    }

}
