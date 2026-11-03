package com.github.tvbox.osc.util;

import com.github.tvbox.osc.bean.SourceBean;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * 站点按「后端」去重。
 *
 * <p>合并源里同一个采集站常以不同名字出现多次（http/https、结尾斜杠等差异），
 * 搜索/换源时重复请求同一后端只会得到重复结果。归组规则：
 * <ul>
 *     <li>直连站（type 0/1）：地址归一后归组——忽略协议、大小写、结尾斜杠，
 *         常见的 {@code /at/xml} 纯格式后缀也视为同一后端</li>
 *     <li>爬虫站（type 3）：真实后端由 ext 决定，按 api+ext+jar 归组，
 *         ext 不同的仍是不同网站，不去重</li>
 * </ul>
 * 每组保留第一个出现的站点，顺序不变。
 */
public final class SourceDedupe {

    private SourceDedupe() {
    }

    public static List<SourceBean> dedupe(List<SourceBean> sources) {
        ArrayList<SourceBean> out = new ArrayList<>();
        if (sources == null || sources.isEmpty()) {
            return out;
        }
        HashSet<String> seen = new HashSet<>();
        for (SourceBean bean : sources) {
            String sig = backendSig(bean);
            if (sig != null && !seen.add(sig)) {
                continue;
            }
            out.add(bean);
        }
        return out;
    }

    static String backendSig(SourceBean bean) {
        if (bean == null) {
            return null;
        }
        if (bean.getType() == 3) {
            return "3|" + safe(bean.getApi()) + "|" + safe(bean.getExt()) + "|" + safe(bean.getJar());
        }
        String api = safe(bean.getApi()).trim().toLowerCase();
        api = api.replaceFirst("^https?://", "");
        api = stripTrailingSlashes(api);
        if (api.endsWith("/at/xml")) {
            api = stripTrailingSlashes(api.substring(0, api.length() - "/at/xml".length()));
        }
        return bean.getType() + "|" + api;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String stripTrailingSlashes(String value) {
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
