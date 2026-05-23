package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import org.rx.io.Bytes;
import java.net.InetSocketAddress;

import java.net.InetSocketAddress;

public final class Udp2rawCodec {
    public static final short MAGIC = (short) 0x5532; // U2
    public static final int VERSION = 1;
    public static final int FIXED_HEADER_LENGTH = 39;

    public static final int FLAG_NEW_CONN = 1;
    public static final int FLAG_HAS_CLIENT = 1 << 1;
    public static final int FLAG_HAS_DST = 1 << 2;
    public static final int FLAG_HAS_SRC = 1 << 3;
    public static final int FLAG_COMPRESSED = 1 << 4;
    public static final int FLAG_REDUNDANT = 1 << 5;
    public static final int FLAG_CLOSE_CONN = 1 << 6;
    public static final int FLAG_AUTH_TAG = 1 << 7;

    private Udp2rawCodec() {
    }

    public static Udp2rawFrame decode(ByteBuf in) {
        int start = in.readerIndex();
        if (in.readableBytes() < FIXED_HEADER_LENGTH) {
            throw new IllegalArgumentException("udp2raw frame too short");
        }
        if (in.readShort() != MAGIC) {
            throw new IllegalArgumentException("bad udp2raw magic");
        }
        int version = in.readUnsignedByte();
        int headerLength = in.readUnsignedByte();
        if (version != VERSION) {
            throw new IllegalArgumentException("bad udp2raw version " + version);
        }
        if (headerLength < FIXED_HEADER_LENGTH || in.readableBytes() < headerLength - 4) {
            throw new IllegalArgumentException("bad udp2raw header length " + headerLength);
        }

        int headerEnd = start + headerLength;
        Udp2rawFrame frame = new Udp2rawFrame();
        frame.setVersion(version);
        frame.setFlags(in.readUnsignedShort());
        frame.setType(Udp2rawFrameType.fromCode(in.readUnsignedByte()));
        frame.setSessionHi(in.readLong());
        frame.setSessionLo(in.readLong());
        frame.setConnId(in.readLong());
        frame.setPacketSeq(in.readLong());
        int flags = frame.getFlags();
        if ((flags & FLAG_HAS_CLIENT) != 0) {
            frame.setClientSource(decodeAddress(in));
        }
        if ((flags & FLAG_HAS_DST) != 0) {
            frame.setDestination(UdpManager.decode(in));
        }
        if ((flags & FLAG_HAS_SRC) != 0) {
            frame.setSourceAddress(decodeAddress(in));
        }
        if ((flags & FLAG_AUTH_TAG) != 0) {
            if (in.readerIndex() >= headerEnd) {
                throw new IllegalArgumentException("missing udp2raw auth tag length");
            }
            int authLen = in.readUnsignedByte();
            if (authLen <= 0 || in.readerIndex() + authLen > headerEnd) {
                throw new IllegalArgumentException("bad udp2raw auth tag length " + authLen);
            }
            frame.setAuthTag(in.readSlice(authLen));
        }
        if (in.readerIndex() != headerEnd) {
            throw new IllegalArgumentException("udp2raw header length mismatch");
        }
        return frame;
    }

    public static ByteBuf encode(ByteBufAllocator alloc, Udp2rawFrame frame, ByteBuf payload) {
        if (frame == null) {
            throw new IllegalArgumentException("frame must not be null");
        }
        ByteBuf header = alloc.directBuffer(128);
        CompositeByteBuf out = alloc.compositeDirectBuffer(payload != null && payload.isReadable() ? 2 : 1);
        try {
            int start = header.writerIndex();
            int flags = frame.getFlags();
            header.writeShort(MAGIC);
            header.writeByte(frame.getVersion());
            header.writeByte(0);
            header.writeShort(flags);
            header.writeByte(frame.getType().code());
            header.writeLong(frame.getSessionHi());
            header.writeLong(frame.getSessionLo());
            header.writeLong(frame.getConnId());
            header.writeLong(frame.getPacketSeq());
            if ((flags & FLAG_HAS_CLIENT) != 0) {
                InetSocketAddress clientSource = frame.getClientSource();
                if (clientSource == null) {
                    throw new IllegalArgumentException("clientSource is required");
                }
                UdpManager.encode(header, clientSource);
            }
            if ((flags & FLAG_HAS_DST) != 0) {
                InetSocketAddress destination = frame.getDestination();
                if (destination == null) {
                    throw new IllegalArgumentException("destination is required");
                }
                UdpManager.encode(header, destination);
            }
            if ((flags & FLAG_HAS_SRC) != 0) {
                InetSocketAddress sourceAddress = frame.getSourceAddress();
                if (sourceAddress == null) {
                    throw new IllegalArgumentException("sourceAddress is required");
                }
                UdpManager.encode(header, sourceAddress);
            }
            if ((flags & FLAG_AUTH_TAG) != 0) {
                ByteBuf authTag = frame.getAuthTag();
                if (authTag == null || !authTag.isReadable() || authTag.readableBytes() > 255) {
                    throw new IllegalArgumentException("bad authTag length");
                }
                int authLen = authTag.readableBytes();
                header.writeByte(authLen);
                header.writeBytes(authTag, authTag.readerIndex(), authLen);
            }
            int headerLength = header.writerIndex() - start;
            if (headerLength > 255) {
                throw new IllegalArgumentException("udp2raw header too large " + headerLength);
            }
            header.setByte(start + 3, headerLength);
            out.addComponent(true, header);
            header = null;
            if (payload != null && payload.isReadable()) {
                out.addComponent(true, payload);
            }
            return out;
        } catch (Throwable e) {
            Bytes.release(header);
            Bytes.release(out);
            throw e;
        }
    }

    private static InetSocketAddress decodeAddress(ByteBuf in) {
        return UdpManager.decode(in);
    }
}
