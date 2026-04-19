package org.rx.core;

import org.junit.jupiter.api.Test;
import org.rx.bean.LogStrategy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RxConfigTest {
    @Test
    void getIntId_parsesNumericAppId() {
        RxConfig conf = RxConfig.INSTANCE;
        String oldId = conf.id;
        try {
            conf.id = "1";
            assertEquals(1, conf.getIntId());
        } finally {
            conf.id = oldId;
        }
    }

    @Test
    void refreshFrom_invalidatesLogNameMatchers() {
        RxConfig conf = RxConfig.INSTANCE;
        LogStrategy oldStrategy = conf.logStrategy;
        List<String> oldLogNames = new ArrayList<>(conf.logNameList);
        int oldRestLogMode = conf.rest.logMode;
        List<String> oldRestLogNames = new ArrayList<>(conf.rest.logNameList);
        try {
            String oldGlobalName = "org.rx.core.OldGlobalLog";
            String newGlobalName = "org.rx.core.NewGlobalLog";
            String oldRestName = "org.rx.core.OldRestLog";
            String newRestName = "org.rx.core.NewRestLog";

            conf.logStrategy = LogStrategy.WHITELIST;
            conf.logNameList.clear();
            conf.logNameList.add(oldGlobalName);
            conf.rest.logMode = 1;
            conf.rest.logNameList.clear();
            conf.rest.logNameList.add(oldRestName);
            conf.refreshFrom(Collections.<String, Object>emptyMap());

            assertTrue(conf.getLogNameMatcher().matches(oldGlobalName));
            assertTrue(conf.rest.getLogNameMatcher().matches(oldRestName));

            Map<String, Object> props = new HashMap<>();
            props.put(RxConfig.ConfigNames.LOG_NAME_LIST, Collections.singletonList(newGlobalName));
            props.put(RxConfig.ConfigNames.REST_LOG_NAME_LIST, Collections.singletonList(newRestName));
            conf.refreshFrom(props);

            assertFalse(conf.getLogNameMatcher().matches(oldGlobalName));
            assertTrue(conf.getLogNameMatcher().matches(newGlobalName));
            assertFalse(conf.rest.getLogNameMatcher().matches(oldRestName));
            assertTrue(conf.rest.getLogNameMatcher().matches(newRestName));
        } finally {
            conf.logStrategy = oldStrategy;
            conf.logNameList.clear();
            conf.logNameList.addAll(oldLogNames);
            conf.rest.logMode = oldRestLogMode;
            conf.rest.logNameList.clear();
            conf.rest.logNameList.addAll(oldRestLogNames);
            conf.refreshFrom(Collections.<String, Object>emptyMap());
        }
    }

    @Test
    void refreshFromSystemProperty_keepsTraceNameWhenPropertyMissing() {
        RxConfig conf = RxConfig.INSTANCE;
        String propName = RxConfig.ConfigNames.THREAD_POOL_TRACE_NAME;
        String oldProp = System.getProperty(propName);
        String oldTraceName = conf.threadPool.traceName;
        try {
            System.clearProperty(propName);
            conf.threadPool.traceName = "yaml-trace";
            conf.refreshFromSystemProperty();
            assertEquals("yaml-trace", conf.threadPool.traceName);

            System.setProperty(propName, "sys-trace");
            conf.refreshFromSystemProperty();
            assertEquals("sys-trace", conf.threadPool.traceName);
        } finally {
            restoreProperty(propName, oldProp);
            conf.threadPool.traceName = oldTraceName;
            conf.refreshFrom(Collections.<String, Object>emptyMap());
        }
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
            return;
        }
        System.setProperty(name, value);
    }
}
