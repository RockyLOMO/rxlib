package org.rx.core.cache;

import com.alibaba.fastjson.JSON;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Scheduler;
import lombok.SneakyThrows;
import org.rx.core.Cache;
import org.rx.core.Strings;
import org.rx.core.Tasks;
import org.rx.io.FileStream;
import org.rx.io.IOStream;

import java.io.Serializable;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import static org.rx.core.App.*;

public class PersistentCache<TK, TV> extends CaffeineCache<TK, TV> {
    public static Cache DEFAULT = new PersistentCache<>("RxCache.db");
    static final String JSON_TYPE = "JSON:";

    final String snapshotFilePath;

    public PersistentCache(String snapshotFilePath) {
        super(Caffeine.newBuilder().executor(Tasks.pool()).scheduler(Scheduler.disabledScheduler())
                .softValues().expireAfterAccess(1, TimeUnit.MINUTES)
                .build());
        this.snapshotFilePath = snapshotFilePath;
        Tasks.run(this::loadFromDisk);
        Tasks.schedule(this::saveToDisk, 60 * 1000);
    }

    public synchronized void loadFromDisk() {
        HashMap<Serializable, Serializable> map = IOStream.deserialize(new FileStream(snapshotFilePath));
        for (Entry<Serializable, Serializable> entry : map.entrySet()) {
            quietly(() -> put(from(entry.getKey()), from(entry.getValue())));
        }
    }

    @SneakyThrows
    private <T> T from(Serializable serializable) {
        String json = as(serializable, String.class);
        if (json != null && json.startsWith(JSON_TYPE)) {
            String[] pair = Strings.split(json.substring(JSON_TYPE.length()), ":", 2);
            return fromJson(pair[1], Class.forName(pair[0]));
        }
        return (T) serializable;
    }

    public synchronized void saveToDisk() {
        HashMap<Serializable, Serializable> map = new HashMap<>(size());
        for (Entry<TK, TV> entry : entrySet()) {
            quietly(() -> map.put(to(entry.getKey()), to(entry.getValue())));
        }
        IOStream.serialize(map, 0, snapshotFilePath).close();
    }

    private Serializable to(Object val) {
        if (val instanceof Serializable) {
            return (Serializable) val;
        }
        return String.format("%s:%s:%s", JSON_TYPE, val.getClass().getName(), JSON.toJSONString(val));
    }
}
