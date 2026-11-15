package com.anontokyo.lottery.test.manual;

import org.junit.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

/** 验证生产 application.yml 的默认库存模式为 8 桶，仍可通过环境变量显式回退到 0。 */
public class StockBucketDefaultConfigurationTest {
    @Test public void productionDefaultsToEightBucketsButAllowsExplicitLegacyMode() throws IOException {
        List<PropertySource<?>> yaml = new YamlPropertySourceLoader().load(
                "application", new ClassPathResource("application.yml"));
        MutablePropertySources sources = new MutablePropertySources();
        yaml.forEach(sources::addLast);
        PropertySourcesPropertyResolver resolver = new PropertySourcesPropertyResolver(sources);
        assertEquals("8", resolver.getProperty("lottery.stock.bucket-count"));
        sources.addFirst(new MapPropertySource("env", Collections.singletonMap("LOTTERY_STOCK_BUCKET_COUNT", "0")));
        assertEquals("0", resolver.getProperty("lottery.stock.bucket-count"));
    }
}
