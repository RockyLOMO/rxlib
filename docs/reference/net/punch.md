# 双 NAT 打洞、直连优先选路与公网按需 TCP 中继

> **状态更新（2026-11-28）**：旧版无鉴权 `org.rx.net.transport.UdpClient`、`UdpHolePunch*` 与 `Hybrid*` 已彻底退役删除。新版基于 `NodeRegistry` + `Transport Core v2` + 统一 `EgressCodec` + `RrpServer` RAW 中继 + `org.rx.net.rendezvous` 的**直连优先双 NAT 互通与按需公网 TCP 中继体系**已全部实现并完成双 JDK `PARANOID` 回归与公网多机实测（见 [test-benchmarks.md](../../test-benchmarks.md) §6）。

## 架构分层与核心组件

1. **统一地址发现与节点注册权威（`AddressDiscovery` + `NodeRegistry`）**
   - `org.rx.net.AddressDiscovery` & `EndpointCandidate`：汇聚 `PublicIpObserver` 公网 IP 投票结果与本机全部非回环网卡候选（IPv4/IPv6、按接口去重与优先级排序、TTL 缓存，不阻塞 EventLoop）。
   - `org.rx.net.registry.NodeRegistry`：H2 轻量冷存 + 有界热索引节点注册表（默认关闭，`openIfEnabled` 零资源启动），提供分段锁、单调 `authGeneration`、O(1) 租户配额统计、认证并发/速率限流、可配 PBKDF2 迭代次数、单 owner 批量落盘、**子系统级共享在线会话**（`authenticateAndAttachSession` / `detachSubsystemSession`，使同机部署的 `RendezvousService` 与 `RrpServer` 共用单个 `OnlineNode` 不互相踢下线）以及多监听器即时吊销（禁用/改密立刻切断所有子系统会话与活跃中继流）。

2. **受控协调与防放大 UDP 观测（`org.rx.net.rendezvous`）**
   - **`RendezvousService` / `RendezvousContract` / `RendezvousConfig`**：基于 `Transport Core v2`（强制 `authSecret` + 每会话 AEAD 加密 + 统一 `EgressCodec` Server `AUTO`）提供节点认证（`authenticateNode` 标记敏感脱敏）、同租户目标发现（`discoverPeer`）、候选地址更新与并发打洞会话协调（`requestPunch` → `onPunchInvite` 推送给目标节点 B）。
   - **`UdpEndpointObserver`**：绑定独立 UDP 观测端口，请求必须携带由 `RendezvousService` 颁发的短时效 `obsTokenId + obsKey` 的 HMAC-SHA256 签名与防重放时间戳；对无效/伪造/超频请求**静默丢弃且 0 回包**，响应包体严格小于请求包体，彻底杜绝公网 UDP 反射放大风险。

3. **RFC 8445 风格 UDP 打洞与自适应快轨升级（`PeerUdpPuncher`）**
   - `PeerUdpPuncher` 复用 `AdaptiveUdpPort.bindShared(...)` 共享 UDP 端口（无需独立重造可靠 UDP 栈），使用协调层下发的单会话 `punchSessionId + punchKey` 发送短小签名 `PUNCH_PROBE` / `PUNCH_ACK`，内置 **触发检查（triggered reciprocal probe）** 与 **2 秒被动应答滞留（passive ACK linger）** 消除两端时钟/JIT 抖动导致的收尾竞争。
   - 双向连通验证通过后，直接调用 `AdaptiveTcpHandler.attachVerifiedUdpPath(...)` 将已验证的直连/打洞 UDP 路径挂载到端到端 `AdaptiveTcpStream` 上无缝升轨（严格校验 `serverRole` 与精确源 IP+端口），UDP 遇限速/黑洞时由 TCP 主通道或按需中继通道无损修复。

4. **直连优先、最小化公网中继流量的多候选选路（`PathSelector`）**
   - **设计目标**：优先选择可直连的局域网/公网 UDP 或 TCP 路径，**仅在直连 TCP 全部失败或超过预算时才按需打开公网 RRP RAW Relay 逻辑流**；直连 TCP 成功时公网 Relay 流打开数与中继字节数严格为 `0`。
   - **选路顺序**：
     1. 并发按优先级探测目标候选（同网段/局域网私网 TCP/UDP → 公网直连 TCP/UDP → 协调层同时 UDP 打洞），支持通过 `TcpClientConfig.localAddress` 绑定本机多网卡发起真实跨网卡直连测试。
     2. 若直连 TCP 优先成功，立即取消中继兜底并建立直连 E2E AEAD `TransportSession`；同步完成的直连/打洞 UDP 路径直接升轨至该会话。
     3. 仅当直连 TCP 全部失败或超时，才按需通过 `RrpClient.openRawCarrierAsync(...)` 开启公网 RRP RAW Relay 逻辑流建立 E2E AEAD 会话，并仍可并行尝试 UDP 打洞升轨以将后续大流量卸载出公网中继（若两端位于对称 NAT 或禁 UDP 网络，则平稳停留在纯 TCP 中继）。

5. **公网已认证 RAW 逻辑流中继（`RrpServer` / `RrpClient`）**
   - RRP 控制与数据通道已全面迁移至纯 TCP `Transport Core v2` + 统一 `EgressCodec`（彻底删除旧明文/弱混淆与敏感令牌日志）。
   - 开启 `enableRawRelay` 并注入 `NodeRegistry` 后，支持租户隔离的 `ACTION_RAW_*` 多路复用 RAW 逻辑流中继，提供 **4 级待发字节预算（全局/租户/节点/单流）**、轮询调度防队头阻塞、单流反压 `PAUSE`/`RESUME`、有序半关闭、节点吊销即时全流切断，以及通过 `LocalAddress`/`LocalChannel` 零额外 Socket 桥接上层 `TransportServer`/`TransportClient` 与 `Remoting` RPC（端到端业务载荷在节点 A/B 间始终独立 AES-128-GCM 加密，中继节点仅转发密文帧）。

6. **`RssRuntime` 按需集成（默认关闭）**
   - 通过 `RssClientConf.nodeRegistry` 与 `nodeService`（`rendezvousEnabled` / `relayEnabled` / `authToken` / `egressConfig`）统一托管单个共享 `NodeRegistry`、`RendezvousService` 与 `RrpServer`；默认关闭时零 H2 文件、零 Socket、零定时器；启动与热重载前执行严格端口冲突与无鉴权公网暴露校验，失败自动回滚不破坏既有运行实例。
