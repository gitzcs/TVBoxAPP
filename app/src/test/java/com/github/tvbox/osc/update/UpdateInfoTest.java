package com.github.tvbox.osc.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * update.json 解析与版本比较的纯函数测试。
 */
public class UpdateInfoTest {

    @Test
    public void parsesFullJson() {
        UpdateInfo info = UpdateInfo.parse(
                "{\"versionCode\":2,\"versionName\":\"1.1.0\",\"changelog\":\"修复若干问题\","
                        + "\"sha256\":\"ABCDEF\",\"apk\":[\"https://a/x.apk\",\"https://b/x.apk\"]}");
        assertEquals(2, info.versionCode);
        assertEquals("1.1.0", info.versionName);
        assertEquals("修复若干问题", info.changelog);
        assertEquals("ABCDEF", info.sha256);
        assertEquals(2, info.apk.size());
        assertTrue(info.isNewerThan(1));
        assertFalse(info.isNewerThan(2));
        assertFalse(info.isNewerThan(3));
    }

    @Test
    public void filtersInvalidUrlsAndEmptyLists() {
        assertNull(UpdateInfo.parse("{\"versionCode\":2,\"apk\":[\"ftp://x\",\"  \"]}"));
        assertNull(UpdateInfo.parse("{\"versionCode\":2,\"apk\":[]}"));
        UpdateInfo info = UpdateInfo.parse(
                "{\"versionCode\":2,\"apk\":[\" javascript:0 \",\" https://ok/x.apk \"]}");
        assertEquals(1, info.apk.size());
        assertEquals("https://ok/x.apk", info.apk.get(0));
    }

    @Test
    public void rejectsIncompleteInput() {
        assertNull(UpdateInfo.parse(null));
        assertNull(UpdateInfo.parse(""));
        assertNull(UpdateInfo.parse("   "));
        assertNull(UpdateInfo.parse("not json"));
        assertNull(UpdateInfo.parse("{}"));
        assertNull(UpdateInfo.parse("{\"versionName\":\"1.1.0\",\"apk\":[\"https://a/x.apk\"]}"));
        assertNull(UpdateInfo.parse("{\"versionCode\":0,\"apk\":[\"https://a/x.apk\"]}"));
    }

    @Test
    public void versionCodeMustIncrease() {
        UpdateInfo info = UpdateInfo.parse("{\"versionCode\":5,\"apk\":[\"https://a/x.apk\"]}");
        assertTrue(info.isNewerThan(4));
        assertFalse(info.isNewerThan(5));
        assertFalse(info.isNewerThan(6));
    }
}
