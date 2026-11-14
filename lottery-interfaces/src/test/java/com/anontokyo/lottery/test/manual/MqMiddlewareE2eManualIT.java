package com.anontokyo.lottery.test.manual;

import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** HTTP 与 Broker 故障测试共用同一个隔离数据库初始化入口。 */
public final class MqMiddlewareE2eManualIT {
    static final String PROJECT = "lottery-rocketmq-e2e";
    private static final String MYSQL = "jdbc:mysql://127.0.0.1:13309/?useSSL=false&allowPublicKeyRetrieval=true";

    private MqMiddlewareE2eManualIT() {
    }

    public static void createSchema() throws Exception {
        // DROP 前核实 Docker 项目和端口，避免误连业务实例。
        Process inspected = new ProcessBuilder("docker", "inspect", PROJECT + "-mysql-1", "--format",
                "{{index .Config.Labels \"com.docker.compose.project\"}}:{{index .Config.Labels \"com.docker.compose.service\"}}:{{(index (index .NetworkSettings.Ports \"3306/tcp\") 0).HostPort}}")
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        String identity = new String(org.springframework.util.StreamUtils.copyToByteArray(inspected.getInputStream()), StandardCharsets.UTF_8).trim();
        assertTrue("专用 MySQL 身份检查超时", inspected.waitFor(10, TimeUnit.SECONDS));
        assertEquals("专用 MySQL 身份检查失败", 0, inspected.exitValue());
        assertEquals("必须使用专用 E2E MySQL", PROJECT + ":mysql:13309", identity);
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            // 只在已验证的独立 E2E 实例重建本轮单库 fixture。
            statement.execute("DROP DATABASE IF EXISTS lottery");
            ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.FileSystemResource("../docs/sql/lottery-schema.sql"));
            statement.execute("INSERT INTO lottery.award (award_id,award_type) VALUES ('C',3)");
        }
    }

    static void compose(String... arguments) throws Exception {
        java.util.List<String> command = new java.util.ArrayList<>(java.util.Arrays.asList(
                "docker", "compose", "-f", "src/test/resources/mq-e2e/docker-compose.yml"));
        command.addAll(java.util.Arrays.asList(arguments));
        Process process = new ProcessBuilder(command).inheritIO().start();
        assertTrue("测试容器操作超时", process.waitFor(130, TimeUnit.SECONDS));
        assertEquals("测试容器操作失败", 0, process.exitValue());
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(MYSQL, "root", "root");
    }
}
