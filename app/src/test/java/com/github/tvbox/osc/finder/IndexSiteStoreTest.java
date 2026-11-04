package com.github.tvbox.osc.finder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class IndexSiteStoreTest {

    @Test
    public void encodeDecodePreservesSiteSettings() {
        SourceIndex.IndexSite site = new SourceIndex.IndexSite(
                "镜像", "https://mirror.example.com",
                new ArrayList<>(Arrays.asList("tvbox config", "影视仓")), true);
        site.setEnabled(false);

        List<SourceIndex.IndexSite> decoded = IndexSiteStore.decode(
                IndexSiteStore.encode(Arrays.asList(site)), new ArrayList<SourceIndex.IndexSite>());

        assertEquals(1, decoded.size());
        assertEquals("镜像", decoded.get(0).getName());
        assertEquals("https://mirror.example.com", decoded.get(0).getBaseUrl());
        assertEquals(Arrays.asList("tvbox config", "影视仓"), decoded.get(0).getQueries());
        assertEquals(false, decoded.get(0).isEnabled());
    }

    @Test
    public void malformedJsonFallsBackToDefaults() {
        SourceIndex.IndexSite fallback = new SourceIndex.IndexSite(
                "默认", "https://default.example.com",
                new ArrayList<>(Arrays.asList("tvbox")), true);

        List<SourceIndex.IndexSite> decoded = IndexSiteStore.decode(
                "not-json", Arrays.asList(fallback));

        assertEquals(1, decoded.size());
        assertEquals("默认", decoded.get(0).getName());
        assertEquals("https://default.example.com", decoded.get(0).getBaseUrl());
        assertEquals("默认", IndexSiteStore.decode(
                "null", Arrays.asList(fallback)).get(0).getName());
    }

    @Test
    public void emptyStoredListRemainsExplicitlyEmpty() {
        SourceIndex.IndexSite fallback = new SourceIndex.IndexSite(
                "默认", "https://default.example.com",
                new ArrayList<>(Arrays.asList("tvbox")), true);

        List<SourceIndex.IndexSite> decoded = IndexSiteStore.decode(
                "[]", Arrays.asList(fallback));

        assertTrue(decoded.isEmpty());
    }

    @Test
    public void summaryCountsOnlyEnabledSites() {
        SourceIndex.IndexSite first = new SourceIndex.IndexSite(
                "A", "https://a.example.com", new ArrayList<String>(), true);
        SourceIndex.IndexSite second = new SourceIndex.IndexSite(
                "B", "https://b.example.com", new ArrayList<String>(), true);
        SourceIndex.IndexSite disabled = new SourceIndex.IndexSite(
                "C", "https://c.example.com", new ArrayList<String>(), false);

        assertEquals("A", IndexSiteStore.summary(Arrays.asList(first, disabled)));
        assertEquals("2 个启用", IndexSiteStore.summary(Arrays.asList(first, second)));
        assertEquals("已停用", IndexSiteStore.summary(Arrays.asList(disabled)));
    }

    @Test
    public void customSitesReplaceDefaultsAndDisabledSitesAreSkipped() {
        SourceIndex index = SourceIndex.parse("{\"indexSites\":[{"
                + "\"name\":\"default\",\"baseUrl\":\"https://default.example.com\""
                + "}]}");
        SourceIndex.IndexSite enabled = new SourceIndex.IndexSite(
                "A", "https://a.example.com", Arrays.asList("tvbox"), true);
        SourceIndex.IndexSite disabled = new SourceIndex.IndexSite(
                "B", "https://b.example.com", Arrays.asList("tvbox"), false);

        index.setIndexSites(Arrays.asList(enabled, disabled));

        assertEquals(1, index.indexSites().size());
        assertEquals("A", index.indexSites().get(0).getName());
    }
}
