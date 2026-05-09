package org.rx.net.support;

import io.netty.util.NetUtil;

import java.io.ByteArrayOutputStream;

final class V2RayGeoDataTestUtil {
    private V2RayGeoDataTestUtil() {
    }

    static byte[] geoSiteList(byte[]... entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] entry : entries) {
            message(out, 1, entry);
        }
        return out.toByteArray();
    }

    static byte[] geoSiteEntry(String code, byte[]... domains) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        string(out, 4, code);
        for (byte[] domain : domains) {
            message(out, 2, domain);
        }
        return out.toByteArray();
    }

    static byte[] geoSiteEntryByCountry(String countryCode, byte[]... domains) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        string(out, 1, countryCode);
        for (byte[] domain : domains) {
            message(out, 2, domain);
        }
        return out.toByteArray();
    }

    static byte[] domain(int type, String value, String... attributes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        varint(out, 1, type);
        string(out, 2, value);
        for (String attribute : attributes) {
            message(out, 3, attribute(attribute));
        }
        return out.toByteArray();
    }

    static byte[] geoIpList(byte[]... entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] entry : entries) {
            message(out, 1, entry);
        }
        return out.toByteArray();
    }

    static byte[] geoIpEntry(String code, boolean inverse, byte[]... cidrs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        string(out, 5, code);
        for (byte[] cidr : cidrs) {
            message(out, 2, cidr);
        }
        if (inverse) {
            varint(out, 3, 1);
        }
        return out.toByteArray();
    }

    static byte[] cidr(byte[] ip, int prefix) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bytes(out, 1, ip);
        varint(out, 2, prefix);
        return out.toByteArray();
    }

    static byte[] ip4(int a, int b, int c, int d) {
        return new byte[]{(byte) a, (byte) b, (byte) c, (byte) d};
    }

    static byte[] ip6(String ip) {
        byte[] bytes = NetUtil.createByteArrayFromIpAddressString(ip);
        if (bytes == null || bytes.length != 16) {
            throw new IllegalArgumentException(ip);
        }
        return bytes;
    }

    static byte[] withUnknownVarint(byte[] message, int fieldNumber, long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, message);
        varint(out, fieldNumber, value);
        return out.toByteArray();
    }

    static byte[] truncatedLengthDelimited() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        tag(out, 1, 2);
        writeVarint(out, 8);
        out.write(1);
        return out.toByteArray();
    }

    private static byte[] attribute(String key) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        string(out, 1, key);
        varint(out, 2, 1);
        return out.toByteArray();
    }

    private static void string(ByteArrayOutputStream out, int fieldNumber, String value) {
        bytes(out, fieldNumber, value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void bytes(ByteArrayOutputStream out, int fieldNumber, byte[] value) {
        tag(out, fieldNumber, 2);
        writeVarint(out, value.length);
        write(out, value);
    }

    private static void message(ByteArrayOutputStream out, int fieldNumber, byte[] value) {
        bytes(out, fieldNumber, value);
    }

    private static void varint(ByteArrayOutputStream out, int fieldNumber, long value) {
        tag(out, fieldNumber, 0);
        writeVarint(out, value);
    }

    private static void tag(ByteArrayOutputStream out, int fieldNumber, int wireType) {
        writeVarint(out, ((long) fieldNumber << 3) | wireType);
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7fL) != 0L) {
            out.write(((int) value & 0x7f) | 0x80);
            value >>>= 7;
        }
        out.write((int) value);
    }

    private static void write(ByteArrayOutputStream out, byte[] value) {
        out.write(value, 0, value.length);
    }
}
