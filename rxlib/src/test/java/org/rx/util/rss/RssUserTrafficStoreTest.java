package org.rx.util.rss;

import org.junit.jupiter.api.Test;
import org.rx.AbstractTester;
import org.rx.io.EntityDatabaseImpl;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class RssUserTrafficStoreTest extends AbstractTester {
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
    public void retentionDaysUsesCurrentRssConfig() {
        RSSConf oldConf = RssClient.rssConf;
        EntityDatabaseImpl db = new EntityDatabaseImpl(path("h2/rss_user_traffic_retention"), null);
        try {
            RssUserTrafficStore store = new RssUserTrafficStore(db, 60);
            RSSConf conf = new RSSConf();
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
