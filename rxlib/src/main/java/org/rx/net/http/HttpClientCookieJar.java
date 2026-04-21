package org.rx.net.http;

import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.ClientCookieEncoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import lombok.Getter;
import org.rx.annotation.DbColumn;
import org.rx.core.Strings;
import org.rx.io.EntityDatabase;
import org.rx.io.EntityQueryLambda;

import java.io.Serializable;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

public final class HttpClientCookieJar {
    public static final HttpClientCookieJar COOKIES = new HttpClientCookieJar();

    final HttpClientCookieStorage storage;
    final CopyOnWriteArrayList<StoredCookie> cookies;

    public HttpClientCookieJar() {
        this(new MemoryCookieStorage());
    }

    public HttpClientCookieJar(HttpClientCookieStorage storage) {
        this.storage = storage != null ? storage : new MemoryCookieStorage();
        cookies = new CopyOnWriteArrayList<>(this.storage.loadAll());
    }

    public static HttpClientCookieJar memory() {
        return new HttpClientCookieJar(new MemoryCookieStorage());
    }

    public static HttpClientCookieJar h2(EntityDatabase db) {
        return new HttpClientCookieJar(new H2CookieStorage(db));
    }

    public String loadForRequest(URI uri) {
        long now = System.currentTimeMillis();
        List<Cookie> matched = new ArrayList<>();
        for (StoredCookie stored : cookies) {
            if (stored.isExpired(now)) {
                cookies.remove(stored);
                storage.remove(stored);
                continue;
            }
            if (stored.matches(uri)) {
                matched.add(stored.cookie);
            }
        }
        return matched.isEmpty() ? null : ClientCookieEncoder.STRICT.encode(matched);
    }

    public void saveFromResponse(URI uri, List<String> setCookies) {
        if (setCookies == null || setCookies.isEmpty()) {
            return;
        }
        for (String raw : setCookies) {
            Cookie cookie = ClientCookieDecoder.STRICT.decode(raw);
            if (cookie != null) {
                save(uri, cookie);
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
        if (Strings.isEmpty(cookie.domain())) {
            cookie.setDomain(uri.getHost());
        }
        if (Strings.isEmpty(cookie.path())) {
            cookie.setPath("/");
        }
        StoredCookie stored = new StoredCookie(cookie);
        cookies.remove(stored);
        if (stored.isExpired(System.currentTimeMillis())) {
            storage.remove(stored);
            return;
        }
        cookies.add(stored);
        storage.save(stored);
    }

    public void clearSession() {
        long now = System.currentTimeMillis();
        for (StoredCookie cookie : cookies) {
            if (cookie.session || cookie.isExpired(now)) {
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
            this.db.createMapping(CookieEntity.class);
        }

        @Override
        public List<StoredCookie> loadAll() {
            long now = System.currentTimeMillis();
            List<StoredCookie> result = new ArrayList<>();
            List<CookieEntity> entities = db.findBy(new EntityQueryLambda<>(CookieEntity.class));
            for (CookieEntity entity : entities) {
                StoredCookie cookie = entity.toStoredCookie(now);
                if (cookie == null || cookie.isExpired(now)) {
                    db.deleteById(CookieEntity.class, entity.id);
                    continue;
                }
                result.add(cookie);
            }
            return result;
        }

        @Override
        public void save(StoredCookie cookie) {
            if (cookie.session) {
                remove(cookie);
                return;
            }
            db.save(CookieEntity.from(cookie), true);
        }

        @Override
        public void remove(StoredCookie cookie) {
            db.deleteById(CookieEntity.class, CookieEntity.id(cookie.name, cookie.domain, cookie.path));
        }

        @Override
        public void clear() {
            db.truncateMapping(CookieEntity.class);
        }
    }

    public static final class CookieEntity implements Serializable {
        private static final long serialVersionUID = -1714170629027078331L;

        @DbColumn(primaryKey = true, length = 512)
        public String id;
        @DbColumn(index = DbColumn.IndexKind.INDEX_ASC, length = 255)
        public String domain;
        public String path;
        public String name;
        public String value;
        public Boolean secure;
        public Boolean httpOnly;
        @DbColumn(index = DbColumn.IndexKind.INDEX_ASC)
        public Long expiresAt;

        public static CookieEntity from(StoredCookie cookie) {
            CookieEntity entity = new CookieEntity();
            entity.id = id(cookie.name, cookie.domain, cookie.path);
            entity.domain = cookie.domain;
            entity.path = cookie.path;
            entity.name = cookie.name;
            entity.value = cookie.cookie.value();
            entity.secure = cookie.secure;
            entity.httpOnly = cookie.httpOnly;
            entity.expiresAt = cookie.expiresAt;
            return entity;
        }

        public static String id(String name, String domain, String path) {
            return (domain == null ? Strings.EMPTY : domain.toLowerCase(Locale.ENGLISH)) + "|" + (path == null ? "/" : path) + "|" + name;
        }

        StoredCookie toStoredCookie(long now) {
            if (Strings.isEmpty(name) || Strings.isEmpty(domain) || Strings.isEmpty(path) || expiresAt == null || expiresAt <= now) {
                return null;
            }
            DefaultCookie cookie = new DefaultCookie(name, value != null ? value : Strings.EMPTY);
            cookie.setDomain(domain);
            cookie.setPath(path);
            cookie.setSecure(Boolean.TRUE.equals(secure));
            cookie.setHttpOnly(Boolean.TRUE.equals(httpOnly));
            long seconds = Math.max(1L, (expiresAt - now + 999L) / 1000L);
            cookie.setMaxAge(seconds);
            return new StoredCookie(cookie, false, expiresAt);
        }
    }

    @Getter
    public static final class StoredCookie {
        final Cookie cookie;
        final String name;
        final String domain;
        final String path;
        final boolean secure;
        final boolean httpOnly;
        final boolean session;
        final long expiresAt;

        StoredCookie(Cookie cookie) {
            this(cookie, cookie.maxAge() == Long.MIN_VALUE,
                    cookie.maxAge() == Long.MIN_VALUE ? Long.MAX_VALUE : System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(cookie.maxAge()));
        }

        StoredCookie(Cookie cookie, boolean session, long expiresAt) {
            this.cookie = cookie;
            name = cookie.name();
            domain = cookie.domain() != null ? cookie.domain().toLowerCase(Locale.ENGLISH) : Strings.EMPTY;
            path = cookie.path() != null ? cookie.path() : "/";
            secure = cookie.isSecure();
            httpOnly = cookie.isHttpOnly();
            this.session = session;
            this.expiresAt = expiresAt;
        }

        boolean isExpired(long now) {
            return expiresAt <= now;
        }

        boolean matches(URI uri) {
            if (secure && !"https".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }
            String host = uri.getHost().toLowerCase(Locale.ENGLISH);
            if (!host.equals(domain) && !host.endsWith("." + domain)) {
                return false;
            }
            String rawPath = uri.getRawPath();
            if (Strings.isEmpty(rawPath)) {
                rawPath = "/";
            }
            return rawPath.startsWith(path);
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
            return name.equals(that.name) && domain.equals(that.domain) && path.equals(that.path);
        }

        @Override
        public int hashCode() {
            int result = name.hashCode();
            result = 31 * result + domain.hashCode();
            result = 31 * result + path.hashCode();
            return result;
        }
    }
}
