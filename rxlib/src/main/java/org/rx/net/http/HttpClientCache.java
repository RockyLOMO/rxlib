package org.rx.net.http;

import com.alibaba.fastjson2.JSON;
import io.netty.handler.codec.DateFormatter;
import io.netty.handler.codec.http.*;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;
import org.rx.codec.CodecUtil;
import org.rx.core.Constants;
import org.rx.core.Strings;
import org.rx.io.Files;
import org.rx.io.HybridStream;

import java.io.File;
import java.io.Serializable;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Getter
@Accessors(chain = true)
/**
 * HTTP 磁盘缓存。
 * 当前缓存命中后会把缓存文件包装为 {@link HybridStream} 返回，后续发送/读取路径仍是
 * FileChannel -> ByteBuf -> Channel(OutputStream)，不是 Netty {@code FileRegion}/{@code sendfile}
 * 这种内核零拷贝；也就是“程序读文件再写出”，只是避免了额外 byte[] 中转。
 * 若后续需要真正零拷贝，只能在明文 TCP 场景单独走 FileRegion，TLS/HTTPS 仍需经过用户态加密链路。
 */
public final class HttpClientCache {
    static final String DEFAULT_DIR_NAME = "rx-http-cache";
    private static final String META_FILE_NAME = "meta.json";
    private static final String BODY_FILE_NAME = "body.bin";
    private static final Set<String> STATIC_FILE_EXTENSIONS = new HashSet<>(Arrays.asList(
            "js", "css", "png", "jpg", "jpeg", "gif", "svg", "ico", "woff", "woff2", "ttf", "eot",
            "mp3", "mp4", "m4a", "webm", "wav", "pdf", "zip", "rar", "7z", "tar", "gz", "bz2",
            "wasm", "map", "txt", "xml"));

    private File directory;
    private long maxBytes;
    private final AtomicBoolean trimming = new AtomicBoolean();
    private final ConcurrentHashMap<String, AtomicInteger> inUseEntries = new ConcurrentHashMap<>();

    public HttpClientCache() {
        this(new File(".", DEFAULT_DIR_NAME), 512L * Constants.MB);
    }

    public HttpClientCache(File directory, long maxBytes) {
        setDirectory(directory);
        setMaxBytes(maxBytes);
    }

    public HttpClientCache(HttpClientCache source) {
        this(source != null ? source.directory : null, source != null ? source.maxBytes : 0L);
    }

    public HttpClientCache setDirectory(String directory) {
        return setDirectory(Strings.isEmpty(directory) ? null : new File(directory));
    }

    public HttpClientCache setDirectory(File directory) {
        this.directory = (directory != null ? directory : new File(".", DEFAULT_DIR_NAME)).getAbsoluteFile();
        return this;
    }

    public HttpClientCache setMaxBytes(long maxBytes) {
        this.maxBytes = Math.max(0L, maxBytes);
        return this;
    }

    CacheEntry get(String url) {
        if (Strings.isEmpty(url)) {
            return null;
        }
        File entryDir = entryDirectory(url);
        File metaFile = new File(entryDir, META_FILE_NAME);
        File bodyFile = new File(entryDir, BODY_FILE_NAME);
        if (!metaFile.isFile() || !bodyFile.isFile()) {
            deleteEntry(entryDir);
            return null;
        }
        EntryMetadata metadata = readMetadata(metaFile);
        if (metadata == null || !Strings.equals(metadata.url, url)) {
            deleteEntry(entryDir);
            return null;
        }
        touch(entryDir, metaFile, bodyFile);
        return new CacheEntry(url, entryKey(url), entryDir, metaFile, bodyFile, metadata);
    }

    void applyValidators(@NonNull HttpHeaders headers, CacheEntry entry) {
        if (entry == null || entry.metadata == null) {
            return;
        }
        if (!Strings.isEmpty(entry.metadata.etag)) {
            headers.set(HttpHeaderNames.IF_NONE_MATCH, entry.metadata.etag);
        }
        if (!Strings.isEmpty(entry.metadata.lastModified)) {
            headers.set(HttpHeaderNames.IF_MODIFIED_SINCE, entry.metadata.lastModified);
        }
    }

    boolean storeable(String url, HttpResponse response) {
        if (response == null || response.status().code() != HttpResponseStatus.OK.code()) {
            return false;
        }
        HttpHeaders headers = response.headers();
        if (headers == null || headers.contains(HttpHeaderNames.SET_COOKIE)) {
            return false;
        }
        if (headers.contains(HttpHeaderNames.CONTENT_RANGE)) {
            return false;
        }
        String cacheControl = headers.get(HttpHeaderNames.CACHE_CONTROL);
        if (hasDirective(cacheControl, "no-store") || hasDirective(cacheControl, "private")) {
            return false;
        }
        return hasExplicitCaching(headers) || isStaticAsset(url, headers.get(HttpHeaderNames.CONTENT_TYPE));
    }

    void store(String url, HttpResponse response, HybridStream body) {
        if (Strings.isEmpty(url) || response == null || body == null) {
            return;
        }
        File root = ensureDirectory();
        String key = entryKey(url);
        File entryDir = new File(root, key);
        File tempDir = new File(root, key + ".tmp-" + Long.toHexString(ThreadLocalRandom.current().nextLong()));
        try {
            java.nio.file.Files.createDirectories(tempDir.toPath());
            File bodyFile = new File(tempDir, BODY_FILE_NAME);
            java.nio.file.Files.copy(body.rewind().asInputStream(), bodyFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

            EntryMetadata metadata = buildMetadata(url, response, bodyFile.length());
            File metaFile = new File(tempDir, META_FILE_NAME);
            java.nio.file.Files.write(metaFile.toPath(), JSON.toJSONBytes(metadata), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            touch(tempDir, metaFile, bodyFile);

            if (entryDir.exists() && !deleteEntry(entryDir)) {
                deleteEntry(tempDir);
                return;
            }
            if (entryDir.exists()) {
                deleteEntry(entryDir);
            }
            Files.move(tempDir, entryDir);
            touch(entryDir, new File(entryDir, META_FILE_NAME), new File(entryDir, BODY_FILE_NAME));
            trim(entryDir);
        } catch (Throwable e) {
            deleteEntry(tempDir);
        }
    }

    HttpClient.Response createCachedResponse(HttpClient owner, HttpClient.Request request, String responseUrl, CacheEntry entry, HttpResponse networkResponse, long elapsedNanos) {
        if (owner == null || request == null || entry == null || !entry.bodyFile.isFile()) {
            return null;
        }
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(entry.metadata.statusCode));
        HttpHeaders headers = response.headers();
        restoreHeaders(headers, entry.metadata.headers);
        if (networkResponse != null) {
            mergeValidationHeaders(entry.metadata.headers, networkResponse.headers());
            restoreHeaders(headers, entry.metadata.headers);
        }
        headers.set(HttpHeaderNames.CONTENT_LENGTH, entry.bodyFile.length());
        HybridStream cachedBody = new HybridStream(HybridStream.NON_MEMORY_SIZE, false, entry.bodyFile.getAbsolutePath());
        retain(entry.key);
        return new CachedResponse(owner, request, responseUrl, response, cachedBody, elapsedNanos, this, entry.key);
    }

    void revalidate(CacheEntry entry, HttpResponse response) {
        if (entry == null || response == null || !entry.metaFile.isFile()) {
            return;
        }
        try {
            EntryMetadata metadata = entry.metadata;
            mergeValidationHeaders(metadata.headers, response.headers());
            metadata.storedAt = System.currentTimeMillis();
            metadata.etag = firstHeader(metadata.headers, HttpHeaderNames.ETAG);
            metadata.lastModified = firstHeader(metadata.headers, HttpHeaderNames.LAST_MODIFIED);
            metadata.expiresAt = resolveExpiresAt(metadata.headers, metadata.storedAt);
            java.nio.file.Files.write(entry.metaFile.toPath(), JSON.toJSONBytes(metadata), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            touch(entry.entryDir, entry.metaFile, entry.bodyFile);
        } catch (Throwable e) {
            deleteEntry(entry.entryDir);
        }
    }

    long totalBytes() {
        return fileTreeBytes(ensureDirectory());
    }

    private File ensureDirectory() {
        File dir = directory != null ? directory : new File(".", DEFAULT_DIR_NAME);
        try {
            java.nio.file.Files.createDirectories(dir.toPath());
        } catch (Throwable e) {
            throw new IllegalStateException("create cache directory failed: " + dir, e);
        }
        return dir;
    }

    private EntryMetadata buildMetadata(String url, HttpResponse response, long bodyLength) {
        HttpHeaders responseHeaders = response.headers();
        long now = System.currentTimeMillis();
        EntryMetadata metadata = new EntryMetadata();
        metadata.url = url;
        metadata.statusCode = response.status().code();
        metadata.storedAt = now;
        metadata.expiresAt = resolveExpiresAt(responseHeaders, now);
        metadata.etag = responseHeaders.get(HttpHeaderNames.ETAG);
        metadata.lastModified = responseHeaders.get(HttpHeaderNames.LAST_MODIFIED);
        metadata.bodyLength = Math.max(0L, bodyLength);
        metadata.headers = snapshotHeaders(responseHeaders, metadata.bodyLength);
        return metadata;
    }

    private long resolveExpiresAt(HttpHeaders headers, long now) {
        if (headers == null || headers.isEmpty()) {
            return 0L;
        }
        String cacheControl = headers.get(HttpHeaderNames.CACHE_CONTROL);
        if (hasDirective(cacheControl, "no-cache")) {
            return 0L;
        }
        Long maxAge = maxAgeSeconds(cacheControl);
        if (maxAge != null) {
            return maxAge <= 0L ? 0L : now + maxAge * 1000L;
        }
        String expires = headers.get(HttpHeaderNames.EXPIRES);
        if (!Strings.isEmpty(expires)) {
            Date time = DateFormatter.parseHttpDate(expires);
            return time == null ? 0L : time.getTime();
        }
        return 0L;
    }

    private long resolveExpiresAt(Map<String, List<String>> headers, long now) {
        if (headers == null || headers.isEmpty()) {
            return 0L;
        }
        String cacheControl = firstHeader(headers, HttpHeaderNames.CACHE_CONTROL);
        if (hasDirective(cacheControl, "no-cache")) {
            return 0L;
        }
        Long maxAge = maxAgeSeconds(cacheControl);
        if (maxAge != null) {
            return maxAge <= 0L ? 0L : now + maxAge * 1000L;
        }
        String expires = firstHeader(headers, HttpHeaderNames.EXPIRES);
        if (!Strings.isEmpty(expires)) {
            Date time = DateFormatter.parseHttpDate(expires);
            return time == null ? 0L : time.getTime();
        }
        return 0L;
    }

    private boolean hasExplicitCaching(HttpHeaders headers) {
        if (headers == null || headers.isEmpty()) {
            return false;
        }
        String cacheControl = headers.get(HttpHeaderNames.CACHE_CONTROL);
        if (maxAgeSeconds(cacheControl) != null) {
            return true;
        }
        return !Strings.isEmpty(headers.get(HttpHeaderNames.EXPIRES))
                || !Strings.isEmpty(headers.get(HttpHeaderNames.ETAG))
                || !Strings.isEmpty(headers.get(HttpHeaderNames.LAST_MODIFIED));
    }

    private static boolean isStaticAsset(String url, String contentType) {
        if (!Strings.isEmpty(contentType)) {
            String lower = contentType.toLowerCase(Locale.ROOT);
            if (lower.startsWith("image/")
                    || lower.startsWith("audio/")
                    || lower.startsWith("video/")
                    || lower.startsWith("font/")
                    || lower.startsWith("application/octet-stream")
                    || lower.startsWith("application/pdf")
                    || lower.startsWith("application/javascript")
                    || lower.startsWith("text/css")) {
                return true;
            }
        }
        String path = url;
        int queryIndex = path.indexOf('?');
        if (queryIndex >= 0) {
            path = path.substring(0, queryIndex);
        }
        String ext = org.rx.io.Files.getExtension(path);
        return !Strings.isEmpty(ext) && STATIC_FILE_EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT));
    }

    private static Map<String, List<String>> snapshotHeaders(HttpHeaders headers, long bodyLength) {
        Map<String, List<String>> snapshot = new LinkedHashMap<>();
        if (headers != null && !headers.isEmpty()) {
            for (Map.Entry<String, String> entry : headers) {
                CharSequence name = entry.getKey();
                if (HttpHeaderNames.CONNECTION.contentEqualsIgnoreCase(name)
                        || HttpHeaderNames.KEEP_ALIVE.contentEqualsIgnoreCase(name)
                        || HttpHeaderNames.PROXY_AUTHENTICATE.contentEqualsIgnoreCase(name)
                        || HttpHeaderNames.PROXY_AUTHORIZATION.contentEqualsIgnoreCase(name)
                        || HttpHeaderNames.TE.contentEqualsIgnoreCase(name)
                        || HttpHeaderNames.TRAILER.contentEqualsIgnoreCase(name)
                        || HttpHeaderNames.TRANSFER_ENCODING.contentEqualsIgnoreCase(name)
                        || HttpHeaderNames.UPGRADE.contentEqualsIgnoreCase(name)
                        || HttpHeaderNames.SET_COOKIE.contentEqualsIgnoreCase(name)) {
                    continue;
                }
                putHeader(snapshot, entry.getKey(), entry.getValue(), false);
            }
        }
        putHeader(snapshot, HttpHeaderNames.CONTENT_LENGTH.toString(), String.valueOf(bodyLength), true);
        return snapshot;
    }

    private static void restoreHeaders(HttpHeaders target, Map<String, List<String>> source) {
        if (target == null || source == null || source.isEmpty()) {
            return;
        }
        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            List<String> values = entry.getValue();
            if (values == null || values.isEmpty()) {
                continue;
            }
            target.set(entry.getKey(), values);
        }
    }

    private static void mergeValidationHeaders(Map<String, List<String>> stored, HttpHeaders updated) {
        if (stored == null || updated == null || updated.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : updated) {
            CharSequence name = entry.getKey();
            if (HttpHeaderNames.CONNECTION.contentEqualsIgnoreCase(name)
                    || HttpHeaderNames.KEEP_ALIVE.contentEqualsIgnoreCase(name)
                    || HttpHeaderNames.PROXY_AUTHENTICATE.contentEqualsIgnoreCase(name)
                    || HttpHeaderNames.PROXY_AUTHORIZATION.contentEqualsIgnoreCase(name)
                    || HttpHeaderNames.TE.contentEqualsIgnoreCase(name)
                    || HttpHeaderNames.TRAILER.contentEqualsIgnoreCase(name)
                    || HttpHeaderNames.TRANSFER_ENCODING.contentEqualsIgnoreCase(name)
                    || HttpHeaderNames.UPGRADE.contentEqualsIgnoreCase(name)
                    || HttpHeaderNames.CONTENT_LENGTH.contentEqualsIgnoreCase(name)
                    || HttpHeaderNames.SET_COOKIE.contentEqualsIgnoreCase(name)) {
                continue;
            }
            putHeader(stored, entry.getKey(), entry.getValue(), true);
        }
    }

    private static void putHeader(Map<String, List<String>> headers, String name, String value, boolean replace) {
        if (headers == null || Strings.isEmpty(name) || value == null) {
            return;
        }
        if (replace) {
            List<String> values = new ArrayList<>(1);
            values.add(value);
            headers.put(name, values);
            return;
        }
        List<String> values = headers.get(name);
        if (values == null) {
            values = new ArrayList<>(1);
            headers.put(name, values);
        }
        values.add(value);
    }

    private static String firstHeader(Map<String, List<String>> headers, CharSequence name) {
        if (headers == null || name == null) {
            return null;
        }
        List<String> values = headers.get(name.toString());
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static boolean hasDirective(String cacheControl, String directive) {
        if (Strings.isEmpty(cacheControl) || Strings.isEmpty(directive)) {
            return false;
        }
        String[] tokens = cacheControl.split(",");
        for (String token : tokens) {
            String value = token.trim();
            if (Strings.equalsIgnoreCase(value, directive) || Strings.startsWithIgnoreCase(value, directive + "=")) {
                return true;
            }
        }
        return false;
    }

    private static Long maxAgeSeconds(String cacheControl) {
        if (Strings.isEmpty(cacheControl)) {
            return null;
        }
        String[] tokens = cacheControl.split(",");
        for (String token : tokens) {
            String value = token.trim();
            if (!Strings.startsWithIgnoreCase(value, "max-age=")) {
                continue;
            }
            try {
                return Long.parseLong(value.substring("max-age=".length()).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static EntryMetadata readMetadata(File metaFile) {
        try {
            return JSON.parseObject(java.nio.file.Files.readAllBytes(metaFile.toPath()), EntryMetadata.class);
        } catch (Throwable e) {
            return null;
        }
    }

    private String entryKey(String url) {
        return CodecUtil.hexMd5(url);
    }

    private File entryDirectory(String url) {
        return new File(ensureDirectory(), entryKey(url));
    }

    private void retain(String key) {
        inUseEntries.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    }

    private void release(String key) {
        AtomicInteger counter = inUseEntries.get(key);
        if (counter == null) {
            return;
        }
        if (counter.decrementAndGet() <= 0) {
            inUseEntries.remove(key, counter);
        }
    }

    private void trim(File protectedDir) {
        long limit = maxBytes;
        if (limit <= 0L || !trimming.compareAndSet(false, true)) {
            return;
        }
        try {
            File root = ensureDirectory();
            File[] children = root.listFiles();
            if (children == null || children.length == 0) {
                return;
            }
            long used = fileTreeBytes(root);
            if (used <= limit) {
                return;
            }
            Arrays.sort(children, new Comparator<File>() {
                @Override
                public int compare(File left, File right) {
                    return Long.compare(left.lastModified(), right.lastModified());
                }
            });
            File protectedCanonical = canonicalFile(protectedDir);
            for (File child : children) {
                if (used <= limit) {
                    break;
                }
                File canonical = canonicalFile(child);
                if (canonical == null || !canonical.isDirectory() || sameFile(canonical, protectedCanonical)) {
                    continue;
                }
                if (inUseEntries.containsKey(canonical.getName())) {
                    continue;
                }
                long childBytes = fileTreeBytes(canonical);
                if (deleteEntry(canonical)) {
                    used -= childBytes;
                }
            }
        } finally {
            trimming.set(false);
        }
    }

    private static File canonicalFile(File file) {
        if (file == null) {
            return null;
        }
        try {
            return file.getCanonicalFile();
        } catch (Throwable e) {
            return null;
        }
    }

    private static boolean sameFile(File left, File right) {
        if (left == null || right == null) {
            return false;
        }
        return Strings.equals(left.getAbsolutePath(), right.getAbsolutePath());
    }

    private static long fileTreeBytes(File file) {
        if (file == null || !file.exists()) {
            return 0L;
        }
        if (file.isFile()) {
            return Math.max(0L, file.length());
        }
        File[] children = file.listFiles();
        if (children == null) {
            return 0L;
        }
        long total = 0L;
        for (File child : children) {
            total += fileTreeBytes(child);
        }
        return total;
    }

    private static boolean deleteEntry(File dir) {
        if (dir == null || !dir.exists()) {
            return true;
        }
        try {
            Files.delete(dir.getAbsolutePath());
            return !dir.exists();
        } catch (Throwable e) {
            return false;
        }
    }

    private static void touch(File... files) {
        long now = System.currentTimeMillis();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file != null && file.exists()) {
                file.setLastModified(now);
            }
        }
    }

    static final class CacheEntry {
        final String url;
        final String key;
        final File entryDir;
        final File metaFile;
        final File bodyFile;
        final EntryMetadata metadata;

        CacheEntry(String url, String key, File entryDir, File metaFile, File bodyFile, EntryMetadata metadata) {
            this.url = url;
            this.key = key;
            this.entryDir = entryDir;
            this.metaFile = metaFile;
            this.bodyFile = bodyFile;
            this.metadata = metadata;
        }

        boolean isFresh(long now) {
            return metadata != null && metadata.expiresAt > now && bodyFile.isFile();
        }

        boolean canRevalidate() {
            return metadata != null && (!Strings.isEmpty(metadata.etag) || !Strings.isEmpty(metadata.lastModified));
        }
    }

    static final class EntryMetadata implements Serializable {
        private static final long serialVersionUID = 7746714842209740319L;

        public String url;
        public int statusCode;
        public long storedAt;
        public long expiresAt;
        public long bodyLength;
        public String etag;
        public String lastModified;
        public Map<String, List<String>> headers;
    }

    static final class CachedResponse extends HttpClient.Response {
        private final HttpClientCache cache;
        private final String cacheKey;

        CachedResponse(HttpClient owner, HttpClient.Request request, String url, HttpResponse response, HybridStream body, long elapsedNanos,
                       HttpClientCache cache, String cacheKey) {
            super(owner, request, url, response, body, elapsedNanos);
            this.cache = cache;
            this.cacheKey = cacheKey;
        }

        @Override
        public void close() {
            try {
                super.close();
            } finally {
                if (cache != null) {
                    cache.release(cacheKey);
                }
            }
        }
    }
}
