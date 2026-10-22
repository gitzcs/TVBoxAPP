package com.github.tvbox.osc.finder;

import static org.junit.Assert.assertSame;

import com.github.tvbox.osc.bean.ConfigBean;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class AutoSourceManagerTest {

    @Test
    public void keepsSelectedConfigWhenItRemainsInUpdatedList() {
        ConfigBean first = new ConfigBean("first", "https://example.com/first.json");
        ConfigBean selected = new ConfigBean("selected", "https://example.com/selected.json");

        ConfigBean applied = AutoSourceManager.selectConfigToApply(
                Arrays.asList(first, selected),
                Collections.singletonList(first),
                selected.id);

        assertSame(selected, applied);
    }

    @Test
    public void selectsFirstWhenCurrentConfigWasRemoved() {
        ConfigBean first = new ConfigBean("first", "https://example.com/first.json");

        ConfigBean applied = AutoSourceManager.selectConfigToApply(
                Collections.singletonList(first),
                Collections.singletonList(first),
                "removed-id");

        assertSame(first, applied);
    }

    @Test
    public void selectsFirstWhenCurrentConfigIsDisabled() {
        ConfigBean first = new ConfigBean("first", "https://example.com/first.json");
        ConfigBean selected = new ConfigBean("selected", "https://example.com/selected.json");
        selected.enabled = false;

        ConfigBean applied = AutoSourceManager.selectConfigToApply(
                Arrays.asList(first, selected),
                Collections.singletonList(first),
                selected.id);

        assertSame(first, applied);
    }
}
