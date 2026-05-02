# SocksScene 场景4 UDP 链路最终复查结果（2026-05-03）

本文件记录对 `docs/plan/SocksScene4BidirectionalUdpRedundant-plan.md` 所列主体 7 项和 follow-up 4 项的 master 二次复查结果。

## 1. 复查范围

复查最新 master 相关提交：

```text
ccaf7b7493dfe2c62ac2bb87eb1f15c7dbee33bf
feat(socks): add udpRedundantTrackClientPeer config for A<->B fallback chain
```

复查代码范围：

```text
rxlib/src/main/java/org/rx/net/socks/SocksConfig.java
rxlib/src/main/java/org/rx/net/socks/Socks5CommandRequestHandler.java
rxlib/src/main/java/org/rx/net/socks/SocksUdpRelayHandler.java
rxlib/src/main/java/org/rx/net/socks/SSUdpProxyHandler.java
rxlib/src/main/java/org/rx/net/socks/UdpRelayAttributes.java
rxlib/src/main/java/org/rx/net/socks/UdpRelayGroupManager.java
rxlib/src/main/java/org/rx/net/socks/UdpRedundantEncoder.java
rxlib/src/main/java/org/rx/net/socks/UdpRedundantDecoder.java
```

复查测试范围：

```text
rxlib/src/test/java/org/rx/net/socks/SocksUdpRelayHandlerTest.java
rxlib/src/test/java/org/rx/net/socks/Socks5CommandRequestHandlerTest.java
rxlib/src/test/java/org/rx/net/socks/SSUdpProxyHandlerTest.java
rxlib/src/test/java/org/rx/net/socks/UdpRedundantTest.java
```

说明：本次为静态 review；GitHub connector 没有查到该 commit 的 workflow run。

## 2. 结论

主体 7 项和 follow-up 4 项均已在 master 中落地，当前未发现新的阻断问题。

| 编号 | 状态 | 复查结论 |
| --- | --- | --- |
| 1 | 已完成 | `SocksUdpUpstream.selectUdpRelayAddressAndRecord(...)` 已用于 SS/SOCKS UDP 主链路，避免 select + record 每包双 maintenance。 |
| 2 | 已完成 | `UdpRedundantEncoder` 已限制 delayed copy pending 数，超限 inline fallback。 |
| 3 | 已完成 | `UdpRedundantDecoder` 已增加 `maxWindows` 和 bounded cleanup，window 满时 RDNT 包 drop。 |
| 4 | 已完成 | RPC relay group 的 B -> A 已通过 client sender peer tracking 补齐 RDNT 条件。 |
| 7 | 已完成 | `SSUdpProxyHandler.OUTBOUND_POOL` 已有全局上限、warn 阈值、per-source 限制和关闭回收。 |
| 8 | 已完成 | UDP write limit 与 SS inbound per-source pending soft limit 已落地。 |
| 9 | 已完成 | UDP metrics tags 已收敛为低基数，动态 endpoint/port/key 不进入 tag。 |
| F1 | 已完成 | `SocksUdpRelayHandler` 已只在 client sender 首次确认或变化时调用 `addRedundantClientPeerIfChanged(...)`，避免每包 CHM put。 |
| F2 | 已完成 | `SocksConfig.udpRedundantTrackClientPeer` 已加入，标准 SOCKS5 fallback 可显式打开 B -> A RDNT，默认 false 保护普通 client。 |
| F3 | 已完成 | `UdpRedundantEncoder` / `UdpRedundantDecoder` 已加入 `METRIC_SAMPLE_RATE=64` 和 `shouldSampleMetric()`，高频指标改为采样记录。 |
| F4 | 已完成 | `SSUdpProxyHandler.acquireOutboundChannel(...)` 已用 try/catch 包住 `computeIfAbsent/openOutboundChannel`，异常路径会释放 source count。 |

## 3. 关键核验点

### 3.1 F1：避免每包重复登记 RDNT peer

`SocksUdpRelayHandler.handleClientPacket(...)` 现在通过 `clientChanged` 和 `ATTR_REDUNDANT_CLIENT_ADDR` 判断，只在 sender 首次确认或变化时调用：

```java
UdpRelayAttributes.addRedundantClientPeerIfChanged(relay, sender);
```

`UdpRelayAttributes.addRedundantClientPeerIfChanged(...)` 会先比较 normalized sender 与当前 `ATTR_REDUNDANT_CLIENT_ADDR`，相同则直接 return。

对应测试：

```text
SocksUdpRelayHandlerTest#redundantClientPeerAddedOnlyWhenClientSenderChanges
```

### 3.2 F2：标准 SOCKS5 fallback 显式支持 B -> A RDNT

`SocksConfig` 已新增：

```java
private boolean udpRedundantTrackClientPeer;
```

`Socks5CommandRequestHandler.shouldTrackRedundantClientPeer(...)` 现在逻辑为：

```java
return config.isUdpRedundantTrackClientPeer()
        || (clientTcpAddr != null && tcpPeerAddr != null && !clientTcpAddr.equals(tcpPeerAddr));
```

前置仍要求：

```java
UdpRelayAttributes.shouldTrackClientAsRedundantPeer(config)
```

因此默认 false 不会误伤普通 SOCKS5 client；场景4标准 fallback 的 Proxy B 可以显式开启。

对应测试：

```text
Socks5CommandRequestHandlerTest#redundantClientPeerTrackingRequiresExplicitFallbackFlagForSamePeer
Socks5CommandRequestHandlerTest#redundantClientPeerTrackingKeepsExistingDifferentPeerHeuristic
```

### 3.3 F3：高频 RDNT metrics 采样

`UdpRedundantEncoder` / `UdpRedundantDecoder` 均新增：

```java
static final int METRIC_SAMPLE_RATE = 64;
boolean shouldSampleMetric()
```

计数类指标使用采样倍率补偿，例如：

```java
DiagnosticMetrics.record("udp.redundant.decoder.drop.count", METRIC_SAMPLE_RATE, "reason=" + reason);
```

对应测试：

```text
UdpRedundantTest#testEncoderDelayedCopyMetricsAreSampled
UdpRedundantTest#testDecoderDuplicateMetricsAreSampled
```

### 3.4 F4：OUTBOUND_POOL source count 异常路径释放

`SSUdpProxyHandler.acquireOutboundChannel(...)` 已对 `computeIfAbsent(...)` 包 try/catch：

```java
try {
    ChannelFuture channelFuture = OUTBOUND_POOL.computeIfAbsent(...);
    ...
    return channelFuture;
} catch (Throwable e) {
    releaseOutboundSource(key);
    recordOutboundPoolOpen("error");
    throw e;
}
```

对应测试：

```text
SSUdpProxyHandlerTest#outboundPoolSourceCountReleasedWhenOpenThrows
```

## 4. 保留边界

继续保持：

```text
不处理问题 5：端口跳跃资源线性增长。
不处理问题 6：spreadRedundantCopies / 跨 relay shared dedup。
不改 udp2raw 重构中的类。
不引入 hopCount * multiplier 的同包放大。
```

## 5. 建议后续

当前没有必须阻断的问题。后续如果继续优化，可以考虑把 RDNT 采样指标升级为每 EventLoop 周期聚合，这样比固定采样更准确；但这属于增强项，不影响当前场景4链路修复闭环。
