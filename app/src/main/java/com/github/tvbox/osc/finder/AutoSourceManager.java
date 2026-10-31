package com.github.tvbox.osc.finder;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.github.tvbox.osc.bean.ConfigBean;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.util.ConfigManager;
import com.github.tvbox.osc.util.LOG;

import org.greenrobot.eventbus.EventBus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 「自动找源」的调度中枢：<b>启动即用本地，后台定时刷新，直到通了为止</b>。
 *
 * <p>时序（对应需求「每次打开 app 时查找/验证/保存；GitHub 不通就先用本地，
 * 后台定时再试」）：
 * <ol>
 *     <li>{@link #start()} 被 {@code App.onCreate} 调用 → 读本地缓存，
 *         立刻把可用源暴露出去（<b>零网络</b>，冷启动不卡）</li>
 *     <li>按下面的策略把一次「发现 + 验证 + 保存」排进后台线程</li>
 * </ol>
 *
 * <p>调度策略（可用 {@code SourceCache} 里的记录跨进程重启生效）：
 * <table border="1">
 *     <tr><th>情况</th><th>下次刷新</th></tr>
 *     <tr><td>还没缓存 / 从没试过</td><td>立即</td></tr>
 *     <tr><td>上次成功</td><td>1 小时后</td></tr>
 *     <tr><td>上次失败（GitHub 不通、网络断）</td><td>15 分钟后再试，直到成功</td></tr>
 *     <tr><td>缓存太旧（&gt;24h）</td><td>不再等待，立即刷新</td></tr>
 * </table>
 *
 * <p>刷新失败时<b>不会清掉旧缓存</b>——这是「不通时先用本地」的关键，
 * 否则一次网络抖动就会让 App 变成没有任何源可用。
 */
public final class AutoSourceManager {

    /** 正常刷新间隔：1 小时。 */
    public static final long REFRESH_INTERVAL_MS = 60L * 60L * 1000L;

    /** 失败后的重试间隔：15 分钟（直到通了为止）。 */
    public static final long RETRY_INTERVAL_MS = 15L * 60L * 1000L;

    /** 缓存超过这个时长就不等间隔了，直接刷新。 */
    public static final long STALE_INTERVAL_MS = 24L * 60L * 60L * 1000L;

    /** 一个源验证通过后，多久可以「不重新验证也先用着」。 */
    public static final long TRUST_INTERVAL_MS = 6L * 60L * 60L * 1000L;

    private static volatile AutoSourceManager sInstance;

    /** 对外状态快照（不可变）。 */
    public static final class State {
        private final boolean refreshing;
        private final String stage;
        private final int usableCount;
        private final long lastCheckedAt;
        private final long lastAttemptAt;
        private final boolean lastAttemptOk;
        private final boolean attempted;
        private final int failStreak;
        private final String lastError;

        State(boolean refreshing, String stage, int usableCount, long lastCheckedAt,
              long lastAttemptAt, boolean lastAttemptOk, boolean attempted,
              int failStreak, String lastError) {
            this.refreshing = refreshing;
            this.stage = stage;
            this.usableCount = usableCount;
            this.lastCheckedAt = lastCheckedAt;
            this.lastAttemptAt = lastAttemptAt;
            this.lastAttemptOk = lastAttemptOk;
            this.attempted = attempted;
            this.failStreak = failStreak;
            this.lastError = lastError;
        }

        public boolean isRefreshing() {
            return refreshing;
        }

        /** 当前阶段描述（搜索中 / 验证 3/12…），未在刷新时为 null。 */
        public String getStage() {
            return stage;
        }

        public int getUsableCount() {
            return usableCount;
        }

        /** 上次「发现 + 验证」完成的时间。 */
        public long getLastCheckedAt() {
            return lastCheckedAt;
        }

        public long getLastAttemptAt() {
            return lastAttemptAt;
        }

        public boolean isLastAttemptOk() {
            return lastAttemptOk;
        }

        public boolean hasAttempted() {
            return attempted;
        }

        public int getFailStreak() {
            return failStreak;
        }

        public String getLastError() {
            return lastError;
        }

        /** 一句话状态，可直接丢给 UI。 */
        public String describe() {
            if (refreshing || stage != null) {
                return stage == null ? "正在自动找源…" : stage;
            }
            if (usableCount <= 0) {
                if (!attempted) {
                    return "还没有可用源，正在准备自动找源…";
                }
                return "暂时没有可用源" + (lastError == null ? "" : "：" + lastError);
            }
            StringBuilder sb = new StringBuilder("本地已保存 ").append(usableCount).append(" 个可用源");
            if (lastCheckedAt > 0) {
                sb.append("，上次验证 ").append(ago(lastCheckedAt));
            }
            if (attempted && !lastAttemptOk) {
                sb.append("；上次联网刷新失败，正在后台重试")
                        .append(lastError == null ? "" : "（" + lastError + "）");
            }
            return sb.toString();
        }

        private static String ago(long at) {
            long ms = System.currentTimeMillis() - at;
            if (ms < 60_000L) {
                return "刚刚";
            }
            if (ms < 3600_000L) {
                return (ms / 60_000L) + " 分钟前";
            }
            if (ms < 24L * 3600_000L) {
                return (ms / 3600_000L) + " 小时前";
            }
            return (ms / (24L * 3600_000L)) + " 天前";
        }
    }

    /** 状态变化回调（主线程）。 */
    public interface Listener {
        void onState(State state);
    }

    private final Context appContext;
    private final SourceCache cache;
    private final SourceFinder finder;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile ScheduledExecutorService scheduler;
    private volatile boolean refreshing;
    /**
     * 是否已经排过调度任务。
     *
     * <p>{@link #start()} 会被 {@code App.onCreate} 和 {@code FinderActivity.onResume} 各调一次；
     * 首次安装时缓存为空，{@link #nextDelayMs()} 两次都返回 0，于是会排出两轮完整的
     * 发现流程——每轮都要扫 GitHub 仓库、发上百个请求，白烧一次未认证 API 配额。
     * 所以这里做幂等保护。
     */
    private volatile boolean started;
    private volatile String stage;
    private volatile SourceFinder.Report lastReport;
    private volatile boolean pendingRefresh;

    private AutoSourceManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.cache = new SourceCache(appContext);
        this.finder = new SourceFinder(appContext);
    }

    public static AutoSourceManager get(Context context) {
        if (sInstance == null) {
            synchronized (AutoSourceManager.class) {
                if (sInstance == null) {
                    sInstance = new AutoSourceManager(context);
                }
            }
        }
        return sInstance;
    }

    // ---------------- 生命周期 ----------------

    /**
     * 由 {@code App.onCreate} 调用：立刻暴露本地缓存，并按策略排一次后台刷新。
     *
     * <p>幂等：重复调用只会刷新一次 UI 状态，不会重复排刷新任务。
     */
    public void start() {
        List<Finding> cached = cache.usable();
        syncConfigs(cache.load());
        LOG.i("自动找源启动：本地可用源 " + cached.size() + " 个"
                + (cache.hasAttempted()
                ? ("，上次尝试 " + (cache.lastAttemptOk() ? "成功" : "失败")
                + "，连续失败 " + cache.failStreak() + " 次") : "，从未尝试"));
        notifyState();
        synchronized (this) {
            if (started) {
                LOG.i("自动找源已在调度中，跳过重复启动");
                return;
            }
            started = true;
        }
        ensureScheduler();
        long delay = nextDelayMs();
        LOG.i("下次自动找源：" + (delay <= 0 ? "立即" : (delay / 1000) + "s 后"));
        scheduleRefresh(delay);
        // 立刻要联网刷新时不必抢跑（刷新成功后本来就会合并）；只有还要等很久、
        // 而合并源又不存在时，才先用缓存里的可用源生成一份
        if (delay > 0 && !cached.isEmpty() && !hasMergedSource()) {
            scheduler.execute(new Runnable() {
                @Override
                public void run() {
                    autoMergeToLocal();
                }
            });
        }
    }

    public void stop() {
        ScheduledExecutorService s = scheduler;
        if (s != null) {
            s.shutdownNow();
            scheduler = null;
        }
        synchronized (this) {
            started = false;
        }
    }

    // ---------------- 读取 ----------------

    /** 本地缓存的可用源（已排序，best 打头）；零网络。 */
    public List<Finding> usableCached() {
        List<Finding> cached = cache.usable();
        if (cached.isEmpty() && lastReport != null) {
            return lastReport.usable();
        }
        return cached;
    }

    /** 全部缓存的验证结果（含不可用）。 */
    public List<Finding> allCached() {
        return cache.load();
    }

    /** 最近一次完整的发现结果；还没跑过时为 null。 */
    public SourceFinder.Report lastReport() {
        return lastReport;
    }

    public SourceCache cache() {
        return cache;
    }

    public SourceIndex index() {
        return finder.index();
    }

    public State state() {
        List<Finding> usable = cache.usable();
        return new State(refreshing, stage, usable.size(), cache.savedAt(),
                cache.lastAttemptAt(), cache.lastAttemptOk(), cache.hasAttempted(),
                cache.failStreak(), cache.lastError());
    }

    // ---------------- 刷新 ----------------

    /** 后台刷新（非阻塞）。 */
    public void refreshAsync(boolean includeGithub) {
        cache.setIncludeGithub(includeGithub);
        synchronized (this) {
            if (refreshing) {
                pendingRefresh = true;
                return;
            }
        }
        ensureScheduler();
        scheduleRefresh(0L);
    }

    /** 同步刷新（阻塞，自己找后台线程调用）。 */
    public SourceFinder.Report refreshSync(boolean includeGithub) {
        cache.setIncludeGithub(includeGithub);
        return doRefresh(includeGithub);
    }

    /**
     * 手动验证一个地址，通过就写入本地缓存（不影响当前配置）。
     *
     * <p>对应界面上的「验证并保存」：用户从别处拿到一个配置地址，
     * 不想等后台定时任务，也不想先启用它，只想确认它能不能用并留档。
     * 验证跑在调度线程上，回调切回主线程。
     */
    public void verifyAndSaveAsync(final String url, final String name,
                                   final VerifyCallback callback) {
        ensureScheduler();
        scheduler.execute(new Runnable() {
            @Override
            public void run() {
                final Finding finding = finder.verify(url, name, Finding.ORIGIN_MANUAL);
                if (finding.isUsable()) {
                    cache.upsert(finding);
                    syncConfigs(cache.load());
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (callback != null) {
                            callback.onDone(finding, finding.isUsable());
                        }
                    }
                });
                notifyState();
            }
        });
    }

    /** 手动验证的回调（主线程）。 */
    public interface VerifyCallback {
        void onDone(Finding finding, boolean saved);
    }

    /** 从本地缓存删除一条（不处理当前正在使用的配置）。 */
    public void removeCached(String url) {
        cache.remove(url);
        syncConfigs(cache.load());
        notifyState();
    }

    // ---------------- 本地合并源 ----------------

    /** 合并完成回调（主线程）。 */
    public interface MergeCallback {
        void onDone(boolean ok, String message, ConfigBean config, int spiderSiteCount);
    }

    /** 本地合并源文件是否已生成。 */
    public boolean hasMergedSource() {
        return LocalSourceStore.mergedFile(appContext).isFile();
    }

    /**
     * 把所有可用源里的视频源合并成一个本地源，并写入配置列表（不切换当前配置）。
     * 跑在调度线程上，和自动找源串行，不会读到写了一半的缓存。
     */
    public void mergeToLocalAsync(final MergeCallback callback) {
        ensureScheduler();
        scheduler.execute(new Runnable() {
            @Override
            public void run() {
                final LocalSourceBuilder.Outcome outcome = buildMerged();
                final ConfigBean bean = outcome.ok ? ensureMergedConfig() : null;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (callback != null) {
                            callback.onDone(outcome.ok, outcome.message, bean,
                                    outcome.result == null ? 0 : outcome.result.spiderSiteCount);
                        }
                    }
                });
            }
        });
    }

    private LocalSourceBuilder.Outcome buildMerged() {
        stage = "正在合并视频源…";
        notifyState();
        try {
            return new LocalSourceBuilder(appContext).build(cache.usable(),
                    new LocalSourceBuilder.Progress() {
                        @Override
                        public void onStep(String message) {
                            stage = message;
                            notifyState();
                        }
                    });
        } catch (Throwable t) {
            LOG.e("合并视频源异常", t);
            return new LocalSourceBuilder.Outcome(false,
                    "合并失败：" + t.getClass().getSimpleName(), null);
        } finally {
            stage = null;
            notifyState();
        }
    }

    /** 配置列表里没有合并源就在最前面加一条；有就保持用户的启停状态不变，并确保它排在第一位。 */
    private ConfigBean ensureMergedConfig() {
        ConfigBean bean = ConfigManager.findConfigByUrl(LocalSourceStore.MERGED_URL);
        List<ConfigBean> configs = ConfigManager.getConfigs();
        if (bean == null) {
            bean = new ConfigBean(LocalSourceStore.MERGED_NAME, LocalSourceStore.MERGED_URL);
            configs.add(0, bean);
            ConfigManager.saveConfigs(configs);
            return bean;
        }
        for (int i = 0; i < configs.size(); i++) {
            ConfigBean exist = configs.get(i);
            if (exist != null && bean.id.equals(exist.id)) {
                if (i > 0) {
                    configs.remove(i);
                    configs.add(0, exist);
                    ConfigManager.saveConfigs(configs);
                }
                break;
            }
        }
        return bean;
    }

    /**
     * 自动合并：只要缓存里有可用源，就把它们合并成本地合并源并写入配置列表。
     *
     * <p>首次也会自动生成（不要求用户先点过「合并到本地」）；已有合并源时顺带重建，
     * 让它跟上最新的可用源。不切换当前配置、不发首页刷新事件：用户可能正在播放，
     * 新内容在下次加载配置时生效。只能在调度线程上调用（和找源、手动合并串行）。
     */
    private void autoMergeToLocal() {
        if (cache.usable().isEmpty()) {
            return;
        }
        boolean existed = hasMergedSource();
        LocalSourceBuilder.Outcome outcome = buildMerged();
        if (!outcome.ok) {
            LOG.w((existed ? "自动重建合并源失败，保留旧文件：" : "自动合并失败：") + outcome.message);
            return;
        }
        ConfigBean bean = ensureMergedConfig();
        promoteMergedConfig(bean);
        LOG.i("自动合并完成：" + outcome.message);
    }

    /**
     * 把本地合并源设为当前主页配置（相当于把它作为默认源）。
     *
     * <p>已经是当前配置时不做任何事，避免每次刷新都让首页重载；
     * 用户在配置管理里把它停用时也尊重用户的选择，不再自动切过去。
     */
    private void promoteMergedConfig(ConfigBean bean) {
        if (bean == null || !bean.enabled) {
            return;
        }
        String oldHomeId = ConfigManager.getHomeConfigId();
        if (bean.id.equals(oldHomeId)) {
            return;
        }
        ConfigManager.setHomeConfigId(bean.id);
        LOG.i("已把「" + LocalSourceStore.MERGED_NAME + "」设为当前配置");
        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_HOME_SOURCE_CHANGE));
    }

    /** 缓存文件在设备上的路径，设置页/找源页用来告诉用户「存哪了」。 */
    public String cacheFilePath() {
        return appContext.getFilesDir() + "/finder/sources.json";
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    // ---------------- 内部：调度 ----------------

    private void ensureScheduler() {
        if (scheduler == null) {
            synchronized (this) {
                if (scheduler == null) {
                    scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "auto-source");
                            t.setDaemon(true);
                            return t;
                        }
                    });
                }
            }
        }
    }

    private void scheduleRefresh(long delayMs) {
        ScheduledExecutorService s = scheduler;
        if (s == null) {
            return;
        }
        s.schedule(new Runnable() {
            @Override
            public void run() {
                runCycle();
            }
        }, Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
    }

    /** 一次刷新 + 决定下次什么时候再来。 */
    private void runCycle() {
        boolean includeGithub = cache.includeGithub();
        try {
            doRefresh(includeGithub);
            if (cache.lastAttemptOk()) {
                autoMergeToLocal();
            }
        } catch (Throwable t) {
            LOG.e("自动找源异常", t);
            cache.markAttempt(false, t.getClass().getSimpleName() + ": " + t.getMessage());
            notifyState();
        }
        if (pendingRefresh) {
            pendingRefresh = false;
            scheduleRefresh(0L);
            return;
        }
        scheduleRefresh(nextDelayMs());
    }

    /**
     * 下次刷新延迟。
     *
     * <p>失败要「快速重试直到通了」，成功就回到正常间隔；
     * 缓存太旧则不再等待。
     */
    long nextDelayMs() {
        long now = System.currentTimeMillis();
        if (cache.load().isEmpty()) {
            return 0L;
        }
        if (!cache.hasAttempted()) {
            return 0L;
        }
        long savedAt = cache.savedAt();
        if (savedAt > 0 && now - savedAt > STALE_INTERVAL_MS) {
            return 0L;
        }
        long base = cache.lastAttemptOk() ? REFRESH_INTERVAL_MS : RETRY_INTERVAL_MS;
        long due = cache.lastAttemptAt() + base;
        return Math.max(0L, due - now);
    }

    // ---------------- 内部：执行 ----------------

    private SourceFinder.Report doRefresh(boolean includeGithub) {
        synchronized (this) {
            if (refreshing) {
                return lastReport;
            }
            refreshing = true;
        }
        notifyState();
        try {
            SourceFinder.Report report = finder.discover(includeGithub, new SourceFinder.Listener() {
                @Override
                public void onStage(String message) {
                    stage = message;
                    notifyState();
                }

                @Override
                public void onFound(Finding finding, int done, int total) {
                    stage = "已验证 " + done + "/" + total + "：" + finding.getName()
                            + (finding.isUsable() ? " ✓" : " ✗");
                    if (finding.isUsable()) {
                        cache.upsert(finding);
                        syncConfigs(cache.load());
                    }
                    notifyState();
                }
            });
            lastReport = report;
            persist(report);
            return report;
        } finally {
            refreshing = false;
            stage = null;
            notifyState();
        }
    }

    /**
     * 落地验证结果。
     *
     * <p>只有「这次真的验证出可用源」才覆盖旧缓存；一个都没验出来时保留旧的，
     * 并把失败原因记下来交给重试策略。
     */
    private void persist(SourceFinder.Report report) {
        List<Finding> usable = report.usable();
        if (!usable.isEmpty()) {
            cache.save(report.all());
            syncConfigs(report.all());
            cache.markAttempt(true, null);
            LOG.i("源缓存已更新：" + usable.size() + " 个可用源");
            return;
        }
        String why = report.note() == null ? "本次验证没有可用的源" : report.note();
        if (report.all().isEmpty()) {
            why = "没有可验证的候选源";
        }
        cache.markAttempt(false, why);
        LOG.w("本次没有验证出可用源，保留旧缓存：" + why);
    }

    /**
     * 把找源结果同步到用户配置列表。
     *
     * <p>自动验证过的不可用地址会从配置中移除；可用地址自动新增或复用。
     * 当前主页配置仍在列表中时保持不变，否则才使用排名第一的可用源。
     */
    private void syncConfigs(List<Finding> findings) {
        purgeRemovedBuiltInConfigs();
        if (findings == null || findings.isEmpty()) {
            return;
        }
        Map<String, Finding> checked = new LinkedHashMap<>();
        for (Finding finding : findings) {
            if (finding == null || finding.getUrl() == null) continue;
            String url = finding.getUrl().trim();
            if (!url.isEmpty()) checked.put(url, finding);
        }

        List<Finding> usable = new ArrayList<>();
        for (Finding finding : SourceFinder.rank(new ArrayList<>(checked.values()))) {
            if (finding.isUsable()) usable.add(finding);
        }
        if (usable.isEmpty()) {
            return;
        }

        Set<String> usableUrls = new HashSet<>();
        for (Finding finding : usable) {
            usableUrls.add(finding.getUrl().trim());
        }

        List<ConfigBean> configs = ConfigManager.getConfigs();
        boolean configsChanged = false;
        for (int i = configs.size() - 1; i >= 0; i--) {
            ConfigBean bean = configs.get(i);
            if (bean == null || bean.apiUrl == null) continue;
            String url = bean.apiUrl.trim();
            Finding checkedFinding = checked.get(url);
            if (checkedFinding == null) continue;
            if (!bean.autoSource) {
                bean.autoSource = true;
                configsChanged = true;
            }
            if (!usableUrls.contains(url)) {
                configs.remove(i);
                configsChanged = true;
            }
        }

        List<ConfigBean> orderedUsable = new ArrayList<>();
        for (Finding finding : usable) {
            String url = finding.getUrl().trim();
            ConfigBean bean = findConfig(configs, url);
            if (bean == null) {
                bean = new ConfigBean(finding.getName(), url);
                bean.autoSource = true;
                configs.add(bean);
                configsChanged = true;
            }
            if (!bean.autoSource || !bean.enabled) {
                bean.autoSource = true;
                bean.enabled = true;
                configsChanged = true;
            }
            orderedUsable.add(bean);
        }
        if (orderedUsable.isEmpty()) {
            return;
        }

        String oldHomeId = ConfigManager.getHomeConfigId();
        ConfigBean applied = selectConfigToApply(configs, orderedUsable, oldHomeId);
        if (applied == null) {
            return;
        }
        boolean homeChanged = !applied.id.equals(oldHomeId);
        if (configsChanged) {
            ConfigManager.saveConfigs(configs);
        }
        ConfigManager.setHomeConfigId(applied.id);
        cache.setAppliedUrl(applied.apiUrl);

        LOG.i("自动找源已同步配置：可用 " + orderedUsable.size()
                + " 个，当前配置 " + applied.displayName());
        if (homeChanged) {
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_HOME_SOURCE_CHANGE));
        }
    }

    /**
     * 随包内置源已移除（source_index.json 的 local 已清空、assets 配置已删除），
     * 老版本升级后配置列表里可能还残留 assets:// 条目，这里顺手清掉。
     * 当前主页配置恰好是被清的内置源时，由 {@link #selectConfigToApply} 重新挑选。
     */
    private static void purgeRemovedBuiltInConfigs() {
        List<ConfigBean> configs = ConfigManager.getConfigs();
        boolean changed = false;
        for (int i = configs.size() - 1; i >= 0; i--) {
            ConfigBean bean = configs.get(i);
            if (bean != null && bean.apiUrl != null && bean.apiUrl.trim().startsWith("assets://")) {
                configs.remove(i);
                changed = true;
            }
        }
        if (changed) {
            ConfigManager.saveConfigs(configs);
            LOG.i("已清理随包内置源残留的配置项");
        }
    }

    static ConfigBean selectConfigToApply(List<ConfigBean> configs,
                                         List<ConfigBean> orderedUsable,
                                         String homeConfigId) {
        if (homeConfigId != null && !homeConfigId.isEmpty() && configs != null) {
            for (ConfigBean bean : configs) {
                if (bean != null && homeConfigId.equals(bean.id) && bean.enabled
                        && bean.apiUrl != null && !bean.apiUrl.trim().isEmpty()) {
                    return bean;
                }
            }
        }
        if (orderedUsable != null) {
            for (ConfigBean bean : orderedUsable) {
                if (bean != null && bean.enabled
                        && bean.apiUrl != null && !bean.apiUrl.trim().isEmpty()) {
                    return bean;
                }
            }
        }
        return null;
    }

    private static ConfigBean findConfig(List<ConfigBean> configs, String url) {
        for (ConfigBean bean : configs) {
            if (bean != null && bean.apiUrl != null && url.equals(bean.apiUrl.trim())) {
                return bean;
            }
        }
        return null;
    }

    private void notifyState() {
        final State snapshot = state();
        main.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : listeners) {
                    try {
                        l.onState(snapshot);
                    } catch (Exception e) {
                        LOG.w("找源监听器异常：" + e);
                    }
                }
            }
        });
    }

    /** 供 UI 展示：当前策略的可读描述。 */
    public static String describePolicy() {
        return "启动即用本地缓存；每 " + (REFRESH_INTERVAL_MS / 60000) + " 分钟联网刷新一次；"
                + "失败后每 " + (RETRY_INTERVAL_MS / 60000) + " 分钟重试，直到恢复；"
                + "找源成功后自动合并为「本地合并源」";
    }
}
