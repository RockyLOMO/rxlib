package org.rx.net.http;

import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.ClientCookieEncoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.CookieHeaderNames;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import lombok.Getter;
import org.rx.annotation.DbColumn;
import org.rx.codec.CodecUtil;
import org.rx.core.RxConfig;
import org.rx.core.Strings;
import org.rx.io.EntityDatabase;
import org.rx.io.EntityQueryLambda;
import org.rx.net.support.PublicSuffixMatcher;

import java.io.Serializable;
import java.net.IDN;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class HttpClientCookieJar {
    private static final int MAX_COOKIE_NAME_VALUE_OCTETS = 4096;
    private static final int MAX_COOKIE_ATTRIBUTE_OCTETS = 1024;
    private static final int MAX_COOKIES = 3000;
    private static final int MAX_COOKIES_PER_DOMAIN = 50;
    private static final long MAX_COOKIE_AGE_SECONDS = TimeUnit.DAYS.toSeconds(400);
    private static final PublicSuffixMatcher PUBLIC_SUFFIX_MATCHER = PublicSuffixMatcher.DEFAULT;
    /**
     * 进程内共享的默认 Cookie 罐，由 {@link RxConfig.HttpConfig#getClientCookieJar()}（配置键 app.net.http.clientCookieJar）决定 memory 或 H2 storage。
     */
    public static final HttpClientCookieJar DEFAULT = createDefaultJar();

    private static HttpClientCookieJar createDefaultJar() {
        try {
            String kind = RxConfig.INSTANCE.getNet().getHttp().getClientCookieJar();
            if ("storage".equalsIgnoreCase(kind)) {
                return storage(EntityDatabase.DEFAULT);
            }
        } catch (Throwable ignored) {
        }
        return memory();
    }
    private static final AtomicLong COOKIE_SEQUENCE = new AtomicLong();
    private static final Comparator<StoredCookie> COOKIE_ORDER = new Comparator<StoredCookie>() {
        @Override
        public int compare(StoredCookie a, StoredCookie b) {
            int result = b.path.length() - a.path.length();
            if (result != 0) {
                return result;
            }
            long av = a.creationOrder != null ? a.creationOrder : 0L;
            long bv = b.creationOrder != null ? b.creationOrder : 0L;
            return av < bv ? -1 : (av == bv ? 0 : 1);
        }
    };

    final HttpClientCookieStorage storage;
    final CopyOnWriteArrayList<StoredCookie> cookies;

    public enum SameSiteContext {
        SAME_SITE,
        CROSS_SITE_TOP_LEVEL_SAFE,
        CROSS_SITE
    }

    public HttpClientCookieJar() {
        this(new MemoryCookieStorage());
    }

    public HttpClientCookieJar(HttpClientCookieStorage storage) {
        this.storage = storage != null ? storage : new MemoryCookieStorage();
        cookies = new CopyOnWriteArrayList<>(this.storage.loadAll());
        for (StoredCookie cookie : cookies) {
            advanceCookieSequence(cookie.creationOrder);
            advanceCookieSequence(cookie.lastAccessTime);
        }
    }

    public static HttpClientCookieJar memory() {
        return new HttpClientCookieJar(new MemoryCookieStorage());
    }

    public static HttpClientCookieJar storage(EntityDatabase db) {
        return new HttpClientCookieJar(new H2CookieStorage(db));
    }

    public String loadForRequest(URI uri) {
        return loadForRequest(uri, SameSiteContext.SAME_SITE);
    }

    public String loadForRequest(URI uri, SameSiteContext sameSiteContext) {
        return loadForRequest(uri, sameSiteContext, uri);
    }

    public String loadForRequest(URI uri, SameSiteContext sameSiteContext, URI topLevelUri) {
        List<StoredCookie> matched = cookies(uri, sameSiteContext, topLevelUri);
        if (matched.isEmpty()) {
            return null;
        }
        Collections.sort(matched, COOKIE_ORDER);
        List<Cookie> encoded = new ArrayList<>(matched.size());
        for (StoredCookie stored : matched) {
            encoded.add(stored.cookie());
        }
        return ClientCookieEncoder.STRICT.encode(encoded);
    }

    public List<StoredCookie> cookies(URI uri) {
        return cookies(uri, SameSiteContext.SAME_SITE, uri);
    }

    public List<StoredCookie> cookies(URI uri, SameSiteContext sameSiteContext, URI topLevelUri) {
        long now = System.currentTimeMillis();
        String host = canonicalHost(uri.getHost());
        if (Strings.isEmpty(host)) {
            return Collections.emptyList();
        }
        boolean secureRequest = isSecureConnection(uri.getScheme(), host);
        String path = requestPath(uri);
        String partitionKey = siteKey(topLevelUri != null ? topLevelUri : uri);
        List<StoredCookie> matched = new ArrayList<>();
        for (StoredCookie stored : cookies) {
            if (stored.isExpired(now)) {
                cookies.remove(stored);
                storage.remove(stored);
                continue;
            }
            if (stored.matches(secureRequest, host, path, sameSiteContext, partitionKey)) {
                stored.lastAccessTime = now;
                matched.add(stored);
            }
        }
        Collections.sort(matched, COOKIE_ORDER);
        return matched;
    }

    public void clear(URI uri) {
        List<StoredCookie> matched = cookies(uri);
        for (StoredCookie stored : matched) {
            cookies.remove(stored);
            storage.remove(stored);
        }
    }

    public void saveFromResponse(URI uri, List<String> setCookies) {
        saveFromResponse(uri, setCookies, SameSiteContext.SAME_SITE, true);
    }

    public void saveFromResponse(URI uri, List<String> setCookies, SameSiteContext sameSiteContext, boolean topLevelNavigation) {
        saveFromResponse(uri, setCookies, sameSiteContext, topLevelNavigation, uri);
    }

    public void saveFromResponse(URI uri, List<String> setCookies, SameSiteContext sameSiteContext, boolean topLevelNavigation, URI topLevelUri) {
        if (setCookies == null || setCookies.isEmpty()) {
            return;
        }
        for (String raw : setCookies) {
            Cookie cookie = ClientCookieDecoder.STRICT.decode(raw);
            if (cookie != null) {
                save(uri, cookie, sameSiteContext, topLevelNavigation, topLevelUri);
            }
        }
    }

    public void saveRawCookie(URI uri, String rawCookie) {
        if (Strings.isEmpty(rawCookie)) {
            return;
        }
        for (String pair : Strings.split(rawCookie, ";")) {
            int i = pair.indexOf("=");
            if (i <= 0) {
                continue;
            }
            DefaultCookie cookie = new DefaultCookie(pair.substring(0, i).trim(), pair.substring(i + 1).trim());
            save(uri, cookie);
        }
    }

    void save(URI uri, Cookie cookie) {
        save(uri, cookie, SameSiteContext.SAME_SITE, true);
    }

    void save(URI uri, Cookie cookie, SameSiteContext sameSiteContext, boolean topLevelNavigation) {
        save(uri, cookie, sameSiteContext, topLevelNavigation, uri);
    }

    void save(URI uri, Cookie cookie, SameSiteContext sameSiteContext, boolean topLevelNavigation, URI topLevelUri) {
        String requestHost = canonicalHost(uri.getHost());
        if (Strings.isEmpty(requestHost)) {
            return;
        }
        if (!isCookieNameValueValid(cookie)) {
            return;
        }
        boolean secureConnection = isSecureConnection(uri.getScheme(), requestHost);
        boolean secure = cookie.isSecure();
        if (secure && !secureConnection) {
            return;
        }
        boolean hostOnly = Strings.isEmpty(cookie.domain()) || cookie.domain().length() > MAX_COOKIE_ATTRIBUTE_OCTETS;
        if (hostOnly) {
            cookie.setDomain(requestHost);
        } else {
            String domain = canonicalDomain(cookie.domain());
            if (Strings.isEmpty(domain)) {
                return;
            }
            if (isPublicSuffix(domain)) {
                if (!domain.equals(requestHost)) {
                    return;
                }
                hostOnly = true;
                domain = requestHost;
            } else if (!domainMatches(requestHost, domain)) {
                return;
            }
            cookie.setDomain(domain);
        }
        boolean hasPathAttribute = !Strings.isEmpty(cookie.path()) && cookie.path().charAt(0) == '/'
                && cookie.path().length() <= MAX_COOKIE_ATTRIBUTE_OCTETS;
        if (!hasPathAttribute) {
            cookie.setPath(defaultPath(uri));
        }
        if (cookie.path().length() > MAX_COOKIE_ATTRIBUTE_OCTETS) {
            return;
        }
        String sameSite = sameSite(cookie);
        if (!"None".equals(sameSite) && sameSiteContext == SameSiteContext.CROSS_SITE && !topLevelNavigation) {
            return;
        }
        if ("None".equals(sameSite) && !secure) {
            return;
        }
        boolean partitioned = isPartitioned(cookie);
        String partitionKey = partitioned ? siteKey(topLevelUri != null ? topLevelUri : uri) : null;
        if (partitioned && (!secure || Strings.isEmpty(partitionKey))) {
            return;
        }
        if (!isCookiePrefixValid(cookie, secure, hostOnly, hasPathAttribute)) {
            return;
        }
        StoredCookie stored = new StoredCookie(cookie, hostOnly, partitionKey);
        if (overlaysSecureCookie(stored, secureConnection)) {
            return;
        }
        StoredCookie old = find(stored);
        if (old != null) {
            stored.creationOrder = old.creationOrder;
        }
        cookies.remove(stored);
        long now = System.currentTimeMillis();
        if (stored.isExpired(now)) {
            storage.remove(stored);
            return;
        }
        cookies.add(stored);
        storage.save(stored);
        evictExpiredAndExcess(now);
    }

    private StoredCookie find(StoredCookie target) {
        for (StoredCookie cookie : cookies) {
            if (cookie.equals(target)) {
                return cookie;
            }
        }
        return null;
    }

    private boolean overlaysSecureCookie(StoredCookie target, boolean secureConnection) {
        if (target.isSecure() || secureConnection) {
            return false;
        }
        for (StoredCookie cookie : cookies) {
            if (!cookie.isSecure() || !cookie.name.equals(target.name)) {
                continue;
            }
            if ((domainMatches(cookie.domain, target.domain) || domainMatches(target.domain, cookie.domain))
                    && pathMatches(target.path, cookie.path)) {
                return true;
            }
        }
        return false;
    }

    private void evictExpiredAndExcess(long now) {
        for (StoredCookie cookie : cookies) {
            if (cookie.isExpired(now)) {
                cookies.remove(cookie);
                storage.remove(cookie);
            }
        }
        evictExcess(MAX_COOKIES_PER_DOMAIN, true);
        evictExcess(MAX_COOKIES, false);
    }

    private void evictExcess(int limit, boolean perDomain) {
        while (true) {
            StoredCookie evicted = null;
            int maxCount = 0;
            for (StoredCookie cookie : cookies) {
                int count = perDomain ? countDomain(cookie.domain) : cookies.size();
                if (count <= limit || count < maxCount) {
                    continue;
                }
                if (count > maxCount) {
                    maxCount = count;
                    evicted = cookie;
                    continue;
                }
                if (evictionPriority(cookie, perDomain) < evictionPriority(evicted, perDomain)
                        || (evictionPriority(cookie, perDomain) == evictionPriority(evicted, perDomain) && cookie.accessTime() < evicted.accessTime())) {
                    evicted = cookie;
                }
            }
            if (evicted == null) {
                return;
            }
            cookies.remove(evicted);
            storage.remove(evicted);
        }
    }

    private int countDomain(String domain) {
        int count = 0;
        for (StoredCookie cookie : cookies) {
            if (cookie.domain.equals(domain)) {
                count++;
            }
        }
        return count;
    }

    private static int evictionPriority(StoredCookie cookie, boolean perDomain) {
        return perDomain && cookie.isSecure() ? 1 : 0;
    }

    private static void advanceCookieSequence(Long value) {
        if (value == null) {
            return;
        }
        long current;
        do {
            current = COOKIE_SEQUENCE.get();
            if (current >= value) {
                return;
            }
        } while (!COOKIE_SEQUENCE.compareAndSet(current, value));
    }

    public void clearSession() {
        long now = System.currentTimeMillis();
        for (StoredCookie cookie : cookies) {
            if (cookie.isSession() || cookie.isExpired(now)) {
                cookies.remove(cookie);
                storage.remove(cookie);
            }
        }
    }

    public void clear() {
        cookies.clear();
        storage.clear();
    }

    public interface HttpClientCookieStorage {
        List<StoredCookie> loadAll();

        void save(StoredCookie cookie);

        void remove(StoredCookie cookie);

        void clear();
    }

    public static final class MemoryCookieStorage implements HttpClientCookieStorage {
        final CopyOnWriteArrayList<StoredCookie> store = new CopyOnWriteArrayList<>();

        @Override
        public List<StoredCookie> loadAll() {
            return new ArrayList<>(store);
        }

        @Override
        public void save(StoredCookie cookie) {
            store.remove(cookie);
            store.add(cookie);
        }

        @Override
        public void remove(StoredCookie cookie) {
            store.remove(cookie);
        }

        @Override
        public void clear() {
            store.clear();
        }
    }

    public static final class H2CookieStorage implements HttpClientCookieStorage {
        final EntityDatabase db;

        public H2CookieStorage(EntityDatabase db) {
            this.db = db != null ? db : EntityDatabase.DEFAULT;
            this.db.createMapping(StoredCookie.class);
        }

        @Override
        public List<StoredCookie> loadAll() {
            long now = System.currentTimeMillis();
            List<StoredCookie> result = new ArrayList<>();
            List<StoredCookie> entities = db.findBy(new EntityQueryLambda<>(StoredCookie.class));
            for (StoredCookie cookie : entities) {
                if (!cookie.isValid() || cookie.isExpired(now)) {
                    db.deleteById(StoredCookie.class, cookie.id);
                    continue;
                }
                result.add(cookie);
            }
            return result;
        }

        @Override
        public void save(StoredCookie cookie) {
            db.save(cookie, true);
        }

        @Override
        public void remove(StoredCookie cookie) {
            db.deleteById(StoredCookie.class, StoredCookie.id(cookie.name, cookie.domain, cookie.path, cookie.isHostOnly(), cookie.partitionKey));
        }

        @Override
        public void clear() {
            db.truncateMapping(StoredCookie.class);
        }
    }

    @Getter
    public static final class StoredCookie implements Serializable {
        private static final long serialVersionUID = -1714170629027078331L;

        @DbColumn(primaryKey = true, length = 512)
        public String id;
        @DbColumn(index = DbColumn.IndexKind.INDEX_ASC, length = 255)
        public String domain;
        public String path;
        public String name;
        public String value;
        public Boolean secure;
        public Boolean hostOnly;
        public Boolean httpOnly;
        public Boolean wrap;
        public String sameSite;
        public Boolean partitioned;
        public String partitionKey;
        @DbColumn(index = DbColumn.IndexKind.INDEX_ASC)
        public Long expiresAt;
        public Boolean session;
        public Long creationOrder;
        public Long lastAccessTime;
        transient Cookie cookie;

        public static String id(String name, String domain, String path, boolean hostOnly, String partitionKey) {
            String key = (hostOnly ? "H|" : "D|")
                    + (domain == null ? Strings.EMPTY : domain.toLowerCase(Locale.ENGLISH))
                    + "|" + (path == null ? "/" : path)
                    + "|" + name;
            if (!Strings.isEmpty(partitionKey)) {
                key += "|P|" + partitionKey;
            }
            return CodecUtil.hexMd5(key);
        }

        public StoredCookie() {
        }

        StoredCookie(Cookie cookie) {
            this(cookie, false, cookie.maxAge() == Cookie.UNDEFINED_MAX_AGE,
                    expiresAt(cookie, System.currentTimeMillis()));
        }

        StoredCookie(Cookie cookie, boolean hostOnly) {
            this(cookie, hostOnly, null);
        }

        StoredCookie(Cookie cookie, boolean hostOnly, String partitionKey) {
            this(cookie, hostOnly, cookie.maxAge() == Cookie.UNDEFINED_MAX_AGE,
                    expiresAt(cookie, System.currentTimeMillis()), partitionKey);
        }

        StoredCookie(Cookie cookie, boolean hostOnly, boolean session, long expiresAt) {
            this(cookie, hostOnly, session, expiresAt, null);
        }

        StoredCookie(Cookie cookie, boolean hostOnly, boolean session, long expiresAt, String partitionKey) {
            this.cookie = cookie;
            name = cookie.name();
            domain = cookie.domain() != null ? cookie.domain().toLowerCase(Locale.ENGLISH) : Strings.EMPTY;
            path = cookie.path() != null ? cookie.path() : "/";
            value = cookie.value();
            secure = cookie.isSecure();
            this.hostOnly = hostOnly;
            httpOnly = cookie.isHttpOnly();
            wrap = cookie.wrap();
            sameSite = HttpClientCookieJar.sameSite(cookie);
            partitioned = HttpClientCookieJar.isPartitioned(cookie);
            this.partitionKey = partitionKey;
            this.session = session;
            this.expiresAt = expiresAt;
            creationOrder = COOKIE_SEQUENCE.incrementAndGet();
            lastAccessTime = creationOrder;
            id = id(name, domain, path, isHostOnly(), this.partitionKey);
        }

        Cookie cookie() {
            Cookie c = cookie;
            if (c != null) {
                return c;
            }
            DefaultCookie dc = new DefaultCookie(name, value != null ? value : Strings.EMPTY);
            dc.setWrap(Boolean.TRUE.equals(wrap));
            dc.setDomain(domain);
            dc.setPath(path);
            dc.setSecure(Boolean.TRUE.equals(secure));
            dc.setHttpOnly(Boolean.TRUE.equals(httpOnly));
            CookieHeaderNames.SameSite ss = sameSiteValue(sameSite);
            if (ss != null) {
                dc.setSameSite(ss);
            }
            dc.setPartitioned(Boolean.TRUE.equals(partitioned));
            if (!Boolean.TRUE.equals(session) && expiresAt != null && expiresAt != Long.MAX_VALUE) {
                long seconds = Math.max(1L, (expiresAt - System.currentTimeMillis() + 999L) / 1000L);
                dc.setMaxAge(seconds);
            }
            cookie = dc;
            return dc;
        }

        public Cookie getCookie() {
            return cookie();
        }

        boolean isValid() {
            if (Strings.isEmpty(name) || Strings.isEmpty(domain) || Strings.isEmpty(path) || value == null || expiresAt == null) {
                return false;
            }
            domain = canonicalHost(domain);
            if (Strings.isEmpty(domain) || path.length() > MAX_COOKIE_ATTRIBUTE_OCTETS
                    || !isNameValueValid(name, value)) {
                return false;
            }
            if (session == null) {
                session = false;
            }
            if (secure == null) {
                secure = false;
            }
            if (hostOnly == null) {
                hostOnly = false;
            }
            if (wrap == null) {
                wrap = false;
            }
            if (partitioned == null) {
                partitioned = false;
            }
            if (!isPartitioned()) {
                partitionKey = null;
            }
            if (creationOrder == null) {
                creationOrder = COOKIE_SEQUENCE.incrementAndGet();
            }
            if (lastAccessTime == null) {
                lastAccessTime = creationOrder;
            }
            if (!isStoredDomainValid()) {
                return false;
            }
            String expectedId = id(name, domain, path, isHostOnly(), partitionKey);
            if (!Strings.isEmpty(id) && !id.equals(expectedId)) {
                return false;
            }
            id = expectedId;
            return true;
        }

        boolean isExpired(long now) {
            return expiresAt <= now;
        }

        public boolean isSession() {
            return Boolean.TRUE.equals(session);
        }

        public boolean isSecure() {
            return Boolean.TRUE.equals(secure);
        }

        public boolean isHostOnly() {
            return Boolean.TRUE.equals(hostOnly);
        }

        public boolean isHttpOnly() {
            return Boolean.TRUE.equals(httpOnly);
        }

        public boolean isPartitioned() {
            return Boolean.TRUE.equals(partitioned);
        }

        long accessTime() {
            return lastAccessTime != null ? lastAccessTime : (creationOrder != null ? creationOrder : 0L);
        }

        boolean isStoredDomainValid() {
            return isHostOnly() || !isPublicSuffix(domain);
        }

        boolean matches(boolean secureRequest, String host, String requestPath, SameSiteContext sameSiteContext, String requestPartitionKey) {
            if (isSecure() && !secureRequest) {
                return false;
            }
            if (isPartitioned() && (Strings.isEmpty(partitionKey) || !partitionKey.equals(requestPartitionKey))) {
                return false;
            }
            if (!isHostOnly() && isPublicSuffix(domain)) {
                return false;
            }
            if (isHostOnly() ? !host.equals(domain) : !domainMatches(host, domain)) {
                return false;
            }
            return pathMatches(requestPath, path) && sameSiteAllows(sameSite, sameSiteContext);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof StoredCookie)) {
                return false;
            }
            StoredCookie that = (StoredCookie) o;
            return name.equals(that.name)
                    && domain.equals(that.domain)
                    && path.equals(that.path)
                    && isHostOnly() == that.isHostOnly()
                    && Objects.equals(partitionKey, that.partitionKey);
        }

        @Override
        public int hashCode() {
            int result = name.hashCode();
            result = 31 * result + domain.hashCode();
            result = 31 * result + path.hashCode();
            result = 31 * result + (isHostOnly() ? 1 : 0);
            result = 31 * result + (partitionKey != null ? partitionKey.hashCode() : 0);
            return result;
        }
    }

    private static boolean isCookieNameValueValid(Cookie cookie) {
        return isNameValueValid(cookie.name(), cookie.value());
    }

    private static boolean isNameValueValid(String name, String value) {
        if (name == null || value == null) {
            return false;
        }
        if (containsDisallowedCtl(name) || containsDisallowedCtl(value)) {
            return false;
        }
        return name.getBytes(StandardCharsets.UTF_8).length + value.getBytes(StandardCharsets.UTF_8).length <= MAX_COOKIE_NAME_VALUE_OCTETS;
    }

    private static boolean containsDisallowedCtl(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c <= 0x08 || (c >= 0x0A && c <= 0x1F) || c == 0x7F)) {
                return true;
            }
        }
        return false;
    }

    private static long expiresAt(Cookie cookie, long now) {
        long maxAge = cookie.maxAge();
        if (maxAge == Cookie.UNDEFINED_MAX_AGE) {
            return Long.MAX_VALUE;
        }
        if (maxAge <= 0L) {
            return Long.MIN_VALUE;
        }
        long seconds = Math.min(maxAge, MAX_COOKIE_AGE_SECONDS);
        long millis = TimeUnit.SECONDS.toMillis(seconds);
        return now > Long.MAX_VALUE - millis ? Long.MAX_VALUE : now + millis;
    }

    private static String sameSite(Cookie cookie) {
        if (!(cookie instanceof DefaultCookie)) {
            return null;
        }
        CookieHeaderNames.SameSite sameSite = ((DefaultCookie) cookie).sameSite();
        return sameSite != null ? sameSite.name() : null;
    }

    private static CookieHeaderNames.SameSite sameSiteValue(String sameSite) {
        if ("Strict".equalsIgnoreCase(sameSite)) {
            return CookieHeaderNames.SameSite.Strict;
        }
        if ("Lax".equalsIgnoreCase(sameSite)) {
            return CookieHeaderNames.SameSite.Lax;
        }
        if ("None".equalsIgnoreCase(sameSite)) {
            return CookieHeaderNames.SameSite.None;
        }
        return null;
    }

    private static boolean sameSiteAllows(String sameSite, SameSiteContext context) {
        SameSiteContext c = context != null ? context : SameSiteContext.SAME_SITE;
        if (c == SameSiteContext.SAME_SITE || "None".equalsIgnoreCase(sameSite)) {
            return true;
        }
        if ("Strict".equalsIgnoreCase(sameSite)) {
            return false;
        }
        return c == SameSiteContext.CROSS_SITE_TOP_LEVEL_SAFE;
    }

    private static boolean isPartitioned(Cookie cookie) {
        return cookie instanceof DefaultCookie && ((DefaultCookie) cookie).isPartitioned();
    }

    private static boolean isCookiePrefixValid(Cookie cookie, boolean secure, boolean hostOnly, boolean hasPathAttribute) {
        String name = cookie.name();
        if (startsWithIgnoreCase(name, "__Secure-") && !secure) {
            return false;
        }
        if (startsWithIgnoreCase(name, "__Host-")) {
            return secure && hostOnly && hasPathAttribute && "/".equals(cookie.path());
        }
        return true;
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value != null && value.length() >= prefix.length() && value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static boolean isSecureConnection(String scheme, String host) {
        return "https".equalsIgnoreCase(scheme) || "wss".equalsIgnoreCase(scheme) || isLocalhost(host);
    }

    private static boolean isLocalhost(String host) {
        return "localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host) || "0:0:0:0:0:0:0:1".equals(host);
    }

    private static String siteKey(URI uri) {
        if (uri == null) {
            return null;
        }
        String host = canonicalHost(uri.getHost());
        if (Strings.isEmpty(host)) {
            return null;
        }
        String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ENGLISH) : "http";
        return scheme + "://" + registrableDomain(host);
    }

    private static String registrableDomain(String host) {
        if (isIpAddress(host) || "localhost".equals(host)) {
            return host;
        }
        return PUBLIC_SUFFIX_MATCHER.registrableDomain(host);
    }

    private static String canonicalHost(String host) {
        if (Strings.isEmpty(host)) {
            return null;
        }
        String value = host.trim();
        if (value.length() > 1 && value.charAt(0) == '[' && value.charAt(value.length() - 1) == ']') {
            value = value.substring(1, value.length() - 1);
        }
        if (value.length() == 0 || value.charAt(0) == '.' || value.charAt(value.length() - 1) == '.') {
            return null;
        }
        if (value.indexOf(':') >= 0) {
            return value.toLowerCase(Locale.ENGLISH);
        }
        try {
            return IDN.toASCII(value).toLowerCase(Locale.ENGLISH);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String canonicalDomain(String domain) {
        if (Strings.isEmpty(domain)) {
            return null;
        }
        String value = domain.trim();
        while (value.startsWith(".")) {
            value = value.substring(1);
        }
        return canonicalHost(value);
    }

    private static boolean domainMatches(String host, String domain) {
        if (host.equals(domain)) {
            return true;
        }
        return !isIpAddress(host) && host.endsWith("." + domain);
    }

    private static boolean isPublicSuffix(String domain) {
        if (Strings.isEmpty(domain) || isIpAddress(domain)) {
            return false;
        }
        return PUBLIC_SUFFIX_MATCHER.isPublicSuffix(domain);
    }

    private static boolean isIpAddress(String host) {
        if (host.indexOf(':') >= 0) {
            return true;
        }
        boolean dot = false;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c == '.') {
                dot = true;
                continue;
            }
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return dot;
    }

    private static String requestPath(URI uri) {
        String path = uri.getRawPath();
        return Strings.isEmpty(path) ? "/" : path;
    }

    private static String defaultPath(URI uri) {
        String path = requestPath(uri);
        if (path.charAt(0) != '/') {
            return "/";
        }
        int i = path.lastIndexOf('/');
        return i <= 0 ? "/" : path.substring(0, i);
    }

    private static boolean pathMatches(String requestPath, String cookiePath) {
        if (requestPath.equals(cookiePath)) {
            return true;
        }
        if (!requestPath.startsWith(cookiePath)) {
            return false;
        }
        return cookiePath.endsWith("/") || (requestPath.length() > cookiePath.length() && requestPath.charAt(cookiePath.length()) == '/');
    }
}
