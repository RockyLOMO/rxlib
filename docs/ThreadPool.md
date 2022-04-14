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
```

