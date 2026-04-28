package org.rx.net.dns;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.dns.*;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DoHMessageCodecTest {
    @Test
    void encodeDecodeQuery_supportsAAndAaaa() {
        ByteBuf buf = Unpooled.buffer();
        try {
            DoHMessageCodec.encodeQuery(buf, 7, "example.com", DnsRecordType.A);
            DefaultDnsQuery query = DoHMessageCodec.decodeQuery(buf);
            DefaultDnsQuestion question = query.recordAt(DnsSection.QUESTION);

            assertEquals(7, query.id());
            assertEquals("example.com.", question.name());
            assertEquals(DnsRecordType.A, question.type());
        } finally {
            buf.release();
        }
    }

    @Test
    void encodeDecodeResponse_filtersAAndAaaaAddresses() throws Exception {
        DefaultDnsResponse response = new DefaultDnsResponse(9);
        response.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion("example.com.", DnsRecordType.A));
        response.addRecord(DnsSection.ANSWER, new DefaultDnsRawRecord("example.com.", DnsRecordType.A, 60,
                Unpooled.wrappedBuffer(InetAddress.getByName("192.0.2.10").getAddress())));
        response.addRecord(DnsSection.ANSWER, new DefaultDnsRawRecord("example.com.", DnsRecordType.AAAA, 60,
                Unpooled.wrappedBuffer(InetAddress.getByName("2001:db8::1").getAddress())));

        ByteBuf buf = Unpooled.buffer();
        try {
            DoHMessageCodec.encodeResponse(buf, response);
            List<InetAddress> addresses = DoHMessageCodec.decodeAddresses(buf, 9);

            assertEquals(2, addresses.size());
            assertTrue(addresses.contains(InetAddress.getByName("192.0.2.10")));
            assertTrue(addresses.contains(InetAddress.getByName("2001:db8::1")));
        } finally {
            buf.release();
            response.release();
        }
    }

    @Test
    void decodeResponse_supportsNameCompressionPointer() throws Exception {
        ByteBuf buf = Unpooled.buffer();
        try {
            buf.writeShort(11);
            buf.writeShort(0x8180);
            buf.writeShort(1);
            buf.writeShort(1);
            buf.writeShort(0);
            buf.writeShort(0);
            DoHMessageCodec.writeName(buf, "example.com");
            buf.writeShort(DnsRecordType.A.intValue());
            buf.writeShort(DnsRecord.CLASS_IN);
            buf.writeShort(0xC00C);
            buf.writeShort(DnsRecordType.A.intValue());
            buf.writeShort(DnsRecord.CLASS_IN);
            buf.writeInt(60);
            buf.writeShort(4);
            buf.writeBytes(InetAddress.getByName("198.51.100.7").getAddress());

            List<InetAddress> addresses = DoHMessageCodec.decodeAddresses(buf, 11);

            assertEquals(1, addresses.size());
            assertEquals(InetAddress.getByName("198.51.100.7"), addresses.get(0));
        } finally {
            buf.release();
        }
    }

    @Test
    void decodeResponse_nxdomainReturnsEmptyList() {
        DefaultDnsResponse response = new DefaultDnsResponse(12, DnsOpCode.QUERY, DnsResponseCode.NXDOMAIN);
        response.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion("missing.example.", DnsRecordType.A));

        ByteBuf buf = Unpooled.buffer();
        try {
            DoHMessageCodec.encodeResponse(buf, response);

            assertTrue(DoHMessageCodec.decodeAddresses(buf, 12).isEmpty());
        } finally {
            buf.release();
            response.release();
        }
    }

    @Test
    void decodeResponse_servfailThrows() {
        DefaultDnsResponse response = new DefaultDnsResponse(13, DnsOpCode.QUERY, DnsResponseCode.SERVFAIL);
        response.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion("fail.example.", DnsRecordType.A));

        ByteBuf buf = Unpooled.buffer();
        try {
            DoHMessageCodec.encodeResponse(buf, response);

            assertThrows(IllegalStateException.class, () -> DoHMessageCodec.decodeAddresses(buf, 13));
        } finally {
            buf.release();
            response.release();
        }
    }
}
