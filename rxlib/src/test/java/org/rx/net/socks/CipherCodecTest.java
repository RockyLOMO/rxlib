package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.junit.jupiter.api.Test;
import org.rx.io.Bytes;
import org.rx.net.socks.encryption.ICrypto;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CipherCodecTest {
    @Test
    void decode_invalidCipherTextWithoutRootCauseDoesNotPropagate() throws Exception {
        CipherCodec codec = new CipherCodec();
        EmbeddedChannel channel = new EmbeddedChannel(codec);
        ByteBuf in = Bytes.directBuffer(8).writeLong(1L);
        channel.attr(ShadowsocksConfig.CIPHER).set(new InvalidCipherCrypto());

        ChannelHandlerContext ctx = channel.pipeline().context(codec);
        List<Object> out = new ArrayList<>();
        try {
            codec.decode(ctx, in, out);
            channel.runPendingTasks();

            assertEquals(0, out.size());
        } finally {
            ReferenceCountUtil.release(in);
            channel.finishAndReleaseAll();
        }
    }

    private static class InvalidCipherCrypto implements ICrypto {
        @Override
        public void setForUdp(boolean forUdp) {
        }

        @Override
        public ByteBuf encrypt(ByteBuf in) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ByteBuf decrypt(ByteBuf in) {
            throw new IllegalStateException(new InvalidCipherTextException("synthetic invalid cipher text"));
        }
    }
}
