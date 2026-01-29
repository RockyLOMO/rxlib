package org.rx.io;

import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.*;
import org.rx.core.cache.MemoryCache;

import java.io.*;
import java.lang.reflect.Type;

import static org.rx.core.Extends.as;
import static org.rx.core.Extends.ifNull;
import static org.rx.core.Sys.*;

//https://github.com/RuedigerMoeller/fast-serialization
@Slf4j
public class JdkAndJsonSerializer implements Serializer, JsonTypeInvoker {
    static class JsonWrapper implements Compressible {
        private static final long serialVersionUID = 8279878386622487781L;

        final String typeDescriptor;
        final String json;

        @Override
        public boolean enableCompress() {
            return Strings.length(json) >= MIN_LENGTH;
        }

        public JsonWrapper(Type type, Object obj) {
            typeDescriptor = Reflects.getTypeDescriptor(type);
            json = toJsonString(obj);
        }
    }

    static final String GZIP_HEX = String.format("%04X%04X", Compressible.STREAM_MAGIC, Compressible.STREAM_VERSION);

    public <T> IOStream serialize(@NonNull T obj, @NonNull Type type) {
        HybridStream stream = new HybridStream();
        serialize(obj, type, stream);
        return stream.rewind();
    }

    @Override
    public <T> void serialize(@NonNull T obj, IOStream stream) {
        serialize(obj, obj.getClass(), stream);
    }

    @SneakyThrows
    public <T> void serialize(@NonNull T obj, @NonNull Type type, @NonNull IOStream stream) {
        Cache<String, Object> cache = Cache.getInstance(MemoryCache.class);
        Class<?> objKls = obj.getClass();
        String skipNS = fastCacheKey(Constants.CACHE_REGION_SKIP_SERIALIZE, objKls);
        Object obj0 = !cache.containsKey(skipNS) && Serializable.class.isAssignableFrom(objKls) ? obj : new JsonWrapper(type, obj);

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

        long pos = stream.canSeek() ? stream.getPosition() : -1;
        ObjectOutputStream out = new ObjectOutputStream(stream.getWriter());
        try {
            out.writeObject(obj0);
        } catch (NotSerializableException e) {
            if (pos == -1) {
                throw e;
            }

            log.error("NotSerializable {}", obj, e);
            cache.put(skipNS, this);

            stream.setPosition(pos);
            out = new ObjectOutputStream(stream.getWriter());
            out.writeObject(new JsonWrapper(type, obj));
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
                return fromJson(wrapper.json, ifNull(JSON_TYPE.getIfExists(), () -> Reflects.fromTypeDescriptor(wrapper.typeDescriptor)));
            }
            return (T) obj0;
        } finally {
            if (!leveOpen) {
                stream.close();
            }
        }
    }
}
