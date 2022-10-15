package org.rx.bean;

import com.alibaba.fastjson.annotation.JSONType;
import com.alibaba.fastjson.parser.DefaultJSONParser;
import com.alibaba.fastjson.parser.deserializer.ObjectDeserializer;
import com.alibaba.fastjson.serializer.JSONSerializer;
import com.alibaba.fastjson.serializer.ObjectSerializer;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.rx.codec.CodecUtil;
import org.rx.codec.MD5Util;
import org.rx.exception.InvalidException;
import org.rx.io.Bytes;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

/**
 * |---------|--------------|
 * 48bit time  80bit random
 */
@JSONType(serializer = ULID.Serializer.class, deserializer = ULID.Serializer.class)
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class ULID implements Serializable, Comparable<ULID> {
    public static class Serializer implements ObjectSerializer, ObjectDeserializer {
        @Override
        public <T> T deserialze(DefaultJSONParser parser, Type fieldType, Object fieldName) {
            return (T) ULID.valueOf(parser.parse().toString());
        }

        @Override
        public int getFastMatchToken() {
            return 0;
        }

        @Override
        public void write(JSONSerializer serializer, Object object, Object fieldName, Type fieldType, int features) throws IOException {
            serializer.write(object.toString());
        }
    }

    private static final long serialVersionUID = -8408685951892844844L;

    public static ULID valueOf(UUID uuid) {
        byte[] buf = new byte[16];
        Bytes.getBytes(uuid.getMostSignificantBits(), buf, 0);
        Bytes.getBytes(uuid.getLeastSignificantBits(), buf, 8);
        return new ULID(buf);
    }

    public static ULID valueOf(@NonNull byte[] buf) {
        if (buf.length != 16) {
            throw new InvalidException("Invalid ULID");
        }
        return new ULID(buf);
    }

    public static ULID valueOf(@NonNull String ulid) {
        byte[] buf = null;
        switch (ulid.length()) {
            case 22:
                buf = Base64.getUrlDecoder().decode(ulid);
                break;
            case 36:
                ulid = ulid.replace("-", "");
            case 32:
                byte[] hexBytes = ulid.getBytes();
                buf = new byte[16];
                CodecUtil.HEX.decode(hexBytes, 0, hexBytes.length, buf, 0);
                break;
        }
        if (buf == null) {
            throw new InvalidException("Invalid ULID {}", ulid);
        }
        return new ULID(buf);
    }

    public static ULID randomULID() {
        return new ULID(Bytes.randomBytes(16), System.currentTimeMillis());
    }

    public static ULID newULID(@NonNull Object key) {
        if (key instanceof String) {
            return newULID(((String) key).getBytes());
        }
        if (key instanceof Long) {
            return newULID(Bytes.getBytes(((Long) key)));
        }
        return newULID(org.rx.io.Serializer.DEFAULT.serialize(key));
    }

    public static ULID newULID(byte[] key) {
        return newULID(key, System.currentTimeMillis());
    }

    public static ULID newULID(byte[] key, long timestamp) {
        return new ULID(MD5Util.md5(key), timestamp);
    }

    final byte[] buf;
    transient long timestamp = -1;
    transient String hexString;
    transient String base64String;

    private ULID(byte[] buf, long timestamp) {
        buf[0] = (byte) ((int) (timestamp >>> 40));
        buf[1] = (byte) ((int) (timestamp >>> 32));
        buf[2] = (byte) ((int) (timestamp >>> 24));
        buf[3] = (byte) ((int) (timestamp >>> 16));
        buf[4] = (byte) ((int) (timestamp >>> 8));
        buf[5] = (byte) ((int) timestamp);
        this.buf = buf;
        this.timestamp = timestamp;
    }

    public long getTimestamp() {
        if (timestamp == -1) {
            timestamp = ((long) buf[0] & 255L) << 40 | ((long) buf[1] & 255L) << 32 | ((long) buf[2] & 255L) << 24 | ((long) buf[3] & 255L) << 16 | ((long) buf[4] & 255L) << 8 | (long) buf[5] & 255L;
        }
        return timestamp;
    }

    public String toBase64String() {
        if (base64String == null) {
            base64String = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        }
        return base64String;
    }

    @Override
    public String toString() {
        if (hexString == null) {
            byte[] hexBytes = new byte[32];
            CodecUtil.HEX.encode(buf, 0, buf.length, hexBytes, 0);
            hexString = new String(hexBytes);
        }
        return hexString;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ULID ulid = (ULID) o;
        return Arrays.equals(buf, ulid.buf);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(buf);
    }

    @Override
    public int compareTo(ULID val) {
        return Long.compare(getTimestamp(), val.getTimestamp());
    }
}
