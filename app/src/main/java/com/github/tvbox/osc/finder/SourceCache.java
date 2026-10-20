package com.github.tvbox.osc.finder;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.github.tvbox.osc.util.LOG;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 验证结果持久化。
 *
 * <p>这是「GitHub 不通就先用本地，通了再换」的关键：
 * 验证通过的源写盘，下次冷启动可以 <b>零网络</b> 立刻用起来；
 * 同时记录「上次什么时候试的、成没成、连续失败几次」，
 * 供 {@link AutoSourceManager} 决定下次什么时候重试。
 *
 * <p>地址这类元信息放 SharedPreferences，条目正文写 {@code filesDir/finder/sources.json}。
 */
public final class SourceCache {

    private static final String SP_NAME = "tvapp_finder";
    private static final String KEY_SAVED_AT = "saved_at";
    private static final String KEY_LAST_ATTEMPT = "last_attempt_at";
    private static final String KEY_LAST_OK = "last_attempt_ok";
    private static final String KEY_LAST_ERROR = "last_error";
    private static final String KEY_FAIL_STREAK = "fail_streak";
    private static final String KEY_INCLUDE_GITHUB = "include_github";
    private static final String KEY_APPLIED_URL = "applied_url";
    private static final String DIR = "finder";
    private static final String FILE = "sources.json";

    private static final Type LIST_TYPE = new TypeToken<List<Finding>>() {
    }.getType();

    private final Context appContext;
    private final SharedPreferences sp;
    private final Gson gson = new Gson();

    /** 内存镜像：UI 每次刷新状态都会问「有哪些可用源」，不能每次都读盘。 */
    private volatile List<Finding> memory;

    public SourceCache(Context context) {
        this.appContext = context.getApplicationContext();
        this.sp = appContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
    }

    // ---------------- 条目 ----------------

    /** 读取上次保存的验证结果；没有或损坏时返回空列表。 */
    public synchronized List<Finding> load() {
        List<Finding> cached = memory;
        if (cached != null) {
            return cached;
        }
        List<Finding> loaded = readFromDisk();
        memory = loaded;
        return loaded;
    }

    private List<Finding> readFromDisk() {
        File file = file();
        if (!file.exists()) {
            return Collections.emptyList();
        }
        try {
            List<Finding> list = gson.fromJson(read(file), LIST_TYPE);
            if (list == null) {
                return Collections.emptyList();
            }
            List<Finding> out = new ArrayList<>(list.size());
            for (Finding f : list) {
                if (f != null && f.getUrl() != null) {
                    out.add(f);
                }
            }
            return Collections.unmodifiableList(out);
        } catch (Exception e) {
            LOG.e("读取源缓存失败，已丢弃", e);
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            return Collections.emptyList();
        }
    }

    public synchronized void save(List<Finding> findings) {
        List<Finding> safe = findings == null ? new ArrayList<Finding>() : findings;
        memory = Collections.unmodifiableList(new ArrayList<>(safe));
        File dir = new File(appContext.getFilesDir(), DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            LOG.e("无法创建源缓存目录");
            return;
        }
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file());
            out.write(gson.toJson(safe).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOG.e("保存源缓存失败", e);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
        sp.edit().putLong(KEY_SAVED_AT, System.currentTimeMillis()).apply();
    }

    /** 只保留可用条目。 */
    public List<Finding> usable() {
        List<Finding> out = new ArrayList<>();
        for (Finding f : load()) {
            if (f.isUsable()) {
                out.add(f);
            }
        }
        return SourceFinder.rank(out);
    }

    /**
     * 写入或更新单条验证结果（手动添加地址时用）。
     *
     * <p>同一个地址只保留一条，避免用户反复点「验证」把列表刷成一堆重复项。
     * 和批量刷新一样，这里只要求调用方传入「已经验证过」的结果；
     * 是否可用由 {@link Finding#isUsable()} 决定，不可用的也保留，
     * 便于界面上解释「这个地址试过了，但不行」。
     */
    public synchronized void upsert(Finding finding) {
        if (finding == null || finding.getUrl() == null || finding.getUrl().trim().isEmpty()) {
            return;
        }
        List<Finding> next = new ArrayList<>(load());
        boolean replaced = false;
        for (int i = 0; i < next.size(); i++) {
            Finding old = next.get(i);
            if (old != null && finding.getUrl().equals(old.getUrl())) {
                next.set(i, finding);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            next.add(finding);
        }
        save(next);
    }

    /** 删除一条缓存（按地址匹配）。 */
    public synchronized void remove(String url) {
        if (url == null) {
            return;
        }
        List<Finding> next = new ArrayList<>();
        for (Finding f : load()) {
            if (f != null && !url.equals(f.getUrl())) {
                next.add(f);
            }
        }
        save(next);
    }

    public boolean isEmpty() {
        return load().isEmpty();
    }

    public long savedAt() {
        return sp.getLong(KEY_SAVED_AT, 0L);
    }

    /** 缓存是否还在新鲜期内。 */
    public boolean isFresh(long ttlMs) {
        long at = savedAt();
        return at > 0 && System.currentTimeMillis() - at < ttlMs;
    }

    public void clear() {
        memory = null;
        sp.edit().clear().apply();
        //noinspection ResultOfMethodCallIgnored
        file().delete();
    }

    // ---------------- 尝试记录 ----------------

    /** 记录一次「找源/验证」尝试的结果。 */
    public void markAttempt(boolean ok, String error) {
        SharedPreferences.Editor editor = sp.edit()
                .putLong(KEY_LAST_ATTEMPT, System.currentTimeMillis())
                .putBoolean(KEY_LAST_OK, ok)
                .putString(KEY_LAST_ERROR, error);
        if (ok) {
            editor.putInt(KEY_FAIL_STREAK, 0);
        } else {
            editor.putInt(KEY_FAIL_STREAK, failStreak() + 1);
        }
        editor.apply();
    }

    public long lastAttemptAt() {
        return sp.getLong(KEY_LAST_ATTEMPT, 0L);
    }

    public boolean lastAttemptOk() {
        return sp.getBoolean(KEY_LAST_OK, false);
    }

    public String lastError() {
        return sp.getString(KEY_LAST_ERROR, null);
    }

    /** 连续失败次数（成功一次就清零）。 */
    public int failStreak() {
        return sp.getInt(KEY_FAIL_STREAK, 0);
    }

    /** 是否曾经尝试过。 */
    public boolean hasAttempted() {
        return lastAttemptAt() > 0;
    }

    // ---------------- 选项 ----------------

    /** 是否开启 GitHub 在线发现（用户可关，只留内置索引）。 */
    public boolean includeGithub() {
        return sp.getBoolean(KEY_INCLUDE_GITHUB, true);
    }

    public void setIncludeGithub(boolean include) {
        sp.edit().putBoolean(KEY_INCLUDE_GITHUB, include).apply();
    }

    /** 当前实际生效的配置地址，用于判断「已应用的源是否还在可用列表里」。 */
    public String appliedUrl() {
        return sp.getString(KEY_APPLIED_URL, null);
    }

    public void setAppliedUrl(String url) {
        sp.edit().putString(KEY_APPLIED_URL, url).apply();
    }

    // ---------------- 内部 ----------------

    private File file() {
        return new File(new File(appContext.getFilesDir(), DIR), FILE);
    }

    private static String read(File file) throws IOException {
        FileInputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }
}
