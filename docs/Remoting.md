基于netty实现。

```java
@Test
public void apiRpc() {
    UserManagerImpl server = new UserManagerImpl();
    restartServer(server, 3307);

    String ep = "127.0.0.1:3307";
    String groupA = "a", groupB = "b";
    List<UserManager> facadeGroupA = new ArrayList<>();
    facadeGroupA.add(RemotingFactory.create(UserManager.class, Sockets.parseEndpoint(ep), groupA, null));
    facadeGroupA.add(RemotingFactory.create(UserManager.class, Sockets.parseEndpoint(ep), groupA, null));

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
    facadeGroupB.add(RemotingFactory.create(UserManager.class, Sockets.parseEndpoint(ep), groupB, null));
    facadeGroupB.add(RemotingFactory.create(UserManager.class, Sockets.parseEndpoint(ep), groupB, null));

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

    UserManager userManager = RemotingFactory.create(UserManager.class, Sockets.parseEndpoint(ep), groupA, null, p -> {
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
    Tasks.scheduleOnce(() -> tcpServer2 = RemotingFactory.listen(server, 3308), 32000);  //32秒后开启3308端口实例，重连3308成功
    System.in.read();
}
```