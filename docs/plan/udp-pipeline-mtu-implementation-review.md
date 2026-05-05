# UDP pipeline MTU 实现 Review 计划与结论

# 背景

用户说明 `docs/plan/udp-pipeline-mtu-unified-entry-plan.md` 对应代码已经实现，要求再次 review 当前实现。

本次 review 基于分支：

- `agent/udp-pipeline-mtu-plan`
- 实现 commit：`ded9125bce41a223e429b0b11e1fb5243ed16880`
- 对比基线：`master` / `bb910a5416dc15af40544a39bef39e5cefa09f9b`

本轮只做 review 和计划文档提交，不直接修改业务代码。

# 任务类型判断

本次任务归类为 **Review / 修复 / 重构 / 优化需求**。

原因：

- 用户要求“再 review 下”，目标是检查已实现代码是否满足前一版计划。
- 需要分析 UDP 背压、压缩、多倍发送、MTU 出口约束的落地正确性。
- 按仓库 agent 规则，Review 类任务先提交 review/计划文档，等用户明确要求后再进入代码修复阶段。

# 当前上下文

## 已 review 的文件

实现 commit 修改了以下文件，本次均已 review：

- `rxlib/src/main/java/org/rx/net/SocketConfig.java`
- `rxlib/src/main/java/org/rx/net/Sockets.java`
- `rxlib/src/main/java/org/rx/net/socks/SSUdpProxyHandler.java`
- `rxlib/src/main/java/org/rx/net/socks/SocksConfig.java`
- `rxlib/src/main/java/org/rx/net/socks/SocksUdpRelayHandler.java`
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawHandler.java`
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawSession.java`
- `rxlib/src/test/java/org/rx/net/SocketsTest.java`

同时结合前序计划和现有 UDP handler 行为，重点复核了：

- `Sockets.writeUdp(...)` 的 backpressure / pending / MTU guard。
- `Sockets.addUdpOptimizationHandlers(...)` 的 UDP compression / redundant handler 安装顺序。
- `UdpRedundantEncoder` 的真实出站 datagram 生成方式。
- `SocksUdpRelayHandler`、`SSUdpProxyHandler`、`Udp2rawHandler`、`Udp2rawSession` 对统一写出口的调用。
- `SocketsTest` 中新增的 MTU 单测覆盖范围。

## 关键调用链

### 当前实现的写出链路

当前实现中，业务 handler 调用：

```java
Sockets.writeUdp(channel, datagramPacket, config, metricPrefix, tags)
```

`Sockets.writeUdp(...)` 中依次做：

1. null / inactive / unresolved recipient 检查。
2. `udpMtu` 检查。
3. pending bytes 计数与 pending limit 检查。
4. `channel.isWritable()` 检查。
5. `channel.writeAndFlush(packet)`。

### 当前实现的 UDP optimization pipeline

`Sockets.udpBootstrap(...)` 中，如果 `finalConfig instanceof SocksConfig`，会自动调用：

```java
addUdpOptimizationHandlers(ch.pipeline(), (SocksConfig) finalConfig);
```

`addUdpOptimizationHandlers(...)` 会按配置安装：

- `UdpRedundantDecoder`
- `UdpCompressDecoder`
- `UdpRedundantEncoder`
- `UdpCompressEncoder`

其中 outbound 实际执行顺序需要按 Netty tail -> head 规则理解。以当前 addLast 顺序看，outbound 大致会先经过 `UdpCompressEncoder`，再经过 `UdpRedundantEncoder`，最后到 transport。

# 目标

1. 判断当前实现是否满足“最终真实 UDP 出口 <= udpMtu”的目标。
2. 判断背压、pending bytes、MTU、压缩、多倍发送是否已收敛到可靠统一入口。
3. 找出必须修复的问题与建议修复方案。
4. 给出后续验证方案，特别是 JDK8 CI 和必要单测。

# 非目标

1. 本轮不直接修改业务代码。
2. 本轮不触发或伪造 CI 通过结果。
3. 不扩大到 TCP、HTTP tunnel、非 UDP pipeline 的重构。
4. 不引入新依赖或调整 release 发布流程。

# 设计方案

## Review 结论概览

当前实现完成了以下正向改进：

1. `SocketConfig` 新增了 `udpMtu`，默认 0，setter 做了非负保护，语义注释也明确“不含 IP/UDP header”。
2. `Sockets.writeUdp(...)` 新增了带 `SocketConfig` 的 overload，并能记录 `MTU_EXCEEDED`、`mtu.drop.count`、`mtu.drop.bytes` 等指标。
3. `SocksUdpRelayHandler`、`SSUdpProxyHandler`、`Udp2rawHandler`、`Udp2rawSession` 的一部分直接 UDP 写出已改为调用 `Sockets.writeUdp(...)`。
4. `SocketsTest` 增加了 MTU disabled、等于 MTU、超过 MTU、MTU drop metrics 等基础测试。

但当前实现还没有满足最关键目标：**确保压缩、多倍发送等 pipeline 处理后的最终真实 UDP datagram 小于等于 `udpMtu`**。

原因是当前 MTU 检查发生在 `Sockets.writeUdp(...)` 调用 `channel.writeAndFlush(packet)` 之前，而 UDP compression / redundant encoder 是 Netty outbound handler，会在 `channel.writeAndFlush(packet)` 之后才真正执行。也就是说，当前 MTU guard 检查的是进入 outbound pipeline 之前的 packet，不是最终 transport 层真实写出的 packet。

## 发现 1：MTU 检查位置不是真正 final egress，无法保证最终包 <= udpMtu

严重级别：高。

当前 `Sockets.writeUdp(...)` 的 MTU 检查在：

```java
int bytes = packet.content().readableBytes();
int udpMtu = udpMtu(channel, config);
if (udpMtu > 0 && bytes > udpMtu) {
    releaseUdpPacketByMtu(...);
    return UdpWriteResult.MTU_EXCEEDED;
}
channel.writeAndFlush(packet)
```

如果 channel pipeline 里存在 `UdpCompressEncoder` 或 `UdpRedundantEncoder`，这些 outbound handler 会在 `channel.writeAndFlush(packet)` 之后执行。因此会出现两类问题：

1. 原始 packet 小于等于 `udpMtu`，但 outbound encoder 加 header 后超过 `udpMtu`，当前实现仍会发送。
2. 原始 packet 大于 `udpMtu`，但 compression encoder 本可以压缩到小于等于 `udpMtu`，当前实现会提前 drop。

特别是 `UdpRedundantEncoder` 会在 payload 前添加 8 字节 header，并可能复制出多个 datagram。当前 `Sockets.writeUdp(...)` 无法看到这些真正出站的 datagram。

建议修复：

- 把 MTU guard 从 `Sockets.writeUdp(...)` 的前置检查中拆出来，改成一个真正靠近 transport/head 的 outbound handler，例如 `UdpMtuGuardHandler`。
- 在 pipeline 顺序上保证 outbound 执行顺序为：业务写出 -> compression encoder -> redundant encoder -> mtu guard -> transport。
- 按 Netty outbound tail -> head 规则，`UdpMtuGuardHandler` 应放在比 compression/redundant encoder 更靠近 head 的位置，或者让 `UdpRedundantEncoder` 每次生成真实 datagram 时显式调用 final guard。
- `Sockets.writeUdp(...)` 可继续保留 inactive、pending、`isWritable()` 等提交前保护，但不要声称它已经保证“最终 packet size <= udpMtu”。

## 发现 2：UdpRedundantEncoder 生成的副本绕过了 Sockets.writeUdp 的 pending/backpressure/MTU 统计

严重级别：高。

`UdpRedundantEncoder` 在 outbound handler 内部调用：

```java
ctx.write(new DatagramPacket(...), promise)
ctx.write(new DatagramPacket(...), ctx.voidPromise())
```

并且在有 interval 时还会 schedule 延迟副本。

这意味着：

1. `Sockets.writeUdp(...)` 只对原始业务 packet 做了一次 pending bytes 计数。
2. redundant encoder 后续生成的多个真实 datagram 不会再进入 `Sockets.writeUdp(...)`。
3. 延迟副本发送时也不会参与 `Sockets.writeUdp(...)` 的 pending limit 和 MTU 结果枚举。
4. `ctx.voidPromise()` 会弱化失败可观测性，至少不利于统一统计每个真实 datagram 的 write failure。

建议修复：

- 如果保留 `UdpRedundantEncoder` 当前结构，必须在 encoder 生成每个真实 `DatagramPacket` 后、`ctx.write(...)` 前调用 final MTU/backpressure guard。
- 更推荐将 final MTU guard 设计为 outbound handler，放在 redundant encoder 之后、transport 之前，保证 encoder 产生的每个 datagram 都经过它。
- 对延迟副本也必须经过同一个 final guard。
- pending bytes 统计需要明确语义：是业务提交前 pending，还是最终 datagram pending。若目标是出口真实背压，应以最终 datagram bytes 为准。

## 发现 3：压缩/多倍发送统一入口只在 udpBootstrap 自动安装，覆盖范围仍需核对

严重级别：中。

`Sockets.udpBootstrap(...)` 中仅在：

```java
if (finalConfig instanceof SocksConfig) {
    addUdpOptimizationHandlers(...)
}
```

时自动安装 UDP optimization handlers。

这对普通 `SocksConfig` UDP channel 有效果，但需要继续确认以下路径是否都经过该 bootstrap 且 config 类型满足条件：

- `ShadowsocksServer` 的 `ShadowsocksConfig` 是否应该支持同一套 UDP 压缩/多倍发送能力。
- `Udp2rawServerEntryManager` 创建的 fixed entry channel 是否都经过 `Sockets.udpBootstrap(...)`。
- 其他手动创建或复用的 UDP channel 是否需要显式调用 `addUdpOptimizationHandlers(...)`。

如果需求是“org.rx.net package 下 UDP 压缩和 UDP 多倍发送统一入口”，当前实现更像是“在 `Sockets.udpBootstrap` 中对 `SocksConfig` 自动安装”，还不是完整的统一入口约束。

建议修复：

- 明确 UDP optimization 能力只支持 `SocksConfig`，还是也支持 `ShadowsocksConfig`。
- 对每个 UDP channel 创建点列清单，确认是否已经经过统一安装。
- 对不经过 `udpBootstrap` 的 UDP channel，补充显式安装或说明非目标。

## 发现 4：测试覆盖没有覆盖“final after compression/redundant”的核心场景

严重级别：中。

新增 `SocketsTest` 主要覆盖的是直接调用 `Sockets.writeUdp(...)` 的基础场景：

- disabled。
- 等于 MTU。
- 超过 MTU。
- MTU metrics 低基数 tag。

这些测试不能证明以下关键目标：

1. 压缩后再判断 MTU。
2. redundant encoder 添加 header 后再判断 MTU。
3. redundant encoder 生成多个副本时，每个真实 datagram 都分别经过 MTU guard。
4. 原始 payload > MTU 但压缩后 <= MTU 时允许发送。
5. 原始 payload <= MTU 但 redundant/compression 后 > MTU 时阻止发送。

建议补充测试：

- EmbeddedChannel pipeline 加入 `UdpCompressEncoder` / `UdpRedundantEncoder` / `UdpMtuGuardHandler` 的顺序测试。
- 构造 1298 bytes payload + redundant 8 bytes header，在 `udpMtu=1300` 时必须 drop 最终 1306 bytes datagram。
- 构造可压缩 payload，原始 > 1300，压缩后 <= 1300，验证允许发送。
- multiplier > 1 时，确认每个 outbound datagram 均不超过 MTU。

## 发现 5：`SocksConfig` 删除 `kcptunClient` 属于无关变更，存在 API 兼容风险

严重级别：中。

本次 diff 中 `SocksConfig` 删除了：

```java
private AuthenticEndpoint kcptunClient;
```

该修改与 UDP MTU、背压、压缩、多倍发送目标无关。由于 `SocksConfig` 使用 Lombok `@Getter` / `@Setter`，删除字段会删除公开 getter/setter，可能破坏外部配置或历史 API。

建议修复：

- 如无明确需求，恢复该字段。
- 如果确实要删除，应单独提交并说明迁移路径，不应混入 UDP MTU 实现。

## 发现 6：当前分支没有 GitHub Actions 验证记录

严重级别：中。

查询 `agent/udp-pipeline-mtu-plan` 分支的 GitHub Actions workflow run，当前返回 0 条记录。

建议：

- 后续修复 commit 后触发 `jdk8-unit-tests.yml`。
- `test_classes` 至少包含 `org.rx.net.SocketsTest`，以及新增的 UDP pipeline / redundant / compression 测试类。
- 只有 `conclusion=success` 才能认为 CI 通过。

# 修改文件列表

本轮 review 文档新增：

- `docs/plan/udp-pipeline-mtu-implementation-review.md`

如果后续执行修复，预计需要修改：

- `rxlib/src/main/java/org/rx/net/Sockets.java`
  - 拆分提交前 write guard 与 final MTU guard。
  - 或新增 final outbound guard 的安装入口。
- `rxlib/src/main/java/org/rx/net/socks/UdpRedundantEncoder.java`
  - 确保每个真实冗余 datagram 经过 final MTU/backpressure guard。
  - 避免延迟副本绕过统一出口策略。
- `rxlib/src/main/java/org/rx/net/socks/UdpCompressEncoder.java`
  - 一般不需要大改，但需要配合 handler 顺序测试。
- `rxlib/src/main/java/org/rx/net/socks/SocksConfig.java`
  - 恢复无关删除的 `kcptunClient`，或单独解释并拆分。
- `rxlib/src/test/java/org/rx/net/SocketsTest.java`
  - 保留基础 `writeUdp` 测试。
- 新增或扩展 UDP pipeline 测试类
  - 覆盖 compression / redundant / final mtu guard / handler order。

# 风险点

1. **最终 MTU 语义风险**：当前实现可能只保证进入 outbound pipeline 前的大小，而不是真正发送出去的 datagram 大小。
2. **压缩误 drop 风险**：原始包超过 MTU 但压缩后可以满足 MTU 的情况会被提前丢弃。
3. **冗余头部越界风险**：原始包未超 MTU，但 redundant header 增加后可能超过 MTU 仍被发送。
4. **背压统计偏差**：冗余副本和延迟副本没有纳入 `Sockets.writeUdp` 的 pending bytes 统计。
5. **API 兼容风险**：`kcptunClient` 删除属于无关 API 变化。
6. **验证风险**：当前无 Actions run，不能判断 JDK8 编译与测试是否通过。

# 验证方案

## 修复前可复现测试建议

1. 直接用当前实现构造一个 pipeline：
   - `udpMtu = 1300`
   - `UdpRedundantEncoder(multiplier=2)`
   - payload 1298 bytes
   - 预期最终 redundant datagram 1306 bytes，应 drop。
   - 当前实现大概率会在 `Sockets.writeUdp` 前置检查通过后发送。
2. 构造 compress 场景：
   - `udpMtu = 1300`
   - 原始 payload 2000 bytes，但高度可压缩。
   - 预期压缩后 <= 1300 可以发送。
   - 当前实现大概率在压缩前 drop。

## 修复后必须验证

1. `mvn -pl rxlib -am test -DskipTests`
2. `mvn -pl rxlib -am test -Dtest=SocketsTest,<新增UDP pipeline测试类>`
3. GitHub Actions：
   - 触发或依赖 `jdk8-unit-tests.yml`。
   - 分支过滤：`agent/udp-pipeline-mtu-plan` 或后续修复分支。
   - `test_classes` 包含 `org.rx.net.SocketsTest` 和新增 UDP 测试类。
   - 只有 `conclusion=success` 才视为通过。

# 建议修复方案

## 最小修复路线

1. 新增 `UdpMtuGuardHandler`，作为 `ChannelOutboundHandlerAdapter`。
2. 在 `addUdpOptimizationHandlers(...)` 中按 Netty outbound 顺序安装：
   - inbound decoder 仍按当前顺序。
   - outbound encoder 后的最终 guard 必须位于更靠近 head 的位置，确保它看到 compression/redundant 之后的真实 datagram。
3. `Sockets.writeUdp(...)` 保留 channel active、unresolved recipient、pending limit、`isWritable()` 检查；MTU 检查可以：
   - 仅在 pipeline 未安装 final guard 时兜底；或
   - 移除前置 MTU drop，统一由 final guard 处理。
4. `UdpRedundantEncoder` 的延迟副本也必须经过 final guard；若 final guard 位于 encoder 之后且靠近 head，则 `ctx.write(...)` 会自然经过它。
5. 补齐压缩和冗余后的 final size 测试。
6. 恢复 `SocksConfig.kcptunClient` 或拆分为独立兼容性变更。

## 更彻底修复路线

1. 将 UDP 提交前背压、最终出口背压、最终 MTU guard 拆成三个职责：
   - `Sockets.writeUdpSubmit(...)`：提交前基本保护。
   - `UdpFinalEgressGuardHandler`：最终 datagram 的 MTU 和 backpressure 保护。
   - `UdpOptimizationInstaller`：统一安装 compression / redundant / final guard。
2. 让所有 UDP channel 创建点都通过同一 installer。
3. 让 metrics 区分 submit drop 与 final egress drop。

# 当前 Review 结论

当前实现方向是对的，但还不能认为已经满足用户最关键要求：

> pipeline 经过压缩、多倍等，最后出口是真实小于等于 1300。

原因是当前 `Sockets.writeUdp(...)` 的 MTU 检查发生在 outbound compression / redundant encoder 之前，而真正改变 datagram 大小和数量的是后续 outbound handler。需要把 MTU guard 移到最终出站 datagram 位置，或者让 `UdpRedundantEncoder` 等生成真实 datagram 的位置调用统一 final guard。
