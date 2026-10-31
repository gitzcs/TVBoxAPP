package com.github.tvbox.osc.update;

import androidx.annotation.NonNull;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.util.ArrayList;
import java.util.List;

/**
 * update.json 的解析模型。
 *
 * <p>托管方（GitHub Releases、任何静态 HTTP 服务都行）提供一份这样的 JSON：
 *
 * <pre>
 * {
 *   "versionCode": 2,
 *   "versionName": "1.1.0",
 *   "changelog": "修复了什么、新增了什么",
 *   "sha256": "apk 文件的 SHA-256（小写十六进制；可省略）",
 *   "apk": [
 *     "https://github.com/you/repo/releases/download/v1.1.0/app.apk",
 *     "https://gh-proxy.com/https://github.com/you/repo/releases/download/v1.1.0/app.apk"
 *   ]
 * }
 * </pre>
 *
 * <p>解析失败返回 null，调用方按「没有更新」处理，绝不抛异常打断使用。
 */
public final class UpdateInfo {

    /** 远端声明的版本号；与包内 versionCode 数字比较。 */
    public int versionCode;
    public String versionName;
    public String changelog;
    /** APK 的 SHA-256（十六进制，大小写均可）；为空时跳过校验。 */
    public String sha256;
    /** APK 下载地址，按顺序尝试（建议第一个官方直连、后面跟镜像）。 */
    public List<String> apk;

    /** 解析 update.json；内容不完整（没有版本号或没有任何下载地址）返回 null。 */
    public static UpdateInfo parse(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            UpdateInfo info = new Gson().fromJson(json.trim(), UpdateInfo.class);
            if (info == null || info.versionCode <= 0
                    || info.apk == null || info.apk.isEmpty()) {
                return null;
            }
            List<String> urls = new ArrayList<>();
            for (String url : info.apk) {
                if (url != null && url.trim().startsWith("http")) {
                    urls.add(url.trim());
                }
            }
            if (urls.isEmpty()) {
                return null;
            }
            info.apk = urls;
            return info;
        } catch (JsonSyntaxException e) {
            return null;
        }
    }

    /** 远端版本是否比当前包新。 */
    public boolean isNewerThan(int currentVersionCode) {
        return versionCode > currentVersionCode;
    }

    @NonNull
    @Override
    public String toString() {
        return "v" + (versionName == null ? String.valueOf(versionCode) : versionName)
                + " (" + versionCode + ")";
    }
}
