package com.github.tvbox.osc.finder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;


import com.github.tvbox.osc.util.LOG;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把一个 GitHub 仓库展开成「候选配置地址」。
 *
 * <p>这里必须做多通道降级，因为实测 GitHub 未认证 API 很容易被限流：
 * <table border="1">
 *     <tr><th>通道</th><th>接口</th><th>实测</th></tr>
 *     <tr><td>1. 猜文件名</td><td>{@code raw.githubusercontent.com}（不走 API）</td>
 *         <td>可用，最稳</td></tr>
 *     <tr><td>2. 文件树</td><td>{@code api.github.com/git/trees}</td><td>常 403 限流</td></tr>
 *     <tr><td>3. 文件列表</td><td>{@code data.jsdelivr.com}</td><td>仓库超 50MB 时 403</td></tr>
 *     <tr><td>4. README</td><td>{@code raw.githubusercontent.com} 抽链接</td>
 *         <td>可用，能捞到作者写在说明里的地址</td></tr>
 * </table>
 *
 * <p>任何一条通道失败都不影响其它通道，最终由 {@link SourceProbe} 逐个下载验证——
 * 猜错的地址只会浪费一次 404 请求，不会污染结果。
 */
public final class RepoScanner {

    private static final String API = "https://api.github.com";
    private static final String RAW = "https://raw.githubusercontent.com";
    private static final String CDN = "https://cdn.jsdelivr.net/gh";

    /** 从 README 里抽取 http(s) 链接。中文文件名（「多仓.json」）很常见，所以不能用 \w。 */
    private static final Pattern URL_PATTERN = Pattern.compile(
            "https?://[^\\s\"'<>()\\[\\]{},;，。；、！？`]+", Pattern.CASE_INSENSITIVE);

    /** 明显不是配置的地址。 */
    private static final String[] URL_BLOCKLIST = {
            "api.github.com", "github.com/search", "shields.io", "badge",
            "docs.github.com", "help.github.com", "opensource.org", "img.shields",
            "telegram", "t.me", "gitee.com/help", "license", ".png", ".jpg", ".gif",
            ".svg", ".webp", ".zip", ".apk", ".dex", "/jar/", "node_modules",
    };

    private final SourceIndex.Github cfg;

    public RepoScanner(SourceIndex.Github cfg) {
        this.cfg = cfg == null ? new SourceIndex.Github() : cfg;
    }

    /** 一个搜索到的仓库。 */
    public static final class Repo {
        private final String fullName;
        private final String branch;
        private final int stars;
        private final String description;

        Repo(String fullName, String branch, int stars, String description) {
            this.fullName = fullName;
            this.branch = branch;
            this.stars = stars;
            this.description = description;
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

        public String description() {
            return description;
        }

        public String owner() {
            int i = fullName.indexOf('/');
            return i > 0 ? fullName.substring(0, i) : fullName;
        }

        @Override
        public String toString() {
            return fullName + "★" + stars;
        }
    }

    /**
     * 解析种子仓库写法：{@code owner/repo} 或 {@code owner/repo@branch}。
     *
     * <p>不合法时返回 null（调用方跳过并记 note），不要抛异常——索引是随包发布的
     * 数据，写错一个字符不该让整个发现流程挂掉。
     */
    public static Repo parseSeed(String spec) {
        if (spec == null) {
            return null;
        }
        String s = spec.trim();
        // 允许直接写网页地址，容错一下
        s = s.replaceFirst("(?i)^https?://github\\.com/", "");
        s = s.replaceFirst("(?i)^git@github\\.com:", "");
        s = s.replaceFirst("\\.git$", "");
        if (s.isEmpty()) {
            return null;
        }
        String branch = null;
        int at = s.indexOf('@');
        if (at >= 0) {
            branch = s.substring(at + 1).trim();
            s = s.substring(0, at).trim();
        }
        if (branch != null && branch.isEmpty()) {
            branch = null;
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        int slash = s.indexOf('/');
        if (slash <= 0 || slash == s.length() - 1 || s.indexOf('/', slash + 1) >= 0) {
            return null;
        }
        return new Repo(s, branch, 0, "内置种子仓库");
    }

    /**
     * 探测仓库默认分支：先试 {@code main}，再试 {@code master}。
     *
     * <p>只在种子仓库没写分支时才调用。用 raw 上的 README 探路（不走 API、不消耗配额），
     * raw 不可达时（部分网络屏蔽 GitHub）改走 jsDelivr 镜像；两次请求都失败就回落到 {@code main}。
     */
    public static String detectBranch(String fullName) {
        for (String branch : new String[]{"main", "master"}) {
            if (branchReachable(fullName, branch)) {
                return branch;
            }
        }
        return "main";
    }

    /** 用 README 探测分支是否存在；raw 不通时走镜像，404 说明分支确实不存在。 */
    private static boolean branchReachable(String fullName, String branch) {
        String[] urls = {rawUrl(fullName, branch, "README.md"),
                mirrorUrl(fullName, branch, "README.md")};
        for (String url : urls) {
            FinderResult r = FinderHttp.get(url, null, FinderHttp.PROBE_TIMEOUT_SECONDS);
            if (r.isOk()) {
                return true;
            }
            if (r.isNotFound()) {
                return false;
            }
        }
        return false;
    }

    /** 一次扫描的结果。 */
    public static final class Scan {
        private final List<String> urls;
        private final String note;

        Scan(List<String> urls, String note) {
            this.urls = urls == null ? Collections.<String>emptyList() : urls;
            this.note = note;
        }

        /** 候选配置地址。 */
        public List<String> urls() {
            return urls;
        }

        /** 降级说明（用于 UI 提示 / 日志）；全部通道正常时为 null。 */
        public String note() {
            return note;
        }

        public boolean isEmpty() {
            return urls.isEmpty();
        }
    }

    // ---------------- 仓库搜索 ----------------

    /**
     * 按关键字搜索仓库。
     *
     * @return 搜索被限流或失败时返回空列表（调用方自行降级，不要当错误抛）
     */
    public static List<Repo> searchRepos(String query, int max) {
        List<Repo> out = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) {
            return out;
        }
        String url = API + "/search/repositories?q="
                + enc(query.trim()) + "&sort=stars&per_page=" + Math.max(1, Math.min(max, 20));
        FinderResult r = FinderHttp.get(url, githubHeaders());
        if (!r.isOk()) {
            LOG.w("GitHub 搜索失败（将降级到内置索引）：" + r.getMessage());
            return out;
        }
        try {
            JsonElement root = JsonParser.parseString(r.getData());
            if (!root.isJsonObject()) {
                return out;
            }
            JsonObject obj = root.getAsJsonObject();
            if (obj.has("message")) {
                // 典型是 "API rate limit exceeded"
                LOG.w("GitHub 搜索被拒绝：" + obj.get("message").getAsString());
                return out;
            }
            JsonArray items = obj.getAsJsonArray("items");
            if (items == null) {
                return out;
            }
            for (JsonElement el : items) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject repo = el.getAsJsonObject();
                String full = str(repo, "full_name");
                if (full == null || full.indexOf('/') < 0) {
                    continue;
                }
                String branch = str(repo, "default_branch");
                int stars = repo.has("stargazers_count")
                        ? repo.get("stargazers_count").getAsInt() : 0;
                out.add(new Repo(full, branch == null ? "main" : branch, stars,
                        str(repo, "description")));
            }
        } catch (Exception e) {
            LOG.w("解析 GitHub 搜索结果失败：" + e.getMessage());
        }
        return out;
    }

    // ---------------- 仓库扫描 ----------------

    public Scan scan(String fullName, String branch) {
        String br = branch == null || branch.trim().isEmpty() ? "main" : branch.trim();
        // 猜名与「发现结果」分开收集：两者要各占一半名额，
        // 否则猜名会把 README 里作者亲手写的真实地址全挤掉（见 mergeCapped）。
        Set<String> guessed = new LinkedHashSet<>();
        Set<String> discovered = new LinkedHashSet<>();
        List<String> notes = new ArrayList<>();

        // 通道 1：猜常见文件名（不走 API，最稳）
        for (String name : cfg.getGuessFilenames()) {
            guessed.add(rawUrl(fullName, br, name));
        }

        // 通道 2：GitHub 文件树（常被限流）
        List<String> treePaths = listByGithubTree(fullName, br, notes);
        for (String p : treePaths) {
            discovered.add(rawUrl(fullName, br, p));
        }

        if (treePaths.isEmpty()) {
            // 通道 3：jsDelivr 文件列表
            List<String> cdnPaths = listByJsdelivr(fullName, br, notes);
            for (String p : cdnPaths) {
                discovered.add(rawUrl(fullName, br, p));
            }
        }

        // 通道 4：README 里作者自己写的地址
        // 本仓库的地址排前面：README 常顺手链到别的仓库/别的作者的配置
        // （qist 的 README 里就有 jihulab、agit.ai、xhww.fun），
        // 名额有限时不该让它们挤掉本仓库自己的配置。
        List<String> readme = readmeUrls(fullName, br);
        String owner = fullName.toLowerCase(Locale.ROOT);
        List<String> others = new ArrayList<>();
        for (String u : readme) {
            if (u != null && u.toLowerCase(Locale.ROOT).contains(owner)) {
                discovered.add(u);
            } else {
                others.add(u);
            }
        }
        discovered.addAll(others);

        List<String> limited = mergeCapped(new ArrayList<>(guessed),
                new ArrayList<>(discovered), cfg.getMaxFilesPerRepo());
        return new Scan(limited, notes.isEmpty() ? null : join(notes, "；"));
    }

    /**
     * 把「猜出来的文件名」和「发现出来的地址」合并到一个名额里。
     *
     * <p>为什么不能简单按插入顺序截断：{@code guessFilenames} 是一份全局清单，
     * 十几个名字会把 {@code maxFilesPerRepo} 直接占满，于是 README 里作者亲手
     * 写的真实地址一个都留不下来。
     *
     * <p>但也不能反过来全让给发现结果：README 抽链接会捞到<b>别的仓库</b>的地址
     * （qist 的 README 里就链着 jihulab、agit.ai、xhww.fun 等），把本仓库自己的
     * 配置文件挤掉；而文件树 API 又经常被限流，此时猜名是唯一还能工作的通道。
     *
     * <p>所以：先给猜名留一个<b>保底名额</b>（最稳的通道必须有机会跑），
     * 再让发现结果占满剩余名额，最后把没用完的名额回填给更多猜名。
     */
    static List<String> mergeCapped(List<String> guessed, List<String> discovered, int cap) {
        List<String> out = new ArrayList<>();
        if (cap <= 0) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<>();
        int guessFloor = Math.min(guessed.size(), cap >= 6 ? 3 : 1);
        for (String u : guessed) {
            if (out.size() >= guessFloor) {
                break;
            }
            if (u != null && seen.add(u)) {
                out.add(u);
            }
        }
        for (String u : discovered) {
            if (out.size() >= cap) {
                break;
            }
            if (u != null && seen.add(u)) {
                out.add(u);
            }
        }
        // 发现结果没占满名额，把剩下的猜名回填进去
        for (String u : guessed) {
            if (out.size() >= cap) {
                break;
            }
            if (u != null && seen.add(u)) {
                out.add(u);
            }
        }
        return out;
    }

    private List<String> listByGithubTree(String fullName, String branch, List<String> notes) {
        FinderResult r = FinderHttp.get(apiTreeUrl(fullName, branch), githubHeaders());
        if (!r.isOk()) {
            notes.add("文件树 API 不可用（多被限流）");
            return Collections.emptyList();
        }
        List<String> paths = new ArrayList<>();
        try {
            JsonElement root = JsonParser.parseString(r.getData());
            if (!root.isJsonObject()) {
                return paths;
            }
            JsonObject obj = root.getAsJsonObject();
            if (obj.has("message")) {
                notes.add("文件树 API 被拒绝：" + obj.get("message").getAsString());
                return paths;
            }
            JsonArray tree = obj.getAsJsonArray("tree");
            if (tree == null) {
                return paths;
            }
            for (JsonElement el : tree) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject node = el.getAsJsonObject();
                if (!"blob".equals(str(node, "type"))) {
                    continue;
                }
                String path = str(node, "path");
                if (isConfigCandidate(path)) {
                    paths.add(path);
                }
            }
        } catch (Exception e) {
            notes.add("文件树解析失败");
        }
        return paths;
    }

    private List<String> listByJsdelivr(String fullName, String branch, List<String> notes) {
        FinderResult r = FinderHttp.get(jsdelivrListUrl(fullName, branch), null);
        if (!r.isOk()) {
            notes.add("jsDelivr 文件列表不可用");
            return Collections.emptyList();
        }
        List<String> paths = new ArrayList<>();
        try {
            JsonElement root = JsonParser.parseString(r.getData());
            if (!root.isJsonObject()) {
                return paths;
            }
            JsonArray files = root.getAsJsonObject().getAsJsonArray("files");
            if (files == null) {
                return paths;
            }
            for (JsonElement el : files) {
                if (!el.isJsonObject()) {
                    continue;
                }
                String name = str(el.getAsJsonObject(), "name");
                if (isConfigCandidate(name)) {
                    paths.add(name);
                }
            }
        } catch (Exception e) {
            notes.add("jsDelivr 列表解析失败");
        }
        return paths;
    }

    private List<String> readmeUrls(String fullName, String branch) {
        List<String> out = new ArrayList<>();
        for (String file : cfg.getReadmeCandidates()) {
            // raw 与 jsDelivr 镜像各试一次：部分网络屏蔽 raw.githubusercontent.com
            String[] urls = {rawUrl(fullName, branch, file), mirrorUrl(fullName, branch, file)};
            for (String url : urls) {
                FinderResult r = FinderHttp.get(url, null);
                if (!r.isOk()) {
                    if (r.isNotFound()) {
                        break; // 文件确定不存在，镜像也不必再试
                    }
                    continue;
                }
                out.addAll(extractConfigUrls(r.getData()));
                break; // 读到第一个就够
            }
            if (!out.isEmpty()) {
                break;
            }
        }
        return out;
    }

    // ---------------- 纯函数（可单测） ----------------

    /** 这个路径像不像 TVBox 配置。 */
    static boolean isConfigCandidate(String path) {
        if (path == null) {
            return false;
        }
        String p = path.trim();
        if (p.isEmpty()) {
            return false;
        }
        String lower = p.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".json")) {
            return false;
        }
        if (lower.startsWith(".") || lower.contains("/.")) {
            return false;
        }
        // 前面补一个 "/"，这样 "jar/x.json" 也能被 "/jar/" 命中
        String probe = "/" + lower;
        String[] skip = {"/node_modules/", "/jar/", "/assets/", "/.github/", "/src/",
                "/test/", "/tests/", "/docs/", "/gradle/", "/dist/", "/public/",
                "/static/", "/build/", "package.json", "package-lock", "tsconfig",
                "manifest.json", "dependabot", ".eslintrc", "composer.json",
                "renovate.json"};
        for (String s : skip) {
            if (probe.contains(s)) {
                return false;
            }
        }
        // 层级太深的多半是构建产物或图片清单
        int depth = 0;
        for (int i = 0; i < p.length(); i++) {
            if (p.charAt(i) == '/') {
                depth++;
            }
        }
        return depth <= 2;
    }

    /** 从文本（README / 配置）里抽取看起来是配置的 http(s) 链接。 */
    static List<String> extractConfigUrls(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = URL_PATTERN.matcher(text);
        while (m.find()) {
            String url = trimUrl(m.group());
            if (!seen.add(url)) {
                continue;
            }
            String lower = url.toLowerCase(Locale.ROOT);
            if (!lower.contains(".json")) {
                continue;
            }
            boolean blocked = false;
            for (String b : URL_BLOCKLIST) {
                if (lower.contains(b)) {
                    blocked = true;
                    break;
                }
            }
            if (!blocked) {
                out.add(url);
            }
        }
        return out;
    }

    /** 去掉 markdown 里粘连的尾部字符。 */
    private static String trimUrl(String url) {
        String s = url;
        while (s.length() > 0) {
            char c = s.charAt(s.length() - 1);
            if (c == ')' || c == ']' || c == '>' || c == '"' || c == '\'' || c == ','
                    || c == '.' || c == ';') {
                s = s.substring(0, s.length() - 1);
            } else {
                break;
            }
        }
        return s;
    }

    static String rawUrl(String fullName, String branch, String path) {
        return RAW + "/" + fullName + "/" + branch + "/" + stripLeadingSlash(path);
    }

    /** jsDelivr 镜像：raw 不通时可用（不做 CDN 缓存，避免拿到过期的验证结果）。 */
    static String mirrorUrl(String fullName, String branch, String path) {
        return CDN + "/" + fullName + "@" + branch + "/" + stripLeadingSlash(path);
    }

    static String apiTreeUrl(String fullName, String branch) {
        return API + "/repos/" + fullName + "/git/trees/" + branch + "?recursive=1";
    }

    static String jsdelivrListUrl(String fullName, String branch) {
        return "https://data.jsdelivr.com/v1/packages/gh/" + fullName
                + "@" + branch + "?structure=flat";
    }

    static Map<String, String> githubHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/vnd.github+json");
        headers.put("X-GitHub-Api-Version", "2022-11-28");
        return headers;
    }

    private static String stripLeadingSlash(String path) {
        String p = path == null ? "" : path;
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        return p;
    }

    private static String str(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        try {
            return obj.get(key).getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    static String enc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.'
                    || c == '~' || c == ':' || c == '+' || c == ',' || c == ' ') {
                sb.append(c == ' ' ? '+' : c);
            } else {
                for (byte b : String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                    sb.append('%');
                    sb.append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)));
                    sb.append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
                }
            }
        }
        return sb.toString();
    }

    private static String join(List<String> items, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            if (sb.length() > 0) {
                sb.append(sep);
            }
            sb.append(s);
        }
        return sb.toString();
    }
}
