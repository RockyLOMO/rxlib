//package org.rx.core.cache;
//
//import io.netty.util.concurrent.FastThreadLocal;
//import org.rx.core.Cache;
//import org.rx.io.IOStream;
//import org.rx.util.function.BiFunc;
//
//import java.io.InputStream;
//import java.io.OutputStream;
//import java.nio.MappedByteBuffer;
//import java.util.Map;
//import java.util.Set;
//import java.util.WeakHashMap;
//import java.util.concurrent.ConcurrentHashMap;
//
//public final class MappedStream extends IOStream<InputStream, OutputStream> {
//    private MappedByteBuffer buffer;
//
//    @Override
//    public String getName() {
//        return null;
//    }
//
//    public MappedStream(InputStream reader, OutputStream writer) {
//        super(reader, writer);
//    }
//}
