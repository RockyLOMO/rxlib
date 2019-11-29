# rxlib-java
A set of utilities for Java.

* Rpc - netty tcp 实现
```java
@Test
public void apiRpc() {
    UserManagerImpl server = new UserManagerImpl();
    restartServer(server, 3307);

    String ep = "127.0.0.1:3307";
    String groupA = "a", groupB = "b";
    List<UserManager> facadeGroupA = new ArrayList<>();
    facadeGroupA.add(RemotingFactor.create(UserManager.class, Sockets.parseEndpoint(ep), groupA, null));
    facadeGroupA.add(RemotingFactor.create(UserManager.class, Sockets.parseEndpoint(ep), groupA, null));

    for (UserManager facade : facadeGroupA) {
        assert facade.computeInt(1, 1) == 2;
    }
    //重启server，客户端自动重连
    restartServer(server, 3307);
    for (UserManager facade : facadeGroupA) {
        facade.testError();
        assert facade.computeInt(2, 2) == 4;  //服务端计算并返回
    }

    List<UserManager> facadeGroupB = new ArrayList<>();
    facadeGroupB.add(RemotingFactor.create(UserManager.class, Sockets.parseEndpoint(ep), groupB, null));
    facadeGroupB.add(RemotingFactor.create(UserManager.class, Sockets.parseEndpoint(ep), groupB, null));

    for (UserManager facade : facadeGroupB) {
        assert facade.computeInt(1, 1) == 2;
        facade.testError();
        assert facade.computeInt(2, 2) == 4;
    }

    //自定义事件（广播）
    String groupAEvent = "onAuth-A", groupBEvent = "onAuth-B";
    for (int i = 0; i < facadeGroupA.size(); i++) {
        int x = i;
        facadeGroupA.get(i).<UserEventArgs>attachEvent(groupAEvent, (s, e) -> {
            System.out.println(String.format("!!groupA - facade%s - %s[flag=%s]!!", x, groupAEvent, e.getFlag()));
            e.setFlag(e.getFlag() + 1);
        });
    }
    for (int i = 0; i < facadeGroupB.size(); i++) {
        int x = i;
        facadeGroupB.get(i).<UserEventArgs>attachEvent(groupBEvent, (s, e) -> {
            System.out.println(String.format("!!groupB - facade%s - %s[flag=%s]!!", x, groupBEvent, e.getFlag()));
            e.setFlag(e.getFlag() + 1);
        });
    }

    UserEventArgs args = new UserEventArgs(PersonInfo.def);
    facadeGroupA.get(0).raiseEvent(groupAEvent, args);  //客户端触发事件，不广播
    assert args.getFlag() == 1;
    facadeGroupA.get(1).raiseEvent(groupAEvent, args);
    assert args.getFlag() == 2;

    server.raiseEvent(groupAEvent, args);
    assert args.getFlag() == 3;  //服务端触发事件，先执行最后一次注册事件，拿到最后一次注册客户端的EventArgs值，再广播其它组内客户端。

    facadeGroupB.get(0).raiseEvent(groupBEvent, args);
    assert args.getFlag() == 4;

    sleep(1000);
    args.setFlag(8);
    server.raiseEvent(groupBEvent, args);
    assert args.getFlag() == 9;

    facadeGroupB.get(0).close();  //facade接口继承AutoCloseable调用后可主动关闭连接
    sleep(1000);
    args.setFlag(16);
    server.raiseEvent(groupBEvent, args);
    assert args.getFlag() == 17;
}

@SneakyThrows
private void epGroupReconnect() {
    UserManagerImpl server = new UserManagerImpl();
    restartServer(server, 3307);
    String ep = "127.0.0.1:3307";
    String groupA = "a", groupB = "b";

    UserManager userManager = RemotingFactor.create(UserManager.class, Sockets.parseEndpoint(ep), groupA, null, p -> {
        InetSocketAddress result;
        if (p.equals(Sockets.parseEndpoint(ep))) {
            result = Sockets.parseEndpoint("127.0.0.1:3308");
        } else {
            result = Sockets.parseEndpoint(ep);  //3307和3308端口轮询重试连接，模拟分布式不同端口重试连接
        }
        log.debug("reconnect {}", result);
        return result;
    });
    assert userManager.computeInt(1, 1) == 2;
    sleep(1000);
    tcpServer.close();  //关闭3307
    Tasks.scheduleOnce(() -> tcpServer2 = RemotingFactor.listen(server, 3308), 32000);  //32秒后开启3308端口实例，重连3308成功
    System.in.read();
}
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
