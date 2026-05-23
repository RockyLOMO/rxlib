package org.rx.net.udp;

import io.netty.buffer.ByteBuf;

/**
 * UDP Resilience 线协议头。
 */
public final class UdpResilienceHeader {
    public static final int MAGIC = 0x5258; // "RX"
    public static final short VERSION = 1;
    public static final int HEADER_SIZE = 25;

    public static final int FLAG_DATA = 0x01;
    public static final int FLAG_PARITY = 0x02;
    public static final int FLAG_REDUNDANT = 0x04;

    public static final int CODEC_NONE = 0;
    public static final int CODEC_XOR = 1;

    private UdpResilienceHeader() {
    }

    static void write(ByteBuf out, int flags, int codec, int shardK, int shardP, int shardIdx,
                      int sessionId, int seq, int groupId, int payloadLen) {
        out.writeShort(MAGIC);
        out.writeByte(VERSION);
        out.writeByte(flags);
        out.writeByte(HEADER_SIZE);
        out.writeByte(codec);
        out.writeByte(shardK);
        out.writeByte(shardP);
        out.writeByte(shardIdx);
        out.writeInt(sessionId);
        out.writeInt(seq);
        out.writeInt(groupId);
        out.writeShort(payloadLen);
        out.writeShort(0);
    }

    public static boolean isResilienceFrame(ByteBuf in) {
        int readerIndex = in.readerIndex();
        return in.readableBytes() >= 2 && in.getUnsignedShort(readerIndex) == MAGIC;
    }

    static int version(ByteBuf in, int index) {
        return in.getUnsignedByte(index + 2);
    }

    static int flags(ByteBuf in, int index) {
        return in.getUnsignedByte(index + 3);
    }

    static int headerLength(ByteBuf in, int index) {
        return in.getUnsignedByte(index + 4);
    }

    static int codec(ByteBuf in, int index) {
        return in.getUnsignedByte(index + 5);
    }

    static int shardK(ByteBuf in, int index) {
        return in.getUnsignedByte(index + 6);
    }

    static int shardP(ByteBuf in, int index) {
        return in.getUnsignedByte(index + 7);
    }

    static int shardIdx(ByteBuf in, int index) {
        return in.getUnsignedByte(index + 8);
    }

    static int sessionId(ByteBuf in, int index) {
        return in.getInt(index + 9);
    }

    static int seq(ByteBuf in, int index) {
        return in.getInt(index + 13);
    }

    static int groupId(ByteBuf in, int index) {
        return in.getInt(index + 17);
    }

    static int payloadLen(ByteBuf in, int index) {
        return in.getUnsignedShort(index + 21);
    }
}
