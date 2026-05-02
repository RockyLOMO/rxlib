# SocksScene 场景4双向 UDP 多倍发送计划

## 1. 背景

`docs/test/SocksScene.md` 场景4链路：

```text
ShadowsocksClient(ip c)
  -> ShadowsocksServer(ip a)
  -> SocksProxyServer A(ip a)
  -> SocksProxyServer B(ip b)
  -> dest
```

本计划只处理场景4中 **Proxy A <-> Proxy B 链路的双向 UDP 多倍发送（RDNT）效果**。

本计划明确先忽略：

- 端口跳跃资源线性增长、hopCount 上限、RPC group UDP channel 数等问题。
- `UdpPortHoppingConfig.spreadRedundantCopies` / 跨 relay 分散同一 RDNT 副本。
- `udp2raw` 正在重构的相关类。

## 2. 当前实现判断

### 2.1 A -> B 方向

A 侧 `SocksUdpUpstream.bindGroup(...)` 会把上游 relay 地址加入当前 outbound channel 的 redundant peer：

```java
for (InetSocketAddress relayAddr : relayAddresses) {
    UdpRelayAttributes.addRedundantPeer(channel, relayAddr);
}
```

`UdpRedundantEncoder.write(...)` 只有在 `UdpRelayAttributes.shouldEncode(ctx.channel(), recipient)` 为 true 时才会添加 RDNT 头并按倍率发送。

因此 A -> B 方向只要满足：

```text
A outbound channel pipeline 安装 UdpRedundantEncoder
且 B relay 地址已加入 A outbound channel 的 redundant peers
且 multiplier > 1
```

就具备 RDNT 多倍发送效果。

### 2.2 B -> A 方向

标准 SOCKS5 `UDP_ASSOCIATE` 路径中，`Socks5CommandRequestHandler` 会根据 TCP control 原始地址与直接 peer 地址判断：

```java
redundantClientPeer = clientTcpAddr != null && tcpPeerAddr != null && !clientTcpAddr.equals(tcpPeerAddr)
```

随后真实 UDP client sender 可在首包到达时被加入 relay channel 的 redundant peer，从而让 B -> A 回包方向也能被 `UdpRedundantEncoder` 编码。

但 RPC relay group 路径中，`UdpRelayGroupManager.bindRelay(...)` 当前固定设置：

```java
UdpRelayAttributes.initRedundantPeers(relay);
relay.attr(UdpRelayAttributes.ATTR_REDUNDANT_CLIENT_PEER).set(Boolean.FALSE);
```

这会导致 B 侧 RPC relay group 在收到 A 的 UDP 包后，不会把 A 的 sender 加入 redundant peer。最终 B -> A 回包方向虽然可能安装了 `UdpRedundantEncoder`，但 `shouldEncode(...)` 不命中，回包不会加 RDNT 头，也不会多倍发送。

## 3. 目标

必须达成：

```text
A -> B：保持现有 RDNT 多倍发送效果。
B -> A：在场景4 chained / RPC relay group 路径下同样具备 RDNT 多倍发送效果。
```

兼容边界：

- RDNT 只允许作用于 rxlib 代理链路对端，不允许误打到普通 UDP dest。
- 不把同一个 UDP payload fan-out 到所有 hop port。
- 不开启跨 relay RDNT shared dedup。
- 不改变标准 SOCKS5 兼容路径语义。
- 如果没有开启 UDP redundant，不能额外增加 peer map、header、重复发送开销。

## 4. 设计方案

### 4.1 增加“relay 是否应将首个 client sender 登记为 RDNT peer”的统一判断

新增一个小工具方法，建议放在 `UdpRelayAttributes` 或 `SocksConfig`：

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

语义：

```text
只要当前 relay 所在 config 确实可能启用 UDP redundant，
就允许 relay 在首个真实 client UDP sender 到来时，把 sender 加入 redundant peer。
```

这样 `UdpRedundantEncoder.shouldEncode(...)` 仍保持 fail-close：没有明确 peer 时不编码。

### 4.2 修改 RPC relay group bindRelay

位置：

```text
rxlib/src/main/java/org/rx/net/socks/UdpRelayGroupManager.java
```

当前：

```java
UdpRelayAttributes.initRedundantPeers(relay);
relay.attr(UdpRelayAttributes.ATTR_REDUNDANT_CLIENT_PEER).set(Boolean.FALSE);
```

建议改为：

```java
boolean redundantClientPeer = UdpRelayAttributes.shouldTrackClientAsRedundantPeer(config);
if (redundantClientPeer) {
    UdpRelayAttributes.initRedundantPeers(relay);
}
relay.attr(UdpRelayAttributes.ATTR_REDUNDANT_CLIENT_PEER).set(redundantClientPeer);
```

如果 `group.clientAddr` 是明确地址，也可以在 bind 后直接加入 peer：

```java
if (redundantClientPeer && group.clientAddr != null) {
    UdpRelayAttributes.addRedundantPeer(relay, group.clientAddr);
}
```

但公网/NAT 场景里 `group.clientAddr` 经常为 null；这时继续依赖 `SocksUdpRelayHandler.handleClientPacket(...)` 中首个真实 UDP sender 登记 peer。

### 4.3 修改标准 UDP_ASSOCIATE 路径判断，保持语义一致

位置：

```text
rxlib/src/main/java/org/rx/net/socks/Socks5CommandRequestHandler.java
```

当前：

```java
final boolean redundantClientPeer = clientTcpAddr != null && tcpPeerAddr != null && !clientTcpAddr.equals(tcpPeerAddr);
```

建议改为：

```java
final boolean redundantClientPeer = UdpRelayAttributes.shouldTrackClientAsRedundantPeer(config)
        && clientTcpAddr != null && tcpPeerAddr != null && !clientTcpAddr.equals(tcpPeerAddr);
```

目的：

- 没开 UDP redundant 时不维护 peer map，减少不必要开销。
- 开了 UDP redundant 且存在代理链路时，标准路径仍保持 B -> A RDNT 能力。

如果希望“只要开启 UDP redundant，不管 TCP peer 是否等于 origin 都允许 client peer 登记”，也可以放宽为：

```java
final boolean redundantClientPeer = UdpRelayAttributes.shouldTrackClientAsRedundantPeer(config);
```

但第一期建议保守，继续利用现有 `clientTcpAddr != tcpPeerAddr` 判断，避免本地普通客户端路径误加 RDNT。

### 4.4 确认 SS outbound channel 回程 decoder 已覆盖

位置：

```text
rxlib/src/main/java/org/rx/net/socks/SSUdpProxyHandler.java
```

`ensureRelayResponseDecoder(...)` 已经在 upstream 是 `SocksUdpUpstream` 时补：

```java
UdpRedundantDecoder
UdpCompressDecoder
```

因此 B -> A 回包如果加 RDNT，A/SS outbound 收包时应该能先去重、剥 RDNT，再交给 `UdpBackendRelayHandler` 处理。

这块原则上不需要新增 encoder，避免 SS -> Socks A 本地段也被放大。

### 4.5 保持 A -> B 行为不变

`SocksUdpUpstream.bindGroup(...)` 当前给所有远端 relay 地址执行：

```java
UdpRelayAttributes.addRedundantPeer(channel, relayAddr);
```

这条路径继续保留。它是 A -> B 方向 RDNT 生效的关键。

## 5. 关键风险

### 5.1 不应把 RDNT 打到普通 UDP dest

必须继续依赖：

```java
UdpRelayAttributes.shouldEncode(channel, recipient)
```

只有 explicit peer 才编码，不能改成全局 multiplier > 1 就对所有 DatagramPacket 编码。

### 5.2 B -> A 首包可能不是 RDNT

如果 `group.clientAddr == null`，B 侧 relay 只有收到 A 的第一包后才能知道真实 sender，并登记 redundant peer。

因此实际表现可能是：

```text
A -> B 第一包：可 RDNT
B -> A 第一包回包：可能不 RDNT，取决于 peer 登记发生时机
后续 B -> A 回包：应 RDNT
```

这是 NAT A 兼容下可接受的取舍。若必须首个回包也 RDNT，需要 RPC open request 能提供明确 client UDP 出口地址，但公网 NAT 下通常无法可靠提前得知。

### 5.3 不做跨 relay shared dedup

本计划不改 `spreadRedundantCopies`。同一 payload 仍然只选择一个 relay port 后做 RDNT，避免不同 sender 导致 dedup 窗口不共享。

## 6. 实施步骤

### Step 1：新增统一判断方法

文件：

```text
rxlib/src/main/java/org/rx/net/socks/UdpRelayAttributes.java
```

新增：

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

### Step 2：RPC relay group 支持 B -> A RDNT peer 登记

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

### Step 3：标准 UDP_ASSOCIATE 路径收敛判断

文件：

```text
rxlib/src/main/java/org/rx/net/socks/Socks5CommandRequestHandler.java
```

改成：

```java
final boolean redundantClientPeer = UdpRelayAttributes.shouldTrackClientAsRedundantPeer(config)
        && clientTcpAddr != null && tcpPeerAddr != null && !clientTcpAddr.equals(tcpPeerAddr);
```

### Step 4：补测试

建议新增/调整以下测试：

```text
SocksProxyServerIntegrationTest#shadowsocksUdpRelay_socks5_chained_withRpcRelayGroup_bidirectionalUdpRedundant_e2e
SocksProxyServerIntegrationTest#shadowsocksUdpRelay_socks5_chained_withUdpRedundantOnProxyAB_e2e
UdpRedundantTest#rpcRelayGroupAddsClientSenderAsRedundantPeerWhenEnabled
UdpRedundantTest#rpcRelayGroupDoesNotTrackClientPeerWhenRedundantDisabled
```

测试重点：

1. A -> B 包在 B 侧 decoder 能看到 RDNT 并去重。
2. B -> A 回包在 A/SS outbound decoder 能看到 RDNT 并去重。
3. 本地 SOCKS5 client 不应看到 RDNT 头。
4. 未开启 UDP redundant 时，RPC relay group 不应把 client sender 加入 redundant peer。
5. `group.clientAddr == null` 时，首个真实 sender 到达后可以登记 peer，后续 B -> A 回包进入 RDNT。

### Step 5：补低基数指标

建议增加：

```text
socks.udp.redundant.peer.track.count{path=rpc-group,result=enabled|disabled}
socks.udp.redundant.peer.add.count{path=client-sender,result=success}
udp.redundant.encode.count{direction=to-client|to-upstream,result=encoded|plain}
udp.redundant.decode.count{result=unique|duplicate|plain}
```

注意不要把 source、sender、recipient、port、bytes 放入 metric tags。

## 7. 验收标准

### 7.1 功能验收

必须满足：

```text
场景4 chained + UDP redundant 开启时：
  A -> B 有 RDNT 多倍发送
  B -> A 有 RDNT 多倍发送
  SS client 收到的是正常 Shadowsocks UDP 回包，不包含 RDNT 头
  普通 UDP dest 不收到 RDNT 头
```

### 7.2 回归命令

建议：

```bash
mvn -pl rxlib test \
  "-Dtest=UdpRedundantTest,SocksProxyServerIntegrationTest#shadowsocksUdpRelay_socks5_chained_withUdpRedundantOnProxyA_e2e+shadowsocksUdpRelay_socks5_chained_withUdpCompressAndRedundantOnProxyAB_e2e+shadowsocksUdpRelay_socks5_chained_withPortHopping_e2e" \
  "-Dmaven.test.skip=false"
```

如果新增 RPC group 双向 RDNT 测试后，追加：

```bash
mvn -pl rxlib test \
  "-Dtest=SocksProxyServerIntegrationTest#shadowsocksUdpRelay_socks5_chained_withRpcRelayGroup_bidirectionalUdpRedundant_e2e" \
  "-Dmaven.test.skip=false"
```

### 7.3 性能验收

不要求本计划解决端口跳跃资源问题，但需要确认：

```text
未开启 UDP redundant 时：peer map 不增长，RDNT encoder 不编码。
开启 UDP redundant 时：B -> A 增加的包量约等于 multiplier，不出现 hopCount * multiplier 放大。
EventLoop 无大量 intervalMicros scheduled task 堆积。
```

## 8. 最终推荐

第一期只做：

```text
让 RPC relay group 的 B 侧 relay 在 UDP redundant 开启时，允许把真实 A sender 登记为 RDNT peer。
```

不要同时做：

```text
spreadRedundantCopies=true
跨 relay shared dedup
端口跳跃 hop/fd/channel 资源重构
udp2raw 相关改造
```

这样改动面最小，能直接补齐场景4 B -> A 多倍发送效果，同时不破坏当前 A -> B 行为和标准 SOCKS5 兼容边界。
