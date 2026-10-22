package com.github.tvbox.osc.util;

import com.github.tvbox.osc.bean.ConfigBean;
import com.orhanobut.hawk.Hawk;

import java.util.ArrayList;
import java.util.List;

/**
 * 多配置地址管理器：新增 / 编辑 / 删除 / 启停 / 设主页源 / 旧配置迁移。
 *
 * <p><b>与 {@link HawkConfig#API_URL} 的关系</b>：本类是写入侧的唯一权威，
 * {@code API_URL} 退化为「当前生效配置」的镜像。全仓有近 20 处代码直接读
 * {@code Hawk.get(HawkConfig.API_URL, "")}，重构这些读取路径风险太大，
 * 因此统一由 {@link #syncLegacyApiUrl()} 单向同步过去。
 *
 * <p>配置来源包括随包内置的已验证采集配置、手动填写，以及「视频源自动获取」
 * （finder 包）同步的可用配置。内置配置不包含 jar 爬虫。
 */
public class ConfigManager {

    private static final String KEY_CONFIG_LIST = "config_list";
    private static final String KEY_HOME_CONFIG_ID = "home_config_id";

    private ConfigManager() {
    }

    // ---------------- 主页配置 ----------------

    /** 设置主页配置（该配置的数据显示在主页）。 */
    public static void setHomeConfigId(String id) {
        Hawk.put(KEY_HOME_CONFIG_ID, id == null ? "" : id);
        syncLegacyApiUrl();
    }

    /** 读取主页配置 ID，空串表示未设置。 */
    public static String getHomeConfigId() {
        String id = Hawk.get(KEY_HOME_CONFIG_ID, "");
        return id == null ? "" : id;
    }

    /**
     * 把「当前生效配置」同步到旧的 {@link HawkConfig#API_URL}。
     *
     * <p>规则：优先用主页配置；没有主页配置时用第一条启用配置。
     */
    private static void syncLegacyApiUrl() {
        String api = "";
        String homeId = getHomeConfigId();
        List<ConfigBean> configs = getConfigs();
        if (!homeId.isEmpty()) {
            for (ConfigBean cb : configs) {
                if (cb != null && homeId.equals(cb.id)) {
                    api = cb.apiUrl == null ? "" : cb.apiUrl.trim();
                    break;
                }
            }
        }
        if (api.isEmpty()) {
            List<ConfigBean> enabled = getEnabledConfigs();
            if (!enabled.isEmpty()) {
                api = enabled.get(0).apiUrl == null ? "" : enabled.get(0).apiUrl.trim();
            }
        }
        Hawk.put(HawkConfig.API_URL, api);
    }

    // ---------------- 增删改查 ----------------

    /** 读取全部配置。 */
    public static List<ConfigBean> getConfigs() {
        List<ConfigBean> list = Hawk.get(KEY_CONFIG_LIST, new ArrayList<ConfigBean>());
        if (list == null) {
            list = new ArrayList<>();
        }
        return list;
    }

    /** 保存配置列表。 */
    public static void saveConfigs(List<ConfigBean> list) {
        if (list == null) {
            list = new ArrayList<>();
        }
        Hawk.put(KEY_CONFIG_LIST, list);
        syncLegacyApiUrl();
    }

    /** 新增配置。 */
    public static void addConfig(ConfigBean bean) {
        if (bean == null) {
            return;
        }
        List<ConfigBean> list = getConfigs();
        list.add(bean);
        saveConfigs(list);
    }

    /** 更新配置（按 id 匹配）。 */
    public static void updateConfig(ConfigBean bean) {
        if (bean == null || bean.id == null) {
            return;
        }
        List<ConfigBean> list = getConfigs();
        for (int i = 0; i < list.size(); i++) {
            ConfigBean exist = list.get(i);
            if (exist != null && bean.id.equals(exist.id)) {
                list.set(i, bean);
                break;
            }
        }
        saveConfigs(list);
    }

    /** 删除配置（按 id 匹配）。 */
    public static void deleteConfig(String id) {
        if (id == null) {
            return;
        }
        List<ConfigBean> list = getConfigs();
        for (int i = list.size() - 1; i >= 0; i--) {
            ConfigBean exist = list.get(i);
            if (exist != null && id.equals(exist.id)) {
                list.remove(i);
            }
        }
        // 删掉的是主页配置时，清掉主页标记
        if (id.equals(getHomeConfigId())) {
            Hawk.put(KEY_HOME_CONFIG_ID, "");
        }
        saveConfigs(list);
    }

    /** 切换启用状态。 */
    public static void toggleEnabled(String id, boolean enabled) {
        if (id == null) {
            return;
        }
        List<ConfigBean> list = getConfigs();
        for (ConfigBean cb : list) {
            if (cb != null && id.equals(cb.id)) {
                cb.enabled = enabled;
                break;
            }
        }
        saveConfigs(list);
    }

    /**
     * 获取启用的配置（主页配置优先排在最前）。
     *
     * <p>只保留 apiUrl 非空的条目——地址为空没法用。
     */
    public static List<ConfigBean> getEnabledConfigs() {
        List<ConfigBean> out = new ArrayList<>();
        for (ConfigBean cb : getConfigs()) {
            if (cb != null && cb.enabled && cb.apiUrl != null && !cb.apiUrl.trim().isEmpty()) {
                out.add(cb);
            }
        }
        String homeId = getHomeConfigId();
        if (!homeId.isEmpty()) {
            for (int i = 0; i < out.size(); i++) {
                if (homeId.equals(out.get(i).id)) {
                    ConfigBean home = out.remove(i);
                    out.add(0, home);
                    break;
                }
            }
        }
        return out;
    }

    /** 按 id 取配置。 */
    public static ConfigBean getConfig(String id) {
        if (id == null) {
            return null;
        }
        for (ConfigBean cb : getConfigs()) {
            if (cb != null && id.equals(cb.id)) {
                return cb;
            }
        }
        return null;
    }

    /** 按配置地址查找；忽略首尾空白。 */
    public static ConfigBean findConfigByUrl(String apiUrl) {
        if (apiUrl == null || apiUrl.trim().isEmpty()) {
            return null;
        }
        String target = apiUrl.trim();
        for (ConfigBean cb : getConfigs()) {
            if (cb != null && cb.apiUrl != null && target.equals(cb.apiUrl.trim())) {
                return cb;
            }
        }
        return null;
    }

    /** 主页配置对象；未设置时返回 null。 */
    public static ConfigBean getHomeConfig() {
        return getConfig(getHomeConfigId());
    }

    // ---------------- 迁移 ----------------

    /**
     * 首次迁移：把旧版单一的 {@link HawkConfig#API_URL} 转成一条「默认配置」。
     *
     * <p>只有在配置列表为空时才会执行，因此不会覆盖用户已有的配置。
     */
    public static void migrateFromLegacy() {
        List<ConfigBean> list = getConfigs();
        if (list != null && !list.isEmpty()) {
            return;
        }
        String legacyApi = Hawk.get(HawkConfig.API_URL, "");
        if (legacyApi == null || legacyApi.trim().isEmpty()) {
            return;
        }
        String legacyLive = Hawk.get(HawkConfig.LIVE_API_URL, "");
        String legacyEpg = Hawk.get(HawkConfig.EPG_URL, "");
        ConfigBean bean = new ConfigBean("默认配置", legacyApi.trim());
        bean.liveUrl = legacyLive;
        bean.epgUrl = legacyEpg;
        list = new ArrayList<>();
        list.add(bean);
        saveConfigs(list);
        // 迁移出来的这条就是主页配置
        setHomeConfigId(bean.id);
    }
}
