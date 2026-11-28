# RSS Client → RSS Server 传输配置

适用于同版两端的内置 RSS 跳。外部 Shadowsocks 协议沿用原配置；直连目的地和标准第三方 SOCKS5 最终出口不注入 RSS 私有帧。

## 1. 四种固定 Profile

在 Client 的 `conf.yml` 中给已有的 SS 用户增加 `transport`。省略时使用 `plain`。热更新影响新建连接和 UDP 会话；用户按外部 SS 用户名匹配，内部 SOCKS 账号可复用。

```yaml
shadowUsers:
  - ssPort: 6898
    transport:
      profile: daily
```

| `profile` | TCP 默认 | 普通业务 UDP 默认 | UDP 跳跃 | 额外 `udpclient` 承载 |
| --- | --- | --- | --- | --- |
| `plain` | 原可靠 TCP（端口 codec 按 §3） | 原生 UDP 单发（无 LZ4/FEC/复制/分片；必须保护端口保留 AES-128-GCM） | 不启用 | 禁止 |
| `daily` | zlib 3 + `AdaptiveTcpStream` 自适应 XOR + TCP 修复 | 按收益 LZ4 + `AdaptiveUdpFec` 自适应 XOR + 认证分片 | 可配置（默认 `true`） | 支持（须 `udpHop: false`） |
| `game` | zlib 3 + `AdaptiveTcpStream` 自适应 XOR + 按需扩窗 | 按收益 LZ4 + `AdaptiveUdpReplication` 自适应 1～3 倍（首份有效副本立即交付，不叠 FEC） | 可配置（默认 `true`） | 支持（须 `udpHop: false`） |
| `weak` | zlib 3 + 按编码字节预算 XOR + 按需扩窗 + 选择性 TCP 修复 | 按收益 LZ4 + `AdaptiveUdpFec` 按编码字节预算 XOR + 认证分片 | 可配置（默认 `true`） | 支持（须 `udpHop: false`） |

收口后的固定规则：

- `profile` 是增强模式的唯一算法选择入口：增强模式下不再自由改写 `fec` / `tcp` / `udp` 组合以偏离 profile 语义；非算法参数（`tcpCompressionLevel`、`udpHop`、`udpMtu`、显式关闭普通 UDP LZ4 `udp: 0`）仍按规则配置。`plain` 保留既有 TCP 压缩配置与显式位掩码（`0..3`，仅 zlib + PSK AES）。
- `udpHop`（布尔值，省略默认 `true`，`plain` 不支持 `true`）独立控制自适应 TCP/FEC 与增强普通 UDP 是否启用双端口 30 秒轮换跳跃；设为 `false`（或 Server `-udpHop=0`）时，保留 `daily` / `weak` / `game` 的压缩、XOR/副本与 TCP 修复能力，协商单端口（`primary == alias`，非 AEAD TCP 经 SOCKS5 `0x8c` 轻量扩展协商，AEAD 或显式 `CORE` 经 `TransportCoreCarrier` `RXTC` v2 `FLAG_UDP_HOP` / `serverUdpAliasPort` 协商，普通 UDP 经 RPC `SUPPORT_NO_HOP_UDP` 协商）。`OutboundProfiles`、TCP 预热池与 UDP 租约池均按 `udpHop`、MTU、`handshakeMode`、`securityMode` 与完整 `EgressConfig` 隔离缓存。
- **五维正交配置**：
  - `securityMode`（顶层或 `shadowUsers[].transport.securityMode`，Server `-securityMode=fast|none`）：默认 **`fast`**（固定 `AES-128-GCM + HMAC-SHA256`，16 B 握手标签与每记录 2 B 长度 + 16 B GCM Tag，无替代加密/MAC 算法，禁止 XOR 认证）；`none` 仅允许显式无额外加密链路，不得与 `tcpCipherPorts` / `enableEncryption=true` 混用。
  - `handshakeMode`（顶层或 `shadowUsers[].transport.handshakeMode`，Server `-handshakeMode=lightweight|core`）：RSS 默认 **`lightweight`**（零新增 RTT：纯 `plain` 非 AEAD 无压缩直走标准 SOCKS5 且不分配 `NonceReplayCache`；非 AEAD `plain+zlib` 与 `daily`/`weak`/`game` 经私有方法 `0x8c` 将 35 B 客户端扩展与 43 B HMAC-SHA256 应答捎带于现有 RFC 1929 密码认证往返中，内层首字节恒为 `0x05`，与 Server `AUTO` 零歧义；受保护端口 `RSS_PSK_AES` 自动走单次认证 `RXTC` v2 AEAD 握手）；设为 `core` 时所有 RSS 私有 TCP 统一执行 `RXTC` v2 握手。
  - `maxBusinessInflateBytes`（Server `-maxBusinessInflateBytes=4194304`）：业务 `zlib 1..9` 解压输出独立上限（默认 `4 MiB`，范围 `4 KiB..64 MiB`），与 `EgressConfig.maxInflateBytes` 彻底解耦。
- `tcpCompressionLevel` 取 `1..9`，默认 `3`，仅实际业务压缩开启时生效。Client 用户字段控制上行，Server `-tcpCompressionLevel=3` 控制下行，解码两端独立兼容。
- 业务实际压缩只在 `AdaptiveTcpStream` 之上执行一次：自适应 TCP 的 UDP 快路径（FEC）与 TCP 修复帧复用同一份已编码业务流，绝不二次压缩或二次业务加密。
- 游戏副本默认单发起步，按方向反馈升档（可用 `-Dapp.rss.game.initialCopies=2` 对照测试）；`weak` 尾包探测可用 `-Dapp.rss.weak.tailProbe=tcp` 选择 TCP 候选。

## 2. 三类完整上游

RSS 上游作为完整逻辑对象同时具备 TCP 与 UDP 能力，配置在 `socksServers` 中通过 `type` 声明（默认为 `rss`）：

| `type` | 典型配置字段 | 适用 Profile | 说明 |
| --- | --- | --- | --- |
| `rss` | `endpoint`（可选 `rpcEndpoint`、`egress`） | 全部 4 种 | 直连 RSS Server 的原生 TCP/SOCKS 入口、RPC 控制面与 UDP 数据面（支持跳跃或单端口）。 |
| `socks5`（标准出口） | `endpoint`（不填 `rssEndpoint`） | 仅 `plain` | 标准第三方 SOCKS5 最终代理，不注入任何 RSS 私有 codec/出口包装；命中 `tcpCipherPorts` / `udpCipherPorts` 必须保护端口时显式拒绝，绝不静默降级为明文。 |
| `socks5`（嵌套承载） | `endpoint`（外层代理）+ `rssEndpoint`（目标 RSS Server）+ 可选 `rpcEndpoint`、`egress` | 全部 4 种 | 通过 `RssSocksCarrier` 经外层 SOCKS5 `CONNECT` 与 `UDP ASSOCIATE` 完整承载 RSS TCP、RPC、自适应 UDP 与普通增强 UDP（如外接 Hysteria2 本地 SOCKS5）。 |
| `udpclient` | `tcpClient` + `udpClient` + `adaptiveUdpClient`（+ `endpoint`/`rpcEndpoint`/`egress`） | 仅 `daily` / `weak` / `game` | 固定外部 UDP 映射（如外接 `udp2raw`），须显式配对可靠 TCP 与双路 UDP 入口，绑定 `udpHop: false` 与显式 `udpMtu`，服务端开启 `-fixedUdpPort=<port>` 由 `RssFixedUdpEntry` 按会话令牌解复用。 |

不再保留 `kcptun` 或 RSS 专用的 `udp2raw` 路由类型/服务端 `-udp2rawPort`（改用 `-fixedUdpPort`）；旧的 `udp2rawClient` 等字段解析时直接拒绝。

## 3. 全模式 DNS AOP、端口 Codec 策略与 PSK AES-128-GCM

决策顺序：**认证与访问控制 → DNS AOP（TCP/UDP 53）→ `RssPortPolicy` → Profile 策略 → 选定上游承载**。

### 3.1 DNS AOP（`RssDnsInterceptor`）

- 全入口（Shadowsocks TCP/UDP 53、本地 SOCKS5 TCP/UDP 53）与全 4 种 profile 统一由 `RssDnsInterceptor` 在普通路由和 profile codec 之前截获。
- 严格遵守用户/全局 `BLOCK` / `DIRECT` / `PROXY` 规则：
  - `BLOCK` 优先于缓存与本地 hosts，直接返回 `REFUSED`。
  - `DIRECT` 使用本地 hosts / 直连解析器。
  - `PROXY` 从该用户可用上游中挑选健康且具备 RPC 能力的 RSS 对端（混合 `[socks5, rss]` 上游列表时自动跳过无 RPC 的第三方 `socks5`），A/AAAA 走 `resolveHost`，其余 RR 类型（如 `TXT` / `MX` / `SRV`）经 `queryDnsRecord`（`DnsRecordExchange`）透传；缓存键按路由动作、用户、来源 IP、对端路由 ID 与域名隔离。无可用健康 RPC 时返回 `SERVFAIL`，绝不暗中回退本地系统 DNS 或转发给第三方代理。
- 非法/非 DNS 载荷在端口 53 上直接拒绝/关闭连接；UDP 响应超过 512 B 自动置 `TC` 提示客户端切 TCP 重试。
- `RssServer` 默认通过 `enforceServerDnsPolicy` 拒绝意外直达服务端的原始 TCP/UDP 53 流量（除非显式通过 `-tcpCipherPorts` / `-udpCipherPorts` 放行并强制 AES），同时保持认证 DNS RPC 正常可用。

### 3.2 默认端口表与加密边界

| Client 顶层字段 | 默认目标端口 | 行为 |
| --- | --- | --- |
| `tcpCipherPorts` | `21, 23, 25, 80, 110, 143, 389, 587, 1883, 8080` | 必须认证加密：业务实际 `zlib`（仅显式 `tcp: 3` 且位于加密内层）→ `TransportCoreCarrier` 单层 AES-128-GCM（`RXTC` v2 `FLAG_AUTH + FLAG_ENCRYPT`）。 |
| `tcpCodecBypassPorts`（迁移别名 `tcpAlreadyEncryptedPorts`） | `22, 443, 465, 636, 853, 990, 993, 995, 8883` | 跳过业务实际压缩与业务加密；增强模式仍保留 TCP 自适应 FEC 与可靠修复。 |
| `udpCipherPorts` | 空列表 `[]` | 可显式配置必须保护 UDP 端口：增强模式先按需 LZ4 → 分片/FEC 或多发 → `RssDatagramCipher`（AES-128-GCM）；`plain` 走 AES-only 保护（无 LZ4/FEC/复制/分片/跳跃）。 |
| `udpCodecBypassPorts`（迁移别名 `udpAlreadyEncryptedPorts`） | `443, 853` | QUIC / DoQ 跳过业务 LZ4 与额外加密；增强模式仍保留 FEC 或游戏副本。 |

- 端口 `53` 保留给 `RssDnsInterceptor`，不允许配置进上述任意保护或旁路列表；同一协议的保护表与旁路表不允许重叠。
- **RSS↔RSS 私有增强 TCP 链路（统一 `TransportCoreCarrier` + 默认 `LIGHTWEIGHT` 轻量握手 + 固定 `FAST` 套件）**：原 `RssHopAes`、`RssTcpCodecSelector` 与 `SocksAdaptiveHandshake` 已移除。默认 `handshakeMode: lightweight` 下：纯 `plain` 非 AEAD 无压缩直走标准 SOCKS5 零额外握手；非 AEAD `plain+zlib` 与增强模式通过 SOCKS5 `0x8c` 捎带防重放 HMAC-SHA256 扩展（零新增 RTT，单 TCP 段应答）；命中 `tcpCipherPorts`（`RSS_PSK_AES`）或显式 `handshakeMode: core` 时，使用 32 字节 Base64 密钥 `RSS_TCP_AES_KEY`（或密码派生密钥）完成 `RXTC` v2 双向 HMAC-SHA256 鉴权与 `NonceReplayCache` 防重放，受保护端口开启单层每会话/每方向独立 `AES-128-GCM`（`TransportAeadCodec`），不叠双层加密；业务 `zlib 1..9` 严格在 AEAD 内层与 `AdaptiveTcpHandler` 之上执行。服务端用户认证严格 fail-closed（错密/禁用/撤销/身份不一致均拒绝，绝不回落 `ANONYMOUS`），共享 PSK 不可替代用户授权。缺失或非法密钥时配置拒绝启动，不静默退回明文。标准 Shadowsocks、标准 SOCKS5、第三方标准上游与原生 UDP 协议不受影响。

## 4. 统一公共 TCP 出口包装（`EgressCodec`）

真实 TCP 连续流出口包装已收敛为公共组件 `org.rx.net.EgressCodec` / `EgressConfig` / `EgressPipeline`（统一服务 RSS、`Transport Core v2`、`RrpServer`/`RrpClient` 与 `RendezvousService`，原 RSS 专属 `RssEgressCodec`、`RSZ\x01` 自定义前缀及旧 `HttpPseudoHeader*` 已删除），与业务实际压缩（`zlib 1..9`）完全解耦，**默认关闭（`none`），按需显式开启**。客户端 `egress` 严格支持 **`global → user → upstream` 局部继承**：省略字段（`null`）继承上级配置，显式设置 `tcpCodec: none` 覆盖关闭；客户端禁止填写 `allowedCodecs`（仅服务端 `AUTO` 支持）：

```yaml
# 可配在 Client 顶层（全 RSS 路由默认）、shadowUsers[].transport 或单个 socksServers 条目下（支持局部字段继承）
egress:
  tcpCodec: none              # 可选：none（显式关闭） | zlib-stored | http；省略(null)时继承上级
  udpCodec: none              # 当前仅允许 none；省略(null)时继承上级
  httpRandomHeaders: true     # 默认 true（内置随机 Host/Path/Query + 80% GET / 20% POST + 请求/响应头模板）；设为 false 走固定 Host/Path
  httpHost: ""                # 仅 httpRandomHeaders=false 时使用的固定 Host
  httpPath: ""                # 仅 httpRandomHeaders=false 时使用的固定 Path
  httpBodyEncoding: none      # 可选：none（默认） | zlib-stored
  maxHeaderBytes: 4096        # HTTP 首部预算上限（严格与 payload 分离，防合并读误拒）
  maxBodyBytes: 16777216      # 单个 HTTP 帧体长度上限
  maxInflateBytes: 4194304    # 出口层 ZLIB_STORED/HTTP body 解压上限（独立于 maxBusinessInflateBytes）
  detectTimeoutMillis: 5000   # 仅服务端 AUTO 首包探测超时
```

- 服务端通过启动参数 `-tcpEgressCodec=none`（默认）、`-tcpEgressCodec=zlib-stored`、`-tcpEgressCodec=http` 或 `-tcpEgressCodec=auto`（单端口 1-shot `EgressAutoDetector`），并支持 `-tcpEgressAllowedCodecs=...`、`-tcpEgressHttpRandomHeaders=true|false`、`-tcpEgressHttpHost=...`、`-tcpEgressHttpPath=...`、`-tcpEgressHttpBodyEncoding=none|zlib-stored`、`-tcpEgressMaxHeaderBytes=...`、`-tcpEgressMaxBodyBytes=...`、`-tcpEgressMaxInflateBytes=...`、`-tcpEgressDetectTimeoutMillis=...` 完整透传 `EgressConfig`。
- `zlib-stored` 使用纯标准 RFC 1950 `ZLIB` 2 字节头（`0x78 0x01`）+ DEFLATE Level 0 stored block（无私有 `RSZ` 魔数），位于加密层与握手最外侧包裹真实 TCP 物理流；因 `LIGHTWEIGHT` 首字节恒为 `0x05`、`CORE`/AEAD 首字节恒为 `'R'`（`"RXTC"`），且业务 `zlib` 严格在认证后安装，无加密的业务 `zlib 1..9` 在 Server `AUTO` 模式下**零歧义**。
- `http` 采用方案 A 零拷贝全双工流式封装（`Content-Type: application/json` + 精确 `Content-Length` + 二进制加密/握手 Body，明确为私有约定而非标准 JSON HTTP 语义）：默认 `httpRandomHeaders=true` 恢复旧内置随机 Host/URL Path/Query、80% `GET` / 20% `POST`、Chrome 137 `User-Agent`/`Accept`/`Connection` 与实时 UTC `Date`/`Server: Tengine` 响应头（Host 仅作报文头字段，不参与 DNS/拨号/路由/TLS 校验）；`httpRandomHeaders=false` 保留显式固定 Host/Path 模式。`AdaptiveTcpStream` 的 UDP 快路径与普通业务 UDP 均**不经过** `EgressCodec`。
- `none` 路径零额外 handler、零额外包装开销；标准第三方 `socks5` 路由禁止配置非 `none` 的 `egress.tcpCodec`。
- 完整 `EgressConfig`、`handshakeMode` 与 `securityMode` 已纳入 `TcpWarmPoolKey` 与路由配置缓存，热更新仅作用于新连接。
- **普通 UDP 复用 RPC 控制面（`AUTO`）与按需资源池**：已认证 RSS 对端上的 `plain` UDP（`udpRelayControlMode = AUTO`）优先走 `SocksRpcCapabilitiesCache`（零线程单飞缓存）+ `openUdpRelayGroup`（含 `clientRequestId` 幂等与原子容量预留），零专用 SOCKS5 TCP 控制连接且同关联跨目标复用零额外控制 RTT；RPC 不可达时 `plain AUTO` 0 ms 回退 `SOCKS5_COMPAT`，标准第三方 SOCKS5 继续走 `UDP ASSOCIATE`。TCP Warm Pool 改为共享单调度器按需收缩至 0（进程级上限 64 pool / 64 ready / 8 in-flight），`rpcMinSize` 默认由 `2` 降至 `1`（支持显式 `0` 完全按需收缩）。

## 5. 统一 UDP MTU 预算、认证分片与跳跃端口

- `RssUdpMtuBudget` 统一负责加密决策前置、实际 AES 后置的 MTU 预算计算：`udpMtu` / `dailyUdpMtu` 表示净 RSS UDP payload 上限（不含外层 IP/UDP 头；`0` 表示该层不设显式限制，嵌套 SOCKS5 承载仅扣减一次外层 SOCKS5 UDP 头长）。
- 普通增强 UDP（`RssDatagramHandler`）：按收益 LZ4（≥96 B、净省 ≥24 B 且 ≥12%，连续 8 次低收益旁路 30 秒）→ 按预算判断是否需要认证分片（`RssDatagramFragmenter`，`FRAG_MAGIC = 0x0001`，单会话上限 16 组 / 256 KiB、全局上限 24 MiB、超时 750 ms）→ `AdaptiveUdpFec`（daily/weak）或 `AdaptiveUdpReplication`（game）→ 外层 `RssDatagramCipher`（AES-128-GCM，`RSS_UDP_AES_KEY`）或 `RssDatagramMac` → 最终出口长度守卫。`plain` AES-only 不启用分片/FEC/复制/LZ4，超限直接失败绝不退回明文。
- 自适应 TCP 的 UDP 数据面（`AdaptiveTcpStream` + `AdaptiveUdpPort`）：在 `TransportCoreCarrier`（`RXTC` v2 `Hello`/`HelloAck`）握手阶段统一协商 profile、`FLAG_UDP_HOP`、`serverUdpAliasPort` 与有效 `udpMtu`，动态收缩 `CHUNK` 保证每个 DATA / PARITY 帧不超过预算，路径受限时平稳回退至可靠 TCP 修复。
- 默认专用 UDP 端口段为 `20000-20127`（Server `-udpHopPorts=起始-结束`）。首对由全部自适应 TCP 共用，其余每对服务一个普通增强 UDP 组；Windows 绑定双 socket，Linux 使用 `nft` 将第二端口重定向至主端口（需要 `CAP_NET_ADMIN`）。
- 外部 Shadowsocks 与 SOCKS5 `CONNECT` 保持客户端先 FIN 单向半关闭语义与背压感知 `HalfCloseWatch`（默认 120 s）。

## 6. 核心监控指标与堆外内存基线

启用诊断后，建议持续采集以下核心指标（按 1 vCPU / 768 MiB 服务端预算基线对照进程 RSS 与 Netty 堆外内存）：

| 指标 | 含义 |
| --- | --- |
| `rss.tcp.egress.rx.bytes`、`rss.tcp.egress.tx.bytes` | 真实 RSS TCP 物理出口包装（`zlib-stored`）的实际收发字节统计，独立于业务压缩统计。 |
| `rss.tcp.adaptive.probes`、`transitions`、`fallbacks`、`tcpMillis`、`peakPendingFrames`、`deliveredBytes`、`udpUnavailable` | 自适应 TCP 流关闭汇总：探测、路径切换、`window/timeout/fin` 回退、TCP 驻留、待确认峰值及双路交付字节。 |
| `rss.tcp.weak.udpTailProbes`、`probeAckedBeforeTcp`、`probeHandedToTcp`、`probeOutstandingAtClose`、`probeGroupParityBytes`、`filteredDuplicates`、`ackSent`、`untrackedTcpFrames`、`gapSent`、`sackSent` | `weak` 尾包探测、选择性 SACK/GAP 修复、去重与校验开销。 |
| `rss.udp.daily.mtuDrop`、`finalMtuDrop`、`tx.unprotectedGroups`、`rx.unprotectedGroups`、`frag.tx.*`、`frag.rx.*` | 普通 UDP MTU 超限拒绝、出口守卫丢弃、未保护分片组，以及认证分片发送/重组/超时/验签失败/预算拒绝计数。 |
| `rss.udp.fec.sessions.total`、`sessions.active`、`cache.bytes`、`cache.peakBytes` | UDP FEC 活跃会话数及每会话有界 XOR 累加器直接内存占用与峰值。 |
| `rss.upstream.health`、`rss.upstream.health.failures` | 各逻辑上游健康状态与连续失败次数。 |
| `ss.aead.rejected`、`ss.salt.*` | 外部 Shadowsocks AEAD 认证/截断拒绝与 `SaltHistory` 重放/淘汰指标。 |
| **JVM / Netty 堆外内存指标（必选）** | `PooledByteBufAllocator.DEFAULT.metric().usedDirectMemory()` / `usedHeapMemory()`、JVM `BufferPoolMXBean("direct")`、EventLoop pending tasks、GC 停顿与进程 RSS（`-Dio.netty.leakDetection.level=SIMPLE` 线上常驻，压测/回归使用 `PARANOID` 确保 `LEAK: 0`）。 |

[Hysteria 2 的 Linux 端口重定向](https://v2.hysteria.network/docs/advanced/Port-Hopping/) · [IANA 端口登记](https://www.iana.org/assignments/service-names-port-numbers/service-names-port-numbers.xhtml)
