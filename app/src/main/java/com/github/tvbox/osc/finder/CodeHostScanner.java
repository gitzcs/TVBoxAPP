package com.github.tvbox.osc.finder;

import com.github.tvbox.osc.util.LOG;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * GitLab / Gitee 仓库发现。
 *
 * <p>平台搜索 API 只负责找仓库；配置文件仍通过 raw 地址、常见文件名和 README
 * 中的链接发现，最终交给 {@link FinderProbe} 统一验证。
 */
public final class CodeHostScanner {

    public enum Platform {
        GITLAB("GitLab", "https://gitlab.com"),
        GITEE("Gitee", "https://gitee.com");

        private final String label;
        private final String webBase;

        Platform(String label, String webBase) {
            this.label = label;
            this.webBase = webBase;
        }

        public String label() {
            return label;
        }
    }

    public static final class Repo {
        private final String fullName;
        private final String branch;
        private final int stars;

        Repo(String fullName, String branch, int stars) {
            this.fullName = fullName;
            this.branch = branch == null || branch.trim().isEmpty() ? "main" : branch.trim();
            this.stars = stars;
        }

        public String fullName() {
            return fullName;
        }

        public String branch() {
            return branch;
        }

        public int stars() {
            return stars;
        }
    }

    public static final class Scan {
        private final List<String> urls;
        private final String note;

        Scan(List<String> urls, String note) {
            this.urls = urls == null ? Collections.<String>emptyList() : urls;
            this.note = note;
        }

        public List<String> urls() {
            return urls;
        }

        public String note() {
            return note;
        }
    }

    private final Platform platform;
    private final SourceIndex.CodeHost cfg;

    public CodeHostScanner(Platform platform, SourceIndex.CodeHost cfg) {
        this.platform = platform;
        this.cfg = cfg == null ? new SourceIndex.CodeHost() : cfg;
    }

    public static Repo parseSeed(Platform platform, String spec) {
        if (platform == null || spec == null) return null;
        String value = spec.trim();
        if (value.isEmpty()) return null;
        String prefix = platform.webBase + "/";
        if (value.toLowerCase().startsWith(prefix.toLowerCase())) {
            value = value.substring(prefix.length());
        }
        value = value.replaceFirst("\\.git$", "");
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        String branch = null;
        int at = value.indexOf('@');
        if (at >= 0) {
            branch = value.substring(at + 1).trim();
            value = value.substring(0, at).trim();
            if (branch.isEmpty()) branch = null;
        }
        int slash = value.indexOf('/');
        if (slash <= 0 || slash == value.length() - 1 || value.indexOf('/', slash + 1) >= 0) {
            return null;
        }
        return new Repo(value, branch, 0);
    }

    public List<Repo> searchRepos(String query, int max) {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }
        int limit = Math.max(1, Math.min(max, 20));
        String url;
        if (platform == Platform.GITLAB) {
            url = "https://gitlab.com/api/v4/projects?search="
                    + enc(query.trim()) + "&simple=true&per_page=" + limit
                    + "&order_by=last_activity_at&sort=desc";
        } else {
            url = "https://gitee.com/api/v5/search/repositories?q="
                    + enc(query.trim()) + "&per_page=" + limit;
        }
        FinderResult result = FinderHttp.get(url, null);
        if (!result.isOk()) {
            LOG.w(platform.label() + " 搜索失败：" + result.getMessage());
            return Collections.emptyList();
        }
        return parseRepos(result.getData());
    }

    static List<Repo> parseRepos(String json) {
        List<Repo> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return out;
        try {
            JsonElement root = JsonParser.parseString(json);
            JsonArray items;
            if (root.isJsonArray()) {
                items = root.getAsJsonArray();
            } else if (root.isJsonObject()) {
                JsonObject obj = root.getAsJsonObject();
                items = obj.has("items") && obj.get("items").isJsonArray()
                        ? obj.getAsJsonArray("items") : null;
            } else {
                items = null;
            }
            if (items == null) return out;
            for (JsonElement element : items) {
                if (!element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                String fullName = str(item, "path_with_namespace");
                if (fullName == null) fullName = str(item, "full_name");
                if (fullName == null || fullName.indexOf('/') < 0) continue;
                String branch = str(item, "default_branch");
                int stars = integer(item, "star_count");
                if (stars == 0) stars = integer(item, "stargazers_count");
                out.add(new Repo(fullName, branch, stars));
            }
        } catch (Exception e) {
            LOG.w("解析代码托管平台搜索结果失败：" + e.getMessage());
        }
        return out;
    }

    public Scan scan(String fullName, String branch) {
        if (fullName == null || fullName.trim().isEmpty()) {
            return new Scan(Collections.<String>emptyList(), "仓库名为空");
        }
        String repo = fullName.trim();
        String br = branch == null || branch.trim().isEmpty() ? "main" : branch.trim();
        Set<String> guessed = new LinkedHashSet<>();
        Set<String> discovered = new LinkedHashSet<>();
        List<String> notes = new ArrayList<>();

        for (String name : cfg.getGuessFilenames()) {
            guessed.add(rawUrl(repo, br, name));
        }

        List<String> treePaths = listTree(repo, br, notes);
        for (String path : treePaths) {
            discovered.add(rawUrl(repo, br, path));
        }

        for (String readme : readmeUrls(repo, br)) {
            discovered.add(readme);
        }

        List<String> limited = RepoScanner.mergeCapped(
                new ArrayList<>(guessed), new ArrayList<>(discovered), cfg.getMaxFilesPerRepo());
        return new Scan(limited, notes.isEmpty() ? null : join(notes, "；"));
    }

    private List<String> listTree(String repo, String branch, List<String> notes) {
        String url;
        if (platform == Platform.GITLAB) {
            url = "https://gitlab.com/api/v4/projects/" + enc(repo)
                    + "/repository/tree?ref=" + enc(branch) + "&recursive=true&per_page=100";
        } else {
            url = "https://gitee.com/api/v5/repos/" + repo
                    + "/git/trees/" + enc(branch) + "?recursive=1";
        }
        FinderResult result = FinderHttp.get(url, null);
        if (!result.isOk()) {
            notes.add("文件树 API 不可用");
            return Collections.emptyList();
        }
        List<String> paths = new ArrayList<>();
        try {
            JsonElement root = JsonParser.parseString(result.getData());
            JsonArray tree = null;
            if (root.isJsonArray()) {
                tree = root.getAsJsonArray();
            } else if (root.isJsonObject()) {
                JsonObject obj = root.getAsJsonObject();
                if (obj.has("tree") && obj.get("tree").isJsonArray()) {
                    tree = obj.getAsJsonArray("tree");
                }
            }
            if (tree == null) return paths;
            for (JsonElement element : tree) {
                if (!element.isJsonObject()) continue;
                JsonObject node = element.getAsJsonObject();
                String type = str(node, "type");
                if (type != null && !"blob".equals(type)) continue;
                String path = str(node, "path");
                if (RepoScanner.isConfigCandidate(path)) {
                    paths.add(path);
                }
            }
        } catch (Exception e) {
            notes.add("文件树解析失败");
        }
        return paths;
    }

    private List<String> readmeUrls(String repo, String branch) {
        List<String> out = new ArrayList<>();
        for (String file : cfg.getReadmeCandidates()) {
            FinderResult result = FinderHttp.get(rawUrl(repo, branch, file), null);
            if (!result.isOk()) continue;
            out.addAll(RepoScanner.extractConfigUrls(result.getData()));
            break;
        }
        return out;
    }

    String rawUrl(String repo, String branch, String path) {
        String clean = path == null ? "" : path;
        while (clean.startsWith("/")) clean = clean.substring(1);
        if (platform == Platform.GITLAB) {
            return platform.webBase + "/" + repo + "/-/raw/" + branch + "/" + clean;
        }
        return platform.webBase + "/" + repo + "/raw/" + branch + "/" + clean;
    }

    private static String enc(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private static String str(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return null;
        try {
            return obj.get(key).getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    private static int integer(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return 0;
        try {
            return obj.get(key).getAsInt();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String join(List<String> values, String separator) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (sb.length() > 0) sb.append(separator);
            sb.append(value);
        }
        return sb.toString();
    }
}
