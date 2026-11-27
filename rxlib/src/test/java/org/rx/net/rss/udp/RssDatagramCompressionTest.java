package org.rx.net.rss.udp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class RssDatagramCompressionTest {
    @Test
    void lostCompressedShardIsRecoveredBeforeDecompression() {
        RssDatagramCompression codec = new RssDatagramCompression();
        int[] seen = {0};
        try (AdaptiveUdpFec tx = new AdaptiveUdpFec(UnpooledByteBufAllocator.DEFAULT, () -> 0);
             AdaptiveUdpFec rx = new AdaptiveUdpFec(UnpooledByteBufAllocator.DEFAULT, () -> 0)) {
            tx.write(Unpooled.EMPTY_BUFFER, ByteBuf::release);
            ByteBuf report = Unpooled.buffer().writeByte(AdaptiveUdpFec.REPORT).writeInt(0).writeByte(4);
            try {
                tx.read(report, ByteBuf::release, ByteBuf::release);
            } finally {
                report.release();
            }
            Consumer<ByteBuf> deliver = block -> {
                ByteBuf data = RssDatagramCompression.decode(block, 1200);
                assertNotNull(data);
                try {
                    assertEquals(1200, data.readableBytes());
                    int bit = 1 << data.getUnsignedByte(3);
                    assertEquals(0, seen[0] & bit);
                    seen[0] |= bit;
                } finally {
                    data.release();
                }
            };
            for (int i = 0; i < 4; i++) {
                ByteBuf original = Unpooled.buffer(1200).writeInt(i).writeZero(1196);
                try {
                    ByteBuf compressed = codec.tryEncode(original, 0);
                    assertNotNull(compressed);
                    try {
                        tx.write(compressed, frame -> {
                            try {
                                int pos = frame.readerIndex();
                                if (frame.getByte(pos) != AdaptiveUdpFec.SHARD || frame.getByte(pos + 5) != 1) {
                                    rx.read(frame, deliver, ByteBuf::release);
                                }
                            } finally {
                                frame.release();
                            }
                        });
                    } finally {
                        compressed.release();
                    }
                } finally {
                    original.release();
                }
            }
            assertEquals(15, seen[0]);
            assertEquals(1, rx.recoveredData);
        }
    }

    @Test
    void incompressibleTrafficBacksOffAndProbesAgain() {
        RssDatagramCompression codec = new RssDatagramCompression();
        byte[] entropy = new byte[1200];
        new Random(42).nextBytes(entropy);
        ByteBuf noisy = Unpooled.wrappedBuffer(entropy).setShort(0, 0);
        ByteBuf text = Unpooled.buffer(1200).writeZero(1200);
        try {
            for (int i = 0; i < 8; i++) {
                assertNull(codec.tryEncode(noisy, i));
            }
            assertNull(codec.tryEncode(text, TimeUnit.SECONDS.toNanos(29)));
            assertSame(noisy, RssDatagramCompression.decode(noisy.retain(), 1200));
            noisy.release();
            ByteBuf block = codec.tryEncode(text, TimeUnit.SECONDS.toNanos(31));
            assertNotNull(block);
            assertTrue(block.readableBytes() < 100);
            ByteBuf roundTrip = RssDatagramCompression.decode(block, 1200);
            assertEquals(0, block.refCnt());
            try {
                assertEquals(text, roundTrip);
            } finally {
                roundTrip.release();
            }
        } finally {
            noisy.release();
            text.release();
        }
    }

    @Test
    void corruptAndOversizedBlocksAreDroppedAndReleased() {
        ByteBuf input = Unpooled.buffer(1000).writeZero(1000);
        try {
            ByteBuf block = new RssDatagramCompression().tryEncode(input, 0);
            assertNotNull(block);
            assertNull(RssDatagramCompression.decode(block, 999));
            assertEquals(0, block.refCnt());
            ByteBuf truncated = Unpooled.buffer().writeShort(1000).writeByte(255);
            assertNull(RssDatagramCompression.decode(truncated, 1000));
            assertEquals(0, truncated.refCnt());
        } finally {
            input.release();
        }
    }
}
