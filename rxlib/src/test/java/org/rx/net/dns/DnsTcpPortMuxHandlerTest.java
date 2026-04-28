package org.rx.net.dns;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.dns.TcpDnsQueryDecoder;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.SslHandler;
import org.junit.jupiter.api.Test;
import org.rx.net.Sockets;

import java.net.ServerSocket;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class DnsTcpPortMuxHandlerTest {
    static int freePort() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    @Test
    void dnsTcpFirstPacket_installsDnsPipeline() throws Exception {
            DnsServer server = new DnsServer(freePort(), Collections.emptyList());
        try {
            EmbeddedChannel channel = new EmbeddedChannel(new DnsTcpPortMuxHandler(server));
            ByteBuf packet = Unpooled.buffer();
            packet.writeShort(32);

            channel.writeInbound(packet);

            assertNotNull(channel.pipeline().get(TcpDnsQueryDecoder.class));
            assertNull(channel.pipeline().get(DnsTcpPortMuxHandler.class));
            channel.finishAndReleaseAll();
        } finally {
            server.close();
        }
    }

    @Test
    void plainHttpFirstPacket_installsHttpPipelineWhenAllowed() throws Exception {
        DnsServer server = new DnsServer(freePort(), Collections.emptyList());
        try {
            DnsDoHConfig config = new DnsDoHConfig();
            config.setAllowPlainHttp(true);
            server.enableDoH(config);
            EmbeddedChannel channel = new EmbeddedChannel(new DnsTcpPortMuxHandler(server));

            channel.writeInbound(Unpooled.copiedBuffer(new byte[]{'P', 'O', 'S', 'T', ' '}));

            assertNotNull(channel.pipeline().get(HttpServerCodec.class));
            assertNull(channel.pipeline().get(DnsTcpPortMuxHandler.class));
            channel.finishAndReleaseAll();
        } finally {
            server.close();
        }
    }

    @Test
    void tlsClientHelloFirstPacket_installsSslPipeline() throws Exception {
        DnsServer server = new DnsServer(freePort(), Collections.emptyList());
        try {
            DnsDoHConfig config = new DnsDoHConfig();
            config.setSslContext(Sockets.getSelfSignedTls());
            server.enableDoH(config);
            EmbeddedChannel channel = new EmbeddedChannel(new DnsTcpPortMuxHandler(server));

            channel.writeInbound(Unpooled.copiedBuffer(new byte[]{0x16, 0x03, 0x01}));

            assertNotNull(channel.pipeline().get(SslHandler.class));
            assertNull(channel.pipeline().get(DnsTcpPortMuxHandler.class));
            channel.finishAndReleaseAll();
        } finally {
            server.close();
        }
    }
}
