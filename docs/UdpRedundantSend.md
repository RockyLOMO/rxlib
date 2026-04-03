## UDP 多倍发包 (Packet Redundancy)

游戏加速场景中，UDP 丢包会直接导致延迟上涨（无 TCP 重传机制）。多倍发包通过将每个 UDP 包冗余发送 N 次，接收端去重，只要 N 份中任意一份到达即可，是一种以带宽换延迟的简单高效策略。

**特点：**
- 零延迟增加：立即发送所有冗余副本，无需等组满（对比 FEC）
- 实现简单可靠：无编解码复杂度
- 适合包小（< 1KB 的游戏操控包）、对延迟极端敏感的场景

### 快速使用

```java
SocksConfig config = new SocksConfig(1080);
// 三倍发送，每个 UDP 包发 3 份
config.setUdpRedundantMultiplier(3);
// 可选：冗余副本间隔 500μs（应对 burst loss）
config.setUdpRedundantIntervalMicros(500);

SocksProxyServer server = new SocksProxyServer(config);
```

默认 `udpRedundantMultiplier = 1`，表示不启用冗余，功能完全无侵入。

### 配置参数

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `udpRedundantMultiplier` | int | 1 | 发送倍率。1 = 不冗余，2 = 双发，3 = 三发。取值范围 [1, 5]，超出自动钳位 |
| `udpRedundantIntervalMicros` | int | 0 | 冗余副本之间的发送间隔（微秒）。0 = 同一时刻发送；> 0 = 间隔发送，利用不同时间窗口提高到达概率。建议 200~1000μs |

**带宽影响：** `multiplier = 3` 意味着 UDP 带宽消耗 ×3。游戏操控包通常 < 100B，三倍发送额外开销仅约 200B/包，对带宽影响极小。

### Wire Protocol

每个 UDP 包前面附加 8 字节 header：

```
+-------------------+-------------------+
|  MAGIC (4 bytes)  |  SEQ_ID (4 bytes) |
|  0x52444E54       |  auto-increment   |
+-------------------+-------------------+
|              原始 payload             |
+---------------------------------------+
```

- **MAGIC**: `0x52444E54`（ASCII "RDNT"），用于向后兼容识别
- **SEQ_ID**: 每个 channel 独立的 AtomicInteger 递增序列号，用于接收端去重

接收端检测到 MAGIC 不匹配时，视为普通包直接透传，保证与非冗余端的兼容性。

### 去重算法

采用 DTLS anti-replay window 风格的 **滑动窗口 + 位图** 去重：

```
               ← 窗口宽度 64 →
 ┌─────────────────────────────┐
 │ ... bit63  bit2  bit1  bit0 │
 └─────────────────────────────┘
                           ↑
                      highestSeq
```

- 每个 sender 地址独立维护一个去重窗口（`ConcurrentHashMap<InetSocketAddress, DeduplicationWindow>`）
- `highestSeq`：已见到的最高序列号
- `bitmap`：long 类型，64 bit，`bit[i] = 1` 表示 `(highestSeq - i)` 已收到

**判定逻辑：**

| 场景 | 处理 |
|------|------|
| `seqId > highestSeq` | 新包，窗口前移，传递 |
| `seqId == highestSeq` | 重复，丢弃 |
| `highestSeq - seqId < 64` 且 bit 未标记 | 窗口内新包，传递 |
| `highestSeq - seqId < 64` 且 bit 已标记 | 窗口内重复，丢弃 |
| `highestSeq - seqId >= 64` | 窗口外（过旧），丢弃 |

过期 sender 窗口（10 分钟无活动）自动清理，防止内存泄漏。

### Pipeline 架构

功能通过两个 Netty Handler 实现，插入在 UDP channel pipeline 中：

```
                        Inbound                          Outbound
Client ──→ UdpRedundantDecoder ──→ SocksUdpRelayHandler ──→ UdpRedundantEncoder ──→ Target
                  (去重)                  (业务路由)              (N 倍发送)

Target ──→ UdpRedundantDecoder ──→ UdpBackendRelayHandler ──→ UdpRedundantEncoder ──→ Client
                  (去重)                 (回程转发)               (N 倍发送)
```

**Handler 说明：**

| Handler | 方向 | 功能 |
|---------|------|------|
| `UdpRedundantDecoder` | Inbound | 读取 8B header、去重、剥离 header 后传递给下游 |
| `UdpRedundantEncoder` | Outbound | 添加 8B header、写入 N 份冗余副本 |

两个 Handler 均非 `@Sharable`，每个 channel 持有独立状态（序列号 / 去重窗口）。

### 覆盖路径

多倍发包集成在以下三处 UDP 路径中：

| 路径 | 集成位置 | 说明 |
|------|----------|------|
| SOCKS5 UDP Relay | `SocksProxyServer` 主 channel + `SocksUdpRelayHandler` outbound channel | 标准 SOCKS5 UDP 代理 |
| Udp2raw | `SocksProxyServer` 主 channel + `Udp2rawHandler` server outbound channel | UDP-over-rawsocket 模式 |

### 发送策略

```java
// UdpRedundantEncoder.write() 伪代码
void write(ctx, DatagramPacket original, promise) {
    int seqId = seqGenerator.incrementAndGet();
    ByteBuf packetWithHeader = prependHeader(original.content(), seqId);

    // 第 1 份：使用原始 promise，保证写入结果正确回调
    ctx.write(new DatagramPacket(packetWithHeader, recipient), promise);

    // 第 2~N 份：使用 voidPromise，避免多次回调
    for (int i = 1; i < multiplier; i++) {
        if (intervalMicros > 0) {
            // 延迟发送，利用不同时间窗口应对 burst loss
            ctx.executor().schedule(() -> {
                writeRedundantCopy(ctx, seqId, payload, recipient);
                ctx.flush();
            }, intervalMicros * i, MICROSECONDS);
        } else {
            // 同一时刻发送
            writeRedundantCopy(ctx, seqId, payload, recipient);
        }
    }
}
```

### 与 FEC 的对比

| 维度 | 多倍发包 (Redundant Send) | FEC (Forward Error Correction) |
|------|--------------------------|--------------------------------|
| 延迟增加 | 0 | 需等组满（groupSize 个包） |
| 带宽开销 | ×N（multiplier） | ×(K+1)/K |
| 恢复能力 | N-1 个副本可丢 | 组内最多丢 1 个 |
| 实现复杂度 | 低（header + 去重） | 中（XOR 编解码） |
| 适用场景 | 极低延迟、小包 | 中等延迟容忍、较大包 |

两种方案建议**互斥使用**，不建议叠加——FEC 本身已有冗余度，叠加后带宽开销过大。

### 涉及文件

```
rxlib/src/main/java/org/rx/net/socks/
├── SocksConfig.java               # 配置：udpRedundantMultiplier, udpRedundantIntervalMicros
├── UdpRedundantEncoder.java        # 出站 Handler：8B header + N 倍发送
├── UdpRedundantDecoder.java        # 入站 Handler：滑动窗口位图去重
├── SocksProxyServer.java           # 主 UDP channel pipeline 集成
├── SocksUdpRelayHandler.java       # SOCKS5 outbound channel pipeline 集成
└── Udp2rawHandler.java             # Udp2raw outbound channel pipeline 集成
```
