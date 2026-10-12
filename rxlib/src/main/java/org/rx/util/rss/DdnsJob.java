package org.rx.util.rss;

import io.netty.util.NetUtil;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.Sockets;
import org.rx.net.http.HttpClient;

import java.net.InetAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cold worker job: direct HTTPS IP observation, deduplicated provider writes and an optional local hook. */
@Slf4j
final class DdnsJob {
    interface PublicIp { String observe() throws Exception; }
    interface Provider { boolean update(RssClientConf conf, String ip) throws Exception; }
    interface Local { void run(RssClientConf conf, Map<String, String> environment) throws Exception; }
    private final PublicIp publicIp;
    private final Provider provider;
    private final Local local;
    private final AtomicBoolean running = new AtomicBoolean();
    private String lastObservedIp, providerIp, providerKey;
    private List<String> providerDomains;

    DdnsJob(PublicIp publicIp, Provider provider, Local local) {
        this.publicIp = publicIp; this.provider = provider; this.local = local;
    }

    static DdnsJob production() {
        return new DdnsJob(() -> {
            try {
                String peer = RssClient.observedWowPublicIp();
                if (peer != null) return peer;
            } catch (Exception ignored) { /* Explicit failover to validated independent HTTPS observers. */ }
            return Sockets.getPublicIp(false);
        }, new DomainUpdater()::update, LocalDdnsCommand::run);
    }

    void run(RssClientConf conf) {
        if (conf == null || !running.compareAndSet(false, true)) return;
        try {
            String ip = null;
            try {
                String observed = publicIp.observe();
                if (observed != null && NetUtil.isValidIpV4Address(observed)
                        && WowEndpointRegistry.publicIpv4(Sockets.parseIpAddress(observed))) ip = observed;
            } catch (Exception error) { log.warn("ddns public IPv4 observation failed type={}", error.getClass().getSimpleName()); }
            boolean changed = ip != null && !ip.equals(lastObservedIp);
            if (ip != null) lastObservedIp = ip;
            List<String> domains = conf.ddnsDomains == null ? Collections.emptyList() : conf.ddnsDomains;
            if (!Objects.equals(providerDomains, domains) || !Objects.equals(providerKey, conf.ddnsApiKey)) {
                providerDomains = new ArrayList<>(domains); providerKey = conf.ddnsApiKey; providerIp = null;
            }
            boolean updated = false, providerSuccess = domains.isEmpty();
            if (ip != null && !domains.isEmpty()) {
                providerSuccess = ip.equals(providerIp);
                if (!providerSuccess) {
                    try {
                        providerSuccess = provider.update(conf, ip);
                        updated = providerSuccess;
                        if (providerSuccess) providerIp = ip;
                    } catch (Exception error) { log.warn("ddns provider update failed type={}", error.getClass().getSimpleName()); }
                }
            }
            if (conf.ddnsLocalCommand != null && conf.ddnsLocalCommand.length > 0) {
                Map<String, String> environment = new LinkedHashMap<>();
                environment.put("RSS_DDNS_IPV4", ip == null ? "" : ip);
                environment.put("RSS_DDNS_CHANGED", changed ? "1" : "0");
                environment.put("RSS_DDNS_PROVIDER_UPDATED", updated ? "1" : "0");
                environment.put("RSS_DDNS_PROVIDER_SUCCESS", ip != null && providerSuccess ? "1" : "0");
                environment.put("RSS_DDNS_OBSERVED_SUCCESS", ip != null ? "1" : "0");
                try { local.run(conf, environment); }
                catch (Exception error) { log.warn("ddns local hook failed type={}", error.getClass().getSimpleName()); }
            }
        } finally { running.set(false); }
    }

    static final class DomainUpdater {
        private String key;
        private final Map<String, String> successfulIps = new LinkedHashMap<>();

        boolean update(RssClientConf conf, String ip) throws Exception {
        if (!Objects.equals(key, conf.ddnsApiKey)) { key = conf.ddnsApiKey; successfulIps.clear(); }
        Map<String, List<String>> domains = new LinkedHashMap<>();
        for (String hostname : conf.ddnsDomains) {
            int dot = hostname == null ? -1 : hostname.indexOf('.');
            if (dot <= 0 || dot == hostname.length() - 1) throw new IllegalArgumentException("invalid DDNS hostname");
            domains.computeIfAbsent(hostname.substring(dot + 1), k -> new ArrayList<>()).add(hostname.substring(0, dot));
        }
        boolean success = true;
        for (Map.Entry<String, List<String>> domain : domains.entrySet()) {
            String scope = domain.getKey() + ":" + String.join(",", domain.getValue());
            if (ip.equals(successfulIps.get(scope))) continue;
            // Existing setDDns preserves other records and TTL=300; only selected A records change.
            try { RssClient.setDDns(conf.ddnsApiKey, domain.getKey(), domain.getValue(), ip); successfulIps.put(scope, ip); }
            catch (Exception error) { success = false; log.warn("ddns domain update failed type={}", error.getClass().getSimpleName()); }
        }
        return success;
        }
    }
}
