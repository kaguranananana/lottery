package com.anontokyo.lottery.test.manual;

import com.anontokyo.lottery.application.process.draw.IActivityDrawProcess;
import com.anontokyo.lottery.application.process.draw.req.DrawProcessReq;
import com.anontokyo.lottery.application.process.draw.res.DrawProcessResult;
import com.alibaba.fastjson.JSON;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/23
 */
@RunWith(SpringRunner.class)
@SpringBootTest
public class ActivityProcessManualIT {

    private Logger logger = LoggerFactory.getLogger(ActivityProcessManualIT.class);

    @Resource
    private IActivityDrawProcess activityProcess;

    @Test
    public void test_doDrawProcess() {
        DrawProcessReq req = new DrawProcessReq();
        req.setuId("anontokyo4");
        req.setActivityId(100001L);
        // 重复执行该用例回放或恢复同一操作；要测试新一轮须使用新请求号。
        req.setRequestId("manual-draw-A");
        DrawProcessResult drawProcessResult = activityProcess.doDrawProcess(req);

        logger.info("请求入参：{}", JSON.toJSONString(req));
        logger.info("测试结果：{}", JSON.toJSONString(drawProcessResult));
    }

}
