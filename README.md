# rxlib-java
A set of utilities for Java.

* ThreadPool - optimum thread count
```java
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
    ExecutorService pool = new ThreadPool(1, 1, 1, 8, "RxPool")
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
```

* [BeanMapper - 基于cglib bytecode实现](https://github.com/RockyLOMO/rxlib/wiki/BeanMapper---%E5%9F%BA%E4%BA%8Ecglib-bytecode%E5%AE%9E%E7%8E%B0)
* [Rpc - netty tcp 实现](https://github.com/RockyLOMO/rxlib/wiki/Rpc---netty-tcp-%E5%AE%9E%E7%8E%B0)
* [NQuery - lambda parallel stream](https://github.com/RockyLOMO/rxlib/wiki/NQuery---lambda-parallel-stream)
* [SUID - Base64缩短的不丢精度的UUID](https://github.com/RockyLOMO/rxlib/wiki/ShortUUID---%E5%9F%BA%E4%BA%8EBase64%E7%BC%A9%E7%9F%AD)

### shadowsocks (Only tested AES encryption, BELOW VERSION 2.13.13)
    * A pure client for [shadowsocks](https://github.com/shadowsocks/shadowsocks).
    * Requirements
        Bouncy Castle v1.5.9 [Release](https://www.bouncycastle.org/)
    * Using Non-blocking server
        Config config = new Config("SS_SERVER_IP", "SS_SERVER_PORT", "LOCAL_IP", "LOCAL_PORT", "CIPHER_NAME", "PASSWORD");
        NioLocalServer server = new NioLocalServer(config);
        new Thread(server).start();

### Maven

```xml
<dependency>
    <groupId>com.github.rockylomo</groupId>
    <artifactId>rxlib</artifactId>
    <version>2.13.13</version>
</dependency>
```
