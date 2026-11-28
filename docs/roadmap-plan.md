# rxlib 未来规划与待实现计划（Roadmap）

> 本文档仅记录**尚未实现、待验证或未来规划**的演进事项与设计思路。2026-11-28 第一轮与第二轮 Review（P1 §3–§5）的工程落地项——含 Nameserver 强制认证 v2 AEAD 与共享 `NodeRegistry` 子系统会话隔离、具体会话 UDP 路径绑定、统一 `PathAttempt` 原子取消、`RendezvousService`/`PeerUdpPuncher` 资源预算、公开 `RrpServer`/`RrpClient` 空 token fail-closed、`EgressCodec` 恢复旧 HTTP 随机 Host/Path/Query 与请求/响应头及 `AUTO` 合并读修复、RSS 全局/每上游/CLI 全量 `EgressConfig` 公共化、以及 **RSS↔RSS 私有增强 TCP 链路统一迁至 `Transport Core v2`（`TransportCoreCarrier`，删除 `RssHopAes`/`RssTcpCodecSelector`/`SocksAdaptiveHandshake` 并彻底消除 `AUTO` 业务 `zlib` 歧义）**——均已完成编码与双 JDK 28 类 373 项 `PARANOID LEAK=0` 回归及公网真机验证，实测数据见 [测试与基准记录](test-benchmarks.md) §6–§7。第三轮用户决定与新增待办见 §8–§12：保持标准 SOCKS5、RSS 轻量握手、RPC 降 RTT、私有安全传输默认 FAST；该节优先于旧握手设计，不否认已实现功能。

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

## 2026-11-28 第三轮 Review · RSS 无损迁移、轻量握手与 RPC 降 RTT（待实施）

源码基线：[7d1aefa6](https://github.com/RockyLOMO/rxlib/commit/7d1aefa67df199f27f80079b901958c87bf79e72)；迁移前功能及性能参照 [7b858624](https://github.com/RockyLOMO/rxlib/commit/7b858624f4b37676ce3994e7c1390b91a370cd72)，原 HTTP 功能参照 [6af56245](https://github.com/RockyLOMO/rxlib/commit/6af562450dca67d67884f19119b66213de70fe9f)。本节为第三轮新增需求及静态审查待办，覆盖 §5 中“RSS 必须执行完整 RXTC 握手”的旧决定；§3–§5 的已实施记录保留，不表示本轮已经完成。

**用户明确目标**：RSS 保持标准 SOCKS5 业务协议及既有功能，统一公共传输组件不等于强制统一 wire/握手；相同功能、安全等级和网络条件下尽可能保持或减少 RTT、公网流量、CPU、内存，不因重构增加额外消耗。优先复用已有 SOCKS5 协商与共享 RPC 控制面，不默认启用额外加密、压缩、连接、线程或可靠流多路复用。RPC/RRP、Nameserver、Rendezvous 的既有认证传输与安全要求不放宽。RSS Server 1vCPU/768MiB 整机红线，公网业务 Relay 仍仅按需。

### P1 · 8. 功能无损与迁移缺陷修复

#### 8.1 功能保留矩阵（源码核对，不等同全部真机验收）

| 能力 | 当前核对 | 后续要求 |
|---|---|---|
| 公共 HTTP 出口原有随机参数与请求/响应字段、固定模式、Body 可选 deflate、AUTO | §4 已恢复，§7.3 已测试 | 不因轻量化再丢失；保持现有字段、行为与安全边界；不恢复旧 handler 或 RSZ |
| plain/daily/weak/game、实际 zlib 1..9、按目的端口保护/绕过、单层 AEAD | 策略仍在，§7.4 六组短流真机测试通过 | 按相同 profile/出口/端口/密钥完整 A/B；不强制所有业务开启 AEAD 或压缩 |
| Adaptive UDP/FEC、TCP repair、UDP hop/no-hop、MTU/PMTU、原生 UDP LZ4/FEC/AES、分片 | 主要实现仍在，公网增强模式升轨通过 | 回归失败修复、双端口/单端口、MTU、普通 UDP 与自适应 UDP 两条路径 |
| UDP Lease Pool/Relay Group、TCP Warm Pool、上游权重及健康、热更/排空/回滚 | 实现保留，存在下面待核实边界 | 先修正配置与认证，补齐实际调用链和生命周期测试 |
| 外部 udpclient/udp2raw、嵌套 Hysteria2 SOCKS5 | 参数和适配仍在 | §1 真机验收前不宣称无损完成，不以 TCP 成功代替 UDP 升轨成功 |
| 标准 SOCKS5/SS/第三方上游、DNS/Geo、SS 用户级限速 | 保留独立业务路径 | 外部标准客户端不得要求 RXTC 或私有 RPC；用户隔离/限速及 DNS 拦截原语义不变 |

#### 8.2 本轮 Review 缺陷及风险

- [ ] **Egress 局部继承（明确源码逻辑缺陷）**：[RssClientConf.java L68-L123](https://github.com/RockyLOMO/rxlib/blob/7d1aefa67df199f27f80079b901958c87bf79e72/rxlib/src/main/java/org/rx/util/rss/RssClientConf.java#L68-L123) 与 [RssClientConf.java L283-L304](https://github.com/RockyLOMO/rxlib/blob/7d1aefa67df199f27f80079b901958c87bf79e72/rxlib/src/main/java/org/rx/util/rss/RssClientConf.java#L283-L304) 的 Egress.tcpCodec 默认 none，使仅配置 maxBodyBytes/httpBodyEncoding 等局部字段也覆盖全局 HTTP。区分 absent/null 与显式 none，明确全局→用户→上游覆盖优先级（延续既有明确覆盖语义）；按实际合并后配置校验，而非用局部默认值；测试全局、用户、上游组合、序列化、热更与 warm pool 隔离。同步核对 allowedCodecs 客户端字段是否应拒绝或明确仅服务端支持，以及 CLI 参数实际名称与文档一致性。
- [ ] **认证兼容性**：[TransportCoreCarrier.java L172-L210](https://github.com/RockyLOMO/rxlib/blob/7d1aefa67df199f27f80079b901958c87bf79e72/rxlib/src/main/java/org/rx/net/transport/core/TransportCoreCarrier.java#L172-L210) 非 AEAD 私有连接强制非空密码 HMAC，原先无密码/plain 或自定义 Authenticator 仅 login() 的使用方式可能失效。记录行为差异，启动/配置校验应明确必要凭据，标准 SOCKS5 不受影响；轻量策略按 §9 设计，禁止未经明确配置的静默降级。
- [ ] **业务认证与授权**：[RssServer.java L101-L111](https://github.com/RockyLOMO/rxlib/blob/7d1aefa67df199f27f80079b901958c87bf79e72/rxlib/src/main/java/org/rx/util/rss/RssServer.java#L101-L111) 错误账号/密码返回 ANONYMOUS；[Authenticator.java L8-L15](https://github.com/RockyLOMO/rxlib/blob/7d1aefa67df199f27f80079b901958c87bf79e72/rxlib/src/main/java/org/rx/net/socks/Authenticator.java#L8-L15) 默认 loginResult 仅拒绝 null；[Socks5PasswordAuthRequestHandler.java L31-L39](https://github.com/RockyLOMO/rxlib/blob/7d1aefa67df199f27f80079b901958c87bf79e72/rxlib/src/main/java/org/rx/net/socks/Socks5PasswordAuthRequestHandler.java#L31-L39) 仅判断结果是否 null。共享 PSK 验证不能替代用户授权。核实完整入口的拒绝行为并修复，错误账号/密码、禁用用户、共享 PSK 持有者、身份不一致、NO_AUTH 协商及热更撤销专项验证；标准显式匿名模式与失败必须严格区分。非 AEAD 的 HMAC 不是加密，不能宣称凭据或业务具有保密性。
- [ ] **外部载体验收**：固定外部 UDP 映射与嵌套 SOCKS5 的 TCP/RPC/普通 UDP/Adaptive UDP 均需实际使用和抓包，确认真实目标、MTU、无跳跃、握手次序、UDP 升轨与回退、故障切换、连接池/热更。归口 §1，作为 §2 生产切换前置条件。
- [ ] **业务压缩和出口解码预算独立（P2）**：[TransportCoreCarrier.java L188-L217](https://github.com/RockyLOMO/rxlib/blob/7d1aefa67df199f27f80079b901958c87bf79e72/rxlib/src/main/java/org/rx/net/transport/core/TransportCoreCarrier.java#L188-L217) 将业务 zlib 解压上限取自 EgressConfig.maxInflateBytes。拆分独立预算语义，仍有界且默认安全；不因为调整 HTTP body 解压限制影响业务压缩，验证高压缩率、分片、异常输入、内存峰值，不放宽任一层限制。
- [ ] **TCP warm pool UDP 健康反馈（P2）**：[TransportCoreCarrier.java L137-L187](https://github.com/RockyLOMO/rxlib/blob/7d1aefa67df199f27f80079b901958c87bf79e72/rxlib/src/main/java/org/rx/net/transport/core/TransportCoreCarrier.java#L137-L187) 普通 Socks5ClientHandler 传入 UpstreamSupport，Socks5WarmupHandler 分支没有同等反馈。核实预热完成、正式借用后故障/恢复对当前 route generation 的能力统计，避免旧池事件污染新上游。不要为了统计额外发探包。

### P1 · 9. RSS 轻量握手：保持 SOCKS5、无新增独立 RTT

#### 9.1 开销基线

现有 [Sockets.java L1040-L1068](https://github.com/RockyLOMO/rxlib/blob/7d1aefa67df199f27f80079b901958c87bf79e72/rxlib/src/main/java/org/rx/net/Sockets.java#L1040-L1068) 只要 RSS private、adaptive、RSS_PSK_AES 或非 NONE 出口满足其一就可能安装 CoreCarrier；[TransportCoreCarrier.java L465-L507](https://github.com/RockyLOMO/rxlib/blob/7d1aefa67df199f27f80079b901958c87bf79e72/rxlib/src/main/java/org/rx/net/transport/core/TransportCoreCarrier.java#L465-L507) 先发独立 Hello，等 Ack 后才发送待排队 SOCKS 数据。没有可用的 RSS handshake 关闭配置，单独置 rssPrivateTransport=false 不能覆盖所有情况。

按协议流程推算（不是实测），带密码的普通冷连接、RPC 已无关、无丢包/预热且不计目标服务器建立时间：

| 场景 | 迁移前 | 当前 | 目标 |
|---|---|---|---|
| plain 无 AEAD，固定且无需额外协商 | TCP + SOCKS5 方法/认证/CONNECT，4 RTT | 加独立 Core，5 RTT | 4 RTT 或更少；无额外私有握手 |
| daily/weak/game 无 AEAD | 原 adaptive 协商随 SOCKS5 往返完成 | 独立 Core 增约1 RTT | 不再多一个独立往返 |
| 开启 AES 的 plain/增强模式 | 原 AES 已独立认证握手，adaptive 随 SOCKS5 | Core 替代旧 AES 握手 | 不超过原独立往返次数，保留安全性 |
| TCP warm 命中 | 已建立连接、已认证，CONNECT 约1 RTT | 同样约1 RTT（总开销提前发生） | 维持命中时延，减少预热总资源 |

按 [TransportProtocol.java L235-L261](https://github.com/RockyLOMO/rxlib/blob/7d1aefa67df199f27f80079b901958c87bf79e72/rxlib/src/main/java/org/rx/net/transport/core/TransportProtocol.java#L235-L261)、[TransportProtocol.java L330-L363](https://github.com/RockyLOMO/rxlib/blob/7d1aefa67df199f27f80079b901958c87bf79e72/rxlib/src/main/java/org/rx/net/transport/core/TransportProtocol.java#L330-L363)：当前 RSS Hello 为56B+peerId 字节（最多64B），HelloAck 为56B（当前空 server peerId）；合计112B+peerId，不含外层包装、IP/TCP 和重传。旧 [RssHopAes.java](https://github.com/RockyLOMO/rxlib/blob/7b858624/rxlib/src/main/java/org/rx/net/RssHopAes.java) AES 初始交换65B；旧 [SocksAdaptiveHandshake.java](https://github.com/RockyLOMO/rxlib/blob/7b858624/rxlib/src/main/java/org/rx/net/socks/SocksAdaptiveHandshake.java) 已有随 SOCKS5 传输的 nonce/MTU，因此不能把全部新握手字节算作所有模式的净增量。旧/新 AES 数据记录均为2B长度+16B tag，最大记录16KiB；RAW_STREAM 不引入每业务包 RPC 序列化或 Core 通用 frame。

当前额外状态包括 HMAC、每连接独立 deadline、按需 PendingWriteQueue（32项/64KiB）、每 SocksProxyServer 创建的 NonceReplayCache（上限16384条/120s）。无 AEAD 的 HTTPS 等短流尤其需要冷建连对照；不能仅凭低 RTT 预热结果说明系统总成本下降。

#### 9.2 实施策略

- [ ] **统一底层、可选择握手策略**：公共 TransportCoreCarrier/AEAD、Adaptive TCP、EgressCodec、真实压缩、MTU 继续复用。RSS 默认轻量策略（拟议名称 handshake=lightweight；配置层级、字段名待原型），完整 core 策略作为显式选择；RPC/RRP 等保持现行完整认证握手。不是仅去掉 HMAC flag 或认证检查；不维护旧私有 wire 双栈，不恢复独立旧 AES/HTTP/FEC 实现。
- [ ] **标准 SOCKS5 边界**：外部标准入口/第三方上游的 method、auth、CONNECT、UDP ASSOCIATE 与数据语义保持标准，不要求 RXTC 或 RPC。RSS 两端之间的可选增强承载是私有协议，必须明确双方配置，不冒称增强后的物理 wire 等同标准 SOCKS5。没有 AEAD、Adaptive UDP、压缩及其他需要协商的固定 NONE 场景，直接使用标准 SOCKS5 握手，不安装 Core 握手、不创建额外认证缓存/定时器。对于固定出口/实际压缩组合，验证是否可无附加往返；未能保证边界及安全则使用显式配置或完整 Core。保留原认证/端口保护策略，不因轻量化让已受保护的数据或凭据转为明文。
- [ ] **增强链路最少必要协商**：按原 SOCKS5 业务握手的 RTT 预算评估可复用协商阶段，必要认证、会话密钥、nonce、能力、MTU、端口等必须正确绑定；有 AES 的链路不能省掉必要安全交换。只有经过验证的相同用户身份及授权才能复用认证结果，全局 PSK 不是用户身份，不得取消业务授权。无握手的路径不创建 Core 专属缓存和 deadline，需防重放的路径继续有界；昂贵 UDP 状态按授权和实际需要分配。禁止重放旧会话密钥、无条件提前执行非幂等操作或因 RPC 不可达而绕过鉴权。
- [ ] **AUTO 不退化**：保留公共三出口及已恢复 HTTP 功能；不能将内层业务 zlib 与外层纯 ZLIB_STORED 混淆。轻量路径若无法证明 AUTO 所有首部边界/认证安全，要求明确固定配置或完整 Core，不依据压缩等级猜测，不恢复 RSZ，不自动尝试多种协议，不强制每条连接为 AUTO 付出独立握手。明确矩阵中不可支持的组合并在配置时拒绝，而不是静默降低安全或业务功能。
- [ ] **预算及验证**：记录每阶段首次/冷/预热命中/未命中 RTT、真实握手字节、加密/压缩次数、业务数据记录数、socket/handler/task/queue 数量、CPU/分配/峰值内存。无独立协商需求的 plain 无 AES 不新增 RTT；增强模式不增加重复往返；不增加业务帧层数和常驻线程。相同 profile/端口/安全等级/出口下与迁移前 A/B，不达标的组合先保留明确可用策略并列出阻塞，不声称已达标。

### P1 · 10. 复用 RPC 控制面减少 UDP 首包 RTT 与 TCP 控制连接

现有 SocksRpcContract 已支持 capabilities、open/add/remove/heartbeat/closeUdpRelayGroup、claim/resetUdpRelay、DNS 与 fake endpoint。[SocksUdpUpstream.java L309-L429](https://github.com/RockyLOMO/rxlib/blob/7d1aefa67df199f27f80079b901958c87bf79e72/rxlib/src/main/java/org/rx/net/socks/upstream/SocksUdpUpstream.java#L309-L429) 每新 group 先 capabilities 再 open；[RssClient.java L361-L362](https://github.com/RockyLOMO/rxlib/blob/7d1aefa67df199f27f80079b901958c87bf79e72/rxlib/src/main/java/org/rx/util/rss/RssClient.java#L361-L362) ForwardingSocksRpcContract 直接转发 capabilities，并无该层缓存。现有 [Socks5UpstreamPoolManager.java L231-L255](https://github.com/RockyLOMO/rxlib/blob/7d1aefa67df199f27f80079b901958c87bf79e72/rxlib/src/main/java/org/rx/net/socks/Socks5UpstreamPoolManager.java#L231-L255) UDP Lease Pool 预建 SOCKS5 TCP 控制连接，借用时 claim RPC；RPC Relay Group 则无需为每个 group 建独立 TCP 控制连接。

| UDP 创建方式（带密码、无额外 AES，RPC 已连接） | 理论关键路径 |
|---|---|
| 标准 SOCKS5 UDP ASSOCIATE 冷连接 | 4 RTT |
| 当前 Core + SOCKS5 UDP ASSOCIATE | 5 RTT |
| 传统 UDP lease 命中 + claim | 1 RTT（预建成本另计） |
| RPC group capabilities + open | 2 RTT |
| 能力有效缓存 + RPC open | 1 RTT |
| 同一仍有效授权 association 内复用已有 group | 0 额外控制 RTT |

以上不含 RPC 冷连接、外部 SOCKS5 载体握手、DNS、UDP 首包路径、服务器处理和失败重试；不保证实测恰好达到理论数值。

- [ ] **能力缓存**：在实际共享 RPC facade/上游控制状态中做有界按需缓存，绑定经认证对端、路由/配置 generation、授权范围与有效期；并发首次查询 single-flight，不对每个 UDP group 重新 capabilities。断线重连、服务重启、热更、能力不匹配时失效重查；支持显式不兼容结果，不能永久缓存失败或错误身份的能力。可评估把能力返回合并到已有认证控制响应而非独立增加 RPC；不为了缓存新增常驻线程或周期轮询。
- [ ] **plain UDP 私有 RSS 优先 RPC Group**：现 AUTO 在非 daily 且无 hopping 时直接返回 null；对于明确支持并已授权的 RSS 对端，让 plain 同样优先 RPC group。保留标准第三方 SOCKS5 UDP ASSOCIATE 及必要兼容路径，不把普通 UDP 转成可靠 TCP 数据流；增强模式既有不允许静默降级的约束不变。RPC 不可用、能力不支持时仅按明确且等价安全策略回退，不创建重复组，不以破坏目标授权或源地址关联换 RTT。
- [ ] **传统 lease 按需保留**：RPC group 可用时避免创建每 lease 独立 TCP 控制连接；普通 SOCKS5 控制连接生命周期仍遵守标准，不能因新 RPC group 设计提前关闭标准 UDP ASSOCIATE 的 TCP。评估实际 group 打开、claim、reset、close、失败回收与池命中成本；不立即删除现存兼容实现。非幂等 open/claim 等不能无条件重试，必要时使用受控幂等标识或明确失败/回收，防止超时遗留资源。
- [ ] **安全和资源前置**：RPC 的创建/续租/关闭与具体授权用户、节点、association、route generation 绑定；凭据与敏感控制返回必须有适当认证保护，日志脱敏不等于加密；校验过期/撤销/并发预算及源地址/目标约束。[UdpRelayGroupManager.java L89-L151](https://github.com/RockyLOMO/rxlib/blob/7d1aefa67df199f27f80079b901958c87bf79e72/rxlib/src/main/java/org/rx/net/socks/UdpRelayGroupManager.java#L89-L151) 的 group 容量检查和插入之间存在并发窗口，核查原子预留、节点/用户/进程总 socket 和 pending 限额、失败回滚/空闲回收；减少 TCP 控制连接不能换来 UDP group 无界增长。只在合法关联内复用，不跨用户/安全上下文混用。
- [ ] **验收**：普通/增强 UDP、hop/no-hop、固定外部入口、嵌套载体、标准第三方、RPC 冷/热/断线、并发首次、能力变化、拒绝与取消、超时/过期/回收。统计 RPC 每组调用次数、首包 P50/P95/P99、实际控制连接和 UDP socket 数、公网控制/业务字节、CPU/内存；目标有效缓存时 open 仅1次 RPC，正常私有 group 无专用 SOCKS5 TCP 控制连接，已有合法 group 复用不新增控制往返。

### P2 · 11. UDP/TCP/RPC 生命周期与资源优化

- [ ] **UDP group 合法复用**：现 SessionGroup 已有活跃复用，先核查是否因每个 destination 重复创建；仅相同用户、上游、profile、密钥/授权、UDP association 与网络 generation 内复用；保留数据报边界/源目标关联、限速、租约过期和撤销。按实际需求扩缩容，避免无业务的保活及探测；服务端 group/socket/任务总预算不变。
- [ ] **TCP Warm Pool 按需动态容量**：[Socks5UpstreamPoolManager.java L30-L112](https://github.com/RockyLOMO/rxlib/blob/7d1aefa67df199f27f80079b901958c87bf79e72/rxlib/src/main/java/org/rx/net/socks/Socks5UpstreamPoolManager.java#L30-L112) 现每 pool 有补充任务、按固定 minSize 补充。按连接频率/命中率/空闲期评估按需预热与收缩，限制 pool key 数、进程预热连接/在途连接总数，合并调度，闲置上游不持续建连。预热是转移等待而非消除总成本；报告 hit/miss/unused/过期/失败、字节、socket、CPU、内存，不通过无限预热伪造低时延。保留 per-user/route/profile/完整 EgressConfig/MTU/认证隔离及热更排空。
- [ ] **RPC 池按需收缩**：[RssClient.java L876-L911](https://github.com/RockyLOMO/rxlib/blob/7d1aefa67df199f27f80079b901958c87bf79e72/rxlib/src/main/java/org/rx/util/rss/RssClient.java#L876-L911) 已按 RPC endpoint/嵌套路由共享 facade，同时有 pool 和 stateful event 连接；测实际调用负载和事件订阅需求，再减少不必要的 idle pool、重复心跳或连接，不影响事件推送、WoW、DNS、控制请求和重连。避免把稳定能力缓存变成每连接 RPC 查询；不得缓存或复用必须独立的会话密钥及防重放状态。
- [ ] **状态开销**：无额外握手时不创建相关 replay cache/deadline/queue；必须启用的模式保留安全边界并按需分配，复查定时器、状态表和指标开销。不新增每连接线程，不默认全业务 TCP 多路复用（HOL、故障域及游戏时延风险）；只有真实瓶颈才立项。
- [ ] **统一验收与交付**：优先完成 §8/9/10，再按实测推进 §11；外部载体与长期资源验收继续归口 §1/3。以迁移前真实同配置四档混合负载（短连接、不可压缩数据、大流、交互、普通 UDP、QUIC/WoW、弱网多种子）与当前/优化后 A/B，分开固定与 AUTO、AEAD on/off、冷/热。实际 IP 层流量、CPU、RSS/堆外/cgroup/整机峰值、socket/线程、P99/失败率与保活成本一同记录；不以本轮 §7 六组32KiB回显的不同出口数据直接比较 profile 性能。JDK8/master 完整回归、merge master→rss-21 后 JDK21 回归、PARANOID、24h/768MiB 整机及真实生产前置条件，报告归入 test-benchmarks.md；仅实际实现后更新 .memory/配置文档。RRP UDP over TCP (§7)、全局整形及复杂 FEC 继续暂缓。

### P1 · 12. 统一 SecurityMode：FAST 默认，NORMAL 显式可选

2026-11-28 用户最新决定：**所有 rxlib 私有传输服务的默认安全模式为 FAST**。本节与 §9 轻量握手、§10 RPC 降 RTT 一起设计；安全模式选择不等于强制增加一次独立握手。优先复用已有安全组件，不因引入枚举重写整个传输栈。

#### 12.1 模式定义及默认行为（拟议，尚未实现）

| SecurityMode | 加密与认证 | 默认/边界 |
|---|---|---|
| FAST | AES-128-GCM；基于高熵 PSK 的 HMAC-SHA256 握手认证与独立会话/方向密钥派生；业务记录由 GCM Tag 认证 | RSS、RPC、RRP、Nameserver 私有 RPC、Rendezvous、NodeRegistry 相关私有安全链路统一默认 |
| NORMAL | 标准 TLS 1.3，使用成熟 TLS 实现的证书认证或受支持的 PSK 认证及密钥协商 | 显式可选；不再叠加等价 Core AEAD 或重复的传输层 HMAC，应用身份与授权仍保留 |
| NONE | 无额外传输加密，不等于免用户认证或授权 | 仅明确允许的非保护链路、已有端到端加密业务的绕过策略等；不得成为 FAST/NORMAL 失败后的回退模式 |

- [ ] **FAST 为默认算法套件，不改变 RSS 既有端口保护/绕过策略的优先级**：为兼顾此前用户明确的“相同或更少 RTT、原有功能无损”要求，默认 FAST 指需安全传输时的默认实现，不因设置默认值而让所有 bypass/plain 业务额外套一次 AEAD 或 Core Hello。按现有 protected/bypass/ordinary 策略及显式配置决定是否启用额外保护；必须保护的连接不得选 NONE。若部署需要全量私有业务加密，应明确配置并记录成本，不能悄然改动既有配置语义。标准外部 SOCKS5、Shadowsocks、DNS 和第三方上游协议不注入私有握手。NodeRegistry、Nameserver 私有 RPC、Rendezvous、公开 RRP/RAW 等既有强制认证加密要求保持 fail-closed；缺少有效密钥/认证配置时拒绝，不默认生成不安全共享密钥或降级。
- [ ] **五个独立维度**：SecurityMode（NONE/FAST/NORMAL）、HandshakeMode（LIGHTWEIGHT/CORE，RSS 默认轻量，RPC/RRP 默认完整）、EgressCodec（NONE/ZLIB_STORED/HTTP；服务端 AUTO）、业务 Compression（NONE/ZLIB）、TransportProfile（PLAIN/DAILY/WEAK/GAME）。FAST 不隐含开启 zlib/FEC/HTTP，也不改变原生 UDP 数据报语义；不允许不安全或无法正确协商的组合。配置名如 security.mode=fast 仅为拟议，按现有配置体系定稿；服务端允许模式与客户端选择必须明确、受认证约束，不允许自动安全降级。
- [ ] **统一实现而非统一所有 wire**：沿用 §9 的轻量握手与授权绑定，避免为了 FAST 新增独立往返；无协商需求的 plain 不产生 Core 专属状态。RAW_STREAM 不加通用业务 frame，保留现有端口策略、HTTP 出口字段、压缩、FEC、MTU、固定/跳跃 UDP、外部载体和标准协议边界。

#### 12.2 FAST 安全与性能

源码基线：[TransportProtocol.java](https://github.com/RockyLOMO/rxlib/blob/7d1aefa67df199f27f80079b901958c87bf79e72/rxlib/src/main/java/org/rx/net/transport/core/TransportProtocol.java)、[TransportAeadCodec.java](https://github.com/RockyLOMO/rxlib/blob/7d1aefa67df199f27f80079b901958c87bf79e72/rxlib/src/main/java/org/rx/net/transport/core/TransportAeadCodec.java)、[TransportCoreCarrier.java](https://github.com/RockyLOMO/rxlib/blob/7d1aefa67df199f27f80079b901958c87bf79e72/rxlib/src/main/java/org/rx/net/transport/core/TransportCoreCarrier.java)。

- [ ] 复用现有 AES-128-GCM、HMAC-SHA256、会话密钥派生及必要防重放组件。当前 HMAC 输出32B、握手线上标签16B（128 bit），正常 TCP AEAD 每条记录2B长度+16B GCM Tag（最大明文记录16KiB），不再叠加逐记录 HMAC。保留方向隔离、独立会话密钥、唯一 nonce、完整握手上下文及认证标签验证、计数耗尽处理和有界防重放。纯 PSK 派生不具备前向保密；HMAC 认证本身也不提供保密性。弱密码不能等同高熵 PSK，不将全局 PSK 持有者直接当作已授权业务用户。密钥配置/轮换及失败行为纳入回归。
- [ ] **明确排除 XOR 认证**：XOR、CRC32、xxHash 等可用于非安全校验，XOR FEC 保留，但不得替代安全 MAC 或 AEAD。HMAC-SHA256 并不是在所有硬件上消耗最低的安全 MAC，但成熟、兼容性好，当前没有同硬件基准证明更换算法值得；默认不增加新的 MAC 算法或可选项。
- [ ] **优化顺序**：先消除重复握手 RTT、重复等价认证、无必要的包装及临时对象/ByteBuf 分配，再评估 Mac.getInstance / init 等初始化成本与安全的实例复用。Mac 非线程安全，禁止跨线程共享可变实例或错误复用旧密钥状态；任何缓存必须有界，密钥轮换时失效，不为优化增加线程/无界 ThreadLocal 或泄露密钥。保持恒定时间标签比较及必要的认证/授权，不以降低标签长度或取消校验换性能。

#### 12.3 NORMAL：标准 TLS（显式选择）

- [ ] 使用标准 TLS 1.3（可协商 AES-128-GCM 等安全套件），正确校验证书链、预期对端身份及有效期；双向认证或 TLS-PSK 的具体支持按成熟 provider 能力决定，禁止 trust-all、忽略身份验证或自动回退明文。传输层身份与业务用户身份分离，TLS 不替代业务 ACL/token。需要前向保密时使用临时密钥交换；TLS-PSK 不是自动具备前向保密。使用会话恢复可评估减少 CPU/延迟，不默认启用可重放的 0-RTT 业务请求。
- [ ] 核查 JDK8/JDK21 所选 TLS provider 的 TLS1.3/认证能力及 Netty 集成、外部载体、证书/密钥轮换、连接池和热更；不支持时明确拒绝配置。NORMAL 不得被实现为 TLS 外再套一层同等 Core AEAD。独立原生 UDP、Adaptive UDP 的认证/可靠性与必要控制数据仍按其自身安全语义处理，不能用 TCP TLS 的存在推定 UDP 已受保护；在真实拓扑证明可用之前，不宣称 NORMAL 已覆盖所有 Profile/载体/UDP 组合。不为 NORMAL 新增私有 TLS 仿制实现。

#### 12.4 MAC 微基准及验收（P2，按实测瓶颈推进）

| 候选 | 评估要求 |
|---|---|
| HMAC-SHA256 | 当前默认基线；测 Mac 实例创建、init、doFinal、复用以及标签截断的完整成本 |
| AES-CMAC / AES-GMAC | 测实际 provider 与 AES 硬件加速；GMAC 必须正确管理每密钥唯一 nonce |
| Poly1305 | 测成熟实现及每消息一次性密钥派生/管理的完整成本，不能只比较裸 MAC 运算 |
| SipHash | 非本轮替代目标；需匹配安全目标与标签长度，不能因短标签更快而直接与128-bit安全标签基线等价比较 |

- [ ] JMH 或等价可重复微基准：JDK8/JDK21、实际低配 VPS CPU 与开发机、64/128/256B 代表性握手消息；报告 ns/op、ops/s、B/op、GC、硬件加速/provider、warmup/fork/样本波动。完整计算实例初始化、密钥/nonce 管理等成本，不拿不同安全语义/标签长度的结果直接排序。没有明显端到端收益时继续 HMAC-SHA256，不改变默认算法。
- [ ] FAST/NORMAL 与旧 RSS 及 §9 优化后相同功能/安全等级的冷建连、预热、实际握手往返、持续业务吞吐、短记录与混合负载 A/B；记录真实线字节、CPU、峰值 heap/direct/RSS/整机内存、连接数及失败率。分别说明 FAST/NORMAL 的安全属性差异，不能只用速度比较安全级别；强制认证、错误密钥、重放、篡改、降级、用户撤销、重复密钥/nonce、断线与轮换测试。双 JDK PARANOID 与 1vCPU/768MiB 整机目标继续沿用 §11。具体 TLS 落地与 MAC 算法更换不抢占 §8–§10 的正确性、轻量握手与 RPC 降 RTT 优先级；先统一 FAST 默认配置及复用当前实现，再按实测推进 NORMAL 与微优化。结果集中记录于 test-benchmarks.md。

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

> 注：2026-11-28 第二轮 Review 的 P1 §3–§5 代码实现、双 JDK `PARANOID` 28 类超集回归及公网真机验证已落地（提交 `29bd0e65`..`0c47859f`）；第三轮 §8–§12 为新增待办，第二轮已实施记录不是轻量握手与无损迁移的最终验收。本文件继续保留长期压测、外部载体/生产切换及 P2/P3 演进项。本轮为静态审查与理论 RTT 推算，未执行新测试、未改业务源码；潜在风险及预期收益须专项复现。
