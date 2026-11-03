package com.github.tvbox.osc.update;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.github.catvod.net.OkHttp;
import com.github.tvbox.osc.finder.FinderHttp;
import com.github.tvbox.osc.finder.FinderResult;
import com.github.tvbox.osc.ui.dialog.UpdateProgressDialog;
import com.github.tvbox.osc.ui.dialog.UpdateUrlDialog;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.orhanobut.hawk.Hawk;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Formatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 应用内自动更新：检查、下载、校验、调起安装。
 *
 * <p><b>托管方式</b>：不需要自己的服务器。任何能提供 HTTPS GET 的地方都行，
 * 推荐 GitHub Releases（免费、无限流量）：仓库里放一份 {@code update.json}
 * （见 {@link UpdateInfo}），APK 挂在 Release 附件上。地址填到
 * 设置 → 检查更新（长按可修改）。
 *
 * <p><b>安全边界</b>：
 * <ul>
 *     <li>只走 HTTPS 之外还做 SHA-256 校验（update.json 里给值时）</li>
 *     <li>Android 覆盖安装要求签名一致，签名不同的包系统直接拒绝</li>
 *     <li>解析失败/网络失败一律按「没有更新」处理，绝不影响使用</li>
 * </ul>
 *
 * <p><b>时机</b>：首页启动后延迟静默检查（{@link #checkSilently}，24 小时节流，
 * 无更新零打扰）；设置页「检查更新」随时手动触发。
 */
public final class UpdateManager {

    /** 静默检查的最小间隔：24 小时。 */
    private static final long SILENT_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L;

    /**
     * 内置默认检查地址（逗号分隔，依次尝试）。
     *
     * <p>指向 gitzcs/TVBoxApp-release 仓库根目录的 update.json；
     * 镜像在前（国内可达性更好），官方 raw 兜底。用户在设置里自定义后以自定义为准，
     * 清空自定义则回到这份默认。
     */
    private static final String DEFAULT_UPDATE_URLS =
            "https://gh-proxy.com/https://raw.githubusercontent.com/gitzcs/TVBoxApp-release/main/update.json"
                    + ",https://raw.githubusercontent.com/gitzcs/TVBoxApp-release/main/update.json";

    /** 下载用的临时文件名（先写 .tmp 再改名，避免半截包被装）。 */
    private static final String APK_TMP = "update.apk.tmp";
    private static final String APK_FILE = "update.apk";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** 下载会话标记：新下载开始时递增，旧回调据此丢弃。 */
    private static final java.util.concurrent.atomic.AtomicInteger SESSION = new java.util.concurrent.atomic.AtomicInteger();

    private UpdateManager() {
    }

    /** 当前包的 versionCode；取不到按 0 处理（此时永远提示有新版本）。 */
    public static int currentVersionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    /** 静默检查：24 小时最多一次；没有更新或出错时完全不打扰。 */
    public static void checkSilently(Activity activity) {
        Hawk.put(HawkConfig.UPDATE_LAST_CHECK, System.currentTimeMillis());
        doCheck(activity, true);
    }

    /** 手动检查：结果无论如何都给提示。 */
    public static void checkManually(final Activity activity) {
        doCheck(activity, false);
    }

    /** 打开更新源地址编辑框；确定并保存后回调（回调仍在主线程）。 */
    public static void editUpdateUrl(Activity activity, final Runnable onSaved) {
        new UpdateUrlDialog(activity, Hawk.get(HawkConfig.UPDATE_URL, ""),
                new UpdateUrlDialog.OnSaveListener() {
                    @Override
                    public void onSave(String url) {
                        Hawk.put(HawkConfig.UPDATE_URL, url == null ? "" : url.trim());
                        if (onSaved != null) {
                            onSaved.run();
                        }
                    }
                }).show();
    }

    private static void doCheck(final Activity activity, final boolean silent) {
        Hawk.put(HawkConfig.UPDATE_LAST_CHECK, System.currentTimeMillis());
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                final UpdateInfo info = fetchUpdateInfo();
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (activity.isFinishing() || activity.isDestroyed()) {
                            return;
                        }
                        if (info == null) {
                            if (!silent) {
                                Toast.makeText(activity, "检查更新失败，请稍后再试", Toast.LENGTH_SHORT).show();
                            }
                            return;
                        }
                        if (!info.isNewerThan(currentVersionCode(activity))) {
                            if (!silent) {
                                Toast.makeText(activity, "已是最新版本", Toast.LENGTH_SHORT).show();
                            }
                            return;
                        }
                        showUpdateFound(activity, info);
                    }
                });
            }
        });
    }

    /**
     * 拉取并解析 update.json：设置里的地址优先；多个地址（英文逗号分隔）依次尝试，
     * 全部失败返回 null。跑在调度线程上。
     */
    private static UpdateInfo fetchUpdateInfo() {
        String config = Hawk.get(HawkConfig.UPDATE_URL, "");
        if (TextUtils.isEmpty(config.trim())) {
            config = DEFAULT_UPDATE_URLS;
        }
        for (String url : config.split(",")) {
            url = url.trim();
            if (url.isEmpty()) {
                continue;
            }
            FinderResult result = FinderHttp.get(url);
            UpdateInfo info = result.isOk() ? UpdateInfo.parse(result.getData()) : null;
            if (info != null) {
                return info;
            }
            LOG.w("检查更新地址失败，换下一个：" + url
                    + (result.isOk() ? "" : " - " + result.getMessage()));
        }
        return null;
    }

    /** 发现新版本的确认弹窗。 */
    private static void showUpdateFound(final Activity activity, final UpdateInfo info) {
        String tip = "发现新版本 " + info
                + "（当前 v" + currentVersionCode(activity) + "）";
        if (!TextUtils.isEmpty(info.changelog)) {
            tip += "\n\n" + info.changelog;
        }
        tip += "\n\n下载完成后将自动调起安装。";
        new com.github.tvbox.osc.ui.dialog.TipDialog(activity, tip, "立即更新", "暂不",
                new com.github.tvbox.osc.ui.dialog.TipDialog.OnListener() {
                    @Override
                    public void left() {
                        startDownload(activity, info);
                    }

                    @Override
                    public void right() {
                    }

                    @Override
                    public void cancel() {
                    }
                }).show();
    }

    /** 弹出进度框并开始下载，下载完校验后调起系统安装。 */
    private static void startDownload(final Activity activity, final UpdateInfo info) {
        final UpdateProgressDialog dialog = new UpdateProgressDialog(activity,
                new UpdateProgressDialog.OnCancelListener() {
                    @Override
                    public void onCancel() {
                        SESSION.incrementAndGet();
                    }
                });
        dialog.show();
        final int session = SESSION.incrementAndGet();
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                final File apk = downloadApk(activity, info, session, dialog);
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (SESSION.get() != session) {
                            return; // 用户已取消
                        }
                        if (activity.isFinishing() || activity.isDestroyed()) {
                            return;
                        }
                        if (apk == null) {
                            dialog.dismiss();
                            Toast.makeText(activity, "下载失败，请检查网络后重试", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        dialog.dismiss();
                        installApk(activity, apk);
                    }
                });
            }
        });
    }

    /** 按顺序尝试所有下载地址；全部失败返回 null。进度回调已切到主线程。 */
    private static File downloadApk(Context context, UpdateInfo info, int session,
                                    final UpdateProgressDialog dialog) {
        File out = new File(context.getCacheDir(), APK_FILE);
        for (String url : info.apk) {
            if (SESSION.get() != session) {
                return null;
            }
            publishProgress(dialog, 0, 0, 0);
            File file = downloadOne(context, url, out, session, dialog);
            if (file != null && verifySha256(file, info.sha256)) {
                return file;
            }
            if (file != null) {
                LOG.w("更新包校验失败，尝试下一个地址：" + url);
                file.delete();
            }
        }
        return null;
    }

    private static File downloadOne(Context context, String url, File out,
                                    int session, final UpdateProgressDialog dialog) {
        File tmp = new File(context.getCacheDir(), APK_TMP);
        InputStream in = null;
        FileOutputStream fos = null;
        Response response = null;
        try {
            OkHttpClient client = OkHttp.client(30_000L);
            response = client.newCall(new Request.Builder().url(url).tag("tvbox-update").build())
                    .execute();
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }
            long total = response.body().contentLength();
            in = response.body().byteStream();
            fos = new FileOutputStream(tmp);
            byte[] buf = new byte[16384];
            long done = 0;
            long lastPost = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                if (SESSION.get() != session) {
                    return null;
                }
                fos.write(buf, 0, n);
                done += n;
                // 进度按 100ms 节流，避免刷爆主线程
                if (System.currentTimeMillis() - lastPost > 100) {
                    lastPost = System.currentTimeMillis();
                    publishProgress(dialog, done, total, 100);
                }
            }
            fos.flush();
            fos.close();
            fos = null;
            publishProgress(dialog, done, done, 100);
            if (out.exists() && !out.delete()) {
                return null;
            }
            return tmp.renameTo(out) ? out : null;
        } catch (Throwable t) {
            LOG.w("更新包下载失败：" + url + " - " + t.getMessage());
            return null;
        } finally {
            closeQuietly(in);
            closeQuietly(fos);
            if (response != null) {
                response.close();
            }
        }
    }

    private static void publishProgress(final UpdateProgressDialog dialog,
                                        final long done, final long total, final long percentRaw) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                int percent = total > 0 ? (int) (done * 100 / total)
                        : (int) Math.min(99, percentRaw);
                dialog.updateProgress(percent, done, total);
            }
        });
    }

    /** 校验文件 SHA-256；期望值为空时跳过校验直接通过。 */
    static boolean verifySha256(File file, String expected) {
        if (TextUtils.isEmpty(expected)) {
            return true;
        }
        FileInputStream in = null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            in = new FileInputStream(file);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) {
                digest.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            Formatter formatter = new Formatter(sb);
            for (byte b : digest.digest()) {
                formatter.format("%02x", b);
            }
            formatter.close();
            return sb.toString().equalsIgnoreCase(expected.trim());
        } catch (Throwable t) {
            LOG.w("更新包校验异常：" + t.getMessage());
            return false;
        } finally {
            closeQuietly(in);
        }
    }

    /** 调起系统安装器。API 24+ 走 FileProvider（file:// 在 Nougat 上直接崩）。 */
    private static void installApk(Activity activity, File apk) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Uri uri = FileProvider.getUriForFile(activity,
                        activity.getPackageName() + ".fileprovider", apk);
                intent.setDataAndType(uri, "application/vnd.android.package-archive");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                intent.setDataAndType(Uri.fromFile(apk), "application/vnd.android.package-archive");
            }
            activity.startActivity(intent);
        } catch (Throwable t) {
            LOG.e("调起安装失败", t);
            Toast.makeText(activity, "无法调起安装：" + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
