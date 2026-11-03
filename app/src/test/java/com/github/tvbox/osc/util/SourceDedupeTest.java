package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.github.tvbox.osc.bean.SourceBean;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 站点后端去重的纯函数测试：搜索/换源不应重复请求同一采集站。
 */
public class SourceDedupeTest {

    private static SourceBean site(String key, String name, int type, String api) {
        return site(key, name, type, api, "", "");
    }

    private static SourceBean site(String key, String name, int type, String api, String ext, String jar) {
        SourceBean bean = new SourceBean();
        bean.setKey(key);
        bean.setName(name);
        bean.setType(type);
        bean.setApi(api);
        bean.setExt(ext);
        bean.setJar(jar);
        return bean;
    }

    @Test
    public void sameBackendDifferentSchemeAndSlashIsDeduped() {
        List<SourceBean> out = SourceDedupe.dedupe(Arrays.asList(
                site("a", "影视 • 量子[直连]", 1, "https://cj.lziapi.com/api.php/provide/vod/"),
                site("b", "❤量子资源2", 1, "http://cj.lziapi.com/api.php/provide/vod"),
                site("c", "量子", 1, "https://CJ.LZIAPI.com/api.php/provide/vod///")));
        assertEquals(1, out.size());
        assertEquals("a", out.get(0).getKey());
    }

    @Test
    public void atXmlSuffixIsSameBackend() {
        List<SourceBean> out = SourceDedupe.dedupe(Arrays.asList(
                site("a", "非凡", 1, "http://cj.ffzyapi.com/api.php/provide/vod"),
                site("b", "❤非凡资源", 1, "https://cj.ffzyapi.com/api.php/provide/vod/at/xml/")));
        assertEquals(1, out.size());
        assertEquals("a", out.get(0).getKey());
    }

    @Test
    public void differentBackendsAreKept() {
        List<SourceBean> out = SourceDedupe.dedupe(Arrays.asList(
                site("a", "量子", 1, "https://cj.lziapi.com/api.php/provide/vod"),
                site("b", "非凡", 1, "http://cj.ffzyapi.com/api.php/provide/vod"),
                site("c", "电影天堂", 0, "http://caiji.dyttzyapi.com/api.php/provide/vod/at/xml")));
        assertEquals(3, out.size());
    }

    @Test
    public void spiderSitesWithDifferentExtAreKept() {
        List<SourceBean> out = SourceDedupe.dedupe(Arrays.asList(
                site("x1", "樱花1", 3, "csp_xbpq", "https://a.com/1.json", ""),
                site("x2", "樱花2", 3, "csp_xbpq", "https://b.com/2.json", ""),
                site("x3", "樱花3", 3, "csp_xbpq", "https://a.com/1.json", "https://jar/x.jar")));
        assertEquals(3, out.size());
    }

    @Test
    public void identicalSpiderSitesAreDeduped() {
        List<SourceBean> out = SourceDedupe.dedupe(Arrays.asList(
                site("x1", "豆瓣┃搜索", 3, "csp_Douban", "", "https://jar/fan.txt"),
                site("x2", "豆瓣搜索（qist/tvbox）", 3, "csp_Douban", "", "https://jar/fan.txt")));
        assertEquals(1, out.size());
        assertEquals("x1", out.get(0).getKey());
    }

    @Test
    public void orderIsPreservedAndFirstWins() {
        List<SourceBean> out = SourceDedupe.dedupe(Arrays.asList(
                site("keep", "第一个", 1, "http://same.host/api"),
                site("b", "中间不同", 1, "http://other.host/api"),
                site("drop", "重复的", 1, "https://same.host/api/")));
        assertEquals(2, out.size());
        assertEquals("keep", out.get(0).getKey());
        assertEquals("b", out.get(1).getKey());
    }

    @Test
    public void nullAndEmptyAreSafe() {
        assertNull(SourceDedupe.backendSig(null));
        assertEquals(0, SourceDedupe.dedupe(null).size());
        assertEquals(0, SourceDedupe.dedupe(new ArrayList<SourceBean>()).size());
        List<SourceBean> out = SourceDedupe.dedupe(Arrays.asList(
                site("a", "含空api", 1, "  "),
                null));
        assertEquals(2, out.size());
        assertNull(out.get(1));
    }
}
