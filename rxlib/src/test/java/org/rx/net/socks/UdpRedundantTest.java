package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
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
    public void testEncoderMultiplierOne() {
        // Test that multiplier=1 works without throwing exception
        UdpRedundantEncoder encoder = new UdpRedundantEncoder(1, 0);
        EmbeddedChannel channel = new EmbeddedChannel(encoder);

        ByteBuf data = Unpooled.copiedBuffer("test".getBytes());
        channel.writeOutbound(new DatagramPacket(data, REMOTE));

        // Should only have one packet (no redundancy)
        DatagramPacket out = channel.readOutbound();
        assertNotNull(out);
        // Should be passed through without header when multiplier=1
        assertEquals("test", out.content().toString(StandardCharsets.UTF_8));
        out.release();
        assertNull(channel.readOutbound());
    }

    @Test
    public void testEncoderMultiplierOnePassthrough() {
        // Test that multiplier=1 behaves like passthrough (no header added)
        UdpRedundantEncoder encoder = new UdpRedundantEncoder(1, 0);
        EmbeddedChannel channel = new EmbeddedChannel(encoder);

        ByteBuf data = Unpooled.copiedBuffer("plain".getBytes());
        channel.writeOutbound(new DatagramPacket(data, REMOTE));

        DatagramPacket out = channel.readOutbound();
        assertNotNull(out);
        // Verify no header was added
        assertEquals("plain", out.content().toString(StandardCharsets.UTF_8));
        assertEquals(5, out.content().readableBytes()); // Only "plain", no 8-byte header
        out.release();
    }

    @Test
    public void testEncoderBoundaryValidation() {
        // Test boundary validation
        assertThrows(IllegalArgumentException.class, () -> new UdpRedundantEncoder(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new UdpRedundantEncoder(6, 0));
        // These should work
        assertDoesNotThrow(() -> new UdpRedundantEncoder(1, 0));
        assertDoesNotThrow(() -> new UdpRedundantEncoder(5, 0));
    }

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

    @Test
    public void testRedundantCopiesUseContiguousBuffer() {
        UdpRedundantEncoder encoder = new UdpRedundantEncoder(3, 0);
        EmbeddedChannel channel = new EmbeddedChannel(encoder);

        ByteBuf data = Unpooled.copiedBuffer("compat".getBytes(StandardCharsets.UTF_8));
        channel.writeOutbound(new DatagramPacket(data, REMOTE));

        DatagramPacket first = channel.readOutbound();
        assertNotNull(first);
        assertTrue(first.content() instanceof CompositeByteBuf, "首包保留零拷贝 composite");
        first.release();

        DatagramPacket redundant1 = channel.readOutbound();
        assertNotNull(redundant1);
        assertFalse(redundant1.content() instanceof CompositeByteBuf, "冗余副本应使用连续缓冲，避免 native sendmmsg 兼容性问题");
        redundant1.release();

        DatagramPacket redundant2 = channel.readOutbound();
        assertNotNull(redundant2);
        assertFalse(redundant2.content() instanceof CompositeByteBuf, "冗余副本应使用连续缓冲，避免 native sendmmsg 兼容性问题");
        redundant2.release();

        assertNull(channel.readOutbound());
    }

    @Test
    public void testAdaptiveModeInitialMultiplier() {
        // Test that adaptive mode respects user's initial multiplier
        UdpRedundantStats stats = new UdpRedundantStats(1, 1, 5, 0, 0.20, 0.05, 1);
        assertEquals(1, stats.getMultiplier(), "Should respect initial multiplier=1");
        
        stats = new UdpRedundantStats(2, 1, 5, 0, 0.20, 0.05, 1);
        assertEquals(2, stats.getMultiplier(), "Should respect initial multiplier=2");
    }

    @Test
    public void testEncoderMemoryOptimization() {
        // Test that memory optimization works correctly with ByteBuf slices
        UdpRedundantEncoder encoder = new UdpRedundantEncoder(3, 0);
        EmbeddedChannel channel = new EmbeddedChannel(encoder);

        // Create a larger payload to verify slice optimization
        String largeData = "This is a larger payload to test memory optimization with ByteBuf slices";
        ByteBuf data = Unpooled.copiedBuffer(largeData.getBytes());
        assertEquals(1, data.refCnt(), "Original data should have refCnt=1");

        channel.writeOutbound(new DatagramPacket(data, REMOTE));

        // Should have 3 copies
        for (int i = 0; i < 3; i++) {
            DatagramPacket out = channel.readOutbound();
            assertNotNull(out, "copy " + i + " should exist");
            ByteBuf content = out.content();
            content.skipBytes(8); // skip header
            assertEquals(largeData, content.toString(StandardCharsets.UTF_8));
            out.release();
        }
        assertNull(channel.readOutbound());
        assertEquals(0, data.refCnt(), "Original data should be fully released");
    }

    /**
     * 多倍发送时 Encoder 替换为新的 DatagramPacket，必须 release 原始包，否则 payload ByteBuf 泄漏。
     */
    @Test
    public void testEncoderReleasesOriginalDatagramPacket() {
        UdpRedundantEncoder encoder = new UdpRedundantEncoder(2, 0);
        EmbeddedChannel channel = new EmbeddedChannel(encoder);

        ByteBuf payload = Unpooled.directBuffer(8);
        payload.writeBytes("ping".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, payload.refCnt());

        channel.writeOutbound(new DatagramPacket(payload, REMOTE));
        // 原 DatagramPacket 已释放；payload 仅被 composite 中 retain 的一份引用
        assertEquals(1, payload.refCnt());

        for (int i = 0; i < 2; i++) {
            DatagramPacket out = channel.readOutbound();
            assertNotNull(out);
            out.release();
        }
        assertNull(channel.readOutbound());
        assertEquals(0, payload.refCnt());
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
    public void testSerialArithmeticWraparound() {
        UdpRedundantDecoder decoder = new UdpRedundantDecoder();
        EmbeddedChannel channel = new EmbeddedChannel(decoder);

        // 1. 发送接近上限的包 (0x7FFFFFFF)
        int seqMax = Integer.MAX_VALUE;
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(UdpRedundantEncoder.HEADER_MAGIC);
        buf.writeInt(seqMax);
        buf.writeBytes("max".getBytes());
        channel.writeInbound(new DatagramPacket(buf, REMOTE, LOCAL));
        DatagramPacket p = channel.readInbound();
        assertNotNull(p);
        p.release();

        // 2. 发送回绕后的包 (0x80000000, 对应 signed MIN_VALUE)
        // 在 32 位 Serial Arithmetic 中, (MIN_VALUE - MAX_VALUE) = 1, diff > 0, 判定为新包
        int seqWrapped = Integer.MIN_VALUE;
        buf = Unpooled.buffer();
        buf.writeInt(UdpRedundantEncoder.HEADER_MAGIC);
        buf.writeInt(seqWrapped);
        buf.writeBytes("wrapped".getBytes());
        channel.writeInbound(new DatagramPacket(buf, REMOTE, LOCAL));
        p = channel.readInbound();
        assertNotNull(p, "Wraparound packet should be accepted as NEW");
        assertEquals("wrapped", p.content().toString(StandardCharsets.UTF_8));
        p.release();

        // 3. 发送回绕后的重复包
        buf = Unpooled.buffer();
        buf.writeInt(UdpRedundantEncoder.HEADER_MAGIC);
        buf.writeInt(seqWrapped);
        buf.writeBytes("wrapped".getBytes());
        channel.writeInbound(new DatagramPacket(buf, REMOTE, LOCAL));
        assertNull(channel.readInbound(), "Duplicate wrapped packet should be discarded");

        // 4. 发送回绕前的旧包 (MAX_VALUE)，应在窗口内 (diff = -1)
        buf = Unpooled.buffer();
        buf.writeInt(UdpRedundantEncoder.HEADER_MAGIC);
        buf.writeInt(seqMax);
        buf.writeBytes("max".getBytes());
        channel.writeInbound(new DatagramPacket(buf, REMOTE, LOCAL));
        assertNull(channel.readInbound(), "Old packet before wrap should be recognized as duplicate");
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

    @Test
    public void testDecoderSequenceWrapAround() {
        // 测试序列号回绕场景
        UdpRedundantDecoder decoder = new UdpRedundantDecoder();
        EmbeddedChannel channel = new EmbeddedChannel(decoder);

        // 发送接近最大值的序列号
        int maxSeq = 0xFFFFFFFE; // 接近 32 位最大值
        sendPacketWithSeq(channel, maxSeq, "near-max");
        
        DatagramPacket out1 = channel.readInbound();
        assertNotNull(out1);
        assertEquals("near-max", out1.content().toString(StandardCharsets.UTF_8));
        out1.release();

        // 发送回绕后的序列号（应该被识别为新包）
        int wrappedSeq = 0x00000001; // 回绕后的小值
        sendPacketWithSeq(channel, wrappedSeq, "wrapped");
        
        DatagramPacket out2 = channel.readInbound();
        assertNotNull(out2);
        assertEquals("wrapped", out2.content().toString(StandardCharsets.UTF_8));
        out2.release();

        // 重复发送回绕后的序列号（应该被丢弃）
        sendPacketWithSeq(channel, wrappedSeq, "wrapped-duplicate");
        assertNull(channel.readInbound()); // 应该被丢弃
    }

    @Test
    public void testDecoderLargeSequenceJump() {
        // 测试大跳跃序列号（超过窗口大小）
        UdpRedundantDecoder decoder = new UdpRedundantDecoder();
        EmbeddedChannel channel = new EmbeddedChannel(decoder);

        // 发送序列号 100
        sendPacketWithSeq(channel, 100, "packet-100");
        DatagramPacket out1 = channel.readInbound();
        assertNotNull(out1);
        out1.release();

        // 发送序列号 200（跳跃 100，超过窗口大小 64）
        sendPacketWithSeq(channel, 200, "packet-200");
        DatagramPacket out2 = channel.readInbound();
        assertNotNull(out2);
        out2.release();

        // 再次发送序列号 100（应该被丢弃，因为窗口已重置）
        sendPacketWithSeq(channel, 100, "packet-100-again");
        assertNull(channel.readInbound()); // 应该被丢弃
    }

    // 辅助方法：发送带序列号的数据包
    private void sendPacketWithSeq(EmbeddedChannel channel, int seqId, String data) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(UdpRedundantEncoder.HEADER_MAGIC);
        buf.writeInt(seqId);
        buf.writeBytes(data.getBytes());
        channel.writeInbound(new DatagramPacket(buf, REMOTE, LOCAL));
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

    // ===================== 分目的地倍率 =====================

    @Test
    public void testDestinationRuleExactHostAndPort() {
        UdpRedundantDestinationRule rule = new UdpRedundantDestinationRule();
        rule.setHost("192.0.2.10");
        rule.setPort(7777);
        rule.setMultiplier(3);
        assertTrue(rule.matches(new InetSocketAddress("192.0.2.10", 7777)));
        assertFalse(rule.matches(new InetSocketAddress("192.0.2.10", 7778)));
        assertFalse(rule.matches(new InetSocketAddress("192.0.2.11", 7777)));
    }

    @Test
    public void testDestinationRuleIpv4Cidr() {
        UdpRedundantDestinationRule rule = new UdpRedundantDestinationRule();
        rule.setHost("10.0.0.0/24");
        rule.setPort(0);
        rule.setMultiplier(3);
        assertTrue(rule.matches(new InetSocketAddress("10.0.0.1", 5000)));
        assertTrue(rule.matches(new InetSocketAddress("10.0.0.255", 1)));
        assertFalse(rule.matches(new InetSocketAddress("10.0.1.0", 5000)));
    }

    @Test
    public void testEncoderPerDestinationResolverOverridesGlobal() {
        InetSocketAddress bump = new InetSocketAddress("198.51.100.50", 4000);
        InetSocketAddress normal = new InetSocketAddress("203.0.113.1", 4000);
        UdpRedundantMultiplierResolver r = dest -> {
            if ("198.51.100.50".equals(dest.getAddress().getHostAddress())) {
                return 3;
            }
            return UdpRedundantMultiplierResolver.NO_MATCH;
        };
        UdpRedundantEncoder encoder = new UdpRedundantEncoder(2, 0, r);
        EmbeddedChannel ch = new EmbeddedChannel(encoder);

        ByteBuf b1 = Unpooled.copiedBuffer("a".getBytes(StandardCharsets.UTF_8));
        ch.writeOutbound(new DatagramPacket(b1, bump));
        for (int i = 0; i < 3; i++) {
            DatagramPacket out = ch.readOutbound();
            assertNotNull(out, "resolver bump -> 3 copies");
            out.release();
        }
        assertNull(ch.readOutbound());

        ByteBuf b2 = Unpooled.copiedBuffer("b".getBytes(StandardCharsets.UTF_8));
        ch.writeOutbound(new DatagramPacket(b2, normal));
        for (int i = 0; i < 2; i++) {
            DatagramPacket out = ch.readOutbound();
            assertNotNull(out, "global multiplier 2");
            out.release();
        }
        assertNull(ch.readOutbound());
    }

    @Test
    public void testRuleMultiplierOneForcesPassthrough() {
        UdpRedundantMultiplierResolver r = dest -> dest.getPort() == 7 ? 1 : UdpRedundantMultiplierResolver.NO_MATCH;
        UdpRedundantEncoder encoder = new UdpRedundantEncoder(3, 0, r);
        EmbeddedChannel ch = new EmbeddedChannel(encoder);

        ByteBuf small = Unpooled.copiedBuffer("x".getBytes(StandardCharsets.UTF_8));
        ch.writeOutbound(new DatagramPacket(small, new InetSocketAddress("127.0.0.1", 7)));
        DatagramPacket one = ch.readOutbound();
        assertNotNull(one);
        assertEquals(1, one.content().readableBytes());
        assertEquals('x', one.content().readByte());
        one.release();
        assertNull(ch.readOutbound());
    }

    @Test
    public void testSocksConfigResolverFirstRuleWins() {
        SocksConfig cfg = new SocksConfig(1080);
        UdpRedundantDestinationRule wide = new UdpRedundantDestinationRule();
        wide.setHost("192.0.2.0/24");
        wide.setPort(0);
        wide.setMultiplier(2);
        UdpRedundantDestinationRule narrow = new UdpRedundantDestinationRule();
        narrow.setHost("192.0.2.10");
        narrow.setPort(0);
        narrow.setMultiplier(4);
        cfg.getUdpRedundantDestinationRules().add(wide);
        cfg.getUdpRedundantDestinationRules().add(narrow);

        UdpRedundantMultiplierResolver res = cfg.buildUdpRedundantMultiplierResolver();
        assertEquals(2, res.resolve(new InetSocketAddress("192.0.2.10", 9999)));
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
