# rxlib 未来规划与待实现计划（Roadmap）

> 本文档仅记录**尚未实现、待验证或未来规划**的演进事项与设计思路。2026-11-28 第一轮、第二轮（P1 §3–§5，提交 `29bd0e65`..`0c47859f`）与第三轮（P1 §8–§12，提交 `3044e7c5`..`927475a6`）的工程落地项——含 Nameserver 强制认证 v2 AEAD 与共享 `NodeRegistry` 子系统会话隔离、具体会话 UDP 路径绑定、统一 `PathAttempt` 原子取消、`RendezvousService`/`PeerUdpPuncher` 资源预算、公开 `RrpServer`/`RrpClient` 空 token fail-closed、`EgressCodec` 恢复旧 HTTP 随机模板与局部继承修复、认证 fail-closed 与独立业务解压预算、**统一 `SecurityMode`（默认固定 `FAST` = `AES-128-GCM` + `HMAC-SHA256`）与 RSS 轻量握手（`HandshakeMode.LIGHTWEIGHT` 默认：纯 `plain` 无 AEAD 零 `RXTC`/零重放缓存，非 AEAD `plain+zlib`/增强模式经 SOCKS5 `0x8c` 零新增 RTT 捎带协商且 `AUTO` 零歧义）**、**`SocksRpcCapabilitiesCache` 单飞缓存 + `plain` UDP 私有 RSS 优先 RPC Relay Group（零专用 SOCKS5 TCP 控制连接）+ `UdpRelayGroupManager` 原子容量预留与幂等**，以及按需收缩的共享调度 TCP Warm Pool 与 `rpcMinSize=1`（支持 `0`）——均已完成编码、双 JDK 32 类 389 项 `PARANOID LEAK=0` 回归及公网高丢包 VPS A/B 实测，实测数据见 [测试与基准记录](test-benchmarks.md) §6–§8。本文件继续保留长期压测、外部载体/生产切换及 P2/P3 演进项。

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

## 2026-11-28 第三轮 Review · RSS 无损迁移、轻量握手与 RPC 降 RTT（实施状态）

源码基线：[7d1aefa6](https://github.com/RockyLOMO/rxlib/commit/7d1aefa67df199f27f80079b901958c87bf79e72)；本轮落地提交：`3044e7c5`、`086be044`、`927475a6`（实测数据见 [test-benchmarks.md](test-benchmarks.md) §8）。本节覆盖 §5 中“RSS 必须执行完整 RXTC 握手”的旧决定：保持标准 SOCKS5 业务协议、RSS 默认 `LIGHTWEIGHT` 轻量握手、RPC 降 RTT、私有安全传输默认固定 `FAST` 套件。

**用户明确目标**：RSS 保持标准 SOCKS5 业务协议及既有功能，统一公共传输组件不等于强制统一 wire/握手；相同功能、安全等级和网络条件下尽可能保持或减少 RTT、公网流量、CPU、内存，不因重构增加额外消耗。优先复用已有 SOCKS5 协商与共享 RPC 控制面，不默认启用额外加密、压缩、连接、线程或可靠流多路复用。RPC/RRP、Nameserver、Rendezvous 的既有认证传输与安全要求不放宽。RSS Server 1vCPU/768MiB 整机红线，公网业务 Relay 仍仅按需。

### P1 · 8. 功能无损与迁移缺陷修复

#### 8.1 功能保留矩阵（源码核对与单/双 JDK 及公网验收）

| 能力 | 当前状态 | 剩余要求 |
|---|---|---|
| 公共 HTTP 出口原有随机参数与请求/响应字段、固定模式、Body 可选 `zlib-stored`、AUTO | 已保留并修复 `EgressHttpDecoder.findHeaderEnd` 边界越界（`3044e7c5`），§7.3 / §8.2 已实测 | 无 |
| `plain`/`daily`/`weak`/`game`、实际 `zlib 1..9`、按目的端口保护/绕过、单层 `FAST` AEAD | 已完成 `LIGHTWEIGHT` vs `CORE` 同配置公网 A/B 实测（§8.2） | 混合大并发/多随机种子长稳压测归口 §1/§3 |
| Adaptive UDP/FEC、TCP repair、UDP hop/no-hop、MTU/PMTU、原生 UDP LZ4/FEC/AES、分片 | 双 JDK 32 类 389 项 `PARANOID LEAK=0` 全通，公网 `daily`/`weak` 实测通过 | 长期稳态压测归口 §1 |
| UDP Lease Pool/Relay Group、TCP Warm Pool、上游权重及健康、热更/排空/回滚 | §8/§10/§11 配置、认证、健康反馈、单调度按需预热池与原子容量已全部修复并单测覆盖（`086be044`、`927475a6`） | 无 |
| 外部 `udpclient`/`udp2raw`、嵌套 Hysteria2 SOCKS5 | 参数和适配仍在 | 归口 §1 真机抓包验收，作为 §2 切换前置条件 |
| 标准 SOCKS5/SS/第三方上游、DNS/Geo、SS 用户级限速 | 保留独立业务路径，修复 `SSTcpProxyHandler` `LocalChannel` 来源链接竞态（`3044e7c5`） | 无 |

#### 8.2 本轮 Review 缺陷及风险

- [x] **Egress 局部继承（`3044e7c5`、`927475a6`）**：`RssClientConf.Egress.tcpCodec`/`udpCodec` 默认 `null`（区分未设置与显式 `"none"`），实现 `global → user → upstream` 局部字段合并并按最终合并结果校验；拒绝客户端配置 `allowedCodecs`；统一 `docs/rss-transport.md` 与 `RssClientConf`/`RssServer` CLI 参数名（`maxHeaderBytes`/`maxBodyBytes`/`maxInflateBytes`/`detectTimeoutMillis` 及 `-tcpEgressMax*`）；补齐 `RssRound3ConfigAndAuthTest` 全局/用户/上游组合、JSON 往返及 `TcpWarmPoolKey` 隔离回归。
- [x] **认证兼容性与轻量策略（`3044e7c5`、`927475a6`）**：纯 `plain` 非 AEAD 无压缩直走标准 SOCKS5（不再强制 `RXTC` HMAC），非 AEAD `plain+zlib` 与增强模式经 `0x8c` 在认证密码上执行防重放 HMAC-SHA256，受保护端口与显式 `CORE` 强制认证 `RXTC` AEAD；启动校验明确必要密钥，禁止静默安全降级。
- [x] **业务认证与授权 fail-closed（`3044e7c5`、`927475a6`）**：修复 `RssServer` `defAuth.login` 错密误返 `ANONYMOUS` 改为返回 `null`；`Socks5PasswordAuthRequestHandler` 强制拒绝 `null` 与 `ANONYMOUS`（开启认证时）并校验 `ATTR_CORE_AUTH_USER` 与 SOCKS 用户名一致；`Socks5InitialRequestHandler` 严格执行 RFC 1928 方法匹配（向启用认证服务端仅提供 `NO_AUTH` 返回 `0xFF UNACCEPTED`）；共享 PSK 不可绕过用户授权；补齐错密、未授权方法、`0x8c` 重放零字节拒绝、Core-vs-SOCKS 身份不一致与 `RssAuthenticator.reload` 撤销专项测试。
- [ ] **外部载体验收（归口 §1）**：固定外部 UDP 映射（`udpclient`/`udp2raw`）与嵌套 Hysteria2 SOCKS5 的真实外部进程抓包验收归口 §1，作为 §2 生产切换前置条件。
- [x] **业务压缩和出口解码预算独立（P2，`3044e7c5`、`927475a6`）**：新增独立 `maxBusinessInflateBytes`（默认 4 MiB，`4 KiB..64 MiB`；`TransportConfig`/`SocksConfig`/`RssServer` `-maxBusinessInflateBytes`），与 `EgressConfig.maxInflateBytes` 彻底解耦；专项测试验证小 `EgressConfig.maxInflateBytes`（64 KiB）不中断 128 KiB 业务 `zlib` 流，同时小 `maxBusinessInflateBytes`（4 KiB）可靠拦截 64 KiB 高压缩比炸弹且 `LEAK=0`。
- [x] **TCP warm pool UDP 健康反馈（P2，`3044e7c5`、`086be044`）**：`Socks5WarmupHandler` 透传并绑定当前 route generation 的 `UpstreamSupport`，在 `TransportCoreCarrier` 中与 `Socks5ClientHandler` 等价上报 UDP 探测健康，并在 `UpstreamSupport.markClosed()` 后静默忽略旧代际事件，零额外探包。

### P1 · 9. RSS 轻量握手：保持 SOCKS5、无新增独立 RTT

#### 9.2 实施策略

- [x] **统一底层、可选择握手策略（`3044e7c5`）**：新增 `HandshakeMode`（`LIGHTWEIGHT` 默认用于 RSS，`CORE` 作为显式选择并保持为 RPC/RRP/Nameserver/Rendezvous 默认），复用于 `TransportCoreCarrier`/`TransportAeadCodec`/`AdaptiveTcpHandler`/`EgressCodec`，不恢复任何旧私有 wire 或旧 handler。
- [x] **标准 SOCKS5 边界（`3044e7c5`、`927475a6`）**：纯 `plain` 非 AEAD 无压缩直走标准 SOCKS5 握手（零 `RXTC`、零 `TransportCoreCarrier` 客户端 handler、零 `NonceReplayCache` 分配、零 deadline timer）；外部标准入口与第三方上游保持标准 SOCKS5 语义。
- [x] **增强链路最少必要协商（`3044e7c5`、`927475a6`）**：非 AEAD `plain+zlib` 与 `daily`/`weak`/`game` 经私有方法 `0x8c` 将 35 B 客户端扩展与 43 B HMAC-SHA256 服务端应答直接捎带在现有 RFC 1929 密码认证往返中（**零新增 RTT**，`Socks5PasswordAuthRequestHandler` + `LightweightServerExtHandler` 单 TCP 段写出并在重放/错密时零字节关闭）；受保护端口（`RSS_PSK_AES` / `SecurityMode.FAST`）或显式 `CORE` 执行单次认证 `RXTC` v2 AEAD 握手；`SocksProxyServer.coreReplayCache` 改为首次收到 `RXTC`/`0x8c` 时惰性分配。
- [x] **AUTO 不退化（`3044e7c5`、`927475a6` + §8.2）**：轻量路径内层首字节恒为 `0x05`（`nmethods 1..32`），业务 `zlib` 严格在 `0x8c` 认证完成后安装，与 `EgressAutoDetector` 的 `'P'`/`'G'`/`RFC 1950` 低 4 位 `8`/`'R'` 首字节完全互斥；单元与公网 `182.255.85.247:18960` 单端口 `AUTO` 实测 `NONE`/`ZLIB_STORED`/`HTTP_RANDOM` 下 `plain+zlib 3` 零歧义、零退化。
- [x] **预算及验证（`927475a6` + §8.2）**：单元验证精确往返次数少 1 RTT 且纯 `plain` `hasCoreReplayCache()==false`；公网高丢包 VPS `182.255.85.247` 5 组同配置 A/B 实测 `LIGHTWEIGHT` 较 `CORE` 稳定降低 **136.5–197.3 ms**（约 1 RTT），单连接线字节减少 **50.8–541.6 B**（`plain-NONE` `-39.8%`、`plain-HTTP_RANDOM` `-22.6%`），双端 `PARANOID LEAK=0`。

### P1 · 10. 复用 RPC 控制面减少 UDP 首包 RTT 与 TCP 控制连接

- [x] **能力缓存（`086be044`、`927475a6`）**：新增零线程有界单飞缓存 `SocksRpcCapabilitiesCache`（绑定对端 facade 实例与 token SHA-256 哈希，双检防并发重复穿透、60 s 正缓存 / 5 s 显式 `UNSUPPORTED` 短负缓存、绝不缓存瞬态异常、支持断线/健康翻转/能力不匹配/关闭即时失效），16 线程并发首查严格合并为 1 次 RPC。
- [x] **plain UDP 私有 RSS 优先 RPC Group（`086be044`、`927475a6` + §8.3）**：`SocksUdpUpstream` 在 `AUTO` 模式下对已认证私有 RSS 对端（`!isStandardSocks5 && facade != null`）让 `plain` UDP 同样优先 RPC Relay Group（零专用 SOCKS5 TCP 控制连接、缓存命中后仅 1 次 `open` RPC、同 association/policy/generation 跨目标复用零额外控制 RTT）；新增本地 `ControlReadiness` 提示使 RPC 不可达时 `plain AUTO` 0 ms 回退 `SOCKS5_COMPAT` 而增强模式严格 fail-closed；标准第三方 SOCKS5 `UDP ASSOCIATE` 与显式非 RSS `UdpLeasePool` 完整保留。
- [x] **传统 lease 按需保留与幂等保护（`086be044`）**：保留既有 `UdpLeasePool` 与标准 `UDP ASSOCIATE`；为 `UdpRelayGroupOpenRequest` 增加 `clientRequestId` 及服务端有界 15 s 幂等去重表，防止超时重试泄漏重复 relay group。
- [x] **安全和资源前置（`086be044`、`927475a6`）**：修复 `UdpRelayGroupManager.open` 容量竞态，改为原子 `reservedGroups` / `reservedRelays` + 每客户端 `clientGroupCounts`（归零自动移除）+ 绑定失败严格回滚；32 线程并发压测验证精确守住全局与每客户端上限且关闭后计数归零。
- [x] **验收（`927475a6` + §8.3）**：公网 `182.255.85.247` A/B 实测 `RSS_RPC_GROUP` 10 组仅触发 `1` 次 `capabilities()`、`0` 条专用 SOCKS5 TCP 控制连接（对比 `SOCKS5_UDP_ASSOCIATE` 8 条），缓存首包 P50 从 `1,015.9 ms` 降至 **`782.2 ms`**（`-23.0%`，P95 `-31.1%`），同关联跨目标复用 P50 仅 **`192.9 ms`**（1 次纯 UDP RTT，零额外控制 RTT），双端 `PARANOID LEAK=0`。

### P2 · 11. UDP/TCP/RPC 生命周期与资源优化

- [x] **UDP group 合法复用（`086be044`、`927475a6`）**：移除 `SocksUdpUpstream` 中向 Netty 全局 `ConstantPool` 持续泄漏的动态 `AttributeKey.valueOf(...)`，改为静态 per-channel `ATTR_SESSION_GROUPS`/`ATTR_SESSION_INITS` 映射，按 `(routeGeneration, endpoint, username, trafficUser, features, udpHop, effectiveMtu, controlMode)` 复合键隔离；同关联/策略/代际跨目标零开销复用，跨用户/策略/代际严格隔离。
- [x] **TCP Warm Pool 按需动态容量（`086be044`、`927475a6`）**：重构 `Socks5UpstreamPoolManager` 为单一共享惰性维护调度器（零 per-pool 定时器，全部关闭自动取消）、闲置超 `maxIdleMillis` 收缩至 0 停止无效建连、进程级总预算（`maxTcpPools=64`、`maxTotalWarmChannels=64`、`maxInFlightCreates=8`）、`TcpWarmPoolKey` 纳入 `handshakeMode`/`securityMode`/完整 `EgressConfig`，并暴露 `TcpWarmPoolStats`（含 `hit`/`miss`/`unusedExpired`/`createFailures`/`schedulerActive`）。
- [x] **RPC 池按需收缩（`086be044`、`927475a6`）**：`RpcTransportClientPoolImpl` 允许 `minPoolSize >= 0` 且将 `borrowTimeout` 收敛至 `requestTimeoutMillis`；`RssClientConf.rpcMinSize` 默认值由 `2` 降至 `1`（空闲连接减半并保持 0-RTT 热 RPC 与健康检测，同时支持显式设为 `0` 完全按需收缩）。
- [x] **状态开销（`3044e7c5`、`086be044`）**：无独立握手的 `plain` 路径零 `NonceReplayCache`/deadline/queue 分配；不引入每连接线程与默认全业务 TCP 多路复用。
- [ ] **长期统一验收遗留（归口 §1/§3）**：`1024` 节点 / `≥24h` `768 MiB` 整机红线长稳压测、外部载体（`udpclient`/`udp2raw`、Hysteria2）实机抓包与双锥型 NAT 跨 ISP 打洞留待对应环境窗口补齐。

### P1 · 12. 统一 SecurityMode：固定 FAST 算法套件

2026-11-28 用户决定：**所有 rxlib 私有安全传输默认 `FAST`，固定 `AES-128-GCM + HMAC-SHA256`，不再立项其他加密/MAC 算法或安全模式的对比验证**。本轮仅实现与验收 `FAST` 与 `NONE`；`NORMAL`/TLS 1.3 不新增实现，不影响既有标准 TLS 能力。

#### 12.1–12.3 实施与验收状态

- [x] **统一 `SecurityMode`（`NONE` / `FAST`）与五维正交配置（`3044e7c5`、`927475a6`）**：新增 `org.rx.net.SecurityMode`（默认 `FAST`），贯穿 `TransportConfig`、`SocksConfig`、`RssClientConf`、`RssTransport`、`RssServer`（`-securityMode`）与 `TcpWarmPoolKey`，与 `HandshakeMode`、`EgressCodec`、业务 `Compression`、`TransportProfile` 五维独立正交；禁止 `NONE` 与 `enableEncryption=true` 或受保护端口组合，不改变既有端口保护/绕过策略，不注入标准外部协议。
- [x] **固定 `FAST` 安全参数与零 XOR 认证（`3044e7c5`、`927475a6`）**：复用单一 `AES-128-GCM`（2 B 长度 + 16 B GCM Tag，最大 16 KiB 明文记录，方向隔离会话密钥与唯一 nonce）+ `HMAC-SHA256`（16 B 握手认证标签，恒定时间 `MessageDigest.isEqual`，零 `ByteBuffer` 临时分配）+ 有界 `NonceReplayCache`；严禁 XOR 替代安全 MAC，不引入替代算法选型或微基准。
- [x] **双 JDK `PARANOID` 与公网 `FAST` 回归验收（`927475a6` + §8）**：双 JDK 32 类 `389/389` 全通且 `PARANOID LEAK=0`；公网 `182.255.85.247` `weak-FAST_AEAD-ZLIB_STORED` 8/8 全通且 `SHA-256` 一致、双端 `LEAK=0`，远端清理核验 `PROC:0 DIR:clean NFT:0`。

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

> 注：2026-11-28 第二轮 Review（P1 §3–§5，提交 `29bd0e65`..`0c47859f`）与第三轮 Review（P1 §8–§12，提交 `3044e7c5`..`927475a6`）均已完成编码、双 JDK `PARANOID LEAK=0` 回归及公网真机验证（实测见 [test-benchmarks.md](test-benchmarks.md) §6–§8）。本文件继续保留长期 24h 稳态压测、双锥型 NAT 跨 ISP 组合、外部载体抓包/生产切换及 P2 §6/§7、P3 演进项。
