package com.github.tvbox.osc.finder;

import com.github.catvod.net.OkHttp;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

/**
 * 找源模块的 HTTP 封装。
 *
 * <p>用 A 现成的 {@link OkHttp} 而不是自己造轮子，为的是继承它的
 * TLS 1.2 兼容层（{@code SSLSocketFactoryCompat}）和 DoH DNS——
 * Android 4.4 不开 TLS1.2 时，用原生 HttpURLConnection 会全挂。
 *
 * <p>但有两个地方必须和主流程隔离：
 * <ul>
 *     <li><b>独立超时</b>：主流程 30s 太长，探针要的是「快速失败」。</li>
 *     <li><b>独立 tag</b>：A 里 {@code SearchActivity} 会 {@code cancelTag("search")}，
 *         用自己的 tag 才不会被误伤。</li>
 * </ul>
 */
public final class FinderHttp {

    /** 本模块所有请求的 tag，便于统一取消。 */
    public static final String TAG = "tvbox-finder";

    /** 单次请求默认超时（秒）。 */
    public static final int DEFAULT_TIMEOUT_SECONDS = 15;

    /** 探针请求超时（秒）——比整体探测预算短，快速失败。 */
    public static final int PROBE_TIMEOUT_SECONDS = 8;

    private FinderHttp() {
    }

    /** GET，返回文本。 */
    public static FinderResult get(String url) {
        return get(url, null, DEFAULT_TIMEOUT_SECONDS);
    }

    /** GET，带自定义请求头。 */
    public static FinderResult get(String url, Map<String, String> headers) {
        return get(url, headers, DEFAULT_TIMEOUT_SECONDS);
    }

    /** GET，带自定义请求头与超时。 */
    public static FinderResult get(String url, Map<String, String> headers, int timeoutSeconds) {
        if (url == null || !url.startsWith("http")) {
            return FinderResult.fail("URL 非法: " + url);
        }
        try {
            OkHttpClient client = OkHttp.client(TimeUnit.SECONDS.toMillis(timeoutSeconds));
            okhttp3.Request.Builder builder = new okhttp3.Request.Builder()
                    .url(url)
                    .tag(TAG)
                    .get();
            if (headers != null && !headers.isEmpty()) {
                builder.headers(okhttp3.Headers.of(headers));
            }
            try (okhttp3.Response response = client.newCall(builder.build()).execute()) {
                if (!response.isSuccessful()) {
                    return FinderResult.fail(response.code(), "HTTP " + response.code());
                }
                okhttp3.ResponseBody body = response.body();
                if (body == null) {
                    return FinderResult.fail("空响应体");
                }
                return FinderResult.ok(body.string());
            }
        } catch (Throwable th) {
            String msg = th.getMessage();
            return FinderResult.fail(msg == null ? th.getClass().getSimpleName() : msg);
        }
    }

    /** 取消本模块所有在途请求。 */
    public static void cancelAll() {
        try {
            OkHttp.cancel(OkHttp.client(), TAG);
        } catch (Throwable ignored) {
        }
    }
}
