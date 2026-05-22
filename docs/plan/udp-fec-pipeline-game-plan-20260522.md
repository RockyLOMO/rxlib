# 游戏 UDP：自研轻量 FEC + 可选多倍发包 Pipeline 技术方案

> 生成日期：2026-05-22  
> 目标模块：`org.rx.net` / `org.rx.net.socks` / 建议新增 `org.rx.net.udp`  
> 目标场景：游戏 UDP、SOCKS UDP、udp2raw UDP、remoting UDP 的公网链路抗丢包优化

## 1. 背景与目标

当前目标不是实现一个独立的 `FecUdpClient`，而是实现一个可插拔的 Netty UDP pipeline 能力：

```text
Inbound:
    DatagramPacket
        -> UdpProtectDecoder
        -> business handler

Outbound:
    business handler
        -> UdpProtectEncoder
        -> DatagramPacket
```

核心目标：

```text
1. 面向游戏 UDP，优先低延迟，不追求大吞吐。
2. 提供轻量 XOR FEC，解决轻微随机丢包。
3. 提供可选多倍发包，解决短时突发丢包/抖动。
4. 不做 Client 封装，必须以 ChannelHandler / pipeline 方式接入。
5. 能接入 SOCKS UDP、udp2raw、remoting、普通 UDP bootstrap。
6. 能和现有 UDP MTU guard、背压、限速、统计机制协作。
```

非目标：

```text
1. 第一版不做完整 KCP。
2. 第一版不做 Reed-Solomon 多 parity FEC。
3. 第一版不保证 UDP 严格有序交付。
4. 第一版不把保护协议暴露给普通游戏服务器，只用于自有两端之间的公网链路。
```

## 2. 现有实现评估

### 2.1 `FecUdpClient`：建议删除

现有文件：

```text
rxlib/src/main/java/org/rx/net/FecUdpClient.java
```

问题：

```text
1. 它直接创建 Bootstrap，固定绑定端口并安装 FecEncoder/FecDecoder。
2. 它把 FEC 能力和客户端封装绑定，不能作为 SOCKS UDP / udp2raw / remoting 的通用 pipeline 能力。
3. 它不适合统一接入 Sockets.udpBootstrap。
4. 它容易导致后续每个场景都重复封装一个 client。
```

结论：

```text
删除 FecUdpClient，或者临时迁移到 test/demo，但不应作为生产 API。
```

## 2.2 `FecEncoder/FecDecoder/FecPacket/FecConfig`：不建议直接修补使用

现有文件：

```text
rxlib/src/main/java/org/rx/net/FecConfig.java
rxlib/src/main/java/org/rx/net/FecEncoder.java
rxlib/src/main/java/org/rx/net/FecDecoder.java
rxlib/src/main/java/org/rx/net/FecPacket.java
```

主要问题：

### 2.2.1 Encoder 只有一个全局 group，无法支持多 peer

现有编码器状态类似：

```java
private byte[][] groupBuffers;
private byte groupIdx;
private int currentGroupId;
private InetSocketAddress lastRemote;
```

如果同一个 UDP channel 上交错发往多个 remote：

```text
packet1 -> peerA
packet2 -> peerB
packet3 -> peerA
parity  -> lastRemote，可能是 peerB
```

这会导致 parity 发错对象，也会把不同 peer 的 payload 混进同一个 FEC group。SOCKS UDP、udp2raw、reuseport 入口都可能出现这个问题。

### 2.2.2 Decoder 只按 groupId 分组，无法隔离 sender/session

现有 decoder 只维护：

```java
private final Map<Integer, FecGroup> groups = new ConcurrentHashMap<>();
```

正确做法应该至少按以下维度隔离：

```text
sender + sessionId + groupId
```

否则多个 sender 的相同 groupId 会相互污染。

### 2.2.3 变长 UDP payload 恢复不正确

现有 `FecPacket` header 只有：

```text
Magic + SeqNo + GroupId + GroupIdx + IsParity + Payload
```

没有原始 payload 长度。XOR parity 恢复变长包时，只能恢复到 maxPayloadLen，尾部会多出 0 字节，业务层会收到脏包。

游戏 UDP payload 长度通常不固定，所以必须在 FEC block 中包含原始长度。

### 2.2.4 byte[] 拷贝较多，不适合高频 UDP

当前实现会把 `ByteBuf` 拷贝成 `byte[]`，恢复后再 `Unpooled.wrappedBuffer`。这会增加堆内存分配、GC 和延迟抖动。

游戏 UDP 高频小包场景下，虽然单包小，但长时间高并发会放大 GC 抖动。

结论：

```text
现有 FEC 类不建议修修补补，应重写为新的 pipeline 版实现。
```

## 2.3 `UdpRedundantEncoder/Decoder/Stats`：保留并迁移思想

现有文件：

```text
rxlib/src/main/java/org/rx/net/socks/UdpRedundantEncoder.java
rxlib/src/main/java/org/rx/net/socks/UdpRedundantDecoder.java
rxlib/src/main/java/org/rx/net/socks/UdpRedundantStats.java
```

这部分比当前 FEC 更接近生产可用：

```text
1. 已经是 Netty pipeline handler。
2. 支持固定倍率、自适应倍率、分目的地倍率。
3. 支持冗余副本间隔发送。
4. 支持 pending delayed copies 上限。
5. decoder 已经按 sender 地址维护去重窗口。
6. 使用 directBuffer + retainedDuplicate，避免 composite buffer 在 epoll/sendmmsg 下的问题。
```

建议：

```text
1. 短期保留 UdpRedundant*，不要删除。
2. 新 UdpProtectEncoder/Decoder 稳定前，旧类仍可继续服务现有 socks redundant 场景。
3. 新实现可以抽取它的 DeduplicationWindow、adaptive stats、delayed copy 逻辑。
4. 最终把 redundant 能力合并到 UdpProtect pipeline，减少双层 header。
```

## 3. 总体设计

建议新增通用 UDP 保护模块：

```text
rxlib/src/main/java/org/rx/net/udp/
```

核心类：

```text
org.rx.net.udp.UdpProtectConfig
org.rx.net.udp.UdpProtect
org.rx.net.udp.UdpProtectEncoder
org.rx.net.udp.UdpProtectDecoder
org.rx.net.udp.UdpProtectHeader
org.rx.net.udp.UdpProtectPacket
org.rx.net.udp.UdpProtectPolicy
org.rx.net.udp.UdpProtectStats
org.rx.net.udp.UdpProtectAttributes
org.rx.net.udp.UdpProtectFlowIdResolver
org.rx.net.udp.FecGroupKey
org.rx.net.udp.FecEncodeGroup
org.rx.net.udp.FecDecodeGroup
org.rx.net.udp.UdpDedupWindow
```

推荐 pipeline：

```java
UdpProtectConfig cfg = UdpProtectConfig.gameLowLatency();
UdpProtect.install(ch.pipeline(), cfg);
```

安装后 pipeline 形态：

```text
Inbound:
    udpProtectDecoder
    businessHandler

Outbound:
    businessHandler
    udpProtectEncoder
```

注意 Netty outbound 是从 tail 往 head 传播，所以安装顺序要统一由 `UdpProtect.install` 控制，避免业务方加错顺序。

## 4. 协议设计

不要沿用旧 `FecPacket`。新的 header 需要同时支持：

```text
1. 普通受保护 DATA 包
2. FEC DATA shard
3. FEC PARITY shard
4. 多倍发包副本去重
5. 后续可扩展 ACK/统计/压缩标记
```

建议 header：

```text
+--------+--------+--------+--------+
| magic  | ver    | flags  | hdrLen |
| 2B     | 1B     | 1B     | 1B     |
+--------+--------+--------+--------+
| codec  | shardK | shardP | shardIdx |
| 1B     | 1B     | 1B     | 1B       |
+--------+--------+--------+--------+
| sessionId / flowId       |
| 4B                       |
+--------------------------+
| seq                       |
| 4B                       |
+--------------------------+
| groupId                   |
| 4B                       |
+--------------------------+
| payloadLen                |
| 2B                       |
+--------------------------+
| reserved / crc16 optional |
| 2B                       |
+--------------------------+
| payload...                |
+--------------------------+
```

字段说明：

| 字段 | 含义 |
|---|---|
| `magic` | 固定魔数，例如 `0x5258`，用于识别 rx UDP protect 包 |
| `ver` | 协议版本，第一版为 `1` |
| `flags` | DATA / PARITY / REDUNDANT / RESERVED |
| `hdrLen` | header 长度，便于后续扩展 |
| `codec` | `0=none`, `1=xor`, 后续可扩展 `2=reed-solomon` |
| `shardK` | FEC 数据 shard 数量 |
| `shardP` | FEC parity shard 数量，XOR 第一版固定为 1 |
| `shardIdx` | `0..K-1` 为 data，`K..K+P-1` 为 parity |
| `sessionId` | flow/session 标识，避免多 peer groupId 冲突 |
| `seq` | 单调递增包序号，用于去重和统计 |
| `groupId` | FEC 分组号 |
| `payloadLen` | 原始 payload 长度，用于变长包恢复 |

## 5. FEC 设计

### 5.1 第一版使用 XOR FEC

第一版只实现 XOR：

```text
K 个 data shard 生成 1 个 parity shard。
任意一个 group 内最多丢 1 个 data shard 时可恢复。
```

默认游戏低延迟参数：

```text
fecDataShards = 3
fecParityShards = 1
fecFlushTimeoutMs = 5
staleGroupTimeoutMs = 300
maxProtectedPayload = 1200
```

原因：

```text
1. 3:1 额外开销约 33%，可接受。
2. 组大小小，等待时间短。
3. 游戏 UDP 更关心延迟，不适合 10:3 这种大组。
```

### 5.2 必须按 peer/session 分组

Encoder 不能只有一个全局 group。应按 peer 或 flow 维护状态：

```java
class PeerFecState {
    int nextSeq;
    int nextGroupId;
    FecEncodeGroup currentGroup;
    ScheduledFuture<?> flushFuture;
}
```

默认 key：

```text
recipient normalized address
```

SOCKS UDP / udp2raw 场景可以自定义：

```java
public interface UdpProtectFlowIdResolver {
    int resolve(Channel channel, DatagramPacket packet);
}
```

推荐 flowId：

```text
SOCKS UDP: hash(clientSourceEndpoint + targetEndpoint)
udp2raw:   hash(clientSourceEndpoint)
remoting:  hash(remoteEndpoint)
普通 UDP:  hash(recipient)
```

### 5.3 变长包恢复

不能只 XOR payload。推荐 FEC block：

```text
2B originalPayloadLength + payload bytes
```

parity 对 FEC block 做 XOR。

恢复后：

```java
int originalLen = recoveredBlock.getUnsignedShort(0);
ByteBuf payload = recoveredBlock.retainedSlice(2, originalLen);
```

这样可以正确恢复不同长度的 UDP 包。

### 5.4 交付策略

游戏 UDP 不应等待整组齐再交付。正确策略：

```text
DATA 包到达：立即透传给业务，同时缓存用于 FEC。
PARITY 到达：如果发现只缺 1 个 DATA，则恢复并补交付。
```

这意味着恢复包可能乱序到达，但游戏 UDP 本身应能接受乱序/丢包。相比等待整组，这种方式延迟最低。

## 6. 多倍发包设计

### 6.1 和 FEC 的关系

FEC 和多倍发包解决的问题不同：

| 能力 | 适合解决 | 代价 |
|---|---|---|
| XOR FEC | 轻微随机丢 1 包 | 25%~33% 额外流量 |
| 多倍发包 | 短时突发丢包、运营商抖动 | 100%~200% 额外流量 |

推荐默认：

```text
fecEnabled = true
fecDataShards = 3
fecParityShards = 1
redundantEnabled = true
redundantMultiplier = 1
redundantMaxMultiplier = 2
redundantIntervalMicros = 500
```

也就是说默认只开 FEC，不默认 2 倍发包；当链路丢包高时再自适应升到 2 倍。

### 6.2 合并到 `UdpProtectEncoder`

短期可以继续保留 `UdpRedundantEncoder/Decoder`，但新方案里建议把多倍发包合并进 `UdpProtectEncoder/Decoder`。

原因：

```text
1. 避免 RDNT header + FEC header 双层封装。
2. 统一 seq/sessionId。
3. 统一 MTU 判断。
4. FEC DATA 和 PARITY 可以按不同策略复制。
5. 统一 stats 和 metrics。
```

### 6.3 多倍发包策略

推荐：

```text
DATA 包：可 1x/2x。
PARITY 包：默认 1x，丢包高时可 2x。
恢复包/控制包：不做多倍。
```

开销估算：

```text
FEC 3:1          实际发送量 = 原始 * 4/3，额外约 33%
纯 2x            实际发送量 = 原始 * 2，额外 100%
FEC 3:1 + 2x     实际发送量 = 原始 * 4/3 * 2，额外约 166%
```

所以游戏场景不要默认高倍率。

## 7. Encoder 流程

伪代码：

```java
write(ctx, msg, promise):
    if msg is not DatagramPacket:
        ctx.write(msg, promise)
        return

    packet = (DatagramPacket) msg

    if !shouldProtect(channel, packet.recipient()):
        ctx.write(packet, promise)
        return

    if packet.content().readableBytes() > config.maxProtectedPayload:
        handleTooLarge(packet)
        return

    policy = resolvePolicy(channel, packet)
    state = peerState(resolveFlowId(channel, packet), packet.recipient())

    dataPacket = encodeDataHeader(packet, state.nextSeq, state.groupId, state.groupIdx)
    writeWithRedundant(ctx, dataPacket, policy.dataMultiplier, promise)

    state.currentGroup.add(packet.payloadWithLengthPrefix)

    if state.currentGroup.isFull():
        parityPacket = buildParityPacket(state.currentGroup)
        writeWithRedundant(ctx, parityPacket, policy.parityMultiplier, voidPromise)
        state.resetGroup()
    else:
        scheduleFlush(ctx, state)

    release original packet
```

注意：

```text
1. promise 只绑定第一个 DATA 包。
2. redundant 副本使用 voidPromise。
3. flush timer 必须按 peer/session 维护，不能全局一个 timer。
4. 每个 peer 的 pending timer 和 group 数量要有限制。
```

## 8. Decoder 流程

伪代码：

```java
channelRead(ctx, msg):
    if msg is not DatagramPacket:
        ctx.fireChannelRead(msg)
        return

    if not UdpProtect packet:
        ctx.fireChannelRead(msg)
        return

    header = decodeHeader(msg.content())
    key = FecGroupKey(sender, header.sessionId, header.groupId)

    if dedupWindow.isDuplicate(sender, header.seq):
        drop packet
        return

    if header.isData():
        fire original payload immediately
        cache data shard into group
        if group complete:
            remove group
        return

    if header.isParity():
        cache parity shard
        if group.missingDataCount == 1:
            recovered = group.recover()
            if recovered not duplicate:
                fire recovered payload
            remove group
        return
```

去重必须发生在 FEC 之前，否则多倍副本会污染 FEC group 计数。

## 9. MTU、背压和限速

保护层必须在最终 UDP 出口前完成编码，因为 FEC header 和 redundant 副本会改变真实发送大小。

推荐出站顺序：

```text
业务 UDP / socks 封装 / 加密
    -> UdpProtectEncoder
    -> UdpFinalEgressGuardHandler
    -> socket
```

推荐入站顺序：

```text
socket
    -> UdpProtectDecoder
    -> 解密 / 解封装
    -> 业务处理
```

原则：

```text
1. FEC 应保护最终要过公网的 payload，通常是加密/封装后的 payload。
2. MTU guard 必须在所有 header 增加完成之后执行。
3. 全局限速必须计算 FEC parity 和 redundant 副本的额外流量。
4. UDP pending bytes/packets 背压仍由 final egress guard 兜底。
```

如果上行限制为 20Mb/s，FEC 3:1 下有效业务上限应估算为：

```text
20 * 3 / 4 = 15Mb/s
```

如果再开 2x，多倍发包后有效业务上限约为：

```text
20 * 3 / 4 / 2 = 7.5Mb/s
```

## 10. 与现有 `UdpRelayAttributes` 的关系

当前 `UdpRedundantEncoder` 使用 `UdpRelayAttributes.shouldEncode(channel, recipient)` 判断某个 peer 是否启用冗余。

新方案建议新增泛化类：

```text
UdpProtectAttributes
```

建议 API：

```java
UdpProtectAttributes.addProtectedPeer(channel, remoteAddress);
UdpProtectAttributes.removeProtectedPeer(channel, remoteAddress);
UdpProtectAttributes.shouldProtect(channel, remoteAddress);
```

兼容策略：

```text
1. 短期不删除 UdpRelayAttributes。
2. UdpProtectAttributes 可以兼容读取旧 redundant peer 集合。
3. 新代码优先使用 protected peer 概念，不再使用 redundant peer 命名。
```

## 11. 配置建议

### 11.1 游戏低延迟默认

```java
UdpProtectConfig.gameLowLatency() {
    enabled = true;

    fecEnabled = true;
    fecDataShards = 3;
    fecParityShards = 1;
    fecFlushTimeoutMs = 5;
    staleGroupTimeoutMs = 300;

    redundantEnabled = true;
    redundantMultiplier = 1;
    redundantMaxMultiplier = 2;
    redundantIntervalMicros = 500;

    maxProtectedPayload = 1200;
    maxPeersPerChannel = 4096;
    maxGroupsPerPeer = 32;
}
```

### 11.2 轻量模式

```text
fecDataShards = 4
fecParityShards = 1
redundantMultiplier = 1
```

特点：

```text
额外流量约 25%，延迟略高于 3:1，但更省带宽。
```

### 11.3 极致抗丢包模式

```text
fecDataShards = 3
fecParityShards = 1
redundantMultiplier = 2
redundantIntervalMicros = 700
```

特点：

```text
抗丢能力强，但额外流量约 166%，只建议游戏小流量或临时开启。
```

### 11.4 不推荐默认模式

```text
fecDataShards = 10
fecParityShards = 3
redundantMultiplier = 3
```

原因：

```text
1. 大 FEC group 增加等待时间。
2. 多 parity 第一版不支持。
3. 3 倍发包非常容易打满 VPS 带宽。
4. 对游戏 UDP 不够低延迟。
```

## 12. 删除、替换、保留清单

### 12.1 建议删除

```text
rxlib/src/main/java/org/rx/net/FecUdpClient.java
```

原因：不是 pipeline 形态，和通用接入目标冲突。

### 12.2 建议替换删除

```text
rxlib/src/main/java/org/rx/net/FecEncoder.java
rxlib/src/main/java/org/rx/net/FecDecoder.java
rxlib/src/main/java/org/rx/net/FecPacket.java
```

原因：

```text
1. 多 peer 场景会串 group。
2. decoder 不按 sender/session 隔离。
3. 变长 payload 恢复不正确。
4. byte[] 拷贝较多。
```

### 12.3 建议改名/重写

```text
rxlib/src/main/java/org/rx/net/FecConfig.java
```

替换为：

```text
rxlib/src/main/java/org/rx/net/udp/UdpProtectConfig.java
```

### 12.4 建议保留并逐步迁移

```text
rxlib/src/main/java/org/rx/net/socks/UdpRedundantEncoder.java
rxlib/src/main/java/org/rx/net/socks/UdpRedundantDecoder.java
rxlib/src/main/java/org/rx/net/socks/UdpRedundantStats.java
```

原因：现有多倍发包实现已经是 pipeline 方式，且有可复用的去重、自适应、延迟副本逻辑。

## 13. 实施阶段

### Phase 1：实现 pipeline 版 XOR FEC

新增：

```text
UdpProtectConfig
UdpProtect
UdpProtectEncoder
UdpProtectDecoder
UdpProtectHeader
FecGroupKey
FecEncodeGroup
FecDecodeGroup
UdpDedupWindow
```

能力：

```text
1. XOR 3:1 FEC。
2. 按 peer/session 分组。
3. 支持变长 payload 恢复。
4. DATA 立即透传。
5. PARITY 恢复后补交付。
6. stale group 清理。
7. max peers / max groups 限制。
```

### Phase 2：合并多倍发包

从 `UdpRedundant*` 迁移：

```text
1. 64-bit bitmap 去重窗口。
2. fixed multiplier。
3. delayed redundant copies。
4. max pending delayed copies。
5. adaptive stats。
6. per destination policy resolver。
```

最终 `UdpProtectEncoder` 内部完成：

```text
FEC encode -> redundant duplicate -> write DatagramPacket
```

`UdpProtectDecoder` 内部完成：

```text
dedup -> FEC decode/recover -> fire original DatagramPacket
```

### Phase 3：接入 SOCKS UDP / udp2raw

接入原则：

```text
1. 只在自有代理两端公网链路启用。
2. client -> 本地网关 不启用。
3. 远端代理 -> 游戏服务器 不启用。
4. 避免把 UdpProtect header 发给普通目标服务器。
```

推荐接入点：

```java
Sockets.udpBootstrap(config, ch -> {
    UdpProtect.install(ch.pipeline(), udpProtectConfig);
    // existing socks / udp2raw handlers
});
```

## 14. 测试计划

必须覆盖：

```text
1. 单 peer，3 个 DATA 丢 1 个，PARITY 恢复成功。
2. 单 peer，3 个 DATA 丢 2 个，不能恢复。
3. 变长包恢复后长度必须等于原始长度。
4. 多 peer 交错发包，group 不串。
5. PARITY 先到，DATA 后到。
6. DATA 先到，PARITY 后到。
7. 多倍发包 2x，decoder 只交付一次。
8. FEC + redundant 同开，重复副本不污染 FEC group。
9. seq 回绕。
10. stale group 自动清理。
11. maxPeersPerChannel 生效。
12. maxGroupsPerPeer 生效。
13. payload 超过 maxProtectedPayload 的行为符合配置。
14. MTU 超限被 final egress guard 拦截。
15. channel inactive 后 timer/group/window 正确释放。
```

建议测试类型：

```text
1. EmbeddedChannel 单元测试：验证编码/解码逻辑。
2. 本地 DatagramChannel 集成测试：验证真实 UDP pipeline。
3. 丢包模拟测试：按比例 drop DATA/PARITY。
4. 多 peer 并发测试：验证隔离和内存上限。
5. 性能测试：比较 off / FEC / redundant / FEC+redundant 的 CPU 和延迟。
```

## 15. 关键风险

### 15.1 带宽放大

FEC 和多倍发包都会增加真实发送量。必须在全局限速和统计中计算额外开销。

### 15.2 乱序补交付

恢复包可能晚于后续包。游戏 UDP 一般可以接受，但某些业务协议可能不接受。需要配置开关。

### 15.3 内存泄漏

每个 peer/session 都会维护 group、timer、dedup window。必须有：

```text
1. stale timeout
2. max peers
3. max groups per peer
4. channelInactive 清理
5. handlerRemoved 清理
```

### 15.4 MTU 超限

FEC header 会增加包大小，多倍发包会放大发送量。应默认 `maxProtectedPayload <= 1200`，避免公网路径 MTU 风险。

### 15.5 错误启用范围

UdpProtect 是自定义协议。只能用于自有代理两端之间，不能直接发给普通目标服务器。

## 16. 最终结论

推荐路线：

```text
1. 删除 FecUdpClient。
2. 废弃旧 FecEncoder/FecDecoder/FecPacket。
3. 保留 UdpRedundant*，作为多倍发包迁移基础。
4. 新增 org.rx.net.udp.UdpProtectEncoder/Decoder。
5. 第一版只做 XOR 3:1 FEC + 可选 2x 多倍发包。
6. 所有能力以 pipeline 方式接入，不做 Client 封装。
```

最关键设计原则：

```text
1. FEC 必须按 peer/session 分组。
2. Decoder 必须按 sender/session/group 隔离。
3. 必须支持变长 payload 恢复。
4. DATA 必须立即透传，恢复包后续补交付。
5. 多倍发包必须先去重再进入 FEC group。
6. UdpProtectEncoder 必须在最终 MTU/backpressure guard 前完成编码。
7. 该协议只用于自有代理链路，不直接暴露给普通 UDP 目标。
```
