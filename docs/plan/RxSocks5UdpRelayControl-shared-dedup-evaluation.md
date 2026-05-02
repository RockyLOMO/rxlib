# RX UDP Relay Group 跨 relay RDNT 共享去重窗口评估

本文档补充 `RxSocks5UdpRelayControl-plan.md` 中“阶段 F：跨 relay RDNT 共享去重窗口”的收益、成本、启用条件和建议落地方式。

## 1. 结论

跨 relay RDNT 共享去重窗口不是 RSS RPC relay group 第一阶段的必需项。

它的主要价值只在准备开启：

```java
spreadRedundantCopies = true;
```

时才明显。也就是把同一个 RDNT 冗余组的多个副本分散到不同 UDP relay port。

当前推荐策略：

```text
默认继续保持 spreadRedundantCopies=false
先压测 RSS_RPC relay group + 逻辑包级 relay 轮换
如果仍存在单 relay port 黑洞、端口级限速或游戏/语音抖动，再实现 shared dedup
```

收益评级：

```text
普通场景：中等
强端口/五元组限速场景：较高
纯链路拥塞场景：一般
```

实现成本评级：

```text
仅 B 端 inbound shared dedup：中等，约 1~2 天
双向 shared dedup：中等偏高，约 2~4 天
加权/质量感知副本分布：偏高，约 4~7 天
兼容标准 SOCKS5 多 control 模式：不建议
```

## 2. 当前模式下为什么收益有限

当前端口跳跃和 RDNT 联用时，推荐语义是：

```text
逻辑包 #1 的 N 个 RDNT 副本 -> relayPortA
逻辑包 #2 的 N 个 RDNT 副本 -> relayPortB
逻辑包 #3 的 N 个 RDNT 副本 -> relayPortC
```

这种方式已经能把不同逻辑包分摊到多个 relay port，降低单端口持续流量，同时不会让 Proxy B 把同一个逻辑包重复转发到真实目标。

在这种模式下，同一个 RDNT 组没有跨 relay，因此跨 relay shared dedup 几乎没有直接收益。

也就是说，只要保持：

```java
spreadRedundantCopies = false;
```

就可以暂缓实现 shared dedup。

## 3. shared dedup 的真正收益

shared dedup 的核心意义是允许：

```text
逻辑包 #1 副本1 -> relayPortA
逻辑包 #1 副本2 -> relayPortB
逻辑包 #1 副本3 -> relayPortC
```

Proxy B 多个 relay port 收到同一个 RDNT seq 后，只把第一份新包转发给真实目标，后续副本被 group 级去重窗口丢弃。

### 3.1 抗单 relay port 丢包更强

如果某个 UDP relay port 被限速、丢包、QoS、NAT 映射异常或局部黑洞，当前“同 RDNT 组走同一个 relay”的模式下，同一个逻辑包的所有副本可能一起失败。

跨 relay 后：

```text
relayPortA 丢了
relayPortB 可能到
relayPortC 可能到
```

这对游戏、语音、弱网 UDP 更有价值。

### 3.2 从流量分散升级为单包冗余

当前端口跳跃主要是“包级轮换”：

```text
不同逻辑包分散到不同 relay
```

shared dedup 后才是“副本级分散”：

```text
同一个逻辑包的多个副本分散到不同 relay
```

如果链路问题与端口或五元组相关，副本级分散比包级轮换更有效。

### 3.3 端口级限速场景收益更高

如果中间设备、运营商、NAT 或防火墙存在按 UDP 端口/五元组限速，shared dedup 可以让同一逻辑包的多份副本跨端口走，降低单端口瞬时失败概率。

但如果丢包来自以下原因，收益可能有限：

```text
公网链路整体拥塞
国际出口丢包
VPS 带宽打满
机器 CPU 或网卡软中断打满
```

这些情况下多个 relay port 仍然共享同一条物理路径，跨端口副本不一定能显著提高到达率。

## 4. 实现成本来源

当前 RDNT 去重模型是 channel 级的。

`UdpRedundantDecoder` 每个 channel 持有独立去重状态，并且按 sender 地址维护窗口。若同一个 RDNT seq 的副本进入 Proxy B 的不同 UDP relay channel，每个 decoder 都可能认为它是首次包。

所以 shared dedup 不能简单把 handler 标记为 `@Sharable`，需要把去重窗口提升到 `UdpRelayGroup` 级别。

### 4.1 去重窗口从 channel 级迁移到 group 级

当前模型：

```text
relayChannelA -> decoderA -> windows
relayChannelB -> decoderB -> windows
relayChannelC -> decoderC -> windows
```

目标模型：

```text
UdpRelayGroup
  -> sharedDedupWindow
      relayChannelA / relayChannelB / relayChannelC 共用
```

### 4.2 去重 key 需要重新设计

建议 key：

```text
groupId + direction + destAddr + seqId
```

字段含义：

- `groupId`：RSS RPC relay group 标识，避免不同 group 的 seq 冲突。
- `direction`：方向，至少区分 A_TO_B 与 B_TO_A，避免双向 RDNT seq 空间互相污染。
- `destAddr`：SOCKS5 UDP header 内的真实目标地址，避免未来一个 group 复用多个目标时误丢包。
- `seqId`：RDNT header 中的 sequence id。

如果确认一个 Proxy A outbound channel 的 seqId 对所有目标全局递增，也可以简化为：

```text
groupId + direction + seqId
```

但默认建议保留 `destAddr`，更稳。

### 4.3 dedup 位置需要调整

为了生成 `destAddr` 维度的 key，Proxy B 在执行 shared dedup 时需要同时读取：

```text
RDNT header: magic + seqId
SOCKS5 UDP header: ATYP + DST.ADDR + DST.PORT
```

推荐处理顺序：

```text
收到 UDP datagram
  -> 判断 RDNT magic
  -> 读取 seqId，但不要破坏下游 readerIndex
  -> peek SOCKS5 UDP header，解析 destAddr
  -> group shared dedup check
  -> 新包：strip RDNT header 后继续转发
  -> 重复包：释放并丢弃
```

### 4.4 生命周期和内存清理

shared dedup window 必须绑定 group 生命周期：

```text
open group -> 创建 shared dedup window
close group / idle timeout -> 清空 shared dedup window
```

避免 group 多、route 多时窗口状态残留。

窗口本身可以继续使用 64-bit sliding bitmap，内存成本可控。

## 5. 推荐启用条件

只建议在 RSS RPC relay group 模式下启用。

启用条件：

```text
udpRelayControlMode = RSS_RPC
或 AUTO 命中 RSS_RPC

capability 支持 UDP_RELAY_GROUP_SHARED_DEDUP
udpRedundantMultiplier > 1
spreadRedundantCopies = true
```

不满足条件时强制保持：

```java
spreadRedundantCopies = false;
```

不建议在标准 SOCKS5 兼容模式中实现跨 relay shared dedup。原因是标准模式下每个 hop 的控制面和生命周期是独立的，硬做 shared dedup 会增加大量状态桥接成本，收益不如 RSS RPC group 清晰。

## 6. 推荐配置

默认关闭：

```java
bConf.setUdpPortHoppingSpreadRedundantCopies(false);
bConf.setUdpRelayGroupSharedDedupEnabled(false);
```

仅在确认存在端口级限速或单 relay port 黑洞后开启：

```java
bConf.setUdpRelayControlMode(UdpRelayControlMode.AUTO);
bConf.setUdpPortHoppingEnabled(true);
bConf.setUdpRedundantMultiplier(2);
bConf.setUdpPortHoppingSpreadRedundantCopies(true);
bConf.setUdpRelayGroupSharedDedupEnabled(true);
```

建议增加保护：

```text
如果 spreadRedundantCopies=true 但 peer capability 不支持 UDP_RELAY_GROUP_SHARED_DEDUP：
  - 默认降级为 spreadRedundantCopies=false
  - 输出 warn 日志
  - 不让真实目标收到重复 payload
```

## 7. 分阶段实现建议

### 7.1 第一版：仅 B 端 inbound shared dedup

目标：解决“同一个 RDNT 组副本跨 relay 后，Proxy B 重复转发到真实目标”的问题。

新增结构：

```java
final class UdpRelayGroup {
    final SharedUdpDedupWindow inboundDedup;
}
```

核心行为：

```text
relayPortA 收到 seq=100 -> 新包，转发真实目标
relayPortB 收到 seq=100 -> 重复，丢弃
relayPortC 收到 seq=100 -> 重复，丢弃
```

第一版只处理 A_TO_B 方向即可支撑 `spreadRedundantCopies=true` 的主要收益。

### 7.2 第二版：双向 shared dedup

如果 B->A 回包方向也启用了 RDNT 并需要跨 relay 分散，再补双向去重。

key：

```text
groupId + direction + srcAddr/destAddr + seqId
```

### 7.3 第三版：质量感知副本分布

引入 relay 权重：

```text
relayPortA 丢包低 -> 权重高
relayPortB 丢包高 -> 权重低
relayPortC 最近失败 -> 暂停发送
```

这是高阶优化，不建议第一版就做。

## 8. 数据结构草案

```java
final class SharedUdpDedupWindow {
    private final ConcurrentHashMap<DedupKey, DeduplicationWindow> windows;

    boolean checkAndMark(DedupKey key, long seqId);
    void cleanupStale(long nowNanos);
    void clear();
}

final class DedupKey {
    final String groupId;
    final byte direction;
    final InetSocketAddress endpoint;
}
```

`DeduplicationWindow` 可以复用当前 `UdpRedundantDecoder` 的 64-bit bitmap 思路：

```text
highestSeq
bitmap
lastAccessNanos
```

注意：`seqId` 不要放入 `DedupKey` 对象字段中，否则每个包都会产生新 key；应当由 `DeduplicationWindow.checkAndMark(seqId)` 处理。

## 9. 发送策略草案

当 `spreadRedundantCopies=false`：

```text
同一个逻辑包的所有 RDNT 副本 -> 同一个 selectedRelayAddr
```

当 `spreadRedundantCopies=true` 且 shared dedup 可用：

```text
副本1 -> relayPortA
副本2 -> relayPortB
副本3 -> relayPortC
```

选择方式：

```text
copyIndex=0: group.selectRelayAddress()
copyIndex=1: group.selectNextRelayAddressAvoidSame()
copyIndex=2: group.selectNextRelayAddressAvoidSame()
```

如果 active relay 数小于 multiplier，则允许部分副本复用 relay，但仍需保证 B 端 shared dedup 正常工作。

## 10. 指标建议

新增 metrics：

```text
socks.udp.relay.group.dedup.check.count
socks.udp.relay.group.dedup.duplicate.count
socks.udp.relay.group.dedup.unique.count
socks.udp.relay.group.dedup.window.count
socks.udp.relay.group.dedup.cleanup.count
socks.udp.relay.group.spread.copy.count
socks.udp.relay.group.spread.fallback.count
```

关键观测：

```text
duplicate / check 比例
spread 后端到端丢包率是否下降
真实 UDP 目标是否仍只收到一份 payload
shared dedup window 是否随 group close 清理
```

## 11. 测试计划

### 11.1 单元测试

```text
SharedUdpDedupWindowTest
UdpRelayGroupSharedDedupTest
UdpRedundantSpreadAcrossRelaysTest
```

覆盖：

- 同一 `groupId + direction + destAddr + seqId` 只通过一次。
- 不同 `destAddr` 的相同 seqId 不互相误丢。
- 不同 `direction` 的相同 seqId 不互相误丢。
- 不同 `groupId` 的相同 seqId 不互相误丢。
- 过期窗口可清理。
- group close 后 dedup window 清空。

### 11.2 集成测试

新增场景：

```text
ShadowsocksClient -> ShadowsocksServer -> Proxy A(rxlib)
  -> RSS_RPC relay group with spreadRedundantCopies=true
  -> Proxy B(rxlib)
  -> UDP echo dest
```

验证：

- A->B 抓包看到同一 RDNT seq 的副本分散到多个 relay port。
- Proxy B 的真实 UDP echo 目标只收到一份业务 payload。
- relayPortA 丢包或关闭时，同一逻辑包仍可能通过 relayPortB/relayPortC 到达。
- peer 不支持 `UDP_RELAY_GROUP_SHARED_DEDUP` 时自动降级为 `spreadRedundantCopies=false`。

## 12. 风险点

- dedup key 如果不包含 direction，双向 RDNT 可能互相污染。
- dedup key 如果不包含 destAddr，未来多目标复用 group 时可能误丢不同目标的包。
- 在 ByteBuf readerIndex 处理上必须谨慎，peek RDNT 和 SOCKS5 header 后要保证下游解析一致。
- shared dedup window 必须跟随 group close / idle timeout 清理，避免状态泄漏。
- 不能在未确认 peer 支持 shared dedup 时开启 `spreadRedundantCopies`，否则真实目标可能收到重复业务 payload。

## 13. 最终建议

短期：

```text
继续保持 spreadRedundantCopies=false
优先压测 RSS_RPC relay group 的控制面收益和端口分散收益
```

中期：

```text
如果存在单 relay port 黑洞、端口级限速或游戏/语音仍明显抖动，再实现 B 端 inbound shared dedup
```

长期：

```text
再做双向 shared dedup、relay 权重和质量感知副本分布
```

一句话：跨 relay RDNT 共享去重窗口有价值，但它是“弱网/端口级限速增强项”，不是 RSS RPC relay group 的主收益来源。只有当 `spreadRedundantCopies=true` 真正要上线时，才建议实现并默认只在 RSS RPC relay group capability 明确支持时启用。
