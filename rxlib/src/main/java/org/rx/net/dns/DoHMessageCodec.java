package org.rx.net.dns;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.dns.*;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

public final class DoHMessageCodec {
    static final int DNS_HEADER_BYTES = 12;
    static final int NAME_POINTER_MASK = 0xC0;
    static final int NAME_POINTER_VALUE = 0xC0;
    static final int MAX_NAME_DEPTH = 8;

    private DoHMessageCodec() {
    }

    public static void encodeQuery(ByteBuf out, int id, String name, DnsRecordType type) {
        out.writeShort(id & 0xffff);
        out.writeShort(0x0100);
        out.writeShort(1);
        out.writeShort(0);
        out.writeShort(0);
        out.writeShort(0);
        writeName(out, name);
        out.writeShort(type.intValue());
        out.writeShort(DnsRecord.CLASS_IN);
    }

    public static DefaultDnsQuery decodeQuery(ByteBuf in) {
        if (in.readableBytes() < DNS_HEADER_BYTES) {
            throw new IllegalArgumentException("DNS message too short");
        }
        int id = in.readUnsignedShort();
        int flags = in.readUnsignedShort();
        int qdCount = in.readUnsignedShort();
        in.skipBytes(6);
        if (qdCount != 1) {
            throw new IllegalArgumentException("Only one DNS question is supported");
        }
        DefaultDnsQuery query = new DefaultDnsQuery(id);
        query.setRecursionDesired((flags & 0x0100) != 0);
        String name = readName(in);
        int type = in.readUnsignedShort();
        int dnsClass = in.readUnsignedShort();
        query.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion(name, DnsRecordType.valueOf(type), dnsClass));
        return query;
    }

    public static void encodeResponse(ByteBuf out, DnsResponse response) {
        out.writeShort(response.id() & 0xffff);
        out.writeShort(responseFlags(response));
        out.writeShort(response.count(DnsSection.QUESTION));
        out.writeShort(response.count(DnsSection.ANSWER));
        out.writeShort(response.count(DnsSection.AUTHORITY));
        out.writeShort(response.count(DnsSection.ADDITIONAL));
        writeSection(out, response, DnsSection.QUESTION, true);
        writeSection(out, response, DnsSection.ANSWER, false);
        writeSection(out, response, DnsSection.AUTHORITY, false);
        writeSection(out, response, DnsSection.ADDITIONAL, false);
    }

    public static List<InetAddress> decodeAddresses(ByteBuf in, int expectedId) {
        if (in.readableBytes() < DNS_HEADER_BYTES) {
            throw new IllegalArgumentException("DNS response too short");
        }
        int id = in.readUnsignedShort();
        if (id != (expectedId & 0xffff)) {
            throw new IllegalArgumentException("DNS response id mismatch");
        }
        int flags = in.readUnsignedShort();
        int qdCount = in.readUnsignedShort();
        int anCount = in.readUnsignedShort();
        int nsCount = in.readUnsignedShort();
        int arCount = in.readUnsignedShort();
        int rcode = flags & 0x0f;
        if (rcode != DnsResponseCode.NOERROR.intValue()) {
            return new ArrayList<>(0);
        }
        for (int i = 0; i < qdCount; i++) {
            readName(in);
            in.skipBytes(4);
        }
        List<InetAddress> addresses = new ArrayList<>(Math.min(anCount, 4));
        readAddressRecords(in, anCount, addresses);
        skipRecords(in, nsCount + arCount);
        return addresses;
    }

    static void writeName(ByteBuf out, String name) {
        String n = name;
        if (n.endsWith(".")) {
            n = n.substring(0, n.length() - 1);
        }
        int start = 0;
        while (start < n.length()) {
            int dot = n.indexOf('.', start);
            int end = dot == -1 ? n.length() : dot;
            int len = end - start;
            if (len <= 0 || len > 63) {
                throw new IllegalArgumentException("Invalid DNS label");
            }
            out.writeByte(len);
            for (int i = start; i < end; i++) {
                out.writeByte((byte) n.charAt(i));
            }
            if (dot == -1) {
                break;
            }
            start = dot + 1;
        }
        out.writeByte(0);
    }

    static String readName(ByteBuf in) {
        StringBuilder sb = new StringBuilder(64);
        readName0(in, sb, 0);
        return sb.length() == 0 ? "." : sb.append('.').toString();
    }

    private static void readName0(ByteBuf in, StringBuilder sb, int depth) {
        if (depth > MAX_NAME_DEPTH) {
            throw new IllegalArgumentException("DNS name pointer loop");
        }
        while (true) {
            int len = in.readUnsignedByte();
            if (len == 0) {
                return;
            }
            if ((len & NAME_POINTER_MASK) == NAME_POINTER_VALUE) {
                int pointer = ((len & 0x3f) << 8) | in.readUnsignedByte();
                int oldIndex = in.readerIndex();
                in.readerIndex(pointer);
                readName0(in, sb, depth + 1);
                in.readerIndex(oldIndex);
                return;
            }
            if ((len & NAME_POINTER_MASK) != 0 || in.readableBytes() < len) {
                throw new IllegalArgumentException("Invalid DNS name");
            }
            if (sb.length() > 0) {
                sb.append('.');
            }
            for (int i = 0; i < len; i++) {
                sb.append((char) in.readUnsignedByte());
            }
        }
    }

    private static int responseFlags(DnsResponse response) {
        int flags = 0x8000;
        flags |= (response.opCode().byteValue() & 0x0f) << 11;
        if (response.isAuthoritativeAnswer()) {
            flags |= 0x0400;
        }
        if (response.isTruncated()) {
            flags |= 0x0200;
        }
        if (response.isRecursionDesired()) {
            flags |= 0x0100;
        }
        if (response.isRecursionAvailable()) {
            flags |= 0x0080;
        }
        flags |= (response.z() & 0x07) << 4;
        flags |= response.code().intValue() & 0x0f;
        return flags;
    }

    private static void writeSection(ByteBuf out, DnsMessage message, DnsSection section, boolean question) {
        int count = message.count(section);
        for (int i = 0; i < count; i++) {
            DnsRecord record = message.recordAt(section, i);
            writeName(out, record.name());
            out.writeShort(record.type().intValue());
            out.writeShort(record.dnsClass());
            if (!question) {
                out.writeInt((int) record.timeToLive());
                if (record instanceof DnsRawRecord) {
                    ByteBuf content = ((DnsRawRecord) record).content().duplicate();
                    out.writeShort(content.readableBytes());
                    out.writeBytes(content, content.readerIndex(), content.readableBytes());
                } else {
                    out.writeShort(0);
                }
            }
        }
    }

    private static void readAddressRecords(ByteBuf in, int count, List<InetAddress> addresses) {
        for (int i = 0; i < count; i++) {
            readName(in);
            int type = in.readUnsignedShort();
            in.skipBytes(2);
            in.skipBytes(4);
            int len = in.readUnsignedShort();
            if ((type == DnsRecordType.A.intValue() && len == 4) || (type == DnsRecordType.AAAA.intValue() && len == 16)) {
                byte[] bytes = new byte[len];
                in.readBytes(bytes);
                try {
                    addresses.add(InetAddress.getByAddress(bytes));
                } catch (Exception e) {
                    throw new IllegalArgumentException("Invalid DNS address", e);
                }
            } else {
                in.skipBytes(len);
            }
        }
    }

    private static void skipRecords(ByteBuf in, int count) {
        for (int i = 0; i < count; i++) {
            readName(in);
            in.skipBytes(8);
            int len = in.readUnsignedShort();
            in.skipBytes(len);
        }
    }
}
