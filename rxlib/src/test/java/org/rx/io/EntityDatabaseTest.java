package org.rx.io;

import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.rx.AbstractTester;
import org.rx.annotation.DbColumn;
import org.rx.bean.*;
import org.rx.core.*;
import org.rx.core.cache.H2CacheItem;
import org.rx.exception.TraceHandler;
import org.rx.test.PersonBean;
import org.rx.test.PersonGender;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.rx.core.Extends.sleep;
import static org.rx.core.Sys.toJsonString;

@Slf4j
public class EntityDatabaseTest extends AbstractTester {
    static final String h2Db = path("h2/test");

    @Data
    public static class UserAuth implements Serializable {
        @DbColumn(primaryKey = true, autoIncrement = true)
        Long id;
        String tableX;
        Long rowId;
        String json;
    }

    @Data
    public static class SaveEntity implements Serializable {
        @DbColumn(primaryKey = true)
        String id;
        String name;
        Integer age;
    }

    static class CountingEntityDatabaseImpl extends EntityDatabaseImpl {
        int existsByIdCalls;

        public CountingEntityDatabaseImpl(String filePath, String timeRollingPattern) {
            super(filePath, timeRollingPattern);
        }

        @Override
        public <T> boolean existsById(Class<T> entityType, Serializable id) {
            existsByIdCalls++;
            return super.existsById(entityType, id);
        }
    }

    @Test
    public void testDefaultMaxConnectionsUsesRxConfig() {
        RxConfig conf = RxConfig.INSTANCE;
        int oldMaxConnections = conf.getStorage().getEntityDatabaseMaxConnections();
        try {
            conf.refreshFrom(Collections.<String, Object>singletonMap(RxConfig.ConfigNames.STORAGE_ENTITY_DATABASE_MAX_CONNECTIONS, 3));
            EntityDatabaseImpl db = new EntityDatabaseImpl(path("h2/max-conn-default"), null);
            try {
                assertEquals(3, db.maxConnections);
            } finally {
                db.close();
            }
        } finally {
            conf.refreshFrom(Collections.<String, Object>singletonMap(RxConfig.ConfigNames.STORAGE_ENTITY_DATABASE_MAX_CONNECTIONS, oldMaxConnections));
        }
    }

    @Test
    public void testExplicitMaxConnectionsOverridesRxConfig() {
        RxConfig conf = RxConfig.INSTANCE;
        int oldMaxConnections = conf.getStorage().getEntityDatabaseMaxConnections();
        try {
            conf.refreshFrom(Collections.<String, Object>singletonMap(RxConfig.ConfigNames.STORAGE_ENTITY_DATABASE_MAX_CONNECTIONS, 3));
            EntityDatabaseImpl db = new EntityDatabaseImpl(path("h2/max-conn-explicit"), null, 6);
            try {
                assertEquals(6, db.maxConnections);
            } finally {
                db.close();
            }
        } finally {
            conf.refreshFrom(Collections.<String, Object>singletonMap(RxConfig.ConfigNames.STORAGE_ENTITY_DATABASE_MAX_CONNECTIONS, oldMaxConnections));
        }
    }

    @Test
    public void testInheritedExpirationColumnCreatesIndex() {
        EntityDatabaseImpl db = new EntityDatabaseImpl(path("h2/cache_item_index"), null);
        db.createMapping(H2CacheItem.class);
        try {
            String tableName = db.tableName(H2CacheItem.class).toUpperCase();
            String indexName = db.indexName(tableName, "expiration").toUpperCase();
            DataTable dt = db.executeQuery(String.format("SELECT INDEX_NAME, COLUMN_NAME FROM INFORMATION_SCHEMA.INDEX_COLUMNS WHERE UPPER(TABLE_NAME)='%s' AND UPPER(COLUMN_NAME)='EXPIRATION'", tableName));
            boolean found = false;
            for (DataRow row : dt.getRows()) {
                String actualIndexName = row.get("INDEX_NAME");
                String columnName = row.get("COLUMN_NAME");
                if (indexName.equals(actualIndexName) && "EXPIRATION".equals(columnName)) {
                    found = true;
                    break;
                }
            }
            assertTrue(found);
        } finally {
            db.dropMapping(H2CacheItem.class);
            db.close();
        }
    }

//    @SneakyThrows
//    @Test
//    public synchronized void h2ShardingDb() {
//        // Use a more explicit constructor to avoid ambiguity and provide a valid local endpoint if possible
//        EntityDatabase db1 = new ShardingEntityDatabase(path("h2/s1"), null, 0, 3306, "127.0.0.1:854");
//
//        db1.createMapping(PersonBean.class);
//        for (int i = 0; i < 10; i++) {
//            PersonBean personBean = new PersonBean();
//            personBean.setIndex(i);
//            personBean.setName("老王" + i);
//            if (i % 2 == 0) {
//                personBean.setGender(PersonGender.GIRL);
//                personBean.setFlags(PersonBean.PROP_Flags);
//                personBean.setExtra(PersonBean.PROP_EXTRA);
//            } else {
//                personBean.setGender(PersonGender.BOY);
//            }
//            db1.save(personBean);
//        }
//
//        List<PersonBean> result = db1.findBy(new EntityQueryLambda<>(PersonBean.class));
//        System.out.println(result);
//        db1.dropMapping(PersonBean.class);
//        db1.close();
//    }

    @Test
    public synchronized void h2DbShardingMethod() {
        EntityDatabaseImpl db = new EntityDatabaseImpl(path("h2/sharding"), null);
        db.createMapping(PersonBean.class);

        try {
            for (int i = 0; i < 10; i++) {
                PersonBean personBean = new PersonBean();
                personBean.setIndex(i);
                personBean.setName("老王" + i);
                if (i % 2 == 0) {
                    personBean.setGender(PersonGender.GIRL);
                    personBean.setFlags(PersonBean.PROP_Flags);
                    personBean.setExtra(PersonBean.PROP_EXTRA);
                } else {
                    personBean.setGender(PersonGender.BOY);
                }
                db.save(personBean);
            }

            DataTable dt1, dt2, dt;
            String querySql = "select id, index, name from person where 1=1 and name != '' order by gender";

            dt1 = db.executeQuery(querySql + " limit 0,5", PersonBean.class);
            dt2 = db.executeQuery(querySql + " limit 5,5", PersonBean.class);
            System.out.println(dt1);
            System.out.println(dt2);
            dt = EntityDatabaseImpl.sharding(Arrays.toList(dt1, dt2), querySql);
            System.out.println(dt);

            querySql = "select sum(index), gender, count(1) count, count(*) count2, count(id)  count3 from person where 1=1 and name!='' group by gender order by sum(index) asc";
            dt1 = db.executeQuery(querySql + " limit 0,5", PersonBean.class);
            dt2 = db.executeQuery(querySql + " limit 5", PersonBean.class);
            System.out.println(dt1);
            System.out.println(dt2);

            dt = EntityDatabaseImpl.sharding(Arrays.toList(dt1, dt2), querySql);
            System.out.println(dt);
        } finally {
            db.dropMapping(PersonBean.class);
            db.close();
        }
    }

    @SneakyThrows
    @Test
    public synchronized void h2DbReduce() {
        EntityDatabaseImpl db = new EntityDatabaseImpl(path("h2/reduce"), "yyyyMMddHH");
        db.setRollingHours(0);
        db.createMapping(PersonBean.class);
        for (int i = 0; i < 1000; i++) {
            db.save(new PersonBean());
        }
        db.compact();
        db.clearTimeRollingFiles();
        Tasks.setTimeout(() -> {
            db.save(new PersonBean());
        }, 1000);
        sleep(1500); // Wait for the timeout task to run once
        db.dropMapping(PersonBean.class);
        db.close();
    }

    @Test
    public void h2DbAutoIncr() {
        EntityDatabaseImpl db = new EntityDatabaseImpl(path("h2/autoinc"), null);
        db.createMapping(UserAuth.class);

        try {
            for (int i = 0; i < 2; i++) {
                UserAuth ua = new UserAuth();
                ua.setTableX("t_usr_auth");
                ua.setRowId(i + 1L);
                ua.setJson("{\"time\":\"" + DateTime.now() + "\"}");
                db.save(ua);
            }

            System.out.println(db.findBy(new EntityQueryLambda<>(UserAuth.class)));
        } finally {
            db.dropMapping(UserAuth.class);
            db.close();
        }
    }

    @Test
    public void h2Db() {
        EntityDatabaseImpl db = new EntityDatabaseImpl(path("h2/crud"), null);
        db.createMapping(PersonBean.class);
        System.out.println(db.executeQuery("EXPLAIN select * from person"));

        db.begin();
        db.save(PersonBean.YouFan);
        PersonBean entity = PersonBean.LeZhi;
        db.save(entity);

        EntityQueryLambda<PersonBean> queryLambda = new EntityQueryLambda<>(PersonBean.class).eq(PersonBean::getName, "乐之").orderBy(PersonBean::getId).limit(10);
        assert db.exists(queryLambda);
        db.commit();

        System.out.println(db.executeQuery("select * from `person` limit 2", PersonBean.class));
        System.out.println(db.count(queryLambda));
        List<PersonBean> list = db.findBy(queryLambda);
        assert !list.isEmpty();
        ULID pk = entity.getId();
        assert db.existsById(PersonBean.class, pk);
        PersonBean byId = db.findById(PersonBean.class, pk);
        System.out.println(byId);
        assert byId != null;

        db.delete(new EntityQueryLambda<>(PersonBean.class).lt(PersonBean::getId, null));

        EntityQueryLambda<PersonBean> q = new EntityQueryLambda<>(PersonBean.class).eq(PersonBean::getName, "张三").in(PersonBean::getIndex, 1, 2, 3).between(PersonBean::getAge, 6, 14).notLike(PersonBean::getName, "王%");
        q.and(q.newClause().ne(PersonBean::getAge, 10).ne(PersonBean::getAge, 11)).or(q.newClause().ne(PersonBean::getAge, 12).ne(PersonBean::getAge, 13).orderByDescending(PersonBean::getCash)).orderBy(PersonBean::getAge).limit(100);
        System.out.println(q);
        List<Object> params = new ArrayList<>();
        System.out.println(q.toString(params));
        System.out.println(toJsonString(params));
        System.out.println(q.orderByRand());

        List<Object> paramsX = new ArrayList<>();
        String sql = new EntityQueryLambda<>(TraceHandler.ExceptionEntity.class)
                .lt(TraceHandler.ExceptionEntity::getModifyTime, DateTime.now()).toString(paramsX);
        System.out.println(sql + "(" + paramsX + ")");
        db.dropMapping(PersonBean.class);
        db.close();
    }

    @Test
    public void testEntityDatabaseOptimizations() {
        String testDb = path("h2/perf_test");
        EntityDatabaseImpl db = new EntityDatabaseImpl(testDb, null);
        db.createMapping(PersonBean.class);
        try {
            // Test 1: Verify Correctness of findById and existsById (using pre-generated SQL)
            PersonBean p1 = new PersonBean();
            p1.setName("OptimizedUser");
            p1.setGender(PersonGender.BOY);
            db.save(p1);
            ULID id = p1.getId();

            assert db.existsById(PersonBean.class, id);
            PersonBean found = db.findById(PersonBean.class, id);
            assert found != null;
            assert found.getName().equals("OptimizedUser");

            // Test 2: Verify Multiple Row Result Set Mapping (optimized executeQuery)
            for (int i = 0; i < 50; i++) {
                PersonBean p = new PersonBean();
                p.setName("Batch" + i);
                p.setGender(PersonGender.GIRL);
                db.save(p);
            }
            List<PersonBean> girls = db.findBy(new EntityQueryLambda<>(PersonBean.class).eq(PersonBean::getGender, PersonGender.GIRL));
            assert girls.size() >= 50;
            for (PersonBean g : girls) {
                assert g.getGender() == PersonGender.GIRL;
                assert g.getName().startsWith("Batch") || g.getName().equals("乐之") || g.getName().equals("湵范");
            }

            // Test 3: Micro-benchmark of existsById (Stress testing the cached SQL)
            long start = System.currentTimeMillis();
            int iterations = 1000;
            for (int i = 0; i < iterations; i++) {
                db.existsById(PersonBean.class, id);
            }
            long end = System.currentTimeMillis();
            log.info("{} iterations of existsById took {}ms", iterations, end - start);

            // Test 4: Verify partial update (secondaryView fix)
            p1.setAge(99);
            db.save(p1);
            PersonBean updated = db.findById(PersonBean.class, id);
            assert updated.getAge() == 99;
        } finally {
            db.dropMapping(PersonBean.class);
            db.close();
        }
    }

    @Test
    public void testSaveSkipsExistsForFullEntityAndKeepsPartialUpdate() {
        CountingEntityDatabaseImpl db = new CountingEntityDatabaseImpl(path("h2/save_fast"), null);
        db.createMapping(SaveEntity.class);
        try {
            SaveEntity full = new SaveEntity();
            full.setId("row-1");
            full.setName("origin");
            full.setAge(18);
            db.save(full);
            assertEquals(0, db.existsByIdCalls);

            db.existsByIdCalls = 0;
            SaveEntity partial = new SaveEntity();
            partial.setId("row-1");
            partial.setAge(20);
            db.save(partial);
            assertEquals(1, db.existsByIdCalls);

            SaveEntity loaded = db.findById(SaveEntity.class, "row-1");
            assertEquals("origin", loaded.getName());
            assertEquals(Integer.valueOf(20), loaded.getAge());
        } finally {
            db.dropMapping(SaveEntity.class);
            db.close();
        }
    }

    @Test
    public void testCountAndExistsDoNotMutateQueryState() {
        EntityDatabaseImpl db = new EntityDatabaseImpl(path("h2/query_state"), null);
        db.createMapping(SaveEntity.class);
        try {
            SaveEntity entity = new SaveEntity();
            entity.setId("row-1");
            entity.setName("query");
            entity.setAge(30);
            db.save(entity);

            EntityQueryLambda<SaveEntity> query = new EntityQueryLambda<>(SaveEntity.class)
                    .eq(SaveEntity::getName, "query")
                    .orderBy(SaveEntity::getAge)
                    .limit(1, 1);
            int orderSize = query.orders.size();
            Integer limit = query.limit;
            Integer offset = query.offset;

            assertTrue(db.exists(query));
            assertEquals(orderSize, query.orders.size());
            assertEquals(limit, query.limit);
            assertEquals(offset, query.offset);

            assertEquals(1L, db.count(query));
            assertEquals(orderSize, query.orders.size());
            assertEquals(limit, query.limit);
            assertEquals(offset, query.offset);
        } finally {
            db.dropMapping(SaveEntity.class);
            db.close();
        }
    }
}
