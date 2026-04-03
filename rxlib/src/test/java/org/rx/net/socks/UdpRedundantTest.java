package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.DatagramPacket;
import org.junit.jupiter.api.Test;
import org.rx.net.Sockets;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class UdpRedundantTest {
    private static final InetSocketAddress LOCAL = Sockets.newAnyEndpoint(0);
    private static final InetSocketAddress REMOTE = new InetSocketAddress("127.0.0.1", 12345);

    // ===================== Encoder 测试 =====================

    @Test
    public void testEncoder() {
        int multiplier = 3;
        UdpRedundantEncoder encoder = new UdpRedundantEncoder(multiplier, 0);
        EmbeddedChannel channel = new EmbeddedChannel(encoder);

        ByteBuf data = Unpooled.copiedBuffer("hello".getBytes());
        channel.writeOutbound(new DatagramPacket(data, REMOTE));

        for (int i = 0; i < multiplier; i++) {
            DatagramPacket out = channel.readOutbound();
            assertNotNull(out, "copy " + i + " should exist");
            ByteBuf content = out.content();
            assertEquals(UdpRedundantEncoder.HEADER_MAGIC, content.readInt());
            content.readInt(); // seqId
            assertEquals("hello", content.readCharSequence(content.readableBytes(), StandardCharsets.UTF_8).toString());
            out.release();
        }
        assertNull(channel.readOutbound());
    }

    // ===================== Decoder 测试 =====================

    @Test
    public void testDecoder() {
        UdpRedundantDecoder decoder = new UdpRedundantDecoder();
        EmbeddedChannel channel = new EmbeddedChannel(decoder);

        int seqId = 100;
        for (int i = 0; i < 3; i++) {
            ByteBuf buf = Unpooled.buffer();
            buf.writeInt(UdpRedundantEncoder.HEADER_MAGIC);
            buf.writeInt(seqId);
            buf.writeBytes("hello".getBytes());
            channel.writeInbound(new DatagramPacket(buf, REMOTE, LOCAL));
        }

        DatagramPacket in = channel.readInbound();
        assertNotNull(in);
        assertEquals("hello", in.content().toString(StandardCharsets.UTF_8));
        in.release();
        assertNull(channel.readInbound());
    }

    @Test
    public void testDecoderSlidingWindow() {
        UdpRedundantDecoder decoder = new UdpRedundantDecoder();
        EmbeddedChannel channel = new EmbeddedChannel(decoder);

        // Send 100, 101, 102
        for (int seqId = 100; seqId <= 102; seqId++) {
            ByteBuf buf = Unpooled.buffer();
            buf.writeInt(UdpRedundantEncoder.HEADER_MAGIC);
            buf.writeInt(seqId);
            buf.writeBytes("packet".getBytes());
            channel.writeInbound(new DatagramPacket(buf, REMOTE, LOCAL));

            DatagramPacket out = channel.readInbound();
            assertNotNull(out);
            out.release();
        }

        // Send 100 again (in-window duplicate → discard)
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(UdpRedundantEncoder.HEADER_MAGIC);
        buf.writeInt(100);
        buf.writeBytes("packet".getBytes());
        channel.writeInbound(new DatagramPacket(buf, REMOTE, LOCAL));
        assertNull(channel.readInbound());

        // Send 164 (advances window beyond 100)
        buf = Unpooled.buffer();
        buf.writeInt(UdpRedundantEncoder.HEADER_MAGIC);
        buf.writeInt(164);
        buf.writeBytes("packet".getBytes());
        channel.writeInbound(new DatagramPacket(buf, REMOTE, LOCAL));
        DatagramPacket p = channel.readInbound();
        assertNotNull(p);
        p.release();

        // Now 100 is out of window → discard
        buf = Unpooled.buffer();
        buf.writeInt(UdpRedundantEncoder.HEADER_MAGIC);
        buf.writeInt(100);
        buf.writeBytes("packet".getBytes());
        channel.writeInbound(new DatagramPacket(buf, REMOTE, LOCAL));
        assertNull(channel.readInbound());
    }

    @Test
    public void testNonMagicPassthrough() {
        UdpRedundantDecoder decoder = new UdpRedundantDecoder();
        EmbeddedChannel channel = new EmbeddedChannel(decoder);

        ByteBuf data = Unpooled.copiedBuffer("plain data".getBytes());
        channel.writeInbound(new DatagramPacket(data, REMOTE, LOCAL));

        DatagramPacket out = channel.readInbound();
        assertNotNull(out);
        assertEquals("plain data", out.content().toString(StandardCharsets.UTF_8));
        out.release();
    }

    // ===================== Stats 自适应测试 =====================

    @Test
    public void testStatsLossCalculation() {
        // multiplier=3, 收到 3 份（全到）→ loss ≈ 0%
        UdpRedundantStats stats = new UdpRedundantStats(3, 1, 5, 0, 0.20, 0.05, 1);
        // 模拟 10 个 unique 包，每个收到 3 份
        for (int i = 0; i < 10; i++) {
            stats.recordUnique();
            stats.recordReceived();
            stats.recordReceived();
            stats.recordReceived();
        }
        stats.adjustMultiplier();
        // ratio = 30/10 = 3.0, loss = 1 - 3.0/3 = 0% → should decrease
        assertEquals(2, stats.getMultiplier(), "Should decrease from 3 to 2 when loss < 5%");
    }

    @Test
    public void testStatsHighLoss() {
        // multiplier=2, 收到 1 份（50% loss）→ should increase
        UdpRedundantStats stats = new UdpRedundantStats(2, 1, 5, 0, 0.20, 0.05, 1);
        for (int i = 0; i < 10; i++) {
            stats.recordUnique();
            stats.recordReceived(); // 只到了 1 份
        }
        stats.adjustMultiplier();
        // ratio = 10/10 = 1.0, loss = 1 - 1.0/2 = 50% → increase
        assertEquals(3, stats.getMultiplier(), "Should increase from 2 to 3 when loss > 20%");
    }

    @Test
    public void testStatsDebounce() {
        // 防抖周期 = 3，需要连续 3 次满足才调整
        UdpRedundantStats stats = new UdpRedundantStats(3, 1, 5, 0, 0.20, 0.05, 3);

        // 第 1 次：低丢包
        feedLowLoss(stats, 3);
        stats.adjustMultiplier();
        assertEquals(3, stats.getMultiplier(), "Should NOT decrease after only 1 period");

        // 第 2 次：低丢包
        feedLowLoss(stats, 3);
        stats.adjustMultiplier();
        assertEquals(3, stats.getMultiplier(), "Should NOT decrease after only 2 periods");

        // 第 3 次：低丢包 → 满足防抖条件，应该降低
        feedLowLoss(stats, 3);
        stats.adjustMultiplier();
        assertEquals(2, stats.getMultiplier(), "Should decrease after 3 consecutive low-loss periods");
    }

    @Test
    public void testStatsDebounceResetOnFluctuation() {
        UdpRedundantStats stats = new UdpRedundantStats(3, 1, 5, 0, 0.20, 0.05, 3);

        // 2 次低丢包
        feedLowLoss(stats, 3);
        stats.adjustMultiplier();
        feedLowLoss(stats, 3);
        stats.adjustMultiplier();
        assertEquals(3, stats.getMultiplier());

        // 第 3 次：高丢包 → 打断降级防抖
        feedHighLoss(stats, 3);
        stats.adjustMultiplier();
        assertEquals(3, stats.getMultiplier(), "Debounce reset: should NOT change");

        // 再来 3 次低丢包才会降
        for (int i = 0; i < 3; i++) {
            feedLowLoss(stats, 3);
            stats.adjustMultiplier();
        }
        assertEquals(2, stats.getMultiplier(), "Should decrease after 3 clean periods post-reset");
    }

    @Test
    public void testStatsClampBounds() {
        // 已经是最大值 5，不应该再增加
        UdpRedundantStats stats = new UdpRedundantStats(5, 1, 5, 0, 0.20, 0.05, 1);
        feedHighLoss(stats, 5);
        stats.adjustMultiplier();
        assertEquals(5, stats.getMultiplier(), "Should not exceed maxMultiplier");

        // 已经是最小值 1，不应该再减少
        stats = new UdpRedundantStats(1, 1, 5, 0, 0.20, 0.05, 1);
        feedLowLoss(stats, 1);
        stats.adjustMultiplier();
        assertEquals(1, stats.getMultiplier(), "Should not go below minMultiplier");
    }

    @Test
    public void testAdaptiveEncoderUsesStats() {
        // 验证 encoder 在自适应模式下使用 stats 的倍率
        UdpRedundantStats stats = new UdpRedundantStats(2, 1, 5, 0, 0.20, 0.05, 1);
        UdpRedundantEncoder encoder = new UdpRedundantEncoder(stats);
        EmbeddedChannel channel = new EmbeddedChannel(encoder);

        // 初始 multiplier=2，应该产生 2 个包
        ByteBuf data = Unpooled.copiedBuffer("test".getBytes());
        channel.writeOutbound(new DatagramPacket(data, REMOTE));

        int count = 0;
        while (channel.readOutbound() != null) {
            count++;
        }
        assertEquals(2, count, "Should send 2 copies with initial multiplier=2");
    }

    @Test
    public void testDecoderFeedsStats() {
        UdpRedundantStats stats = new UdpRedundantStats(3, 1, 5, 0, 0.20, 0.05, 1);
        UdpRedundantDecoder decoder = new UdpRedundantDecoder(stats);
        EmbeddedChannel channel = new EmbeddedChannel(decoder);

        // 发送 3 个冗余副本（同一 seqId）
        for (int i = 0; i < 3; i++) {
            ByteBuf buf = Unpooled.buffer();
            buf.writeInt(UdpRedundantEncoder.HEADER_MAGIC);
            buf.writeInt(42);
            buf.writeBytes("data".getBytes());
            channel.writeInbound(new DatagramPacket(buf, REMOTE, LOCAL));
        }

        // 只有 1 个传递给下游
        DatagramPacket p = channel.readInbound();
        assertNotNull(p);
        p.release();
        assertNull(channel.readInbound());

        // 验证 stats 被正确喂入数据后 adjustMultiplier 能正常执行
        stats.adjustMultiplier();
        // lossRate = 1 - (3/1)/3 = 0% → 低丢包 → 应该降低（stablePeriods=1 立即生效）
        assertEquals(2, stats.getMultiplier(), "Low loss should decrease multiplier");
    }

    // ===================== 辅助方法 =====================

    /**
     * 模拟低丢包场景：每个 unique 包都收到了 multiplier 份副本
     */
    private void feedLowLoss(UdpRedundantStats stats, int currentMultiplier) {
        for (int i = 0; i < 20; i++) {
            stats.recordUnique();
            for (int j = 0; j < currentMultiplier; j++) {
                stats.recordReceived();
            }
        }
    }

    /**
     * 模拟高丢包场景：每个 unique 包平均只收到 1 份
     */
    private void feedHighLoss(UdpRedundantStats stats, int currentMultiplier) {
        for (int i = 0; i < 20; i++) {
            stats.recordUnique();
            stats.recordReceived(); // 只有 1 份到达
        }
    }
}
