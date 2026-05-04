# RX SOCKS5 UDP 端口跳跃与 RPC Relay Group 方案

## 1. 结论

当前方案已经收敛为一个主线：

```text
两端都是 rxlib 且 RPC 能力可用：
  1 条 RSS/RPC 控制会话
  + N 个远端 UDP relay port
  + 1 个客户端逻辑 SessionGroup

任意一端不是 rxlib、无 facade、能力不支持或 RPC 调用失败：
  回退标准 SOCKS5 UDP_ASSOCIATE
  1 个 UDP relay = 1 条 TCP control channel
```

本方案明确不做：

- 不做 `RX_SOCKS5_BATCH` 私有 SOCKS5 batch command。
- 不做跨 relay RDNT 共享去重窗口。
- 不把同一个 UDP payload fan-out 到所有 hop 端口。

当前“端口跳跃 + 多倍发送”的语义是：每个 UDP 包先选择一个 relay 地址，再对这个 relay 地址按 RDNT 倍率发送副本。

## 2. 当前实现状态

已完成：

- `UdpPortHoppingConfig`、`UdpPortHoppingMode`、`UdpRelayControlMode` 配置。
- `SocksUdpUpstream.SessionGroup` 支持固定 hop、自适应扩容、补洞、heartbeat。
- 标准 SOCKS5 回退路径完整保留。
- `SocksRpcContract` 增加 token 化 RPC relay group 控制面。
- `SocksRpcCapabilities`、`UdpRelayGroupOpenRequest`、`UdpRelayGroupOpenResult`、`UdpRelayEndpoint` DTO。
- Proxy B 侧 `UdpRelayGroupManager` 支持 open/add/remove/heartbeat/close。
- Proxy A 侧 `SocksUdpUpstream` 在 `AUTO/RSS_RPC` 下优先尝试 RPC relay group。
- `SocksRpcContract` 所有控制接口携带 `token`，服务端按 `RxConfig.INSTANCE.getRtoken()` 校验，失败抛 `SecurityException`。
- RPC group 与 lease pool 的 any-local clientAddr 风险已收敛：不再用上游 server IP 或 `0.0.0.0:port` 猜客户端地址。
- `UdpRelayAttributes.shouldEncode()` 已改为 fail-close：没有显式 redundant peer map 时不加 RDNT。

未完成或不执行：

- 不执行 `RX_SOCKS5_BATCH`。
- 不执行跨 relay RDNT shared dedup。
- TCP control channel 数量和 UDP relay port 数量的压测对比仍需独立压测环境验证。

## 3. 设计目标

目标：

- 在 rxlib 双端场景降低标准 SOCKS5 多 hop 的 TCP control 开销。
- 保持标准 SOCKS5 兼容，非 rxlib 或 RPC 不可用时可通信。
- 支持固定 hop、自适应 hop、单 hop 摘除补洞。
- 支持 UDP lease pool 与 RPC relay group 共同工作。
- 保持热点路径低分配、低锁竞争，不在 EventLoop 上执行阻塞 RPC。

非目标：

- 不改变标准 SOCKS5 `UDP_ASSOCIATE` 协议语义。
- 不要求普通 SOCKS5 服务端识别 rxlib 私有能力。
- 不在普通 UDP 目标上发送 RDNT/UCMP 私有头。

## 4. 控制模式

```java
public enum UdpRelayControlMode {
    AUTO,
    SOCKS5_COMPAT,
    RSS_RPC,
    RX_SOCKS5_BATCH
}
```

当前只启用：

- `AUTO`：默认模式。端口跳跃开启时优先尝试 RSS/RPC relay group，失败按配置回退标准 SOCKS5。
- `SOCKS5_COMPAT`：强制标准 SOCKS5。
- `RSS_RPC`：强制 rxlib RPC relay group；如果关闭 fallback，失败直接初始化失败。

`RX_SOCKS5_BATCH` 保留枚举但本方案不执行。

关键默认值：

```java
private UdpRelayControlMode udpRelayControlMode = UdpRelayControlMode.AUTO;
private boolean udpRelayControlFallbackToSocks5 = true;
private int udpRelayControlMaxRelaysPerGroup = 4;
private long udpRelayGroupIdleMillis = 300_000L;
private long udpRelayGroupHeartbeatMillis = 30_000L;
private int udpRelayControlFailureThreshold = 5;
private long udpRelayControlBreakerOpenMillis = 60_000L;
```

## 5. 标准 SOCKS5 兼容路径

标准路径保持原样：

```text
SocksUdpUpstream.initSlowPath()
  -> Socks5Client.udpAssociateAsync()
  -> 标准 UDP_ASSOCIATE
  -> 服务端创建独立 UDP relay
  -> 返回标准 SOCKS5 response
```

端口跳跃在标准模式下会创建多个 `UDP_ASSOCIATE` session：

```text
N 个 hop = N 条 TCP control channel + N 个 UDP relay port
```

这条路径用于：

- 对端不是 rxlib。
- `next.getFacade() == null`。
- RPC capability 不支持 `UDP_RELAY_GROUP`。
- RPC 调用抛异常或返回 unsupported。
- RPC breaker 打开。
- 显式配置 `SOCKS5_COMPAT`。

## 6. RSS RPC Relay Group 快路径

快路径流程：

```text
Proxy A SocksUdpUpstream
  -> facade.capabilities(rtoken)
  -> facade.openUdpRelayGroup(request, rtoken)
  -> Proxy B UdpRelayGroupManager.open()
  -> 批量 bind N 个 UDP relay
  -> 返回 UdpRelayEndpoint[]
  -> Proxy A 转成 SessionHolder.rpc
  -> 绑定 SessionGroup
  -> selectUdpRelayAddress() 选择 relay
```

控制面开销：

```text
N 个 hop = 1 条 RPC control session + N 个 UDP relay port
```

RPC group 后续生命周期：

- `addUdpRelays(groupId, count, token)`：自适应扩容。
- `removeUdpRelay(groupId, relayPort, token)`：单 hop 摘除。
- `heartbeatUdpRelayGroup(groupId, token)`：长时间无 UDP 但需要保活。
- `closeUdpRelayGroup(groupId, token)`：本地 group 失效或关闭。

## 7. RPC 授权

所有 RPC 控制接口都必须携带 `token`：

```java
static String rpcToken() {
    return RxConfig.INSTANCE.getRtoken();
}

static boolean isValidRpcToken(String token) {
    String expected = RxConfig.INSTANCE.getRtoken();
    return !Strings.isEmpty(expected) && expected.equals(token);
}

static void requireValidRpcToken(String token) {
    if (!isValidRpcToken(token)) {
        throw new SecurityException("invalid rpc token");
    }
}
```

服务端 wrapper 校验失败时：

- 记录 `socks.rpc.auth.fail.count{action=...}`。
- 抛 `SecurityException`。
- 客户端 `AUTO` 模式捕获 RPC 失败后回退标准 SOCKS5。

`UdpRelayGroupManager.UdpRelayGroup.dataPlaneToken` 仅为未来 RXUDP 数据面 header/MAC 预留；当前 RPC 授权不使用该随机 token。

## 8. clientAddr 锁定规则

服务端 UDP relay 的客户端锁定规则：

```text
ATTR_CLIENT_LOCKED=true:
  sender 必须等于 ATTR_CLIENT_ADDR，否则丢 unexpected-client

ATTR_CLIENT_LOCKED=false:
  首个真实 UDP sender 写入 ATTR_CLIENT_ADDR
```

已修正的边界：

- RPC group `resolveRpcClientAddress()` 遇到 any-local UDP 地址返回 `null`，不再用上游 server IP 猜测。
- lease pool `resolveClaimClientAddress()` 遇到 UDP any-local 且 TCP local 也不可用时返回 `null`，不再返回 `0.0.0.0:port`。
- `claimUdpRelay(relayPort, null, token)` 会清理旧 relay 状态，但不会设置 `ATTR_CLIENT_LOCKED=true`，由首个真实 UDP sender 建立锁定。
- 如果能得到明确 UDP local 或 TCP local IP，则继续传入具体 `clientAddr` 并锁定。

## 9. 端口跳跃行为

固定 hop：

```text
hopCount = N
初始化时获取 N 个 relay
每个 UDP payload 选择一个 relay 地址发送
```

自适应 hop：

```text
minHopCount 起步
按流量阈值或活跃时长触发 add relay
不超过 maxHopCount / maxRelaysPerGroup
```

补洞：

```text
holder 失效或 relay 关闭
  -> remove holder
  -> needsReplenish()
  -> cooldown 后补一个新 relay
```

选择策略：

- `ROUND_ROBIN`：默认轮询。
- `RANDOM`：随机选取活动 relay。

## 10. RDNT 多倍发送兼容边界

RDNT 编码只允许对显式 redundant peer 生效：

```java
return peers != null && recipient != null && peers.containsKey(normalize(recipient));
```

因此：

- 标准 UDP 目标不会被默认加 RDNT 头。
- 忘记初始化 peer map 时 fail-close，不编码。
- 当前不做跨 relay RDNT shared dedup。
- 当前不把同一个包同时发到多个 hop 端口。

## 11. Heartbeat 与运行期失效

RPC group 不依赖 N 条 TCP control channel 的 closeFuture 表示生命周期，所以需要 heartbeat 和 idle timeout：

```text
客户端：
  发送路径上按 udpRelayGroupHeartbeatMillis 异步触发 heartbeat
  不阻塞 EventLoop
  连续失败达到 udpRelayControlFailureThreshold 后失效本地 group
  后续流量重新初始化，AUTO 模式可回退标准 SOCKS5

服务端：
  UDP 流量或 heartbeat 刷新 lastActiveAtMillis
  idle timeout 后关闭 group 和所有 relay
```

## 12. 数据结构

Proxy A：

```text
SessionGroup
  SessionHolder[]
  UdpPortHoppingMode
  RpcRelayGroupControl
  targetHopCount
  scale/replenish/heartbeat 状态
```

Proxy B：

```text
UdpRelayGroup
  groupId
  dataPlaneToken
  clientAddr
  maxRelayCount
  idleTimeoutMillis
  relays

UdpRelayEntry
  relayId
  relayPort
  udpChannel
  relayAddress
```

## 13. 监控指标

必须关注：

- `socks.udp.relay.group.open.count{result,relays}`
- `socks.udp.relay.group.add.count{result,relays}`
- `socks.udp.relay.group.remove.count{result}`
- `socks.udp.relay.group.heartbeat.count{result}`
- `socks.udp.relay.group.close.count{result,reason}`
- `socks.udp.relay.group.active.count`
- `socks.udp.relay.group.relay.count`
- `socks.udp.porthop.group.active.count`
- `socks.udp.porthop.adaptive.scale.count`
- `socks.udp.session.invalidate.count`
- `socks.udp.drop.count{reason=unexpected-client|...}`
- `socks.rpc.auth.fail.count`
- Netty direct memory / pooled direct memory
- UDP 吞吐、延迟、丢包率

## 14. 测试与验收

已覆盖：

- `SocksUdpUpstreamPortHoppingTest`
  - round-robin 选择。
  - 跳过关闭 holder。
  - 自适应扩容阈值。
  - 补洞 cooldown。
  - any-local claim clientAddr 不误返回。
  - heartbeat 连续失败阈值。
- `SocksProxyServerIntegrationTest`
  - 标准端口跳跃 e2e。
  - RPC relay group e2e。
  - RPC token 失败抛异常。
  - `claimUdpRelay(null)` 不锁定 relay。
- `UdpRedundantTest`
  - 未初始化 peer map 时不编码 RDNT。
- `SocksUdpRelayHandlerTest`
  - UDP relay 基础转发和队列行为。

推荐回归命令：

```bash
mvn -pl rxlib test "-Dtest=SocksUdpUpstreamPortHoppingTest,SocksUdpRelayHandlerTest,UdpRedundantTest,SocksProxyServerIntegrationTest#claimUdpRelayWithNullClientDoesNotLockRelay+rpcControlRejectsInvalidToken+shadowsocksUdpRelay_socks5_chained_withPortHopping_e2e+shadowsocksUdpRelay_socks5_chained_withRpcRelayGroup_e2e" "-Dmaven.test.skip=false"
```

仍建议补充的压测：

- 标准 SOCKS5 N hop 与 RPC group N hop 的 TCP control channel 数对比。
- UDP relay port 数、direct memory、吞吐、延迟对比。
- RPC heartbeat failover 后重建耗时。
- any-local/NAT 场景下首包锁定真实 sender 的公网回归。

## 15. 风险与约束

- RPC group 依赖 `app.rtoken` 一致；不一致会抛 `SecurityException` 并触发回退。
- any-local 场景只能让首个真实 UDP sender 建立锁定，存在极短抢首包窗口；这是避免错误锁到 `0.0.0.0` 或 server IP 的更稳妥取舍。
- RDNT 仍是 per selected relay 发送，不提供跨 relay 去重。
- 不做 `RX_SOCKS5_BATCH` 后，非 RSS/RPC 的标准模式仍会回到 N 条 TCP control channel。
- EventLoop 不执行阻塞 RPC；RPC open/add/heartbeat 在异步任务中执行，结果回投 EventLoop。

## 16. 最终推荐

默认推荐：

```text
udpPortHopping.enabled = true
udpPortHopping.adaptive = true
udpRelayControlMode = AUTO
udpRelayControlFallbackToSocks5 = true
udpRelayControlMaxRelaysPerGroup = 4
udpRelayGroupHeartbeatMillis = 30_000
udpRelayGroupIdleMillis = 300_000
```

生产观测先看：

- RPC group 成功率。
- 标准 SOCKS5 fallback 次数。
- UDP unexpected-client drop。
- direct memory。
- relay group active/relay 数。
- UDP 延迟和丢包。

只有在明确遇到单 relay port 黑洞或端口级限速时，才重新评估跨 relay RDNT shared dedup；当前主线不实现。
