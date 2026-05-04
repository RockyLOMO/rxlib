# SocksScene 场景4 UDP 链路性能与双向 RDNT 修改计划

## 1. 背景

`docs/test/SocksScene.md` 场景4链路：

```text
ShadowsocksClient(ip c)
  -> ShadowsocksServer(ip a)
  -> SocksProxyServer A(ip a)
  -> SocksProxyServer B(ip b)
  -> dest
```

本计划基于前一次 review 提到的 9 个问题重新收敛：

- 问题 5：端口跳跃资源线性增长、hopCount 上限、RPC group UDP channel 数等，按要求先忽略。
- 问题 6：`UdpPortHoppingConfig.spreadRedundantCopies` / 跨 relay 分散同一 RDNT 副本，按要求先忽略。
- 问题 4：不只需要 A -> B，多倍发送也必须覆盖 B -> A。
- 剩余 7 个问题都进入修改计划：问题 1、2、3、4、7、8、9。

`udp2raw` 正在重构的相关类继续不纳入本计划。

## 2. 本计划覆盖的 7 个修改项

| 编号 | 问题 | 是否修改 | 优先级 |
| --- | --- | --- | --- |
| 1 | `selectUdpRelayAddress()` 与 `recordUdpTraffic()` 每包重复执行 maintenance | 修改 | P1 |
| 2 | `UdpRedundantEncoder intervalMicros > 0` 每包多 scheduled task，EventLoop 压力大 | 修改 | P1 |
| 3 | `UdpRedundantDecoder` sender 去重窗口无容量上限，高基数 sender 会撑大 map | 修改 | P1 |
| 4 | RPC relay group 下 B -> A 回包方向可能没有 RDNT 多倍发送 | 修改，要求双向 | P0 |
| 7 | `SSUdpProxyHandler.OUTBOUND_POOL` 缺少容量/生命周期治理 | 修改 | P2 |
| 8 | UDP write pending limit 默认 256KB 偏小，且 SS inbound 共享 channel 缺 per-source soft limit | 修改 | P1 |
| 9 | UDP 指标 tags 存在高基数风险 | 修改 | P0 |

不处理项：

| 编号 | 问题 | 处理方式 |
| --- | --- | --- |
| 5 | 端口跳跃资源线性增长 | 先忽略 |
| 6 | spreadRedundantCopies / 跨 relay shared dedup | 先忽略 |

### 2.1 首次代码核验结果（主体问题已修复）

以下为 2026-05-02 首次代码核验记录。当时问题 1、2、3、4、7、8、9 仍存在，因此形成本计划主体修复项。关键证据：

- 问题 1：`SocksUdpUpstream.selectUdpRelayAddress(...)` 与 `recordUdpTraffic(...)` 仍各自执行 `maybeReplenishGroup / maybeExpandGroup / maybeHeartbeatGroup`，`SSUdpProxyHandler` 与 `SocksUdpRelayHandler` 主路径仍存在 select + record 双调用。
- 问题 2：`UdpRedundantEncoder intervalMicros > 0` 仍按每个冗余副本调用 `ctx.executor().schedule(...)`，未限制 delayed task 数。
- 问题 3：`UdpRedundantDecoder.windows` 仍是无容量上限的 `ConcurrentHashMap<InetSocketAddress, DeduplicationWindow>`，过期清理仍是全量 `removeIf`。
- 问题 4：`UdpRelayGroupManager.bindRelay(...)` 仍固定 `ATTR_REDUNDANT_CLIENT_PEER=false`，RPC relay group 的 B -> A 回包方向仍不会登记 A 的 sender 作为 RDNT peer。
- 问题 7：`SSUdpProxyHandler.OUTBOUND_POOL` 仍只有 close/idle 被动回收，没有全局容量和 per-source 新建限制。
- 问题 8：`Sockets.DEFAULT_UDP_WRITE_LIMIT_BYTES` 仍是 256KB，`SocketConfig` 还没有独立 UDP write limit，SS inbound 也没有 per-source pending soft limit。
- 问题 9：UDP 指标仍存在 `port/listenPort/key/relays/count` 等 tag，至少 `SocksProxyServer`、`SocksUdpRelayHandler`、`SSUdpProxyHandler`、`SocksUdpUpstream`、`Socks5UpstreamPoolManager` 需要统一收敛。

本次文档修正点：

- `udp2raw` 路径也存在 select + record 双调用，但本计划继续保持“不纳入 udp2raw 重构类”的边界，验收口径只覆盖场景4 SS/SOCKS UDP 主链路。
- `UdpRedundantEncoder` 明确为非 `@Sharable`，delayed copy pending 计数应保持每 channel/每 encoder 实例，不允许做成 static 全局计数。
- `SSUdpProxyHandler` per-source 计数不能只以 `InetSocketAddress` 为 key，必须包含 inbound channel 身份，避免多个 SS inbound 或源端口复用时相互污染。
- 指标 tag 收敛范围补充 `SocksUdpUpstream` 与 `Socks5UpstreamPoolManager`，否则问题 9 不能完整闭环。

### 2.2 当前修改进度（截至 2026-05-03）

主体 7 项已完成并通过定向验证；master 二次 review follow-up 的 4 项也已合并到本计划并完成修复。

| 编号 | 当前状态 | 落地说明 |
| --- | --- | --- |
| 1 | 已完成 | `SocksUdpUpstream.selectUdpRelayAddressAndRecord(...)` 合并选择、流量记录与 group maintenance；SS/SOCKS UDP 主链路不再每包两轮 maintenance。 |
| 2 | 已完成 | `UdpRedundantEncoder` 对 `intervalMicros > 0` 的 delayed copy 增加 pending 上限，超限降级为立即写。 |
| 3 | 已完成 | `UdpRedundantDecoder` 增加 sender window 上限和 bounded cleanup，window 满时 RDNT 包丢弃、非 RDNT 包透传。 |
| 4 | 已完成 | RPC relay group 下按配置登记 client sender 为 RDNT peer，A -> B 与 B -> A 双向具备 RDNT 条件。 |
| 7 | 已完成 | `SSUdpProxyHandler.OUTBOUND_POOL` 增加全局上限、告警阈值、per-source 新建限制和 close/bind fail/inbound close 回收。 |
| 8 | 已完成 | `SocketConfig` 增加 UDP write limit 与 per-source soft limit；SS inbound 回包写入前做 per-source pending 保护。 |
| 9 | 已完成 | UDP 指标 tag 收敛为低基数，不再携带 endpoint、动态端口、bytes、pool key 等高基数字段。 |
| F1 | 已完成 | `SocksUdpRelayHandler` 只在 client sender 首次确认或变化时登记 RDNT client peer，避免每包 `InetSocketAddress` 分配和 CHM put。 |
| F2 | 已完成 | `SocksConfig.udpRedundantTrackClientPeer` 显式控制标准 SOCKS5 fallback 下 B -> A RDNT，默认 false，避免普通 SOCKS5 client 被误打 RDNT。 |
| F3 | 已完成 | `UdpRedundantEncoder` / `UdpRedundantDecoder` 对 delayed copy、pending、duplicate/window 指标增加 1/64 采样，计数类指标按倍率补偿。 |
| F4 | 已完成 | `SSUdpProxyHandler.acquireOutboundChannel(...)` 对 `computeIfAbsent/openOutboundChannel` 抛异常增加 source count 兜底释放。 |

## 3. 总体原则

必须保持：

```text
每个 UDP payload：
  先选择一个 relay port
  再对这个 relay port 做 RDNT multiplier
```

不能变成：

```text
同一个 payload fan-out 到所有 hop port
```

因此本计划不会引入 `hopCount * multiplier` 的包量放大，也不会打开 `spreadRedundantCopies`。

## 4. 修改项 4：Proxy A <-> Proxy B 双向 RDNT

### 4.1 当前问题

A -> B 方向：

`SocksUdpUpstream.bindGroup(...)` 会把上游 relay 地址加入当前 outbound channel 的 redundant peer：

```java
for (InetSocketAddress relayAddr : relayAddresses) {
    UdpRelayAttributes.addRedundantPeer(channel, relayAddr);
}
```

`UdpRedundantEncoder.write(...)` 只有在：

```java
UdpRelayAttributes.shouldEncode(ctx.channel(), recipient)
```

为 true 时才加 RDNT 头并按倍率发送。所以 A -> B 已具备生效条件。

B -> A 方向：

标准 SOCKS5 `UDP_ASSOCIATE` 路径会根据 TCP control 原始地址与直接 peer 地址决定是否把真实 UDP sender 加入 redundant peer。

但 RPC relay group 路径中，`UdpRelayGroupManager.bindRelay(...)` 当前固定：

```java
UdpRelayAttributes.initRedundantPeers(relay);
relay.attr(UdpRelayAttributes.ATTR_REDUNDANT_CLIENT_PEER).set(Boolean.FALSE);
```

导致 B 侧 RPC relay 收到 A 的 UDP 包后，不会把 A 的 sender 加入 redundant peer。最终 B -> A 回包虽然可能经过 `UdpRedundantEncoder`，但 `shouldEncode(...)` 不命中，不会加 RDNT 头，也不会多倍发送。

### 4.2 目标

必须达成：

```text
A -> B：保持现有 RDNT 多倍发送效果。
B -> A：在场景4 chained / RPC relay group 路径下同样具备 RDNT 多倍发送效果。
```

兼容边界：

- RDNT 只允许作用于 rxlib 代理链路对端，不允许误打到普通 UDP dest。
- 如果没有开启 UDP redundant，不能额外维护 peer map，也不能添加 RDNT 头。
- `group.clientAddr == null` 时，允许首个真实 UDP sender 建立 peer；这符合 NAT A 场景。

### 4.3 实施方案

文件：

```text
rxlib/src/main/java/org/rx/net/socks/UdpRelayAttributes.java
```

新增统一判断：

```java
public static boolean shouldTrackClientAsRedundantPeer(SocksConfig config) {
    if (config == null) {
        return false;
    }
    return config.getUdpRedundantMultiplier() > 1
            || config.isUdpRedundantAdaptive()
            || config.hasUdpRedundantDestinationRules();
}
```

该判断必须与 `Sockets.addUdpOptimizationHandlers(...)` 中是否安装 RDNT handler 的条件保持一致，避免“未安装 encoder 但仍维护 peer map”的无效开销。

文件：

```text
rxlib/src/main/java/org/rx/net/socks/UdpRelayGroupManager.java
```

在 `bindRelay(...)` 中替换固定 false：

```java
boolean redundantClientPeer = UdpRelayAttributes.shouldTrackClientAsRedundantPeer(config);
if (redundantClientPeer) {
    UdpRelayAttributes.initRedundantPeers(relay);
    if (group.clientAddr != null) {
        UdpRelayAttributes.addRedundantPeer(relay, group.clientAddr);
    }
}
relay.attr(UdpRelayAttributes.ATTR_REDUNDANT_CLIENT_PEER).set(redundantClientPeer);
```

文件：

```text
rxlib/src/main/java/org/rx/net/socks/Socks5CommandRequestHandler.java
```

标准 UDP_ASSOCIATE 路径同步收敛，并去掉当前无冗余配置时仍初始化 peer map 的多余分配：

```java
final boolean redundantClientPeer = UdpRelayAttributes.shouldTrackClientAsRedundantPeer(config)
        && clientTcpAddr != null && tcpPeerAddr != null && !clientTcpAddr.equals(tcpPeerAddr);
if (redundantClientPeer) {
    UdpRelayAttributes.initRedundantPeers(udpFuture.channel());
}
udpFuture.channel().attr(UdpRelayAttributes.ATTR_REDUNDANT_CLIENT_PEER).set(redundantClientPeer);
```

如果后续确认本地普通 SOCKS5 client 场景也需要回程 RDNT，再考虑放宽为：

```java
final boolean redundantClientPeer = UdpRelayAttributes.shouldTrackClientAsRedundantPeer(config);
```

第一期建议保守，不直接放宽。

### 4.4 回程 decoder 确认

文件：

```text
rxlib/src/main/java/org/rx/net/socks/SSUdpProxyHandler.java
```

`ensureRelayResponseDecoder(...)` 对 `SocksUdpUpstream` 已安装：

```java
UdpRedundantDecoder
UdpCompressDecoder
```

因此 B -> A 回包加 RDNT 后，A/SS outbound channel 能先去重、剥头，再交给 `UdpBackendRelayHandler`。

## 5. 修改项 1：合并 UDP upstream 选择与流量记录的 maintenance

### 5.1 当前问题

`SocksUdpUpstream.selectUdpRelayAddress(...)` 中每包会执行：

```java
maybeReplenishGroup(channel, group);
maybeExpandGroup(channel, group);
maybeHeartbeatGroup(channel, group);
```

`recordUdpTraffic(...)` 里记录 bytes 后又执行同样三组检查。

在 `SSUdpProxyHandler.writePacketNow(...)` 中，构建 outbound packet 时会先间接调用 `selectUdpRelayAddress(...)`，写前又调用 `recordUdpTraffic(...)`。

高 PPS 下，相当于同一个 UDP 包触发两轮 maintenance：

```text
activeGroup 查询
System.currentTimeMillis()
atomic/cooldown 判断
replenish / expand / heartbeat 判断
```

同类 select + record 双调用在 `Udp2rawHandler` 中也存在；但 `udp2raw` 已声明不纳入本计划，因此第一期只修场景4 SS/SOCKS UDP 主链路，不把 `udp2raw` 计入本计划验收。

### 5.2 目标

场景4 SS/SOCKS UDP 主链路中，每个 UDP 包最多执行一轮 group maintenance。

### 5.3 实施方案

文件：

```text
rxlib/src/main/java/org/rx/net/socks/upstream/SocksUdpUpstream.java
```

新增一个合并接口，示例：

```java
public InetSocketAddress selectUdpRelayAddressAndRecord(Channel channel, int bytes) {
    SessionGroup group = activeGroup(channel);
    if (group == null) {
        return null;
    }
    InetSocketAddress relayAddress = group.selectRelayAddress();
    if (bytes > 0) {
        group.recordBytes(bytes);
    }
    maintainGroup(channel, group);
    return relayAddress;
}

private void maintainGroup(Channel channel, SessionGroup group) {
    maybeReplenishGroup(channel, group);
    maybeExpandGroup(channel, group);
    maybeHeartbeatGroup(channel, group);
}
```

保留旧方法兼容，但内部复用：

```java
public InetSocketAddress selectUdpRelayAddress(Channel channel) {
    return selectUdpRelayAddressAndRecord(channel, 0);
}

public void recordUdpTraffic(Channel channel, int bytes) {
    SessionGroup group = activeGroup(channel);
    if (group == null || bytes <= 0) {
        return;
    }
    group.recordBytes(bytes);
    maintainGroup(channel, group);
}
```

然后改调用方：

- `SSUdpProxyHandler.buildOutboundPacket(...)` / `writePacketNow(...)`：避免 select 后再 record，改为一次性传入 `trafficBytes`。
- `SocksUdpRelayHandler.writeClientPacket(...)`：同样避免 select + record 双调用。
- `Udp2rawHandler` 本期不改，后续 udp2raw 重构时用同一 API 收敛。

注意：如果 packet 构造前还不知道最终 bytes，可以先按 `payload.readableBytes() + socks5 header length` 估算；差异只影响自适应扩容阈值，不影响正确性。

## 6. 修改项 2：优化 `UdpRedundantEncoder intervalMicros` 调度压力

### 6.1 当前问题

`UdpRedundantEncoder` 在 `intervalMicros > 0` 时，每个原始包的每个冗余副本都会：

```java
ctx.executor().schedule(...)
ctx.flush()
```

`multiplier=3` 时，每包多 2 个 scheduled task。高 PPS 场景下 EventLoop 会被大量定时任务占用，反而增加延迟抖动。

### 6.2 目标

- 默认不制造大量 scheduled task。
- 保留 interval 语义，但需要限流、合批或降级。
- 不改变 `intervalMicros=0` 的快速路径。

### 6.3 实施方案

文件：

```text
rxlib/src/main/java/org/rx/net/socks/UdpRedundantEncoder.java
```

第一期建议做轻量修复：

1. 新增每 channel pending redundant scheduled task 计数：

```java
private final AtomicInteger pendingDelayedCopies = new AtomicInteger();
private static final int DEFAULT_MAX_PENDING_DELAYED_COPIES = 4096;
```

`UdpRedundantEncoder` 当前明确不可 `@Sharable`，因此该字段是每 channel 级别；不要改成 static 全局计数，否则会让不同 UDP channel 互相影响。

2. `intervalMicros > 0` 且 pending 超限时降级为立即写：

```java
if (pendingDelayedCopies.incrementAndGet() > maxPendingDelayedCopies) {
    pendingDelayedCopies.decrementAndGet();
    writeRedundantCopy(ctx, seqId, copy, recipient);
    continue;
}
ctx.executor().schedule(() -> {
    try {
        writeRedundantCopy(ctx, seqId, copy, recipient);
        ctx.flush();
    } finally {
        pendingDelayedCopies.decrementAndGet();
    }
}, delayMicros, TimeUnit.MICROSECONDS);
```

`schedule(...)` 抛异常时必须释放本次 `copy` 并回退 pending；延迟任务执行体必须用 `finally` 回退 pending，避免 handler close 或写异常后计数泄漏。

3. 默认建议配置：

```text
udpRedundant.intervalMicros=0
```

4. 后续如果需要更进一步，再做 EventLoop 级别批量 flush 队列，不在第一期复杂化。

### 6.4 指标

新增低基数指标：

```text
udp.redundant.delayed.copy.count{result=scheduled|inline|drop}
udp.redundant.delayed.pending.count
```

不能带 recipient、seqId、bytes。

## 7. 修改项 3：限制 `UdpRedundantDecoder` sender 去重窗口容量

### 7.1 当前问题

`UdpRedundantDecoder` 当前用：

```java
ConcurrentHashMap<InetSocketAddress, DeduplicationWindow> windows
```

每个 sender 一个窗口，只有每 1024 包触发一次过期清理。

公网 UDP 被扫或被伪造源攻击时，sender 高基数会撑大 map；`cleanupStaleWindows()` 在 EventLoop 上 `removeIf` 扫 map，也会造成周期性卡顿。

### 7.2 目标

- 限制每 channel sender window 数量。
- 清理不能在 EventLoop 上产生长时间 stop-the-world 式遍历。
- 不影响正常 A <-> B 少量 sender 的去重效果。

### 7.3 实施方案

文件：

```text
rxlib/src/main/java/org/rx/net/socks/UdpRedundantDecoder.java
```

第一期建议：

1. 增加容量上限：

```java
private static final int MAX_WINDOWS = 4096;
```

2. `computeIfAbsent` 前先检查容量：

```java
DeduplicationWindow window = windows.get(sender);
if (window == null) {
    if (windows.size() >= MAX_WINDOWS) {
        cleanupStaleWindows(128); // bounded cleanup
        if (windows.size() >= MAX_WINDOWS) {
            record drop / passthrough policy
            return;
        }
    }
    window = windows.computeIfAbsent(sender, k -> new DeduplicationWindow());
}
```

3. 把全量清理改为 bounded cleanup，单次最多扫描/删除固定数量，避免 EventLoop 长尾：

```java
private void cleanupStaleWindows(int maxScan) { ... }
```

4. 对于超过上限的新 sender，建议策略：

```text
如果包带 RDNT magic：drop，并记录 decoder-window-full。
如果包不带 RDNT magic：继续 passthrough。
```

原因：带 RDNT 的包如果无法去重，直接透传可能造成重复包。

### 7.4 指标

```text
udp.redundant.decoder.window.count
udp.redundant.decoder.drop.count{reason=window-full|duplicate}
```

## 8. 修改项 7：治理 `SSUdpProxyHandler.OUTBOUND_POOL`

### 8.1 当前问题

`SSUdpProxyHandler.OUTBOUND_POOL` 是 static 全局：

```java
static final ConcurrentHashMap<OutboundPoolKey, ChannelFuture> OUTBOUND_POOL
```

当前依赖 inbound close、outbound close、idle handler 回收。正常场景可以，但 source/destination churn 很高时，缺少容量上限和主动治理。

### 8.2 目标

- 防止异常 churn 导致全局 pool 持续膨胀。
- 不误杀正常活跃 UDP 会话。
- 先软治理，后硬驱逐。

### 8.3 实施方案

文件：

```text
rxlib/src/main/java/org/rx/net/socks/SSUdpProxyHandler.java
```

新增配置建议放在 `ShadowsocksConfig`：

```java
private int udpOutboundPoolMaxSize = 8192;
private int udpOutboundPoolWarnSize = 4096;
private int udpOutboundPoolMaxPerSource = 256;
```

第一期逻辑：

1. `acquireOutboundChannel(...)` 前判断全局 size：

```java
if (OUTBOUND_POOL.size() >= config.getUdpOutboundPoolMaxSize()) {
    recordUdpDrop("outbound-pool-full", ...);
    return failedFuture / null;
}
```

2. 可选 per-source 软限制：

维护低成本计数：

```java
ConcurrentMap<OutboundSourceKey, AtomicInteger> OUTBOUND_POOL_SOURCE_COUNTS
```

`OutboundSourceKey` 至少包含 inbound channel 身份和 `srcEp`，不能只用 `InetSocketAddress`，否则多个 SS inbound 或 NAT 源端口复用会互相污染。open 成功 +1，close/remove -1。

3. 超过 per-source 限制时只拒绝新建，不关闭已有 active channel。

4. channel close、inbound close、bind fail 都必须回收 source count，避免计数泄漏；建议封装一次性 release 方法，防止多个 listener 重复扣减。

### 8.4 指标

```text
ss.udp.outbound.pool.size
ss.udp.outbound.pool.open.count{result=success|fail|full|per-source-full}
ss.udp.outbound.pool.close.count{reason=close|inbound-close|bind-fail|idle}
```

注意 tags 不带 source。

## 9. 修改项 8：UDP write limit 配置化 + SS inbound per-source soft limit

### 9.1 当前问题

`Sockets.DEFAULT_UDP_WRITE_LIMIT_BYTES = 256 * 1024`。

开启 RDNT 后，短突发写入会按 multiplier 放大，256KB 对高 PPS 或大包场景可能偏小。

另外 SS inbound 是共享 UDP channel，一个 source 把 pending 撑满后，可能影响其他 source 的回包写入。

### 9.2 目标

- UDP write pending limit 独立配置，不完全依赖 TCP WBM。
- SS inbound 增加 per-source soft limit，避免单源挤占全局 channel。
- 保持超限即丢，不做 UDP 传输层背压。

### 9.3 实施方案

文件：

```text
rxlib/src/main/java/org/rx/net/SocketConfig.java
rxlib/src/main/java/org/rx/net/Sockets.java
rxlib/src/main/java/org/rx/net/socks/SSUdpProxyHandler.java
```

新增配置：

```java
private int udpWriteLimitBytes = 1024 * 1024;
private int udpWritePerSourceLimitBytes = 256 * 1024;
```

`Sockets.writeUdp(...)` 优先使用 config：

```java
int limit = config != null && config.getUdpWriteLimitBytes() > 0
        ? config.getUdpWriteLimitBytes()
        : DEFAULT_UDP_WRITE_LIMIT_BYTES;
```

SS inbound per-source：

```java
AttributeKey<ConcurrentMap<InetSocketAddress, AtomicInteger>> ATTR_UDP_PENDING_WRITE_BYTES_BY_SOURCE
```

在 `UdpBackendRelayHandler` 写回 `binding.inbound` 前：

```java
reserveSourcePending(binding.inbound, srcEp, bytes, perSourceLimit)
```

write listener 回退 per-source pending；早期 drop、构造异常、`Sockets.writeUdp` 拒绝时也必须回退。全局 `Sockets.writeUdp` 仍保留，per-source 是额外保护。

### 9.4 默认建议

```text
udpWriteLimitBytes = 1MB
udpWritePerSourceLimitBytes = 256KB
```

弱 VPS 或游戏优先可先用：

```text
udpWriteLimitBytes = 512KB
udpWritePerSourceLimitBytes = 128KB
```

## 10. 修改项 9：收敛 UDP 指标 tags 高基数

### 10.1 当前问题

之前 review 已指出，UDP drop/write/route 类指标如果把：

```text
source
sender
recipient
destination
relay
client
port
bytes
pendingBytes
limitBytes
key
```

放入 metric tags，会导致每包或每 endpoint 生成新 series，生产环境会撑爆指标存储。

### 10.2 目标

- 指标 tags 只保留低基数字段。
- endpoint、port、bytes 进入日志，不进入 metrics tag。
- 新增指标必须先过“低基数检查”。

### 10.3 低基数 tag 规范

允许：

```text
component=socks|ss|udp-redundant
path=frontend|backend|relay|rpc-group
flow=to-client|to-upstream
result=success|fail|drop|encoded|plain|duplicate
reason=unwritable|pending-overlimit|window-full|unexpected-client|invalid-header|...
mode=rpc|socks5|direct
```

禁止：

```text
source=1.2.3.4:xxx
sender=...
recipient=...
destination=...
relay=...
client=...
port=动态端口
bytes=...
pendingBytes=...
key=包含上游地址/端口的动态 key
```

`listenPort`、relay port、port hopping 端口也按动态端口处理，不进入 UDP metric tag；如需定位具体端点，只进入日志。

### 10.4 实施方案

文件范围：

```text
rxlib/src/main/java/org/rx/net/Sockets.java
rxlib/src/main/java/org/rx/net/socks/SSUdpProxyHandler.java
rxlib/src/main/java/org/rx/net/socks/SocksUdpRelayHandler.java
rxlib/src/main/java/org/rx/net/socks/SocksProxyServer.java
rxlib/src/main/java/org/rx/net/socks/upstream/SocksUdpUpstream.java
rxlib/src/main/java/org/rx/net/socks/Socks5UpstreamPoolManager.java
rxlib/src/main/java/org/rx/net/socks/UdpRelayGroupManager.java
rxlib/src/main/java/org/rx/net/socks/UdpRedundantEncoder.java
rxlib/src/main/java/org/rx/net/socks/UdpRedundantDecoder.java
```

建议新增统一方法：

```java
static String udpMetricTags(String component, String path, String flow, String result, String reason) {
    // 只拼低基数字段，null 跳过
}
```

endpoint 信息只打日志：

```java
log.warn("UDP drop reason={} sender={} recipient={} bytes={} pending={} limit={}", ...)
```

## 11. 实施顺序

建议按以下顺序提交，降低互相影响：

### PR 1：指标 tag 收敛，先防止新改动扩大高基数

覆盖问题 9。

- 新增低基数 tag helper。
- 替换现有高基数 UDP metric tags。
- endpoint 信息转移到日志。
- 覆盖 `SocksUdpUpstream` / `Socks5UpstreamPoolManager` 中的 UDP lease、porthop、relay group 指标 tag。

### PR 2：双向 RDNT 修复

覆盖问题 4。

- `shouldTrackClientAsRedundantPeer(config)`。
- RPC relay group B 侧允许登记 A sender。
- 标准 UDP_ASSOCIATE 路径判断收敛。
- 补双向 RDNT 测试。

### PR 3：UDP upstream maintenance 合并

覆盖问题 1。

- 新增 `selectUdpRelayAddressAndRecord(...)` 或同等 API。
- 修改 SS 与 SOCKS UDP 写路径，避免每包两轮 maintenance。

### PR 4：RDNT encoder/decoder 热路径保护

覆盖问题 2、3。

- Encoder delayed copies 限制与降级。
- Decoder sender window 上限与 bounded cleanup。

### PR 5：UDP write limit 与 outbound pool 治理

覆盖问题 7、8。

- UDP write limit 配置化。
- SS inbound per-source pending limit。
- OUTBOUND_POOL max size / per-source max / 回收计数。

## 12. 测试计划

### 12.1 功能测试

新增或调整：

```text
SocksProxyServerIntegrationTest#shadowsocksUdpRelay_socks5_chained_withRpcRelayGroup_bidirectionalUdpRedundant_e2e
SocksProxyServerIntegrationTest#shadowsocksUdpRelay_socks5_chained_withUdpRedundantOnProxyAB_bidirectional_e2e
UdpRedundantTest#rpcRelayGroupAddsClientSenderAsRedundantPeerWhenEnabled
UdpRedundantTest#rpcRelayGroupDoesNotTrackClientPeerWhenRedundantDisabled
UdpRedundantTest#decoderDropsRdntWhenWindowFull
UdpRedundantTest#encoderDelayedCopiesFallbackToInlineWhenPendingLimitReached
SocketsTest#writeUdpUsesConfiguredUdpWriteLimit
SSUdpProxyHandlerTest#perSourcePendingLimitDropsOnlyOffendingSource
```

### 12.2 回归命令

```bash
mvn -pl rxlib test \
  "-Dtest=UdpRedundantTest,SocksProxyServerIntegrationTest#shadowsocksUdpRelay_socks5_chained_withUdpRedundantOnProxyA_e2e+shadowsocksUdpRelay_socks5_chained_withUdpCompressAndRedundantOnProxyAB_e2e+shadowsocksUdpRelay_socks5_chained_withPortHopping_e2e" \
  "-Dmaven.test.skip=false"
```

新增测试后追加：

```bash
mvn -pl rxlib test \
  "-Dtest=SocksProxyServerIntegrationTest#shadowsocksUdpRelay_socks5_chained_withRpcRelayGroup_bidirectionalUdpRedundant_e2e+shadowsocksUdpRelay_socks5_chained_withUdpRedundantOnProxyAB_bidirectional_e2e,SSUdpProxyHandlerTest,SocketsTest" \
  "-Dmaven.test.skip=false"
```

建议额外跑：

```bash
mvn -pl rxlib test \
  "-Dtest=UdpRedundantTest,SocksProxyServerIntegrationTest" \
  "-Dio.netty.leakDetection.level=PARANOID" \
  "-Dmaven.test.skip=false"
```

## 13. 验收标准

### 13.1 双向 RDNT

必须满足：

```text
场景4 chained + UDP redundant 开启时：
  A -> B 有 RDNT 多倍发送
  B -> A 有 RDNT 多倍发送
  SS client 收到正常 Shadowsocks UDP 回包，不包含 RDNT 头
  普通 UDP dest 不收到 RDNT 头
```

### 13.2 性能与稳定性

必须满足：

```text
本计划覆盖的 SS/SOCKS UDP 主链路每包不再触发两轮 SocksUdpUpstream maintenance。
intervalMicros > 0 时 delayed scheduled task 有上限，超限降级或受控丢弃。
UdpRedundantDecoder sender window 有上限，异常 sender 高基数不会无限增长。
SS outbound pool 有全局和 per-source 新建限制，close 后计数回收。
UDP write pending limit 可配置，SS inbound 单源不能挤占全局。
UDP metrics 不产生 endpoint/bytes/port/key 高基数 series。
```

### 13.3 不应发生

```text
不出现 hopCount * multiplier 的同包放大。
不打开 spreadRedundantCopies。
不引入跨 relay shared dedup。
不修改 udp2raw 重构中的类。
```

## 14. 推荐默认配置

第一期生产建议：

```properties
udpRedundant.multiplier=2
udpRedundant.intervalMicros=0

udpWriteLimitBytes=1048576
udpWritePerSourceLimitBytes=262144

udpOutboundPoolMaxSize=8192
udpOutboundPoolWarnSize=4096
udpOutboundPoolMaxPerSource=256
```

对于弱 VPS 或游戏优先链路，可保守：

```properties
udpWriteLimitBytes=524288
udpWritePerSourceLimitBytes=131072
udpOutboundPoolMaxSize=4096
udpOutboundPoolMaxPerSource=128
```

新增 follow-up 配置：

```properties
# 仅在 rxlib A<->B 标准 SOCKS5 fallback 链路的 B 侧开启
udpRedundantTrackClientPeer=false
```

场景4如果需要标准 SOCKS5 fallback 下也保证 B -> A RDNT：

```properties
# Proxy B
udpRedundantTrackClientPeer=true
```

## 15. master 二次 review follow-up 合并

`docs/plan/SocksScene4BidirectionalUdpRedundant-followup.md` 中补充的 4 项已合并到本计划，实施边界如下：

| 编号 | 问题 | 修复状态 | 关键变更 |
| --- | --- | --- | --- |
| F1 | B 侧双向 RDNT 后每包重复 `addRedundantPeer` | 已修复 | 新增 `UdpRelayAttributes.addRedundantClientPeerIfChanged(...)` 和 `ATTR_REDUNDANT_CLIENT_ADDR`，`SocksUdpRelayHandler` 只在 client sender 首次确认或变化时更新 peer。 |
| F2 | 标准 SOCKS5 fallback 下 B -> A RDNT 仍依赖 `clientTcpAddr != tcpPeerAddr` 启发式 | 已修复 | `SocksConfig` 新增 `udpRedundantTrackClientPeer`；`Socks5CommandRequestHandler.shouldTrackRedundantClientPeer(...)` 支持显式开启，同时默认保持 false。 |
| F3 | RDNT duplicate/delayed copy 指标低基数但仍高频 record | 已修复 | Encoder/Decoder 内部增加 1/64 固定采样；计数类指标用采样倍率补偿，pending/window gauge 记录采样时的当前值。 |
| F4 | `OUTBOUND_POOL` source count 在 open 抛异常时可能泄漏 | 已修复 | `acquireOutboundChannel(...)` 包裹 `computeIfAbsent`，异常路径调用 `releaseOutboundSource(...)` 并记录 `result=error`。 |

新增/调整测试：

```text
SocksUdpRelayHandlerTest#redundantClientPeerAddedOnlyWhenClientSenderChanges
Socks5CommandRequestHandlerTest#redundantClientPeerTrackingRequiresExplicitFallbackFlagForSamePeer
Socks5CommandRequestHandlerTest#redundantClientPeerTrackingKeepsExistingDifferentPeerHeuristic
SSUdpProxyHandlerTest#outboundPoolSourceCountReleasedWhenOpenThrows
UdpRedundantTest#testEncoderDelayedCopyMetricsAreSampled
UdpRedundantTest#testDecoderDuplicateMetricsAreSampled
```

本次 follow-up 验证命令：

```bash
mvn -pl rxlib test \
  "-Dtest=UdpRedundantTest,SSUdpProxyHandlerTest,SocksUdpRelayHandlerTest,Socks5CommandRequestHandlerTest" \
  "-Dmaven.test.skip=false"
```

验证结果：54 个测试通过，0 failure，0 error。

相关集成回归命令：

```bash
mvn -pl rxlib test \
  "-Dtest=SocksProxyServerIntegrationTest#socks5UdpRelay_chained_withUdpRedundant_plainReplyToClient_e2e+shadowsocksUdpRelay_socks5_chained_withUdpRedundantOnProxyA_e2e+shadowsocksUdpRelay_socks5_chained_withUdpCompressAndRedundantOnProxyAB_e2e+shadowsocksUdpRelay_socks5_chained_withRpcRelayGroup_e2e" \
  "-Dmaven.test.skip=false"
```

验证结果：4 个集成测试通过，0 failure，0 error。

## 16. 最终结论

本计划不是单点修复 B -> A RDNT，而是覆盖 review 中除问题 5、问题 6 以外的 7 个修改项：

```text
1. 合并 SocksUdpUpstream 每包重复 maintenance
2. 限制 RDNT interval 延迟副本调度压力
3. 限制 RDNT decoder sender window 高基数风险
4. 修复 A <-> B 双向 RDNT 多倍发送
7. 治理 SS outbound pool 容量与生命周期
8. UDP write limit 配置化 + SS inbound per-source soft limit
9. 收敛 UDP metrics 高基数 tags
F1. 避免 B 侧每包重复登记 RDNT client peer
F2. 标准 SOCKS5 fallback 下通过显式配置支持 B -> A RDNT
F3. RDNT 高频 metrics 采样
F4. OUTBOUND_POOL source count 异常路径兜底释放
```

其中问题 4 是功能正确性 P0，问题 9 是生产可观测性 P0；截至 2026-05-03，主体 7 项与 follow-up 4 项均已完成定向验证。

---

# 附录：场景4 UDP 链路最终复查结果（2026-05-03）

本附录记录对本文档所列主体 7 项和 follow-up 4 项的 master 二次复查详细结果。

## 复查范围

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

## 复查结论

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

## 关键核验点

### F1：避免每包重复登记 RDNT peer

`SocksUdpRelayHandler.handleClientPacket(...)` 现在通过 `clientChanged` 和 `ATTR_REDUNDANT_CLIENT_ADDR` 判断，只在 sender 首次确认或变化时调用：

```java
UdpRelayAttributes.addRedundantClientPeerIfChanged(relay, sender);
```

`UdpRelayAttributes.addRedundantClientPeerIfChanged(...)` 会先比较 normalized sender 与当前 `ATTR_REDUNDANT_CLIENT_ADDR`，相同则直接 return。

对应测试：

```text
SocksUdpRelayHandlerTest#redundantClientPeerAddedOnlyWhenClientSenderChanges
```

### F2：标准 SOCKS5 fallback 显式支持 B -> A RDNT

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

### F3：高频 RDNT metrics 采样

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

### F4：OUTBOUND_POOL source count 异常路径释放

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

## 保留边界

继续保持：

```text
不处理问题 5：端口跳跃资源线性增长。
不处理问题 6：spreadRedundantCopies / 跨 relay shared dedup。
不改 udp2raw 重构中的类。
不引入 hopCount * multiplier 的同包放大。
```

## 建议后续

当前没有必须阻断的问题。后续如果继续优化，可以考虑把 RDNT 采样指标升级为每 EventLoop 周期聚合，这样比固定采样更准确；但这属于增强项，不影响当前场景4链路修复闭环。
