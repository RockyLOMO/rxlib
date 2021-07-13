package org.rx.io;

import com.alibaba.fastjson.JSON;
import io.netty.util.concurrent.FastThreadLocal;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Type;

import static org.rx.core.App.*;

public class JdkAndJsonSerializer implements Serializer {
    @RequiredArgsConstructor
    static class JsonWrapper implements Serializable {
        private static final long serialVersionUID = 8279878386622487781L;

        final Class<?> type;
        final String json;
    }

    public static final FastThreadLocal<Type> jsonType = new FastThreadLocal<>();

    @SneakyThrows
    @Override
    public <T> void serialize(@NonNull T obj, @NonNull IOStream<?, ?> stream) {
        Object obj0 = obj instanceof Serializable ? obj : new JsonWrapper(obj.getClass(), JSON.toJSONString(obj));

        ObjectOutputStream out = new ObjectOutputStream(stream.getWriter());
        out.writeObject(obj0);
//        out.flush();
    }

    @SneakyThrows
    @Override
    public <T> T deserialize(@NonNull IOStream<?, ?> stream, boolean leveOpen) {
        try {
            ObjectInputStream in = new ObjectInputStream(stream.getReader());
            Object obj0 = in.readObject();

            JsonWrapper wrapper;
            if ((wrapper = as(obj0, JsonWrapper.class)) != null) {
                Type type = isNull(jsonType.getIfExists(), wrapper.type);
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
