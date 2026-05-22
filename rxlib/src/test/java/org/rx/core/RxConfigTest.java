package org.rx.core;

import org.junit.jupiter.api.Test;
import org.rx.bean.LogStrategy;
import org.rx.net.NetworkFlowControl;
import org.rx.net.NetworkTrafficConfig;
import org.rx.net.http.HttpServer;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
        try (ThreadPoolConfigSnapshot ignored = ThreadPoolConfigSnapshot.capture()) {
            System.clearProperty(propName);
            conf.threadPool.traceName = "yaml-trace";
            conf.refreshFromSystemProperty();
            assertEquals("yaml-trace", conf.threadPool.traceName);

            System.setProperty(propName, "sys-trace");
            conf.refreshFromSystemProperty();
            assertEquals("sys-trace", conf.threadPool.traceName);
        } finally {
            restoreProperty(propName, oldProp);
        }
    }

    @Test
    void refreshFrom_acceptsRenamedThreadPoolResizeProps() {
        RxConfig conf = RxConfig.INSTANCE;
        try (ThreadPoolConfigSnapshot ignored = ThreadPoolConfigSnapshot.capture()) {
            Map<String, Object> props = new HashMap<>();
            props.put(RxConfig.ConfigNames.THREAD_POOL_MIN_IDLE_SIZE, 4);
            props.put(RxConfig.ConfigNames.THREAD_POOL_MAX_POOL_SIZE, 8);
            props.put(RxConfig.ConfigNames.THREAD_POOL_RESIZE_STEP, 5);
            conf.refreshFrom(props);
            assertEquals(4, conf.threadPool.getMinIdleSize());
            assertEquals(8, conf.threadPool.getMaxPoolSize());
            assertEquals(5, conf.threadPool.getResizeStep());
        }
    }

    @Test
    void refreshFrom_acceptsThreadPoolSafetyProps() {
        RxConfig conf = RxConfig.INSTANCE;
        try (ThreadPoolConfigSnapshot ignored = ThreadPoolConfigSnapshot.capture()) {
            Map<String, Object> props = new HashMap<>();
            props.put(RxConfig.ConfigNames.THREAD_POOL_QUEUE_OFFER_MODE, " timeout-reject ");
            props.put(RxConfig.ConfigNames.THREAD_POOL_QUEUE_OFFER_TIMEOUT_MILLIS, 25);
            props.put(RxConfig.ConfigNames.THREAD_POOL_SERIAL_QUEUE_CAPACITY, 16);
            props.put(RxConfig.ConfigNames.THREAD_POOL_SERIAL_QUEUE_HARD_LIMIT, 32);
            props.put(RxConfig.ConfigNames.THREAD_POOL_PATCH_COMPLETABLE_FUTURE_ASYNC_POOL, true);
            props.put(RxConfig.ConfigNames.THREAD_POOL_RESIZE_COOLDOWN_MILLIS, 500);
            conf.refreshFrom(props);

            assertEquals(ThreadPoolQueueOfferMode.TIMEOUT_REJECT, conf.threadPool.getQueueOfferMode());
            assertEquals(25, conf.threadPool.getQueueOfferTimeoutMillis());
            assertEquals(16, conf.threadPool.getSerialQueueCapacity());
            assertEquals(32, conf.threadPool.getSerialQueueHardLimit());
            assertTrue(conf.threadPool.isPatchCompletableFutureAsyncPool());
            assertEquals(500, conf.threadPool.getResizeCooldownMillis());
        }
    }

    @Test
    void refreshFromSystemProperty_acceptsRelaxedQueueOfferMode() {
        RxConfig conf = RxConfig.INSTANCE;
        String propName = RxConfig.ConfigNames.THREAD_POOL_QUEUE_OFFER_MODE;
        String oldProp = System.getProperty(propName);
        try (ThreadPoolConfigSnapshot ignored = ThreadPoolConfigSnapshot.capture()) {
            conf.threadPool.setQueueOfferMode(ThreadPoolQueueOfferMode.BLOCK);

            System.setProperty(propName, " caller-runs ");
            conf.refreshFromSystemProperty();
            assertEquals(ThreadPoolQueueOfferMode.CALLER_RUNS, conf.threadPool.getQueueOfferMode());

            System.setProperty(propName, "bad-mode");
            conf.refreshFromSystemProperty();
            assertEquals(ThreadPoolQueueOfferMode.CALLER_RUNS, conf.threadPool.getQueueOfferMode());
        } finally {
            restoreProperty(propName, oldProp);
        }
    }

    @Test
    void refreshFromSystemProperty_acceptsGlobalTrafficProps() {
        RxConfig conf = RxConfig.INSTANCE;
        NetworkTrafficConfig oldConfig = new NetworkTrafficConfig(conf.net.globalTraffic);
        String enabled = RxConfig.ConfigNames.NET_GLOBAL_TRAFFIC_ENABLED;
        String upload = RxConfig.ConfigNames.NET_GLOBAL_TRAFFIC_UPLOAD_KILOBYTES_PER_SECOND;
        String download = RxConfig.ConfigNames.NET_GLOBAL_TRAFFIC_DOWNLOAD_KILOBYTES_PER_SECOND;
        String checkInterval = RxConfig.ConfigNames.NET_GLOBAL_TRAFFIC_CHECK_INTERVAL_MILLIS;
        String udpPendingPackets = RxConfig.ConfigNames.NET_GLOBAL_TRAFFIC_UDP_MAX_PENDING_PACKETS;
        String oldEnabled = System.getProperty(enabled);
        String oldUpload = System.getProperty(upload);
        String oldDownload = System.getProperty(download);
        String oldCheckInterval = System.getProperty(checkInterval);
        String oldUdpPendingPackets = System.getProperty(udpPendingPackets);
        try {
            System.setProperty(enabled, "true");
            System.setProperty(upload, "2048");
            System.setProperty(download, "4096");
            System.setProperty(checkInterval, "50");
            System.setProperty(udpPendingPackets, "16");

            conf.refreshFromSystemProperty();

            assertTrue(conf.net.globalTraffic.isEnabled());
            assertEquals(2048L, conf.net.globalTraffic.getUploadKilobytesPerSecond());
            assertEquals(4096L, conf.net.globalTraffic.getDownloadKilobytesPerSecond());
            assertEquals(50L, conf.net.globalTraffic.getCheckIntervalMillis());
            assertEquals(16, conf.net.globalTraffic.getUdpMaxPendingPackets());
        } finally {
            restoreProperty(enabled, oldEnabled);
            restoreProperty(upload, oldUpload);
            restoreProperty(download, oldDownload);
            restoreProperty(checkInterval, oldCheckInterval);
            restoreProperty(udpPendingPackets, oldUdpPendingPackets);
            conf.net.globalTraffic = new NetworkTrafficConfig(oldConfig);
            NetworkFlowControl.DEFAULT.refresh(oldConfig);
        }
    }

    @Test
    void refreshFrom_initializesDefaultHttpServerWhenPortConfigured() throws Exception {
        assumeTrue(HttpServer.getDefault() == null);
        RxConfig conf = RxConfig.INSTANCE;
        int oldPort = conf.net.http.serverPort;
        boolean oldTls = conf.net.http.serverTls;
        int port = freePort();
        try {
            Map<String, Object> props = new HashMap<>();
            props.put(RxConfig.ConfigNames.NET_HTTP_SERVER_PORT, port);
            props.put(RxConfig.ConfigNames.NET_HTTP_SERVER_TLS, false);
            conf.refreshFrom(props);

            HttpServer server = HttpServer.getDefault();
            assertNotNull(server);
            assertEquals(port, server.getPort());
            assertFalse(server.isTls());
            assertTrue(server.getMapping().containsKey("/rdiag"));
            assertSame(server, HttpServer.getDefault());
        } finally {
            HttpServer server = HttpServer.getDefault();
            if (server != null) {
                server.close();
            }
            conf.net.http.serverPort = oldPort;
            conf.net.http.serverTls = oldTls;
        }
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
            return;
        }
        System.setProperty(name, value);
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
