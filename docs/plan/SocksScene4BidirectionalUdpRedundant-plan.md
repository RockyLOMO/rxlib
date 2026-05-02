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

标准 UDP_ASSOCIATE 路径同步收敛：

```java
final boolean redundantClientPeer = UdpRelayAttributes.shouldTrackClientAsRedundantPeer(config)
        && clientTcpAddr != null && tcpPeerAddr != null && !clientTcpAddr.equals(tcpPeerAddr);
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

### 5.2 目标

每个 UDP 包最多执行一轮 group maintenance。

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
ConcurrentMap<InetSocketAddress, AtomicInteger> OUTBOUND_POOL_SOURCE_COUNTS
```

open 成功 +1，close/remove -1。

3. 超过 per-source 限制时只拒绝新建，不关闭已有 active channel。

4. channel close、inbound close、bind fail 都必须回收 source count，避免计数泄漏。

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

write listener 回退 per-source pending。全局 `Sockets.writeUdp` 仍保留，per-source 是额外保护。

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
```

### 10.4 实施方案

文件范围：

```text
rxlib/src/main/java/org/rx/net/Sockets.java
rxlib/src/main/java/org/rx/net/socks/SSUdpProxyHandler.java
rxlib/src/main/java/org/rx/net/socks/SocksUdpRelayHandler.java
rxlib/src/main/java/org/rx/net/socks/SocksProxyServer.java
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
SocksProxyServerIntegrationTest#shadowsocksUdpRelay_socks5_chained_withUdpRedundantOnProxyAB_e2e
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
  "-Dtest=SocksProxyServerIntegrationTest#shadowsocksUdpRelay_socks5_chained_withRpcRelayGroup_bidirectionalUdpRedundant_e2e,SSUdpProxyHandlerTest,SocketsTest" \
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
每包不再触发两轮 SocksUdpUpstream maintenance。
intervalMicros > 0 时 delayed scheduled task 有上限，超限降级或受控丢弃。
UdpRedundantDecoder sender window 有上限，异常 sender 高基数不会无限增长。
SS outbound pool 有全局和 per-source 新建限制，close 后计数回收。
UDP write pending limit 可配置，SS inbound 单源不能挤占全局。
UDP metrics 不产生 endpoint/bytes/port 高基数 series。
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

## 15. 最终结论

本计划不是单点修复 B -> A RDNT，而是覆盖 review 中除问题 5、问题 6 以外的 7 个修改项：

```text
1. 合并 SocksUdpUpstream 每包重复 maintenance
2. 限制 RDNT interval 延迟副本调度压力
3. 限制 RDNT decoder sender window 高基数风险
4. 修复 A <-> B 双向 RDNT 多倍发送
7. 治理 SS outbound pool 容量与生命周期
8. UDP write limit 配置化 + SS inbound per-source soft limit
9. 收敛 UDP metrics 高基数 tags
```

其中问题 4 是功能正确性 P0，问题 9 是生产可观测性 P0，建议优先进入实现。
