# SocksScene 场景4 UDP 链路 master 二次 review 补充计划

> 本文件是 `docs/plan/SocksScene4BidirectionalUdpRedundant-plan.md` 的补充内容。由于原计划文档在更新时发生 SHA 冲突，先独立落地为 follow-up 文档；后续可合并回原文档。

## 1. 背景

当前 master 已完成场景4 UDP 链路相关修复，最新重点提交：

```text
1497b905817a41b4c90e141794843246e82cdb73
refactor(socks): UDP 冗余编解码器与 Socket 配置优化
```

静态 review 结论：原计划 7 项主体基本落地，但仍建议追加一个小 PR 修以下 4 个后续点。

| 编号 | 后续点 | 优先级 | 说明 |
| --- | --- | --- | --- |
| F1 | `SocksUdpRelayHandler` 只在 client sender 首次确认/变化时 `addRedundantPeer` | P0/P1 | 避免 B 侧双向 RDNT 后每包分配 `InetSocketAddress` + CHM put |
| F2 | 标准 SOCKS5 fallback 路径显式支持 B -> A RDNT | P0/P1 | RPC relay group 已修；fallback 下仍受 `clientTcpAddr != tcpPeerAddr` 启发式限制 |
| F3 | RDNT encoder/decoder 高频 metrics 改为采样或周期聚合 | P1 | 低基数已收敛，但 duplicate/delayed copy 高频 record 仍可能有性能压力 |
| F4 | `OUTBOUND_POOL` reserve source count 异常路径兜底释放 | P2 | 防止 `computeIfAbsent/openOutboundChannel` 抛异常后 source count 泄漏 |

## 2. 后续修复 F1：避免每包重复 `addRedundantPeer`

### 2.1 当前风险

B 侧 RPC relay group 双向 RDNT 开启后，`ATTR_REDUNDANT_CLIENT_PEER=true`。

当前 `SocksUdpRelayHandler.handleClientPacket(...)` 每个 client 包都会执行：

```java
if (Boolean.TRUE.equals(relay.attr(UdpRelayAttributes.ATTR_REDUNDANT_CLIENT_PEER).get())) {
    UdpRelayAttributes.addRedundantPeer(relay, sender);
}
```

`UdpRelayAttributes.addRedundantPeer(...)` 内部会执行：

```java
initRedundantPeers(channel).put(normalize(address), Boolean.TRUE);
```

`normalize(address)` 会创建新的 `InetSocketAddress`。因此高 PPS 下 B 侧每包都会产生：

```text
new InetSocketAddress
ConcurrentHashMap.put
```

功能正确，但会形成新的热路径开销。

### 2.2 修复目标

只在以下情况登记 RDNT peer：

```text
client sender 第一次确认
或者 client sender 发生变化
```

不要每包重复登记。

### 2.3 推荐实现

文件：

```text
rxlib/src/main/java/org/rx/net/socks/SocksUdpRelayHandler.java
```

推荐改法：

```java
InetSocketAddress clientAddr = relay.attr(ATTR_CLIENT_ADDR).get();
boolean locked = Boolean.TRUE.equals(relay.attr(UdpRelayAttributes.ATTR_CLIENT_LOCKED).get());
boolean clientChanged = false;

if (locked) {
    if (clientAddr == null || !clientAddr.equals(sender)) {
        recordDrop("unexpected-client", sender, clientOriginAddr, config, inBuf.readableBytes());
        return;
    }
} else if (clientAddr == null || !clientAddr.equals(sender)) {
    relay.attr(ATTR_CLIENT_ADDR).set(sender);
    clientChanged = true;
}

if (clientChanged && Boolean.TRUE.equals(relay.attr(UdpRelayAttributes.ATTR_REDUNDANT_CLIENT_PEER).get())) {
    UdpRelayAttributes.addRedundantPeer(relay, sender);
}
```

说明：

- `group.clientAddr != null` 的 RPC relay group bind 阶段已预加 peer，不需要每包重复加。
- `group.clientAddr == null` 的 NAT A 场景，会在第一个真实 UDP sender 到来时登记一次。
- 如果 unlocked relay 后续 sender 变化，也会更新并重新登记一次。

### 2.4 建议测试

```text
SocksUdpRelayHandlerTest#redundantClientPeerAddedOnlyWhenClientSenderChanges
```

测试思路：

- 构造 relay channel，设置 `ATTR_REDUNDANT_CLIENT_PEER=true`。
- 发送同一 sender 多个包，确认 redundant peer map size 保持 1。
- 更换 sender 后，确认 map size 变为 2，且 `ATTR_CLIENT_ADDR` 更新。

## 3. 后续修复 F2：标准 SOCKS5 fallback 路径显式支持 B -> A RDNT

### 3.1 当前风险

RPC relay group 下 B -> A 已修，但标准 SOCKS5 `UDP_ASSOCIATE` 路径仍使用：

```java
final boolean redundantClientPeer = UdpRelayAttributes.shouldTrackClientAsRedundantPeer(config)
        && clientTcpAddr != null && tcpPeerAddr != null && !clientTcpAddr.equals(tcpPeerAddr);
```

如果 Proxy B 侧拿到：

```text
clientTcpAddr == tcpPeerAddr
```

即使 `configB.setUdpRedundantMultiplier(2)`，B -> A 也不会登记 A sender 为 RDNT peer。

如果场景4要求：

```text
只要 A <-> B 链路开启 UDP redundant，
无论 RPC relay group 还是标准 SOCKS5 fallback，
B -> A 都必须多倍发送。
```

则需要显式配置控制，而不是依赖 `clientTcpAddr != tcpPeerAddr` 这个启发式判断。

### 3.2 推荐方案

新增配置项，建议放在 `SocksConfig`：

```java
/**
 * UDP relay 是否允许把真实 client UDP sender 登记为 RDNT peer。
 * 用于 rxlib A<->B 链路标准 SOCKS5 fallback 下的 B->A RDNT。
 * 默认 false，避免普通 SOCKS5 client 被误打 RDNT。
 */
private boolean udpRedundantTrackClientPeer;
```

然后 `Socks5CommandRequestHandler` 改为：

```java
final boolean redundantClientPeer = UdpRelayAttributes.shouldTrackClientAsRedundantPeer(config)
        && (config.isUdpRedundantTrackClientPeer()
            || (clientTcpAddr != null && tcpPeerAddr != null && !clientTcpAddr.equals(tcpPeerAddr)));
```

场景4 Proxy B 使用：

```java
configB.setUdpRedundantTrackClientPeer(true);
```

### 3.3 为什么不直接全局放宽

不要直接改成：

```java
final boolean redundantClientPeer = UdpRelayAttributes.shouldTrackClientAsRedundantPeer(config);
```

原因：普通 SOCKS5 client 没有 RDNT decoder。如果 B 侧 relay 把普通 client sender 加入 redundant peer，回包会带 RDNT 头，普通 client 会收到错误 payload。

### 3.4 建议测试

```text
SocksProxyServerIntegrationTest#socks5UdpRelay_chained_fallback_bidirectionalUdpRedundant_enabledByTrackClientPeer
SocksProxyServerIntegrationTest#socks5UdpRelay_plainClient_doesNotReceiveRdntWhenTrackClientPeerDisabled
```

验收：

- `udpRedundantTrackClientPeer=true` 时，fallback 下 B -> A 能 RDNT。
- 默认 false 时，普通 SOCKS5 client 不会收到 RDNT 头。

## 4. 后续修复 F3：RDNT 高频 metrics 改为采样或周期聚合

### 4.1 当前风险

当前 tag 已经低基数，但以下指标仍可能在高 PPS 下每包 record：

```text
udp.redundant.delayed.copy.count
udp.redundant.delayed.pending.count
udp.redundant.decoder.drop.count{reason=duplicate}
udp.redundant.decoder.window.count
```

典型场景：

- `multiplier=3` 时，decoder 对重复副本可能每包记录 `duplicate`。
- `intervalMicros > 0` 时，encoder 对每个 delayed copy 记录 scheduled/inline/drop。

低基数只能保护指标存储 series 数，不能避免高频 record 带来的 CPU/队列压力。

### 4.2 推荐方案 A：轻量采样

在 `UdpRedundantEncoder` / `UdpRedundantDecoder` 内部做固定采样，例如 1/64：

```java
private final AtomicInteger metricSampleCounter = new AtomicInteger();

private boolean shouldSampleMetric() {
    return (metricSampleCounter.incrementAndGet() & 63) == 0;
}
```

然后：

```java
if (DiagnosticMetrics.isEnabled() && shouldSampleMetric()) {
    DiagnosticMetrics.record("udp.redundant.decoder.drop.count", 64D, "reason=duplicate");
}
```

注意 sample 后 value 用采样倍率补偿，例如 64D。

### 4.3 推荐方案 B：周期聚合

更推荐：每个 encoder/decoder 实例用 `LongAdder` 聚合：

```java
LongAdder duplicateDrops;
LongAdder windowFullDrops;
LongAdder delayedScheduled;
LongAdder delayedInline;
LongAdder delayedDrop;
```

每 1~2 秒在 EventLoop 上 flush 一次：

```java
DiagnosticMetrics.record("udp.redundant.decoder.drop.count", duplicateDrops.sumThenReset(), "reason=duplicate");
```

优点：

- 热路径只做 LongAdder increment。
- 指标写入频率固定。
- 保留更准确的总量。

第一期如果不想加调度复杂度，可以先用采样；长期建议周期聚合。

## 5. 后续修复 F4：`OUTBOUND_POOL` source count 异常路径兜底释放

### 5.1 当前风险

`SSUdpProxyHandler.acquireOutboundChannel(...)` 逻辑是：

```java
if (!reserveOutboundSource(key, config.getUdpOutboundPoolMaxPerSource())) {
    return failedFuture;
}

ChannelFuture channelFuture = OUTBOUND_POOL.computeIfAbsent(key, k -> {
    created.set(true);
    return openOutboundChannel(inbound, server, upstream, routeKey.source, k);
});
```

正常路径中：

- bind fail 会 remove + release。
- close 会 remove + release。
- inbound close 会 remove + release。
- computeIfAbsent 未创建时会 release。

但如果 `computeIfAbsent(...)` 或 `openOutboundChannel(...)` 在返回 `ChannelFuture` 前抛异常，source count 已经 +1，可能没有释放。

### 5.2 推荐实现

文件：

```text
rxlib/src/main/java/org/rx/net/socks/SSUdpProxyHandler.java
```

增加 try/catch 兜底：

```java
boolean reserved = reserveOutboundSource(key, config.getUdpOutboundPoolMaxPerSource());
if (!reserved) {
    recordOutboundPoolOpen("per-source-full");
    recordUdpDrop("outbound-pool-per-source-full", routeKey.source, routeKey.destination, null, 0);
    return inbound.newFailedFuture(new IllegalStateException("SS UDP outbound pool source full"));
}

try {
    AtomicBoolean created = new AtomicBoolean();
    ChannelFuture channelFuture = OUTBOUND_POOL.computeIfAbsent(key, k -> {
        created.set(true);
        return openOutboundChannel(inbound, server, upstream, routeKey.source, k);
    });
    if (created.get()) {
        recordOutboundPoolOpen("success");
    } else {
        releaseOutboundSource(key);
    }
    return channelFuture;
} catch (Throwable e) {
    releaseOutboundSource(key);
    recordOutboundPoolOpen("error");
    throw e;
}
```

### 5.3 建议测试

```text
SSUdpProxyHandlerTest#outboundPoolSourceCountReleasedWhenOpenThrows
```

测试可通过 mock/测试 hook 让 `openOutboundChannel` 抛异常，确认 `OUTBOUND_POOL_SOURCE_COUNTS` 回到 0。

## 6. 建议实施顺序

### PR A：热路径和功能边界

1. F1：`SocksUdpRelayHandler` 只在 client sender 首次确认/变化时 add RDNT peer。
2. F2：新增 `udpRedundantTrackClientPeer`，保证标准 SOCKS5 fallback 下 B -> A RDNT 可显式开启。

### PR B：稳定性与观测开销

3. F4：`OUTBOUND_POOL` source count 异常路径兜底释放。
4. F3：RDNT 高频 metrics 采样或周期聚合。

如果只做一个提交，也建议按 F1 -> F2 -> F4 -> F3 的顺序改。

## 7. 补充测试清单

```text
SocksUdpRelayHandlerTest#redundantClientPeerAddedOnlyWhenClientSenderChanges
SocksProxyServerIntegrationTest#socks5UdpRelay_chained_fallback_bidirectionalUdpRedundant_enabledByTrackClientPeer
SocksProxyServerIntegrationTest#socks5UdpRelay_plainClient_doesNotReceiveRdntWhenTrackClientPeerDisabled
SSUdpProxyHandlerTest#outboundPoolSourceCountReleasedWhenOpenThrows
UdpRedundantTest#decoderDuplicateMetricsAreSampledOrAggregated
UdpRedundantTest#encoderDelayedCopyMetricsAreSampledOrAggregated
```

## 8. 验收标准

### 8.1 功能验收

```text
RPC relay group：A -> B 与 B -> A 均有 RDNT 多倍发送。
标准 SOCKS5 fallback：开启 udpRedundantTrackClientPeer 后，A -> B 与 B -> A 均有 RDNT 多倍发送。
普通 SOCKS5 client：默认配置下不应收到 RDNT 头。
```

### 8.2 性能验收

```text
B 侧 relay 不再每包重复 addRedundantPeer。
RDNT duplicate / delayed copy 指标不再每包直接写 DiagnosticMetrics。
OUTBOUND_POOL_SOURCE_COUNTS 在异常路径不会泄漏。
```

### 8.3 保持不变

```text
不处理问题 5：端口跳跃资源线性增长。
不处理问题 6：spreadRedundantCopies / 跨 relay shared dedup。
不改 udp2raw 重构中的类。
不引入 hopCount * multiplier 的同包放大。
```

## 9. 推荐默认配置

```properties
udpRedundant.multiplier=2
udpRedundant.intervalMicros=0

udpWriteLimitBytes=1048576
udpWritePerSourceLimitBytes=262144

udpOutboundPoolMaxSize=8192
udpOutboundPoolWarnSize=4096
udpOutboundPoolMaxPerSource=256

# 仅在 rxlib A<->B 标准 SOCKS5 fallback 链路的 B 侧开启
udpRedundantTrackClientPeer=false
```

场景4如果需要标准 SOCKS5 fallback 下也保证 B -> A RDNT：

```properties
# Proxy B
udpRedundantTrackClientPeer=true
```
