package com.github.tvbox.osc.finder;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.SourceBean;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * 配置「只读」解析器。
 *
 * <p><b>为什么不直接调 {@code ApiConfig.parseJson()}？</b>
 * 因为那个方法有全局副作用——它会 {@code resetConfigData()} 清空 spider 缓存、
 * 清空 {@code sourceBeanList}、写 {@code LIVE_GROUP_LIST}、还会
 * {@code setSourceBean()} 改掉首页源。探针要是调了它，
 * 用户正在看的视频就断了。
 *
 * <p>所以这里另起炉灶：只做「解密 → 解析 → 提取 sites / urls」，
 * 一个全局状态都不碰。
 *
 * <p>解密复用 {@link ApiConfig#FindResult(String, String)}——它是 public static
 * 的纯函数，无副作用。
 */
public final class ConfigSniffer {

    private ConfigSniffer() {
    }

    /** 解析结果。 */
    public static final class Sniffed {
        /** 配置里的全部站点。 */
        public final List<SourceBean> sites = new ArrayList<>();
        /** 多仓线路（urls / storeHouse 合并去重）。 */
        public final List<Line> lines = new ArrayList<>();
        /** 内容是否被加密。 */
        public boolean encrypted;

        /** 是否是多仓配置。 */
        public boolean isMultiRepo() {
            return !lines.isEmpty();
        }

        /** 可用于验证的采集站（type 0/1/4）。 */
        public List<SourceBean> cmsSites() {
            List<SourceBean> out = new ArrayList<>();
            for (SourceBean sb : sites) {
                if (sb != null && isCmsType(sb.getType())) {
                    out.add(sb);
                }
            }
            return out;
        }

        /** 非采集站（type 3 爬虫等）的数量——只计数，绝不加载。 */
        public int spiderSiteCount() {
            int n = 0;
            for (SourceBean sb : sites) {
                if (sb != null && !isCmsType(sb.getType())) {
                    n++;
                }
            }
            return n;
        }
    }

    /** 一条多仓线路。 */
    public static final class Line {
        public final String name;
        public final String url;

        public Line(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }

    /**
     * A 支持的采集站类型。
     *
     * <p>注意：除了常见的 0(xml)/1(json)，A 还支持 <b>type 4</b>
     * （带 ext 参数的采集站，见 {@code SourceViewModel} 的 type==4 分支）。
     * 只认 0/1 会误杀一批可用源。
     */
    public static boolean isCmsType(int type) {
        return type == 0 || type == 1 || type == 4;
    }

    /** 解析配置文本。失败返回 null。 */
    public static Sniffed parse(String rawJson, String configKey) {
        if (rawJson == null || rawJson.trim().isEmpty()) {
            return null;
        }
        String json;
        try {
            // 复用 A 的解密逻辑（纯函数）
            json = ApiConfig.FindResult(rawJson, configKey);
        } catch (Throwable th) {
            return null;
        }
        if (json == null) {
            return null;
        }
        json = json.trim();
        // 去掉 BOM 和 markdown 代码围栏
        json = stripFence(json);
        if (!json.startsWith("{") && !json.startsWith("[")) {
            return null;
        }
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                return null;
            }
            root = parsed.getAsJsonObject();
        } catch (Throwable th) {
            return null;
        }

        Sniffed out = new Sniffed();
        out.encrypted = !rawJson.trim().startsWith("{");

        // 站点
        if (root.has("sites") && root.get("sites").isJsonArray()) {
            JsonArray arr = root.getAsJsonArray("sites");
            for (JsonElement opt : arr) {
                if (!opt.isJsonObject()) {
                    continue;
                }
                JsonObject obj = opt.getAsJsonObject();
                if (!obj.has("key") || !obj.has("type") || !obj.has("api")) {
                    continue;
                }
                SourceBean sb = new SourceBean();
                String key = obj.get("key").getAsString().trim();
                sb.setKey(key);
                sb.setName(obj.has("name") ? obj.get("name").getAsString().trim() : key);
                sb.setType(obj.get("type").getAsInt());
                sb.setApi(obj.get("api").getAsString().trim());
                sb.setExt(obj.has("ext") ? obj.get("ext").toString() : "");
                out.sites.add(sb);
            }
        }

        // 多仓线路：urls 优先，其后 storeHouse
        collectLines(root, "urls", out);
        collectLines(root, "storeHouse", out);
        return out;
    }

    private static void collectLines(JsonObject root, String key, Sniffed out) {
        if (!root.has(key) || !root.get(key).isJsonArray()) {
            return;
        }
        JsonArray arr = root.getAsJsonArray(key);
        for (JsonElement opt : arr) {
            String name = "";
            String url = "";
            if (opt.isJsonObject()) {
                JsonObject obj = opt.getAsJsonObject();
                if (obj.has("name")) {
                    name = obj.get("name").getAsString();
                }
                if (obj.has("url")) {
                    url = obj.get("url").getAsString();
                } else if (obj.has("api")) {
                    url = obj.get("api").getAsString();
                }
            } else if (opt.isJsonPrimitive()) {
                url = opt.getAsString();
            }
            if (url == null || url.trim().isEmpty()) {
                continue;
            }
            url = url.trim();
            boolean dup = false;
            for (Line exist : out.lines) {
                if (exist.url.equals(url)) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                out.lines.add(new Line(name == null ? "" : name.trim(), url));
            }
        }
    }

    /** 去掉 markdown 代码围栏和 BOM。 */
    private static String stripFence(String text) {
        String s = text;
        if (s.length() > 0 && s.charAt(0) == '\uFEFF') {
            s = s.substring(1);
        }
        s = s.trim();
        if (s.startsWith("```")) {
            int idx = s.indexOf('\n');
            if (idx > 0) {
                s = s.substring(idx + 1);
            }
            int end = s.lastIndexOf("```");
            if (end > 0) {
                s = s.substring(0, end);
            }
            s = s.trim();
        }
        return s;
    }
}
