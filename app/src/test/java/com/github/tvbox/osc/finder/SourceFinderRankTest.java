package com.github.tvbox.osc.finder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 排序与索引解析的纯函数测试。
 */
public class SourceFinderRankTest {

    private static Finding make(String url, boolean usable, boolean playable,
                                int verified, int siteCount) {
        Finding f = Finding.of(url, url, Finding.ORIGIN_GITHUB, null);
        f.setUsable(usable);
        f.setPlayable(playable);
        f.setVerifiedSites(verified);
        f.setSiteCount(siteCount);
        return f;
    }

    @Test
    public void usableComesFirst() {
        List<Finding> list = new ArrayList<>();
        list.add(make("https://bad", false, false, 0, 0));
        list.add(make("https://good", true, false, 1, 1));

        List<Finding> ranked = SourceFinder.rank(list);
        assertEquals("https://good", ranked.get(0).getUrl());
    }

    @Test
    public void playableBeatsNonPlayable() {
        List<Finding> list = new ArrayList<>();
        list.add(make("https://a", true, false, 3, 10));
        list.add(make("https://b", true, true, 1, 1));

        List<Finding> ranked = SourceFinder.rank(list);
        assertEquals("https://b", ranked.get(0).getUrl());
    }

    @Test
    public void moreVerifiedSitesWins() {
        List<Finding> list = new ArrayList<>();
        list.add(make("https://few", true, true, 1, 10));
        list.add(make("https://many", true, true, 5, 2));

        List<Finding> ranked = SourceFinder.rank(list);
        assertEquals("https://many", ranked.get(0).getUrl());
    }

    @Test
    public void handlesNulls() {
        List<Finding> list = new ArrayList<>();
        list.add(null);
        list.add(make("https://x", true, true, 1, 1));
        List<Finding> ranked = SourceFinder.rank(list);
        assertEquals(1, ranked.size());
    }

    @Test
    public void indexParsesInline() {
        String json = "{\"version\":2,\"local\":[{\"name\":\"本地\",\"url\":\"assets://a.json\"}],"
                + "\"sources\":[{\"name\":\"在线\",\"url\":\"https://x/y.json\"}],"
                + "\"github\":{\"enabled\":false,\"maxRepos\":3,\"queries\":[\"q1\"],"
                + "\"guessFilenames\":[\"config.json\"]},"
                + "\"probe\":{\"maxSitesPerConfig\":2,\"budgetMs\":1000}}";

        SourceIndex index = SourceIndex.parse(json);
        assertEquals(2, index.version());
        assertEquals(1, index.localEntries().size());
        assertEquals("assets://a.json", index.localEntries().get(0).getUrl());
        assertEquals(1, index.remoteEntries().size());
        assertEquals(2, index.allEntries().size());
        assertFalse(index.github().isEnabled());
        assertEquals(3, index.github().getMaxRepos());
        assertEquals(2, index.probe().getMaxSitesPerConfig());
        assertEquals(1000L, index.probe().getBudgetMs());
    }

    @Test
    public void indexFallsBackOnGarbage() {
        for (String bad : new String[]{"", "   ", "not json", "[1,2,3]", "null", "\"str\""}) {
            SourceIndex index = SourceIndex.parse(bad);
            assertTrue("坏输入应回落到兜底索引：" + bad, index.localEntries().size() >= 1);
        }
    }

    @Test
    public void githubMirrorsForRawUrl() {
        List<String> mirrors = SourceFinder.mirrorsFor(
                "https://raw.githubusercontent.com/user/repo/main/config.json");
        assertEquals(2, mirrors.size());
        assertTrue(mirrors.get(0).contains("cdn.jsdelivr.net"));
    }

    @Test
    public void noMirrorsForNonGithubUrl() {
        assertTrue(SourceFinder.mirrorsFor("https://example.com/config.json").isEmpty());
        assertTrue(SourceFinder.mirrorsFor(null).isEmpty());
    }
}
