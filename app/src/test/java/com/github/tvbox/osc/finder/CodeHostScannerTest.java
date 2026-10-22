package com.github.tvbox.osc.finder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.List;

public class CodeHostScannerTest {

    @Test
    public void parsesGitlabAndGiteeSeedUrls() {
        CodeHostScanner.Repo gitlab = CodeHostScanner.parseSeed(
                CodeHostScanner.Platform.GITLAB,
                "https://gitlab.com/noimank/tvbox@main");
        assertNotNull(gitlab);
        assertEquals("noimank/tvbox", gitlab.fullName());
        assertEquals("main", gitlab.branch());

        CodeHostScanner.Repo gitee = CodeHostScanner.parseSeed(
                CodeHostScanner.Platform.GITEE, "owner/repo");
        assertNotNull(gitee);
        assertEquals("owner/repo", gitee.fullName());

        assertNull(CodeHostScanner.parseSeed(
                CodeHostScanner.Platform.GITLAB, "not-a-repository"));
    }

    @Test
    public void buildsPlatformRawUrls() {
        CodeHostScanner gitlab = new CodeHostScanner(
                CodeHostScanner.Platform.GITLAB, new SourceIndex.Gitlab());
        assertEquals("https://gitlab.com/noimank/tvbox/-/raw/main/tvboxmuti.json",
                gitlab.rawUrl("noimank/tvbox", "main", "tvboxmuti.json"));

        CodeHostScanner gitee = new CodeHostScanner(
                CodeHostScanner.Platform.GITEE, new SourceIndex.Gitee());
        assertEquals("https://gitee.com/owner/repo/raw/main/tvbox.json",
                gitee.rawUrl("owner/repo", "main", "tvbox.json"));
    }

    @Test
    public void parsesSearchResultsFromBothPlatforms() {
        String gitlab = "[{\"path_with_namespace\":\"noimank/tvbox\","
                + "\"default_branch\":\"main\",\"star_count\":12}]";
        List<CodeHostScanner.Repo> gl = CodeHostScanner.parseRepos(gitlab);
        assertEquals(1, gl.size());
        assertEquals("noimank/tvbox", gl.get(0).fullName());
        assertEquals(12, gl.get(0).stars());

        String gitee = "[{\"full_name\":\"owner/repo\","
                + "\"default_branch\":\"master\",\"stargazers_count\":8}]";
        List<CodeHostScanner.Repo> ge = CodeHostScanner.parseRepos(gitee);
        assertEquals(1, ge.size());
        assertEquals("owner/repo", ge.get(0).fullName());
        assertEquals("master", ge.get(0).branch());
    }
}
