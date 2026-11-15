package com.anontokyo.lottery.test.web;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ActiveSpecLinksTest {
    private static final Pattern LINK = Pattern.compile("\\]\\(([^\\s)]+)\\)");

    @Test
    public void activeSpecsLinkToExistingFiles() throws IOException {
        Path specs = Paths.get("../.trellis/spec");
        try (Stream<Path> files = Files.walk(specs)) {
            List<Path> pages = files.filter(path -> path.toString().endsWith(".md")).collect(Collectors.toList());
            assertFalse("现行规范未找到", pages.isEmpty());
            for (Path page : pages) {
                Matcher links = LINK.matcher(new String(Files.readAllBytes(page), StandardCharsets.UTF_8));
                while (links.find()) {
                    // 只核对现行规范的本地文件路径，历史报告和网址不在门禁内。
                    String target = links.group(1).split("#", 2)[0];
                    if (target.isEmpty() || target.contains("://")) continue;
                    assertTrue(page + " 引用了不存在的路径 " + target, Files.exists(page.getParent().resolve(target)));
                }
            }
        }
    }
}
