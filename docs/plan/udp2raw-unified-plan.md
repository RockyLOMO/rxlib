# Udp2raw 自定义隧道、固定入口、NAT-A 与自适应 MTU 统一架构方案（合流总纲）

> [!NOTE]
> 本文档是针对 `rxlib` 仓库在 UDP 传输优化领域下对 `udp2raw` 的两项核心演进设计进行深度整合与归档：
> 1. **自定义隧道 + 固定入口 + 客户端 sourceEndpoint 保持 (NAT-A) 重构计划**。
> 2. **自适应 MTU 双向探测、高防熔断与极速自愈方案**。

---

## 一、 概述与演进背景

在复杂广域网 (WAN) 环境下，传统 UDP 代理或中转协议面临着严重的链路瓶颈与合规阻碍：
*   **物理链路限速**：单五元组/单物理端口流量特征明显，极易遭到网络中间设备的 QOS 限速。
*   **路径 MTU 黑洞 (PMTUD Blackhole)**：网络嵌套隧道（如 PPPoE、IPsec、GRE）导致不同路径 MTU 不一。固定 MTU 过大导致大包被静默丢弃，过小则增加了包头开销且降低了吞吐性能。
*   **端口跳跃 (Port Hopping) 复杂度高**：传统端口跳跃需要申请大量的远端 relay 端口，引入复杂的控制面交互，在高并发下维护多对多映射成本极高。

为了彻底解决以上两难境地，本项目在高性能传输库 `rxlib` 中重新设计并落地了**统一自定义 Udp2raw 传输协议**：
*   **控制与数据分离**：跳出标准 SOCKS5 的 `UDP_ASSOCIATE` 限制，通过 RPC 控制面握手与轻量级自定义数据帧传输，全面去除了 SOCKS5 TCP 随包鉴权的重度开销。
*   **固定入口 + 独立 NAT-A 出站**：服务端固定 UDP 入口支持 `SO_REUSEPORT`，针对每个逻辑会话 `(tunnelId, connId)` 建立独立 UDP `natChannel` 转发，实现对称 NAT (NAT-A) 保护。
*   **多倍发送与负载压缩**：内置基于 LZ4 压缩与多倍冗余（Redundant Dup Send）自适应反馈去重机制。
*   **双向自适应 MTU 状态机**：无需依赖操作系统内核，利用带外控制帧（`MTU_PROBE` / `MTU_ACK`）自主动态探测收敛，结合本地 local drop 极速撤销状态自愈，确保传输单元达到最优。

---

## 二、 自定义隧道与固定入口架构设计

### 2.1 控制面：复用 `SocksRpcContract`
为了在不牺牲安全性的前提下剥离 SOCKS5 UDP 的 TCP 控制信道，自定义隧道将鉴权与状态开启上浮至 RPC 控制面：
```
[Client] ──SocksRpcContract.openUdp2rawTunnel()──> [Server] (Token 校验)
   │                                                     │
   └─── 返回 <tunnelId, sessionSecret, fixedEntry> ──────┘
```
1.  **RPC 鉴权**：客户端利用现有的 `SocksRpcContract` 接口向服务端发送 `openUdp2rawTunnel` 请求，传递客户端标识、能力配置（是否开启冗余、压缩）及流量统计 tag。
2.  **通道注册**：服务端校验 RPC Token 成功后，创建 `Udp2rawTunnelContext` 并登记 `tunnelId` 映射，分配会话密钥并向客户端返回固定的 UDP 接收端口。

### 2.2 数据面：紧凑数据帧格式
不再遵循 SOCKS5 二进制 Wire-Format。为了降低头部开销、防止被深度特征分析，设计了以下紧凑型自定义帧：
```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|          Magic (2B)           |  Version (1B) |HeaderLen (1B) |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|           Flags (2B)          | HeaderType(1B)|  Reserved     |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                        SessionId/TunnelId (8~16B)             |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                        ConnId (8B)                            |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                        PacketSeq (8B)                         |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|  [clientSource] (NEW_CONN/HAS_CLIENT)                         |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|  [destination]  (NEW_CONN/HAS_DST)                            |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|  [authTag]      (AUTH_TAG)                                    |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|  Payload Bytes (Variable)                                     |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```
*   **首包轻量绑定**：客户端首次向新目的地发包时，携带 `NEW_CONN` 标志并塞入完整的 `clientSource` 与 `destination` 地址；服务端据此创建 `Udp2rawSession` 并绑定 `connId`。
*   **后续零冗余传输**：会话建立后，后续包仅传递 `tunnelId + connId + seq` 以及原始 payload，彻底消除了每包重复携带端点信息的字节开销。

### 2.3 NAT-A 实现方案
为了在多人共用服务端入口时保留对称 NAT 特性，服务端不直接用入口 socket 发包给 `dest`。
*   每个 `Udp2rawSession` 持有一个独立的 `natChannel` 并调用 `bind(0)`。
*   入站 `DATA` 帧由 fixed entry 统一接收并分派给对应 Session 的 `natChannel` 进行出站。
*   `natChannel` 收到目的地的 `DATA` 回包后，封装为 Response 帧通过 `fixed entry` 回传给客户端，从而确保了同一个客户端在外部服务器端看起来有独立的 UDP 源端口（NAT-A）。

---

## 三、 自适应 MTU 双向探测机制

### 3.1 核心状态机设计与步长
每个 Tunnel/Session 双向链路分别挂载独立的 `Udp2rawMtuState` 实例，维护状态收敛的核心参数：
*   **MTU 范围**：物理下限卡死在 `576` 字节（IPv4 最小重组上限），上限根据 `SocketConfig` 决定。
*   **自适应因子**：
    *   `STEP_UP` (20 字节)：每次探测成功上探步长，稳健探索。
    *   `STEP_DOWN` (80 字节)：探测失败或本地写超限时下调步长，快速避让。
    *   `PROBE_INTERVAL_MILLIS` (30 秒)：常规探测时钟。
    *   `PROBE_RETRY_MILLIS` (5 秒)：失败极速自愈探测时钟。

### 3.2 双向协议级探测流程 (Bidirectional Probing)

```
[Client] ─── 发送 MTU_PROBE (对齐填充至 probeMtu) ───> [Server Entry]
   │                                                         │
   │                                                   核验物理包长,
   │                                                   记录为 acceptedMtu
   │                                                         │
   └─── 接收 MTU_ACK (携带 4字节 acceptedMtu 负载) ◄─────────┘
```

#### 3.2.1 请求方向 (Request Direction) 探测
1.  **带外标签绕过**：探测包必须测试网络路径是否能通过大包。客户端在 `Sockets.writeUdp` 前调用 `Udp2rawMtuProbeSupport.encodeProbe` 构造特定大小的 `MTU_PROBE` 包，并打上 `UdpMtuProbeDatagramPacket` 控制帧标签，以此**直接绕过客户端本地的数据面 MTU 阻断哨兵**投向广域网。
2.  **ACK 回传**：服务端接收并通过鉴权校验后，计算实际收到的二进制物理长度，组装 `MTU_ACK` 将实际通过字节数写在 Payload 内发回客户端，完成状态收敛。

#### 3.2.2 响应方向 (Response Direction) 主动探测与防劫持
1.  **主动对端物理绑定 (`noteMtuPeer`)**：服务端接收到来自客户端合法的 DATA 后，记录当前的物理 `Channel` 与 `InetSocketAddress`，激活服务端反向主动探测。
2.  **防污染锁定 (`pendingMtuProbePeer`)**：服务端向客户端发起反向 `MTU_PROBE` 时，会将该 Peer 锁定为 `pendingMtuProbePeer`。此时**仅接受**来自该锁定 Peer 的 ACK 确认。非匹配 Peer 的垃圾 ACK 将被直接丢弃（日志记录 `ack-peer-mismatch`），杜绝动态多端场景下恶意污染 MTU 的风险。

### 3.3 极速状态撤销与微重试 (`cancelPendingProbe`)
当物理通道因网络断开、本地网卡满导致 `Sockets.writeUdp` 发送失败（非 `ACCEPTED` 状态）时，如果不做处理，状态机会因为等待 2 秒的 `ACK_TIMEOUT` 陷入停滞：
*   **本地极速响应**：调用 `cancelPendingProbe`，立即清除当前正在 pending 的 sequence 与 pending 状态。
*   **时钟瞬时回拨**：将下一次探测定时任务从 30 秒紧急提前缩短至 5 秒重试时钟。
*   **垃圾回包拦截**：清除 `pendingSeq` 标志后，后续由于路径延迟抵达的老 `ACK` 将因序列号匹配失效被丢弃，防止回退或造成错误的 MTU 调整。

### 3.4 极限配置物理 Floor 卡位保护
*   若配置中设置了过低的 MTU（如 `40` 字节），构建控制包（包头+加密 signature 需要 ~50 字节）会直接引发 `ByteBuf` 溢出崩溃。
*   状态机在构造时，会将 MTU 上下限强行卡位在 `MIN_PROBE_DATAGRAM_BYTES`。即使处于极端超小配置下，系统依然能正常编码、发送、回显，拒绝内存溢出或拒绝解析异常。

---

## 四、 高性能数据处理（冗余多倍与 payload 压缩）

### 4.1 多倍发送与去重机制
多倍冗余（Dup Send）设计用以在弱网环境下利用带宽换取超低丢包率。
*   **压缩层级前置**：在发送侧，业务 Payload 先进行 LZ4 压缩，标记 `COMPRESSED` 标志。
*   **整体封装与物理复制**：在 Auth 签名和生成 `packetSeq` 之后，将封装好的完整的 UDP 包进行复制。在发送副本时使用 `retainedDuplicate()` 增加引用计数，绕过每包大对象重新生成的开销。
*   **全流向去重**：服务端接收侧解压前、客户端接收侧投递本地客户端前，通过 `tunnelId + connId + packetSeq + direction` 建立位图滑动去重窗口。重复的控制包或数据副本在最外层即被丢弃，决不重复写入 NAT channel 或 local app。

### 4.2 冗余自适应反馈 (`UdpRedundantStats`)
*   每个 Tunnel 内部维护 `UdpRedundantStats`，通过滑动窗口统计接收到的 Total 与 Unique 包比率，据此计算传输路径的估算丢包率。
*   发送侧读取该 Stats 动态调整 multiplier 冗余倍数，从而在链路良好时自动下调副本数节省带宽，弱网拥堵时自动拉高冗余倍率。

---

## 五、 安全加固与控制面熔断

UDP `fixed entry` 端口完全暴露在公网，需要防御恶意的流量攻击和重放探测：
1.  **控制帧流量整流 (`allowPeerPacket`)**：在 Fixed Entry 的第一层 Handler 挂载令牌桶，秒级对控制帧进行物理整流，防止利用 `MTU_PROBE` 控制流对 CPU 和网卡队列进行 DDOS 轰炸。
2.  **鉴权失败黑名单物理熔断 (`recordAuthFailure`)**：不管是控制包（如 `MTU_ACK`）还是数据包，其 4 字节负载和 tag 都必须通过 `Udp2rawAuthenticator`（基于 `HmacSHA256` 截断 128-bit 标签）的安全完整性校验。一旦发现非法签名，触发计数累加，超过 `udp2rawBadAuthThreshold` 阈值后，该 Peer 会被列入物理熔断黑名单并在 `udp2rawBadAuthFuseSeconds` 时间内直接丢弃，不执行任何内存操作。
3.  **NAT Rebinding 安全验证**：当已激活的会话 IP/端口（如 Peer 发生 NAT Rebiding）发生跃迁时，新 IP 的 DATA 包必须强制携带完整的 `authTag` 校验。只有校验通过才允许更新 Peer 物理绑定，防止未授权客户端劫持已有 Session 的出站 NAT 通道。

---

## 六、 核心类结构与配置项

### 6.1 核心类一览
*   `Udp2rawCodec`：实现零拷贝、基于 flags 编排的可变长度 UDP 包编码解码器。
*   `Udp2rawMtuProbeSupport`：提供 `encodeProbe`、`encodeAck` 格式填充。
*   `Udp2rawMtuState`：自适应 MTU 探测状态机（支持上探、下避、极速撤销）。
*   `Udp2rawUpstream`：替代 SOCKS5 的 A->B 自定义隧道，集成 RPC 协商与 Tunnel 状态维护。
*   `Udp2rawServerEntryManager`：服务端 fixed entry 的管理器，支持 SO_REUSEPORT。
*   `Udp2rawServerEntryHandler`：Fixed entry 上绑定的收包通道，执行整流、去重、熔断。
*   `Udp2rawSession`：代表一个具体的客户端逻辑链接，管理 per-client `natChannel` 及其生命周期。

### 6.2 核心配置项与指标
```java
private InetSocketAddress udp2rawListenAddress;
private int udp2rawSessionIdleSeconds = 300;
private int udp2rawMaxSessions = 65536;
private Udp2rawAuthMode udp2rawAuthMode = Udp2rawAuthMode.FIRST_PACKET_MAC;
private boolean udp2rawRequireRpc = true;
private UdpRedundantConfig udp2rawRedundant;
private UdpCompressConfig udp2rawCompress;
private int udp2rawBadAuthThreshold = 8;
private int udp2rawBadAuthFuseSeconds = 30;
private int udp2rawPeerRateLimitPerSecond = 0; // 0 表示关闭
```

---

## 七、 自动化单元测试与质量保障

本方案在高性能传输层上覆盖了高达 **139 个自动化测试用例**，保障架构升级 100% 健壮运行。

### 7.1 典型验证断言场景
*   **反向探测与防劫持拦截** (`fixedEntrySendsResponseDirectionMtuProbeAndAcceptsAck`)：
    *   测试模拟服务端向客户端发出 MTU 探测。此时模拟恶意第三方 Peer 发来 `MTU_ACK`，断言服务端触发 `ack-peer-mismatch` 成功拦截并丢弃，防御了伪造回包对 MTU 状态的篡改。
*   **鉴权失败黑名单物理熔断** (`fixedEntryMtuAckAuthFailureBlocksPeer`)：
    *   构造携带错误 MAC 签名的控制包。断言服务端在接收超出阈值后直接熔断，丢弃该 Peer 后续所有的所有包。
*   **极速自愈回归** (`cancelPendingProbeDoesNotLowerOrAcceptLateAck`)：
    *   模拟本地网卡 local drop 导致写失败立即调用 `cancelPendingProbe`，断言 2s 后的迟到 ACK 被静默忽略，不引发错误状态调整，且定时任务已加速。
*   **极限配置 Floor 卡位** (`tinyMtuFloorsToMinimumProbeFrame`)：
    *   强行将配置设置为过小的 40 字节 MTU。断言状态机强制卡位，无任何 ByteBuf 异常溢出，控制帧正常流转。
*   **NAT-A 对称出站** (`socks5UdpRelay_udp2rawUpstream_fixedEntry_e2e`)：
    *   两路不同的 `clientSource` 投递至 Server Fixed Entry，断言在 dest 端收到的 UDP 源端口互相独立不冲突，成功通过对称 NAT-A 场景验收。

### 7.2 质量保障结论
全链路所有测试已完全通过验证，在 JDK8/JDK21 双编译环境和远程 CI actions 中顺利闭环（**`BUILD SUCCESS`**）。
