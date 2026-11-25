package org.rx.net.socks.encryption;

import lombok.SneakyThrows;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Exact, process-local, bounded FIFO history shared by all classic AEAD server listeners. */
public final class SaltHistory {
    private static final int SHARDS = 16;
    private static final Table SHARED = new Table(65536, TimeUnit.MINUTES.toNanos(2), System::nanoTime);
    private final CipherKind kind;
    private final String password;
    private final byte[] domain;
    private final int domainHash;
    private final Table table;

    public SaltHistory(String method, String password) {
        this(method, password, SHARED);
    }

    @SneakyThrows
    SaltHistory(String method, String password, Table table) {
        kind = CipherKind.forName(method);
        this.password = password;
        byte[] key = new ShadowSocksKey(password, kind == CipherKind.AES_128_GCM ? 16 : 32).getEncoded();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((byte) kind.ordinal());
            domain = digest.digest(key);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
        domainHash = Arrays.hashCode(domain);
        this.table = table;
    }

    public ICrypto newCrypto(boolean udp) {
        AeadCrypto crypto = kind.newAead(password);
        crypto.history = this;
        crypto.setForUdp(udp);
        return crypto;
    }

    boolean record(byte[] salt, int length) {
        return table.add(new Entry(domain, domainHash, salt, length));
    }

    static final class Table {
        private final LinkedHashMap<Entry, Entry>[] maps;
        private final int perShard;
        private final long lifetime;
        private final LongSupplier clock;

        @SuppressWarnings("unchecked")
        Table(int capacity, long lifetime, LongSupplier clock) {
            this.clock = clock;
            this.lifetime = lifetime;
            perShard = capacity / SHARDS;
            maps = new LinkedHashMap[SHARDS];
            for (int i = 0; i < SHARDS; i++) maps[i] = new LinkedHashMap<>();
        }

        boolean add(Entry key) {
            int hash = key.hash;
            LinkedHashMap<Entry, Entry> map = maps[(hash ^ (hash >>> 16)) & (SHARDS - 1)];
            synchronized (map) {
                long now = clock.getAsLong();
                Entry existing = map.get(key);
                if (existing != null) {
                    if (now - existing.inserted < lifetime) return false;
                    map.remove(existing);
                } else if (map.size() == perShard) {
                    map.remove(map.keySet().iterator().next());
                }
                key.inserted = now;
                map.put(key, key);
                return true;
            }
        }
    }

    private static final class Entry {
        final byte[] domain, salt;
        final int hash;
        long inserted;

        Entry(byte[] domain, int domainHash, byte[] salt, int length) {
            this.domain = domain;
            this.salt = Arrays.copyOf(salt, length);
            hash = 31 * domainHash + Arrays.hashCode(this.salt);
        }

        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Entry)) return false;
            Entry that = (Entry) other;
            return Arrays.equals(domain, that.domain) && Arrays.equals(salt, that.salt);
        }
    }
}
