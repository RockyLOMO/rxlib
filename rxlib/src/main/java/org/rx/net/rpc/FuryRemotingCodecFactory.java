package org.rx.net.rpc;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.util.concurrent.FastThreadLocal;
import lombok.NoArgsConstructor;
import org.apache.fury.Fury;
import org.apache.fury.memory.MemoryBuffer;
import org.rx.core.EventArgs;
import org.rx.core.NEventArgs;
import org.rx.exception.InvalidException;
import org.rx.net.FuryCodecSupport;
import org.rx.net.rpc.protocol.EventFlag;
import org.rx.net.rpc.protocol.EventMessage;
import org.rx.net.rpc.protocol.MetadataMessage;
import org.rx.net.rpc.protocol.MethodMessage;
import org.rx.net.transport.UdpClientCodec;
import org.rx.net.transport.protocol.ErrorPacket;
import org.rx.net.transport.protocol.PingPacket;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@NoArgsConstructor
public class FuryRemotingCodecFactory implements RemotingCodecFactory {
    private static final long serialVersionUID = 5637987785585559036L;
    static final short FRAME_MAGIC = FuryCodecSupport.FRAME_MAGIC;
    static final byte FRAME_VERSION = FuryCodecSupport.FRAME_VERSION;
    static final byte CODEC_ID_FURY = FuryCodecSupport.CODEC_ID_FURY;
    static final short REGISTER_BASE_ID = 3000;

    public static FuryRemotingCodecFactory createDefault() {
        return new FuryRemotingCodecFactory();
    }

    final Set<String> allowedClassPrefixes = FuryCodecSupport.defaultAllowedClassPrefixes();

    public FuryRemotingCodecFactory allowPrefix(String prefix) {
        if (prefix != null && !prefix.isEmpty()) {
            allowedClassPrefixes.add(prefix);
        }
        return this;
    }

    public FuryRemotingCodecFactory allowClass(Class<?> type) {
        if (type != null) {
            allowedClassPrefixes.add(type.getName());
        }
        return this;
    }

    @Override
    public UdpClientCodec newCodec() {
        return new FuryRemotingUdpCodec(new ArrayList<String>(allowedClassPrefixes));
    }

    static final class FuryRemotingSupport implements Serializable {
        private static final long serialVersionUID = -3042962101241652126L;
        final List<String> allowedPrefixes;

        FuryRemotingSupport(List<String> allowedPrefixes) {
            this.allowedPrefixes = allowedPrefixes;
        }

        Fury newFury() {
            return FuryCodecSupport.newFury(FuryRemotingCodecFactory.class, allowedPrefixes, this::registerTypes);
        }

        void registerTypes(Fury fury) {
            fury.register(PingPacket.class, (short) (REGISTER_BASE_ID + 1));
            fury.register(ErrorPacket.class, (short) (REGISTER_BASE_ID + 2));
            fury.register(MetadataMessage.class, (short) (REGISTER_BASE_ID + 3));
            fury.register(MethodMessage.class, (short) (REGISTER_BASE_ID + 4));
            fury.register(EventMessage.class, (short) (REGISTER_BASE_ID + 5));
            fury.register(EventFlag.class, (short) (REGISTER_BASE_ID + 6));
            fury.register(EventArgs.class, (short) (REGISTER_BASE_ID + 7));
            fury.register(NEventArgs.class, (short) (REGISTER_BASE_ID + 8));
            fury.register(RemotingEventArgs.class, (short) (REGISTER_BASE_ID + 9));
            FuryCodecSupport.registerDateTime(fury, (short) (REGISTER_BASE_ID + 10));
        }
    }

    static abstract class FuryHandlerSupport {
        final FuryRemotingSupport support;
        final FastThreadLocal<Fury> furyLocal = new FastThreadLocal<Fury>() {
            @Override
            protected Fury initialValue() {
                return support.newFury();
            }
        };

        FuryHandlerSupport(FuryRemotingSupport support) {
            this.support = support;
        }
    }

    static final class FuryRemotingUdpCodec implements UdpClientCodec {
        private static final long serialVersionUID = 2863199051413105376L;
        final List<String> allowedPrefixes;
        transient volatile FastThreadLocal<Fury> furyLocal;

        FuryRemotingUdpCodec(List<String> allowedPrefixes) {
            this.allowedPrefixes = allowedPrefixes;
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
        public Object decode(ByteBuf payload) {
            if (payload.readableBytes() < 8) {
                throw new InvalidException("Fury frame too short {}", payload.readableBytes());
            }

            short magic = payload.readShort();
            int version = payload.readUnsignedByte();
            int codecId = payload.readUnsignedByte();
            int payloadLength = payload.readInt();
            if (magic != FRAME_MAGIC) {
                throw new InvalidException("Fury frame magic mismatch {}", Integer.toHexString(magic & 0xFFFF));
            }
            if (version != FRAME_VERSION) {
                throw new InvalidException("Fury frame version mismatch {}", version);
            }
            if (codecId != CODEC_ID_FURY) {
                throw new InvalidException("Fury frame codec mismatch {}", codecId);
            }
            if (payloadLength < 0 || payloadLength != payload.readableBytes()) {
                throw new InvalidException("Fury frame payload length mismatch payload={} actual={}", payloadLength, payload.readableBytes());
            }

            Fury fury = fury();
            try {
                return fury.deserializeJavaObjectAndClass(toMemoryBuffer(payload, payloadLength));
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
                    final FuryRemotingSupport support = new FuryRemotingSupport(allowedPrefixes);
                    local = new FastThreadLocal<Fury>() {
                        @Override
                        protected Fury initialValue() {
                            return support.newFury();
                        }
                    };
                    furyLocal = local;
                }
            }
            return local.get();
        }

        MemoryBuffer toMemoryBuffer(ByteBuf frame, int payloadLength) {
            return FuryCodecSupport.toMemoryBuffer(frame, frame.readerIndex(), payloadLength);
        }
    }

    static final class FuryMessageEncoder extends MessageToByteEncoder<Object> {
        final FuryHandlerSupport handlerSupport;

        FuryMessageEncoder(FuryRemotingSupport support) {
            handlerSupport = new FuryHandlerSupport(support) {
            };
        }

        @Override
        protected void encode(ChannelHandlerContext ctx, Object msg, ByteBuf out) throws Exception {
            out.writeShort(FRAME_MAGIC);
            out.writeByte(FRAME_VERSION);
            out.writeByte(CODEC_ID_FURY);
            int lengthIndex = out.writerIndex();
            out.writeInt(0);
            int payloadStart = out.writerIndex();
            Fury fury = handlerSupport.furyLocal.get();
            try {
                try (ByteBufOutputStream stream = new ByteBufOutputStream(out)) {
                    fury.serializeJavaObjectAndClass(stream, msg);
                }
            } finally {
                fury.reset();
            }
            out.setInt(lengthIndex, out.writerIndex() - payloadStart);
        }
    }

    static final class FuryMessageDecoder extends MessageToMessageDecoder<ByteBuf> {
        final FuryHandlerSupport handlerSupport;

        FuryMessageDecoder(FuryRemotingSupport support) {
            handlerSupport = new FuryHandlerSupport(support) {
            };
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf frame, List<Object> out) {
            if (frame.readableBytes() < 8) {
                throw new InvalidException("Fury frame too short {}", frame.readableBytes());
            }

            short magic = frame.readShort();
            int version = frame.readUnsignedByte();
            int codecId = frame.readUnsignedByte();
            int payloadLength = frame.readInt();
            if (magic != FRAME_MAGIC) {
                throw new InvalidException("Fury frame magic mismatch {}", Integer.toHexString(magic & 0xFFFF));
            }
            if (version != FRAME_VERSION) {
                throw new InvalidException("Fury frame version mismatch {}", version);
            }
            if (codecId != CODEC_ID_FURY) {
                throw new InvalidException("Fury frame codec mismatch {}", codecId);
            }
            if (payloadLength < 0 || payloadLength != frame.readableBytes()) {
                throw new InvalidException("Fury frame payload length mismatch payload={} actual={}", payloadLength, frame.readableBytes());
            }

            Fury fury = handlerSupport.furyLocal.get();
            try {
                out.add(fury.deserializeJavaObjectAndClass(toMemoryBuffer(frame, payloadLength)));
            } finally {
                fury.reset();
                frame.skipBytes(payloadLength);
            }
        }

        MemoryBuffer toMemoryBuffer(ByteBuf frame, int payloadLength) {
            return FuryCodecSupport.toMemoryBuffer(frame, frame.readerIndex(), payloadLength);
        }
    }
}
