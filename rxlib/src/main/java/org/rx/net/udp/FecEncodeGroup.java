package org.rx.net.udp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

/**
 * 出站 XOR FEC 分组，缓存 length-prefixed block。
 */
final class FecEncodeGroup {
    final int groupId;
    private final ByteBuf[] blocks;
    private int count;
    private int maxBlockLen;

    FecEncodeGroup(int groupId, int dataShards) {
        this.groupId = groupId;
        this.blocks = new ByteBuf[dataShards];
    }

    int count() {
        return count;
    }

    boolean isFull() {
        return count == blocks.length;
    }

    int capacity() {
        return blocks.length;
    }

    int add(ByteBuf block) {
        int idx = count++;
        blocks[idx] = block;
        if (block.readableBytes() > maxBlockLen) {
            maxBlockLen = block.readableBytes();
        }
        return idx;
    }

    ByteBuf buildParity(ByteBufAllocator alloc) {
        ByteBuf parity = alloc.directBuffer(maxBlockLen);
        try {
            parity.writeZero(maxBlockLen);
            for (int i = 0; i < count; i++) {
                ByteBuf block = blocks[i];
                int readerIndex = block.readerIndex();
                int len = block.readableBytes();
                for (int j = 0; j < len; j++) {
                    parity.setByte(j, parity.getUnsignedByte(j) ^ block.getUnsignedByte(readerIndex + j));
                }
            }
            return parity;
        } catch (Throwable e) {
            parity.release();
            throw e;
        }
    }

    void release() {
        for (int i = 0; i < blocks.length; i++) {
            ByteBuf block = blocks[i];
            if (block != null) {
                block.release();
                blocks[i] = null;
            }
        }
        count = 0;
        maxBlockLen = 0;
    }
}
