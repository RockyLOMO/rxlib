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
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.rx.core.Extends.tryClose;

/**
 * V2Ray geodata 管理器。
 * <p>match、resolve、geo 访问类 API 是 lazy non-blocking API，首次调用只触发后台加载，可能返回 false/null。</p>
 * <p>compile 和 waitLoad 是配置期同步 API，可能等待下载和 dat 解析；请求热点路径应复用编译后的 matcher。</p>
 * <p>set 配置异步触发加载，需要同步生效时调用 compile 或 waitLoad。</p>
 * <p>close 是终态操作，关闭后不支持 reopen 或 reload。</p>
 */
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
    volatile Future<Void> ipTask;
    volatile Future<Void> siteTask;
    volatile boolean dailyScheduled;
    volatile List<? extends ScheduledFuture<?>> dailyTasks;
    volatile boolean closed;

    private V2RayGeoManager() {
        this(true);
    }

    V2RayGeoManager(boolean autoLoad) {
        if (autoLoad) {
            ensureDailyScheduled();
            Future<Void> task = Tasks.run(() -> load(false, true, true));
            ipTask = task;
            siteTask = task;
        }
    }

    public void setTimeoutMillis(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    public void setProxy(AuthenticProxy proxy) {
        this.proxy = proxy;
    }

    /**
     * 异步切换 GeoIP URL；需要同步生效时调用 compileGeoIpMatcher 或 waitLoad。
     */
    public void setGeoIpFileUrl(String geoIpFileUrl) {
        this.geoIpFileUrl = geoIpFileUrl;
        if (shouldLoadGeoConfig(geoIpFile, geoIpFileUrl, "geoip.dat")) {
            ensureLoadTask(true, false);
        }
    }

    /**
     * 异步切换 GeoIP 文件；需要同步生效时调用 compileGeoIpMatcher 或 waitLoad。
     */
    public void setGeoIpFile(String geoIpFile) {
        this.geoIpFile = geoIpFile;
        if (shouldLoadGeoConfig(geoIpFile, geoIpFileUrl, "geoip.dat")) {
            ensureLoadTask(true, false);
        }
    }

    /**
     * 异步切换 GeoSite URL；需要同步生效时调用 compileGeoSiteMatcher 或 waitLoad。
     */
    public void setGeoSiteFileUrl(String geoSiteFileUrl) {
        this.geoSiteFileUrl = geoSiteFileUrl;
        if (shouldLoadGeoConfig(geoSiteFile, geoSiteFileUrl, "geosite.dat")) {
            ensureLoadTask(false, true);
        }
    }

    /**
     * 异步切换 GeoSite 文件；需要同步生效时调用 compileGeoSiteMatcher 或 waitLoad。
     */
    public void setGeoSiteFile(String geoSiteFile) {
        this.geoSiteFile = geoSiteFile;
        if (shouldLoadGeoConfig(geoSiteFile, geoSiteFileUrl, "geosite.dat")) {
            ensureLoadTask(false, true);
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
        java.sql.Time.valueOf(dailyDownloadTime);
        synchronized (this) {
            checkOpen();
            if (!dailyScheduled) {
                this.dailyDownloadTime = dailyDownloadTime;
                return;
            }
            List<? extends ScheduledFuture<?>> newTasks = Tasks.scheduleDaily(() -> load(true, true, true), dailyDownloadTime);
            if (closed) {
                cancelTasks(newTasks);
                throw closedException();
            }
            List<? extends ScheduledFuture<?>> oldTasks = dailyTasks;
            this.dailyDownloadTime = dailyDownloadTime;
            dailyTasks = newTasks;
            cancelTasks(oldTasks);
        }
    }

    public void reload() {
        checkOpen();
        load(true, true, true);
    }

    public void waitLoad() throws ExecutionException, InterruptedException, TimeoutException {
        Future<Void> ip = ensureIpLoaded();
        Future<Void> site = ensureSiteLoaded();
        if (ip == null) {
            ip = currentLoadTask(true);
        }
        if (site == null) {
            site = currentLoadTask(false);
        }
        awaitLoadTask(ip);
        if (site != ip) {
            awaitLoadTask(site);
        }
    }

    private Future<Void> ensureIpLoaded() {
        checkOpen();
        if (ipMatcher != null || !shouldLoadGeoConfig(geoIpFile, geoIpFileUrl, "geoip.dat")) {
            return null;
        }
        return ensureLoadTask(true, false);
    }

    private Future<Void> ensureSiteLoaded() {
        checkOpen();
        if (siteIndex != null || !shouldLoadGeoConfig(geoSiteFile, geoSiteFileUrl, "geosite.dat")) {
            return null;
        }
        return ensureLoadTask(false, true);
    }

    private Future<Void> ensureLoadTask(boolean loadIp, boolean loadSite) {
        checkOpen();
        ensureDailyScheduled();
        Future<Void> task = null;
        synchronized (this) {
            checkOpen();
            if (loadIp) {
                task = ipTask;
                if (!isTaskActive(task)) {
                    task = Tasks.run(() -> load(false, true, false));
                    ipTask = task;
                }
            }
            if (loadSite) {
                Future<Void> site = siteTask;
                if (!isTaskActive(site)) {
                    site = Tasks.run(() -> load(false, false, true));
                    siteTask = site;
                }
                task = task == null ? site : task;
            }
        }
        if (closed) {
            task.cancel(true);
            throw closedException();
        }
        return task;
    }

    private void ensureDailyScheduled() {
        if (dailyScheduled || closed) {
            return;
        }
        synchronized (this) {
            if (dailyScheduled || closed) {
                return;
            }
            List<? extends ScheduledFuture<?>> newTasks = Tasks.scheduleDaily(() -> load(true, true, true), dailyDownloadTime);
            if (closed) {
                cancelTasks(newTasks);
                return;
            }
            dailyTasks = newTasks;
            dailyScheduled = true;
        }
    }

    private void awaitLoadTask(Future<Void> task) throws ExecutionException, InterruptedException, TimeoutException {
        if (task != null) {
            task.get(timeoutMillis, TimeUnit.MILLISECONDS);
        }
    }

    private void load(boolean force, boolean loadIp, boolean loadSite) {
        if (closed) {
            return;
        }
        ensureDailyScheduled();
        V2RayGeoIpMatcher newIpMatcher = null;
        V2RayGeoSiteIndex newSiteIndex = null;
        GeoSiteMatcher newDirectSiteMatcher = directSiteMatcher;
        GeoSiteMatcher newDirectSiteExtraMatcher = directSiteExtraMatcher;
        V2RayGeoIpMatcher oldIpMatcher = null;
        V2RayGeoSiteIndex oldSiteIndex = null;
        boolean replaceIp = false;
        boolean replaceSite = false;
        try {
            if (loadIp && shouldLoadGeoConfig(geoIpFile, geoIpFileUrl, "geoip.dat")) {
                File ipFile = innerDl(force, geoFile(geoIpFile, "geoip.dat"), geoIpFileUrl);
                newIpMatcher = new V2RayGeoIpReader().read(ipFile);
                replaceIp = true;
            }
            if (loadSite && shouldLoadGeoConfig(geoSiteFile, geoSiteFileUrl, "geosite.dat")) {
                File siteFile = innerDl(force, geoFile(geoSiteFile, "geosite.dat"), geoSiteFileUrl);
                newSiteIndex = new V2RayGeoSiteReader().read(siteFile);
                newDirectSiteMatcher = newSiteIndex.matcher(directGeoSiteCode, null);
                newDirectSiteExtraMatcher = buildCustomSiteMatcher(directSiteExtraRules);
                replaceSite = true;
            }

            synchronized (this) {
                if (closed) {
                    if (replaceIp) {
                        tryClose(newIpMatcher);
                        newIpMatcher = null;
                    }
                    if (replaceSite) {
                        tryClose(newSiteIndex);
                        newSiteIndex = null;
                    }
                    return;
                }
                if (replaceIp && newIpMatcher != oldIpMatcher) {
                    oldIpMatcher = ipMatcher;
                    ipMatcher = newIpMatcher;
                    newIpMatcher = null;
                }
                if (replaceSite && newSiteIndex != oldSiteIndex) {
                    oldSiteIndex = siteIndex;
                    siteIndex = newSiteIndex;
                    directSiteMatcher = newDirectSiteMatcher;
                    directSiteExtraMatcher = newDirectSiteExtraMatcher;
                    newSiteIndex = null;
                }
            }
            if (replaceIp) {
                final V2RayGeoIpMatcher closeIpMatcher = oldIpMatcher;
                Tasks.setTimeout(() -> tryClose(closeIpMatcher), 2000);
            }
            if (replaceSite) {
                final V2RayGeoSiteIndex closeSiteIndex = oldSiteIndex;
                Tasks.setTimeout(() -> tryClose(closeSiteIndex), 2000);
            }
        } catch (Exception e) {
            if (newIpMatcher != null) {
                tryClose(newIpMatcher);
            }
            if (newSiteIndex != null) {
                tryClose(newSiteIndex);
            }
            log.error("v2ray geo load error", e);
            throw ApplicationException.sneaky(e);
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
     * 配置期编译 GeoSite matcher，可能等待下载和 dat 解析；请求期直接复用 GeoSiteMatcher.matches(domain)。
     */
    public GeoSiteMatcher compileGeoSiteMatcher(String code) {
        return compileGeoSiteMatcher(code, null);
    }

    public GeoSiteMatcher compileGeoSiteMatcher(String code, String attrFilter) {
        Future<Void> task = ensureSiteLoaded();
        if (task == null) {
            task = currentLoadTask(false);
        }
        try {
            awaitLoadTask(task);
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
     * 配置期编译 GeoIP matcher，可能等待下载和 dat 解析；请求期直接复用 CodeMatcher.matches(byte[])。
     */
    public V2RayGeoIpMatcher.CodeMatcher compileGeoIpMatcher(String code) {
        Future<Void> task = ensureIpLoaded();
        if (task == null) {
            task = currentLoadTask(true);
        }
        try {
            awaitLoadTask(task);
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
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;

        Future<Void> ip = ipTask;
        Future<Void> site = siteTask;
        ipTask = null;
        siteTask = null;
        if (ip != null) {
            ip.cancel(true);
        }
        if (site != null && site != ip) {
            site.cancel(true);
        }
        cancelTasks(dailyTasks);
        dailyTasks = null;
        dailyScheduled = false;

        V2RayGeoIpMatcher oldIpMatcher = ipMatcher;
        V2RayGeoSiteIndex oldSiteIndex = siteIndex;
        ipMatcher = null;
        siteIndex = null;
        directSiteMatcher = null;
        directSiteExtraMatcher = null;
        tryClose(oldIpMatcher);
        tryClose(oldSiteIndex);
    }

    private void cancelTasks(List<? extends ScheduledFuture<?>> tasks) {
        if (tasks == null) {
            return;
        }
        for (ScheduledFuture<?> task : tasks) {
            task.cancel(false);
        }
    }

    private Future<Void> currentLoadTask(boolean ipSide) {
        Future<Void> task = ipSide ? ipTask : siteTask;
        return isTaskActive(task) ? task : null;
    }

    private boolean isTaskActive(Future<Void> task) {
        return task != null && !task.isDone() && !task.isCancelled();
    }

    private void checkOpen() {
        if (closed) {
            throw closedException();
        }
    }

    private IllegalStateException closedException() {
        return new IllegalStateException("v2ray geo manager closed");
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
