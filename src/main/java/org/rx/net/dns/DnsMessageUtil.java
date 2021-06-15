package org.rx.net.dns;

import io.netty.handler.codec.dns.*;
import io.netty.util.ReferenceCounted;

import javax.annotation.Nonnull;
import java.net.InetSocketAddress;

public class DnsMessageUtil {
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

    public static DatagramDnsResponse newUdpResponse(@Nonnull InetSocketAddress sender,
                                                     @Nonnull InetSocketAddress recipient,
                                                     @Nonnull DnsResponse dnsResponse) {
        final DatagramDnsResponse newResponse = new DatagramDnsResponse(sender, recipient, dnsResponse.id(), dnsResponse.opCode(), dnsResponse.code())
                .setAuthoritativeAnswer(dnsResponse.isAuthoritativeAnswer())
                .setTruncated(dnsResponse.isTruncated())
                .setRecursionAvailable(dnsResponse.isRecursionAvailable())
                .setRecursionDesired(dnsResponse.isRecursionDesired())
                .setZ(dnsResponse.z());
        for (DnsSection section : DnsSection.values()) {
            setRecord(section, dnsResponse, newResponse);
        }
        return newResponse;
    }

    public static DatagramDnsResponse newErrorUdpResponse(@Nonnull DatagramDnsQuery datagramDnsQuery,
                                                          @Nonnull DnsResponseCode rCode) {
        return newErrorUdpResponse(datagramDnsQuery.recipient(), datagramDnsQuery.sender(), datagramDnsQuery, rCode);
    }

    public static DatagramDnsResponse newErrorUdpResponse(@Nonnull DatagramDnsResponse datagramDnsResponse,
                                                          @Nonnull DnsResponseCode rCode) {
        return newErrorUdpResponse(datagramDnsResponse.sender(), datagramDnsResponse.recipient(), datagramDnsResponse, rCode);
    }

    public static DatagramDnsResponse newErrorUdpResponse(@Nonnull InetSocketAddress sender,
                                                          @Nonnull InetSocketAddress recipient,
                                                          @Nonnull DnsMessage dnsMessage,
                                                          @Nonnull DnsResponseCode rCode) {
        DatagramDnsResponse response = new DatagramDnsResponse(sender, recipient, dnsMessage.id(), dnsMessage.opCode(), rCode);
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
