package com.github.tvbox.osc.bean;

import java.io.Serializable;
import java.util.UUID;

/**
 * 配置地址条目（多配置模式）。
 *
 * <p>支持：名称 / 配置地址 / 直播地址 / EPG 地址 / 代理 / 启用状态。
 *
 * <p>{@link #id} 用 UUID 而不是时间戳：毫秒级快速连续创建（如批量导入）
 * 时时间戳会重复，导致启用/删除/主页标记只命中第一个，出现「三地址绑定」问题。
 */
public class ConfigBean implements Serializable {

    /** 唯一标识 */
    public String id;
    /** 配置名称 */
    public String name;
    /** 配置地址（必填） */
    public String apiUrl;
    /** 直播地址（可选） */
    public String liveUrl;
    /** EPG 地址（可选） */
    public String epgUrl;
    /** 代理（可选） */
    public String proxy;
    /** 是否启用 */
    public boolean enabled;

    public ConfigBean() {
        this.id = UUID.randomUUID().toString();
        this.enabled = true;
    }

    public ConfigBean(String name, String apiUrl) {
        this();
        this.name = name;
        this.apiUrl = apiUrl;
    }

    /** 展示用名称：空则回落到地址。 */
    public String displayName() {
        if (name == null || name.trim().isEmpty()) {
            return apiUrl == null ? "未命名" : apiUrl;
        }
        return name;
    }

    /** 是否有直播地址。 */
    public boolean hasLive() {
        return liveUrl != null && !liveUrl.trim().isEmpty();
    }
}
