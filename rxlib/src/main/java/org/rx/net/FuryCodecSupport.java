package org.rx.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import org.apache.fury.Fury;
import org.apache.fury.config.CompatibleMode;
import org.apache.fury.config.Language;
import org.apache.fury.memory.MemoryBuffer;
import org.apache.fury.resolver.ClassChecker;
import org.apache.fury.resolver.ClassResolver;
import org.apache.fury.serializer.Serializer;
import org.rx.bean.DateTime;
import org.rx.core.Constants;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.function.Consumer;

public final class FuryCodecSupport {
    public static final short FRAME_MAGIC = (short) 0x5258;
    public static final byte FRAME_VERSION = 1;
    public static final byte CODEC_ID_FURY = 1;
    public static final short DATE_TIME_REGISTER_ID_OFFSET = 1;

    public static Set<String> defaultAllowedClassPrefixes() {
        LinkedHashSet<String> prefixes = new LinkedHashSet<>();
        prefixes.add("java.");
        prefixes.add("javax.");
        prefixes.add("org.rx.");
        return prefixes;
    }

    public static Fury newFury(Class<?> ownerType, List<String> allowedPrefixes, Consumer<Fury> registerAction) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = ownerType.getClassLoader();
        }
        Fury fury = Fury.builder()
                .withLanguage(Language.JAVA)
                .withClassLoader(classLoader)
                .withRefTracking(true)
                .withCompatibleMode(CompatibleMode.SCHEMA_CONSISTENT)
                .withBufferSizeLimitBytes(Constants.MAX_HEAP_BUF_SIZE)
                .withAsyncCompilation(true)
                .requireClassRegistration(false)
                .suppressClassRegistrationWarnings(true)
                .build();
        fury.getClassResolver().setClassChecker(new PrefixClassChecker(allowedPrefixes));
        if (registerAction != null) {
            registerAction.accept(fury);
        }
        return fury;
    }

    public static void registerDateTime(Fury fury, short registerId) {
        fury.register(DateTime.class, registerId);
        fury.registerSerializer(DateTime.class, new DateTimeSerializer(fury));
    }

    public static MemoryBuffer toMemoryBuffer(ByteBuf payload, int index, int payloadLength) {
        if (payloadLength == 0) {
            return MemoryBuffer.fromByteArray(new byte[0]);
        }
        if (payload.nioBufferCount() == 1) {
            ByteBuffer buffer = payload.nioBuffer(index, payloadLength);
            return MemoryBuffer.fromByteBuffer(buffer);
        }
        byte[] bytes = ByteBufUtil.getBytes(payload, index, payloadLength, false);
        return MemoryBuffer.fromByteArray(bytes);
    }

    public static boolean isAllowed(String className, List<String> allowedPrefixes) {
        if (className == null || className.isEmpty()) {
            return false;
        }
        if (className.charAt(0) == '[') {
            int index = 0;
            while (index < className.length() && className.charAt(index) == '[') {
                index++;
            }
            if (index >= className.length()) {
                return false;
            }
            char type = className.charAt(index);
            if (type != 'L') {
                return true;
            }
            int end = className.indexOf(';', index);
            className = end < 0 ? className.substring(index + 1) : className.substring(index + 1, end);
        }
        for (String prefix : allowedPrefixes) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    static final class DateTimeSerializer extends Serializer<DateTime> {
        DateTimeSerializer(Fury fury) {
            super(fury, DateTime.class);
        }

        @Override
        public void write(MemoryBuffer buffer, DateTime value) {
            buffer.writeInt64(value.getTime());
            fury.writeJavaString(buffer, value.getTimeZone().getID());
        }

        @Override
        public DateTime read(MemoryBuffer buffer) {
            long ticks = buffer.readInt64();
            String zoneId = fury.readJavaString(buffer);
            return new DateTime(ticks, TimeZone.getTimeZone(zoneId));
        }
    }

    static final class PrefixClassChecker implements ClassChecker, Serializable {
        private static final long serialVersionUID = -7994790458104221153L;
        final List<String> allowedPrefixes;

        PrefixClassChecker(List<String> allowedPrefixes) {
            this.allowedPrefixes = allowedPrefixes;
        }

        @Override
        public boolean checkClass(ClassResolver classResolver, String className) {
            return isAllowed(className, allowedPrefixes);
        }
    }

    private FuryCodecSupport() {
    }
}
