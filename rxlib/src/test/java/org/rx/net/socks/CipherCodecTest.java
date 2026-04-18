package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;
import org.rx.io.Bytes;
import org.rx.net.socks.encryption.ICrypto;

import java.util.AbstractList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CipherCodecTest {
    @Test
    void decode_releasesDecryptedBufferWhenOwnershipTransferFails() {
        CipherCodec codec = new CipherCodec();
        EmbeddedChannel channel = new EmbeddedChannel(codec);
        ByteBuf in = Bytes.directBuffer(8).writeLong(1L);
        TrackingCrypto crypto = new TrackingCrypto();
        channel.attr(ShadowsocksConfig.CIPHER).set(crypto);

        ChannelHandlerContext ctx = channel.pipeline().context(codec);
        List<Object> out = new AbstractList<Object>() {
            @Override
            public Object get(int index) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int size() {
                return 0;
            }

            @Override
            public void add(int index, Object element) {
                throw new IllegalStateException("inject add failure");
            }
        };

        try {
            assertThrows(IllegalStateException.class, () -> codec.decode(ctx, in, out));
            assertEquals(0, crypto.out.refCnt());
        } finally {
            ReferenceCountUtil.release(in);
            channel.finishAndReleaseAll();
        }
    }

    private static class TrackingCrypto implements ICrypto {
        ByteBuf out;

        @Override
        public void setForUdp(boolean forUdp) {
        }

        @Override
        public ByteBuf encrypt(ByteBuf in) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ByteBuf decrypt(ByteBuf in) {
            out = Bytes.directBuffer(16);
            out.writeLong(2L);
            return out;
        }
    }
}
