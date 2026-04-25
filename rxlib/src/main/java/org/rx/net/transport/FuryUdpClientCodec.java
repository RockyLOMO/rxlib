package org.rx.net.transport;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.util.concurrent.FastThreadLocal;
import org.apache.fury.Fury;
import org.rx.exception.InvalidException;
import org.rx.net.FuryCodecSupport;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class FuryUdpClientCodec implements UdpClientCodec {
    private static final long serialVersionUID = 2408618163606782278L;
    static final short FRAME_MAGIC = FuryCodecSupport.FRAME_MAGIC;
    static final byte FRAME_VERSION = FuryCodecSupport.FRAME_VERSION;
    static final byte CODEC_ID_FURY = FuryCodecSupport.CODEC_ID_FURY;
    static final short REGISTER_BASE_ID = 3200;
    final Set<String> allowedClassPrefixes = FuryCodecSupport.defaultAllowedClassPrefixes();
    transient volatile FastThreadLocal<Fury> furyLocal;

    public static FuryUdpClientCodec createDefault() {
        return new FuryUdpClientCodec();
    }

    public FuryUdpClientCodec allowPrefix(String prefix) {
        if (prefix != null && !prefix.isEmpty()) {
            allowedClassPrefixes.add(prefix);
            furyLocal = null;
        }
        return this;
    }

    public FuryUdpClientCodec allowClass(Class<?> type) {
        if (type != null) {
            allowedClassPrefixes.add(type.getName());
            furyLocal = null;
        }
        return this;
    }

    @Override
    public ByteBuf encode(ByteBufAllocator allocator, Object packet) throws Exception {
        ByteBuf payload = allocator.ioBuffer();
        boolean success = false;
        try {
            payload.writeShort(FRAME_MAGIC);
            payload.writeByte(FRAME_VERSION);
            payload.writeByte(CODEC_ID_FURY);
            int lengthIndex = payload.writerIndex();
            payload.writeInt(0);
            int payloadStart = payload.writerIndex();
            Fury fury = fury();
            try {
                try (ByteBufOutputStream stream = new ByteBufOutputStream(payload)) {
                    fury.serializeJavaObjectAndClass(stream, packet);
                }
            } finally {
                fury.reset();
            }
            payload.setInt(lengthIndex, payload.writerIndex() - payloadStart);
            success = true;
            return payload;
        } finally {
            if (!success) {
                payload.release();
            }
        }
    }

    @Override
    public Object decode(ByteBuf payload) throws Exception {
        int readerIndex = payload.readerIndex();
        int readableBytes = payload.readableBytes();
        if (readableBytes < 8) {
            throw new InvalidException("Fury udp frame too short {}", readableBytes);
        }

        short magic = payload.getShort(readerIndex);
        int version = payload.getUnsignedByte(readerIndex + 2);
        int codecId = payload.getUnsignedByte(readerIndex + 3);
        int payloadLength = payload.getInt(readerIndex + 4);
        int actualLength = readableBytes - 8;
        if (magic != FRAME_MAGIC) {
            throw new InvalidException("Fury udp frame magic mismatch {}", Integer.toHexString(magic & 0xFFFF));
        }
        if (version != FRAME_VERSION) {
            throw new InvalidException("Fury udp frame version mismatch {}", version);
        }
        if (codecId != CODEC_ID_FURY) {
            throw new InvalidException("Fury udp frame codec mismatch {}", codecId);
        }
        if (payloadLength < 0 || payloadLength != actualLength) {
            throw new InvalidException("Fury udp frame payload length mismatch payload={} actual={}", payloadLength, actualLength);
        }

        Fury fury = fury();
        try {
            return fury.deserializeJavaObjectAndClass(toMemoryBuffer(payload, readerIndex + 8, payloadLength));
        } finally {
            fury.reset();
        }
    }

    Fury fury() {
        FastThreadLocal<Fury> local = furyLocal;
        if (local != null) {
            return local.get();
        }
        synchronized (this) {
            local = furyLocal;
            if (local == null) {
                local = new FastThreadLocal<Fury>() {
                    @Override
                    protected Fury initialValue() {
                        return newFury(new ArrayList<>(allowedClassPrefixes));
                    }
                };
                furyLocal = local;
            }
        }
        return local.get();
    }

    Fury newFury(List<String> allowedPrefixes) {
        return FuryCodecSupport.newFury(FuryUdpClientCodec.class, allowedPrefixes,
                fury -> FuryCodecSupport.registerDateTime(fury, (short) (REGISTER_BASE_ID + FuryCodecSupport.DATE_TIME_REGISTER_ID_OFFSET)));
    }

    org.apache.fury.memory.MemoryBuffer toMemoryBuffer(ByteBuf payload, int index, int payloadLength) {
        return FuryCodecSupport.toMemoryBuffer(payload, index, payloadLength);
    }
}
