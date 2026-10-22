package com.github.tvbox.osc.finder;

import com.google.gson.annotations.SerializedName;

/**
 * 一条「验证过的源」。
 *
 * <p>只有 {@link #usable} 为 true 的条目才会被 {@link SourceCache} 持久化并优先使用；
 * 不可用的条目也会被记录下来，用于告诉用户「试过了、为什么不行」，
 * 避免每次都重复踩同一个坑。
 */
public final class Finding {

    public static final String ORIGIN_LOCAL = "内置";
    public static final String ORIGIN_BUILTIN = "内置索引";
    public static final String ORIGIN_GITHUB = "GitHub";
    public static final String ORIGIN_GITLAB = "GitLab";
    public static final String ORIGIN_GITEE = "Gitee";
    public static final String ORIGIN_MULTI = "多仓线路";
    public static final String ORIGIN_MANUAL = "手动";

    /** 展示名。 */
    private String name;

    /** 配置地址（http(s):// 或 assets://）。 */
    private String url;

    /** 来源分类，见上面的常量。 */
    private String origin;

    /** 来源细节，例如仓库全名 {@code gaotianliuyun/gao}。 */
    @SerializedName("origin_detail")
    private String originDetail;

    /** 配置里可用于 V1 的采集站数量。 */
    @SerializedName("site_count")
    private int siteCount;

    /** 实际验证通过的站点数量。 */
    @SerializedName("verified_sites")
    private int verifiedSites;

    /** 抽样站点的分类数量（-1 表示没取到）。 */
    @SerializedName("category_count")
    private int categoryCount = -1;

    /** 抽样条目的数量（-1 表示没取到）。 */
    @SerializedName("item_count")
    private int itemCount = -1;

    /** 是否验证到「详情里有播放地址」（也就是真的能播）。 */
    private boolean playable;

    /** 可用站点的名字，逗号分隔，用于展示。 */
    @SerializedName("sample_sites")
    private String sampleSites;

    /** 是否通过验证。 */
    private boolean usable;

    /**
     * 配置里的爬虫站（type 3 等）数量。
     *
     * <p>探针<b>只计数、绝不加载</b>——jar 爬虫意味着远程代码执行（见
     * docs/实现方案.md §4.5）。但用户导入这个配置时，App 就会真的去加载那些 jar。
     * 所以导入前必须用这个数字明示风险。
     */
    @SerializedName("spider_site_count")
    private int spiderSiteCount;

    /** 失败原因 / 补充说明。 */
    private String message;

    /** 验证时间。 */
    @SerializedName("checked_at")
    private long checkedAt;

    /** 验证耗时（毫秒）。 */
    @SerializedName("latency_ms")
    private long latencyMs;

    /**
     * 本次失败是否属于「资源确定不存在」（HTTP 404 / 410）。
     *
     * <p>{@code transient}：只用于当轮判断「值不值得再试镜像」，不写进缓存——
     * 下一次刷新时该地址可能已经存在了，落盘反而会把过期结论固化。
     */
    private transient boolean notFound;

    public Finding() {
    }

    public static Finding of(String name, String url, String origin, String originDetail) {
        Finding f = new Finding();
        f.name = name;
        f.url = url;
        f.origin = origin;
        f.originDetail = originDetail;
        f.checkedAt = System.currentTimeMillis();
        return f;
    }

    // ---------------- getter / setter ----------------

    public String getName() {
        return name == null || name.trim().isEmpty() ? url : name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getOrigin() {
        return origin == null ? ORIGIN_BUILTIN : origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public String getOriginDetail() {
        return originDetail;
    }

    public void setOriginDetail(String originDetail) {
        this.originDetail = originDetail;
    }

    public int getSiteCount() {
        return siteCount;
    }

    public int getSpiderSiteCount() {
        return spiderSiteCount;
    }

    public void setSpiderSiteCount(int spiderSiteCount) {
        this.spiderSiteCount = spiderSiteCount;
    }

    public void setSiteCount(int siteCount) {
        this.siteCount = siteCount;
    }

    public int getVerifiedSites() {
        return verifiedSites;
    }

    public void setVerifiedSites(int verifiedSites) {
        this.verifiedSites = verifiedSites;
    }

    public int getCategoryCount() {
        return categoryCount;
    }

    public void setCategoryCount(int categoryCount) {
        this.categoryCount = categoryCount;
    }

    public int getItemCount() {
        return itemCount;
    }

    public void setItemCount(int itemCount) {
        this.itemCount = itemCount;
    }

    public boolean isPlayable() {
        return playable;
    }

    public void setPlayable(boolean playable) {
        this.playable = playable;
    }

    public String getSampleSites() {
        return sampleSites;
    }

    public void setSampleSites(String sampleSites) {
        this.sampleSites = sampleSites;
    }

    public boolean isUsable() {
        return usable && url != null && !url.trim().isEmpty();
    }

    public void setUsable(boolean usable) {
        this.usable = usable;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public long getCheckedAt() {
        return checkedAt;
    }

    public void setCheckedAt(long checkedAt) {
        this.checkedAt = checkedAt;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(long latencyMs) {
        this.latencyMs = latencyMs;
    }

    /** 是否「确定不存在」。仅供 {@code SourceFinder} 决定要不要试镜像。 */
    public boolean isNotFound() {
        return notFound;
    }

    public void setNotFound(boolean notFound) {
        this.notFound = notFound;
    }

    // ---------------- 展示 ----------------

    /** 是否来自内置索引 / 本地内置（这两种不依赖网络发现的通道）。 */
    public boolean isLocal() {
        return url != null && url.startsWith("assets://");
    }

    /** 展示用的一行摘要。 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(getName());
        sb.append("  [").append(getOrigin()).append(']');
        if (isUsable()) {
            sb.append("\n    ").append(siteCount).append(" 个采集站");
            if (verifiedSites > 0) {
                sb.append("，验证通过 ").append(verifiedSites).append(" 个");
            }
            if (categoryCount > 0) {
                sb.append("，分类 ").append(categoryCount).append(" 个");
            }
            if (itemCount > 0) {
                sb.append("，抽样 ").append(itemCount).append(" 条");
            }
            sb.append(playable ? "，可播放" : "，未取到播放地址");
            if (sampleSites != null && !sampleSites.isEmpty()) {
                sb.append("\n    ").append(sampleSites);
            }
        } else if (message != null && !message.isEmpty()) {
            sb.append("\n    ").append(message);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return getName() + "@" + url + (usable ? "(可用)" : "(不可用)");
    }
}
