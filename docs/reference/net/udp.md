# UDP 能力使用说明

本文整理 rxlib 当前 UDP 相关能力：基础 UDP channel、可靠消息、SOCKS/SS UDP relay、压缩、多倍发包、端口跳跃、udp2raw、FEC、打洞、Hybrid 传输、DNS/NTP 与 MTU/背压。当前项目按高性能模式维护，默认 Java 8，UDP 热点路径避免阻塞、反射、正则和无意义对象分配。

## 命名说明

`UdpProtect` 这个名字是“链路保护层”的临时总称，覆盖 FEC、多倍发包、去重、恢复等能力。但它确实偏泛，容易让人误解成安全加密或防火墙。

更准确的候选名：

| 名称 | 含义 | 建议 |
|---|---|---|
| `UdpResilience` | UDP 抗弱网能力，覆盖 FEC/冗余/恢复 | 最推荐 |
| `UdpLinkGuard` | 公网链路防护层，强调链路质量 | 可接受 |
| `UdpLossRecovery` | 强调丢包恢复，但覆盖不了压缩/限速 | 偏窄 |
| `UdpEnhance` | 增强层，语义太宽 | 不推荐 |

短期不建议立刻重命名生产类，避免刚落地的 pipeline API 和测试再大面积 churn。后续如果要统一命名，建议一次性把 `UdpProtect*` 迁移为 `UdpResilience*`，并保留一版兼容 facade。

## 能力总览

| 能力 | 当前核心类 | 主要场景 | 是否自定义协议 | 默认建议 |
|---|---|---|---|---|
| UDP Bootstrap/写出口保护 | `Sockets.udpBootstrap`，`Sockets.writeUdp`，`UdpBackpressure*` | 所有 DatagramChannel 出入口 | 否 | 统一走 `Sockets.writeUdp` |
| UDP 可靠消息/RPC | `org.rx.net.transport.UdpClient*` | 内部 UDP 请求响应、ACK、分片重组 | 是，`RXUP` frame | 内部控制消息可用 |
| SOCKS/SS UDP relay | `SocksUdpRelayHandler`，`SSUdpProxyHandler`，`SocksUdpUpstream` | SOCKS5 UDP ASSOCIATE、Shadowsocks UDP 转发 | SOCKS/SS 标准包 + 内部扩展 | 代理 UDP 主路径 |
| UDP 压缩 | `org.rx.net.socks.UdpCompress*` | 大包、可压缩 payload、代理链路省带宽 | 是，`UCMP` header | 只在自有代理两端开启 |
| UDP 多倍发包 | `org.rx.net.socks.UdpRedundant*`，新 pipeline 中为 `UdpProtectEncoder/Decoder` | 短时突发丢包、运营商抖动 | 是，`RDNT` 或 `UdpProtect` header | 默认 1，确认丢包再 2 |
| UDP FEC | `org.rx.net.udp.UdpProtect*` | 游戏 UDP 轻微随机丢包 | 是，`UdpProtect` header | 3:1 XOR FEC |
| UDP 端口跳跃 | `org.rx.net.socks.UdpPortHopping*`，`SocksUdpUpstream` | 单端口被限速、端口级抖动 | 控制面依赖 SOCKS/RPC relay | 默认关闭，确认端口限速再开 |
| udp2raw 隧道 | `Udp2rawHandler`，`Udp2rawUpstream`，`Udp2rawCodec` | 把 UDP 代理 payload 包装到自有隧道 | 是，udp2raw frame | 需要 RPC 能力协商 |
| UDP relay group/lease pool | `UdpRelayGroupManager`，`UdpRelayGroup*`，`UdpLeasePoolKey` | 批量开 relay、端口跳跃、复用 UDP lease | RPC 控制面 | 由 SOCKS upstream 自动使用 |
| UDP 打洞 | `org.rx.net.punch.UdpHolePunch*` | P2P NAT 穿透 | 使用 `UdpClient` 消息协议 | 适合同 NAT 友好网络 |
| Hybrid TCP/UDP | `org.rx.net.transport.hybrid.*` | 小包走 UDP，大包/失败回 TCP | 内部 Hybrid 消息 | 默认 TCP 兜底 |
| DNS/NTP/Nameserver | `Dns*`，`NtpClient`，`NameserverImpl` | DNS 查询、NTP、名称服务同步 | DNS/NTP 或内部包 | 按具体组件使用 |
| MTU/背压 | `Sockets.UdpFinalEgressGuardHandler`，`UdpBackpressure*` | 最终出口兜底 | 否 | 必须在所有 UDP header 后执行 |

## 包放置建议

通用 Datagram pipeline 能力应逐步放到 `org.rx.net.udp`：

```text
org.rx.net.udp
  UdpResilience / UdpProtect
  FEC
  多倍发包去重与统计
  压缩 codec 的通用 handler
  MTU / egress guard 辅助策略
```

保留在 `org.rx.net.socks` 的内容：

```text
SocksConfig 字段和兼容 getter/setter
SOCKS UDP relay 接入逻辑
udp2raw open/capability 协商
端口跳跃的 SOCKS/RPC relay 控制面
```

端口跳跃不是单纯 pipeline handler，它涉及 upstream session 数量、RPC relay group、补充 relay、控制面状态，所以短期继续放 `org.rx.net.socks` 更稳。后续可抽出端口选择策略到 `org.rx.net.udp`，接入层仍由 SOCKS 持有。

## Pipeline 顺序

通过 `Sockets.udpBootstrap(config, initChannel)` 创建 UDP channel 时，`SocksConfig` 会自动安装压缩、多倍发包和最终出口 guard。

当前旧优化链路的出站顺序：

```text
业务 / socks / udp2raw handler
  -> UdpCompressEncoder
  -> UdpRedundantEncoder
  -> UdpFinalEgressGuardHandler
  -> socket
```

当前旧优化链路的入站顺序：

```text
socket
  -> UdpRedundantDecoder
  -> UdpCompressDecoder
  -> 业务 / socks / udp2raw handler
```

新 FEC pipeline 使用：

```java
UdpProtectConfig cfg = UdpProtectConfig.gameLowLatency();
UdpProtect.install(ch.pipeline(), cfg);
```

新 FEC pipeline 目标顺序：

```text
出站：业务 handler -> UdpProtectEncoder -> UdpFinalEgressGuardHandler -> socket
入站：socket -> UdpProtectDecoder -> 业务 handler
```

原则：

```text
1. 自定义 UDP header 必须在 final egress guard 之前完成。
2. MTU 检查必须看最终真实包大小。
3. 多倍发包/ FEC 增加的额外流量必须计入限速与统计。
4. 不要把 UdpProtect/RDNT/UCMP 包直接发给普通游戏服务器，只能用于自有两端。
```

## 基础 UDP 能力

当前类：

```text
org.rx.net.Sockets
org.rx.net.SocketConfig
org.rx.net.NetworkTrafficConfig
org.rx.net.UdpBackpressurePolicy
org.rx.net.UdpBackpressureDecision
```

核心能力：

```text
1. Sockets.udpBootstrap(config, initChannel) 统一创建 DatagramChannel。
2. 支持 multicast bootstrap。
3. 支持 Linux epoll + SO_REUSEPORT 多 listener。
4. Sockets.writeUdp 统一处理 inactive、notWritable、未解析 recipient、MTU、pending bytes、pending packets。
5. UdpFinalEgressGuardHandler 作为最后出口兜底，确保最终包大小和真实排队量被检查。
```

关键配置：

```text
SocketConfig.reusePortBindCount: UDP 多 listener 数，-1 表示自动。
SocketConfig.udpWriteLimitBytes: 单 channel UDP pending bytes 软上限，默认 1MiB。
SocketConfig.udpWritePerSourceLimitBytes: 单来源 UDP pending bytes 软上限，默认 256KiB。
SocketConfig.udpMtu: 最终 UDP datagram MTU 上限，0 表示不启用。
NetworkTrafficConfig.udpBackpressureEnabled: UDP 写侧过载保护开关。
NetworkTrafficConfig.udpMaxPendingBytes / udpMaxPendingPackets: 全局 UDP pending 限制。
```

写 UDP 推荐：

```java
DatagramPacket packet = new DatagramPacket(buf, remote);
Sockets.UdpWriteResult result = Sockets.writeUdp(channel, packet, config, "net.udp", "role=client");
if (result != Sockets.UdpWriteResult.ACCEPTED) {
    // packet 已由 writeUdp 负责释放，不要再次 release。
}
```

注意：

```text
1. writeUdp 拒绝写入时会释放 DatagramPacket content，调用方不能二次释放。
2. 如果 pipeline 已安装 final egress guard，writeUdp 不重复做 MTU/pending 预检查。
3. UdpMtuProbeDatagramPacket 可绕过普通 MTU drop，用于探测。
4. 所有自定义 header、FEC parity、多倍副本都必须在 final guard 前生成。
```

## UDP 可靠消息/RPC

当前类：

```text
org.rx.net.transport.UdpClient
org.rx.net.transport.UdpClientConfig
org.rx.net.transport.UdpClientCodec
org.rx.net.transport.FuryUdpClientCodec
org.rx.net.transport.UdpSendResult
org.rx.net.transport.protocol.UdpMessage
org.rx.net.transport.protocol.UdpRpcResponse
org.rx.net.transport.protocol.AckSync
```

能力：

```text
1. 自有 RXUP frame。
2. 支持 DATA / ACK。
3. 支持 NONE / SEMI / FULL ACK 模式。
4. 支持 request / requestAsync / reply / replyError。
5. 支持 ByteBuf 分片、重组、重传和超时。
6. 默认 codec 为 FuryUdpClientCodec。
```

关键配置：

```text
waitAckTimeoutMillis: 等 ACK 超时，默认 15000。
fullSync: 是否默认 FULL ACK。
maxResend: 最大重传次数。
maxFragmentPayloadBytes: 单分片 payload 上限，默认 1024。
maxFragmentCount: 最大分片数，默认 128。
```

适用场景：

```text
1. 控制面、低 QPS 请求响应、打洞 rendezvous 消息。
2. 需要 ACK 和重传，但不想引入 TCP 连接的内部协议。
3. 不适合高频游戏帧热路径；游戏帧优先用原始 UDP + FEC/冗余。
```

风险：

```text
1. FULL ACK 和重传会引入延迟，不适合每帧强同步。
2. 分片重组需要限制 maxFragmentCount 和总 payload，防止内存被打爆。
3. ReceiveAssembly 持有 ByteBuf，超时清理必须可靠。
```

## SOCKS/SS UDP Relay

当前类：

```text
org.rx.net.socks.SocksUdpRelayHandler
org.rx.net.socks.SSUdpProxyHandler
org.rx.net.socks.UdpRelayAttributes
org.rx.net.socks.UdpManager
org.rx.net.socks.upstream.SocksUdpUpstream
org.rx.net.socks.upstream.UdpClientUpstream
org.rx.net.socks.upstream.Udp2rawUpstream
```

能力：

```text
1. SOCKS5 UDP ASSOCIATE 入站 relay。
2. Shadowsocks UDP relay。
3. direct、SOCKS upstream、UdpClient upstream、udp2raw upstream 多种上游。
4. 支持客户端地址锁定、relay 地址归属校验和 redundant peer 记录。
5. 通过 SocksConfig 自动接入 UDP 压缩、多倍发包、MTU 与背压。
```

使用建议：

```text
1. 代理链路 UDP 优化优先挂在 SOCKS relay 两端，而不是裸发给目标服务。
2. UDP relay 必须限制 client origin 和 upstream relay address，避免被滥用成反射放大入口。
3. relay 关闭时要同步清理 redundant peer、port hopping holder、lease holder。
```

## UDP 压缩

当前类：

```text
org.rx.net.socks.UdpCompressConfig
org.rx.net.socks.UdpCompressEncoder
org.rx.net.socks.UdpCompressDecoder
org.rx.net.socks.UdpCompressStats
```

协议头：

```text
UCMP magic + originalLen + flags + dictionaryId + compressedPayload
```

当前只支持：

```text
codec = LZ4_FAST
dictionaryId = 0
```

推荐配置：

```java
SocksConfig config = new SocksConfig();
config.setUdpCompressEnabled(true);
config.setUdpCompressMinPayloadBytes(96);
config.setUdpCompressMinSavingsBytes(24);
config.setUdpCompressMinSavingsRatio(0.12D);
config.setUdpCompressCompressionLevel(UdpCompressConfig.DEFAULT_COMPRESSION_LEVEL);
config.setUdpCompressAdaptiveBypass(true);
config.setUdpCompressAdaptiveBypassWindowSeconds(30);
```

使用建议：

```text
1. 只压缩代理链路内部 payload，不压缩直接面向普通 UDP 目标的报文。
2. 小包不要压缩，游戏小包通常不值得为 LZ4 支付 CPU。
3. 开多倍发包前可先开压缩，回收一部分带宽放大。
4. 高压缩等级只适合带宽极紧、CPU 充足的低 QPS 场景。
```

## UDP 多倍发包

当前旧链路类：

```text
org.rx.net.socks.UdpRedundantConfig
org.rx.net.socks.UdpRedundantEncoder
org.rx.net.socks.UdpRedundantDecoder
org.rx.net.socks.UdpRedundantStats
```

新 pipeline 中的多倍发包能力：

```text
org.rx.net.udp.UdpProtectConfig.redundant*
org.rx.net.udp.UdpProtectEncoder
org.rx.net.udp.UdpProtectDecoder
```

适用场景：

```text
1. 短时突发丢包。
2. 跨运营商抖动。
3. 游戏小包、带宽成本可接受。
```

不适用场景：

```text
1. 带宽已经接近上限。
2. 丢包来自 MTU 分片或本机队列堆积。
3. 普通大流量下载/视频类 UDP。
```

推荐配置：

```java
SocksConfig config = new SocksConfig();
config.setUdpRedundantMultiplier(2);
config.setUdpRedundantIntervalMicros(500);
config.setUdpRedundantAdaptive(true);
config.setUdpRedundantMinMultiplier(1);
config.setUdpRedundantMaxMultiplier(2);
config.setUdpRedundantLossThresholdHigh(0.20D);
config.setUdpRedundantLossThresholdLow(0.05D);
config.setUdpRedundantStablePeriods(3);
```

带宽估算：

```text
1x: 原始流量
2x: 原始流量 * 2
3x: 原始流量 * 3
FEC 3:1 + 2x: 原始流量 * 4 / 3 * 2，额外约 166%
```

上线建议：

```text
1. 默认 multiplier=1。
2. 只对登记过的 tunnel peer 生效。
3. intervalMicros 建议 200~1000，避免副本同一瞬间被同一轮突发丢掉。
4. max pending delayed copies 必须有限制，避免 EventLoop scheduled task 堆积。
```

## UDP FEC

当前类：

```text
org.rx.net.udp.UdpProtect
org.rx.net.udp.UdpProtectConfig
org.rx.net.udp.UdpProtectEncoder
org.rx.net.udp.UdpProtectDecoder
org.rx.net.udp.UdpProtectStats
```

当前实现：

```text
1. XOR FEC。
2. 默认 3 个 DATA shard + 1 个 PARITY shard。
3. DATA 包立即透传。
4. PARITY 到达后如只缺 1 个 DATA，则恢复并补交付。
5. 按 sender + sessionId + groupId 隔离解码组。
6. 支持变长 payload 恢复。
```

推荐配置：

```java
UdpProtectConfig cfg = UdpProtectConfig.gameLowLatency();
cfg.setProtectAll(false);
cfg.setFecEnabled(true);
cfg.setFecDataShards(3);
cfg.setFecParityShards(1);
cfg.setFecFlushTimeoutMs(5);
cfg.setStaleGroupTimeoutMs(300);
cfg.setMaxProtectedPayload(1200);
cfg.setRedundantEnabled(true);
cfg.setRedundantMultiplier(1);
cfg.setRedundantMaxMultiplier(2);
cfg.setRedundantIntervalMicros(500);
UdpProtect.install(ch.pipeline(), cfg);
```

登记保护 peer：

```java
UdpProtectAttributes.addProtectedPeer(channel, remoteAddress);
```

自定义 flowId：

```java
cfg.setFlowIdResolver((channel, packet) -> {
    InetSocketAddress recipient = packet.recipient();
    return recipient == null ? 0 : recipient.hashCode();
});
```

使用建议：

```text
1. FEC 适合轻微随机丢 1 包，不适合连续大段丢包。
2. 游戏 UDP 通常能接受乱序，DATA 立即透传比等待整组更低延迟。
3. maxProtectedPayload 默认不要超过 1200，给公网 MTU 和额外 header 留空间。
4. 如果业务协议不能接受恢复包乱序到达，不要开 FEC。
5. 该协议只能用于自有两端，不能直接面向普通 UDP 服务端。
```

## UDP 端口跳跃

当前类：

```text
org.rx.net.socks.UdpPortHoppingConfig
org.rx.net.socks.UdpPortHoppingMode
org.rx.net.socks.upstream.SocksUdpUpstream
org.rx.net.socks.UdpRelayGroupManager
```

配置项：

```text
enabled: 是否开启。
hopCount: 固定端口数。
minActiveHops: 最少活跃 hop。
adaptive: 是否自适应扩容。
minHopCount / maxHopCount: 自适应范围。
adaptiveScaleUpBytes: 双向累计字节达到阈值后扩容。
adaptiveScaleUpActiveMillis: 活跃时长达到阈值后扩容。
mode: ROUND_ROBIN 或 RANDOM。
replenishDelayMillis: relay 补充延迟。
```

推荐配置：

```java
SocksConfig config = new SocksConfig();
config.setUdpPortHoppingEnabled(true);
config.setUdpPortHoppingAdaptive(true);
config.setUdpPortHoppingMinHopCount(1);
config.setUdpPortHoppingMaxHopCount(2);
config.setUdpPortHoppingMinActiveHops(1);
config.setUdpPortHoppingMode(UdpPortHoppingMode.ROUND_ROBIN);
```

使用建议：

```text
1. 默认关闭。
2. 只有确认单端口被限速、端口级抖动时再开。
3. 固定 hopCount 不建议超过 4；最大硬限制为 8。
4. hop 越多，控制通道、relay 端口、状态维护成本线性增长。
5. 第一版不建议同一冗余组跨端口分散，避免远端重复转发。
```

## UDP Relay Group / Lease Pool

当前类：

```text
org.rx.net.socks.UdpRelayGroupManager
org.rx.net.socks.UdpRelayGroupOpenRequest
org.rx.net.socks.UdpRelayGroupOpenResult
org.rx.net.socks.UdpRelayGroupUpdateResult
org.rx.net.socks.UdpRelayEndpoint
org.rx.net.socks.UdpRelayControlMode
org.rx.net.socks.UdpLeasePoolKey
org.rx.net.socks.upstream.SocksUdpUpstream
```

能力：

```text
1. 通过 RPC 一次打开一组 UDP relay。
2. 支持 relay group heartbeat、idle timeout、add/remove relay。
3. 支持 RSS_RPC、RX_SOCKS5_BATCH、SOCKS5_COMPAT、AUTO 控制模式。
4. 支持控制面失败熔断和 fallback 到 SOCKS5 兼容方式。
5. 支持 UDP lease pool 复用 relay，减少高频开关 relay 的控制面成本。
```

关键配置：

```text
udpRelayControlMode: relay 控制模式，默认 AUTO。
udpRelayControlFallbackToSocks5: RPC 控制失败是否回退。
udpRelayControlMaxRelaysPerGroup: 单组最大 relay 数。
udpRelayGroupIdleMillis: group 空闲回收时间。
udpRelayGroupHeartbeatMillis: heartbeat 间隔。
udpRelayControlFailureThreshold / udpRelayControlBreakerOpenMillis: 控制面熔断。
udpLeasePoolEnabled: 是否启用 UDP lease pool。
udpLeasePoolMinSize / udpLeasePoolMaxSize / udpLeasePoolMaxIdleMillis: lease pool 规模和空闲回收。
```

使用建议：

```text
1. 端口跳跃需要多个 relay 时优先用 relay group，减少逐个 SOCKS5 UDP ASSOCIATE 的往返。
2. lease pool 适合短会话很多的 UDP 代理，不适合长期固定少量会话。
3. group token 和 relay ownership 必须校验，避免跨客户端复用。
```

## udp2raw 隧道

当前类：

```text
org.rx.net.socks.Udp2rawHandler
org.rx.net.socks.Udp2rawCodec
org.rx.net.socks.Udp2rawFrame
org.rx.net.socks.Udp2rawPayloadSupport
org.rx.net.socks.Udp2rawAuthenticator
org.rx.net.socks.Udp2rawCapabilities
org.rx.net.socks.Udp2rawMtuState
org.rx.net.socks.Udp2rawMtuProbeSupport
org.rx.net.socks.upstream.Udp2rawUpstream
```

能力：

```text
1. RPC open tunnel，协商 entry address、session secret、capabilities。
2. FIRST_PACKET_MAC 鉴权模式。
3. bad auth fuse 和 peer rate limit。
4. 支持 udp2raw payload 上的 RDNT 方向控制。
5. 支持 MTU probe / ACK / 自适应降 MTU。
6. 可和 UDP 压缩、冗余能力协商组合。
```

关键配置：

```text
udp2rawClient / udp2rawListenAddress: 客户端与监听地址。
udp2rawSessionIdleSeconds: 隧道空闲回收。
udp2rawMaxSessions: 最大 session 数。
udp2rawAuthMode: 鉴权模式。
udp2rawRedundantMode: udp2raw payload 冗余方向。
udp2rawRequireRpc: 是否要求 RPC 能力。
udp2rawBadAuthThreshold / udp2rawBadAuthFuseSeconds: 错误鉴权熔断。
udp2rawPeerRateLimitPerSecond / udp2rawPeerRateLimitBurst: peer 限速。
```

使用建议：

```text
1. udp2raw 属于自有代理链路协议，不能对普通 UDP 目标开放。
2. MTU 自适应下降要和 Sockets.writeUdp 的 MTU_EXCEEDED 结果联动。
3. 鉴权失败、rate limit、session 上限都必须打指标，避免被放大攻击拖垮。
```

## UDP 打洞

当前类：

```text
org.rx.net.punch.UdpHolePunchServer
org.rx.net.punch.UdpHolePunchClient
org.rx.net.punch.UdpHolePunchSession
org.rx.net.punch.UdpHolePunchPackets
```

基本流程：

```text
1. 两端都向 rendezvous server 注册 roomId + peerId。
2. server 记录双方公网观测 endpoint。
3. client 拿到对端 observed endpoint 后，同一本地 UDP 端口连续发送 DirectProbe。
4. 任一端收到 DirectProbe 后回 DirectProbeAck。
5. 收到 ACK 后建立 UdpHolePunchSession，后续直接向 directRemoteEndpoint 发送。
```

服务端示例：

```java
UdpHolePunchServer server = new UdpHolePunchServer(9000);
```

客户端示例：

```java
UdpHolePunchClient client = new UdpHolePunchClient(0);
UdpHolePunchSession session = client.connect(
        new InetSocketAddress("1.2.3.4", 9000),
        "room-1",
        "peer-a");
session.send(myPacket);
```

关键参数：

```text
rendezvousPollIntervalMillis: 默认 200。
peerWaitTimeoutMillis: 默认 30000。
directConnectTimeoutMillis: 默认 5000。
directProbeCount: 默认 8。
directProbeIntervalMillis: 默认 120。
rendezvousRequestTimeoutMillis: 默认 1500。
```

限制：

```text
1. 对称 NAT 成功率低。
2. 必须复用同一个本地 UDP 端口。
3. 打洞探测不能在 I/O 线程里做阻塞等待。
4. 需要 session TTL 和 registry cleanup，避免房间状态泄漏。
```

## Hybrid TCP/UDP 传输

当前类：

```text
org.rx.net.transport.hybrid.HybridClient
org.rx.net.transport.hybrid.HybridServer
org.rx.net.transport.hybrid.HybridConfig
org.rx.net.transport.hybrid.HybridRoutePolicy
org.rx.net.transport.hybrid.HybridUdpProbe
org.rx.net.transport.hybrid.HybridUdpData
org.rx.net.transport.hybrid.HybridRouteState
org.rx.net.transport.hybrid.HybridSendOptions
```

能力：

```text
1. TCP 作为基础控制/兜底通道。
2. UDP probe 建立直连可用性。
3. UDP_READY 且 encodedBytes <= udpSmallPacketThresholdBytes 时小包走 UDP。
4. 支持 forceTcp 发送选项。
5. UDP 失败超过阈值后回退 TCP。
6. 可结合 rendezvousEndpoint 做 UDP hole punch。
```

关键配置：

```text
udpBindPort: UDP 本地绑定端口。
udpSmallPacketThresholdBytes: UDP 小包阈值，默认 8KiB。
udpProbeTimeoutMillis / udpProbeIntervalMillis / udpProbeCount: UDP 探测参数。
udpAckTimeoutMillis: UDP ACK 超时。
maxUdpFailuresBeforeFallback: UDP 失败转 TCP 阈值。
maxUdpInflightMessagesPerSession: 单 session UDP 在途限制。
enableUdpDirect / enableUdpHolePunch: UDP 直连和打洞开关。
rendezvousEndpoint: 打洞 rendezvous 地址。
```

使用建议：

```text
1. Hybrid 适合“控制可靠、数据低延迟”的内部传输。
2. 大包默认走 TCP，避免 UDP 分片和重组压力。
3. UDP in-flight 上限必须按 session 控制，避免弱网下无限堆积。
```

## DNS、NTP 与名称服务

当前类：

```text
org.rx.net.dns.DnsClient
org.rx.net.dns.DnsServer
org.rx.net.dns.DnsDatagramSourceHandler
org.rx.net.NtpClient
org.rx.net.NtpPacket
org.rx.net.nameserver.NameserverImpl
org.rx.net.NetEventWait
```

能力：

```text
1. DNS client/server 使用 UDP datagram。
2. NtpClient 使用 UDP 查询时间。
3. NameserverImpl 使用 UDP 做局域网发现/同步类能力。
4. NetEventWait 使用 UDP multicast 做事件等待。
```

使用建议：

```text
1. DNS/NTP 属于标准协议，不要叠加 UCMP/RDNT/UdpProtect header。
2. 名称服务和 multicast 场景要显式限制 TTL、网卡和监听范围。
3. DNS 查询链路避免阻塞式 InetAddress 解析进入 I/O 热路径。
```

## 推荐组合

游戏 UDP 默认低延迟：

```text
FEC 3:1
多倍发包 multiplier=1
maxProtectedPayload=1200
端口跳跃关闭
压缩关闭或只对大包开启
```

轻微随机丢包：

```text
FEC 3:1 或 4:1
多倍发包保持 1
```

突发丢包明显：

```text
FEC 3:1
多倍发包 adaptive，范围 1~2
intervalMicros=500
```

带宽紧张但 payload 可压缩：

```text
先开 UDP 压缩
FEC 4:1
多倍发包保持 1
```

端口级限速：

```text
端口跳跃 adaptive 1~2
必要时再叠 FEC
谨慎叠 2x 多倍发包
```

## MTU、背压与限速

必须满足：

```text
1. final egress guard 在所有 UDP header 后执行。
2. maxProtectedPayload 默认 1200。
3. UDP pending bytes / pending packets 要纳入背压。
4. FEC parity 和 redundant copy 都要计入真实流量。
5. MTU drop 要反馈给 udp2raw dynamic MTU state。
```

有效业务带宽估算：

```text
20Mb/s + FEC 3:1: 约 15Mb/s 有效业务。
20Mb/s + FEC 3:1 + 2x: 约 7.5Mb/s 有效业务。
```

## 监控指标

必须关注：

```text
堆外内存占用: PooledByteBufAllocator direct memory。
连接 / peer 数: active UDP peer、protected peer、hole punch session。
FEC: protected data、parity、recovered、decode drop、stale group、group limit drop。
多倍发包: multiplier、duplicate drop、pending delayed copies、adaptive loss rate。
压缩: applied、low gain bypass、decode failure、saved bytes。
端口跳跃: active hops、relay replenish、relay group idle、control RPC failure。
MTU/背压: mtu drop、pending packets、pending bytes、egress guard drop。
吞吐与延迟: p50/p95/p99 send latency、recv latency、恢复包乱序延迟。
```

## 当前缺口

已经具备但尚未完全统一的点：

```text
1. UdpProtect 新管线还没有接入 SocksConfig / udp2raw 默认链路，当前自动安装的仍是旧 UCMP + RDNT。
2. UDP 压缩和旧多倍发包仍在 org.rx.net.socks 包下，通用 handler 尚未迁移到 org.rx.net.udp。
3. UdpProtect 目前覆盖 FEC、固定冗余、去重和基础统计，但还没有旧 UdpRedundant 的自适应丢包调倍率策略。
4. UdpProtect 还没有和 udp2raw capabilities 做能力协商，也没有配置桥接。
5. 端口跳跃核心策略还绑定在 SocksUdpUpstream / relay group 控制面，尚未抽出通用端口选择策略。
6. UdpClient 可靠消息、Hybrid UDP、UdpProtect 三套 UDP 协议尚未形成统一组合指南和互斥规则。
7. 缺少统一 UDP metrics facade；目前指标分散在 socks.udp、socks.udp2raw、net.udp 等前缀。
8. 没有 Reed-Solomon 类多 parity FEC；当前 XOR FEC 只能恢复每组 1 个丢包。
```

建议补齐顺序：

```text
1. 先把 UdpProtect 改名或 facade 为 UdpResilience，并接入 SocksConfig 开关。
2. 再迁移 UdpCompress* / UdpRedundant* 通用部分到 org.rx.net.udp，保留 socks 兼容配置。
3. 给 udp2raw open capabilities 增加 FEC/Resilience 协商。
4. 统一 UDP 指标命名，至少覆盖堆外内存、pending、drop、MTU、FEC、冗余、压缩、端口跳跃。
5. 最后再考虑多 parity FEC 或 KCP/QUIC 类可靠流；这属于新协议层，不应混进当前轻量 datagram pipeline。
```

## 上线检查清单

```text
1. 是否只在自有代理两端启用自定义 UDP 协议。
2. 是否确认最终出口 MTU guard 已安装。
3. 是否确认 ByteBuf ownership 转移与 release 成对。
4. 是否限制 max peers、max groups、pending delayed copies。
5. 是否避免 I/O 线程阻塞等待。
6. 是否评估带宽放大。
7. 是否验证 DATA 立即透传和恢复包乱序对业务可接受。
8. 是否跑过 EmbeddedChannel 单测和本地 DatagramChannel 集成测试。
```
