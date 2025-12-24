package org.rx.net.support;

import com.maxmind.db.CHMCache;
import com.maxmind.db.Reader;
import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.model.CountryResponse;
import com.maxmind.geoip2.record.Country;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Constants;
import org.rx.exception.InvalidException;
import org.rx.net.Sockets;
import org.rx.net.http.HttpClient;

import java.io.Closeable;
import java.io.File;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Optional;

import static org.rx.core.Extends.tryClose;

@Slf4j
public class GeoIPSearcher implements Closeable {
    static final long CACHE_PUBLIC_IP_MINUTES = 2 * 60 * 1000 * Constants.NANO_TO_MILLIS;
    static final IpGeolocation PRIVATE_IP = new IpGeolocation(null, null, "private");

    final DatabaseReader reader;
    @Setter
    String resolveServer = Constants.rSS();
    String publicIp;
    long lastPublicIpTime;

    @SneakyThrows
    public GeoIPSearcher(File database) {
        reader = new DatabaseReader.Builder(database)
                .withCache(new CHMCache())  // 可选：添加节点缓存，提升性能（约 2MB 内存开销）
                .locales(Arrays.asList("zh-CN", "en"))  // 可选：语言优先级 fallback
                .fileMode(Reader.FileMode.MEMORY_MAPPED)  // 可选：文件映射模式（默认 MEMORY_MAPPED）
                .build();
    }

    @Override
    public void close() {
        tryClose(reader);
    }

    public String getPublicIp() {
        if (System.nanoTime() - lastPublicIpTime < CACHE_PUBLIC_IP_MINUTES) {
            return publicIp;
        }

        String[] services = resolveServer != null
                ? new String[]{"https://" + resolveServer + "/getPublicIp", "https://checkip.amazonaws.com", "https://api.seeip.org"}
                : new String[]{"https://checkip.amazonaws.com", "https://api.seeip.org"};
        HttpClient client = new HttpClient().withTimeoutMillis(5000);
        for (String service : services) {
            try {
                String ip = client.get(service).toString();
                if (Sockets.isValidIp(ip)) {
                    lastPublicIpTime = System.nanoTime();
                    return publicIp = ip;
                }
            } catch (Exception e) {
                log.warn("getPublicIp retry", e);
            }
        }
        throw new InvalidException("getPublicIp fail");
    }

    @SneakyThrows
    public IpGeolocation lookup(String host) {
        InetAddress ip = InetAddress.getByName(host);
        if (Sockets.isPrivateIp(ip)) {
            return PRIVATE_IP;
        }

        Optional<CountryResponse> countryResponse;
        if (ip.isAnyLocalAddress() || !(countryResponse = reader.tryCountry(ip)).isPresent()) {
            return new IpGeolocation(null, null, "unknown");
        }
        CountryResponse cp = countryResponse.get();
        Country c = cp.getCountry();
        return new IpGeolocation(c.getName(), c.getIsoCode(), c.getIsoCode());
    }
}
