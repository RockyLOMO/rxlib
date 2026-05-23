package org.rx.net.udp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

/**
 * 入站 XOR FEC 分组。DATA 立即透传，本对象只缓存恢复所需 block。
 */
final class FecDecodeGroup {
    final FecGroupKey key;
    final long createTimeNanos = System.nanoTime();
    private final ByteBuf[] dataBlocks;
    private final boolean[] received;
    private int effectiveShardK;
    private int receivedCount;
    private ByteBuf parityBlock;

    FecDecodeGroup(FecGroupKey key, int maxDataShards, int shardK) {
        this.key = key;
        this.dataBlocks = new ByteBuf[maxDataShards];
        this.received = new boolean[maxDataShards];
        this.effectiveShardK = Math.max(1, Math.min(maxDataShards, shardK));
    }

    boolean addData(int shardIdx, ByteBuf block) {
        if (shardIdx < 0 || shardIdx >= effectiveShardK || shardIdx >= dataBlocks.length || received[shardIdx]) {
            block.release();
            return false;
        }
        dataBlocks[shardIdx] = block;
        received[shardIdx] = true;
        receivedCount++;
        return true;
    }

    boolean addParity(ByteBuf block, int shardK) {
        int nextShardK = Math.max(1, Math.min(dataBlocks.length, shardK));
        for (int i = nextShardK; i < dataBlocks.length; i++) {
            if (received[i]) {
                block.release();
                return false;
            }
        }
        effectiveShardK = nextShardK;
        if (parityBlock != null) {
            block.release();
            return false;
        }
        parityBlock = block;
        return true;
    }

    boolean isComplete() {
        return receivedCount >= effectiveShardK;
    }

    ByteBuf tryRecover(ByteBufAllocator alloc, int maxProtectedPayload) {
        if (parityBlock == null || missingDataCount() != 1) {
            return null;
        }

        int parityReader = parityBlock.readerIndex();
        int parityLen = parityBlock.readableBytes();
        ByteBuf recovered = alloc.directBuffer(parityLen);
        try {
            recovered.writeBytes(parityBlock, parityReader, parityLen);
            for (int i = 0; i < effectiveShardK; i++) {
                if (!received[i]) {
                    continue;
                }
                ByteBuf block = dataBlocks[i];
                int readerIndex = block.readerIndex();
                int len = block.readableBytes();
                for (int j = 0; j < len; j++) {
                    recovered.setByte(j, recovered.getUnsignedByte(j) ^ block.getUnsignedByte(readerIndex + j));
                }
            }
            if (recovered.readableBytes() < 2) {
                recovered.release();
                return null;
            }
            int originalLen = recovered.getUnsignedShort(recovered.readerIndex());
            if (originalLen > maxProtectedPayload || originalLen > recovered.readableBytes() - 2) {
                recovered.release();
                return null;
            }
            ByteBuf payload = recovered.retainedSlice(recovered.readerIndex() + 2, originalLen);
            recovered.release();
            return payload;
        } catch (Throwable e) {
            recovered.release();
            throw e;
        }
    }

    void release() {
        for (int i = 0; i < dataBlocks.length; i++) {
            ByteBuf block = dataBlocks[i];
            if (block != null) {
                block.release();
                dataBlocks[i] = null;
            }
        }
        if (parityBlock != null) {
            parityBlock.release();
            parityBlock = null;
        }
    }

    private int missingDataCount() {
        int missing = 0;
        for (int i = 0; i < effectiveShardK; i++) {
            if (!received[i]) {
                missing++;
            }
        }
        return missing;
    }
}
