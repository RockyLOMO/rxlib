package org.rx.core.cache;

import com.alibaba.fastjson.JSON;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Scheduler;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Cache;
import org.rx.core.Tasks;
import org.rx.io.FileStream;
import org.rx.io.Files;
import org.rx.io.IOStream;
import org.rx.io.Serializer;

import java.io.File;
import java.io.Serializable;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import static org.rx.core.App.*;

@Slf4j
public class PersistentCache<TK, TV> extends CaffeineCache<TK, TV> {
    public static Cache DEFAULT = new PersistentCache<>("RxCache.db");
    static final String JSON_TYPE = "JSON:";

    final String snapshotFilePath;
    int lastSize;

    public PersistentCache(String snapshotFilePath) {
        super(Caffeine.newBuilder().executor(Tasks.pool()).scheduler(Scheduler.disabledScheduler())
                .softValues().expireAfterAccess(1, TimeUnit.MINUTES).maximumSize(Short.MAX_VALUE)
                .build());
        this.snapshotFilePath = snapshotFilePath;
        Tasks.run(this::loadFromDisk);
        Tasks.schedule(this::saveToDisk, 30 * 1000);
    }

    public synchronized void loadFromDisk() {
        HashMap<Serializable, Serializable> map = Serializer.DEFAULT.deserialize(new FileStream(snapshotFilePath));
        log.info("load {} items from db file {}", map.size(), new File(snapshotFilePath).getAbsolutePath());
        for (Entry<Serializable, Serializable> entry : map.entrySet()) {
            quietly(() -> put(from(entry.getKey()), from(entry.getValue())));
        }
    }

    @SneakyThrows
    private <T> T from(Serializable serializable) {
        String json = as(serializable, String.class);
        if (json != null && json.startsWith(JSON_TYPE)) {
            int i = json.indexOf(":", JSON_TYPE.length());
            return fromJson(json.substring(i + 1), Class.forName(json.substring(JSON_TYPE.length(), i)));
        }
        return (T) serializable;
    }

    public synchronized void saveToDisk() {
        int curSize = size();
        if (lastSize == curSize) {
            return;
        }

        Files.delete(snapshotFilePath);
        HashMap<Serializable, Serializable> map = new HashMap<>(curSize);
        for (Entry<TK, TV> entry : entrySet()) {
            quietly(() -> map.put(to(entry.getKey()), to(entry.getValue())));
        }
        log.info("save {} items to db file {}", map.size(), new File(snapshotFilePath).getAbsolutePath());
        Serializer.DEFAULT.serialize(map, 0, snapshotFilePath).close();
        lastSize = curSize;
    }

    private Serializable to(Object val) {
        if (val instanceof Serializable) {
            return (Serializable) val;
        }
        return String.format("%s%s:%s", JSON_TYPE, val.getClass().getName(), JSON.toJSONString(val));
    }
}
