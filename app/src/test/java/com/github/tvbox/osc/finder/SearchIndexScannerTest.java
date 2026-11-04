package com.github.tvbox.osc.finder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class SearchIndexScannerTest {

    @Test
    public void parsesWrappedSearchResponse() {
        String json = "{"
                + "\"code\":0,\"message\":\"ok\",\"data\":{"
                + "\"total_count\":2,\"items\":["
                + "{\"full_name\":\"owner/a\",\"default_branch\":\"master\","
                + "\"stargazers_count\":12,\"description\":\"TVBox config\"},"
                + "{\"full_name\":\"owner/b\",\"default_branch\":null,"
                + "\"stargazers_count\":\"7\",\"description\":null}"
                + "]}}";

        List<RepoScanner.Repo> repos = SearchIndexScanner.parseRepos(json, 10);

        assertEquals(2, repos.size());
        assertEquals("owner/a", repos.get(0).fullName());
        assertEquals("master", repos.get(0).branch());
        assertEquals(12, repos.get(0).stars());
        assertEquals("owner/b", repos.get(1).fullName());
        assertEquals("缺省分支应回落到 main", "main", repos.get(1).branch());
        assertEquals(7, repos.get(1).stars());
    }

    @Test
    public void acceptsUnwrappedItemsAndDeduplicates() {
        String json = "{\"items\":["
                + "{\"full_name\":\"owner/a\",\"default_branch\":\"main\"},"
                + "{\"full_name\":\"owner/a\",\"default_branch\":\"dev\"},"
                + "{\"full_name\":\"bad\"},"
                + "{\"full_name\":\"too/many/parts\"}"
                + "]}";

        List<RepoScanner.Repo> repos = SearchIndexScanner.parseRepos(json, 10);

        assertEquals(1, repos.size());
        assertEquals("owner/a", repos.get(0).fullName());
        assertEquals("main", repos.get(0).branch());
    }

    @Test
    public void respectsResultLimit() {
        String json = "{\"data\":{\"items\":["
                + "{\"full_name\":\"owner/a\"},"
                + "{\"full_name\":\"owner/b\"},"
                + "{\"full_name\":\"owner/c\"}"
                + "]}}";

        List<RepoScanner.Repo> repos = SearchIndexScanner.parseRepos(json, 2);

        assertEquals(2, repos.size());
        assertEquals("owner/a", repos.get(0).fullName());
        assertEquals("owner/b", repos.get(1).fullName());
    }

    @Test
    public void rejectsErrorAndMalformedResponses() {
        assertTrue(SearchIndexScanner.parseRepos(
                "{\"code\":403,\"message\":\"rate limited\"}", 5).isEmpty());
        assertTrue(SearchIndexScanner.parseRepos("not-json", 5).isEmpty());
        assertTrue(SearchIndexScanner.parseRepos("", 5).isEmpty());
        assertTrue(SearchIndexScanner.parseRepos("{\"data\":{}}", 0).isEmpty());
    }

    @Test
    public void buildsSearchUrlWithScopeAndPaging() {
        SourceIndex.IndexSite site = SourceIndex.parse("{"
                + "\"indexSites\":[{"
                + "\"baseUrl\":\"https://bdnb.cn/\","
                + "\"maxRepos\":8,\"perPage\":12,\"sort\":\"updated\",\"order\":\"desc\""
                + "}]}").indexSites().get(0);

        String url = SearchIndexScanner.searchUrl(site, "tvbox 接口", 8);

        assertTrue(url.startsWith("https://bdnb.cn/api/search?q=tvbox+%E6%8E%A5%E5%8F%A3"));
        assertTrue(url.contains("&sort=updated&order=desc&page=1"));
        assertTrue(url.contains("&per_page=8&scope=github"));
        assertFalse(url.contains("//api/search"));
    }
}
