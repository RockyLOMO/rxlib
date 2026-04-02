package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.rx.net.socks.RrpConfig.ATTR_SVR;

class RrpValidationTest {
    @Test
    void serverRejectsOversizedTokenLenAndReleases() {
        RrpConfig conf = new RrpConfig();
        conf.setToken("t");
        conf.setBindPort(0);
        RrpServer server = new RrpServer(conf);
        try {
            EmbeddedChannel ch = new EmbeddedChannel();
            ch.attr(ATTR_SVR).set(server);
            ch.pipeline().addLast(RrpServer.ServerHandler.DEFAULT);
            ch.pipeline().fireChannelActive();

            ByteBuf buf = Unpooled.buffer();
            buf.writeByte(RrpConfig.ACTION_REGISTER);
            buf.writeInt(RrpServer.MAX_TOKEN_LEN + 1);
            // token bytes omitted on purpose

            ch.writeInbound(buf);
            assertEquals(0, buf.refCnt(), "msg should be released by handler");

            ch.runPendingTasks();
            assertFalse(ch.isOpen(), "channel should be closed on invalid tokenLen");
        } finally {
            server.close();
        }
    }

    @Test
    void serverRejectsOversizedRegisterPayloadLenAndReleases() {
        RrpConfig conf = new RrpConfig();
        conf.setToken("t");
        conf.setBindPort(0);
        RrpServer server = new RrpServer(conf);
        try {
            EmbeddedChannel ch = new EmbeddedChannel();
            ch.attr(ATTR_SVR).set(server);
            ch.pipeline().addLast(RrpServer.ServerHandler.DEFAULT);
            ch.pipeline().fireChannelActive();

            byte[] token = "t".getBytes(StandardCharsets.US_ASCII);
            ByteBuf buf = Unpooled.buffer();
            buf.writeByte(RrpConfig.ACTION_REGISTER);
            buf.writeInt(token.length);
            buf.writeBytes(token);
            buf.writeInt(RrpServer.MAX_REGISTER_BYTES + 1);
            // payload omitted on purpose

            ch.writeInbound(buf);
            assertEquals(0, buf.refCnt(), "msg should be released by handler");

            ch.runPendingTasks();
            assertFalse(ch.isOpen(), "channel should be closed on invalid register len");
        } finally {
            server.close();
        }
    }

    @Test
    void clientRejectsInvalidIdLenAndReleases() {
        RrpConfig conf = new RrpConfig();
        conf.setProxies(Collections.emptyList());
        RrpClient client = new RrpClient(conf);
        RrpClient.ClientHandler h = client.new ClientHandler();

        EmbeddedChannel ch = new EmbeddedChannel(h);
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(RrpConfig.ACTION_FORWARD);
        buf.writeInt(2090);
        buf.writeInt(RrpServer.MAX_CHANNEL_ID_LEN + 1);
        // id bytes omitted

        ch.writeInbound(buf);
        assertEquals(0, buf.refCnt(), "msg should be released by handler");

        ch.runPendingTasks();
        assertFalse(ch.isOpen(), "server channel should be closed on invalid idLen");
    }

    @Test
    void clientIgnoresUnknownRemotePortButStillReleases() {
        RrpConfig conf = new RrpConfig();
        conf.setProxies(Collections.emptyList());
        RrpClient client = new RrpClient(conf);
        RrpClient.ClientHandler h = client.new ClientHandler();

        EmbeddedChannel ch = new EmbeddedChannel(h);
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(RrpConfig.ACTION_FORWARD);
        buf.writeInt(2090);
        byte[] id = "abc".getBytes(StandardCharsets.US_ASCII);
        buf.writeInt(id.length);
        buf.writeBytes(id);
        buf.writeBytes(new byte[]{1, 2, 3}); // payload

        ch.writeInbound(buf);
        assertEquals(0, buf.refCnt(), "msg should be released by handler");
        assertTrue(ch.isOpen(), "channel should remain open for unknown remotePort");
    }
}

