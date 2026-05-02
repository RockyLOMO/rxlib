# Udp2raw 场景4固定入口 + sourceEndpoint 透传 + NAT-A UDP 重构计划

## 背景

`docs/test/SocksScene.md` 中已有两个相关链路：

```text
udp2raw场景:
socks5 client -> SocksServerProxy A(广域网ip a), udp associate
  -> udp2raw client(广域网ip a)
  -> udp2raw server(广域网ip b)
  -> SocksServerProxy B(广域网ip b), udp -> dest

场景4:
ShadowsocksClient(广域网ip c)
  -> ShadowsocksServer(广域网ip a)
  -> SocksServerProxy A(广域网ip a), udp associate
  -> SocksServerProxy B(广域网ip b), udp -> dest
```

本次目标是把场景4的 A->B UDP 代理链路通过 udp2raw 固定入口承载：

```text
socks5client / ss client
  -> RSS Client 侧 SocksProxyServer
  -> SocksUdpUpstream 把原始 client sourceEndpoint 写入 udp2raw 数据包
  -> udp2raw client
  -> Internet
  -> udp2raw server
  -> RSS Server 侧 SocksProxyServer UDP 固定入口
  -> 解包，按包内 client sourceEndpoint 创建独立 UDP NAT channel
  -> dest
```

核心不是按 `DatagramPacket.sender()` 建业务 session，而是按 udp2raw 包内透传的 `client sourceEndpoint` 建 server 侧出站 UDP channel。`DatagramPacket.sender()` 只代表 udp2raw client/peer 的公网地址，用于回包写回 udp2raw 对端、NAT rebinding 更新和安全校验。

## Git 历史 review 结论

### 1. 旧版 `Udp2rawHandler` 已经具备 sourceEndpoint 透传雏形

历史提交 `30ffa37f67daf5c93bf03463d01e0e80c4f3cded` 的 `Udp2rawHandler` 里，client mode 大致流程是：

```java
InetSocketAddress clientEp = sender;
UnresolvedEndpoint dstEp = UdpManager.socks5Decode(inBuf);
SocksContext e = SocksContext.getCtx(clientEp, dstEp);
server.raiseEvent(server.onUdpRoute, e);
Upstream upstream = e.getUpstream();
UnresolvedEndpoint upDstEp = upstream.getDestination();

header.writeShort(STREAM_MAGIC);
header.writeByte(STREAM_VERSION);
UdpManager.encode(header, clientEp);
UdpManager.encode(header, upDstEp);
outBuf.addComponents(true, header, inBuf.retain());
relay.writeAndFlush(new DatagramPacket(outBuf, udp2rawClient));
```

server mode 则是：

```java
UnresolvedEndpoint clientEp = UdpManager.decode(inBuf);
UnresolvedEndpoint dstEp = UdpManager.decode(inBuf);
SocksContext e = SocksContext.getCtx(clientEp.socketAddress(), dstEp);
e.udp2rawClient = sender;
server.raiseEvent(server.onUdpRoute, e);
relay.writeAndFlush(new DatagramPacket(inBuf.retain(), upDstAddr));
```

这套思路和本次需求一致：A 侧把原始 client sourceEndpoint 写入 udp2raw 包，B 侧解包后按这个 sourceEndpoint 构造 `SocksContext`，并把 UDP 发往真实目的地。

但旧实现仍然直接复用同一个 `relay` channel 发往 upstream/dest，因此如果这个 `relay` 是 server 固定入口，就会让所有 client 共享同一个本地 UDP 源端口，无法保证 NAT A。

### 2. 当前实现已经改成 per UDP relay channel 状态模型

当前 `Udp2rawHandler` 的类注释明确写着它是 per-client UDP relay handler，安装在 `Socks5CommandRequestHandler` 为 `UDP_ASSOCIATE` 创建的 dedicated UDP channel 上。状态也放在 channel attr 上：

- `ATTR_CLIENT_ADDR`
- `ATTR_CTX_MAP`
- `ATTR_ROUTE_MAP`

当前 `Socks5CommandRequestHandler` 的 `UDP_ASSOCIATE` 分支会动态创建一个 UDP channel，并把该动态端口返回给 SOCKS5 client。这个模型适合标准 SOCKS5 UDP，也适合 client 本地侧 udp2raw wrap channel；但不适合 server 侧固定 udp2raw UDP 入口。

### 3. 现有 reuseport 基础设施可直接复用

`Sockets.bindChannels(Bootstrap, bindAddress, config)` 已经统一处理 UDP `SO_REUSEPORT` 多 bind：Linux epoll + 固定 `InetSocketAddress` + 固定 port 时，可以按 `reusePortBindCount` 绑定多个 UDP channel。

所以本次不需要在 `Udp2rawHandler` 内硬写 epoll 逻辑，只需要让 server fixed entry 使用固定 UDP bind address，并走 `Sockets.bindChannels(Bootstrap, ...)`。

## 修正后的核心设计判断

### 1. session key 应优先使用包内 sourceEndpoint，而不是 UDP sender

上一版计划里把 `DatagramPacket.sender()` 当成 primary `sourceEndpoint`，这对普通 UDP 入口成立，但对场景4不够准确。

在目标链路中，B 侧看到的 UDP sender 是：

```text
udp2raw client / A 侧公网地址
```

而真正需要 NAT A 隔离的是：

```text
socks5client / ss client 的原始 sourceEndpoint
```

因此 fixed entry 的业务 session key 应为：

```java
Udp2rawSessionKey {
    InetSocketAddress udp2rawPeer;      // DatagramPacket.sender(), 用于防碰撞和回包
    InetSocketAddress clientSource;     // 包内 decode 出来的 clientEp/sourceEndpoint
}
```

如果确认同一个 udp2raw peer 下不会出现重复的 client private endpoint，也可以先用 `clientSource` 作为主 key；更稳妥的第一版建议用 `(udp2rawPeer, clientSource)` 复合 key，避免两个不同 udp2raw peer 都上报 `192.168.1.2:50000` 这类私网 sourceEndpoint 时互相覆盖。

### 2. fixed entry 只做入口和回包，不直接出站到 dest

server fixed UDP entry 的本地端口是固定的，且可能有 `SO_REUSEPORT` 多个 channel。它不能直接发往真实 `dest`：

```text
错误:
clientA -> fixedEntry:40000 -> dest
clientB -> fixedEntry:40000 -> dest
```

真实目标看到的都是 `server:40000`，NAT A 语义丢失。

正确做法：

```text
clientA -> fixedEntry:40000 -> natChannelA:51001 -> dest
clientB -> fixedEntry:40000 -> natChannelB:51002 -> dest
```

回包：

```text
dest -> natChannelA:51001 -> fixedEntry:40000 -> udp2rawPeerA -> clientA
dest -> natChannelB:51002 -> fixedEntry:40000 -> udp2rawPeerB -> clientB
```

即：

- 出站到真实目标或 upstream relay：必须用 per-client NAT channel。
- 回 udp2raw client：必须用 fixed entry channel，这样对端看到的仍是固定入口 port。

### 3. wire 协议沿用旧实现语义

udp2raw 数据包结构继续保持：

```text
STREAM_MAGIC short
STREAM_VERSION byte
clientSourceEndpoint encoded by UdpManager.encode
upstream/destination endpoint encoded by UdpManager.encode
payload
```

client 侧：

- 从本地 SOCKS5 UDP 包解析 `dstEp`。
- 用 `EndpointTracer.UDP.find(sender)` 或 `sender` 得到原始 `clientSourceEndpoint`。
- 将 `clientSourceEndpoint + upstream/dstEp + payload` 写入 udp2raw 包。

server 侧：

- 从 udp2raw 包 decode `clientSourceEndpoint`。
- decode `dstEp`。
- 以 `(udp2rawPeer, clientSourceEndpoint)` 找或建 `Udp2rawSession`。
- 用 session 的 NAT channel 发往 `dstEp` 或 route 后的 upstream。

## 推荐架构

```text
RSS Client / Proxy A side
=========================
SOCKS5 UDP client packet
  -> SocksProxyServer A UDP_ASSOCIATE dynamic relay
  -> Udp2rawClientRelayHandler
       decode socks5 dstEp
       resolve original sourceEndpoint = EndpointTracer.UDP.find(sender) ?: sender
       route if needed, get upstream/dstEp
       encode udp2raw header: sourceEndpoint + upstream/dstEp
  -> udp2rawClient fixed remote address

Internet
========
udp2raw client -> udp2raw server

RSS Server / Proxy B side
=========================
SocksProxyServer B starts fixed UDP entry
  -> SO_REUSEPORT N entry channels bound to same udp2raw UDP port
  -> Udp2rawServerEntryHandler
       verify magic/version
       decode sourceEndpoint
       decode dstEp
       key = (udp2rawPeer sender, sourceEndpoint)
       session = manager.getOrCreate(key)
       session.handleClientPacket(entryChannel, udp2rawPeer, sourceEndpoint, dstEp, payload)
  -> Udp2rawSession.natChannel, one per key
       route to direct dest or SocksUdpUpstream
       write UDP from independent local port
  -> dest

Response
========
dest / upstream relay
  -> session.natChannel
  -> Udp2rawSessionHandler wraps response with sourceEndpoint
  -> session.lastEntryChannel.write(DatagramPacket(to udp2rawPeer))
  -> udp2raw client
  -> RSS Client local UDP relay
  -> socks5/ss client
```

## 类拆分建议

### 1. `Udp2rawCodec`

抽出纯协议编解码，避免 client/server handler 重复操作 ByteBuf。

职责：

```java
final class Udp2rawCodec {
    static final short STREAM_MAGIC = -21264;
    static final byte STREAM_VERSION = 1;

    static boolean hasMagic(ByteBuf in);
    static Udp2rawFrame decodeRequest(ByteBuf in);
    static ByteBuf encodeRequest(ByteBufAllocator alloc,
                                 InetSocketAddress clientSource,
                                 UnresolvedEndpoint dstEp,
                                 ByteBuf payload);
    static ByteBuf encodeResponse(ByteBufAllocator alloc,
                                  InetSocketAddress clientSource,
                                  ByteBuf payload);
}
```

`Udp2rawFrame` 至少包含：

```java
final class Udp2rawFrame {
    InetSocketAddress clientSource;
    UnresolvedEndpoint destination;
    ByteBuf payload;
}
```

### 2. `Udp2rawClientRelayHandler`

保留当前 client mode 的核心逻辑，但明确它只跑在 RSS Client / Proxy A 本地的动态 UDP relay channel 上。

核心逻辑：

```text
local app -> SOCKS5 UDP packet
  -> decode dstEp
  -> clientSource = EndpointTracer.UDP.find(sender) ?: sender
  -> route / SocksContext
  -> target = config.udp2rawClient 或 SocksUdpUpstream selected relay
  -> encode sourceEndpoint + dstEp + payload
  -> write to udp2raw target

udp2raw server response
  -> decode clientSource
  -> write payload back to clientSource socketAddress
```

注意：client 侧仍可继续使用现有 per-relay channel 状态，因为这是本地 `UDP_ASSOCIATE` 创建的独立 UDP channel，不是 server fixed shared entry。

### 3. `Udp2rawServerEntryManager`

归属于 `SocksProxyServer`，只在 server mode 启动。

建议字段：

```java
final class Udp2rawServerEntryManager implements AutoCloseable {
    private final SocksProxyServer server;
    private final ConcurrentMap<Udp2rawSessionKey, Udp2rawSession> sessions;
    private final List<Channel> entryChannels;

    void start();
    Udp2rawSession getOrCreate(Channel entryChannel,
                               InetSocketAddress udp2rawPeer,
                               InetSocketAddress clientSource);
    void closeSession(Udp2rawSessionKey key, String reason);
    void close();
}
```

职责：

- 根据 `udp2rawListenAddress` 或 `listenAddress/listenPort` 解析 fixed UDP bind address。
- 用 `Sockets.udpBootstrap(config, ...)` 创建 UDP bootstrap。
- 用 `Sockets.bindChannels(bootstrap, fixedBindAddress, config)` 绑定 fixed entry，多 bind 自动走 reuseport。
- 每个 entry channel 安装 `Udp2rawServerEntryHandler`。
- 管理 `(udp2rawPeer, clientSource) -> Udp2rawSession`。
- session idle 清理。
- server dispose 时关闭 entry channels 和 sessions。

不要把 fixed entry channel 注册进现有 `udpRelayRegistry`，因为 `udpRelayRegistry` 是 `localPort -> Channel`，同一个 reuseport 端口会覆盖。

### 4. `Udp2rawServerEntryHandler`

挂在 fixed entry channel 上，尽量无业务状态。

处理流程：

```text
DatagramPacket in from udp2rawPeer
  -> if magic/version invalid: drop
  -> clientSource = decode first endpoint
  -> dstEp = decode second endpoint
  -> sessionKey = (udp2rawPeer, clientSource)
  -> session = manager.getOrCreate(entryChannel, udp2rawPeer, clientSource)
  -> session.updatePeer(entryChannel, udp2rawPeer)
  -> session.handleClientPacket(payload, dstEp)
```

entry channel 级别不要保存：

- 单一 `ATTR_CLIENT_ADDR`
- 业务 `routeMap`
- 业务 `ctxMap`
- 多 client redundant peers

### 5. `Udp2rawSession`

每个 `(udp2rawPeer, clientSource)` 一个 session。

建议字段：

```java
final class Udp2rawSession implements AutoCloseable {
    final Udp2rawSessionKey key;
    final SocksProxyServer server;

    volatile Channel entryChannel;
    volatile InetSocketAddress udp2rawPeer;
    volatile Channel natChannel;
    volatile long lastActiveMillis;

    final ConcurrentMap<InetSocketAddress, SocksContext> ctxMap;
    final ConcurrentMap<UnresolvedEndpoint, SocksContext> routeMap;
    final ConcurrentMap<UnresolvedEndpoint, RouteInitState> routeInitMap;
}
```

职责：

- 首包创建独立 `natChannel`，绑定随机本地 UDP 端口。
- 所有发往真实目标或 upstream relay 的数据都从 `natChannel` 出站。
- 所有回 client 的数据都从 `entryChannel` 出站到 `udp2rawPeer`。
- `natChannel.attr(SocksContext.SOCKS_SVR).set(server)`，方便复用 upstream init / traffic 逻辑。
- `SocksUdpUpstream.initChannelAsync(natChannel)` 继续可用。

### 6. `Udp2rawSessionHandler`

挂在 per-client NAT channel 上。

处理来自 dest/upstream 的回包：

```text
DatagramPacket from dest/upstream
  -> sc = session.ctxMap.get(sender)
  -> direct response: socks5Encode(payload, sender)
  -> socks upstream response: payload already has SOCKS5 UDP header
  -> encode udp2raw response header with session.key.clientSource
  -> entryChannel.writeAndFlush(packet to session.udp2rawPeer)
```

注意：回包只需要带 `clientSourceEndpoint`，因为 client 侧收到后要把 payload 投递回本地 app；如果沿用当前实现，也可以保留只 encode clientSource 的 response 格式。

## SocksProxyServer 接入点

### server mode 启动 fixed entry

建议语义：

```text
config.isEnableUdp2raw() && config.getUdp2rawClient() == null
  -> RSS Server / Proxy B: start fixed udp2raw UDP entry

config.isEnableUdp2raw() && config.getUdp2rawClient() != null
  -> RSS Client / Proxy A: keep client relay handler, send to udp2rawClient
```

`SocksProxyServer` 构造完成 TCP bind 后：

```java
if (config.isEnableUdp2raw() && config.getUdp2rawClient() == null) {
    udp2rawEntryManager.start();
}
```

`dispose()`：

```java
tryClose(udp2rawEntryManager);
// then close dynamic udpRelayRegistry
```

### UDP_ASSOCIATE 分支保持兼容

`Socks5CommandRequestHandler` 的动态 UDP relay 创建逻辑继续保留，用于：

- 普通 SOCKS5 UDP。
- RSS Client / Proxy A 本地 udp2raw client relay。
- upstream fallback 标准 SOCKS5 UDP_ASSOCIATE。

但当 server fixed entry 已启用时，B 侧不依赖 `UDP_ASSOCIATE` 返回动态端口给 udp2raw client。udp2raw client 永远发固定 entry address。

## 配置建议

在 `SocksConfig` 中增加：

```java
private InetSocketAddress udp2rawListenAddress;
private int udp2rawSessionIdleSeconds;
private int udp2rawMaxSessions;
```

默认规则：

- `udp2rawListenAddress == null`：使用 `listenAddress` 的 host + `listenPort` 作为 UDP fixed entry。
- `udp2rawSessionIdleSeconds <= 0`：复用 `udpReadTimeoutSeconds`。
- `udp2rawMaxSessions <= 0`：使用安全默认值，例如 `65536` 或按配置不限制，但建议公网场景必须有上限。

示例：

```java
SocksConfig serverConf = new SocksConfig(Sockets.newAnyEndpoint(1080));
serverConf.setEnableUdp2raw(true);
serverConf.setUdp2rawClient(null); // server mode
serverConf.setUdp2rawListenAddress(Sockets.newAnyEndpoint(4096));
serverConf.setReusePortBindCount(0); // Linux epoll 下按 CPU/reactor 估算
serverConf.setUdp2rawSessionIdleSeconds(300);
serverConf.setUdp2rawMaxSessions(65536);
```

## 与 SocksUdpUpstream / port hopping / RPC relay group 的关系

server fixed entry 不直接做 port hopping。port hopping 仍属于 `SocksUdpUpstream`。

但 channel 参数必须从 fixed entry channel 改成 session NAT channel：

```java
routeContext.getUpstream().initChannelAsync(session.natChannel);
InetSocketAddress relayAddr = socksUdpUpstream.selectUdpRelayAddress(session.natChannel);
```

这样每个 client sourceEndpoint 在 B 侧都有独立 upstream session group / relay group 视图，符合 NAT A。

如果 route 直连：

```text
session.natChannel -> dest
```

如果 route 到下一跳 SOCKS5 upstream：

```text
session.natChannel -> selected SOCKS5 UDP relay port
```

`ctxMap` 必须是 session 级别：

```text
sessionA.ctxMap[dest or relayAddr] = contextA
sessionB.ctxMap[dest or relayAddr] = contextB
```

不要把这些状态放在 fixed entry channel attr 上。

## 与 UDP 多倍发送/压缩的关系

### client -> server

RSS Client / Proxy A 的动态 UDP relay channel 仍是 per-client 的，可以继续用现有 UDP redundant/compress handler。它把完整 udp2raw payload 发给固定 server entry。

### server fixed entry

fixed entry 是 shared channel，不能复用全局 peer 列表：

- 不使用 `ATTR_REDUNDANT_CLIENT_PEER` 存多个 client。
- 不在 entry channel 上维护 per-client RDNT 去重窗口。
- 如果需要对 server -> udp2rawPeer 的回包做多倍发送，应在 `Udp2rawSession.writeToPeer` 中按 session 独立处理。

### server -> dest/upstream

走 `session.natChannel`，可以继续复用现有 UDP write limit、upstream port hopping、RPC relay group。

## 路由初始化建议

第一阶段可以沿用旧版简单 route 模型：

```text
首包 decode dstEp
  -> SocksContext.getCtx(clientSource, dstEp)
  -> publishEvent(onUdpRoute)
  -> upstream.initChannelAsync(session.natChannel)
  -> write
```

但建议同步引入 `SocksUdpRelayHandler` 已经成熟的 pending route init 模型：

- `routeMap`：ready route。
- `routeInitMap`：正在初始化 route。
- `PendingPacket`：首包和短时间 burst 缓存。
- 限制 pending 包数和字节数。
- `publishEvent` 不阻塞 EventLoop。

session 级别缓存 key 建议为：

```text
(clientSourceEndpoint, dstEp) within one Udp2rawSession
```

因为一个 client sourceEndpoint 可以访问多个 UDP 目标。

## 实施步骤

### P0：文档和测试基线

- 保留当前标准 SOCKS5 UDP 测试。
- 明确新增场景4 udp2raw 链路测试名，例如：
  - `socks5UdpRelay_udp2raw_fixedEntry_sourceEndpointNatA_e2e`
  - `shadowsocksUdpRelay_udp2raw_fixedEntry_sameDestinationDifferentClientPorts_e2e`
- 验证旧版 `30ffa37` 的 wire 语义：request 包带 `clientSource + dstEp`，response 包带 `clientSource`。

### P1：协议 codec 和 client handler 清理

- 新增 `Udp2rawCodec`。
- 将当前 `Udp2rawHandler` 的 client mode 抽为 `Udp2rawClientRelayHandler` 或保留方法但明确只服务 client relay。
- client 侧继续从 `EndpointTracer.UDP.find(sender)` 或 `sender` 获取原始 sourceEndpoint。
- client 侧写包必须包含：`sourceEndpoint + dstEp + payload`。

### P2：server fixed entry 生命周期

- 新增 `Udp2rawServerEntryManager`。
- `SocksProxyServer` server mode 启动 fixed entry。
- fixed entry 走 `Sockets.bindChannels(Bootstrap, fixedUdpAddress, config)`，获得 reuseport 多入口。
- fixed entry 不注册到 `udpRelayRegistry`。

### P3：按包内 sourceEndpoint 创建 session

- 新增 `Udp2rawSessionKey(udp2rawPeer, clientSource)`。
- 新增 `Udp2rawSession`。
- fixed entry 收包后 decode `clientSource`，用 session key get/create。
- `DatagramPacket.sender()` 只更新 `session.udp2rawPeer` 和 `session.entryChannel`。
- session idle 清理。

### P4：per-client NAT channel

- 每个 session 首包创建独立 `natChannel`，bind `0`。
- `natChannel` 出站到 dest/upstream。
- `natChannel` 收到回包后，通过 fixed entry wrap 回 `udp2rawPeer`。
- 确保回包的 UDP 源端口仍是 fixed entry port。

### P5：upstream 和 port hopping 兼容

- `SocksUdpUpstream.initChannelAsync` 参数改为 `session.natChannel`。
- `selectUdpRelayAddress` / `snapshotUdpRelayAddresses` 都用 session channel。
- upstream relay add/remove 清理 session 的 ctxMap，不清理 fixed entry channel attr。
- RX RPC relay group fallback 保持现有策略。

### P6：保护、指标和压测

新增指标：

- `socks.udp2raw.entry.bind.count`
- `socks.udp2raw.session.active.count`
- `socks.udp2raw.session.create.count`
- `socks.udp2raw.session.close.count,reason=idle|entry-close|nat-close|max-sessions|error`
- `socks.udp2raw.drop.count,reason=bad-magic|bad-version|bad-endpoint|max-sessions|route-init-fail|nat-not-ready|write-fail`
- `socks.udp2raw.write.count,direction=to-peer|to-dest|from-dest`

保护：

- `udp2rawMaxSessions`。
- bad packet 快速丢弃。
- per-route pending bytes/packets 上限。
- session idle timeout。
- fixed entry bind 失败要关闭已绑定 channel，避免半启动。

## 测试计划

### 单元测试

- `Udp2rawCodec`：request encode/decode，response encode/decode。
- `Udp2rawSessionKey`：不同 udp2raw peer + 相同 private clientSource 不冲突。
- `Udp2rawServerEntryManager`：same key 命中同一 session，different clientSource 创建不同 session。
- bad magic/version/drop。

### 集成测试

1. **基础 fixed entry**

```text
socks5client -> RSS Client SocksProxyServer -> udp2raw client
  -> udp2raw server fixed entry -> RSS Server SocksProxyServer -> UDP echo
```

验证 echo 正常返回。

2. **NAT A**

两个不同 client sourceEndpoint 访问同一个 UDP echo server：

```text
clientA:portA -> dest:53
clientB:portB -> dest:53
```

验证 echo server 看到两个不同的 server UDP 源端口。

3. **fixed entry 回包源端口**

验证 udp2raw client 收到的回包源端口仍是 server fixed entry port，而不是 session NAT channel port。

4. **reuseport**

`reusePortBindCount=2/4`，多个 fixed entry channel 同端口绑定，session 状态不因 channel 切换而丢失。

5. **port hopping / RPC relay group**

开启 `SocksUdpUpstream` port hopping，验证 `session.natChannel` 可以轮换多个 upstream UDP relay port，并且回包正确归属到对应 session。

6. **场景4 shadowsocks 链路**

```text
ShadowsocksClient -> ShadowsocksServer -> SocksProxyServer A
  -> udp2raw client -> udp2raw server fixed entry -> SocksProxyServer B -> dest
```

覆盖：

- DNS 远程解析路径。
- same destination different client ports。
- UDP redundant/compress 开启后不串包。

## 风险点

1. **session key 选择错误**：如果按 UDP sender 建 session，多个原始 client 会被合并；必须按包内 sourceEndpoint 建 session，并建议带上 udp2rawPeer 防碰撞。
2. **fixed entry 直接出站**：会破坏 NAT A，必须通过 per-client NAT channel。
3. **reuseport 多 channel 状态分散**：业务状态必须在 manager/session，不放 entry channel attr。
4. **回包端口错误**：回 udp2raw client 必须从 fixed entry channel 写，不从 natChannel 写。
5. **ByteBuf 生命周期**：entry -> natChannel、natChannel -> entry 都跨 channel，必须明确 retain/release。
6. **UDP redundant 状态串流**：fixed entry 不能使用全局 peer list；需要 per-session 处理。
7. **私网 sourceEndpoint 冲突**：不同 udp2raw peer 可能携带相同私网 sourceEndpoint，建议 session key 包含 udp2rawPeer。

## 验收标准

- 场景4链路能通过 udp2raw client/server 固定入口转发 UDP。
- RSS Client/Proxy A 发出的 udp2raw request 包内包含原始 client sourceEndpoint。
- RSS Server/Proxy B fixed entry 按包内 sourceEndpoint 创建独立 NAT channel。
- 同一 dest 下，不同 client sourceEndpoint 在 dest 侧表现为不同 server UDP 源端口。
- udp2raw client 收到回包时，server 源端口仍是 fixed entry port。
- Linux epoll + fixed port 下可以启用 `SO_REUSEPORT` 多入口。
- 标准 SOCKS5 UDP 和 client 本地 udp2raw relay 行为不回退。
- 与 UDP port hopping、RX RPC relay group fallback、UDP compress/redundant 兼容。
