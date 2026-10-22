package com.github.tvbox.osc.finder;

import android.content.Context;

import com.github.tvbox.osc.util.LOG;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「自动找源」总编排：找 → 验 → 给结果。
 *
 * <p>两个通道：
 * <ol>
 *     <li><b>内置索引</b>（{@code assets/source_index.json}）——首选通道，
 *         随包声明 qist/tvbox 等优先验证地址，不依赖 GitHub 仓库搜索</li>
 *     <li><b>GitHub 在线发现</b>——搜索仓库 → 扫描仓库 → 得到候选地址</li>
 * </ol>
 *
 * <p>所有候选都交给 {@link FinderProbe} 逐个验证，验证通过的才叫「可用」。
 * GitHub 通道失败（限流 / 不通）只记录 {@link Report#note()}，不影响内置通道。
 */
public final class SourceFinder {

    /** 候选总数上限：验证是要发请求的，不能无限膨胀。 */
    private static final int MAX_CANDIDATES = 40;

    /** 验证并发度。 */
    private static final int PROBE_THREADS = 3;

    /** 单个候选的验证总超时（秒），兜住「探测预算没算到的卡死」。 */
    private static final long PROBE_TIMEOUT_SECONDS = 90L;

    /** GitHub raw 地址的两种写法：raw.githubusercontent.com 与 github.com/.../raw|blob/...。 */
    private static final Pattern RAW_GITHUB = Pattern.compile(
            "^https?://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/([^/]+)/(.+)$");
    private static final Pattern WEB_GITHUB = Pattern.compile(
            "^https?://github\\.com/([^/]+)/([^/]+)/(?:raw|blob)/([^/]+)/(.+)$");

    /** 进度回调。 */
    public interface Listener {
        /** 阶段提示（搜索中 / 扫描仓库中…）。 */
        void onStage(String stage);

        /** 每验证完一个候选回调一次。 */
        void onFound(Finding finding, int done, int total);
    }

    /** 一次发现的结果。 */
    public static final class Report {
        private final List<Finding> findings;
        private final int candidates;
        private final long costMs;
        private final String note;

        Report(List<Finding> findings, int candidates, long costMs, String note) {
            this.findings = findings == null ? Collections.<Finding>emptyList() : findings;
            this.candidates = candidates;
            this.costMs = costMs;
            this.note = note;
        }

        /** 全部验证结果（含不可用，便于展示「试过了但不行」）。 */
        public List<Finding> all() {
            return findings;
        }

        /** 可用结果，已排序（best 打头）。 */
        public List<Finding> usable() {
            List<Finding> out = new ArrayList<>();
            for (Finding f : rank(findings)) {
                if (f.isUsable()) {
                    out.add(f);
                }
            }
            return out;
        }

        public int candidateCount() {
            return candidates;
        }

        public int usableCount() {
            return usable().size();
        }

        public long costMs() {
            return costMs;
        }

        /** 降级说明；一切正常时为 null。 */
        public String note() {
            return note;
        }

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("候选 ").append(candidates).append(" 个，可用 ").append(usableCount())
                    .append(" 个，耗时 ").append(costMs / 1000).append("s");
            if (note != null && !note.isEmpty()) {
                sb.append("\n").append(note);
            }
            return sb.toString();
        }
    }

    /** 待验证的候选。 */
    private static final class Candidate {
        final String name;
        final String url;
        final String origin;
        final String originDetail;

        Candidate(String name, String url, String origin, String originDetail) {
            this.name = name;
            this.url = url;
            this.origin = origin;
            this.originDetail = originDetail;
        }
    }

    private final SourceIndex index;
    private final FinderProbe probe;

    private volatile ExecutorService pool;

    public SourceFinder(Context context) {
        this(context, SourceIndex.fromAssets(context));
    }

    public SourceFinder(Context context, SourceIndex index) {
        this.index = index == null ? SourceIndex.fallback() : index;
        this.probe = new FinderProbe(this.index.probe());
    }

    public SourceIndex index() {
        return index;
    }

    // ---------------- 发现 ----------------

    public Report discover(boolean includeGithub) {
        return discover(includeGithub, null);
    }

    public Report discover(boolean includeGithub, Listener listener) {
        long start = System.currentTimeMillis();
        List<Candidate> candidates = new ArrayList<>();
        List<Candidate> githubCandidates = new ArrayList<>();
        List<Candidate> gitlabCandidates = new ArrayList<>();
        List<Candidate> giteeCandidates = new ArrayList<>();
        StringBuilder note = new StringBuilder();

        // 通道 1：内置索引（离线可用）
        for (SourceIndex.Entry e : index.allEntries()) {
            boolean local = e.getUrl().startsWith("assets://");
            add(candidates, new Candidate(e.getName(), e.getUrl(),
                    local ? Finding.ORIGIN_LOCAL : Finding.ORIGIN_BUILTIN, null));
        }
        stage(listener, "内置索引 " + candidates.size() + " 条");

        // 先验证随包内置源，让首次启动尽快拿到可用配置；在线搜索随后继续。
        int builtInTotal = candidates.size();
        List<Finding> findings = probeCandidates(candidates, 0, builtInTotal, listener);

        // 通道 2：GitHub 在线发现（可失败）
        if (includeGithub && index.github().isEnabled()) {
            collectGithub(githubCandidates, note, listener);
        }
        if (includeGithub && index.gitlab().isEnabled()) {
            collectCodeHost(CodeHostScanner.Platform.GITLAB, index.gitlab(),
                    gitlabCandidates, note, listener);
        }
        if (includeGithub && index.gitee().isEnabled()) {
            collectCodeHost(CodeHostScanner.Platform.GITEE, index.gitee(),
                    giteeCandidates, note, listener);
        } else if (!includeGithub) {
            note.append("已跳过 GitHub/GitLab/Gitee 在线发现（仅用内置索引）");
        }
        List<Candidate> onlineCandidates = new ArrayList<>();
        mergePlatformCandidates(onlineCandidates, githubCandidates, gitlabCandidates, giteeCandidates);
        int total = builtInTotal + onlineCandidates.size();
        findings.addAll(probeCandidates(onlineCandidates, builtInTotal, total, listener));

        long cost = System.currentTimeMillis() - start;
        Report report = new Report(findings, total, cost,
                note.length() == 0 ? null : note.toString());
        LOG.i("自动找源完成：" + report.summary().replace('\n', ' '));
        return report;
    }

    private List<Finding> probeCandidates(List<Candidate> candidates, int offset,
                                          int total, Listener listener) {
        if (candidates.isEmpty()) return new ArrayList<>();
        stage(listener, "开始验证 " + candidates.size() + " 个候选源");
        List<Future<Finding>> futures = new ArrayList<>(candidates.size());
        for (final Candidate c : candidates) {
            futures.add(pool().submit(new Callable<Finding>() {
                @Override
                public Finding call() {
                    return probeCandidate(c);
                }
            }));
        }

        List<Finding> findings = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            Candidate c = candidates.get(i);
            Finding f;
            try {
                f = futures.get(i).get(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                futures.get(i).cancel(true);
                f = Finding.of(c.name, c.url, c.origin, c.originDetail);
                f.setUsable(false);
                f.setMessage("验证超时或被中断（" + e.getClass().getSimpleName() + "）");
            }
            if (f == null) {
                continue;
            }
            findings.add(f);
            if (listener != null) {
                listener.onFound(f, offset + findings.size(), total);
            }
        }
        return findings;
    }

    /** 手动验证单个地址（用户在界面上粘贴地址时用）。 */
    public Finding verify(String url, String name, String origin) {
        if (url == null || url.trim().isEmpty()) {
            Finding f = Finding.of(name, url, origin, null);
            f.setUsable(false);
            f.setMessage("地址为空");
            return f;
        }
        return probe.probe(name == null ? url : name, url.trim(), origin, null);
    }

    /**
     * 验证一个候选：主地址失败时再试镜像。
     *
     * <p>{@code raw.githubusercontent.com} 在部分网络环境下会被阻断，
     * 而 jsDelivr / gitmirror 往往还能拿到同一份文件。
     * 镜像只在主地址失败后才请求，所以正常情况下不会增加额外耗时；
     * 命中镜像时保存的地址就是镜像地址（下次冷启动可以直连）。
     *
     * <p>但「失败」要分类：404 说明这个文件<b>本来就不存在</b>（猜文件名猜错是常态），
     * 而所有镜像服务的是同一份仓库文件，换过去必然也是 404——实测 jsDelivr 还会
     * 吃满 15s 超时。所以这种情况直接跳过镜像，省下的是几十次无效长等待。
     */
    private Finding probeCandidate(Candidate c) {
        Finding primary = probe.probe(c.name, c.url, c.origin, c.originDetail);
        if (primary.isUsable()) {
            return primary;
        }
        if (primary.isNotFound()) {
            return primary;
        }
        for (String mirror : mirrorsFor(c.url)) {
            Finding mirrored = probe.probe(c.name + "（镜像）", mirror, c.origin, c.originDetail);
            if (mirrored.isUsable()) {
                mirrored.setMessage("主地址不可用，已改用镜像：" + mirror);
                LOG.i("主地址不可用，镜像可用：" + mirror);
                return mirrored;
            }
            if (mirrored.isNotFound()) {
                // 镜像也确认文件不存在，剩下的镜像不用再试
                break;
            }
        }
        return primary;
    }

    /** GitHub raw 地址的镜像候选；不是 GitHub 地址时返回空列表。 */
    public static List<String> mirrorsFor(String url) {
        List<String> out = new ArrayList<>();
        if (url == null || url.trim().isEmpty()) {
            return out;
        }
        Matcher raw = RAW_GITHUB.matcher(url.trim());
        if (!raw.matches()) {
            raw = WEB_GITHUB.matcher(url.trim());
        }
        if (raw.matches()) {
            String user = raw.group(1);
            String repo = raw.group(2);
            String branch = raw.group(3);
            String path = raw.group(4);
            addMirror(out, "https://cdn.jsdelivr.net/gh/" + user + "/" + repo + "@" + branch + "/" + path);
            addMirror(out, "https://raw.gitmirror.com/" + user + "/" + repo + "/" + branch + "/" + path);
        }
        return out;
    }

    private static void addMirror(List<String> out, String mirror) {
        if (mirror != null && !out.contains(mirror)) {
            out.add(mirror);
        }
    }

    private void collectGithub(List<Candidate> candidates, StringBuilder note, Listener listener) {
        SourceIndex.Github gh = index.github();
        List<RepoScanner.Repo> repos = new ArrayList<>();
        Set<String> seenRepos = new LinkedHashSet<>();
        int maxRepos = gh.getMaxRepos();

        // 通道 A：种子仓库。不依赖 Search API，所以搜索被限流时这条通道照样有效。
        for (String spec : gh.getRepos()) {
            if (repos.size() >= maxRepos) {
                break;
            }
            RepoScanner.Repo seed = RepoScanner.parseSeed(spec);
            if (seed == null) {
                appendNote(note, "种子仓库写法不合法，已跳过：" + spec);
                continue;
            }
            if (seenRepos.add(seed.fullName())) {
                repos.add(seed);
            }
        }
        int seedCount = repos.size();

        // 通道 B：Search API 填满剩余名额（最容易被限流的一环，放后面）
        if (repos.size() < maxRepos) {
            List<String> queries = gh.getQueries();
            int tried = 0;
            for (String q : queries) {
                if (tried >= 3 || repos.size() >= maxRepos) {
                    break;
                }
                tried++;
                stage(listener, "搜索 GitHub：" + q);
                List<RepoScanner.Repo> found = RepoScanner.searchRepos(q, maxRepos);
                if (found.isEmpty()) {
                    appendNote(note, "GitHub 搜索无结果或被限流"
                            + (seedCount > 0 ? "，本次只扫种子仓库" : "，已降级为仅用内置索引"));
                    break;
                }
                for (RepoScanner.Repo r : found) {
                    if (seenRepos.add(r.fullName())) {
                        repos.add(r);
                    }
                }
            }
        }

        if (repos.isEmpty()) {
            return;
        }
        if (repos.size() > maxRepos) {
            repos = new ArrayList<>(repos.subList(0, maxRepos));
        }

        RepoScanner scanner = new RepoScanner(gh);
        for (RepoScanner.Repo repo : repos) {
            String branch = repo.branch();
            if (branch == null || branch.trim().isEmpty()) {
                branch = RepoScanner.detectBranch(repo.fullName());
            }
            stage(listener, "扫描仓库：" + repo.fullName()
                    + (repo.stars() > 0 ? "（" + repo.stars() + "★）" : ""));
            RepoScanner.Scan scan = scanner.scan(repo.fullName(), branch);
            for (String url : scan.urls()) {
                if (isExcluded(url, gh)) {
                    continue;
                }
                add(candidates, new Candidate(repo.fullName(), url,
                        Finding.ORIGIN_GITHUB, repo.fullName()));
            }
            if (scan.note() != null) {
                appendNote(note, repo.fullName() + "：" + scan.note());
            }
        }
    }

    private void collectCodeHost(CodeHostScanner.Platform platform,
                                 SourceIndex.CodeHost cfg,
                                 List<Candidate> candidates,
                                 StringBuilder note,
                                 Listener listener) {
        List<CodeHostScanner.Repo> repos = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int maxRepos = cfg.getMaxRepos();
        for (String spec : cfg.getRepos()) {
            if (repos.size() >= maxRepos) break;
            CodeHostScanner.Repo repo = CodeHostScanner.parseSeed(platform, spec);
            if (repo != null && seen.add(repo.fullName())) repos.add(repo);
        }

        CodeHostScanner scanner = new CodeHostScanner(platform, cfg);
        for (String query : cfg.getQueries()) {
            if (repos.size() >= maxRepos) break;
            stage(listener, "搜索 " + platform.label() + "：" + query);
            for (CodeHostScanner.Repo repo : scanner.searchRepos(query, maxRepos - repos.size())) {
                if (repos.size() >= maxRepos) break;
                if (seen.add(repo.fullName())) repos.add(repo);
            }
        }

        for (CodeHostScanner.Repo repo : repos) {
            stage(listener, "扫描 " + platform.label() + " 仓库：" + repo.fullName());
            CodeHostScanner.Scan scan = scanner.scan(repo.fullName(), repo.branch());
            for (String url : scan.urls()) {
                if (isExcluded(url, index.github())) continue;
                String origin = platform == CodeHostScanner.Platform.GITLAB
                        ? Finding.ORIGIN_GITLAB : Finding.ORIGIN_GITEE;
                add(candidates, new Candidate(repo.fullName(), url, origin, repo.fullName()));
            }
            if (scan.note() != null) {
                appendNote(note, platform.label() + " " + repo.fullName() + "：" + scan.note());
            }
        }
    }

    private static void mergePlatformCandidates(List<Candidate> target,
                                                List<Candidate> github,
                                                List<Candidate> gitlab,
                                                List<Candidate> gitee) {
        List<List<Candidate>> channels = new ArrayList<>();
        channels.add(github);
        channels.add(gitlab);
        channels.add(gitee);
        int max = 0;
        for (List<Candidate> channel : channels) {
            max = Math.max(max, channel.size());
        }
        for (int i = 0; i < max && target.size() < MAX_CANDIDATES; i++) {
            for (List<Candidate> channel : channels) {
                if (i < channel.size()) add(target, channel.get(i));
            }
        }
    }

    /**
     * 这个候选是否命中排除规则（小写子串匹配）。
     *
     * <p>只作用于自动发现。用户手动粘贴地址走 {@link #verify}，不受限制。
     */
    static boolean isExcluded(String url, SourceIndex.Github gh) {
        if (url == null || url.trim().isEmpty()) {
            return true;
        }
        if (gh == null) {
            return false;
        }
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        for (String pattern : gh.getExcludePatterns()) {
            if (pattern != null && !pattern.trim().isEmpty()
                    && lower.contains(pattern.trim().toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
    // ---------------- 排序（纯函数，可单测） ----------------

    /**
     * 结果排序：可用的排前面，然后依次比「验证通过的站数」「配置里的站数」
     * 「能否播放」「是否本地（不依赖在线发现的通道）」。
     */
    public static List<Finding> rank(List<Finding> findings) {
        List<Finding> out = new ArrayList<>();
        if (findings != null) {
            for (Finding f : findings) {
                if (f != null) {
                    out.add(f);
                }
            }
        }
        Collections.sort(out, new Comparator<Finding>() {
            @Override
            public int compare(Finding a, Finding b) {
                if (a.isUsable() != b.isUsable()) {
                    return a.isUsable() ? -1 : 1;
                }
                if (a.isLocal() != b.isLocal()) {
                    return a.isLocal() ? -1 : 1;
                }
                if (a.isPlayable() != b.isPlayable()) {
                    return a.isPlayable() ? -1 : 1;
                }
                if (a.getVerifiedSites() != b.getVerifiedSites()) {
                    return b.getVerifiedSites() - a.getVerifiedSites();
                }
                if (a.getSiteCount() != b.getSiteCount()) {
                    return b.getSiteCount() - a.getSiteCount();
                }
                return Long.compare(b.getCheckedAt(), a.getCheckedAt());
            }
        });
        return out;
    }

    // ---------------- 内部 ----------------

    private static void add(List<Candidate> list, Candidate candidate) {
        if (candidate == null || candidate.url == null || candidate.url.trim().isEmpty()) {
            return;
        }
        for (Candidate exist : list) {
            if (exist.url.equals(candidate.url)) {
                return;
            }
        }
        if (list.size() >= MAX_CANDIDATES) {
            return;
        }
        list.add(candidate);
    }

    private static void appendNote(StringBuilder note, String text) {
        if (note.length() > 0) {
            note.append("；");
        }
        note.append(text);
    }

    private static void stage(Listener listener, String message) {
        LOG.i("[找源] " + message);
        if (listener != null) {
            listener.onStage(message);
        }
    }

    private ExecutorService pool() {
        if (pool == null) {
            synchronized (this) {
                if (pool == null) {
                    pool = Executors.newFixedThreadPool(PROBE_THREADS, new ThreadFactory() {
                        private int seq;

                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "source-probe-" + (++seq));
                            t.setDaemon(true);
                            return t;
                        }
                    });
                }
            }
        }
        return pool;
    }
}
