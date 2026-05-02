# Udp2raw 固定入口 + SO_REUSEPORT + per-client NAT-A UDP relay 重构计划

## 背景

`udp2raw` 场景下，client -> server 的 UDP 数据必须打到一个固定的 server 入口端口。当前 `Udp2rawHandler` 仍沿用了标准 SOCKS5 `UDP_ASSOCIATE` 的模型：每条 TCP control connection 动态创建一个 per-client UDP relay channel，并把该 channel 的随机本地端口通告给客户端。

这对标准 SOCKS5 UDP 是合理的，但对 `udp2raw` server 入口不合适：

```text
标准 SOCKS5 UDP:
client --UDP_ASSOCIATE--> server 动态分配 UDP relay port
client UDP 包发到动态 relay port

udp2raw:
client UDP 包必须发到 server 固定 udp2raw port
server 入口需要高吞吐，最好用 SO_REUSEPORT 多 bind
server 再按 client sourceEndpoint 独立创建出站 UDP channel，保证 NAT A
```

本计划目标是在保持 client 侧尽量少改的前提下，重构 server 侧 `udp2raw` UDP 数据面。

## 当前实现 review

### 1. `Udp2rawHandler` 目前是 per-relay-channel 状态模型

当前 `Udp2rawHandler` 是 `@Sharable` handler，但状态放在 Netty `Channel.attr` 上：

- `ATTR_CLIENT_ADDR`：当前 UDP relay 绑定/确认的 client 地址。
- `ATTR_CTX_MAP`：upstream `InetSocketAddress -> SocksContext`，用于回包识别。
- `ATTR_ROUTE_MAP`：目标地址 `UnresolvedEndpoint -> SocksContext`，用于路由缓存。

类注释也明确写着它安装在 `Socks5CommandRequestHandler` 为 `UDP_ASSOCIATE` 创建的 dedicated UDP channel 上，即“一条 TCP control connection 对应一个 UDP relay channel”。

这个模型适合标准 SOCKS5 UDP；但如果把同一个 `Udp2rawHandler` 直接挂到固定入口端口上，会有几个问题：

- `ATTR_CLIENT_ADDR` 只能表达一个 client，无法承载多个 client sourceEndpoint。
- `ATTR_CTX_MAP/ATTR_ROUTE_MAP` 是 channel 级别；`SO_REUSEPORT` 多入口 channel 下，同一固定端口会有多份分散状态。
- 当前 server mode 的出站包直接用入口 `relay` channel 发往真实目标或上游 relay；如果入口是固定端口，会导致所有 client 共享同一个本地 UDP 源端口，破坏 NAT A 语义。
- 入口 channel 上的 redundant peers / client lock 不能直接复用，否则 shared entry 可能把不同 client 的 peer 状态混在一起。

### 2. `Socks5CommandRequestHandler` 当前总是动态创建 UDP relay

`UDP_ASSOCIATE` 分支当前逻辑是：

1. 根据 TCP control 本地地址选择 UDP bind address。
2. `Sockets.udpBootstrap(config, ...)` 创建 UDP channel。
3. 绑定到端口 `0` 或指定本地 IP 的端口 `0`。
4. 安装 `Udp2rawHandler.DEFAULT` 或 `SocksUdpRelayHandler.DEFAULT`。
5. 把动态绑定出的 UDP 端口返回给 SOCKS5 client。

这个路径会继续保留给标准 SOCKS5 UDP 和 client 本地侧使用；但 server 侧 `udp2raw` 固定入口不应该依赖该动态端口模型。

### 3. `SocksProxyServer` 已有 UDP relay registry，但不适合固定入口多 bind

`SocksProxyServer.registerUdpRelay(Channel relay)` 以 `localPort -> Channel` 注册动态 relay。对于 `SO_REUSEPORT` 同端口多 channel，这个 map 会被同一个 port 覆盖，不适合作为 fixed udp2raw entry 的状态容器。

因此 fixed entry 应该有独立生命周期和独立集合，例如：

```java
private final Udp2rawEntryManager udp2rawEntryManager;
```

不要把多个 fixed entry channel 混进现有 `udpRelayRegistry`。

### 4. `Sockets` 已有可复用的 SO_REUSEPORT 机制

`Sockets.bindChannels(Bootstrap, bindAddress, config)` 已经统一处理 UDP `SO_REUSEPORT` 多 bind：

- 仅 Linux epoll 可用。
- 仅固定 `InetSocketAddress` 且 port > 0 时启用。
- `reusePortBindCount == 0` 时按 CPU / reactor 线程数估算。
- `bindCount > 1` 时设置 `EpollChannelOption.SO_REUSEPORT=true`。

所以新设计不需要在 `Udp2rawHandler` 中重复判断 epoll，只要 server fixed entry 使用固定端口并走 `Sockets.bindChannels(Bootstrap, ...)` 即可。

## 设计目标

- server 侧开启 `udp2raw` 时，`SocksProxyServer` 启动一个专用 UDP fixed entry。
- fixed entry 支持 `SO_REUSEPORT` 多 channel 绑定，提升高并发 UDP 收包吞吐。
- fixed entry 只负责：
  - 验证/解包 `udp2raw` header；
  - 按 `client sourceEndpoint` 分派到 session；
  - 把 session 回包通过固定入口端口发回 client。
- 每个 client sourceEndpoint 独立创建一个 NAT channel：
  - 出站发往真实 UDP destination 或下一跳 SOCKS5 UDP relay；
  - 接收真实 destination / upstream 回包；
  - 保证不同 client 在服务端表现为不同 UDP 源端口，即 NAT A。
- client 侧尽量不改，继续把本地 SOCKS5 UDP 包 wrap 成 `udp2raw` 后发送到 `config.udp2rawClient`。
- 与现有 `SocksUdpUpstream`、UDP port hopping、UDP 多倍发送、UDP 压缩、RX RPC relay group 兼容。
- 标准 SOCKS5 UDP 逻辑不受影响；未开启 server fixed entry 时保持当前行为。

## 非目标

- 不把 fixed udp2raw entry 做成标准 SOCKS5 `UDP_ASSOCIATE` 返回端口。
- 不在 fixed entry channel 上复用 per-client `ATTR_CLIENT_ADDR`。
- 不让多个 client 共享同一个出站 UDP NAT channel。
- 不改变 `udp2raw` wire header 的基本语义。
- 第一阶段不做跨 client / 跨 entry channel 的复杂负载迁移；只做按 sourceEndpoint 建 session。

## 推荐架构

```text
client local SOCKS5 UDP relay
  -> wrap udp2raw
  -> server fixed udp2raw UDP port, SO_REUSEPORT N channels
  -> Udp2rawEntryHandler
  -> Udp2rawEntryManager.sessions[sourceEndpoint]
  -> Udp2rawSession.natChannel, one per client sourceEndpoint
  -> real UDP destination / SocksUdpUpstream selected relay

real UDP destination / upstream relay
  -> Udp2rawSession.natChannel
  -> wrap udp2raw response
  -> write by fixed entry channel
  -> client sourceEndpoint
```

### 关键点：入口 channel 和 NAT channel 分离

不要用 fixed entry channel 直接发往真实目标。

原因：fixed entry 的本地端口是所有 client 共享的固定端口，如果直接出站：

```text
clientA -> fixedEntry:40000 -> target
clientB -> fixedEntry:40000 -> target
```

真实目标看到的都是同一个 server UDP 源端口，NAT A 会退化。

新方案应变为：

```text
clientA -> fixedEntry:40000 -> natChannelA:51001 -> target
clientB -> fixedEntry:40000 -> natChannelB:51002 -> target
```

回包时：

```text
target -> natChannelA:51001 -> fixedEntry:40000 -> clientA
target -> natChannelB:51002 -> fixedEntry:40000 -> clientB
```

这样 client 仍只看到 server fixed udp2raw port，真实目标则看到每个 client 独立的服务端 UDP 源端口。

## 新增/调整配置建议

在 `SocksConfig` 中增加 server fixed entry 配置，保持默认关闭或复用现有 `enableUdp2raw` 语义：

```java
/** udp2raw server fixed UDP entry bind address; null means reuse listenAddress host + listenPort. */
private InetSocketAddress udp2rawListenAddress;

/** fixed entry idle session timeout; default reuse udpReadTimeoutSeconds. */
private int udp2rawSessionIdleSeconds;

/** max active sourceEndpoint sessions; 0 means unlimited or use safe default. */
private int udp2rawMaxSessions = 0;
```

建议语义：

```text
config.isEnableUdp2raw() && config.getUdp2rawClient() == null
  -> server mode: SocksProxyServer startup binds dedicated fixed udp2raw UDP entry

config.isEnableUdp2raw() && config.getUdp2rawClient() != null
  -> client mode: local UDP_ASSOCIATE channel 继续按当前逻辑 wrap 到 udp2rawClient
```

`udp2rawListenAddress == null` 时，默认使用：

- TCP `listenAddress` 的 host；
- TCP `listenPort` 的 port；
- UDP 与 TCP 可共享同一个数字端口，不冲突。

如果不希望 UDP 与 TCP 同端口，可显式设置 `udp2rawListenAddress`。

## 核心类设计

### 1. `Udp2rawEntryManager`

归属于 `SocksProxyServer`，负责 fixed entry 生命周期和 session 管理。

建议字段：

```java
final class Udp2rawEntryManager implements AutoCloseable {
    private final SocksProxyServer server;
    private final ConcurrentMap<InetSocketAddress, Udp2rawSession> sessions = new ConcurrentHashMap<>();
    private final List<Channel> entryChannels = new CopyOnWriteArrayList<>();

    void start();
    Udp2rawSession getOrCreate(Channel entryChannel, InetSocketAddress sourceEndpoint, UnresolvedEndpoint clientEp);
    void touch(Udp2rawSession session);
    void closeSession(InetSocketAddress sourceEndpoint, String reason);
    void close();
}
```

职责：

- 用 `Sockets.udpBootstrap(config, ...)` 创建 fixed entry bootstrap。
- 用 `Sockets.bindChannels(bootstrap, udp2rawListenAddress, config)` 完成多 bind。
- 每个 entry channel pipeline 安装：
  - UDP 压缩/冗余相关基础 handler；
  - `Udp2rawEntryHandler`。
- 维护 `sourceEndpoint -> Udp2rawSession`。
- session idle 清理，关闭 session NAT channel。
- server dispose 时关闭所有 entry channel 和 session channel。

注意：fixed entry 多 channel 不能注册到现有 `udpRelayRegistry`，否则同端口多 channel 会互相覆盖。

### 2. `Udp2rawEntryHandler`

只挂在 fixed entry channel 上，尽量保持无状态。

处理 server inbound：

```text
DatagramPacket from client sourceEndpoint
  -> 校验 STREAM_MAGIC / STREAM_VERSION
  -> decode clientEp
  -> decode dstEp
  -> manager.getOrCreate(sourceEndpoint, clientEp)
  -> session.handleClientPacket(entryChannel, payload, clientEp, dstEp)
```

关键约束：

- 不使用 `ATTR_CLIENT_ADDR` 作为 channel 级 client lock。
- 不使用 entry channel 的 `ATTR_CTX_MAP/ATTR_ROUTE_MAP` 表示业务 route。
- 不把不同 client 的 redundant peer 写到同一个 entry channel attr。
- 支持同一 sourceEndpoint 后续包可能落到不同 reuseport channel：session 内保存 `lastEntryChannel`，每次收到包更新，回包优先从最近 entry channel 写回。

### 3. `Udp2rawSession`

每个 client sourceEndpoint 一个实例。

建议字段：

```java
final class Udp2rawSession implements AutoCloseable {
    final InetSocketAddress sourceEndpoint;
    volatile InetSocketAddress clientOriginAddr;
    volatile Channel entryChannel;
    volatile Channel natChannel;
    final ConcurrentMap<InetSocketAddress, SocksContext> ctxMap;
    final ConcurrentMap<UnresolvedEndpoint, SocksContext> routeMap;
    final ConcurrentMap<UnresolvedEndpoint, RouteInitState> routeInitMap;
    volatile long lastActiveMillis;
}
```

职责：

- 首包创建独立 `natChannel`，绑定端口 `0`。
- `natChannel` 只用于出站到真实 destination / upstream relay 和接收对应回包。
- `natChannel` pipeline 安装 `Udp2rawSessionHandler`。
- 在 `natChannel.attr(SocksContext.SOCKS_SVR)` 绑定 server，便于复用现有 upstream init / traffic 逻辑。
- `SocksUdpUpstream.initChannelAsync(natChannel)` 继续可用；UDP port hopping / RX RPC relay group 的 session group 也挂在这个 per-client channel 上。

### 4. `Udp2rawSessionHandler`

只挂在 per-client NAT channel 上。

处理 NAT channel inbound：

```text
DatagramPacket from real destination / upstream relay
  -> 用 session.ctxMap 按 sender 找 SocksContext
  -> 如果是 SocksUdpUpstream 回包，按现有 SOCKS5 UDP 语义处理
  -> wrap udp2raw response: STREAM_MAGIC + VERSION + client/source endpoint + payload
  -> 通过 session.entryChannel writeAndFlush 到 session.sourceEndpoint
```

注意：回 client 必须通过 fixed entry channel 写出，不能通过 NAT channel 写出，否则 client 看到的 server source port 会变成随机 NAT port，不再是 udp2raw fixed entry port。

## 路由初始化建议

当前 `Udp2rawHandler` 的 route 初始化比 `SocksUdpRelayHandler` 简单：命中陌生 dst 后直接创建 `SocksContext`、同步 `publishEvent`，然后 `initChannelAsync` 完成后发当前包。

建议这次重构顺便迁移到类似 `SocksUdpRelayHandler` 的异步 route init 模型：

- `routeMap`：ready route。
- `routeInitMap`：正在初始化的 route。
- `PendingPacket`：初始化期间短暂缓存首批 UDP 包。
- 限制：`MAX_PENDING_ROUTE_PACKETS = 32`、`MAX_PENDING_ROUTE_BYTES = 256 * 1024` 可沿用。
- `publishEvent(server.onUdpRoute, ctx)` 放到异步线程，避免阻塞 EventLoop。
- 完成后回到 session NAT channel 的 EventLoop flush pending。

这样可以避免高并发首包同时创建多份相同 route，也减少 EventLoop 被业务路由逻辑阻塞的风险。

## 与 UDP port hopping / RX RPC relay group 的关系

### 当前 port hopping 仍放在 `SocksUdpUpstream` 层

server fixed entry 不应该自己做 port hopping。它只负责入口和 session 分派。

当 route upstream 是 `SocksUdpUpstream` 时：

```java
InetSocketAddress relayAddr = socksUdpUpstream.selectUdpRelayAddress(session.natChannel);
```

选择出的 relay 地址注册到 `session.ctxMap`，用于 upstream 回包识别。

这样现有能力继续成立：

- 标准 SOCKS5 兼容模式：多条 UDP_ASSOCIATE control channel。
- 两端 rxlib 且支持 `SocksRpcContract`：优先使用 RX RPC relay group。
- 任意一端不支持扩展：按现有 fallback 回退标准 SOCKS5。

### ctxMap 必须是 session 级别

不要把 upstream relay 地址注册到 fixed entry channel 的 ctxMap。

正确关系：

```text
sourceEndpoint A -> Udp2rawSession A -> ctxMapA[upstreamRelay1] = routeContext
sourceEndpoint B -> Udp2rawSession B -> ctxMapB[upstreamRelay1] = routeContext
```

即使两个 session 都选择了同一个 remote relay port，回包也会先回到各自 NAT channel，因为 NAT channel 的本地源端口不同；不会需要 fixed entry 级别做 demux。

## 与 UDP 多倍发送的关系

当前 UDP 多倍发送依赖 channel 上的 peer/去重状态。fixed entry 是 shared channel，不能直接复用 per-client peer 集合，否则可能串 client。

建议：

1. client -> server 方向：client 侧保持当前逻辑；本地 per-client UDP relay channel 仍可安装现有 redundant/compress handler。
2. server fixed entry 收包：只接收、解包、按 sourceEndpoint 分派，不在 shared entry 上维护 client peer 集合。
3. server -> client 方向：如果需要多倍发包，优先在 `Udp2rawSession` 层实现 `writeToClient(session, buf)`，只对 `session.sourceEndpoint` 做重复发送，不能使用 entry channel 全局 peers。
4. server -> upstream / real destination 方向：走 `session.natChannel`，可继续复用 upstream port hopping 和已有 UDP 写限流。

第一阶段可以先保证“不开启 server->client 多倍发包时正确”；再补 per-session redundant writer。

## 兼容策略

### 标准 SOCKS5 UDP 不变

`Socks5CommandRequestHandler` 当前 `UDP_ASSOCIATE` 的动态 relay channel 逻辑保留，用于：

- 普通 SOCKS5 UDP client。
- client 本地侧 `udp2raw` wrap channel。
- upstream 标准 SOCKS5 fallback。

### udp2raw server entry 独立启动

`SocksProxyServer` 构造完成 TCP bind 后：

```java
if (config.isEnableUdp2raw() && config.getUdp2rawClient() == null) {
    udp2rawEntryManager.start();
}
```

`dispose()` 中先关闭 `udp2rawEntryManager`，再关闭动态 UDP relay registry。

### 旧 `Udp2rawHandler` 迁移方式

建议不要一次性硬改成完全不同语义。可以拆分：

- `Udp2rawCodec`：保留 magic/version、encode/decode clientEp/dstEp 工具方法。
- `Udp2rawClientRelayHandler`：保留当前 client mode 逻辑。
- `Udp2rawEntryHandler`：server fixed entry 新 handler。
- `Udp2rawSessionHandler`：server per-client NAT channel handler。

如果为了降低 diff，也可以短期保留 `Udp2rawHandler` 兼容旧测试，但新 server fixed entry 不再直接使用它。

## 实施步骤

### P0：配置和生命周期骨架

- `SocksConfig` 增加 `udp2rawListenAddress / udp2rawSessionIdleSeconds / udp2rawMaxSessions`。
- `SocksProxyServer` 增加 `Udp2rawEntryManager` 字段。
- server mode `enableUdp2raw && udp2rawClient == null` 时启动 fixed UDP entry。
- `dispose()` 完整关闭 entry channels 和 sessions。

### P1：fixed entry + session 分派

- 新增 `Udp2rawEntryManager`。
- 新增 `Udp2rawEntryHandler`。
- 使用 `Sockets.bindChannels(Bootstrap, fixedUdpAddress, config)` 绑定固定 UDP port。
- 以 `DatagramPacket.sender()` 作为 primary `sourceEndpoint` key。
- 每个 sourceEndpoint 创建 `Udp2rawSession`。
- session idle timeout 关闭 NAT channel 并从 map 移除。

### P2：per-client NAT channel

- 新增 `Udp2rawSessionHandler`。
- 每个 session 创建独立 UDP `natChannel`，绑定端口 `0`。
- client packet 通过 `natChannel` 发往真实目的地或 upstream relay。
- destination/upstream 回包由 `natChannel` 收到后，经 fixed entry channel wrap 回 client。
- 确保所有 ByteBuf retain/release 清晰，跨 channel write 时不泄漏。

### P3：路由初始化和 upstream 兼容

- 将 `routeMap/ctxMap/routeInitMap` 下沉到 `Udp2rawSession`。
- 复用或抽取 `SocksUdpRelayHandler` 的 pending route init 模型。
- `SocksUdpUpstream.initChannelAsync(session.natChannel)` 继续支持：
  - UDP port hopping；
  - lease pool；
  - RX RPC relay group；
  - fallback 标准 SOCKS5。
- upstream relay add/remove 回调需要能清理 session 级 ctxMap；不要只清理 fixed entry channel attr。

### P4：UDP 多倍发送适配

- 禁止 fixed entry channel 使用全局 `ATTR_REDUNDANT_CLIENT_PEER` 存多个 client peer。
- 如需 server->client 多倍发送，新增 per-session writer。
- 验证 RDNT 去重窗口不因 fixed entry 多 client 混用而串流。

### P5：指标、保护和测试

新增指标建议：

- `socks.udp2raw.entry.bind.count`
- `socks.udp2raw.session.active.count`
- `socks.udp2raw.session.create.count`
- `socks.udp2raw.session.close.count,reason=idle|entry-close|nat-close|error`
- `socks.udp2raw.route.init.count,result=success|fail|pending-overflow`
- `socks.udp2raw.drop.count,reason=bad-magic|bad-version|max-sessions|nat-not-ready|write-fail`
- `socks.udp2raw.write.count,direction=to-client|to-upstream`

保护建议：

- `udp2rawMaxSessions` 防止伪造 sourceEndpoint 打爆 NAT channel。
- `pending route` 包数/字节上限。
- session idle timeout 默认复用 `udpReadTimeoutSeconds`。
- fixed entry bind 失败时 server 构造失败或明确 warn 后关闭已绑定 channel，避免半启动。

## 测试计划

### 单元测试

- `Udp2rawEntryManager` fixed port bind：
  - Linux epoll + fixed port + `reusePortBindCount>1` 时 entry channel 数量正确。
  - port=0 时不启用 reuseport。
- sourceEndpoint session：
  - 同一个 sender 命中同一个 session。
  - 不同 sender 创建不同 session。
  - session idle 后关闭 NAT channel 并移除 map。
- bad packet：
  - magic/version 错误丢弃。
  - payload 不完整丢弃。

### 集成测试

- `client udp2raw -> server fixed entry -> UDP echo/DNS` 正常返回。
- 两个 client sourceEndpoint 并发访问同一个 echo server，echo server 看到两个不同的 server UDP 源端口，证明 NAT A。
- 开启 `reusePortBindCount=2/4` 后，多 entry channel 能同时收包，session 不串。
- 开启 `SocksUdpUpstream` port hopping 后，session NAT channel 能在多个 upstream UDP relay port 之间轮换。
- 对端不支持 `SocksRpcContract` 时 fallback 到标准 SOCKS5 UDP_ASSOCIATE。
- 开启 UDP 多倍发送时，不会把 clientA 的响应发给 clientB。

### 压测观察

- fixed entry PPS。
- session active 数。
- NAT channel 数与 fd 数。
- direct memory / pending write bytes。
- UDP drop reason 分布。
- upstream port hopping active hop 数。
- tail latency：p50/p95/p99。

## 风险点

1. **reuseport 多 entry channel 的状态一致性**：状态必须集中在 manager/session，不能放 entry channel attr。
2. **ByteBuf 生命周期**：entry channel -> natChannel、natChannel -> entry channel 都是跨 channel 写，必须明确 retain/release。
3. **EventLoop 线程亲和**：session 状态最好由 natChannel eventLoop 串行维护，manager 只做 sourceEndpoint map 和调度。
4. **client NAT rebinding**：sourceEndpoint 变化会创建新 session。第一阶段接受该行为；后续可按 decoded clientEp + token 做迁移。
5. **安全与资源上限**：fixed UDP port 暴露在公网，必须有 max sessions、idle、bad packet drop 指标。
6. **多倍发送串流风险**：shared entry 上不能放全局 peer 列表。
7. **现有 RPC 控制面清理**：upstream relay remove/add 回调要适配 session channel，不要只找旧的 per-relay channel attr。

## 验收标准

- 开启 `enableUdp2raw` 且 server mode 时，server 启动后固定 UDP port 可用；Linux epoll + fixed port 可按配置多 bind。
- client 侧无需知道 server 动态 UDP relay port，只配置固定 `udp2rawClient` 即可。
- 每个 client sourceEndpoint 在 server 侧有独立 NAT channel。
- 真实 UDP destination 看到不同 client 对应不同 server UDP 源端口。
- 回 client 的 UDP 包源端口仍是 fixed udp2raw entry port。
- 标准 SOCKS5 UDP 和未开启 udp2raw 的路径完全不变。
- 与 UDP port hopping / RX RPC relay group fallback 兼容。
- 高并发下无明显 ByteBuf leak、session 泄漏、跨 client 串包。
