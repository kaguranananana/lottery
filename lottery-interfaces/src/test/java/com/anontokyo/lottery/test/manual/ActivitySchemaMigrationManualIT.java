package com.anontokyo.lottery.test.manual;

import org.junit.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.Assert.*;

/** 在专用 SQL 测试实例执行完整种子和交付迁移，不连接业务数据库。 */
public class ActivitySchemaMigrationManualIT {

    @Test
    public void freshSeedsAndLegacyUpgradePreserveActivityAndPrizeData() throws Exception {
        String url = System.getProperty("lottery.it.mysql");
        assertEquals("须使用既有专用 SQL 实例", "jdbc:mysql://127.0.0.1:13319/", url);
        try (Connection connection = DriverManager.getConnection(url, "root", "");
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS lottery");
            statement.execute("USE lottery");
            // 执行全部种子 INSERT，验证删除列后不存在隐含列序和数量不匹配。
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("../doc/sql/lottery.sql"));
            assertEquals(4, number(statement, "SELECT COUNT(*) FROM activity"));
            assertEquals(4, number(statement, "SELECT COUNT(*) FROM activity WHERE state=4"));
            assertEquals(0, stockColumns(statement));
            long drawConfig = number(statement, "SELECT strategy_id FROM activity WHERE activity_id=100001");
            long quota = number(statement, "SELECT take_count FROM activity WHERE activity_id=100001");
            long prizeRemaining = number(statement, "SELECT SUM(award_surplus_count) FROM strategy_detail");
            long prizeTotal = number(statement, "SELECT SUM(award_count) FROM strategy_detail");
            // 还原旧库仅多出两列的形态，使用原样 V4 验证增量升级不丢业务行。
            statement.execute("ALTER TABLE activity ADD stock_count INT DEFAULT 100, ADD stock_surplus_count INT DEFAULT 80");
            assertEquals(2, stockColumns(statement));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("../docs/sql/V4__remove_activity_stock.sql"));
            assertEquals(0, stockColumns(statement));
            assertEquals(4, number(statement, "SELECT COUNT(*) FROM activity"));
            assertEquals(drawConfig, number(statement, "SELECT strategy_id FROM activity WHERE activity_id=100001"));
            assertEquals(quota, number(statement, "SELECT take_count FROM activity WHERE activity_id=100001"));
            assertEquals(prizeRemaining, number(statement, "SELECT SUM(award_surplus_count) FROM strategy_detail"));
            assertEquals(prizeTotal, number(statement, "SELECT SUM(award_count) FROM strategy_detail"));
        }
    }

    private long stockColumns(Statement statement) throws Exception {
        return number(statement, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='lottery'"
                + " AND table_name='activity' AND column_name IN ('stock_count','stock_surplus_count')");
    }

    private long number(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }
}
