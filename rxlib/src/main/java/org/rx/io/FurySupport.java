package org.rx.io;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.util.NetUtil;
import io.netty.util.concurrent.FastThreadLocal;
import org.apache.fury.Fury;
import org.apache.fury.config.CompatibleMode;
import org.apache.fury.config.Language;
import org.apache.fury.memory.MemoryBuffer;
import org.apache.fury.resolver.ClassChecker;
import org.apache.fury.resolver.ClassResolver;
import org.rx.bean.DateTime;
import org.rx.core.Constants;

import java.io.Serializable;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

public final class FurySupport {
    public static final short FRAME_MAGIC = (short) 0x5258;
    public static final byte FRAME_VERSION = 1;
    public static final byte CODEC_ID_FURY = 1;
    public static final short DATE_TIME_REGISTER_ID_OFFSET = 1;
    public static final short INET4_ADDRESS_REGISTER_ID_OFFSET = 2;
    public static final short INET6_ADDRESS_REGISTER_ID_OFFSET = 3;
    public static final short INET_SOCKET_ADDRESS_REGISTER_ID_OFFSET = 4;
    private static final ConcurrentMap<FuryLocalKey, FastThreadLocal<Fury>> SHARED_LOCALS =
            new ConcurrentHashMap<FuryLocalKey, FastThreadLocal<Fury>>();

    public static Set<String> defaultAllowedClassPrefixes() {
        LinkedHashSet<String> prefixes = new LinkedHashSet<>();
        prefixes.add("java.");
        prefixes.add("javax.");
        prefixes.add("org.rx.");
        return prefixes;
    }

    public static void allowPrefix(Set<String> allowedPrefixes, String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return;
        }
        String p = prefix.trim();
        if (p.isEmpty()) {
            return;
        }
        allowedPrefixes.add(p.endsWith(".") ? p : p + ".");
    }

    public static void allowClass(Set<String> allowedPrefixes, Class<?> type) {
        if (type != null) {
            allowedPrefixes.add(type.getName());
        }
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

    public static FastThreadLocal<Fury> sharedFuryLocal(Class<?> ownerType, String purpose,
            List<String> allowedPrefixes, Consumer<Fury> registerAction) {
        final FuryLocalKey key = new FuryLocalKey(ownerType, purpose, allowedPrefixes);
        FastThreadLocal<Fury> local = SHARED_LOCALS.get(key);
        if (local != null) {
            return local;
        }

        FastThreadLocal<Fury> created = new FastThreadLocal<Fury>() {
            @Override
            protected Fury initialValue() {
                return newFury(ownerType, key.allowedPrefixes, registerAction);
            }
        };
        FastThreadLocal<Fury> old = SHARED_LOCALS.putIfAbsent(key, created);
        return old == null ? created : old;
    }

    public static void registerDateTime(Fury fury, short registerId) {
        fury.register(DateTime.class, registerId);
        fury.registerSerializer(DateTime.class, new DateTimeSerializer(fury));
    }

    public static void registerInetAddress(Fury fury, short inet4RegisterId, short inet6RegisterId) {
        fury.register(Inet4Address.class, inet4RegisterId);
        fury.registerSerializer(Inet4Address.class, new InetAddressSerializer<Inet4Address>(fury, Inet4Address.class));
        fury.register(Inet6Address.class, inet6RegisterId);
        fury.registerSerializer(Inet6Address.class, new InetAddressSerializer<Inet6Address>(fury, Inet6Address.class));
    }

    public static void registerInetSocketAddress(Fury fury, short registerId) {
        fury.register(InetSocketAddress.class, registerId);
        fury.registerSerializer(InetSocketAddress.class, new InetSocketAddressSerializer(fury));
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
            if (prefix == null || prefix.isEmpty()) {
                continue;
            }
            if (prefix.charAt(prefix.length() - 1) == '.') {
                if (className.startsWith(prefix)) {
                    return true;
                }
                continue;
            }
            if (className.equals(prefix) || className.startsWith(prefix + "$")) {
                return true;
            }
        }
        return false;
    }

    static final class DateTimeSerializer extends org.apache.fury.serializer.Serializer<DateTime> {
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

    static final class InetAddressSerializer<T extends InetAddress> extends org.apache.fury.serializer.Serializer<T> {
        InetAddressSerializer(Fury fury, Class<T> type) {
            super(fury, type);
        }

        @Override
        public void write(MemoryBuffer buffer, T value) {
            byte[] address = value.getAddress();
            buffer.writeByte(address.length);
            buffer.writeBytes(address);
        }

        @Override
        @SuppressWarnings("unchecked")
        public T read(MemoryBuffer buffer) {
            int len = buffer.readUnsignedByte();
            if (len != 4 && len != 16) {
                throw new IllegalArgumentException("Invalid InetAddress length " + len);
            }
            try {
                return (T) InetAddress.getByAddress(buffer.readBytes(len));
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException(e);
            }
        }
    }

    static final class InetSocketAddressSerializer extends org.apache.fury.serializer.Serializer<InetSocketAddress> {
        private static final byte MODE_HOST = 0;
        private static final byte MODE_ADDRESS = 1;

        InetSocketAddressSerializer(Fury fury) {
            super(fury, InetSocketAddress.class);
        }

        @Override
        public void write(MemoryBuffer buffer, InetSocketAddress value) {
            String host = value.getHostString();
            InetAddress address = value.getAddress();
            if (address == null || !isLiteralIp(host)) {
                buffer.writeByte(MODE_HOST);
                buffer.writeVarUint32(value.getPort());
                fury.writeJavaString(buffer, host);
                return;
            }

            byte[] bytes = address.getAddress();
            buffer.writeByte(MODE_ADDRESS);
            buffer.writeVarUint32(value.getPort());
            buffer.writeByte(bytes.length);
            buffer.writeBytes(bytes);
        }

        @Override
        public InetSocketAddress read(MemoryBuffer buffer) {
            int mode = buffer.readUnsignedByte();
            int port = buffer.readVarUint32();
            if (mode == MODE_HOST) {
                return InetSocketAddress.createUnresolved(fury.readJavaString(buffer), port);
            }
            if (mode != MODE_ADDRESS) {
                throw new IllegalArgumentException("Invalid InetSocketAddress mode " + mode);
            }

            int len = buffer.readUnsignedByte();
            if (len != 4 && len != 16) {
                throw new IllegalArgumentException("Invalid InetSocketAddress length " + len);
            }
            try {
                return new InetSocketAddress(InetAddress.getByAddress(buffer.readBytes(len)), port);
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException(e);
            }
        }

        private static boolean isLiteralIp(String host) {
            return host != null && (NetUtil.isValidIpV4Address(host) || NetUtil.isValidIpV6Address(host));
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

    static final class FuryLocalKey {
        final String ownerName;
        final String purpose;
        final List<String> allowedPrefixes;
        final int hash;

        FuryLocalKey(Class<?> ownerType, String purpose, List<String> allowedPrefixes) {
            this.ownerName = ownerType == null ? "" : ownerType.getName();
            this.purpose = purpose == null ? "" : purpose;
            this.allowedPrefixes = Collections.unmodifiableList(new ArrayList<String>(allowedPrefixes));
            this.hash = Objects.hash(ownerName, this.purpose, this.allowedPrefixes);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof FuryLocalKey)) {
                return false;
            }
            FuryLocalKey that = (FuryLocalKey) obj;
            return ownerName.equals(that.ownerName)
                    && purpose.equals(that.purpose)
                    && allowedPrefixes.equals(that.allowedPrefixes);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private FurySupport() {
    }
}
