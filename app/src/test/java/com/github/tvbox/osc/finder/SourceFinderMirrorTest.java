package com.github.tvbox.osc.finder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * GitHub raw 镜像候选地址的生成规则（jar 下载与配置探测共用）。
 */
public class SourceFinderMirrorTest {

    @Test
    public void rawGithubUrl_getsTwoMirrors() {
        List<String> mirrors = SourceFinder.mirrorsFor(
                "https://raw.githubusercontent.com/qist/tvbox/master/jar/custom_spider.jar;md5;abc");
        assertEquals(2, mirrors.size());
        assertEquals("https://cdn.jsdelivr.net/gh/qist/tvbox@master/jar/custom_spider.jar;md5;abc",
                mirrors.get(0));
        assertEquals("https://raw.gitmirror.com/qist/tvbox/master/jar/custom_spider.jar;md5;abc",
                mirrors.get(1));
    }

    @Test
    public void webRawUrl_getsMirrors() {
        List<String> mirrors = SourceFinder.mirrorsFor(
                "https://github.com/qist/tvbox/raw/master/jsm.json");
        assertEquals(2, mirrors.size());
        assertEquals("https://cdn.jsdelivr.net/gh/qist/tvbox@master/jsm.json", mirrors.get(0));
    }

    @Test
    public void webBlobUrl_getsMirrors() {
        List<String> mirrors = SourceFinder.mirrorsFor(
                "https://github.com/qist/tvbox/blob/master/jsm.json");
        assertEquals("https://cdn.jsdelivr.net/gh/qist/tvbox@master/jsm.json", mirrors.get(0));
    }

    @Test
    public void nonGithubUrl_hasNoMirror() {
        assertTrue(SourceFinder.mirrorsFor("https://raw.giteeusercontent.com/gaoops404/tvbox-config/raw/main/tvbox.json").isEmpty());
        assertTrue(SourceFinder.mirrorsFor("http://tv.nxog.top/m/jar.php?id=ou").isEmpty());
    }

    @Test
    public void nullOrEmpty_hasNoMirror() {
        assertTrue(SourceFinder.mirrorsFor(null).isEmpty());
        assertTrue(SourceFinder.mirrorsFor("  ").isEmpty());
    }

    @Test
    public void noDuplicateMirrors() {
        // jsDelivr 与 gitmirror 生成的地址必须互不相同
        List<String> mirrors = SourceFinder.mirrorsFor(
                "https://raw.githubusercontent.com/a/b/main/c.jar");
        assertEquals(2, new java.util.HashSet<String>(mirrors).size());
    }
}
