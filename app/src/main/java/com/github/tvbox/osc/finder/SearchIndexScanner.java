package com.github.tvbox.osc.finder;

import com.github.tvbox.osc.util.LOG;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从第三方 GitHub 搜索索引站获取仓库元数据。
 *
 * <p>索引站只替代 GitHub Search API 的“发现”阶段。返回的仓库仍会交给
 * {@link RepoScanner} 展开，并由 {@link FinderProbe} 下载配置后验证。
 */
public final class SearchIndexScanner {

    private SearchIndexScanner() {
    }

    public static final class Result {
        private final boolean ok;
        private final List<RepoScanner.Repo> repos;
        private final String message;

        private Result(boolean ok, List<RepoScanner.Repo> repos, String message) {
            this.ok = ok;
            this.repos = repos == null ? Collections.<RepoScanner.Repo>emptyList() : repos;
            this.message = message;
        }

        public boolean isOk() {
            return ok;
        }

        public List<RepoScanner.Repo> repos() {
            return repos;
        }

        public String message() {
            return message;
        }
    }

    public static Result search(SourceIndex.IndexSite site, String query, int max) {
        if (site == null || site.getBaseUrl().isEmpty()) {
            return new Result(false, null, "索引站地址为空");
        }
        if (query == null || query.trim().isEmpty() || max <= 0) {
            return new Result(true, null, null);
        }
        FinderResult response = FinderHttp.get(
                searchUrl(site, query, max),
                headers(),
                site.getTimeoutSeconds());
        if (!response.isOk()) {
            LOG.w("索引站搜索失败（" + site.getName() + "）：" + response.getMessage());
            return new Result(false, null, response.getMessage());
        }
        List<RepoScanner.Repo> repos = parseRepos(response.getData(), max);
        if (repos.size() > max) {
            repos = new ArrayList<>(repos.subList(0, max));
        }
        return new Result(true, repos, null);
    }

    static String searchUrl(SourceIndex.IndexSite site, String query, int max) {
        int perPage = Math.min(site.getPerPage(), Math.max(max, 1));
        return site.getBaseUrl() + "/api/search?q=" + RepoScanner.enc(query.trim())
                + "&sort=" + RepoScanner.enc(site.getSort())
                + "&order=" + RepoScanner.enc(site.getOrder())
                + "&page=1&per_page=" + perPage
                + "&scope=github";
    }

    static List<RepoScanner.Repo> parseRepos(String json, int max) {
        if (json == null || json.trim().isEmpty() || max <= 0) {
            return new ArrayList<>();
        }
        try {
            JsonElement rootElement = JsonParser.parseString(json);
            if (!rootElement.isJsonObject()) {
                return new ArrayList<>();
            }
            JsonObject root = rootElement.getAsJsonObject();
            JsonObject data = root;
            if (root.has("data") && root.get("data").isJsonObject()) {
                data = root.getAsJsonObject("data");
            }
            if (root.has("code") && !root.get("code").isJsonNull()
                    && root.get("code").getAsInt() != 0) {
                LOG.w("索引站拒绝搜索：" + string(root, "message"));
                return new ArrayList<>();
            }
            JsonArray items = data.getAsJsonArray("items");
            if (items == null) {
                return new ArrayList<>();
            }
            List<RepoScanner.Repo> out = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (JsonElement item : items) {
                if (out.size() >= max) {
                    break;
                }
                if (!item.isJsonObject()) {
                    continue;
                }
                JsonObject repo = item.getAsJsonObject();
                String fullName = string(repo, "full_name");
                if (!validFullName(fullName) || !seen.add(fullName)) {
                    continue;
                }
                String branch = string(repo, "default_branch");
                int stars = integer(repo, "stargazers_count");
                out.add(new RepoScanner.Repo(fullName,
                        branch == null || branch.trim().isEmpty() ? "main" : branch,
                        stars, string(repo, "description")));
            }
            return out;
        } catch (Exception e) {
            LOG.w("解析索引站搜索结果失败：" + e.getMessage());
            return new ArrayList<>();
        }
    }

    private static boolean validFullName(String fullName) {
        if (fullName == null) {
            return false;
        }
        String value = fullName.trim();
        int slash = value.indexOf('/');
        return slash > 0 && slash < value.length() - 1
                && value.indexOf('/', slash + 1) < 0;
    }

    private static Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        return headers;
    }

    private static String string(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        try {
            return obj.get(key).getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    private static int integer(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return 0;
        }
        try {
            return obj.get(key).getAsInt();
        } catch (Exception e) {
            return 0;
        }
    }
}
