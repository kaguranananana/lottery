package com.anontokyo.lottery.test.domain;

import com.anontokyo.lottery.domain.rule.model.req.DecisionMatterReq;
import com.anontokyo.lottery.domain.rule.model.res.EngineResult;
import com.anontokyo.lottery.domain.rule.service.engine.EngineFilter;
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
 * @description: 规则引擎测试
 * @author: anontokyo
 * @date: 2026/1/23
 */
@RunWith(SpringRunner.class)
@SpringBootTest
public class RuleEngineTest {

    private Logger logger = LoggerFactory.getLogger(RuleEngineTest.class);

    @Resource
    private EngineFilter ruleEngineHandle;

    @Test
    public void test_process() {
        DecisionMatterReq req = new DecisionMatterReq();
        req.setTreeId(2110081902L);
        req.setUserId("test_user_001");

        Map<String, Object> valMap = new HashMap<>();
        valMap.put("gender", "man");
        valMap.put("age", "22");
        req.setValMap(valMap);

        EngineResult result = ruleEngineHandle.process(req);

        logger.info("决策结果 userId：{} treeId：{} nodeId：{} nodeValue：{}",
                result.getUserId(), result.getTreeId(), result.getNodeId(), result.getNodeValue());
    }

    /**
     * 测试用例：男性, 年龄小于25岁
     * 预期结果：活动ID = 100001
     */
    @Test
    public void test_man_age_less_than_25() {
        DecisionMatterReq req = new DecisionMatterReq();
        req.setTreeId(2110081902L);
        req.setUserId("user_001");

        Map<String, Object> valMap = new HashMap<>();
        valMap.put("gender", "man");
        valMap.put("age", "20");
        req.setValMap(valMap);

        EngineResult result = ruleEngineHandle.process(req);

        logger.info("测试结果 - 男性<25岁: nodeValue={}", result.getNodeValue());
        // 预期 nodeValue = 100001
    }

    /**
     * 测试用例：男性, 年龄大于等于25岁
     * 预期结果：活动ID = 100002
     */
    @Test
    public void test_man_age_greater_or_equal_25() {
        DecisionMatterReq req = new DecisionMatterReq();
        req.setTreeId(2110081902L);
        req.setUserId("user_002");

        Map<String, Object> valMap = new HashMap<>();
        valMap.put("gender", "man");
        valMap.put("age", "30");
        req.setValMap(valMap);

        EngineResult result = ruleEngineHandle.process(req);

        logger.info("测试结果 - 男性>=25岁: nodeValue={}", result.getNodeValue());
        // 预期 nodeValue = 100002
    }

    /**
     * 测试用例：女性, 年龄小于25岁
     * 预期结果：活动ID = 100003
     */
    @Test
    public void test_woman_age_less_than_25() {
        DecisionMatterReq req = new DecisionMatterReq();
        req.setTreeId(2110081902L);
        req.setUserId("user_003");

        Map<String, Object> valMap = new HashMap<>();
        valMap.put("gender", "woman");
        valMap.put("age", "18");
        req.setValMap(valMap);

        EngineResult result = ruleEngineHandle.process(req);

        logger.info("测试结果 - 女性<25岁: nodeValue={}", result.getNodeValue());
        // 预期 nodeValue = 100003
    }

    /**
     * 测试用例：女性, 年龄大于等于25岁
     * 预期结果：活动ID = 100004
     */
    @Test
    public void test_woman_age_greater_or_equal_25() {
        DecisionMatterReq req = new DecisionMatterReq();
        req.setTreeId(2110081902L);
        req.setUserId("user_004");

        Map<String, Object> valMap = new HashMap<>();
        valMap.put("gender", "woman");
        valMap.put("age", "28");
        req.setValMap(valMap);

        EngineResult result = ruleEngineHandle.process(req);

        logger.info("测试结果 - 女性>=25岁: nodeValue={}", result.getNodeValue());
        // 预期 nodeValue = 100004
    }

}
