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
}
