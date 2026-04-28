package org.rx.net.dns;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.dns.DnsRecordType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DoHClientIntegrationTest {
    static int freePort() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    @Test
    @Timeout(20)
    void sameTcpPort_servesDnsOverTcpAndPlainDoh() throws Exception {
        int port = freePort();
        String host = "doh-it.example";
        InetAddress ip = InetAddress.getByName("127.0.0.9");
        DnsServer server = new DnsServer(port, Collections.emptyList());
        try {
            DnsDoHConfig config = new DnsDoHConfig();
            config.setAllowPlainHttp(true);
            server.enableDoH(config);
            server.addHosts(host, 1, Collections.singleton(ip));
            Thread.sleep(300);

            assertEquals(ip, dnsOverTcp(port, host));

            DoHEndpoint endpoint = new DoHEndpoint(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), null, "/dns-query");
            try (DoHClient client = new DoHClient(Collections.singletonList(endpoint))) {
                List<InetAddress> result = client.resolveHost(null, host);
                assertTrue(result.contains(ip));
            }
        } finally {
            server.close();
        }
    }

    @Test
    @Timeout(20)
    void plainDohFallbackUpstream_preservesQueryId() throws Exception {
        int dohPort = freePort();
        String host = "doh-upstream-" + UUID.randomUUID() + ".example";
        InetAddress ip = InetAddress.getByName("127.0.0.19");
        try (RawDnsServer upstream = new RawDnsServer(ip)) {
            DnsServer server = new DnsServer(dohPort,
                    Collections.singletonList(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), upstream.port())));
            DnsDoHConfig config = new DnsDoHConfig();
            config.setAllowPlainHttp(true);
            server.enableDoH(config);
            try {
                Thread.sleep(300);

                DoHEndpoint endpoint = new DoHEndpoint(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), dohPort), null, "/dns-query");
                try (DoHClient client = new DoHClient(Collections.singletonList(endpoint))) {
                    List<InetAddress> result = client.resolveHost(null, host);

                    assertTrue(result.contains(ip));
                }
            } finally {
                server.close();
            }
        }
    }

    private InetAddress dnsOverTcp(int port, String host) throws Exception {
        ByteBuf query = Unpooled.buffer();
        DoHMessageCodec.encodeQuery(query, 22, host, DnsRecordType.A);
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeShort(query.readableBytes());
            byte[] q = new byte[query.readableBytes()];
            query.readBytes(q);
            out.write(q);
            out.flush();

            DataInputStream in = new DataInputStream(socket.getInputStream());
            int len = in.readUnsignedShort();
            byte[] payload = new byte[len];
            in.readFully(payload);
            ByteBuf response = Unpooled.wrappedBuffer(payload);
            List<InetAddress> addresses = DoHMessageCodec.decodeAddresses(response, 22);
            response.release();
            assertFalse(addresses.isEmpty());
            return addresses.get(0);
        } finally {
            query.release();
        }
    }

    static final class RawDnsServer implements AutoCloseable {
        final DatagramSocket socket;
        final Thread worker;
        final byte[] address;
        volatile boolean closed;

        RawDnsServer(InetAddress address) throws Exception {
            this.address = address.getAddress();
            socket = new DatagramSocket(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            worker = new Thread(this::run, "raw-dns-test");
            worker.setDaemon(true);
            worker.start();
        }

        int port() {
            return socket.getLocalPort();
        }

        void run() {
            byte[] buf = new byte[512];
            while (!closed) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(packet);
                    byte[] response = response(packet.getData(), packet.getLength());
                    if (response != null) {
                        socket.send(new DatagramPacket(response, response.length, packet.getSocketAddress()));
                    }
                } catch (IOException e) {
                    if (!closed) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }

        byte[] response(byte[] request, int len) throws IOException {
            if (len < 16) {
                return null;
            }
            int index = 12;
            while (index < len && request[index] != 0) {
                index += (request[index] & 0xff) + 1;
            }
            if (index + 4 > len) {
                return null;
            }
            index++;
            int type = ((request[index] & 0xff) << 8) | (request[index + 1] & 0xff);
            int questionEnd = index + 4;
            boolean answerA = type == DnsRecordType.A.intValue();

            ByteArrayOutputStream out = new ByteArrayOutputStream(64);
            DataOutputStream data = new DataOutputStream(out);
            data.writeByte(request[0]);
            data.writeByte(request[1]);
            data.writeShort(0x8180);
            data.writeShort(1);
            data.writeShort(answerA ? 1 : 0);
            data.writeShort(0);
            data.writeShort(0);
            data.write(request, 12, questionEnd - 12);
            if (answerA) {
                data.writeShort(0xc00c);
                data.writeShort(DnsRecordType.A.intValue());
                data.writeShort(1);
                data.writeInt(30);
                data.writeShort(address.length);
                data.write(address);
            }
            data.flush();
            return out.toByteArray();
        }

        @Override
        public void close() {
            closed = true;
            socket.close();
        }
    }
}
