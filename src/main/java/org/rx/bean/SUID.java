package org.rx.bean;

import io.netty.buffer.ByteBuf;
import lombok.*;
import org.rx.core.StringBuilder;
import org.rx.io.Bytes;
import org.rx.security.MD5Util;

import java.io.Serializable;
import java.util.Base64;
import java.util.UUID;

@EqualsAndHashCode
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class SUID implements Serializable {
    private static final long serialVersionUID = -8613691951420514734L;

    public static SUID compute(String data) {
        return valueOf(newUUID(MD5Util.md5(data)));
    }

    public static SUID valueOf(@NonNull String suid) {
        switch (suid.length()) {
            case 22:
                return new SUID(newUUID(Base64.getUrlDecoder().decode(suid)));
            case 32:
            case 36:
                if (suid.length() == 32) {
                    suid = new StringBuilder().append(suid.substring(0, 8)).append("-")
                            .append(suid.substring(8, 12)).append("-")
                            .append(suid.substring(12, 16)).append("-")
                            .append(suid.substring(16, 20)).append("-")
                            .append(suid.substring(20, 32)).toString();
                }
                return new SUID(UUID.fromString(suid));
        }
        throw new IllegalArgumentException("suid");
    }

    public static SUID randomSUID() {
        return valueOf(UUID.randomUUID());
    }

    public static SUID valueOf(UUID uuid) {
        return new SUID(uuid);
    }

    public static UUID newUUID(byte[] guidBytes) {
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
            ByteBuf buf = Bytes.heapBuffer(16)
                    .writeLong(uuid.getMostSignificantBits())
                    .writeLong(uuid.getLeastSignificantBits());
            base64String = Bytes.toString(Base64.getUrlEncoder().withoutPadding().encode(buf.nioBuffer()));
        }
        return base64String;
    }

    @Override
    public String toString() {
        return getBase64String();
    }
}
