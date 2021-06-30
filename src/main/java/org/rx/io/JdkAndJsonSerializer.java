package org.rx.io;

import com.alibaba.fastjson.JSON;
import io.netty.buffer.ByteBuf;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;

public class JdkAndJsonSerializer<T> implements Serializer<T> {
    @Override
    public void serialize(T obj, IOStream<?, ?> stream) {
        if (obj instanceof Serializable) {
            IOStream.serialize((Serializable) obj, stream);
            return;
        }

        ByteBuf buf = Bytes.directBuffer();
        try {
            buf.writeCharSequence(JSON.toJSONString(obj), StandardCharsets.UTF_8);
            stream.write(buf);
        } finally {
            buf.release();
        }
    }

    @Override
    public T deserialize(IOStream<?, ?> stream) {
        return null;
    }
}
