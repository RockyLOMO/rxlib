package org.rx.net.support;

import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Constants;
import org.rx.core.RxConfig;
import org.rx.core.Tasks;
import org.rx.io.Files;
import org.rx.net.Sockets;
import org.rx.net.http.AuthenticProxy;
import org.rx.net.http.HttpClient;
import org.rx.net.http.HttpClientConfig;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Stream;

import static org.rx.core.Extends.ifNull;
import static org.rx.core.Extends.tryClose;

@Slf4j
public class GeoManager {
    public static final GeoManager INSTANCE = new GeoManager();
    @Setter
    int timeoutMillis = 5 * 60 * 1000;
    @Setter
    AuthenticProxy proxy;
    @Setter
    String geoIpFileUrl = "https://" + Constants.rCloud() + ":6501/Country.mmdb?rtoken=" + RxConfig.INSTANCE.getRtoken();
    @Setter
    String geoIpFile = "geoip.mmdb";
    @Setter
    String geoSiteDirectFileUrl = "https://" + Constants.rCloud() + ":6501/geosite-direct.txt?rtoken=" + RxConfig.INSTANCE.getRtoken();
    @Setter
    String geoSiteDirectFile = "geosite-direct.txt";
    Set<String> geoSiteDirectRules;
    @Setter
    String dailyDownloadTime = "06:30:00";
    volatile GeoIPSearcher ipSearcher;
    volatile GeoSiteMatcher siteMatcher;

    public synchronized void setGeoSiteDirectRules(Set<String> geoSiteDirectRules) {
        this.geoSiteDirectRules = geoSiteDirectRules;
        siteMatcher = null;
    }

    private GeoManager() {
    }

    private synchronized void loadIpSearcher() throws IOException {
        if (ipSearcher != null) {
            return;
        }
        File ipf = innerDl(false, new File(geoIpFile), geoIpFileUrl);
        GeoIPSearcher old = ipSearcher;
        ipSearcher = new GeoIPSearcher(ipf);
        Tasks.setTimeout(() -> tryClose(old), 2000);
    }

    private synchronized void loadSiteMatcher() throws IOException {
        if (siteMatcher != null) {
            return;
        }
        buildSiteMatcher(innerDl(false, new File(geoSiteDirectFile), geoSiteDirectFileUrl));
    }

    private void buildSiteMatcher(File sited) throws IOException {
        try (Stream<String> lines = java.nio.file.Files.lines(sited.toPath(), StandardCharsets.UTF_8)) {
            siteMatcher = new GeoSiteMatcher(lines.iterator(), ifNull(geoSiteDirectRules, Collections.<String>emptySet()).iterator());
        }
    }

    private void ensureSiteMatcherLoaded() {
        if (siteMatcher != null) {
            return;
        }
        try {
            loadSiteMatcher();
        } catch (IOException e) {
            log.error("geo site download error", e);
        }
    }

    private File innerDl(boolean force, File f, String fileUrl) throws IOException {
        String url = GeoIPSearcher.trimAscii(fileUrl);
        if (force || !f.exists()) {
            if (url == null || url.isEmpty()) {
                if (!f.exists()) {
                    throw new IOException("geo file not found " + f.getAbsolutePath());
                }
                return f;
            }
            File tmp = new File(f.getPath() + ".tmp");
            log.info("geo download file {} -> {} begin", tmp.getAbsolutePath(), f.getAbsolutePath());
            try {
                try (HttpClient client = new HttpClient(new HttpClientConfig().setTimeoutMillis(timeoutMillis).setProxy(proxy))) {
                    try (HttpClient.Response response = client.get(url)) {
                        response.bodyAsFile(tmp.getPath());
                    }
                }
                f = Files.moveFile(tmp, f);
                log.info("geo download file {} -> {} end", tmp.getAbsolutePath(), f.getAbsolutePath());
            } catch (Exception e) {
                if (tmp.exists()) {
                    tmp.delete();
                }
                throw e;
            }
        }
        return f;
    }

    @SneakyThrows
    public void waitLoad() {
        loadIpSearcher();
        loadSiteMatcher();
    }

    public String getPublicIp() {
        try {
            loadIpSearcher();
        } catch (IOException e) {
            log.error("geo ip download error", e);
        }
        GeoIPSearcher r = ipSearcher;
        if (r == null) {
            return Sockets.getAnyLocalAddress().getHostAddress();
        }
        return r.getPublicIp();
    }

    private static final IpGeolocation NOT_READY = new IpGeolocation(null, null, null, "notReady");

    public IpGeolocation resolveIp(String ip) {
        try {
            loadIpSearcher();
        } catch (IOException e) {
            log.error("geo ip download error", e);
        }
        GeoIPSearcher r = ipSearcher;
        if (r == null) {
            return NOT_READY;
        }
        return r.lookup(ip);
    }

    public String resolveCity(String ip) {
        IpGeolocation geolocation = resolveIp(ip);
        return geolocation == null ? null : geolocation.getCity();
    }

    public boolean matchSiteDirect(String domain) {
        ensureSiteMatcherLoaded();
        GeoSiteMatcher r = siteMatcher;
        if (r == null) {
            return false;
        }
        return r.matches(domain);
    }
}
