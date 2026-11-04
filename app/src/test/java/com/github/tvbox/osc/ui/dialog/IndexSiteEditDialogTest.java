package com.github.tvbox.osc.ui.dialog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class IndexSiteEditDialogTest {

    @Test
    public void normalizesHttpsBaseUrl() {
        assertEquals("https://bdnb.cn",
                IndexSiteEditDialog.normalizeBaseUrl("bdnb.cn/"));
        assertEquals("https://mirror.example.com/tvbox",
                IndexSiteEditDialog.normalizeBaseUrl("https://mirror.example.com/tvbox/"));
    }

    @Test
    public void rejectsUnsupportedOrInvalidSchemes() {
        assertNull(IndexSiteEditDialog.normalizeBaseUrl("http://bdnb.cn"));
        assertNull(IndexSiteEditDialog.normalizeBaseUrl("ftp://bdnb.cn"));
        assertNull(IndexSiteEditDialog.normalizeBaseUrl("https://"));
        assertNull(IndexSiteEditDialog.normalizeBaseUrl(""));
    }

    @Test
    public void parsesQueriesByLineAndDeduplicates() {
        assertEquals(Arrays.asList("tvbox config", "影视仓"),
                IndexSiteEditDialog.parseQueries(
                        " tvbox config \r\n\r\n影视仓\ntvbox config\n"));
        assertTrue(IndexSiteEditDialog.parseQueries(null).isEmpty());
    }
}
