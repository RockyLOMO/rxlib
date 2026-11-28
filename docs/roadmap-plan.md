# rxlib 未来规划与待实现计划（Roadmap）

> 本文档仅记录**尚未实现、待验证或未来规划**的演进事项与设计思路。2026-11-28 第一轮与第二轮 Review（P1 §3–§5）的工程落地项——含 Nameserver 强制认证 v2 AEAD 与共享 `NodeRegistry` 子系统会话隔离、具体会话 UDP 路径绑定、统一 `PathAttempt` 原子取消、`RendezvousService`/`PeerUdpPuncher` 资源预算、公开 `RrpServer`/`RrpClient` 空 token fail-closed、`EgressCodec` 恢复旧 HTTP 随机 Host/Path/Query 与请求/响应头及 `AUTO` 合并读修复、RSS 全局/每上游/CLI 全量 `EgressConfig` 公共化、以及 **RSS↔RSS 私有增强 TCP 链路统一迁至 `Transport Core v2`（`TransportCoreCarrier`，删除 `RssHopAes`/`RssTcpCodecSelector`/`SocksAdaptiveHandshake` 并彻底消除 `AUTO` 业务 `zlib` 歧义）**——均已完成编码与双 JDK 28 类 373 项 `PARANOID LEAK=0` 回归及公网真机验证，实测数据见 [测试与基准记录](test-benchmarks.md) §6–§7。

---

## P1 · 近期优先（真机验收与运维切换）

### 1. 真实外部载体、多上游切换与长期 Linux Native `epoll` 验收
- **外部载体实机抓包**：覆盖 `RssFixedUdpEntry`（外部 `udpclient` + 外部 `udp2raw`）、`RssSocksCarrier`（嵌套 SOCKS5 如 Hysteria2 `CONNECT` + `UDP ASSOCIATE`），以及多上游混配下的故障切换、热切排空与回滚（统一 `EgressCodec` `NONE` / `ZLIB_STORED` / `HTTP` 与 Server `AUTO` 已于 2026-11-28 在高丢包 VPS `182.255.85.247` 完成实测）。
- **Linux `epoll` / `SO_REUSEPORT` 长期压测与应用层实测**：
  - 2026-11-28 已在 `182.255.85.247` 完成短期 4-profile + `SO_REUSEPORT=2` + SS 用户限速热更新、以及统一 `EgressCodec` + `NodeRegistry`/`RendezvousService`/`RrpServer` RAW Relay 实测；后续补充长时间稳态压测、对比 `kcptun` 的多随机种子 Linux 基准，以及真实 QUIC/HTTP3、外部 UDP 应用与 WoW 3.3.5a 公网联机验证。
- **更多双 NAT 组合（全锥/受限锥 NAT）公网打洞直连验收**：
  - 已验证同网多网卡直连优先（`AnZhi-PC → Xsy-PC`，`17.43 Mbps` + UDP `6.63 ms` 升轨 + 断洞无损修复，VPS 中继 `0 B`）与跨运营商双 NAT（`YOUFAN-PC` 端口受限锥型 NAT ↔ `AnZhi-PC` 对称型 NAT，RFC 5780 双向诊断并平稳按需降级至 E2E AEAD RRP RAW Relay）；后续待在**两端均为锥型 NAT** 的独立运营商链路补充公网跨 ISP UDP 打洞升轨直连与中继退避统计。
- **`RssDatagramFragmenter` 边界与物理内存实测**：
  - 验证 Linux Pooled Direct Allocator 在高丢包乱序与 OOM 预算下的 backing-capacity 压缩表现。
  - 验证多分片写出时前置分片失败的 Write Promise 聚合行为，并补齐 `MTU=256`、64 分片上限及魔数转义边界测试。

### 2. 生产运维切换（6896 下线 `kcptun` / WoW TLS 验收）
- **6896 (`rGrey`) 切换**：在确认 `udp2raw` 与 `Adaptive UDP` 链路稳定后，将生产 `6896` 切至 `ssCipher: chacha20-ietf-poly1305` + `udpHop: false` + `main-tun`（直连 `udp2raw` `127.0.0.1:4080` / `9910`），下线 `client_linux_amd64` / `server_linux_amd64`（`kcptun`）及其保活 cron（保留 `udp2raw` 保活），对比 `6896` 与 `6898`（跳端口 `daily`）双端抓包。
- **WoW 直连端点**：完成生产 TLS 证书与公网游戏登录全链路验证；后续按需评估 IPv6 支持。

---

## 2026-11-28 第二轮 Review 与后续实施计划（实施状态）

源码基线：[7b858624](https://github.com/RockyLOMO/rxlib/commit/7b858624f4b37676ce3994e7c1390b91a370cd72)；对比重构前 [6af56245](https://github.com/RockyLOMO/rxlib/commit/6af562450dca67d67884f19119b66213de70fe9f)；本轮落地提交：`29bd0e65`..`0c47859f`。上一轮详细设计 [noderegistry-nat-relay-plan.md](noderegistry-nat-relay-plan.md) 为历史设计基线；本节记录 P1 §3–§5 实施状态及仍留待长期/运维验证的剩余项。测试实测数据集中在 [test-benchmarks.md](test-benchmarks.md) §6–§7。

本轮用户决策：恢复 HTTP Pseudo 原有随机 Host/URL Path/Query、请求头与响应头生成行为，沿用旧内置参数而不要求用户配置自己的域名，公共 EgressCodec 继续作为唯一实现；RSS 私有增强链路统一 Transport Core；RRP UDP over TCP 记录后续实施，本阶段不开发。不恢复旧私有 wire、RSZ、UdpClient/UdpHolePunch* 或独立 HTTP Pseudo handler。全局限速旧问题继续暂缓。优先正确性/安全/稳定性，再减少实际公网流量和 CPU；资源有界，RSS Server 1vCPU/768MiB 整机约束不变。

### P1 · 3. 上一轮 Review 的正确性、安全和资源边界

- [x] **Nameserver 强制认证传输**（`29bd0e65`）：`NameserverImpl` / `NameserverClient` / 副本连接在启用凭据或 `NodeRegistry` 时强制使用 v2 AEAD（`authToken` / `replicaAuthToken`），凭据禁止明文会话传输；副本鉴权绑定身份与来源白名单；共享 `NodeRegistry` 通过子系统会话（`authenticateAndAttachSession`/`detachSubsystemSession`）隔离 `NameserverImpl` / `RendezvousService` / `RrpServer`，单子系统下线不误踢其他子系统，禁用/改密跨子系统立刻吊销；标准 DNS TCP/UDP 保持不变。
- [x] **具体会话的 UDP 路径绑定**（`fb06888a`）：`PathSelector` / `PeerEndpointAcceptor` 将已验证 UDP 路径从单纯 `nodeId` 键升级为复合键 `(initiatorNodeId, correlationId/attemptId)` + 服务名 + 有效期 + 会话代际绑定，并消除 `punchSessionId` 与 `peerIdTag`/`SelectedConnection` 构造时间错位；路径先到/会话先到均正确，禁止旧路径跨会话复用，有界缓存并在撤销/断开/过期时清理。
- [x] **RSS AUTO 业务 zlib 歧义**（`ecc594b2` + 真机 `182.255.85.247:18915` 实测）：通过 §5 的 `TransportCoreCarrier`（`RXTC` v2 统一握手帧在 `EgressCodec` 内层、业务 `zlib` 之前出现）彻底消除 `AUTO` 识别出口 `ZLIB_STORED` 与无加密内层业务 `zlib` 的首字节歧义；单元测试与公网真机 3 组无 AEAD `plain + zlib 3`（`NONE`/`ZLIB_STORED`/`HTTP_RANDOM`）在单端口 `AUTO` 下全部无歧义通过且 `LEAK=0`。
- [x] **PathAttempt 生命周期**（`fb06888a`）：统一 `PathAttempt` 原子取消/失败/超时/所有权转移，覆盖未赢 TCP 连接、UDP 检查、未消费 RAW carrier、定时器、pending future、`inFlightClients`/`uncheckedReasons` 并发边界，以及 `SelectedConnection.close()` 联动清理；专项测试验证调用方取消、晚到协调、Relay 后握手失败与短连接风暴无泄漏。
- [x] **打洞资源上限**（`fb06888a`）：`RendezvousService` 同步限制 initiator / target / tenant 并发与配额并在邀请投递失败或过期时回收；`PeerUdpPuncher` 为动态候选、在途会话、检查任务、nonce/ACK 状态增加独立条目与字节上限，零计数条目自动回收，拒绝未授权反射/扫描。
- [x] **公开 RRP 的空 token 安全边界**（`29bd0e65`）：`RrpServer` / `RrpClient` 独立构造时即强制校验：公网监听或启用 RAW Relay 禁止空 `authToken`（fail-closed），仅显式受控 loopback 场景允许本地测试豁免；未认证帧与反序列化前即校验截断。
- [x] **AUTO/HTTP 首包及资源边界**（`fc4777db`）：修复 `EgressAutoDetector` 按整个可读缓冲区误判 `maxHeaderBytes` 的合并读 bug，严格区分首部预算与 payload 预算；补齐写 promise 聚合、慢首部超时、EOF/FIN、分片/空帧、解码输出上限与失败关闭测试。
- [x] **直连优先与路径恢复**（`fb06888a`）：保留 16 TCP / 32 UDP 候选与 64 KiB 探测预算默认基线；实现多网卡轮询公平性截断（避免次网卡被主网卡多地址挤掉）、通配绑定 `UDP_MAPPED` 优先级加权、网络 generation 变更触发缓存重验；可用直连时不创建 Relay。
- [x] **验收补齐（双 JDK 28 类超集 + 30 轮 HTTP 基准 + 跨网双 NAT 诊断）**（`0c47859f` + §7）：已完成 JDK 21 与 Temurin JDK 8 全量 28 类 373 项 `PARANOID LEAK=0` 超集回归；完成 `182.255.85.247` 30 轮 `AUTO` 出口（`NONE`/`ZLIB_STORED`/`HTTP_FIXED`/`HTTP_RANDOM`，含小包交互/4 并发/大流）与 6 组 RSS v2 真机实测；完成 `YOUFAN-PC`（PRC NAT）↔ `AnZhi-PC`（Symmetric NAT）RFC 5780 诊断与双向同时打洞/按需中继实测。
- [ ] **验收遗留（需长期窗口 / 外部硬件条件）**：① 1024 在线节点 + 慢消费者 + 多并发 Relay 的 **≥24h 稳态压测**（按 `768 MiB` 整机红线持续采样峰值 RSS/堆外/cgroup/CPU/IP 字节）；② **两端均为锥型 NAT** 的独立运营商公网跨 ISP UDP 打洞直连升轨验收；③ 保留 §1 的外部载体（`udpclient`/Hysteria2）、multi-upstream、QUIC/HTTP3、WoW 及 SS 生产切换验证；不修改全局限速旧问题，不在无实测瓶颈时重写 FEC。

### P1 · 4. HTTP 出口能力恢复及重构遗漏核对

依据旧 [HttpPseudoHeaderEncoder.java（重构前）](https://github.com/RockyLOMO/rxlib/blob/6af562450dca67d67884f19119b66213de70fe9f/rxlib/src/main/java/org/rx/net/HttpPseudoHeaderEncoder.java)、[EgressConfig.java](https://github.com/RockyLOMO/rxlib/blob/fc4777db/rxlib/src/main/java/org/rx/net/EgressConfig.java) 与 [EgressCodec.java](https://github.com/RockyLOMO/rxlib/blob/fc4777db/rxlib/src/main/java/org/rx/net/EgressCodec.java)，已在单一公共 `EgressCodec` 内全部落地（提交 `fc4777db`）：

| 能力 | 实施状态 | 说明 |
|---|---|---|
| 随机 Host、URL Path、Query | **已恢复（`fc4777db`）** | 恢复旧内置随机 Host/URL Path/Query 参数池，固定 `httpHost`/`httpPath` 保留为显式选项（`EgressConfig.clientHttpFixed`）；不参与真实 DNS/路由/TLS 校验 |
| GET/POST 混用 | **已恢复（`fc4777db`）** | 私有 HTTP 随机模式恢复 80% `GET` / 20% `POST` 选择，同步覆盖 `EgressAutoDetector` 与 HTTP decoder 识别及严格上限校验（明确非标准通用 HTTP 语义） |
| User-Agent / Accept / Connection | **已恢复（`fc4777db`）** | 恢复旧请求头模板（Chrome 137 `User-Agent`、`Accept`、`Connection: keep-alive`） |
| Date / Server | **已恢复（`fc4777db`）** | 恢复响应头模板（实时 UTC `Date` 与 `Server: Tengine`） |
| HTTP 二进制 Body / Content-Type | **保持方案 A** | 统一保持 `Content-Type: application/json` + 真实 `Content-Length` + 可选 `deflate` 二进制 Body 私有约定 |
| 纯 zlib-stored / RSZ | **保持纯 RFC 1950** | 不恢复 `RSZ` 头 |
| 旧 RRP 连接清理与失败释放测试 | **已补齐（`29bd0e65`）** | 对照 `TransportSession` 生命周期在 `RrpIntegrationTest` 补齐 SOCKS 认证、连接失败、重连清理、远端绑定失败重试、慢消费者背压与零泄漏用例 |
| 旧 Adaptive + 实际压缩边界测试 | **已补齐（`fc4777db` / `9df28529`）** | 在 `EgressCodecTest` 补回 `compressionRunsOnceAboveAdaptiveStreamAndEgressWrapsOnlyPhysicalTcp` 集成回归 |
| RSS 配置公共化 | **已落地（`fc4777db`）** | 全量透传至 `RssClientConf`、`RssTransport`、`RssClient`、`BoundRoute`、`OutboundProfiles`、`TcpWarmPoolKey`、`UpstreamSupport` 与 `RssServer` CLI，第三方标准 SOCKS5 禁止非 `NONE` 出口 |

- [x] **恢复旧 HTTP Pseudo 内置随机参数与请求/响应头**（`fc4777db`）：不要求用户配置自有域名，不恢复旧 handler/旧 wire，固定模式保留为显式选项，Host 仅作私有报文头字段绝不驱动真实拨号或第三方访问。
- [x] **保持方案 A 与严格边界校验**（`fc4777db`）：`GET`/`POST` 均遵循方案 A `Content-Type: application/json` + 精确 `Content-Length` + 可选 `deflate`，不放宽认证、头/体长度或错误关闭策略。
- [x] **RSS/RPC/RRP 共用完整 `EgressConfig` 与实测对照**（`fc4777db` + §7.3）：全量透传配置、热更与 `TcpWarmPoolKey` 隔离；完成公网 30 轮 `NONE` / `ZLIB_STORED` / `HTTP_FIXED` / `HTTP_RANDOM` 对照（`NONE` 零额外开销，`HTTP_RANDOM` 相对 `NONE` 额外线字节 `+3.56%`、`LEAK=0`）。
- [x] **补齐旧 RRP 断线/失败/背压与 Adaptive+压缩边界回归**（`29bd0e65`、`fc4777db`、`9df28529`）。

### P1 · 5. RSS 私有增强链路统一 Transport Core

仅覆盖 **RSS↔RSS 私有增强 TCP 链路**；标准 Shadowsocks、标准 SOCKS5、第三方标准 upstream、标准 DNS 与原生 UDP（`DatagramTransport`/`RssDatagramHandler`）线协议完全不变。

- [x] **梳理业务矩阵并实现 `TransportCoreCarrier`**（`ecc594b2`）：`TransportProtocol` v2 扩展 `FLAG_RAW_STREAM`、`FLAG_UDP_HOP`、2-bit profile（`plain`/`daily`/`weak`/`game`）与 `HelloAck.serverUdpAliasPort`，首字节即 fail-fast 校验 `"RXTC"` 魔数。新建 `org.rx.net.transport.core.TransportCoreCarrier` 透明承载 SOCKS5 握手与字节流，不引入 RPC 序列化、额外线程或额外 TCP Socket，完整支持 4 档 profile、`udpHop`、`udpMtu`、固定 `adaptiveUdpClient`、嵌套 `RssSocksCarrier`、`TcpWarmPoolKey` 预热池、half-close、背压与 SS 用户带宽限速。
- [x] **统一认证、单层 AEAD、业务压缩分层与清理旧实现**（`ecc594b2`）：私有入口强制 `FLAG_AUTH` + `NonceReplayCache` 防重放；受保护端口使用单一 `TransportAeadCodec`（基于 `RSS_TCP_AES_KEY` PSK 或认证密码派生）取代 `RssHopAes`，业务 `zlib 1..9` 严格位于 AEAD 内层与 `AdaptiveTcpHandler` 之上、出口 `EgressCodec` 仅位于最外层物理 TCP 一次；删除已取代的 `RssHopAes`、`RssTcpCodecSelector`、`SocksAdaptiveHandshake` 及其旧测试，新增 `TransportCoreCarrierTest`（8 项专项回归）。
- [x] **双 JDK 回归、公网真机验证与 `rss-21` 合入**（`ecc594b2`..`0c47859f` + §7.4）：JDK 21 与 JDK 8 全量 28 类 373 项 `PARANOID LEAK=0` 通过；`182.255.85.247:18915` 实测 6 组场景（无 AEAD `plain + zlib 3` 三出口过 `AUTO` + `daily`/`weak`/`game` 全部升轨 `usingUdp=true`）零误判、零泄漏；低配 RSS Server 未启用 `NodeRegistry`/Rendezvous/RRP 时零新增常驻资源与线程。
- [ ] **外部载体真机联调（合并归口至 §1）**：外接 `udpclient`/`udp2raw` 与嵌套 Hysteria2 SOCKS5 实机抓包对照改造前后数据，归口至 §1 统一验收。

## P2 · 中期演进（待定治理项）

### 6. 现有全局流量整形（`NetworkFlowControl` `GlobalChannelTrafficShapingHandler`）治理（暂缓）
- **现状**：为避免影响现有线上默认行为，本次未修改全局 `GlobalChannelTrafficShapingHandler`（用户级带宽限速已通过独立 `BandwidthLimiter` / `TcpBandwidthPacer` 实现，RRP RAW Relay 已具备独立 4 级待发字节预算与轮询调度）。
- **已识别风险**：`refresh()` 全量遍历更新、高并发连接下共享写缓冲延迟、以及与 `TcpBackpressureHandler` 的叠加行为；后续仅在开启全局限速且出现真实瓶颈时再做专项重构。

---

### 7. RRP UDP over TCP（用户明确记录，后续再实现）

当前 RRP 的 SOCKS 反代公网监听为 TCP，RRP transport 为 pure TCP；ACTION_RAW_* 是可靠逻辑字节流，内网 SocksProxyServer 支持 UDP 并不等于公网具备完整 UDP ASSOCIATE 或 UDP 反代。依据 [RrpConfig.java L34-L49](https://github.com/RockyLOMO/rxlib/blob/7b858624f4b37676ce3994e7c1390b91a370cd72/rxlib/src/main/java/org/rx/net/socks/RrpConfig.java#L34-L49)、[RrpServer.java L709-L727](https://github.com/RockyLOMO/rxlib/blob/7b858624f4b37676ce3994e7c1390b91a370cd72/rxlib/src/main/java/org/rx/net/socks/RrpServer.java#L709-L727)、[RrpClient.java L768-L777](https://github.com/RockyLOMO/rxlib/blob/7b858624f4b37676ce3994e7c1390b91a370cd72/rxlib/src/main/java/org/rx/net/socks/RrpClient.java#L768-L777)。

后续优先评估用户提出的 UDP over TCP 方式，而非现在实现独立公网原生 UDP Relay。**本项仅记录，当前实施阶段不开发，不作为 §3–5 的阻塞条件**。在 RRP 已有认证可靠 carrier 上评估数据报封装，保持每个原始 datagram 边界、双向关联和会话生命周期；需要时补齐标准 SOCKS5 UDP ASSOCIATE 的公网数据报入口与内网实际 UDP 出口，严格限制目标授权、端口、数据报长度、待发字节、任务、关联数、超时、断开回收及慢消费者影响。复用公共 DatagramTransport 做原生 UDP 端点 I/O、复用现有 RRP/Transport Core 做可靠承载，不恢复 UdpClient、不另造可靠协议。

UDP over TCP 会改变传输时延和丢包行为，TCP 丢包可造成队头阻塞；不保证游戏、语音、QUIC 延迟与原生 UDP 相同。默认关闭，必须显式启用；直连原生 UDP/打洞可用时仍优先直连，公网业务中继按需。不要将独立数据报和 Adaptive UDP 可靠流混淆，也不要为 TCP 中继再叠加一套 Adaptive FEC。原生 UDP Relay 是否单独开发留待真实业务需求决定。

后续独立验收：UDP 双向数据报边界、多个关联、IPv4/IPv6、SOCKS5 UDP ASSOCIATE、授权撤销、拥塞/队头阻塞、过期数据、资源上限、TCP 断开及真实应用延迟；与直连原生 UDP 对比公网字节及 CPU。具体 wire、API、队列策略和默认阈值待后续专项方案，不在本轮提前固化。

---

## P3 · 长期备选 / 暂缓项（按真实瓶颈触发）

- **抗突发丢包编解码**：仅当单 XOR FEC 在 25% 名义冗余预算内无法满足突发丢包目标时，再评估 RS 8:2 或交织 XOR（Interleaved XOR）。
- **TCP 补传有界 UDP 快速重传**：此前独立 Repair Wire 候选已测试淘汰；若未来重启需引入独立能力协商字段。
- **出站伪装与配置扩展**：`egress.udpCodec` 除 `none` 外的编码格式、`EgressCodec` 后续扩展协议（如真 TLS / HTTP2 外壳）、以及 `socksServers` 配置项更名为 `upstreams`。
- **Shadowsocks 服务端先发 FIN 半关闭**：当前完整支持客户端先发 FIN 的单向半关闭；若需支持后端先 `shutdownOutput` 且客户端持续读取的双向半关闭，需单独设计。
- **高频路径微优化与结构整理**（当前无瓶颈证据，暂缓）：大包聚合/零拷贝批量写、`SaltHistory` 多域 O(1) 淘汰、以及 P3 级包名重构（`net.handler`/`net.flow`/`net.rss.security`/`net.socks.udp2raw` 等）。

> 注：2026-11-28 第二轮 Review 的 P1 §3–§5 代码实现、双 JDK `PARANOID` 28 类超集回归及公网真机验证已落地（提交 `29bd0e65`..`0c47859f`）；本文件仅保留尚未执行的长期压测、外部载体/生产运维切换以及 P2/P3 演进项。
