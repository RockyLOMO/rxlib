package org.rx.core;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import okhttp3.ResponseBody;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.rx.AbstractTester;
import org.rx.annotation.DbColumn;
import org.rx.annotation.ErrorCode;
import org.rx.annotation.Subscribe;
import org.rx.bean.*;
import org.rx.codec.RSAUtil;
import org.rx.core.cache.DiskCache;
import org.rx.exception.ApplicationException;
import org.rx.exception.ExceptionLevel;
import org.rx.exception.InvalidException;
import org.rx.exception.TraceHandler;
import org.rx.io.EntityDatabase;
import org.rx.io.EntityQueryLambda;
import org.rx.io.MemoryStream;
import org.rx.net.Sockets;
import org.rx.third.open.CrcModel;
import org.rx.util.function.Func;
import org.rx.util.function.TripleAction;
import org.slf4j.MDC;

import javax.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.rx.bean.$.$;
import static org.rx.core.Extends.*;
import static org.rx.core.Sys.*;

@Slf4j
public class TestCore extends AbstractTester {
    //region thread pool
    @SneakyThrows
    @Test
    public void threadPool() {
        ThreadPool pool = Tasks.nextPool();
        //RunFlag.SINGLE        根据taskId单线程执行，只要有一个线程在执行，其它线程直接跳过执行。
        //RunFlag.SYNCHRONIZED  根据taskId同步执行，只要有一个线程在执行，其它线程等待执行。
        //RunFlag.TRANSFER      直到任务被执行或放入队列否则一直阻塞调用线程。
        //RunFlag.PRIORITY      如果线程和队列都无可用的则直接新建线程执行。
        //RunFlag.INHERIT_THREAD_LOCALS 子线程会继承父线程的FastThreadLocal
        //RunFlag.THREAD_TRACE  开启trace,支持timer和CompletableFuture
        AtomicInteger c = new AtomicInteger();
        for (int i = 0; i < 5; i++) {
            int x = i;
            Future<Void> f1 = pool.run(() -> {
                log.info("exec SINGLE begin {}", x);
                c.incrementAndGet();
                sleep(oneSecond);
                wait.set();
                log.info("exec SINGLE end {}", x);
            }, c, RunFlag.SINGLE.flags());
        }
        wait.waitOne();
        wait.reset();
        assert c.get() == 1;

        for (int i = 0; i < 5; i++) {
            int x = i;
            Future<Void> f1 = pool.run(() -> {
                log.info("exec SYNCHRONIZED begin {}", x);
                c.incrementAndGet();
                sleep(oneSecond);
                log.info("exec SYNCHRONIZED end {}", x);
            }, c, RunFlag.SYNCHRONIZED.flags());
        }
        sleep(6000);
        assert c.get() == 6;

        for (int i = 0; i < 5; i++) {
            int x = i;
            Future<Void> f1 = pool.run(() -> {
                log.info("exec TRANSFER begin {}", x);
                c.incrementAndGet();
                sleep(oneSecond);
                log.info("exec TRANSFER end {}", x);
            }, c, RunFlag.TRANSFER.flags());
        }
        sleep(6000);
        assert c.get() == 11;


        c.set(0);
        for (int i = 0; i < 5; i++) {
            int x = i;
            CompletableFuture<Void> f1 = pool.runAsync(() -> {
                log.info("exec SINGLE begin {}", x);
                c.incrementAndGet();
                sleep(oneSecond);
                wait.set();
                log.info("exec SINGLE end {}", x);
            }, c, RunFlag.SINGLE.flags());
            f1.whenCompleteAsync((r, e) -> log.info("exec SINGLE uni"));
        }
        wait.waitOne();
        wait.reset();
        assert c.get() == 1;

        for (int i = 0; i < 5; i++) {
            int x = i;
            CompletableFuture<Void> f1 = pool.runAsync(() -> {
                log.info("exec SYNCHRONIZED begin {}", x);
                c.incrementAndGet();
                sleep(oneSecond);
                log.info("exec SYNCHRONIZED end {}", x);
            }, c, RunFlag.SYNCHRONIZED.flags());
            f1.whenCompleteAsync((r, e) -> log.info("exec SYNCHRONIZED uni"));
        }
        sleep(8000);
        assert c.get() == 6;

        pool.runAsync(() -> System.out.println("runAsync"))
                .whenCompleteAsync((r, e) -> System.out.println("whenCompleteAsync"))
                .join();
        List<Func<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            int x = i;
            tasks.add(() -> {
                log.info("TASK begin {}", x);
                sleep(oneSecond);
                log.info("TASK end {}", x);
                return x + 100;
            });
        }
        List<Future<Integer>> futures = pool.runAll(tasks, 0);
        for (Future<Integer> future : futures) {
            System.out.println(future.get());
        }

        ThreadPool.MultiTaskFuture<Integer, Integer> anyMf = pool.runAnyAsync(tasks);
        anyMf.getFuture().whenCompleteAsync((r, e) -> log.info("ANY TASK MAIN uni"));
        for (CompletableFuture<Integer> sf : anyMf.getSubFutures()) {
            sf.whenCompleteAsync((r, e) -> log.info("ANY TASK uni {}", r));
        }
        for (CompletableFuture<Integer> sf : anyMf.getSubFutures()) {
            sf.join();
        }
        log.info("wait ANY TASK");
        anyMf.getFuture().get();

        ThreadPool.MultiTaskFuture<Void, Integer> mf = pool.runAllAsync(tasks);
        mf.getFuture().whenCompleteAsync((r, e) -> log.info("ALL TASK MAIN uni"));
        for (CompletableFuture<Integer> sf : mf.getSubFutures()) {
            sf.whenCompleteAsync((r, e) -> log.info("ALL TASK uni {}", r));
        }
        for (CompletableFuture<Integer> sf : mf.getSubFutures()) {
            sf.join();
        }
        log.info("wait ALL TASK");
        mf.getFuture().get();
    }

    @Test
    public void threadPoolAutosize() {
        //LinkedTransferQueue基于CAS实现，大部分场景下性能比LinkedBlockingQueue好。
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
    }

    @SneakyThrows
    @Test
    public void inheritThreadLocal() {
        //线程trace，支持异步trace包括Executor(ThreadPool), ScheduledExecutorService(WheelTimer), CompletableFuture.xxAsync()系列方法。
        RxConfig.INSTANCE.getThreadPool().setTraceName("rx-traceId");
        ThreadPool.traceIdGenerator = () -> UUID.randomUUID().toString().replace("-", "");
        ThreadPool.onTraceIdChanged.combine((s, e) -> MDC.put("rx-traceId", e.getValue()));
        ThreadPool pool = new ThreadPool(3, 1, new IntWaterMark(20, 40), "DEV");

        //当线程池无空闲线程时，任务放置队列后，当队列任务执行时会带上正确的traceId
//        ThreadPool.startTrace(null);
//        for (int i = 0; i < 2; i++) {
//            int finalI = i;
//            pool.run(() -> {
//                log.info("TRACE DELAY-1 {}", finalI);
//                pool.run(() -> {
//                    log.info("TRACE DELAY-1_1 {}", finalI);
//                    sleep(oneSecond);
//                });
//                sleep(oneSecond);
//            });
//            log.info("TRACE DELAY MAIN {}", finalI);
//            pool.run(() -> {
//                log.info("TRACE DELAY-2 {}", finalI);
//                sleep(oneSecond);
//            });
//        }
//        ThreadPool.endTrace();
//        sleep(8000);
//
//        //WheelTimer(ScheduledExecutorService) 异步trace
//        WheelTimer timer = Tasks.timer();
//        ThreadPool.startTrace(null);
//        for (int i = 0; i < 2; i++) {
//            int finalI = i;
//            timer.setTimeout(() -> {
//                log.info("TRACE TIMER {}", finalI);
//                sleep(oneSecond);
//            }, oneSecond);
//            log.info("TRACE TIMER MAIN {}", finalI);
//        }
//        ThreadPool.endTrace();
//        sleep(4000);
//
//        //CompletableFuture.xxAsync异步方法正确获取trace
//        ThreadPool.startTrace(null);
//        for (int i = 0; i < 2; i++) {
//            int finalI = i;
//            pool.runAsync(() -> {
//                log.info("TRACE ASYNC-1 {}", finalI);
//                pool.runAsync(() -> {
//                    log.info("TRACE ASYNC-1_1 {}", finalI);
//                    sleep(oneSecond);
//                }).whenCompleteAsync((r, e) -> log.info("TRACE ASYNC-1_1 uni {}", r));
//                sleep(oneSecond);
//            }).whenCompleteAsync((r, e) -> log.info("TRACE ASYNC-1 uni {}", r));
//            log.info("TRACE ASYNC MAIN {}", finalI);
//            pool.runAsync(() -> {
//                log.info("TRACE ASYNC-2 {}", finalI);
//                sleep(oneSecond);
//            }).whenCompleteAsync((r, e) -> log.info("TRACE ASYNC-2 uni {}", r));
//        }
//        ThreadPool.endTrace();
//        sleep(10000);
//
//        //netty FastThreadLocal 支持继承
//        FastThreadLocal<Integer> ftl = new FastThreadLocal<>();
//        ftl.set(64);
//        pool.run(() -> {
//            assert ftl.get() == 64;
//            log.info("Inherit ok 1");
//        }, null, RunFlag.INHERIT_FAST_THREAD_LOCALS.flags());
//
//        pool.runAsync(() -> {
//            assert ftl.get() == 64;
//            log.info("Inherit ok 2");
//        }, null, RunFlag.INHERIT_FAST_THREAD_LOCALS.flags());
//        sleep(2000);
//
        log.info("--ExecutorService--");
        ThreadPool.startTrace(null);
//        ExecutorService es = pool;
//        es.submit(() -> {
//            log.info("submit..");
//            return 1024;
//        });
//        es.execute(() -> {
//            log.info("exec..");
//        });
//        sleep(1000);

        log.info("test scope1 start");
        ThreadPool.startTrace("newScope1", true);
        log.info("test scope2 start");

        ThreadPool.startTrace("newScope2", true);
        log.info("test scope3");
        ThreadPool.endTrace();

        log.info("test scope2 end");
        ThreadPool.endTrace();

        log.info("test scope1 end");
        ThreadPool.endTrace();
        log.info("--done--");
    }

    @SneakyThrows
    @Test
    public void serialAsync() {
        RxConfig.INSTANCE.getThreadPool().setTraceName("rx-traceId");
        ThreadPool.onTraceIdChanged.combine((s, e) -> MDC.put("rx-traceId", e.getValue()));
        ThreadPool.startTrace(null);

        ThreadPool pool = Tasks.nextPool();
        String tid1 = "sat1", tid2 = "sat2";
        Future<Integer> f = null;
        for (int i = 0; i < 10; i++) {
            Object tid = i % 2 == 0 ? tid1 : tid2;
            int finalI = i;
            f = pool.runSerial(() -> {
                log.info("serial {} - {}", tid, finalI);
                return finalI + 100;
            }, tid);
        }
        log.info("last result {}", f.get());
        System.out.println("ok");

        CompletableFuture<Integer> fa = null;
        for (int i = 0; i < 10; i++) {
            Object tid = i % 2 == 0 ? tid1 : tid2;
            int finalI = i;
            fa = pool.runSerialAsync(() -> {
                log.info("serial {} - {} CTX:{}", tid, finalI, ThreadPool.completionReturnedValue());
                return finalI + 100;
            }, tid);
            if (i == 5) {
                CompletableFuture<String> tf = fa.thenApplyAsync(rv -> {
                    log.info("linkTf returned {}", rv);
                    return "okr";
                });
                log.info("linkTf then get {}", tf.get());
            }
        }
        log.info("last result {}", fa.get());
        sleep(2000);
        System.out.println("ok");
    }

    @SneakyThrows
    @Test
    public void timer() {
        WheelTimer timer = Tasks.timer();
        //TimeoutFlag.SINGLE       根据taskId单线程执行，只要有一个线程在执行，其它线程直接跳过执行。
        //TimeoutFlag.REPLACE      根据taskId执行，如果已有其它线程执行或等待执行则都取消，只执行当前。
        //TimeoutFlag.PERIOD       定期重复执行，遇到异常不会终止直到asyncContinue(false) 或 next delay = -1。
        //TimeoutFlag.THREAD_TRACE 开启trace
        AtomicInteger c = new AtomicInteger();
        for (int i = 0; i < 5; i++) {
            int finalI = i;
            timer.setTimeout(() -> {
                log.info("exec SINGLE plus by {}", finalI);
                assert finalI == 0;
                c.incrementAndGet();
                sleep(oneSecond);
                wait.set();
            }, oneSecond, c, TimeoutFlag.SINGLE.flags());
        }
        wait.waitOne();
        wait.reset();
        assert c.get() == 1;
        log.info("exec SINGLE flag ok..");

        for (int i = 0; i < 5; i++) {
            int finalI = i;
            timer.setTimeout(() -> {
                log.info("exec REPLACE plus by {}", finalI);
                assert finalI == 4;
                c.incrementAndGet();
                sleep(oneSecond);
                wait.set();
            }, oneSecond, c, TimeoutFlag.REPLACE.flags());
        }
        wait.waitOne();
        wait.reset();
        assert c.get() == 2;
        log.info("exec REPLACE flag ok..");

        TimeoutFuture<Integer> f = timer.setTimeout(() -> {
            log.info("exec PERIOD");
            int i = c.incrementAndGet();
            if (i > 10) {
                circuitContinue(false);
                return null;
            }
            if (i == 4) {
                throw new InvalidException("Will exec next");
            }
            circuitContinue(true);
            return i;
        }, oneSecond, c, TimeoutFlag.PERIOD.flags());
        sleep(8000);
        f.cancel();
        log.info("exec PERIOD flag ok and last value={}", f.get());
        assert f.get() == 9;

        c.set(0);
        timer.setTimeout(() -> {
            log.info("exec nextDelayFn");
            c.incrementAndGet();
            circuitContinue(true);
        }, d -> d > 1000 ? -1 : Math.max(d, 100) * 2);
        sleep(5000);
        log.info("exec nextDelayFn ok");
        assert c.get() == 4;

        //包装为ScheduledExecutorService
        ScheduledExecutorService ses = timer;
        ScheduledFuture<Integer> f1 = ses.schedule(() -> 1024, oneSecond, TimeUnit.MILLISECONDS);
        long start = System.currentTimeMillis();
        assert f1.get() == 1024;
        log.info("schedule wait {}ms", (System.currentTimeMillis() - start));

        log.info("scheduleAtFixedRate step 1");
        ScheduledFuture<?> f2 = ses.scheduleAtFixedRate(() -> log.info("scheduleAtFixedRate step 2"), 500, oneSecond, TimeUnit.MILLISECONDS);
        log.info("scheduleAtFixedRate delay {}ms", f2.getDelay(TimeUnit.MILLISECONDS));
        sleep(5000);
        f2.cancel(true);
        log.info("scheduleAtFixedRate delay {}ms", f2.getDelay(TimeUnit.MILLISECONDS));
    }

    @SneakyThrows
    @Test
    public void mx() {
        assert Sys.formatNanosElapsed(985).equals("985ns");
        assert Sys.formatNanosElapsed(211985).equals("211µs");
        assert Sys.formatNanosElapsed(2211985).equals("2ms");
        assert Sys.formatNanosElapsed(2211985, 1).equals("2s");
        assert Sys.formatNanosElapsed(2048211985).equals("2s");

//        Sys.mxScheduleTask(p -> {
//            log.info("Disk={} <- {}", p.getDisks().where(DiskInfo::isBootstrapDisk).first().getUsedPercent(),
//                    toJsonString(p));
//        });
//
//        System.out.println("main thread done");
//        sleep(12000);
//        log.info("dlt: {}", Sys.findDeadlockedThreads());
//        log.info("at: {}", Sys.getAllThreads().toJoinString("\n", ThreadInfo::toString));

        Tasks.nextPool().submit(() -> {
            log.info("pool exec");
            throw new Exception("pool exec");
        });
        Tasks.timer().setTimeout(() -> {
            log.info("timer exec");
            throw new Exception("timer exec");
        }, 1000);

        _wait();
    }
    //endregion

    @Test
    public void ntpClock() {
        //硬码方式获取同步时间
        NtpClock clock = NtpClock.UTC;
        log.info("local ts {}", clock.millis());

        //手动同步
        NtpClock.sync();
        log.info("ntp ts {}", clock.millis());

        //定时同步
        RxConfig.INSTANCE.getNet().getNtp().setSyncPeriod(2000);
        NtpClock.scheduleTask();
        for (int i = 0; i < 10; i++) {
            sleep(2500);
            log.info("ntp ts {}", clock.millis());
        }

        //注入System.currentTimeMillis()方法，全局代码无入侵
        NtpClock.TimeAdvice.transform();
    }

    @Test
    public void objectPool() {
        AtomicInteger c = new AtomicInteger();
        ObjectPool<Long> pool = new ObjectPool<>(1, 5, () -> {
            System.out.println(1);
            return (long) c.incrementAndGet();
        }, x -> true, x -> {
        });

        List<Tuple<Long, String>> msg = new Vector<>();
        for (int i = 0; i < 10; i++) {
            Tasks.run(() -> {
                msg.add(Tuple.of(System.nanoTime(), String.format("%s preBorrow %s - ?", Thread.currentThread().getId(), pool.size())));
                Long t = pool.borrow();
                msg.add(Tuple.of(System.nanoTime(), String.format("%s postBorrow %s - %s", Thread.currentThread().getId(), pool.size(), t)));
                log.info("{}: borrow {} - {}", System.nanoTime(), pool.size(), t);

                msg.add(Tuple.of(System.nanoTime(), String.format("%s preRecycle %s - %s", Thread.currentThread().getId(), pool.size(), t)));
                pool.recycle(t);
                msg.add(Tuple.of(System.nanoTime(), String.format("%s postRecycle %s - %s", Thread.currentThread().getId(), pool.size(), t)));
                log.info("{}: recycle {} - {}", System.nanoTime(), pool.size(), t);
            });
        }
        sleep(2000);
        for (Tuple<Long, String> tuple : Linq.from(msg).orderBy(p -> p.left)) {
            System.out.println(tuple.right);
        }

        pool.setIdleTimeout(1);
        pool.setValidationTime(1);
        pool.setLeakDetectionThreshold(1);
//        pool.setRetireLeak(true);
        sleep(15000);
    }

    static final String MY_TOPIC = "myTopic";
    static final String HER_TOPIC = "herTopic";
    AtomicInteger eventBusCounter = new AtomicInteger();
    AtomicInteger eventBusTopicCounter = new AtomicInteger();

    @Test
    public void eventBus() {
        AtomicInteger deadEventCounter = new AtomicInteger();
        EventBus bus = EventBus.DEFAULT;
        bus.onDeadEvent.combine((s, e) -> {
            log.error("DeadEvent {}", e.getValue());
            deadEventCounter.incrementAndGet();
        });

        bus.register(this);
        bus.register(this);
        for (int i = 0; i < 4; i++) {
            String topic = i % 2 == 0 ? MY_TOPIC : null;
            bus.publish(PersonBean.YouFan, topic);
        }
        assert eventBusCounter.get() == 2;
        assert eventBusTopicCounter.get() == 4;
        assert deadEventCounter.get() == 0;

        bus.unregister(this, MY_TOPIC);
        for (int i = 0; i < 2; i++) {
            bus.publish(PersonBean.YouFan, MY_TOPIC);
        }
        assert eventBusCounter.get() == 2;
        assert eventBusTopicCounter.get() == 4;
        assert deadEventCounter.get() == 2;

        bus.register(this);
        for (int i = 0; i < 4; i++) {
            String topic = i % 2 == 0 ? MY_TOPIC : null;
            bus.publish(PersonBean.YouFan, topic);
        }
        assert eventBusCounter.get() == 4;
        assert eventBusTopicCounter.get() == 8;
        assert deadEventCounter.get() == 2;

        bus.unregister(this);
        for (int i = 0; i < 2; i++) {
            bus.publish(PersonBean.YouFan);
        }
        assert eventBusCounter.get() == 4;
        assert eventBusTopicCounter.get() == 8;
        assert deadEventCounter.get() == 4;

        bus.register(TestCore.class);
        bus.publish(1);
    }

    @Subscribe
    void OnUserCreate(PersonBean personBean) {
        log.info("OnUserCreate: {}", personBean);
        eventBusCounter.incrementAndGet();
    }

    @Subscribe(MY_TOPIC)
    void OnUserCreateWithTopic(PersonBean personBean) {
        log.info("OnUserCreateWithTopic: {}", personBean);
        eventBusTopicCounter.incrementAndGet();
    }

    @Subscribe
    static void onEvent(Integer obj) {
        log.info("onEvent {}", obj);
    }

    @Subscribe(topicClass = TestCore.class)
    static void onEventWithTopic(Integer obj) {
        log.info("onEventWithTopic {}", obj);
    }

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

        showResult("leftJoin", Linq.from(new PersonBean(27, 27, "jack", PersonGender.BOY, 6, DateTime.now(), 1L, Decimal.valueOf(1d), PersonBean.PROP_Flags, PersonBean.PROP_EXTRA),
                new PersonBean(28, 28, "tom", PersonGender.BOY, 6, DateTime.now(), 1L, Decimal.valueOf(1d), PersonBean.PROP_Flags, PersonBean.PROP_EXTRA),
                new PersonBean(29, 29, "lily", PersonGender.GIRL, 8, DateTime.now(), 1L, Decimal.valueOf(1d), PersonBean.PROP_Flags, PersonBean.PROP_EXTRA),
                new PersonBean(30, 30, "cookie", PersonGender.BOY, 6, DateTime.now(), 1L, Decimal.valueOf(1d), PersonBean.PROP_Flags, PersonBean.PROP_EXTRA)).leftJoin(
                Arrays.toList(new PersonBean(27, 27, "cookie", PersonGender.BOY, 5, DateTime.now(), 1L, Decimal.valueOf(1d), PersonBean.PROP_Flags, PersonBean.PROP_EXTRA),
                        new PersonBean(28, 28, "tom", PersonGender.BOY, 10, DateTime.now(), 1L, Decimal.valueOf(1d), PersonBean.PROP_Flags, PersonBean.PROP_EXTRA),
                        new PersonBean(29, 29, "jack", PersonGender.BOY, 1, DateTime.now(), 1L, Decimal.valueOf(1d), PersonBean.PROP_Flags, PersonBean.PROP_EXTRA),
                        new PersonBean(30, 30, "session", PersonGender.BOY, 25, DateTime.now(), 1L, Decimal.valueOf(1d), PersonBean.PROP_Flags, PersonBean.PROP_EXTRA),
                        new PersonBean(31, 31, "trump", PersonGender.BOY, 55, DateTime.now(), 1L, Decimal.valueOf(1d), PersonBean.PROP_Flags, PersonBean.PROP_EXTRA),
                        new PersonBean(32, 32, "jack", PersonGender.BOY, 55, DateTime.now(), 1L, Decimal.valueOf(1d), PersonBean.PROP_Flags, PersonBean.PROP_EXTRA)), (p, x) -> p.name.equals(x.name), Tuple::of
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

        mgr.onCreate.last((s, e) -> System.out.println("always tail:" + e));
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

        mgr.onCreate.purge().combine((s, e) -> {
            e.setHandled(true);
            System.out.println("only handled");
        }, a);
        mgr.create(p);
    }
    //endregion

    //region codec
    @Data
    public static class CollisionEntity implements Serializable {
        @DbColumn(primaryKey = true)
        long id;
    }

    @Test
    public void codec() {
        EntityDatabase db = EntityDatabase.DEFAULT;
        db.createMapping(CollisionEntity.class);
        db.dropMapping(CollisionEntity.class);
        db.createMapping(CollisionEntity.class);
        int c = 200000000;
        AtomicInteger collision = new AtomicInteger();
        invoke("codec", i -> {
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

        String signMsg = RSAUtil.sign(str_name_wyf, privateKey);
        System.out.println("sign: " + signMsg);
        boolean verifySignResult = RSAUtil.verify(str_name_wyf, signMsg, publicKey);
        System.out.println("verify: " + verifySignResult);
        assert verifySignResult;

        signMsg = RSAUtil.encrypt(str_name_wyf, publicKey);
        System.out.println("encrypt: " + signMsg);
        assert str_name_wyf.equals(RSAUtil.decrypt(signMsg, privateKey));
    }
    //endregion

    @Test
    public void json() {
//        RxConfig.INSTANCE.getJsonSkipTypes().add(ErrorBean.class);
        Object[] args = new Object[]{str_name_wyf, proxy(HttpServletResponse.class, (m, i) -> {
            throw new InvalidException("wont reach");
        }), new ErrorBean()};
        System.out.println(toJsonString(args) + " -> " + toJsonString(args));
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
        data.put("name", str_name_wyf);
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

        //fastjson2
        Object[] x = {2, "b"};
        Iterable<Object> iter = new Iterable<Object>() {
            @NotNull
            @Override
            public Iterator<Object> iterator() {
                return Collections.singletonList(x[0]).iterator();
            }
        };
        System.out.println(toJsonString(x) + ", "
                + toJsonString(iter) + " & " + toJsonString(Collections.singletonMap("list", iter)));
        System.out.println(JSON.toJSONString(x) + ", "
                + JSON.toJSONString(iter) + " & " + JSON.toJSONString(Collections.singletonMap("list", iter)));
        InetAddress addr = Sockets.getLocalAddress();
        System.out.println(toJsonString(addr) + ", " + toJsonString(Collections.singletonList(addr)) + " & " + JSON.toJSONString(addr));
    }

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
        executor.onPrintOut.combine(new ShellCommander.FileOutHandler(AbstractTester.path("out.txt")));
        executor.onExited.combine((s, e) -> System.out.println("shell exit"));
        executor.start().waitFor();

        System.out.println("done");
        sleep(5000);
    }

    @Test
    public void fluentWait() throws TimeoutException {
        AtomicInteger i = new AtomicInteger();
        FluentWait.polling(2000, 200).await(w -> {
            log.info("each[{}] {}", i.incrementAndGet(), w);
            return true;
        });
        assert i.get() == 1;
        FluentWait.polling(2000, 200).awaitTrue(w -> {
            log.info("each[{}] {}", i.incrementAndGet(), w);
            return w.getEvaluatedCount() == 9;
        });
        assert i.get() == 11;

        FluentWait.polling(2000, 200).retryEvery(500, w -> {
            log.info("doRetry {}", w);
        }).awaitTrue(w -> {
            log.info("each ec={} {}", w.getEvaluatedCount(), w);
            w.signalAll();
            return w.getEvaluatedCount() == 9;
        });
    }

    @Test
    public void dynamicProxy() {
        PersonBean leZhi = PersonBean.LeZhi;

        IPerson proxy = proxy(PersonBean.class, (m, p) -> p.fastInvoke(leZhi), leZhi, false);
        assert targetObject(proxy) == leZhi;

        IPerson iproxy = proxy(IPerson.class, (m, p) -> p.fastInvoke(leZhi), leZhi, false);
        assert targetObject(iproxy) == leZhi;
    }

    @SneakyThrows
    @Test
    public void reflect() {
        for (InputStream resource : Reflects.getResources(Constants.DEFAULT_CONFIG_FILE)) {
            System.out.println(resource);
            assert resource != null;
        }
        assert Reflects.stackClass(0) == this.getClass();
//        for (StackTraceElement traceElement : Reflects.stackTrace(8)) {
//            System.out.println(traceElement);
//        }

        Tuple<String, String> resolve = Reflects.resolveImpl(PersonBean::getAge);
        assert resolve.left.equals(PersonBean.class.getName()) && resolve.right.equals("age");
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
        Method genericCall = Reflects.getMethodMap(bean.getClass()).get("genericCall").first();

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

        assert Reflects.isBasicType(int.class)
                && Reflects.isBasicType(Integer.class)
                && Reflects.isBasicType(boolean.class)
                && Reflects.isBasicType(Boolean.class)
                && Reflects.isBasicType(BigDecimal.class);
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
    public void yamlConf() {
        YamlConfiguration conf = YamlConfiguration.RX_CONF;
//        conf.enableWatch();

        System.out.println(conf.getYaml());
        Map codeMap = conf.readAs("org.rx.test.CoreTester", Map.class);
        System.out.println(codeMap);
        String codeFormat = conf.readAs("org.rx.test.CoreTester.exceptionHandle<IllegalArgumentException>", String.class);
        System.out.println(codeFormat);
        assert eq(codeFormat, "Test IAException, value={0}");

//        Iterable<Object> all = new Yaml().loadAll(Reflects.getResource("application.yml"));
//        List<Object> list = IterableUtils.toList(all);
        RxConfig rxConf = RxConfig.INSTANCE;
        System.out.println(rxConf.getNet().getReactorThreadAmount());
//        assert rxConf.getId().equals("Rx");
//        assert rxConf.getThreadPool().getReplicas() == 4;
//        assert rxConf.getNet().getConnectTimeoutMillis() == 40000;
//        assert rxConf.getLogTypeWhitelist().size() == 2;
//        assert rxConf.getJsonSkipTypes().size() == 1;

        sleep(60000);
    }
}
