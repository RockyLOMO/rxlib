package org.rx.io;

import io.netty.buffer.ByteBuf;
import io.netty.util.concurrent.FastThreadLocal;
import lombok.SneakyThrows;
import org.apache.fury.Fury;
import org.apache.fury.memory.MemoryBuffer;
import org.rx.core.Constants;

import java.io.EOFException;
import java.io.StreamCorruptedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class FurySerializer implements Serializer {
    static final short REGISTER_BASE_ID = 3400;
    final Set<String> allowedClassPrefixes = FurySupport.defaultAllowedClassPrefixes();
    transient volatile FastThreadLocal<Fury> furyLocal;

    public FurySerializer allowPrefix(String prefix) {
        FurySupport.allowPrefix(allowedClassPrefixes, prefix);
        furyLocal = null;
        return this;
    }

    public FurySerializer allowClass(Class<?> type) {
        FurySupport.allowClass(allowedClassPrefixes, type);
        furyLocal = null;
        return this;
    }

    @SneakyThrows
    @Override
    public <T> void serialize(T obj, DuplexStream stream) {
        long start = stream.canSeek() ? stream.getPosition() : -1;
        boolean success = false;
        try {
            byte[] payload = serializePayload(obj);
            writeFrameHeader(stream, payload.length);
            stream.write(payload);
            stream.flush();
            success = true;
        } finally {
            if (!success && start >= 0) {
                stream.setPosition(start);
            }
        }
    }

    @SneakyThrows
    @Override
    public <T> T deserialize(DuplexStream stream, boolean leveOpen) {
        try {
            short magic = stream.readShort();
            int version = stream.read();
            int codecId = stream.read();
            int payloadLength = stream.readInt();
            validateFrame(magic, version, codecId, payloadLength);

            Fury fury = fury();
            try {
                return (T) fury.deserializeJavaObjectAndClass(readPayload(stream, payloadLength));
            } finally {
                fury.reset();
            }
        } finally {
            if (!leveOpen) {
                stream.close();
            }
        }
    }

    Fury fury() {
        FastThreadLocal<Fury> local = furyLocal;
        if (local != null) {
            return local.get();
        }
        synchronized (this) {
            local = furyLocal;
            if (local == null) {
                local = FurySupport.sharedFuryLocal(FurySerializer.class, "serializer",
                        new ArrayList<String>(allowedClassPrefixes),
                        fury -> FurySupport.registerDateTime(fury,
                                (short) (REGISTER_BASE_ID + FurySupport.DATE_TIME_REGISTER_ID_OFFSET)));
                furyLocal = local;
            }
        }
        return local.get();
    }

    Fury newFury(List<String> allowedPrefixes) {
        return FurySupport.newFury(FurySerializer.class, allowedPrefixes,
                fury -> FurySupport.registerDateTime(fury, (short) (REGISTER_BASE_ID + FurySupport.DATE_TIME_REGISTER_ID_OFFSET)));
    }

    @SneakyThrows
    private byte[] serializePayload(Object obj) {
        Fury fury = fury();
        try {
            byte[] payload = fury.serializeJavaObjectAndClass(obj);
            if (payload.length > Constants.MAX_HEAP_BUF_SIZE) {
                throw new StreamCorruptedException("Fury payload too large " + payload.length);
            }
            return payload;
        } finally {
            fury.reset();
        }
    }

    private void writeFrameHeader(DuplexStream stream, int payloadLength) {
        stream.writeShort(FurySupport.FRAME_MAGIC);
        stream.write(FurySupport.FRAME_VERSION);
        stream.write(FurySupport.CODEC_ID_FURY);
        stream.writeInt(payloadLength);
    }

    private void validateFrame(short magic, int version, int codecId, int payloadLength) throws StreamCorruptedException {
        if (magic != FurySupport.FRAME_MAGIC) {
            throw new StreamCorruptedException("Fury frame magic mismatch " + Integer.toHexString(magic & 0xFFFF));
        }
        if (version != FurySupport.FRAME_VERSION) {
            throw new StreamCorruptedException("Fury frame version mismatch " + version);
        }
        if (codecId != FurySupport.CODEC_ID_FURY) {
            throw new StreamCorruptedException("Fury frame codec mismatch " + codecId);
        }
        if (payloadLength < 0 || payloadLength > Constants.MAX_HEAP_BUF_SIZE) {
            throw new StreamCorruptedException("Fury frame payload length invalid " + payloadLength);
        }
    }

    private MemoryBuffer readPayload(DuplexStream stream, int payloadLength) throws EOFException {
        if (payloadLength == 0) {
            return MemoryBuffer.fromByteArray(new byte[0]);
        }
        if (stream instanceof MemoryStream) {
            ByteBuf buffer = ((MemoryStream) stream).getBuffer();
            int readerIndex = buffer.readerIndex();
            if (buffer.readableBytes() < payloadLength) {
                throw new EOFException();
            }
            MemoryBuffer memoryBuffer = FurySupport.toMemoryBuffer(buffer, readerIndex, payloadLength);
            stream.setPosition(stream.getPosition() + payloadLength);
            return memoryBuffer;
        }

        byte[] payload = new byte[payloadLength];
        int offset = 0;
        while (offset < payloadLength) {
            int read = stream.read(payload, offset, payloadLength - offset);
            if (read < 0) {
                throw new EOFException();
            }
            offset += read;
        }
        return MemoryBuffer.fromByteArray(payload);
    }
}
