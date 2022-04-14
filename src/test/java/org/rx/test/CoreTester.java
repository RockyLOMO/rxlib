package org.rx.test;

import com.alibaba.fastjson.TypeReference;
import io.netty.util.Timeout;
import io.netty.util.concurrent.FastThreadLocal;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import okhttp3.ResponseBody;
import org.apache.commons.collections4.IterableUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.concurrent.CircuitBreakingException;
import org.junit.jupiter.api.Test;
import org.rx.annotation.DbColumn;
import org.rx.annotation.ErrorCode;
import org.rx.bean.*;
import org.rx.codec.CrcModel;
import org.rx.core.*;
import org.rx.core.Arrays;
import org.rx.core.cache.DiskCache;
import org.rx.core.YamlConfiguration;
import org.rx.exception.ApplicationException;
import org.rx.exception.ExceptionHandler;
import org.rx.exception.InvalidException;
import org.rx.io.*;
import org.rx.test.bean.*;
import org.rx.test.common.TestUtil;
import org.rx.util.function.TripleAction;
import org.yaml.snakeyaml.Yaml;

import javax.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.rx.bean.$.$;
import static org.rx.core.App.*;
import static org.rx.core.Extends.*;

@Slf4j
public class CoreTester extends TestUtil {
    //region thread pool
    @SneakyThrows
    @Test
    public void threadPool() {
        //LinkedTransferQueue基于CAS实现，性能比LinkedBlockingQueue要好。
        //拒绝策略 当thread和queue都满了后会block调用线程直到queue加入成功，平衡生产和消费
        //支持netty FastThreadLocal
        long delayMillis = 5000;
        ExecutorService pool = new ThreadPool(1, 1, new IntWaterMark(20, 40), "DEV");
        for (int i = 0; i < 100; i++) {
            int x = i;
            pool.execute(() -> {
                log.info("exec {} begin..", x);
                sleep(delayMillis);
                log.info("exec {} end..", x);
            });
        }

        for (int i = 0; i < 10; i++) {
            int x = i;
            //RunFlag.SYNCHRONIZED  根据taskId同步执行，只要有一个线程在执行，其它线程等待执行。
            //RunFlag.SINGLE        根据taskId单线程执行，只要有一个线程在执行，其它线程直接跳过执行。
            //RunFlag.TRANSFER      直到任务被执行或放入队列否则一直阻塞调用线程。
            //RunFlag.PRIORITY      如果线程和队列都无可用的则直接新建线程执行。
            //RunFlag.INHERIT_THREAD_LOCALS 子线程会继承父线程的FastThreadLocal
            Tasks.run(() -> {
                log.info("exec {} begin..", x);
                sleep(delayMillis);
                log.info("exec {} end..", x);
            }, "myTaskId", RunFlag.SINGLE.flags()).whenCompleteAsync((r, e) -> log.info("Done: " + x));
        }

        System.out.println("main thread done");
        System.in.read();
    }

    @SneakyThrows
    @Test
    public void inheritThreadLocal() {
        final FastThreadLocal<IntTuple<String>> inherit = new FastThreadLocal<IntTuple<String>>() {
            @Override
            protected void onRemoval(IntTuple<String> value) {
                System.out.println("rm:" + value);
            }
        };
        inherit.set(IntTuple.of(1, "a"));
        ThreadPool pool = new ThreadPool(1, 1, new IntWaterMark(20, 40), "DEV");
        AtomicReference<Thread> t = new AtomicReference<>();
        pool.run(() -> {
            t.set(Thread.currentThread());
            IntTuple<String> tuple = inherit.get();
            assert IntTuple.of(1, "a").equals(tuple);
            System.out.println("ok");
        }, null, RunFlag.INHERIT_THREAD_LOCALS.flags()).get();

        pool.run(() -> {
            IntTuple<String> tuple = inherit.get();
            assert IntTuple.of(1, "a").equals(tuple);
            System.out.println("ok");
            return null;
        }, null, RunFlag.INHERIT_THREAD_LOCALS.flags()).get();

        pool.runAsync(() -> {
            IntTuple<String> tuple = inherit.get();
            assert IntTuple.of(1, "a").equals(tuple);
            System.out.println("ok");
        }, null, RunFlag.INHERIT_THREAD_LOCALS.flags()).get();

        pool.runAsync(() -> {
            IntTuple<String> tuple = inherit.get();
            assert IntTuple.of(1, "a").equals(tuple);
            System.out.println("ok");
            return null;
        }, null, RunFlag.INHERIT_THREAD_LOCALS.flags()).get();
    }

    @SneakyThrows
    @Test
    public void timer() {
        Tasks.timer().setTimeout(() -> {
            System.out.println("once: " + DateTime.now());
            return false;
        }, d -> Math.max(d, 100) * 2);

        Timeout t = Tasks.timer().setTimeout(s -> {
            System.out.println("loop: " + DateTime.now());
            int i = s.incrementAndGet();
            if (i > 4) {
                return false;
            }
            if (i > 1) {
                throw new InvalidException("max exec");
            }
            return true;
        }, d -> Math.max(d, 100) * 2, new AtomicInteger());

        sleep(1000);
        t.cancel();

        //TimeoutFlag.SINGLE  根据taskId单线程执行，只要有一个线程在执行，其它线程直接跳过执行。
        //TimeoutFlag.REPLACE 根据taskId执行，如果已有其它线程执行或等待执行则都取消，只执行当前。
        //TimeoutFlag.PERIOD  定期重复执行，遇到异常不会终止直到return false 或 next delay = -1。
        Tasks.setTimeout(() -> {
            System.out.println(System.currentTimeMillis());
        }, 50, this, TimeoutFlag.REPLACE);

        System.in.read();
    }

    @SneakyThrows
    @Test
    public synchronized void MXBean() {
        int productCount = 10;
        ExecutorService fix = Executors.newFixedThreadPool(productCount);
        ThreadPool pool = new ThreadPool(1, 4, "rx");

        for (int i = 0; i < productCount; i++) {
            int finalI = i;
            fix.execute(() -> {
//                while (true) {
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        System.out.println("i-" + finalI + ": " + System.currentTimeMillis());
                        sleep(5000);
                    }

                    @Override
                    public String toString() {
                        return "-[" + finalI;
                    }
                });
//                }
            });
        }

//        for (int i = 0; i < 10; i++) {
//            Tasks.schedule(() -> {
//                sleep(2000);
//            }, 1000);
//        }
//
//        Tasks.schedule(() -> {
//            List<ManagementMonitor.ThreadMonitorInfo> threads = ManagementMonitor.getInstance().findTopCpuTimeThreads(10);
//            for (ManagementMonitor.ThreadMonitorInfo thread : threads) {
//                log.info("{}", thread.toString());
//            }
//        }, 2000);
//        System.out.println("main thread done");

        wait();
    }
    //endregion

    @SneakyThrows
    @Test
    public void cache() {
        System.out.println(hashKey("prefix", "aaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        System.out.println(cacheKey("prefix", "12345"));
        System.out.println(cacheKey("prefix", 12345));

//        BiAction<Caffeine<Object, Object>> dump = b -> b.removalListener((k, v, c) -> log.info("onRemoval {} {} {}", k, v, c));
//        testCache(new MemoryCache<>(dump));

        DiskCache<Tuple<?, ?>, Integer> diskCache = (DiskCache) Cache.getInstance(Cache.DISK_CACHE);
        testCache(diskCache);

        System.in.read();
    }

    private void testCache(Cache<Tuple<?, ?>, Integer> cache) {
        Tuple<Integer, String> key1 = Tuple.of(1, "a");
        Tuple<Integer, String> key2 = Tuple.of(2, "b");
        Tuple<Integer, String> key3 = Tuple.of(3, "c");

        cache.put(key1, 100);
        assert cache.get(key1).equals(100);
        cache.put(key2, 100, CachePolicy.absolute(10));
        assert cache.get(key2).equals(100);
        cache.put(key3, 100, CachePolicy.sliding(5));
        assert cache.get(key3).equals(100);

        cache.put(key1, 100);
        assert cache.get(key1).equals(100);
        cache.put(key2, 100, CachePolicy.absolute(10));
        assert cache.get(key2).equals(100);
        cache.put(key3, 100, CachePolicy.sliding(5));
        assert cache.get(key3).equals(100);

        assert cache.containsKey(key1);
        assert cache.containsKey(key2);
        assert cache.containsKey(key3);
        assert cache.size() == 3;
        Integer val2 = cache.remove(key1);
        assert 100 == val2;
        assert cache.size() == 2;

        Tasks.setTimeout(() -> {
            assert cache.get(key3).equals(100);
            log.info("check sliding ok");
        }, 4000);
    }

    //region codec
    @Data
    public static class CollisionEntity implements Serializable {
        @DbColumn(primaryKey = true)
        long id;
    }

    @Test
    public void codec() {
        for (int i = 0; i < 10; i++) {
            long ts = System.nanoTime();
            assert App.orderedUUID(ts, i).equals(App.orderedUUID(ts, i));
        }

        EntityDatabase db = EntityDatabase.DEFAULT;
        db.createMapping(CollisionEntity.class);
        db.dropMapping(CollisionEntity.class);
        db.createMapping(CollisionEntity.class);
        int c = 200000000;
        AtomicInteger collision = new AtomicInteger();
        invoke("codec", i -> {
//            long id = App.hash64("codec", i);
//            long id = App.hash64(h -> h.putBytes(MD5Util.md5("codec" + i)));
            long id = CrcModel.CRC64_ECMA_182.getCRC((UUID.randomUUID().toString() + i).getBytes(StandardCharsets.UTF_8)).getCrc();
            CollisionEntity po = db.findById(CollisionEntity.class, id);
            if (po != null) {
                log.warn("collision: {}", collision.incrementAndGet());
                return;
            }
            po = new CollisionEntity();
            po.setId(id);
            db.save(po, true);
        }, c);
        assert db.count(new EntityQueryLambda<>(CollisionEntity.class)) == c;
    }
    //endregion

    //region NQuery & NEvent
    @Test
    public void parallelNQuery() {
        NQuery<Integer> pq = NQuery.of(Arrays.toList(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), true)
//                .groupBy(p -> p > 5, (p, x) -> x.first())
                ;
        //not work
        for (Integer p : pq) {
            log.info(p.toString());
        }
        pq.forEach(p -> {
            log.info(p.toString());
            throw new CircuitBreakingException();
        });
    }

    @Test
    public void runNQuery() {
        Collection<PersonBean> personSet = new HashSet<>();
        personSet.add(PersonBean.LeZhi);
        for (int i = 0; i < 5; i++) {
            PersonBean p = new PersonBean();
            p.index = i % 2 == 0 ? 2 : i;
            p.index2 = i % 2 == 0 ? 3 : 4;
            p.name = Strings.randomValue(5);
            p.age = ThreadLocalRandom.current().nextInt(100);
            personSet.add(p);
        }

        showResult("leftJoin", NQuery.of(new PersonBean(27, 27, "jack", PersonGender.BOY, 6, DateTime.now(), 1L, Decimal.valueOf(1d), PersonBean.Flags, PersonBean.Array),
                new PersonBean(28, 28, "tom", PersonGender.BOY, 6, DateTime.now(), 1L, Decimal.valueOf(1d), PersonBean.Flags, PersonBean.Array),
                new PersonBean(29, 29, "lily", PersonGender.GIRL, 8, DateTime.now(), 1L, Decimal.valueOf(1d), PersonBean.Flags, PersonBean.Array),
                new PersonBean(30, 30, "cookie", PersonGender.BOY, 6, DateTime.now(), 1L, Decimal.valueOf(1d), PersonBean.Flags, PersonBean.Array)).leftJoin(
                Arrays.toList(new PersonBean(27, 27, "cookie", PersonGender.BOY, 5, DateTime.now(), 1L, Decimal.valueOf(1d), PersonBean.Flags, PersonBean.Array),
                        new PersonBean(28, 28, "tom", PersonGender.BOY, 10, DateTime.now(), 1L, Decimal.valueOf(1d), PersonBean.Flags, PersonBean.Array),
                        new PersonBean(29, 29, "jack", PersonGender.BOY, 1, DateTime.now(), 1L, Decimal.valueOf(1d), PersonBean.Flags, PersonBean.Array),
                        new PersonBean(30, 30, "session", PersonGender.BOY, 25, DateTime.now(), 1L, Decimal.valueOf(1d), PersonBean.Flags, PersonBean.Array),
                        new PersonBean(31, 31, "trump", PersonGender.BOY, 55, DateTime.now(), 1L, Decimal.valueOf(1d), PersonBean.Flags, PersonBean.Array),
                        new PersonBean(32, 32, "jack", PersonGender.BOY, 55, DateTime.now(), 1L, Decimal.valueOf(1d), PersonBean.Flags, PersonBean.Array)), (p, x) -> p.name.equals(x.name), Tuple::of
        ));

        showResult("groupBy(p -> p.index2...", NQuery.of(personSet).groupBy(p -> p.index2, (p, x) -> {
            System.out.println("groupKey: " + p);
            List<PersonBean> list = x.toList();
            System.out.println("items: " + toJsonString(list));
            return list.get(0);
        }));
        showResult("groupByMany(p -> new Object[] { p.index2, p.index3 })",
                NQuery.of(personSet).groupByMany(p -> Arrays.toList(p.index, p.index2), (p, x) -> {
                    System.out.println("groupKey: " + toJsonString(p));
                    List<PersonBean> list = x.toList();
                    System.out.println("items: " + toJsonString(list));
                    return list.get(0);
                }));

        showResult("orderBy(p->p.index)", NQuery.of(personSet).orderBy(p -> p.index));
        showResult("orderByDescending(p->p.index)", NQuery.of(personSet).orderByDescending(p -> p.index));
        showResult("orderByMany(p -> new Object[] { p.index2, p.index })",
                NQuery.of(personSet).orderByMany(p -> Arrays.toList(p.index2, p.index)));
        showResult("orderByDescendingMany(p -> new Object[] { p.index2, p.index })",
                NQuery.of(personSet).orderByDescendingMany(p -> Arrays.toList(p.index2, p.index)));

        showResult("select(p -> p.index).reverse()",
                NQuery.of(personSet).orderBy(p -> p.index).select(p -> p.index).reverse());

        showResult(".max(p -> p.index)", NQuery.of(personSet).<Integer>max(p -> p.index));
        showResult(".min(p -> p.index)", NQuery.of(personSet).<Integer>min(p -> p.index));

        showResult("take(0).average(p -> p.index)", NQuery.of(personSet).take(0).average(p -> p.index));
        showResult("average(p -> p.index)", NQuery.of(personSet).average(p -> p.index));
        showResult("take(0).sum(p -> p.index)", NQuery.of(personSet).take(0).sum(p -> p.index));
        showResult("sum(p -> p.index)", NQuery.of(personSet).sum(p -> p.index));
        showResult("sumMoney(p -> p.index)", NQuery.of(personSet).sumDecimal(p -> Decimal.valueOf((double) p.index)));

        showResult("cast<IPerson>", NQuery.of(personSet).<IPerson>cast());
        NQuery<?> oq = NQuery.of(personSet).cast().union(Arrays.toList(1, 2, 3));
        showResult("ofType(Integer.class)", oq.ofType(Integer.class));

        showResult("firstOrDefault()", NQuery.of(personSet).orderBy(p -> p.index).firstOrDefault());
        showResult("lastOrDefault()", NQuery.of(personSet).orderBy(p -> p.index).lastOrDefault());
        showResult("skip(2)", NQuery.of(personSet).orderBy(p -> p.index).skip(2));
        showResult("take(2)", NQuery.of(personSet).orderBy(p -> p.index).take(2));

        showResult(".skipWhile((p, i) -> p.index < 3)",
                NQuery.of(personSet).orderBy(p -> p.index).skipWhile((p, i) -> p.index < 3));

        showResult(".takeWhile((p, i) -> p.index < 3)",
                NQuery.of(personSet).orderBy(p -> p.index).takeWhile((p, i) -> p.index < 3));

        NQuery<PersonBean> set0 = NQuery.of(personSet);
        NQuery<PersonBean> set1 = set0.take(1);
        System.out.printf("set a=%s,b=%s%n", set0.count(), set1.count());
        assert set0.count() > set1.count();
    }

    private void showResult(String n, Object q) {
        System.out.println();
        System.out.println();
        System.out.println("showResult: " + n);
        System.out.println(toJsonString(q));
    }

    private void showResult(String n, NQuery<?> q) {
        System.out.println();
        System.out.println();
        System.out.println("showResult: " + n);
        System.out.println(toJsonString(q.toList()));
    }

    @Test
    public void runNEvent() {
        UserManagerImpl mgr = new UserManagerImpl();
        PersonBean p = PersonBean.YouFan;

        mgr.onCreate.tail((s, e) -> System.out.println("always tail:" + e));
        TripleAction<UserManager, UserEventArgs> a = (s, e) -> System.out.println("a:" + e);
        TripleAction<UserManager, UserEventArgs> b = (s, e) -> System.out.println("b:" + e);
        TripleAction<UserManager, UserEventArgs> c = (s, e) -> System.out.println("c:" + e);

        mgr.onCreate.combine(a);
        mgr.create(p);  //触发事件（a执行）

        mgr.onCreate.combine(b);
        mgr.create(p); //触发事件（a, b执行）

        mgr.onCreate.combine(a, b);  //会去重
        mgr.create(p); //触发事件（a, b执行）

        mgr.onCreate.remove(b);
        mgr.create(p); //触发事件（a执行）

        mgr.onCreate.replace(a, c);
        mgr.create(p); //触发事件（a, c执行）
    }
    //endregion

    @Test
    public void shellExec() {
        ShellCommander executor = new ShellCommander("ping www.baidu.com", null);
        executor.onOutPrint.combine(ShellCommander.CONSOLE_OUT_HANDLER);
        executor.start().waitFor();

        executor = new ShellCommander("ping www.baidu.com", null);
        ShellCommander finalExecutor = executor;
        executor.onOutPrint.combine((s, e) -> {
            System.out.println(e.getLine());
            finalExecutor.kill();
        });
        executor.onOutPrint.combine(new ShellCommander.FileOutHandler(TConfig.path("out.txt")));
        executor.start().waitFor();

        sleep(5000);
    }

    @Test
    public void json() {
        Object[] args = new Object[]{TConfig.NAME_WYF, proxy(HttpServletResponse.class, (m, i) -> {
            throw new InvalidException("wont reach");
        }), new ErrorBean()};
        System.out.println(toJsonString(args));
        System.out.println(toJsonString(Tuple.of(Collections.singletonList(new MemoryStream(12, false)), false)));

        String str = "abc";
        assert str.equals(toJsonString(str));
        String jObj = toJsonString(PersonBean.LeZhi);
        System.out.println("encode jObj: " + jObj);
        System.out.println("decode jObj: " + fromJson(jObj, PersonBean.class));
        List<PersonBean> arr = Arrays.toList(PersonBean.LeZhi, PersonBean.LeZhi);
        String jArr = toJsonString(arr);
        System.out.println("encode jArr: " + jArr);
        System.out.println("decode jArr: " + fromJson(jArr, new TypeReference<List<PersonBean>>() {
        }.getType()));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", TConfig.NAME_WYF);
        data.put("age", 10);
        data.put("date", DateTime.now());
        System.out.println(toJsonString(data));
        System.out.println(fromJson(data, Map.class).toString());

        Tuple<String, List<Float>> tuple1 = Tuple.of("abc", Arrays.toList(1.2F, 2F, 3F));
        Tuple<String, List<Float>> tuple2 = fromJson(toJsonString(tuple1), new TypeReference<Tuple<String, List<Float>>>() {
        }.getType());
        assert tuple1.equals(tuple2);
        Tuple<String, List<Float>> tuple3 = fromJson(tuple1, new TypeReference<Tuple<String, List<Float>>>() {
        }.getType());
        assert tuple1.equals(tuple3);
    }

    @Test
    public void fluentWait() throws TimeoutException {
        FluentWait.newInstance(2000, 200).until(s -> {
            System.out.println(System.currentTimeMillis());
            return false;
        });
    }

    @Test
    public void dynamicProxy() {
        PersonBean leZhi = PersonBean.LeZhi;

        IPerson proxy = proxy(PersonBean.class, (m, p) -> p.fastInvoke(leZhi), leZhi, false);
        assert rawObject(proxy) == leZhi;

        IPerson iproxy = proxy(IPerson.class, (m, p) -> p.fastInvoke(leZhi), leZhi, false);
        assert rawObject(iproxy) == leZhi;
    }

    @SneakyThrows
    @Test
    public void reflect() {
        for (InputStream resource : Reflects.getResources(Constants.RX_CONFIG_FILE)) {
            System.out.println(resource);
            assert resource != null;
        }

        Tuple<String, String> resolve = Reflects.resolveImpl(PersonBean::getAge);
        assert resolve.left.equals(PersonBean.class.getName()) && resolve.right.equals("age");

        assert Reflects.stackClass(0) == this.getClass();
//        for (StackTraceElement traceElement : Reflects.stackTrace(8)) {
//            System.out.println(traceElement);
//        }

        assert Reflects.getMethodMap(ResponseBody.class).get("charset") != null;

        Method defMethod = IPerson.class.getMethod("enableCompress");
        assert (Boolean) Reflects.invokeDefaultMethod(defMethod, PersonBean.YouFan);

        ErrorBean bean = Reflects.newInstance(ErrorBean.class, 1, null);
        assert bean != null;
        int code = 1;
        String msg = "Usr not found";
        String r = code + msg;
        assert eq("S-" + r, Reflects.invokeStaticMethod(ErrorBean.class, "staticCall", code, msg));
        assert eq("I-" + r, Reflects.invokeMethod(bean, "instanceCall", code, msg));
        assert eq("D-" + r, Reflects.invokeMethod(bean, "defCall", code, msg));
        assert eq("N-" + r, Reflects.invokeMethod(bean, "nestedDefCall", code, msg));


        //convert
        assert Reflects.changeType(1, boolean.class);
        assert Reflects.changeType(1, Boolean.class);
        assert Reflects.changeType(true, byte.class) == 1;
        assert Reflects.changeType(true, Byte.class) == 1;
        assert Reflects.changeType(new BigDecimal("1.002"), int.class) == 1;

        assert Reflects.changeType("1", Integer.class) == 1;
        assert Reflects.changeType(10, long.class) == 10L;

        int enumVal = Reflects.changeType(PersonGender.BOY, Integer.class);
        assert enumVal == 1;
        PersonGender enumBoy = Reflects.changeType(enumVal, PersonGender.class);
        assert enumBoy == PersonGender.BOY;

        String date = "2017-08-24 02:02:02";
        assert Reflects.changeType(date, DateTime.class) instanceof Date;

        assert Reflects.defaultValue(Integer.class) == null;
        assert Reflects.defaultValue(int.class) == 0;
        assert Reflects.defaultValue(List.class) == Collections.emptyList();
        assert Reflects.defaultValue(Map.class) == Collections.emptyMap();

    }

    @ErrorCode
    @ErrorCode(cause = IllegalArgumentException.class)
    @Test
    public void exceptionHandle() {
        ExceptionHandler handler = ExceptionHandler.INSTANCE;
        handler.log(new InvalidException("test error"));
        System.out.println(handler.queryTraces(null, null, null));


        String err = "ERR";
        ApplicationException ex = new ApplicationException(values(err));
        assert eq(ex.getFriendlyMessage(), "Test error code, value=" + err);

        ex = new ApplicationException(values(err), new IllegalArgumentException());
        assert eq(ex.getFriendlyMessage(), "Test IAException, value=" + err);
        $<IllegalArgumentException> out = $();
        assert ex.tryGet(out, IllegalArgumentException.class);

        String errCode = "ERR_CODE";
        ex = new ApplicationException(UserManager.BizCode.USER_NOT_FOUND, values(errCode));
        assert eq(ex.getFriendlyMessage(), "User " + errCode + " not found");

        ex = new ApplicationException(UserManager.BizCode.COMPUTE_FAIL, values(errCode));
        assert eq(ex.getFriendlyMessage(), "Compute user level error " + errCode);

        try {
            Reflects.changeType("x", Date.class);
        } catch (InvalidException e) {
            e.printStackTrace();
        }
    }

    @Test
    public void rxConf() {
        Iterable<Object> all = new Yaml().loadAll(Reflects.getResource("application.yml"));
        List<Object> list = IterableUtils.toList(all);

        YamlConfiguration conf = YamlConfiguration.RX_CONF;
        System.out.println(conf.getYaml());

        Map codeMap = conf.readAs("org.rx.test.CoreTester", Map.class);
        System.out.println(codeMap);

        String codeFormat = conf.readAs("org.rx.test.CoreTester.exceptionHandle<IllegalArgumentException>", String.class);
        System.out.println(codeFormat);
        assert eq(codeFormat, "Test IAException, value={0}");

        RxConfig rxConf = RxConfig.INSTANCE;
        assert rxConf.getId().equals("Rx");
        assert rxConf.getThreadPool().getReplicas() == 4;
        assert rxConf.getNet().getConnectTimeoutMillis() == 40000;
        assert rxConf.getLogTypeWhitelist().size() == 2;
        assert rxConf.getJsonSkipTypes().size() == 1;
    }

    @Test
    public void yamlConf() {
        System.out.println(FilenameUtils.getFullPath("b.txt"));
        System.out.println(FilenameUtils.getFullPath("c:\\a\\b.txt"));
        System.out.println(FilenameUtils.getFullPath("/a/b.txt"));

        YamlConfiguration conf = YamlConfiguration.RX_CONF;
        conf.enableWatch();

        sleep(60000);
    }
}
