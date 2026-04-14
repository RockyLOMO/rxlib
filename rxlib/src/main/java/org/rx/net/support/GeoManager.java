package org.rx.net.support;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Constants;
import org.rx.core.RxConfig;
import org.rx.core.Tasks;
import org.rx.exception.ApplicationException;
import org.rx.io.Files;
import org.rx.net.Sockets;
import org.rx.net.http.AuthenticProxy;
import org.rx.net.http.HttpClient;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    volatile Future<Void> dTask;

    public void setGeoSiteDirectRules(Set<String> geoSiteDirectRules) {
        this.geoSiteDirectRules = geoSiteDirectRules;
        load(false);
    }

    private void ensureLoaded() {
        if (ipSearcher != null && siteMatcher != null) {
            return;
        }
        if (dTask == null) {
            synchronized (this) {
                if (dTask == null) {
                    dTask = Tasks.run(() -> load(false));
                }
            }
        }
    }

    private GeoManager() {
        dTask = Tasks.run(() -> load(false));
        Tasks.scheduleDaily(() -> load(true), dailyDownloadTime);
    }

    private synchronized void load(boolean force) {
        try {
            File ipf = innerDl(force, new File(geoIpFile), geoIpFileUrl);
            GeoIPSearcher old = ipSearcher;
            ipSearcher = new GeoIPSearcher(ipf);
            Tasks.setTimeout(() -> tryClose(old), 2000);

            File sited = innerDl(force, new File(geoSiteDirectFile), geoSiteDirectFileUrl);
            try (Stream<String> lines = java.nio.file.Files.lines(sited.toPath(), StandardCharsets.UTF_8)) {
                siteMatcher = new GeoSiteMatcher(lines.iterator(), ifNull(geoSiteDirectRules, Collections.<String>emptySet()).iterator());
            }
        } catch (IOException e) {
            log.error("geoip download error", e);
            throw ApplicationException.sneaky(e);
        } finally {
            dTask = null;
        }
    }

    private File innerDl(boolean force, File f, String fileUrl) throws IOException {
        if (force || !f.exists()) {
            File tmp = new File(f.getPath() + ".tmp");
            log.info("geo download file {} -> {} begin", tmp.getAbsolutePath(), f.getAbsolutePath());
            try (HttpClient client = new HttpClient().withTimeoutMillis(timeoutMillis).withProxy(proxy)) {
                client.get(fileUrl).toFile(tmp.getPath());
            }
            f = Files.moveFile(tmp, f);
            log.info("geo download file {} -> {} end", tmp.getAbsolutePath(), f.getAbsolutePath());
        }
        return f;
    }

    public void waitLoad() throws ExecutionException, InterruptedException, TimeoutException {
        Future<Void> t = dTask;
        if (t != null) {
            t.get(timeoutMillis, TimeUnit.MILLISECONDS);
        }
    }

    public String getPublicIp() {
        ensureLoaded();
        GeoIPSearcher r = ipSearcher;
        if (r == null) {
            return Sockets.getAnyLocalAddress().getHostAddress();
        }
        return r.getPublicIp();
    }

    private static final IpGeolocation NOT_READY = new IpGeolocation(null, null, "notReady");

    public IpGeolocation resolveIp(String ip) {
        ensureLoaded();
        GeoIPSearcher r = ipSearcher;
        if (r == null) {
            return NOT_READY;
        }
        return r.lookup(ip);
    }

    public boolean matchSiteDirect(String domain) {
        ensureLoaded();
        GeoSiteMatcher r = siteMatcher;
        if (r == null) {
            return false;
        }
        return r.matches(domain);
    }
}
