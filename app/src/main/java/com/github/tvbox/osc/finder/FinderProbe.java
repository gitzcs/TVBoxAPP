package com.github.tvbox.osc.finder;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.AbsSortJson;
import com.github.tvbox.osc.bean.AbsSortXml;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.util.LOG;
import com.google.gson.JsonParser;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.DomDriver;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 验证一个配置地址「是不是真的能用」。
 *
 * <p>三层验证，代价与置信度成正比：
 * <ol>
 *     <li><b>能下载 + 能解密 + 能解析</b>：得到站点列表</li>
 *     <li><b>有可用的采集站</b>：type 0/1/4 且至少 1 个</li>
 *     <li><b>站点真的有响应</b>：抽样请求拿分类，进一步取列表 → 详情，
 *         确认能拿到播放地址</li>
 * </ol>
 *
 * <p>第 3 层只抽样前几个站（默认 3 个）并受总时间预算约束。
 * 验证的目的是判断「这个源值不值得保存」，不是给它做体检报告。
 *
 * <p><b>安全约束</b>（务必遵守）：
 * <ul>
 *     <li>绝不调用 {@code ApiConfig.parseJson()}——它会清空用户当前配置</li>
 *     <li>绝不调用 {@code ApiConfig.loadJar()} / {@code getCSP()}——
 *         那会下载并用 DexClassLoader 执行远程 jar，等于执行陌生代码</li>
 *     <li>只验证 type 0/1/4 的采集站；type 3 爬虫只计数，不碰</li>
 * </ul>
 */
public final class FinderProbe {

    private final int maxSites;
    private final long budgetMs;

    public FinderProbe(SourceIndex.Probe cfg) {
        this.maxSites = cfg == null ? 3 : cfg.getMaxSitesPerConfig();
        this.budgetMs = cfg == null ? 25000L : cfg.getBudgetMs();
    }

    /** 验证一个候选。 */
    public Finding probe(String name, String url, String origin, String originDetail) {
        long start = System.currentTimeMillis();
        Finding finding = Finding.of(name, url, origin, originDetail);
        try {
            // ---------- L1：下载 + 解密 + 解析 ----------
            String raw = fetchConfig(url);
            if (raw == null) {
                finding.setUsable(false);
                finding.setMessage("配置下载失败");
                return finish(finding, start);
            }
            ConfigSniffer.Sniffed sniffed = ConfigSniffer.parse(raw, null);
            if (sniffed == null) {
                finding.setUsable(false);
                finding.setMessage("配置解析失败（可能已加密或格式不对）");
                return finish(finding, start);
            }

            // 多仓：逐条线路试，直到找到一条能用的
            if (sniffed.isMultiRepo()) {
                ConfigSniffer.Sniffed resolved = resolveMultiRepo(sniffed, finding);
                if (resolved == null) {
                    finding.setUsable(false);
                    finding.setMessage("多仓线路均不可用");
                    return finish(finding, start);
                }
                sniffed = resolved;
            }

            // ---------- L2：筛选采集站 ----------
            List<SourceBean> cms = sniffed.cmsSites();
            finding.setSiteCount(cms.size());
            if (cms.isEmpty()) {
                finding.setUsable(false);
                int spiders = sniffed.spiderSiteCount();
                finding.setMessage("没有可用的采集站"
                        + (spiders > 0 ? "（含 " + spiders + " 个爬虫站，探针不加载远程代码）" : ""));
                return finish(finding, start);
            }

            // ---------- L3：抽样验证站点真实响应 ----------
            verifySites(finding, cms);
        } catch (Throwable th) {
            finding.setUsable(false);
            finding.setMessage("验证异常：" + th.getClass().getSimpleName() + " " + brief(th.getMessage()));
        }
        return finish(finding, start);
    }

    // ---------------- 多仓 ----------------

    /**
     * 多仓配置：按顺序尝试各条线路，返回第一条含可用采集站的。
     *
     * <p>最多试 5 条，避免线路全挂时卡太久。
     */
    private ConfigSniffer.Sniffed resolveMultiRepo(ConfigSniffer.Sniffed multi, Finding finding) {
        int tried = 0;
        StringBuilder errors = new StringBuilder();
        for (ConfigSniffer.Line line : multi.lines) {
            if (tried >= 5) {
                break;
            }
            tried++;
            try {
                String raw = fetchConfig(line.url);
                if (raw == null) {
                    errors.append(line.name).append("：下载失败；");
                    continue;
                }
                ConfigSniffer.Sniffed one = ConfigSniffer.parse(raw, null);
                if (one == null) {
                    errors.append(line.name).append("：解析失败；");
                    continue;
                }
                if (!one.cmsSites().isEmpty()) {
                    finding.setMessage("多仓自动选中线路：" + line.name);
                    return one;
                }
                errors.append(line.name).append("：无采集站；");
            } catch (Throwable th) {
                errors.append(line.name).append("：").append(th.getClass().getSimpleName()).append("；");
            }
        }
        finding.setMessage("多仓线路均不可用（已试 " + tried + " 条）：" + errors);
        return null;
    }

    // ---------------- L3：站点抽样 ----------------

    private void verifySites(Finding finding, List<SourceBean> sites) {
        long deadline = System.currentTimeMillis() + budgetMs;
        int verified = 0;
        int categories = -1;
        int items = -1;
        boolean playable = false;
        List<String> okNames = new ArrayList<>();
        StringBuilder failed = new StringBuilder();

        int limit = Math.min(maxSites, sites.size());
        for (int i = 0; i < limit; i++) {
            if (System.currentTimeMillis() > deadline) {
                failed.append("（验证超时，只查了 ").append(i).append('/').append(limit).append(" 个站）");
                break;
            }
            SourceBean site = sites.get(i);
            List<MovieSort.SortData> classes = fetchCategories(site);
            if (classes == null || classes.isEmpty()) {
                failed.append(site.getName()).append("：无分类；");
                continue;
            }
            verified++;
            okNames.add(site.getName());
            if (categories < 0) {
                categories = classes.size();
            }
            // 只对第一个站深挖「能不能播」，控制代价
            if (!playable && items < 0) {
                PlayProbe probe = probePlayable(site, classes.get(0));
                items = probe.items;
                playable = probe.playable;
            }
        }

        finding.setVerifiedSites(verified);
        finding.setCategoryCount(categories);
        finding.setItemCount(items);
        finding.setPlayable(playable);
        finding.setSampleSites(okNames.isEmpty() ? null : join(okNames, "、"));
        finding.setUsable(verified > 0);

        if (verified == 0) {
            finding.setMessage("采集站都没有响应：" + failed);
        } else if (!playable) {
            finding.setMessage("能取到分类，但抽样没拿到播放地址");
        }
    }

    /** 「能不能播」的深挖结果。 */
    private static final class PlayProbe {
        final int items;
        final boolean playable;

        PlayProbe(int items, boolean playable) {
            this.items = items;
            this.playable = playable;
        }
    }

    /**
     * 取分类列表。
     *
     * <p>请求方式与 A 的 {@code SourceViewModel.getSort()} 对 type 0/1 一致：
     * 就是裸 GET 站点 api（不带参数），返回分类信息。
     */
    private List<MovieSort.SortData> fetchCategories(SourceBean site) {
        FinderResult result = FinderHttp.get(site.getApi(), null, FinderHttp.PROBE_TIMEOUT_SECONDS);
        if (!result.isOk()) {
            return null;
        }
        try {
            AbsSortXml sort;
            if (site.getType() == 0) {
                sort = parseXml(result.getData());
            } else {
                sort = parseJson(result.getData());
            }
            if (sort == null || sort.classes == null || sort.classes.sortList == null) {
                return null;
            }
            return sort.classes.sortList;
        } catch (Throwable th) {
            return null;
        }
    }

    /**
     * 取分类第一条 → 详情 → 看有没有播放地址。
     *
     * <p>请求方式与 A 的 {@code SourceViewModel.getDetail()} 一致：
     * type 0/1 用 {@code ?ac=videolist|detail&ids={id}}。
     */
    private PlayProbe probePlayable(SourceBean site, MovieSort.SortData category) {
        if (category == null || category.id == null || category.id.trim().isEmpty()) {
            return new PlayProbe(0, false);
        }
        try {
            StringBuilder sb = new StringBuilder(site.getApi());
            sb.append(site.getApi().contains("?") ? "&" : "?");
            sb.append("ac=").append(site.getType() == 0 ? "videolist" : "detail");
            sb.append("&ids=").append(java.net.URLEncoder.encode(category.id, "UTF-8"));
            FinderResult result = FinderHttp.get(sb.toString(), null, FinderHttp.PROBE_TIMEOUT_SECONDS);
            if (!result.isOk()) {
                return new PlayProbe(0, false);
            }
            String body = result.getData();
            if (body == null || body.trim().isEmpty()) {
                return new PlayProbe(0, false);
            }
            // 详情返回里有没有可播放地址：粗判 vod_play_url / "play_url" / url 字段
            boolean playable = looksPlayable(body);
            return new PlayProbe(1, playable);
        } catch (Throwable th) {
            return new PlayProbe(0, false);
        }
    }

    /**
     * 粗判详情响应里有没有播放地址。
     *
     * <p>这里刻意不做完整解析——A 的 {@code AbsJson} / {@code AbsXml} 解析
     * 依赖 XStream 与具体字段结构，而探针只需要一个「有没有播放地址」的信号。
     * 宁可宽松（false positive 多）也不要严格（把好源误杀）。
     */
    private static boolean looksPlayable(String body) {
        if (body == null || body.isEmpty()) {
            return false;
        }
        String lower = body.toLowerCase();
        if (lower.contains("vod_play_url") || lower.contains("\"play_url\"")
                || lower.contains("vod_play_from") || lower.contains("\"url\"")) {
            // 再确认一下不是空值
            return !(lower.contains("\"vod_play_url\":\"\"") || lower.contains("\"play_url\":\"\""));
        }
        return false;
    }

    // ---------------- 解析 ----------------

    /** 解析 XML 分类响应（复用 A 的 XStream 配置）。 */
    private static AbsSortXml parseXml(String xml) {
        if (xml == null || xml.trim().isEmpty()) {
            return null;
        }
        try {
            XStream xstream = new XStream(new DomDriver());
            xstream.autodetectAnnotations(true);
            xstream.processAnnotations(AbsSortXml.class);
            xstream.ignoreUnknownElements();
            AbsSortXml data = (AbsSortXml) xstream.fromXML(xml);
            if (data != null && data.classes != null && data.classes.sortList == null) {
                data.classes.sortList = new ArrayList<>();
            }
            return data;
        } catch (Throwable th) {
            return null;
        }
    }

    /** 解析 JSON 分类响应（复用 A 的 {@link AbsSortJson}）。 */
    private static AbsSortXml parseJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            com.google.gson.JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            AbsSortJson sortJson = new com.google.gson.Gson().fromJson(
                    obj, new com.google.gson.reflect.TypeToken<AbsSortJson>() {
                    }.getType());
            return sortJson == null ? null : sortJson.toAbsSortXml();
        } catch (Throwable th) {
            return null;
        }
    }

    // ---------------- 下载 ----------------

    /**
     * 取配置文本；失败返回 null。
     *
     * <p>支持 {@code assets://} 协议读内置配置——这样「内置兜底源」不依赖网络，
     * 断网也有东西可验证。
     */
    private String fetchConfig(String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }
        String s = url.trim();
        if (s.startsWith("assets://")) {
            return readAsset(s.substring("assets://".length()));
        }
        FinderResult result = FinderHttp.get(s, null, FinderHttp.DEFAULT_TIMEOUT_SECONDS);
        return result.isOk() ? result.getData() : null;
    }

    /** 读 assets 里的文本文件。 */
    private String readAsset(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        try (InputStream in = App.getInstance().getAssets().open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable th) {
            LOG.e("读取内置配置失败: " + path);
            return null;
        }
    }

    // ---------------- 工具 ----------------

    private static Finding finish(Finding finding, long start) {
        finding.setLatencyMs(System.currentTimeMillis() - start);
        LOG.i("验证 " + finding.getUrl() + " → "
                + (finding.isUsable() ? "可用" : "不可用")
                + "（" + finding.getLatencyMs() + "ms）"
                + (finding.getMessage() == null ? "" : " " + finding.getMessage()));
        return finding;
    }

    private static String brief(String message) {
        if (message == null) {
            return "";
        }
        String one = message.replace('\n', ' ').trim();
        return one.length() > 60 ? one.substring(0, 60) + "…" : one;
    }

    private static String join(List<String> items, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            if (sb.length() > 0) {
                sb.append(sep);
            }
            sb.append(s);
        }
        return sb.toString();
    }
}
