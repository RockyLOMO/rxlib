package org.rx.io;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.JsonTypeInvoker;
import org.rx.core.Strings;
import org.rx.exception.TraceHandler;

import java.io.*;
import java.lang.reflect.Type;

import static org.rx.core.Extends.as;
import static org.rx.core.Extends.ifNull;
import static org.rx.core.Sys.fromJson;
import static org.rx.core.Sys.toJsonString;

//https://github.com/RuedigerMoeller/fast-serialization
@Slf4j
public class JdkAndJsonSerializer implements Serializer, JsonTypeInvoker {
    @RequiredArgsConstructor
    static class JsonWrapper implements Compressible {
        private static final long serialVersionUID = 8279878386622487781L;

        final Class<?> type;
        final String json;

        @Override
        public boolean enableCompress() {
            return Strings.length(json) >= MIN_LENGTH;
        }
    }

    static final String GZIP_HEX = String.format("%04X%04X", Compressible.STREAM_MAGIC, Compressible.STREAM_VERSION);

    @SneakyThrows
    @Override
    public <T> void serialize(@NonNull T obj, @NonNull IOStream stream) {
        Object obj0 = obj instanceof Serializable ? obj : new JsonWrapper(obj.getClass(), toJsonString(obj));

        Compressible c0 = as(obj0, Compressible.class);
        if (c0 != null && c0.enableCompress()) {
            stream.writeShort(Compressible.STREAM_MAGIC);
            stream.writeShort(Compressible.STREAM_VERSION);
//            log.debug("switch gzip serialize {}", obj0);
            try (GZIPStream gzip = new GZIPStream(stream, true)) {
                ObjectOutputStream out = new ObjectOutputStream(gzip.getWriter());
                out.writeObject(obj0);
            }
            return;
        }

        ObjectOutputStream out = new ObjectOutputStream(stream.getWriter());
        try {
            out.writeObject(obj0);
        } catch (NotSerializableException e) {
            TraceHandler.INSTANCE.log("NotSerializable {}", obj, e);
            out.writeObject(new JsonWrapper(obj.getClass(), toJsonString(obj)));
        }
    }

    @SneakyThrows
    @Override
    public <T> T deserialize(@NonNull IOStream stream, boolean leveOpen) {
        try {
            Object obj0;

            try {
                ObjectInputStream in = new ObjectInputStream(stream.getReader());
                obj0 = in.readObject();
            } catch (StreamCorruptedException e) {
                if (!Strings.endsWith(e.getMessage(), GZIP_HEX)) {
                    throw e;
                }

//                log.debug("switch gzip deserialize, reason={}", e.getMessage());
                try (GZIPStream gzip = new GZIPStream(stream, true)) {
                    ObjectInputStream in = new ObjectInputStream(gzip.getReader());
                    obj0 = in.readObject();
                }
            }

            JsonWrapper wrapper;
            if ((wrapper = as(obj0, JsonWrapper.class)) != null) {
                Type type = ifNull(JSON_TYPE.getIfExists(), wrapper.type);
                return fromJson(wrapper.json, type);
            }
            return (T) obj0;
        } finally {
            if (!leveOpen) {
                stream.close();
            }
        }
    }
}
