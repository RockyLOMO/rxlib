# RPC 模块 (org.rx.net.rpc)

rxlib 自研的高性能远程过程调用框架。构建在共享传输内核 `org.rx.net.transport.core` 之上，通过内置 `Fury` 序列化实现低延迟、高吞吐的远程调用与分布式事件路由。

## 核心类介绍

- **`Remoting`**:
  RPC 模块的顶级外观类，提供服务端注册（`register(...)` 返回 `TransportServer`）和客户端代理创建（`createFacade(...)`）工厂方法。支持分布式事件广播、定向推送与 **Event Compute Args**（客户端协同参数计算）。

- **`RpcServerConfig` / `RpcClientConfig`**:
  RPC 服务端与客户端配置类。内嵌共享传输配置 `TransportConfig`（控制纯 TCP 或单端口 `daily` 自适应 UDP 加速、MTU、认证密钥与握手超时）、连接超时、池大小、敏感方法与事件路由参数。

- **`RpcTransportClientPool` / `RpcTransportClientPoolImpl`**:
  基于 `TransportClient` 的客户端连接池，缓存长连接以避免每次 RPC 调用都重建连接。

- **`RemotingCodec` / `RemotingCodecFactory` / `FuryRemotingCodecFactory`**:
  RPC 应用层编解码接口与工厂。默认基于 `Fury` 将 `MethodMessage` / `EventMessage` / `MetadataMessage` 编码为 `ByteBuf` 交由 Transport Core 传输；Core 本身只处理原始字节帧，不感知序列化或 RPC 协议语义。

- **`RemotingContext`**:
  RPC 调用上下文，在单次调用生命周期中持有 `TransportSession`、Trace ID 及扩展参数，支持链路追踪与客户端句柄获取。

## 适用场景
- 内部系统间的低延迟、高频调用通信。
- 对传输性能与弱网恢复有严苛要求的私有协议服务（相比 HTTP REST 或 gRPC 更轻量）。

## 客户端模式约束

- 普通 request/response 调用可使用 `RpcClientConfig.poolMode(...)`，适合高并发短调用。
- 事件订阅、事件广播、断线自动重订阅必须使用 `RpcClientConfig.statefulMode(...)`。池化客户端会在调用后回收到连接池并重置 handler，不具备稳定的长连接订阅语义。

## 事件路由模式

`Remoting` 的事件发布保持统一签名：

```java
publishEvent(eventName, args)
```

事件类型不放在方法签名里，而是由 `args` 决定。推荐使用 `RemotingEventArgs<T>` 显式指定路由模式。

### 1. 普通事件（BROADCAST）

默认模式。服务端收到事件后，推送给该事件的所有订阅者。

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

服务端先选择一个计算订阅者处理 `args`，等客户端补全参数后，再将最终结果广播出去。

```java
facade.publishEvent("onCallback", RemotingEventArgs.compute("work"));
```

典型流程：
1. 服务端收到 `COMPUTE`
2. 选择一个合适的客户端做参数计算
3. 该客户端在本地事件回调里修改 `args`
4. 服务端拿到修改后的结果后，再广播给其他订阅者

### 3. 定向事件（DIRECT）

只推送给发起这次 `publishEvent(...)` 的客户端，不广播给其他订阅者。

```java
facade.publishEvent("onCallback", RemotingEventArgs.direct("direct-1"));
```

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

如果不显式设置 `dispatchMode`，默认就是 `BROADCAST`。

## Transport Core 传输模式注意事项

RPC 已统一迁移到共享 `org.rx.net.transport.core`（`TransportServer` / `TransportClient` / `TransportSession`），旧 `Hybrid*` 双栈与 `UdpClient` 整消息 ACK/重发已移除：

### 默认行为（`daily` 可靠字节流）
1. **单一可靠恢复层**：普通 RPC 方法、响应和事件统一编码为可靠有序字节流。`TransportConfig.enableAdaptiveUdp` 默认开启时，复用 RSS `daily` 策略（单端口 `AdaptiveTcpHandler` + `AdaptiveUdpPort`，不启用端口跳跃和包复制），由 Transport Core 单层负责 FEC 与可靠 TCP 修复，不再叠加第二套 ACK/重发或重复压缩。
2. **UDP 不可用即时回退**：认证 TCP 主通道握手完成立即可用；UDP 不可用时无缝走 TCP，不等待打洞也不阻塞业务。
3. **无独立 UDP 端口跳跃与打洞**：RPC 默认不开启打洞与端口跳跃。

### 如何切换为纯 TCP 模式（零 UDP Socket）
如果业务只需要纯 TCP 通信（例如在严格防火墙环境下、经由嵌套 SOCKS5 承载、或为了不占用任何 UDP 端口），可显式关闭 `enableAdaptiveUdp`：

```java
RpcServerConfig rpcConf = new RpcServerConfig(new TcpServerConfig(port));
rpcConf.getTransportConfig().setEnableAdaptiveUdp(false);
TransportServer server = Remoting.register(contractInstance, rpcConf);

RpcClientConfig<UserManager> clientConf = RpcClientConfig.statefulMode("127.0.0.1:" + port, 0);
clientConf.getTransportConfig().setEnableAdaptiveUdp(false);
```

关闭 `enableAdaptiveUdp` 后，服务端 `server.udpPort()` 为 `0`，双端均不会创建任何 UDP Socket。

## 分布式事件增强 (Event Compute Args)

`computeArgs` 允许服务端在广播事件之前，先请求特定版本（通常是最新版本）的客户端计算或填充 `EventArgs`。

### 配置策略 (RpcServerConfig)
通过 `RpcServerConfig.setEventComputeVersion(short)` 控制：
- **`EVENT_DISABLE_COMPUTE (-1)`**：默认值。禁用计算功能，直接广播。
- **`EVENT_LATEST_COMPUTE (0)`**：**自动选择最新版本**。服务端从当前在线客户端中选择 `eventVersion` 最高的执行计算。
- **指定版本号 (>0)**：强制要求特定版本的客户端执行计算。

### 开启方式
```java
// 方式1：注册时开启（默认使用 EVENT_LATEST_COMPUTE）
TransportServer server = Remoting.register(contractInstance, port, true);

// 方式2：通过配置类显式开启
RpcServerConfig conf = new RpcServerConfig(new TcpServerConfig(port));
conf.setEventComputeVersion(RpcServerConfig.EVENT_LATEST_COMPUTE);
TransportServer server = Remoting.register(contractInstance, conf);
```

### 注册与调用

没有 `RemotingFactory`。服务端通过 `Remoting.register(...)` 创建并返回 `TransportServer`：

```java
TransportServer server = Remoting.register(contractInstance, 3307, false);
UserManager facade = Remoting.createFacade(UserManager.class,
        RpcClientConfig.statefulMode("127.0.0.1:3307", 0));
facade.publishEvent("onAuth", RemotingEventArgs.broadcast(args));
```

定向回推用 `Remoting.currentClientHandle()` 和 `Remoting.publishEventToClient(handle, name, args)`。对端已断开时返回 `false`。

`RpcServerConfig.executor` 非空时，业务方法丢到该池；默认在调度线程执行（`MetadataMessage` 在 EventLoop 同步生效以保证元数据严格保序）。只有 `executorForPing` 为真时，ping 也丢出去。`poolMode` 下多参数的 `attachEvent` 会抛 `InvalidException`。

没有 H2 Outbox，也没有重启后补推。
