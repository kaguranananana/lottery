package com.anontokyo.lottery.test.manual;

import com.anontokyo.lottery.domain.support.ids.IdGenerator;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;

/**
 * @description: 支撑领域测试（需本机 Redis/MySQL 环境，手动运行）
 * @author：anontokyo
 */
@RunWith(SpringRunner.class)
@SpringBootTest
public class SupportManualIT {

    private Logger logger = LoggerFactory.getLogger(SupportManualIT.class);

    @Resource
    private IdGenerator idGenerator;

    @Test
    public void test_ids() {
        logger.info("snowflake id: {}", idGenerator.nextId());
        logger.info("snowflake id: {}", idGenerator.nextId());
    }

}
