package org.rx.beans;

import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.security.MD5Util;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.UUID;

@EqualsAndHashCode
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class SUID implements Serializable {
    public static SUID compute(String data) {
        return valueOf(newUUID(MD5Util.md5(data)));
    }

    public static SUID valueOf(String suid) {
        byte[] bytes = Base64.getUrlDecoder().decode(suid);
        return new SUID(newUUID(bytes));
    }

    public static SUID randomSUID() {
        return valueOf(UUID.randomUUID());
    }

    public static SUID valueOf(UUID uuid) {
        return new SUID(uuid);
    }

    private static UUID newUUID(byte[] guidBytes) {
        long msb = 0, lsb = 0;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (guidBytes[i] & 0xff);
        }
        for (int i = 8; i < 16; i++) {
            lsb = (lsb << 8) | (guidBytes[i] & 0xff);
        }
        return new UUID(msb, lsb);
    }

    @Getter
    private final UUID uuid;
    private transient String base64String;

    public String getBase64String() {
        if (base64String == null) {
            ByteBuffer byteBuffer = ByteBuffer.allocate(16)
                    .putLong(uuid.getMostSignificantBits())
                    .putLong(8, uuid.getLeastSignificantBits());
            base64String = Base64.getUrlEncoder().withoutPadding().encodeToString(byteBuffer.array());
        }
        return base64String;
    }

    @Override
    public String toString() {
        return getBase64String();
    }
}
