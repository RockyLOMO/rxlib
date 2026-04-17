package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.DatagramPacket;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.*;

class UdpValidationTest {
    @Test
    void socksUdpRelayIgnoresTooShortPackets() {
        EmbeddedChannel ch = new EmbeddedChannel(SocksUdpRelayHandler.DEFAULT);
        ByteBuf buf = Unpooled.wrappedBuffer(new byte[]{0x00}); // < 4 bytes
        DatagramPacket pkt = new DatagramPacket(buf, new InetSocketAddress("127.0.0.1", 9999));

        assertDoesNotThrow(() -> ch.writeInbound(pkt));
    }

    @Test
    void udpManagerDecodeThrowsOnTooShortPacket() {
        ByteBuf buf = Unpooled.wrappedBuffer(new byte[]{0x00, 0x00, 0x00}); // < 4 bytes
        assertThrows(IllegalArgumentException.class, () -> UdpManager.socks5Decode(buf));
    }

    @Test
    void udpManagerRejectsUnsupportedAddressTypeZero() {
        ByteBuf buf = Unpooled.wrappedBuffer(new byte[]{
                0x00, 0x00, 0x00, 0x00,
                0x7F, 0x00, 0x00, 0x01,
                0x00, 0x35
        });
        assertFalse(UdpManager.isValidSocks5UdpPacket(buf));
        assertThrows(IllegalArgumentException.class, () -> UdpManager.socks5Decode(buf));
    }
}

