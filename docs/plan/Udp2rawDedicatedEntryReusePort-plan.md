# Udp2raw 场景4自定义隧道 + 固定入口 + sourceEndpoint NAT-A 重构计划

## 背景

目标链路来自 `docs/test/SocksScene.md` 的场景4变体：

```text
socks5client / ss client
  -> RSS Client 侧 SocksProxyServer
  -> Udp2rawUpstream / Udp2rawTunnelUpstream
       把原始 client sourceEndpoint 写入自定义 udp2raw 数据包
  -> udp2raw client
  -> Internet
  -> udp2raw server
  -> RSS Server 侧 SocksProxyServer 的 UDP 固定入口
  -> 解包，按包内 client sourceEndpoint 创建独立 UDP NAT channel
  -> dest
```

这次重写 `udp2raw` 后，A->B 之间不再需要兼容标准 SOCKS5 UDP wire protocol，也不再需要为了 udp2raw 数据面创建标准 SOCKS5 `UDP_ASSOCIATE` TCP control。udp2raw 本身的目标就是减少单端口/单五元组被限速，定位与 UDP port hopping 类似，因此本方案不再要求兼容端口跳跃场景。

保留要求：

- 支持 UDP 多倍发包。
- 支持 UDP 包压缩。
- RSS Server 侧固定 UDP 入口支持 `SO_REUSEPORT` 多 bind。
- RSS Server 侧按包内原始 `client sourceEndpoint` 创建独立出站 UDP channel，保证 NAT-A。

## 关键结论

### 1. A->B 不再走标准 SOCKS5 UDP_ASSOCIATE

上一版方案里还保留了较多 `SocksUdpUpstream` / SOCKS5 UDP relay 兼容描述。这里修正为：

```text
本地 app -> RSS Client 入口
  仍然可以是 SOCKS5 UDP 或 Shadowsocks UDP

RSS Client -> RSS Server
  改为自定义 udp2raw tunnel，不再是标准 SOCKS5 UDP upstream
```

因此建议新增一个独立 upstream 类型，例如：

```java
final class Udp2rawUpstream extends Upstream {
    private final AuthenticEndpoint serverEndpoint;
    private final SocksRpcContract rpcFacade;
    private final Udp2rawTunnelOptions options;
}
```

不要继续把 A->B 这段命名为 `SocksUdpUpstream`，否则后续实现容易把标准 SOCKS5 `UDP_ASSOCIATE`、UDP relay port、port hopping、claim/reset relay 等旧逻辑又带回来。

### 2. 不再兼容 UDP port hopping

`udp2raw` fixed entry + 多倍发包/压缩已经是新的 A->B UDP 隧道能力。它和 port hopping 都是为了降低被单端口/单链路限速的概率，二者同时实现会增加状态复杂度：

- port hopping 需要多个远端 relay port 和多个 control/session holder。
- udp2raw fixed entry 要求所有包打固定入口 port。
- 两者目标冲突，收益重叠。

所以本方案明确：

```text
Udp2raw tunnel 开启时，不启用 UDP port hopping。
Udp2rawUpstream 不调用 SocksUdpUpstream.selectUdpRelayAddress。
Udp2rawUpstream 不申请远端 UDP relay group。
Udp2rawUpstream 不走 claimUdpRelay/resetUdpRelay。
```

### 3. 鉴权不走 SOCKS5 UDP TCP control

A->B 既然不是标准 SOCKS5 UDP，就没有必要再做 SOCKS5 UDP_ASSOCIATE 的 TCP control 鉴权。

推荐做法：

```text
控制面鉴权：复用 SocksRpcContract / RSS RPC token
数据面鉴权：使用 RPC 下发的 tunnelId/sessionSecret，UDP 包头只带轻量 tunnel/session 字段
```

也就是：

- RSS Client 侧本地入口的 SOCKS5/SS 鉴权照旧。
- RSS Client -> RSS Server 的 udp2raw tunnel 不再逐 UDP client 重跑 SOCKS5 用户名密码鉴权。
- A 侧通过 `SocksRpcContract` 向 B 侧打开/刷新 udp2raw tunnel，B 侧基于 RPC token 验证 A 是否可信。
- B 侧把 tunnel 与 traffic user / connection tag / route policy 绑定；数据包只带 `tunnelId + connId + seq + flags` 等轻量字段。

如果没有 `SocksRpcContract` facade，本方案建议第一阶段直接失败或仅允许测试用静态 PSK，不再回退第三方标准 SOCKS5 UDP。

## 鉴权设计建议

### 控制面：复用 `SocksRpcContract`

现有 `SocksRpcContract` 已经有 `rpcToken()`、`isValidRpcToken()`、`requireValidRpcToken()` 这套 token 校验入口。建议新增 udp2raw tunnel 控制面方法：

```java
Udp2rawOpenResult openUdp2rawTunnel(Udp2rawOpenRequest request, String token);
boolean heartbeatUdp2rawTunnel(String tunnelId, String token);
boolean closeUdp2rawTunnel(String tunnelId, String token);
```

`Udp2rawOpenRequest` 建议包含：

```java
class Udp2rawOpenRequest {
    String clientId;                       // RSS Client 标识
    InetSocketAddress clientBindAddress;   // A 侧期望出口，可空
    int protocolVersion;
    int maxSessions;
    int idleTimeoutSeconds;
    Udp2rawCompressConfig compress;
    Udp2rawRedundantConfig redundant;
    String connectionTag;                  // 可选，用于复用现有 connectionTagResolver
    String trafficUser;                    // 可选，用于流量统计绑定
}
```

`Udp2rawOpenResult` 建议包含：

```java
class Udp2rawOpenResult {
    boolean success;
    boolean supported;
    String tunnelId;                       // 128-bit 随机，base64url/hex
    byte[] sessionSecret;                  // 用于数据面轻量 MAC，可选
    InetSocketAddress udpEntryAddress;     // B 侧 fixed entry 地址
    long expireAtMillis;
    Udp2rawCapabilities capabilities;
}
```

B 侧收到 `openUdp2rawTunnel` 后：

- `SocksRpcContract.requireValidRpcToken(token)`。
- 根据 `connectionTag` 或 `trafficUser` 绑定用户/流量统计。
- 创建 `Udp2rawTunnelContext`，放入 `tunnelId -> context`。
- 返回 fixed entry address 和 sessionSecret。

### 数据面：轻量 tunnel 头，不做 SOCKS5 鉴权

为避免每个 UDP 包都塞用户名密码或完整鉴权对象，数据面建议只带轻量字段：

```text
magic              2 bytes
version            1 byte
headerLength       1 byte
flags              2 bytes
headerType         1 byte    // DATA / PING / CLOSE / RESET
sessionId/tunnelId 8~16 bytes
connId             8 bytes   // clientSource 的短 ID，可由 A 侧生成
packetSeq          8 bytes   // 多倍发包去重/乱序统计
clientSource       variable  // 首包/NEW_CONN 必带，后续可省略
sourceAddr         variable  // response 可选，表示真实回包来源，或 payload 内带 SOCKS5 header
destination        variable  // request 首包/路由变化时必带
authTag            0/8/16 bytes, configurable
payload            bytes
```

推荐 flags：

```text
NEW_CONN       首包或 NAT rebinding 后携带完整 clientSource/destination
HAS_CLIENT     包头携带 clientSource
HAS_DST        包头携带 destination
HAS_SRC        response 包头携带真实 source endpoint
COMPRESSED     payload 已压缩
REDUNDANT      多倍发送副本
CLOSE_CONN     主动关闭 connId/session
AUTH_TAG       包尾或头部携带轻量 MAC
```

### authTag 模式

考虑性能，建议提供三档：

```java
enum Udp2rawAuthMode {
    RPC_SESSION_ONLY,      // 只依赖 RPC 下发的随机 tunnelId + peer 绑定，性能最高
    FIRST_PACKET_MAC,      // NEW_CONN 首包带 authTag，后续按 connId + peer 信任，推荐默认
    EVERY_PACKET_MAC       // 每包带 64/128-bit MAC，安全更高，CPU 更重
}
```

推荐默认：`FIRST_PACKET_MAC`。

原因：

- 比 SOCKS5 用户密码随包携带轻得多。
- 能防止未授权 client 随便创建 server NAT channel。
- 后续包只校验 `tunnelId + connId + udp2rawPeer + seq window`，路径更轻。
- 如果公网攻击风险较高，可以切到 `EVERY_PACKET_MAC`。

`authTag` 算法建议：

- Java 8 可先用 `HmacSHA256` 截断 8 或 16 字节，简单可靠。
- 后续如果追求 PPS，可换 SipHash/BLAKE3 keyed hash，封装在 `Udp2rawAuthenticator`，不影响协议层。

## 自定义 packet 格式建议

因为协议可以自由发挥，不再沿用旧版 `STREAM_MAGIC + clientEp + dstEp + payload` 的固定格式。建议分成 request/response 统一 frame。

### DATA request

```text
magic/version/headerLength/flags/headerType
sessionId
connId
packetSeq
[clientSource]   // NEW_CONN 或 HAS_CLIENT 时出现
[destination]    // NEW_CONN 或 HAS_DST 时出现
[authTag]
payload          // 原始 UDP payload，或 SOCKS5 UDP payload 去 header 后的业务 payload
```

A 侧第一次看到某个原始 `clientSource -> dstEp` 时：

- 生成或查找 `connId`。
- 发送 `NEW_CONN | HAS_CLIENT | HAS_DST`。
- B 侧用 `(tunnelId, connId)` 建 `Udp2rawSession`。
- B 侧 session 内记录 `clientSource` 和 `dstEp`。

后续同一路由包：

- 只带 `tunnelId + connId + packetSeq + payload`。
- 不再每包重复编码 endpoint，降低头部开销。

如果同一个 `clientSource` 访问新的 `dstEp`，可以：

- 新建 `connId`；或
- 复用 session 但创建新的 routeId。

第一阶段建议简单：`connId = clientSource + dstEp` 的逻辑连接 ID，一个 `connId` 对应一个 clientSource/dstEp。

### DATA response

两种可选方式：

#### 方案 A：response 头携带真实 source endpoint

```text
sessionId
connId
packetSeq
[sourceAddr]     // dest 回包来源
payload
```

A 侧收到后组装本地 SOCKS5 UDP response：

```text
SOCKS5 UDP header(sourceAddr) + payload -> clientSource
```

#### 方案 B：payload 内直接放 SOCKS5 UDP response

B 侧回包时把 `sourceAddr` 编成 SOCKS5 UDP header，payload 就是完整 SOCKS5 UDP response。

第一阶段建议用方案 A：协议语义更清晰，而且 A 侧统一负责把 response 转成本地入口需要的格式。

## NAT-A 设计

B 侧 fixed entry 只负责收发 tunnel 包，不直接访问 `dest`。

```text
错误：
A peer -> B fixedEntry:40000 -> dest

正确：
A peer -> B fixedEntry:40000 -> Udp2rawSession.natChannel:randomPort -> dest
```

B 侧 session key：

```java
class Udp2rawSessionKey {
    String tunnelId;
    long connId;
}
```

session 内记录：

```java
class Udp2rawSession {
    String tunnelId;
    long connId;
    InetSocketAddress udp2rawPeer;      // DatagramPacket.sender()
    InetSocketAddress clientSource;     // 包内 sourceEndpoint
    UnresolvedEndpoint destination;
    Channel entryChannel;               // 最近收到该 conn 包的 fixed entry channel
    Channel natChannel;                 // 独立出站 UDP channel
    long lastActiveMillis;
    long lastSeq;
}
```

`udp2rawPeer` 允许更新，用于 NAT rebinding；但建议规则是：

- `FIRST_PACKET_MAC/EVERY_PACKET_MAC` 验证通过后允许更新。
- `RPC_SESSION_ONLY` 下只允许在短时间窗口内从同 ASN/IP 段迁移，或默认不允许迁移。

## 多倍发包支持

### 发送位置

多倍发包应在 udp2raw tunnel 层实现，而不是 fixed entry channel 全局 peer 实现。

```text
A -> B request 多倍：Udp2rawClientRelayHandler / Udp2rawTunnelWriter
B -> A response 多倍：Udp2rawSession.writeToPeer
```

### 去重 key

使用自定义头里的：

```text
tunnelId + connId + packetSeq + direction
```

B 侧 fixed entry 收 request 时先做去重，重复包直接丢弃，不创建重复 NAT write。

A 侧收到 response 时同样按 `tunnelId + connId + packetSeq + direction` 去重，避免给本地 client 重复投递。

### 发送策略

沿用现有 `UdpRedundantConfig` 的语义即可：

- `multiplier`
- `intervalMicros`
- `adaptive`
- `min/max multiplier`
- 按目的地规则可选保留，但规则作用在 `destination` 或 tunnel。

注意：多倍发包复制的是完整 encoded udp2raw packet，不要在每个副本重新分配大对象。

## 压缩支持

### 压缩层级

推荐顺序：

```text
业务 payload
  -> optional compress
  -> udp2raw header flags 标记 COMPRESSED
  -> optional authTag
  -> redundant duplicate send
```

接收顺序：

```text
校验 magic/version/session
  -> authTag 校验
  -> redundant 去重
  -> decompress
  -> route / write NAT channel / deliver local client
```

### 压缩粒度

压缩只针对 tunnel payload，不压缩固定头：

- request payload：原始 UDP payload。
- response payload：dest 回包 payload。

`sourceEndpoint/destination/sessionId/seq` 等头字段不参与压缩，便于快速路由、鉴权和去重。

配置复用 `UdpCompressConfig`：

- `enabled`
- `codec`
- `minPayloadBytes`
- `minSavingsBytes`
- `minSavingsRatio`
- `adaptiveBypass`

## 类设计

### 1. `Udp2rawCodec`

负责自定义头编解码，不再绑定 SOCKS5 UDP header。

```java
final class Udp2rawCodec {
    static Udp2rawFrame decode(ByteBuf in);
    static ByteBuf encode(ByteBufAllocator alloc, Udp2rawFrame frame, ByteBuf payload);
}
```

`Udp2rawFrame`：

```java
final class Udp2rawFrame {
    int version;
    int flags;
    Udp2rawFrameType type;
    long sessionHi;
    long sessionLo;
    long connId;
    long packetSeq;
    InetSocketAddress clientSource;
    UnresolvedEndpoint destination;
    InetSocketAddress sourceAddress;
    ByteBuf authTag;
}
```

### 2. `Udp2rawUpstream`

替代 A->B 的 `SocksUdpUpstream`。

职责：

- 通过 `SocksRpcContract.openUdp2rawTunnel` 获取 tunnel。
- 维护 `clientSource/dstEp -> connId` 映射。
- 将本地 UDP 包写成自定义 udp2raw DATA request。
- 不打开 SOCKS5 UDP_ASSOCIATE。
- 不使用 port hopping。

### 3. `Udp2rawClientRelayHandler`

运行在 RSS Client 侧本地 UDP relay channel。

职责：

```text
本地 SOCKS5/SS UDP 包
  -> decode dstEp
  -> clientSource = EndpointTracer.UDP.find(sender) ?: sender
  -> route 得到 Udp2rawUpstream
  -> Udp2rawUpstream.write(clientSource, dstEp, payload)
```

它仍然需要兼容本地客户端协议，因为本地 app 可能仍是 SOCKS5 UDP 或 Shadowsocks UDP；但它不再把 A->B 当标准 SOCKS5 UDP。

### 4. `Udp2rawServerEntryManager`

RSS Server 侧启动 fixed UDP entry。

职责：

- `Sockets.bindChannels(udpBootstrap, fixedBindAddress, config)`。
- 支持 `SO_REUSEPORT` 多 channel。
- 管理 `tunnelId -> Udp2rawTunnelContext`。
- 管理 `(tunnelId, connId) -> Udp2rawSession`。
- session idle 清理。

### 5. `Udp2rawServerEntryHandler`

挂在 fixed entry channel 上。

流程：

```text
DatagramPacket from udp2rawPeer
  -> decode frame
  -> validate tunnelId / authTag / seq window
  -> if duplicate: drop
  -> tunnelContext.getOrCreateSession(connId, clientSource, destination)
  -> session.updatePeer(entryChannel, udp2rawPeer)
  -> session.writeToDestination(payload)
```

### 6. `Udp2rawSession`

每个 `(tunnelId, connId)` 独立 NAT channel。

职责：

- 创建 `natChannel`，bind `0`。
- 出站到 `destination`。
- 收到 dest response 后封装 DATA response，从 fixed entry channel 回 `udp2rawPeer`。
- 按 session 独立处理 response 多倍发包。

## SocksProxyServer 接入点

### RSS Client / Proxy A

本地仍然可以暴露 SOCKS5 UDP 或 Shadowsocks UDP。

但 route 到 B 时：

```java
proxyA.onUdpRoute.replace((s, e) -> {
    e.setUpstream(new Udp2rawUpstream(e.getFirstDestination(), udp2rawConfig, rpcFacade));
});
```

### RSS Server / Proxy B

server mode：

```java
if (config.isEnableUdp2raw() && config.getUdp2rawClient() == null) {
    udp2rawEntryManager.start();
}
```

B 侧不需要为 udp2raw 数据面执行 SOCKS5 `UDP_ASSOCIATE`。

## 配置建议

```java
private InetSocketAddress udp2rawListenAddress;
private int udp2rawSessionIdleSeconds = 300;
private int udp2rawMaxSessions = 65536;
private Udp2rawAuthMode udp2rawAuthMode = Udp2rawAuthMode.FIRST_PACKET_MAC;
private boolean udp2rawRequireRpc = true;
private UdpRedundantConfig udp2rawRedundant;
private UdpCompressConfig udp2rawCompress;
private int udp2rawBadAuthThreshold = 8;
private int udp2rawBadAuthFuseSeconds = 30;
```

默认建议：

- `udp2rawRequireRpc=true`：没有 RPC facade 就不开启生产 udp2raw tunnel。
- `udp2rawAuthMode=FIRST_PACKET_MAC`：性能/安全折中。
- `udp2rawRedundant` 默认关闭，按链路质量开启。
- `udp2rawCompress` 默认按现有压缩阈值开启或按配置开启。
- `udpPortHopping` 与 `udp2raw` 同时配置时，udp2raw 优先生效并忽略 port hopping。

## 实施步骤

### P0：明确边界

- 删除计划中“兼容 UDP port hopping / SOCKS5 UDP upstream fallback”的要求。
- 明确 A->B 是 `Udp2rawUpstream`，不是 `SocksUdpUpstream`。
- 明确 A->B 数据面不需要 SOCKS5 UDP_ASSOCIATE TCP control。

### P1：RPC 控制面

- `SocksRpcCapabilities` 增加 `UDP2RAW_TUNNEL`。
- `SocksRpcContract` 增加 `openUdp2rawTunnel / heartbeatUdp2rawTunnel / closeUdp2rawTunnel`。
- RSS Server 侧实现 tunnel context 管理。
- RSS Client 侧 `Udp2rawUpstream` 初始化时打开 tunnel。

### P2：自定义 codec

- 新增 `Udp2rawCodec`、`Udp2rawFrame`、`Udp2rawFrameType`、`Udp2rawAuthMode`。
- request/response 不再沿用 SOCKS5 UDP wire header。
- endpoint 使用现有 `UdpManager.encode/decode` 或新建更紧凑的 endpoint codec。

### P3：client tunnel writer

- 新增 `Udp2rawUpstream`。
- 维护 `clientSource + dstEp -> connId`。
- 支持压缩、seq、authTag、多倍发包。
- 本地 response 转回 SOCKS5/SS UDP 格式。

### P4：server fixed entry

- 新增 `Udp2rawServerEntryManager`。
- fixed entry 走 `Sockets.bindChannels`，支持 reuseport。
- fixed entry 不注册到 `udpRelayRegistry`。
- entry handler 完成 tunnel 校验、去重、session 分派。

### P5：per-client NAT channel

- 每个 `(tunnelId, connId)` 创建独立 `natChannel`。
- `natChannel` 出站到 dest。
- response 从 fixed entry channel 回 peer。
- 验证 NAT-A：同一 dest 下不同 clientSource 在 dest 侧看到不同 server UDP 源端口。

### P6：多倍发包和压缩

- request/response 两个方向都支持 redundant send。
- request/response 两个方向都支持 compress。
- 去重发生在解压前。
- 压缩发生在多倍复制前。

### P7：指标和保护

新增指标：

- `socks.udp2raw.tunnel.open.count,result=success|fail`
- `socks.udp2raw.tunnel.active.count`
- `socks.udp2raw.session.active.count`
- `socks.udp2raw.session.create.count`
- `socks.udp2raw.session.close.count,reason=idle|close|error|max-sessions`
- `socks.udp2raw.drop.count,reason=bad-magic|bad-version|unknown-tunnel|auth-fail|duplicate|max-sessions|bad-dst|write-fail`
- `socks.udp2raw.redundant.duplicate.drop.count`
- `socks.udp2raw.compress.count,result=compressed|bypass|fail`

保护：

- tunnel idle timeout。
- session idle timeout。
- max tunnels / max sessions。
- seq replay window。
- per-peer packet rate limit，可选。
- bad auth fail 计数熔断。

## 实施进度（2026-05-04）

本次已落地可编译、可验证的 **server fixed entry + 自定义 frame 基础层 + client tunnel writer + tunnel 层压缩/冗余发送**。新链路通过 `Udp2rawUpstream` 接管 RSS Client/SS 本地入口到 RSS Server fixed entry 的数据面；旧 `Udp2rawHandler` 目前仅是现存历史代码，不再作为本计划的兼容目标。

### 边界确认（2026-05-05）

- udp2raw dedicated fixed entry 后续不再以 SOCKS5 UDP relay/replay 兼容作为验收目标。
- A->B 数据面只保留本计划的 `Udp2rawUpstream -> fixed entry -> per-client NAT channel` 自定义 frame 方式。
- 不再为了 udp2raw 新链路回退标准 SOCKS5 `UDP_ASSOCIATE`、远端 UDP relay port、port hopping、claim/reset relay。
- 旧 `Udp2rawHandler` 属于现存历史路径，本计划后续实现和测试重点不再由它驱动。

### 已完成

- P1 RPC 控制面基础：
  - `SocksRpcCapabilities` 增加 `UDP2RAW_TUNNEL` 能力位。
  - `SocksRpcContract` 增加 `openUdp2rawTunnel / heartbeatUdp2rawTunnel / closeUdp2rawTunnel` 默认方法。
  - `RssRpcApp` 与 `RssClient.ForwardingSocksRpcContract` 已转发新增 RPC 方法。
  - `SocksProxyServer` server mode 下自动启动 fixed UDP entry，并通过 RPC 打开 tunnel。
- P2 自定义 codec：
  - 新增 `Udp2rawCodec`、`Udp2rawFrame`、`Udp2rawFrameType`、`Udp2rawAuthMode`。
  - 新增 `Udp2rawAuthenticator`，当前使用 Java 8 可用的 `HmacSHA256` 截断 tag。
  - `authTag` 已从热路径 `byte[]` 改为 `ByteBuf`：decode 使用 `readSlice`，sign 返回 pooled heap `ByteBuf`，encode 从 `ByteBuf` 复制 tag，避免每包 `new byte[]`。
  - 新增 `Udp2rawSessionKey` 与 `Udp2rawSeqWindow`，按 `sessionHi/sessionLo/connId/seq` 做基础隔离和去重。
- P3 client tunnel writer：
  - 新增 `Udp2rawUpstream`，通过 `SocksRpcContract.openUdp2rawTunnel` 打开 tunnel，不再执行标准 SOCKS5 `UDP_ASSOCIATE` control。
  - 维护 `clientSource + dstEp -> connId`，首包携带 `NEW_CONN | HAS_CLIENT | HAS_DST | AUTH_TAG`，后续同一路由只带轻量 session/conn/seq header。
  - SOCKS5 UDP relay 已接入 request/response：本地 SOCKS5 payload -> 自定义 DATA request，fixed entry DATA response -> 本地 SOCKS5 UDP response。
  - Shadowsocks UDP relay 已接入 request/response：SS 明文 payload -> 自定义 DATA request，fixed entry DATA response -> SS address header response。
  - `Udp2rawUpstream` 不调用 `SocksUdpUpstream.selectUdpRelayAddress`，不申请远端 UDP relay group，不走 claim/reset relay。
- P4 server fixed entry 基线：
  - 新增 `Udp2rawServerEntryManager`，fixed entry 通过 `Sockets.bindChannels(...)` 绑定，继承 Linux epoll `SO_REUSEPORT` 多 bind 能力。
  - fixed entry 状态集中在 manager/tunnel context，不注册到 `udpRelayRegistry`。
- P5 per-client NAT channel 基线：
  - 新增 `Udp2rawServerEntryHandler`、`Udp2rawTunnelContext`、`Udp2rawSession`。
  - 每个 `(tunnel session, connId)` 创建独立 UDP `natChannel` 并 `bind(0)` 出站到 dest。
  - dest response 通过 fixed entry channel 回 udp2raw peer，回包源端口保持 fixed entry port。
- P6 多倍发包和压缩基础闭环：
  - 新增 `Udp2rawPayloadSupport`，统一处理 tunnel payload LZ4 压缩/解压、encoded frame 冗余复制发送。
  - request/response 两个方向均支持 payload 压缩：压缩发生在 authTag 计算前，去重发生在解压前。
  - request/response 两个方向均支持 redundant send：复制完整 encoded udp2raw frame，副本使用 `retainedDuplicate()`，不重复编码大 payload。
  - redundant 支持静态 multiplier、分目的地规则解析、intervalMicros 延迟副本；延迟副本有 pending 上限保护。
  - `Udp2rawUpstream` SOCKS5 与 Shadowsocks 两条本地入口都接入 request 压缩/冗余，client 侧 response 解压和 response seq 去重已接入。
  - `Udp2rawSession.writeToPeer` 已接入 response 压缩/冗余，server fixed entry 已支持 request 压缩解包和 request seq 去重。
  - open tunnel 时根据 client request 与 server config 协商 `compress/redundant` capabilities，避免单端误启用。
- P7 指标和保护基础：
  - 已记录 tunnel open/active、session create/close/active、drop、duplicate drop 等基础指标。
  - 已新增 `socks.udp2raw.compress.count`、`socks.udp2raw.redundant.copy.count`、`socks.udp2raw.redundant.delayed.drop.count` 等基础指标。
  - 已有 maxSessions、tunnel idle cleanup、seq duplicate drop、auth-fail drop、UDP write pending 过载保护。
  - 已补齐 NAT rebinding 基础安全策略：已有 `(tunnel, connId)` 会话的 `udp2rawPeer` 发生变化时，必须携带有效 `authTag` 才允许更新 peer；`FIRST_PACKET_MAC` 后续普通包不能无鉴权迁移 peer。
  - 已补齐 bad auth 熔断：同一 peer 在短窗口内达到 `udp2rawBadAuthThreshold` 后，按 `udp2rawBadAuthFuseSeconds` 短时阻断，降低公网 fixed entry 被无效 MAC 探测拖垮的风险。

### 已验证

```text
mvn -pl rxlib "-Dtest=Udp2rawCodecTest,Udp2rawAuthenticatorTest,Udp2rawFixedEntryIntegrationTest" test
mvn -pl rxlib "-Dtest=Udp2rawHandlerTest" test
mvn -pl rxlib "-Dtest=SocksProxyServerIntegrationTest#socks5UdpRelay_udp2raw_chained_e2e" test
mvn -pl rxlib "-Dtest=SocksProxyServerIntegrationTest#socks5UdpRelay_udp2rawUpstream_fixedEntry_e2e" test
mvn -pl rxlib "-Dtest=SocksProxyServerIntegrationTest#shadowsocksUdpRelay_udp2rawUpstream_fixedEntry_e2e" test
mvn -pl rxlib "-Dtest=Udp2rawCodecTest,Udp2rawAuthenticatorTest,Udp2rawFixedEntryIntegrationTest,Udp2rawHandlerTest,SocksProxyServerIntegrationTest#socks5UdpRelay_udp2rawUpstream_fixedEntry_e2e+shadowsocksUdpRelay_udp2rawUpstream_fixedEntry_e2e+socks5UdpRelay_udp2raw_chained_e2e" test
mvn -pl rxlib "-Dtest=Udp2rawFixedEntryIntegrationTest#fixedEntryCompressesResponseAndDropsRedundantRequest,SocksProxyServerIntegrationTest#socks5UdpRelay_udp2rawUpstream_fixedEntry_compressAndRedundant_e2e" test
mvn -pl rxlib "-Dtest=SocksProxyServerIntegrationTest#shadowsocksUdpRelay_udp2rawUpstream_fixedEntry_compressAndRedundant_e2e" test
mvn -pl rxlib "-Dtest=Udp2rawCodecTest,Udp2rawAuthenticatorTest,Udp2rawFixedEntryIntegrationTest,Udp2rawHandlerTest,SocksProxyServerIntegrationTest#socks5UdpRelay_udp2rawUpstream_fixedEntry_e2e+socks5UdpRelay_udp2rawUpstream_fixedEntry_compressAndRedundant_e2e+shadowsocksUdpRelay_udp2rawUpstream_fixedEntry_e2e+shadowsocksUdpRelay_udp2rawUpstream_fixedEntry_compressAndRedundant_e2e+socks5UdpRelay_udp2raw_chained_e2e" test
mvn -pl rxlib "-Dtest=Udp2rawFixedEntryIntegrationTest" test
mvn -pl rxlib "-Dtest=Udp2rawCodecTest,Udp2rawAuthenticatorTest,Udp2rawFixedEntryIntegrationTest,SocksProxyServerIntegrationTest#socks5UdpRelay_udp2rawUpstream_fixedEntry_e2e+shadowsocksUdpRelay_udp2rawUpstream_fixedEntry_e2e" test
```

验证结论：

- 自定义 udp2raw request/response frame encode/decode 通过。
- FIRST_PACKET_MAC 鉴权、payload 篡改失败、seq 去重、session key 隔离通过。
- fixed entry E2E 通过：RPC open tunnel 后，DATA request 经 fixed UDP entry 到 echo dest，response 从 fixed entry port 返回。
- NAT-A 基线通过：两个不同 `client sourceEndpoint/connId` 访问同一 dest，dest 侧观察到两个不同 server UDP 源端口。
- `Udp2rawUpstream` SOCKS5 本地入口 E2E 通过：RSS Client 不再向 B 创建标准 SOCKS5 UDP relay，B 侧 `udpRelayRegistry` 保持 0。
- `Udp2rawUpstream` Shadowsocks 本地入口 E2E 通过：SS UDP 明文 address payload 经自定义 tunnel 到 fixed entry，response 可正确还原为 SS UDP response。
- P6 fixed entry 压缩/冗余通过：同一个 `tunnel/connId/seq` 的 request 冗余副本只写一次 dest；response 带 `FLAG_COMPRESSED | FLAG_REDUNDANT`，client 可解压还原。
- P6 SOCKS5 本地入口 E2E 通过：`Udp2rawUpstream` request 压缩/冗余、server fixed entry 解压/去重、response 压缩/冗余、client response 解压/去重闭环通过。
- P6 Shadowsocks 本地入口 E2E 通过：SS UDP 明文 address payload 经 udp2raw 压缩/冗余 tunnel 后可正确回显并还原为 SS UDP response。
- 旧 `Udp2rawHandler` UDP_ASSOCIATE 历史兼容测试曾通过；2026-05-05 后不再作为本计划新增能力的验收目标。
- 当前最终组合目标测试 17 个用例通过。
- P7 NAT rebinding 安全策略通过：同一 session 从新 peer 发来的无 MAC 包被拒绝，携带有效 MAC 的 rebind 包可继续写入 dest。
- P7 bad auth 熔断通过：同一 peer 达到 bad-auth 阈值后短时阻断，熔断窗口过期后有效 MAC 包可恢复。

### 未完成/下一步

- UDP redundant 自适应反馈尚未接入 tunnel 层：当前已支持静态倍率、分目的地规则与延迟副本；还未根据实际丢包率动态调整 multiplier。
- P1 控制面仍是基础 open/heartbeat/close；还未把 `connectionTag/trafficUser` 完整绑定到流量统计策略。
- per-peer rate limit 仍待补齐。

## 测试计划

### 单元测试

- `Udp2rawCodec` request/response encode/decode。
- `Udp2rawAuthenticator` FIRST_PACKET_MAC / EVERY_PACKET_MAC。
- `Udp2rawRedundant` 去重 key：`tunnelId + connId + seq + direction`。
- `Udp2rawCompress` 压缩/绕过/解压失败。
- `Udp2rawSessionKey` 不同 connId 不串。

### 集成测试

1. **RPC tunnel open**

```text
RSS Client -> SocksRpcContract.openUdp2rawTunnel -> RSS Server
```

验证 token 错误失败，token 正确返回 tunnelId/sessionSecret/fixed entry。

2. **基础 UDP tunnel**

```text
socks5client -> RSS Client SocksProxyServer
  -> Udp2rawUpstream
  -> RSS Server fixed entry
  -> dest echo
```

验证正常返回。

3. **不走 SOCKS5 UDP_ASSOCIATE**

验证 A->B 没有创建标准 SOCKS5 UDP control channel，也没有远端 relay port。

4. **NAT-A**

两个不同 client sourceEndpoint 访问同一个 UDP echo server，dest 侧看到两个不同 server UDP 源端口。

5. **固定入口回包源端口**

udp2raw client 收到的 B 侧回包源端口仍是 fixed entry port。

6. **reuseport**

`reusePortBindCount=2/4`，多个 fixed entry channel 同端口绑定，session 状态不因 entry channel 切换而丢失。

7. **多倍发包**

开启 redundant multiplier，验证 dest 不收到重复业务包，client 不收到重复 response。

8. **压缩**

开启 compress，验证压缩包能被 server 正确解压，低收益包绕过压缩。

## 风险点

1. **无鉴权 fixed UDP entry 被滥用**：生产环境不建议 `NONE`，至少使用 RPC tunnel + FIRST_PACKET_MAC。
2. **每包 MAC CPU 成本**：默认 FIRST_PACKET_MAC，安全要求高时再切 EVERY_PACKET_MAC。
3. **connId 冲突**：connId 由 A 侧随机或 hash+random 生成，B 侧按 tunnel 隔离。
4. **NAT rebinding**：已要求 peer 更新必须携带有效 MAC；后续如要放宽到 IP 段迁移，必须另行补充策略和测试。
5. **多倍发包重复写 dest**：B 侧必须在写 NAT channel 前去重。
6. **压缩炸包/异常包**：解压必须有最大输出限制。
7. **fixed entry 多 channel 状态分散**：tunnel/session 状态必须在 manager，不放 entry channel attr。

## 验收标准

- A->B udp2raw tunnel 不依赖标准 SOCKS5 UDP_ASSOCIATE/TCP control。
- A->B 不启用、不兼容 UDP port hopping。
- tunnel 通过 `SocksRpcContract` 或 PSK 完成轻量鉴权。
- UDP 数据包使用自定义头，携带 tunnelId/connId/seq/flags，首包携带 clientSource/destination。
- RSS Server fixed entry 按 `(tunnelId, connId)` 创建独立 NAT channel。
- 同一 dest 下，不同 client sourceEndpoint 在 dest 侧表现为不同 server UDP 源端口。
- udp2raw client 收到回包时，server 源端口仍是 fixed entry port。
- 多倍发包 request/response 都支持，且去重正确。
- 压缩 request/response 都支持，且低收益包可绕过。
- Linux epoll + fixed UDP port 下可启用 `SO_REUSEPORT` 多入口。
