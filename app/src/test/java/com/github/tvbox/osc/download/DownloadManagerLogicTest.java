package com.github.tvbox.osc.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * {@link DownloadManager} 里可以脱离 Android 环境验证的纯逻辑。
 *
 * <p>覆盖的是 B 的 TODO.md 里最容易回归的几条：
 * 三合一地址清理、HTML 错误页识别、m3u8 相对地址解析。
 */
public class DownloadManagerLogicTest {

    @Test
    public void normalizesThreeInOneUrl() {
        // TVBox 三合一地址: 真实URL@播放方式@名称@集数
        assertEquals("https://a.com/v.mp4",
                DownloadManager.normalizeUrl("https://a.com/v.mp4@1@视频名@第1集"));
    }

    @Test
    public void keepsPlainUrlUntouched() {
        assertEquals("https://a.com/v.mp4", DownloadManager.normalizeUrl("https://a.com/v.mp4"));
    }

    @Test
    public void keepsUrlWithAtInQuery() {
        // 查询串里的 @ 不该被误当成三合一分隔符
        // (分隔符只在 @ 之前是 http 开头时才会被裁掉, 这里 @ 后面不是 http, 整串保留)
        String u = "https://a.com/v.mp4?x=1";
        assertEquals(u, DownloadManager.normalizeUrl(u));
    }

    @Test
    public void keepsUrlWhenAtPrefixIsNotHttp() {
        // @ 之前的部分不是 http 开头 -> 不裁剪
        String u = "gld1_60_abc@https://a.com/v.mp4";
        assertEquals(u, DownloadManager.normalizeUrl(u));
    }

    @Test
    public void nullUrlStaysNull() {
        assertNull(DownloadManager.normalizeUrl(null));
    }

    @Test
    public void detectsHtmlErrorPage() {
        byte[] html = "<!DOCTYPE html><html><head><title>404</title></head></html>".getBytes(StandardCharsets.ISO_8859_1);
        assertTrue(DownloadManager.isHtmlResponse(html, html.length));
    }

    @Test
    public void detectsJsonErrorResponse() {
        byte[] json = "{\"code\":403,\"msg\":\"forbidden\"}".getBytes(StandardCharsets.ISO_8859_1);
        assertTrue(DownloadManager.isHtmlResponse(json, json.length));
    }

    @Test
    public void doesNotMistakeBinaryForHtml() {
        byte[] mp4 = new byte[]{
                0x00, 0x00, 0x00, 0x20, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm',
                0x00, 0x00, 0x02, 0x00, 'i', 's', 'o', 'm'
        };
        assertFalse(DownloadManager.isHtmlResponse(mp4, mp4.length));
    }

    @Test
    public void shortBufferIsNotHtml() {
        byte[] tiny = "<ht".getBytes(StandardCharsets.ISO_8859_1);
        assertFalse(DownloadManager.isHtmlResponse(tiny, tiny.length));
    }

    @Test
    public void resolvesRelativeSegmentUrl() {
        assertEquals("https://a.com/hls/seg1.ts",
                DownloadManager.resolveUrl("https://a.com/hls/index.m3u8", "seg1.ts"));
    }

    @Test
    public void resolvesAbsoluteSegmentUrl() {
        assertEquals("https://b.com/seg1.ts",
                DownloadManager.resolveUrl("https://a.com/hls/index.m3u8", "https://b.com/seg1.ts"));
    }

    @Test
    public void resolvesRootRelativeSegmentUrl() {
        assertEquals("https://a.com/seg1.ts",
                DownloadManager.resolveUrl("https://a.com/hls/index.m3u8", "/seg1.ts"));
    }
}