package org.rx.io;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.rx.core.JsonTypeInvoker;
import org.rx.core.Strings;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.StreamCorruptedException;
import java.lang.reflect.Type;

import static org.rx.core.Extends.as;
import static org.rx.core.Extends.ifNull;
import static org.rx.core.Sys.fromJson;
import static org.rx.core.Sys.toJsonString;

//https://github.com/RuedigerMoeller/fast-serialization
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

    @SneakyThrows
    @Override
    public <T> void serialize(@NonNull T obj, @NonNull IOStream stream) {
        Object obj0 = obj instanceof Serializable ? obj : new JsonWrapper(obj.getClass(), toJsonString(obj));

        Compressible c0 = as(obj0, Compressible.class);
        if (c0 != null && c0.enableCompress()) {
            stream.writeShort(Compressible.STREAM_MAGIC);
            stream.writeShort(Compressible.STREAM_VERSION);
//            App.log("switch gzip serialize {}", obj0);
            try (GZIPStream gzip = new GZIPStream(stream, true)) {
                ObjectOutputStream out = new ObjectOutputStream(gzip.getWriter());
                out.writeObject(obj0);
            }
            return;
        }

        ObjectOutputStream out = new ObjectOutputStream(stream.getWriter());
        out.writeObject(obj0);
//        out.flush();
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
                String hex = String.format("%04X%04X", Compressible.STREAM_MAGIC, Compressible.STREAM_VERSION);
                if (!Strings.endsWith(e.getMessage(), hex)) {
                    throw e;
                }

//                App.log("switch gzip deserialize, reason={}", e.getMessage());
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
