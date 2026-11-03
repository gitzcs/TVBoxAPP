package com.github.tvbox.osc.finder;

/**
 * 找源模块的 HTTP 结果包装。
 *
 * <p>对应 TVApp 工程里的 {@code com.video.tvapp.net.Result}，
 * 但实现放在本包里，避免为了移植而引入 TVApp 的整个 net 模块。
 */
public class FinderResult {

    private final boolean ok;
    private final String data;
    private final String message;
    /** HTTP 状态码；非 HTTP 失败（超时、URL 非法等）为 0。 */
    private final int httpCode;

    private FinderResult(boolean ok, String data, String message, int httpCode) {
        this.ok = ok;
        this.data = data;
        this.message = message;
        this.httpCode = httpCode;
    }

    public static FinderResult ok(String data) {
        return new FinderResult(true, data == null ? "" : data, null, 0);
    }

    public static FinderResult fail(String message) {
        return new FinderResult(false, "", message, 0);
    }

    /** 带 HTTP 状态码的失败。 */
    public static FinderResult fail(int httpCode, String message) {
        return new FinderResult(false, "", message, httpCode);
    }

    public boolean isOk() {
        return ok;
    }

    /**
     * 是否是「资源确定不存在」（404 / 410）。
     *
     * <p>这类失败换镜像也没用——猜文件名猜错时，raw 与 jsDelivr / gitmirror 服务的是
     * 同一份仓库文件，必然一起 404。区分出它可以避免为不存在的文件白等一次
     * 镜像超时（实测 jsDelivr 会吃满 15s）。
     *
     * <p>网络不通、超时、5xx 都<b>不</b>算，这些情况下镜像确实可能救回来。
     */
    public boolean isNotFound() {
        return !ok && (httpCode == 404 || httpCode == 410);
    }

    public int getHttpCode() {
        return httpCode;
    }

    /** 响应正文；失败时为空串。 */
    public String getData() {
        return data;
    }

    /** 失败原因；成功时为 null。 */
    public String getMessage() {
        return message;
    }
}
