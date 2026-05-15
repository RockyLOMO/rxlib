package org.rx.net.support;

import com.github.benmanes.caffeine.cache.Cache;
import com.maxmind.db.CHMCache;
import com.maxmind.db.Reader;
import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.model.CityResponse;
import com.maxmind.geoip2.model.CountryResponse;
import com.maxmind.geoip2.record.City;
import com.maxmind.geoip2.record.Country;
import io.netty.util.NetUtil;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Constants;
import org.rx.core.RxConfig;
import org.rx.core.cache.MemoryCache;
import org.rx.io.Files;
import org.rx.net.Sockets;
import org.rx.net.http.AuthenticProxy;
import org.rx.net.http.HttpClient;
import org.rx.net.http.HttpClientConfig;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.rx.core.Extends.tryClose;

@Slf4j
public class GeoIPSearcher implements Closeable {
    public static final GeoIPSearcher INSTANCE = new GeoIPSearcher(true);
    static final IpGeolocation PRIVATE_IP = new IpGeolocation(null, null, null, "private");
    static final IpGeolocation UNKNOWN_IP = new IpGeolocation(null, null, null, "unknown");
    static final IpGeolocation NOT_READY = new IpGeolocation(null, null, null, "notReady");

    final boolean autoLoad;
    volatile DatabaseReader reader;
    final Cache<String, IpGeolocation> lookupCache = MemoryCache.<String, IpGeolocation>rootBuilder()
            .maximumSize(4096)
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build();
    @Setter
    int timeoutMillis = 5 * 60 * 1000;
    @Setter
    AuthenticProxy proxy;
    String geoIpFileUrl = "https://" + Constants.rCloud() + ":6501/Country.mmdb?rtoken=" + RxConfig.INSTANCE.getRtoken();
    String geoIpFile = "geoip.mmdb";

    public GeoIPSearcher(File database) {
        this(buildReader(database));
    }

    GeoIPSearcher(DatabaseReader reader) {
        this.reader = reader;
        this.autoLoad = false;
    }

    private GeoIPSearcher(boolean autoLoad) {
        this.reader = null;
        this.autoLoad = autoLoad;
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
        reader = null;
        lookupCache.invalidateAll();
    }

    public synchronized void setGeoIpFileUrl(String geoIpFileUrl) {
        this.geoIpFileUrl = geoIpFileUrl;
        unloadReader();
    }

    public synchronized void setGeoIpFile(String geoIpFile) {
        this.geoIpFile = geoIpFile;
        unloadReader();
    }

    public void waitLoad() {
        if (autoLoad) {
            ensureReaderLoadedQuietly();
        }
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
        InetAddress ip = toAddress(ipBytes);
        if (Sockets.isPrivateIp(ip)) {
            return PRIVATE_IP;
        }
        if (ip.isAnyLocalAddress()) {
            return UNKNOWN_IP;
        }

        DatabaseReader current = reader;
        if (current == null && autoLoad) {
            current = ensureReaderLoadedQuietly();
            if (current == null) {
                return NOT_READY;
            }
        }
        if (current == null) {
            return UNKNOWN_IP;
        }
        String cacheKey = NetUtil.bytesToIpAddress(ipBytes);
        final InetAddress target = ip;
        final DatabaseReader searcher = current;
        return lookupCache.get(cacheKey, k -> doLookup(target, searcher));
    }

    public IpGeolocation resolveIp(String ip) {
        return lookup(ip);
    }

    private IpGeolocation doLookup(InetAddress ip, DatabaseReader reader) {
        IpGeolocation geolocation = tryLookupCity(ip, reader);
        return geolocation != null ? geolocation : tryLookupCountry(ip, reader);
    }

    private IpGeolocation tryLookupCity(InetAddress ip, DatabaseReader reader) {
        try {
            Optional<CityResponse> cityResponse = reader.tryCity(ip);
            if (!cityResponse.isPresent()) {
                return null;
            }
            CityResponse cp = cityResponse.get();
            Country country = cp.getCountry();
            City city = cp.getCity();
            return new IpGeolocation(country.getName(), country.getIsoCode(),
                    city == null ? null : city.getName(), country.getIsoCode());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private IpGeolocation tryLookupCountry(InetAddress ip, DatabaseReader reader) {
        try {
            Optional<CountryResponse> countryResponse = reader.tryCountry(ip);
            if (!countryResponse.isPresent()) {
                return UNKNOWN_IP;
            }
            CountryResponse cp = countryResponse.get();
            Country c = cp.getCountry();
            return new IpGeolocation(c.getName(), c.getIsoCode(), null, c.getIsoCode());
        } catch (Throwable ignored) {
            return UNKNOWN_IP;
        }
    }

    private DatabaseReader ensureReaderLoadedQuietly() {
        try {
            return loadReader(false);
        } catch (IOException e) {
            log.error("geo ip download error", e);
            return null;
        }
    }

    private synchronized DatabaseReader loadReader(boolean force) throws IOException {
        DatabaseReader current = reader;
        if (!force && current != null) {
            return current;
        }
        File ipFile = innerDl(force, new File(geoIpFile), geoIpFileUrl);
        DatabaseReader next = buildReader(ipFile);
        reader = next;
        lookupCache.invalidateAll();
        tryClose(current);
        return next;
    }

    private File innerDl(boolean force, File file, String fileUrl) throws IOException {
        String url = trimAscii(fileUrl);
        if (!force && file.exists()) {
            return file;
        }
        if (url == null || url.isEmpty()) {
            if (!file.exists()) {
                throw new IOException("geo file not found " + file.getAbsolutePath());
            }
            return file;
        }

        File tmp = new File(file.getPath() + ".tmp");
        log.info("geo download file {} -> {} begin", tmp.getAbsolutePath(), file.getAbsolutePath());
        try {
            try (HttpClient client = new HttpClient(new HttpClientConfig().setTimeoutMillis(timeoutMillis).setProxy(proxy))) {
                try (HttpClient.Response response = client.get(url)) {
                    response.bodyAsFile(tmp.getPath());
                }
            }
            File moved = Files.moveFile(tmp, file);
            log.info("geo download file {} -> {} end", tmp.getAbsolutePath(), moved.getAbsolutePath());
            return moved;
        } catch (Exception e) {
            if (tmp.exists()) {
                tmp.delete();
            }
            throw e;
        }
    }

    private synchronized void unloadReader() {
        DatabaseReader old = reader;
        reader = null;
        lookupCache.invalidateAll();
        tryClose(old);
    }

    @SneakyThrows
    private static InetAddress toAddress(byte[] ipBytes) {
        return InetAddress.getByAddress(ipBytes);
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
}
