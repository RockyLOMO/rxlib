package org.rx.net.socks.encryption;

import lombok.SneakyThrows;
import org.rx.diagnostic.DiagnosticMetrics;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Exact, process-local, bounded FIFO history shared by all classic AEAD server listeners. */
public final class SaltHistory {
    public static final String CAPACITY_PROPERTY = "rx.ss.saltHistory.capacity";
    public static final String TTL_SECONDS_PROPERTY = "rx.ss.saltHistory.ttlSeconds";
    public static final int DEFAULT_CAPACITY = 65536;
    public static final long DEFAULT_TTL_SECONDS = 120L;
    private static final int SHARDS = 16;
    private static final long METRICS_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final Table SHARED = new Table(
            resolveConfiguredCapacity(),
            TimeUnit.SECONDS.toNanos(resolveConfiguredTtlSeconds()),
            System::nanoTime);

    private final CipherKind kind;
    private final ShadowSocksKey masterKey;
    private final DomainKey domainKey;
    private final Table table;

    public SaltHistory(String method, String password) {
        this(method, password, SHARED);
    }

    @SneakyThrows
    SaltHistory(String method, String password, Table table) {
        kind = CipherKind.forName(method);
        masterKey = new ShadowSocksKey(password, kind.keyLength());
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update((byte) kind.ordinal());
        domainKey = new DomainKey(digest.digest(masterKey.keyBytes()));
        this.table = table;
    }

    private static int resolveConfiguredCapacity() {
        int raw = Integer.getInteger(CAPACITY_PROPERTY, DEFAULT_CAPACITY);
        return Math.max(SHARDS, raw);
    }

    private static long resolveConfiguredTtlSeconds() {
        long raw = Long.getLong(TTL_SECONDS_PROPERTY, DEFAULT_TTL_SECONDS);
        return Math.max(1L, raw);
    }

    public static Stats sharedStats() {
        return SHARED.stats();
    }

    Stats stats() {
        return table.stats();
    }

    public ICrypto newCrypto(boolean udp) {
        AeadCrypto crypto = kind.newAead(masterKey);
        crypto.history = this;
        crypto.setForUdp(udp);
        return crypto;
    }

    boolean record(byte[] salt, int length) {
        return table.add(domainKey, salt, length);
    }

    public static final class Stats {
        public final int size;
        public final int capacity;
        public final long lifetimeNanos;
        public final long capacityEvictions;
        public final long expiredReplacements;
        public final long replayRejections;

        Stats(int size, int capacity, long lifetimeNanos, long capacityEvictions,
              long expiredReplacements, long replayRejections) {
            this.size = size;
            this.capacity = capacity;
            this.lifetimeNanos = lifetimeNanos;
            this.capacityEvictions = capacityEvictions;
            this.expiredReplacements = expiredReplacements;
            this.replayRejections = replayRejections;
        }
    }

    static final class Table {
        private final Shard[] shards;
        private final int capacity;
        private final int perShard;
        private final long lifetime;
        private final LongSupplier clock;
        private final AtomicLong lastMetricsNanos = new AtomicLong();
        private final AtomicLong reportedCapacityEvictions = new AtomicLong();
        private final AtomicLong reportedExpiredReplacements = new AtomicLong();
        private final AtomicLong reportedReplayRejections = new AtomicLong();

        Table(int capacity, long lifetime, LongSupplier clock) {
            this.clock = clock;
            this.lifetime = Math.max(1L, lifetime);
            this.perShard = Math.max(1, (Math.max(SHARDS, capacity) + SHARDS - 1) / SHARDS);
            this.capacity = this.perShard * SHARDS;
            shards = new Shard[SHARDS];
            for (int i = 0; i < SHARDS; i++) {
                shards[i] = new Shard(this.perShard, this.lifetime);
            }
        }

        boolean add(DomainKey domainKey, byte[] salt, int length) {
            Entry probe = new Entry(domainKey, salt, length);
            int hash = probe.hash;
            Shard shard = shards[(hash ^ (hash >>> 16)) & (SHARDS - 1)];
            long now = clock.getAsLong();
            boolean accepted = shard.add(probe, now);
            maybeRecordMetrics(now);
            return accepted;
        }

        Stats stats() {
            int totalSize = 0;
            long capEvict = 0;
            long expReplace = 0;
            long replayReject = 0;
            for (Shard shard : shards) {
                synchronized (shard) {
                    totalSize += shard.index.size();
                    capEvict += shard.capacityEvictions;
                    expReplace += shard.expiredReplacements;
                    replayReject += shard.replayRejections;
                }
            }
            return new Stats(totalSize, capacity, lifetime, capEvict, expReplace, replayReject);
        }

        private void maybeRecordMetrics(long now) {
            if (!DiagnosticMetrics.isEnabled()) {
                return;
            }
            long last = lastMetricsNanos.get();
            if (last != 0L && now - last < METRICS_INTERVAL_NANOS) {
                return;
            }
            if (!lastMetricsNanos.compareAndSet(last, now)) {
                return;
            }
            Stats s = stats();
            long prevCap = reportedCapacityEvictions.getAndSet(s.capacityEvictions);
            long prevExp = reportedExpiredReplacements.getAndSet(s.expiredReplacements);
            long prevRep = reportedReplayRejections.getAndSet(s.replayRejections);
            DiagnosticMetrics.record("ss.salt.size", s.size);
            DiagnosticMetrics.record("ss.salt.capacityEvictions", Math.max(0L, s.capacityEvictions - prevCap));
            DiagnosticMetrics.record("ss.salt.expiredReplacements", Math.max(0L, s.expiredReplacements - prevExp));
            DiagnosticMetrics.record("ss.salt.replayRejections", Math.max(0L, s.replayRejections - prevRep));
        }
    }

    private static final class Shard {
        final HashMap<Entry, Entry> index;
        final HashMap<DomainKey, DomainState> domains = new HashMap<>();
        final int perShard;
        final long lifetime;
        Entry shardHead;
        Entry shardTail;
        long capacityEvictions;
        long expiredReplacements;
        long replayRejections;

        Shard(int perShard, long lifetime) {
            this.perShard = perShard;
            this.lifetime = lifetime;
            this.index = new HashMap<>(Math.min(1024, Math.max(16, perShard)));
        }

        synchronized boolean add(Entry entry, long now) {
            Entry existing = index.get(entry);
            if (existing != null) {
                if (now - existing.inserted < lifetime) {
                    replayRejections++;
                    return false;
                }
                expiredReplacements++;
                unlinkShard(existing);
                unlinkDomain(existing.domainState, existing);
                existing.inserted = now;
                linkShardTail(existing);
                linkDomainTail(existing.domainState, existing);
                return true;
            }

            if (index.size() >= perShard) {
                if (shardHead != null && now - shardHead.inserted >= lifetime) {
                    expiredReplacements++;
                    removeEntry(shardHead);
                } else {
                    capacityEvictions++;
                    DomainState incomingDomain = domains.get(entry.domain);
                    int activeDomains = domains.size() + (incomingDomain == null ? 1 : 0);
                    int fairCap = Math.max(1, perShard / Math.max(1, activeDomains));
                    if (incomingDomain != null && incomingDomain.count >= fairCap && incomingDomain.head != null) {
                        removeEntry(incomingDomain.head);
                    } else {
                        DomainState victimDomain = null;
                        for (DomainState ds : domains.values()) {
                            if (ds.count <= 0 || ds.head == null) {
                                continue;
                            }
                            if (victimDomain == null || ds.count > victimDomain.count
                                    || (ds.count == victimDomain.count && ds.head.inserted < victimDomain.head.inserted)) {
                                victimDomain = ds;
                            }
                        }
                        removeEntry(victimDomain != null ? victimDomain.head : shardHead);
                    }
                }
            }

            DomainState ds = domains.get(entry.domain);
            if (ds == null) {
                ds = new DomainState(entry.domain);
                domains.put(entry.domain, ds);
            }
            entry.inserted = now;
            entry.domainState = ds;
            index.put(entry, entry);
            linkShardTail(entry);
            linkDomainTail(ds, entry);
            return true;
        }

        private void removeEntry(Entry target) {
            if (target == null) {
                return;
            }
            index.remove(target);
            unlinkShard(target);
            DomainState ds = target.domainState;
            if (ds != null) {
                unlinkDomain(ds, target);
                if (ds.count <= 0) {
                    domains.remove(ds.key);
                }
                target.domainState = null;
            }
        }

        private void linkShardTail(Entry e) {
            e.shardPrev = shardTail;
            e.shardNext = null;
            if (shardTail != null) {
                shardTail.shardNext = e;
            } else {
                shardHead = e;
            }
            shardTail = e;
        }

        private void unlinkShard(Entry e) {
            if (e.shardPrev != null) {
                e.shardPrev.shardNext = e.shardNext;
            } else {
                shardHead = e.shardNext;
            }
            if (e.shardNext != null) {
                e.shardNext.shardPrev = e.shardPrev;
            } else {
                shardTail = e.shardPrev;
            }
            e.shardPrev = e.shardNext = null;
        }

        private static void linkDomainTail(DomainState ds, Entry e) {
            e.domainPrev = ds.tail;
            e.domainNext = null;
            if (ds.tail != null) {
                ds.tail.domainNext = e;
            } else {
                ds.head = e;
            }
            ds.tail = e;
            ds.count++;
        }

        private static void unlinkDomain(DomainState ds, Entry e) {
            if (e.domainPrev != null) {
                e.domainPrev.domainNext = e.domainNext;
            } else {
                ds.head = e.domainNext;
            }
            if (e.domainNext != null) {
                e.domainNext.domainPrev = e.domainPrev;
            } else {
                ds.tail = e.domainPrev;
            }
            e.domainPrev = e.domainNext = null;
            ds.count--;
        }
    }

    private static final class DomainKey {
        final byte[] bytes;
        final int hash;

        DomainKey(byte[] bytes) {
            this.bytes = bytes;
            this.hash = Arrays.hashCode(bytes);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof DomainKey)) return false;
            DomainKey that = (DomainKey) other;
            return hash == that.hash && Arrays.equals(bytes, that.bytes);
        }
    }

    private static final class DomainState {
        final DomainKey key;
        Entry head;
        Entry tail;
        int count;

        DomainState(DomainKey key) {
            this.key = key;
        }
    }

    private static final class Entry {
        final DomainKey domain;
        final byte[] salt;
        final int hash;
        long inserted;
        DomainState domainState;
        Entry shardPrev;
        Entry shardNext;
        Entry domainPrev;
        Entry domainNext;

        Entry(DomainKey domain, byte[] salt, int length) {
            this.domain = domain;
            this.salt = Arrays.copyOf(salt, length);
            this.hash = 31 * domain.hash + Arrays.hashCode(this.salt);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Entry)) return false;
            Entry that = (Entry) other;
            return domain.equals(that.domain) && Arrays.equals(salt, that.salt);
        }
    }
}
