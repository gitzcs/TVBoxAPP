package com.github.tvbox.osc.finder;

import com.github.tvbox.osc.finder.CodeHostScanner;
import com.github.tvbox.osc.finder.ConfigSniffer;
import com.github.tvbox.osc.finder.FinderHttp;
import com.github.tvbox.osc.finder.FinderResult;
import com.github.tvbox.osc.finder.LocalSourceMerger;
import com.github.tvbox.osc.finder.RepoScanner;
import com.github.tvbox.osc.finder.SourceIndex;
import com.github.tvbox.osc.util.AES;
import com.github.tvbox.osc.util.RegexUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 合并源导出工具（产物不入库，仅代码入库）：在本机 JVM 上用项目真实的扫描/解密/合并代码，
 * 从 GitHub/GitLab/Gitee 找出当前可用配置并生成「合并后的本地源」同格式文件，
 * 用于托管到 Gitee 等国内可达位置（用法见 docs/合并源导出工具.md）。
 *
 * <p>运行：{@code gradlew :app:testJavaDebugUnitTest --tests "com.github.tvbox.osc.finder.MergedSourceExportTest"}
 * <p>产物：{@code /.temp/merged_export/merged_source.json + report.json}
 *
 * <p>与 App 的差异：验证只做到「取到配置、可解析、sites 合法」（App 里还有播放层验证）。
 * <p>镜像改写：默认把产物里的 GitHub raw 地址（spider / jar / 字符串 ext）改写为 jsDelivr，
 * 便于国内直连；用 {@code -Dexport.mirror=false} 关闭。
 */
public class MergedSourceExportTest {

    private static final boolean PREFER_MIRROR =
            Boolean.parseBoolean(System.getProperty("export.mirror", "true"));

    private static final Pattern RAW_GITHUB = Pattern.compile(
            "^https?://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/([^/]+)/(.+)$");
    private static final Pattern WEB_GITHUB = Pattern.compile(
            "^https?://github\\.com/([^/]+)/([^/]+)/(?:raw|blob)/([^/]+)/(.+)$");
    private static final String PK = ";pk;";
    private static final int MAX_CANDIDATES = 40;

    private static final class Cand {
        final String name, url, origin, detail;

        Cand(String name, String url, String origin, String detail) {
            this.name = name;
            this.url = url;
            this.origin = origin;
            this.detail = detail;
        }
    }

    private static final class Verified {
        final Cand cand;
        final JsonObject root;
        final int siteCount;
        final int spiderSites;
        final String message;

        Verified(Cand cand, JsonObject root, int siteCount, int spiderSites, String message) {
            this.cand = cand;
            this.root = root;
            this.siteCount = siteCount;
            this.spiderSites = spiderSites;
            this.message = message;
        }
    }

    @Test
    public void export() throws Exception {
        long start = System.currentTimeMillis();
        String idxJson = readFile("src/main/assets/source_index.json");
        SourceIndex index = SourceIndex.parse(idxJson);
        System.out.println("[export] 索引载入 OK");

        List<Cand> candidates = new ArrayList<>();
        collectGithub(index, candidates);
        collectCodeHost(CodeHostScanner.Platform.GITLAB, index.gitlab(), candidates, index.github());
        collectCodeHost(CodeHostScanner.Platform.GITEE, index.gitee(), candidates, index.github());
        System.out.println("[export] 候选共 " + candidates.size() + " 个");

        // 验证（4 线程，与 App 的并发度接近）
        List<Verified> verified = Collections.synchronizedList(new ArrayList<Verified>());
        List<Cand> failed = Collections.synchronizedList(new ArrayList<Cand>());
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<?>> futures = new ArrayList<Future<?>>();
        for (final Cand c : candidates) {
            futures.add(pool.submit(new Callable<Void>() {
                @Override
                public Void call() {
                    try {
                        Verified v = probe(c);
                        if (v != null) {
                            verified.add(v);
                        } else {
                            failed.add(c);
                        }
                    } catch (Throwable th) {
                        failed.add(c);
                    }
                    return null;
                }
            }));
        }
        for (Future<?> f : futures) {
            try {
                f.get(120, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                f.cancel(true);
            }
        }
        pool.shutdown();

        // 排序：站点多的在前，GitHub/GitLab/Gitee 混排（同 App 的 rank 思路）
        Collections.sort(verified, new Comparator<Verified>() {
            @Override
            public int compare(Verified a, Verified b) {
                return b.siteCount - a.siteCount;
            }
        });

        // 合并（与 App 完全同一套合并代码）
        List<LocalSourceMerger.Input> inputs = new ArrayList<LocalSourceMerger.Input>();
        for (Verified v : verified) {
            inputs.add(new LocalSourceMerger.Input(v.cand.name, v.cand.url, v.root));
        }
        LocalSourceMerger.Result result = LocalSourceMerger.merge(inputs);

        // 输出（可选：把 GitHub raw 地址改写为 jsDelivr，便于国内直连）
        int mirrorRewritten = 0;
        if (PREFER_MIRROR) {
            mirrorRewritten = rewriteGithubRawToJsDelivr(result.config);
            System.out.println("[export] 镜像改写：" + mirrorRewritten + " 个地址 -> cdn.jsdelivr.net");
        }

        File outDir = new File("../.temp/merged_export");
        outDir.mkdirs();
        write(new File(outDir, "merged_source.json"), result.config.toString());
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"generated\": \"").append(new java.util.Date()).append("\",\n");
        sb.append("  \"sourceCount\": ").append(result.sourceCount)
                .append(",\n  \"siteCount\": ").append(result.siteCount)
                .append(",\n  \"duplicateCount\": ").append(result.duplicateCount).append(",\n");
        sb.append("  \"usable\": [\n");
        for (int i = 0; i < verified.size(); i++) {
            Verified v = verified.get(i);
            sb.append("    {\"origin\":\"").append(v.cand.origin)
                    .append("\",\"name\":\"").append(jsonEsc(v.cand.name))
                    .append("\",\"url\":\"").append(jsonEsc(v.cand.url))
                    .append("\",\"siteCount\":").append(v.siteCount)
                    .append(",\"spiderSites\":").append(v.spiderSites)
                    .append(",\"note\":\"").append(jsonEsc(v.message == null ? "" : v.message))
                    .append("\"}").append(i < verified.size() - 1 ? "," : "").append("\n");
        }
        sb.append("  ],\n  \"failed\": [\n");
        for (int i = 0; i < failed.size(); i++) {
            Cand c = failed.get(i);
            sb.append("    {\"origin\":\"").append(c.origin)
                    .append("\",\"url\":\"").append(jsonEsc(c.url)).append("\"}")
                    .append(i < failed.size() - 1 ? "," : "").append("\n");
        }
        sb.append("  ],\n  \"candidates\": ").append(candidates.size())
                .append(",\n  \"mirrorRewrite\": {\"enabled\": ").append(PREFER_MIRROR)
                .append(", \"count\": ").append(mirrorRewritten).append("}\n}");
        write(new File(outDir, "report.json"), sb.toString());

        System.out.println("[export] === 完成 ===");
        System.out.println("[export] 可用配置 " + verified.size() + " 个，合并后站点 " + result.siteCount
                + " 个（去重 " + result.duplicateCount + "），失败候选 " + failed.size() + " 个");
        System.out.println("[export] 耗时 " + (System.currentTimeMillis() - start) / 1000 + "s");
        for (Verified v : verified) {
            System.out.println("[export]   [" + v.cand.origin + "] " + v.siteCount + " 站  " + v.cand.url);
        }
    }

    // ---------------- 发现 ----------------

    private void collectGithub(SourceIndex index, List<Cand> out) {
        SourceIndex.Github gh = index.github();
        List<RepoScanner.Repo> repos = new ArrayList<RepoScanner.Repo>();
        Set<String> seen = new LinkedHashSet<String>();
        for (String spec : gh.getRepos()) {
            if (repos.size() >= gh.getMaxRepos()) break;
            RepoScanner.Repo seed = RepoScanner.parseSeed(spec);
            if (seed != null && seen.add(seed.fullName())) repos.add(seed);
        }
        int tried = 0;
        for (String q : gh.getQueries()) {
            if (tried >= 2 || repos.size() >= gh.getMaxRepos()) break;
            tried++;
            try {
                List<RepoScanner.Repo> found = RepoScanner.searchRepos(q, gh.getMaxRepos());
                for (RepoScanner.Repo r : found) {
                    if (seen.add(r.fullName())) repos.add(r);
                }
            } catch (Throwable th) {
                System.out.println("[export] GitHub 搜索失败(可能限流): " + q);
                break;
            }
        }
        if (repos.size() > gh.getMaxRepos()) {
            repos = repos.subList(0, gh.getMaxRepos());
        }
        RepoScanner scanner = new RepoScanner(gh);
        for (RepoScanner.Repo repo : repos) {
            String branch = repo.branch();
            if (branch == null || branch.trim().isEmpty()) {
                branch = RepoScanner.detectBranch(repo.fullName());
            }
            System.out.println("[export] 扫描 GitHub：" + repo.fullName());
            try {
                RepoScanner.Scan scan = scanner.scan(repo.fullName(), branch);
                for (String url : scan.urls()) {
                    if (isExcluded(url, gh)) continue;
                    add(out, new Cand(repo.fullName(), url, "GitHub", repo.fullName()));
                }
            } catch (Throwable th) {
                System.out.println("[export] 扫描失败：" + repo.fullName() + " " + th);
            }
        }
    }

    private void collectCodeHost(CodeHostScanner.Platform platform, SourceIndex.CodeHost cfg,
                                 List<Cand> out, SourceIndex.Github gh) {
        if (cfg == null || !cfg.isEnabled()) return;
        List<CodeHostScanner.Repo> repos = new ArrayList<CodeHostScanner.Repo>();
        Set<String> seen = new LinkedHashSet<String>();
        for (String spec : cfg.getRepos()) {
            if (repos.size() >= cfg.getMaxRepos()) break;
            CodeHostScanner.Repo repo = CodeHostScanner.parseSeed(platform, spec);
            if (repo != null && seen.add(repo.fullName())) repos.add(repo);
        }
        CodeHostScanner scanner = new CodeHostScanner(platform, cfg);
        for (String q : cfg.getQueries()) {
            if (repos.size() >= cfg.getMaxRepos()) break;
            System.out.println("[export] 搜索 " + platform.label() + "：" + q);
            try {
                for (CodeHostScanner.Repo r : scanner.searchRepos(q, cfg.getMaxRepos() - repos.size())) {
                    if (repos.size() >= cfg.getMaxRepos()) break;
                    if (seen.add(r.fullName())) repos.add(r);
                }
            } catch (Throwable th) {
                System.out.println("[export] " + platform.label() + " 搜索失败：" + q + " " + th);
            }
        }
        String origin = platform == CodeHostScanner.Platform.GITLAB ? "GitLab" : "Gitee";
        for (CodeHostScanner.Repo repo : repos) {
            System.out.println("[export] 扫描 " + platform.label() + "：" + repo.fullName());
            try {
                CodeHostScanner.Scan scan = scanner.scan(repo.fullName(), repo.branch());
                for (String url : scan.urls()) {
                    if (isExcluded(url, gh)) continue;
                    add(out, new Cand(repo.fullName(), url, origin, repo.fullName()));
                }
            } catch (Throwable th) {
                System.out.println("[export] 扫描失败：" + repo.fullName() + " " + th);
            }
        }
    }

    private static boolean isExcluded(String url, SourceIndex.Github gh) {
        if (url == null || url.trim().isEmpty()) return true;
        if (gh == null) return false;
        String lower = url.toLowerCase();
        for (String p : gh.getExcludePatterns()) {
            if (p != null && !p.trim().isEmpty() && lower.contains(p.trim().toLowerCase())) return true;
        }
        return false;
    }

    private static void add(List<Cand> list, Cand c) {
        if (c == null || c.url == null || c.url.trim().isEmpty()) return;
        for (Cand e : list) {
            if (e.url.equals(c.url)) return;
        }
        if (list.size() < MAX_CANDIDATES) list.add(c);
    }

    // ---------------- 验证 ----------------

    private Verified probe(Cand c) {
        String url = c.url;
        String key = null;
        int pk = url.indexOf(PK);
        if (pk > 0) {
            key = url.substring(pk + PK.length());
            url = url.substring(0, pk);
        }
        Verified v = probeOne(c, url, key, "");
        if (v != null) return v;
        // 镜像兜底（与 App 相同策略：仅 GitHub raw 地址）
        for (String mirror : mirrorsFor(url)) {
            Verified m = probeOne(c, mirror, key, "主地址不可用，命中镜像 " + hostOf(mirror));
            if (m != null) return m;
        }
        return null;
    }

    private Verified probeOne(Cand c, String address, String key, String note) {
        try {
            FinderResult r = FinderHttp.get(address, null, FinderHttp.DEFAULT_TIMEOUT_SECONDS);
            if (!r.isOk()) return null;
            String raw = r.getData();
            if (raw == null || raw.trim().isEmpty()) return null;
            String json = findResult(raw, key);
            if (json == null) return null;
            json = ConfigSniffer.stripFence(json.trim());
            json = LocalSourceMerger.resolveRelativePaths(json, address);
            JsonElement el = new JsonParser().parse(json);
            if (!el.isJsonObject()) return null;
            JsonObject root = el.getAsJsonObject();
            JsonArray sites = root.has("sites") && root.get("sites").isJsonArray()
                    ? root.getAsJsonArray("sites") : null;
            if (sites == null || sites.size() == 0) return null;
            int count = 0, spider = 0;
            for (JsonElement s : sites) {
                if (s == null || !s.isJsonObject()) continue;
                JsonObject so = s.getAsJsonObject();
                String k = so.has("key") && so.get("key").isJsonPrimitive() ? so.get("key").getAsString() : null;
                String api = so.has("api") && so.get("api").isJsonPrimitive() ? so.get("api").getAsString() : null;
                if (k == null || k.isEmpty() || api == null || api.isEmpty() || !so.has("type")) continue;
                count++;
                if (so.get("type").getAsInt() == 3) spider++;
            }
            if (count == 0) return null;
            return new Verified(new Cand(c.name, address, c.origin, c.detail), root, count, spider,
                    note == null || note.isEmpty() ? "" : note);
        } catch (Throwable th) {
            return null;
        }
    }

    /** 与 App 同策略的 GitHub raw 镜像候选。 */
    private static List<String> mirrorsFor(String url) {
        List<String> out = new ArrayList<String>();
        if (url == null) return out;
        String t = url.trim();
        Matcher m = RAW_GITHUB.matcher(t);
        if (!m.matches()) m = WEB_GITHUB.matcher(t);
        if (m.matches()) {
            out.add("https://cdn.jsdelivr.net/gh/" + m.group(1) + "/" + m.group(2)
                    + "@" + m.group(3) + "/" + m.group(4));
            out.add("https://raw.gitmirror.com/" + m.group(1) + "/" + m.group(2)
                    + "/" + m.group(3) + "/" + m.group(4));
        }
        return out;
    }

    private static String hostOf(String url) {
        int s = url.indexOf("://");
        int e = url.indexOf('/', s + 3);
        return e > 0 ? url.substring(s + 3, e) : url;
    }

    // ---------------- 解密（ApiConfig.FindResult 的 JVM 版，Base64 换 java 实现） ----------------

    private static String findResult(String json, String configKey) {
        String content = json;
        try {
            if (AES.isJson(content)) return content;
            Pattern pattern = RegexUtils.getPattern("[A-Za-z0-9]{8}\\*\\*");
            Matcher matcher = pattern.matcher(content);
            if (matcher.find()) {
                content = content.substring(content.indexOf(matcher.group()) + 10);
                content = new String(java.util.Base64.getMimeDecoder().decode(content), "UTF-8");
            }
            content = content.trim();
            if (content.startsWith("2423")) {
                content = content.replaceAll("\\s+", "");
                String data = content.substring(content.indexOf("2324") + 4, content.length() - 26);
                content = new String(AES.toBytes(content)).toLowerCase();
                String key = AES.rightPadding(content.substring(content.indexOf("$#") + 2, content.indexOf("#$")), "0", 16);
                String iv = AES.rightPadding(content.substring(content.length() - 13), "0", 16);
                json = AES.CBC(data, key, iv);
            } else if (configKey != null && !AES.isJson(content)) {
                json = AES.ECB(content, configKey);
            } else {
                json = content;
            }
        } catch (Throwable th) {
            return content;
        }
        return json;
    }

    // ---------------- GitHub raw → jsDelivr 镜像改写 ----------------

    /** 改写配置里的 GitHub raw 地址（顶层 spider、sites 的 jar 与字符串 ext）。返回改写数量。 */
    private static int rewriteGithubRawToJsDelivr(JsonObject root) {
        int[] counter = {0};
        rewriteMember(root, "spider", counter);
        JsonArray sites = root.has("sites") && root.get("sites").isJsonArray()
                ? root.getAsJsonArray("sites") : null;
        if (sites != null) {
            for (JsonElement s : sites) {
                if (s == null || !s.isJsonObject()) continue;
                JsonObject site = s.getAsJsonObject();
                rewriteMember(site, "jar", counter);
                JsonElement ext = site.get("ext");
                if (ext != null && ext.isJsonPrimitive() && ext.getAsJsonPrimitive().isString()) {
                    rewriteMember(site, "ext", counter);
                }
            }
        }
        return counter[0];
    }

    private static void rewriteMember(JsonObject obj, String key, int[] counter) {
        JsonElement e = obj.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) return;
        String origin = e.getAsString();
        String mirrored = toJsDelivr(origin);
        if (mirrored != null && !mirrored.equals(origin)) {
            obj.addProperty(key, mirrored);
            counter[0]++;
        }
    }

    /** raw.githubusercontent.com / github.com raw|blob → jsDelivr；非 GitHub raw 返回 null。 */
    private static String toJsDelivr(String url) {
        if (url == null) return null;
        String t = url.trim();
        Matcher m = RAW_GITHUB.matcher(t);
        if (!m.matches()) m = WEB_GITHUB.matcher(t);
        if (!m.matches()) return null;
        return "https://cdn.jsdelivr.net/gh/" + m.group(1) + "/" + m.group(2)
                + "@" + m.group(3) + "/" + m.group(4);
    }

    // ---------------- 工具 ----------------

    private static String readFile(String path) throws Exception {
        FileInputStream in = new FileInputStream(path);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    private static void write(File f, String content) throws Exception {
        OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), Charset.forName("UTF-8"));
        try {
            w.write(content);
        } finally {
            w.close();
        }
    }

    private static String jsonEsc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}
