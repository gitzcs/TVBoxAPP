package com.github.tvbox.osc.finder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.util.Arrays;

public class LocalSourceMergerTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static final String A = "{\"spider\":\"https://a.com/a.jar\","
            + "\"sites\":["
            + "{\"key\":\"cms\",\"name\":\"影视\",\"type\":1,\"api\":\"https://x.com/api.php/provide/vod/\"},"
            + "{\"key\":\"csp_A\",\"name\":\"爬虫A\",\"type\":3,\"api\":\"csp_A\"}],"
            + "\"parses\":[{\"name\":\"p1\",\"type\":0,\"url\":\"https://p1/?url=\"}],"
            + "\"lives\":[{\"name\":\"live\",\"url\":\"https://a.com/live.txt\"}]}";

    private static final String B = "{\"spider\":\"https://b.com/b.jar\","
            + "\"sites\":["
            + "{\"key\":\"cms\",\"name\":\"影视\",\"type\":1,\"api\":\"https://x.com/api.php/provide/vod/\"},"
            + "{\"key\":\"cms\",\"name\":\"影视\",\"type\":1,\"api\":\"https://y.com/api.php/provide/vod/\"},"
            + "{\"key\":\"csp_A\",\"name\":\"爬虫A\",\"type\":3,\"api\":\"csp_A\"},"
            + "{\"key\":\"js\",\"name\":\"JS\",\"type\":3,\"api\":\"https://b.com/drpy.js\"}],"
            + "\"parses\":[{\"name\":\"p1\",\"type\":0,\"url\":\"https://p1/?url=\"},"
            + "{\"name\":\"p2\",\"type\":0,\"url\":\"https://p2/?url=\"}],"
            + "\"flags\":[\"qq\",\"youku\"]}";

    @Test
    public void mergesSitesWithDedupAndUniqueKeys() {
        LocalSourceMerger.Result r = LocalSourceMerger.merge(Arrays.asList(
                new LocalSourceMerger.Input("源A", "https://a.com/a.json", json(A)),
                new LocalSourceMerger.Input("源B", "https://b.com/b.json", json(B))));

        assertEquals(2, r.sourceCount);
        // A 的 2 个 + B 的 y.com、csp_A(不同 jar)、js；B 的 x.com 重复
        assertEquals(5, r.siteCount);
        assertEquals(1, r.duplicateCount);

        JsonArray sites = r.config.getAsJsonArray("sites");
        assertEquals("cms", sites.get(0).getAsJsonObject().get("key").getAsString());
        JsonObject y = sites.get(2).getAsJsonObject();
        assertEquals("cms_2", y.get("key").getAsString());
        assertEquals("影视（源B）", y.get("name").getAsString());

        assertEquals("https://a.com/a.jar", r.config.get("spider").getAsString());
        // 主 jar 来自 A，A 的爬虫站不需要单独写 jar
        assertFalse(sites.get(1).getAsJsonObject().has("jar"));
        // B 的爬虫站要带上 B 自己的 jar
        JsonObject bSpider = sites.get(3).getAsJsonObject();
        assertEquals("csp_A_2", bSpider.get("key").getAsString());
        assertEquals("https://b.com/b.jar", bSpider.get("jar").getAsString());
        // js 站不走 jar
        assertFalse(sites.get(4).getAsJsonObject().has("jar"));
    }

    @Test
    public void mergesParsesFlagsAndKeepsFirstLives() {
        LocalSourceMerger.Result r = LocalSourceMerger.merge(Arrays.asList(
                new LocalSourceMerger.Input("源A", "https://a.com/a.json", json(A)),
                new LocalSourceMerger.Input("源B", "https://b.com/b.json", json(B))));
        assertEquals(2, r.config.getAsJsonArray("parses").size());
        assertEquals(2, r.config.getAsJsonArray("flags").size());
        assertEquals(1, r.config.getAsJsonArray("lives").size());
        // csp_A、csp_A_2、js 都是 type 3
        assertEquals(3, r.spiderSiteCount);
    }

    @Test
    public void skipsConfigsWithoutSites() {
        LocalSourceMerger.Result r = LocalSourceMerger.merge(Arrays.asList(
                new LocalSourceMerger.Input("空", "https://c.com/c.json", json("{\"urls\":[]}")),
                null));
        assertEquals(0, r.sourceCount);
        assertEquals(0, r.siteCount);
        assertTrue(r.config.getAsJsonArray("sites").size() == 0);
    }

    @Test
    public void resolvesRelativePaths() {
        String raw = "{\"spider\":\"./jar/a.jar;md5;abc\",\"sites\":[{\"ext\":\"../lib/x.json\"}],"
                + "\"dir\":\"./res/\"}";
        String fixed = LocalSourceMerger.resolveRelativePaths(raw, "https://h.com/repo/tv/a.json");
        assertTrue(fixed.contains("\"https://h.com/repo/tv/jar/a.jar;md5;abc\""));
        assertTrue(fixed.contains("\"https://h.com/repo/lib/x.json\""));
        assertTrue(fixed.contains("\"https://h.com/repo/tv/res/\""));
    }

    @Test
    public void localUrlRejectsTraversal() {
        assertNull(LocalSourceStore.fileFor(null, "local://../x.json"));
        assertNull(LocalSourceStore.fileFor(null, "local://a/b.json"));
        assertNull(LocalSourceStore.fileFor(null, "https://a.com/x.json"));
        assertTrue(LocalSourceStore.isLocal(LocalSourceStore.MERGED_URL));
    }
}
