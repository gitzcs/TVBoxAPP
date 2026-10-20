package com.github.tvbox.osc.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.github.catvod.net.OkHttp;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * m3u8 解析链路的端到端测试：真实拉取一个公开的 master playlist。
 *
 * <p>验证的是 {@code DownloadManager.downloadM3u8} 里最关键、也最容易出错的一段：
 * master playlist → 选子流 → 解析分片 → 拼绝对地址。
 *
 * <p>网络不通时用 {@code Assume} 跳过，不影响其它测试。
 */
public class M3u8ParseIntegrationTest {

    /** 公开的测试流，带 5 档码率的 master playlist。 */
    private static final String MASTER = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8";

    private static final Pattern BANDWIDTH = Pattern.compile("BANDWIDTH=(\\d+)");

    @Test
    public void parsesMasterPlaylistAndResolvesChildren() {
        String content = fetch(MASTER);
        org.junit.Assume.assumeTrue("网络不通，跳过 m3u8 端到端测试", content != null);
        assertNotNull(content);
        assertTrue("应为标准 m3u8 清单", content.startsWith("#EXTM3U"));
        assertTrue("应含 master 标记", content.contains("#EXT-X-STREAM-INF"));

        // 按 DownloadManager 的方式解析子流: [带宽, 地址]
        List<String[]> children = new ArrayList<>();
        String lastInf = null;
        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                lastInf = line;
                continue;
            }
            if (line.isEmpty() || line.startsWith("#")) {
                lastInf = null;
                continue;
            }
            long bw = -1;
            if (lastInf != null) {
                Matcher m = BANDWIDTH.matcher(lastInf);
                if (m.find()) bw = Long.parseLong(m.group(1));
            }
            children.add(new String[]{String.valueOf(bw), line});
            lastInf = null;
        }

        assertTrue("应解析出多档码率子流", children.size() >= 2);
        for (String[] c : children) {
            assertTrue("子流应带 BANDWIDTH", Long.parseLong(c[0]) > 0);
            assertTrue("子流地址不应为空", c[1] != null && !c[1].isEmpty());
        }

        // 相对地址必须能拼成绝对地址
        String child = DownloadManager.resolveUrl(MASTER, children.get(0)[1]);
        assertTrue("子流应为绝对地址: " + child, child.startsWith("http"));
        assertTrue("子流应仍在同一 host 下: " + child, child.startsWith("https://test-streams.mux.dev/"));

        // 子流本身也应是标准 m3u8（媒体清单），并含分片
        String media = fetch(child);
        org.junit.Assume.assumeTrue("子流拉取失败，跳过", media != null);
        assertTrue("子流应为标准 m3u8", media.startsWith("#EXTM3U"));

        List<String> segments = new ArrayList<>();
        for (String line : media.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            segments.add(line);
        }
        assertTrue("子流应含分片", segments.size() > 0);

        // 分片地址同样要能解析成绝对地址
        String seg = DownloadManager.resolveUrl(child, segments.get(0));
        assertTrue("分片应为绝对地址: " + seg, seg.startsWith("http"));

        // 拉一个分片，确认是真二进制 TS 而不是错误页
        byte[] data = fetchBytes(seg);
        org.junit.Assume.assumeTrue("分片拉取失败，跳过", data != null && data.length > 0);
        assertTrue("分片不应是 HTML 错误页", !DownloadManager.isHtmlResponse(data, data.length));
        assertEquals("TS 分片应以 0x47 同步字节开头", (byte) 0x47, data[0]);
        assertEquals("TS 分片应为 188 字节对齐", 0, data.length % 188);
    }

    private static String fetch(String url) {
        byte[] b = fetchBytes(url);
        if (b == null) return null;
        return new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] fetchBytes(String url) {
        try (okhttp3.Response res = OkHttp.newCall(url).execute()) {
            if (!res.isSuccessful()) return null;
            okhttp3.ResponseBody body = res.body();
            if (body == null) return null;
            InputStream is = body.byteStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int len;
            while ((len = is.read(buf)) > 0) bos.write(buf, 0, len);
            return bos.toByteArray();
        } catch (Throwable th) {
            return null;
        }
    }
}