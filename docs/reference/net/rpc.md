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

## 事件路由模式

`Remoting` 的事件发布仍然保持统一签名：

```java
publishEvent(eventName, args)
```

事件类型不放在方法签名里，而是由 `args` 决定。推荐使用 `RemotingEventArgs<T>` 来显式指定路由模式。

### 1. 普通事件（BROADCAST）

默认模式。服务端收到事件后，会推送给该事件的所有订阅者。

```java
facade.publishEvent("onCallback", RemotingEventArgs.broadcast("hello"));
// 也可以直接传普通 EventArgs，默认就是广播
facade.publishEvent("onCallback", new UserEventArgs(PersonInfo.def));
```

适用场景：
- 通知类消息
- 状态变更广播
- 所有订阅者都应该看到的事件

### 2. 计算事件（COMPUTE）

服务端先选择一个计算订阅者来处理 `args`，等这个客户端把参数补全后，再把最终结果广播出去。

```java
facade.publishEvent("onCallback", RemotingEventArgs.compute("work"));
```

典型流程：
1. 服务端收到 `COMPUTE`
2. 选择一个合适的客户端做参数计算
3. 该客户端在本地事件回调里修改 `args`
4. 服务端拿到修改后的结果后，再广播给其他订阅者

适用场景：
- 事件参数依赖客户端本地状态
- 需要由某个版本的客户端补全数据
- 服务端只负责分发，不负责计算细节

### 3. 定向事件（DIRECT）

只推送给发起这次 `publishEvent(...)` 的那个客户端，不广播给其他订阅者。

```java
facade.publishEvent("onCallback", RemotingEventArgs.direct("direct-1"));
```

适用场景：
- 任务完成回执
- 只给发起者看的提示
- 请求方和其他订阅者需要完全隔离的事件

### 事件参数定义

```java
@Getter
@Setter
public class RemotingEventArgs<T> extends NEventArgs<T> {
    private EventDispatchMode dispatchMode = EventDispatchMode.BROADCAST;

    public static <T> RemotingEventArgs<T> broadcast(T value) { ... }
    public static <T> RemotingEventArgs<T> compute(T value) { ... }
    public static <T> RemotingEventArgs<T> direct(T value) { ... }
}
```

如果你不显式设置 `dispatchMode`，默认就是 `BROADCAST`。

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
1. **服务端触发事件**：业务代码调用 `EventPublisher.publishEvent(eventName, args)`。没有 `raiseEvent`。
2. **请求计算 (COMPUTE_ARGS)**：服务端根据配置选择一个客户端发送请求。此时服务端会同步等待客户端回传。
3. **客户端计算**：被选中的客户端执行本地事件逻辑，更新 `args` 中的属性并回传给服务端。
4. **最终广播 (BROADCAST)**：服务端收到回传后，将“计算完成”的参数广播给所有订阅该事件的客户端。

> 注意：这里的 `computeArgs` 是服务端驱动的“参数计算流程”，和上面 `publishEvent(..., RemotingEventArgs.compute(...))` 的“计算事件”是同一套路由能力，只是入口不同。前者是服务端触发，后者是客户端触发。

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


### 注册与调用

没有 `RemotingFactory`。服务端始终创建 `HybridServer`。`register` 返回其中的 TCP 服务器，`registerHybrid` 返回 `HybridServer`。

```java
Remoting.register(contractInstance, 3307, false);
UserManager facade = Remoting.createFacade(UserManager.class,
        RpcClientConfig.statefulMode("127.0.0.1:3307", 0));
facade.publishEvent("onAuth", RemotingEventArgs.broadcast(args));
```

定向回推用 `Remoting.currentClientHandle()` 和 `Remoting.publishEventToClient(handle, name, args)`。对端已断开时返回 false。这和客户端 `publishEvent` 上的 `RemotingEventArgs.direct` 不是同一条路径。

`RpcServerConfig.executor` 非空时，业务方法丢到该池；默认在调用线程执行。只有 `executorForPing` 为真时，ping 也丢出去。`poolMode` 下多参数的 `attachEvent` 会抛 `InvalidException`。

没有 H2 Outbox，也没有重启后补推。
