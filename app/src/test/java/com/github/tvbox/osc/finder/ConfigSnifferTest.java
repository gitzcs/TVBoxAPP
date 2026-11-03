package com.github.tvbox.osc.finder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.github.tvbox.osc.bean.SourceBean;

import org.junit.Test;

import java.util.List;

/**
 * {@link ConfigSniffer} 的只读解析测试。
 *
 * <p>重点验证：type 4 被正确识别为采集站（TVApp 那版只认 0/1，会误杀），
 * 以及多仓线路的提取。
 */
public class ConfigSnifferTest {

    private static final String SINGLE = "{"
            + "\"sites\":["
            + "{\"key\":\"a\",\"name\":\"A\",\"type\":1,\"api\":\"https://a/api.php/provide/vod/\"},"
            + "{\"key\":\"b\",\"name\":\"B\",\"type\":0,\"api\":\"https://b/xml/\"},"
            + "{\"key\":\"c\",\"name\":\"C\",\"type\":3,\"api\":\"csp_Demo\",\"jar\":\"http://x/a.jar\"},"
            + "{\"key\":\"d\",\"name\":\"D\",\"type\":4,\"api\":\"https://d/api\",\"ext\":\"{}\"}"
            + "]}";

    @Test
    public void parsesSingleRepoSites() {
        ConfigSniffer.Sniffed s = ConfigSniffer.parse(SINGLE, null);
        assertNotNull(s);
        assertFalse(s.isMultiRepo());
        assertEquals(4, s.sites.size());
        assertEquals("a", s.sites.get(0).getKey());
        assertEquals("https://a/api.php/provide/vod/", s.sites.get(0).getApi());
    }

    @Test
    public void treatsTypeFourAsCmsSite() {
        ConfigSniffer.Sniffed s = ConfigSniffer.parse(SINGLE, null);
        assertNotNull(s);
        List<SourceBean> cms = s.cmsSites();
        // type 0 / 1 / 4 都算采集站；type 3 不算
        assertEquals(3, cms.size());
        assertEquals(1, s.spiderSiteCount());
    }

    @Test
    public void skipsIncompleteSiteEntries() {
        String json = "{\"sites\":["
                + "{\"key\":\"ok\",\"type\":1,\"api\":\"https://ok/\"},"
                + "{\"key\":\"noapi\",\"type\":1},"
                + "{\"type\":1,\"api\":\"https://nokey/\"},"
                + "{\"key\":\"notype\",\"api\":\"https://notype/\"}"
                + "]}";
        ConfigSniffer.Sniffed s = ConfigSniffer.parse(json, null);
        assertNotNull(s);
        assertEquals(1, s.sites.size());
        assertEquals("ok", s.sites.get(0).getKey());
    }

    @Test
    public void parsesMultiRepoLines() {
        String json = "{\"urls\":["
                + "{\"name\":\"线路一\",\"url\":\"https://one/config.json\"},"
                + "{\"name\":\"线路二\",\"api\":\"https://two/config.json\"},"
                + "\"https://three/config.json\""
                + "]}";
        ConfigSniffer.Sniffed s = ConfigSniffer.parse(json, null);
        assertNotNull(s);
        assertTrue(s.isMultiRepo());
        assertEquals(3, s.lines.size());
        assertEquals("线路一", s.lines.get(0).name);
        assertEquals("https://two/config.json", s.lines.get(1).url);
        assertEquals("https://three/config.json", s.lines.get(2).url);
    }

    @Test
    public void mergesStoreHouseAndDeduplicates() {
        String json = "{\"urls\":[{\"name\":\"A\",\"url\":\"https://same/config.json\"}],"
                + "\"storeHouse\":[{\"name\":\"B\",\"url\":\"https://same/config.json\"},"
                + "{\"name\":\"C\",\"url\":\"https://other/config.json\"}]}";
        ConfigSniffer.Sniffed s = ConfigSniffer.parse(json, null);
        assertNotNull(s);
        assertEquals(2, s.lines.size());
    }

    @Test
    public void rejectsGarbage() {
        assertNull(ConfigSniffer.parse(null, null));
        assertNull(ConfigSniffer.parse("", null));
        assertNull(ConfigSniffer.parse("   ", null));
        assertNull(ConfigSniffer.parse("not json at all", null));
    }

    @Test
    public void stripsMarkdownFence() {
        String fenced = "```json\n" + SINGLE + "\n```";
        ConfigSniffer.Sniffed s = ConfigSniffer.parse(fenced, null);
        assertNotNull(s);
        assertEquals(4, s.sites.size());
    }
}
