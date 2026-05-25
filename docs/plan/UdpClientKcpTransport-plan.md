# UdpClient 与 KcpClient 传输拆分计划

> 仓库：`rockylomo/rxlib`  
> 分支：`master`  
> 文件：`docs/plan/UdpClientKcpTransport-plan.md`  
> 更新日期：2026-05-25

## 0. 结论先行

本计划调整为：**不把当前 `UdpClient` 改造成 KCP，也不在 KCP 上强行同时支持有序和无序。**

最终建议拆成两个清晰组件：

```text
UdpClient
  = 继续保留现有 RXUP 简易可靠 UDP
  = 无序可靠
  = message-level ACK / resend / fragment / assembly
  = 适合控制面、低 QPS RPC、打洞、轻量可靠消息

KcpClient
  = 新增 KCP 客户端
  = 有序可靠
  = session / window / rtt / rto / update timer
  = 适合顺序敏感消息、连续状态同步、流式可靠 UDP
```

这样做的原因：

```text
1. 当前 UdpClient 本质就是“按 messageId 独立可靠”，天然是无序可靠。
2. KCP 单 conv 天然有序交付；为了在 KCP 上做无序，需要多 conv / 多 lane，反而增加资源和复杂度。
3. 如果无序可靠的目标是更省资源、更简单，那么继续使用当前 UdpClient 语义更合适。
4. KCP 的 ACK 是传输层 ACK，不等价于当前 FULL ACK 的“业务 handler 成功后 ACK”。
5. 两个类拆开后，API 语义清晰，不需要在一个 `UdpClientConfig` 里塞两套可靠协议。
```

核心命名：

```java
UdpClient   // 无序可靠 UDP，保留当前实现方向
KcpClient   // 有序可靠 UDP，新增实现
```

同时确认：**`UdpClient` 和 `KcpClient` 都可以兼容现有 UDP FEC、压缩、多倍发包 pipeline。**

这些 pipeline 应该位于二者下层：

```text
业务 / UdpClient / KcpClient
  -> 自有协议 header：RXUP 或 RXKC/KCP
  -> UDP 压缩 / FEC / 多倍发包 pipeline
  -> UdpFinalEgressGuardHandler
  -> socket
```

只要二者继续使用：

```java
Sockets.udpBootstrap(config, initChannel)
Sockets.writeUdp(channel, packet, ...)
```

就可以复用 rxlib 现有 UDP 出入口保护、MTU guard、pending bytes 背压、压缩、FEC、多倍发包能力。

---

## 1. 当前代码与项目约束

### 1.1 项目约束

项目级规范要求：

```text
1. Java 版本严格 Java 8。
2. 网络路径属于高性能 Netty 底层网络编程。
3. 热点路径避免频繁 new 对象。
4. 优先 PooledByteBufAllocator / Direct Buffer / 零拷贝。
5. ByteBuf 必须严格遵守引用计数。
6. I/O 线程不得执行阻塞逻辑。
7. 网络改动必须显式评估内存泄漏、背压、生命周期、线程模型、协议兼容和监控指标。
```

### 1.2 当前 UdpClient 职责

当前 `UdpClient` 位于：

```text
rxlib/src/main/java/org/rx/net/transport/UdpClient.java
```

当前职责：

```text
1. 绑定 DatagramChannel / 多 channel。
2. 自定义 RXUP wire 协议解析。
3. DATA / ACK 帧处理。
4. NONE / SEMI / FULL ACK 语义。
5. ByteBuf payload 分片、重组、重传。
6. request / requestAsync / reply / replyError RPC facade。
7. onReceive / onError 事件分发。
8. close 时清理 pendingSends / pendingReceives / pendingRequests。
```

当前 RXUP wire header：

```java
static final int MAGIC = 0x52585550; // RXUP
static final byte TYPE_DATA = 1;
static final byte TYPE_ACK = 2;
static final int ACK_HEADER_SIZE = 9;
static final int DATA_HEADER_SIZE = 18;
```

当前 `UdpClientConfig`：

```java
public class UdpClientConfig extends SocketConfig {
    private UdpClientCodec codec = FuryUdpClientCodec.createDefault();
    private int waitAckTimeoutMillis = 15 * 1000;
    private boolean fullSync;
    private int maxResend = 2;
    private int maxFragmentPayloadBytes = 1024;
    private int maxFragmentCount = 128;
}
```

### 1.3 当前 UdpClient 的实际语义

当前 `UdpClient` 不是连接流模型，而是 message-level 可靠模型：

```text
1. 每条逻辑消息有独立 messageId。
2. 每条消息独立等待 ACK、timeout、resend。
3. 接收端按 sender + messageId 做 fragment assembly。
4. 哪条消息先完整，哪条消息就可以先投递。
5. 不存在 per-peer 全局接收序号。
6. 不存在“必须先投递 messageId=N，才能投递 messageId=N+1”的约束。
```

因此它非常适合作为：

```text
无序可靠 UDP
```

也就是：

```text
reliable unordered datagram message transport
```

### 1.4 当前测试基线

当前 `UdpTransportTest` 已覆盖：

```text
1. FULL ACK 首次业务处理失败后重发成功。
2. request / response 跨 fragment 传输。
3. 自定义 UdpClientCodec。
4. payload 超限释放 encoded buffer。
5. duplicate fragment 释放。
6. assembly timeout 释放 fragments。
7. decode failure 释放 merged payload 并触发 onError。
8. EventLoop 内 close 不阻塞。
```

这些测试应继续保留，作为 `UdpClient = 无序可靠` 的回归基线。

---

## 2. 目标与非目标

### 2.1 目标

本计划目标：

```text
1. 保留 UdpClient 作为无序可靠 UDP。
2. 新增 KcpClient 作为有序可靠 UDP。
3. 不在 KCP 上实现无序可靠。
4. 不用 KCP 替换 UdpClient 的 RXUP 语义。
5. 两者都复用 UdpClientCodec / FuryUdpClientCodec。
6. 两者都继续走 Sockets.udpBootstrap / Sockets.writeUdp。
7. 两者都兼容 UDP 压缩、FEC、多倍发包、FinalEgressGuard pipeline。
8. 补充 KcpClient 的有序可靠测试、弱网测试、关闭释放测试。
```

### 2.2 非目标

本期不做：

```text
1. 不删除 UdpClient。
2. 不把 UdpClient 默认切到 KCP。
3. 不引入 UdpReliabilityMode.LEGACY_RXUP / KCP 这种双模式大开关。
4. 不在 KCP 上通过多 lane / 多 conv 实现无序可靠。
5. 不把 KcpClient 用于普通第三方 UDP server。
6. 不把 FEC / 压缩 / 多倍发包揉进 UdpClient 或 KcpClient 内部。
7. 不在 I/O 线程执行阻塞等待。
```

---

## 3. 最终组件定位

### 3.1 UdpClient：无序可靠

保留：

```java
org.rx.net.transport.UdpClient
```

定位：

```text
Simple reliable UDP client
Reliable unordered UDP message client
```

特点：

```text
1. messageId 独立可靠。
2. 消息之间没有全局顺序。
3. 重传粒度是完整逻辑消息的 fragments。
4. 资源占用轻，不需要 per-peer KCP session/window。
5. 适合低 QPS、控制面、RPC、打洞、Nameserver 同步。
```

继续保留 API：

```java
send(...)
sendAsync(...)
sendWithResult(...)
request(...)
requestAsync(...)
reply(...)
replyError(...)
onReceive
onError
```

文档需要明确：

```text
UdpClient 提供可靠性，但不提供消息全局有序性。
```

### 3.2 KcpClient：有序可靠

新增：

```java
org.rx.net.transport.KcpClient
```

定位：

```text
KCP based reliable ordered UDP client
```

特点：

```text
1. 每个 remote 建立 KCP 会话。
2. KCP 负责 ACK、重传、窗口、RTO、fast resend、MTU segment。
3. 同一 remote / conv 内严格有序交付。
4. 存在队头阻塞，这是有序可靠的自然代价。
5. 适合顺序敏感消息、连续状态同步、可靠流式 UDP。
```

建议 API 与 `UdpClient` 保持相似：

```java
public class KcpClient implements EventPublisher<KcpClient>, AutoCloseable {
    public final Delegate<KcpClient, NEventArgs<UdpMessage>> onReceive = Delegate.create();
    public final Delegate<KcpClient, NEventArgs<Throwable>> onError = Delegate.create();

    public KcpClient(int bindPort);
    public KcpClient(int bindPort, KcpClientConfig config);
    public KcpClient(int bindPort, UdpClientCodec codec);

    public ChannelFuture send(InetSocketAddress remoteAddress, Object packet);
    public KcpSendResult sendWithResult(InetSocketAddress remoteAddress, Object packet);

    public <T extends Serializable> T request(InetSocketAddress remoteAddress, Object packet, Class<T> responseType) throws TimeoutException;
    public <T extends Serializable> T request(InetSocketAddress remoteAddress, Object packet, Class<T> responseType, int timeoutMillis) throws TimeoutException;
    public <T extends Serializable> CompletableFuture<T> requestAsync(InetSocketAddress remoteAddress, Object packet, Class<T> responseType);
    public <T extends Serializable> CompletableFuture<T> requestAsync(InetSocketAddress remoteAddress, Object packet, Class<T> responseType, int timeoutMillis);

    public void reply(UdpMessage request, Serializable packet);
    public void replyError(UdpMessage request, Throwable error);
}
```

注意：

```text
KcpClient 不提供 sendUnordered。
```

如果业务需要无序可靠，使用 `UdpClient`。

---

## 4. 为什么不在 KCP 上做无序可靠

KCP 单 conv 的核心价值是可靠有序传输。它维护发送窗口、接收窗口、segment 序号、ACK、重传和按序 recv。若在同一 KCP conv 上做无序投递，会遇到：

```text
1. 前序 segment 丢失时，后续 segment 即使到达也不能按标准 recv 投递。
2. 修改 KCP 内部接收队列会偏离标准实现，维护成本高。
3. 多 conv / 多 lane 可以绕过队头阻塞，但会增加：
   - 多个 KCP 控制块；
   - 多套窗口；
   - 多套 update timer；
   - 更多内存；
   - 更复杂的 session 管理。
```

这与“无序可靠更简单、更省资源”的初衷相反。

因此最终原则：

```text
KcpClient = 有序可靠
UdpClient = 无序可靠
```

---

## 5. Pipeline 兼容性：FEC / 压缩 / 多倍发包

### 5.1 总体结论

`UdpClient` 和 `KcpClient` 都可以兼容现有 UDP pipeline：

```text
1. UDP 压缩：UdpCompressEncoder / UdpCompressDecoder
2. UDP 多倍发包：UdpRedundantEncoder / UdpRedundantDecoder
3. UDP FEC / resilience：UdpResilienceEncoder / UdpResilienceDecoder
4. UDP 最终出口保护：UdpFinalEgressGuardHandler
5. UDP 背压：Sockets.writeUdp / UdpBackpressure*
```

前提：

```text
1. 两者都用 Sockets.udpBootstrap(config, initChannel) 创建 DatagramChannel。
2. 两者所有 UDP 写出都走 Sockets.writeUdp(...)。
3. 自有协议 header 必须在 final egress guard 之前完成。
4. FEC / 压缩 / 多倍发包只在自有双端开启，不能直接发给普通 UDP 目标。
```

### 5.2 推荐 pipeline 位置

#### UdpClient 出站

```text
Object
  -> UdpClientCodec.encode(...)
  -> RXUP DATA / ACK header
  -> RXUP fragment datagram
  -> UdpCompressEncoder          optional
  -> UdpRedundantEncoder         optional
  -> UdpResilienceEncoder        optional，建议不要和 Redundant 同时默认开启
  -> UdpFinalEgressGuardHandler
  -> socket
```

#### UdpClient 入站

```text
socket
  -> UdpResilienceDecoder        optional
  -> UdpRedundantDecoder         optional
  -> UdpCompressDecoder          optional
  -> RXUP header parse
  -> RXUP fragment assembly
  -> UdpClientCodec.decode(...)
  -> onReceive / completeRequest
```

#### KcpClient 出站

```text
Object
  -> UdpClientCodec.encode(...)
  -> KcpClient logical message frame
  -> KCP send / flush
  -> RXKC datagram header + KCP segment
  -> UdpCompressEncoder          optional
  -> UdpRedundantEncoder         optional
  -> UdpResilienceEncoder        optional
  -> UdpFinalEgressGuardHandler
  -> socket
```

#### KcpClient 入站

```text
socket
  -> UdpResilienceDecoder        optional
  -> UdpRedundantDecoder         optional
  -> UdpCompressDecoder          optional
  -> RXKC datagram header parse
  -> KCP input
  -> KCP recv ordered message
  -> KcpClient logical frame parse
  -> UdpClientCodec.decode(...)
  -> onReceive / completeRequest
```

### 5.3 压缩兼容性

压缩可以放在 `UdpClient` / `KcpClient` 下层。效果如下：

```text
UdpClient:
  压缩的是每个 RXUP fragment datagram。
  如果想对完整业务对象做压缩，应放到 codec 层。

KcpClient:
  压缩的是每个 RXKC + KCP segment datagram。
  KCP 看到的是压缩前的 logical payload / segment size；UDP 线上看到的是压缩后的 datagram。
```

注意：

```text
1. 压缩后可能变小，也可能极端情况下变大。
2. final egress guard 必须在压缩之后检查最终真实 datagram 大小。
3. 双端必须同时开启压缩。
4. 不建议对已经加密或高度随机的数据默认开启压缩。
```

### 5.4 多倍发包兼容性

多倍发包可用于 `UdpClient` 和 `KcpClient` 下层。

对 `UdpClient`：

```text
1. 多倍发包可减少 RXUP DATA / ACK 单包丢失概率。
2. duplicate fragment 当前已有释放逻辑，需要继续回归测试。
3. duplicate ACK 对 pendingSends 应保持幂等。
```

对 `KcpClient`：

```text
1. 多倍发包可减少 KCP segment 丢失概率，降低重传延迟。
2. KCP input 必须能自然处理 duplicate segment。
3. 额外副本会增加 UDP 出口流量，必须计入 backpressure。
```

建议默认：

```text
UdpClient: 可按现有配置使用。
KcpClient: 默认关闭多倍发包，弱网低延迟场景再开启。
```

### 5.5 FEC / UdpResilience 兼容性

FEC 可以放在两者下层。

对 `UdpClient`：

```text
1. FEC 保护 RXUP fragment datagram。
2. 如果 FEC 恢复出丢失 fragment，RXUP assembly 可正常完成。
3. FEC 与 RXUP resend 同时存在时，可能减少 resend 次数。
```

对 `KcpClient`：

```text
1. FEC 保护 KCP segment datagram。
2. 如果 FEC 在 KCP RTO 前恢复 segment，可降低 KCP 重传和 tail latency。
3. KCP 自身已经可靠，FEC 只是降低恢复延迟，不是可靠性的必要条件。
```

建议默认：

```text
UdpClient: 默认不开 FEC，按业务弱网需要开启。
KcpClient: 默认不开 FEC，先保证 KCP 基础稳定；低延迟弱网场景再做 KCP + FEC 调参。
```

### 5.6 Redundant 与 FEC 不建议默认同时开启

原因：

```text
1. 两者都会增加额外 datagram。
2. 与 KCP 重传叠加后，出口流量可能明显放大。
3. 背压、MTU、统计、限速都更复杂。
```

建议策略：

```text
轻微随机丢包：优先 FEC。
短时突发丢包：可考虑 Redundant。
KCP 有序可靠：优先调 KCP noDelay / interval / fastResend，再考虑 FEC/Redundant。
```

### 5.7 Pipeline 安装原则

继续沿用现有原则：

```text
1. 自定义 UDP header 必须在 final egress guard 之前完成。
2. MTU 检查必须看最终真实包大小。
3. 多倍发包 / FEC 增加的额外流量必须计入限速和统计。
4. UdpResilience / RDNT / UCMP 包只能用于自有两端。
5. 不要把这些自定义包发给普通游戏服务器或普通 UDP 服务。
```

---

## 6. KcpClient 配置设计

新增：

```java
package org.rx.net.transport;

@Getter
@Setter
public class KcpClientConfig extends SocketConfig {
    private static final long serialVersionUID = 1L;

    private UdpClientCodec codec = FuryUdpClientCodec.createDefault();

    private int mtu = 1200;
    private int noDelay = 1;
    private int intervalMillis = 10;
    private int fastResend = 2;
    private int noCongestionControl = 1;
    private int sendWindow = 128;
    private int receiveWindow = 128;

    private int sessionIdleTimeoutMillis = 60 * 1000;
    private int requestTimeoutMillis = 15 * 1000;
    private int maxPayloadBytes = 128 * 1024;
    private int maxPendingBytesPerSession = 4 * 1024 * 1024;
    private int maxPendingMessagesPerSession = 1024;

    private boolean flushOnSend = true;
}
```

说明：

```text
1. KcpClientConfig 独立于 UdpClientConfig。
2. 两者都继承 SocketConfig，因此都能使用 UDP compression / redundant / resilience / mtu / backpressure 配置。
3. KCP 参数不要塞进 UdpClientConfig，避免语义污染。
```

---

## 7. KcpClient Wire 协议

### 7.1 UDP datagram header

建议 KCP 外层 datagram 使用独立 magic：

```text
RXKC UDP datagram:
+----------------------+----------------------+
| MAGIC 4B = 'RXKC'    | VERSION 1B = 1       |
+----------------------+----------------------+
| TYPE 1B              | FLAGS 1B             |
+----------------------+----------------------+
| RESERVED 1B          | CONV 4B              |
+----------------------+----------------------+
| KCP_SEGMENT...                              |
+---------------------------------------------+
```

建议常量：

```java
static final int MAGIC = 0x52584B43; // RXKC
static final byte VERSION = 1;
static final byte TYPE_KCP_DATA = 1;
static final byte TYPE_CLOSE = 2;
static final int HEADER_SIZE = 12;
```

说明：

```text
1. RXKC 与 RXUP magic 不同，避免误解码。
2. CONV 用于 KCP 会话识别。
3. 第一版不做复杂 handshake，可先用 remoteAddress + conv 建 session。
4. 后续如需 NAT rebinding，再扩展 sessionId。
```

### 7.2 KCP 内层 logical message frame

KCP recv 后得到有序 message payload。建议内层增加轻量 frame：

```text
KCP logical message:
+----------------------+----------------------+
| MSG_MAGIC 2B         | VERSION 1B           |
+----------------------+----------------------+
| MESSAGE_TYPE 1B      | MESSAGE_ID 4B        |
+----------------------+----------------------+
| REQUEST_ID 4B        | PAYLOAD_LENGTH 4B    |
+----------------------+----------------------+
| codec payload bytes                         |
+---------------------------------------------+
```

消息类型：

```java
DATA = 1
RPC_RESPONSE = 2
PING = 3
PONG = 4
CLOSE = 5
```

第一期可简化：

```text
1. DATA 直接携带 codec payload。
2. RPC_RESPONSE 可以继续复用 UdpRpcResponse，降低改造量。
3. 第二期再把 RPC response 收敛到 MESSAGE_TYPE。
```

---

## 8. KcpClient 内部结构

推荐新增包：

```text
org.rx.net.transport.kcp
  KcpSession
  KcpSessionKey
  KcpPacketHeader
  KcpMessageFrame
  KcpOutput
  KcpScheduler
  KcpStats
```

### 8.1 KcpSession

```java
final class KcpSession {
    final InetSocketAddress remoteAddress;
    final int conv;
    final RxKcp kcp;
    volatile long lastActiveMillis;
    volatile long nextUpdateMillis;
    volatile boolean closed;

    void send(ByteBuf payload);
    void input(ByteBuf kcpSegment);
    void update(long nowMillis);
    void drainRecv();
    void close();
}
```

约束：

```text
1. 每个 remote 默认一个 conv。
2. 同一 session 内严格有序。
3. 所有 kcp 操作必须在 Channel EventLoop 上执行。
4. 不支持 unordered lane。
```

### 8.2 RxKcp adapter

无论引入外部 Java KCP，还是内部 port，统一包装为：

```java
interface RxKcp {
    int send(ByteBuf payload);

    int input(ByteBuf segment);

    int recv(ByteBufAllocator allocator, ByteBuf out);

    long check(long nowMillis);

    void update(long nowMillis);

    void release();
}
```

如果底层 KCP 只能使用 byte[]：

```text
1. copy 集中在 RxKcpAdapter。
2. KcpClient 其他代码仍以 ByteBuf ownership 为准。
3. copy 后立即释放输入 ByteBuf。
```

---

## 9. Timer 与线程模型

KCP 需要 update 驱动。建议绑定 Netty EventLoop：

```text
channel.eventLoop().schedule(this::onKcpTick, interval, TimeUnit.MILLISECONDS)
```

原则：

```text
1. handlePacket 已在 EventLoop 上执行。
2. send 可能来自业务线程；如果不在 EventLoop，切回 EventLoop 执行 kcp.send。
3. KCP update / input / recv / flush 不跨线程并发执行。
4. 不在 EventLoop 内阻塞等待 request future。
5. close 从 EventLoop 调用时不能 sync 阻塞。
```

简化第一版：

```text
固定每 intervalMillis tick 一次。
```

后续优化：

```text
使用 kcp.check(now) 动态安排下一次 update。
```

---

## 10. send / request 语义

### 10.1 send

`KcpClient.send(...)` 表示：

```text
1. 业务对象成功 codec.encode。
2. logical message 成功写入 KCP send queue。
3. KCP output 写 UDP datagram 时通过 Sockets.writeUdp。
```

注意：

```text
KCP mode 的 send future 不表示对端业务 handler 已处理成功。
```

### 10.2 request / reply

继续保留 requestId：

```text
requestAsync:
  requestId = nextMessageId()
  pendingRequests.put(requestId, ctx)
  send DATA with requestId
  timeout 后 remove + completeExceptionally

reply:
  send RPC_RESPONSE with requestId

receive RPC_RESPONSE:
  pendingRequests.remove(requestId)
  complete future
```

这样 `KcpClient` 可以提供和 `UdpClient` 类似的 RPC 使用体验。

---

## 11. ByteBuf 生命周期规则

### 11.1 UdpClient

继续保持现有规则：

```text
1. codec.encode 返回 refCnt=1 的 ByteBuf，UdpClient 接管。
2. ACK != NONE 时 SendContext 持有 payload，直到 ACK / timeout / failure / close。
3. ACK == NONE 时 writeFragments 后释放 payload。
4. ReceiveAssembly 持有 retained fragment。
5. duplicate / timeout / replace / decode failure / close 都必须释放 fragment。
```

### 11.2 KcpClient

新增规则：

```text
1. codec.encode 返回 refCnt=1 的 ByteBuf，KcpClient 接管。
2. logical frame encode 成功后，应明确 payload ownership。
3. kcp.send 成功后，如果底层已复制，立即释放 logical frame；如果底层持有 ByteBuf，则由 RxKcp.release 释放。
4. kcp.input 如果底层已复制 datagram，立即释放 segment slice。
5. kcp.recv 产出的 ByteBuf 在 decode finally 中释放。
6. close 必须释放 KCP 内部 send/recv queue、pendingRequests、timer。
```

---

## 12. 背压与 MTU

### 12.1 UDP 出口背压

两者都必须使用：

```java
Sockets.writeUdp(channel, packet, "transport.udp", "component=...")
```

推荐 component：

```text
UdpClient: component=rxup
KcpClient: component=kcp
```

### 12.2 KcpClient 额外背压

KCP 有自己的 send queue，因此需要额外限制：

```text
1. maxPendingBytesPerSession
2. maxPendingMessagesPerSession
3. 全 client pending bytes，可后续增加
```

超过限制：

```text
1. send future failed。
2. payload release。
3. 计数 udp.kcp.backpressure.drop.count。
```

### 12.3 MTU

KcpClient 建议：

```text
effectiveKcpMtu = min(config.mtu, config.udpMtu > 0 ? config.udpMtu - RXKC_HEADER_SIZE : config.mtu)
```

说明：

```text
1. KCP segment + RXKC header 后仍需满足 final UDP MTU。
2. 如果启用压缩，压缩后可能变大，最终仍由 UdpFinalEgressGuardHandler 兜底。
3. 如果启用 FEC / Redundant，额外包也必须走 final guard。
```

---

## 13. 实施阶段

### 阶段 1：文档与定位修正

改动：

```text
1. 更新本计划文档。
2. docs/reference/net/udp.md 明确：
   - UdpClient = 无序可靠。
   - KcpClient = 有序可靠。
   - 二者都可接入 UDP pipeline。
```

验收：

```text
文档无歧义，不再描述“UdpClient KCP 化替换”。
```

### 阶段 2：保留并强化 UdpClient

改动：

```text
1. 不改变 UdpClient wire format。
2. 补充类注释：reliable unordered UDP message transport。
3. 补充 docs 中的使用场景。
4. 保留现有 UdpTransportTest。
```

验收：

```text
现有 UdpTransportTest 全通过。
```

### 阶段 3：新增 KcpClientConfig / KcpClient skeleton

改动：

```text
1. 新增 KcpClientConfig。
2. 新增 KcpClient 构造器。
3. 使用 Sockets.udpBootstrap 创建 channel。
4. pipeline 仍由 SocketConfig 控制。
5. 暂不接 KCP 逻辑，先完成生命周期。
```

验收：

```text
1. bind / close 正常。
2. EventLoop 内 close 不阻塞。
3. pipeline 可正常安装 compress/redundant/resilience/final guard。
```

### 阶段 4：接入 KCP adapter

改动：

```text
1. 引入或实现 Java 8 compatible KCP。
2. 新增 RxKcp adapter。
3. 新增 KcpSession。
4. 实现 KCP output -> RXKC DatagramPacket -> Sockets.writeUdp。
5. 实现 RXKC parse -> kcp.input。
6. 实现 EventLoop tick update / recv drain。
```

验收：

```text
1. 本地两个 KcpClient 可收发 ByteBuf payload。
2. 有序投递。
3. UDP 写出都经过 Sockets.writeUdp。
```

### 阶段 5：接入 codec 与 onReceive

改动：

```text
1. send 前 UdpClientCodec.encode。
2. recv 后 UdpClientCodec.decode。
3. 复用 UdpMessage 投递 onReceive。
4. decode failure 触发 onError。
```

验收：

```text
1. Fury 默认 codec 收发成功。
2. 自定义 codec 收发成功。
3. decode failure 无泄漏。
```

### 阶段 6：request / reply

改动：

```text
1. KcpClient 实现 requestAsync。
2. KcpClient 实现 reply/replyError。
3. pendingRequests timeout 清理。
4. response type mismatch 失败。
```

验收：

```text
1. request/response 成功。
2. request timeout 正常。
3. replyError 正常。
```

### 阶段 7：Pipeline 组合测试

分别测试：

```text
UdpClient + compress
UdpClient + redundant
UdpClient + resilience/FEC
KcpClient + compress
KcpClient + redundant
KcpClient + resilience/FEC
```

组合测试不建议默认全开，优先单项验证。

---

## 14. 测试计划

### 14.1 UdpClient 回归

继续执行：

```text
UdpTransportTest
```

重点确保：

```text
1. duplicate fragment 释放。
2. assembly timeout 释放。
3. FULL ACK 重发语义不变。
4. request 跨 fragment 不变。
```

### 14.2 KcpClient 新测试

新增：

```text
KcpClientTest
  - kcpClientSendsMessagesInOrder
  - kcpClientRequestResponse
  - kcpClientCustomCodecCanBeConfigured
  - kcpClientDecodeFailureReleasesPayload
  - kcpClientRequestTimeoutClearsPendingRequest
  - kcpClientCloseFromEventLoopDoesNotBlock
```

### 14.3 弱网测试

新增 fake UDP link 或 pipeline handler：

```text
LossyDatagramHandler
  - drop every N packet
  - duplicate every N packet
  - delay selected packet
  - reorder queue
```

用例：

```text
1. KcpClient 10% random loss 下最终有序到达。
2. KcpClient duplicate datagram 不重复投递业务消息。
3. KcpClient reorder datagram 后仍按发送顺序投递。
4. UdpClient 在乱序下不保证全局顺序，但全部可靠消息最终到达。
```

### 14.4 Pipeline 兼容测试

新增：

```text
UdpClientPipelineTest
  - udpClientWithCompression
  - udpClientWithRedundant
  - udpClientWithFec

KcpClientPipelineTest
  - kcpClientWithCompression
  - kcpClientWithRedundant
  - kcpClientWithFec
```

验证点：

```text
1. 双端开启后收发成功。
2. 单端开启时应丢弃或 onError，不应误解码。
3. writeUdp rejected 时 packet 释放正确。
4. final guard 能拦截超过 MTU 的最终 datagram。
```

### 14.5 内存泄漏测试

建议：

```bash
mvn -pl rxlib "-Dtest=UdpTransportTest,KcpClientTest,KcpClientPipelineTest" -DskipTests=false -Dio.netty.leakDetection.level=paranoid test
```

---

## 15. 风险与应对

### 15.1 语义误用风险

风险：

```text
使用方以为 KcpClient 也支持无序可靠。
```

应对：

```text
1. 类注释明确 ordered reliable。
2. 文档明确无序可靠请使用 UdpClient。
3. 不提供 sendUnordered API。
```

### 15.2 协议兼容风险

风险：

```text
RXUP 与 RXKC 不兼容。
```

应对：

```text
1. UdpClient 和 KcpClient 使用不同 magic。
2. 两者使用不同类，不共享配置开关。
3. 不尝试自动兼容对端协议。
```

### 15.3 Pipeline 叠加流量风险

风险：

```text
KCP 重传 + FEC + Redundant 同时开启导致流量放大。
```

应对：

```text
1. KcpClient 默认不开 FEC/Redundant。
2. docs 标明调参顺序。
3. 所有额外 datagram 计入 UDP backpressure。
4. 加监控指标。
```

### 15.4 ByteBuf 泄漏风险

风险点：

```text
1. KCP adapter 复制 / 持有 payload 的 ownership 不清晰。
2. output datagram rejected 后二次 release。
3. recv payload decode failure。
4. close 时 KCP queue 未释放。
```

应对：

```text
1. RxKcp adapter 注释 ownership。
2. 所有 failure 分支单测。
3. paranoid leak detector。
```

---

## 16. 监控指标

### 16.1 UdpClient 指标

```text
udp.rxup.send.count
udp.rxup.recv.count
udp.rxup.fragment.out.count
udp.rxup.fragment.in.count
udp.rxup.assembly.timeout.count
udp.rxup.ack.timeout.count
udp.rxup.resend.count
udp.rxup.decode.error.count
udp.rxup.write.drop.count
```

### 16.2 KcpClient 指标

```text
udp.kcp.session.count
udp.kcp.send.message.count
udp.kcp.recv.message.count
udp.kcp.send.bytes
udp.kcp.recv.bytes
udp.kcp.output.datagram.count
udp.kcp.input.datagram.count
udp.kcp.retransmit.count
udp.kcp.fastresend.count
udp.kcp.rtt.ms
udp.kcp.rto.ms
udp.kcp.cwnd
udp.kcp.snd_queue.size
udp.kcp.rcv_queue.size
udp.kcp.backpressure.drop.count
udp.kcp.decode.error.count
udp.kcp.encode.error.count
udp.kcp.session.idle.close.count
udp.kcp.tick.cost.ms
```

### 16.3 Pipeline 指标

```text
udp.compress.in.count
udp.compress.out.count
udp.compress.error.count
udp.redundant.copy.count
udp.redundant.duplicate.drop.count
udp.resilience.fec.recover.count
udp.resilience.fec.fail.count
udp.final_guard.drop.mtu.count
udp.final_guard.drop.backpressure.count
```

---

## 17. 文档更新计划

需要更新：

```text
docs/reference/net/udp.md
```

新增或调整：

```text
## UDP 可靠消息/RPC

UdpClient:
  - reliable unordered UDP message transport
  - RXUP frame
  - message-level ACK / resend / fragment
  - 适合控制面、打洞、低 QPS RPC

KcpClient:
  - reliable ordered UDP transport
  - RXKC + KCP
  - session/window/rto/update
  - 适合顺序敏感消息

Pipeline:
  - UdpClient 和 KcpClient 都可以使用 SocketConfig 上的 UDP 压缩、多倍发包、FEC 配置。
  - 双端必须一致开启。
  - 不要发给普通 UDP 目标。
```

---

## 18. 建议提交拆分

```text
1. docs: revise UDP reliable transport plan for UdpClient and KcpClient split
2. docs: clarify UdpClient as unordered reliable transport
3. transport: add KcpClientConfig
4. transport: add KcpClient skeleton and lifecycle
5. transport-kcp: add RXKC packet header and KCP adapter
6. transport-kcp: implement KcpSession update/input/output
7. transport: wire KcpClient codec and receive events
8. transport: implement KcpClient request/reply
9. test: add KcpClient ordered reliable tests
10. test: add UDP pipeline compatibility tests for UdpClient and KcpClient
11. docs: update UDP reference for KcpClient usage
```

---

## 19. 最小可交付版本

MVP 定义：

```text
1. UdpClient 不破坏现有行为，继续作为无序可靠 UDP。
2. 新增 KcpClient，可 bind / close / send / receive。
3. KcpClient 同一 remote 下消息严格按发送顺序投递。
4. KcpClient 支持默认 Fury codec 和自定义 UdpClientCodec。
5. KcpClient 支持 request / reply。
6. KcpClient 所有 UDP output 都走 Sockets.writeUdp。
7. KcpClient 可在双端开启 compression / redundant / FEC pipeline 后正常通信。
8. KcpClient close 不阻塞 EventLoop。
9. 关键 ByteBuf failure 分支无泄漏。
```

---

## 20. 最终建议

最终方向：

```text
UdpClient = 简易可靠 UDP，保留并明确为“无序可靠”。
KcpClient = 新增 KCP UDP，明确为“有序可靠”。
```

不要做：

```text
UdpClient 内部 mode 切换 KCP
KCP 上的 unordered lane / multi conv
一个类同时表达有序可靠和无序可靠
```

这样既保留当前 `UdpClient` 的轻量优势，也能新增 KCP 的有序可靠能力，并且两者都能复用 rxlib 已有 UDP pipeline：压缩、FEC、多倍发包、MTU guard 和背压保护。