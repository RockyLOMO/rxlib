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
import org.rx.core.Tasks;
import org.rx.exception.ApplicationException;
import org.rx.exception.InvalidException;
import org.rx.io.Files;
import org.rx.net.Sockets;
import org.rx.net.http.AuthenticProxy;
import org.rx.net.http.HttpClient;

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
public class GeoLite2 implements IPSearcher {
    public static final GeoLite2 INSTANCE = new GeoLite2("https://" + Constants.rCloud() + ":6501/Country.mmdb?x_token=4rT2pQvX8kLmN9wYfH6jSa", "04:00:00");
    static final String DB_FILE = "geoip.mmdb";
    static final long CACHE_PUBLIC_IP_MINUTES = 2 * 60 * 1000 * Constants.NANO_TO_MILLIS;
    @Setter
    String fileUrl;
    @Setter
    long timeoutMillis = 5 * 60 * 1000;
    @Setter
    AuthenticProxy proxy;
    @Setter
    String resolveServer = Constants.rSS();
    String publicIp;
    long lastPublicIpTime;
    volatile DatabaseReader reader;
    volatile Future<Void> dTask;

    private GeoLite2(String fileUrl, String dailyDownloadTime) {
        this.fileUrl = fileUrl;
        dTask = Tasks.run(() -> download(false));
        Tasks.scheduleDaily(() -> download(true), dailyDownloadTime);
    }

    public void waitDownload() throws ExecutionException, InterruptedException, TimeoutException {
        Future<Void> t = dTask;
        if (t != null) {
            t.get(timeoutMillis, TimeUnit.MILLISECONDS);
        }
    }

    private synchronized void download(boolean force) {
        File f = new File(DB_FILE);
        try {
            if (force || !f.exists()) {
                File tmp = new File(f.getPath() + ".tmp");
                log.info("geo download file {} -> {} begin", tmp.getAbsolutePath(), f.getAbsolutePath());
                new HttpClient().withTimeoutMillis(timeoutMillis).withProxy(proxy)
                        .get(fileUrl).toFile(tmp.getPath());
                f = Files.moveFile(tmp, f);
                log.info("geo download file {} -> {} end", tmp.getAbsolutePath(), f.getAbsolutePath());
            }

            DatabaseReader old = reader;
            reader = new DatabaseReader.Builder(f)
                    .withCache(new CHMCache())  // 可选：添加节点缓存，提升性能（约 2MB 内存开销）
                    .locales(Arrays.asList("zh-CN", "en"))  // 可选：语言优先级 fallback
                    .fileMode(Reader.FileMode.MEMORY_MAPPED)  // 可选：文件映射模式（默认 MEMORY_MAPPED）
                    .build();
            Tasks.setTimeout(() -> tryClose(old), 2000);
        } catch (IOException e) {
            log.error("geo download error", e);
            throw ApplicationException.sneaky(e);
        } finally {
            dTask = null;
        }
    }

    @Override
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
    @Override
    public IpGeolocation resolve(String host) {
        InetAddress ipAddr = InetAddress.getByName(host);
        return lookup(ipAddr);
    }

    @SneakyThrows
    IpGeolocation lookup(InetAddress ip) {
        if (Sockets.isPrivateIp(ip) || ip.isAnyLocalAddress()) {
            return new IpGeolocation(ip.getHostAddress(), null, null, "private");
        }
        DatabaseReader r = reader;
        if (r == null) {
            if (dTask == null) {
                synchronized (this) {
                    if (dTask == null) {
                        dTask = Tasks.run(() -> download(false));
                    }
                }
            }
            return new IpGeolocation(ip.getHostAddress(), null, null, "notReady");
        }

        Optional<CountryResponse> countryResponse = r.tryCountry(ip);
        if (!countryResponse.isPresent()) {
            return new IpGeolocation(ip.getHostAddress(), null, null, "unknown");
        }
        CountryResponse cp = countryResponse.get();
        Country c = cp.getCountry();
        return new IpGeolocation(ip.getHostAddress(), c.getName(), c.getIsoCode(), c.getIsoCode());
    }
}
