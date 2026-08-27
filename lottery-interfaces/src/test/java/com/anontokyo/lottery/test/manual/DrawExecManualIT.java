package com.anontokyo.lottery.test.manual;

import com.anontokyo.lottery.domain.strategy.model.req.DrawReq;
import com.anontokyo.lottery.domain.strategy.model.res.DrawResult;
import com.anontokyo.lottery.domain.strategy.service.draw.IDrawExec;
import com.alibaba.fastjson.JSON;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;

/**
 * 手工集成测试：需要本机 MySQL/Redis 环境。验证策略快照加载与 Redis 库存扣减。
 */
@RunWith(SpringRunner.class)
@SpringBootTest
public class DrawExecManualIT {

    private Logger logger = LoggerFactory.getLogger(DrawExecManualIT.class);

    @Resource
    private IDrawExec drawExec;

    @Test
    public void test_doDrawExec() {
        DrawReq req = new DrawReq("anontokyo", 10001L);
        DrawResult drawResult = drawExec.doDrawExec(req);
        logger.info("req:{}", JSON.toJSONString(req));
        logger.info("result:{}", JSON.toJSONString(drawResult));

        for (int i = 0; i < 5; i++) {
            drawExec.doDrawExec(req);
        }
    }
}
