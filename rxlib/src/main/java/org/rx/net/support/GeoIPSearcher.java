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
import org.rx.core.RxConfig;
import org.rx.core.Tasks;
import org.rx.exception.ApplicationException;
import org.rx.exception.InvalidException;
import org.rx.io.Files;
import org.rx.net.Sockets;
import org.rx.net.http.AuthenticProxy;
import org.rx.net.http.HttpClient;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.rx.core.Extends.tryClose;

@Slf4j
public class GeoIPSearcher implements Closeable {
    static final long CACHE_PUBLIC_IP_MINUTES = 2 * 60 * 1000 * Constants.NANO_TO_MILLIS;
    static final IPGeolocation EMPTY = new IPGeolocation(null, null, "privateOrUnknown");

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
    public IPGeolocation resolve(String host) {
        InetAddress ipAddr = InetAddress.getByName(host);
        return lookup(ipAddr);
    }

    @SneakyThrows
    IPGeolocation lookup(InetAddress ip) {
        if (Sockets.isPrivateIp(ip) || ip.isAnyLocalAddress()) {
            return EMPTY;
        }

        Optional<CountryResponse> countryResponse = reader.tryCountry(ip);
        if (!countryResponse.isPresent()) {
            return EMPTY;
        }
        CountryResponse cp = countryResponse.get();
        Country c = cp.getCountry();
        return new IPGeolocation(c.getName(), c.getIsoCode(), c.getIsoCode());
    }
}
