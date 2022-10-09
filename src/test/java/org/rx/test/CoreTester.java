package org.rx.test;

import com.alibaba.fastjson.TypeReference;
import io.netty.util.concurrent.FastThreadLocal;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import okhttp3.ResponseBody;
import org.apache.commons.collections4.IterableUtils;
import org.apache.commons.io.FilenameUtils;
import org.junit.jupiter.api.Test;
import org.rx.annotation.DbColumn;
import org.rx.annotation.ErrorCode;
import org.rx.bean.*;
import org.rx.codec.CrcModel;
import org.rx.codec.RSAUtil;
import org.rx.core.*;
import org.rx.core.Arrays;
import org.rx.core.cache.DiskCache;
import org.rx.core.YamlConfiguration;
import org.rx.exception.ApplicationException;
import org.rx.exception.ExceptionLevel;
import org.rx.exception.TraceHandler;
import org.rx.exception.InvalidException;
import org.rx.io.*;
import org.rx.test.bean.*;
import org.rx.test.common.TestUtil;
import org.rx.util.function.Func;
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

//        ExecutorService pool = new ThreadPool(1, 1, new IntWaterMark(20, 40), "DEV");
//        for (int i = 0; i < 100; i++) {
//            int x = i;
//            pool.execute(() -> {
//                log.info("exec {} begin..", x);
//                sleep(delayMillis);
//                log.info("exec {} end..", x);
//            });
//        }

        Object id = new Object();
        ThreadPool pool = Tasks.pool();
        for (int i = 0; i < 10; i++) {
            int x = i;
            //RunFlag.SINGLE        根据taskId单线程执行，只要有一个线程在执行，其它线程直接跳过执行。
            //RunFlag.SYNCHRONIZED  根据taskId同步执行，只要有一个线程在执行，其它线程等待执行。
            //RunFlag.TRANSFER      直到任务被执行或放入队列否则一直阻塞调用线程。
            //RunFlag.PRIORITY      如果线程和队列都无可用的则直接新建线程执行。
            //RunFlag.INHERIT_THREAD_LOCALS 子线程会继承父线程的FastThreadLocal
//            Future<?> future = pool.run(() -> {
//                log.info("TASK begin {}", x);
//                sleep(delayMillis);
//                log.info("TASK end {}", x);
//            }, id, RunFlag.SYNCHRONIZED.flags());

//            CompletableFuture<Void> completableFuture = pool.runAsync(() -> {
//                log.info("TASK begin {}", x);
//                sleep(delayMillis);
//                log.info("TASK end {}", x);
//            }, id, RunFlag.SINGLE.flags()).whenCompleteAsync((r, e) -> log.info("TASK done {}", x));
        }

        List<Func<Integer>> tasks = new ArrayList<>();
        for (int x = 0; x < 10; x++) {
            int finalX = x;
            tasks.add(() -> {
                log.info("TASK begin {}", finalX);
                sleep(delayMillis);
                log.info("TASK end {}", finalX);
                return finalX + 100;
            });
        }
        List<Future<Integer>> futures = pool.runAll(tasks, 0);
        for (Future<Integer> future : futures) {
            System.out.println(future.get());
        }

        System.out.println("main thread done");
        System.in.read();
    }

    @SneakyThrows
    @Test
    public void inheritThreadLocal() {
        final ThreadLocal<String> jdkTL = new InheritableThreadLocal<>();//new ThreadLocal<>();
        jdkTL.set("JDK-TL");
        final FastThreadLocal<String> nettyTL = new FastThreadLocal<String>();
        nettyTL.set("NETTY-TL");
        final FastThreadLocal<String> autoRmTL = new FastThreadLocal<String>() {
            @Override
            protected void onRemoval(String value) {
                System.out.println("rm:" + value);
            }
        };
        autoRmTL.set("AUTO");
        ThreadPool pool = new ThreadPool(3, 1, new IntWaterMark(20, 40), "DEV");

        pool.run(() -> {
//            sleep(1000);
            System.out.println(jdkTL.get());
            assert nettyTL.get() == null;
            log.info("Not inherit ok 1");
        });

        final String NETTY_NV = "NV";
        pool.run(() -> {
            sleep(100);
            System.out.println("x:" + jdkTL.get());
            assert "NETTY-TL".equals(nettyTL.get());
            assert "AUTO".equals(autoRmTL.get());
            log.info("Inherit ok 1");
            jdkTL.set("asd");
            nettyTL.set(NETTY_NV);
            pool.run(() -> {
                assert nettyTL.get() == null;
                log.info("Inherit ok 1 - not inherit ok");
            });
            pool.run(() -> {
                System.out.println("x:" + jdkTL.get());
                assert NETTY_NV.equals(nettyTL.get());
                assert "AUTO".equals(autoRmTL.get());
                log.info("Inherit ok 1 - nested inherit ok");
            }, null, RunFlag.INHERIT_THREAD_LOCALS.flags());
        }, null, RunFlag.INHERIT_THREAD_LOCALS.flags());

        pool.run(() -> {
            sleep(1000);
            System.out.println(jdkTL.get());
            assert NETTY_NV.equals(nettyTL.get());
            log.info("Inherit ok 2");
            return null;
        }, null, RunFlag.INHERIT_THREAD_LOCALS.flags());

        pool.runAsync(() -> {
            assert "NETTY-TL".equals(nettyTL.get());
            log.info("Inherit ok 3");
        }, null, RunFlag.INHERIT_THREAD_LOCALS.flags());

        pool.runAsync(() -> {
            sleep(1000);
            assert NETTY_NV.equals(nettyTL.get());
            log.info("Inherit ok 4");
            return null;
        }, null, RunFlag.INHERIT_THREAD_LOCALS.flags());

        log.info("wait..");
        sleep(5000);

        pool.run(() -> {
            sleep(1000);
//            System.out.println(jdkTL.get());
            assert nettyTL.get() == null;
            log.info("Not inherit ok 2 | {}", jdkTL.get());
        });

        sleep(2000);
    }

    @SneakyThrows
    @Test
    public void timer() {
        WheelTimer timer = Tasks.timer();
//        ScheduledFuture<Integer> future = timer.schedule(() -> 1024, 1000, TimeUnit.MILLISECONDS);
//        long start = System.currentTimeMillis();
//        assert future.get() == 1024;
//        System.out.println("wait: " + (System.currentTimeMillis() - start) + "ms");

//        log.info("fixedRate-0");
//        ScheduledFuture<?> fixedRate = timer.scheduleAtFixedRate(() -> log.info("fixedRate"), 500, 1000, TimeUnit.MILLISECONDS);
//        log.info("delay: {}ms", fixedRate.getDelay(TimeUnit.MILLISECONDS));
//        sleep(5000);
//        fixedRate.cancel(true);
        Tasks.schedulePeriod(() -> log.info("fixedRate"), 1000);

//        timer.setTimeout(() -> {
//            System.out.println("once: " + DateTime.now());
//        }, d -> Math.max(d, 100) * 2);
//
//        timer.setTimeout(() -> {
//            System.out.println("loop: " + DateTime.now());
//            asyncContinue(true);
//        }, d -> Math.max(d, 100) * 2);

        //TimeoutFlag.SINGLE  根据taskId单线程执行，只要有一个线程在执行，其它线程直接跳过执行。
        //TimeoutFlag.REPLACE 根据taskId执行，如果已有其它线程执行或等待执行则都取消，只执行当前。
        //TimeoutFlag.PERIOD  定期重复执行，遇到异常不会终止直到asyncContinue(false) 或 next delay = -1。
//        AtomicInteger c = new AtomicInteger();
//        TimeoutFuture<Integer> f = timer.setTimeout(() -> {
//            System.out.println("loop: " + DateTime.now());
//            int i = c.incrementAndGet();
//            if (i > 10) {
//                asyncContinue(false);
//                return null;
//            }
//            if (i == 2) {
//                throw new InvalidException("Will exec next");
//            }
//            asyncContinue(true);
//            return i;
//        }, 1000, this, TimeoutFlag.PERIOD);
//        sleep(8000);
//        f.cancel();
//        System.out.println("last: " + f.get());

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
        System.out.println(cacheKey("prefix", "login", 12345));

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

        for (int i = 0; i < 2; i++) {
//            cache.put(key1, 100);
//            assert cache.get(key1).equals(100);
//            cache.put(key2, 200, CachePolicy.absolute(10));
//            assert cache.get(key2).equals(200);
            cache.put(key3, 300, CachePolicy.sliding(5));
            assert cache.get(key3).equals(300);
        }

//        assert cache.containsKey(key1);
//        assert cache.containsKey(key2);
//        assert cache.containsKey(key3);
//        assert cache.size() == 3;
//        Integer val1 = cache.remove(key1);
//        assert 100 == val1;
//        assert cache.size() == 2;

        Tasks.setTimeout(() -> {
            assert cache.get(key3).equals(300);
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

    @Test
    public void rasTest() {
        UUID id = UUID.randomUUID();
        String[] kp = RSAUtil.generateKeyPair();
        System.out.println("id=" + id + ", kp=" + toJsonString(kp));

        String publicKey = kp[0];
        String privateKey = kp[1];
        String content = "这是一个使用RSA公私钥对加解密的例子";

        String signMsg = RSAUtil.sign(content, privateKey);
        System.out.println("sign: " + signMsg);
        boolean verifySignResult = RSAUtil.verify(content, signMsg, publicKey);
        System.out.println("verify: " + verifySignResult);

        signMsg = RSAUtil.encrypt(content, publicKey);
        System.out.println("encrypt: " + signMsg);
        System.out.println("decrypt: " + RSAUtil.decrypt(signMsg, privateKey));
    }
    //endregion

    //region Linq & NEvent
    @Test
    public void parallelLinq() {
        Linq.from(Arrays.toList(1, 2, 3, 4), true).takeWhile((p) -> {
            Thread.sleep(200);
            System.out.println(Thread.currentThread().getName() + "=" + p);
            return true;
        });

        Linq<Integer> pq = Linq.from(Arrays.toList(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), true)
//                .groupBy(p -> p > 5, (p, x) -> x.first())
                ;
        //not work
        for (Integer p : pq) {
            log.info(p.toString());
        }
        pq.forEach(p -> {
            log.info(p.toString());
            throw new RuntimeException();
        });
    }

    @Test
    public void runLinq() {
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

        showResult("leftJoin", Linq.from(new PersonBean(27, 27, "jack", PersonGender.BOY, 6, DateTime.now(), 1L, Decimal.valueOf(1d), PersonBean.Flags, PersonBean.Array),
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

        showResult("groupBy(p -> p.index2...", Linq.from(personSet).groupBy(p -> p.index2, (p, x) -> {
            System.out.println("groupKey: " + p);
            List<PersonBean> list = x.toList();
            System.out.println("items: " + toJsonString(list));
            return list.get(0);
        }));
        showResult("groupByMany(p -> new Object[] { p.index2, p.index3 })",
                Linq.from(personSet).groupByMany(p -> Arrays.toList(p.index, p.index2), (p, x) -> {
                    System.out.println("groupKey: " + toJsonString(p));
                    List<PersonBean> list = x.toList();
                    System.out.println("items: " + toJsonString(list));
                    return list.get(0);
                }));

        showResult("orderBy(p->p.index)", Linq.from(personSet).orderBy(p -> p.index));
        showResult("orderByDescending(p->p.index)", Linq.from(personSet).orderByDescending(p -> p.index));
        showResult("orderByMany(p -> new Object[] { p.index2, p.index })",
                Linq.from(personSet).orderByMany(p -> Arrays.toList(p.index2, p.index)));
        showResult("orderByDescendingMany(p -> new Object[] { p.index2, p.index })",
                Linq.from(personSet).orderByDescendingMany(p -> Arrays.toList(p.index2, p.index)));

        showResult("select(p -> p.index).reverse()",
                Linq.from(personSet).orderBy(p -> p.index).select(p -> p.index).reverse());

        showResult(".max(p -> p.index)", Linq.from(personSet).<Integer>max(p -> p.index));
        showResult(".min(p -> p.index)", Linq.from(personSet).<Integer>min(p -> p.index));

        showResult("take(0).average(p -> p.index)", Linq.from(personSet).take(0).average(p -> p.index));
        showResult("average(p -> p.index)", Linq.from(personSet).average(p -> p.index));
        showResult("take(0).sum(p -> p.index)", Linq.from(personSet).take(0).sum(p -> p.index));
        showResult("sum(p -> p.index)", Linq.from(personSet).sum(p -> p.index));
        showResult("sumMoney(p -> p.index)", Linq.from(personSet).sumDecimal(p -> Decimal.valueOf((double) p.index)));

        showResult("cast<IPerson>", Linq.from(personSet).<IPerson>cast());
        Linq<?> oq = Linq.from(personSet).cast().union(Arrays.toList(1, 2, 3));
        showResult("ofType(Integer.class)", oq.ofType(Integer.class));

        showResult("firstOrDefault()", Linq.from(personSet).orderBy(p -> p.index).firstOrDefault());
        showResult("lastOrDefault()", Linq.from(personSet).orderBy(p -> p.index).lastOrDefault());
        showResult("skip(2)", Linq.from(personSet).orderBy(p -> p.index).skip(2));
        showResult("take(2)", Linq.from(personSet).orderBy(p -> p.index).take(2));

        showResult(".skipWhile((p, i) -> p.index < 3)",
                Linq.from(personSet).orderBy(p -> p.index).skipWhile((p, i) -> p.index < 3));

        showResult(".takeWhile((p, i) -> p.index < 3)",
                Linq.from(personSet).orderBy(p -> p.index).takeWhile((p, i) -> p.index < 3));

        Linq<PersonBean> set0 = Linq.from(personSet);
        Linq<PersonBean> set1 = set0.take(1);
        System.out.printf("set a=%s,b=%s%n", set0.count(), set1.count());
        assert set0.count() > set1.count();
    }

    private void showResult(String n, Object q) {
        System.out.println();
        System.out.println();
        System.out.println("showResult: " + n);
        System.out.println(toJsonString(q));
    }

    private void showResult(String n, Linq<?> q) {
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
        executor.onPrintOut.combine(ShellCommander.CONSOLE_OUT_HANDLER);
        executor.start().waitFor();

        executor = new ShellCommander("ping f-li.cn", null);
        ShellCommander finalExecutor = executor;
        executor.onPrintOut.combine((s, e) -> {
            System.out.println("K: " + e.getLine());
            finalExecutor.kill();
        });
        executor.onPrintOut.combine(new ShellCommander.FileOutHandler(TConfig.path("out.txt")));
        executor.onExited.combine((s, e) -> System.out.println("shell exit"));
        executor.start().waitFor();

        System.out.println("done");
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
        AtomicInteger i = new AtomicInteger();
        FluentWait.newInstance(2000, 200).until(s -> {
            log.info("each[{}] {}", i.incrementAndGet(), s);
            return true;
        });
        assert i.get() == 1;
        FluentWait.newInstance(2000, 200).until(s -> {
            log.info("each[{}] {}", i.incrementAndGet(), s);
            return s.getEvaluatedCount() == 9;
        });
        assert i.get() == 11;

        FluentWait.newInstance(2000, 200).retryEvery(500).until(s -> {
            log.info("each {}", s);
            return s.getEvaluatedCount() == 9;
        }, s -> {
            log.info("doRetry {}", s);
            return true;
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
        TraceHandler handler = TraceHandler.INSTANCE;
        handler.log(new InvalidException("test error"));
        System.out.println(handler.queryTraces(null, (ExceptionLevel) null, null));


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


        assert eq(new InvalidException(err).getMessage(), err);
        err += "x{}y";
        assert eq(new InvalidException(err).getMessage(), err);
        assert eq(new InvalidException("have {} err", 2).getMessage(), "have 2 err");
        assert eq(new InvalidException("have {} err", 2, new RuntimeException()).getMessage(), "have 2 err; nested exception is java.lang.RuntimeException");
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
