# rxlib 未来规划与待实现计划（Roadmap）

> 本文档仅记录**尚未实现、待验证或未来规划**的演进事项与设计思路。2026-11-28 已完成的 P0/P1/P2 项（SS 用户共享带宽限速与热更新、`weak` 大流 FEC/直通 TCP 优化、Geo 条件增量更新与缺失 `BLOCK` fail-closed / 非 EventLoop 编译、DNS 非地址 RR TTL 实时衰减缓存与字节预算、Nameserver 迁移至已鉴权 Transport Core RPC 并接入可选 `NodeRegistry` 权威源、删除旧版 `UdpClient`/`UdpHolePunch*`/`RssEgressCodec`/`HttpPseudoHeader*`、统一公共 `EgressCodec` (`NONE`/`ZLIB_STORED`/`HTTP` + Server `AUTO`)、`Transport Core v2` AEAD+`zlib3`+`LocalChannel`、`RrpServer`/`RrpClient` 迁移 v2 及 `NodeRegistry` 授权 RAW Relay、`org.rx.net.rendezvous` 双 NAT 协调/防放大 UDP 观测/打洞与直连优先 `PathSelector`、以及 `RssRuntime` opt-in `nodeService` 事务式启停热重载）已移出待办（其后续 Review 修复和用户新增需求见下文），实测数据见 [测试与基准记录](test-benchmarks.md)。

---

## P1 · 近期优先（真机验收与运维切换）

### 1. 真实外部载体、多上游切换与长期 Linux Native `epoll` 验收
- **外部载体实机抓包**：覆盖 `RssFixedUdpEntry`（外部 `udpclient` + 外部 `udp2raw`）、`RssSocksCarrier`（嵌套 SOCKS5 如 Hysteria2 `CONNECT` + `UDP ASSOCIATE`），以及多上游混配下的故障切换、热切排空与回滚（统一 `EgressCodec` `NONE` / `ZLIB_STORED` / `HTTP` 与 Server `AUTO` 已于 2026-11-28 在高丢包 VPS `182.255.85.247` 完成实测）。
- **Linux `epoll` / `SO_REUSEPORT` 长期压测与应用层实测**：
  - 2026-11-28 已在 `182.255.85.247` 完成短期 4-profile + `SO_REUSEPORT=2` + SS 用户限速热更新、以及统一 `EgressCodec` + `NodeRegistry`/`RendezvousService`/`RrpServer` RAW Relay 实测；后续补充长时间稳态压测、对比 `kcptun` 的多随机种子 Linux 基准，以及真实 QUIC/HTTP3、外部 UDP 应用与 WoW 3.3.5a 公网联机验证。
- **更多双 NAT 组合（全锥/受限锥 NAT）公网打洞直连验收**：
  - 本次已验证多网卡直连优先（局域网直连 TCP + 直连 UDP `6.63 ms` 升级 + 断洞无损修复，VPS 中继流量 `0 B`）与跨运营商对称 NAT（自动按需降级至 E2E AEAD RRP RAW Relay 与 RPC）；后续可在两端均为锥型 NAT 的运营商链路补充公网跨 ISP UDP 打洞升轨直连与中继退避统计。
- **`RssDatagramFragmenter` 边界与物理内存实测**：
  - 验证 Linux Pooled Direct Allocator 在高丢包乱序与 OOM 预算下的 backing-capacity 压缩表现。
  - 验证多分片写出时前置分片失败的 Write Promise 聚合行为，并补齐 `MTU=256`、64 分片上限及魔数转义边界测试。

### 2. 生产运维切换（6896 下线 `kcptun` / WoW TLS 验收）
- **6896 (`rGrey`) 切换**：在确认 `udp2raw` 与 `Adaptive UDP` 链路稳定后，将生产 `6896` 切至 `ssCipher: chacha20-ietf-poly1305` + `udpHop: false` + `main-tun`（直连 `udp2raw` `127.0.0.1:4080` / `9910`），下线 `client_linux_amd64` / `server_linux_amd64`（`kcptun`）及其保活 cron（保留 `udp2raw` 保活），对比 `6896` 与 `6898`（跳端口 `daily`）双端抓包。
- **WoW 直连端点**：完成生产 TLS 证书与公网游戏登录全链路验证；后续按需评估 IPv6 支持。

---

## 2026-11-28 第二轮 Review 与后续实施计划

源码基线：[7b858624](https://github.com/RockyLOMO/rxlib/commit/7b858624f4b37676ce3994e7c1390b91a370cd72)；对比重构前 [6af56245](https://github.com/RockyLOMO/rxlib/commit/6af562450dca67d67884f19119b66213de70fe9f)。上一轮详细设计 [noderegistry-nat-relay-plan.md](noderegistry-nat-relay-plan.md) 为历史设计基线；本节是后续待办的权威来源，覆盖其中不再保留 HTTP 随机字段的旧决定。已实现功能不重复立项。测试实测数据仍集中在 [test-benchmarks.md](test-benchmarks.md) §6。

本轮用户决策：保留 HTTP Pseudo 原有随机 Host/URL Path 能力，公共 EgressCodec 继续作为唯一实现；RSS 私有增强链路统一 Transport Core；RRP UDP over TCP 记录后续实施，本阶段不开发。不恢复旧私有 wire、RSZ、UdpClient/UdpHolePunch* 或独立 HTTP Pseudo handler。全局限速旧问题继续暂缓。优先正确性/安全/稳定性，再减少实际公网流量和 CPU；资源有界，RSS Server 1vCPU/768MiB 整机约束不变。

### P1 · 3. 上一轮 Review 的正确性、安全和资源边界

- [ ] **Nameserver 强制认证传输**：[NameserverImpl.java L417-L421](https://github.com/RockyLOMO/rxlib/blob/7b858624f4b37676ce3994e7c1390b91a370cd72/rxlib/src/main/java/org/rx/net/nameserver/NameserverImpl.java#L417-L421) 创建 dailyServer 但未显式配置 authSecret/enableEncryption，启用 NodeRegistry 后 authenticateNode 涉及节点凭据。核查注册端、客户端池和副本连接，使用经过认证的 v2 AEAD，禁止把 sensitiveMethods 日志脱敏当作加密，凭据不得在未加密会话传输；副本授权仍须绑定身份、来源分区。Nameserver 与 Rendezvous/RRP 的共享节点会话不得因一个子系统离线而错误撤销其他在线子系统，增加真实并发及重连用例。标准 DNS TCP/UDP 不改为私有传输。
- [ ] **具体会话的 UDP 路径绑定**：[PathSelector.java L952-L1068](https://github.com/RockyLOMO/rxlib/blob/7b858624f4b37676ce3994e7c1390b91a370cd72/rxlib/src/main/java/org/rx/net/rendezvous/PathSelector.java#L952-L1068) 目前 verifiedByInitiator/latestSessionByInitiator 以 nodeId 为键，存在多会话覆盖及陈旧路径复用风险。关联认证的节点、服务、punchSessionId、具体 transport session/generation 及有效期；路径先到/会话先到均正确，不允许旧路径直接挂到新连接；有界缓存、撤销/断开/过期清理。当前异步协调取得的 punchSessionId 与 peerIdTag/SelectedConnection 构造时间可能不一致，需一起修复。并发不同服务、同服务多连接、重连/过期/权限撤销测试。
- [ ] **RSS AUTO 业务 zlib 歧义**：[EgressCodec.java L369-L389](https://github.com/RockyLOMO/rxlib/blob/7b858624f4b37676ce3994e7c1390b91a370cd72/rxlib/src/main/java/org/rx/net/EgressCodec.java#L369-L389) 优先把 zlib 头视为出口 ZLIB_STORED，而 [RssTcpCodecSelector.java L27-L47](https://github.com/RockyLOMO/rxlib/blob/7b858624f4b37676ce3994e7c1390b91a370cd72/rxlib/src/main/java/org/rx/net/RssTcpCodecSelector.java#L27-L47) 仍识别业务 zlib。当前 EgressCodecTest 中 RSS 三出口交叉测试使用 RssHopAes + 内层 zlib（[测试 L370-L397](https://github.com/RockyLOMO/rxlib/blob/7b858624f4b37676ce3994e7c1390b91a370cd72/rxlib/src/test/java/org/rx/net/EgressCodecTest.java#L370-L397)），尚不足以证明无 AES 的业务 zlib 在 AUTO 下无歧义。先复现双向数据/响应格式；未解决前受影响入口固定配置，最终通过 §5 的私有统一握手解决。不恢复 RSZ、不根据压缩等级猜测层次、不静默降级。
- [ ] **PathAttempt 生命周期**：[PathSelector.java L632-L667](https://github.com/RockyLOMO/rxlib/blob/7b858624f4b37676ce3994e7c1390b91a370cd72/rxlib/src/main/java/org/rx/net/rendezvous/PathSelector.java#L632-L667)、[PathSelector.java L733-L925](https://github.com/RockyLOMO/rxlib/blob/7b858624f4b37676ce3994e7c1390b91a370cd72/rxlib/src/main/java/org/rx/net/rendezvous/PathSelector.java#L733-L925) 统一取消、失败、超时、成功后所有权转移，取消未赢 TCP 连接、UDP 检查、尚未消费的 RAW carrier、定时器与 pending future；SelectedConnection.close 同时清理关联检查。测试调用方取消、晚到的协调结果、预算边界竞争、Relay 打开后上层握手失败、无可靠路径、短连接风暴，确保无遗留业务中继流或 buffer。同步确认取消与 inFlightClients 添加、uncheckedReasons 修改的并发边界。
- [ ] **打洞资源上限**：[RendezvousService.java L302-L323](https://github.com/RockyLOMO/rxlib/blob/7b858624f4b37676ce3994e7c1390b91a370cd72/rxlib/src/main/java/org/rx/net/rendezvous/RendezvousService.java#L302-L323) 现主要限制 initiator，需同时限制目标节点和租户；[PeerUdpPuncher.java L445-L479](https://github.com/RockyLOMO/rxlib/blob/7b858624f4b37676ce3994e7c1390b91a370cd72/rxlib/src/main/java/org/rx/net/rendezvous/PeerUdpPuncher.java#L445-L479) 动态候选映射需独立数量/字节上限。端侧进程在途会话、检查任务、候选、nonce/ACK 状态及 socket 总预算；过期/撤销/取消释放计数，零计数条目回收。邀请投递失败及时释放；检查目标服务授权、旧会话身份与已验证候选生命周期。未经授权的探测不得引发反射、扫描或任意目的地址连接。
- [ ] **公开 RRP 的空 token 安全边界**：[RrpServer.java L440-L451](https://github.com/RockyLOMO/rxlib/blob/7b858624f4b37676ce3994e7c1390b91a370cd72/rxlib/src/main/java/org/rx/net/socks/RrpServer.java#L440-L451) / [RrpClient.java L768-L777](https://github.com/RockyLOMO/rxlib/blob/7b858624f4b37676ce3994e7c1390b91a370cd72/rxlib/src/main/java/org/rx/net/socks/RrpClient.java#L768-L777) 根据 token 是否存在决定 AEAD，单独构造 RrpServer/RrpClient 的路径需核查是否也强制必要认证；公网或 RAW Relay 服务禁止无认证配置，限制未认证帧和反序列化；不能只依赖 RssRuntime 配置校验。保留合法本地使用场景但明确安全边界。
- [ ] **AUTO/HTTP 首包及资源边界**：[EgressCodec.java L339-L346](https://github.com/RockyLOMO/rxlib/blob/7b858624f4b37676ce3994e7c1390b91a370cd72/rxlib/src/main/java/org/rx/net/EgressCodec.java#L339-L346) 在选择前按整个可读缓冲区检查 maxHeaderBytes，验证合法首包与较大后续数据合并到一次 read 时不会误拒；区别首部预算与 payload 预算。检查 HTTP header/body 分片、空帧、写 promise 聚合、EOF/FIN、失败关闭、解码输出上限、慢连接及总体未认证资源；不得将 maxHeaderBytes 简单提高来掩盖误判。非标准 JSON 二进制 Body 的私有约定不改变。
- [ ] **直连优先与路径恢复**：现有默认 16 TCP 对/32 UDP 对、4 TCP 并发/每轮6 UDP、1200ms TCP/1500ms UDP、64KiB 每会话探测预算、近期缓存128条/60s 先保持。验证候选截断时非默认网卡的可达性、不同接口公平性、网络 generation 与缓存重新验证；可用直连时不创建 Relay。动态 UDP 重选仅在明确故障信号后完善；活动可靠流无缝 Relay→直连迁移暂不要求，新连接优先直连。保持一个可靠状态机。
- [ ] **验收补齐**：§6 报告是18类158项的双 JDK 核心回归，并非旧26类359项的完整超集。补跑全部受影响既有功能及新增专项，JDK8/JDK21 PARANOID；同办公网直连成功不代表跨运营商公网 UDP 打洞成功，补充真实可打通 NAT 组合、不同网卡、断洞恢复和按需中继。1024在线节点、慢消费者、多并发 Relay、至少24h 稳态，分别记录峰值 RSS/堆外/cgroup/整机内存、CPU、实际 IP 层字节；不能用测试结束时392MiB/960MiB或零泄漏日志替代768MiB整机长期验收。高丢包 HTTP 公网数据每模式6次建连，不能作为可靠 P95/P99 结论；补测小包/长连接/并发。
- [ ] 保留 §1 的外部载体、multi-upstream、QUIC/HTTP3、WoW 及 SS 用户限速验证；不修改全局限速旧问题，不在没有实测瓶颈时重写 FEC。

### P1 · 4. HTTP 出口能力恢复及重构遗漏核对

依据旧 [HttpPseudoHeaderEncoder.java（重构前）](https://github.com/RockyLOMO/rxlib/blob/6af562450dca67d67884f19119b66213de70fe9f/rxlib/src/main/java/org/rx/net/HttpPseudoHeaderEncoder.java)、当前 [EgressConfig.java L28-L38](https://github.com/RockyLOMO/rxlib/blob/7b858624f4b37676ce3994e7c1390b91a370cd72/rxlib/src/main/java/org/rx/net/EgressConfig.java#L28-L38) 与 [EgressCodec.java L465-L545](https://github.com/RockyLOMO/rxlib/blob/7b858624f4b37676ce3994e7c1390b91a370cd72/rxlib/src/main/java/org/rx/net/EgressCodec.java#L465-L545)，对比旧/新功能而不是恢复旧私有 wire。

| 能力 | 当前状态 | 后续决定 |
|---|---|---|
| 随机 Host、URL Path、Query | 旧生成器删除，当前固定 httpHost/httpPath | 按用户要求恢复随机选择能力，公共 EgressCodec 唯一实现 |
| GET/POST 混用 | 仅 POST | POST 保留；GET Body 不作为恢复要求，避免破坏正常 HTTP 语义 |
| User-Agent/Accept | 旧有、当前省略 | 可选合法头配置，非必需 |
| Date/Server | 旧有、当前省略 | 非必需，不为还原旧代码而增加 |
| HTTP 二进制 Body / Content-Type 声明 | 方案 A 已实现 | 保持用户选定的私有约定，记录与标准 JSON 语义不一致 |
| Body zlib0 | 新增可选 | 保留，默认 none，不重复包装 |
| 纯 zlib-stored / RSZ | 纯 RFC1950 已实现 | 保留纯流、不恢复 RSZ |
| 诊断字节统计 | 旧/新均有 | 保留 wire/input 计数，指标名称变动须检查 |
| 旧 RRP 本地/公网连接清理及失败释放专项 | 部分旧测试方法消失或重写 | 对照新 TransportSession 生命周期补回行为验收 |
| 旧 RssEgressCodecTest 的 Adaptive + 实际压缩边界专项 | 旧测试删除，新的 EgressCodecTest 主要覆盖各模式及 AES 内层压缩 | 核对其他测试是否已覆盖，缺失则补等价集成测试；不保留旧 preface 测试 |
| RSS 配置公共化 | RssClientConf.Egress 仅 tcpCodec/udpCodec，readEgress 只接受这两项 | 接入公共 EgressConfig 能力；校验全局/上游/服务端配置、克隆、热更及连接池 key |

- [ ] 随机能力限定于配置允许的自有/授权服务 Host、Path/Query 候选集合；固定模式仍可用。默认不生成第三方网站域名，不声称随机字段带来匿名、安全、通用 HTTP 互操作或网络可达性保证。随机策略属于配置行为，不应恢复旧 HTTP Pseudo 协议或重复编码栈；具体默认值由部署配置明确。
- [ ] RSS/RPC/RRP 使用同一 EgressConfig 与实现；支持合法配置验证、HTTP BodyEncoding、头/体/解压预算、服务端 allowedCodecs、固定与随机选择行为。RSS 的全局配置及每上游覆盖、RssServer CLI、热更、warm pool 隔离须真正透传这些配置；标准 SOCKS5/SS 对外及第三方标准上游不安装私有 EgressCodec。敏感配置不进日志。旧私有 wire 无兼容要求，但现有业务功能不能丢。
- [ ] 对比固定/随机选择模式的正确性、并发、对象分配、CPU、实际字节、短包延迟；保留 NONE 额外开销为零的目标。默认不增加无意义头字段、重复压缩或新永久线程/定时任务。
- [ ] 回归旧 RRP SOCKS 密码认证、连接失败、重连清理、异常关闭、远端监听绑定失败重试、背压/慢消费者与引用释放。检查新版已有测试与旧删除用例逐项对应，而非要求旧 wire 兼容。保留 Standard SOCKS5 TCP CONNECT。

### P1 · 5. RSS 私有增强链路统一 Transport Core

用户批准统一。现在 RSS 已复用 EgressCodec 与 AdaptiveTcp* 底层，但仍直接通过 SocksConfig/Sockets/RssTcpCodecSelector 安装 RssHopAes、业务 zlib 和 SOCKS greeting；RPC/RRP 则使用 RXTC v2 Hello/HelloAck、AEAD、FRAME_READY 与 TransportSession。不能把共享 codec 等同于已经统一了内部会话。

**目标仅限 RSS↔RSS 的私有增强链路**：共享一套经过认证的可靠会话、加密、实际压缩、出口编码及通用路径管理，SOCKS5 业务仍在上层。标准 Shadowsocks/标准 SOCKS5/第三方 upstream/DNS 线协议保持不变；原生 UDP 继续复用 DatagramTransport/现有 RSS datagram 能力，不因为可靠流统一而改为 UDP over TCP。

- [ ] 先建立现有业务矩阵：plain/daily/weak/game、native/hop/fixed UDP、外部 udpclient/udp2raw、嵌套 SOCKS5、per-route profile、已加密目的端口的压缩绕过、PSK 保护、MTU/FEC/分片、warm pool、连接与 half-close、SS 用户限速、热更排空/回滚。明确每条链路是否进入私有 Core；不把现有四档都替换为 daily。
- [ ] Core 增加必要且最小的 carrier/profile 适配能力（已有 Adaptive UDP、LocalChannel、实际压缩、AEAD 和 EgressCodec 优先复用）。通过原型证明 SOCKS5 握手与业务流可以透明承载，方向/边界/背压/半关闭正确；不要为每次代理转发附加 RPC 对象序列化或不必要的额外数据拷贝、线程、TCP socket。
- [ ] 私有入口采用统一认证握手消除 AUTO 与内层业务压缩的歧义；压缩、加密在就绪边界协商，出口编码仅一次，TCP repair 与原生 UDP 维持现有语义。确保私有链路认证必需、能力不匹配明确失败；不再重复 RssHopAes 与 Core AEAD。业务压缩按目的端口/数据策略保留，不强制每条链路压缩。完成迁移后再清理不再使用的私有实现；仍被非目标调用方使用的底层安全组件不得盲删。
- [ ] RRP、RPC、RSS 共享底层传输实现，不将 RRP/RPC 的业务协议或依赖反向引入 Core。保留 RSS 的独立路由、用户、上游管理与 SS 限速，避免重复计费/限速。原生 UDP 不增加 EgressCodec 包装；Transport Core 的 Adaptive UDP 是可靠流加速，与 UDP 数据报语义不同。
- [ ] 完整新 wire 联调，私有客户端/服务端配套升级，不维护旧 wire 双栈；按完整旧版本回滚。真实外部载体、四 profile、混合上游、加密与压缩开关、固定/AUTO 三出口、TCP/UDP、弱网、慢消费者、半关闭及完整回归。相同有效负载、相同链路条件对比改造前后线字节/CPU/RSS/延迟/吞吐；新增资源必须有界，若性能/正确性不达标则先缩小迁移范围修复，不强推架构。
- [ ] 公网低配 RSS Server 仍不得因未启用 NodeRegistry/Rendezvous/RRP 或新路径能力增加常驻资源；不新增每连接独立线程。通过 JDK8/master、merge master→rss-21 后 JDK21 回归，更新实际配置与部署说明、测试数据及 .memory。

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

> 本轮计划为源码与已有测试报告的静态核对；未执行测试、未修改业务源码。列出的并发/资源风险需要专项复现，旧测试消失不等于已确认功能回归。实施时按最新代码重核。
