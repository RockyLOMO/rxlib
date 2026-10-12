package org.rx.util.rss;

import org.rx.core.Tasks;
import org.rx.net.rpc.RpcClientConfig;
import org.rx.net.socks.SocksRpcContract;
import org.rx.net.socks.WowEndpointObservation;
import org.rx.net.transport.hybrid.HybridClient;

import java.util.Collections;
import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns one low-frequency observer on the existing persistent event RPC connection. */
final class WowEndpointPublisher implements AutoCloseable {
    private final String identity, writerKey, readyFile;
    private final AtomicBoolean sending = new AtomicBoolean();
    private volatile boolean closed;
    private volatile ScheduledFuture<?> heartbeat;
    private volatile HybridClient client;
    private volatile SocksRpcContract facade;

    WowEndpointPublisher(String identity, String writerKey, String readyFile) {
        this.identity = identity; this.writerKey = writerKey; this.readyFile = readyFile;
    }

    static WowEndpointPublisher configure(RssClientConf conf, RpcClientConfig<SocksRpcContract> rpc) {
        if (conf.wowEndpointClientId == null || conf.wowEndpointClientId.isEmpty()
                || conf.wowEndpointWriterKey == null || conf.wowEndpointWriterKey.length() < 32) return null;
        if (conf.wowEndpointRpcEndpoint != null && !conf.wowEndpointRpcEndpoint.isEmpty()) {
            java.net.InetSocketAddress selected = org.rx.net.Sockets.parseEndpoint(conf.wowEndpointRpcEndpoint);
            java.net.InetSocketAddress endpoint = rpc.getTcpConfig().getServerEndpoint();
            if (selected.getPort() != endpoint.getPort() || !selected.getHostString().equalsIgnoreCase(endpoint.getHostString())) return null;
        }
        WowEndpointPublisher publisher = new WowEndpointPublisher(conf.wowEndpointClientId, conf.wowEndpointWriterKey, conf.wowEndpointReadyFile);
        rpc.setSensitiveMethods(WowEndpointRegistry.SENSITIVE_METHODS);
        rpc.setInitHandler((facade, client) -> publisher.start(facade, client));
        return publisher;
    }

    String observedPublicIp() {
        HybridClient active = client;
        if (closed || active == null || !active.isConnected()) return null;
        return facade.getObservedPublicIp(new WowEndpointObservation(identity, writerKey, SocksRpcContract.rpcToken(), null, false));
    }

    private synchronized void start(SocksRpcContract facade, HybridClient client) {
        if (closed) return;
        this.facade = facade; this.client = client;
        // SessionReady is dispatched off EventLoop; registration cannot block network I/O.
        client.onSessionReady.add((s, e) -> Tasks.runAsync(this::publish));
        heartbeat = Tasks.schedulePeriod(this::publish, 10000);
        Tasks.runAsync(this::publish);
    }

    private void publish() {
        HybridClient active = client;
        if (closed || active == null || !active.isConnected() || !sending.compareAndSet(false, true)) return;
        try {
            WowEndpointObservation observation = new WowEndpointObservation(identity, writerKey, SocksRpcContract.rpcToken(), null, false);
            readReadiness(readyFile, observation, System.currentTimeMillis());
            facade.observeWowEndpoint(observation);
        } catch (Throwable ignored) {
            // Retries are bounded by the existing RPC request timeout and the next 10s tick.
            // Do not log credentials or exception payloads from a remote implementation.
        } finally { sending.set(false); }
    }

    static void readReadiness(String path, WowEndpointObservation observation, long now) {
        if (path == null || path.isEmpty()) return;
        try {
            File file = new File(path);
            if (!file.isFile() || file.length() > 4096) return;
            try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[512]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > 4096) return;
                    bytes.write(buffer, 0, count);
                }
                JSONObject value = JSON.parseObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
                Object timestamp = value.get("updated_at"), ip = value.get("ip");
                if (!(timestamp instanceof Long || timestamp instanceof Integer) || !(ip instanceof String)) return;
                long updated = ((Number) timestamp).longValue();
                long nowSeconds = now / 1000;
                if (updated <= nowSeconds && updated >= nowSeconds - 30 && Boolean.TRUE.equals(value.get("ready"))) {
                    observation.readyIp = (String) ip; observation.ready = true;
                }
            }
        } catch (Exception ignored) { /* Missing, malformed or stale acknowledgement is fail-closed. */ }
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (heartbeat != null) heartbeat.cancel(false);
    }
}
