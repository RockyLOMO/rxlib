# Hybrid Client/Server 支撑 Remoting 的能力补齐计划

## 本次模式

**高性能模式（Netty 底层网络编程）**

## 1. 目标

`HybridClient` / `HybridServer` 的长期目标是替代 `Remoting` 当前基于 `DefaultTcpClient` / `TcpServer` 的通信方式。本文只规划混合传输层自身需要补齐的能力，不处理 `Remoting` 对外接口兼容，也不保证旧 TCP 节点兼容。

目标结果：

- `HybridClient` 能作为 Remoting 客户端侧唯一连接对象。
- `HybridServer` 能作为 Remoting 服务端侧唯一监听对象。
- `HybridSession` 能承载 Remoting 的请求、响应、元数据、事件订阅和断链清理。
- UDP 只做小包快路径，TCP 仍是控制面、兜底数据面和严格有序选项。

## 2. 当前能力基线

已具备：

- `HybridClient.connect(...)` 基于 TCP 建连，并发送 `HybridHello`。
- `HybridServer` 基于 TCP 接收 `HybridHello` 后创建 `DefaultHybridSession`。
- `DefaultHybridSession.send(...)` 按编码后大小和路由状态选择 TCP/UDP。
- UDP 数据已有 `sessionId`、`token`、sender endpoint 校验。
- TCP/UDP 双通道统一使用 `seq` 去重。
- UDP ACK 失败、写失败、inflight 超限后可降级 TCP 并补发。
- `HybridMetrics` 已暴露堆外内存、收发包、ACK timeout、write drop、fallback、重复包和非法 UDP 包计数。

当前不足：

- `HybridServer.onReceive` 只抛出业务对象，丢失来源 `HybridSession`。
- `HybridClient` 缺少 `connectAsync`、重连完成后重新 hello/probe 的完整生命周期。
- `HybridSession` 缺少连接级属性存储，不能替代 Remoting 当前 `TcpClient.attr(...)` 存储握手元数据。
- `HybridSession` 缺少可取消的 `onSend` 钩子，Remoting 当前可通过 TCP send 事件观察或取消出站包。
- `HybridServer` 缺少按 session 查询、广播、关闭单 session 的稳定 API。
- `DefaultHybridSession.startDirectProbe(...)` 当前使用 sleep 循环，应改成调度式探测，避免占用通用异步线程。
- UDP 打洞只有扩展点，尚未接入 `UDP_PUNCHING` 状态机。
- 指标缺少 session 状态分布、发送字节数、端到端延迟和 route 切换次数。

## 3. HybridClient 扩展计划

### 3.1 生命周期

新增能力：

```java
public Future<Void> connectAsync(InetSocketAddress serverEndpoint);

public Delegate<HybridClient, EventArgs> onReconnected();

public Delegate<HybridClient, NEventArgs<InetSocketAddress>> onReconnecting();

public Delegate<HybridClient, NEventArgs<HybridSession>> onSessionReady();
```

设计要求：

- `connect(...)` 保持阻塞语义，但只等待 TCP 建连和 `HybridHello` 入队，不等待 UDP ready。
- `onSessionReady` 在本地 session 创建并完成 hello 入队后触发，Remoting 可立即发送元数据；TCP 顺序保证服务端先处理 hello。
- TCP reconnect 成功后必须新建或重置 session token，重新发送 `HybridHello`，重新 direct probe。
- UDP endpoint、route state、sequence window 必须在 reconnect 时清空，避免旧 NAT 映射串到新连接。

### 3.2 客户端池友好 API

新增能力：

```java
public HybridSession session();

public boolean isSessionReady();

public void resetHandlers();
```

用途：

- Remoting pool borrow 后绑定 onReceive/onDisconnected。
- Remoting pool recycle 前清理事件订阅，避免复用连接时 handler 泄漏。
- 非池化 stateful 客户端关闭 facade 时直接关闭 `HybridClient`。

### 3.3 发送观测

新增出站事件：

```java
public final Delegate<HybridClient, NEventArgs<Object>> onSend = Delegate.create();
```

规则：

- `HybridClient.send(...)` 进入 `HybridSession.send(...)` 前触发。
- 事件 cancel 后不进入传输层。
- 不在事件里做耗时逻辑；Remoting 只用于轻量追踪、限流或测试断言。

## 4. HybridServer 扩展计划

### 4.1 保留来源 session 的接收事件

新增事件参数：

```java
public final class HybridServerEventArgs<T> extends NEventArgs<T> {
    private final HybridSession session;
}
```

调整服务端事件：

```java
public final Delegate<HybridServer, HybridServerEventArgs<Object>> onReceive = Delegate.create();
public final Delegate<HybridServer, HybridServerEventArgs<Object>> onSend = Delegate.create();
```

设计要求：

- `HybridServer.handleHello(...)` 里绑定 `session.onReceive()` 时必须包装为 `HybridServerEventArgs`。
- Remoting 服务端通过 `e.getSession().send(pack)` 回包。
- 事件订阅集合以 `HybridSession` 为 key，不再以 `TcpClient` 为 key。

### 4.2 Session 管理 API

新增能力：

```java
public Map<Long, HybridSession> sessions();

public HybridSession getSession(long sessionId);

public boolean closeSession(long sessionId);
```

设计要求：

- 对外返回只读视图。
- `closeSession` 必须同时移除 `sessionsById`、`sessionsByTcp`，并 detach session。
- 服务端关闭时先 detach 所有 session，再关闭 TCP server 和 UDP client。

### 4.3 控制包处理

当前 `HybridRouteUpdate` 在客户端被忽略，服务端未主动发送。建议补齐：

- direct probe 成功或失败后发送 `HybridRouteUpdate`。
- UDP fallback 后发送 `HybridRouteUpdate`。
- Remoting 可订阅 route 变化用于诊断，但不能依赖 route 变化保证业务有序。

## 5. HybridSession 扩展计划

### 5.1 连接属性

新增轻量 attr 能力：

```java
<T> T attr(AttributeKey<T> key);

<T> void attr(AttributeKey<T> key, T value);

boolean hasAttr(AttributeKey<?> key);
```

要求：

- 属性存储放在 `DefaultHybridSession` 内部，不依赖 TCP channel。
- attr map 使用 `ConcurrentHashMap<AttributeKey<?>, Object>`。
- `detach/close` 时清空 attr，避免 Remoting metadata 泄漏。

### 5.2 发送结果与失败回调

新增非阻塞发送结果：

```java
public HybridSendResult sendWithResult(Object packet, HybridSendOptions options);
```

建议字段：

- selectedRoute
- actualRoute
- sequence
- encodedBytes
- udpFragmentCount
- `CompletableFuture<Void> writeFuture`
- `CompletableFuture<Void> ackFuture`

用途：

- Remoting 后续可统计方法调用的 route、重试、fallback。
- 测试可精确断言小包走 UDP、大包走 TCP。

### 5.3 探测调度

改造 `startDirectProbe(...)`：

- 用 `Tasks.setTimeout(...)` 或固定调度链发送 probe。
- 不使用 `Thread.sleep(...)`。
- 每次 probe 前检查 session 是否 closed、route 是否已 ready。
- timeout 后进入 `UDP_PUNCHING` 或 `TCP_ONLY`。

### 5.4 UDP 打洞状态机

接入 `UdpHolePunchClient.connectAsync(...)`：

```text
UDP_PROBING timeout
  -> enableUdpHolePunch && rendezvousEndpoint != null
  -> UDP_PUNCHING
  -> punch success: UDP_READY
  -> punch fail: TCP_ONLY
```

要求：

- 打洞流程不得运行在 Netty EventLoop。
- pending punch future 在 session close / TCP disconnect 时取消。
- 打洞成功后必须用合法 probe/probe ack 再确认 token 和 sender endpoint。

## 6. Codec 与 ByteBuf 约束

现有 `HybridTcpChannelCodec` 复用 `UdpClientCodec` 编解码 TCP payload。Remoting 接入时建议先统一使用 `FuryUdpClientCodec` 或新增 `RemotingHybridCodecFactory` 生成 `UdpClientCodec`。

约束：

- Java 8，不引入 Java 9+ API。
- `ByteBuf` 所有权继续由 codec / `UdpClient` 管理。
- `encodedSize(...)` 必须在 finally 中释放临时 buffer。
- TCP/UDP 补发同一业务对象时不得复用已释放 `ByteBuf`。
- 热点路径避免反射、正则和临时字符串；日志只保留 debug 或抽样指标。

## 7. 背压与降级

必须保留：

- UDP inflight per session 上限。
- `Sockets.writeUdp(...)` 写拒绝立即计数并降级。
- ACK timeout 达阈值后回到 `TCP_ONLY`。

新增建议：

- `maxPendingHybridSendsPerSession`
- `maxRouteSwitchesPerMinute`
- TCP channel `isWritable == false` 时记录指标，必要时对 Remoting 方法调用快速失败或排队。
- UDP fallback TCP 补发时继续使用原 seq，依赖去重窗口避免重复投递。

## 8. 实施步骤

### 阶段 1：服务端事件补齐

- 新增 `HybridServerEventArgs`。
- `HybridServer.onReceive/onSend` 改为携带 session。
- 增加 `sessions()`、`getSession(...)`、`closeSession(...)`。
- 补单元测试验证 server receive 能拿到来源 session 并回包。

### 阶段 2：客户端生命周期补齐

- 新增 `connectAsync`。
- 接入 `onReconnecting/onReconnected` 并在 reconnect 后重新 hello/probe。
- 增加 `resetHandlers()`，支撑池化复用。
- 补 reconnect 集成测试。

### 阶段 3：Session attr 与发送结果

- `HybridSession` 增加 attr API。
- 新增 `HybridSendResult`。
- `DefaultHybridSession.send(...)` 内部改成构造结果对象，保留原 `void send(...)` 兼容内部调用。
- 补 attr 清理、send result、UDP fallback 的单测。

### 阶段 4：探测与打洞

- direct probe 改为非阻塞调度。
- direct probe 超时后接入 `UDP_PUNCHING`。
- 打洞成功后通过 probe ack 确认 endpoint。
- 补 `HybridTransportUdpPunchIntegrationTest` 和失败降级测试。

### 阶段 5：指标增强

- 增加 bytes、route 状态分布、route switch、session active、UDP probe/punch 延迟。
- 接入现有 `DiagnosticMetrics` 或保留独立 `HybridMetrics` 导出。

## 9. 测试计划

单元测试：

- `HybridServerEventArgsTest`
- `HybridClientLifecycleTest`
- `HybridSessionAttrTest`
- `HybridSendResultTest`
- `HybridRouteUpdateTest`

集成测试：

- `HybridTransportTcpOnlyIntegrationTest`
- `HybridTransportUdpDirectIntegrationTest`
- `HybridTransportUdpFallbackIntegrationTest`
- `HybridTransportReconnectIntegrationTest`
- `HybridTransportUdpPunchIntegrationTest`

建议命令：

```bash
mvn -pl rxlib -DskipTests compile
mvn -pl rxlib "-Dtest=Hybrid*Test" test
```

## 10. 风险复核

- 内存泄漏：重点检查 UDP 分片 payload、send result future、session close 清理。
- 背压：重点验证 UDP inflight、TCP channel unwritable、池化客户端借还。
- 连接生命周期：重点验证 TCP reconnect 后旧 token/endpoint 不再可用。
- 线程模型：probe/punch 不得阻塞 EventLoop；Remoting 方法反射调用仍在原业务调度路径内处理。
- 协议兼容性：本计划明确不兼容旧 Remoting TCP 节点，双端必须同时使用 Hybrid。

## 11. 核心监控指标建议

- 堆外内存占用：`PooledByteBufAllocator.DEFAULT.metric().usedDirectMemory()`
- active hybrid session 数。
- route 状态分布：`TCP_ONLY`、`UDP_PROBING`、`UDP_PUNCHING`、`UDP_READY`。
- TCP/UDP messages/s、bytes/s。
- RPC 方法 p50/p95/p99 延迟与 route 分布。
- UDP probe 成功率、耗时。
- UDP 打洞成功率、耗时。
- UDP ACK timeout、write drop、fallback to TCP。
- duplicate seq drops。
- illegal session/token/sender drops。
- TCP reconnect 次数、重连耗时。

## 12. 当前结论

`HybridClient` / `HybridServer` 已补齐 Remoting 迁移前需要的关键传输层能力：服务端收发事件可携带 `HybridSession`，客户端具备 async connect、session ready、reconnect 后重新 hello/probe，session 已支持 attr、可取消 onSend、send result、非阻塞 direct probe、UDP punching 状态接入和基础指标增强。

Remoting 后续可优先改为只依赖 `HybridSession` 进行收发与连接元数据存取，再逐步替换旧 `DefaultTcpClient` / `TcpServer` 路径。

## 13. 执行结果（2026-04-26）

已完成：

- 阶段 1：新增 `HybridServerEventArgs`，`HybridServer.onReceive/onSend` 携带来源 `HybridSession`；新增 `sessions()`、`getSession(...)`、`closeSession(...)`。
- 阶段 1 追加修复：服务端 hello 建 session 前到达的 TCP data 会按 `TcpClient` 暂存，建 session 后 drain，避免 `connect()` 后立即 `send()` 时因异步事件重排丢首包；暂存队列上限 1024，超限计入 `tcpPendingDrops`。
- 阶段 2：新增 `HybridClient.connectAsync(...)`、`onReconnecting`、`onReconnected`、`onSessionReady`、`isSessionReady()`、`resetHandlers()`；TCP reconnect 后会重建 session token、重新发送 `HybridHello`，并重新启动 probe。
- 阶段 3：`HybridSession` 新增 attr API、可取消 `onSend`、`HybridSendResult sendWithResult(...)`；原 `send(...)` 保持兼容。
- 阶段 4：`DefaultHybridSession.startDirectProbe(...)` 已改为 `Tasks.setTimeout(...)` 调度链，不再 sleep 占用异步线程；direct probe timeout 后接入 `UDP_PUNCHING`，并在 punch 成功后重新通过 probe/probe ack 确认 endpoint。
- 阶段 5：`HybridMetrics` 新增 TCP/UDP 发送字节数、TCP hello 前待投递丢弃数、route switch、active session、route 状态分布指标；保留堆外内存、连接/收发/ACK timeout/fallback/非法包等既有指标。

新增/调整测试：

- `HybridServerEventArgsTest`
- `HybridClientLifecycleTest`
- `HybridSessionAttrTest`
- `HybridSendResultTest`
- 既有 `HybridTransportTcpOnlyIntegrationTest`、`HybridTransportUdpDirectIntegrationTest`、`HybridRoutePolicyTest`、`HybridSequenceWindowTest` 回归通过。

验证命令与结论：

```bash
mvn -pl rxlib "-Dtest=Hybrid*Test" test
```

结果：BUILD SUCCESS，20 个测试通过。

风险复核：

- 内存泄漏：`ByteBuf` 编解码所有权仍由 `UdpClient`/codec 管理；`encodedSize(...)` 继续在 finally 释放临时 buffer；session close/detach 会清理 attr、事件、probe timer、pending punch future。
- 背压：保留 UDP inflight 上限和写拒绝计数，UDP 失败继续按配置 fallback TCP；新增 send result 可供 Remoting 后续按 route/ack/fallback 做调用级统计。
- 连接生命周期：TCP disconnect 会 detach session；TCP reconnect 后新建 session/token，清空旧 endpoint、sequence window 和 attr，重新 hello/probe。
- 线程模型：direct probe 改为 timer 调度；UDP punching 使用现有 `UdpHolePunchClient.connectAsync(...)`，不在 Netty EventLoop 中阻塞；HybridSession receive 投递去掉多余二次异步，底层 TCP/UDP 事件已经先离开 EventLoop。
- 协议兼容性：仍不承诺兼容旧 Remoting TCP 节点，双端需要同时使用 Hybrid。
- 未完全覆盖项：本次未搭建 rendezvous 协调端执行 `HybridTransportUdpPunchIntegrationTest`；已完成状态机接入和编译/Hybrid 回归验证，后续可在具备协调端测试环境时补集成压测。
