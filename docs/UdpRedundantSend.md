# UDP 多倍发包 (Packet Redundancy)

> **说明：** 本文由原用户说明与 [UdpRedundantSend_plan.md](./UdpRedundantSend_plan.md) 中的实现计划合并整理；该计划文件现仅作归档入口并指向本文。设计与行为以仓库当前代码为准。

## 背景与目标

游戏加速场景中，UDP 丢包会直接导致延迟上涨（无 TCP 重传机制）。**多倍发包**将每个 UDP 包冗余发送 N 次，接收端去重，只要 N 份中任意一份到达即可，是以带宽换延迟的简单策略。相比 FEC（需凑组与 XOR），特点是：

- **零组延迟**：立即发齐冗余副本，无需等组满
- **实现简单**：header + 去重，无复杂编解码
- **适用场景**：包小（例如不足 1KB 的操控包）、对延迟极敏感

功能覆盖 **SOCKS5 UDP relay** 与 **Udp2raw** 两条路径（见下文「覆盖路径」）。

## 设计决策与运维注意

**去重策略（已实现为方案 1）**  
在 payload 前增加带序列号的 8 字节 header，**双端**（代理入站/出站链路）需部署一致版本，由 Netty Handler 去重后再交给业务。若仅单端多倍发送且不剥重，对端会收到重复包，游戏等场景可能异常。proxy-to-proxy 链建议两端同步升级。

**带宽**  
`multiplier = 3` 即 UDP 方向带宽约 ×3。实现中将倍率限制在 **[1, 5]**（配置 setter 会钳位）。

**突发丢包（burst loss）**  
同一时刻发送 N 份可能同批次丢失。提供 **`udpRedundantIntervalMicros`**：冗余副本可按 `intervalMicros × 副本序号` 微秒级错峰发送（默认 0 为同时发）。建议需要时取约 200～1000μs。

**与 FEC**  
项目另有 `FecEncoder` / `FecDecoder`（如 `FecUdpClient`）。多倍发包与 FEC **建议互斥使用**，避免冗余叠加、带宽过大。

**自适应模式细节**  
启用 `udpRedundantAdaptive` 时，入口使用 `SocksProxyServer.addRedundantHandlers()` 创建 `UdpRedundantStats` + 成对的 Decoder/Encoder；`udpRedundantMultiplier` 作为 `UdpRedundantStats` 的初始倍率（可设为 1）。

---

## 快速使用

**方式一：SocksConfig 直接配置（推荐）**

```java
SocksConfig config = new SocksConfig(1080);
config.setUdpRedundantMultiplier(3);
config.setUdpRedundantIntervalMicros(500); // 可选

SocksProxyServer server = new SocksProxyServer(config);
```

**方式二：独立配置对象**

```java
// 创建独立UDP冗余配置
UdpRedundantConfig udpConfig = new UdpRedundantConfig();
udpConfig.setMultiplier(3);
udpConfig.setIntervalMicros(500);

// 方式2A：应用到SocksConfig（向后兼容）
SocksConfig config = new SocksConfig(1080);
config.setUdpRedundantConfig(udpConfig);
SocksProxyServer server = new SocksProxyServer(config);

// 方式2B：直接使用（推荐用于模块化设计）
UdpRedundantStats stats = new UdpRedundantStats(
    udpConfig.getMultiplier(), 
    udpConfig.getMinMultiplier(), 
    udpConfig.getMaxMultiplier(),
    udpConfig.getIntervalMicros(),
    udpConfig.getLossThresholdHigh(),
    udpConfig.getLossThresholdLow(),
    udpConfig.getStablePeriods()
);
UdpRedundantEncoder encoder = new UdpRedundantEncoder(stats, udpConfig.buildMultiplierResolver());
```

**静态模式**（固定倍率）：

```java
UdpRedundantConfig config = new UdpRedundantConfig();
config.setMultiplier(3);
config.setIntervalMicros(500);

SocksConfig socksConfig = new SocksConfig(1080);
socksConfig.setUdpRedundantConfig(config);
SocksProxyServer server = new SocksProxyServer(socksConfig);
```

**自适应模式**：

```java
UdpRedundantConfig config = new UdpRedundantConfig();
config.setMultiplier(2);           // 初始倍率
config.setAdaptive(true);
config.setMinMultiplier(1);
config.setMaxMultiplier(5);
config.setLossThresholdHigh(0.20);
config.setLossThresholdLow(0.05);
config.setStablePeriods(3);
config.setIntervalMicros(500);

SocksConfig socksConfig = new SocksConfig(1080);
socksConfig.setUdpRedundantConfig(config);
SocksProxyServer server = new SocksProxyServer(socksConfig);
```

**分目的地倍率模式**：

```java
UdpRedundantConfig config = new UdpRedundantConfig();
config.setMultiplier(2); // 全局默认倍率（未命中规则时使用）

// 游戏服务器：高冗余
UdpRedundantDestinationRule gameRule = new UdpRedundantDestinationRule();
gameRule.setHost("192.168.1.0/24");  // 游戏服务器网段
gameRule.setPort(0);                  // 任意端口
gameRule.setMultiplier(4);              // 4倍发送
config.getDestinationRules().add(gameRule);

// 视频服务器：低冗余
UdpRedundantDestinationRule videoRule = new UdpRedundantDestinationRule();
videoRule.setHost("203.0.113.100");   // 特定视频服务器
videoRule.setPort(8080);               // 视频端口
videoRule.setMultiplier(1);              // 关闭冗余（透传）
config.getDestinationRules().add(videoRule);

// 混合模式：自适应 + 分目的地规则
config.setAdaptive(true);
config.setMinMultiplier(1);
config.setMaxMultiplier(5);
config.setLossThresholdHigh(0.20);
config.setLossThresholdLow(0.05);
config.setStablePeriods(3);

SocksConfig socksConfig = new SocksConfig(1080);
socksConfig.setUdpRedundantConfig(config);
SocksProxyServer server = new SocksProxyServer(socksConfig);
```

**配置迁移**：

```java
// 从现有SocksConfig提取UDP冗余配置
SocksConfig oldConfig = new SocksConfig(1080);
oldConfig.setUdpRedundantMultiplier(3);
// ... 设置其他UDP冗余参数

UdpRedundantConfig udpConfig = UdpRedundantConfig.fromSocksConfig(oldConfig);

// 应用到新的SocksConfig
SocksConfig newConfig = new SocksConfig(1080);
newConfig.setUdpRedundantConfig(udpConfig);
```

默认 `udpRedundantMultiplier = 1` 且 `udpRedundantAdaptive = false` 时不启用冗余，对未配置场景无侵入。

---

## 配置参数

### SocksConfig 方式（向后兼容）
| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `udpRedundantMultiplier` | int | 1 | 静态倍率或自适应初始倍率，取值 [1, 5] |
| `udpRedundantIntervalMicros` | int | 0 | 冗余副本间隔（μs）；0 = 同时发送 |
| `udpRedundantAdaptive` | boolean | false | 是否启用自适应 |
| `udpRedundantMinMultiplier` | int | 1 | 自适应下限（可完全关冗余） |
| `udpRedundantMaxMultiplier` | int | 5 | 自适应上限 |
| `udpRedundantLossThresholdHigh` | double | 0.20 | 高于则倾向升倍率 |
| `udpRedundantLossThresholdLow` | double | 0.05 | 低于则倾向降倍率 |
| `udpRedundantStablePeriods` | int | 3 | 防抖：连续多少个 2 秒周期满足条件才调整 |
| `udpRedundantDestinationRules` | `List<UdpRedundantDestinationRule>` | 空 | 分目的地倍率，**列表顺序即优先级**（先匹配先生效） |
| `udpRedundantConfig` | `UdpRedundantConfig` | null | **推荐**：独立配置对象，设置后优先使用 |

### UdpRedundantConfig 方式（推荐）
| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `multiplier` | int | 1 | 静态倍率或自适应初始倍率，取值 [1, 5] |
| `intervalMicros` | int | 0 | 冗余副本间隔（μs）；0 = 同时发送 |
| `adaptive` | boolean | false | 是否启用自适应 |
| `minMultiplier` | int | 1 | 自适应下限（可完全关冗余） |
| `maxMultiplier` | int | 5 | 自适应上限 |
| `lossThresholdHigh` | double | 0.20 | 高于则倾向升倍率 |
| `lossThresholdLow` | double | 0.05 | 低于则倾向降倍率 |
| `stablePeriods` | int | 3 | 防抖：连续多少个 2 秒周期满足条件才调整 |
| `destinationRules` | `List<UdpRedundantDestinationRule>` | 空 | 分目的地倍率，**列表顺序即优先级**（先匹配先生效） |

**推荐使用 `UdpRedundantConfig`**：
- 更好的模块化设计，配置逻辑独立
- 支持配置迁移和复用
- 类型安全的参数验证
- 便于单元测试和配置管理

### 分目的地倍率

在 `SocksConfig` 上向 `udpRedundantDestinationRules` 添加若干 `UdpRedundantDestinationRule`：

- **`host`**：IPv4/IPv6 字面量或主机名（解析为单地址做精确匹配），或 **仅 IPv4 CIDR**（如 `10.0.0.0/24`；IPv6 CIDR 不支持）。
- **`port`**：`0` 表示任意目的端口；否则须与 `DatagramPacket.recipient()` 的端口一致。
- **`multiplier`**：`[1, 5]`；命中时可设为 `1` 以在该目的地上**强制关闭**冗余（覆盖全局倍率或自适应结果）。

未命中任何规则时，仍使用 `udpRedundantMultiplier` 或自适应 `UdpRedundantStats`。只要配置了至少一条规则，即会安装冗余 Handler（即使全局倍率为 1，也可仅靠规则对部分目的地启用多倍发送）。

高级用法可直接实现 `UdpRedundantMultiplierResolver` 并传入 `UdpRedundantEncoder` 的扩展构造（一般通过 `SocksConfig.buildUdpRedundantMultiplierResolver()` 即可）。

---

## Wire Protocol

每个 UDP 数据报在 **原始 payload 前** 附加 **8 字节**（网络字节序）：

```
+-------------------+-------------------+
|  MAGIC (4 bytes)  |  SEQ_ID (4 bytes) |
|  0x52444E54       |  递增序列号        |
+-------------------+-------------------+
|              原始 payload             |
+---------------------------------------+
```

- **MAGIC**：`0x52444E54`（ASCII `RDNT`），用于识别本协议
- **SEQ_ID**：每个 **channel** 内 `AtomicInteger` 递增；接收端按 **sender 地址** 分窗口去重，不同 sender 的序号互不干扰

MAGIC 不匹配或长度不足 8 字节时，整包按普通 UDP **原样透传**，便于与非冗余对端混布。

---

## 实现架构

### Handler 职责

| 组件 | 方向 | 职责 |
|------|------|------|
| `UdpRedundantDecoder` | Inbound | 预读 MAGIC；冗余包则读 SEQ_ID、滑动窗口去重、剥掉 8B 后交给下游 |
| `UdpRedundantEncoder` | Outbound | 前置 8B header，写 N 份；首份用调用方 `ChannelPromise`，其余用 `voidPromise` |
| `UdpRedundantStats`（可选） | — | 自适应：Decoder 计数，Encoder 定时 `adjustMultiplier()` |

二者均 **非 `@Sharable`**：每 channel 独立序列号发生器与去重状态。

**Encoder 实现要点：**

- 首包 `ctx.write(..., promise)`，冗余副本 `ctx.write(..., ctx.voidPromise())`
- `intervalMicros > 0` 时用 `ctx.executor().schedule(..., intervalMicros * i, MICROSECONDS)` 发送第 i 个副本并可 `flush`
- 多倍发送时用新 `DatagramPacket` 接管写出后，须 **`ReferenceCountUtil.release` 原始 `DatagramPacket`**，符合 Netty 引用计数约定
- `prependHeader` 对 payload `retain()` 后与 header 组成 composite，再写出

**Decoder 实现要点：**

- 每 sender 一个 **64 位滑动位图**（DTLS anti-replay 风格）；`SEQ_ID` 按无符号扩展到 `long` 参与比较
- 长时间无活动的 sender 窗口会淘汰（约 10 分钟），并周期性清理，减轻 `ConcurrentHashMap` 膨胀

### Pipeline 数据流

```
                        Inbound                          Outbound
Client ──→ UdpRedundantDecoder ──→ SocksUdpRelayHandler ──→ UdpRedundantEncoder ──→ Target
                  (去重)                  (业务路由)              (N 倍发送)

Target ──→ UdpRedundantDecoder ──→ UdpBackendRelayHandler ──→ UdpRedundantEncoder ──→ Client
                  (去重)                 (回程转发)               (N 倍发送)
```

### 覆盖路径与集成点

| 路径 | 集成位置 | 说明 |
|------|----------|------|
| SOCKS5 UDP | `SocksProxyServer` UDP 主 channel + `SocksUdpRelayHandler` 里 `UdpManager.open` 创建的 **outbound** channel | 客户端侧与上游侧均可编解码 |
| Udp2raw | 同上主 channel + `Udp2rawHandler` 侧 **server outbound** pipeline | UDP-over-raw 模式 |

统一通过 **`SocksProxyServer.addRedundantHandlers(pipeline, config)`** 挂接：当 `multiplier > 1` **或** `udpRedundantAdaptive == true` 时，按序 `addLast` Decoder、Encoder（静态构造 `UdpRedundantEncoder(multiplier, interval)`；自适应则共享同一 `UdpRedundantStats` 实例）。

---

## 发送策略（伪代码）

```java
void write(ctx, DatagramPacket original, promise) {
    int seqId = seqGenerator.incrementAndGet();
    ByteBuf packetWithHeader = prependHeader(original.content(), seqId);

    ctx.write(new DatagramPacket(packetWithHeader, recipient), promise);

    for (int i = 1; i < multiplier; i++) {
        if (intervalMicros > 0) {
            ctx.executor().schedule(() -> {
                writeRedundantCopy(ctx, seqId, payload, recipient);
                ctx.flush();
            }, intervalMicros * i, MICROSECONDS);
        } else {
            writeRedundantCopy(ctx, seqId, payload, recipient);
        }
    }
    ReferenceCountUtil.release(original);
}
```

---

## 去重算法（判定表）

| 场景 | 处理 |
|------|------|
| `seqId > highestSeq` | 新包，窗口前移，转发 |
| `seqId == highestSeq` | 与当前最高点重复，丢弃 |
| `highestSeq - seqId < 64` 且对应 bit 未置位 | 窗口内首次，置位并转发 |
| `highestSeq - seqId < 64` 且 bit 已置位 | 窗口内重复，丢弃 |
| `highestSeq - seqId >= 64` | 过旧，丢弃 |

---

## 与 FEC 的对比

| 维度 | 多倍发包 | FEC |
|------|----------|-----|
| 延迟 | 不等待组满 | 需组满再编码 |
| 带宽 | ×N | 约 ×(K+1)/K |
| 恢复 | 至多丢 N−1 份副本（依路径） | 组内通常最多恢复 1 个丢失等 |
| 复杂度 | 低 | 中高 |
| 场景 | 极低延迟、小包 | 可容忍组延迟、较大包 |

---

## 自适应倍率调整

Decoder 统计 `totalReceived`（含重复）与 `uniqueReceived`（去重后）。

```
redundancy_ratio = totalReceived / uniqueReceived
per_copy_loss    = 1 - (redundancy_ratio / currentMultiplier)
```

每 **2 秒** 在 Encoder 侧调用 `adjustMultiplier()`；高低阈值与 **连续 N 个周期** 防抖由配置控制；每次倍率 **±1** 阶梯调整。详情见 `UdpRedundantStats`。

## 部署与配置策略建议

在不同的部署环境（客户端侧 vs 服务端侧）下，建议采取不同的配置策略以达到最佳的网络优化效果：

### 1. 客户端侧 (Local Proxy / Client-side)
**建议：启用自适应模式 (`udpRedundantAdaptive = true`)**
- **原因**：客户端侧（如 `SocksUdpRelayHandler`）为每个上游中继创建独立的 Channel，拥有与之对应的**私有** `UdpRedundantStats`。
- **优势**：能够精确探测该特定路径的实时丢包情况，并动态调整倍率。在网络恢复后自动降倍率以节省移动端流量或家庭带宽。

### 2. 服务端侧 (Remote Proxy / Aggregating Server)
**建议：优先使用静态模式或分目的地规则，谨慎使用全局自适应**
- **原因**：主端口 UDP 服务器（`SocksProxyServer`）是一个单 Channel 聚合网关，来自不同物理位置、使用不同初始冗余度的客户端流量会在此汇聚。
- **风险（指标污染）**：若在服务端开启全局自适应，来自多个客户端的丢包统计会混合在一起。例如，Client A（5倍冗余）和 Client B（1倍冗余）的包混在一起，会导致 `UdpRedundantStats` 计算出一个“折中”的失真丢包率，进而影响服务端向所有终端回程时的倍率决策。
- **最佳实践**：服务端倾向于保持静态倍率，或通过 `udpRedundantDestinationRules` 针对特定客户端 IP 段配置专属倍率，以确保回程链路的稳定性。

---

## 源码与文件清单

```
rxlib/src/main/java/org/rx/net/socks/
├── SocksConfig.java
├── UdpRedundantConfig.java          # 新增：独立UDP冗余配置类
├── UdpRedundantStats.java
├── UdpRedundantEncoder.java
├── UdpRedundantDecoder.java
├── UdpRedundantDestinationRule.java   # 新增：分目的地倍率规则
├── UdpRedundantMultiplierResolver.java # 新增：目的地倍率解析接口
├── SocksProxyServer.java          # addRedundantHandlers(...)
├── SocksUdpRelayHandler.java
└── Udp2rawHandler.java
```

---

## 未覆盖 / 开放问题（后续可演进）

1. **回程是否始终多倍**：实现为双向往返一致；若仅需 client→server，需在架构上再裁剪。
2. **与 FEC 同 pipeline**：无硬互斥，由部署策略避免叠用。
3. **ShadowSocks UDP（如 `SSUdpProxyHandler`）**：当前未接入同一套 Handler。

---

## 测试与验证

**自动化：** `rxlib/src/test/java/org/rx/net/socks/UdpRedundantTest.java`

覆盖要点包括：N 倍写出与 header、去重与 64 窗口、非 MAGIC 透传、自适应丢包估计与防抖、Stats 与 Encoder 联动、**原始 `DatagramPacket` 引用释放** 等。

```bash
mvn -pl rxlib test -Dtest=UdpRedundantTest -Dmaven.test.skip=false
```

**手工建议：** Wireshark 看冗余份数与间隔；Linux 可用 `tc netem` 模拟丢包对比延迟。

**集成场景（计划级）：** 起 `SocksProxyServer`（如 multiplier=2），走 SOCKS5 UDP associate，确认对端收到多份、回程经去重后客户端只收一份——可按需在 `rxlib` 中补充专用集成测试。
