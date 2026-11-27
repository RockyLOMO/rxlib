package org.rx.net.rpc;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.io.Serializable;

/** Encodes and decodes RPC protocol objects to/from raw Transport Core {@link ByteBuf} frames. */
public interface RemotingCodec extends Serializable {
    ByteBuf encode(ByteBufAllocator allocator, Object packet) throws Exception;

    Object decode(ByteBuf payload) throws Exception;
}
