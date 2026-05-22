# Udp2raw 综合演进与重构计划

本文档整合了 `Udp2rawAdaptiveMtu-plan.md` 与 `Udp2rawDedicatedEntryReusePort-plan.md` 两个规划文档，涵盖自适应 MTU 双向探测机制以及自定义隧道（Fixed Entry + NAT-A）的核心设计与落地总结，作为 udp2raw 传输层底座全面演进的统一记录。

---

## 1. 整体背景与目标

为解决复杂网络下 UDP 隧道面临的单端口限速、路径 MTU 黑洞以及安全性能等问题，`udp2raw` 在最新迭代中进行了两大核心重构：
1. **自定义隧道与固定入口**：完全摒弃基于 SOCKS5 `UDP_ASSOCIATE` 的历史负担，引入 `SO_REUSEPORT` 固化接收入口，并通过为每个 Client Source 创建独立 NAT Channel 来保证 NAT-A（完全圆锥型 NAT）特征。
2. **自适应 MTU 双向探测**：在物理路径上实施毫秒级、零阻塞的 MTU 动态伸缩与收敛，避免路径黑洞并提升传输效能。

本计划属于 **高性能模式（Netty 底层网络编程）** 范围，聚焦于零分配、低延迟、高吞吐以及高安全的协议层改造。

---

## 2. 架构演进一：自定义隧道与固定入口 (Dedicated Entry & NAT-A)

### 2.1 彻底剥离 SOCKS5 UDP 历史兼容
- 新增 `Udp2rawUpstream` 取代原有的 `SocksUdpUpstream`。
- 不再为 udp2raw 数据面创建标准 SOCKS5 `UDP_ASSOCIATE` 的 TCP 控制连接。
- 不再启用、不兼容 UDP port hopping，避免多端状态爆炸。

### 2.2 控制面与数据面解耦
- **控制面鉴权**：复用现有的 `SocksRpcContract` 控制流。A 侧通过 RPC 下发 `openUdp2rawTunnel` 请求（携带 client 标识、压缩/冗余配置、流量统计标记等），获取 `tunnelId` 和会话密钥。
- **数据面鉴权**：废弃随包携带用户名密码的笨重模式。数据包仅使用自定义轻量 Frame Header（携带 `tunnelId`, `connId`, `packetSeq`）以及可选的 `authTag` 进行 MAC 校验。推荐使用 `FIRST_PACKET_MAC` 模式在首包完成强校验。

### 2.3 Per-Client NAT Channel 与独立会话
- **服务端入口**：RSS Server 侧基于 `Udp2rawServerEntryManager` 启动固定的 UDP 收包入口，支持 Linux epoll `SO_REUSEPORT`。
- **NAT-A 保证**：针对每个逻辑连接 `(tunnelId, connId)` 创建独立的出站 `natChannel` 并 `bind(0)`。相同目标（dest）下不同客户端来源的包，经过 NAT 转换后在目标端表现为不同的源端口，彻底避免交叉污染。

### 2.4 内置压缩与多倍发包 (Redundant)
- 协议层直接内置传输级 LZ4 压缩与多倍发包控制。
- 去重策略：依据 `tunnelId + connId + packetSeq + direction` 在解压之前、写入目标通道之前进行严格去重。

---

## 3. 架构演进二：自适应 MTU 双向探测 (Adaptive MTU)

### 3.1 核心状态机与探测机制
- **`Udp2rawMtuState` 状态机**：管理每条隧道的物理传输单元上限，设定 `STEP_UP` (20字节) 与 `STEP_DOWN` (80字节) 进行安全上探与极速回退。
- **零分配公共组件**：通过 `Udp2rawMtuProbeSupport` 提供探测帧 (`MTU_PROBE`) 与确认帧 (`MTU_ACK`) 的零碎分配构造和签名加解密封装。
- **双向独立探测**：Request 和 Response 两个方向各自维护独立的探测计划，严格使用专用控制帧探测物理丢包瓶颈。

### 3.2 极速自愈与安全隔离
- **极速状态撤销 (cancelPendingProbe)**：在发包前的本地写预检 (Local write check) 中，若发生网卡缓存满或丢包，直接抛弃等待而立即触发取消机制，无需等待 2 秒的确认超时。
- **延迟/过期 ACK 隔离**：由于序列号的严格约束，因为重试或网络延迟到达的历史 ACK 将被彻底无视。
- **物理下限保护 (Floor)**：强制锁定一个物理探测包底线长度（约 50 字节）。即使用户错误地配置了超小 MTU（如 40），状态机也能安全卡位，保证控制帧不发生缓冲区溢出。

### 3.3 防止劫持与控制面熔断 (Security Fuse)
- **物理 Peer 绑定 (pendingMtuProbePeer)**：Response 探测时严格锁定期望回包的物理通道，来自非预定 Peer 的 `MTU_ACK` 将被抛弃，记为 `ack-peer-mismatch`。
- **DDOS 与坏签名熔断**：如果控制帧签名校验失败，该对端的失效计数器将累加，达到阈值后直接触发黑名单机制，短期内物理阻断其所有业务。

---

## 4. 实施与验证状态

本综合演进计划已全部落地可编译，各项功能与安全点通过严格验证。

### 4.1 核心成果
- 完全移除了 A->B 间的 SOCKS5 冗余绑定，Fixed Entry 搭配 `SO_REUSEPORT` 并行架构运行正常。
- 独立的 Per-client `natChannel` 完成了 NAT-A 映射隔离。
- 自适应冗余、请求/响应双向压缩与去重全流程闭环打通。
- 基于数字签名熔断、Peer Rate Limit、Session Idle 的安全体系均已就绪。
- Adaptive MTU 双向探测、防污染、极速撤销及极限配置下探全部跑通。

### 4.2 质量保障
整个 `udp2raw` 新内核已通过 139 项全链路单元测试及集成测试，覆盖了以下关键 E2E 场景：
- 固定入口压缩解压/冗余发送去重验证。
- 伪造控制帧隔离与 `MTU_ACK` 熔断拦截。
- Udp2raw 专属指标（`tunnel.active`, `redundant.multiplier`, `compress.count`, `redundant.delayed.drop` 等）埋点验证。

后续工作将进入压测与长周期灰度观察，重点监控真实公网环境下的丢包率估算、冗余反馈机制与堆外内存健康状态。
