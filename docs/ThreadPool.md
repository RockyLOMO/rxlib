## 常见线程池

* Executors.newCachedThreadPool(); 
  没有queue缓冲，一直new thread执行，当CPU负载高时加上更多线程上下文切换损耗，性能会急速下降。
* Executors.newFixedThreadPool(16); 
  执行的thread数量固定，但当thread等待时间（IO Wait / Blocked Time）过长时会造成吞吐量下降。当堆积的任务过多时，无界的LinkedBlockingQueue可能会引发OOM。
* new ThreadPoolExecutor(nThreads, nThreads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(10000));
  有界的LinkedBlockingQueue可以避免OOM，但吞吐量下降的情况避免不了，加上LinkedBlockingQueue使用的重量级锁ReentrantLock对并发下性能可能有影响。

## 最佳线程数线程池

最佳线程数=CPU 线程数 * (1 + CPU 等待时间 / CPU 执行时间)，由于执行任务的不同，CPU 等待时间和执行时间无法确定，因此换一种思路，当列队满的情况下，如果CPU使用率小于40%，则会动态增大线程池maxThreads 最大线程数的值来提高吞吐量。如果CPU使用率大于60%，则会动态减小maxThreads 值来降低生产者的任务生产速度。当最小线程数的线程量处理不过来的时候，会创建到最大线程数的线程量来执行。当最大线程量的线程执行不过来的时候，会把任务丢进列队，当列队满的时候会阻塞当前线程，降低生产者的生产速度。

### 调用方式

```java
@SneakyThrows
@Test
public void threadPool() {
    ThreadPool pool = Tasks.pool();
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
    sleep(8000);
    assert c.get() == 6;


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
}
```

## 异步trace

zipkin不支持异步trace，rxlib提供支持支持异步trace包括Executor(ThreadPool), ScheduledExecutorService(WheelTimer), CompletableFuture.xxAsync()系列方法。

```java
@SneakyThrows
@Test
public void inheritThreadLocal() {
    //线程trace，支持异步trace包括Executor(ThreadPool), ScheduledExecutorService(WheelTimer), CompletableFuture.xxAsync()系列方法。
    RxConfig.INSTANCE.getThreadPool().setTraceName("rx-traceId");
    ThreadPool.traceIdGenerator = () -> UUID.randomUUID().toString().replace("-", "");
    ThreadPool.traceIdChangedHandler = t -> MDC.put("rx-traceId", t);
    ThreadPool pool = new ThreadPool(3, 1, new IntWaterMark(20, 40), "DEV");

    //当线程池无空闲线程时，任务放置队列后，当队列任务执行时会带上正确的traceId
    ThreadPool.startTrace(null);
    for (int i = 0; i < 2; i++) {
        int finalI = i;
        pool.run(() -> {
            log.info("TRACE DELAY-1 {}", finalI);
            pool.run(() -> {
                log.info("TRACE DELAY-1_1 {}", finalI);
                sleep(oneSecond);
            });
            sleep(oneSecond);
        });
        log.info("TRACE DELAY MAIN {}", finalI);
        pool.run(() -> {
            log.info("TRACE DELAY-2 {}", finalI);
            sleep(oneSecond);
        });
    }
    ThreadPool.endTrace();
    sleep(8000);

    //WheelTimer(ScheduledExecutorService) 异步trace
    WheelTimer timer = Tasks.timer();
    ThreadPool.startTrace(null);
    for (int i = 0; i < 2; i++) {
        int finalI = i;
        timer.setTimeout(() -> {
            log.info("TRACE TIMER {}", finalI);
            sleep(oneSecond);
        }, oneSecond);
        log.info("TRACE TIMER MAIN {}", finalI);
    }
    ThreadPool.endTrace();
    sleep(4000);

    //CompletableFuture.xxAsync异步方法正确获取trace
    ThreadPool.startTrace(null);
    for (int i = 0; i < 2; i++) {
        int finalI = i;
        pool.runAsync(() -> {
            log.info("TRACE ASYNC-1 {}", finalI);
            pool.runAsync(() -> {
                log.info("TRACE ASYNC-1_1 {}", finalI);
                sleep(oneSecond);
            }).whenCompleteAsync((r, e) -> log.info("TRACE ASYNC-1_1 uni {}", r));
            sleep(oneSecond);
        }).whenCompleteAsync((r, e) -> log.info("TRACE ASYNC-1 uni {}", r));
        log.info("TRACE ASYNC MAIN {}", finalI);
        pool.runAsync(() -> {
            log.info("TRACE ASYNC-2 {}", finalI);
            sleep(oneSecond);
        }).whenCompleteAsync((r, e) -> log.info("TRACE ASYNC-2 uni {}", r));
    }
    ThreadPool.endTrace();
    sleep(10000);

    //netty FastThreadLocal 支持继承
    FastThreadLocal<Integer> ftl = new FastThreadLocal<>();
    ftl.set(64);
    pool.run(() -> {
        assert ftl.get() == 64;
        log.info("Inherit ok 1");
    }, null, RunFlag.INHERIT_FAST_THREAD_LOCALS.flags());

    pool.runAsync(() -> {
        assert ftl.get() == 64;
        log.info("Inherit ok 2");
    }, null, RunFlag.INHERIT_FAST_THREAD_LOCALS.flags());
    sleep(2000);
}
```

## 定时任务

### ScheduledExecutorService

jdk实现的ScheduledExecutorService只会创建coreSize的线程，coreSize设置小了吞吐量低，coreSize设置大了性能反而降低。另外当执行的任务blocking wait多时，耗光了coreSize的线程后续任务堆积不能按时处理。rxlib实现的ScheduledThreadPool同上述线程池一样依据cpuLoad动态调整coreSize解决痛点问题。

### Netty WheelTimer

WheelTimer虽然精度不准，但是只消耗1个线程以及消耗更少的内存。单线程的HashedWheelTimer也使blocking wait痛点放大，好在动态调整maxSize的ThreadPool存在，WheelTimer只做调度，执行全交给ThreadPool异步执行，完美解决痛点。

```java
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
            asyncContinue(false);
            return null;
        }
        if (i == 4) {
            throw new InvalidException("Will exec next");
        }
        asyncContinue(true);
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
        asyncContinue(true);
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
```
