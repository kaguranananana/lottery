package com.anontokyo.lottery.test.web;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertFalse;

public class WebPersistenceBoundaryTest {
    private static final Pattern DAO_OR_PO = Pattern.compile("com\\.anontokyo\\.lottery\\.infrastructure\\.(dao|po)\\.");

    @Test
    public void webUsesApplicationOrDomainContractsInsteadOfPersistenceTypes() throws IOException {
        Path web = Paths.get("src/main/java/com/anontokyo/lottery/interfaces/web");
        try (Stream<Path> files = Files.walk(web)) {
            List<Path> sources = files.filter(path -> path.toString().endsWith(".java")).collect(Collectors.toList());
            assertFalse("Web 源码未找到", sources.isEmpty());
            for (Path source : sources) {
                String code = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
                assertFalse(source + " 直接依赖 DAO 或 PO", DAO_OR_PO.matcher(code).find());
            }
        }
    }
}
