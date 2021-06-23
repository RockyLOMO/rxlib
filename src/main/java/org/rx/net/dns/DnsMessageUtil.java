package org.rx.net.dns;

import io.netty.handler.codec.dns.*;
import io.netty.util.ReferenceCounted;

import javax.annotation.Nonnull;
import java.net.InetSocketAddress;

public class DnsMessageUtil {
    public static DefaultDnsResponse newResponse(DefaultDnsQuery query, DnsResponse response, boolean isTcp) {
        DefaultDnsResponse newResponse = newResponse(query, isTcp);
        newResponse.setOpCode(response.opCode()).setCode(response.code())
                .setAuthoritativeAnswer(response.isAuthoritativeAnswer())
                .setTruncated(response.isTruncated())
                .setRecursionAvailable(response.isRecursionAvailable())
                .setRecursionDesired(response.isRecursionDesired())
                .setZ(response.z());
        for (DnsSection section : DnsSection.values()) {
            setRecord(section, response, newResponse);
        }
        return newResponse;
    }

    public static DefaultDnsResponse newResponse(DefaultDnsQuery query, boolean isTcp) {
        DefaultDnsResponse response;
        if (!isTcp && query instanceof DatagramDnsQuery) {
            response = new DatagramDnsResponse(((DatagramDnsQuery) query).recipient(), ((DatagramDnsQuery) query).sender(), query.id());
        } else {
            response = new DefaultDnsResponse(query.id());
        }
        return response;
    }

    @Nonnull
    public static DatagramDnsQuery newUdpQuery(@Nonnull InetSocketAddress sender,
                                               @Nonnull InetSocketAddress recipient,
                                               @Nonnull DnsQuery dnsQuery) {
        final DatagramDnsQuery newQuery = new DatagramDnsQuery(sender, recipient, dnsQuery.id(), dnsQuery.opCode())
                .setRecursionDesired(dnsQuery.isRecursionDesired())
                .setZ(dnsQuery.z());
        if (dnsQuery.count(DnsSection.QUESTION) > 0) {
            setRecord(DnsSection.QUESTION, dnsQuery, newQuery);
        }
        return newQuery;
    }

    public static DefaultDnsResponse newErrorResponse(@Nonnull DefaultDnsQuery dnsQuery, @Nonnull DnsResponseCode rCode) {
        if (dnsQuery instanceof DatagramDnsQuery) {
            return newErrorResponse(((DatagramDnsQuery) dnsQuery).recipient(), ((DatagramDnsQuery) dnsQuery).sender(), dnsQuery, rCode);
        }
        return newErrorResponse(null, null, dnsQuery, rCode);
    }

    public static DefaultDnsResponse newErrorResponse(@Nonnull DefaultDnsResponse dnsResponse, @Nonnull DnsResponseCode rCode) {
        if (dnsResponse instanceof DatagramDnsResponse) {
            return newErrorResponse(((DatagramDnsResponse) dnsResponse).sender(), ((DatagramDnsResponse) dnsResponse).recipient(), dnsResponse, rCode);
        }
        return newErrorResponse(null, null, dnsResponse, rCode);
    }

    private static DefaultDnsResponse newErrorResponse(InetSocketAddress sender, InetSocketAddress recipient,
                                                       @Nonnull DnsMessage dnsMessage, @Nonnull DnsResponseCode rCode) {
        DefaultDnsResponse response;
        if (sender != null && recipient != null) {
            response = new DatagramDnsResponse(sender, recipient, dnsMessage.id(), dnsMessage.opCode(), rCode);
        } else {
            response = new DefaultDnsResponse(dnsMessage.id(), dnsMessage.opCode(), rCode);
        }
        if (dnsMessage.count(DnsSection.QUESTION) > 0) {
            setRecord(DnsSection.QUESTION, dnsMessage, response);
        }
        return response;
    }

    private static void setRecord(DnsSection dnsSection, DnsMessage oldDnsMessage, DnsMessage newDnsMessage) {
        for (int i = 0; i < oldDnsMessage.count(dnsSection); i++) {
            DnsRecord dnsRecord = oldDnsMessage.recordAt(dnsSection, i);
            if (dnsRecord instanceof ReferenceCounted) {
                ((ReferenceCounted) dnsRecord).retain();
            }
            newDnsMessage.addRecord(dnsSection, dnsRecord);
        }
    }
}
