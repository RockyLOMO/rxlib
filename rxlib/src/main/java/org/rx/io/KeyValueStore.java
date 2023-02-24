package org.rx.io;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.IteratorUtils;
import org.rx.bean.$;
import org.rx.bean.AbstractMap;
import org.rx.bean.DateTime;
import org.rx.codec.CodecUtil;
import org.rx.core.Disposable;
import org.rx.core.Linq;
import org.rx.core.Reflects;
import org.rx.core.Strings;
import org.rx.exception.ExceptionLevel;
import org.rx.exception.InvalidException;
import org.rx.net.http.HttpServer;
import org.rx.net.http.ServerRequest;
import org.rx.third.guava.AbstractSequentialIterator;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.rx.bean.$.$;
import static org.rx.core.Extends.*;
import static org.rx.core.Sys.fromJson;
import static org.rx.core.Sys.toJsonObject;

/**
 * meta
 * logPosition + size
 *
 * <p>log
 * status(1) + key + value + size(4)
 */
@Slf4j
public class KeyValueStore<TK, TV> extends Disposable implements AbstractMap<TK, TV> {
    @AllArgsConstructor
    @Getter
    @ToString
    @EqualsAndHashCode
    static class Entry<TK, TV> implements Map.Entry<TK, TV>, Compressible {
        private static final long serialVersionUID = -2218602651671401557L;

        private void writeObject(ObjectOutputStream out) throws IOException {
            out.writeObject(key);
            out.writeObject(value);
        }

        private void readObject(ObjectInputStream in) throws IOException {
            try {
                key = (TK) in.readObject();
                value = (TV) in.readObject();
            } catch (ClassNotFoundException e) {
                log.error("readObject {}", e.getMessage());
            }
        }

        TK key;
        TV value;

        @Override
        public TV setValue(TV value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean enableCompress() {
            Compressible v = as(value, Compressible.class);
            return v != null && v.enableCompress();
        }
    }

    @RequiredArgsConstructor
    class IteratorContext {
        final Entry<TK, TV>[] buf;
        long logPos = wal.meta.getLogPosition();
        int writePos;
        int readPos;
        int remaining;
    }

    //0 NORMAL, 1 DELETE
    static final byte TOMB_MARK = 1;
    static final int DEFAULT_ITERATOR_SIZE = 50;
    static final String KEY_TYPE_FIELD = "_KEY_TYPE", VALUE_TYPE_FIELD = "_VAL_TYPE";
    static final Map<Class<?>, KeyValueStore> instances = new ConcurrentHashMap<>();

    public static <TK, TV> KeyValueStore<TK, TV> getInstance(Class<TK> keyType, Class<TV> valueType) {
        return instances.computeIfAbsent(keyType, k -> new KeyValueStore<>(KeyValueStoreConfig.newConfig(keyType, valueType)));
    }

    final KeyValueStoreConfig config;
    final File parentDirectory;
    final String logName;
    final WALFileStream wal;
    final KeyIndexer<TK> indexer;
    final Serializer serializer;
    //    transient EntrySetView entrySet;
    transient HttpServer apiServer;

    String getTypeId() {
        return String.format("%s:%s", config.getKeyType().getName(), config.getValueType().getName());
    }

    public KeyValueStore(KeyValueStoreConfig config) {
        this(config, Serializer.DEFAULT);
    }

    @SneakyThrows
    public KeyValueStore(@NonNull KeyValueStoreConfig config, @NonNull Serializer serializer) {
        require(config.getKeyType());
        require(config.getValueType());
        this.config = config;
        parentDirectory = new File(Files.createDirectory(config.getDirectoryPath()));
        String typeId = getTypeId();
        logName = String.format("%s.log", CodecUtil.hashUnsigned64(typeId));
        File logFile = new File(String.format("%s/%s", config.getDirectoryPath(), logName));
        wal = new WALFileStream(logFile, config.getLogGrowSize(), config.getLogReaderCount(), serializer);
        wal.setFlushDelayMillis(config.getFlushDelayMillis());
        UserDefinedFileAttributeView view = java.nio.file.Files.getFileAttributeView(logFile.toPath(), UserDefinedFileAttributeView.class);
        view.write("typeId", StandardCharsets.UTF_8.encode(typeId));

//        String name = "user.mimetype";
//        ByteBuffer buf = ByteBuffer.allocate(view.size(name));
//        view.read(name, buf);
//        buf.flip();
//        String value = StandardCharsets.UTF_8.decode(buf).toString();
        this.serializer = serializer;

        String idxName = Files.changeExtension(logName, "idx");
        indexer = new ExternalSortingIndexer<>(new File(String.format("%s/%s", config.getDirectoryPath(), idxName)), config.getIndexBufferSize(), config.getIndexReaderCount());

        wal.lock.writeInvoke(() -> {
            long pos = wal.meta.getLogPosition();
            Entry<TK, TV> val;
            $<Long> endPos = $();
            while ((val = unsafeRead(pos, null, endPos)) != null) {
                boolean incr = false;
                TK k = val.key;
                KeyIndexer.KeyEntity<TK> key = indexer.find(k);
                if (key == null) {
                    key = indexer.newKey(k);
                    incr = true;
                }
                if (key.logPosition == TOMB_MARK) {
                    incr = true;
                }

                key.logPosition = pos;
                wal.meta.setLogPosition(endPos.v);
                indexer.save(key);

                if (incr) {
                    wal.meta.incrementSize();
                }
                log.debug("recover {}", key);
                pos = endPos.v;
            }
        });
        if (wal.meta.extra == null) {
            wal.meta.extra = new AtomicInteger();
        }

        if (config.getApiPort() > 0) {
            startApiServer(config.getApiPort());
        }
    }

    @SneakyThrows
    @Override
    protected void freeObjects() {
        indexer.close();
        wal.close();
    }

    public void fastPut(@NonNull TK k, TV v) {
        checkNotClosed();

        Entry<TK, TV> val = new Entry<>(k, v);
        wal.lock.writeInvoke(() -> {
            boolean incr = false;
            KeyIndexer.KeyEntity<TK> key = indexer.find(k);
            if (key == null) {
                key = indexer.newKey(k);
                incr = true;
            }
            if (key.logPosition == TOMB_MARK) {
                incr = true;
            }

            long pos = wal.meta.getLogPosition();
            if (key.logPosition >= WALFileStream.HEADER_SIZE) {
                KeyIndexer.KeyEntity<TK> finalKey = key;
                wal.lock.writeInvoke(() -> {
                    wal.meta.setLogPosition(finalKey.logPosition);
                    wal.write(TOMB_MARK);
                    wal.meta.setLogPosition(pos);
                    log.debug("fastPut mark TOMB {} <- {}", finalKey.logPosition, pos);
                }, key.logPosition, 1);
            }

            key.logPosition = pos;
            wal.write(0);
            serializer.serialize(val, wal);
            int size = (int) (wal.meta.getLogPosition() - key.logPosition);
            wal.writeInt(size);
//            log.debug("fastPut {} {}", key, val);

            indexer.save(key);
            if (incr) {
                wal.meta.incrementSize();
            }
        }, wal.meta.getLogPosition());
    }

    public void fastRemove(@NonNull TK k) {
        checkNotClosed();

        KeyIndexer.KeyEntity<TK> key = indexer.find(k);
        if (key == null || key.logPosition < WALFileStream.HEADER_SIZE) {
            return;
        }

        wal.lock.writeInvoke(() -> {
            long pos = wal.meta.getLogPosition();
            wal.meta.setLogPosition(key.logPosition);
            wal.write(TOMB_MARK);
            wal.meta.setLogPosition(pos);
            log.debug("fastRemove {}", key);

            key.logPosition = TOMB_MARK;
            indexer.save(key);
        }, key.logPosition, 1);
        wal.meta.decrementSize();
    }

    protected TV read(@NonNull TK k) {
        Entry<TK, TV> val = wal.lock.readInvoke(() -> {
            KeyIndexer.KeyEntity<TK> key = indexer.find(k);
            if (key == null || key.logPosition == TOMB_MARK) {
                return null;
            }

            return unsafeRead(key.logPosition, key.key, null);
        }, WALFileStream.HEADER_SIZE); //todo read lock size
        return val != null ? val.value : null;
    }

    @SneakyThrows
    private Entry<TK, TV> unsafeRead(long logPosition, TK k, $<Long> position) {
//        log.debug("readValue {} {}", k, logPosition);
        Entry<TK, TV> val;
        wal.setReaderPosition(logPosition);
        try {
            int status = wal.read();
            if (status == TOMB_MARK) {
                return null;
            }
            val = serializer.deserialize(wal, true);

            if (k != null && !k.equals(val.key)) {
                AtomicInteger counter = (AtomicInteger) wal.meta.extra;
                int total = counter == null ? -1 : counter.incrementAndGet();
                log.warn("LogPosError hash collision {} total={}", k, total);
                Files.writeLines("./hc_err.log", Linq.from(String.format("%s %s hc=%s total=%s", DateTime.now(), logName
                        , k, total)), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                return null;
            }
            return val;
        } catch (Exception e) {
            if (e instanceof StreamCorruptedException) {
                log.warn("readValue {} {} {}", k == null ? "[INIT]" : k, logPosition, e.getMessage());
                return null;
            }
            throw e;
        } finally {
            long readerPosition = wal.getReaderPosition(true);
            if (position != null) {
                position.v = readerPosition;
            }
        }
    }

    private boolean readBackwards(IteratorContext ctx, int prefetchCount) {
        wal.setReaderPosition(ctx.logPos); //4 lock
        return wal.backwardReadObject(reader -> {
            ctx.writePos = ctx.readPos = 0;
            for (int i = 0; i < prefetchCount; ) {
                long logPos = ctx.logPos - 4;
                if (logPos < WALFileStream.HEADER_SIZE) {
                    ctx.remaining = 0;
                    return false;
                }

                long p1 = 0, p2 = 0;
                int size = 0, status = 0;
                try {
                    p1 = logPos;
                    reader.setPosition(logPos);
                    size = reader.readInt();

                    logPos -= size;
                    p2 = logPos;
                    reader.setPosition(logPos);
                    status = reader.read();
                    if (status == TOMB_MARK) {
                        continue;
                    }

                    Entry<TK, TV> val = serializer.deserialize(reader, true);
//                    log.debug("readBackwards {}", val);
                    ctx.buf[ctx.writePos++] = val;
                    i++;
                } catch (Exception e) {
                    if (e instanceof StreamCorruptedException | e instanceof EOFException) {
                        log.warn("readBackwards error {}", e.getMessage());
                        ctx.remaining = 0;
                        return false;
                    }
                    throw e;
                } finally {
                    ctx.logPos = logPos;
                    log.debug("readBackwards prev[{}] status[{}]={} len[{}]={}", logPos, p2, status, p1, size);
                }
            }
            return true;
        });
    }

    //region api
    void startApiServer(int port) {
        apiServer = new HttpServer(port, config.isApiSsl())
                .requestMapping("/get", ((request, response) -> {
                    apiCheck(request);
                    JSONObject reqJson = toJsonObject(request.jsonBody());
                    JSONObject resJson = new JSONObject();

                    Object key = reqJson.get("key");
                    if (key == null) {
                        JSONArray keys = reqJson.getJSONArray("keys");
                        if (keys != null) {
                            Map<TK, TV> map = new LinkedHashMap<>();
                            for (int i = 0; i < keys.size(); i++) {
                                TK k = apiDeserialize(reqJson, KEY_TYPE_FIELD, keys.get(i));
                                map.put(k, get(k));
                            }
                            resJson.put("code", 0);
                            resJson.put("entry", map);
                        } else {
                            resJson.put("code", 1);
                            resJson.put("top", entrySet());
                        }
                        response.jsonBody(resJson);
                        return;
                    }

                    TK k = apiDeserialize(reqJson, KEY_TYPE_FIELD, key);
                    apiSerialize(resJson, VALUE_TYPE_FIELD, get(k));
                    response.jsonBody(resJson);
                })).requestMapping("/set", (request, response) -> {
                    apiCheck(request);
                    JSONObject reqJson = toJsonObject(request.jsonBody());
                    JSONObject resJson = new JSONObject();

                    Object key = reqJson.get("key");
                    if (key == null) {
                        resJson.put("code", 1);
                        response.jsonBody(resJson);
                        return;
                    }
                    TK k = apiDeserialize(reqJson, KEY_TYPE_FIELD, key);

                    Object value = reqJson.get("value"), concurrentValue = reqJson.get("concurrentValue");
                    if (value == null) {
                        if (concurrentValue == null) {
                            apiSerialize(resJson, VALUE_TYPE_FIELD, remove(k));
                        } else {
                            apiSerialize(resJson, VALUE_TYPE_FIELD, remove(k, apiDeserialize(reqJson, VALUE_TYPE_FIELD, concurrentValue)));
                        }
                        response.jsonBody(resJson);
                        return;
                    }

                    TV v = apiDeserialize(reqJson, VALUE_TYPE_FIELD, value);
                    if (concurrentValue == null) {
                        byte flag = ifNull(reqJson.getByte("flag"), (byte) 0);
                        switch (flag) {
                            case 1:
                                apiSerialize(resJson, VALUE_TYPE_FIELD, putIfAbsent(k, v));
                                break;
                            case 2:
                                apiSerialize(resJson, VALUE_TYPE_FIELD, replace(k, v));
                                break;
                            default:
                                apiSerialize(resJson, VALUE_TYPE_FIELD, put(k, v));
                                break;
                        }
                    } else {
                        apiSerialize(resJson, VALUE_TYPE_FIELD, replace(k, apiDeserialize(reqJson, VALUE_TYPE_FIELD, concurrentValue), v));
                    }
                    response.jsonBody(resJson);
                });
    }

    private <T> T apiDeserialize(JSONObject reqJson, String typeField, Object obj) {
        if (obj instanceof byte[]) {
            byte[] bytes = (byte[]) obj;
            return serializer.deserialize(IOStream.wrap(null, bytes));
        }
        String type = reqJson.getString(typeField);
        return type == null ? (T) obj : fromJson(obj, Reflects.loadClass(type, false));
    }

    private void apiSerialize(JSONObject resJson, String typeField, Object obj) {
        resJson.put("code", 0);
        if (obj == null) {
            return;
        }
        if (config.isApiReturnJson()) {
            resJson.put(typeField, obj.getClass().getName());
            resJson.put("value", obj);
        } else {
            resJson.put("value", serializer.serializeToBytes(obj));
        }
    }

    private void apiCheck(ServerRequest req) {
        if (Strings.isEmpty(config.getApiPassword())) {
            return;
        }
        if (!eq(config.getApiPassword(), req.getHeaders().get("apiPassword"))) {
            throw new InvalidException(ExceptionLevel.USER_OPERATION, "{} auth fail", req.getRemoteEndpoint());
        }
    }
    //endregion

    //region map
    @Override
    public int size() {
        return wal.meta.getSize();
    }

    @Override
    public boolean containsKey(Object key) {
        TK k = (TK) key;
        return indexer.find(k) != null;
    }

    @Override
    public TV get(Object key) {
        TK k = (TK) key;
        return read(k);
    }

    @Override
    public TV put(TK key, TV value) {
        TV old = read(key);
        if (!eq(old, value)) {
            fastPut(key, value);
        }
        return old;
    }

    @Override
    public TV remove(Object key) {
        TK k = (TK) key;
        TV old = read(k);
        fastRemove(k);
        return old;
    }

    @Override
    public void clear() {
        wal.lock.writeInvoke(() -> {
            indexer.clear();
            wal.clear();
        });
    }

    @Override
    public Set<Map.Entry<TK, TV>> entrySet() {
        return entrySet(0, DEFAULT_ITERATOR_SIZE);
    }

    public Set<Map.Entry<TK, TV>> entrySet(int offset, int size) {
        require(offset, offset >= 0);
        require(size, size >= 0);

        return new EntrySetView(offset, size);
//        EntrySetView<TK, TV> es;
//        return (es = entrySet) != null ? es : (entrySet = new EntrySetView<>(this, offset, size));
    }
    //endregion

    @RequiredArgsConstructor
    class EntrySetView extends AbstractSet<Map.Entry<TK, TV>> {
        final int offset;
        final int size;

        @Override
        public int size() {
            return KeyValueStore.this.size();
        }

        @Override
        public void clear() {
            KeyValueStore.this.clear();
        }

        @Override
        public Iterator<Map.Entry<TK, TV>> iterator() {
            int total = KeyValueStore.this.size();
            if (total <= offset) {
                return IteratorUtils.emptyIterator();
            }

            IteratorContext ctx = new IteratorContext(new Entry[config.getIteratorPrefetchCount()]);
            if (offset > 0 && !readBackwards(ctx, offset)) {
                return IteratorUtils.emptyIterator();
            }
            readBackwards(ctx, ctx.buf.length);
            if (ctx.writePos == 0) {
                return IteratorUtils.emptyIterator();
            }
            ctx.remaining = size;
            return new AbstractSequentialIterator<Map.Entry<TK, TV>>(ctx.buf[ctx.readPos]) {
                Map.Entry<TK, TV> current;

                @Override
                protected Map.Entry<TK, TV> computeNext(Map.Entry<TK, TV> current) {
                    this.current = current;
                    if (--ctx.remaining <= 0) {
                        return null;
                    }
                    while (true) {
                        if (++ctx.readPos == ctx.buf.length) {
                            readBackwards(ctx, Math.min(ctx.buf.length, ctx.remaining));
                            if (ctx.writePos == 0) {
                                return null;
                            }
                        }
                        Entry<TK, TV> entry = ctx.buf[ctx.readPos];
                        if (entry == null) {
                            continue;
                        }
                        return entry;
                    }
                }

                @Override
                public void remove() {
                    KeyValueStore.this.fastRemove(current.getKey());
                }
            };
        }
    }
}
