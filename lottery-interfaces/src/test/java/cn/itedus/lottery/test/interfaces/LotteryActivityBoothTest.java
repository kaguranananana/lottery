package cn.itedus.lottery.test.interfaces;

import cn.itedus.lottery.rpc.activity.booth.ILotteryActivityBooth;
import cn.itedus.lottery.rpc.activity.booth.req.DrawReq;
import cn.itedus.lottery.rpc.activity.booth.req.QuantificationDrawReq;
import cn.itedus.lottery.rpc.activity.booth.res.DrawRes;
import com.alibaba.fastjson.JSON;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

/**
 * @description: 抽奖活动展台门面接口测试
 * @author：anontokyo
 * @date: 2026/1/25
 */
@RunWith(SpringRunner.class)
@SpringBootTest
public class LotteryActivityBoothTest {

    private Logger logger = LoggerFactory.getLogger(LotteryActivityBoothTest.class);

    @Resource
    private ILotteryActivityBooth lotteryActivityBooth;

    /**
     * 测试普通抽奖
     * 
     * 使用用户ID和活动ID进行抽奖
     */
    @Test
    public void test_doDraw() {
        DrawReq drawReq = new DrawReq();
        drawReq.setuId("fustack");
        drawReq.setActivityId(100001L);

        DrawRes drawRes = lotteryActivityBooth.doDraw(drawReq);

        logger.info("请求参数：{}", JSON.toJSONString(drawReq));
        logger.info("测试结果：{}", JSON.toJSONString(drawRes));
    }

    /**
     * 测试量化人群抽奖 - 男性 年龄小于25岁
     * 
     * 预期：规则引擎决策活动ID = 100001
     */
    @Test
    public void test_doQuantificationDraw_man_young() {
        QuantificationDrawReq req = new QuantificationDrawReq();
        req.setuId("test_user_001");
        req.setTreeId(2110081902L);

        Map<String, Object> valMap = new HashMap<>();
        valMap.put("gender", "man");
        valMap.put("age", "22");
        req.setValMap(valMap);

        DrawRes drawRes = lotteryActivityBooth.doQuantificationDraw(req);

        logger.info("请求参数：{}", JSON.toJSONString(req));
        logger.info("测试结果：{}", JSON.toJSONString(drawRes));
    }

    /**
     * 测试量化人群抽奖 - 女性 年龄小于25岁
     * 
     * 预期：规则引擎决策活动ID = 100003
     */
    @Test
    public void test_doQuantificationDraw_woman_young() {
        QuantificationDrawReq req = new QuantificationDrawReq();
        req.setuId("test_user_002");
        req.setTreeId(2110081902L);

        Map<String, Object> valMap = new HashMap<>();
        valMap.put("gender", "woman");
        valMap.put("age", "18");
        req.setValMap(valMap);

        DrawRes drawRes = lotteryActivityBooth.doQuantificationDraw(req);

        logger.info("请求参数：{}", JSON.toJSONString(req));
        logger.info("测试结果：{}", JSON.toJSONString(drawRes));
    }

    /**
     * 测试量化人群抽奖 - 男性 年龄大于等于25岁
     * 
     * 预期：规则引擎决策活动ID = 100002
     */
    @Test
    public void test_doQuantificationDraw_man_old() {
        QuantificationDrawReq req = new QuantificationDrawReq();
        req.setuId("test_user_003");
        req.setTreeId(2110081902L);

        Map<String, Object> valMap = new HashMap<>();
        valMap.put("gender", "man");
        valMap.put("age", "30");
        req.setValMap(valMap);

        DrawRes drawRes = lotteryActivityBooth.doQuantificationDraw(req);

        logger.info("请求参数：{}", JSON.toJSONString(req));
        logger.info("测试结果：{}", JSON.toJSONString(drawRes));
    }

}
