# Remoting 接入 HybridClient/HybridServer 迁移计划

## 本次模式

**高性能模式（Netty 底层网络编程）**

## 执行结果（2026-04-26）

本计划已完成核心迁移并完成回归验证，当前落地结果如下：

- `Remoting` 客户端/服务端已切换到 `HybridClient` / `HybridServer` / `HybridSession`。
- `RpcClientConfig` / `RpcServerConfig` 已引入 `HybridConfig`，并保留 `getTcpConfig()` 兼容访问器。
- 已新增 `RpcHybridClientPool`、`RpcHybridClientPoolImpl`、`NonHybridClientPool`、`RemotingHybridOptions`。
- `RemotingContext`、事件订阅集合、metadata 握手、服务端回包路径均已改为 `HybridSession` 视角。
- `HybridSession.attr(...)` 已补齐字符串便捷访问，并增加 session attr 到 channel attr 的兼容镜像与 close 清理。
- `HybridClient.resetHandlers()`、`HybridServer.close()/onTransportClosed()` 已补齐 handler/session 生命周期清理。
- `FuryRemotingCodecFactory` 已改为可安全深拷贝/序列化的 Hybrid codec 工厂实现，修复 pool deep-clone 失败问题。

当前保守策略：

- `MetadataMessage`、事件控制消息、方法调用、方法响应、事件广播默认全部走 `FORCE_TCP`。
- 也就是说，Remoting 已完成 Hybrid 身份迁移与重连闭环，但 **UDP method/event 快路径本轮没有作为完成项启用**，后续可在稳定基线之上逐步放开。

## 1. 目标与前提

目标：把 `Remoting` 当前基于 `DefaultTcpClient` / `TcpServer` 的通信方式替换为 `HybridClient` / `HybridServer`，让 RPC 方法调用、响应、事件订阅和事件广播统一跑在 `HybridSession` 上。

本计划按以下前提制定：

- 不处理旧 Remoting TCP wire 协议兼容。
- 不保留老客户端/老服务端互通。
- 可调整 Remoting 内部通信对象、池化接口和上下文类型。
- Java 版本严格 Java 8。
- 性能敏感路径优先低分配、低锁竞争和 EventLoop 线程友好。

## 2. 当前 Remoting 通信结构

客户端侧：

- `createFacade(...)` 使用动态代理拦截方法调用。
- 非事件调用封装为 `MethodMessage`，通过 `DefaultTcpClient.send(...)` 发送。
- 响应用 `clientBeans: Map<DefaultTcpClient, Map<Integer, ClientBean>>` 等待。
- stateful 模式使用单个 `DefaultTcpClient`，pool 模式通过 `RpcTcpClientPool` 借还 `DefaultTcpClient`。
- `init(...)` 在 `DefaultTcpClient.onReceive` 里处理 `EventMessage` 和 `MethodMessage` 响应。
- reconnect 后在 TCP EventLoop 上重发 pending method pack。

服务端侧：

- `register(...)` 创建 `TcpServer`。
- `TcpServer.onReceive` 通过 `TcpServerEventArgs.getClient()` 拿到发送方。
- `MetadataMessage` 当前存在 `TcpClient.attr(HANDSHAKE_META_KEY, p)`。
- 事件订阅集合是 `Set<TcpClient>`。
- 回包通过 `e.getClient().send(pack)`。

迁移核心：把所有 `DefaultTcpClient` / `TcpClient` key 替换成 `HybridClient` / `HybridSession`，把 `TcpServerEventArgs` 替换成 `HybridServerEventArgs`。

## 3. 目标结构

### 3.1 客户端

新增或替换内部池接口：

```java
interface RpcHybridClientPool {
    HybridClient borrowClient();

    HybridClient returnClient(HybridClient client);
}
```

客户端等待表：

```java
static final Map<HybridClient, Map<Integer, ClientBean>> clientBeans = new ConcurrentHashMap<>();
static final Map<HybridClient, AtomicInteger> clientRefCounts = new ConcurrentHashMap<>();
```

发送：

```text
MethodMessage/EventMessage/MetadataMessage
  -> HybridClient.session().send(pack, remotingOptions)
  -> 当前默认全量 FORCE_TCP
```

当前默认路由：

- `MetadataMessage`：强制 TCP。
- `EventMessage.SUBSCRIBE/UNSUBSCRIBE/COMPUTE_ARGS/PUBLISH/BROADCAST`：强制 TCP。
- `MethodMessage`：强制 TCP。
- `MethodMessage` 响应：强制 TCP。

### 3.2 服务端

服务端容器：

```java
static class ServerBean {
    final RpcServerConfig config;
    final HybridServer server;
    final Map<String, EventBean> eventBeans = new ConcurrentHashMap<>();
}
```

事件订阅：

```java
static class EventBean {
    final Set<HybridSession> subscribe = ConcurrentHashMap.newKeySet();
    final Map<Long, EventContext> contextMap = new ConcurrentHashMap<>();
}
```

服务端收包：

```text
HybridServer.onReceive(HybridServerEventArgs<Object> e)
  -> e.getSession()
  -> MetadataMessage 写入 session attr
  -> EventMessage 处理订阅/广播
  -> MethodMessage 反射调用
  -> e.getSession().send(pack, responseOptions)
```

## 4. Config 调整

当前 `RpcClientConfig` / `RpcServerConfig` 强依赖 TCP config。按“不保留旧兼容”前提，建议直接切换到 Hybrid config。

客户端：

```java
public class RpcClientConfig<T> {
    private final HybridConfig hybridConfig;
    private short eventVersion;
    private short minPoolSize;
    private short maxPoolSize;
    private TripleAction<T, HybridClient> initHandler;
    private RemotingHybridCodecFactory codecFactory;
}
```

服务端：

```java
public class RpcServerConfig {
    private final HybridConfig hybridConfig;
    private final List<Integer> eventBroadcastVersions;
    private short eventComputeVersion;
    private RemotingHybridCodecFactory codecFactory;
}
```

Codec：

```java
public interface RemotingHybridCodecFactory extends Serializable {
    UdpClientCodec newCodec();
}
```

说明：

- `HybridTcpChannelCodec` 复用 `UdpClientCodec`，所以 Remoting 迁移后 codecFactory 应生成 `UdpClientCodec`。
- 第一版可用 `FuryUdpClientCodec.createDefault()` 走 `org.rx.` 前缀 allowlist；后续再按 RPC 热路径补显式类型注册。
- `HybridConfig.tcpClientConfig/tcpServerConfig` 的 codec 由 `HybridClient/HybridServer.ensureTcpCodec(...)` 注入，不在 Remoting 手工写 TCP codec。

## 5. Remoting 客户端迁移步骤

### 阶段 1：引入 Hybrid pool

- 新增 `RpcHybridClientPool`。
- 新增 `NonHybridClientPool`。
- 新增 `RpcHybridClientPoolImpl`。
- pool 创建 `HybridClient`，深拷贝 `HybridConfig`，调用 `connect(serverEndpoint)`。
- pool recycle 前调用 `client.resetHandlers()`，但不关闭连接。

### 阶段 2：替换等待表 key

- `clientBeans` key 从 `DefaultTcpClient` 改为 `HybridClient`。
- `clientRefCounts` key 从 `DefaultTcpClient` 改为 `HybridClient`。
- `getClientBeans(...)`、`retainClient(...)`、`releaseClient(...)` 改为接收 `HybridClient`。
- 方法响应继续由 `HybridSession` 承载，但等待表不再随 session 切换迁移，直接挂在 client 上，避免 reconnect/pool 并发竞态。

### 阶段 3：改造 init

当前：

```java
private static void init(DefaultTcpClient client, Object proxyObject, FastThreadLocal<Boolean> isCompute)
```

目标：

```java
private static void init(HybridClient client, Object proxyObject, RpcClientConfig<?> config, FastThreadLocal<Boolean> isCompute)
```

处理逻辑：

- `client.onError` 仍用于吞掉传输层错误，避免直接关闭 facade。
- `client.onDisconnected` 清理 `clientBeans` 和 ref count。
- `client.onSessionReady` 发送 `MetadataMessage`、执行 `initHandler`、触发 pending 重发。
- `client.onReceive` 处理 `EventMessage` 与 `MethodMessage` 响应。

### 阶段 4：发送路径替换

方法调用发送：

```text
wait map put
session.send(methodMessage, methodOptions)
syncRoot.waitOne(timeout)
```

注意：

- wait map 仍必须先登记再发送，避免极快响应丢失。
- `TimeoutException` 后如果 `session.isConnected()` 为 false，抛 `ClientDisconnectedException`。
- UDP 失败自动 TCP 补发时 seq 去重在 Hybrid 层完成，Remoting 不额外重发同一个 pack。

### 阶段 5：reconnect pending 重发

当前通过 `DefaultTcpClient.onReconnected` 进入 channel EventLoop 重发 pending pack。迁移后：

- `HybridClient.onReconnected` 后重新 init 和发送 `MetadataMessage`。
- 待 `onSessionReady` 后，遍历 `HybridClient` 级别 pending beans 重发。
- pending pack 通过新 session 强制 TCP 重发，保证 hello/metadata 之后有序。
- 老 session detach 后只负责连接生命周期清理，请求等待表不再依赖旧 session key。

## 6. Remoting 服务端迁移步骤

### 阶段 1：注册入口替换

当前：

```java
ServerBean bean = new ServerBean(config, new TcpServer(config.getTcpConfig()));
```

目标：

```java
ServerBean bean = new ServerBean(config, new HybridServer(config.getHybridConfig()));
```

启动：

```java
bean.server.start();
```

关闭清理：

- `server.onClosed` 从 `serverBeans` 移除 contract。
- `server.onDisconnected` 清理所有事件订阅中的 session。

### 阶段 2：onReceive 改造

依赖 `HybridServerEventArgs<Object>`：

```java
HybridSession session = e.getSession();
Object value = e.getValue();
```

处理：

- `MetadataMessage`：`session.attr(HANDSHAKE_META_KEY, p)`。
- `EventMessage.SUBSCRIBE`：`eventBean.subscribe.add(session)`。
- `EventMessage.UNSUBSCRIBE`：`eventBean.subscribe.remove(session)`。
- `EventMessage.PUBLISH`：以 session 作为 computing/broadcast 排除对象。
- `MethodMessage`：反射调用后 `session.send(pack, responseOptions)`。

### 阶段 3：事件计算与广播

替换类型：

- `EventContext.computingClient` -> `HybridSession computingSession`
- `selectComputingClient(...)` -> `selectComputingSession(...)`
- `collectBroadcastTargets(...)` 返回 `List<HybridSession>`
- `doSendBroadcast(...)` 遍历 session 发送

广播前校验：

- `session.isConnected()`。
- `session.attr(HANDSHAKE_META_KEY) != null`。
- event version 在允许列表内。

### 阶段 4：异常处理

当前服务端 `onError` 会向 TCP client 发送 `ErrorPacket`。迁移后：

- 如果 `HybridServerEventArgs` 能提供 session，则发送 `ErrorPacket` 强制 TCP。
- 没有 session 的传输层错误只记录指标并触发 `onError`。
- 非法 UDP 包只由 Hybrid 层计数丢弃，不进入 Remoting 异常包。

## 7. 路由策略

新增 Remoting 发送选项工具：

```java
final class RemotingHybridOptions {
    static final HybridSendOptions CONTROL = HybridSendOptions.FORCE_TCP;
    static final HybridSendOptions METHOD = HybridSendOptions.FORCE_TCP;
    static final HybridSendOptions RESPONSE = HybridSendOptions.FORCE_TCP;
    static final HybridSendOptions EVENT = HybridSendOptions.FORCE_TCP;
}
```

推荐默认：

- 控制消息强制 TCP。
- 普通 method request/response 当前强制 TCP。
- 事件订阅控制强制 TCP。
- 事件广播当前强制 TCP。
- compute args 强制 TCP，避免跨通道乱序影响计算链。

后续扩展：

- 在 `MethodMessage` 增加 flags，允许调用方声明 `FORCE_TCP`、`ALLOW_UDP`、`NO_UDP_FALLBACK`。
- 大返回值自动 TCP 已由 Hybrid encoded size 决策完成。

## 8. 并发与锁模型

必须保持：

- wait map 先 put 再 send。
- `ClientBean.syncRoot.waitOne(...)` 不在 EventLoop 上调用。
- 服务端反射调用不在 UDP/TCP I/O handler 同步执行，继续走 `raiseEventAsync` / 现有事件调度。
- pool borrow 不在 synchronized 内执行网络连接。
- 事件订阅集合使用 `ConcurrentHashMap.newKeySet()`，断链时主动清理。

建议优化：

- 当前实现已调整为 `clientBeans` 按 `HybridClient` 存储，以消除 reconnect/pool 并发下的 session 迁移竞态。
- `EventContext.contextMap` timeout 清理沿用 `Tasks.setTimeout(...)`。
- pending 重发只在 `onSessionReady` 后执行，避免 hello/metadata 乱序。

## 9. ByteBuf 与内存风险

Remoting 自身仍只发送对象包，不直接持有 `ByteBuf`。迁移后风险主要在 Hybrid codec 和 UDP 分片：

- `FuryUdpClientCodec.encode(...)` 返回的 `ByteBuf` 必须由调用方释放。
- `HybridTcpChannelCodec.Encoder` 已在 finally 释放 payload。
- `UdpClient.beginSend(...)` 接管 payload 后必须在 success/failure/timeout/close 分支释放。
- Remoting 不缓存包含 `ByteBuf` 的业务参数；如业务需要裸 buffer，必须单独定义 retain/release 语义。

## 10. 背压与超时

客户端调用：

- RPC read timeout 继续使用 `HybridConfig.tcpClientConfig.connectTimeoutMillis` 或新增 `RpcClientConfig.requestTimeoutMillis`。
- UDP fallback 不应延长总 RPC timeout。
- UDP ACK timeout 只触发 route 降级和 TCP 补发，不代表 RPC 调用超时。

服务端：

- `session.send(...)` 如果 TCP 不可写或断链，移除订阅并计数。
- 广播时跳过断链 session。
- 对同一事件广播目标做快照，避免边遍历边发送时锁住订阅集合。

## 11. 实施拆分

### 阶段 1：Hybrid 能力前置

- 完成 `HybridServerEventArgs`。
- 完成 `HybridSession.attr(...)`。
- 完成 `HybridClient` reconnect/session ready/reset handlers。
- 完成 send result 与必要指标。

### 阶段 2：Remoting 编译级迁移

- `RpcClientConfig` / `RpcServerConfig` 切换 `HybridConfig`。
- `RpcTcpClientPool` 替换为 `RpcHybridClientPool`。
- `ServerBean.server` 替换为 `HybridServer`。
- `clientBeans/clientRefCounts` 的 key 替换为 `HybridClient`，`event subscribe` 的 key 替换为 `HybridSession`。
- 编译通过。

### 阶段 3：客户端调用闭环

- stateful client 方法调用成功。
- pool client 方法调用成功。
- 客户端收到 method response 能唤醒 `ClientBean.syncRoot`。
- pending wait map 在成功、异常、超时、断链时清理。

### 阶段 4：服务端事件闭环

- metadata 握手写入 session attr。
- subscribe/unsubscribe 生效。
- publish/broadcast 生效。
- compute args 生效。
- 断链 session 自动移出订阅集合。

### 阶段 5：UDP 路由与降级回归

- 当前已完成 TCP_ONLY 基线与 reconnect/pending 重发闭环。
- 小 MethodMessage 走 UDP、事件广播自动路由，暂未在本轮作为完成项放开。
- 控制消息强制 TCP。
- 后续再补 UDP ACK timeout 后 TCP 补发与业务单次投递回归。

## 12. 测试计划

单元测试：

- `RemotingHybridConfigTest`
- `RemotingHybridClientPoolTest`
- `RemotingHybridRouteOptionsTest`
- `RemotingHybridEventBeanTest`

集成测试：

- `RemotingHybridStatefulTest`
- `RemotingHybridPoolTest`
- `RemotingHybridEventTest`
- `RemotingHybridReconnectTest`
- `RemotingHybridUdpFallbackTest`
- `RemotingHybridUdpDirectTest`

建议回归：

```bash
mvn -pl rxlib -DskipTests compile
mvn -pl rxlib -DskipTests test-compile
mvn -pl rxlib "-Dtest=Hybrid*Test,RemotingHybrid*Test" test
mvn -pl rxlib "-Dtest=RemotingTest,RrpIntegrationTest" test
```

本次实际执行：

```bash
mvn -pl rxlib -DskipTests test-compile
mvn -pl rxlib "-Dtest=RemotingTest" test
mvn -pl rxlib "-Dtest=Hybrid*Test,Remoting*Test,FuryRemotingCodecTest,RrpIntegrationTest" test
```

执行结论：

- `test-compile` 通过。
- `RemotingTest` 7/7 通过。
- 组合回归通过，Maven 结果为 `BUILD SUCCESS`，共执行 50 个相关测试用例，失败 0、错误 0。

说明：

- 因本计划明确不做旧协议兼容，旧 `RemotingTest` 需要同步改造成 Hybrid 预期。
- 如果 `RrpIntegrationTest` 仍依赖 Remoting，则必须纳入迁移回归。

## 13. 验收标准

- 编译通过，无 Java 9+ API。
- stateful、pool 两种客户端模式可正常 RPC 调用。
- 事件订阅、取消订阅、事件广播、compute args 全部通过。
- TCP_ONLY 下 Remoting 功能完整。
- UDP_READY 下小包路由到 UDP，大包路由到 TCP。
- UDP 不可达时自动降级 TCP，方法调用不丢、不重复。
- TCP reconnect 后 metadata 重发、pending 请求重发或失败语义明确。
- close facade、pool recycle、server close 后无 handler 泄漏和 pending wait 泄漏。

## 14. 核心监控指标建议

- 堆外内存占用：`PooledByteBufAllocator.DEFAULT.metric().usedDirectMemory()`
- Remoting active hybrid session 数。
- Hybrid route 状态分布。
- RPC request/response messages/s、bytes/s。
- RPC p50/p95/p99 延迟、超时数。
- MethodMessage route 分布：TCP、UDP、UDP fallback TCP。
- EventMessage route 分布。
- pending ClientBean 数。
- client pool borrow/recycle/timeout/leak 数。
- TCP reconnect 次数和 pending 重发数。
- UDP ACK timeout、write drop、duplicate drops、illegal UDP drops。

## 15. 风险与最优解

### 15.1 来源 session 缺失

风险：服务端无法回包或按连接维护事件订阅。

最优解：先补 `HybridServerEventArgs`，Remoting 迁移不得绕过该步骤。

### 15.2 控制消息乱序

风险：metadata、subscribe、compute args 经 UDP 导致跨通道乱序。

最优解：控制类消息强制 TCP，pending method 重发也强制 TCP。

### 15.3 reconnect 旧 session 污染

风险：旧 token、旧 endpoint、旧 wait map 在新连接上误用。

最优解：reconnect 后创建新 session，pending 明确迁移；旧 session detach 并清空 attr/wait map。

### 15.4 UDP 补发重复投递

风险：UDP timeout 后 TCP 补发，原 UDP 随后到达。

最优解：Hybrid 层保留原 seq，接收侧 sequence window 去重。

### 15.5 池化 handler 泄漏

风险：pool recycle 后旧 facade handler 仍在连接上，导致重复唤醒或广播。

最优解：`HybridClient.resetHandlers()` 清理 onReceive/onError/onDisconnected/onSessionReady，再由下一次 borrow 重新 init。

## 16. 当前结论

Remoting Hybrid 迁移已完成第一阶段目标：客户端/服务端身份、池化、metadata、事件订阅、方法调用、重连与 pending 重发均已切到 Hybrid 实现，并通过编译、`RemotingTest` 与组合回归验证。

本轮最终策略是先建立稳定的 TCP 基线：

- Remoting 业务消息默认仍强制 TCP。
- Hybrid 的 UDP 直连/探测/降级能力已在传输层可用，但 Remoting 侧尚未把 method/event 正式切到 UDP 快路径。

后续若继续推进第二阶段，可在现有实现上按消息类型逐步放开 UDP 路由，并补齐“UDP ACK timeout 后补发且业务只投递一次”的专项回归。
