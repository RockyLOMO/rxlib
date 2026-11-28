package org.rx.net.socks;

import org.rx.diagnostic.DiagnosticMetrics;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Bounded, zero-thread, single-flight cache for {@link SocksRpcCapabilities} bound to a specific
 * {@link SocksRpcContract} peer instance and credential token hash.
 * <ul>
 *     <li>Coalesces concurrent first-time {@code capabilities()} RPCs into a single round-trip.</li>
 *     <li>Caches supported capabilities for a bounded TTL and explicit unsupported responses for a short TTL.</li>
 *     <li>Never caches transient RPC/transport exceptions.</li>
 *     <li>Supports immediate invalidation on disconnect, hot reload, or capability mismatch.</li>
 * </ul>
 */
public final class SocksRpcCapabilitiesCache {
    /**
     * Local non-RPC hint implemented by managed RPC facades to indicate whether the control plane
     * is known to be unreachable so plain AUTO UDP can fall back immediately without blocking.
     */
    public interface ControlReadiness {
        boolean isRpcControlReady();
    }

    public static final long DEFAULT_POSITIVE_TTL_MILLIS = 60_000L;
    public static final long DEFAULT_NEGATIVE_TTL_MILLIS = 5_000L;
    private static final int MAX_ENTRIES_PER_FACADE = 16;
    private static final int MAX_SHARED_FACADES = 256;

    private static final Map<SocksRpcContract, SocksRpcCapabilitiesCache> SHARED =
            Collections.synchronizedMap(new WeakHashMap<SocksRpcContract, SocksRpcCapabilitiesCache>());

    private final long positiveTtlNanos;
    private final long negativeTtlNanos;
    private final Map<String, Entry> entries = new LinkedHashMap<String, Entry>(8, 0.75f, true);
    private final ConcurrentHashMap<String, CompletableFuture<SocksRpcCapabilities>> inFlight = new ConcurrentHashMap<>();

    public SocksRpcCapabilitiesCache() {
        this(DEFAULT_POSITIVE_TTL_MILLIS, DEFAULT_NEGATIVE_TTL_MILLIS);
    }

    public SocksRpcCapabilitiesCache(long positiveTtlMillis, long negativeTtlMillis) {
        this.positiveTtlNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1000L, positiveTtlMillis));
        this.negativeTtlNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(500L, negativeTtlMillis));
    }

    public static SocksRpcCapabilitiesCache forFacade(SocksRpcContract facade) {
        if (facade == null) {
            return null;
        }
        synchronized (SHARED) {
            SocksRpcCapabilitiesCache cache = SHARED.get(facade);
            if (cache == null) {
                if (SHARED.size() >= MAX_SHARED_FACADES) {
                    SHARED.clear();
                }
                cache = new SocksRpcCapabilitiesCache();
                SHARED.put(facade, cache);
            }
            return cache;
        }
    }

    public static void invalidateFacade(SocksRpcContract facade) {
        if (facade == null) {
            return;
        }
        SocksRpcCapabilitiesCache cache;
        synchronized (SHARED) {
            cache = SHARED.get(facade);
        }
        if (cache != null) {
            cache.invalidateAll();
        }
    }

    public SocksRpcCapabilities get(String token, Supplier<SocksRpcCapabilities> loader) {
        String key = tokenHashKey(token);
        long now = System.nanoTime();
        SocksRpcCapabilities cached = lookup(key, now);
        if (cached != null) {
            if (DiagnosticMetrics.isEnabled()) {
                DiagnosticMetrics.record("socks.udp.rpc.capabilities.cache.count", 1D, "result=hit");
            }
            return cached;
        }

        CompletableFuture<SocksRpcCapabilities> created = new CompletableFuture<>();
        CompletableFuture<SocksRpcCapabilities> existing = inFlight.putIfAbsent(key, created);
        if (existing != null) {
            if (DiagnosticMetrics.isEnabled()) {
                DiagnosticMetrics.record("socks.udp.rpc.capabilities.cache.count", 1D, "result=coalesced");
            }
            return awaitInFlight(existing);
        }

        if (DiagnosticMetrics.isEnabled()) {
            DiagnosticMetrics.record("socks.udp.rpc.capabilities.cache.count", 1D, "result=miss");
        }
        try {
            // Re-check cache after winning inFlight slot in case another caller populated & cleared inFlight just before us.
            SocksRpcCapabilities cachedAgain = lookup(key, System.nanoTime());
            if (cachedAgain != null) {
                created.complete(cachedAgain);
                return cachedAgain;
            }
            SocksRpcCapabilities loaded = loader.get();
            SocksRpcCapabilities effective = loaded == null ? SocksRpcCapabilities.EMPTY : loaded;
            boolean supported = effective.getFlags() != 0;
            long ttlNanos = supported ? positiveTtlNanos : negativeTtlNanos;
            store(key, effective, System.nanoTime() + ttlNanos);
            created.complete(effective);
            return effective;
        } catch (Throwable e) {
            // Never cache transient network or auth errors.
            created.completeExceptionally(e);
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new IllegalStateException(e);
        } finally {
            inFlight.remove(key, created);
        }
    }

    public void invalidate(String token) {
        String key = tokenHashKey(token);
        synchronized (entries) {
            entries.remove(key);
        }
        if (DiagnosticMetrics.isEnabled()) {
            DiagnosticMetrics.record("socks.udp.rpc.capabilities.cache.count", 1D, "result=invalidate");
        }
    }

    public void invalidateAll() {
        synchronized (entries) {
            entries.clear();
        }
        if (DiagnosticMetrics.isEnabled()) {
            DiagnosticMetrics.record("socks.udp.rpc.capabilities.cache.count", 1D, "result=invalidate-all");
        }
    }

    private SocksRpcCapabilities lookup(String key, long nowNanos) {
        synchronized (entries) {
            Entry e = entries.get(key);
            if (e == null) {
                return null;
            }
            if (nowNanos - e.expireAtNanos >= 0L) {
                entries.remove(key);
                return null;
            }
            return e.value;
        }
    }

    private void store(String key, SocksRpcCapabilities value, long expireAtNanos) {
        synchronized (entries) {
            while (entries.size() >= MAX_ENTRIES_PER_FACADE) {
                java.util.Iterator<String> it = entries.keySet().iterator();
                if (it.hasNext()) {
                    it.next();
                    it.remove();
                } else {
                    break;
                }
            }
            entries.put(key, new Entry(value, expireAtNanos));
        }
    }

    private static SocksRpcCapabilities awaitInFlight(CompletableFuture<SocksRpcCapabilities> future) {
        try {
            return future.get();
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IllegalStateException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for capabilities RPC", e);
        }
    }

    private static String tokenHashKey(String token) {
        if (token == null || token.isEmpty()) {
            return "";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            return Integer.toHexString(token.hashCode());
        }
    }

    private static final class Entry {
        final SocksRpcCapabilities value;
        final long expireAtNanos;

        Entry(SocksRpcCapabilities value, long expireAtNanos) {
            this.value = value;
            this.expireAtNanos = expireAtNanos;
        }
    }
}
