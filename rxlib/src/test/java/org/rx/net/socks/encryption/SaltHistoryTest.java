package org.rx.net.socks.encryption;

import org.junit.jupiter.api.Test;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class SaltHistoryTest {
    @Test
    void onlyFullyAuthenticatedRecordsEnterTheServerHistory() throws Exception {
        for (CipherKind method : CipherKind.values()) {
            for (boolean udp : new boolean[]{false, true}) {
                for (boolean valid : new boolean[]{false, true}) {
                    String cipher = method.getCipherName();
                    SaltHistory history = new SaltHistory(cipher, "fixture");
                    ICrypto sender = ICrypto.get(cipher, "fixture", udp);
                    ICrypto receiver = history.newCrypto(udp);
                    ByteBuf clear = Unpooled.buffer(4).writeInt(42);
                    ByteBuf wire = sender.encrypt(clear);
                    try {
                        byte[] salt = new byte[method == CipherKind.AES_128_GCM ? 16 : 32];
                        wire.getBytes(wire.readerIndex(), salt);
                        if (valid) {
                            ByteBuf decoded = receiver.decrypt(wire);
                            try { assertEquals(42, decoded.readInt()); }
                            finally { decoded.release(); }
                        } else {
                            int last = wire.writerIndex() - 1;
                            wire.setByte(last, wire.getByte(last) ^ 1);
                            assertThrows(io.netty.handler.codec.DecoderException.class, () -> receiver.decrypt(wire));
                        }
                        assertEquals(!valid, history.record(salt, salt.length));
                    } finally {
                        wire.release();
                        clear.release();
                        ((AutoCloseable)sender).close();
                        ((AutoCloseable)receiver).close();
                    }
                }
            }
        }
    }

    @Test
    void claimsAreAtomicScopedBoundedAndExpire() {
        AtomicLong clock = new AtomicLong();
        SaltHistory.Table table = new SaltHistory.Table(16, 100, clock::get);
        String method = CipherKind.AES_256_GCM.getCipherName();
        SaltHistory first = new SaltHistory(method, "one", table);
        SaltHistory second = new SaltHistory(method, "one", table);
        byte[] salt = new byte[32];
        assertEquals(1, IntStream.range(0, 128).parallel().filter(i ->
                (i % 2 == 0 ? first : second).record(salt, salt.length)).count());
        clock.set(99);
        assertFalse(first.record(salt, salt.length));
        clock.set(100);
        assertTrue(second.record(salt, salt.length));
        assertTrue(new SaltHistory(method, "two", table).record(salt, salt.length));
        assertTrue(new SaltHistory("chacha20-ietf-poly1305", "one", table).record(salt, salt.length));
        for (int i = 1; i < 100; i++) {
            salt[0] = (byte)i;
            assertTrue(first.record(salt, salt.length));
        }
        salt[0] = 0;
        assertTrue(first.record(salt, salt.length));
    }

    @Test
    void perDomainFairnessPreventsNoisyUserFloodFromEvictingQuietUsersAndTracksStats() {
        AtomicLong clock = new AtomicLong(1_000L);
        // 16 shards * 8 entries/shard = 128 total entries, TTL = 120s.
        SaltHistory.Table table = new SaltHistory.Table(128, java.util.concurrent.TimeUnit.SECONDS.toNanos(120), clock::get);
        String aes256 = CipherKind.AES_256_GCM.getCipherName();
        String chacha = CipherKind.CHACHA20_IETF_POLY1305.getCipherName();
        SaltHistory quietUserB = new SaltHistory(aes256, "quiet-user-b", table);
        SaltHistory quietUserC = new SaltHistory(chacha, "quiet-user-c", table);
        SaltHistory noisyUserA = new SaltHistory(aes256, "noisy-user-a", table);

        // Quiet users B and C each record 16 authenticated salts across the table at t=1s.
        byte[][] saltsB = new byte[16][32];
        byte[][] saltsC = new byte[16][32];
        for (int i = 0; i < 16; i++) {
            saltsB[i][0] = (byte) (0x10 + i);
            saltsB[i][1] = (byte) (i * 17 + 3);
            saltsC[i][0] = (byte) (0x50 + i);
            saltsC[i][1] = (byte) (i * 29 + 7);
            assertTrue(quietUserB.record(saltsB[i], 32));
            assertTrue(quietUserC.record(saltsC[i], 32));
        }

        // Noisy user A floods 2,000 distinct salts within 5 seconds (>> 128 table capacity).
        clock.addAndGet(java.util.concurrent.TimeUnit.SECONDS.toNanos(5));
        for (int i = 0; i < 2000; i++) {
            byte[] floodSalt = new byte[32];
            floodSalt[0] = (byte) (i & 0xFF);
            floodSalt[1] = (byte) ((i >>> 8) & 0xFF);
            floodSalt[2] = (byte) ((i * 31) & 0xFF);
            assertTrue(noisyUserA.record(floodSalt, 32));
        }

        // Every single unexpired salt of quietUserB and quietUserC MUST still reject replays!
        for (int i = 0; i < 16; i++) {
            assertFalse(quietUserB.record(saltsB[i], 32), "quietUserB salt " + i + " must be protected from noisy flood");
            assertFalse(quietUserC.record(saltsC[i], 32), "quietUserC salt " + i + " must be protected from noisy flood");
        }

        SaltHistory.Stats midStats = table.stats();
        assertEquals(128, midStats.size);
        assertTrue(midStats.capacityEvictions > 1800);
        assertEquals(32, midStats.replayRejections);
        assertEquals(0, midStats.expiredReplacements);

        // Advance past 120s TTL: expired salts are replaced cleanly and counted as expiredReplacements.
        clock.addAndGet(java.util.concurrent.TimeUnit.SECONDS.toNanos(125));
        assertTrue(quietUserB.record(saltsB[0], 32));
        SaltHistory.Stats afterExpiry = table.stats();
        assertTrue(afterExpiry.expiredReplacements >= 1);
    }
}
