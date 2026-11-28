# NodeRegistry、NAT 打洞、RRP TCP Relay 与公共 EgressCodec 实施计划

日期：2026-11-28。原源码基线：[c54aa200](https://github.com/RockyLOMO/rxlib/commit/c54aa200f012d261ad3e6be907b4b745391384d8)。
关联：[最新测试](test-benchmarks.md)、[前次 Review](rxlib-review-20261128.md)、[Roadmap](roadmap-plan.md)、[架构](net-architecture.md)。

## 1. 本次明确决策

本计划根据用户最新要求制定（2026-11-28 补充 RRP/公共 TCP EgressCodec 收口），**覆盖前次 Review §6、Roadmap §3 中 NAT 打洞与公网 TCP Relay 仅 ADR/暂缓的范围限制**。现阶段正式实施；旧文档作为历史记录保留，实施后同步 roadmap、reference、架构图与测试报告。

- RSS Client Launcher 承载 NodeRegistry 节点服务（服务端角色，需有可达公网地址或合法端口映射）；不是要求每个普通 RSS 终端都运行独立 H2 或公开节点服务。RSS Server 仍不默认启动该模块。
- 普通内网节点以注册客户端角色使用统一节点服务，复用 AddressDiscovery、RPC、Transport Core，按需启动 UDP 路径能力。Nameserver 使用同一个 NodeRegistry，不再维护两份在线权威目录。
- **不恢复已删除的 UdpClient、FuryUdpClientCodec、UdpHolePunch*、Hybrid 或重复 UDP ACK/FEC/重发协议。** 历史打洞实现只作功能参考，不作为新增依赖。
- UDP 打洞是通用路径建立能力；可靠 TCP Relay 是通用承载能力。优先复用 RrpClient/RrpServer 的反向连接、逻辑通道、背压、close/reconnect；扩展受授权的 RAW 承载，不新建另一套反向代理引擎。保留 SOCKS 反向代理业务能力，但无需兼容旧 RRP 私有 wire、旧 HTTP_PSEUDO handler 或旧传输配置；客户端和服务端配套升级。
- RSS、RPC、RRP 共享 Transport Core、实际压缩、认证加密和公共 TCP EgressCodec；业务模块只保留各自职责。端到端可靠流使用 daily，RRP Relay 物理承载默认纯 TCP，不重复 FEC/重传。RPC 仍负责序列化、方法与事件。
- EgressCodec 仅负责物理 TCP 最终出口，三种实际格式 NONE / ZLIB_STORED / HTTP，UDP 不增加出口包装。ZLIB_STORED 输出纯 zlib level 0 流；HTTP 默认采用用户指定的方案 A：二进制密文 Body，Content-Type 固定声明 application/json，不转 JSON、不做 Base64、不默认加 zlib 0。该 Content-Type 与实际内容不一致，是明确的私有协议选择，不宣称标准 JSON/HTTP 应用互操作或不可识别性。此前讨论的 HTTP Body zlib 0 保留为独立可选扩展，默认关闭，不与独立 ZLIB_STORED 层叠加。
- 公共 EgressCodec 必须支持服务端 AUTO（每连接一次、允许列表、统一认证握手）；客户端明确选定实际编码。删除 RSZ 后不能依赖 zlib 压缩等级区分业务层和出口层。详见 §5.4.5。
- 私有传输 wire 不要求向后兼容，不保留旧协议双栈、旧 preface 或兼容性自动降级；标准 SOCKS5、Shadowsocks、DNS 的外部协议仍保持正确语义。
- 现有全局限速缺陷仍仅记录、不处理。SS 用户粒度限速保持在 SS 入口，不自动给 Relay 或内部 RPC 叠加用户额度；Relay 的独立资源/授权配额不是重复 SS 业务限速。
- 安全、正确性、稳定性为硬约束；优先实际流量及 CPU，内存有界。严格 Java 8 master、JDK21 回归；RSS Server 1 vCPU/768 MiB 整机基线仍不变，RSS Client 没有相同硬件限制但需实际增量资源预算。遵循 [AGENTS.md](../AGENTS.md)。

## 2. 当前源码核对与复用边界

| 模块 | 已具备 | 缺失/本轮工作 |
|---|---|---|
| AddressDiscovery / EndpointCandidate | 多网卡地址、HTTPS 公网 IP、TCP observed、UDP mapped 数据模型与缓存 | 真正同一 UDP socket 的公网端点观测、会话绑定的路径验证 |
| NodeRegistry | H2 三表、在线目录、TTL、候选地址、认证/ACL 基础 | Nameserver 实际接入、严格并发及资源上限、会话授权一致性 |
| Nameserver | RPC 注册/发现/分区复制 | 统一 NodeRegistry 权威目录、远端租约/回收 |
| DatagramTransport | 原生 UDP 收发 | 路径协调所需受限控制消息，不提供可靠应用消息协议 |
| Transport Core | daily TCP/UDP、HMAC 握手、FEC、TCP repair、动态 PMTU | 双 NAT 可靠虚拟承载与已验证 UDP 路径注入；端到端认证加密及防重放验收 |
| RRP | 内网主动反向连接、remotePort/channelId、多路转发、背压和关闭 | 迁移公共 Transport Core / EgressCodec，RAW 私有会话 Relay，A/B 身份授权、无需每服务开放公网端口 |
| TCP EgressCodec | RSS RssEgressCodec、RRP HTTP_PSEUDO、Sockets 公共 pipeline | 收口一套公共出口编码，明确编码顺序和私有 wire 新版本 |
| RssLauncher / RssRuntime | RSS Client 启动 DNS/Nameserver，已有 RRP 热更计划 | 持有单一 NodeRegistry/节点服务生命周期及事务式启停 |

静态依据：

- [AddressDiscovery.java L181-L237](https://github.com/RockyLOMO/rxlib/blob/c54aa200f012d261ad3e6be907b4b745391384d8/rxlib/src/main/java/org/rx/net/AddressDiscovery.java#L181-L237)：recordUdpMapped 接收调用者已经观察到的 endpoint；不是主动探测或打洞。HTTPS_PUBLIC 的 port=0 不可当作 UDP 可达地址。[EndpointCandidate.java L34-L74](https://github.com/RockyLOMO/rxlib/blob/c54aa200f012d261ad3e6be907b4b745391384d8/rxlib/src/main/java/org/rx/net/EndpointCandidate.java#L34-L74) 有地址族、协议、来源、TTL、verified，verified 不能由远端自报直接成为信任依据。
- [NodeRegistry.java L262-L281](https://github.com/RockyLOMO/rxlib/blob/c54aa200f012d261ad3e6be907b4b745391384d8/rxlib/src/main/java/org/rx/net/registry/NodeRegistry.java#L262-L281) 当前构造会创建 H2 映射/调度，即使 config.enabled 字段默认 false；需由运行时工厂/调用者真正控制实例化。
- [NameserverImpl.java L388-L435](https://github.com/RockyLOMO/rxlib/blob/c54aa200f012d261ad3e6be907b4b745391384d8/rxlib/src/main/java/org/rx/net/nameserver/NameserverImpl.java#L388-L435) 仍持有 SourcePartition 和独立 RPC 目录，没有 NodeRegistry 注入。普通注册与副本消息认证是不同安全边界。
- [TransportClient.java L365-L397](https://github.com/RockyLOMO/rxlib/blob/c54aa200f012d261ad3e6be907b4b745391384d8/rxlib/src/main/java/org/rx/net/transport/core/TransportClient.java#L365-L397) 当前以 TCP handshake 对端及 serverUdpPort 安装 AdaptiveTcpHandler；[TransportServer.java L250-L280](https://github.com/RockyLOMO/rxlib/blob/c54aa200f012d261ad3e6be907b4b745391384d8/rxlib/src/main/java/org/rx/net/transport/core/TransportServer.java#L250-L280) 使用服务端 UDP listener。现有 Core 不会自动连接 NAT 后的另一个 peer，也不等同于 UDP 路径已打通。
- [TransportConfig.java L22-L44](https://github.com/RockyLOMO/rxlib/blob/c54aa200f012d261ad3e6be907b4b745391384d8/rxlib/src/main/java/org/rx/net/transport/core/TransportConfig.java#L22-L44) authSecret 可选；握手 HMAC 不是完整数据面端到端 AEAD 证明。新节点服务必须强制认证，不继承可选空 secret 的默认行为。
- 删除旧 UdpClient/UdpHolePunch 的提交：[26dc7870](https://github.com/RockyLOMO/rxlib/commit/26dc78706fe7c6a796c351f3c3ac3084ac080673)。当前无替代的完整打洞实现，勿以地址记录 API 或普通 Adaptive UDP probe 充当验收。
- [RssLauncher.java L15-L34](https://github.com/RockyLOMO/rxlib/blob/c54aa200f012d261ad3e6be907b4b745391384d8/rxlib/src/main/java/org/rx/util/rss/RssLauncher.java#L15-L34) 根据 shadowMode 分派；[RssRuntime.java L73-L100](https://github.com/RockyLOMO/rxlib/blob/c54aa200f012d261ad3e6be907b4b745391384d8/rxlib/src/main/java/org/rx/util/rss/RssRuntime.java#L73-L100) 已是 RSS Client 子服务的生命周期入口，适合在这里统一管理 NodeRegistry。[RssClientConf.java L112-L120](https://github.com/RockyLOMO/rxlib/blob/c54aa200f012d261ad3e6be907b4b745391384d8/rxlib/src/main/java/org/rx/util/rss/RssClientConf.java#L112-L120) 已有 nameserver、rrpToken、rrpPort。

## 3. 目标架构：一个节点服务、一个可靠传输内核

```text
                    RSS Client Launcher（公网节点服务角色）
                  RssRuntime / NodeService（生命周期）
                                |
        +-----------------------+----------------------+
        |                       |                      |
  Nameserver (DNS)      Rendezvous (路径协调)      RRP Relay (承载)
        |                       |                      |
        +------------------ NodeRegistry -------------+
                          在线目录 / 授权
                          H2 EntityDatabase 冷数据
                                |
                   RPC 控制面 / Transport Core
                                |
                 AddressDiscovery / Netty I/O

内网节点 A  ── 主动认证可靠连接 ── 公网 RRP Relay ── B 的反向连接 ── 内网节点 B
      └────────── 已验证直连 UDP 快路径（如可用） ────────────┘
```

Nameserver 保留标准 DNS TCP/UDP、查询索引及复制协议所需 epoch/version/tombstone。NodeRegistry 是节点/实例/候选/授权权威；RPC 是控制面；RRP 负责可靠中继承载。独立组件可同进程部署，按需启用。不能让 Transport Core 依赖 RSS 或 Nameserver；不能让 Relay 业务数据经过 RPC 对象反序列化。

### 3.1 NodeRegistry 启动及配置

用户明确要求 RSS Client Launcher 同时运行 NodeRegistry。建议 RssClientConf 增加 nodeRegistry（复用 NodeRegistryConfig）与最小节点服务配置；RssRuntime 负责启动/关闭而不是 main 创建全局静态实例。部署该 Launcher 的节点服务配置显式 enabled=true；为防止无意暴露服务，新功能未配置时默认关闭，不无意开启公网监听。启用 NodeRegistry 时 Nameserver 与协调服务共享同一实例；开启公网能力须满足角色、监听地址、认证、ACL 与配额校验。不把私密凭据放在普通节点列表/示例配置/日志。公网/内网角色可以独立测试与部署。

拟议配置结构（字段名/协议版本实施时锁定；不是当前可直接使用配置）：

```yaml
nodeRegistry:
  enabled: true
  dbPath: ./data/node-registry
  maxOnlineNodes: 1024
  maxCandidatesPerNode: 16
  maxAttrsBytesPerNode: 8192
  maxColdCacheBytes: 2097152
  maxPendingPersistBytes: 2097152
  h2CacheSizeKiB: 8192
  h2MaxConnections: 4
nodeService:
  enabled: true
  rendezvousEnabled: true
  relayEnabled: true
```

认证及监听端点另外明确配置并在启动时强校验，不提供无认证公网默认值。现有 nameserver/rrp 配置复用，避免同一监听端口双 bind；NodeRegistry enabled 与 nodeService enabled 的依赖关系校验，功能关闭不创建对应 Socket/定时器/DB。支持只运行 Nameserver + NodeRegistry 而不开放打洞/Relay。普通 RSS 代理数据路径不因本功能改变。

启动顺序：验证配置及资源→打开 H2/NodeRegistry→注入 Nameserver→准备认证控制服务/可选 Rendezvous/RRP→发布服务状态；失败逆序回收。关闭顺序：停止新注册/协商→排空或关闭会话与 Relay→Nameserver/RPC→flush 冷数据并关闭 H2。热更复用同一目录/池，先准备后提交，失败回滚；DB 路径等不可安全热更的配置明确要求重启或维护窗口，不能无声重建两个注册表。接入已有 RssRuntime 事务及 shutdown。

### 3.2 先收口 NodeRegistry / Nameserver

以下是上一轮最新源码 Review 发现的先决条件，未通过不得公开节点服务：

- [NodeRegistry.java L454-L481](https://github.com/RockyLOMO/rxlib/blob/c54aa200f012d261ad3e6be907b4b745391384d8/rxlib/src/main/java/org/rx/net/registry/NodeRegistry.java#L454-L481) 在线数量 check-then-put 非原子；用有界原子准入，同 node 重连替换不得重复占额度。禁用、凭据/ACL 变更与上线/缓存刷新需要一致授权 generation；防止验证完成后被禁用仍上线。旧会话 heartbeat、服务注册、下线不能操作新会话。
- [NodeRegistry.java L540-L610](https://github.com/RockyLOMO/rxlib/blob/c54aa200f012d261ad3e6be907b4b745391384d8/rxlib/src/main/java/org/rx/net/registry/NodeRegistry.java#L540-L610) liveServices/metadata 与返回列表未严格有界。建议每节点在线/持久服务各 32、服务 metadata 合计 8 KiB，发现分页，所有字段字节上限、全局总量、租户限额明确；与现有 1024 节点、16 候选、2 MiB cold cache、2 MiB pending、8 MiB H2 cache、90s online TTL 配合。数值为初始建议非实测；NodeRegistry 增量内存目标约16 MiB/初期上限32 MiB（不含业务连接及 Relay 队列）。
- [NodeRegistry.java L662-L719](https://github.com/RockyLOMO/rxlib/blob/c54aa200f012d261ad3e6be907b4b745391384d8/rxlib/src/main/java/org/rx/net/registry/NodeRegistry.java#L662-L719) 属性配额读取再入队及 flush 中间态需原子化；[NodeRegistry.java L880-L915](https://github.com/RockyLOMO/rxlib/blob/c54aa200f012d261ad3e6be907b4b745391384d8/rxlib/src/main/java/org/rx/net/registry/NodeRegistry.java#L880-L915) 同时 flush 及 in-flight batch 未计入 pendingBytes，应有单一 flush owner/总预算，授权更新与普通元数据不得互相覆盖。H2 I/O、密码 KDF 在有界后台执行器，认证请求有准入/速率限制，不在 EventLoop 阻塞。
- Nameserver 注册绑定经过认证的 nodeId+instanceId+当前会话，IP 只是可变属性。保留服务发现 DNS 索引与来源分区必要同步元数据，不再重复持有全套节点资料。NodeRegistry 的在线视图/授权目录是权威；远端 replica 只同步必要服务状态，不同步凭据和私有候选列表，复制对象不是已认证本地连接。来源租约到期删除/隔离远端服务，分区/快照/增量/事件有界；版本间隙全量对账并按来源原子替换，不清空其他来源。
- [NameserverImpl.java L1147-L1187](https://github.com/RockyLOMO/rxlib/blob/c54aa200f012d261ad3e6be907b4b745391384d8/rxlib/src/main/java/org/rx/net/nameserver/NameserverImpl.java#L1147-L1187) 当前副本鉴权与 peer 白名单尚不能保证所有普通注册会话认证。新服务必须绑定经过验证的节点身份与授权对象；不依赖调用者自报 sourceId/nodeId；无授权不能读 LAN 地址、发起探测、注册服务或开 Relay。
- 现有 H2 node/node_attribute/node_service 三表继续使用；只保存长期身份、verifier、ACL、低频属性/持久配置/最后上线。在线路径、UDP 映射、打洞会话、Relay 状态不恢复为在线，不持久化连接与密钥。历史 IP 默认不记录。安全关键变更同步可靠落盘并撤销现有授权；低频数据按 60s 合并批写，不每 heartbeat 写盘。不开新数据库进程。

## 4. UDP NAT 打洞：补路径建立，不重写传输

### 4.1 职责与状态

Rendezvous 是经过认证的协调服务；外部 endpoint observation 是 STUN 类功能（当前非标准 STUN/ICE，除非明确采用标准并通过互操作验证）；Relay 是业务数据承载。节点 A/B 通过可靠 RPC 注册与交换获授权候选。公网观察者必须记录实际业务 UDP socket 对应的外部端点；HTTPS 公网地址、TCP 观察端口不可代替 UDP 映射。多网卡、IPv4/IPv6、局域网、同 NAT hairpin 等只选择合理且经授权的候选，不无条件收集所有虚拟网卡或公开私网信息。

初始 reliable path 可用立即开始业务；直连 UDP 未验证前保持 TCP。直连/打洞成功只意味着 UDP 快路径可以启用，不关闭可靠兜底。所有候选均不可用则 TCP-only；可靠路径也不存在时返回明确失败。UDP 故障后使用已有 adaptive fallback/recovery，不能产生第二套整消息 ACK/重试。目标状态示意：

```text
AUTHENTICATED → RELIABLE_READY
                   ├─ DIRECT_UDP_CHECK → UDP_READY
                   ├─ PUNCH_CHECK      → UDP_READY
                   └─ TIMEOUT          → TCP_ONLY
UDP_READY ── path failure ──→ TCP_ONLY ── bounded recheck ──→ UDP_READY
RELIABLE_READY / UDP_READY ── reliable unavailable ──→ FAIL / authorized reconnect
```

### 4.2 实施边界

新增最小路径协商模块（建议 RendezvousService + peer path adapter，类名非强制），使用 AddressDiscovery、EndpointCandidate、NodeRegistry、RPC 与 DatagramTransport/AdaptiveUdpPort 底层。协调控制数据与业务数据分开；实际路径启用前验证身份、目标会话、端点、有效期和双向可达性；同一 UDP socket 生命周期贯穿观察、检查与业务。未经认证或超限输入不得触发昂贵分配、反射放大或发往任意第三方的流量。候选地址只由有权限的双方获取，观察值与自报值分开；不能信任客户端声明 verified/TTL。对探测、协商会话、来源及全局限制资源；过期立即回收。无业务时不新增高频永久保活；失败退避与既有连接生命周期协调。

AdaptiveTcpHandler/AdaptiveUdpPort 当前由 TCP 对端和端口协商选路径。必须补一个最小可验证的已认证路径绑定接口，使可靠 TCP 通过公网 Relay 时 UDP 的真实对端可以是授权的另一个节点，而不是 Relay 的 TCP 地址。保留 endpoint 绑定、认证、重放检查、方向、MTU 与窗口语义；不得通过关闭现有源地址校验或接受任意 UDP sender 实现。现有普通公网直连/hop/fixed UDP/外部 carrier 不受影响。无须恢复旧 UdpHolePunch* 类型或其旧 wire。

## 5. RRP 复用为 TCP Relay 兜底（核心任务）

### 5.1 现有 RRP 不是现成双 NAT Relay

[RrpConfig.java L28-L63](https://github.com/RockyLOMO/rxlib/blob/c54aa200f012d261ad3e6be907b4b745391384d8/rxlib/src/main/java/org/rx/net/socks/RrpConfig.java#L28-L63) 已定义 REGISTER/FORWARD/SYNC_CLOSE/HEARTBEAT，Proxy 以 name/remotePort/auth 为主。[RrpServer.java L198-L262](https://github.com/RockyLOMO/rxlib/blob/c54aa200f012d261ad3e6be907b4b745391384d8/rxlib/src/main/java/org/rx/net/socks/RrpServer.java#L198-L262) 用 remotePort/channelId 将公网连接数据转发至内网节点反向通道；[RrpServer.java L496-L535](https://github.com/RockyLOMO/rxlib/blob/c54aa200f012d261ad3e6be907b4b745391384d8/rxlib/src/main/java/org/rx/net/socks/RrpServer.java#L496-L535) 当前为每个 proxy bind 公网 remotePort。[RrpClient.java L244-L275](https://github.com/RockyLOMO/rxlib/blob/c54aa200f012d261ad3e6be907b4b745391384d8/rxlib/src/main/java/org/rx/net/socks/RrpClient.java#L244-L275) 最终创建本地 SocksProxyServer（LocalChannel 或 loopback）；[RrpClient.java L441-L505](https://github.com/RockyLOMO/rxlib/blob/c54aa200f012d261ad3e6be907b4b745391384d8/rxlib/src/main/java/org/rx/net/socks/RrpClient.java#L441-L505) FORWARD 时建立到该本地 SOCKS 的连接。[RrpServer.java L40-L110](https://github.com/RockyLOMO/rxlib/blob/c54aa200f012d261ad3e6be907b4b745391384d8/rxlib/src/main/java/org/rx/net/socks/RrpServer.java#L40-L110) 与 RrpClient 有每通道 1 MiB pending 与排空/close，当前不是整个 Relay 进程总量保证。

因此：可以复用 RRP 核心，不能直接把其现有公网 SOCKS 端口当作已授权 P2P 可靠通道。尤其现有共享 token、旧 Cipher、部分日志输出 auth/token（RrpClient L254-266，RrpServer L306-315）不满足新增公开节点 Relay 的安全要求。

### 5.2 推荐方案：RRP 新增受授权 RAW 逻辑通道

保留当前 SOCKS Proxy 模式；为通用节点服务增加受 NodeRegistry 授权的 RAW carrier 模式（先设计清晰的内部接口，统一新 wire，不维护旧私有协议兼容分支）。RRP 公网节点通过已有长连接/逻辑通道桥接 A 与 B；B 主动反向注册，A 发起授权连接。数据不要求每个 node/会话额外 bind 公网端口，目标为已登记且授权的服务实例而非任意内网 IP/port。复用 RRP 注册/重连/逻辑通道标识、队列/背压/关闭机制；新增最小受控 open/accept/close 生命周期、双端会话绑定、ByteBuf RAW carrier。普通 SOCKS 反代业务保留，内部承载一并迁移至新 Transport Core；新 wire 明确版本和能力，不接受旧协议回退，不允许降级到不认证的代理。

可靠路径使用 RRP 提供的透明有序双向字节承载，交给端到端 Transport Core 建立会话。不能在公网 Relay 终止上层 RPC 并再调用 B 的方法，不能叠加两个独立可靠传输状态机；Relay 不做应用序列化、重复压缩或 FEC。Transport Core 的可靠底座目前假设真实 Netty TCP Channel，需要最小 carrier adapter（可评估 Netty LocalChannel/虚拟 stream，避免不必要的 localhost TCP 拷贝/监听）。先做原型验证握手/FRAME_READY、writability、ByteBuf 引用、半关闭和 drain；如果现有 RRP multiplex 机制不支持语义，抽取其通用队列/会话桥接部分而非照搬 SOCKS 实现或另造完整反代。

拓扑行为：

| 情况 | 可靠路径 | UDP 快路径 |
|---|---|---|
| 公网/局域网双方可直接 TCP | 直接 TCP | 可验证的直接 UDP |
| 双 NAT、Relay 可达、打洞成功 | A↔Relay↔B（保留） | A↔B |
| 双 NAT、打洞失败或 UDP 被禁 | A↔Relay↔B | 无 |
| UDP 运行中失效 | 同一可靠 Relay 路径修复 | 暂停并按需恢复 |
| Relay 断开且无可靠直接路径 | 明确连接失败或受授权重连 | 不宣称 UDP 可独立保证可靠会话 |

第一版不要求从已建立的 Relay 可靠连接无缝迁移到直接 TCP，也不保证 Relay 断开时原 RPC 请求 exactly-once。先保证可靠载体生命周期和应用错误语义。公网 Relay 不得把普通 UDP 数据报无条件包装为可靠 TCP；本轮兜底针对可靠字节流，独立数据报仍按 DatagramTransport 语义。

### 5.3 公网安全与资源

- 强制双端认证和服务级 ACL、会话/方向密钥域与端到端认证加密（复用成熟 AEAD/TLS 或项目既有安全组件，独立 RPC/Relay/路径域）；握手 HMAC 不是数据保密性，不能因已有 rrpToken/peerId 就默认可信。凭据/明文 payload 不写日志，公开模式先清理上述敏感日志。无认证、错误/过期/重放、越权目标、禁用节点、旧会话必须拒绝。权限撤销关闭相应活动会话。
- 公网监听仅显式配置启用；禁止公开任意目标转发、未授权服务注册、未认证数据反序列化、大帧/无界 pending；节点身份与底层认证会话绑定，不以 IP 或自报 ID 当身份。共享 secret 不是每节点 ACL 的替代。候选探测只面向获授权的对端及验证地址；防止被滥用为扫描/反射服务。
- 现有 RRP 1 MiB/通道限制不能作为总量保证；新增全进程、每节点、每租户、每逻辑流的连接/在途字节/任务数预算，实际 backing capacity 与 write queue 同时考虑；溢出 fail-fast/明确关闭，禁止无限缓冲，按公平性调度并处理共享底层 TCP 的 HOL。首版建议保守资源配额，依据低配实测再定值；不得把 Relay 带宽当成免费。无 Relay 活动时不创建 per-stream buffer/task。
- 复用共享 EventLoop 和已有连接保活/重连，不为每节点建立线程/定时任务；限速/背压组件按需使用，不变更现有全局限速。半关闭、断链、重复 open、权限撤销、关闭时在途写、旧连接/新连接竞态必须处理，异常 ByteBuf/Channel 零泄漏。
- 端到端密钥及可靠路径状态不落 H2。普通历史统计可本地聚合，默认不持久化历史 IP/路径；公网节点重启所有在线状态重新认证建立。

## 5.4 公共 TCP EgressCodec 与 RRP 传输层收口（本轮新增前置任务）

### 5.4.1 源码核对

- [RrpClient.java L627-L636](https://github.com/RockyLOMO/rxlib/blob/9ae4620bce56ef2f8c35754eb6e1e9c00a3fdf72/rxlib/src/main/java/org/rx/net/socks/RrpClient.java#L627-L636)、[RrpServer.java L431-L445](https://github.com/RockyLOMO/rxlib/blob/9ae4620bce56ef2f8c35754eb6e1e9c00a3fdf72/rxlib/src/main/java/org/rx/net/socks/RrpServer.java#L431-L445) 强制配置 CIPHER_BOTH + HTTP_PSEUDO_BOTH。
- [Sockets.java L969-L989](https://github.com/RockyLOMO/rxlib/blob/9ae4620bce56ef2f8c35754eb6e1e9c00a3fdf72/rxlib/src/main/java/org/rx/net/Sockets.java#L969-L989)、[L1041-L1061](https://github.com/RockyLOMO/rxlib/blob/9ae4620bce56ef2f8c35754eb6e1e9c00a3fdf72/rxlib/src/main/java/org/rx/net/Sockets.java#L1041-L1061) 先安装 Cipher 再安装 HTTP handler；Netty 出站由 tail 到 head，因此旧 RRP 实际是 HTTP 整体进入 Cipher，再附长度字段，HTTP header 也被加密，并不是物理出口明文 HTTP header + 密文 body。
- [RssEgressCodec.java](https://github.com/RockyLOMO/rxlib/blob/9ae4620bce56ef2f8c35754eb6e1e9c00a3fdf72/rxlib/src/main/java/org/rx/net/RssEgressCodec.java) 已有 NONE/ZLIB_STORED/AUTO，当前 ZLIB_STORED 有 4B RSZ preface 与连续 level 0 zlib 流；安装点依赖 SocksConfig。新设计不要求旧 wire 兼容，因此去掉 RSZ preface，不以旧格式作为长期双栈。
- [HttpPseudoHeaderEncoder.java](https://github.com/RockyLOMO/rxlib/blob/9ae4620bce56ef2f8c35754eb6e1e9c00a3fdf72/rxlib/src/main/java/org/rx/net/HttpPseudoHeaderEncoder.java) 的 POST/response 声明 JSON，Body 是直接写入 ByteBuf；并不代表实际是 JSON。旧 decoder 对 header 累积无独立上限，Content-Length 解析依赖末尾位置。统一时修复解析、资源边界和敏感日志，逐步删除旧 handler/flags 的实际使用点，先核查全部调用方。

### 5.4.2 统一分层及编码定义

```text
RSS / RPC / RRP 业务数据及各自帧边界
        ↓
实际压缩（按业务策略，可关闭；zlib3/LZ4）
        ↓
认证加密（按安全策略，端到端密钥域独立）
        ↓
Transport Core 可靠流 / Adaptive UDP / FEC
        ├─ TCP 主通道与 repair → EgressCodec → 物理 TCP
        └─ UDP 快路径 → 原有 UDP 安全/MTU 处理 → 物理 UDP
```

EgressCodec 是最终物理 TCP 编码，而不是业务 codec、传输 Profile、加密或 FEC。收端逆序恢复；准确的 handler anchor 必须通过 embedded channel 与真实 socket 验证，握手、控制帧、repair 与普通 TCP 数据不可绕过或重复经过它。原有端到端 payload 安全与外层物理连接安全不是同一个职责。未配置或 NONE 不创建额外 handler、buffer、定时任务。UDP 暂不纳入此组件，不改变已有 datagram 安全、压缩、FEC、PMTU。

| 格式 | 物理 TCP 输出 | 默认语义 |
|---|---|---|
| NONE | 既有可靠承载字节 | 不额外包装 |
| ZLIB_STORED | 完整标准 zlib 流（level 0） | 连续流，不带 RSZ 私有 preface；不是实际压缩 |
| HTTP | HTTP 消息结构 + 二进制 Body | 方案 A，Body 直接承载认证后的二进制字节，声明 application/json（私有约定） |

AUTO 正式纳入公共 EgressCodec 服务端能力，只是服务端格式选择策略，不是第四种 wire 格式。客户端明确选择 NONE/ZLIB_STORED/HTTP；服务端可显式指定一种或启用 AUTO + allowedCodecs。采用统一、经过认证的内部握手消除出口编码与业务压缩的歧义，详见 §5.4.5。禁止解析失败后静默降级，不恢复旧 RSZ 或 HTTP 整体加密协议作为兼容分支。

HTTP 本轮用户明确选择方案 A：不把二进制 payload 转 JSON 或 Base64，Content-Type 仍声明 application/json。该声明与真实媒体类型不一致；真实 HTTP JSON API/中间代理可能拒绝或无法处理，不能把它宣传为标准 JSON 服务。HTTP 二进制的标准媒体类型本来是 application/octet-stream。这里仅作为受控两端私有 framing 约定记录，不能据此声称与任意 HTTP 代理兼容或可以隐藏流量特征。

HTTP 默认 Body 不再 zlib 包装；若需要此前讨论的可选 zlib0 内容编码，可在公共 HTTP codec 独立扩展（默认 none），必须明确内容编码、每个 body 的边界和解码预算，不能直接把连续的 ZLIB_STORED handler/旧 preface 塞进独立 body，也不能同时启用两个外层包装。它不减少密文体积。先交付默认 binary，非必要不增加额外运行成本。

### 5.4.3 配置与复用边界

拟议字段（并非当前可直接使用配置）：

```yaml
egress:
  tcpCodec: http  # none / zlib-stored / http
  http:
    bodyEncoding: none  # 默认；可选扩展 zlib-stored
```

实际 tcpCompressionLevel、加密、Transport Profile 与 egress 独立，RSS 用户/路由已有配置能力继续适配。HTTP 的 application/json 声明按本次选择固定，不新增 JSON/Base64 业务转换。RRP 不再自行强制设置 HTTP_PSEUDO_BOTH 或额外 ZLIB_STORED；RSS 与 RPC 可选择同一个 HTTP 出口 codec，RRP 也可选 NONE/ZLIB_STORED。具体默认策略按业务与配置明确，不能自动影响标准 SOCKS5/SS/DNS 对外协议。

- 公共组件建议命名 EgressCodec，放在 transport/codec 或等价现有公共层；实际复用同一安装入口、编码实现和资源预算，不以机械改包名代替收口。RssEgressCodec 的实际 zlib 底层实现可复用；旧 RSS 专属实现与旧 HTTP pseudo 使用点在新路径验收后删除，不长期维护两套。
- RRP 只保留反向注册、逻辑 channelId、服务授权、转发和关闭等业务；共享 Transport Core 连接、重连、心跳、加密、实际压缩、最终出口编码。旧 RRP 内部 wire 无需兼容；保留对外 SOCKS 反向代理功能。
- RRP RAW Relay 的物理连接默认可靠 TCP；A↔B 端到端 daily 仍只维护一套可靠/FEC 状态，不给 A→Relay→B 再叠加 Adaptive UDP、重复业务压缩或 EgressCodec。只有实际物理 TCP 出口安装各自 codec，逻辑 carrier 本身不再包装。
- HTTP framing 实现优先评估 Netty 现有 HTTP codec；约束 header/body 长度、累计在途字节、消息边界、请求响应语义、半关闭和异常回收。不以随机 Host/Path/User-Agent 或模仿第三方站点为目标，不声明具备通用 HTTP/JSON 互操作。协议解析失败明确关闭，不无界缓存、不打印密文、凭据或请求原始内容。

### 5.4.4 实施与验收

- [ ] 先提取公共 EgressCodec，定义清楚三种新 wire，去掉 ZLIB_STORED RSZ preface；两端按新配置升级。测试标准 zlib decoder 能直接解码输出，正常 flush/FIN 与异常关闭，不丢尾包。
- [ ] 实现 HTTP binary 默认模式，确认独立 decoder 能解析消息结构，Body 解码后精确还原承载字节；记录 application/json 声明与实际 binary 的私有约定及局限。可选 body zlib0 若实现，单独验证，不要求作为本轮默认或阻塞 RAW Relay。
- [ ] 验证真实 pipeline 出站/入站顺序、分片/粘包、空消息、坏 header、长度越界、慢连接、双向通信、背压、half-close、异常释放、握手及 repair。通过 byte-level wire 测试确认没有额外长度字段或私有前缀破坏所选外层格式。
- [ ] RSS / RPC / RRP 三者使用同一实现；RSS 四 profile、RPC daily、RRP SOCKS 与 RAW carrier 回归；UDP 不经过 EgressCodec；无旧私有协议兼容分支。现有标准对外协议及 SS 用户限速不改变。
- [ ] 比较 NONE/ZLIB_STORED/HTTP 的真实流量、CPU、分配、P95/P99、待发内存；不开启时无额外开销。先保证传输正确性与安全，不用包装格式作为加密、认证或可达性保证。
- [ ] 配套更新配置说明、部署文档与测试报告；拒绝新旧客户端不匹配的连接，按整套版本切换或回滚。

### 5.4.5 服务端 AUTO：正式实施项

#### 决策和边界

- AUTO 是服务端连接级协议选择策略，客户端禁止配置 AUTO。服务端可固定 NONE/ZLIB_STORED/HTTP 或开启 AUTO，并明确 allowedCodecs；支持在同一私有 transport listener 上接收多个新编码。NONE 仅表示不增加出口编码，不代表关闭认证或加密。
- 不恢复旧 4B RSZ preface，不增加旧 wire 兼容分支，不依靠 zlib header 中的压缩等级提示判定业务压缩与出口编码。
- AUTO 仅识别本项目受控私有承载，不是通用 HTTP 服务、任意代理或通用协议探测器。外部标准 SOCKS5、Shadowsocks、DNS listener 不自动安装该探测器。若现有 RSS 共享 listener 无法保证标准入口与私有承载无歧义，使用明确配置/独立监听；不得误判或改变标准协议。
- HTTP 仍按 §5.4.2 的私有二进制 Body 约定执行，默认不做额外 zlib0；AUTO 不改变其内容语义，不提供任何额外安全性或可达性承诺。

#### 内部握手及层次

当前 Transport Core 已有二进制 Hello/HelloAck、版本、nonce、HMAC 与 FRAME_READY，但现有 Sockets pipeline 可能在 Core handshake 前安装实际压缩/加密，RSS 私有链路也并非全部使用相同握手。不能仅通过移动 detector 就认定无歧义。

新 wire 应保证经选定出口 codec 解码后的初始协议握手有明确统一语义。实际业务压缩必须在完成握手及协商后启用；握手本身必须经过认证，绑定双方身份、协议版本、能力与选定出口模式，不发送明文凭据或业务数据。若需要独立安全层，应确保它与固定外层格式的协议边界一致，而非关闭安全机制以支持识别。业务层加密与压缩切换需要双方明确就绪屏障，处理 TCP 粘包和握手后立即发送的数据；不得让旧 handler 在半次切换中消费新格式数据。

外层识别只是 provisional codec selection，不等于授权或握手成功。任何不一致、格式错误、未知版本、认证错误或能力不允许均关闭连接；不能尝试另一个编码作为回退。若无法在某业务路径证明三种模式有明确边界，则该 listener 必须显式配置，先完成内部协议统一再启用 AUTO。不允许用压缩级别或启发式猜测消除两层 zlib 冲突。

#### 连接生命周期及资源控制

建议公共 EgressAutoDetector（类名可调整），仅在服务端启用 AUTO 的私有 listener 上安装。基于受控协议定义一次选择，保持未消费数据所有权；选择后将相应 codec 安装到正确物理 TCP 边界，再进入统一内部协议验证。服务端必须等到格式选择完成再发送依赖该格式的响应，不提前写出另一种编码。成功选择后移除 detector，同一连接整个生命周期固定格式，不逐帧探测、不在连接中重新协商出口包装。

初始识别、握手及解码均有独立字节/时间/并发预算；不无限累积 header 或压缩数据，不阻塞 EventLoop，失败/超时关闭并释放所有 ByteBuf、连接计数及任务。压缩解码设置实际输出预算与异常处理；对尚未认证的连接限制昂贵操作和资源分配。统一应用既有连接准入与握手超时，不新增每包定时任务。固定模式无 detector；NONE 无额外出口 codec；AUTO 选择完成后不产生持续探测开销。

拟议配置（不是当前已实现字段）：

```yaml
# client
egress:
  tcpCodec: http

# server（独立配置文件）
egress:
  tcpCodec: auto
  allowedCodecs:
    - none
    - zlib-stored
    - http
```

校验：客户端 AUTO、空/未知允许列表、禁止的出口模式及错误配置应明确拒绝；固定模式无需额外列举。公网节点服务使用强制认证及明确允许列表，不能借 AUTO 绕过 ACL。既有部署中需要标准协议的监听与内部 transport 监听分别验证。

#### 验收 checklist

- [ ] 同一启用 AUTO 的私有 listener 上，三种明确配置的客户端分别连接，双向正常、服务端响应使用该连接的编码；固定模式继续工作。RSS、RPC、RRP 复用同一 detector/codec 安装机制，不各写一套。
- [ ] 实际业务压缩 NONE/zlib3 与三种出口格式交叉测试；证明去掉 RSZ 后不存在业务压缩与出口 zlib 的误判，握手后才启用协商的业务 codec，验证合并握手/首业务帧、分片、握手超时、FRAME_READY 和 TCP repair。
- [ ] 未知模式、禁用模式、畸形输入、过长 header、解压预算超限、错误密钥、模式/能力不匹配、重放、未认证慢连接均明确拒绝且不降级；无业务数据在认证完成前被处理。
- [ ] 并发混合连接、关闭、失败连接、ByteBuf 引用计数、首包未消费数据、响应编码、timer/队列回收；验证连接选定后 detector 已移除，不逐包识别。
- [ ] 确认 ZLIB_STORED 新 wire 不携带 RSZ，标准 zlib 解码器可读；旧私有协议不要求兼容。标准 SOCKS5/SS/DNS 入口、UDP 快路径和现有业务限速保持语义。
- [ ] 双 JDK 完整回归，记录 AUTO 与固定模式的建连延迟、失败资源峰值、CPU、持续吞吐和内存；量化连接选择开销，不能仅宣称零开销。涉及实际线上协议切换时两端同版本升级。

## 6. 实施顺序与验收

### P0：先决修复及设计验证

- [ ] 完成 §3.2 NodeRegistry 配额/会话授权/H2 flush 一致性；Nameserver 接入同一 registry、来源过期和认证边界；最新代码完整双 JDK 回归。公网能力未达安全条件前只允许隔离测试。
- [ ] 先修复上一轮另外的正确性风险：DNS RR TTL 衰减、按字节缓存预算/Question 验证/负缓存；Geo 缺失 BLOCK selector 与热路径编译风险。独立提交，不混入 Relay 传输实现。SS 已完成限速保留，旧全局问题继续 deferred。
- [ ] 公共 EgressCodec 与 RRP RAW carrier 小型原型：无需兼容旧 RRP 私有 wire，验证双端可靠 stream 对接 Transport Core、端到端认证、帧边界、背压/半关闭/重连。确认复用范围后再扩展正式 API。

### P1：公共 EgressCodec、AUTO 与 RRP 传输统一

- [ ] 优先完成 §5.4 公共 TCP EgressCodec、服务端 AUTO、统一认证握手及 RRP 接入 Transport Core、RSS/RPC 共用 HTTP 出口；明确新 wire，不维护旧私有协议兼容层。UDP 出口保持不变。
- [ ] RRP RAW Relay 以该公共承载为基础；先验证纯 TCP 与应用层端到端语义，再接入 NAT 快路径。

### P1：启动、目录与可靠 Relay

- [ ] RssClient Launcher/RssRuntime 持有共享 NodeRegistry；配置、按需启停、H2、Nameserver 和 RRP 生命周期/热更回滚；普通 RSS 未启用时无新资源。
- [ ] RRP 增加受授权 RAW 逻辑通道，先验证 A/B 均无入站公网 TCP 时可双向通信；无 UDP 的真实 TCP 兜底必须先完成，不依赖打洞成功。应用 RPC 通过 daily Core；不新增旧 UdpClient。
- [ ] 验证跨节点授权、端到端数据安全、限额、关闭、反向连接故障；完成 RRP SOCKS 业务功能回归与独立 resource budget（不测试旧私有 wire 兼容）。未经安全验收禁止公开部署。

### P1：UDP 路径建立与集成

- [ ] 完成 Rendezvous 受控协调和真实 UDP socket 端点观察，NodeRegistry 候选生命周期及授权。
- [ ] 实现 Core 已验证 UDP path adapter，LAN/直接 UDP、打洞成功启用 daily UDP、失败保持 TCP Relay；与既有 FEC/PMTU/ACK/repair 集成，仅一个可靠状态机；恢复退避和路径变化。
- [ ] 不恢复旧 UdpHolePunch 协议；不引入新完整传输栈。继续支持普通公网 RSS/原生 DatagramTransport。

### P1：实测矩阵（必须实际执行后才标记完成）

| 测试 | 必须验证 |
|---|---|
| 双 NAT 拓扑 | A/B 都无公网入站，公网节点可达；UDP 成功及 UDP 完全阻断时可靠传输 |
| 局域网/IPv4/IPv6 | 同 LAN、不同 NAT、可直接 TCP、实际 UDP 映射变化、网络切换；不保证所有 NAT 可打通 |
| Relay 故障 | B 断线、A 断线、公网节点重启、半关闭、慢消费者、权限撤销、超额连接和积压 |
| 认证安全 | 未授权/过期/重放/伪造身份、候选替换、越权目标、坏帧、公开模式无明文凭据/业务 |
| 传输一致性 | 同一逻辑流 UDP↔TCP 切换、在途修复、乱序/丢包、无重复交付、原始字节完整；RPC 非幂等失败语义 |
| 资源 | 0/100/1024 在线节点、慢 H2/满盘、服务发现分页、并发配额；Relay 并发、待写/堆外/CPU/实际 backing 内存 |
| 既有功能 | Nameserver 多节点、RPC、RRP SOCKS 反代业务、RSS 4 profile/上游、SS 用户限速、DNS/Geo |
| 构建 | Java 8 与 JDK21，全量核心及新增单元/集成，-DskipTests=false、PARANOID leak 检查 |

真实网络试验记录 direct/punch/relay 选择、建立延迟、交付率、失败原因、实际双向公网 IP bytes、额外 CPU、P95/P99、RSS/整机/cgroup peak、socket/线程/定时器。区分公网节点、A、B 的资源；不能将进程 VmHWM 当整机768 MiB验收。中继模式公网节点需承担双方向转发流量与带宽成本，测量而非宣称零额外开销。RSS Server 本身无新增模块资源；RSS Client 节点服务另做低配/长时容量测试（建议先 1 vCPU/768 MiB 等效环境，非 RSS Client 强制产品规格）。

## 7. 发布与交付

每阶段独立提交；新私有 wire 客户端和服务端配套升级，回滚采用整套旧版本和对应配置，不在新实现中保留旧协议分支；先 master Java8 完成验证，再按 AGENTS.md merge master 到 rss-21，JDK21 回归及部署包验证，不直接在 rss-21 开发。同步更新 roadmap-plan、net-architecture、reference/net/punch、nameserver、transport、flow-control（如相关）和 test-benchmarks；完成重大架构演进后按仓库规范记录 .memory。不得把单元测试通过当成真实双 NAT 或公网安全已验收。

最终目标：RSS Client Launcher 可运行统一 NodeRegistry + Nameserver + Rendezvous + RRP Relay 服务；内网 A/B 在直接 UDP 可用时共享 daily 快路径，不可用时拥有真正可用的可靠 TCP 中继。RSS/RPC/RRP 共用一套 Core、实际压缩、认证加密及 TCP EgressCodec，新能力只使用公共 Core 与最小路径/承载适配，不复活旧 UdpClient/UdpHolePunch 栈，不为普通 RSS 增加未启用功能的成本。

本次仅提交实施计划，未改源码、未部署、未运行测试。RRP RAW 适配的具体 API、认证密钥管理与资源阈值需在 P0 原型及测试中确定。
