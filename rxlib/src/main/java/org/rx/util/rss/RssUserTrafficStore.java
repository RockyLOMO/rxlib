package org.rx.util.rss;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.rx.annotation.DbColumn;
import org.rx.core.Tasks;
import org.rx.io.EntityDatabase;
import org.rx.net.socks.SocksUserTraffic;
import org.rx.net.socks.TrafficUser;

import java.io.Serializable;
import java.net.InetSocketAddress;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

@Slf4j
public class RssUserTrafficStore implements SocksUserTraffic.Recorder, AutoCloseable {
    static final long ONE_HOUR_MILLIS = 60L * 60L * 1000L;
    static final long DEFAULT_FLUSH_PERIOD_MILLIS = 60L * 1000L;

    @Data
    public static class HourlyTrafficEntity implements Serializable {
        private static final long serialVersionUID = -1536580854021419427L;

        @DbColumn(primaryKey = true, length = 160)
        String id;
        @DbColumn(index = DbColumn.IndexKind.INDEX_ASC, length = 128)
        String username;
        @DbColumn(index = DbColumn.IndexKind.INDEX_ASC)
        long hourEpoch;
        long readBytes;
        long writeBytes;
        long readPackets;
        long writePackets;
        Date createTime;
        Date modifyTime;

        static String idOf(String username, long hourEpoch) {
            return username + "@" + hourEpoch;
        }
    }

    static final class Counter {
        final String username;
        final long hourEpoch;
        final LongAdder readBytes = new LongAdder();
        final LongAdder writeBytes = new LongAdder();
        final LongAdder readPackets = new LongAdder();
        final LongAdder writePackets = new LongAdder();

        Counter(String username, long hourEpoch) {
            this.username = username;
            this.hourEpoch = hourEpoch;
        }
    }

    private final EntityDatabase db;
    private final AtomicReference<Map<String, Counter>> counters = new AtomicReference<>(new ConcurrentHashMap<String, Counter>());
    private volatile ScheduledFuture<?> flushTask;

    public RssUserTrafficStore(EntityDatabase db) {
        this.db = db != null ? db : EntityDatabase.DEFAULT;
        this.db.createMapping(HourlyTrafficEntity.class);
    }

    public void start() {
        if (flushTask != null) {
            return;
        }
        synchronized (this) {
            if (flushTask == null) {
                flushTask = Tasks.schedulePeriod(this::flushQuietly, DEFAULT_FLUSH_PERIOD_MILLIS);
            }
        }
    }

    @Override
    public void record(TrafficUser user, InetSocketAddress remoteAddress, long readBytes, long writeBytes, long readPackets, long writePackets) {
        if (user == null || user.isAnonymous()) {
            return;
        }
        if ((readBytes | writeBytes | readPackets | writePackets) == 0L) {
            return;
        }

        long hourEpoch = System.currentTimeMillis() / ONE_HOUR_MILLIS;
        String username = user.getUsername();
        Map<String, Counter> activeCounters = counters.get();
        Counter counter = activeCounters.get(username + '@' + hourEpoch);
        if (counter == null) {
            Counter newCounter = new Counter(username, hourEpoch);
            Counter oldCounter = ((ConcurrentHashMap<String, Counter>) activeCounters).putIfAbsent(username + '@' + hourEpoch, newCounter);
            counter = oldCounter != null ? oldCounter : newCounter;
        }
        counter.readBytes.add(readBytes);
        counter.writeBytes.add(writeBytes);
        counter.readPackets.add(readPackets);
        counter.writePackets.add(writePackets);
    }

    public void flush() {
        Map<String, Counter> pending = counters.getAndSet(new ConcurrentHashMap<String, Counter>());
        if (pending.isEmpty()) {
            return;
        }

        db.begin();
        boolean committed = false;
        try {
            for (Counter counter : pending.values()) {
                saveCounter(counter);
            }
            db.commit();
            committed = true;
        } finally {
            if (!committed) {
                db.rollback();
            }
        }
    }

    private void saveCounter(Counter counter) {
        long readBytes = counter.readBytes.sum();
        long writeBytes = counter.writeBytes.sum();
        long readPackets = counter.readPackets.sum();
        long writePackets = counter.writePackets.sum();
        if ((readBytes | writeBytes | readPackets | writePackets) == 0L) {
            return;
        }

        String id = HourlyTrafficEntity.idOf(counter.username, counter.hourEpoch);
        HourlyTrafficEntity entity = db.findById(HourlyTrafficEntity.class, id);
        boolean insert = entity == null;
        if (insert) {
            entity = new HourlyTrafficEntity();
            entity.setId(id);
            entity.setUsername(counter.username);
            entity.setHourEpoch(counter.hourEpoch);
            entity.setCreateTime(new Date());
        }
        entity.setReadBytes(entity.getReadBytes() + readBytes);
        entity.setWriteBytes(entity.getWriteBytes() + writeBytes);
        entity.setReadPackets(entity.getReadPackets() + readPackets);
        entity.setWritePackets(entity.getWritePackets() + writePackets);
        entity.setModifyTime(new Date());
        db.save(entity, insert);
    }

    void flushQuietly() {
        try {
            flush();
        } catch (Throwable e) {
            // 数据允许丢失，失败时只打日志，避免阻塞 I/O 侧统计上报。
            log.warn("flush rss user traffic fail", e);
        }
    }

    @Override
    public void close() {
        ScheduledFuture<?> task = flushTask;
        flushTask = null;
        if (task != null) {
            task.cancel(false);
        }
        flushQuietly();
    }
}
