package org.rx.net.socks;

import org.rx.net.udp.*;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;
import org.rx.diagnostic.DiagnosticMetrics;
import org.rx.io.Bytes;
import org.rx.net.Sockets;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public final class Udp2rawPayloadSupport {
    private static final String METRIC_PREFIX = "socks.udp2raw";
    private static final int MAX_PENDING_REDUNDANT_COPIES = 4096;
    public static final long REDUNDANT_ADJUST_INTERVAL_MILLIS =
            TimeUnit.SECONDS.toMillis(UdpRedundantEncoder.ADJUST_INTERVAL_SECONDS);
    private static final AttributeKey<AtomicInteger> ATTR_PENDING_REDUNDANT_COPIES =
            AttributeKey.valueOf("udp2rawPendingRedundantCopies");

    private Udp2rawPayloadSupport() {
    }

    public static boolean isCompressEnabled(UdpCompressConfig config) {
        return config != null && config.isEnabled() && config.getCodec() == UdpCompressCodec.LZ4_FAST
                && config.getDictionaryId() == 0;
    }

    public static boolean isRedundantEnabled(UdpRedundantConfig config) {
        return config != null && (config.getMultiplier() > 1 || config.isAdaptive()
                || config.hasDestinationRules());
    }

    public static ByteBuf compress(ByteBufAllocator alloc, ByteBuf payload, UdpCompressConfig config,
            UdpCompressStats stats, InetSocketAddress recipient, String direction) {
        if (!isCompressEnabled(config) || payload == null || !payload.isReadable()) {
            return null;
        }
        if (stats != null && stats.shouldBypass(recipient)) {
            recordCompress("bypass", direction);
            return null;
        }
        int payloadLen = payload.readableBytes();
        if (payloadLen < config.getMinPayloadBytes() || payloadLen > 0xFFFF) {
            recordCompress("bypass", direction);
            return null;
        }

        int maxCompressedLen = UdpCompressSupport.maxCompressedLength(payloadLen, config.getCompressionLevel());
        ByteBuf encoded = alloc.directBuffer(UdpCompressSupport.HEADER_SIZE + maxCompressedLen);
        try {
            encoded.writeInt(UdpCompressSupport.HEADER_MAGIC);
            encoded.writeShort(payloadLen);
            encoded.writeByte(0);
            encoded.writeByte(0);

            int dataOffset = encoded.writerIndex();
            int compressedLen = UdpCompressSupport.compress(payload, payload.readerIndex(), payloadLen,
                    encoded, dataOffset, maxCompressedLen, config.getCompressionLevel());
            int totalLen = UdpCompressSupport.HEADER_SIZE + compressedLen;
            int savedBytes = payloadLen - totalLen;
            if (savedBytes <= 0 || savedBytes < config.getMinSavingsBytes()
                    || ((double) savedBytes / (double) payloadLen) < config.getMinSavingsRatio()) {
                if (stats != null) {
                    stats.recordLowGain(recipient);
                }
                recordCompress("bypass", direction);
                encoded.release();
                return null;
            }
            encoded.writerIndex(dataOffset + compressedLen);
            if (stats != null) {
                stats.recordApplied(recipient);
            }
            recordCompress("compressed", direction);
            return encoded;
        } catch (Throwable e) {
            encoded.release();
            recordCompress("fail", direction);
            log.warn("udp2raw compress fallback direction={} recipient={}", direction, recipient, e);
            return null;
        }
    }

    public static ByteBuf decompress(ByteBufAllocator alloc, ByteBuf payload, String direction) {
        if (payload == null || payload.readableBytes() < UdpCompressSupport.HEADER_SIZE) {
            recordCompress("fail", direction);
            return null;
        }
        int readerIndex = payload.readerIndex();
        if (payload.getInt(readerIndex) != UdpCompressSupport.HEADER_MAGIC) {
            recordCompress("fail", direction);
            return null;
        }
        int originalLen = payload.getUnsignedShort(readerIndex + 4);
        int flags = payload.getUnsignedByte(readerIndex + 6);
        int dictionaryId = payload.getUnsignedByte(readerIndex + 7);
        if (originalLen <= 0 || (flags & UdpCompressSupport.FLAG_DICT) != 0 || dictionaryId != 0) {
            recordCompress("fail", direction);
            return null;
        }
        int compressedIndex = readerIndex + UdpCompressSupport.HEADER_SIZE;
        int compressedLen = payload.readableBytes() - UdpCompressSupport.HEADER_SIZE;
        if (compressedLen <= 0) {
            recordCompress("fail", direction);
            return null;
        }

        ByteBuf decoded = alloc.directBuffer(originalLen, originalLen);
        try {
            int actualLen = UdpCompressSupport.decompress(payload, compressedIndex, compressedLen,
                    decoded, 0, originalLen);
            if (actualLen != originalLen) {
                decoded.release();
                recordCompress("fail", direction);
                return null;
            }
            decoded.writerIndex(actualLen);
            recordCompress("decompressed", direction);
            return decoded;
        } catch (Throwable e) {
            decoded.release();
            recordCompress("fail", direction);
            log.warn("udp2raw decompress failed direction={}", direction, e);
            return null;
        }
    }

    public static Sockets.UdpWriteResult writeEncoded(Channel channel, ByteBuf encoded,
            InetSocketAddress recipient, UdpRedundantConfig redundant,
            UdpRedundantMultiplierResolver resolver, String flowTag) {
        return writeEncoded(channel, encoded, recipient, redundant, null, resolver, flowTag);
    }

    public static Sockets.UdpWriteResult writeEncoded(Channel channel, ByteBuf encoded,
            InetSocketAddress recipient, UdpRedundantConfig redundant, UdpRedundantStats stats,
            UdpRedundantMultiplierResolver resolver, String flowTag) {
        return writeEncoded(channel, encoded, recipient, redundant, stats, resolver, flowTag, 0);
    }

    public static Sockets.UdpWriteResult writeEncoded(Channel channel, ByteBuf encoded,
            InetSocketAddress recipient, UdpRedundantConfig redundant, UdpRedundantStats stats,
            UdpRedundantMultiplierResolver resolver, String flowTag, int udpMtu) {
        return writeEncoded(channel, encoded, recipient, redundant, stats, resolver, flowTag, udpMtu, null);
    }

    public static Sockets.UdpWriteResult writeEncoded(Channel channel, ByteBuf encoded,
            InetSocketAddress recipient, UdpRedundantConfig redundant, UdpRedundantStats stats,
            UdpRedundantMultiplierResolver resolver, String flowTag, int udpMtu, Udp2rawMtuState mtuState) {
        if (encoded == null) {
            return Sockets.UdpWriteResult.WRITE_THROWN;
        }
        int bytes = encoded.readableBytes();
        if (udpMtu > 0 && bytes > udpMtu) {
            Bytes.release(encoded);
            String tags = appendDirection(flowTag);
            DiagnosticMetrics.record(METRIC_PREFIX + ".drop.count", 1D,
                    tags + ",reason=mtu-exceeded");
            DiagnosticMetrics.record(METRIC_PREFIX + ".mtu.drop.count", 1D, tags);
            DiagnosticMetrics.record(METRIC_PREFIX + ".mtu.drop.bytes", bytes, tags);
            recordMtuWriteResult(mtuState, Sockets.UdpWriteResult.MTU_EXCEEDED, bytes);
            return Sockets.UdpWriteResult.MTU_EXCEEDED;
        }
        int multiplier = effectiveMultiplier(redundant, stats, resolver, recipient);
        int intervalMicros = redundant != null ? Math.max(0, redundant.getIntervalMicros()) : 0;
        Sockets.UdpWriteResult first = Sockets.UdpWriteResult.WRITE_THROWN;
        try {
            if (multiplier <= 1) {
                DatagramPacket packet = new DatagramPacket(encoded, recipient);
                encoded = null;
                Sockets.UdpWriteResult result = Sockets.writeUdp(channel, packet,
                        METRIC_PREFIX, flowTag + ",redundant=false");
                recordMtuWriteResult(mtuState, result, bytes);
                return result;
            }

            first = writeCopy(channel, encoded.retainedDuplicate(), recipient,
                    flowTag + ",redundant=true,copy=first");
            recordMtuWriteResult(mtuState, first, bytes);
            for (int i = 1; i < multiplier; i++) {
                ByteBuf copy = encoded.retainedDuplicate();
                if (intervalMicros <= 0) {
                    writeCopy(channel, copy, recipient, flowTag + ",redundant=true,copy=extra");
                    continue;
                }
                scheduleCopy(channel, copy, recipient, (long) intervalMicros * i, flowTag);
            }
            DiagnosticMetrics.record(METRIC_PREFIX + ".redundant.copy.count",
                    multiplier, appendDirection(flowTag));
            return first;
        } finally {
            Bytes.release(encoded);
        }
    }

    private static void recordMtuWriteResult(Udp2rawMtuState mtuState,
            Sockets.UdpWriteResult result, int bytes) {
        if (mtuState != null && result == Sockets.UdpWriteResult.MTU_EXCEEDED) {
            mtuState.onWriteMtuDrop(bytes, System.currentTimeMillis());
        }
    }

    public static UdpRedundantStats newAdaptiveStats(UdpRedundantConfig config) {
        if (!isRedundantEnabled(config) || !config.isAdaptive()) {
            return null;
        }
        return new UdpRedundantStats(config.getMultiplier(), config.getMinMultiplier(), config.getMaxMultiplier(),
                config.getIntervalMicros(), config.getLossThresholdHigh(), config.getLossThresholdLow(),
                config.getStablePeriods());
    }

    public static void adjustAdaptiveStats(UdpRedundantStats stats, AtomicLong nextAdjustAtMillis,
            String direction) {
        if (stats == null || nextAdjustAtMillis == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long next = nextAdjustAtMillis.get();
        if (now < next || !nextAdjustAtMillis.compareAndSet(next, now + REDUNDANT_ADJUST_INTERVAL_MILLIS)) {
            return;
        }
        int multiplier = stats.adjustMultiplier();
        DiagnosticMetrics.record(METRIC_PREFIX + ".redundant.adaptive.multiplier",
                multiplier, "direction=" + direction);
        DiagnosticMetrics.record(METRIC_PREFIX + ".redundant.adaptive.loss.rate",
                stats.getLastLossRate(), "direction=" + direction);
    }

    private static Sockets.UdpWriteResult writeCopy(Channel channel, ByteBuf copy,
            InetSocketAddress recipient, String tags) {
        return Sockets.writeUdp(channel, new DatagramPacket(copy, recipient), METRIC_PREFIX, tags);
    }

    private static void scheduleCopy(Channel channel, ByteBuf copy,
            InetSocketAddress recipient, long delayMicros, String flowTag) {
        AtomicInteger pending = pendingCopies(channel);
        int queued = pending.incrementAndGet();
        if (queued > MAX_PENDING_REDUNDANT_COPIES) {
            pending.decrementAndGet();
            writeCopy(channel, copy, recipient, flowTag + ",redundant=true,copy=overflow");
            DiagnosticMetrics.record(METRIC_PREFIX + ".redundant.delayed.drop.count",
                    1D, appendDirection(flowTag) + ",reason=pending-overlimit");
            return;
        }
        try {
            channel.eventLoop().schedule(() -> {
                try {
                    if (!channel.isActive()) {
                        copy.release();
                        DiagnosticMetrics.record(METRIC_PREFIX + ".redundant.delayed.drop.count",
                                1D, appendDirection(flowTag) + ",reason=inactive");
                        return;
                    }
                    writeCopy(channel, copy, recipient, flowTag + ",redundant=true,copy=delayed");
                } finally {
                    pending.decrementAndGet();
                }
            }, delayMicros, TimeUnit.MICROSECONDS);
        } catch (Throwable e) {
            pending.decrementAndGet();
            copy.release();
            throw e;
        }
    }

    private static AtomicInteger pendingCopies(Channel channel) {
        AtomicInteger state = channel.attr(ATTR_PENDING_REDUNDANT_COPIES).get();
        if (state != null) {
            return state;
        }
        AtomicInteger created = new AtomicInteger();
        AtomicInteger old = channel.attr(ATTR_PENDING_REDUNDANT_COPIES).setIfAbsent(created);
        return old != null ? old : created;
    }

    public static int effectiveMultiplier(UdpRedundantConfig config, UdpRedundantStats stats,
                                          UdpRedundantMultiplierResolver resolver, InetSocketAddress recipient) {
        if (config == null) {
            return 1;
        }
        int rule = UdpRedundantMultiplierResolver.NO_MATCH;
        if (resolver != null && recipient != null) {
            rule = resolver.resolve(recipient);
        }
        if (rule >= 1 && rule <= 5) {
            return rule;
        }
        int multiplier = stats != null ? stats.getMultiplier() : config.getMultiplier();
        if (config.isAdaptive() && stats == null) {
            multiplier = Math.max(config.getMinMultiplier(), Math.min(multiplier, config.getMaxMultiplier()));
        }
        return Math.max(1, Math.min(5, multiplier));
    }

    private static void recordCompress(String result, String direction) {
        DiagnosticMetrics.record(METRIC_PREFIX + ".compress.count", 1D,
                "result=" + result + ",direction=" + direction);
    }

    private static String appendDirection(String flowTag) {
        return flowTag != null ? flowTag : "flow=unknown";
    }
}
