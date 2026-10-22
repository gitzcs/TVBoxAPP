package com.github.tvbox.osc.finder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.github.tvbox.osc.util.live.TxtSubscribe;

import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

public class BuiltInConfigTest {

    @Test
    public void qistBuiltInKeepsOnlyVerifiedCollectorSites() throws Exception {
        JsonObject config = JsonParser.parseString(read("qist_tvbox.json")).getAsJsonObject();
        assertEquals("", config.get("spider").getAsString());
        JsonArray sites = config.getAsJsonArray("sites");
        assertEquals(10, sites.size());
        for (JsonElement element : sites) {
            JsonObject site = element.getAsJsonObject();
            int type = site.get("type").getAsInt();
            assertTrue("仅保留已通过分类探测的采集站", type == 0 || type == 1 || type == 4);
            assertFalse(site.get("api").getAsString().trim().isEmpty());
        }
    }

    @Test
    public void noimankMultiConfigIsBundled() throws Exception {
        JsonObject config = JsonParser.parseString(
                read("noimank_tvboxmuti.json")).getAsJsonObject();
        assertTrue(config.has("urls"));
        assertFalse(config.getAsJsonArray("urls").isEmpty());
    }

    @Test
    public void defaultLiveConfigHasChannelListUrl() throws Exception {
        JsonObject config = JsonParser.parseString(read("qist_tvbox.json")).getAsJsonObject();
        JsonObject live = config.getAsJsonArray("lives").get(0).getAsJsonObject();
        assertEquals("电视直播", live.get("name").getAsString());
        assertTrue(live.get("url").getAsString().contains("tvboxtv.txt"));
    }

    @Test
    public void builtInLiveFallbackContainsPlayableChannels() throws Exception {
        JsonArray groups = TxtSubscribe.parseToJsonArray(read("live_fallback.m3u"));
        assertFalse(groups.isEmpty());
        JsonArray channels = groups.get(0).getAsJsonObject().getAsJsonArray("channels");
        assertFalse(channels.isEmpty());
        assertTrue(channels.get(0).getAsJsonObject()
                .getAsJsonArray("urls").get(0).getAsString().startsWith("http"));
    }

    private static String read(String name) throws Exception {
        String[] roots = {"src/main/assets/", "app/src/main/assets/"};
        for (String root : roots) {
            File file = new File(root + name);
            if (!file.isFile()) continue;
            byte[] bytes = new byte[(int) file.length()];
            InputStream in = new FileInputStream(file);
            try {
                int offset = 0;
                int count;
                while (offset < bytes.length
                        && (count = in.read(bytes, offset, bytes.length - offset)) > 0) {
                    offset += count;
                }
            } finally {
                in.close();
            }
            return new String(bytes, "UTF-8");
        }
        throw new java.io.FileNotFoundException(name);
    }
}
