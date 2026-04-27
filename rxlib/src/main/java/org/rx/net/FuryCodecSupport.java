package org.rx.net;

import io.netty.buffer.ByteBuf;
import org.apache.fury.Fury;
import org.apache.fury.memory.MemoryBuffer;
import org.rx.io.FurySupport;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public final class FuryCodecSupport {
    public static final short FRAME_MAGIC = FurySupport.FRAME_MAGIC;
    public static final byte FRAME_VERSION = FurySupport.FRAME_VERSION;
    public static final byte CODEC_ID_FURY = FurySupport.CODEC_ID_FURY;
    public static final short DATE_TIME_REGISTER_ID_OFFSET = FurySupport.DATE_TIME_REGISTER_ID_OFFSET;

    public static Set<String> defaultAllowedClassPrefixes() {
        return FurySupport.defaultAllowedClassPrefixes();
    }

    public static void allowPrefix(Set<String> allowedPrefixes, String prefix) {
        FurySupport.allowPrefix(allowedPrefixes, prefix);
    }

    public static void allowClass(Set<String> allowedPrefixes, Class<?> type) {
        FurySupport.allowClass(allowedPrefixes, type);
    }

    public static Fury newFury(Class<?> ownerType, List<String> allowedPrefixes, Consumer<Fury> registerAction) {
        return FurySupport.newFury(ownerType, allowedPrefixes, registerAction);
    }

    public static void registerDateTime(Fury fury, short registerId) {
        FurySupport.registerDateTime(fury, registerId);
    }

    public static MemoryBuffer toMemoryBuffer(ByteBuf payload, int index, int payloadLength) {
        return FurySupport.toMemoryBuffer(payload, index, payloadLength);
    }

    public static boolean isAllowed(String className, List<String> allowedPrefixes) {
        return FurySupport.isAllowed(className, allowedPrefixes);
    }

    private FuryCodecSupport() {
    }
}
