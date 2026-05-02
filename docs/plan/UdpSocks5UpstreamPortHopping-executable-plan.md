# UDP SOCKS5 Upstream 端口跳跃优化执行计划

本文档与 `UdpSocks5UpstreamPortHopping-plan.md` 配套使用。

原计划已经完成第一期端口跳跃能力：`SocksUdpUpstream` 可以在一个逻辑 UDP upstream 下维护多个 SOCKS5 `UDP_ASSOCIATE` relay 端口，并支持固定 `hopCount` 和自适应 `hopCount`。本执行计划聚焦下一阶段的资源开销优化、稳定性补强和可验证落地步骤。

## 1. 当前问题判断

标准 SOCKS5 UDP 端口跳跃的主要成本来自控制面：

```text
1 个 UDP hop = 1 条 TCP control channel + 1 个远端 UDP relay port
N 个 hop = N 条 TCP control channel + N 个远端 UDP relay port
```

如果所有 UDP route 默认固定 `hopCount=3~4`，在高并发下会带来明显资源压力：

- Proxy A 到 Proxy B 的 TCP control channel 数量膨胀；
- Proxy B 侧 UDP relay port、Channel、ctxMap、去重窗口数量膨胀；
- route 抖动时重复 `UDP_ASSOCIATE`，握手和对象分配增加；
- 任一 hop 失效即整组失效时，短时抖动会放大成整条 UDP route 重建。

因此后续优化的核心不是盲目扩大 `hopCount`，而是让多 hop 只服务于真正需要分散端口的大流量或长活跃 UDP route。

## 2. 总体策略

执行优先级如下：

```text
P0：默认自适应，minHopCount=1，热点 route 才扩到 2~3
P0：开启并调优 UDP lease pool，减少频繁 UDP_ASSOCIATE
P1：ctxMap 多 relayAddr 共享同一个 SocksContext，清理时避免全局扫描
P1：单 hop 摘除 + 异步补洞，避免任一 hop 抖动导致整组失效
P2：rxlib 私有 batch relay 申请，1 条 TCP control 承载多个 UDP relay
P2：跨 relay 共享 RDNT 去重窗口后，再考虑 spreadRedundantCopies
```

## 3. 推荐默认配置

### 3.1 普通默认配置

适合游戏、语音、DNS、低中等 UDP 流量。

```java
SocksConfig bConf = new SocksConfig(proxyBPort);
bConf.setUdpPortHoppingEnabled(true);
bConf.setUdpPortHoppingAdaptive(true);
bConf.setUdpPortHoppingMinHopCount(1);
bConf.setUdpPortHoppingMaxHopCount(2);
bConf.setUdpPortHoppingMinActiveHops(1);
bConf.setUdpPortHoppingMode(UdpPortHoppingMode.ROUND_ROBIN);
bConf.setUdpPortHoppingAdaptiveScaleUpBytes(8L * 1024L * 1024L);
bConf.setUdpPortHoppingAdaptiveScaleUpActiveMillis(90_000L);
bConf.setUdpPortHoppingAdaptiveScaleUpCooldownMillis(1500);
```

行为：

- 新 route 默认只占 `1 TCP + 1 UDP relay`；
- 双向累计超过 8 MiB 或活跃超过 90 秒后扩到 2 hop；
- 大多数短连接和低流量 UDP 不会额外占用端口资源。

### 3.2 大流量防限速配置

适合视频、下载、持续大流量 UDP。

```java
bConf.setUdpPortHoppingEnabled(true);
bConf.setUdpPortHoppingAdaptive(true);
bConf.setUdpPortHoppingMinHopCount(1);
bConf.setUdpPortHoppingMaxHopCount(3);
bConf.setUdpPortHoppingMinActiveHops(1);
bConf.setUdpPortHoppingMode(UdpPortHoppingMode.ROUND_ROBIN);
bConf.setUdpPortHoppingAdaptiveScaleUpBytes(4L * 1024L * 1024L);
bConf.setUdpPortHoppingAdaptiveScaleUpActiveMillis(60_000L);
bConf.setUdpPortHoppingAdaptiveScaleUpCooldownMillis(1000);
```

行为：

- 起步仍然只占 1 hop；
- 热点 route 扩到 2~3 hop；
- 不建议默认超过 3 hop，除非已确认链路存在强端口级限速。

### 3.3 固定 hop 极端配置

仅建议用于指定白名单或压测验证。

```java
bConf.setUdpPortHoppingEnabled(true);
bConf.setUdpPortHoppingAdaptive(false);
bConf.setUdpPortHoppingHopCount(4);
bConf.setUdpPortHoppingMinActiveHops(2);
```

风险：

- 并发 route 数量一大，TCP control 和 UDP relay port 会线性膨胀；
- 不建议作为默认线上配置。

## 4. P0：自适应 hop 策略固化

### 4.1 目标

保证端口跳跃默认低开销：

```text
短流量 route：1 hop
热点 route：2~3 hop
异常或资源不足：降级到已有 hop，不阻断业务
```

### 4.2 实施项

1. 在 `SocksConfig` 默认值或示例配置中明确推荐：
   - `adaptive=true`；
   - `minHopCount=1`；
   - `maxHopCount=2~3`；
   - `minActiveHops=1`。
2. 在 `UdpPortHoppingConfig` 或配置校验中增加保护：
   - `maxHopCount` 不建议超过 4；
   - 即使常量允许更大值，日志也要输出 warn；
   - `adaptive=false && hopCount>4` 输出强提醒。
3. 保持 `selectUdpRelayAddress()` 的热点路径只做数组访问和递增索引。
4. `recordUdpTraffic()` 只做轻量累加和阈值判断，不在 I/O 线程中做 DNS、connect、RPC 或阻塞等待。
5. 扩容任务继续通过慢路径异步执行，完成后回到对应 channel 的 EventLoop 追加 holder。

### 4.3 验收标准

- `enabled=true, adaptive=true, minHopCount=1` 时，新 route 初始只创建 1 个 holder；
- 达到字节阈值后单次只扩容 1 个 holder；
- 达到 `maxHopCount` 后不再扩容；
- 扩容失败时不影响已有 UDP 转发；
- 1 万个短 UDP route 不应被固定 hop 放大成 2~4 万条 control channel。

## 5. P0：UDP lease pool 调优

### 5.1 目标

减少每次扩容或 route 初始化时的 TCP connect、SOCKS5 handshake、`UDP_ASSOCIATE` 成本。

### 5.2 推荐配置

```java
bConf.setUdpLeasePoolEnabled(true);
bConf.setUdpLeasePoolMinSize(8);
bConf.setUdpLeasePoolMaxSize(128);
bConf.setUdpLeasePoolMaxIdleMillis(300_000);
```

建议按热点 route 而不是总 route 粗暴估算：

```text
udpLeasePoolMinSize = 预热热点 route 数 * 平均额外 hop
udpLeasePoolMaxSize = 峰值热点 route 数 * maxHopCount
```

例如：

```text
总 UDP route = 1000
预计热点 route = 50
maxHopCount = 3
则 maxSize 可先设 128~192，而不是 1000 * 3
```

### 5.3 实施项

1. 保持 acquire 顺序：
   - lease pool 可用且 facade 可用；
   - `borrow()` lease；
   - `claimUdpRelay(relayPort, clientAddr)`；
   - claim 成功则使用 pooled holder；
   - claim 失败则 close lease，并可回退慢路径。
2. 增加 pool 观测指标：
   - 当前空闲 lease 数；
   - 当前 borrowed lease 数；
   - borrow 命中率；
   - claim 成功/失败次数；
   - reset 成功/失败次数；
   - breaker open 次数。
3. `resetUdpRelay()` 成功后 recycle；失败则 close lease。
4. pool 不足时不要在 I/O 线程阻塞等待；宁愿降级慢路径或保留现有 hop。

### 5.4 验收标准

- 高频 route 创建时，`UDP_ASSOCIATE` 慢路径次数明显下降；
- 关闭 route 后 pooled holder 能 reset 并回收；
- facade 异常或 breaker open 时自动回退标准慢路径；
- pool 耗尽时不会阻塞 EventLoop。

## 6. P1：ctxMap 与 routeMap 优化

### 6.1 目标

端口跳跃后多个 `relayAddr` 应共享同一个 `SocksContext`，避免每个 relay 复制完整上下文。

### 6.2 推荐结构

```text
routeKey -> SocksContext
relayAddr1 -> same SocksContext
relayAddr2 -> same SocksContext
relayAddr3 -> same SocksContext
```

`SessionGroup` 内部保存该 group 的 relay 地址快照：

```java
InetSocketAddress[] snapshotRelayAddresses();
```

清理时只清理本 group 的 relay 地址：

```java
for (InetSocketAddress relay : group.snapshotRelayAddresses()) {
    ctxMap.remove(relay);
}
```

### 6.3 禁止方案

不要在高频清理路径中做全局扫描：

```java
ctxMap.entrySet().removeIf(e -> e.getValue() == context);
```

这种方式在大量 UDP route 和多 hop 下会放大 CPU 抖动。

### 6.4 验收标准

- 一个 route 多个 relayAddr 指向同一个上下文对象；
- route 关闭时只按该 route 的 relayAddr 数组删除；
- 自适应扩容后新增 relayAddr 能补进 ctxMap；
- 清理后无残留 relayAddr 导致误路由。

## 7. P1：单 hop 摘除与异步补洞

### 7.1 背景

当前保守策略是任一 hop control channel 关闭则整组失效。这个策略简单可靠，但在网络抖动场景下会把单 hop 故障放大为整条 route 重建。

### 7.2 目标行为

```text
某个 hop 失效
  -> 从 SessionGroup 中摘除该 holder
  -> 如果 activeHops >= minActiveHops：继续转发
  -> 后台异步 replenish 一个新 holder
  -> 如果 activeHops < minActiveHops：整组失效并重建
```

### 7.3 实施建议

在 `SessionGroup` 中增加小数组替换方法：

```java
boolean removeHolder(SessionHolder holder);
boolean shouldInvalidate(int minActiveHops);
```

处理流程：

1. control channel close listener 回到 relay channel 的 EventLoop；
2. 判断当前 active group 是否还是该 group；
3. 从 `holders` 数组中删除失效 holder；
4. 删除对应 `relayAddr` 的 ctxMap 和 redundant peer；
5. 如果 active 数量足够，继续服务并异步补洞；
6. 如果 active 数量不足，调用原有 invalidateGroup 流程。

### 7.4 注意事项

- 删除 holder 和追加 holder 都必须在 relay channel 的 EventLoop 内完成；
- `holders` 数组仍然使用 copy-on-write 小数组，避免每包路径加锁；
- 补洞失败不影响已有 hop；
- 补洞重试需要 cooldown，避免故障时连续打满控制面。

### 7.5 验收标准

- 3 hop route 中断 1 个 control channel，业务不中断；
- active hop 低于 `minActiveHops` 时才整组失效；
- 失效 relayAddr 从 ctxMap 移除；
- 补洞成功后新 relayAddr 可收包并进入正确上下文。

## 8. P2：私有 batch UDP relay 申请

### 8.1 目标

在 Proxy A 和 Proxy B 都是 rxlib 的场景下，减少多 hop 带来的 TCP control channel 数。

当前标准 SOCKS5 模式：

```text
3 hop = 3 条 TCP control + 3 个 UDP relay
```

rxlib 私有 batch 模式：

```text
3 hop = 1 条 TCP control + 3 个 UDP relay
```

### 8.2 设计方向

优先基于已有 RPC/facade 能力扩展，而不是强行修改标准 SOCKS5 状态机：

```java
List<UdpRelayClaim> claimUdpRelays(int count, InetSocketAddress clientAddr);
boolean resetUdpRelays(List<Integer> relayPorts);
```

或者：

```text
RX_UDP_ASSOCIATE_BATCH(count=N)
  <- relayPort1, relayPort2, relayPort3
```

### 8.3 兼容策略

- 检测 facade 支持 batch 时使用 batch；
- 不支持 batch 时回退当前多次 `UDP_ASSOCIATE` / lease pool；
- batch 模式仅用于 rxlib 自有 A/B 链路，不影响第三方 SOCKS5 兼容性。

### 8.4 验收标准

- `maxHopCount=3` 时 batch 模式只建立 1 条 TCP control；
- 三个 UDP relay port 都可独立收发；
- 任一 relay 失效时可 reset 单个 port 或整批 port；
- 第三方 SOCKS5 upstream 不受影响。

## 9. P2：跨 relay RDNT 去重窗口

### 9.1 背景

当前不能把同一个 RDNT 冗余组的多个副本拆到不同 relay port，因为远端按 UDP channel 维护去重窗口，跨端口会被视为多个首次包，导致真实目标收到重复 payload。

### 9.2 前置条件

只有实现远端跨 relay 共享去重窗口后，才能启用：

```java
spreadRedundantCopies = true;
```

### 9.3 设计方向

- 去重 key 不能只依赖 channel；
- 建议使用：`clientRouteId + destAddr + seqId`；
- 去重窗口挂在 Proxy B 的逻辑 route 或 batch group 上；
- 多个 relay channel 共享同一个 recent seq window。

### 9.4 验收标准

- 同一 RDNT 组副本分散到多个 relay 后，真实目标只收到一份 payload；
- 重复包统计可观测；
- 去重窗口过期后不会长期占用内存。

## 10. 测试计划

### 10.1 单元测试

新增或补强测试：

```text
SocksUdpUpstreamPortHoppingTest
UdpPortHoppingConfigTest
SocksUdpRelayHandlerTest
SSUdpProxyHandlerTest
Udp2rawHandlerTest
```

覆盖点：

- `minHopCount=1` 初始只创建 1 hop；
- 达到 bytes/time 阈值后扩容；
- cooldown 内不会重复扩容；
- `maxHopCount` 生效；
- relayAddr 去重，重复 relay 不加入 group；
- `ROUND_ROBIN` 分布正确；
- `RANDOM` 不越界且只选有效 holder；
- 单 hop close 后摘除并补洞；
- active hop 不足时整组失效。

### 10.2 集成测试

场景 4：

```text
ShadowsocksClient -> ShadowsocksServer -> SocksServerProxy A
  -> SOCKS5 UDP upstream -> SocksServerProxy B -> UDP dest
```

验证点：

- A->B 抓包可以看到多个 B relay port；
- 同一逻辑包的 RDNT 副本不跨 relay；
- Proxy B 的真实目标不收到重复业务 payload；
- A/B 任一端关闭时 holder 和 lease 能释放；
- lease pool 开启后慢路径 `UDP_ASSOCIATE` 次数下降。

### 10.3 压测指标

建议记录：

```text
socks.udp.porthop.group.active.count
socks.udp.porthop.acquire.count
socks.udp.porthop.adaptive.scale.count
socks.udp.session.invalidate.count
socks.udp.lease.pool.idle.count
socks.udp.lease.pool.borrowed.count
socks.udp.lease.pool.claim.count
socks.udp.lease.pool.reset.count
```

压测维度：

- 并发 UDP route：100 / 1000 / 5000；
- `maxHopCount`：1 / 2 / 3 / 4；
- lease pool：关闭 / 开启；
- control channel 随机断开；
- UDP relay 随机丢包和延迟。

## 11. 分阶段落地清单

### 阶段 A：配置和默认策略固化

- [ ] 文档明确推荐 adaptive 默认值；
- [ ] 对固定 `hopCount>4` 输出 warn；
- [ ] 示例配置全部改成 `minHopCount=1,maxHopCount=2~3`；
- [ ] 补充 metrics 名称和含义。

### 阶段 B：lease pool 观测和保护

- [ ] 增加 borrow/claim/reset 指标；
- [ ] pool 耗尽不阻塞 EventLoop；
- [ ] breaker open 后自动回退慢路径；
- [ ] reset 失败时 close lease，不回收脏连接。

### 阶段 C：ctxMap 清理优化

- [ ] 每个 route 保存 relayAddr 快照；
- [ ] 扩容后新增 relayAddr 立即注册；
- [ ] route 清理只删除本 route relayAddr；
- [ ] 增加无残留断言测试。

### 阶段 D：单 hop 摘除 + 补洞

- [ ] `SessionGroup.removeHolder()`；
- [ ] control close 不再默认整组失效；
- [ ] active hop 足够时继续服务；
- [ ] 后台异步 replenish；
- [ ] active hop 不足才 invalidate group。

### 阶段 E：batch relay 私有协议

- [ ] 设计 `claimUdpRelays/resetUdpRelays` RPC；
- [ ] Proxy B 批量创建 relay port；
- [ ] Proxy A 一个 control channel 绑定多个 relay；
- [ ] 不支持 batch 时回退标准 SOCKS5；
- [ ] 压测对比 TCP control 数量下降比例。

### 阶段 F：跨 relay RDNT 去重

- [ ] 设计跨 relay routeId；
- [ ] Proxy B 多 relay 共享去重窗口；
- [ ] 启用 `spreadRedundantCopies`；
- [ ] 验证真实目标不收到重复 payload。

## 12. 最终推荐结论

短期上线策略：

```text
adaptive=true
minHopCount=1
maxHopCount=2~3
minActiveHops=1
ROUND_ROBIN
lease pool enabled
spreadRedundantCopies=false
```

中期优化策略：

```text
单 hop 摘除
异步补洞
ctxMap 精确清理
更完整 metrics
```

长期最优策略：

```text
rxlib 私有 batch UDP relay 申请
1 条 TCP control 管理多个 UDP relay port
跨 relay 共享 RDNT 去重窗口
```

核心原则：端口跳跃不是默认把每个 UDP route 放大 N 倍，而是让热点 route 按需扩容，把控制面开销限制在可控范围内。