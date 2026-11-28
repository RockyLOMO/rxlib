# `org.rx.net` 整体架构介绍

> 面向 **JDK 8 / JDK 21** 的高性能 Netty 底层网络与传输栈。严格遵循零分配热点、堆外 `PooledByteBufAllocator`、EventLoop 线程亲和与单核 / 768 MiB RSS Server 内存预算基线。

![org.rx.net 整体架构总览](org-rx-net-architecture.png)

> 矢量高清原图：[org-rx-net-architecture.svg](org-rx-net-architecture.svg)

---

## 核心特色：多路径有条件可靠传输与自适应双轨引擎

`org.rx.net` 的核心差异化能力在于将 **RPC、Nameserver 副本同步与 RSS 代理** 统一收敛到 `Transport Core` 之上，形成「**UDP 优先加速 → TCP 自动补传与回落**」的双轨传输体系，并明确区分不同路径的可靠性边界：

1. **RPC / Nameserver / RRP / Rendezvous / RSS 统一接入与五维正交传输**：`Remoting`、`SocksProxyServer`、`NameserverImpl`（强制认证 v2 AEAD）、`RrpServer`/`RrpClient`、`RendezvousService` 全部共享 `Transport Core v2` 与统一 `SecurityMode`（默认固定 `FAST` = `AES-128-GCM + HMAC-SHA256`，16 B 握手标签与每记录 2 B 长度 + 16 B GCM Tag，无替代密码/MAC 选型）和 `HandshakeMode`（RPC/RRP/Nameserver/Rendezvous 默认 `CORE` 完整认证握手；RSS 默认 `LIGHTWEIGHT` 零新增 RTT：纯 `plain` 无 AEAD 直走标准 SOCKS5 且零重放缓存，非 AEAD `plain+zlib`/增强模式经 SOCKS5 `0x8c` 单段捎带防重放扩展且 `AUTO` 零歧义，受保护端口自动单次 `RXTC` v2 AEAD 握手）；支持 `LocalAddress`/`LocalChannel` 零 Socket 虚拟载体接入。标准 Shadowsocks、标准 SOCKS5、第三方标准上游、标准 DNS 与原生 UDP 数据报保持原线协议不变。
2. **Adaptive UDP 优先直传**：通过 `AdaptiveUdpPort` + `RssDatagramHandler`（按收益 LZ4 + 认证分片 + 异或 FEC / 弱网尾包探测 + 动态 PMTU / `CHUNK` 收缩）走 UDP 快轨，降低高丢包网络下的队头阻塞与尾延迟。
3. **有条件可靠交付（TCP 兜底补传与按需公网中继）**：在存在可用 TCP 路径（直连 TCP 或按需开启的 RRP RAW Relay 逻辑流）的前提下，当 UDP 被限速、阻断或触发突发大流回落时，`AdaptiveTcpStream` 无缝回落 TCP 补传与直通，保障有序可靠送达；当两端完全断网或所有候选路径与中继均不可达时显式失败报错，普通 UDP 仍为 best-effort（非 100% 必达）。
4. **公网地址发现、节点注册表、双 NAT 打洞与直连优先选路**：通过 `AddressDiscovery`（多源投票公网 IP + 本机非回环多网卡候选）与加固后的 H2 冷存 `NodeRegistry`（分片锁、单调代际 `authGeneration`、O(1) 租户配额、子系统级会话挂载、认证并发/速率限流、多监听器即时吊销）统一承载 `NameserverImpl`、`RendezvousService`（受控协调 + 防放大 `UdpEndpointObserver` + RFC 8445 触发检查 `PeerUdpPuncher`）与 `RrpServer` RAW 中继；`PathSelector` 严格执行**直连优先、最小化公网中继流量**策略（优先尝试局域网/公网直连 TCP 与直连/打洞 UDP，直连成功时零 Relay 流开启，仅在直连 TCP 失败/超时时按需打开 RRP RAW Relay 逻辑流；旧版 `UdpClient` / `UdpHolePunch*` 已彻底退役删除，详见 [punch.md](reference/net/punch.md)）。

---

## 分层设计总览

### Layer 1 · 应用与协议服务层（Application & Service Layer）

| 模块 | 包路径 | 核心组件 | 职责 | 详细文档 |
| --- | --- | --- | --- | --- |
| **RPC 远程调用** | `org.rx.net.rpc` | `Remoting`、`RpcClientConfig`、`RpcServerConfig`、`RpcTransportClientPool`、`FuryRemotingCodecFactory` | 基于 Transport Core 的双模式 RPC（Pool 连接池 / Stateful 有状态事件订阅），支持自动重连事件重订阅、非幂等调用断线 fail-fast 防重复执行与敏感方法脱敏 | [rpc.md](reference/net/rpc.md) |
| **代理、RRP 与隧道中继** | `org.rx.net.socks` | `SocksProxyServer`、`ShadowsocksServer`、`RrpServer`/`RrpClient`、`RssSocksCarrier`、`UdpRelayGroupManager`、`Udp2rawHandler`、`httptunnel` | SOCKS5 / Shadowsocks 入站（支持 `SO_REUSEPORT`、SS 用户级共享双向带宽限速热更新与优雅排空热切）、直连 RSS / 嵌套 SOCKS5 / 外部 `udp2raw` & `udpclient` 载体、UDP 租约组中继，以及基于 Transport Core v2 的 RRP 端口映射与 `NodeRegistry` 授权 RAW 逻辑流中继（4 级待发预算、轮询防队头阻塞、吊销即断、`LocalAddress` 零 Socket 桥接） | [socks.md](reference/net/socks.md) · [rss-transport.md](rss-transport.md) · [punch.md](reference/net/punch.md) |
| **DNS、Nameserver 与节点注册表** | `org.rx.net.dns` / `org.rx.net.nameserver` / `org.rx.net.registry` | `DnsServer`、`DnsClient`、`DoHClient`、`DnsQueryUpstream`、`DnsRecordExchange`、`NameserverImpl`、`NameserverClient`、`NodeRegistry` | 异步递归 DNS、DoH、非地址 RR TTL 实时衰减缓存 / 字节预算 / 校验 / EDNS0 / 上游连接池、过期降级缓存与熔断；基于已鉴权 Transport Core RPC 的 Nameserver 副本同步与可选 `NodeRegistry` 权威源；加固版 H2 冷存 `NodeRegistry`（子系统级共享在线会话与跨子系统即时吊销） | [dns.md](reference/net/dns.md) · [nameserver.md](reference/net/nameserver.md) |
| **双 NAT 协调与直连选路** | `org.rx.net.rendezvous` | `RendezvousService`、`UdpEndpointObserver`、`PeerUdpPuncher`、`PathSelector` | 受控公网协调与候选交换、防放大 HMAC 鉴权 UDP 公网端点观测、RFC 8445 触发检查与被动应答滞留 UDP 打洞、多网卡/公网直连优先选路与按需 RRP RAW Relay 兜底；支持在 `RssRuntime` 中通过 `nodeService`（默认关闭）单实例 opt-in 启用与事务式热重载 | [punch.md](reference/net/punch.md) |
| **HTTP、地址发现与探测** | `org.rx.net.http` / `org.rx.net.ntp` / `org.rx.net` | `HttpServer`、`HttpClient`、`PublicIpObserver`、`AddressDiscovery`、`NtpClient`、`PingClient` | Netty 异步 HTTP/TLS 服务端与客户端；多源独立 HTTPS 投票直连公网 IP 观测与统一候选地址发现；时钟同步与连通性探测 | [http.md](reference/net/http.md) · [ntp.md](reference/net/ntp.md) |
| **路由与地理匹配** | `org.rx.net.support` | `UltraDomainTrieMatcher`、`V2RayGeoManager`、`UpstreamSupport`、`EndpointTracer` | 双数组 Trie 域名高速分流、GeoIP/GeoSite 条件增量下载与原子落盘、单实例匹配器代际校验与 `BLOCK` fail-closed、权重上游路由与健康检查 | [support.md](reference/net/support.md) |

---

### Layer 2 · 统一传输核心与自适应双轨引擎（Unified Transport Core & Adaptive Engine）

1. **统一传输核心（`org.rx.net.transport.core`）**
   - `TransportClient` / `TransportServer` / `TransportSession` / `DefaultTransportSession` / **`TransportCoreCarrier`**：统一 RPC、Nameserver 副本同步、RRP、Rendezvous 与 **RSS↔RSS 私有增强 TCP 链路** 的建连、重连、容量控制、心跳保活与会话生命周期；支持 `LocalAddress`/`LocalChannel` 虚拟载体承载端到端加密业务会话，以及 `TransportCoreCarrier` 原生透明承载 SOCKS5 原始字节流（零 RPC 序列化与额外线程）。
   - `TransportProtocol`（v2）：精简二进制 `Hello` / `HelloAck` 握手（首字节 fail-fast 校验 `"RXTC"` 魔数），协商 `sessionId`、`serverNonce`、`FLAG_ADAPTIVE_UDP`、`FLAG_AUTH`、`FLAG_ENCRYPT`、`FLAG_COMPRESS`、`FLAG_RAW_STREAM`、`FLAG_UDP_HOP`、2-bit profile、`udpMtu` 与 `serverUdpAliasPort`；强制双向 HMAC-SHA256 鉴权与 `NonceReplayCache` 防重放，握手后开启每会话/每方向独立单一层 AES-128-GCM（`TransportAeadCodec`）与可选业务 `zlib` 压缩（先压缩后加密），并通过 `FRAME_READY` 屏障消除 `AUTO` 出口识别与内层业务 `zlib` 的歧义。
   - `DatagramTransport`：零分配原生 UDP 收发封装。
   - 详见 [transport.md](reference/net/transport.md)。

2. **自适应双轨传输与数据报引擎（`org.rx.net.rss.{profile, tcp, udp}`）**
   - `AdaptiveTcpHandler` / `AdaptiveTcpStream`：TCP 主通道 + 可选 Adaptive UDP 加速轨，支持乱序重组、弱网大流有界 TCP 直通回落与孤立尾包修复、动态 PMTU 探测与 `CHUNK` 收缩（按路径/方向有界缓存）、高 BDP 回落保护与优雅半关闭排空（`shutdownOutput()`）。
   - `AdaptiveUdpPort` / `RssUdpMtuBudget`：服务端/客户端复用 UDP 端口与路径 MTU 预算协商。
   - `RssDatagramHandler` / `RssDatagramCipher` / `RssDatagramMac` / `RssDatagramCompression` / `RssDatagramFragmenter`：统一负责普通增强 UDP 的按收益 LZ4 压缩、认证分片重组、异或 FEC（`AdaptiveUdpFec`）/ 动态冗余复制（`AdaptiveUdpReplication`）及外层 AES-128-GCM / MAC 封装。
   - Profile 体系：`plain`（纯直通/仅可选加密）、`daily`（日常均衡双轨+低开销 FEC）、`weak`（高丢包交互优先、大流直通 TCP 降放大与尾包探测）、`game`（低延迟动态 1x~3x 冗余复制）。详见 [rss-transport.md](rss-transport.md)。

3. **基础 TCP / SFTP 传输（`org.rx.net.transport`）**
   - `TcpServer`、`DefaultTcpClient`、`AbstractTcpReconnectClient`、`TcpChannelCodec`：原生 Netty TCP 服务端与自动重连客户端。
   - `SftpClient`：提供 SSHD SFTP 支持。旧版 `UdpClient` / `UdpHolePunch*` 与 `Hybrid*` 已彻底删除；`KcpClient` / `KcpClientConfig` 已归档。

---

### Layer 3 · 链路编解码、抗丢包与流控背压层（Wire Pipeline, Resilience & Flow Control）

- **协议标志与加密/出口包装管道（`org.rx.net`）**：
  - `TransportFlags` 与 `TransportCoreCarrier`（已取代旧版 `RssHopAes`、`RssTcpCodecSelector`、`SocksAdaptiveHandshake`）统一控制 RSS 私有增强 TCP 链路的端口级保护、单层 AEAD（基于 `RSS_TCP_AES_KEY` PSK 或认证密码派生）、业务 `zlib 1..9` 压缩与自适应 UDP 装配。
  - 统一公共 TCP 出口包装 `EgressCodec` / `EgressConfig` / `EgressPipeline`（取代旧版 `RssEgressCodec` 与 `HttpPseudoHeader*`）：支持 `NONE`（零额外开销）、纯 RFC 1950 `ZLIB_STORED`（无 `RSZ` 自定义头）与方案 A 零拷贝全双工 `HTTP`（同时支持旧版随机 Host/URL Path/Query、80% `GET` / 20% `POST`、Chrome 137 / `Accept` / `Connection` / UTC `Date` / `Server: Tengine` 模板与显式固定 Host/Path 模式，以及可选 `deflate` body）；Server 端支持单端口 1-shot `EgressAutoDetector`（区分首部与载荷预算、防合并读误拒与慢首部超时）按白名单 `allowedCodecs` 自动识别，严格位于加密层最外侧。
- **UDP 抗丢包与压缩（`org.rx.net.udp`）**：
  - `UdpResilience*` / `FecEncodeGroup` / `FecDecodeGroup`、`UdpCompress*`（LZ4 自适应旁路）、`UdpPortHoppingConfig`。详见 [udp.md](reference/net/udp.md) · [punch.md](reference/net/punch.md)。
- **流控、用户级带宽限速、背压与诊断（`org.rx.net`）**：
  - `BandwidthLimitConfig` / `BandwidthLimiter` / `TcpBandwidthPacer` / `BandwidthChannelHandler`：独立无侵入的按用户/作用域共享双向带宽限速组件（SS 用户 TCP+UDP 共享配额、支持热更新不重启监听、与 `Sockets` 多原因读暂停掩码协同）。
  - `NetworkFlowControl`、`TcpBackpressureHandler`、`UdpBackpressurePolicy`、`UdpFinalEgressGuardHandler`、`NetworkFlowDiagnostics`。详见 [flow-control.md](reference/net/flow-control.md)。

---

### Layer 4 · Netty 底座与内核原语（Netty I/O & Socket Foundation）

- **`Sockets`**：统一 Bootstrap 构建、`SO_REUSEPORT` 多核绑定、IPv4/IPv6 双栈、非阻塞 `DnsNameResolver` 与全局公网 IP 解析。
- **`EventLoopGroup`**：Linux 优先 Epoll，I/O 线程与业务线程池严格隔离。
- **`PooledByteBufAllocator`**：全链路 Direct Buffer 池化与零拷贝引用计数管理（回归测试强制开启 `PARANOID` 泄漏检测）。
- **`GlobalChannelHandler` / `FuryCodecSupport` / `AuthenticEndpoint`**：统一连接观测、零反射高性能序列化与凭据端点抽象。详见 [core.md](reference/net/core.md)。
