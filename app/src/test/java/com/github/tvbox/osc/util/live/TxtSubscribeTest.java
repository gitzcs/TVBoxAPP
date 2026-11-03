package com.github.tvbox.osc.util.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.Test;

public class TxtSubscribeTest {

    @Test
    public void m3uHeaderWithoutChannelsParsesToEmptyList() {
        JsonArray groups = TxtSubscribe.parseToJsonArray(
                "#EXTM3U x-tvg-url=\"https://example.com/epg.xml\"\n");

        assertTrue(groups.isEmpty());
    }

    @Test
    public void m3uChannelParsesNameAndStreamUrl() {
        JsonArray groups = TxtSubscribe.parseToJsonArray(
                "#EXTM3U\n"
                        + "#EXTINF:-1 tvg-id=\"cctv1\" group-title=\"央视\",CCTV1\n"
                        + "https://example.com/cctv1.m3u8\n");

        assertEquals(1, groups.size());
        JsonObject group = groups.get(0).getAsJsonObject();
        assertEquals("央视", group.get("group").getAsString());
        JsonObject channel = group.getAsJsonArray("channels").get(0).getAsJsonObject();
        assertEquals("CCTV1", channel.get("name").getAsString());
        assertEquals("https://example.com/cctv1.m3u8",
                channel.getAsJsonArray("urls").get(0).getAsString());
    }
}
