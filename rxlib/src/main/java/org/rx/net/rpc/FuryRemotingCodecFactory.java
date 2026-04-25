package org.rx.net.rpc;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.util.concurrent.FastThreadLocal;
import lombok.NoArgsConstructor;
import org.apache.fury.Fury;
import org.apache.fury.config.CompatibleMode;
import org.apache.fury.config.Language;
import org.apache.fury.memory.MemoryBuffer;
import org.apache.fury.resolver.ClassChecker;
import org.apache.fury.resolver.ClassResolver;
import org.rx.core.Constants;
import org.rx.core.EventArgs;
import org.rx.core.NEventArgs;
import org.rx.exception.InvalidException;
import org.rx.net.Sockets;
import org.rx.net.rpc.protocol.EventFlag;
import org.rx.net.rpc.protocol.EventMessage;
import org.rx.net.rpc.protocol.MetadataMessage;
import org.rx.net.rpc.protocol.MethodMessage;
import org.rx.net.transport.TcpChannelCodec;
import org.rx.net.transport.protocol.ErrorPacket;
import org.rx.net.transport.protocol.PingPacket;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@NoArgsConstructor
public class FuryRemotingCodecFactory implements RemotingCodecFactory {
    private static final long serialVersionUID = 5637987785585559036L;
    static final short FRAME_MAGIC = (short) 0x5258;
    static final byte FRAME_VERSION = 1;
    static final byte CODEC_ID_FURY = 1;
    static final short REGISTER_BASE_ID = 3000;

    public static FuryRemotingCodecFactory createDefault() {
        return new FuryRemotingCodecFactory();
    }

    final Set<String> allowedClassPrefixes = new LinkedHashSet<String>() {
        private static final long serialVersionUID = -8912789182184590374L;

        {
            add("java.");
            add("javax.");
            add("org.rx.");
        }
    };

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
    public TcpChannelCodec newClientCodec(RpcClientConfig<?> config) {
        return new FuryTcpChannelCodec(new ArrayList<>(allowedClassPrefixes));
    }

    @Override
    public TcpChannelCodec newServerCodec(RpcServerConfig config) {
        return new FuryTcpChannelCodec(new ArrayList<>(allowedClassPrefixes));
    }

    static final class FuryTcpChannelCodec implements TcpChannelCodec {
        private static final long serialVersionUID = 6930188452926241924L;
        final List<String> allowedPrefixes;

        FuryTcpChannelCodec(List<String> allowedPrefixes) {
            this.allowedPrefixes = allowedPrefixes;
        }

        @Override
        public void install(ChannelPipeline pipeline) {
            FuryCodecSupport support = new FuryCodecSupport(allowedPrefixes);
            pipeline.addLast(Sockets.intLengthFieldDecoder(),
                    new FuryMessageDecoder(support),
                    Sockets.INT_LENGTH_FIELD_ENCODER,
                    new FuryMessageEncoder(support));
        }
    }

    static final class FuryCodecSupport implements Serializable {
        private static final long serialVersionUID = -3042962101241652126L;
        final List<String> allowedPrefixes;

        FuryCodecSupport(List<String> allowedPrefixes) {
            this.allowedPrefixes = allowedPrefixes;
        }

        Fury newFury() {
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            if (classLoader == null) {
                classLoader = FuryRemotingCodecFactory.class.getClassLoader();
            }
            Fury fury = Fury.builder()
                    .withLanguage(Language.JAVA)
                    .withClassLoader(classLoader)
                    .withRefTracking(true)
                    .withCompatibleMode(CompatibleMode.SCHEMA_CONSISTENT)
                    .withBufferSizeLimitBytes(Constants.MAX_HEAP_BUF_SIZE)
                    .withAsyncCompilation(true)
                    .requireClassRegistration(false)
                    .suppressClassRegistrationWarnings(true)
                    .build();
            fury.getClassResolver().setClassChecker(new PrefixClassChecker(allowedPrefixes));
            registerTypes(fury);
            return fury;
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
        }
    }

    static final class PrefixClassChecker implements ClassChecker, Serializable {
        private static final long serialVersionUID = -6206142736725292467L;
        final List<String> allowedPrefixes;

        PrefixClassChecker(List<String> allowedPrefixes) {
            this.allowedPrefixes = allowedPrefixes;
        }

        @Override
        public boolean checkClass(ClassResolver classResolver, String className) {
            return isAllowed(className);
        }

        boolean isAllowed(String className) {
            if (className == null || className.isEmpty()) {
                return false;
            }
            if (className.charAt(0) == '[') {
                int index = 0;
                while (index < className.length() && className.charAt(index) == '[') {
                    index++;
                }
                if (index >= className.length()) {
                    return false;
                }
                char type = className.charAt(index);
                if (type != 'L') {
                    return true;
                }
                int end = className.indexOf(';', index);
                className = end < 0 ? className.substring(index + 1) : className.substring(index + 1, end);
            }
            for (String prefix : allowedPrefixes) {
                if (className.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }
    }

    static abstract class FuryHandlerSupport {
        final FuryCodecSupport support;
        final FastThreadLocal<Fury> furyLocal = new FastThreadLocal<Fury>() {
            @Override
            protected Fury initialValue() {
                return support.newFury();
            }
        };

        FuryHandlerSupport(FuryCodecSupport support) {
            this.support = support;
        }
    }

    static final class FuryMessageEncoder extends MessageToByteEncoder<Object> {
        final FuryHandlerSupport handlerSupport;

        FuryMessageEncoder(FuryCodecSupport support) {
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

        FuryMessageDecoder(FuryCodecSupport support) {
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
            if (frame.nioBufferCount() == 1) {
                ByteBuffer buffer = frame.nioBuffer(frame.readerIndex(), payloadLength);
                return MemoryBuffer.fromByteBuffer(buffer);
            }
            byte[] bytes = ByteBufUtil.getBytes(frame, frame.readerIndex(), payloadLength, false);
            return MemoryBuffer.fromByteArray(bytes);
        }
    }
}
