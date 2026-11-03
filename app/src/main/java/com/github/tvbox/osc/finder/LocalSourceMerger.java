package com.github.tvbox.osc.finder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把多个配置里的视频源（sites）合并成一份本地配置。纯函数，不碰网络和全局状态。
 *
 * <p>合并规则：
 * <ul>
 *     <li>完全相同的站点（type + api + ext + jar 一致）只保留第一个</li>
 *     <li>key 冲突时追加序号；名称冲突时追加来源名，方便在首页区分</li>
 *     <li>每个配置自己的顶层 {@code spider} 会写到它的 type 3 站点的 {@code jar} 上，
 *         否则合并后这些站点会去主 jar 里找类而失败；第一个带 spider 的配置作为主 jar</li>
 *     <li>{@code ./}、{@code ../} 相对地址按来源配置地址解析成绝对地址</li>
 *     <li>parses / flags / ads / doh 合并去重，lives / wallpaper 取第一个有的</li>
 * </ul>
 */
public final class LocalSourceMerger {

    private static final Pattern RELATIVE_STRING = Pattern.compile("\"(\\.\\.?/[^\"]*)\"");

    private LocalSourceMerger() {
    }

    /** 一个待合并的配置。 */
    public static final class Input {
        final String name;
        final String baseUrl;
        final JsonObject root;

        public Input(String name, String baseUrl, JsonObject root) {
            this.name = name;
            this.baseUrl = baseUrl;
            this.root = root;
        }
    }

    /** 合并结果。 */
    public static final class Result {
        public final JsonObject config;
        public final int sourceCount;
        public final int siteCount;
        public final int spiderSiteCount;
        public final int duplicateCount;

        Result(JsonObject config, int sourceCount, int siteCount, int spiderSiteCount,
               int duplicateCount) {
            this.config = config;
            this.sourceCount = sourceCount;
            this.siteCount = siteCount;
            this.spiderSiteCount = spiderSiteCount;
            this.duplicateCount = duplicateCount;
        }
    }

    /** 把配置文本中以 ./ 或 ../ 开头的字符串解析成绝对地址（在 JSON 解析前调用）。 */
    public static String resolveRelativePaths(String json, String baseUrl) {
        if (json == null || baseUrl == null || !baseUrl.contains("://")
                || (!json.contains("\"./") && !json.contains("\"../"))) {
            return json;
        }
        Matcher m = RELATIVE_STRING.matcher(json);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String rel = m.group(1);
            String abs = FinderProbe.resolveAgainst(rel, baseUrl);
            if (rel.endsWith("/") && abs != null && !abs.endsWith("/")) {
                abs = abs + "/";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement("\"" + abs + "\""));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static Result merge(List<Input> inputs) {
        JsonObject out = new JsonObject();
        JsonArray sites = new JsonArray();
        JsonArray parses = new JsonArray();
        JsonArray flags = new JsonArray();
        JsonArray ads = new JsonArray();
        JsonArray doh = new JsonArray();
        Set<String> siteSigs = new HashSet<>();
        Set<String> keys = new HashSet<>();
        Set<String> names = new HashSet<>();
        Set<String> parseUrls = new HashSet<>();
        Set<String> flagSet = new HashSet<>();
        Set<String> adSet = new HashSet<>();
        Set<String> dohSet = new HashSet<>();
        String mainSpider = null;
        int sourceCount = 0;
        int spiderSites = 0;
        int duplicates = 0;

        for (Input input : inputs == null ? new ArrayList<Input>() : inputs) {
            if (input == null || input.root == null) continue;
            JsonObject root = input.root;
            JsonArray srcSites = array(root, "sites");
            if (srcSites == null || srcSites.size() == 0) continue;
            sourceCount++;
            String spider = string(root, "spider");
            if (mainSpider == null && spider != null && !spider.isEmpty()) {
                mainSpider = spider;
                out.addProperty("spider", spider);
            }
            String sourceName = input.name == null || input.name.trim().isEmpty()
                    ? "源" + sourceCount : input.name.trim();

            for (JsonElement el : srcSites) {
                if (el == null || !el.isJsonObject()) continue;
                JsonObject site = el.getAsJsonObject().deepCopy();
                String key = string(site, "key");
                String api = string(site, "api");
                if (key == null || key.isEmpty() || api == null || api.isEmpty() || !site.has("type")) {
                    continue;
                }
                int type;
                try {
                    type = site.get("type").getAsInt();
                } catch (Throwable th) {
                    continue;
                }
                String jar = string(site, "jar");
                boolean hasJar = jar != null && !jar.isEmpty();
                boolean usesOwnSpider = type == 3 && !hasJar && isJarSpider(api)
                        && spider != null && !spider.isEmpty();
                if (usesOwnSpider && !spider.equals(mainSpider)) {
                    site.addProperty("jar", spider);
                }
                String effectiveJar = hasJar ? jar : (usesOwnSpider ? spider : "");
                String ext = site.has("ext") ? site.get("ext").toString() : "";
                String sig = type + "|" + api + "|" + ext + "|" + effectiveJar;
                if (!siteSigs.add(sig)) {
                    duplicates++;
                    continue;
                }
                String uniqueKey = key;
                for (int n = 2; keys.contains(uniqueKey); n++) {
                    uniqueKey = key + "_" + n;
                }
                keys.add(uniqueKey);
                site.addProperty("key", uniqueKey);
                String name = string(site, "name");
                if (name == null || name.isEmpty()) name = key;
                if (names.contains(name)) {
                    name = name + "（" + sourceName + "）";
                }
                names.add(name);
                site.addProperty("name", name);
                if (type == 3) spiderSites++;
                sites.add(site);
            }

            JsonArray srcParses = array(root, "parses");
            if (srcParses != null) {
                for (JsonElement p : srcParses) {
                    if (p == null || !p.isJsonObject()) continue;
                    String url = string(p.getAsJsonObject(), "url");
                    if (url != null && !url.isEmpty() && parseUrls.add(url)) parses.add(p.deepCopy());
                }
            }
            addStrings(array(root, "flags"), flags, flagSet);
            addStrings(array(root, "ads"), ads, adSet);
            JsonArray srcDoh = array(root, "doh");
            if (srcDoh != null) {
                for (JsonElement d : srcDoh) {
                    if (d != null && dohSet.add(d.toString())) doh.add(d.deepCopy());
                }
            }
            JsonArray lives = array(root, "lives");
            if (!out.has("lives") && lives != null && lives.size() > 0) {
                out.add("lives", lives.deepCopy());
            }
            String wallpaper = string(root, "wallpaper");
            if (!out.has("wallpaper") && wallpaper != null && !wallpaper.isEmpty()) {
                out.addProperty("wallpaper", wallpaper);
            }
        }

        out.add("sites", sites);
        if (parses.size() > 0) out.add("parses", parses);
        if (flags.size() > 0) out.add("flags", flags);
        if (ads.size() > 0) out.add("ads", ads);
        if (doh.size() > 0) out.add("doh", doh);
        return new Result(out, sourceCount, sites.size(), spiderSites, duplicates);
    }

    /** 走 JarLoader 的 type 3 站点；js / py 站点有各自的加载器，不需要 jar。 */
    private static boolean isJarSpider(String api) {
        return !(api.endsWith(".js") || api.contains(".js?") || api.contains(".py"));
    }

    private static void addStrings(JsonArray src, JsonArray dst, Set<String> seen) {
        if (src == null) return;
        for (JsonElement e : src) {
            if (e != null && e.isJsonPrimitive()) {
                String s = e.getAsString();
                if (seen.add(s)) dst.add(new JsonPrimitive(s));
            }
        }
    }

    private static JsonArray array(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    private static String string(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        if (e == null || e.isJsonNull()) return null;
        if (e.isJsonPrimitive()) return e.getAsString().trim();
        return e.toString();
    }
}
