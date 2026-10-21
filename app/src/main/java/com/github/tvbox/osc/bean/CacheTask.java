package com.github.tvbox.osc.bean;

import java.io.Serializable;
import java.util.HashMap;

/**
 * 离线缓存下载任务。
 *
 * <p>从 TVBoxOS2 移植，字段与那边保持一致，便于对照排查。
 */
public class CacheTask implements Serializable {

    /**
     * 显式序列版本号。
     *
     * <p>本类经 Hawk(Java 原生序列化)持久化。若省略, JVM 会根据类名与
     * 字段签名隐式计算, release 混淆改名后会变化, 导致升级后旧下载任务
     * 反序列化失败而静默丢失。配合 proguard-rules.pro 的 keep 规则一起使用。
     */
    private static final long serialVersionUID = 1L;

    public static final int STATE_WAIT = 0;       // 等待
    public static final int STATE_DOWNLOADING = 1; // 下载中
    public static final int STATE_DONE = 2;       // 已完成
    public static final int STATE_FAILED = 3;     // 失败
    public static final int STATE_PAUSED = 4;     // 已暂停

    public String id;
    public String name;      // 任务名称(片名+集数)
    public String url;       // 下载地址
    public String filePath;  // 保存路径
    public int state = STATE_WAIT;
    public long progress = 0; // 已下载字节
    public long total = 0;   // 总字节(0 表示未知)
    public String errorMsg;  // 失败原因(仅失败时有效)
    public HashMap<String, String> headers; // 下载请求头(UA/Referer/Cookie 等, 复用播放参数)
    public String pic;       // 视频缩略图(缓存管理页分组显示)
    public long bandwidthPref = 0; // 码率偏好(0=默认最小码率; >0 时优先选择不低于该码率的子流)

    public CacheTask() {
    }

    public CacheTask(String id, String name, String url, String filePath) {
        this.id = id;
        this.name = name;
        this.url = url;
        this.filePath = filePath;
    }
}