# rxlib-java
A set of utilities for Java.

* Rpc - netty tcp 实现
```java

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

* NEvent
```java
@Test
public void runNEvent() {
    UserManagerImpl mgr = new UserManagerImpl();
    PersonInfo p = new PersonInfo();
    p.id = 1;
    p.name = "rx";
    p.age = 6;

    BiConsumer<UserManager, UserEventArgs> a = (s, e) -> System.out.println("a:" + e);
    BiConsumer<UserManager, UserEventArgs> b = (s, e) -> System.out.println("b:" + e);

    mgr.onAddUser = a;
    mgr.addUser(p);  //触发事件（a执行）

    mgr.onAddUser = combine(mgr.onAddUser, b);
    mgr.addUser(p); //触发事件（a, b执行）

    mgr.onAddUser = remove(mgr.onAddUser, b);
    mgr.addUser(p); //触发事件（b执行）
}
```

* NQuery - lambda parallel stream
```java
@Test
public void runNQuery() {
    Set<Person> personSet = new HashSet<>();
    for (int i = 0; i < 5; i++) {
        Person p = new Person();
        p.index = i;
        p.index2 = i % 2 == 0 ? 2 : i;
        p.index3 = i % 2 == 0 ? 3 : 4;
        p.name = Strings.randomValue(5);
        p.age = ThreadLocalRandom.current().nextInt(100);
        personSet.add(p);
    }
    Person px = new Person();
    px.index2 = 2;
    px.index3 = 41;
    personSet.add(px);

    showResult("groupBy(p -> p.index2...", NQuery.of(personSet).groupBy(p -> p.index2, p -> {
        System.out.println("groupKey: " + p.left);
        List<Person> list = p.right.toList();
        System.out.println("items: " + Contract.toJsonString(list));
        return list.get(0);
    }));
    showResult("groupByMany(p -> new Object[] { p.index2, p.index3 })",
            NQuery.of(personSet).groupByMany(p -> new Object[]{p.index2, p.index3}, p -> {
                System.out.println("groupKey: " + toJsonString(p.left));
                List<Person> list = p.right.toList();
                System.out.println("items: " + toJsonString(list));
                return list.get(0);
            }));

    showResult("orderBy(p->p.index)", NQuery.of(personSet).orderBy(p -> p.index));
    showResult("orderByDescending(p->p.index)", NQuery.of(personSet).orderByDescending(p -> p.index));
    showResult("orderByMany(p -> new Object[] { p.index2, p.index })",
            NQuery.of(personSet).orderByMany(p -> new Object[]{p.index2, p.index}));
    showResult("orderByDescendingMany(p -> new Object[] { p.index2, p.index })",
            NQuery.of(personSet).orderByDescendingMany(p -> new Object[]{p.index2, p.index}));

    showResult("select(p -> p.index).reverse()",
            NQuery.of(personSet).orderBy(p -> p.index).select(p -> p.index).reverse());

    showResult(".max(p -> p.index)", NQuery.of(personSet).<Integer>max(p -> p.index));
    showResult(".min(p -> p.index)", NQuery.of(personSet).<Integer>min(p -> p.index));

    showResult("take(0).average(p -> p.index)", NQuery.of(personSet).take(0).average(p -> p.index));
    showResult("average(p -> p.index)", NQuery.of(personSet).average(p -> p.index));
    showResult("take(0).sum(p -> p.index)", NQuery.of(personSet).take(0).sum(p -> p.index));
    showResult("sum(p -> p.index)", NQuery.of(personSet).sum(p -> p.index));

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
}
```

### shadowsocks (Only tested AES encryption)
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
    <version>2.13.8</version>
</dependency>
```
