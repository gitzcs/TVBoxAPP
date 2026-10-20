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

    private FinderResult(boolean ok, String data, String message) {
        this.ok = ok;
        this.data = data;
        this.message = message;
    }

    public static FinderResult ok(String data) {
        return new FinderResult(true, data == null ? "" : data, null);
    }

    public static FinderResult fail(String message) {
        return new FinderResult(false, "", message);
    }

    public boolean isOk() {
        return ok;
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
