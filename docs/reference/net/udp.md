# UDP 能力使用说明

本文整理 rxlib 当前 UDP 相关能力：基础 UDP channel、可靠消息、SOCKS/SS UDP relay、压缩、多倍发包、端口跳跃、udp2raw、FEC、打洞、Hybrid 传输、DNS/NTP 与 MTU/背压。当前项目按高性能模式维护，默认 Java 8，UDP 热点路径避免阻塞、反射、正则和无意义对象分配。

## 两套平面

抗弱网有两条独立实现，都是单 XOR，没有 Reed-Solomon，也不能混在同一条 pipeline 里。

| 平面 | 类 | 谁在用 |
| --- | --- | --- |
| Resilience | `UdpResilienceEncoder` / `UdpResilienceDecoder` | `SocketConfig.udpResilience` 安装到通用 UDP channel。默认 3 个 DATA + 1 个 PARITY，5ms flush |
| Daily | `UdpDailyFec`，外层 `DailyUdpWire` 或 `AdaptiveStream` | RSS `daily`。分组目标 0/4/8/16，flush 约 15–180ms |

`java_fec.md` 里的动态 RS、14 字节头、拥塞熔断和平滑发送没有对应类，文件已删除。Daily FEC 尚未闭合的问题见 [2026-11-23 审查](../../rxlib_fec_review_20261123.md)。

## 能力总览

| 能力 | 当前核心类 | 主要场景 | 是否自定义协议 | 默认建议 |
|---|---|---|---|---|
| UDP Bootstrap/写出口保护 | `Sockets.udpBootstrap`，`Sockets.writeUdp`，`UdpBackpressure*` | 所有 DatagramChannel 出入口 | 否 | 统一走 `Sockets.writeUdp` |
| UDP 可靠消息/RPC | `org.rx.net.transport.UdpClient*`，`KcpClient*` | 无序可靠控制消息；有序可靠连续消息 | 是，`RXUP` / `RXKC + KCP` frame | 按顺序语义选择 |
| SOCKS/SS UDP relay | `SocksUdpRelayHandler`，`SSUdpProxyHandler`，`SocksUdpUpstream` | SOCKS5 UDP ASSOCIATE、Shadowsocks UDP 转发 | SOCKS/SS 标准包 + 内部扩展 | 代理 UDP 主路径 |
| UDP 压缩 | `org.rx.net.udp.UdpCompress*` | 大包、可压缩 payload、代理链路省带宽 | 是，`UCMP` header | 只在自有代理两端开启 |
| UDP 多倍发包 | `UdpRedundant*`，以及 `UdpResilienceEncoder/Decoder` 里的固定倍率 | 短时突发丢包、运营商抖动 | 是，`RDNT` 或 `UdpResilience` header | 默认 1。按丢包率调整只在 `UdpRedundantStats` |
| UDP FEC | `UdpResilience*` 或 `UdpDailyFec` | 自有链路轻微随机丢 1 包 | 是，两套头互不兼容 | Resilience 默认 3:1；Daily 按反馈选组 |
| UDP 端口跳跃 | `org.rx.net.udp.UdpPortHopping*`，`SocksUdpUpstream` | 单端口被限速、端口级抖动 | 控制面依赖 SOCKS/RPC relay | 默认关闭，确认端口限速再开 |
| udp2raw 隧道 | `Udp2rawHandler`，`Udp2rawUpstream`，`Udp2rawCodec` | 把 UDP 代理 payload 包装到自有隧道 | 是，udp2raw frame | 需要 RPC 能力协商 |
| UDP relay group/lease pool | `UdpRelayGroupManager`，`UdpRelayGroup*`，`UdpLeasePoolKey` | 一次打开一组 relay、端口跳跃、复用 UDP lease | RPC 控制面 | 由 SOCKS upstream 自动使用 |
| UDP 打洞 | `org.rx.net.udp.UdpHolePunch*` | P2P NAT 穿透 | 使用 `UdpClient` 消息协议 | 适合同 NAT 友好网络 |
| Hybrid TCP/UDP | `org.rx.net.transport.hybrid.*` | 小包走 UDP，大包/失败回 TCP | 内部 Hybrid 消息 | 默认 TCP 兜底 |
| DNS/NTP/Nameserver | `Dns*`，`NtpClient`，`NameserverImpl` | DNS 查询、NTP、名称服务同步 | DNS/NTP 或内部包 | 按具体组件使用 |
| MTU/背压 | `org.rx.net.udp.UdpFinalEgressGuardHandler`，`UdpBackpressure*` | 最终出口兜底 | 否 | 必须在所有 UDP header 后执行 |

## 包放置建议

通用 Datagram pipeline 已经在 `org.rx.net.udp`：

```text
org.rx.net.udp
  UdpResilience
  FEC
  多倍发包去重与统计
  压缩 codec 的通用 handler
  MTU / egress guard 辅助策略
```

保留在 `org.rx.net.socks` 的内容：

```text
SocksConfig / udp2raw 的方向控制
SOCKS UDP relay 接入逻辑
udp2raw open/capability 协商
端口跳跃的 SOCKS/RPC relay 控制面
```

`UdpCompressConfig` 与 `UdpRedundantConfig` 的配置入口已上提到 `SocketConfig`；`SocksConfig` 通过继承继续复用这些开关。端口跳跃不是单纯 pipeline handler，它涉及 upstream session 数量、RPC relay group、补充 relay、控制面状态，所以控制面仍由 SOCKS 持有。

## Pipeline 顺序

`Sockets.addUdpHandler(channel, config)` 是 UDP 能力统一入口，负责按配置安装压缩、多倍发包、`UdpResilience`、最终出口 guard 和网络指标；`Sockets.udpBootstrap(config, initChannel)` 创建的 channel 会自动执行该入口。handler 安装不等于对裸 UDP 目标启用能力：裸 UDP 在确认对端能力后调用 `Sockets.addUdpPeer(channel, config, remote)`；`UdpClient` 对发出的 RXUP 目标和成功解码的 RXUP 来源自动登记，`KcpClient` 对 RXKC 会话自动登记和清理已开启的优化能力。重复手动调用 `Sockets.addUdpPeer(...)` 仍是幂等兼容操作。`UdpResilienceConfig.resilienceAll=true` 表示 FEC 显式保护全部目标，不依赖 peer 登记。

只开压缩和多倍发包时，出站顺序：

```text
业务 / socks / udp2raw handler
  -> UdpCompressEncoder
  -> UdpRedundantEncoder
  -> UdpFinalEgressGuardHandler
  -> socket
```

对应入站顺序：

```text
socket
  -> UdpRedundantDecoder
  -> UdpCompressDecoder
  -> 业务 / socks / udp2raw handler
```

手动组装 channel 时统一安装与登记：

```java
SocketConfig config = new SocketConfig();
UdpResilienceConfig cfg = UdpResilienceConfig.gameLowLatency();
cfg.setResilienceAll(false);
config.setUdpResilience(cfg);
Sockets.addUdpHandler(ch, config);
Sockets.addUdpPeer(ch, config, remoteAddress);
```

`Sockets.addUdpOptimizationHandlers` 同时开启三类优化时，自动 pipeline 顺序为：

```text
出站：业务协议 -> UdpCompressEncoder -> UdpRedundantEncoder -> UdpResilienceEncoder -> UdpFinalEgressGuardHandler -> socket
入站：socket -> UdpResilienceDecoder -> UdpRedundantDecoder -> UdpCompressDecoder -> 业务协议
```

原则：

```text
1. 自定义 UDP header 必须在 final egress guard 之前完成。
2. MTU 检查必须看最终真实包大小。
3. 多倍发包/ FEC 增加的额外流量必须计入限速与统计。
4. 不要把 UdpResilience/RDNT/UCMP 包直接发给普通游戏服务器，只能用于自有两端。
```

## 基础 UDP 能力

当前类：

```text
org.rx.net.Sockets
org.rx.net.SocketConfig
org.rx.net.NetworkTrafficConfig
org.rx.net.udp.UdpBackpressurePolicy
org.rx.net.udp.UdpBackpressureDecision
```

核心能力：

```text
1. Sockets.udpBootstrap(config, initChannel) 统一创建 DatagramChannel。
2. Sockets.addUdpHandler / addUdpPeer / removeUdpPeer 统一编排 UDP handler 与目标能力登记。
3. 支持 multicast bootstrap。
4. 支持 Linux epoll + SO_REUSEPORT 多 listener。
5. Sockets.writeUdp 统一处理 inactive、notWritable、未解析 recipient、MTU、pending bytes、pending packets。
6. UdpFinalEgressGuardHandler 作为最后出口兜底，确保最终包大小和真实排队量被检查。
```

关键配置：

```text
SocketConfig.reusePortBindCount: UDP 多 listener 数，-1 表示自动。
SocketConfig.udpWriteLimitBytes: 单 channel UDP pending bytes 软上限，默认 1MiB。
SocketConfig.udpWritePerSourceLimitBytes: 单来源 UDP pending bytes 软上限，默认 256KiB。
SocketConfig.udpMtu: 最终 UDP datagram MTU 上限，0 表示不启用。
SocketConfig.udpCompress*: UDP 单包压缩配置。
SocketConfig.udpRedundant*: UDP 多倍发包配置。
SocketConfig.udpResilience: UDP FEC/弱网保护配置，null 表示不安装。
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

### UdpClient：无序可靠消息

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
7. 每条消息独立 ACK/重传，不承诺不同消息之间的交付顺序。
8. 配置压缩、冗余或 peer-scoped FEC 后，自动为已发送目标和成功解码的 RXUP 来源登记优化 peer。
```

关键配置：

```text
waitAckTimeoutMillis: 等 ACK 超时，默认 15000。
fullSync: 是否默认 FULL ACK。
maxResend: 最大重传次数。
maxFragmentPayloadBytes: 单分片 payload 上限，默认 1024。
maxFragmentCount: 最大分片数，默认 128。
maxAutoPeers: 自动登记优化 peer 的上限，默认 4096；设为 0 时改为纯手动登记。
```

适用场景：

```text
1. 控制面、低 QPS 请求响应、打洞 rendezvous 消息。
2. 需要 ACK 和重传，但不想引入 TCP 连接的内部协议。
3. 不适合高频游戏帧热路径；游戏帧优先用原始 UDP + FEC/冗余。
4. 自动 peer 依赖双方 `UdpClient` 的优化配置一致；普通第三方 UDP 目标仍应使用裸 UDP 并手动管理能力。
```

风险：

```text
1. FULL ACK 和重传会引入延迟，不适合每帧强同步。
2. 分片重组需要限制 maxFragmentCount 和总 payload，防止内存被打爆。
3. ReceiveAssembly 持有 ByteBuf，超时清理必须可靠。
```

### KcpClient：有序可靠消息

当前类：

```text
org.rx.net.transport.KcpClient
org.rx.net.transport.KcpClientConfig
org.rx.net.transport.kcp.RxKcp
```

能力：

```text
1. 自有 RXKC datagram header，内部承载 KCP segment。
2. 同一 remote / conv session 内严格按发送顺序交付 logical message。
3. KCP 管理 ACK、窗口、RTT/RTO、快速重传与 MTU 分段。
4. 支持 send、request / requestAsync、reply / replyError 和 UdpClientCodec。
5. 所有出口仍通过 Sockets.writeUdp，可叠加压缩、多倍发包和 UdpResilience。
6. KcpClient 在 RXKC 会话建立与释放时，通过 Sockets 统一入口自动登记和清理已开启的优化 peer。
7. 可配置 `authenticationKey` 启用认证握手、报文防篡改/重放保护，以及认证通过后的 NAT rebinding 迁移。
```

关键配置：

```text
mtu: RXKC 外层 header 之前的 KCP packet MTU，默认 1200。
noDelay / intervalMillis / fastResend / noCongestionControl: KCP 时延和重传参数。
sendWindow / receiveWindow: KCP 窗口。
maxPendingBytesPerSession / maxPendingMessagesPerSession: KCP 发送队列背压上限。
sessionIdleTimeoutMillis / maxSessions: 空闲清理时间与单个 KcpClient 可持有的入站/出站会话总数上限。
authenticationKey: 可选预共享密钥；设置后双方必须使用相同值，并使用 RXKC v2 认证报文。
allowNatRebinding: 认证会话是否允许由有效的新源地址报文迁移 endpoint，默认 true；无密钥时不生效。
```

约束：

```text
1. KCP transport ACK 不表示 onReceive 业务处理成功；需要 FULL ACK 语义时继续使用 UdpClient。
2. 有序传输会产生队头阻塞；需要无序可靠消息时使用 UdpClient。
3. KcpClient 将 UDP bind 固定为一个 listener，使每个 KCP 控制块只在一个 EventLoop 上执行。
4. RXKC 与 RXUP wire format 不兼容，不能混用对端。
5. 未配置 `authenticationKey` 时保留 RXKC v1 兼容模式：不进行握手认证，也不允许 NAT rebinding；暴露到非可信网络时应配置密钥，并继续配合 ACL/限流。
6. `maxSessions` 始终保留，用于限制 KCP 控制块与队列总量；认证可阻止无密钥报文创建 v2 会话，但不能替代对持有密钥的错误客户端或流量洪泛的资源限制。
7. KCP 是 `KcpClient` 的传输类型选择，不是通用 UDP pipeline 开关；`Sockets` 统一入口管理其下层优化和 peer 生命周期。
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
5. 通过继承自 SocketConfig 的 UDP 配置自动接入压缩、多倍发包、MTU 与背压。
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
org.rx.net.udp.UdpCompressConfig
org.rx.net.udp.compress.UdpCompressEncoder
org.rx.net.udp.compress.UdpCompressDecoder
org.rx.net.udp.compress.UdpCompressSupport
org.rx.net.udp.UdpCompressStats
```

直接引用旧编码器、解码器或静态工具类的应用需更新 import 并重新编译。配置、统计对象与 UCMP 包头沿用原定义。

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
SocketConfig config = new SocketConfig();
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
org.rx.net.udp.UdpRedundantConfig
org.rx.net.udp.UdpRedundantEncoder
org.rx.net.udp.UdpRedundantDecoder
org.rx.net.udp.UdpRedundantStats
```

新 pipeline 中的多倍发包能力：

```text
org.rx.net.udp.UdpResilienceConfig.redundant*
org.rx.net.udp.UdpResilienceEncoder
org.rx.net.udp.UdpResilienceDecoder
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
SocketConfig config = new SocketConfig();
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
org.rx.net.udp.UdpResilience
org.rx.net.udp.UdpResilienceConfig
org.rx.net.udp.UdpResilienceEncoder
org.rx.net.udp.UdpResilienceDecoder
org.rx.net.udp.UdpResilienceStats
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
UdpResilienceConfig cfg = UdpResilienceConfig.gameLowLatency();
cfg.setResilienceAll(false);
cfg.setFecEnabled(true);
cfg.setFecDataShards(3);
cfg.setFecParityShards(1);
cfg.setFecFlushTimeoutMs(5);
cfg.setStaleGroupTimeoutMs(300);
cfg.setMaxResiliencePayload(1200);
cfg.setRedundantEnabled(true);
cfg.setRedundantMultiplier(1);
cfg.setRedundantMaxMultiplier(2);
cfg.setRedundantIntervalMicros(500);
SocketConfig config = new SocketConfig();
config.setUdpResilience(cfg);
Sockets.addUdpHandler(ch, config);
```

对裸 UDP 手动登记目标能力，或对 `UdpClient` 进行显式覆盖登记：

```java
Sockets.addUdpPeer(channel, config, remoteAddress);
```

`UdpClient` 配置优化能力后会在首次写出前登记目标，并在成功解码 RXUP 后登记来源，受 `maxAutoPeers` 限制；因此发送到的目标必须同样启用兼容配置。RXUP 不带认证，面向不可信网络仍需 ACL、限流与合理上限。`KcpClient` 也会自动执行 peer 登记；双方配置必须对齐。需要公网认证和 NAT rebinding 时，两端配置相同的 `authenticationKey`：

```java
KcpClientConfig config = new KcpClientConfig();
config.setAuthenticationKey(preSharedKey);
config.setAllowNatRebinding(true);
```

认证模式使用 RXKC v2：发起侧以签名 `OPEN` 建立带 `sessionId` 的会话，所有控制和 KCP data 报文携带方向位、单调序列及截断 HMAC；新源地址仅在 HMAC、方向与重放窗口校验通过后迁移。HMAC 会增加每个 datagram 的 CPU 和 32 字节线开销，应结合吞吐、P99 延迟、`transport.kcp.auth.fail.count`、`transport.kcp.rebind.count`、活动会话数和 direct/off-heap 使用量观测；拒绝类指标在攻击流量下做采样记录。

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
3. maxResiliencePayload 默认不要超过 1200，给公网 MTU 和额外 header 留空间。
4. 如果业务协议不能接受恢复包乱序到达，不要开 FEC。
5. 该协议只能用于自有两端，不能直接面向普通 UDP 服务端。
6. parity 被钳在 0 或 1。冗余倍率是配置值，这条管线没有按丢包率改倍率。自适应倍率仍在 UdpRedundantStats。
```

## Daily FEC

`UdpDailyFec` 绑定在一条已协商的双向链路上，由调用方的 EventLoop 驱动。`DailyUdpWire` 在 feature 位 `FEC=4` 时使用它；`AdaptiveStream` 在 UDP 已就绪时也使用它，并保留 TCP 修复通道。

```text
类型：DATA=0，SHARD=1，PARITY=2，REPORT=3
MAX_OVERHEAD=8，WIRE_HEADROOM=20
MIN_SHARDS=2，MAX_SHARDS=16，MAX_SHARD_BYTES=1500
发送 flush：约 15ms 到 180ms，随包间隔拉长
接收缓存：从 4 组按需扩到 16 组，300ms 过期
交付去重：UdpSequenceWindow.FEC_SIZE=512，包括原包与恢复包
```

`target==0` 或单包超过 1500 字节时只发 `DATA`，不产生校验。否则数据立即以 `SHARD` 发出，凑满当前 target 或 flush 到期后，在冗余预算足够时发一份 XOR。接收端每 32 个原始序号窗口回 `REPORT`。发送端按窗口内缺失数设置下一组大小：缺失不少于 4 则为 4，不少于 2 则为 8，否则 16。连续 3 个干净窗口，或 5 秒没有新报告，target 回到 0。每组只能恢复 1 个丢包。

`AdaptiveStream` 把有序字节切成最多 1250 字节的 UDP 帧，未确认窗口和乱序窗口都是 64。最短 TCP 修复等待 80ms，乱序宽限初始 40ms，并按 RTT 调整。超时后把尚未累计确认的帧经 TCP 再送出去。

`DailyUdpWire` 另有 30 秒一轮的 primary/alias 端口跳跃，和下面的 SOCKS relay 端口跳跃不是同一套实现。RSS 用户字段见 [RSS 传输配置](../../rss-transport.md)。

### Game 与 Weak

Game UDP 在 1 倍时也给原包分配逻辑序号。每 32 个序号报告原始副本、全部物理副本和唯一交付数，预留 8 个序号容纳乱序。发送端用同方向实际发出的副本数核对反馈。冷启动默认单发，三个干净窗口降一档；残余缺失和副本收益持续两个合格窗口才从 2 升到 3。两个反馈窗口的往返时间持续高于本方向基线时退回单发。反馈未知时退出短时冷启动。同一原包的副本一起 flush。额外副本按平滑预算发送，每会话持续速率上限 512 PPS、512 KiB/s，进程上限 8192 PPS、4 MiB/s，允许短时突发。迟到反馈可按需将 256 项发送历史扩至 1024 项，全进程扩展历史数组限 8 MiB。预算和写水位均只约束附加副本。

Weak 从零校验开始，按各方向的物理缺口、孤立缺口和有效恢复数在 XOR 16:1、8:1、4:1 间调整。校验按实际编码 UDP 载荷字节扣预算，包含认证封装。日常模式继续按包数计预算；预计凑不够预算的稀疏组最多暂存 3004 B 短前缀，确定要发校验或前缀已满时再计算 XOR。保持既有 SHARD 头和相同丢包位置的覆盖，流量突然加快也能正常成组。

TCP 快路径共用 64 帧起步的窗口。Game/Weak 有在途需求且估计 RTT 至少 40 ms 时，发送窗口按需升至 128/256 帧。接收窗口按实际乱序距离扩展。两种模式在 JVM 内共享 32 MiB 的发送/乱序载荷预留上限，累计确认、乱序排空、回退和关闭会释放对应预留。这个上限单独约束恢复窗口；压缩器、FEC、池化分配器和内核仍占用额外内存。

Weak 接收端在乱序宽限到期时提交从下一个待交付序号开始的 64 位接收位图。发送端按序只补位图范围内的缺口，保留原帧直到累计确认或 TCP 接管。超时、窗口满或 FIN 接管时，释放本方向待发校验，保留接收方向的纠错状态。诊断按原因记录修复帧、载荷和含帧头字节；`rss.tcp.adaptive.deliveryStallMillis` 记录已观察到的乱序等待，端到端 P99 需通过业务回显采样。监控同时采集双向字节、吞吐、连接数、CPU、进程 RSS 与 Netty direct memory。

#### 单核 TCP 对照（2026-11-24）

Linux 容器限制为 1 vCPU、768 MiB、禁用 swap。RSS 两端在一个 JVM，应用、回显和抓包进程也计入同一 cgroup。每个条件为一条顺序 TCP 流、100 次完整回显，每次上下行各 164 B，160 B 内容重复，合计有效载荷 32,800 B。内置自适应传输在两端确认 UDP 探测成功后开始采样。TCP 对照使用本地合并版本 `efcf5cd1`。对照使用同一 SOCKS 业务，经单独的 kcptun 客户端/服务端传输，分别使用 10:3、10:1、fast、AES 和默认压缩。普通 TCP 对照走同一跳。仅测试这段内置跳，业务加密端口见 RSS 端口策略。

每个条件重置 netem，在两个方向分别注入一半 RTT 的延迟和表中丢包率。发送字节为 lo egress 上两个方向的 IPv4 字节，包含重传、确认、控制与探测，在注入丢包前计数。CPU 为同一 cgroup 在 100 次回显内的 CPU 秒，包含测试程序；goodput 为双向有效载荷除以回显耗时。

| RTT / 每方向丢包 | 模式 | 双向发送 B | P95 / P99 / 最大回显 ms | goodput kB/s | CPU 秒 |
| --- | --- | ---: | ---: | ---: | ---: |
| 40 ms / 1% | plain | 44,824 | 94.2 / 288.7 / 304.9 | 6.35 | 1.24 |
| 40 ms / 1% | 旧 daily | 29,904 | 98.3 / 101.0 / 132.1 | 6.52 | 2.17 |
| 40 ms / 1% | daily | 29,228 | 96.0 / 102.2 / 105.3 | 6.87 | 1.73 |
| 40 ms / 1% | game | 31,529 | 97.6 / 130.4 / 317.7 | 6.30 | 1.94 |
| 40 ms / 1% | weak | 30,702 | 100.7 / 261.5 / 294.6 | 5.94 | 2.20 |
| 40 ms / 1% | kcptun 10:3 | 134,664 | 81.0 / 101.3 / 177.6 | 6.77 | 1.69 |
| 40 ms / 1% | kcptun 10:1 | 104,644 | 99.6 / 152.6 / 198.9 | 6.28 | 1.89 |
| 150 ms / 5% | plain | 50,744 | 534.6 / 796.5 / 913.9 | 1.54 | 0.49 |
| 150 ms / 5% | 旧 daily | 28,747 | 524.5 / 565.7 / 820.9 | 1.62 | 0.77 |
| 150 ms / 5% | daily | 29,346 | 531.0 / 644.5 / 925.6 | 1.67 | 0.93 |
| 150 ms / 5% | game | 31,087 | 553.2 / 780.6 / 892.4 | 1.64 | 0.94 |
| 150 ms / 5% | weak | 30,551 | 531.1 / 792.2 / 1354.0 | 1.57 | 0.80 |
| 150 ms / 5% | kcptun 10:3 | 141,344 | 415.8 / 652.2 / 974.9 | 1.74 | 0.93 |
| 150 ms / 5% | kcptun 10:1 | 109,172 | 334.7 / 510.1 / 694.1 | 1.71 | 1.29 |

两组 weak 都减少了相对 kcptun 的发送字节，但 P99 和最长回显未同时达到方案的 1.2 倍目标。150 ms / 5% 时 weak 的 P99 也高于本轮 daily。这种顺序负载一次只有一个请求在途，尾包丢失时通常没有后续业务帧暴露多个缺口。结果不能外推到文件传输或高并发。两端合计 Netty 池化 direct memory 的最高观测值为 8 MiB。容器内存样本包含 JVM、隧道程序、文件缓存和探针；宿主 OS 不在此限额内。P99 每组只有 100 个回显样本。

同一资源限额下，另用 Java Deflater、SYNC_FLUSH、每组 4 MiB、三轮测量取中位数。zlib 6 相比 3，长文档输出减少 7.8%、编码 CPU 增加 71%；短 JSON API 减少 5.1%、CPU 增加 10%；合成游戏短包减少 0.6%、CPU 增加 21%；JAR 压缩数据减少 0.14%。这是编码微测，TCP 表使用默认 zlib 3。

#### 单核游戏 UDP 对照（2026-11-24）

使用 `b7a0022a` 的 Netty UDP 通道和 game 认证封装，与普通 UDP 直接回显对照。每个应用数据报 164 B，MTU 1300，双向各注入 40 ms 延迟、10 ms 抖动和 5% 随机丢包。收发两端共享 1 核、768 MiB 容器。每档独立启动 JVM，预热时先以 250 PPS 发送 1,200 个包，再测表中负载。交付数按一次完整往返计，P99 只计算收到的回包。每档均检查回包长度、逻辑序号和校验字段，重复交付数均为 0。

| 发送 PPS | 请求数 | plain 交付 | game 交付 | plain P99 ms | game P99 ms | game / plain 队列出站字节 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 20 | 160 | 150 | 160 | 97.4 | 87.1 | 3.74 倍 |
| 50 | 160 | 150 | 160 | 99.3 | 96.3 | 2.49 倍 |
| 100 | 200 | 186 | 198 | 97.8 | 95.7 | 2.52 倍 |
| 200 | 400 | 358 | 397 | 98.5 | 88.5 | 3.16 倍 |
| 300 | 600 | 542 | 597 | 97.8 | 93.3 | 2.54 倍 |
| 1000 | 2000 | 1800 | 1875 | 106.1 | 98.9 | 1.92 倍 |

队列出站字节取 netem 的双向 qdisc 字节增量，包含游戏认证、反馈和额外副本。它与上表 TCP 注入丢包前的 IP 字节使用不同计数点。

冷启动另测两档，省去全部预热。20 PPS 时，game 收到 159/160，plain 收到 137/160，P99 分别为 92.7 和 97.7 ms，最长回显分别为 154.0 和 101.4 ms。1000 PPS 时，game 仅收到 1561/2000，plain 收到 1803/2000，P99 分别为 213.7 和 184.9 ms。单核新进程直接承受高 PPS 的场景尚未达到 game 的交付和尾延迟目标。每档仅一次短测，真实游戏、长期混合并发和整机 768 MiB 仍待验证。

### 2026-11-23 本地弱网验证

FEC 交付去重改用 512 个序号的环形位图。启用 FEC 的 UDP AES 会话和 TCP/FEC 的认证 UDP 端口也使用 512 序号窗口，否则晚到校验会在进入解码器前被丢弃。每个位图占 64 B 堆内数据，原包和恢复包共用一份交付记录。AES 单独使用时窗口为 64。认证通过后才写入重放记录。线格式、16 个接收组上限和 300 ms 分组寿命沿用既有协议。

三个定向回归在修复前均失败。修复后的测试覆盖了校验落后 80、256 个数据序号，299 ms 仍可恢复，300 ms 已过期；另覆盖 32 位序号回绕、同时保留 16 个满组并逆序接收校验，以及重复原包和校验。IPv4/IPv6 的 TCP/FEC 认证端口测试还验证了乱序帧能交付，回包继续发往最新地址。

#### 校验覆盖率

固定档位实验对每种 PPS 和 K 组合发送 16 轮，每轮 512 个数据包。每 16 包丢一个原包，轮换全部 16 个丢包位置，校验包正常送达。表中为发送的校验包所覆盖的原包占比。

| 发送速率 | 每 16 包一份校验 | 每 8 包一份校验 | 每 4 包一份校验 |
| --- | ---: | ---: | ---: |
| 20 PPS | 25.0% | 50.0% | 100% |
| 50 PPS | 49.2% | 100% | 100% |
| 100 PPS | 100% | 100% | 100% |

20 PPS 时相邻两包相隔 50 ms。180 ms 的组包期限最多纳入 4 包，K=16 时每份校验最多保护 4/16。要覆盖全部原包，需要增加校验预算或延长恢复等待。本轮保留日常模式预算与期限。

另一组实验使用真实的自适应反馈，覆盖 20/50/100/200/300/1000 PPS、0/1/5/10/20% 前向丢包、3 个随机种子和两种丢包模型。数据和校验都可能丢，反向反馈直接送达。20 PPS、名义丢包率 5% 时，独立随机丢包恢复 33/83 个丢失原包，平均连续丢 3 个线包的突发模型恢复 1/63。分母是各组实际丢失的原包数。完整逐项输出可用以下命令在仓库根目录复现。

```powershell
mvn -f rxlib/pom.xml '-DskipTests=false' '-Dtest=UdpDailyFecCoverageTest' test
```

#### 运行指标与验收边界

- 普通 UDP 按方向汇总 `rss.udp.daily.tx.protectedData / rss.udp.daily.tx.data` 可计算校验覆盖率。两项均为诊断周期增量，跨多个周期分别求和再相除，可减少组包跨周期带来的偏差。
- TCP 超时回退同时记录 `rss.tcp.adaptive.timeoutRepairFrames` 和 `rss.tcp.adaptive.timeoutRepairBytes`。字节取实际送往 TCP 的流载荷，扣除每帧 5 B 的内部帧头。GAP 请求、窗口满或 FIN 触发的发送不计入这两个超时指标。
- 同路径直连、关闭 FEC、自适应 FEC 和 kcptun 固定 RS 的业务对照待测。需按相同负载采样业务 P95/P99、最长卡顿、网卡字节、连接数、吞吐、CPU 与 Netty 堆外内存。真实链路数据用于决定是否引入冷启动冗余、双校验或选择性重传。

## UDP 端口跳跃

当前类：

```text
org.rx.net.udp.UdpPortHoppingConfig
org.rx.net.udp.UdpPortHoppingMode
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
3. 控制模式有 `RSS_RPC`、`SOCKS5_COMPAT`、`AUTO`。枚举里的 `RX_SOCKS5_BATCH` 与 `SOCKS5_COMPAT` 一样直接放弃 RPC group，没有 batch 打开实现。
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
org.rx.net.udp.UdpHolePunchServer
org.rx.net.udp.UdpHolePunchClient
org.rx.net.udp.UdpHolePunchSession
org.rx.net.udp.UdpHolePunchPackets
```

基本流程：

```text
1. 两端都向 rendezvous server 注册 roomId + peerId。
2. server 记录双方公网观测 endpoint。
3. client 拿到对端 observed endpoint 后，同一本地 UDP 端口连续发送 DirectProbe。
4. 任一端收到 DirectProbe 后回 DirectProbeAck。
5. 收到第一发 `DirectProbe` 就完成 `directFuture` 并建立会话，同时回 `DirectProbeAck`。ACK 也会完成尚未完成的 future。之后直接向 `directRemoteEndpoint` 发送。
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
1. DNS/NTP 属于标准协议，不要叠加 UCMP/RDNT/UdpResilience header。
2. 名称服务和 multicast 场景要显式限制 TTL、网卡和监听范围。
3. DNS 查询链路避免阻塞式 InetAddress 解析进入 I/O 热路径。
```

## 推荐组合

游戏 UDP 默认低延迟：

```text
FEC 3:1
多倍发包 multiplier=1
maxResiliencePayload=1200
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
2. maxResiliencePayload 默认 1200。
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

## 当前没有的能力

```text
1. 没有 Reed-Solomon，也没有每组多于 1 个 parity。连续丢多个数据包时，单条 XOR 组只能恢复 1 个。
2. UdpResilience 与 UdpRedundant 是两套头。前者的倍率是固定配置；按丢包率调整倍率只在 UdpRedundantStats。
3. udp2raw 的能力协商没有 FEC。UdpResilience 不会自动装进 udp2raw。
4. RX_SOCKS5_BATCH 没有批量打开 relay 的实现。
5. 指标仍分散在 socks.udp、socks.udp2raw、net.udp 等前缀，没有统一 facade。
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
