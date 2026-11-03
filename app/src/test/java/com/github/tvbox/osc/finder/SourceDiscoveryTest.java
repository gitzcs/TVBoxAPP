package com.github.tvbox.osc.finder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * GitHub 发现这条链路上的纯函数测试：种子仓库解析、名额分配、
 * 相对地址解析、候选排除、路径/链接过滤。
 *
 * <p>这些函数都不发网络请求，所以能在 JVM 单测里直接跑。
 */
public class SourceDiscoveryTest {

    private static List<String> list(String... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    // ---------------- parseSeed ----------------

    @Test
    public void parsesSeedWithBranch() {
        RepoScanner.Repo repo = RepoScanner.parseSeed("qist/tvbox@master");
        assertNotNull(repo);
        assertEquals("qist/tvbox", repo.fullName());
        assertEquals("master", repo.branch());
    }

    @Test
    public void parsesSeedWithoutBranch() {
        RepoScanner.Repo repo = RepoScanner.parseSeed("qist/tvbox");
        assertNotNull(repo);
        assertEquals("qist/tvbox", repo.fullName());
        assertNull("没写分支时应为 null，交给 detectBranch 去探", repo.branch());
    }

    @Test
    public void parsesSeedFromWebUrlForms() {
        for (String spec : new String[]{
                "https://github.com/qist/tvbox",
                "https://github.com/qist/tvbox/",
                "http://github.com/qist/tvbox.git",
                "git@github.com:qist/tvbox",
                "  qist/tvbox@master  "}) {
            RepoScanner.Repo repo = RepoScanner.parseSeed(spec);
            assertNotNull("应能解析：" + spec, repo);
            assertEquals("应能解析：" + spec, "qist/tvbox", repo.fullName());
        }
    }

    @Test
    public void rejectsMalformedSeeds() {
        for (String spec : new String[]{
                null, "", "   ", "qist", "qist/", "/tvbox", "a/b/c",
                "https://gitlab.com/qist/tvbox"}) {
            assertNull("应判为不合法：" + spec, RepoScanner.parseSeed(spec));
        }
    }

    @Test
    public void toleratesTrailingAtSign() {
        // 尾随 @ 当作「没写分支」，交给 detectBranch 去探，比整条丢弃更宽容
        RepoScanner.Repo repo = RepoScanner.parseSeed("qist/tvbox@");
        assertNotNull(repo);
        assertEquals("qist/tvbox", repo.fullName());
        assertNull(repo.branch());
    }

    // ---------------- mergeCapped ----------------

    @Test
    public void keepsGuessedFloorAndFillsWithDiscovered() {
        // 猜名清单比名额长时，不能让猜名把发现结果全挤掉
        List<String> guessed = list("g1", "g2", "g3", "g4", "g5", "g6", "g7", "g8");
        List<String> discovered = list("d1", "d2", "d3", "d4");
        List<String> out = RepoScanner.mergeCapped(guessed, discovered, 8);
        assertEquals(8, out.size());
        assertTrue("发现结果必须留得下", out.containsAll(list("d1", "d2", "d3", "d4")));
        assertTrue("猜名也要有保底名额", out.contains("g1"));
    }

    @Test
    public void backfillsGuessedWhenFewDiscovered() {
        // 文件树被限流 + README 没链到东西时，名额应回填给猜名而不是浪费掉
        List<String> guessed = list("g1", "g2", "g3", "g4", "g5", "g6");
        List<String> discovered = list("d1");
        List<String> out = RepoScanner.mergeCapped(guessed, discovered, 8);
        assertEquals(7, out.size());
        assertTrue(out.containsAll(list("d1", "g1", "g2", "g3", "g4")));
    }

    @Test
    public void mergeCappedDedupesAndRespectsCap() {
        List<String> out = RepoScanner.mergeCapped(
                list("a", "b", "a"), list("b", "c", "c", "d", "e"), 4);
        assertEquals(4, out.size());
        assertEquals("a", out.get(0));
        assertEquals("去重后不应有重复项", 4, new java.util.HashSet<>(out).size());
    }

    @Test
    public void mergeCappedHandlesEmptyAndBadCap() {
        assertTrue(RepoScanner.mergeCapped(list(), list(), 8).isEmpty());
        assertTrue(RepoScanner.mergeCapped(list("a"), list("b"), 0).isEmpty());
        assertEquals(list("a"), RepoScanner.mergeCapped(list("a"), list("b"), 1));
        assertEquals(1, RepoScanner.mergeCapped(
                list("g1", "g2", "g3"), list("d1", "d2"), 1).size());
    }

    // ---------------- resolveAgainst ----------------

    @Test
    public void resolvesRelativeMultiRepoLine() {
        // qist/tvbox 的 0707.json 用的就是 ./99188.json 这种写法
        String parent = "https://raw.githubusercontent.com/qist/tvbox/master/0707.json";
        assertEquals("https://raw.githubusercontent.com/qist/tvbox/master/99188.json",
                FinderProbe.resolveAgainst("./99188.json", parent));
        assertEquals("https://raw.githubusercontent.com/qist/tvbox/master/99188.json",
                FinderProbe.resolveAgainst("99188.json", parent));
    }

    @Test
    public void resolvesParentDirAndAbsolutePath() {
        String parent = "https://example.com/a/b/cfg.json";
        assertEquals("https://example.com/a/b/sub/x.json",
                FinderProbe.resolveAgainst("sub/x.json", parent));
        assertEquals("https://example.com/a/x.json",
                FinderProbe.resolveAgainst("../x.json", parent));
        assertEquals("https://example.com/root.json",
                FinderProbe.resolveAgainst("/root.json", parent));
        assertEquals("https://example.com/a/b/cfg.json",
                FinderProbe.resolveAgainst("./cfg.json", parent));
    }

    @Test
    public void keepsAbsoluteAndSpecialSchemes() {
        String parent = "https://example.com/a/b.json";
        assertEquals("https://other.com/x.json",
                FinderProbe.resolveAgainst("https://other.com/x.json", parent));
        // TVBox 特有的 scheme 不能被当成相对路径拼进去
        assertEquals("github://token@github.com/u/r/x.json",
                FinderProbe.resolveAgainst("github://token@github.com/u/r/x.json", parent));
        assertEquals("assets://default_config.json",
                FinderProbe.resolveAgainst("assets://default_config.json", parent));
    }

    @Test
    public void resolveAgainstToleratesBadParent() {
        assertEquals("x.json", FinderProbe.resolveAgainst("x.json", null));
        assertEquals("x.json", FinderProbe.resolveAgainst("x.json", "not-a-url"));
        assertEquals("https://example.com/x.json",
                FinderProbe.resolveAgainst("x.json", "https://example.com"));
        assertNull(FinderProbe.resolveAgainst(null, "https://example.com/a.json"));
        assertEquals("", FinderProbe.resolveAgainst("   ", "https://example.com/a.json"));
    }

    @Test
    public void doesNotEscapeAboveHostRoot() {
        String parent = "https://example.com/cfg.json";
        String out = FinderProbe.resolveAgainst("../../x.json", parent);
        assertEquals("https://example.com/x.json", out);
        assertTrue("不应把 host 段也消掉", out.startsWith("https://example.com/"));
    }

    // ---------------- isExcluded ----------------

    private static SourceIndex.Github githubWithExcludes(String... patterns) {
        StringBuilder sb = new StringBuilder("{ \"enabled\": true, \"excludePatterns\": [");
        for (int i = 0; i < patterns.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(patterns[i]).append('"');
        }
        sb.append("] }");
        SourceIndex index = SourceIndex.parse("{\"github\":" + sb + "}");
        return index.github();
    }

    @Test
    public void excludesAdultConfigsByDefault() {
        SourceIndex.Github gh = SourceIndex.parse("{}").github();
        assertTrue("默认应排除 99188",
                SourceFinder.isExcluded("https://x.com/99188.json", gh));
        assertTrue("默认应排除 0707 多仓壳子",
                SourceFinder.isExcluded("https://x.com/0707.json", gh));
        assertFalse("正常配置不应被排除",
                SourceFinder.isExcluded("https://x.com/jsm.json", gh));
    }

    @Test
    public void excludeIsCaseInsensitiveAndHonoursEmptyList() {
        assertTrue(SourceFinder.isExcluded("https://x.com/ADULT/a.json",
                SourceIndex.parse("{}").github()));
        SourceIndex.Github none = githubWithExcludes();
        assertFalse("索引里显式留空表示不排除",
                SourceFinder.isExcluded("https://x.com/99188.json", none));
    }

    @Test
    public void emptyUrlIsExcluded() {
        SourceIndex.Github gh = SourceIndex.parse("{}").github();
        assertTrue(SourceFinder.isExcluded(null, gh));
        assertTrue(SourceFinder.isExcluded("   ", gh));
        assertFalse("gh 为 null 时不该误杀", SourceFinder.isExcluded("https://x/a.json", null));
    }

    // ---------------- isConfigCandidate / extractConfigUrls ----------------

    @Test
    public void isConfigCandidateAcceptsRootLevelJson() {
        for (String p : new String[]{"config.json", "0821.json", "jsm.json",
                "json/config.json", "a/b/c.json"}) {
            assertTrue("应接受：" + p, RepoScanner.isConfigCandidate(p));
        }
    }

    @Test
    public void isConfigCandidateRejectsNoise() {
        for (String p : new String[]{null, "", "  ", "README.md", "config.txt",
                "package.json", "tsconfig.json", "manifest.json", "composer.json",
                "src/main/config.json", "test/a.json", "docs/a.json",
                "node_modules/x/config.json", "jar/x.json", "assets/a.json",
                ".hidden/a.json", "a/b/c/d/e/f.json"}) {
            assertFalse("应拒绝：" + p, RepoScanner.isConfigCandidate(p));
        }
    }

    @Test
    public void extractConfigUrlsSkipsMarkdownNoiseAndChinesePunctuation() {
        String md = "接口：https://raw.githubusercontent.com/qist/tvbox/master/jsm.json，"
                + "备用 (https://example.com/a.json)\n"
                + "![badge](https://shields.io/badge.json)\n"
                + "群 https://t.me/some.json\n"
                + "图片 https://example.com/logo.png\n"
                + "教程 <https://omii.top/x.json>\n"
                + "重复 https://raw.githubusercontent.com/qist/tvbox/master/jsm.json\n";
        List<String> out = RepoScanner.extractConfigUrls(md);
        assertTrue("应抽到 jsm.json", out.contains(
                "https://raw.githubusercontent.com/qist/tvbox/master/jsm.json"));
        assertTrue("应剥掉括号", out.contains("https://example.com/a.json"));
        assertTrue("应剥掉尖括号", out.contains("https://omii.top/x.json"));
        assertEquals("重复的应去重", 1, countOf(out,
                "https://raw.githubusercontent.com/qist/tvbox/master/jsm.json"));
        for (String bad : out) {
            assertFalse("不该留下图片：" + bad, bad.contains(".png"));
            assertFalse("不该留下徽章：" + bad, bad.contains("shields.io"));
            assertFalse("不该留下 telegram：" + bad, bad.contains("t.me"));
            assertFalse("中文逗号不该被吃进 URL：" + bad, bad.contains("，"));
        }
    }

    @Test
    public void extractConfigUrlsHandlesEmpty() {
        assertTrue(RepoScanner.extractConfigUrls(null).isEmpty());
        assertTrue(RepoScanner.extractConfigUrls("").isEmpty());
        assertTrue(RepoScanner.extractConfigUrls("没有任何链接的说明文字").isEmpty());
    }

    private static int countOf(List<String> list, String target) {
        int n = 0;
        for (String s : list) {
            if (target.equals(s)) {
                n++;
            }
        }
        return n;
    }

    // ---------------- 索引里的新字段 ----------------

    @Test
    public void indexParsesSeedReposAndExcludes() {
        SourceIndex index = SourceIndex.parse("{"
                + "\"github\":{"
                + "\"repos\":[\"qist/tvbox@master\",\"gaotianliuyun/gao\"],"
                + "\"excludePatterns\":[\"foo\"]"
                + "}}");
        assertEquals(2, index.github().getRepos().size());
        assertEquals("qist/tvbox@master", index.github().getRepos().get(0));
        assertEquals(1, index.github().getExcludePatterns().size());
        assertEquals("foo", index.github().getExcludePatterns().get(0));
    }

    @Test
    public void bundledIndexKeepsSeedsAndDefaultExcludes() throws Exception {
        SourceIndex index = SourceIndex.parse(readBundledSourceIndex());
        assertFalse("github.repos 应非空（JSON key 契约）", index.github().getRepos().isEmpty());
        assertFalse("默认排除规则不应为空", index.github().getExcludePatterns().isEmpty());
        // 种子必须能被 parseSeed 解析，否则等于白写
        for (String spec : index.github().getRepos()) {
            assertNotNull("种子仓库写法不合法：" + spec, RepoScanner.parseSeed(spec));
        }
        assertTrue("maxRepos 必须容得下全部种子",
                index.github().getMaxRepos() >= index.github().getRepos().size());
    }

    @Test
    public void notFoundFlagIsNotPersisted() {
        // 404 标记只用于当轮决定是否试镜像，不能进缓存
        Finding f = Finding.of("n", "https://x/a.json", Finding.ORIGIN_GITHUB, null);
        f.setNotFound(true);
        assertTrue(f.isNotFound());
        String json = new com.google.gson.Gson().toJson(f);
        assertFalse("notFound 不应被序列化：" + json, json.contains("notFound"));
    }

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
        throw new java.io.FileNotFoundException("找不到 assets/source_index.json");
    }
}
