package org.rx.net.dns;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.dns.DnsRecordType;
import org.rx.core.RxConfig;

import java.io.*;
import java.net.*;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;

/** Preserves compressed RDATA pointers for non-address records. Called on DNS/RPC workers only. */
public final class DnsRecordExchange {
    private static final Semaphore SLOTS = new Semaphore(32);
    private static final int TIMEOUT = 2000;

    private DnsRecordExchange() { }

    public static List<InetSocketAddress> directResolvers() {
        return DnsClient.parseNameServers(RxConfig.INSTANCE.getNet().getDns().getDirectServers());
    }

    public static byte[] query(List<InetSocketAddress> servers, String domain, int type, boolean tcp) throws IOException {
        if (domain == null || domain.length() > 254 || type < 1 || type >= 249 && type <= 255 || type > 65535) {
            throw new IOException("Unsupported DNS question");
        }
        if (!SLOTS.tryAcquire()) throw new IOException("DNS query capacity reached");
        try {
            int id = ThreadLocalRandom.current().nextInt(65536);
            ByteBuf query = Unpooled.buffer(256, 512);
            byte[] request;
            try {
                DoHMessageCodec.encodeQuery(query, id, domain, DnsRecordType.valueOf(type));
                request = ByteBufUtil.getBytes(query);
            } finally {
                query.release();
            }
            for (InetSocketAddress server : servers) {
                if (server.isUnresolved()) continue;
                byte[] response = tcp ? exchangeTcp(server, request) : exchangeUdp(server, request);
                if (response.length < 12 || (response[2] & 0xf8) != 0x80
                        || ((response[0] & 255) << 8 | response[1] & 255) != id) {
                    throw new IOException("Invalid recursive DNS response");
                }
                return response;
            }
            throw new IOException("No configured DNS resolver");
        } finally {
            SLOTS.release();
        }
    }

    private static byte[] exchangeTcp(InetSocketAddress server, byte[] request) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(server, TIMEOUT);
            socket.setSoTimeout(TIMEOUT);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeShort(request.length);
            out.write(request);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            byte[] response = new byte[in.readUnsignedShort()];
            in.readFully(response);
            return response;
        }
    }

    private static byte[] exchangeUdp(InetSocketAddress server, byte[] request) throws IOException {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(server);
            socket.setSoTimeout(TIMEOUT);
            socket.send(new DatagramPacket(request, request.length, server));
            // The normalized question has no EDNS OPT. The recursive server may set TC.
            DatagramPacket packet = new DatagramPacket(new byte[513], 513);
            socket.receive(packet);
            if (packet.getLength() > 512) throw new IOException("Oversized DNS reply");
            return java.util.Arrays.copyOf(packet.getData(), packet.getLength());
        }
    }
}
