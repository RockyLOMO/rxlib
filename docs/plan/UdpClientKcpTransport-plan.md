# UdpClient KCP 化改造计划：支持有序与无序可靠包

> 仓库：`rockylomo/rxlib`  
> 分支：`master`  
> 目标路径建议：`docs/plan/UdpClientKcpTransport-plan.md`  
> 生成日期：2026-05-25

## 0. 结论先行

建议把当前 `org.rx.net.transport.UdpClient` 从“自研 RXUP ACK / 重传 / 分片重组”逐步切换为“Netty DatagramChannel + KCP session + rxlib codec / RPC facade”的结构。

关键设计点：

1. **KCP 负责可靠传输、重传、窗口、RTT/RTO、MTU 分片。**
2. **`UdpClientCodec` 继续负责对象与 `ByteBuf` payload 的编解码。**
3. **`UdpClient` 继续作为对外 facade，保留 `send` / `request` / `reply` / `onReceive` 等使用方式。**
4. **包有序 / 无序不要在单条 KCP conv 内强行混用。**  
   KCP 单 conv 天然按序交付；第一期建议用“多 lane / 多 conv”实现无序：
   - `ORDERED`：固定 ordered lane，严格按 KCP 顺序交付。
   - `UNORDERED`：按消息 id、hash 或 round-robin 分散到多个 unordered lanes，每个 lane 内有序，lane 间到达即投递，从而实现业务视角的无序可靠。
5. **保留旧 RXUP 协议一个兼容周期。**  
   新增配置开关 `UdpReliabilityMode.LEGACY_RXUP / KCP`，默认值先保持兼容，待测试稳定后再考虑切换默认。

---

## 1. 当前代码与约束

### 1.1 项目约束

项目级规范要求：

- Java 版本严格 Java 8。
- 网络路径属于高性能 Netty 底层网络编程。
- 热点路径避免频繁对象分配，优先 direct buffer / pooled buffer。
- `ByteBuf` 必须严格遵守引用计数。
- I/O 线程不得阻塞。
- 网络改动必须覆盖内存泄漏、背压、生命周期、线程模型、协议兼容和监控指标。

### 1.2 当前 UdpClient 职责

当前 `UdpClient` 位于：

```text
rxlib/src/main/java/org/rx/net/transport/UdpClient.java
```

它目前同时承担：

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

当前 wire header 常量：

```java
static final int MAGIC = 0x52585550;
static final byte TYPE_DATA = 1;
static final byte TYPE_ACK = 2;
static final int ACK_HEADER_SIZE = 9;
static final int DATA_HEADER_SIZE = 18;
```

当前出站大致流程：

```text
Object
  -> UdpClientCodec.encode(...)
  -> ByteBuf payload
  -> fragmentCount(payload.readableBytes())
  -> retainedSlice per fragment
  -> RXUP DATA header + fragment payload
  -> Sockets.writeUdp(...)
  -> ACK timeout / resend timer
```

当前入站大致流程：

```text
DatagramPacket
  -> RXUP magic/type/header 校验
  -> fragment payload readRetainedSlice(...)
  -> ReceiveAssembly(sender, messageId) 聚合
  -> CompositeByteBuf buildPayload(...)
  -> UdpClientCodec.decode(...)
  -> UdpMessage
  -> RPC response 或 onReceive
  -> SEMI/FULL ACK
```

### 1.3 当前配置

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

### 1.4 当前测试基线

当前已有 `UdpTransportTest` 覆盖：

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

KCP 化时这些测试需要迁移或替换为 KCP 语义下的等价测试。

---

## 2. 目标与非目标

### 2.1 目标

本次目标：

1. 用 KCP 替代当前 `UdpClient` 自研 ACK / resend / fragment / receive assembly。
2. 在 KCP 基础上支持可靠有序包与可靠无序包。
3. 尽量保持 `UdpClient` 对外 API 兼容。
4. 继续复用 `UdpClientCodec` / `FuryUdpClientCodec`。
5. 所有 UDP 写出继续走 `Sockets.writeUdp(...)`，继承 rxlib 的 UDP 背压、MTU、pending bytes 保护。
6. 兼容 Java 8 和当前 Netty 版本。
7. 新增足够测试，覆盖丢包、乱序、重复、超时、关闭、内存释放。

### 2.2 非目标

本期不做：

1. 不把 `UdpClient` 直接改造成 QUIC。
2. 不在第一期删除旧 RXUP 实现。
3. 不给普通第三方 UDP server 发送 KCP 包；KCP 仅用于 rxlib 自有双端。
4. 不把 KCP 用作 SOCKS/SS UDP relay 的默认数据面。
5. 不在 I/O 线程做阻塞等待。
6. 不把无序语义伪装成“单 conv 内跳过 KCP 顺序阻塞”。

---

## 3. 推荐总体架构

### 3.1 分层结构

建议把 KCP 相关实现拆到独立包：

```text
org.rx.net.transport.kcp
  KcpClientTransport
  KcpSession
  KcpSessionKey
  KcpSessionConfig
  KcpLane
  KcpLaneMode
  KcpPacketHeader
  KcpOutput
  KcpScheduler
  KcpStats
```

`UdpClient` 保持 facade：

```text
UdpClient
  -> UdpTransportEngine interface
       -> LegacyRxupTransportEngine
       -> KcpTransportEngine
```

推荐接口：

```java
interface UdpTransportEngine extends AutoCloseable {
    ChannelFuture send(InetSocketAddress remoteAddress, Object packet, UdpSendOptions options);

    UdpSendResult sendWithResult(InetSocketAddress remoteAddress, Object packet, UdpSendOptions options);

    void handlePacket(DatagramPacket packet);

    boolean isActive();
}
```

第一期也可以不抽象 `UdpTransportEngine`，直接在 `UdpClient` 内通过 `mode` 分支委托。但为了降低迁移风险，建议抽象。

### 3.2 新配置

新增枚举：

```java
public enum UdpReliabilityMode {
    LEGACY_RXUP,
    KCP
}

public enum UdpPacketOrder {
    ORDERED,
    UNORDERED
}
```

扩展 `UdpClientConfig`：

```java
public class UdpClientConfig extends SocketConfig {
    private UdpClientCodec codec = FuryUdpClientCodec.createDefault();

    // legacy RXUP
    private int waitAckTimeoutMillis = 15 * 1000;
    private boolean fullSync;
    private int maxResend = 2;
    private int maxFragmentPayloadBytes = 1024;
    private int maxFragmentCount = 128;

    // new transport selector
    private UdpReliabilityMode reliabilityMode = UdpReliabilityMode.LEGACY_RXUP;

    // default packet order for send(...)
    private UdpPacketOrder defaultPacketOrder = UdpPacketOrder.ORDERED;

    // KCP settings
    private int kcpMtu = 1200;
    private int kcpOrderedLaneCount = 1;
    private int kcpUnorderedLaneCount = 4;
    private int kcpNoDelay = 1;
    private int kcpIntervalMillis = 10;
    private int kcpFastResend = 2;
    private int kcpNoCongestionControl = 1;
    private int kcpSendWindow = 128;
    private int kcpReceiveWindow = 128;
    private int kcpSessionIdleTimeoutMillis = 60 * 1000;
    private int kcpRequestTimeoutMillis = 15 * 1000;
    private int kcpMaxPayloadBytes = 1024 * 128;
}
```

默认值建议：

```text
reliabilityMode = LEGACY_RXUP
```

原因：

```text
1. 避免 master 直接破坏已有双端 wire compatibility。
2. 旧 UDP 打洞、Nameserver、Hybrid 可能仍依赖 RXUP header。
3. KCP 需要双端同时开启。
```

待 KCP 测试稳定后，再单独做默认值切换计划。

### 3.3 发送选项

新增：

```java
@Getter
@Setter
public final class UdpSendOptions implements Serializable {
    private int timeoutMillis;
    private UdpPacketOrder order = UdpPacketOrder.ORDERED;
    private boolean flush = true;
    private int lane = -1;
}
```

`UdpClient` 增加重载：

```java
public ChannelFuture send(InetSocketAddress remoteAddress, Object packet, UdpPacketOrder order);

public ChannelFuture send(InetSocketAddress remoteAddress, Object packet, UdpSendOptions options);

public UdpSendResult sendWithResult(InetSocketAddress remoteAddress, Object packet, UdpSendOptions options);
```

原有 API 行为：

```text
send(remote, packet)
  -> 使用 config.defaultPacketOrder

request(remote, packet, responseType, timeout)
  -> 强制 ORDERED，除非后续显式支持 request unordered
```

建议 request/reply 默认有序，理由：

```text
1. RPC response 与 requestId 强关联，有序不是必须，但有序更利于调试。
2. request 通常是控制面，不应为了无序牺牲可观测性。
3. 后续可补 requestAsync(..., UdpSendOptions)。
```

---

## 4. 有序与无序设计

### 4.1 不建议的方案：单 KCP conv 内实现无序

不要在单个 KCP conv 上尝试“有些消息有序，有些消息无序”。原因：

```text
1. KCP recv 面向按序 byte/message 流。
2. 如果前序 segment 丢失，后续 segment 即使到达也无法被同一 recv 正常投递。
3. 强行改 KCP 内部接收队列会偏离标准实现，后续维护成本高。
```

### 4.2 推荐方案：多 lane / 多 conv

对每个 remote endpoint 建立一个 `KcpSession`，内部有多个 lane：

```text
KcpSession(remote)
  orderedLane[0]      conv = baseConv + 0
  unorderedLane[0]    conv = baseConv + 1
  unorderedLane[1]    conv = baseConv + 2
  unorderedLane[2]    conv = baseConv + 3
  unorderedLane[3]    conv = baseConv + 4
```

发送策略：

```text
ORDERED:
  固定 orderedLane[0]

UNORDERED:
  如果 options.lane >= 0，使用指定 unordered lane
  否则使用 messageId hash 或 round-robin 选择 unordered lane
```

接收策略：

```text
每个 lane 自己调用 kcp.input(datagramPayload)
每个 lane 自己循环 kcp.recv(...)
ORDERED lane 输出保持该 lane 内顺序
UNORDERED lanes 的输出跨 lane 不排序，哪个 lane recv 到完整消息就立即 publish
```

业务语义：

```text
ORDERED = 对同一 remote 的 ordered lane 全局有序。
UNORDERED = 可靠，但只保证单 lane 内有序；跨 unordered lanes 无全局顺序。
```

如果业务想“同一 entity 有序，不同 entity 无序”，可以让调用方指定 lane：

```java
options.setOrder(UdpPacketOrder.UNORDERED);
options.setLane(entityId & (unorderedLaneCount - 1));
```

### 4.3 lane 数量建议

默认：

```text
orderedLaneCount = 1
unorderedLaneCount = 4
```

原因：

```text
1. lane 越多，KCP 控制块、窗口和 timer 成本越高。
2. 4 条 unordered lane 可以明显降低单 lane 队头阻塞。
3. 对控制面足够轻量。
```

高吞吐场景再调大到 8 或 16。

---

## 5. Wire 协议设计

### 5.1 外层 UDP packet header

KCP 本身需要 conv，但为了区分 rxlib transport 协议、版本和 lane，建议 UDP datagram 外面增加一个轻量 header：

```text
KCP UDP datagram:
+----------------------+----------------------+
| MAGIC 4B = 'RXKP'    | VERSION 1B = 1       |
+----------------------+----------------------+
| TYPE 1B              | FLAGS 1B             |
+----------------------+----------------------+
| LANE_MODE 1B         | SESSION_ID 8B        |
+----------------------+----------------------+
| CONV 4B              | KCP_SEGMENT...       |
+----------------------+----------------------+
```

建议常量：

```java
static final int KCP_MAGIC = 0x52584B50; // RXKP
static final byte KCP_VERSION = 1;
static final byte TYPE_KCP_DATA = 1;
static final byte TYPE_KCP_CLOSE = 2;
static final byte LANE_ORDERED = 1;
static final byte LANE_UNORDERED = 2;
static final int KCP_HEADER_SIZE = 20;
```

`SESSION_ID`：

```text
1. 本端为每个 remote 生成 64-bit random/session id。
2. 双端第一次收到 unknown sessionId 时创建 inbound session。
3. 可选后续做 handshake，把 client/server session id 协商得更严格。
```

第一期简化方案：

```text
使用 remote endpoint + conv 作为 session key，不加复杂 handshake。
SESSION_ID 保留字段，用于后续 NAT rebinding / reconnect。
```

### 5.2 KCP 内层消息帧

KCP 交付出来的是可靠 payload。为了支持 RPC、send result、trace、order 标记，建议 KCP 内部 payload 再包一层 rxlib message frame：

```text
KCP logical message:
+----------------------+----------------------+
| MSG_MAGIC 2B         | VERSION 1B           |
+----------------------+----------------------+
| MESSAGE_TYPE 1B      | FLAGS 1B             |
+----------------------+----------------------+
| ORDER 1B             | MESSAGE_ID 4B        |
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

`REQUEST_ID`：

```text
普通 DATA = 0
request 发出时 = requestId
reply/replyError 响应时 = 原 requestId
```

这样可以替代当前 `UdpRpcResponse` 作为外层对象发送的方式，也可以保持兼容：第一期仍允许 codec payload 是 `UdpRpcResponse`，第二期再收敛内层 frame。

### 5.3 第一阶段最小协议

为降低改造量，第一阶段可采用最小内层帧：

```text
+----------------------+----------------------+
| MESSAGE_ID 4B        | MESSAGE_TYPE 1B      |
+----------------------+----------------------+
| REQUEST_ID 4B        | ORDER 1B             |
+----------------------+----------------------+
| PAYLOAD_LENGTH 4B    | codec payload        |
+----------------------+----------------------+
```

并继续复用：

```java
UdpMessage
UdpRpcResponse
UdpClientCodec
```

---

## 6. KCP 库选择

### 6.1 候选方案

候选：

```text
1. 引入纯 Java KCP 实现。
2. 在 rxlib 内实现最小 KCP Java port。
3. 使用 JNI / native KCP。
```

建议优先级：

```text
第一选择：成熟纯 Java KCP 实现，源码可审计、支持 Java 8、没有重量级框架绑定。
第二选择：将 ikcp.c 语义 port 成 rxlib 内部 Java 版。
不建议：JNI/native，发布和跨平台成本高。
```

### 6.2 选型检查项

引入前必须确认：

```text
1. Java 8 兼容。
2. 无阻塞线程模型假设。
3. output 回调可直接写 Netty DatagramPacket。
4. input/update/check/recv 暴露完整。
5. 支持设置 mtu / nodelay / wndsize。
6. license 与 Apache 2.0 项目兼容。
7. ByteBuf 接入是否会产生不可接受的 byte[] copy。
```

### 6.3 包装层原则

无论底层库怎么选，对 rxlib 暴露统一包装：

```java
interface RxKcp {
    int send(ByteBuf payload);

    int input(ByteBuf datagramPayload);

    int recv(ByteBufAllocator allocator, ByteBuf out);

    long check(long nowMillis);

    void update(long nowMillis);

    void release();
}
```

如果底层 KCP 只能使用 `byte[]`，则 copy 集中在 `RxKcpAdapter`，不要污染 `UdpClient`。

---

## 7. 核心类设计

### 7.1 KcpSessionKey

```java
@RequiredArgsConstructor
final class KcpSessionKey {
    final InetSocketAddress remoteAddress;
    final long sessionId;
}
```

第一期可简化为 remote endpoint：

```java
final InetSocketAddress remoteAddress;
```

### 7.2 KcpLane

```java
final class KcpLane {
    final int conv;
    final UdpPacketOrder order;
    final RxKcp kcp;
    final Queue<ByteBuf> recvQueue;
    volatile long nextUpdateMillis;
    volatile long lastActiveMillis;
    volatile boolean closed;

    int send(ByteBuf messagePayload);

    void input(ByteBuf kcpDatagramPayload);

    void update(long nowMillis);

    void drainRecv(KcpSession session);

    void close();
}
```

注意：

```text
1. lane 的所有 kcp 操作必须在所属 Channel 的 EventLoop 执行。
2. send 入参所有权由 lane 接管，失败时 lane 负责 release。
3. recv 出来的 ByteBuf 解码后由 transport 统一 release。
```

### 7.3 KcpSession

```java
final class KcpSession {
    final InetSocketAddress remoteAddress;
    final long sessionId;
    final KcpLane orderedLane;
    final KcpLane[] unorderedLanes;
    final AtomicInteger unorderedSequence;
    volatile TimeoutFuture<?> idleTimeout;

    KcpLane selectLane(UdpPacketOrder order, int laneHint) {
        if (order == UdpPacketOrder.ORDERED) {
            return orderedLane;
        }
        if (laneHint >= 0) {
            return unorderedLanes[laneHint % unorderedLanes.length];
        }
        return unorderedLanes[unorderedSequence.getAndIncrement() & (unorderedLanes.length - 1)];
    }
}
```

如果 `unorderedLaneCount` 不是 2 的幂，不能使用 `&`，需要 `%`。

### 7.4 KcpTransportEngine

职责：

```text
1. 管理 remote -> KcpSession。
2. 创建 / 查找 lane。
3. 将 Object 经 UdpClientCodec 编码为 payload。
4. 构造 KCP logical message frame。
5. 调用 lane.send。
6. 驱动 lane.update / check。
7. 解析入站 RXKP header 并分发给 lane.input。
8. lane.recv 后 decode 并投递 onReceive / completeRequest。
9. close 清理 KCP 控制块、pendingRequests、pendingSendResults、ByteBuf。
```

---

## 8. Timer 与 EventLoop 模型

### 8.1 update 驱动

KCP 需要周期性 update。建议不要为每个 lane 创建独立全局 timer，而是绑定 Netty EventLoop：

```java
eventLoop.schedule(this::onKcpTick, interval, TimeUnit.MILLISECONDS)
```

每个 `KcpTransportEngine` 按 EventLoop 维护一个 tick：

```text
onKcpTick:
  now = System.currentTimeMillis()
  for each session:
    for each lane:
      if lane.nextUpdateMillis <= now:
        lane.update(now)
        lane.drainRecv(session)
        lane.nextUpdateMillis = lane.kcp.check(now)
  schedule next tick = min(nextUpdateMillis, now + maxInterval)
```

简化第一期：

```text
固定每 kcpIntervalMillis tick 一次。
```

后续优化：

```text
使用 kcp.check(now) 动态计算下次 tick。
```

### 8.2 线程亲和

约束：

```text
1. `handlePacket` 已在 Channel pipeline 的 EventLoop 中执行。
2. `send(...)` 可能来自任意业务线程。
3. 如果 send 不在 EventLoop，必须 `eventLoop.execute(...)` 切回 I/O 线程操作 KCP。
4. 不得在 sendAsync 内 `future.get(...)` 阻塞 EventLoop。
```

`sendAsync` 兼容方案：

```text
旧 sendAsync 当前会等待 ACK；KCP 无应用层 ACK 后，应改为等待“payload 已成功写入 KCP send queue / 首次 flush 写出”，而不是等对端处理。
```

建议重新定义：

```text
KCP mode 下：
  send(...) 返回 writeFuture，表示入队或写出失败。
  sendWithResult.ackFuture 可以表示 KCP flush accepted，不能表示 remote received。
```

如必须保留“对端处理成功”语义，需要新增应用层 ACK frame，这不建议第一期做。

---

## 9. API 兼容策略

### 9.1 保留 API

保持：

```java
public ChannelFuture send(InetSocketAddress remoteAddress, Object packet)
public ChannelFuture send(InetSocketAddress remoteAddress, Object packet, int waitAckTimeoutMillis, boolean fullSync)
public UdpSendResult sendWithResult(...)
public ChannelFuture sendAsync(...) throws TimeoutException
public <T extends Serializable> T request(...)
public <T extends Serializable> CompletableFuture<T> requestAsync(...)
public void reply(...)
public void replyError(...)
```

### 9.2 KCP mode 下旧参数映射

```text
waitAckTimeoutMillis:
  legacy RXUP = ACK timeout
  KCP = request timeout / send queue timeout

fullSync:
  legacy RXUP = 业务 handler 成功后 ACK
  KCP = 不再建议复用。为兼容可映射：true -> ORDERED，false -> config.defaultPacketOrder

maxResend:
  legacy RXUP = 应用层最大重发次数
  KCP = 不直接使用，由 KCP RTO/fastResend 控制

maxFragmentPayloadBytes / maxFragmentCount:
  legacy RXUP = 应用层分片限制
  KCP = 用 kcpMtu / kcpMaxPayloadBytes 替代，旧字段仅作为兼容上限参考
```

### 9.3 建议新增更清晰 API

```java
public ChannelFuture sendOrdered(InetSocketAddress remoteAddress, Object packet);

public ChannelFuture sendUnordered(InetSocketAddress remoteAddress, Object packet);

public ChannelFuture sendUnordered(InetSocketAddress remoteAddress, Object packet, int lane);
```

---

## 10. request / reply 设计

### 10.1 保持 requestId 机制

继续使用当前 `pendingRequests` 思路：

```text
requestAsync:
  requestId = nextMessageId()
  pendingRequests.put(requestId, ctx)
  encode logical DATA frame with requestId
  send ORDERED

reply:
  encode logical RPC_RESPONSE frame with requestId
  send ORDERED

on receive RPC_RESPONSE:
  pendingRequests.remove(requestId)
  complete future
```

### 10.2 不再依赖 UdpRpcResponse 作为业务对象

当前 `reply` 会发送：

```java
UdpRpcResponse.success(request.id, packet)
```

KCP mode 建议内层 frame 直接表达 RPC response，避免业务 codec 看到 `UdpRpcResponse`。但为了降低改动，第一期可以继续使用 `UdpRpcResponse`：

```text
第一期：复用 UdpRpcResponse，逻辑简单。
第二期：迁移到 KcpMessageType.RPC_RESPONSE 内层 frame。
```

推荐第一期选择：

```text
KCP mode 第一版仍复用 UdpRpcResponse。
```

这样 `completeRequest(...)` 几乎不变。

---

## 11. ByteBuf 生命周期

### 11.1 发送侧

规则：

```text
1. codec.encode 返回 refCnt=1 的 ByteBuf，所有权交给 KcpTransportEngine。
2. logical frame encode 成功后，codec payload 应被 logical frame 持有或释放。
3. lane.send 成功后，KCP adapter 接管 logical frame。
4. lane.send 失败必须 release logical frame。
5. 如果底层 KCP 复制到 byte[]，复制完成后立即释放 logical frame。
6. 不允许 SendContext 长时间持有原始业务 payload，重传由 KCP 内部负责。
```

### 11.2 接收侧

规则：

```text
1. handlePacket 不 retain 整个 DatagramPacket，只 retain KCP segment payload 或在 EventLoop 内立即 input。
2. input 后如果底层 KCP 复制 payload，立刻 release retained slice。
3. lane.recv 产出的 ByteBuf 传给 logical frame decoder。
4. codec.decode 后 finally release logical message ByteBuf。
5. decode 失败不触发 RPC complete，不发送业务成功事件。
```

### 11.3 关闭

`close()` 必须释放：

```text
1. 所有 KcpSession / KcpLane。
2. KCP 内部 send/recv queue 里仍持有的 ByteBuf。
3. pendingRequests timeout。
4. pending send result future。
5. EventLoop scheduled tick。
6. channel / channels。
```

---

## 12. 背压与流控

### 12.1 继续使用 Sockets.writeUdp

KCP output 回调必须统一走：

```java
Sockets.writeUdp(channel, packet, "transport.udp.kcp", "component=rpc")
```

原因：

```text
1. 复用已有 UDP inactive / notWritable / unresolved recipient 检查。
2. 复用 pending bytes / packets 限制。
3. 复用 UDP MTU guard。
4. 写失败时统一释放 DatagramPacket content。
```

### 12.2 KCP send queue 上限

新增配置：

```java
private int kcpMaxPendingMessages = 1024;
private int kcpMaxPendingBytes = 4 * 1024 * 1024;
```

发送前检查：

```text
1. 单 session pending bytes。
2. 单 lane pending bytes。
3. 全 client pending bytes。
```

超过限制：

```text
1. send future failed。
2. payload release。
3. 计数 udp.kcp.send.drop.backpressure。
```

### 12.3 MTU

建议：

```text
kcpMtu = min(config.kcpMtu, config.udpMtu > 0 ? config.udpMtu - KCP_HEADER_SIZE : config.kcpMtu)
```

避免：

```text
RXKP header + KCP segment > UDP final MTU
```

---

## 13. 与 UDP resilience / FEC / redundancy 的关系

当前文档里已有 `UdpResilience*`、FEC、多倍发包、压缩和 final egress guard。

KCP 和这些能力的关系：

```text
KCP 负责可靠重传。
UdpResilience/FEC 负责抗随机丢包和低延迟恢复。
UdpRedundant 负责简单多倍发包。
UdpCompress 负责单包压缩。
```

推荐第一期：

```text
KCP mode 不默认叠加 UdpResilience/FEC。
```

原因：

```text
1. KCP 自身有重传和 fast resend。
2. FEC 会增加额外 datagram，和 KCP 拥塞/窗口统计叠加后不容易调参。
3. 先保证 KCP 基础正确，再单独设计 KCP + FEC。
```

如果后续叠加，顺序应是：

```text
KCP logical payload
  -> KCP segment
  -> RXKP UDP header
  -> optional UdpResilienceEncoder
  -> UdpFinalEgressGuardHandler
  -> socket
```

---

## 14. 实施阶段

### 阶段 1：配置与模式选择

改动：

```text
1. 新增 UdpReliabilityMode。
2. 新增 UdpPacketOrder。
3. 扩展 UdpClientConfig KCP 字段。
4. 新增 UdpSendOptions。
5. UdpClient 构造时根据 reliabilityMode 初始化 legacy 或 kcp engine。
```

验收：

```text
1. LEGACY_RXUP 默认行为不变。
2. 现有 UdpTransportTest 全部通过。
3. 新增 config getter/setter 测试。
```

### 阶段 2：KCP adapter 与 lane/session

改动：

```text
1. 引入或实现 RxKcp adapter。
2. 新增 KcpLane。
3. 新增 KcpSession。
4. 新增 KcpTransportEngine skeleton。
5. 实现 output -> Sockets.writeUdp。
6. 实现 EventLoop tick update。
```

验收：

```text
1. 单 lane 本地 loopback 可发送并接收 payload。
2. KCP datagram 经 Netty UDP 实际发送。
3. close 释放全部资源。
```

### 阶段 3：接入 UdpClientCodec 与 onReceive

改动：

```text
1. KCP send 前 codec.encode。
2. KCP recv 后 codec.decode。
3. 复用 UdpMessage 投递 onReceive。
4. 实现 sendOrdered / sendUnordered。
```

验收：

```text
1. StringUtf8Codec 收发成功。
2. Fury 默认 codec 收发成功。
3. 自定义对象收发成功。
4. decode failure 触发 onError 且无泄漏。
```

### 阶段 4：request / reply

改动：

```text
1. requestAsync 使用 KCP ORDERED lane。
2. pendingRequests timeout 保持。
3. reply/replyError 使用 UdpRpcResponse 或 KCP RPC_RESPONSE frame。
4. completeRequest 复用现有逻辑。
```

验收：

```text
1. request/response 成功。
2. response 类型不匹配失败。
3. request timeout 清理 pendingRequests。
4. replyError 传递异常。
```

### 阶段 5：无序可靠包

改动：

```text
1. unordered lane pool。
2. lane 选择策略。
3. 接收端跨 lane 到达即投递。
4. 可选 lane hint API。
```

验收：

```text
1. ORDERED 发送 1..N，接收严格 1..N。
2. UNORDERED 在人工丢包/延迟前序 lane 时，其他 lane 消息可先投递。
3. UNORDERED 消息不丢，最终全部到达。
4. 同一 unordered lane 内仍保持 lane 内顺序。
```

### 阶段 6：压测、文档与灰度

改动：

```text
1. 更新 docs/reference/net/udp.md。
2. 新增 KCP mode 使用示例。
3. 加监控指标。
4. 增加弱网测试。
5. 评估是否把默认 mode 改为 KCP。
```

---

## 15. 测试计划

### 15.1 单元测试

新增：

```text
KcpLaneTest
  - send/recv 单消息
  - 多消息有序
  - close release

KcpPacketHeaderTest
  - encode/decode RXKP header
  - wrong magic/version drop
  - lane mode invalid drop

KcpTransportEngineTest
  - codec encode/decode 成功
  - codec encode failure release
  - codec decode failure release + onError
  - send after close failed
```

### 15.2 集成测试

新增或扩展 `UdpTransportTest`：

```text
kcpOrderedMessagesDeliveredInOrder
kcpUnorderedMessagesCanBypassBlockedLane
kcpRequestResponseAcrossKcp
kcpReplyErrorCompletesExceptionally
kcpCustomCodecCanBeConfigured
kcpOversizedPayloadRejectedAndReleased
kcpCloseFromEventLoopDoesNotBlock
```

### 15.3 弱网模拟测试

建议新增一个测试用 handler 或 fake UDP link：

```text
LossyDatagramHandler
  - drop every N packet
  - duplicate every N packet
  - delay selected packet
  - reorder queue
```

用例：

```text
1. 10% random loss 下 ORDERED 全部到达。
2. 10% random loss 下 UNORDERED 全部到达。
3. 人工延迟 ordered 第 1 包，ordered 第 2 包不得先投递。
4. 人工延迟 unordered lane0 第 1 包，lane1/lane2 的包可以先投递。
5. duplicate datagram 不产生重复业务消息。
```

### 15.4 内存泄漏测试

建议开启 Netty paranoid leak detector 的测试 profile：

```bash
mvn -pl rxlib "-Dtest=UdpTransportTest,Kcp*Test" -DskipTests=false -Dio.netty.leakDetection.level=paranoid test
```

### 15.5 回归测试

至少执行：

```bash
mvn -pl rxlib -DskipTests compile
mvn -pl rxlib -DskipTests test-compile
mvn -pl rxlib "-Dtest=UdpTransportTest" -DskipTests=false test
```

网络相关回归按项目规范补充：

```text
SocksProxyServerIntegrationTest
ShadowsocksServerIntegrationTest
Socks5ClientIntegrationTest
RrpIntegrationTest
RemotingTest
DnsServerIntegrationTest
```

---

## 16. 风险与应对

### 16.1 协议兼容风险

风险：

```text
LEGACY_RXUP 与 KCP wire format 不兼容。
```

应对：

```text
1. 新增 reliabilityMode，默认 LEGACY_RXUP。
2. KCP 只在双端同时配置时启用。
3. RXKP magic 与 RXUP magic 完全不同，避免误解码。
4. docs 明确升级步骤。
```

### 16.2 语义变化风险

风险：

```text
旧 FULL ACK 表示业务 handler 成功后 ACK；KCP ACK 只表示传输层可靠，不表示业务处理成功。
```

应对：

```text
1. 文档明确 KCP mode 下 fullSync 不再等价。
2. request/reply 保持应用层响应。
3. 如业务需要“处理成功 ACK”，新增 explicit app ack，而不是复用 KCP ack。
```

### 16.3 队头阻塞风险

风险：

```text
单 KCP conv 有序会产生队头阻塞。
```

应对：

```text
1. ORDERED 明确接受该语义。
2. UNORDERED 使用多 lane / 多 conv。
3. 对 entity 级顺序使用 lane hint。
```

### 16.4 内存泄漏风险

风险点：

```text
1. KCP adapter 内部 queue。
2. output DatagramPacket content。
3. recv ByteBuf。
4. codec encode/decode 异常分支。
5. close 时未清理 pending lane buffers。
```

应对：

```text
1. 所有 ownership 写入类注释。
2. adapter 统一 release。
3. 单测覆盖 failure / timeout / close。
4. leak detector profile。
```

### 16.5 EventLoop 延迟风险

风险：

```text
KCP update / recv drain / codec decode 都可能在 I/O 线程执行。
```

应对：

```text
1. 限制每 tick drain message 数。
2. 限制单 payload 最大字节数。
3. 大对象或复杂业务 decode 后续可切业务线程。
4. 记录 tick cost 和 p99。
```

---

## 17. 监控指标

建议新增：

```text
udp.kcp.session.count
udp.kcp.lane.count
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
udp.kcp.ordered.deliver.count
udp.kcp.unordered.deliver.count
```

日志建议：

```text
debug:
  session create/close
  mode/lane selected

warn:
  invalid magic/version
  unknown conv
  oversized payload
  writeUdp rejected
  session idle timeout

error:
  codec decode unexpected exception
  KCP adapter invariant failure
```

不要打印 payload 内容。

---

## 18. 文档更新

需要更新：

```text
docs/reference/net/udp.md
```

新增章节：

```text
## UDP KCP reliable transport

能力：
1. KCP reliable transport。
2. ORDERED / UNORDERED packet mode。
3. request/reply。
4. Fury/custom codec。

配置示例：

UdpClientConfig config = new UdpClientConfig();
config.setReliabilityMode(UdpReliabilityMode.KCP);
config.setDefaultPacketOrder(UdpPacketOrder.ORDERED);
config.setKcpUnorderedLaneCount(4);
UdpClient client = new UdpClient(0, config);

client.sendOrdered(remote, packet);
client.sendUnordered(remote, frame);
```

也要补充兼容说明：

```text
KCP mode 只能和 KCP mode 对端通信，不能和 LEGACY_RXUP 对端混用。
```

---

## 19. 建议提交拆分

建议拆成多个 PR / commit：

```text
1. docs: add UdpClient KCP migration plan
2. transport: add UDP reliability mode and send options
3. transport-kcp: add KCP adapter and packet header
4. transport-kcp: add KCP session/lane scheduler
5. transport: wire KCP engine into UdpClient
6. transport: support KCP request/reply
7. transport: support unordered KCP lanes
8. test: add KCP transport loss/reorder tests
9. docs: document KCP UDP transport usage
```

---

## 20. 最小可交付版本定义

MVP 必须满足：

```text
1. `UdpClientConfig.reliabilityMode = KCP` 可启动。
2. 两个本地 `UdpClient` 可通过 KCP 互发普通对象。
3. `sendOrdered` 保序。
4. `sendUnordered` 使用至少 2 条 lane，允许跨 lane 先到先投递。
5. `request/reply` 正常。
6. close 不阻塞 EventLoop。
7. codec encode/decode 失败不泄漏。
8. UDP 写出仍统一经过 `Sockets.writeUdp`。
9. 现有 LEGACY_RXUP 测试不回退。
```

---

## 21. 最终建议

第一期不要试图一次性把旧 `UdpClient` 全量删除。建议采用“双引擎 + 配置开关”的迁移方式：

```text
UdpClient facade
  LEGACY_RXUP engine：保持现有行为
  KCP engine：新增可靠有序/无序能力
```

有序与无序的关键不要放在修改 KCP 内部，而是放在 **lane 设计**：

```text
ORDERED = 单 ordered KCP lane。
UNORDERED = 多 unordered KCP lane，跨 lane 不排序。
```

这样既符合 KCP 的协议模型，也能把改动控制在 rxlib 的 transport 层，后续可逐步把 Nameserver、UdpHolePunch、Hybrid 控制面迁移到 KCP mode。