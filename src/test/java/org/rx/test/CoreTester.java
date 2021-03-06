package org.rx.test;

import com.alibaba.fastjson.TypeReference;
import io.netty.util.Timeout;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.rx.annotation.ErrorCode;
import org.rx.bean.*;
import org.rx.core.*;
import org.rx.core.Arrays;
import org.rx.core.cache.HybridCache;
import org.rx.core.exception.ApplicationException;
import org.rx.core.exception.InvalidException;
import org.rx.io.MemoryStream;
import org.rx.test.bean.*;
import org.rx.test.common.TestUtil;
import org.rx.util.RedoTimer;

import javax.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

import static org.rx.bean.$.$;
import static org.rx.core.App.*;

@Slf4j
public class CoreTester extends TestUtil {
    //region NQuery
    @SneakyThrows
    @Test
    public void runParallelNQuery() {
        for (BigDecimal bigDecimal : NQuery.of(1, 2, 3, 4).where(p -> {
            System.out.println("a:" + p);
            return p > 2;
        }).select(p -> {
            System.out.println("b:" + p);
            return new BigDecimal(p);
        })) {
            System.out.println(bigDecimal);
        }
//
//        List<Integer > a = new ArrayList<>(),b = new ArrayList<>();
//        a.add(1);
//        b.add(1);
        List<Integer> a = Collections.singletonList(1), b = Collections.singletonList(1);
//        a.add(1);
//        b.add(1);
        System.out.println(a.equals(b));
        Map<List<Integer>, String> x = new HashMap<>();
        x.put(a, "woshimap");
        System.out.println(x.get(b));

        for (Integer integer : new Iterable<Integer>() {
            @NotNull
            @Override
            public Iterator<Integer> iterator() {
                System.out.println("wtf");
                return Arrays.toList(1, 2, 3, 4).iterator();
            }
        }) {
            System.out.println(integer);
        }
//        ManagementMonitor.getInstance().scheduled = (s, e) -> {
//            System.out.println(toJsonString(e.getValue()));
//        };
//
//        for (Integer integer : NQuery.of(Arrays.toList(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), true)
//                .groupBy(p -> p > 5, (p, x) -> x.first())) {
//            System.out.println(integer.toString());
//        }
//        System.in.read();
    }

    @Test
    public void runNQuery() {
        Collection<PersonBean> personSet = new HashSet<>();
        personSet.add(PersonBean.girl);
        for (int i = 0; i < 5; i++) {
            PersonBean p = new PersonBean();
            p.index = i % 2 == 0 ? 2 : i;
            p.index2 = i % 2 == 0 ? 3 : 4;
            p.name = Strings.randomValue(5);
            p.age = ThreadLocalRandom.current().nextInt(100);
            personSet.add(p);
        }

        showResult("leftJoin", NQuery.of(new PersonBean(27, 27, "jack", PersonGender.Boy, 6, DateTime.now(), 1L, Decimal.valueOf(1d)),
                new PersonBean(28, 28, "tom", PersonGender.Boy, 6, DateTime.now(), 1L, Decimal.valueOf(1d)),
                new PersonBean(29, 29, "lily", PersonGender.Girl, 8, DateTime.now(), 1L, Decimal.valueOf(1d)),
                new PersonBean(30, 30, "cookie", PersonGender.Boy, 6, DateTime.now(), 1L, Decimal.valueOf(1d))).leftJoin(
                Arrays.toList(new PersonBean(27, 27, "cookie", PersonGender.Boy, 5, DateTime.now(), 1L, Decimal.valueOf(1d)),
                        new PersonBean(28, 28, "tom", PersonGender.Boy, 10, DateTime.now(), 1L, Decimal.valueOf(1d)),
                        new PersonBean(29, 29, "jack", PersonGender.Boy, 1, DateTime.now(), 1L, Decimal.valueOf(1d)),
                        new PersonBean(30, 30, "session", PersonGender.Boy, 25, DateTime.now(), 1L, Decimal.valueOf(1d)),
                        new PersonBean(31, 31, "trump", PersonGender.Boy, 55, DateTime.now(), 1L, Decimal.valueOf(1d)),
                        new PersonBean(32, 32, "jack", PersonGender.Boy, 55, DateTime.now(), 1L, Decimal.valueOf(1d))), (p, x) -> p.name.equals(x.name), Tuple::of
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
        NQuery oq = NQuery.of(personSet).cast().union(Arrays.toList(1, 2, 3));
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
        System.out.println(String.format("set a=%s,b=%s", set0.count(), set1.count()));
        assert set0.count() > set1.count();
    }

    private void showResult(String n, Object q) {
        System.out.println();
        System.out.println();
        System.out.println("showResult: " + n);
        System.out.println(toJsonString(q));
    }

    private void showResult(String n, NQuery q) {
        System.out.println();
        System.out.println();
        System.out.println("showResult: " + n);
        System.out.println(toJsonString(q.toList()));
    }
    //endregion

    @Test
    public void runNEvent() {
        UserManagerImpl mgr = new UserManagerImpl();
        PersonBean p = new PersonBean();
        p.index = 1;
        p.name = "rx";
        p.age = 6;

        BiConsumer<UserManager, UserEventArgs> a = (s, e) -> System.out.println("a:" + e);
        BiConsumer<UserManager, UserEventArgs> b = (s, e) -> System.out.println("b:" + e);

        mgr.onCreate = a;
        mgr.create(p);  //触发事件（a执行）

        mgr.onCreate = combine(mgr.onCreate, b);
        mgr.create(p); //触发事件（a, b执行）

        mgr.onCreate = remove(mgr.onCreate, b);
        mgr.create(p); //触发事件（b执行）
    }

    @SneakyThrows
    @Test
    public void redo() {
        log.info("start...");
//        Tasks.scheduleOnce(() -> log.info("scheduleOnce"), 1000);

        int max = 2;
        RedoTimer monitor = new RedoTimer();
        $<String> va = $.$("a");
        $<String> vb = $.$("b");
        Timeout timeout = monitor.runAndSetTimeout(p -> {
            System.out.println(p.cancel());
            va.v += va.v;
            log.info(va.v);
        }, 2000, max);
        Timeout timeout1 = monitor.runAndSetTimeout(p -> {
            vb.v += vb.v;
            log.info(vb.v);
            throw new InvalidException("x");
        }, 2000, max);

        System.in.read();
    }

    @SneakyThrows
    @Test
    public void asyncTask() {
        ThreadPool.DynamicConfig config = new ThreadPool.DynamicConfig();
        config.setMinThreshold(40);
        config.setMaxThreshold(60);
        Tasks.pool().statistics(config);

        for (int i = 0; i < 6; i++) {
            int x = i;
            //RunFlag.CONCURRENT    默认无锁
            //RunFlag.Synchronous   根据taskName同步执行，只要有一个线程在执行，其它线程等待执行。
            //RunFlag.Single        根据taskName单线程执行，只要有一个线程在执行，其它线程直接跳过执行。
            //RunFlag.TRANSFER      直到任务被执行或放入队列否则一直阻塞调用线程。
            //RunFlag.PRIORITY      如果线程和队列都无可用的则直接新建线程执行。
            Tasks.run(() -> {
                log.info("Exec: " + x);
                sleep(2000);
            }, "myTaskId", RunFlag.CONCURRENT)
                    .whenCompleteAsync((r, e) -> log.info("Done: " + x));
        }

        System.out.println("main thread done");
        System.in.read();
    }

    @SneakyThrows
    @Test
    public void threadPool() {
        //Executors.newCachedThreadPool(); 没有queue缓冲，一直new thread执行，当cpu负载高时加上更多线程上下文切换损耗，性能会急速下降。

        //Executors.newFixedThreadPool(16); 执行的thread数量固定，但当thread 等待时间（IO时间）过长时会造成吞吐量下降。当thread 执行时间过长时无界的LinkedBlockingQueue可能会OOM。

        //new ThreadPoolExecutor(nThreads, nThreads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(10000));
        //有界的LinkedBlockingQueue可以避免OOM，但吞吐量下降的情况避免不了，加上LinkedBlockingQueue使用的重量级锁ReentrantLock对并发下性能可能有影响

        //最佳线程数=CPU 线程数 * (1 + CPU 等待时间 / CPU 执行时间)，由于执行任务的不同，CPU 等待时间和执行时间无法确定，
        //因此换一种思路，当列队满的情况下，如果CPU使用率小于40%，则会动态增大线程池maxThreads 最大线程数的值来提高吞吐量。如果CPU使用率大于60%，则会动态减小maxThreads 值来降低生产者的任务生产速度。
        ThreadPool.DynamicConfig config = new ThreadPool.DynamicConfig();
        config.setMinThreshold(40);
        config.setMaxThreshold(60);
        //当最小线程数的线程量处理不过来的时候，会创建到最大线程数的线程量来执行。当最大线程量的线程执行不过来的时候，会把任务丢进列队，当列队满的时候会阻塞当前线程，降低生产者的生产速度。
        //LinkedTransferQueue基于CAS实现，性能比LinkedBlockingQueue要好。
        //拒绝策略 当thread和queue都满了后会block调用线程直到queue加入成功，平衡生产和消费
        //FastThreadLocal 支持netty FastThreadLocal
        ExecutorService pool = new ThreadPool(1, 1, 1, 8, "")
                .statistics(config);
        for (int i = 0; i < 100; i++) {
            int n = i;
            pool.execute(() -> {
                log.info("exec {} begin..", n);
                sleep(15 * 1000);
                log.info("exec {} end..", n);
            });
        }

        System.out.println("main thread done");
        System.in.read();
    }

    @SneakyThrows
    @Test
    public void MXBean() {
        Tasks.schedule(() -> {
            List<ManagementMonitor.ThreadMonitorInfo> threads = ManagementMonitor.getInstance().findTopCpuTimeThreads(10);
            for (ManagementMonitor.ThreadMonitorInfo thread : threads) {
                log.info("{}", thread.toString());
            }
        }, 2000);
        System.out.println("main thread done");
        System.in.read();
    }

    @SneakyThrows
    @Test
    public void cache() {
        Tuple<Integer, String> key1 = Tuple.of(1, "a");
        Tuple<Integer, String> key2 = Tuple.of(2, "b");

//        Cache<Tuple<?, ?>, Integer> cache = Cache.getInstance(Cache.LOCAL_CACHE);
//        cache.put(key, 100);
//        assert cache.get(key).equals(100);
//        Tasks.scheduleOnce(() -> {
//            assert cache.get(Tuple.of(1, "a")) == null;
//            log.info("LOCAL_CACHE ok");
//        }, 3 * 60 * 1000);

        HybridCache<Serializable, PersonBean> pCache = (HybridCache) Cache.getInstance(Cache.DISTRIBUTED_CACHE);
        pCache.put(key1, PersonBean.girl);
        pCache.put(key2, PersonBean.boy, CacheExpirations.absolute(2 * 60));
        pCache.put(4, PersonBean.girl);
        pCache.remove(4);
        Tasks.scheduleOnce(() -> {
            sleep(1000);
            PersonBean v1 = pCache.get(Tuple.of(1, "a"));
            PersonBean v2 = pCache.get(Tuple.of(2, "b"));
            log.info("key1={} key2={}", v1, v2);
            assert v1.equals(PersonBean.girl);
            assert v2.equals(PersonBean.boy);
            log.info("pCache ok");
        }, 60 * 1000 + 10);
        Tasks.scheduleOnce(() -> {
            PersonBean v1 = pCache.get(key1);
            PersonBean v2 = pCache.get(key2);
            log.info("key1={} key2={}", v1, v2);
            assert v1.equals(PersonBean.girl);
            assert v2 == null;
            log.info("pCache ok");
        }, 60 * 1000 * 2 + 10);

        System.in.read();
    }

    @Test
    public void fluentWait() throws TimeoutException {
        FluentWait.newInstance(2000, 200).until(s -> {
            System.out.println(System.currentTimeMillis());
            return false;
        });
    }

    @Test
    public void sneaky() {
        $<Integer> o = $.$(0);
        sneakyInvoke(() -> {
            o.v++;
            System.out.println(o.v);
        }, 2);
    }

    @Test
    public void convert() {
        Reflects.registerConvert(Integer.class, PersonGender.class, (fromValue, toType) -> NEnum.valueOf(toType, fromValue));

        int val = Reflects.changeType(PersonGender.Boy, Integer.class);
        assert val == 1;

        PersonGender testEnum = Reflects.changeType(1, PersonGender.class);
        assert testEnum == PersonGender.Boy;
        int integer = Reflects.changeType("1", Integer.class);
        assert integer == 1;

        assert Reflects.changeType(10, long.class) instanceof Long;

        assert Reflects.changeType(1, boolean.class);
        assert Reflects.changeType(1, Boolean.class);

        assert Reflects.changeType(true, byte.class) == 1;
        assert Reflects.changeType(true, Byte.class) == 1;

        System.out.println(Reflects.defaultValue(List.class));
        System.out.println(Reflects.defaultValue(Map.class));
    }

    @SneakyThrows
    @Test
    public void reflect() {
        for (InputStream resource : Reflects.getResources("C:\\Project\\rxlib\\src\\main\\resources\\errorCode.yml")) {
            System.out.println(resource);
        }

        Tuple<String, String> resolve = Reflects.resolve(PersonBean::getAge);
        assert resolve.left.equals(PersonBean.class.getName()) && resolve.right.equals("age");

        assert Reflects.stackClass(0) == this.getClass();
        System.out.println(cacheKey("reflect"));
        System.out.println(cacheKey("reflect:", "aaaaaaaaaaaaaaaaaaaaaaaaaaaa"));

        for (StackTraceElement traceElement : Reflects.stackTrace(8)) {
            System.out.println(traceElement);
        }
        ErrorBean bean = Reflects.newInstance(ErrorBean.class, 1, null);
        System.out.println(bean.getError());

        Reflects.invokeMethod(ErrorBean.class, "theStatic", 0, null);
        Reflects.invokeMethod(bean, "theMethod", 2, null);
//        Object v = MethodUtils.invokeMethod(bean, true, "theMethod", 0, null);
//        System.out.println(bean.getError());
    }

    @Test
    @ErrorCode
    @ErrorCode(cause = IllegalArgumentException.class)
    public void exceptionCode() {
        String val = "rx";
        ApplicationException ex = new ApplicationException(values(val));
        assert eq(ex.getFriendlyMessage(), "Default Error Code value=" + val);

        ex = new ApplicationException(values(val), new IllegalArgumentException());
        assert eq(ex.getFriendlyMessage(), "Exception Error Code value=" + val);
        $<IllegalArgumentException> out = $();
        assert ex.tryGet(out, IllegalArgumentException.class);

        String uid = "userId";
        ex = new ApplicationException(UserManager.BizCode.argument, values(uid));
        assert eq(ex.getFriendlyMessage(), "Enum Error Code value=" + uid);

        ex = new ApplicationException(UserManager.BizCode.returnValue, values(uid));
        assert eq(ex.getFriendlyMessage(), "Enum Error returnValue=" + uid);

        String date = "2017-08-24 02:02:02";
        assert Reflects.changeType(date, DateTime.class) instanceof Date;
        try {
            date = "x";
            Reflects.changeType(date, Date.class);
        } catch (InvalidException e) {
            e.printStackTrace();
        }
    }

    @Test
    public void json() {
        Object[] args = new Object[]{"b", proxy(HttpServletResponse.class, (m, i) -> {
            throw new InvalidException("auto skip");
//            return null;
        }), new ErrorBean()};
        System.out.println(toJsonString(args));
        System.out.println(toJsonString(Tuple.of(Collections.singletonList(new MemoryStream(12, false, false)), false)));

        String str = "abc";
        assert str.equals(toJsonString(str));
        String jObj = toJsonString(PersonBean.girl);
        System.out.println("encode jObj: " + jObj);
        System.out.println("decode jObj: " + fromJson(jObj, PersonBean.class));
//        assert fromJsonAsObject(jObj, PersonBean.class).equals(PersonBean.def);
        List<PersonBean> arr = Arrays.toList(PersonBean.girl, PersonBean.girl);
        String jArr = toJsonString(arr);
        System.out.println("encode jArr: " + jArr);
        System.out.println("decode jArr: " + fromJson(jArr, new TypeReference<List<PersonBean>>() {
        }.getType()));
//        assert ListUtils.isEqualList(fromJsonAsList(jArr, List.class), arr);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", "rocky");
        data.put("age", 10);
        data.put("date", DateTime.now());
        System.out.println(toJsonString(data));
        System.out.println(fromJson(data, Map.class).toString());

        Tuple<String, List<Float>> tuple = Tuple.of("abc", Arrays.toList(1.2F, 2F, 3F));
        String tjObj = toJsonString(tuple);
        tuple = fromJson(tjObj, new TypeReference<Tuple<String, List<Float>>>() {
        }.getType());
        System.out.println(tuple);

        List<Tuple<String, List<Float>>> tupleList = Arrays.toList(Tuple.of("abc", Arrays.toList(1.2F, 2F, 3F)), Tuple.of("xyz", Arrays.toList(1.2F, 2F, 3F)));
        String tjArr = toJsonString(tupleList);
        tupleList = fromJson(tjArr, new TypeReference<List<Tuple<String, List<Float>>>>() {
        }.getType());
        for (Tuple<String, List<Float>> item : tupleList) {
            System.out.println(item);
        }
        tupleList = fromJson(tupleList, new TypeReference<List<Tuple<String, List<Float>>>>() {
        }.getType());
        for (Tuple<String, List<Float>> item : tupleList) {
            System.out.println(item);
        }
    }

    @Test
    public void appSetting() {
        System.out.println(App.getConfig());

        Map<String, Object> map = loadYaml("application.yml");
        System.out.println(map);

        Object v = readSetting("org.rx.test.CoreTester", null, loadYaml("errorCode.yml"), false);
        assert v instanceof Map;

        v = readSetting("org.rx.test.CoreTester.exceptionCode<IllegalArgumentException>", null, loadYaml("errorCode.yml"), false);
        assert eq(v, "Exception Error Code value={0}");
    }
}
