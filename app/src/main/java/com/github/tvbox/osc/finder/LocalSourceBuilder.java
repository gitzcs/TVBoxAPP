package com.github.tvbox.osc.finder;

import android.content.Context;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.util.LOG;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 下载每个可用配置 → 解密 → 提取 sites → 合并 → 写入本地文件。
 *
 * <p>只做文本层面的处理，不调用 {@code ApiConfig.parseJson()}（会清空当前配置），
 * 也不加载任何 jar。爬虫站会原样保留，等用户真正使用合并源时才由 App 加载。
 */
final class LocalSourceBuilder {

    /** 构建结果。 */
    static final class Outcome {
        final boolean ok;
        final String message;
        final LocalSourceMerger.Result result;

        Outcome(boolean ok, String message, LocalSourceMerger.Result result) {
            this.ok = ok;
            this.message = message;
            this.result = result;
        }
    }

    interface Progress {
        void onStep(String message);
    }

    private static final String PK = ";pk;";

    private final Context context;

    LocalSourceBuilder(Context context) {
        this.context = context.getApplicationContext();
    }

    Outcome build(List<Finding> usable, Progress progress) {
        List<LocalSourceMerger.Input> inputs = new ArrayList<>();
        List<Finding> list = usable == null ? new ArrayList<Finding>() : usable;
        int failed = 0;
        for (int i = 0; i < list.size(); i++) {
            Finding f = list.get(i);
            if (f == null || f.getUrl() == null || LocalSourceStore.isLocal(f.getUrl())) continue;
            if (progress != null) {
                progress.onStep("合并源：读取 " + (i + 1) + "/" + list.size() + " " + f.getName());
            }
            JsonObject root = load(f.getUrl().trim());
            if (root == null) {
                failed++;
                LOG.w("合并源：读取失败，跳过 " + f.getUrl());
                continue;
            }
            inputs.add(new LocalSourceMerger.Input(f.getName(), f.getUrl(), root));
        }
        LocalSourceMerger.Result result = LocalSourceMerger.merge(inputs);
        if (result.siteCount == 0) {
            return new Outcome(false, "没有读取到任何视频源"
                    + (failed > 0 ? "（" + failed + " 个配置读取失败）" : ""), result);
        }
        if (!LocalSourceStore.writeMerged(context, result.config.toString())) {
            return new Outcome(false, "写入本地文件失败", result);
        }
        String msg = "已合并 " + result.sourceCount + " 个配置、" + result.siteCount + " 个视频源"
                + (result.duplicateCount > 0 ? "，去掉重复 " + result.duplicateCount + " 个" : "")
                + (failed > 0 ? "，" + failed + " 个配置读取失败已跳过" : "");
        LOG.i(msg);
        return new Outcome(true, msg, result);
    }

    /** 读一个配置并解析成 JSON 对象；多仓壳子或失败时返回 null。 */
    private JsonObject load(String url) {
        String address = url;
        String key = null;
        int pk = url.indexOf(PK);
        if (pk > 0) {
            address = url.substring(0, pk);
            key = url.substring(pk + PK.length());
        }
        String raw;
        if (address.startsWith("assets://")) {
            raw = readAsset(address.substring("assets://".length()));
        } else if (address.startsWith("http")) {
            FinderResult r = FinderHttp.get(address, null, FinderHttp.DEFAULT_TIMEOUT_SECONDS);
            raw = r.isOk() ? r.getData() : null;
        } else {
            return null;
        }
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            String json = ApiConfig.FindResult(raw, key);
            if (json == null) return null;
            json = ConfigSniffer.stripFence(json.trim());
            if (address.startsWith("http")) {
                json = LocalSourceMerger.resolveRelativePaths(json, address);
            }
            JsonElement el = JsonParser.parseString(json);
            if (!el.isJsonObject()) return null;
            JsonObject root = el.getAsJsonObject();
            return root.has("sites") && root.get("sites").isJsonArray() ? root : null;
        } catch (Throwable th) {
            return null;
        }
    }

    private String readAsset(String path) {
        InputStream in = null;
        try {
            in = context.getAssets().open(path);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), "UTF-8");
        } catch (Throwable th) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
