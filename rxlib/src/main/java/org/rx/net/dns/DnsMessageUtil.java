package org.rx.net.dns;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.dns.*;
import io.netty.util.ReferenceCounted;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;

public class DnsMessageUtil {
    public static DefaultDnsResponse newResponse(DefaultDnsQuery query, DnsResponse response, boolean isTcp) {
        if (!isTcp) {
            // Always build a fresh DatagramDnsResponse addressed to the original UDP client.
            // Do NOT return the upstream DatagramDnsResponse directly: its recipient() points to
            // the resolver's ephemeral port (not the DNS client), which would send the reply to
            // the wrong address.  Reusing the same object also couples the envelope's refCnt to
            // the writeAndFlush lifecycle, which can cause IllegalReferenceCountException in
            // edge cases where the encoder releases the message before our finally block runs.
            DatagramDnsQuery udpQuery = (DatagramDnsQuery) query;
            DefaultDnsResponse newResponse = new DatagramDnsResponse(udpQuery.recipient(), udpQuery.sender(), query.id());
            newResponse.setOpCode(response.opCode()).setCode(response.code())
                    .setAuthoritativeAnswer(response.isAuthoritativeAnswer())
                    .setTruncated(response.isTruncated())
                    .setRecursionAvailable(response.isRecursionAvailable())
                    .setRecursionDesired(response.isRecursionDesired())
                    .setZ(response.z());
            for (DnsSection section : DnsSection.values()) {
                setRecords(section, response, newResponse);
            }
            return newResponse;
        }

        DefaultDnsResponse newResponse = new DefaultDnsResponse(query.id(), response.opCode(), response.code());
        newResponse.setAuthoritativeAnswer(response.isAuthoritativeAnswer())
                .setTruncated(response.isTruncated())
                .setRecursionAvailable(response.isRecursionAvailable())
                .setRecursionDesired(response.isRecursionDesired())
                .setZ(response.z());
        for (DnsSection section : DnsSection.values()) {
            setRecords(section, response, newResponse);
        }
        return newResponse;
    }

    public static DefaultDnsResponse newResponse(DefaultDnsQuery query, boolean isTcp) {
        DefaultDnsResponse response;
        if (!isTcp) {
            DatagramDnsQuery udpQuery = (DatagramDnsQuery) query;
            response = new DatagramDnsResponse(udpQuery.recipient(), udpQuery.sender(), query.id());
        } else {
            response = new DefaultDnsResponse(query.id());
        }
        return response;
    }

    public static DefaultDnsResponse newErrorResponse(DefaultDnsQuery dnsQuery, DnsResponseCode rCode) {
        if (dnsQuery instanceof DatagramDnsQuery) {
            return newErrorResponse(((DatagramDnsQuery) dnsQuery).recipient(), ((DatagramDnsQuery) dnsQuery).sender(), dnsQuery, rCode);
        }
        return newErrorResponse(null, null, dnsQuery, rCode);
    }

    public static DefaultDnsResponse newAddressResponse(DefaultDnsQuery query, boolean isTcp, DefaultDnsQuestion question,
                                                        long ttl, Iterable<InetAddress> ips) {
        DefaultDnsResponse response = newResponse(query, isTcp);
        if (question instanceof ReferenceCounted) {
            ((ReferenceCounted) question).retain();
        }
        response.addRecord(DnsSection.QUESTION, question);

        DnsRecordType queryType = question.type();
        for (InetAddress ip : ips) {
            DnsRecordType type = ip instanceof Inet6Address ? DnsRecordType.AAAA : DnsRecordType.A;
            if (queryType != type) {
                continue;
            }
            byte[] address = ip.getAddress();
            ByteBuf content = Unpooled.wrappedBuffer(address);
            response.addRecord(DnsSection.ANSWER, new DefaultDnsRawRecord(question.name(), type, ttl, content));
        }
        return response;
    }

    private static DefaultDnsResponse newErrorResponse(InetSocketAddress sender, InetSocketAddress recipient,
                                                       DnsMessage dnsMessage, DnsResponseCode rCode) {
        DefaultDnsResponse response;
        if (sender != null && recipient != null) {
            response = new DatagramDnsResponse(sender, recipient, dnsMessage.id(), dnsMessage.opCode(), rCode);
        } else {
            response = new DefaultDnsResponse(dnsMessage.id(), dnsMessage.opCode(), rCode);
        }
        setRecords(DnsSection.QUESTION, dnsMessage, response);
        setRecords(DnsSection.ANSWER, dnsMessage, response);
        setRecords(DnsSection.AUTHORITY, dnsMessage, response);
        setRecords(DnsSection.ADDITIONAL, dnsMessage, response);
        return response;
    }

    private static void setRecords(DnsSection section, DnsMessage oldDnsMessage, DnsMessage newDnsMessage) {
        int count = oldDnsMessage.count(section);
        for (int i = 0; i < count; i++) {
            DnsRecord dnsRecord = oldDnsMessage.recordAt(section, i);
            if (dnsRecord instanceof ReferenceCounted) {
                ((ReferenceCounted) dnsRecord).retain();
            }
            newDnsMessage.addRecord(section, dnsRecord);
        }
    }
}
