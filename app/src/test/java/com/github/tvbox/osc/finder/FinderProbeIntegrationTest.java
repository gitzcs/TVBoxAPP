package com.github.tvbox.osc.finder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 端到端集成测试：真实下载一个公开的 TVBox 配置并跑完整三层验证。
 *
 * <p>验证「自动找源」主链路：下载 → 解密 → 解析 → 筛采集站 → 抽样验证。
 * 网络不通时用 {@code Assume} 跳过，不影响其它测试。
 */
public class FinderProbeIntegrationTest {

    /** 一个公开的 TVBox 配置：86 个站，其中 7 个是采集站（type 0/1），79 个是爬虫站（type 3）。 */
    private static final String PUBLIC_CONFIG =
            "https://raw.githubusercontent.com/gaotianliuyun/gao/master/0821.json";

    @Test
    public void probesRealPublicConfig() {
        FinderProbe probe = new FinderProbe(null);

        // 先确认网络通，通不了就跳过这个测试
        FinderResult ping = FinderHttp.get(PUBLIC_CONFIG, null, 20);
        org.junit.Assume.assumeTrue("网络不通，跳过端到端测试", ping.isOk());

        Finding finding = probe.probe("测试配置", PUBLIC_CONFIG,
                Finding.ORIGIN_GITHUB, "integration-test");

        assertNotNull(finding);
        assertEquals(PUBLIC_CONFIG, finding.getUrl());

        // 这个配置有 7 个采集站（type 0/1）和 79 个爬虫站（type 3）。
        // 探针只统计采集站，绝不能把爬虫站算进去、更不能去加载它们。
        assertEquals("采集站数应为 7（type 0/1 共 7 个，type 3 不计）",
                7, finding.getSiteCount());

        // 验证通过与否都要给出可读结论
        if (finding.isUsable()) {
            assertTrue(finding.getVerifiedSites() > 0);
            assertNotNull(finding.getSampleSites());
        } else {
            assertNotNull("不可用时必须给出原因", finding.getMessage());
            assertFalse(finding.getMessage().trim().isEmpty());
        }

        // 抽样验证最多 3 个站（maxSitesPerConfig 默认值）
        assertTrue("验证通过的站数不应超过抽样上限",
                finding.getVerifiedSites() <= 3);

        // 耗时要有上限，不能卡死
        assertTrue("验证耗时 " + finding.getLatencyMs() + "ms 超出预期",
                finding.getLatencyMs() < 120_000);
    }

    @Test
    public void probeRejectsGarbageUrl() {
        FinderProbe probe = new FinderProbe(null);
        Finding finding = probe.probe("坏地址", "https://not-a-real-host-xyz.invalid/config.json",
                Finding.ORIGIN_MANUAL, null);
        assertNotNull(finding);
        assertFalse(finding.isUsable());
        assertNotNull(finding.getMessage());
    }
}
