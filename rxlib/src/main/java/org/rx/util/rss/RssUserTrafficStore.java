package org.rx.util.rss;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.rx.annotation.DbColumn;
import org.rx.bean.DateTime;
import org.rx.core.Tasks;
import org.rx.io.EntityDatabase;
import org.rx.net.socks.SocksUserTraffic;
import org.rx.net.socks.TrafficUser;

import java.net.InetAddress;
import java.io.Serializable;
import java.net.InetSocketAddress;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

@Slf4j
public class RssUserTrafficStore implements SocksUserTraffic.Recorder, AutoCloseable {
    static final long ONE_HOUR_MILLIS = 60L * 60L * 1000L;
    static final long DEFAULT_FLUSH_PERIOD_MILLIS = 60L * 1000L;
    static final int DEFAULT_RETENTION_DAYS = 15;

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

    @Data
    public static class HourlyLoginIpTrafficEntity implements Serializable {
        private static final long serialVersionUID = 5802101079684515514L;

        @DbColumn(primaryKey = true, length = 256)
        String id;
        @DbColumn(index = DbColumn.IndexKind.INDEX_ASC, length = 128)
        String username;
        @DbColumn(index = DbColumn.IndexKind.INDEX_ASC, length = 96)
        String remoteIp;
        @DbColumn(index = DbColumn.IndexKind.INDEX_ASC, length = 8)
        String protocol;
        @DbColumn(index = DbColumn.IndexKind.INDEX_ASC)
        long hourEpoch;
        long activeSeconds;
        long sessionCount;
        long readBytes;
        long writeBytes;
        long readPackets;
        long writePackets;
        Date lastSeenTime;
        Date createTime;
        Date modifyTime;

        static String idOf(String username, String remoteIp, String protocol, long hourEpoch) {
            return username + "@" + remoteIp + "@" + protocol + "@" + hourEpoch;
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

    static final class LoginIpCounter {
        final String username;
        final String remoteIp;
        final String protocol;
        final long hourEpoch;
        final LongAdder activeSeconds = new LongAdder();
        final LongAdder sessionCount = new LongAdder();
        final LongAdder readBytes = new LongAdder();
        final LongAdder writeBytes = new LongAdder();
        final LongAdder readPackets = new LongAdder();
        final LongAdder writePackets = new LongAdder();
        final AtomicReference<Date> lastSeenTime = new AtomicReference<Date>();

        LoginIpCounter(String username, String remoteIp, String protocol, long hourEpoch) {
            this.username = username;
            this.remoteIp = remoteIp;
            this.protocol = protocol;
            this.hourEpoch = hourEpoch;
        }
    }

    @Data
    public static class UserTrafficSummary {
        String username;
        long readBytes;
        long writeBytes;
        long readPackets;
        long writePackets;
        long activeSeconds;
        long sessionCount;
        Date latestTime;
    }

    @Data
    public static class LoginIpTrafficSummary {
        String username;
        String remoteIp;
        String protocol;
        long readBytes;
        long writeBytes;
        long readPackets;
        long writePackets;
        long activeSeconds;
        long sessionCount;
        Date latestTime;
    }

    @Data
    public static class ProtocolTrafficSummary {
        String username;
        String protocol;
        long readBytes;
        long writeBytes;
        long readPackets;
        long writePackets;
        long activeSeconds;
        long sessionCount;
        long activeIpCount;
        Date latestTime;
    }

    private final EntityDatabase db;
    private final AtomicReference<Map<String, Counter>> counters = new AtomicReference<>(new ConcurrentHashMap<String, Counter>());
    private final AtomicReference<Map<String, LoginIpCounter>> loginIpCounters = new AtomicReference<>(new ConcurrentHashMap<String, LoginIpCounter>());
    private volatile ScheduledFuture<?> flushTask;
    private volatile long lastCleanupHourEpoch = Long.MIN_VALUE;

    public RssUserTrafficStore(EntityDatabase db) {
        this.db = db != null ? db : EntityDatabase.DEFAULT;
        this.db.createMapping(HourlyTrafficEntity.class, HourlyLoginIpTrafficEntity.class);
    }

    public void start() {
        if (flushTask != null) {
            return;
        }
        synchronized (this) {
            if (flushTask == null) {
                cleanupExpiredIfDueQuietly();
                flushTask = Tasks.schedulePeriod(this::flushQuietly, DEFAULT_FLUSH_PERIOD_MILLIS);
            }
        }
    }

    @Override
    public void record(TrafficUser user, InetSocketAddress remoteAddress, long readBytes, long writeBytes, long readPackets, long writePackets) {
        record(user, remoteAddress, SocksUserTraffic.PROTOCOL_TCP, readBytes, writeBytes, readPackets, writePackets);
    }

    @Override
    public void record(TrafficUser user, InetSocketAddress remoteAddress, String protocol,
                       long readBytes, long writeBytes, long readPackets, long writePackets) {
        if (user == null || user.isAnonymous()) {
            return;
        }
        if ((readBytes | writeBytes | readPackets | writePackets) == 0L) {
            return;
        }

        long hourEpoch = System.currentTimeMillis() / ONE_HOUR_MILLIS;
        String username = user.getUsername();
        String protocolName = protocol == null ? SocksUserTraffic.PROTOCOL_TCP : protocol;
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

        String remoteIp = normalizeRemoteIp(remoteAddress);
        if (remoteIp != null) {
            Map<String, LoginIpCounter> activeLoginCounters = loginIpCounters.get();
            String loginKey = HourlyLoginIpTrafficEntity.idOf(username, remoteIp, protocolName, hourEpoch);
            LoginIpCounter loginCounter = activeLoginCounters.get(loginKey);
            if (loginCounter == null) {
                LoginIpCounter newCounter = new LoginIpCounter(username, remoteIp, protocolName, hourEpoch);
                LoginIpCounter oldCounter = ((ConcurrentHashMap<String, LoginIpCounter>) activeLoginCounters).putIfAbsent(loginKey, newCounter);
                loginCounter = oldCounter != null ? oldCounter : newCounter;
            }
            loginCounter.readBytes.add(readBytes);
            loginCounter.writeBytes.add(writeBytes);
            loginCounter.readPackets.add(readPackets);
            loginCounter.writePackets.add(writePackets);
        }
    }

    @Override
    public void recordSession(TrafficUser user, InetSocketAddress remoteAddress, String protocol, long activeSeconds) {
        if (user == null || user.isAnonymous()) {
            return;
        }
        String remoteIp = normalizeRemoteIp(remoteAddress);
        if (remoteIp == null) {
            return;
        }
        String protocolName = protocol == null ? SocksUserTraffic.PROTOCOL_TCP : protocol;
        long hourEpoch = System.currentTimeMillis() / ONE_HOUR_MILLIS;
        Map<String, LoginIpCounter> activeLoginCounters = loginIpCounters.get();
        String loginKey = HourlyLoginIpTrafficEntity.idOf(user.getUsername(), remoteIp, protocolName, hourEpoch);
        LoginIpCounter loginCounter = activeLoginCounters.get(loginKey);
        if (loginCounter == null) {
            LoginIpCounter newCounter = new LoginIpCounter(user.getUsername(), remoteIp, protocolName, hourEpoch);
            LoginIpCounter oldCounter = ((ConcurrentHashMap<String, LoginIpCounter>) activeLoginCounters).putIfAbsent(loginKey, newCounter);
            loginCounter = oldCounter != null ? oldCounter : newCounter;
        }
        loginCounter.sessionCount.increment();
        if (activeSeconds > 0L) {
            loginCounter.activeSeconds.add(activeSeconds);
        }
        loginCounter.lastSeenTime.set(new Date());
    }

    public void flush() {
        Map<String, Counter> pending = counters.getAndSet(new ConcurrentHashMap<String, Counter>());
        Map<String, LoginIpCounter> pendingLoginIps = loginIpCounters.getAndSet(new ConcurrentHashMap<String, LoginIpCounter>());
        if (pending.isEmpty() && pendingLoginIps.isEmpty()) {
            cleanupExpiredIfDueQuietly();
            return;
        }

        db.begin();
        boolean committed = false;
        try {
            for (Counter counter : pending.values()) {
                saveCounter(counter);
            }
            for (LoginIpCounter counter : pendingLoginIps.values()) {
                saveLoginIpCounter(counter);
            }
            db.commit();
            committed = true;
        } finally {
            if (!committed) {
                db.rollback();
            }
        }
        cleanupExpiredIfDueQuietly();
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

    private void saveLoginIpCounter(LoginIpCounter counter) {
        long activeSeconds = counter.activeSeconds.sum();
        long sessionCount = counter.sessionCount.sum();
        long readBytes = counter.readBytes.sum();
        long writeBytes = counter.writeBytes.sum();
        long readPackets = counter.readPackets.sum();
        long writePackets = counter.writePackets.sum();
        Date lastSeenTime = counter.lastSeenTime.get();
        if ((activeSeconds | sessionCount | readBytes | writeBytes | readPackets | writePackets) == 0L && lastSeenTime == null) {
            return;
        }

        String id = HourlyLoginIpTrafficEntity.idOf(counter.username, counter.remoteIp, counter.protocol, counter.hourEpoch);
        HourlyLoginIpTrafficEntity entity = db.findById(HourlyLoginIpTrafficEntity.class, id);
        boolean insert = entity == null;
        if (insert) {
            entity = new HourlyLoginIpTrafficEntity();
            entity.setId(id);
            entity.setUsername(counter.username);
            entity.setRemoteIp(counter.remoteIp);
            entity.setProtocol(counter.protocol);
            entity.setHourEpoch(counter.hourEpoch);
            entity.setCreateTime(new Date());
        }
        entity.setActiveSeconds(entity.getActiveSeconds() + activeSeconds);
        entity.setSessionCount(entity.getSessionCount() + sessionCount);
        entity.setReadBytes(entity.getReadBytes() + readBytes);
        entity.setWriteBytes(entity.getWriteBytes() + writeBytes);
        entity.setReadPackets(entity.getReadPackets() + readPackets);
        entity.setWritePackets(entity.getWritePackets() + writePackets);
        if (lastSeenTime != null && (entity.getLastSeenTime() == null || entity.getLastSeenTime().before(lastSeenTime))) {
            entity.setLastSeenTime(lastSeenTime);
        }
        entity.setModifyTime(new Date());
        db.save(entity, insert);
    }

    public int retentionDays() {
        return DEFAULT_RETENTION_DAYS;
    }

    public List<UserTrafficSummary> queryUserSummaries(long fromMillis, long toMillis) {
        final long fromHour = normalizeFromHour(fromMillis);
        final long toHour = normalizeToHour(fromMillis, toMillis);
        final String trafficTable = db.tableName(HourlyTrafficEntity.class);
        final String loginIpTable = db.tableName(HourlyLoginIpTrafficEntity.class);
        final Map<String, UserTrafficSummary> result = new ConcurrentHashMap<String, UserTrafficSummary>();

        db.withConnection(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement("SELECT username, SUM(read_bytes) AS total_read_bytes, SUM(write_bytes) AS total_write_bytes, SUM(read_packets) AS total_read_packets, SUM(write_packets) AS total_write_packets FROM "
                    + trafficTable + " WHERE hour_epoch BETWEEN ? AND ? GROUP BY username")) {
                stmt.setLong(1, fromHour);
                stmt.setLong(2, toHour);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        UserTrafficSummary row = result.get(rs.getString(1));
                        if (row == null) {
                            row = new UserTrafficSummary();
                            row.setUsername(rs.getString(1));
                            result.put(row.getUsername(), row);
                        }
                        row.setReadBytes(rs.getLong(2));
                        row.setWriteBytes(rs.getLong(3));
                        row.setReadPackets(rs.getLong(4));
                        row.setWritePackets(rs.getLong(5));
                    }
                }
            }
        });

        db.withConnection(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement("SELECT username, SUM(active_seconds) AS total_active_seconds, SUM(session_count) AS total_session_count, MAX(last_seen_time) AS latest_time FROM "
                    + loginIpTable + " WHERE hour_epoch BETWEEN ? AND ? GROUP BY username")) {
                stmt.setLong(1, fromHour);
                stmt.setLong(2, toHour);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String username = rs.getString(1);
                        UserTrafficSummary row = result.get(username);
                        if (row == null) {
                            row = new UserTrafficSummary();
                            row.setUsername(username);
                            result.put(username, row);
                        }
                        row.setActiveSeconds(rs.getLong(2));
                        row.setSessionCount(rs.getLong(3));
                        Timestamp latest = rs.getTimestamp(4);
                        if (latest != null) {
                            row.setLatestTime(new Date(latest.getTime()));
                        }
                    }
                }
            }
        });

        return new ArrayList<UserTrafficSummary>(result.values());
    }

    public List<LoginIpTrafficSummary> queryLoginIpSummaries(long fromMillis, long toMillis) {
        final long fromHour = normalizeFromHour(fromMillis);
        final long toHour = normalizeToHour(fromMillis, toMillis);
        final String loginIpTable = db.tableName(HourlyLoginIpTrafficEntity.class);
        final List<LoginIpTrafficSummary> rows = new ArrayList<LoginIpTrafficSummary>();

        db.withConnection(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement("SELECT username, remote_ip, protocol, SUM(read_bytes) AS total_read_bytes, SUM(write_bytes) AS total_write_bytes, SUM(read_packets) AS total_read_packets, SUM(write_packets) AS total_write_packets, SUM(active_seconds) AS total_active_seconds, SUM(session_count) AS total_session_count, MAX(last_seen_time) AS latest_time FROM "
                    + loginIpTable + " WHERE hour_epoch BETWEEN ? AND ? GROUP BY username, remote_ip, protocol ORDER BY username ASC, protocol ASC, latest_time DESC, remote_ip ASC")) {
                stmt.setLong(1, fromHour);
                stmt.setLong(2, toHour);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        LoginIpTrafficSummary row = new LoginIpTrafficSummary();
                        row.setUsername(rs.getString(1));
                        row.setRemoteIp(rs.getString(2));
                        row.setProtocol(rs.getString(3));
                        row.setReadBytes(rs.getLong(4));
                        row.setWriteBytes(rs.getLong(5));
                        row.setReadPackets(rs.getLong(6));
                        row.setWritePackets(rs.getLong(7));
                        row.setActiveSeconds(rs.getLong(8));
                        row.setSessionCount(rs.getLong(9));
                        Timestamp latest = rs.getTimestamp(10);
                        if (latest != null) {
                            row.setLatestTime(new Date(latest.getTime()));
                        }
                        rows.add(row);
                    }
                }
            }
        });
        return rows;
    }

    public List<ProtocolTrafficSummary> queryProtocolSummaries(long fromMillis, long toMillis) {
        final long fromHour = normalizeFromHour(fromMillis);
        final long toHour = normalizeToHour(fromMillis, toMillis);
        final String loginIpTable = db.tableName(HourlyLoginIpTrafficEntity.class);
        final List<ProtocolTrafficSummary> rows = new ArrayList<ProtocolTrafficSummary>();

        db.withConnection(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement("SELECT username, protocol, SUM(read_bytes) AS total_read_bytes, SUM(write_bytes) AS total_write_bytes, SUM(read_packets) AS total_read_packets, SUM(write_packets) AS total_write_packets, SUM(active_seconds) AS total_active_seconds, SUM(session_count) AS total_session_count, COUNT(DISTINCT remote_ip) AS active_ip_count, MAX(last_seen_time) AS latest_time FROM "
                    + loginIpTable + " WHERE hour_epoch BETWEEN ? AND ? GROUP BY username, protocol ORDER BY username ASC, protocol ASC")) {
                stmt.setLong(1, fromHour);
                stmt.setLong(2, toHour);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        ProtocolTrafficSummary row = new ProtocolTrafficSummary();
                        row.setUsername(rs.getString(1));
                        row.setProtocol(rs.getString(2));
                        row.setReadBytes(rs.getLong(3));
                        row.setWriteBytes(rs.getLong(4));
                        row.setReadPackets(rs.getLong(5));
                        row.setWritePackets(rs.getLong(6));
                        row.setActiveSeconds(rs.getLong(7));
                        row.setSessionCount(rs.getLong(8));
                        row.setActiveIpCount(rs.getLong(9));
                        Timestamp latest = rs.getTimestamp(10);
                        if (latest != null) {
                            row.setLatestTime(new Date(latest.getTime()));
                        }
                        rows.add(row);
                    }
                }
            }
        });
        return rows;
    }

    void cleanupExpiredIfDueQuietly() {
        try {
            cleanupExpiredIfDue();
        } catch (Throwable e) {
            log.warn("cleanup rss user traffic fail", e);
        }
    }

    void cleanupExpired() {
        long expireBeforeHour = System.currentTimeMillis() / ONE_HOUR_MILLIS - DEFAULT_RETENTION_DAYS * 24L;
        String trafficTable = db.tableName(HourlyTrafficEntity.class);
        String loginIpTable = db.tableName(HourlyLoginIpTrafficEntity.class);
        db.executeUpdate(String.format("DELETE FROM %s WHERE hour_epoch < %s", trafficTable, expireBeforeHour));
        db.executeUpdate(String.format("DELETE FROM %s WHERE hour_epoch < %s", loginIpTable, expireBeforeHour));
        lastCleanupHourEpoch = System.currentTimeMillis() / ONE_HOUR_MILLIS;
    }

    private void cleanupExpiredIfDue() {
        long nowHourEpoch = System.currentTimeMillis() / ONE_HOUR_MILLIS;
        if (nowHourEpoch == lastCleanupHourEpoch) {
            return;
        }
        cleanupExpired();
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

    private static long normalizeFromHour(long fromMillis) {
        return Math.max(0L, fromMillis / ONE_HOUR_MILLIS);
    }

    private static long normalizeToHour(long fromMillis, long toMillis) {
        long normalizedTo = toMillis > 0L ? toMillis : System.currentTimeMillis();
        if (normalizedTo < fromMillis) {
            normalizedTo = fromMillis;
        }
        return Math.max(normalizeFromHour(fromMillis), normalizedTo / ONE_HOUR_MILLIS);
    }

    private static String normalizeRemoteIp(InetSocketAddress remoteAddress) {
        if (remoteAddress == null) {
            return null;
        }
        InetAddress address = remoteAddress.getAddress();
        if (address != null) {
            return address.getHostAddress();
        }
        String hostString = remoteAddress.getHostString();
        return hostString == null || hostString.length() == 0 ? null : hostString;
    }
}
