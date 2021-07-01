package org.rx.io;

import com.alibaba.fastjson.JSON;
import io.netty.buffer.ByteBuf;
import lombok.NonNull;
import lombok.SneakyThrows;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;

public class JdkAndJsonSerializer implements Serializer {
    @SneakyThrows
    @Override
    public <T> void serialize(@NonNull T obj, @NonNull IOStream<?, ?> stream) {
        if (obj instanceof Serializable) {
            ObjectOutputStream out = new ObjectOutputStream(stream.getWriter());
            out.writeObject(obj);
            out.flush();//close会关闭stream
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

    @SneakyThrows
    @Override
    public <T> T deserialize(@NonNull IOStream<?, ?> stream, boolean leveOpen) {
        try {
            ObjectInputStream in = new ObjectInputStream(stream.getReader());
            return (T) in.readObject();
        } finally {
            if (!leveOpen) {
                stream.close();
            }
        }
    }
}
