package org.rx.net.support;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Constants;
import org.rx.core.RxConfig;
import org.rx.core.Tasks;
import org.rx.diagnostic.DiagnosticMetrics;
import org.rx.exception.ApplicationException;
import org.rx.net.http.AuthenticProxy;
import org.rx.net.http.HttpClient;
import org.rx.net.http.HttpClientConfig;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.rx.core.Extends.tryClose;

/**
 * V2Ray geodata 管理器。
 * <p>支持首次加载（本地优先）、本地重载、远端条件增量更新（ETag / Last-Modified / 304）、
 * 临时文件解析校验后再原子替换磁盘与快照、失败退避保留旧版，以及 generation 快照通知。</p>
 */
@Slf4j
public final class V2RayGeoManager implements Closeable {
    public static final long DEFAULT_MAX_FILE_SIZE_BYTES = Math.max(1024L * 1024L,
            Long.getLong("rx.geo.maxFileBytes", 64L * 1024L * 1024L));
    private static final long INITIAL_BACKOFF_MILLIS = TimeUnit.SECONDS.toMillis(60L);
    private static final long MAX_BACKOFF_MILLIS = TimeUnit.HOURS.toMillis(1L);

    /**
     * 全局单例不建议业务主动 close；如被调用，仅清理当前快照并保持可复用。
     */
    public static final V2RayGeoManager INSTANCE = new V2RayGeoManager(false, true);

    enum LoadMode {
        INITIAL,
        LOCAL_RELOAD,
        REMOTE_REFRESH
    }

    public static final class GeoSnapshot {
        public final long ipGeneration;
        public final long siteGeneration;
        public final V2RayGeoIpMatcher ipMatcher;
        public final V2RayGeoSiteIndex siteIndex;
        public final String ipDigest;
        public final String siteDigest;
        public final long ipUpdatedAtMillis;
        public final long siteUpdatedAtMillis;

        GeoSnapshot(long ipGeneration, long siteGeneration,
                    V2RayGeoIpMatcher ipMatcher, V2RayGeoSiteIndex siteIndex,
                    String ipDigest, String siteDigest,
                    long ipUpdatedAtMillis, long siteUpdatedAtMillis) {
            this.ipGeneration = ipGeneration;
            this.siteGeneration = siteGeneration;
            this.ipMatcher = ipMatcher;
            this.siteIndex = siteIndex;
            this.ipDigest = ipDigest;
            this.siteDigest = siteDigest;
            this.ipUpdatedAtMillis = ipUpdatedAtMillis;
            this.siteUpdatedAtMillis = siteUpdatedAtMillis;
        }

        public long generation() {
            return ipGeneration + siteGeneration;
        }
    }

    private static final class RemoteState {
        String url;
        String etag;
        String lastModified;
        String digest;
        long lastSuccessMillis;
        int consecutiveFailures;
        long backoffUntilNanos;

        synchronized void resetForUrlChange(String nextUrl) {
            if (!sameText(this.url, nextUrl)) {
                this.url = nextUrl;
                this.etag = null;
                this.lastModified = null;
                this.consecutiveFailures = 0;
                this.backoffUntilNanos = 0L;
            }
        }

        synchronized void clearBackoff() {
            this.consecutiveFailures = 0;
            this.backoffUntilNanos = 0L;
        }

        synchronized boolean isInBackoff(long nowNanos) {
            return backoffUntilNanos != 0L && nowNanos < backoffUntilNanos;
        }

        synchronized void recordSuccess(String targetUrl, String newEtag, String newLastModified,
                                        String newDigest, long nowMillis) {
            this.url = targetUrl;
            if (newEtag != null) {
                this.etag = newEtag;
            }
            if (newLastModified != null) {
                this.lastModified = newLastModified;
            }
            if (newDigest != null) {
                this.digest = newDigest;
            }
            this.lastSuccessMillis = nowMillis;
            this.consecutiveFailures = 0;
            this.backoffUntilNanos = 0L;
        }

        synchronized void recordFailure(long nowNanos) {
            consecutiveFailures++;
            int shift = Math.min(6, Math.max(0, consecutiveFailures - 1));
            long delayMillis = Math.min(MAX_BACKOFF_MILLIS, INITIAL_BACKOFF_MILLIS << shift);
            backoffUntilNanos = nowNanos + TimeUnit.MILLISECONDS.toNanos(delayMillis);
        }
    }

    private static final class DownloadOutcome {
        final boolean notModified;
        final File tempFile;
        final String etag;
        final String lastModified;
        final String digest;

        DownloadOutcome(boolean notModified, File tempFile, String etag, String lastModified, String digest) {
            this.notModified = notModified;
            this.tempFile = tempFile;
            this.etag = etag;
            this.lastModified = lastModified;
            this.digest = digest;
        }
    }

    int timeoutMillis = 5 * 60 * 1000;
    long maxFileSizeBytes = DEFAULT_MAX_FILE_SIZE_BYTES;
    AuthenticProxy proxy;
    String geoIpFileUrl = "https://" + Constants.rCloud() + ":6501/geoip.dat?rtoken=" + RxConfig.INSTANCE.getRtoken();
    String geoIpFile = "geoip.dat";
    String geoSiteFileUrl = "https://" + Constants.rCloud() + ":6501/geosite.dat?rtoken=" + RxConfig.INSTANCE.getRtoken();
    String geoSiteFile = "geosite.dat";
    String directGeoSiteCode = "cn";
    Set<String> directSiteExtraRules;
    String dailyDownloadTime = "06:30:00";
    volatile Set<String> requiredGeoIpCodes = Collections.emptySet();
    volatile Set<String> requiredGeoSiteCodes = Collections.emptySet();

    volatile V2RayGeoIpMatcher ipMatcher;
    volatile V2RayGeoSiteIndex siteIndex;
    volatile GeoSiteMatcher directSiteMatcher;
    volatile GeoSiteMatcher directSiteExtraMatcher;
    volatile Future<Void> ipTask;
    volatile Future<Void> siteTask;
    volatile boolean dailyScheduled;
    volatile List<? extends ScheduledFuture<?>> dailyTasks;
    volatile boolean closed;
    volatile int closeVersion;
    final boolean singleton;

    private final AtomicLong ipGenerationSeq = new AtomicLong();
    private final AtomicLong siteGenerationSeq = new AtomicLong();
    private final AtomicInteger ipConfigEpoch = new AtomicInteger();
    private final AtomicInteger siteConfigEpoch = new AtomicInteger();
    private final AtomicLong tempFileSeq = new AtomicLong();
    private final RemoteState ipRemote = new RemoteState();
    private final RemoteState siteRemote = new RemoteState();
    private final Set<String> activeSiteSelectors = ConcurrentHashMap.newKeySet();
    private final Set<String> activeIpSelectors = ConcurrentHashMap.newKeySet();
    private final CopyOnWriteArrayList<Consumer<GeoSnapshot>> snapshotListeners = new CopyOnWriteArrayList<>();

    private final AtomicLong loadSuccessCount = new AtomicLong();
    private final AtomicLong notModifiedCount = new AtomicLong();
    private final AtomicLong loadErrorCount = new AtomicLong();

    private volatile GeoSnapshot snapshot = new GeoSnapshot(0L, 0L, null, null, null, null, 0L, 0L);

    private V2RayGeoManager() {
        this(true, false);
    }

    V2RayGeoManager(boolean autoLoad) {
        this(autoLoad, false);
    }

    private V2RayGeoManager(boolean autoLoad, boolean singleton) {
        this.singleton = singleton;
        if (autoLoad) {
            ensureDailyScheduled();
            final int version = closeVersion;
            final int ipEpoch = ipConfigEpoch.get();
            final int siteEpoch = siteConfigEpoch.get();
            Future<Void> task = Tasks.run(() -> load(LoadMode.INITIAL, true, true, version, ipEpoch, siteEpoch, false));
            ipTask = task;
            siteTask = task;
        }
    }

    public void setTimeoutMillis(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    public void setMaxFileSizeBytes(long maxFileSizeBytes) {
        if (maxFileSizeBytes <= 0L) {
            throw new IllegalArgumentException("maxFileSizeBytes must be positive: " + maxFileSizeBytes);
        }
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    public void setProxy(AuthenticProxy proxy) {
        this.proxy = proxy;
    }

    public void setRequiredGeoIpCodes(Set<String> codes) {
        this.requiredGeoIpCodes = codes == null ? Collections.emptySet() : Collections.unmodifiableSet(codes);
    }

    public void setRequiredGeoSiteCodes(Set<String> codes) {
        this.requiredGeoSiteCodes = codes == null ? Collections.emptySet() : Collections.unmodifiableSet(codes);
    }

    public void registerActiveSiteSelector(String code) {
        String trimmed = GeoIPSearcher.trimAscii(code);
        if (trimmed != null && !trimmed.isEmpty() && activeSiteSelectors.size() < 256) {
            activeSiteSelectors.add(trimmed);
        }
    }

    public void registerActiveIpSelector(String code) {
        String trimmed = GeoIPSearcher.trimAscii(code);
        if (trimmed != null && !trimmed.isEmpty() && activeIpSelectors.size() < 256) {
            activeIpSelectors.add(trimmed);
        }
    }

    public void addSnapshotListener(Consumer<GeoSnapshot> listener) {
        if (listener != null) {
            snapshotListeners.addIfAbsent(listener);
        }
    }

    public void removeSnapshotListener(Consumer<GeoSnapshot> listener) {
        if (listener != null) {
            snapshotListeners.remove(listener);
        }
    }

    /**
     * Lock-free snapshot read with monotonic generation counters.
     * Detects package-private direct field injection in tests to stay consistent.
     */
    public GeoSnapshot snapshot() {
        GeoSnapshot current = snapshot;
        V2RayGeoIpMatcher liveIp = ipMatcher;
        V2RayGeoSiteIndex liveSite = siteIndex;
        if (current.ipMatcher == liveIp && current.siteIndex == liveSite) {
            return current;
        }
        synchronized (this) {
            current = snapshot;
            liveIp = ipMatcher;
            liveSite = siteIndex;
            if (current.ipMatcher == liveIp && current.siteIndex == liveSite) {
                return current;
            }
            long nextIpGen = current.ipMatcher == liveIp ? current.ipGeneration
                    : (liveIp == null ? 0L : ipGenerationSeq.incrementAndGet());
            long nextSiteGen = current.siteIndex == liveSite ? current.siteGeneration
                    : (liveSite == null ? 0L : siteGenerationSeq.incrementAndGet());
            GeoSnapshot synced = new GeoSnapshot(nextIpGen, nextSiteGen, liveIp, liveSite,
                    current.ipDigest, current.siteDigest,
                    current.ipUpdatedAtMillis, current.siteUpdatedAtMillis);
            snapshot = synced;
            return synced;
        }
    }

    public long generation() {
        return snapshot().generation();
    }

    public long ipGeneration() {
        return snapshot().ipGeneration;
    }

    public long siteGeneration() {
        return snapshot().siteGeneration;
    }

    public long getLoadSuccessCount() {
        return loadSuccessCount.get();
    }

    public long getNotModifiedCount() {
        return notModifiedCount.get();
    }

    public long getLoadErrorCount() {
        return loadErrorCount.get();
    }

    public String getIpDigest() {
        return snapshot().ipDigest;
    }

    public String getSiteDigest() {
        return snapshot().siteDigest;
    }

    public long getLastIpSuccessMillis() {
        return snapshot().ipUpdatedAtMillis;
    }

    public long getLastSiteSuccessMillis() {
        return snapshot().siteUpdatedAtMillis;
    }

    /**
     * 异步切换 GeoIP URL；需要同步生效时调用 compileGeoIpMatcher 或 waitLoad。
     */
    public void setGeoIpFileUrl(String geoIpFileUrl) {
        this.geoIpFileUrl = geoIpFileUrl;
        ipConfigEpoch.incrementAndGet();
        ipRemote.resetForUrlChange(GeoIPSearcher.trimAscii(geoIpFileUrl));
        if (shouldLoadGeoConfig(geoIpFile, geoIpFileUrl, "geoip.dat")) {
            scheduleIpLoad(hasText(geoIpFileUrl) ? LoadMode.REMOTE_REFRESH : LoadMode.LOCAL_RELOAD, true, false);
        }
    }

    /**
     * 异步切换 GeoIP 文件；需要同步生效时调用 compileGeoIpMatcher 或 waitLoad。
     */
    public void setGeoIpFile(String geoIpFile) {
        this.geoIpFile = geoIpFile;
        ipConfigEpoch.incrementAndGet();
        ipRemote.clearBackoff();
        if (shouldLoadGeoConfig(geoIpFile, geoIpFileUrl, "geoip.dat")) {
            scheduleIpLoad(LoadMode.INITIAL, true, false);
        }
    }

    /**
     * 异步切换 GeoSite URL；需要同步生效时调用 compileGeoSiteMatcher 或 waitLoad。
     */
    public void setGeoSiteFileUrl(String geoSiteFileUrl) {
        this.geoSiteFileUrl = geoSiteFileUrl;
        siteConfigEpoch.incrementAndGet();
        siteRemote.resetForUrlChange(GeoIPSearcher.trimAscii(geoSiteFileUrl));
        if (shouldLoadGeoConfig(geoSiteFile, geoSiteFileUrl, "geosite.dat")) {
            scheduleSiteLoad(hasText(geoSiteFileUrl) ? LoadMode.REMOTE_REFRESH : LoadMode.LOCAL_RELOAD, true, false);
        }
    }

    /**
     * 异步切换 GeoSite 文件；需要同步生效时调用 compileGeoSiteMatcher 或 waitLoad。
     */
    public void setGeoSiteFile(String geoSiteFile) {
        this.geoSiteFile = geoSiteFile;
        siteConfigEpoch.incrementAndGet();
        siteRemote.clearBackoff();
        if (shouldLoadGeoConfig(geoSiteFile, geoSiteFileUrl, "geosite.dat")) {
            scheduleSiteLoad(LoadMode.INITIAL, true, false);
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
            List<? extends ScheduledFuture<?>> newTasks = Tasks.scheduleDaily(this::scheduledRemoteRefresh, dailyDownloadTime);
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

    /**
     * 同步刷新：若配置了远端 URL，按 ETag / Last-Modified 条件拉取更新；纯本地配置则安全重载本地文件。
     */
    public void reload() {
        checkOpen();
        ipRemote.clearBackoff();
        siteRemote.clearBackoff();
        RuntimeException error = null;
        int version = closeVersion;
        int ipEpoch = ipConfigEpoch.get();
        int siteEpoch = siteConfigEpoch.get();
        try {
            load(LoadMode.REMOTE_REFRESH, true, false, version, ipEpoch, siteEpoch, false);
        } catch (RuntimeException e) {
            error = e;
        }
        try {
            load(LoadMode.REMOTE_REFRESH, false, true, version, ipEpoch, siteEpoch, false);
        } catch (RuntimeException e) {
            if (error == null) {
                error = e;
            } else {
                error.addSuppressed(e);
            }
        }
        if (error != null) {
            throw error;
        }
    }

    /**
     * 仅从本地磁盘重载（不发起任何网络请求）。
     */
    public void reloadLocal() {
        checkOpen();
        RuntimeException error = null;
        int version = closeVersion;
        int ipEpoch = ipConfigEpoch.get();
        int siteEpoch = siteConfigEpoch.get();
        try {
            load(LoadMode.LOCAL_RELOAD, true, false, version, ipEpoch, siteEpoch, false);
        } catch (RuntimeException e) {
            error = e;
        }
        try {
            load(LoadMode.LOCAL_RELOAD, false, true, version, ipEpoch, siteEpoch, false);
        } catch (RuntimeException e) {
            if (error == null) {
                error = e;
            } else {
                error.addSuppressed(e);
            }
        }
        if (error != null) {
            throw error;
        }
    }

    /**
     * 异步检查远端增量更新（若配置了远端 URL）或异步重载本地文件。
     */
    public void reloadAsync() {
        checkOpen();
        scheduleIpLoad(LoadMode.REMOTE_REFRESH, false, false);
        scheduleSiteLoad(LoadMode.REMOTE_REFRESH, false, false);
    }

    /**
     * 仅在未加载时触发后台首次加载（非阻塞、用于冷启动路由规则触发，不重复触发远端刷新）。
     */
    public void ensureLoadedAsync() {
        if (closed) {
            return;
        }
        if (ipMatcher == null && shouldLoadGeoConfig(geoIpFile, geoIpFileUrl, "geoip.dat")) {
            scheduleIpLoad(LoadMode.INITIAL, false, true);
        }
        if (siteIndex == null && shouldLoadGeoConfig(geoSiteFile, geoSiteFileUrl, "geosite.dat")) {
            scheduleSiteLoad(LoadMode.INITIAL, false, true);
        }
    }

    private void scheduledRemoteRefresh() {
        if (closed) {
            return;
        }
        scheduleIpLoad(LoadMode.REMOTE_REFRESH, false, true);
        scheduleSiteLoad(LoadMode.REMOTE_REFRESH, false, true);
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
        return scheduleIpLoad(LoadMode.INITIAL, false, false);
    }

    private Future<Void> ensureSiteLoaded() {
        checkOpen();
        if (siteIndex != null || !shouldLoadGeoConfig(geoSiteFile, geoSiteFileUrl, "geosite.dat")) {
            return null;
        }
        return scheduleSiteLoad(LoadMode.INITIAL, false, false);
    }

    private Future<Void> scheduleIpLoad(LoadMode mode, boolean replaceActive, boolean respectBackoff) {
        checkOpen();
        ensureDailyScheduled();
        Future<Void> task;
        synchronized (this) {
            checkOpen();
            task = ipTask;
            if (replaceActive || !isTaskActive(task)) {
                final int version = closeVersion;
                final int epoch = ipConfigEpoch.get();
                task = Tasks.run(() -> load(mode, true, false, version, epoch, siteConfigEpoch.get(), respectBackoff));
                ipTask = task;
            }
        }
        if (closed) {
            task.cancel(true);
            throw closedException();
        }
        return task;
    }

    private Future<Void> scheduleSiteLoad(LoadMode mode, boolean replaceActive, boolean respectBackoff) {
        checkOpen();
        ensureDailyScheduled();
        Future<Void> task;
        synchronized (this) {
            checkOpen();
            task = siteTask;
            if (replaceActive || !isTaskActive(task)) {
                final int version = closeVersion;
                final int epoch = siteConfigEpoch.get();
                task = Tasks.run(() -> load(mode, false, true, version, ipConfigEpoch.get(), epoch, respectBackoff));
                siteTask = task;
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
            List<? extends ScheduledFuture<?>> newTasks = Tasks.scheduleDaily(this::scheduledRemoteRefresh, dailyDownloadTime);
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

    private void load(LoadMode mode, boolean loadIp, boolean loadSite,
                      int loadCloseVersion, int expectedIpEpoch, int expectedSiteEpoch,
                      boolean respectBackoff) {
        if (closed || loadCloseVersion != closeVersion) {
            return;
        }
        ensureDailyScheduled();
        V2RayGeoIpMatcher newIpMatcher = null;
        V2RayGeoSiteIndex newSiteIndex = null;
        GeoSiteMatcher newDirectSiteMatcher = directSiteMatcher;
        GeoSiteMatcher newDirectSiteExtraMatcher = directSiteExtraMatcher;
        V2RayGeoIpMatcher oldIpMatcher = null;
        V2RayGeoSiteIndex oldSiteIndex = null;
        File ipTemp = null;
        File siteTemp = null;
        boolean replaceIp = false;
        boolean replaceSite = false;
        String newIpDigest = null;
        String newSiteDigest = null;
        long nowMillis = System.currentTimeMillis();

        try {
            if (loadIp && shouldLoadGeoConfig(geoIpFile, geoIpFileUrl, "geoip.dat")) {
                if (expectedIpEpoch != ipConfigEpoch.get()) {
                    return;
                }
                String url = GeoIPSearcher.trimAscii(geoIpFileUrl);
                File targetFile = geoFile(geoIpFile, "geoip.dat");
                if (respectBackoff && mode == LoadMode.REMOTE_REFRESH && hasText(url)
                        && ipRemote.isInBackoff(System.nanoTime())) {
                    log.debug("v2ray geoip remote refresh skipped during backoff");
                } else {
                    DownloadOutcome outcome = fetchIfNeeded(mode, targetFile, url, ipRemote, ipMatcher != null);
                    if (outcome.notModified) {
                        notModifiedCount.incrementAndGet();
                        DiagnosticMetrics.record("geo.refresh.notModified", 1D, "side=ip");
                    } else {
                        File sourceFile = outcome.tempFile != null ? outcome.tempFile : targetFile;
                        ipTemp = outcome.tempFile;
                        if (!sourceFile.isFile()) {
                            throw new IOException("v2ray geo file not found " + sourceFile.getAbsolutePath());
                        }
                        if (sourceFile.length() > maxFileSizeBytes) {
                            throw new IOException("v2ray geoip file exceeds max size: " + sourceFile.length());
                        }
                        newIpMatcher = new V2RayGeoIpReader().read(sourceFile);
                        validateRequiredIpCodes(newIpMatcher);
                        prewarmIpSelectors(newIpMatcher);
                        if (closed || loadCloseVersion != closeVersion || expectedIpEpoch != ipConfigEpoch.get()) {
                            tryClose(newIpMatcher);
                            newIpMatcher = null;
                            return;
                        }
                        if (ipTemp != null) {
                            replaceFileAtomically(ipTemp, targetFile);
                            ipTemp = null;
                        }
                        newIpDigest = outcome.digest != null ? outcome.digest : sha256Hex(targetFile);
                        ipRemote.recordSuccess(url, outcome.etag, outcome.lastModified, newIpDigest, nowMillis);
                        replaceIp = true;
                    }
                }
            }

            if (loadSite && shouldLoadGeoConfig(geoSiteFile, geoSiteFileUrl, "geosite.dat")) {
                if (expectedSiteEpoch != siteConfigEpoch.get()) {
                    return;
                }
                String url = GeoIPSearcher.trimAscii(geoSiteFileUrl);
                File targetFile = geoFile(geoSiteFile, "geosite.dat");
                if (respectBackoff && mode == LoadMode.REMOTE_REFRESH && hasText(url)
                        && siteRemote.isInBackoff(System.nanoTime())) {
                    log.debug("v2ray geosite remote refresh skipped during backoff");
                } else {
                    DownloadOutcome outcome = fetchIfNeeded(mode, targetFile, url, siteRemote, siteIndex != null);
                    if (outcome.notModified) {
                        notModifiedCount.incrementAndGet();
                        DiagnosticMetrics.record("geo.refresh.notModified", 1D, "side=site");
                    } else {
                        File sourceFile = outcome.tempFile != null ? outcome.tempFile : targetFile;
                        siteTemp = outcome.tempFile;
                        if (!sourceFile.isFile()) {
                            throw new IOException("v2ray geo file not found " + sourceFile.getAbsolutePath());
                        }
                        if (sourceFile.length() > maxFileSizeBytes) {
                            throw new IOException("v2ray geosite file exceeds max size: " + sourceFile.length());
                        }
                        newSiteIndex = new V2RayGeoSiteReader().read(sourceFile);
                        validateRequiredSiteCodes(newSiteIndex);
                        newDirectSiteMatcher = newSiteIndex.matcher(directGeoSiteCode, null);
                        newDirectSiteExtraMatcher = buildCustomSiteMatcher(directSiteExtraRules);
                        prewarmSiteSelectors(newSiteIndex);
                        if (closed || loadCloseVersion != closeVersion || expectedSiteEpoch != siteConfigEpoch.get()) {
                            tryClose(newSiteIndex);
                            newSiteIndex = null;
                            return;
                        }
                        if (siteTemp != null) {
                            replaceFileAtomically(siteTemp, targetFile);
                            siteTemp = null;
                        }
                        newSiteDigest = outcome.digest != null ? outcome.digest : sha256Hex(targetFile);
                        siteRemote.recordSuccess(url, outcome.etag, outcome.lastModified, newSiteDigest, nowMillis);
                        replaceSite = true;
                    }
                }
            }

            GeoSnapshot published = null;
            synchronized (this) {
                if (closed || loadCloseVersion != closeVersion
                        || (replaceIp && expectedIpEpoch != ipConfigEpoch.get())
                        || (replaceSite && expectedSiteEpoch != siteConfigEpoch.get())) {
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
                if (replaceIp || replaceSite) {
                    GeoSnapshot prev = snapshot;
                    long nextIpGen = prev.ipGeneration;
                    long nextSiteGen = prev.siteGeneration;
                    String ipDigest = prev.ipDigest;
                    String siteDigest = prev.siteDigest;
                    long ipUpdated = prev.ipUpdatedAtMillis;
                    long siteUpdated = prev.siteUpdatedAtMillis;
                    if (replaceIp) {
                        oldIpMatcher = ipMatcher;
                        ipMatcher = newIpMatcher;
                        newIpMatcher = null;
                        nextIpGen = ipGenerationSeq.incrementAndGet();
                        ipDigest = newIpDigest;
                        ipUpdated = nowMillis;
                    }
                    if (replaceSite) {
                        oldSiteIndex = siteIndex;
                        siteIndex = newSiteIndex;
                        directSiteMatcher = newDirectSiteMatcher;
                        directSiteExtraMatcher = newDirectSiteExtraMatcher;
                        newSiteIndex = null;
                        nextSiteGen = siteGenerationSeq.incrementAndGet();
                        siteDigest = newSiteDigest;
                        siteUpdated = nowMillis;
                    }
                    published = new GeoSnapshot(nextIpGen, nextSiteGen, ipMatcher, siteIndex,
                            ipDigest, siteDigest, ipUpdated, siteUpdated);
                    snapshot = published;
                    loadSuccessCount.incrementAndGet();
                }
            }

            if (published != null) {
                if (replaceIp) {
                    DiagnosticMetrics.record("geo.refresh.success", 1D, "side=ip");
                }
                if (replaceSite) {
                    DiagnosticMetrics.record("geo.refresh.success", 1D, "side=site");
                }
                notifySnapshotListeners(published);
            }
            if (replaceIp && oldIpMatcher != null) {
                final V2RayGeoIpMatcher closeIpMatcher = oldIpMatcher;
                Tasks.setTimeout(() -> tryClose(closeIpMatcher), 2000);
            }
            if (replaceSite && oldSiteIndex != null) {
                final V2RayGeoSiteIndex closeSiteIndex = oldSiteIndex;
                Tasks.setTimeout(() -> tryClose(closeSiteIndex), 2000);
            }
        } catch (Exception e) {
            if (loadIp) {
                ipRemote.recordFailure(System.nanoTime());
            }
            if (loadSite) {
                siteRemote.recordFailure(System.nanoTime());
            }
            loadErrorCount.incrementAndGet();
            DiagnosticMetrics.record("geo.refresh.error", 1D, loadIp && !loadSite ? "side=ip" : "side=site");
            if (newIpMatcher != null) {
                tryClose(newIpMatcher);
            }
            if (newSiteIndex != null) {
                tryClose(newSiteIndex);
            }
            log.error("v2ray geo load error", e);
            throw ApplicationException.sneaky(e);
        } finally {
            deleteQuietly(ipTemp);
            deleteQuietly(siteTemp);
        }
    }

    private DownloadOutcome fetchIfNeeded(LoadMode mode, File targetFile, String url,
                                          RemoteState remoteState, boolean hasInMemorySnapshot) throws IOException {
        boolean hasRemote = url != null && !url.isEmpty();
        boolean shouldDownload = false;
        if (mode == LoadMode.REMOTE_REFRESH) {
            shouldDownload = hasRemote;
        } else if (mode == LoadMode.INITIAL) {
            shouldDownload = !targetFile.exists() && hasRemote;
        }
        if (!shouldDownload) {
            return new DownloadOutcome(false, null, null, null, null);
        }
        return downloadToTempFile(targetFile, url, remoteState, hasInMemorySnapshot && targetFile.exists());
    }

    private DownloadOutcome downloadToTempFile(File targetFile, String url,
                                               RemoteState remoteState, boolean allowConditional) throws IOException {
        File parent = targetFile.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        File tmp = new File(parent, targetFile.getName() + "." + tempFileSeq.incrementAndGet() + ".tmp");
        String condEtag = null;
        String condLastMod = null;
        if (allowConditional) {
            synchronized (remoteState) {
                if (sameText(remoteState.url, url)) {
                    condEtag = remoteState.etag;
                    condLastMod = remoteState.lastModified;
                }
            }
        }

        log.info("v2ray geo download file {} -> {} begin", url, tmp.getAbsolutePath());
        try (HttpClient client = new HttpClient(new HttpClientConfig().setTimeoutMillis(timeoutMillis).setProxy(proxy))) {
            HttpClient.Request req = HttpClient.request(HttpMethod.GET, url);
            if (hasText(condEtag)) {
                req.header(HttpHeaderNames.IF_NONE_MATCH, condEtag);
            }
            if (hasText(condLastMod)) {
                req.header(HttpHeaderNames.IF_MODIFIED_SINCE, condLastMod);
            }
            try (HttpClient.Response response = client.execute(req)) {
                int status = response.code();
                if (status == 304) {
                    String newEtag = response.header(HttpHeaderNames.ETAG.toString());
                    String newLastMod = response.header(HttpHeaderNames.LAST_MODIFIED.toString());
                    remoteState.recordSuccess(url, newEtag, newLastMod, null, System.currentTimeMillis());
                    log.info("v2ray geo download not modified (304) url={}", url);
                    return new DownloadOutcome(true, null, newEtag, newLastMod, null);
                }
                if (status != 200) {
                    throw new IOException("v2ray geo download unexpected HTTP status " + status + " url=" + url);
                }
                String contentLen = response.header(HttpHeaderNames.CONTENT_LENGTH.toString());
                if (hasText(contentLen)) {
                    try {
                        long declared = Long.parseLong(contentLen.trim());
                        if (declared < 0L || declared > maxFileSizeBytes) {
                            throw new IOException("v2ray geo download content-length invalid or too large: " + declared);
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }

                MessageDigest sha = newSha256();
                long total = 0L;
                byte[] buffer = new byte[8192];
                try (InputStream in = response.bodyStream().getReader();
                     FileOutputStream out = new FileOutputStream(tmp)) {
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        total += n;
                        if (total > maxFileSizeBytes) {
                            throw new IOException("v2ray geo download exceeded maxFileSizeBytes=" + maxFileSizeBytes);
                        }
                        sha.update(buffer, 0, n);
                        out.write(buffer, 0, n);
                    }
                }
                if (total <= 0L) {
                    throw new IOException("v2ray geo download returned empty body url=" + url);
                }
                String digest = toHex(sha.digest());
                String newEtag = response.header(HttpHeaderNames.ETAG.toString());
                String newLastMod = response.header(HttpHeaderNames.LAST_MODIFIED.toString());
                log.info("v2ray geo download file {} -> {} end bytes={} sha256={}",
                        url, tmp.getAbsolutePath(), total, digest.substring(0, 12));
                return new DownloadOutcome(false, tmp, newEtag, newLastMod, digest);
            }
        } catch (Exception e) {
            deleteQuietly(tmp);
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException("v2ray geo download failed url=" + url, e);
        }
    }

    private void validateRequiredIpCodes(V2RayGeoIpMatcher matcher) {
        Set<String> required = requiredGeoIpCodes;
        if (required == null || required.isEmpty()) {
            return;
        }
        for (String code : required) {
            if (matcher.matcher(code) == null) {
                throw new org.rx.exception.InvalidException("v2ray geoip missing required code: {}", code);
            }
        }
    }

    private void validateRequiredSiteCodes(V2RayGeoSiteIndex index) {
        Set<String> required = requiredGeoSiteCodes;
        if (required == null || required.isEmpty()) {
            return;
        }
        for (String code : required) {
            if (index.matcher(code) == null) {
                throw new org.rx.exception.InvalidException("v2ray geosite missing required code: {}", code);
            }
        }
    }

    private void prewarmIpSelectors(V2RayGeoIpMatcher matcher) {
        for (String selector : activeIpSelectors) {
            try {
                matcher.matcher(selector);
            } catch (RuntimeException e) {
                log.debug("v2ray geoip prewarm selector {} skipped", selector, e);
            }
        }
    }

    private void prewarmSiteSelectors(V2RayGeoSiteIndex index) {
        for (String selector : activeSiteSelectors) {
            try {
                index.matcher(selector);
            } catch (RuntimeException e) {
                log.debug("v2ray geosite prewarm selector {} skipped", selector, e);
            }
        }
    }

    private void notifySnapshotListeners(GeoSnapshot published) {
        for (Consumer<GeoSnapshot> listener : snapshotListeners) {
            try {
                listener.accept(published);
            } catch (Throwable e) {
                log.warn("v2ray geo snapshot listener failed", e);
            }
        }
    }

    private static void replaceFileAtomically(File tempFile, File targetFile) throws IOException {
        Path src = tempFile.toPath();
        Path dst = targetFile.toPath();
        File parent = targetFile.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try {
            java.nio.file.Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            java.nio.file.Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(File f) {
        if (f != null && f.exists()) {
            f.delete();
        }
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256Hex(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            MessageDigest sha = newSha256();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                sha.update(buf, 0, n);
            }
            return toHex(sha.digest());
        } catch (Exception e) {
            return null;
        }
    }

    private static String toHex(byte[] bytes) {
        char[] hex = new char[bytes.length * 2];
        final char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            hex[i * 2] = digits[v >>> 4];
            hex[i * 2 + 1] = digits[v & 0x0F];
        }
        return new String(hex);
    }

    private static boolean sameText(String a, String b) {
        return a == null ? b == null : a.equals(b);
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
        return tryCompileGeoSiteMatcher(code, attrFilter);
    }

    public GeoSiteMatcher tryCompileGeoSiteMatcher(String code) {
        return tryCompileGeoSiteMatcher(code, null);
    }

    /**
     * 非阻塞读取当前 GeoSite 快照；刷新中或刷新失败时仍返回旧快照 matcher。
     */
    public GeoSiteMatcher tryCompileGeoSiteMatcher(String code, String attrFilter) {
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
        return tryCompileGeoIpMatcher(code);
    }

    /**
     * 非阻塞读取当前 GeoIP 快照；刷新中或刷新失败时仍返回旧快照 matcher。
     */
    public V2RayGeoIpMatcher.CodeMatcher tryCompileGeoIpMatcher(String code) {
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
        if (closed && !singleton) {
            return;
        }
        closeVersion++;
        if (!singleton) {
            closed = true;
        }

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
        snapshot = new GeoSnapshot(0L, 0L, null, null, null, null, 0L, 0L);
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
