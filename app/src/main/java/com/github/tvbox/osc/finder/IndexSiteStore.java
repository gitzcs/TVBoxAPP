package com.github.tvbox.osc.finder;

import android.content.Context;
import android.content.SharedPreferences;

import com.github.tvbox.osc.util.LOG;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 用户自定义搜索索引站的持久化。
 *
 * <p>未自定义时直接使用 assets 中的默认站点；用户修改后保存完整列表，
 * 让自动找源不再依赖编译期写死的域名。
 */
public final class IndexSiteStore {

    private static final String SP_NAME = "tvapp_index_sites";
    private static final String KEY_CONFIGURED = "configured";
    private static final String KEY_SITES = "sites";
    private static final Type LIST_TYPE =
            new TypeToken<List<SourceIndex.IndexSite>>() {
            }.getType();

    private static final List<String> DEFAULT_QUERIES =
            Collections.unmodifiableList(Arrays.asList(
                    "tvbox config in:name,description",
                    "tvbox 接口 in:name,description",
                    "tvbox json in:name,description",
                    "影视仓"));

    private final SharedPreferences sp;

    public IndexSiteStore(Context context) {
        sp = context.getApplicationContext()
                .getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
    }

    /** 把用户配置覆盖到指定索引；未自定义时保持 assets 默认值。 */
    public void apply(SourceIndex index) {
        if (index == null || !sp.getBoolean(KEY_CONFIGURED, false)) {
            return;
        }
        index.setIndexSites(decode(sp.getString(KEY_SITES, null), index.indexSites()));
    }

    /** 当前列表；未自定义时返回默认值的副本。 */
    public List<SourceIndex.IndexSite> load(List<SourceIndex.IndexSite> defaults) {
        if (!sp.getBoolean(KEY_CONFIGURED, false)) {
            return copies(defaults);
        }
        return decode(sp.getString(KEY_SITES, null), copies(defaults));
    }

    public void save(List<SourceIndex.IndexSite> sites) {
        List<SourceIndex.IndexSite> safe = sanitize(sites);
        sp.edit()
                .putBoolean(KEY_CONFIGURED, true)
                .putString(KEY_SITES, encode(safe))
                .apply();
    }

    public void reset() {
        sp.edit()
                .remove(KEY_CONFIGURED)
                .remove(KEY_SITES)
                .apply();
    }

    public static SourceIndex.IndexSite newSite() {
        return new SourceIndex.IndexSite("", "", new ArrayList<>(DEFAULT_QUERIES), true);
    }

    public static String summary(List<SourceIndex.IndexSite> sites) {
        int enabled = 0;
        String firstName = null;
        for (SourceIndex.IndexSite site : sites) {
            if (site != null && site.isEnabled()) {
                enabled++;
                if (firstName == null) {
                    firstName = site.getName();
                }
            }
        }
        if (enabled == 0) {
            return "已停用";
        }
        return enabled == 1 ? firstName : enabled + " 个启用";
    }

    static String encode(List<SourceIndex.IndexSite> sites) {
        return new Gson().toJson(sanitize(sites));
    }

    static List<SourceIndex.IndexSite> decode(String json,
                                              List<SourceIndex.IndexSite> fallback) {
        if (json == null || json.trim().isEmpty()) {
            return copies(fallback);
        }
        try {
            List<SourceIndex.IndexSite> parsed = new Gson().fromJson(json, LIST_TYPE);
            if (parsed == null) {
                return copies(fallback);
            }
            return sanitize(parsed);
        } catch (Exception e) {
            LOG.w("解析自定义索引站失败，使用默认站：" + e.getMessage());
            return copies(fallback);
        }
    }

    private static List<SourceIndex.IndexSite> sanitize(
            List<SourceIndex.IndexSite> input) {
        if (input == null) {
            return new ArrayList<>();
        }
        List<SourceIndex.IndexSite> out = new ArrayList<>();
        for (SourceIndex.IndexSite site : input) {
            if (site == null || site.getBaseUrl().isEmpty()) {
                continue;
            }
            out.add(site.copy());
        }
        return out;
    }

    private static List<SourceIndex.IndexSite> copies(
            List<SourceIndex.IndexSite> input) {
        if (input == null) {
            return new ArrayList<>();
        }
        List<SourceIndex.IndexSite> out = new ArrayList<>();
        for (SourceIndex.IndexSite site : input) {
            if (site != null) {
                out.add(site.copy());
            }
        }
        return out;
    }
}
