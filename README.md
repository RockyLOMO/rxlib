[![Java CI](https://github.com/RockyLOMO/rxlib/actions/workflows/maven.yml/badge.svg)](https://github.com/RockyLOMO/rxlib/actions/workflows/maven.yml)

# ℞lib-java
A set of utilities for Java.

### Maven
```xml
<dependency>
    <groupId>com.github.rockylomo</groupId>
    <artifactId>rxlib</artifactId>
    <version>2.17.8</version>
</dependency>
```

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
    IntWaterMark config = new IntWaterMark(20, 40);
    //当最小线程数的线程量处理不过来的时候，会创建到最大线程数的线程量来执行。当最大线程量的线程执行不过来的时候，会把任务丢进列队，当列队满的时候会阻塞当前线程，降低生产者的生产速度。
    //LinkedTransferQueue基于CAS实现，性能比LinkedBlockingQueue要好。
    //拒绝策略 当thread和queue都满了后会block调用线程直到queue加入成功，平衡生产和消费
    //FastThreadLocal 支持netty FastThreadLocal
    ExecutorService pool = new ThreadPool(1, 1, 1, 8, config, "");
    for (int i = 0; i < 100; i++) {
    	int n = i;
    	pool.execute(() -> {
    		log.info("exec {} begin..", n);
    		sleep(15 * 1000);
    		log.info("exec {} end..", n);
    	});
    }

    for (int i = 0; i < 6; i++) {
        int x = i;
        //RunFlag.SYNCHRONIZED  根据taskId同步执行，只要有一个线程在执行，其它线程等待执行。
        //RunFlag.SINGLE        根据taskId单线程执行，只要有一个线程在执行，其它线程直接跳过执行。
        //RunFlag.TRANSFER      直到任务被执行或放入队列否则一直阻塞调用线程。
        //RunFlag.PRIORITY      如果线程和队列都无可用的则直接新建线程执行。
        //RunFlag.INHERIT_THREAD_LOCALS 子线程会继承父线程的FastThreadLocal
        Tasks.run(() -> {
            log.info("Exec: " + x);
            sleep(2000);
        }, "myTaskId", RunFlag.TRANSFER)
                .whenCompleteAsync((r, e) -> log.info("Done: " + x));
    }

    //jdk默认的ScheduledExecutorService只会创建coreSize的线程，当执行的任务blocking wait多时，任务都堆积不能按时处理。
    //ScheduledThreadPool现改写maxSize会生效，再依据cpuLoad动态调整maxSize解决上面痛点问题。
    //WheelTimer虽然精度不准，但是只消耗1个线程以及消耗更少的内存。单线程的HashedWheelTimer也使blocking wait痛点放大，好在动态调整maxSize的ThreadPool存在，WheelTimer只做调度，执行全交给ThreadPool异步执行，完美解决痛点。


    System.out.println("main thread done");
    System.in.read();
}
```

* [Light Reliable UDP - 轻量级可靠性UDP](https://github.com/RockyLOMO/rxlib/wiki/Light-Reliable-UDP-%E8%BD%BB%E9%87%8F%E7%BA%A7%E5%8F%AF%E9%9D%A0%E6%80%A7UDP)
* [Rpc - netty tcp](https://github.com/RockyLOMO/rxlib/wiki/Rpc---netty-tcp-%E5%AE%9E%E7%8E%B0)
* [Restful - 轻量级 连接池 RestClient](https://github.com/RockyLOMO/rxlib/wiki/%E8%BD%BB%E9%87%8F%E7%BA%A7-%E8%BF%9E%E6%8E%A5%E6%B1%A0-RestClient-%E5%AE%9E%E7%8E%B0---%E5%9F%BA%E4%BA%8Eokhttp)
* [DnsServer & DnsClient - Contributed to netty](https://github.com/RockyLOMO/rxlib/wiki/DnsServer-&-DnsClient)
* [Socks5ProxyServer - 扩展socks5协议加入混淆](https://github.com/RockyLOMO/rxlib/wiki/Socks5ProxyServer)
* [ShadowsocksServer & ShadowsocksClient](https://github.com/RockyLOMO/rxlib/wiki/ShadowsocksServer-&-ShadowsocksClient) 
* [KeyValueStore - WAL + MMAP 键值存储](https://github.com/RockyLOMO/rxlib/wiki/KeyValueStore---%E9%94%AE%E5%80%BC%E5%AD%98%E5%82%A8)
* [HybridStream - heep/direct buffer & file buffer混用BIO]()
* [BeanMapper - 基于cglib bytecode实现](https://github.com/RockyLOMO/rxlib/wiki/BeanMapper---%E5%9F%BA%E4%BA%8Ecglib-bytecode%E5%AE%9E%E7%8E%B0)
* [NQuery - lambda parallel stream](https://github.com/RockyLOMO/rxlib/wiki/NQuery---lambda-parallel-stream)


#### Thanks
* https://github.com/hsupu/netty-socks
* https://github.com/TongxiJi/shadowsocks-java
