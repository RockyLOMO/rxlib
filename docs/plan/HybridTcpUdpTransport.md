# TCP/UDP 混合传输计划

## 本次模式

**高性能模式（Netty 底层网络编程）**

## 1. 背景与目标

当前仓库已经具备三块基础能力：

- [`TcpClient`](../../rxlib/src/main/java/org/rx/net/transport/TcpClient.java) / [`TcpServer`](../../rxlib/src/main/java/org/rx/net/transport/TcpServer.java)：稳定可靠的 TCP 对象包传输。
- [`UdpClient`](../../rxlib/src/main/java/org/rx/net/transport/UdpClient.java)：可靠 UDP 传输，已支持 ACK、重传、分片、重组、request/reply。
- [`UdpHolePunchClient`](../../rxlib/src/main/java/org/rx/net/punch/UdpHolePunchClient.java)：基于协调端的 UDP 打洞直连。

目标是在不破坏现有 TCP/UDP 基础类语义的前提下，新增一层混合传输能力：

1. 默认使用 TCP client -> TCP server 通信，保证可靠可达。
2. 如果 UDP client 到对端可互通，小数据包通过 UDP 分片发送，大数据包继续走 TCP。
3. 如果 UDP client 不能直连，尝试 UDP 打洞。
4. 打洞失败继续走 TCP，不影响业务通信。
5. 打洞成功后，小数据包走 UDP，大数据包走 TCP。
6. 整体保持 Java 8、Netty EventLoop 友好、低分配、低延迟和可观测。

## 2. 设计结论

建议新增混合传输层，不直接改动 `TcpClient`、`TcpServer`、`UdpClient` 的现有对外语义。

核心原则：

- TCP 永远作为控制通道和兜底数据通道。
- UDP 只作为可用时的小包快路径。
- UDP 直连探测优先于打洞，因为直连探测更轻。
- 打洞作为第二阶段候选路径，失败不影响 TCP。
- 发送路由由混合层统一决策，业务侧只感知一个 `HybridSession`。
- 接收侧统一做 session token 校验和 sequence 去重，避免 UDP/TCP 双通道补发导致重复投递。

推荐新增包：

```text
org.rx.net.transport.hybrid
```

推荐新增核心类：

- `HybridClient`
- `HybridServer`
- `HybridSession`
- `HybridConfig`
- `HybridRouteState`
- `HybridPacket`
- `HybridControlPacket`
- `HybridUdpProbe`
- `HybridUdpData`
- `HybridTcpData`

## 3. 非目标

本期不建议做以下事情：

- 不把 `TcpClient` 改造成同时管理 UDP 的重型对象，避免影响 RPC、Socks、Remoting 等现有调用方。
- 不改变 `UdpClient` 当前 wire header，继续复用其可靠 UDP、分片和 ACK 语义。
- 不在 I/O 线程里执行阻塞打洞等待。
- 不强制所有业务包都支持 UDP，默认按大小和状态路由。
- 不承诺 TCP/UDP 混发天然有序，严格顺序业务默认走 TCP 或显式开启重排。

## 4. 对外 API 草案

### 4.1 HybridConfig

```java
public final class HybridConfig implements Serializable {
    private TcpClientConfig tcpClientConfig;
    private TcpServerConfig tcpServerConfig;
    private UdpClientConfig udpClientConfig;

    private int udpBindPort;
    private int udpSmallPacketThresholdBytes = 8 * 1024;
    private int udpProbeTimeoutMillis = 1500;
    private int udpProbeIntervalMillis = 120;
    private int udpProbeCount = 6;
    private int udpAckTimeoutMillis = 1200;
    private int maxUdpFailuresBeforeFallback = 3;

    private boolean enableUdpDirect = true;
    private boolean enableUdpHolePunch = true;
    private InetSocketAddress rendezvousEndpoint;
}
```

说明：

- `udpSmallPacketThresholdBytes` 建议按编码后的 payload 长度判断，不按对象估算大小判断。
- 默认阈值建议从 `8KB` 开始，压测后再调到 `16KB` 或 `32KB`。
- `UdpClient.maxFragmentPayloadBytes` 建议继续默认 `1024`，避免接近 MTU 触发 IP 层分片。
- `rendezvousEndpoint` 为空时只做 UDP 直连探测，不做打洞。

### 4.2 HybridSession

```java
public interface HybridSession extends AutoCloseable {
    boolean isConnected();

    HybridRouteState routeState();

    InetSocketAddress tcpRemoteEndpoint();

    InetSocketAddress udpRemoteEndpoint();

    void send(Object packet);

    void send(Object packet, HybridSendOptions options);

    Delegate<HybridSession, NEventArgs<Object>> onReceive();
}
```

推荐发送语义：

- `send(packet)` 默认自动路由。
- 小包且 UDP 已就绪：走 UDP。
- 大包、UDP 未就绪、UDP 不可写、UDP ACK 超时：走 TCP。
- 业务明确要求可靠有序时，通过 `HybridSendOptions` 强制 TCP。

### 4.3 HybridClient / HybridServer

客户端：

```java
HybridClient client = new HybridClient(config);
client.connect(serverEndpoint);
client.send(packet);
```

服务端：

```java
HybridServer server = new HybridServer(config);
server.start();
server.onReceive().combine((s, e) -> {
    Object packet = e.getValue();
});
```

服务端接收 TCP client 后创建 `HybridSession`，后续 TCP 和 UDP 数据都投递到同一个 session。

## 5. 控制面协议

TCP 控制面负责建立混合会话，不走 UDP。建议新增控制包：

```text
HybridHello
  sessionId
  peerId
  udpLocalHost
  udpLocalPort
  udpToken
  udpSmallPacketThresholdBytes
  udpProbeTimeoutMillis
  udpFragmentPayloadBytes
  udpMaxFragmentCount
  enableUdpDirect
  enableUdpHolePunch

HybridHelloAck
  sessionId
  peerId
  udpObservedHost
  udpObservedPort
  udpToken
  acceptedUdpSmallPacketThresholdBytes
  enableUdpDirect
  enableUdpHolePunch

HybridRouteUpdate
  sessionId
  routeState
  udpRemoteHost
  udpRemotePort
  reason
```

设计要点：

- `sessionId` 使用 long 或 String 均可，热点路径建议 long。
- `udpToken` 必须参与 UDP probe 和 UDP data 校验，不能只信任远端地址。
- 服务端看到 TCP 建连后先创建 session，TCP 控制面交换完成前数据默认走 TCP。
- UDP 的真实可达地址以收到的 `DatagramPacket.sender()` 为准，不能盲信对端上报的本地地址。

## 6. 数据面协议

### 6.1 TCP 数据包

业务包通过 TCP 发送时，建议包装：

```text
HybridTcpData
  sessionId
  seq
  flags
  packet
```

TCP 通道本身可靠有序，但仍建议保留 `seq`：

- 用于统一接收去重。
- 用于 TCP 补发 UDP 超时包时避免重复投递。
- 用于监控 TCP/UDP 路由比例和延迟。

### 6.2 UDP 数据包

业务包通过 UDP 发送时，建议包装：

```text
HybridUdpData
  sessionId
  seq
  token
  flags
  packet
```

`UdpClient` 外层继续负责：

- 编码。
- 分片。
- 重组。
- ACK。
- 重传。

混合层负责：

- session 校验。
- token 校验。
- seq 去重。
- 失败降级。
- 路由监控。

### 6.3 UDP 探测包

```text
HybridUdpProbe
  sessionId
  token
  peerId
  probeId
  timestampNanos

HybridUdpProbeAck
  sessionId
  token
  peerId
  probeId
  timestampNanos
```

探测成功条件：

- 收到对端合法 token 的 probe 或 probe ack。
- sender 地址被记录为当前 session 的 UDP remote endpoint。
- 状态切换到 `UDP_READY`。

## 7. 状态机

推荐状态：

```text
TCP_ONLY
  -> UDP_PROBING
  -> UDP_READY

UDP_PROBING
  -> UDP_PUNCHING
  -> UDP_READY

UDP_PROBING / UDP_PUNCHING
  -> TCP_ONLY

UDP_READY
  -> TCP_ONLY
  -> UDP_PROBING
```

状态含义：

- `TCP_ONLY`：只走 TCP，UDP 不参与业务数据。
- `UDP_PROBING`：正在尝试直接 UDP 互通，小包仍走 TCP。
- `UDP_PUNCHING`：正在通过 `UdpHolePunchClient` 建立直连，小包仍走 TCP。
- `UDP_READY`：UDP 可用，小包走 UDP，大包走 TCP。
- `UDP_FAILED` 可作为内部瞬时状态，不建议长期暴露，最终应回到 `TCP_ONLY`。

运行期降级条件：

- UDP ACK 连续超时达到阈值。
- `Sockets.writeUdp(...)` 返回 `PENDING_OVERLIMIT` 或 `CHANNEL_UNWRITABLE` 达到阈值。
- 收到非法 token、非法 sessionId 或异常 sender。
- UDP channel inactive。
- 打洞 session closed。

降级后处理：

- 立即把业务发送切回 TCP。
- 低频后台重新进入 `UDP_PROBING`。
- 不阻塞业务线程，不阻塞 EventLoop。

## 8. 发送路由策略

推荐发送流程：

```text
业务 send(packet)
  -> 判断是否强制 TCP
  -> 编码或估算 UDP payload 大小
  -> state == UDP_READY 且 encodedBytes <= threshold
      -> UDP send
      -> ACK 成功：完成
      -> ACK 失败：记录失败，必要时 TCP 补发
  -> TCP send
```

关键点：

- 最优判断应基于 `UdpClientCodec.encode(...)` 后的 `ByteBuf.readableBytes()`。
- 为避免二次编码，建议给 `UdpClient` 增加“预编码 payload 发送”或“返回 encodedBytes 的发送上下文”能力。
- 如果短期不改 `UdpClient`，第一版可以先按业务包类型或估算大小路由，但准确性较差。
- UDP 发送失败后是否 TCP 补发应由 `HybridSendOptions` 控制，默认建议补发并依赖 seq 去重。
- 对严格低延迟但允许丢包的业务，可允许 `AckSync.NONE` 且不补发。

推荐默认：

- 控制包：TCP。
- 大包：TCP。
- 小包普通业务：UDP `SEMI ACK`。
- 小包且业务要求 handler 成功后才确认：UDP `FULL ACK`。
- 有序强依赖业务：TCP。

## 9. 接收投递策略

接收侧统一入口为 `HybridSession.onReceive()`。

TCP 收到 `HybridTcpData`：

1. 校验 `sessionId`。
2. 读取 `seq`。
3. 去重。
4. 投递业务对象。

UDP 收到 `HybridUdpData`：

1. 校验 `sessionId`。
2. 校验 `token`。
3. 校验 sender 是否等于当前 UDP remote endpoint，或是否处于 endpoint 更新窗口。
4. 读取 `seq`。
5. 去重。
6. 投递业务对象。

去重结构：

- 可用环形窗口保存最近 N 个 seq。
- 第一版可用 `LongHashSet` + TTL，后续热路径再替换为低分配 ring bitmap。
- TTL 建议与 UDP alive timeout 对齐。

## 10. UdpClient 改造建议

为了做到“按编码后大小路由且避免二次序列化”，建议对 [`UdpClient`](../../rxlib/src/main/java/org/rx/net/transport/UdpClient.java) 做小范围增强：

### 10.1 新增发送上下文结果

```java
public final class UdpSendResult {
    private final ChannelFuture writeFuture;
    private final CompletableFuture<Void> ackFuture;
    private final int encodedBytes;
    private final int fragmentCount;
}
```

用途：

- 混合层记录 UDP 成功率、延迟、fragment 数。
- ACK 失败时触发 TCP 补发。
- 路由层可根据 encoded size 做后续调整。

### 10.2 支持预编码发送

可选新增内部方法：

```java
SendContext beginSendEncoded(InetSocketAddress remoteAddress,
                             Object packet,
                             ByteBuf encodedPayload,
                             int waitAckTimeoutMillis,
                             boolean fullSync,
                             int messageId)
```

引用计数约定：

- 入参 `encodedPayload` 所有权转移给 `UdpClient`。
- `UdpClient` 成功、失败、超时、close 都必须释放。
- 调用方不得在转移后继续释放。

### 10.3 避免 EventLoop 阻塞

现有 `sendAsync(...)` 内部会等待 ACK：

```java
context.ackFuture.get(...)
```

混合层不要在 EventLoop 调用这种阻塞 API。应使用返回 `CompletableFuture` 的非阻塞路径。

## 11. UdpHolePunchClient 改造建议

[`UdpHolePunchClient`](../../rxlib/src/main/java/org/rx/net/punch/UdpHolePunchClient.java) 当前 `connect(...)` 是阻塞式流程，内部存在等待和短 sleep。混合层应新增异步入口：

```java
public CompletableFuture<UdpHolePunchSession> connectAsync(InetSocketAddress rendezvousEndpoint,
                                                           String roomId,
                                                           String peerId,
                                                           int waitPeerTimeoutMillis,
                                                           int directTimeoutMillis)
```

实现要求：

- 不在 Netty EventLoop 上阻塞等待。
- 可复用 `TcpServer.SCHEDULER` 或独立轻量调度器执行阻塞兼容逻辑。
- 后续再把 `awaitPeer` 和 `establishDirect` 完全事件化。
- 关闭 `UdpHolePunchClient` 时，需要完成所有 pending future，并返回 `ClientDisconnectedException`。

## 12. 生命周期

### 12.1 建连

1. TCP 建连成功。
2. 创建 `HybridSession`。
3. 通过 TCP 发送 `HybridHello`。
4. 收到 `HybridHelloAck` 后启动 UDP direct probe。
5. direct probe 成功则进入 `UDP_READY`。
6. direct probe 失败且启用打洞，则进入 `UDP_PUNCHING`。
7. 打洞成功进入 `UDP_READY`，失败回到 `TCP_ONLY`。

### 12.2 断链

TCP 断开：

- session 标记 disconnected。
- 停止 UDP 业务投递。
- 清理 pending ACK、pending probe、pending punch。
- 如果启用 TCP reconnect，重连后重新执行 hello 和 UDP 探测。

UDP 降级：

- 不关闭 TCP。
- 不关闭整个 `UdpClient`，只把当前 session 标记为 `TCP_ONLY`。
- 清理该 session 的 UDP endpoint 和失败计数。
- 后台按退避策略重新探测。

### 12.3 关闭

`HybridSession.close()`：

- 关闭 TCP client 或 server side client。
- 移除 UDP receive handler 中的 session 映射。
- 关闭或 detach `UdpHolePunchSession`。
- 清理去重窗口、pending send、pending probe。

`HybridServer.close()`：

- 关闭所有 session。
- 关闭 TCP server。
- 关闭服务端共享 `UdpClient`。

## 13. 线程模型

要求：

- TCP I/O 仍在 TCP reactor。
- UDP I/O 仍在 UDP reactor。
- 控制面状态切换使用轻量 CAS/volatile，不在 I/O 线程做阻塞锁等待。
- 打洞阻塞兼容逻辑必须放到非 EventLoop 调度器。
- 业务事件投递沿用现有 `raiseEventAsync` 模式，避免长业务逻辑阻塞 I/O 线程。

建议：

- `HybridSession` 内的状态字段使用 `volatile HybridRouteState state`。
- pending map 使用 `ConcurrentHashMap`。
- 热点计数使用 `LongAdder` 或 `AtomicInteger`。
- seq 生成使用 `AtomicLong`，避免锁。

## 14. 背压与限流

TCP：

- 继续依赖 Netty `WriteBufferWaterMark`。
- 如果 `channel.isWritable() == false`，自动走现有 TCP 背压策略。

UDP：

- 继续统一通过 `Sockets.writeUdp(...)`。
- 遇到 `PENDING_OVERLIMIT`、`CHANNEL_UNWRITABLE` 时，不继续堆积 UDP 包。
- 小包路由失败时降级 TCP 或直接失败，由发送选项决定。
- 对同一 session 增加 UDP inflight 上限，避免 ACK 慢时占满 pendingSends。

建议默认：

- `maxUdpInflightMessagesPerSession = 1024`
- `maxUdpFailuresBeforeFallback = 3`
- `udpRetryBackoffMillis = 1000`

## 15. 内存与 ByteBuf 风险

必须遵守：

- `ByteBuf` 所有权只在明确边界转移。
- UDP probe/data 包经 `UdpClient` 编码后由 `UdpClient` 负责释放。
- 如果混合层提前编码再交给 `UdpClient`，转移后混合层不得释放。
- TCP 和 UDP 补发同一业务对象时，不能复用已释放的 `ByteBuf`。
- 如果业务对象本身包含 `ByteBuf`，必须定义 retain/release 规则，默认不建议让 Hybrid 层直接接受裸 `ByteBuf` 业务对象。

第一版建议：

- Hybrid 层只处理普通对象包。
- `ByteBuf` 原始流式传输单独设计，不混入对象包路由。

## 16. 安全与协议校验

必须校验：

- `sessionId` 是否存在。
- `token` 是否匹配。
- UDP sender 是否符合当前 endpoint 或探测更新窗口。
- 控制包是否来自当前 TCP session。
- `seq` 是否重复。
- 包类型是否允许。

拒绝策略：

- 非法 UDP 包直接丢弃，不回复 ACK 以外的业务响应。
- 非法控制包关闭当前 TCP session 或触发错误事件。
- 连续非法包计数超过阈值后降级到 TCP_ONLY，并记录指标。

## 17. 协议兼容性

本期新增的是混合层包类型，不改现有 TCP/UDP 基础协议。

兼容建议：

- `HybridHello` 带 `version`。
- 不支持混合传输的旧节点只使用原 TCP client/server。
- 双端均使用 Hybrid 时才启用 UDP 探测。
- `UdpClient` 的 codec 两端必须一致，默认可沿用当前 `FuryUdpClientCodec`。

## 18. 实施拆分

### 阶段 1：文档与 API 骨架

- 新增本计划文档。
- 新增 `HybridConfig`。
- 新增 `HybridRouteState`。
- 新增混合控制包和数据包。
- 新增 `HybridSession` 接口。

### 阶段 2：TCP_ONLY 可用闭环

- `HybridClient` 内部持有 `DefaultTcpClient`。
- `HybridServer` 内部持有 `TcpServer`。
- TCP 建连后建立 `HybridSession`。
- 所有数据先走 TCP。
- 单元测试覆盖基础收发和断链。

### 阶段 3：UDP 直连探测

- `HybridSession` 复用或创建 `UdpClient`。
- TCP 控制面交换 UDP 信息和 token。
- 实现 `HybridUdpProbe` / `HybridUdpProbeAck`。
- 探测成功切换 `UDP_READY`。
- 小包走 UDP，大包走 TCP。

### 阶段 4：UDP 失败降级

- 统计 UDP ACK timeout、write reject、非法 sender。
- 达到阈值后回到 `TCP_ONLY`。
- UDP 失败包按配置 TCP 补发。
- 增加 seq 去重，避免重复投递。

### 阶段 5：UDP 打洞集成

- 给 `UdpHolePunchClient` 增加异步 `connectAsync(...)`。
- direct probe 失败后通过 TCP 协调 `roomId` 和 `peerId`。
- 打洞成功后绑定 `UdpHolePunchSession` 的 direct endpoint。
- 打洞失败保持 `TCP_ONLY`。

### 阶段 6：性能与监控

- 增加混合传输指标。
- 增加压测和泄漏测试。
- 根据 p95/p99、UDP drop、堆外内存调整默认阈值。

## 19. 测试计划

### 单元测试

- `HybridRoutePolicyTest`
  - `TCP_ONLY` 下所有包走 TCP。
  - `UDP_READY` 下小包走 UDP。
  - `UDP_READY` 下大包走 TCP。
  - 强制 TCP 选项生效。
  - UDP 失败后按配置 TCP 补发。

- `HybridSequenceWindowTest`
  - TCP/UDP 重复 seq 只投递一次。
  - 过期 seq 被清理。
  - 无序 UDP 包按配置投递或等待。

- `HybridControlPacketTest`
  - hello/ack 字段校验。
  - version 不兼容拒绝。
  - token 不匹配拒绝。

### 集成测试

- `HybridTransportTcpOnlyIntegrationTest`
  - 只启动 TCP，不启用 UDP，收发成功。

- `HybridTransportUdpDirectIntegrationTest`
  - localhost UDP 直连成功。
  - 小包走 UDP。
  - 大包走 TCP。

- `HybridTransportUdpFallbackIntegrationTest`
  - 模拟 UDP 不可达或写拒绝。
  - 小包自动走 TCP。
  - 业务不中断。

- `HybridTransportUdpPunchIntegrationTest`
  - direct probe 失败后启动打洞。
  - 打洞成功后小包走 UDP。
  - 复用现有 `UdpHolePunchIntegrationTest` 的基础能力。

- `HybridTransportUdpPunchFailIntegrationTest`
  - rendezvous 不可用或打洞超时。
  - 继续 TCP_ONLY。

### 建议回归测试

```bash
mvn -pl rxlib -DskipTests compile
mvn -pl rxlib -DskipTests test-compile
mvn -pl rxlib "-Dtest=UdpTransportTest,UdpHolePunchIntegrationTest" test
```

混合层实现后补充：

```bash
mvn -pl rxlib "-Dtest=Hybrid*Test" test
```

## 20. 验证结论模板

实现完成后每次提交应填写：

```text
编译：
- mvn -pl rxlib -DskipTests compile：通过/失败

单元测试：
- mvn -pl rxlib "-Dtest=Hybrid*Test" test：通过/失败

集成测试：
- UdpTransportTest：通过/失败
- UdpHolePunchIntegrationTest：通过/失败
- HybridTransportUdpDirectIntegrationTest：通过/失败
- HybridTransportUdpPunchIntegrationTest：通过/失败

风险复核：
- ByteBuf 泄漏：已验证/未验证
- UDP 背压：已验证/未验证
- TCP 断链重连：已验证/未验证
- 打洞失败降级：已验证/未验证
```

当前仅生成计划文档，未修改 Java 代码，未执行测试。

## 21. 核心监控指标建议

必须覆盖：

- 堆外内存占用：`PooledByteBufAllocator.DEFAULT.metric().usedDirectMemory()`
- TCP active channel 数。
- Hybrid session active 数。
- 当前路由状态分布：`TCP_ONLY`、`UDP_PROBING`、`UDP_PUNCHING`、`UDP_READY`。
- TCP/UDP 发送包数和字节数。
- TCP/UDP 接收包数和字节数。
- UDP probe 成功率和耗时。
- UDP 打洞成功率和耗时。
- UDP ACK timeout 次数。
- UDP resend 次数。
- UDP write drop 次数。
- UDP pending write bytes。
- UDP fallback to TCP 次数。
- TCP 补发成功次数。
- duplicate seq 丢弃次数。
- 非法 token/session 包丢弃次数。
- 端到端延迟 p50/p95/p99。

## 22. 风险评估与最优解

### 22.1 内存泄漏风险

风险：

- UDP 分片 `ByteBuf` retained 后异常分支未释放。
- UDP 失败后 TCP 补发时复用已释放对象。
- 打洞 session close 后 receive handler 未移除。

最优解：

- ByteBuf 所有权边界写入接口注释和测试。
- 所有 pending context 在 success、failure、timeout、close 分支释放。
- 开启 Netty leak detector 做专项测试。

### 22.2 背压风险

风险：

- UDP 无 TCP 传输层背压，瞬时小包可能冲高 pending write bytes。
- ACK 慢导致 pendingSends 堆积。

最优解：

- 继续使用 `Sockets.writeUdp(...)`。
- 每 session 增加 UDP inflight 上限。
- 过载立即降级 TCP 或失败，不在 UDP 侧排长队。

### 22.3 连接生命周期风险

风险：

- TCP reconnect 后 UDP endpoint/token 仍使用旧值。
- UDP endpoint 因 NAT 变化漂移。

最优解：

- 每次 TCP reconnect 都重新 hello 和 probe。
- UDP READY 运行期允许通过合法 probe ack 更新 endpoint。
- token 每次新 session 重新生成。

### 22.4 线程模型风险

风险：

- 打洞 `connect(...)` 阻塞 EventLoop。
- 业务事件过重拖慢 UDP/TCP I/O。

最优解：

- 打洞改异步，阻塞兼容逻辑放到非 EventLoop 调度器。
- I/O handler 只做校验、状态更新和投递，不做长计算。

### 22.5 协议顺序风险

风险：

- TCP/UDP 混发导致跨通道乱序。
- UDP 超时 TCP 补发后，原 UDP 又到达导致重复。

最优解：

- 默认只保证去重，不保证跨通道严格有序。
- 严格有序业务强制 TCP。
- 如后续需要有序 UDP，增加小窗口重排和超时释放。

### 22.6 安全风险

风险：

- 伪造 UDP 包猜测 sessionId。
- NAT 后多个 peer 地址复用导致串包。

最优解：

- UDP 包必须校验 token。
- sender endpoint 与 token 双校验。
- 非法包只记录计数，不进入业务事件。

## 23. 当前结论

最优实现路径是新增 `Hybrid` 混合传输层，把现有 TCP、UDP、UDP 打洞能力组合起来：

- TCP 负责可靠控制面和兜底数据面。
- UDP 负责小包低延迟快路径。
- UDP 打洞负责 direct probe 失败后的候选路径。
- 状态机负责无感切换和降级。
- seq/token/sessionId 负责去重和安全边界。

这样改动范围清晰，对现有 `TcpClient`、`UdpClient`、`UdpHolePunchClient` 的侵入最小，后续也可以逐步把 `UdpClient` 发送结果和 `UdpHolePunchClient` 异步化补齐到更优性能形态。

## 24. 本轮实现记录（2026-04-26）

本轮按“先优先扩展现有类能力，避免重复代码”的要求推进，实际完成阶段 1、阶段 2，并补齐阶段 3 的 localhost UDP direct 基础闭环。

### 24.1 已扩展现有类

- `UdpClient`
  - 新增 `UdpSendResult`，返回 `writeFuture`、`ackFuture`、`encodedBytes`、`fragmentCount`。
  - 新增 `sendWithResult(...)`，混合层可拿到 ACK future 与编码后大小，避免再新建一套 UDP 发送状态机。
  - 新增 `encodedSize(Object packet)`，混合路由按实际 codec 编码后的 payload 大小判断 TCP/UDP。
  - 对外暴露的 `localEndpoint` 遇到 any-local 地址时转换为 loopback 可连接地址，避免 Windows 下把 `/0:0:0:0:0:0:0:0:port` 当远端发送导致 `Cannot assign requested address`。

- `UdpHolePunchClient`
  - 新增 `connectAsync(...)`。
  - 现阶段异步入口复用已有同步 `connect(...)`，由 `Tasks.runAsync(...)` 放到非 EventLoop 线程执行，避免混合层在 I/O 线程阻塞。

### 24.2 新增混合传输层

新增包：`org.rx.net.transport.hybrid`

核心类：

- `HybridClient`
- `HybridServer`
- `HybridSession`
- `DefaultHybridSession`
- `HybridConfig`
- `HybridRouteState`
- `HybridRoutePolicy`
- `HybridSendOptions`
- `HybridMetrics`
- `HybridSequenceWindow`
- `HybridTcpChannelCodec`
- 控制包：`HybridHello`、`HybridHelloAck`、`HybridRouteUpdate`
- 数据包：`HybridTcpData`、`HybridUdpData`
- 探测包：`HybridUdpProbe`、`HybridUdpProbeAck`

当前能力：

- TCP 建连后通过 `HybridHello` / `HybridHelloAck` 建立混合 session。
- 默认 TCP_ONLY 可完整收发。
- localhost UDP direct probe 成功后进入 `UDP_READY`。
- 小包在 `UDP_READY` 下走 UDP，大包或强制 TCP 选项走 TCP。
- UDP 数据校验 `sessionId`、`token`、sender endpoint。
- TCP/UDP 双通道统一 `seq` 去重。
- UDP ACK 失败、写失败、inflight 超限时降级 TCP，并按选项进行 TCP 补发。
- TCP 断链时 detach session，清理去重窗口和事件订阅。

### 24.3 新增测试

- `HybridRoutePolicyTest`
  - TCP_ONLY 下走 TCP。
  - UDP_READY 小包走 UDP。
  - UDP_READY 大包走 TCP。
  - 强制 TCP 选项生效。

- `HybridSequenceWindowTest`
  - 重复 seq 只接受一次。

- `HybridTransportTcpOnlyIntegrationTest`
  - 禁用 UDP direct 时，TCP_ONLY 收发成功。

- `HybridTransportUdpDirectIntegrationTest`
  - localhost UDP direct probe 成功。
  - 小包经 UDP 投递。

### 24.4 验证结论

编译：

- `mvn -pl rxlib -DskipTests compile`：通过。

单元测试 / 集成测试：

- `mvn -pl rxlib "-Dtest=Hybrid*Test" test`：通过，实际匹配并执行 16 个测试，包含已有 `HybridStreamTest`。
- `mvn -pl rxlib "-Dtest=UdpHolePunchIntegrationTest" test`：通过。
- `mvn -pl rxlib "-Dtest=UdpTransportTest" test`：仍有 1 个既有用例失败，`customCodecCanBeConfigured` 使用只支持字符串的自定义 codec 跑 `request/reply`，但 `reply()` 会发送 `UdpRpcResponse`，该 codec 无法反序列化响应对象，最终超时；该问题与本轮混合传输改动无直接关系。

### 24.5 风险复核

- ByteBuf 泄漏：本轮未新建裸 `ByteBuf` 数据面协议，UDP payload 仍由 `UdpClient` 统一编码、分片、释放；新增 `encodedSize(...)` 在 finally 中释放编码 buffer。
- UDP 背压：混合层增加每 session `maxUdpInflightMessagesPerSession` 限制，超限立即降级 TCP，不在 UDP 侧排长队。
- 连接生命周期：TCP 断链 detach session，清理去重窗口和事件订阅；后续 TCP reconnect 仍需补充重新 hello/probe 的完整回归。
- 打洞失败降级：已提供 `UdpHolePunchClient.connectAsync(...)` 扩展点，本轮尚未把打洞状态机接入 `HybridSession`。
- 协议兼容性：未修改现有 TCP/UDP wire header；混合层控制包和数据包均为新增对象包。

### 24.6 下一步建议

- 接入 UDP 打洞：direct probe 失败后进入 `UDP_PUNCHING`，通过 `connectAsync(...)` 建立候选直连 endpoint。
- 补充 UDP fallback 集成测试：模拟 UDP write reject / ACK timeout，验证 TCP 补发与 seq 去重。
- 增加 reconnect 集成测试：TCP reconnect 后重新 hello、重新生成 token、重新 probe。
- 将 `HybridMetrics` 接入现有诊断指标，至少覆盖堆外内存、session 状态分布、UDP ACK timeout、write drop、fallback、duplicate drop、illegal packet drop。
