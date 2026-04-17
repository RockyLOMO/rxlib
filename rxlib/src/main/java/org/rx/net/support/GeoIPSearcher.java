package org.rx.net.support;

import com.github.benmanes.caffeine.cache.Cache;
import com.maxmind.db.CHMCache;
import com.maxmind.db.Reader;
import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.model.CountryResponse;
import com.maxmind.geoip2.record.Country;
import io.netty.util.NetUtil;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.cache.MemoryCache;
import org.rx.exception.InvalidException;
import org.rx.net.Sockets;
import org.rx.net.http.HttpClient;

import java.io.Closeable;
import java.io.File;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.rx.core.Extends.tryClose;

@Slf4j
public class GeoIPSearcher implements Closeable {
    static final long CACHE_PUBLIC_IP_NANOS = TimeUnit.HOURS.toNanos(2);
    static final IpGeolocation PRIVATE_IP = new IpGeolocation(null, null, "private");
    static final IpGeolocation UNKNOWN_IP = new IpGeolocation(null, null, "unknown");
    private static final String[] defaultPublicIpServices = new String[]{"https://checkip.amazonaws.com", "https://api.seeip.org"};

    final DatabaseReader reader;
    final Cache<String, IpGeolocation> lookupCache = MemoryCache.<String, IpGeolocation>rootBuilder()
            .maximumSize(4096)
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build();
    @Setter
    String resolveServer = org.rx.core.Constants.rSS();
    volatile PublicIpSnapshot publicIpSnapshot = PublicIpSnapshot.empty;

    public GeoIPSearcher(File database) {
        this(buildReader(database));
    }

    GeoIPSearcher(DatabaseReader reader) {
        this.reader = reader;
    }

    @SneakyThrows
    private static DatabaseReader buildReader(File database) {
        return new DatabaseReader.Builder(database)
                .withCache(new CHMCache())
                .locales(Arrays.asList("zh-CN", "en"))
                .fileMode(Reader.FileMode.MEMORY_MAPPED)
                .build();
    }

    @Override
    public void close() {
        tryClose(reader);
    }

    public String getPublicIp() {
        PublicIpSnapshot snapshot = publicIpSnapshot;
        long now = System.nanoTime();
        if (snapshot.isFresh(now)) {
            return snapshot.ip;
        }

        try (HttpClient client = createPublicIpClient()) {
            for (String service : publicIpServices()) {
                try {
                    String ip = trimAscii(queryPublicIp(client, service));
                    if (Sockets.isValidIp(ip)) {
                        publicIpSnapshot = new PublicIpSnapshot(ip, System.nanoTime());
                        return ip;
                    }
                } catch (Exception e) {
                    log.warn("getPublicIp retry", e);
                }
            }
        }
        throw new InvalidException("getPublicIp fail");
    }

    public IpGeolocation lookup(String host) {
        String normalizedHost = trimAscii(host);
        if (normalizedHost == null || normalizedHost.isEmpty()) {
            return UNKNOWN_IP;
        }

        byte[] ipBytes = NetUtil.createByteArrayFromIpAddressString(normalizedHost);
        if (ipBytes == null) {
            return UNKNOWN_IP;
        }
        String cacheKey = NetUtil.bytesToIpAddress(ipBytes);
        return lookupCache.get(cacheKey, k -> doLookup(ipBytes));
    }

    @SneakyThrows
    private IpGeolocation doLookup(byte[] ipBytes) {
        InetAddress ip = InetAddress.getByAddress(ipBytes);
        if (Sockets.isPrivateIp(ip)) {
            return PRIVATE_IP;
        }
        if (reader == null) {
            return UNKNOWN_IP;
        }

        Optional<CountryResponse> countryResponse;
        if (ip.isAnyLocalAddress() || !(countryResponse = reader.tryCountry(ip)).isPresent()) {
            return UNKNOWN_IP;
        }
        CountryResponse cp = countryResponse.get();
        Country c = cp.getCountry();
        return new IpGeolocation(c.getName(), c.getIsoCode(), c.getIsoCode());
    }

    HttpClient createPublicIpClient() {
        return new HttpClient().withTimeoutMillis(5000);
    }

    String[] publicIpServices() {
        return resolveServer != null
                ? new String[]{"https://" + resolveServer + ":8082/getPublicIp", defaultPublicIpServices[0], defaultPublicIpServices[1]}
                : defaultPublicIpServices;
    }

    String queryPublicIp(HttpClient client, String service) {
        return client.get(service).toString();
    }

    static String trimAscii(String value) {
        if (value == null) {
            return null;
        }
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) <= ' ') {
            start++;
        }
        while (end > start && value.charAt(end - 1) <= ' ') {
            end--;
        }
        return start == 0 && end == value.length() ? value : value.substring(start, end);
    }

    static final class PublicIpSnapshot {
        static final PublicIpSnapshot empty = new PublicIpSnapshot(null, 0L);
        final String ip;
        final long refreshTime;

        PublicIpSnapshot(String ip, long refreshTime) {
            this.ip = ip;
            this.refreshTime = refreshTime;
        }

        boolean isFresh(long now) {
            return ip != null && now - refreshTime < CACHE_PUBLIC_IP_NANOS;
        }
    }
}
