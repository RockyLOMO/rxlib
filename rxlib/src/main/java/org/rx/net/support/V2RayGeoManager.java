package org.rx.net.support;

import lombok.extern.slf4j.Slf4j;
import org.rx.core.Constants;
import org.rx.core.RxConfig;
import org.rx.core.Tasks;
import org.rx.exception.ApplicationException;
import org.rx.io.Files;
import org.rx.net.http.AuthenticProxy;
import org.rx.net.http.HttpClient;
import org.rx.net.http.HttpClientConfig;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.rx.core.Extends.tryClose;

@Slf4j
public final class V2RayGeoManager implements Closeable {
    public static final V2RayGeoManager INSTANCE = new V2RayGeoManager(false);

    int timeoutMillis = 5 * 60 * 1000;
    AuthenticProxy proxy;
    String geoIpFileUrl = "https://" + Constants.rCloud() + ":6501/geoip.dat?rtoken=" + RxConfig.INSTANCE.getRtoken();
    String geoIpFile = "geoip.dat";
    String geoSiteFileUrl = "https://" + Constants.rCloud() + ":6501/geosite.dat?rtoken=" + RxConfig.INSTANCE.getRtoken();
    String geoSiteFile = "geosite.dat";
    String directGeoSiteCode = "cn";
    Set<String> directSiteExtraRules;
    String dailyDownloadTime = "06:30:00";
    volatile V2RayGeoIpMatcher ipMatcher;
    volatile V2RayGeoSiteIndex siteIndex;
    volatile GeoSiteMatcher directSiteMatcher;
    volatile GeoSiteMatcher directSiteExtraMatcher;
    volatile Future<Void> dTask;
    volatile boolean dailyScheduled;

    private V2RayGeoManager() {
        this(true);
    }

    V2RayGeoManager(boolean autoLoad) {
        if (autoLoad) {
            ensureDailyScheduled();
            dTask = Tasks.run(() -> load(false));
        }
    }

    public void setTimeoutMillis(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    public void setProxy(AuthenticProxy proxy) {
        this.proxy = proxy;
    }

    public void setGeoIpFileUrl(String geoIpFileUrl) {
        this.geoIpFileUrl = geoIpFileUrl;
        if (shouldLoadGeoConfig(geoIpFile, geoIpFileUrl, "geoip.dat")) {
            ensureLoadTask();
        }
    }

    public void setGeoIpFile(String geoIpFile) {
        this.geoIpFile = geoIpFile;
        if (shouldLoadGeoConfig(geoIpFile, geoIpFileUrl, "geoip.dat")) {
            ensureLoadTask();
        }
    }

    public void setGeoSiteFileUrl(String geoSiteFileUrl) {
        this.geoSiteFileUrl = geoSiteFileUrl;
        if (shouldLoadGeoConfig(geoSiteFile, geoSiteFileUrl, "geosite.dat")) {
            ensureLoadTask();
        }
    }

    public void setGeoSiteFile(String geoSiteFile) {
        this.geoSiteFile = geoSiteFile;
        if (shouldLoadGeoConfig(geoSiteFile, geoSiteFileUrl, "geosite.dat")) {
            ensureLoadTask();
        }
    }

    public void setDirectGeoSiteCode(String directGeoSiteCode) {
        this.directGeoSiteCode = directGeoSiteCode;
        V2RayGeoSiteIndex index = siteIndex;
        directSiteMatcher = index == null ? null : index.matcher(directGeoSiteCode, null);
    }

    public void setDirectSiteExtraRules(Set<String> directSiteExtraRules) {
        this.directSiteExtraRules = directSiteExtraRules;
        directSiteExtraMatcher = buildCustomSiteMatcher(directSiteExtraRules);
    }

    public void setDailyDownloadTime(String dailyDownloadTime) {
        this.dailyDownloadTime = dailyDownloadTime;
    }

    public void reload() {
        load(true);
    }

    public void waitLoad() throws ExecutionException, InterruptedException, TimeoutException {
        ensureLoaded();
        awaitLoadTask();
    }

    private void ensureLoaded() {
        boolean loadIp = shouldLoadGeoConfig(geoIpFile, geoIpFileUrl, "geoip.dat");
        boolean loadSite = shouldLoadGeoConfig(geoSiteFile, geoSiteFileUrl, "geosite.dat");
        if ((ipMatcher != null || !loadIp) && (siteIndex != null || !loadSite)) {
            return;
        }
        ensureLoadTask();
    }

    private void ensureIpLoaded() {
        if (ipMatcher != null || !shouldLoadGeoConfig(geoIpFile, geoIpFileUrl, "geoip.dat")) {
            return;
        }
        ensureLoadTask();
    }

    private void ensureSiteLoaded() {
        if (siteIndex != null || !shouldLoadGeoConfig(geoSiteFile, geoSiteFileUrl, "geosite.dat")) {
            return;
        }
        ensureLoadTask();
    }

    private void ensureLoadTask() {
        ensureDailyScheduled();
        if (dTask == null) {
            synchronized (this) {
                if (dTask == null) {
                    dTask = Tasks.run(() -> load(false));
                }
            }
        }
    }

    private void ensureDailyScheduled() {
        if (dailyScheduled) {
            return;
        }
        synchronized (this) {
            if (dailyScheduled) {
                return;
            }
            Tasks.scheduleDaily(() -> load(true), dailyDownloadTime);
            dailyScheduled = true;
        }
    }

    private void awaitLoadTask() throws ExecutionException, InterruptedException, TimeoutException {
        Future<Void> t = dTask;
        if (t != null) {
            t.get(timeoutMillis, TimeUnit.MILLISECONDS);
        }
    }

    private synchronized void load(boolean force) {
        ensureDailyScheduled();
        V2RayGeoIpMatcher oldIpMatcher = ipMatcher;
        V2RayGeoSiteIndex oldSiteIndex = siteIndex;
        V2RayGeoIpMatcher newIpMatcher = oldIpMatcher;
        V2RayGeoSiteIndex newSiteIndex = oldSiteIndex;
        GeoSiteMatcher newDirectSiteMatcher = directSiteMatcher;
        GeoSiteMatcher newDirectSiteExtraMatcher = directSiteExtraMatcher;
        boolean replaceIp = false;
        boolean replaceSite = false;
        try {
            if (shouldLoadGeoConfig(geoIpFile, geoIpFileUrl, "geoip.dat")) {
                File ipFile = innerDl(force, geoFile(geoIpFile, "geoip.dat"), geoIpFileUrl);
                newIpMatcher = new V2RayGeoIpReader().read(ipFile);
                replaceIp = true;
            }
            if (shouldLoadGeoConfig(geoSiteFile, geoSiteFileUrl, "geosite.dat")) {
                File siteFile = innerDl(force, geoFile(geoSiteFile, "geosite.dat"), geoSiteFileUrl);
                newSiteIndex = new V2RayGeoSiteReader().read(siteFile);
                newDirectSiteMatcher = newSiteIndex.matcher(directGeoSiteCode, null);
                newDirectSiteExtraMatcher = buildCustomSiteMatcher(directSiteExtraRules);
                replaceSite = true;
            }

            if (replaceIp) {
                ipMatcher = newIpMatcher;
                Tasks.setTimeout(() -> tryClose(oldIpMatcher), 2000);
            }
            if (replaceSite) {
                siteIndex = newSiteIndex;
                directSiteMatcher = newDirectSiteMatcher;
                directSiteExtraMatcher = newDirectSiteExtraMatcher;
                Tasks.setTimeout(() -> tryClose(oldSiteIndex), 2000);
            }
        } catch (Exception e) {
            if (replaceIp && newIpMatcher != oldIpMatcher) {
                tryClose(newIpMatcher);
            }
            if (replaceSite && newSiteIndex != oldSiteIndex) {
                tryClose(newSiteIndex);
            }
            log.error("v2ray geo load error", e);
            throw ApplicationException.sneaky(e);
        } finally {
            dTask = null;
        }
    }

    public boolean matchSiteDirect(String domain) {
        ensureSiteLoaded();
        GeoSiteMatcher matcher = directSiteMatcher;
        if (matcher != null && matcher.matches(domain)) {
            return true;
        }
        GeoSiteMatcher extra = directSiteExtraMatcher;
        return extra != null && extra.matches(domain);
    }

    public GeoSiteMatcher directSiteMatcher() {
        ensureSiteLoaded();
        return directSiteMatcher;
    }

    public GeoSiteMatcher siteMatcher(String code) {
        return siteMatcher(code, null);
    }

    public GeoSiteMatcher siteMatcher(String code, String attrFilter) {
        ensureSiteLoaded();
        V2RayGeoSiteIndex index = siteIndex;
        return index == null ? null : index.matcher(code, attrFilter);
    }

    /**
     * 配置期编译 geosite matcher；请求期直接复用 GeoSiteMatcher.matches(domain)。
     */
    public GeoSiteMatcher compileGeoSiteMatcher(String code) {
        return compileGeoSiteMatcher(code, null);
    }

    public GeoSiteMatcher compileGeoSiteMatcher(String code, String attrFilter) {
        ensureSiteLoaded();
        try {
            awaitLoadTask();
        } catch (ExecutionException e) {
            throw ApplicationException.sneaky(e.getCause() == null ? e : e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApplicationException.sneaky(e);
        } catch (TimeoutException e) {
            throw ApplicationException.sneaky(e);
        }
        V2RayGeoSiteIndex index = siteIndex;
        return index == null ? null : index.matcher(code, attrFilter);
    }

    public boolean matchGeoSite(String code, String domain) {
        return matchGeoSite(code, null, domain);
    }

    public boolean matchGeoSite(String code, String attrFilter, String domain) {
        GeoSiteMatcher matcher = siteMatcher(code, attrFilter);
        return matcher != null && matcher.matches(domain);
    }

    public boolean matchGeoIp(String code, String ip) {
        ensureIpLoaded();
        V2RayGeoIpMatcher matcher = ipMatcher;
        return matcher != null && matcher.matches(code, ip);
    }

    public boolean matchGeoIp(String code, byte[] ipBytes) {
        ensureIpLoaded();
        V2RayGeoIpMatcher matcher = ipMatcher;
        return matcher != null && matcher.matches(code, ipBytes);
    }

    /**
     * 配置期编译 geoip matcher；请求期直接复用 CodeMatcher.matches(byte[])。
     */
    public V2RayGeoIpMatcher.CodeMatcher compileGeoIpMatcher(String code) {
        ensureIpLoaded();
        try {
            awaitLoadTask();
        } catch (ExecutionException e) {
            throw ApplicationException.sneaky(e.getCause() == null ? e : e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApplicationException.sneaky(e);
        } catch (TimeoutException e) {
            throw ApplicationException.sneaky(e);
        }
        V2RayGeoIpMatcher matcher = ipMatcher;
        return matcher == null ? null : matcher.matcher(code);
    }

    public String resolveGeoIpCode(String ip) {
        ensureIpLoaded();
        V2RayGeoIpMatcher matcher = ipMatcher;
        return matcher == null ? null : matcher.lookupCode(ip);
    }

    public String resolveGeoIpCode(byte[] ipBytes) {
        ensureIpLoaded();
        V2RayGeoIpMatcher matcher = ipMatcher;
        return matcher == null ? null : matcher.lookupCode(ipBytes);
    }

    public V2RayGeoIpMatcher geoIpMatcher() {
        ensureIpLoaded();
        return ipMatcher;
    }

    public V2RayGeoSiteIndex geoSiteIndex() {
        ensureSiteLoaded();
        return siteIndex;
    }

    @Override
    public void close() {
        tryClose(ipMatcher);
        tryClose(siteIndex);
    }

    private File innerDl(boolean force, File f, String fileUrl) throws IOException {
        String url = GeoIPSearcher.trimAscii(fileUrl);
        if (force || !f.exists()) {
            if (url == null || url.isEmpty()) {
                if (!f.exists()) {
                    throw new IOException("v2ray geo file not found " + f.getAbsolutePath());
                }
                return f;
            }
            File tmp = new File(f.getPath() + ".tmp");
            log.info("v2ray geo download file {} -> {} begin", tmp.getAbsolutePath(), f.getAbsolutePath());
            try {
                try (HttpClient client = new HttpClient(new HttpClientConfig().setTimeoutMillis(timeoutMillis).setProxy(proxy))) {
                    try (HttpClient.Response response = client.get(url)) {
                        response.bodyAsFile(tmp.getPath());
                    }
                }
                f = Files.moveFile(tmp, f);
                log.info("v2ray geo download file {} -> {} end", tmp.getAbsolutePath(), f.getAbsolutePath());
            } catch (Exception e) {
                if (tmp.exists()) {
                    tmp.delete();
                }
                throw e;
            }
        }
        return f;
    }

    private boolean shouldLoadGeoConfig(String file, String fileUrl, String defaultName) {
        if (hasText(fileUrl)) {
            return true;
        }
        return hasText(file) && geoFile(file, defaultName).exists();
    }

    private GeoSiteMatcher buildCustomSiteMatcher(Set<String> rules) {
        if (rules == null || rules.isEmpty()) {
            return null;
        }
        return new GeoSiteMatcher(rules.iterator());
    }

    private boolean hasText(String value) {
        String v = GeoIPSearcher.trimAscii(value);
        return v != null && !v.isEmpty();
    }

    private File geoFile(String file, String defaultName) {
        String path = GeoIPSearcher.trimAscii(file);
        return new File(path == null || path.isEmpty() ? defaultName : path);
    }
}
