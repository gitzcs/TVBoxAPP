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
 * <p>这是「自动找源」的离线基石：即使 GitHub 完全访问不通，
 * 也总有本地内置的配置可用，并且内置的公开地址可以逐个验证。
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

    /** 本地内置配置，永远可用（不依赖网络）。 */
    private List<Entry> local;

    /** 公开配置地址，需要联网验证。 */
    private List<Entry> sources;

    private Github github;
    private Probe probe;

    /** GitHub 发现参数。 */
    public static final class Github {
        private boolean enabled = true;
        private List<String> queries;
        private int maxRepos = 6;
        private int maxFilesPerRepo = 6;
        private List<String> guessFilenames;
        private List<String> readmeCandidates;

        public boolean isEnabled() {
            return enabled;
        }

        public List<String> getQueries() {
            return queries == null ? Collections.<String>emptyList() : queries;
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
     * 兜底索引：assets 读不出来时至少保证有内置配置可用。
     * 这里的本地条目指的是随包发布的 assets 配置。
     */
    public static SourceIndex fallback() {
        SourceIndex index = new SourceIndex();
        index.local = new ArrayList<>(Arrays.asList(
                new Entry("内置示例配置（4 个采集站）", "assets://default_config.json")));
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
        if (probe == null) {
            probe = new Probe();
        }
    }

    // ---------------- 读取 ----------------

    /** 本地内置条目（assets 配置，不依赖网络）。 */
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
