package com.github.tvbox.osc.finder;

import android.content.Context;

import com.google.gson.Gson;
import com.github.tvbox.osc.util.LOG;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 内置源索引（{@code assets/source_index.json}）。
 *
 * <p>这是「自动找源」的配置基石：它声明搜索关键词、索引站、种子仓库和验证参数。
 * 具体配置不在安装包内写死，发现结果仍要逐条联网验证。
 *
 * <p>索引文件而不是代码里写死，是为了以后不改代码就能换地址。
 */
public final class SourceIndex {

    public static final String ASSET_NAME = "source_index.json";

    /** 索引里的一个地址条目。 */
    public static final class Entry {
        private String name;
        private String url;

        public Entry() {
        }

        public Entry(String name, String url) {
            this.name = name;
            this.url = url;
        }

        public String getName() {
            return name == null || name.trim().isEmpty() ? url : name;
        }

        public String getUrl() {
            return url;
        }

        public boolean isValid() {
            return url != null && !url.trim().isEmpty();
        }
    }

    private int version = 1;
    private String updated;
    private String note;

    /** 随包内置的首选候选，启动时优先验证。 */
    private List<Entry> local;

    /** 公开配置地址，需要联网验证。 */
    private List<Entry> sources;

    private Github github;
    private Gitlab gitlab;
    private Gitee gitee;
    private List<IndexSite> indexSites;
    private Probe probe;

    /** GitLab / Gitee 等代码托管平台的通用发现参数。 */
    public static class CodeHost {
        private boolean enabled = true;
        private List<String> repos;
        private List<String> queries;
        private int maxRepos = 3;
        private int maxFilesPerRepo = 5;
        private List<String> guessFilenames;
        private List<String> readmeCandidates;

        public boolean isEnabled() {
            return enabled;
        }

        public List<String> getRepos() {
            return repos == null ? Collections.<String>emptyList() : repos;
        }

        public List<String> getQueries() {
            return queries == null ? Collections.<String>emptyList() : queries;
        }

        public int getMaxRepos() {
            return maxRepos <= 0 ? 3 : maxRepos;
        }

        public int getMaxFilesPerRepo() {
            return maxFilesPerRepo <= 0 ? 5 : maxFilesPerRepo;
        }

        public List<String> getGuessFilenames() {
            return guessFilenames == null
                    ? Collections.<String>emptyList() : guessFilenames;
        }

        public List<String> getReadmeCandidates() {
            return readmeCandidates == null
                    ? Collections.<String>emptyList() : readmeCandidates;
        }
    }

    public static final class Gitlab extends CodeHost {
    }

    public static final class Gitee extends CodeHost {
    }

    /**
     * 第三方 GitHub 搜索索引站。
     *
     * <p>只负责返回仓库元数据，配置仍由 {@link RepoScanner} 扫描并经
     * {@link FinderProbe} 实际验证。GitHub Search API 在不稳定网络下容易超时或被限流，
     * 因此索引站是搜索通道的优先降级项，而不是配置内容的可信来源。
     */
    public static final class IndexSite {
        private boolean enabled = true;
        private String name;
        private String baseUrl;
        private List<String> queries;
        private int maxRepos = 6;
        private int perPage = 12;
        private int timeoutSeconds = 10;
        private String sort = "updated";
        private String order = "desc";

        public boolean isEnabled() {
            return enabled;
        }

        public String getName() {
            return name == null || name.trim().isEmpty() ? getBaseUrl() : name.trim();
        }

        public String getBaseUrl() {
            if (baseUrl == null) {
                return "";
            }
            String value = baseUrl.trim();
            while (value.endsWith("/")) {
                value = value.substring(0, value.length() - 1);
            }
            return value;
        }

        public List<String> getQueries() {
            return queries == null ? Collections.<String>emptyList() : queries;
        }

        public int getMaxRepos() {
            return maxRepos <= 0 ? 6 : maxRepos;
        }

        public int getPerPage() {
            return perPage <= 0 ? 12 : Math.min(perPage, 30);
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds <= 0 ? 10 : Math.min(timeoutSeconds, 30);
        }

        public String getSort() {
            return sort == null || sort.trim().isEmpty() ? "updated" : sort.trim();
        }

        public String getOrder() {
            return order == null || order.trim().isEmpty() ? "desc" : order.trim();
        }
    }

    /** GitHub 发现参数。 */
    public static final class Github {
        /**
         * 索引未显式配置时使用的默认排除规则。
         *
         * <p>{@code 9918}/{@code 99188} 是圈内成人向聚合配置的通行文件名，
         * {@code 0707} 是多仓壳子且线路指向成人内容。这些不该由自动发现
         * 主动推给用户——用户真要，可以在「视频源自动获取」页手动粘贴地址验证。
         */
        private static final List<String> DEFAULT_EXCLUDES = Collections.unmodifiableList(
                Arrays.asList("9918", "99188", "0707", "adult"));

        private boolean enabled = true;
        private List<String> queries;
        /**
         * 种子仓库，写法 {@code owner/repo} 或 {@code owner/repo@branch}。
         *
         * <p>Search API 是整个 GitHub 通道里最脆弱的一环（未认证配额只有
         * 10 次/分钟，实测经常 403）。种子仓库不依赖搜索，直接扫描，
         * 所以即使搜索被限流，这些仓库里的配置依然能被发现。
         *
         * <p>种子优先扫描，搜索结果填满剩余名额（总数仍受 {@code maxRepos} 约束）。
         */
        private List<String> repos;
        private int maxRepos = 6;
        private int maxFilesPerRepo = 6;
        private List<String> guessFilenames;
        private List<String> readmeCandidates;
        /**
         * 候选地址排除规则（小写子串匹配，命中即丢弃）。
         *
         * <p>自动发现是「仓库里有什么就扫什么」，会把作者放在同一仓库里、
         * 但并非面向全体用户的配置一并捞出来（例如成人向内容）。默认排除掉，
         * 需要的人可以在索引里清空这个列表自行放开。
         *
         * <p>只作用于自动发现；用户手动粘贴地址验证时不受此限制。
         */
        private List<String> excludePatterns;

        public boolean isEnabled() {
            return enabled;
        }

        public List<String> getQueries() {
            return queries == null ? Collections.<String>emptyList() : queries;
        }

        public List<String> getRepos() {
            return repos == null ? Collections.<String>emptyList() : repos;
        }

        public int getMaxRepos() {
            return maxRepos <= 0 ? 6 : maxRepos;
        }

        public int getMaxFilesPerRepo() {
            return maxFilesPerRepo <= 0 ? 6 : maxFilesPerRepo;
        }

        public List<String> getGuessFilenames() {
            return guessFilenames == null
                    ? Collections.<String>emptyList() : guessFilenames;
        }

        public List<String> getReadmeCandidates() {
            return readmeCandidates == null
                    ? Collections.<String>emptyList() : readmeCandidates;
        }

        public List<String> getExcludePatterns() {
            // 索引里没写就用默认值；显式写 "excludePatterns": [] 则表示不排除。
            return excludePatterns == null ? DEFAULT_EXCLUDES : excludePatterns;
        }
    }

    /** 验证参数。 */
    public static final class Probe {
        private int maxSitesPerConfig = 3;
        private long budgetMs = 25000L;

        public int getMaxSitesPerConfig() {
            return maxSitesPerConfig <= 0 ? 3 : maxSitesPerConfig;
        }

        public long getBudgetMs() {
            return budgetMs <= 0 ? 25000L : budgetMs;
        }
    }

    // ---------------- 读取 ----------------

    public static SourceIndex fromAssets(Context context) {
        try {
            InputStream in = context.getAssets().open(ASSET_NAME);
            SourceIndex index = parse(read(in));
            LOG.i("源索引已加载: 本地 " + index.localEntries().size()
                    + " 条，公开 " + index.remoteEntries().size() + " 条");
            return index;
        } catch (Exception e) {
            LOG.e("读取内置源索引失败，改用兜底索引", e);
            return fallback();
        }
    }

    /**
     * 解析索引 JSON；解析不出来就用兜底值补齐。
     *
     * <p>单独抽出来是为了能在 JVM 单测里直接喂字符串。
     */
    public static SourceIndex parse(String json) {
        if (json == null || json.trim().isEmpty()) {
            return fallback();
        }
        try {
            SourceIndex parsed = new Gson().fromJson(json, SourceIndex.class);
            if (parsed == null) {
                return fallback();
            }
            parsed.fillDefaults();
            return parsed;
        } catch (Exception e) {
            LOG.e("解析源索引失败，改用兜底索引", e);
            return fallback();
        }
    }

    /**
     * 兜底索引：assets 读不出来时给一个空索引，
     * 找源仍会走 GitHub / GitLab / Gitee 的默认发现配置。
     */
    public static SourceIndex fallback() {
        SourceIndex index = new SourceIndex();
        index.local = new ArrayList<>();
        index.sources = new ArrayList<>();
        index.fillDefaults();
        return index;
    }

    private void fillDefaults() {
        if (local == null) {
            local = new ArrayList<>();
        }
        if (sources == null) {
            sources = new ArrayList<>();
        }
        if (github == null) {
            github = new Github();
        }
        if (gitlab == null) {
            gitlab = new Gitlab();
        }
        if (gitee == null) {
            gitee = new Gitee();
        }
        if (indexSites == null) {
            indexSites = new ArrayList<>();
        }
        if (probe == null) {
            probe = new Probe();
        }
    }

    // ---------------- 读取 ----------------

    /** 随包内置条目（优先于在线仓库发现）。 */
    public List<Entry> localEntries() {
        return local == null ? Collections.<Entry>emptyList() : valid(local);
    }

    /** 公开地址条目（需要联网验证）。 */
    public List<Entry> remoteEntries() {
        return sources == null ? Collections.<Entry>emptyList() : valid(sources);
    }

    public Github github() {
        return github == null ? (github = new Github()) : github;
    }

    public Gitlab gitlab() {
        return gitlab == null ? (gitlab = new Gitlab()) : gitlab;
    }

    public Gitee gitee() {
        return gitee == null ? (gitee = new Gitee()) : gitee;
    }

    public List<IndexSite> indexSites() {
        if (indexSites == null) {
            indexSites = new ArrayList<>();
        }
        List<IndexSite> out = new ArrayList<>();
        for (IndexSite site : indexSites) {
            if (site != null && site.isEnabled()
                    && site.getBaseUrl().startsWith("https://")) {
                out.add(site);
            }
        }
        return out;
    }

    public Probe probe() {
        return probe == null ? (probe = new Probe()) : probe;
    }

    public int version() {
        return version;
    }

    public String updated() {
        return updated;
    }

    public String note() {
        return note;
    }

    /** 全部待验证候选（本地优先）。 */
    public List<Entry> allEntries() {
        List<Entry> all = new ArrayList<>();
        all.addAll(localEntries());
        all.addAll(remoteEntries());
        return all;
    }

    private static List<Entry> valid(List<Entry> in) {
        List<Entry> out = new ArrayList<>(in.size());
        for (Entry e : in) {
            if (e != null && e.isValid()) {
                out.add(e);
            }
        }
        return out;
    }

    private static String read(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }
}
