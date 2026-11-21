package org.rx.net.socks;

import org.rx.net.Sockets;
import java.net.Inet6Address;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
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
    void commandAddressTypesAndLocalIpv6Scope() {
        assertEquals(Socks5AddressType.IPv4, UdpManager.addressType("192.0.2.1"));
        assertEquals(Socks5AddressType.IPv6, UdpManager.addressType("2001:db8::1"));
        assertEquals(Socks5AddressType.DOMAIN, UdpManager.addressType("proxy.example"));
        InetSocketAddress scoped = Sockets.parseEndpoint("[fe80::1%7]:1080");
        assertEquals(7, ((Inet6Address) scoped.getAddress()).getScopeId());
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

