package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.DatagramPacket;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UDP冗余编码器性能测试
 * 验证连续 direct buffer、批量 flush 与内存释放行为。
 */
@Slf4j
public class UdpRedundantEncoderPerformanceTest {

    private static final int TEST_PACKET_SIZE = 1024;
    private static final int TEST_MULTIPLIER = 3;
    private static final int WARMUP_ITERATIONS = 1000;
    private static final int TEST_ITERATIONS = 10000;

    @Test
    public void testMemoryAllocationOptimization() {
        log.info("=== 测试内存分配优化 ===");
        
        EmbeddedChannel channel = new EmbeddedChannel();
        UdpRedundantEncoder encoder = new UdpRedundantEncoder(TEST_MULTIPLIER, 0, null);
        channel.pipeline().addLast(encoder);
        
        InetSocketAddress recipient = new InetSocketAddress("127.0.0.1", 8080);
        ByteBuf payload = createTestPayload(TEST_PACKET_SIZE);
        
        // 预热
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            ByteBuf testPayload = payload.copy();
            DatagramPacket packet = new DatagramPacket(testPayload, recipient);
            channel.writeOutbound(packet);
            // 清理输出
            while (channel.outboundMessages().peek() != null) {
                DatagramPacket outPacket = channel.readOutbound();
                outPacket.release();
            }
        }
        
        // 测试内存分配
        long startTime = System.nanoTime();
        for (int i = 0; i < TEST_ITERATIONS; i++) {
            ByteBuf testPayload = payload.copy();
            DatagramPacket packet = new DatagramPacket(testPayload, recipient);
            channel.writeOutbound(packet);
            // 清理输出
            while (channel.outboundMessages().peek() != null) {
                DatagramPacket outPacket = channel.readOutbound();
                outPacket.release();
            }
        }
        long endTime = System.nanoTime();
        
        double avgTimeNs = (double) (endTime - startTime) / TEST_ITERATIONS;
        log.info("平均处理时间: {} ns ({} μs)", avgTimeNs, avgTimeNs / 1000);
        
        payload.release();
        channel.close();
        
        // 验证性能在合理范围内
        assertTrue(avgTimeNs < 200000, "平均处理时间过长: " + avgTimeNs + " ns");
    }

    @Test
    public void testContiguousDirectBufferCompatibility() {
        log.info("=== 测试连续 direct buffer 兼容性 ===");
        
        EmbeddedChannel channel = new EmbeddedChannel();
        UdpRedundantEncoder encoder = new UdpRedundantEncoder(TEST_MULTIPLIER, 0, null);
        channel.pipeline().addLast(encoder);
        
        InetSocketAddress recipient = new InetSocketAddress("127.0.0.1", 8080);
        ByteBuf originalPayload = createTestPayload(TEST_PACKET_SIZE);
        
        // 记录原始payload的引用计数
        int originalRefCnt = originalPayload.refCnt();
        log.info("原始payload引用计数: {}", originalRefCnt);
        
        // 发送数据包
        DatagramPacket packet = new DatagramPacket(originalPayload, recipient);
        channel.writeOutbound(packet);
        
        // 连续 direct buffer 模式会拷贝 payload 后释放原始包，避免 composite 在 epoll sendmmsg 下触发 EINVAL。
        assertEquals(0, originalPayload.refCnt(),
                  "连续 direct buffer 模式下原始payload应被释放");
        
        // 检查输出包
        int packetCount = 0;
        long firstMemoryAddress = -1;
        while (channel.outboundMessages().peek() != null) {
            DatagramPacket outPacket = channel.readOutbound();
            assertNotNull(outPacket);
            
            ByteBuf content = outPacket.content();
            assertTrue(content.isDirect(), "多倍发包应使用 direct buffer");
            assertFalse(content instanceof CompositeByteBuf, "不应恢复 CompositeByteBuf，避免 native UDP 发送兼容性风险");
            if (content.hasMemoryAddress()) {
                long memoryAddress = content.memoryAddress();
                if (firstMemoryAddress < 0) {
                    firstMemoryAddress = memoryAddress;
                } else {
                    assertEquals(firstMemoryAddress, memoryAddress, "冗余副本应共享同一个连续 direct packet 内存");
                }
            }
            
            packetCount++;
            outPacket.release();
        }
        
        assertEquals(TEST_MULTIPLIER, packetCount);
        channel.close();
    }

    @Test
    public void testZeroIntervalCopiesBatchFlushOnce() {
        FlushCountingHandler flushCounter = new FlushCountingHandler();
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast(flushCounter);
        channel.pipeline().addLast(new UdpRedundantEncoder(TEST_MULTIPLIER, 0, null));

        InetSocketAddress recipient = new InetSocketAddress("127.0.0.1", 8080);
        ByteBuf payload = createTestPayload(TEST_PACKET_SIZE);

        channel.pipeline().write(new DatagramPacket(payload, recipient));

        int packetCount = 0;
        while (channel.outboundMessages().peek() != null) {
            DatagramPacket outPacket = channel.readOutbound();
            packetCount++;
            outPacket.release();
        }

        assertEquals(TEST_MULTIPLIER, packetCount);
        assertEquals(1, flushCounter.flushCount, "零间隔冗余包应批量写入后只 flush 一次");
        channel.close();
    }

    @Test
    public void testIntervalSendingMemoryEfficiency() throws Exception {
        log.info("=== 测试间隔发送内存效率 ===");
        
        EmbeddedChannel channel = new EmbeddedChannel();
        UdpRedundantEncoder encoder = new UdpRedundantEncoder(TEST_MULTIPLIER, 500, null); // 500μs间隔
        channel.pipeline().addLast(encoder);
        
        InetSocketAddress recipient = new InetSocketAddress("127.0.0.1", 8080);
        ByteBuf payload = createTestPayload(TEST_PACKET_SIZE);
        
        long startTime = System.nanoTime();
        
        // 发送数据包
        ByteBuf sentPayload = payload.copy();
        DatagramPacket packet = new DatagramPacket(sentPayload, recipient);
        channel.writeOutbound(packet);
        assertEquals(0, sentPayload.refCnt(), "间隔发送也应释放原始payload");
        
        Thread.sleep(5);
        channel.runScheduledPendingTasks();
        channel.runPendingTasks();
        
        long endTime = System.nanoTime();
        log.info("间隔发送处理时间: {} ns", endTime - startTime);
        
        // 验证输出 - 至少应该有第一个包
        int packetCount = 0;
        while (channel.outboundMessages().peek() != null) {
            Object msg = channel.readOutbound();
            assertNotNull(msg);
            assertTrue(msg instanceof DatagramPacket, "应该输出DatagramPacket");
            packetCount++;
            ((DatagramPacket) msg).release();
        }
        
        assertEquals(TEST_MULTIPLIER, packetCount, "间隔发送也应输出完整冗余副本");
        log.info("实际发送包数: {}", packetCount);
        
        payload.release();
        channel.close();
    }

    @Test
    public void testMemoryLeakPrevention() {
        log.info("=== 测试内存泄漏防护 ===");
        
        EmbeddedChannel channel = new EmbeddedChannel();
        UdpRedundantEncoder encoder = new UdpRedundantEncoder(TEST_MULTIPLIER, 0, null);
        channel.pipeline().addLast(encoder);
        
        InetSocketAddress recipient = new InetSocketAddress("127.0.0.1", 8080);
        
        // 记录初始内存使用
        long initialMemory = PooledByteBufAllocator.DEFAULT.metric().usedDirectMemory();
        
        // 大量发送测试
        for (int i = 0; i < TEST_ITERATIONS; i++) {
            ByteBuf payload = createTestPayload(TEST_PACKET_SIZE);
            DatagramPacket packet = new DatagramPacket(payload, recipient);
            channel.writeOutbound(packet);
            
            // 清理输出
            while (channel.outboundMessages().peek() != null) {
                DatagramPacket outPacket = channel.readOutbound();
                outPacket.release();
            }
        }
        
        // 强制GC
        System.gc();
        System.runFinalization();
        
        // 验证没有严重的内存泄漏
        long finalMemory = PooledByteBufAllocator.DEFAULT.metric().usedDirectMemory();
        long memoryIncrease = finalMemory - initialMemory;
        
        log.info("初始直接内存: {} bytes", initialMemory);
        log.info("最终直接内存: {} bytes", finalMemory);
        log.info("内存增长: {} bytes", memoryIncrease);
        
        // 允许合理的内存增长（缓冲区缓存等），但不应该过大
        assertTrue(memoryIncrease < 10 * 1024 * 1024, 
                  "可能存在内存泄漏：直接内存增长过多 " + memoryIncrease + " bytes");
        
        channel.close();
    }

    @Test
    public void testPerformanceComparison() {
        log.info("=== 性能对比测试 ===");
        
        InetSocketAddress recipient = new InetSocketAddress("127.0.0.1", 8080);
        ByteBuf payload = createTestPayload(TEST_PACKET_SIZE);
        
        // 测试优化后的编码器
        EmbeddedChannel optimizedChannel = new EmbeddedChannel();
        UdpRedundantEncoder optimizedEncoder = new UdpRedundantEncoder(TEST_MULTIPLIER, 0, null);
        optimizedChannel.pipeline().addLast(optimizedEncoder);
        
        long optimizedTime = measureEncodingTime(optimizedChannel, payload, recipient);
        
        // 清理
        while (optimizedChannel.outboundMessages().peek() != null) {
            DatagramPacket outPacket = optimizedChannel.readOutbound();
            outPacket.release();
        }
        optimizedChannel.close();
        
        log.info("优化后编码器平均处理时间: {} ns", optimizedTime);
        
        // 性能基准：应该在合理范围内
        assertTrue(optimizedTime < 100000, "处理时间过长，可能存在性能问题"); // < 100μs
        
        payload.release();
    }

    private long measureEncodingTime(EmbeddedChannel channel, ByteBuf payload, InetSocketAddress recipient) {
        // 预热
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            ByteBuf testPayload = payload.copy();
            DatagramPacket packet = new DatagramPacket(testPayload, recipient);
            channel.writeOutbound(packet);
            
            // 清理
            while (channel.outboundMessages().peek() != null) {
                DatagramPacket outPacket = channel.readOutbound();
                outPacket.release();
            }
        }
        
        // 测试
        long startTime = System.nanoTime();
        for (int i = 0; i < TEST_ITERATIONS; i++) {
            ByteBuf testPayload = payload.copy();
            DatagramPacket packet = new DatagramPacket(testPayload, recipient);
            channel.writeOutbound(packet);
            
            // 清理
            while (channel.outboundMessages().peek() != null) {
                DatagramPacket outPacket = channel.readOutbound();
                outPacket.release();
            }
        }
        long endTime = System.nanoTime();
        
        return (endTime - startTime) / TEST_ITERATIONS;
    }

    private ByteBuf createTestPayload(int size) {
        ByteBuf buffer = PooledByteBufAllocator.DEFAULT.directBuffer(size);
        byte[] data = new byte[size];
        // 填充测试数据
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (i % 256);
        }
        buffer.writeBytes(data);
        return buffer;
    }

    static final class FlushCountingHandler extends ChannelOutboundHandlerAdapter {
        int flushCount;

        @Override
        public void flush(io.netty.channel.ChannelHandlerContext ctx) throws Exception {
            flushCount++;
            super.flush(ctx);
        }
    }
}
