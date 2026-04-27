package org.rx.net.transport;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.io.Serializable;

public interface UdpClientCodec extends Serializable {
    ByteBuf encode(ByteBufAllocator allocator, Object packet) throws Exception;

    Object decode(ByteBuf payload) throws Exception;
}
