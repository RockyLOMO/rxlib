package org.rx.net;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.netty.util.NetUtil;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.http.HttpClient;
import org.rx.net.http.HttpClientConfig;

import java.net.URI;
import java.net.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Ordered independent HTTPS sources, strict IPv4 parsing, two agreeing votes. */
@Slf4j
public final class PublicIpObserver {
    interface Reader { String read(String endpoint) throws Exception; }
    private PublicIpObserver() { }

    private static final class Client {
        static final HttpClient INSTANCE = create();
        @lombok.SneakyThrows private static HttpClient create() {
            HttpClientConfig config = new HttpClientConfig().setTimeoutMillis(3000)
                    .setSslContext(io.netty.handler.ssl.SslContextBuilder.forClient()
                            .sslProvider(io.netty.handler.ssl.SslProvider.JDK).build());
            config.setVerifyHostname(true); config.setIpv4Only(true); config.setProxy(Proxy.NO_PROXY);
            config.setEnableLog(false); config.setFollowRedirects(false);
            return new HttpClient(config);
        }
    }

    static String observe(String[] endpoints) {
        return observe(endpoints, endpoint -> {
            HttpClient.Request request = HttpClient.request(io.netty.handler.codec.http.HttpMethod.GET, endpoint)
                    .proxy(Proxy.NO_PROXY).timeoutMillis(3000);
            try (HttpClient.Response response = Client.INSTANCE.execute(request)) {
                if (response.code() != 200 || response.bodyStream().getLength() > 2048) throw new IllegalStateException("IP observer HTTP response rejected");
                return response.bodyAsString();
            }
        });
    }

    static String observe(String[] endpoints, Reader reader) {
        Map<String, Integer> votes = new HashMap<>();
        Set<String> hosts = new HashSet<>();
        int count = Math.min(8, endpoints.length);
        for (int i = 0; i < count; i++) {
            try {
                URI uri = URI.create(endpoints[i]);
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null)
                    throw new IllegalArgumentException("IP observer must be HTTPS without credentials");
                if (!hosts.add(uri.getHost().toLowerCase(java.util.Locale.ROOT))) continue;
                String ip = parse(uri, reader.read(endpoints[i]));
                int vote = votes.getOrDefault(ip, 0) + 1;
                if (vote >= 2) return ip;
                votes.put(ip, vote);
            } catch (Exception error) { log.warn("public IPv4 source failed index={} type={}", i, error.getClass().getSimpleName()); }
        }
        throw new IllegalStateException("no two independent HTTPS sources agree on a global IPv4");
    }

    static String parse(URI uri, String body) {
        if (body == null || body.length() > 2048) throw new IllegalArgumentException("invalid IP response");
        String ip;
        if ("myip.ipip.net".equalsIgnoreCase(uri.getHost()) && "/json".equals(uri.getPath())) {
            JSONObject response = JSON.parseObject(body);
            JSONObject data = response == null ? null : response.getJSONObject("data");
            Object value = data == null ? null : data.get("ip");
            if (!(value instanceof String)) throw new IllegalArgumentException("missing data.ip");
            ip = (String) value;
        } else ip = body.trim();
        if (!NetUtil.isValidIpV4Address(ip) || !Sockets.isGlobalIpv4(Sockets.parseIpAddress(ip)))
            throw new IllegalArgumentException("response is not one global IPv4 literal");
        return ip;
    }
}
