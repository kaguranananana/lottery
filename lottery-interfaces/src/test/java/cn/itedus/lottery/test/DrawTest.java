package cn.itedus.lottery.test;

import cn.itedus.lottery.domain.strategy.model.req.DrawReq;
import cn.itedus.lottery.domain.strategy.model.res.DrawResult;
import cn.itedus.lottery.domain.strategy.service.draw.IDrawExec;
import com.alibaba.fastjson.JSON;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;

@RunWith(SpringRunner.class)
@SpringBootTest
public class DrawTest {

    private Logger logger = LoggerFactory.getLogger(DrawTest.class);

    @Resource
    private IDrawExec drawExec;

    @Test
    public void test_doDrawExec() {
        // 1. 构建抽奖请求 (使用你 SQL 中已有的策略 ID: 10001)
        DrawReq req = new DrawReq();
        req.setuId("anontokyo"); // 模拟用户ID
        req.setStrategyId(10001L);

        // 2. 执行抽奖
        DrawResult drawResult = drawExec.doDrawExec(req);

        // 3. 打印结果
        // 如果中奖了, 你可以去数据库查看 strategy_detail 表对应的 awardSurplusCount 是否 -1
        logger.info("请求参数：{}", JSON.toJSONString(req));
        logger.info("测试结果：{}", JSON.toJSONString(drawResult));

        // 4. 可选：如果是单项概率模式，连续抽奖测试并发扣减

        for (int i = 0; i < 5; i++) {
            drawExec.doDrawExec(req);
        }

    }
}