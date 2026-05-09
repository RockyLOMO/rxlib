package org.rx.net.support;

import org.rx.exception.InvalidException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * v2ray geodat 最小 protobuf 读取器，仅解析 geosite/geoip 需要的字段。
 */
public final class V2RayGeoDataReader {
    static final int DOMAIN_TYPE_PLAIN = 0;
    static final int DOMAIN_TYPE_REGEX = 1;
    static final int DOMAIN_TYPE_ROOT_DOMAIN = 2;
    static final int DOMAIN_TYPE_FULL = 3;

    private static final int WIRE_VARINT = 0;
    private static final int WIRE_FIXED64 = 1;
    private static final int WIRE_LENGTH_DELIMITED = 2;
    private static final int WIRE_START_GROUP = 3;
    private static final int WIRE_END_GROUP = 4;
    private static final int WIRE_FIXED32 = 5;

    public GeoSiteListData readGeoSiteList(File file) throws IOException {
        return readGeoSiteList(java.nio.file.Files.readAllBytes(file.toPath()));
    }

    public GeoSiteListData readGeoSiteList(byte[] data) {
        Cursor cursor = new Cursor(data, 0, data.length);
        ArrayList<GeoSiteEntry> entries = new ArrayList<>();
        while (cursor.hasRemaining()) {
            int tag = cursor.readTag();
            if (fieldNumber(tag) == 1) {
                expectWire(tag, WIRE_LENGTH_DELIMITED);
                entries.add(readGeoSiteEntry(cursor.readSubCursor()));
            } else {
                cursor.skipField(wireType(tag));
            }
        }
        return new GeoSiteListData(entries);
    }

    public GeoIpListData readGeoIpList(File file) throws IOException {
        return readGeoIpList(java.nio.file.Files.readAllBytes(file.toPath()));
    }

    public GeoIpListData readGeoIpList(byte[] data) {
        Cursor cursor = new Cursor(data, 0, data.length);
        ArrayList<GeoIpEntry> entries = new ArrayList<>();
        while (cursor.hasRemaining()) {
            int tag = cursor.readTag();
            if (fieldNumber(tag) == 1) {
                expectWire(tag, WIRE_LENGTH_DELIMITED);
                entries.add(readGeoIpEntry(cursor.readSubCursor()));
            } else {
                cursor.skipField(wireType(tag));
            }
        }
        return new GeoIpListData(entries);
    }

    private GeoSiteEntry readGeoSiteEntry(Cursor cursor) {
        String countryCode = null;
        String code = null;
        ArrayList<GeoSiteDomain> domains = new ArrayList<>();
        while (cursor.hasRemaining()) {
            int tag = cursor.readTag();
            switch (fieldNumber(tag)) {
                case 1:
                    expectWire(tag, WIRE_LENGTH_DELIMITED);
                    countryCode = cursor.readString();
                    break;
                case 2:
                    expectWire(tag, WIRE_LENGTH_DELIMITED);
                    domains.add(readGeoSiteDomain(cursor.readSubCursor()));
                    break;
                case 4:
                    expectWire(tag, WIRE_LENGTH_DELIMITED);
                    code = cursor.readString();
                    break;
                default:
                    cursor.skipField(wireType(tag));
                    break;
            }
        }
        return new GeoSiteEntry(countryCode, code, domains);
    }

    private GeoSiteDomain readGeoSiteDomain(Cursor cursor) {
        int type = DOMAIN_TYPE_PLAIN;
        String value = null;
        ArrayList<String> attributes = new ArrayList<>();
        while (cursor.hasRemaining()) {
            int tag = cursor.readTag();
            switch (fieldNumber(tag)) {
                case 1:
                    expectWire(tag, WIRE_VARINT);
                    type = cursor.readVarint32();
                    break;
                case 2:
                    expectWire(tag, WIRE_LENGTH_DELIMITED);
                    value = cursor.readString();
                    break;
                case 3:
                    expectWire(tag, WIRE_LENGTH_DELIMITED);
                    String key = readGeoSiteAttributeKey(cursor.readSubCursor());
                    if (key != null && !key.isEmpty()) {
                        attributes.add(key);
                    }
                    break;
                default:
                    cursor.skipField(wireType(tag));
                    break;
            }
        }
        return new GeoSiteDomain(type, value, attributes.toArray(new String[attributes.size()]));
    }

    private String readGeoSiteAttributeKey(Cursor cursor) {
        String key = null;
        boolean present = true;
        while (cursor.hasRemaining()) {
            int tag = cursor.readTag();
            switch (fieldNumber(tag)) {
                case 1:
                    expectWire(tag, WIRE_LENGTH_DELIMITED);
                    key = cursor.readString();
                    break;
                case 2:
                    expectWire(tag, WIRE_VARINT);
                    present = cursor.readVarint64() != 0L;
                    break;
                case 3:
                    expectWire(tag, WIRE_VARINT);
                    cursor.readVarint64();
                    present = true;
                    break;
                default:
                    cursor.skipField(wireType(tag));
                    break;
            }
        }
        return key != null && present ? key : null;
    }

    private GeoIpEntry readGeoIpEntry(Cursor cursor) {
        String countryCode = null;
        String code = null;
        boolean inverseMatch = false;
        ArrayList<Cidr> cidrs = new ArrayList<>();
        while (cursor.hasRemaining()) {
            int tag = cursor.readTag();
            switch (fieldNumber(tag)) {
                case 1:
                    expectWire(tag, WIRE_LENGTH_DELIMITED);
                    countryCode = cursor.readString();
                    break;
                case 2:
                    expectWire(tag, WIRE_LENGTH_DELIMITED);
                    cidrs.add(readCidr(cursor.readSubCursor()));
                    break;
                case 3:
                    expectWire(tag, WIRE_VARINT);
                    inverseMatch = cursor.readVarint64() != 0;
                    break;
                case 5:
                    expectWire(tag, WIRE_LENGTH_DELIMITED);
                    code = cursor.readString();
                    break;
                default:
                    cursor.skipField(wireType(tag));
                    break;
            }
        }
        return new GeoIpEntry(countryCode, code, inverseMatch, cidrs);
    }

    private Cidr readCidr(Cursor cursor) {
        byte[] ip = null;
        int prefix = 0;
        while (cursor.hasRemaining()) {
            int tag = cursor.readTag();
            switch (fieldNumber(tag)) {
                case 1:
                    expectWire(tag, WIRE_LENGTH_DELIMITED);
                    ip = cursor.readBytes();
                    break;
                case 2:
                    expectWire(tag, WIRE_VARINT);
                    prefix = cursor.readVarint32();
                    break;
                default:
                    cursor.skipField(wireType(tag));
                    break;
            }
        }
        if (ip == null) {
            throw new InvalidException("v2ray geoip cidr missing ip");
        }
        int maxPrefix = ip.length == 4 ? 32 : (ip.length == 16 ? 128 : -1);
        if (maxPrefix < 0) {
            throw new InvalidException("v2ray geoip cidr ip length invalid {}", ip.length);
        }
        if (prefix < 0 || prefix > maxPrefix) {
            throw new InvalidException("v2ray geoip cidr prefix invalid {}", prefix);
        }
        return new Cidr(ip, prefix);
    }

    private static int fieldNumber(int tag) {
        return tag >>> 3;
    }

    private static int wireType(int tag) {
        return tag & 7;
    }

    private static void expectWire(int tag, int expected) {
        int actual = wireType(tag);
        if (actual != expected) {
            throw new InvalidException("v2ray geodat wire type invalid field={} expect={} actual={}",
                    fieldNumber(tag), expected, actual);
        }
    }

    public static final class GeoSiteListData {
        final List<GeoSiteEntry> entries;

        GeoSiteListData(List<GeoSiteEntry> entries) {
            this.entries = Collections.unmodifiableList(entries);
        }
    }

    public static final class GeoSiteEntry {
        final String countryCode;
        final String code;
        final List<GeoSiteDomain> domains;

        GeoSiteEntry(String countryCode, String code, List<GeoSiteDomain> domains) {
            this.countryCode = countryCode;
            this.code = code;
            this.domains = Collections.unmodifiableList(domains);
        }
    }

    public static final class GeoSiteDomain {
        final int type;
        final String value;
        final String[] attributes;

        GeoSiteDomain(int type, String value, String[] attributes) {
            this.type = type;
            this.value = value;
            this.attributes = attributes;
        }
    }

    public static final class GeoIpListData {
        final List<GeoIpEntry> entries;

        GeoIpListData(List<GeoIpEntry> entries) {
            this.entries = Collections.unmodifiableList(entries);
        }
    }

    public static final class GeoIpEntry {
        final String countryCode;
        final String code;
        final boolean inverseMatch;
        final List<Cidr> cidrs;

        GeoIpEntry(String countryCode, String code, boolean inverseMatch, List<Cidr> cidrs) {
            this.countryCode = countryCode;
            this.code = code;
            this.inverseMatch = inverseMatch;
            this.cidrs = Collections.unmodifiableList(cidrs);
        }
    }

    public static final class Cidr {
        final byte[] ip;
        final int prefix;

        Cidr(byte[] ip, int prefix) {
            this.ip = ip;
            this.prefix = prefix;
        }
    }

    private static final class Cursor {
        final byte[] data;
        int pos;
        final int limit;

        Cursor(byte[] data, int pos, int limit) {
            if (data == null || pos < 0 || limit < pos || limit > data.length) {
                throw new InvalidException("v2ray geodat input bounds invalid");
            }
            this.data = data;
            this.pos = pos;
            this.limit = limit;
        }

        boolean hasRemaining() {
            return pos < limit;
        }

        int readTag() {
            if (!hasRemaining()) {
                return 0;
            }
            int tag = readVarint32();
            if (tag == 0) {
                throw new InvalidException("v2ray geodat tag zero");
            }
            return tag;
        }

        int readVarint32() {
            long value = readVarint64();
            if (value > Integer.MAX_VALUE) {
                throw new InvalidException("v2ray geodat varint32 overflow {}", value);
            }
            return (int) value;
        }

        long readVarint64() {
            long result = 0L;
            for (int i = 0; i < 10; i++) {
                if (pos >= limit) {
                    throw new InvalidException("v2ray geodat varint truncated");
                }
                int b = data[pos++] & 0xff;
                if (i == 9 && (b & 0xfe) != 0) {
                    throw new InvalidException("v2ray geodat varint overflow");
                }
                result |= (long) (b & 0x7f) << (i * 7);
                if ((b & 0x80) == 0) {
                    return result;
                }
            }
            throw new InvalidException("v2ray geodat varint overflow");
        }

        String readString() {
            int len = readLength();
            String value = new String(data, pos, len, StandardCharsets.UTF_8);
            pos += len;
            return value;
        }

        byte[] readBytes() {
            int len = readLength();
            byte[] bytes = new byte[len];
            System.arraycopy(data, pos, bytes, 0, len);
            pos += len;
            return bytes;
        }

        Cursor readSubCursor() {
            int len = readLength();
            Cursor cursor = new Cursor(data, pos, pos + len);
            pos += len;
            return cursor;
        }

        void skipField(int wire) {
            switch (wire) {
                case WIRE_VARINT:
                    readVarint64();
                    return;
                case WIRE_FIXED64:
                    skipBytes(8);
                    return;
                case WIRE_LENGTH_DELIMITED:
                    skipBytes(readLength());
                    return;
                case WIRE_START_GROUP:
                    skipGroup();
                    return;
                case WIRE_END_GROUP:
                    return;
                case WIRE_FIXED32:
                    skipBytes(4);
                    return;
                default:
                    throw new InvalidException("v2ray geodat wire type unsupported {}", wire);
            }
        }

        private int readLength() {
            int len = readVarint32();
            if (len < 0 || len > limit - pos) {
                throw new InvalidException("v2ray geodat length out of bounds {}", len);
            }
            return len;
        }

        private void skipBytes(int len) {
            if (len < 0 || len > limit - pos) {
                throw new InvalidException("v2ray geodat skip out of bounds {}", len);
            }
            pos += len;
        }

        private void skipGroup() {
            while (hasRemaining()) {
                int tag = readTag();
                int wire = wireType(tag);
                if (wire == WIRE_END_GROUP) {
                    return;
                }
                skipField(wire);
            }
            throw new InvalidException("v2ray geodat group not closed");
        }
    }
}
