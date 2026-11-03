package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 去广告（m3u8 净化）的安全闸。
 *
 * <p>该功能默认开启（{@link HawkConfig#DEFAULT_M3U8_PURIFY}），所以净化逻辑
 * 会作用于全部用户的每一次 m3u8 播放。核心约束是「宁可不去广告，也不能把正片删坏」：
 * 净化结果必须仍是可播放的媒体播放列表，且误删过多时回退原文。
 */
public class M3u8PurifyTest {

    private static final String BASE = "https://main.example.com/vod/";

    /** 20 个正片切片 + 3 个来自广告域名的切片。 */
    private String playlistWithAdHost() {
        StringBuilder sb = new StringBuilder();
        sb.append("#EXTM3U\n");
        sb.append("#EXT-X-VERSION:3\n");
        sb.append("#EXT-X-TARGETDURATION:5\n");
        for (int i = 1; i <= 20; i++) {
            sb.append("#EXTINF:5.000,\n");
            sb.append("seg").append(pad(i)).append(".ts\n");
        }
        sb.append("#EXT-X-DISCONTINUITY\n");
        for (int i = 1; i <= 3; i++) {
            sb.append("#EXTINF:4.000,\n");
            sb.append("https://ads.example.com/ad/").append(pad(i)).append(".ts\n");
        }
        sb.append("#EXT-X-DISCONTINUITY\n");
        for (int i = 21; i <= 25; i++) {
            sb.append("#EXTINF:5.000,\n");
            sb.append("seg").append(pad(i)).append(".ts\n");
        }
        sb.append("#EXT-X-ENDLIST\n");
        return sb.toString();
    }

    /** 纯净化内容，无任何广告痕迹。 */
    private String cleanPlaylist() {
        StringBuilder sb = new StringBuilder();
        sb.append("#EXTM3U\n");
        sb.append("#EXT-X-VERSION:3\n");
        sb.append("#EXT-X-TARGETDURATION:5\n");
        for (int i = 1; i <= 20; i++) {
            sb.append("#EXTINF:5.000,\n");
            sb.append("seg").append(pad(i)).append(".ts\n");
        }
        sb.append("#EXT-X-ENDLIST\n");
        return sb.toString();
    }

    private static String pad(int i) {
        return i < 10 ? "000" + i : "00" + i;
    }

    private static int countMediaLines(String content) {
        int n = 0;
        for (String line : content.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) n++;
        }
        return n;
    }

    private static int countExtInf(String content) {
        int n = 0;
        for (String line : content.split("\n")) {
            if (line.trim().startsWith("#EXTINF")) n++;
        }
        return n;
    }

    @Test
    public void returnsNullForNonPlaylistContent() {
        assertNull(M3u8.purify(BASE, "{\"code\":1}"));
        assertNull(M3u8.purify(BASE, ""));
        assertNull(M3u8.purify(BASE, null));
    }

    @Test
    public void cleanPlaylistReportsNoAd() {
        String content = cleanPlaylist();
        M3u8.purify(BASE, content);
        assertEquals("无广告的播放列表不该报告任何删除", 0, M3u8.currentAdCount);
    }

    @Test
    public void removesAdHostSegmentsAndKeepsAllMainContent() {
        String content = playlistWithAdHost();
        String result = M3u8.purify(BASE, content);

        assertEquals("应删掉 3 个广告切片", 3, M3u8.currentAdCount);
        assertTrue("广告域名不该残留", !result.contains("ads.example.com"));
        assertEquals("25 个正片切片必须全部保留", 25, countMediaLines(result));
    }

    @Test
    public void resultStaysPlayableAfterPurify() {
        String result = M3u8.purify(BASE, playlistWithAdHost());

        assertTrue("净化结果必须仍是媒体播放列表", result.startsWith("#EXTM3U"));
        assertEquals("每个切片都要有配对的 EXTINF，不能出现悬空标记",
                countMediaLines(result), countExtInf(result));
        assertTrue("点播列表必须保留 ENDLIST", result.contains("#EXT-X-ENDLIST"));
        assertTrue("正片首切片不能丢", result.contains("seg0001.ts"));
        assertTrue("正片末切片不能丢", result.contains("seg0025.ts"));
    }

    @Test
    public void neverRemovesMostOfPlaylist() {
        // 构造一个「看起来大半都像广告」的列表：正片分散在多个不同前缀下。
        // 任何单一过滤策略都不该把超过一半的切片删掉；触发保护时必须回退原文。
        StringBuilder sb = new StringBuilder();
        sb.append("#EXTM3U\n");
        for (int i = 1; i <= 40; i++) {
            sb.append("#EXTINF:5.000,\n");
            sb.append("https://host").append(i).append(".example.com/p").append(i)
                    .append("/x").append(pad(i)).append(".ts\n");
        }
        sb.append("#EXT-X-ENDLIST\n");
        String original = sb.toString();

        String result = M3u8.purify(BASE, original);

        assertTrue("误删比例过高时必须回退原文", M3u8.currentAdCount <= countMediaLines(original) / 2);
        assertTrue("回退后仍应可播放", countMediaLines(result) > 0);
    }
}
