package org.rx.util.rss;

import org.junit.jupiter.api.Test;
import org.rx.AbstractTester;
import org.rx.io.EntityDatabaseImpl;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RssUserTrafficStoreTest extends AbstractTester {
    static class CountingEntityDatabaseImpl extends EntityDatabaseImpl {
        final AtomicInteger findByIdCalls = new AtomicInteger();

        public CountingEntityDatabaseImpl(String filePath, String timeRollingPattern) {
            super(filePath, timeRollingPattern);
        }

        @Override
        public <T> T findById(Class<T> entityType, java.io.Serializable id) {
            findByIdCalls.incrementAndGet();
            return super.findById(entityType, id);
        }
    }

    @Test
    public void flushAggregatesHourlyTraffic() {
        EntityDatabaseImpl db = new EntityDatabaseImpl(path("h2/rss_user_traffic"), null);
        try {
            RssUserTrafficStore store = new RssUserTrafficStore(db);
            assertEquals(60, store.retentionDays());
            ShadowUser user = new ShadowUser();
            user.setUsername("rocky");
            user.setSocksUser("socks-rocky");

            store.record(user, new InetSocketAddress("127.0.0.1", 1200), 64, 128, 2, 3);
            store.record(user, new InetSocketAddress("127.0.0.1", 1300), 16, 32, 1, 1);
            store.flush();

            long hourEpoch = System.currentTimeMillis() / RssUserTrafficStore.ONE_HOUR_MILLIS;
            RssUserTrafficStore.HourlyTrafficEntity entity = db.findById(
                    RssUserTrafficStore.HourlyTrafficEntity.class,
                    RssUserTrafficStore.HourlyTrafficEntity.idOf("rocky", hourEpoch));
            assertNotNull(entity);
            assertEquals(80L, entity.getReadBytes());
            assertEquals(160L, entity.getWriteBytes());
            assertEquals(3L, entity.getReadPackets());
            assertEquals(4L, entity.getWritePackets());
        } finally {
            db.dropMapping(RssUserTrafficStore.HourlyTrafficEntity.class);
            db.close();
        }
    }

    @Test
    public void flushUsesBatchUpsertAndAvoidsFindById() {
        CountingEntityDatabaseImpl db = new CountingEntityDatabaseImpl(path("h2/rss_user_traffic_upsert"), null);
        try {
            RssUserTrafficStore store = new RssUserTrafficStore(db);
            ShadowUser user = new ShadowUser();
            user.setUsername("rocky");
            user.setSocksUser("socks-rocky");
            InetSocketAddress remote = new InetSocketAddress("127.0.0.1", 1200);

            store.record(user, remote, 64, 128, 2, 3);
            store.recordSession(user, remote, "tcp", 30L);
            store.flush();

            store.record(user, remote, 16, 32, 1, 1);
            store.recordSession(user, remote, "tcp", 45L);
            store.flush();
            assertEquals(0, db.findByIdCalls.get());

            long hourEpoch = System.currentTimeMillis() / RssUserTrafficStore.ONE_HOUR_MILLIS;
            RssUserTrafficStore.HourlyTrafficEntity entity = db.findById(
                    RssUserTrafficStore.HourlyTrafficEntity.class,
                    RssUserTrafficStore.HourlyTrafficEntity.idOf("rocky", hourEpoch));
            assertNotNull(entity);
            assertEquals(80L, entity.getReadBytes());
            assertEquals(160L, entity.getWriteBytes());
            assertEquals(3L, entity.getReadPackets());
            assertEquals(4L, entity.getWritePackets());

            RssUserTrafficStore.HourlyLoginIpTrafficEntity loginIpEntity = db.findById(
                    RssUserTrafficStore.HourlyLoginIpTrafficEntity.class,
                    RssUserTrafficStore.HourlyLoginIpTrafficEntity.idOf("rocky", "127.0.0.1", "tcp", hourEpoch));
            assertNotNull(loginIpEntity);
            assertEquals(75L, loginIpEntity.getActiveSeconds());
            assertEquals(2L, loginIpEntity.getSessionCount());
            assertEquals(80L, loginIpEntity.getReadBytes());
            assertEquals(160L, loginIpEntity.getWriteBytes());
            assertTrue(loginIpEntity.getLastSeenTime() != null);
            assertEquals(2, db.findByIdCalls.get());
        } finally {
            db.dropMapping(RssUserTrafficStore.HourlyLoginIpTrafficEntity.class);
            db.dropMapping(RssUserTrafficStore.HourlyTrafficEntity.class);
            db.close();
        }
    }

    @Test
    public void retentionDaysUsesCurrentRssConfig() {
        RssClientConf oldConf = RssClient.rssConf;
        EntityDatabaseImpl db = new EntityDatabaseImpl(path("h2/rss_user_traffic_retention"), null);
        try {
            RssUserTrafficStore store = new RssUserTrafficStore(db, 60);
            RssClientConf conf = new RssClientConf();
            conf.trafficRetentionDays = 7;
            RssClient.rssConf = conf;

            assertEquals(7, store.retentionDays());
        } finally {
            RssClient.rssConf = oldConf;
            db.dropMapping(RssUserTrafficStore.HourlyTrafficEntity.class);
            db.close();
        }
    }
}
