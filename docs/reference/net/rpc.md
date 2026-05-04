# RPC 模块 (org.rx.net.rpc)

rxlib 自研的高性能远程过程调用框架。支持 TCP 单一协议，也支持 TCP/UDP 混合传输（Hybrid），通过内置的序列化器（如 Fury）实现极致的序列化性能。

## 核心类介绍

- **`Remoting`**:
  RPC 模块的顶级外观类，提供了注册服务端实现和创建客户端代理的核心工厂方法。支持分布式事件广播与 **Event Compute Args**（客户端协同参数计算）功能。

- **`RpcServerConfig` / `RpcClientConfig`**:
  RPC 服务端与客户端的详细配置类。包含连接超时、池大小、通信加密、混合传输支持等相关设置。

- **`RpcClientPool`**:
  用于缓存客户端长连接。避免每次 RPC 调用都建立新连接。

- **`RpcHybridClientPool` / `RpcHybridClientPoolImpl`**:
  针对混合传输协议的特殊客户端连接池。它可以在 TCP 控制流的基础上，选择将适合的流量切入 UDP 加速通道。

- **`RemotingCodecFactory` / `FuryRemotingCodecFactory`**:
  序列化编解码器工厂。推荐使用基于 `Fury` 的编解码，实现低延迟与高吞吐的零拷贝序列化。

- **`RemotingContext`**:
  RPC 调用上下文，在一次 RPC 调用的全生命周期中存储 Trace ID 及扩展参数，实现链路追踪与隐式参数传递。

## 适用场景
- 内部系统间的低延迟、高频调用通信。
- 对传输性能有严苛要求的私有协议服务（相比 HTTP REST 或 gRPC 更轻量和迅速）。

## 客户端模式约束

- 普通 request/response 调用可使用 `RpcClientConfig.poolMode(...)`，适合高并发短调用。
- 事件订阅、事件广播、断线自动重订阅必须使用 `RpcClientConfig.statefulMode(...)`。池化客户端会在调用后回收到连接池并重置 handler，不具备稳定的长连接订阅语义。

## Hybrid 传输模式注意事项

在 rxlib 的 RPC 模块中，`Remoting` 默认采用 TCP/UDP 混合传输（Hybrid）协议。

### 默认行为
即使你只设置了 `TcpServerConfig` 的端口（例如 `new RpcServerConfig(new TcpServerConfig(port))`），内部调用的 `HybridServer` 依然会：
1. **默认开启 UDP 逻辑**：`HybridConfig` 中的 `enableUdpDirect`（直连）和 `enableUdpHolePunch`（打洞）默认均为 `true`。
2. **自动绑定 UDP 端口**：服务端会自动绑定一个随机的可用 UDP 端口用于数据传输。
3. **主动发起探测**：一旦有支持 Hybrid 的客户端连接进来，服务端会通过 `HybridHelloAck` 宣告自己的 UDP 能力，并主动向客户端发起 UDP 直连探测（Probe）。

### 如何关闭 UDP/打洞逻辑
如果你确定业务只需要纯 TCP 通信（例如为了减少端口占用或在严格的防火墙环境下），可以显式关闭 UDP 相关功能：

```java
RpcServerConfig rpcConf = new RpcServerConfig(new TcpServerConfig(port));
// 显式关闭 UDP 直连和打洞逻辑
rpcConf.getHybridConfig().setEnableUdpDirect(false);
rpcConf.getHybridConfig().setEnableUdpHolePunch(false);

Remoting.register(contractInstance, rpcConf);
```


## 分布式事件增强 (Event Compute Args)

在 RPC 事件模型中，服务端触发事件后通常直接广播参数。但在某些场景下，服务端只知道“事件发生了”，而具体的事件参数（`EventArgs`）可能依赖于客户端的本地状态或逻辑。

`computeArgs` 功能允许服务端在广播事件之前，先请求一个特定的客户端（通常是版本最新的）来“填充”或“计算”事件参数。

### 核心流程
1. **服务端触发事件**：业务代码调用 `server.raiseEvent(eventName, args)`。
2. **请求计算 (COMPUTE_ARGS)**：服务端根据配置选择一个客户端发送请求。此时服务端会同步等待客户端回传。
3. **客户端计算**：被选中的客户端执行本地事件逻辑，更新 `args` 中的属性并回传给服务端。
4. **最终广播 (BROADCAST)**：服务端收到回传后，将“计算完成”的参数广播给所有订阅该事件的客户端。

### 配置策略 (RpcServerConfig)
通过 `RpcServerConfig.setEventComputeVersion(short)` 进行控制：
- **`EVENT_DISABLE_COMPUTE (-1)`**：默认值。禁用计算功能，直接广播。
- **`EVENT_LATEST_COMPUTE (0)`**：**自动选择最新版本**。服务端会从当前在线的客户端中，选择版本号（`eventVersion`）最高的客户端执行计算。这是推荐的生产配置，确保计算逻辑始终是最新的。
- **指定版本号 (>0)**：强制要求特定版本的客户端执行计算。

### 开启方式
```java
// 方式1：注册时开启（默认使用 EVENT_LATEST_COMPUTE）
Remoting.register(contractInstance, port, true);

// 方式2：通过配置类显式开启
RpcServerConfig conf = new RpcServerConfig(new TcpServerConfig(port));
conf.setEventComputeVersion(RpcServerConfig.EVENT_LATEST_COMPUTE);
Remoting.register(contractInstance, conf);
```

> [!TIP]
> 该特性非常适合用于“服务端通知、客户端决策”的异步协同场景。服务端只需负责事件流转，而具体的决策数据由当前逻辑最完备（版本最高）的客户端提供。


### 基本 RPC 调用与事件广播

基于 netty 实现。

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
```

### 客户端重连与端口轮询

```java
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
