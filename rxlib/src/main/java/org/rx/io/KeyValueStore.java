package org.rx.io;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.IteratorUtils;
import org.rx.bean.$;
import org.rx.bean.AbstractMap;
import org.rx.codec.CodecUtil;
import org.rx.core.Disposable;
import org.rx.core.Reflects;
import org.rx.core.Strings;
import org.rx.exception.ExceptionLevel;
import org.rx.exception.InvalidException;
import org.rx.net.http.HttpServer;
import org.rx.net.http.ServerRequest;
import org.rx.third.guava.AbstractSequentialIterator;

import java.io.*;
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
 * <p>index
 * crc64(8) + pos(8)
 *
 * <p>log
 * key + value + size(4)
 *
 * <p>减少文件，二分查找
 */
@Slf4j
public class KeyValueStore<TK, TV> extends Disposable implements AbstractMap<TK, TV>, Iterable<Map.Entry<TK, TV>> {
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
            Compressible k = as(key, Compressible.class);
            Compressible v = as(value, Compressible.class);
            return (k != null && k.enableCompress()) || (v != null && v.enableCompress());
        }
    }

    @RequiredArgsConstructor
    enum EntryStatus {
        NORMAL((byte) 0),
        DELETE((byte) 1);

        final byte value;
    }

    @RequiredArgsConstructor
    class IteratorContext {
        long logPos = wal.meta.getLogPosition();
        final Set<TK> filter;
        final Entry<TK, TV>[] buf;
        int writePos;
        int readPos;
        int remaining;
    }

    static final int TOMB_MARK = -1;
    static final int DEFAULT_ITERATOR_SIZE = 50;
    static final String KEY_TYPE_FIELD = "_KEY_TYPE", VALUE_TYPE_FIELD = "_VAL_TYPE";
    static final Map<Class<?>, KeyValueStore> instances = new ConcurrentHashMap<>();

    public static <TK, TV> KeyValueStore<TK, TV> getInstance(Class<TK> keyType, Class<TV> valueType) {
        return instances.computeIfAbsent(keyType, k -> new KeyValueStore<>(KeyValueStoreConfig.newConfig(keyType, valueType)));
    }

    final KeyValueStoreConfig config;
    final File parentDirectory;
    final String filename;
    final WALFileStream wal;
    final HashKeyIndexer<TK> indexer;
    final Serializer serializer;
    final HttpServer apiServer;

    File getIndexDirectory() {
        File dir = new File(parentDirectory, Files.changeExtension(filename, "idx"));
        dir.mkdirs();
        return dir;
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
        String name = config.getKeyType().getSimpleName() + config.getValueType().getSimpleName();
        filename = String.format("%s.log", CodecUtil.hashUnsigned64(name));
        File logFile = new File(String.format("%s/%s", config.getDirectoryPath(), filename));
        wal = new WALFileStream(logFile, config.getLogGrowSize(), config.getLogReaderCount(), serializer);
        wal.setFlushDelayMillis(config.getFlushDelayMillis());
        java.nio.file.Files.setAttribute(logFile.toPath(), "kvName", name);
        this.serializer = serializer;

        indexer = new HashKeyIndexer<>(getIndexDirectory(), config.getIndexSlotSize(), config.getIndexGrowSize());

        wal.lock.writeInvoke(() -> {
            long pos = wal.meta.getLogPosition();
            log.debug("init logPos={}", pos);
//            pos = WALFileStream.HEADER_SIZE;
            Entry<TK, TV> val;
            $<Long> endPos = $();
            while ((val = findValue(pos, null, endPos)) != null) {
                boolean incr = false;
                TK k = val.key;
                HashKeyIndexer.KeyData<TK> key = indexer.findKey(k);
                if (key == null) {
                    key = new HashKeyIndexer.KeyData<>(k);
                    incr = true;
                }
                if (key.logPosition == TOMB_MARK) {
                    incr = true;
                }

                key.logPosition = val.value == null ? TOMB_MARK : pos;
                wal.meta.setLogPosition(endPos.v);
                indexer.saveKey(key);

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
            apiServer = new HttpServer(config.getApiPort(), config.isApiSsl())
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
                                resJson.put("top", iterator());
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
        } else {
            apiServer = null;
        }
    }

    @Override
    protected void freeObjects() {
        indexer.close();
        wal.close();
    }

    private void apiCheck(ServerRequest req) {
        if (Strings.isEmpty(config.getApiPassword())) {
            return;
        }
        if (!eq(config.getApiPassword(), req.getHeaders().get("apiPassword"))) {
            throw new InvalidException(ExceptionLevel.USER_OPERATION, "{} auth fail", req.getRemoteEndpoint());
        }
    }

    @SneakyThrows
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

    protected void write(TK k, TV v) {
        boolean incr = false;
        HashKeyIndexer.KeyData<TK> key = indexer.findKey(k);
        if (key == null) {
            key = new HashKeyIndexer.KeyData<>(k);
            incr = true;
        }
        if (key.logPosition == TOMB_MARK) {
            if (v == null) {
                return;
            }
            incr = true;
        }

        Entry<TK, TV> val = new Entry<>(k, v);
        HashKeyIndexer.KeyData<TK> finalKey = key;
        wal.lock.writeInvoke(() -> {
            saveValue(finalKey, val);
            indexer.saveKey(finalKey);
        });
        if (incr) {
            wal.meta.incrementSize();
        }
    }

    protected TV delete(TK k) {
        HashKeyIndexer.KeyData<TK> key = indexer.findKey(k);
        if (key == null || key.logPosition == TOMB_MARK) {
            return null;
        }
        Entry<TK, TV> val = findValue(key);
        if (val == null) {
            return null;
        }

        val.value = null;
        wal.lock.writeInvoke(() -> {
            saveValue(key, val);
            indexer.saveKey(key);
        });
        wal.meta.decrementSize();
        return val.value;
    }

    protected TV read(TK k) {
        HashKeyIndexer.KeyData<TK> key = indexer.findKey(k);
        if (key == null || key.logPosition == TOMB_MARK) {
            return null;
        }

        Entry<TK, TV> val = findValue(key);
        return val != null ? val.value : null;
    }

    private void saveValue(HashKeyIndexer.KeyData<TK> key, Entry<TK, TV> value) {
        checkNotClosed();
        require(key, !(key.logPosition == TOMB_MARK && value.value == null));

        key.logPosition = wal.meta.getLogPosition();
        serializer.serialize(value, wal);
        int size = (int) (wal.meta.getLogPosition() - key.logPosition);
        wal.writeInt(size);
        if (value.value == null) {
            log.debug("LogPos auto set TOMB_MARK {}", key);
            key.logPosition = TOMB_MARK;
        }
        log.debug("saveValue {} {}", key, value);
    }

    private Entry<TK, TV> findValue(HashKeyIndexer.KeyData<TK> key) {
//        require(key, key.logPosition >= 0);
        if (key.logPosition == TOMB_MARK) {
            log.warn("LogPosError {} == TOMB_MARK", key);
            return null;
        }
        if (key.logPosition > wal.meta.getLogPosition()) {
            log.warn("LogPosError {} > {}", key, wal.meta.getLogPosition());
            key.logPosition = TOMB_MARK;
            indexer.saveKey(key);
            return null;
        }

        return findValue(key.logPosition, key.key, null);
    }

    @SneakyThrows
    private Entry<TK, TV> findValue(long logPosition, TK k, $<Long> position) {
        String kStr = k == null ? "NULL" : k.toString();
        log.debug("findValue {} {}", kStr, logPosition);
        Entry<TK, TV> val = null;
        try {
            wal.setReaderPosition(logPosition);
            val = serializer.deserialize(wal, true);
        } catch (Exception e) {
            if (e instanceof StreamCorruptedException
//                    | e instanceof EOFException
            ) {
                log.error("findValue {} {}", kStr, logPosition, e);
            } else {
                throw e;
            }
        } finally {
            long readerPosition = wal.getReaderPosition(true);
            if (position != null) {
                position.v = readerPosition;
            }
        }
        if (val == null || val.value == null) {
            return null;
        }
        if (k != null && !k.equals(val.key)) {
            AtomicInteger counter = (AtomicInteger) wal.meta.extra;
            int total = counter == null ? -1 : counter.incrementAndGet();
            log.warn("LogPosError hash collision {} total={}", k, total);
            Files.createDirectory("./hc.err");
            return null;
        }
        return val;
    }

    @Override
    public Iterator<Map.Entry<TK, TV>> iterator() {
        return iterator(0, DEFAULT_ITERATOR_SIZE);
    }

    public Iterator<Map.Entry<TK, TV>> iterator(int offset, int size) {
        require(offset, offset >= 0);
        require(size, size >= 0);

        int total = size();
        if (total <= offset) {
            return IteratorUtils.emptyIterator();
        }

        IteratorContext ctx = new IteratorContext(new HashSet<>(offset + size), new Entry[config.getIteratorPrefetchCount()]);
        if (offset > 0 && !backwardFindValue(ctx, offset)) {
            return IteratorUtils.emptyIterator();
        }
        backwardFindValue(ctx, ctx.buf.length);
        if (ctx.writePos == 0) {
            return IteratorUtils.emptyIterator();
        }
        ctx.remaining = size;
        return new AbstractSequentialIterator<Map.Entry<TK, TV>>(ctx.buf[ctx.readPos]) {
            @Override
            protected Map.Entry<TK, TV> computeNext(Map.Entry<TK, TV> current) {
                if (--ctx.remaining <= 0) {
                    return null;
                }
                while (true) {
                    if (++ctx.readPos == ctx.buf.length) {
                        backwardFindValue(ctx, Math.min(ctx.buf.length, ctx.remaining));
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
        };
    }

    private boolean backwardFindValue(IteratorContext ctx, int prefetchCount) {
        wal.setReaderPosition(ctx.logPos); //4 lock
        return wal.backwardReadObject(reader -> {
            ctx.writePos = ctx.readPos = 0;
            for (int i = 0; i < prefetchCount; i++) {
                if (ctx.logPos <= WALFileStream.HEADER_SIZE) {
                    ctx.remaining = 0;
                    return false;
                }

                ctx.logPos -= 4;
                reader.setPosition(ctx.logPos);
                try {
                    int size = reader.readInt();
                    log.debug("contentLength: {}", size);
                    ctx.logPos -= size;

                    reader.setPosition(ctx.logPos);

                    Entry<TK, TV> val = serializer.deserialize(reader, true);
                    log.debug("read {}", val);
                    if (!ctx.filter.add(val.key)) {
                        log.debug("skip read {}", val.key);
                        val = null;
                    }
                    ctx.buf[ctx.writePos++] = val;
                } catch (Exception e) {
                    if (e instanceof StreamCorruptedException | e instanceof EOFException) {
                        ctx.remaining = 0;
                        return false;
                    }
                    throw e;
                }
            }
            return true;
        });
    }

    @Override
    public int size() {
        return wal.meta.getSize();
    }

    @Override
    public boolean containsKey(Object key) {
        TK k = (TK) key;
        return indexer.findKey(k) != null;
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
            write(key, value);
        }
        return old;
    }

    @Override
    public TV remove(Object key) {
        TK k = (TK) key;
        return delete(k);
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
        return new LinkedHashSet<>(IteratorUtils.toList(iterator(offset, size)));
    }
}
