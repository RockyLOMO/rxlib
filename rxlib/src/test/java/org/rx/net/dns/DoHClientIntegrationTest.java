package org.rx.net.dns;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.dns.DnsRecordType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.List;

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
}
