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
    public void assetUrlWinsOverEquivalentRemoteConfig() {
        Finding remote = make("https://example.com/qist.json", true, true, 3, 15);
        Finding asset = make("assets://qist.json", true, true, 3, 10);

        List<Finding> list = new ArrayList<>();
        list.add(remote);
        list.add(asset);

        assertEquals("assets://qist.json", SourceFinder.rank(list).get(0).getUrl());
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

    /**
     * 锁定随包发布的 assets/source_index.json 的 JSON key 契约。
     *
     * <p>这个文件按字段名反序列化到 {@link SourceIndex}。release 混淆会重命名字段
     * （见 proguard-rules.pro 的 keep 规则），一旦字段名与 JSON key 对不上，
     * {@code Github.queries} 会静默变空 -> 自动找源搜不到任何仓库，且
     * {@link SourceIndex#parse(String)} 用 fillDefaults() 兜底、不抛异常，
     * 极难发现。此测试读取<b>真实 asset 文件</b>（而非手写 JSON），
     * 保证 queries 等关键字段确实解析出非空值。
     */
    @Test
    public void bundledAssetIndexKeepsGithubQueries() throws Exception {
        String json = readBundledSourceIndex();
        SourceIndex index = SourceIndex.parse(json);

        // 内置本地源已移除：索引的 local 必须为空，找源只走在线发现
        assertTrue("asset 索引不应再含本地内置源", index.localEntries().isEmpty());
        assertTrue("GitLab 搜索应启用", index.gitlab().isEnabled());
        assertFalse("GitLab 搜索应含种子仓库", index.gitlab().getRepos().isEmpty());
        assertTrue("Gitee 搜索应启用", index.gitee().isEnabled());
        assertFalse("Gitee 搜索应含查询词", index.gitee().getQueries().isEmpty());
        assertFalse("GitHub 搜索索引站不能为空", index.indexSites().isEmpty());
        SourceIndex.IndexSite site = index.indexSites().get(0);
        assertTrue("索引站必须使用 HTTPS", site.getBaseUrl().startsWith("https://"));
        assertFalse("索引站搜索关键词不能为空", site.getQueries().isEmpty());
        assertEquals("bdnb.cn", site.getName());

        // 核心：queries 不能为空 —— 这正是 release 混淆曾静默清空的字段
        assertTrue("github.enabled 应为 true", index.github().isEnabled());
        assertFalse("github.queries 解析后不能为空（JSON key 契约）",
                index.github().getQueries().isEmpty());
        assertFalse("github.guessFilenames 解析后不能为空",
                index.github().getGuessFilenames().isEmpty());

        // 探针参数应从 asset 读到真实值（而非默认兜底）
        assertTrue("probe.maxSitesPerConfig 应 > 0", index.probe().getMaxSitesPerConfig() > 0);
        assertTrue("probe.budgetMs 应 > 0", index.probe().getBudgetMs() > 0);
    }

    /**
     * 读取打包进 APK 的 source_index.json。
     *
     * <p>单测 user.dir 通常是 app 模块目录；兼顾从工程根目录运行的情况。
     * 读不到就让测试失败，避免静默跳过。
     */
    private static String readBundledSourceIndex() throws Exception {
        String[] candidates = {
                "src/main/assets/source_index.json",
                "app/src/main/assets/source_index.json",
        };
        for (String c : candidates) {
            java.io.File f = new java.io.File(c);
            if (f.isFile()) {
                byte[] bytes = new byte[(int) f.length()];
                java.io.InputStream in = new java.io.FileInputStream(f);
                try {
                    int off = 0, n;
                    while (off < bytes.length && (n = in.read(bytes, off, bytes.length - off)) > 0) {
                        off += n;
                    }
                } finally {
                    in.close();
                }
                return new String(bytes, java.nio.charset.Charset.forName("UTF-8"));
            }
        }
        throw new java.io.FileNotFoundException(
                "找不到 assets/source_index.json（尝试过 src/main/assets 与 app/src/main/assets）");
    }

    @Test
    public void indexFallsBackOnGarbage() {
        for (String bad : new String[]{"", "   ", "not json", "[1,2,3]", "null", "\"str\""}) {
            SourceIndex index = SourceIndex.parse(bad);
            assertTrue("坏输入应回落到兜底索引（本地为空，只走在线发现）：" + bad,
                    index.localEntries().isEmpty());
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
