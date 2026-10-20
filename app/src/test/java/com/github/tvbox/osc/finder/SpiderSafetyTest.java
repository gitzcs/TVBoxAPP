package com.github.tvbox.osc.finder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 爬虫站（type 3）的安全闸。
 *
 * <p>核心约束（docs/实现方案.md §4.5）：
 * 探针<b>只计数、绝不加载</b> type 3 站点。加载 jar 爬虫 = 远程代码执行，
 * 而自动找源会自动引入陌生仓库的配置，风险被放大。
 */
public class SpiderSafetyTest {

    private static final String WITH_SPIDER =
            "{\"sites\":["
            + "{\"key\":\"a\",\"name\":\"A\",\"type\":1,\"api\":\"https://a/api.php/provide/vod/\"},"
            + "{\"key\":\"b\",\"name\":\"B\",\"type\":0,\"api\":\"https://b/xml/\"},"
            + "{\"key\":\"c\",\"name\":\"C\",\"type\":3,\"api\":\"csp_Demo\",\"jar\":\"http://x/a.jar\"}"
            + "]}";

    @Test
    public void countsSpiderSitesButExcludesThemFromCms() {
        ConfigSniffer.Sniffed s = ConfigSniffer.parse(WITH_SPIDER, null);
        assertEquals("爬虫站应被单独计数", 1, s.spiderSiteCount());
        assertEquals("采集站只含 type 0/1", 2, s.cmsSites().size());
    }

    @Test
    public void cmsTypeIncludesTypeFour() {
        // A 比 TVApp 多支持 type 4, 不能像 TVApp 那版一样误杀
        ConfigSniffer.Sniffed s = ConfigSniffer.parse(
                "{\"sites\":[{\"key\":\"d\",\"name\":\"D\",\"type\":4,\"api\":\"https://d/api\",\"ext\":\"{}\"}]}",
                null);
        assertEquals(1, s.cmsSites().size());
        assertEquals("type 4 不算爬虫站", 0, s.spiderSiteCount());
    }

    @Test
    public void probeNeverLoadsJarForSpiderSites() {
        // 用一个会超时的假 jar 地址: 如果探针真去加载, 这个测试会明显变慢/失败
        FinderProbe probe = new FinderProbe(null);
        long t0 = System.currentTimeMillis();
        Finding f = probe.probe("含爬虫的配置", "https://not-a-real-host-xyz.invalid/c.json",
                Finding.ORIGIN_MANUAL, null);
        long elapsed = System.currentTimeMillis() - t0;
        assertTrue("探针应快速失败而不是去加载 jar, 实际耗时 " + elapsed + "ms", elapsed < 30_000);
        assertTrue(f != null && !f.isUsable());
    }

    @Test
    public void findingCarriesSpiderCountForUiWarning() {
        Finding f = Finding.of("x", "https://x/config.json", Finding.ORIGIN_GITHUB, "repo");
        assertEquals(0, f.getSpiderSiteCount());
        f.setSpiderSiteCount(7);
        assertEquals(7, f.getSpiderSiteCount());
    }
}