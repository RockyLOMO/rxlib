# UDP 多倍发包 (Multi-Send / Packet Redundancy) 实现计划

## 背景

游戏加速场景中，UDP 丢包会直接导致延迟上涨（因为无 TCP 重传机制）。**多倍发包**是一种简单高效的低延迟抗丢包策略：将每个 UDP 包冗余发送 N 次，接收端对重复包进行去重，只要 N 份中有任意一份到达即可。相比 FEC（需要凑组 & XOR 计算），多倍发包的特点是：
- **零延迟增加**：立即发送所有冗余副本，无需等组满
- **实现简单可靠**：无编解码复杂度
- **带宽换延迟**：适合包小（< 1KB 的游戏操控包）、对延迟极端敏感的场景

该功能需要在 SOCKS5 代理的 UDP relay 路径和 Udp2raw 路径中同时支持。

## User Review Required

> [!IMPORTANT]
> **去重策略选择**：接收端去重需要给每个 UDP 包附加一个唯一序列号 header，这会修改 wire protocol。从 proxy 透传的角度，有两种选择：
> 1. **仅在 outbound（backend relay）端多倍发送 + inbound（frontend relay 回程）端多倍发送**，并在接收端通过 Netty handler 去重 —— 需要双端都部署新版本
> 2. **仅在出口发送端多倍发送，不加序列号、不做去重** —— 完全无协议变更，但目标服务器或客户端会收到重复包
>
> 推荐方案 1（双端去重），因为游戏场景下接收端收到重复包可能导致异常行为。如果这是一个 proxy-to-proxy 的链路（client → SOCKS5 → upstream SOCKS5 → target），双端同时升级更可控。

> [!WARNING]
> **带宽放大**：multiplier=3 意味着带宽消耗 ×3。需要确认是否需要限制最大 multiplier 值（建议上限为 5），以及是否需要按目的地/源 IP 分别配置 multiplier。

> [!IMPORTANT]
> **间隔发送 vs 同一时刻发送**：同一时刻发送 N 个冗余包，所有包走同一网络路径，可能同时丢失（burst loss）。建议提供一个可选的微间隔参数 `redundantIntervalMicros`，让冗余副本之间有微小延迟（如 200μs ~ 1ms），利用网络路径上的不同时间窗口提高到达概率。默认为 0（同一时刻发送）。

## Proposed Changes

### 配置层

#### [MODIFY] [SocksConfig.java](file:///d:/projs/rxlib/rxlib/src/main/java/org/rx/net/socks/SocksConfig.java)

新增以下配置字段：

```java
/**
 * UDP 多倍发包倍率。1 = 不冗余（默认），2 = 双发，3 = 三发。
 * 用于游戏低延迟场景，以带宽换取丢包容忍度。
 * 取值范围 [1, 5]，超过 5 将被强制限定为 5。
 */
private int udpRedundantMultiplier = 1;

/**
 * 冗余副本之间的发送间隔（微秒）。
 * 0 = 同一时刻发送（默认）；> 0 = 每个冗余副本间隔发送。
 * 建议 200~1000μs，用于应对突发丢包（burst loss）。
 */
private int udpRedundantIntervalMicros = 0;
```

---

### Netty Handler 层

#### [NEW] [UdpRedundantEncoder.java](file:///d:/projs/rxlib/rxlib/src/main/java/org/rx/net/socks/UdpRedundantEncoder.java)

**出站 Handler**，拦截 `DatagramPacket` write 操作，将每个包复制为 N 份发送。

关键设计：
- 在每个 DatagramPacket 内容**前面**添加一个 8 字节 header：`[4B sequenceId] [4B magic/version]`
- 第一份使用原始 `ChannelPromise`，后续冗余副本使用 `voidPromise`（避免多次回调）
- 如果 `redundantIntervalMicros > 0`，使用 `ctx.executor().schedule()` 延迟发送冗余副本
- **不是 `@Sharable`**，因为每个 channel 持有自己的 `AtomicInteger` 序列号
- 序列号使用 AtomicInteger 即可（单 channel 内递增、仅用于去重不需全局唯一）

```
写入流程：
DatagramPacket → [prepend 8B header] → write original (promise)
                                     → write copy 1 (voidPromise, optional delay)
                                     → write copy 2 (voidPromise, optional delay)
                                     → flush
```

#### [NEW] [UdpRedundantDecoder.java](file:///d:/projs/rxlib/rxlib/src/main/java/org/rx/net/socks/UdpRedundantDecoder.java)

**入站 Handler**，对收到的 DatagramPacket 进行去重。

关键设计：
- 读取前 8 字节 header 的 `sequenceId`，检查是否已见过
- **去重窗口**：使用高效的位图/环形缓冲区维护最近 N 个已见 sequenceId（参考 DTLS anti-replay window）
  - 采用 **滑动窗口 + long bitmap** 实现：维护 `highestSeqSeen`（long）和 `receivedBitmap`（long，64 位即 64 个序列号窗口）
  - 如果 seqId > highestSeqSeen，向前滑动窗口
  - 如果 seqId 在窗口内且已标记，丢弃
  - 如果 seqId 在窗口外（太旧），丢弃
- 通过后，剥离 8B header，将原始 DatagramPacket fire 到 pipeline
- **不是 `@Sharable`**，因为持有去重状态
- 使用 `(srcAddr, seqId)` 复合键，因为单个 inbound channel 可能收到来自不同 sender 的包

```
接收流程：
DatagramPacket → [read 8B header] → seqId 已见? → 丢弃 (log debug)
                                   → seqId 新的? → 标记已见 → strip header → fireChannelRead
```

---

### Integration 集成层

#### [MODIFY] [SocksUdpRelayHandler.java](file:///d:/projs/rxlib/rxlib/src/main/java/org/rx/net/socks/SocksUdpRelayHandler.java)

1. **Outbound（backend → target）路径**：在 outbound channel 的 pipeline 中添加 `UdpRedundantEncoder`
2. **Inbound（回程）路径**：
   - `UdpBackendRelayHandler` 收到 target 的回程包后发回给客户端时，也需要多倍发送
   - 在 outbound pipeline 中添加 `UdpRedundantDecoder`（如果 upstream 也支持多倍发送）

具体修改点：
- 在 `channelRead0` 中创建 outbound channel 时（`UdpManager.open` 的 lambda），根据 `config.getUdpRedundantMultiplier() > 1` 条件性地添加 `UdpRedundantEncoder`
- Inbound（主 channel）的 pipeline 中，在 `SocksUdpRelayHandler.DEFAULT` 前添加 `UdpRedundantDecoder`（接收客户端的冗余包）和 `UdpRedundantEncoder`（发回客户端的冗余回程包）

#### [MODIFY] [Udp2rawHandler.java](file:///d:/projs/rxlib/rxlib/src/main/java/org/rx/net/socks/Udp2rawHandler.java)

与 SocksUdpRelayHandler 同理：
- Server 端 outbound pipeline 添加 `UdpRedundantEncoder`
- Client 端收发路径添加对应的 encoder/decoder

#### [MODIFY] [SocksProxyServer.java](file:///d:/projs/rxlib/rxlib/src/main/java/org/rx/net/socks/SocksProxyServer.java)

在 UDP server channel 初始化时（L111-L119），根据配置条件性地在 pipeline 中添加 `UdpRedundantDecoder` 和 `UdpRedundantEncoder`：

```java
// udp server
int udpPort = config.getListenPort();
udpChannel = Sockets.udpBootstrap(config, channel -> {
    ChannelPipeline pipeline = channel.pipeline();
    // 多倍发包 - decode incoming redundant packets, encode outgoing
    if (config.getUdpRedundantMultiplier() > 1) {
        pipeline.addLast(new UdpRedundantDecoder());
        pipeline.addLast(new UdpRedundantEncoder(config.getUdpRedundantMultiplier(), config.getUdpRedundantIntervalMicros()));
    }
    if (config.isEnableUdp2raw()) {
        pipeline.addLast(Udp2rawHandler.DEFAULT);
    } else {
        Sockets.addServerHandler(channel, config);
        pipeline.addLast(SocksUdpRelayHandler.DEFAULT);
    }
}).attr(SocksContext.SOCKS_SVR, this).bind(Sockets.newAnyEndpoint(udpPort)).channel();
```

---

### 文件总览

| 文件 | 操作 | 说明 |
|------|------|------|
| `SocksConfig.java` | MODIFY | 新增 `udpRedundantMultiplier`、`udpRedundantIntervalMicros` 配置 |
| `UdpRedundantEncoder.java` | NEW | 出站多倍发送 handler |
| `UdpRedundantDecoder.java` | NEW | 入站去重 handler |
| `SocksProxyServer.java` | MODIFY | UDP channel pipeline 集成 |
| `SocksUdpRelayHandler.java` | MODIFY | Outbound channel pipeline 集成 |
| `Udp2rawHandler.java` | MODIFY | Udp2raw outbound channel pipeline 集成 |

## Open Questions

> [!IMPORTANT]
> 1. **是否需要按目的地细分 multiplier**？当前设计是全局配置，所有 UDP 连接共用一个 multiplier。是否需要支持按 dstEp 规则配置不同的 multiplier（例如特定游戏服务器 IP 才启用 3 倍发包）？
> 
> 2. **是否只在 outbound（proxy → target）方向做多倍发送**？还是 inbound（target → proxy → client）回程也需要？游戏场景通常是 client→server 的操控包更关键（< 100B），server→client 的状态同步包较大（可能 500B+），全方向多倍会显著增加带宽。
>
> 3. **与已有 FEC 功能的关系**：项目中已有 `FecEncoder`/`FecDecoder` 实现。多倍发包与 FEC 是否互斥？还是可以叠加使用（先多倍发送、再 FEC 编码）？建议互斥，因为 FEC 本身已经有冗余度。
>
> 4. **ShadowSocks 的 UDP handler（`SSUdpProxyHandler`）是否也需要支持**？当前计划只覆盖 SOCKS5 和 Udp2raw 路径。

## Verification Plan

### Automated Tests

1. **UdpRedundantEncoderTest**：
   - 验证 multiplier=3 时，1 次 write 产生 3 个 DatagramPacket
   - 验证每个包的 8B header 包含正确的 sequenceId
   - 验证 `redundantIntervalMicros > 0` 时冗余副本的延迟发送行为

2. **UdpRedundantDecoderTest**：
   - 验证首次收到的包正常传递
   - 验证重复 sequenceId 的包被丢弃
   - 验证滑动窗口正确工作（窗口外的旧包被丢弃）
   - 验证不同 sender 的相同 sequenceId 不会互相影响

3. **集成测试**：
   - 启动 SocksProxyServer（multiplier=2），通过 SOCKS5 UDP associate 发送 UDP 数据
   - 验证 target server 收到 2 份数据包
   - 验证回程数据正确去重后送达 client

### Manual Verification

- 使用 Wireshark 抓包验证冗余包数量和间隔
- 使用 `tc netem` (Linux) 模拟丢包环境，对比有无多倍发包的延迟表现
