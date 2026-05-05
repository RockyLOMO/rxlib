# UDP 背压、压缩/多倍发送统一入口与 MTU 能力计划

# 背景

用户要求在 `rockylomo/rxlib` 中，针对 `org.rx.net.socks` 包下的 UDP 链路做 review 和方案设计，重点包括：

- `ShadowsocksServer.java`
- `SocksProxyServer.java`
- `Udp2rawHandler.java`
- UDP 背压是否能抽出公共方法，形成统一入口并复用。
- UDP 压缩和 UDP 多倍/冗余发送是否能抽出公共统一入口。
- 评估当前 UDP 部分是否已有 MTU 相关设置。
- 设计 `udpMtu = 1300` 能力，确保 UDP pipeline 经过压缩、多倍发送、协议包装等处理后，最终真实 UDP datagram payload 长度小于等于 1300。

本文档仅作为详细计划方案，不修改业务代码。

# 任务类型判断

本次任务归类为 **Review / 修复 / 重构 / 优化需求**。

原因：

- 用户描述是“看下”“能否抽出”“评估”“先出详细计划方案”。
- 当前目标是先理解现有 UDP 调用链和风险点，再设计重构与新增能力方案。
- 按仓库 agent 规则，Review 类任务必须先提交计划文档，等待用户明确要求后再进入代码实现阶段。

# 当前上下文

## 已 review 的文件

- `rxlib/src/main/java/org/rx/net/socks/ShadowsocksServer.java`
- `rxlib/src/main/java/org/rx/net/socks/SocksProxyServer.java`
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawHandler.java`
- `rxlib/src/main/java/org/rx/net/socks/SocksUdpRelayHandler.java`
- `rxlib/src/main/java/org/rx/net/socks/SSUdpProxyHandler.java`
- `rxlib/src/main/java/org/rx/net/socks/SocksConfig.java`
- `rxlib/src/main/java/org/rx/net/Sockets.java`
- UDP 压缩相关：`UdpCompressConfig.java`、`UdpCompressEncoder.java`、`UdpCompressDecoder.java`、`UdpCompressSupport.java`、`UdpCompressCodec.java`、`UdpCompressStats.java`
- UDP 多倍/冗余发送相关：`UdpRedundantConfig.java`、`UdpRedundantEncoder.java`、`UdpRedundantDecoder.java`、`UdpRedundantStats.java`、`UdpRedundantDestinationRule.java`、`UdpRedundantMultiplierResolver.java`
- UDP relay 状态相关：`UdpRelayAttributes.java`、`UdpRelayGroupManager.java`、`UdpManager.java`

## 关键调用链

### Shadowsocks UDP

`ShadowsocksServer` 为 UDP server 创建 pipeline：

1. `Sockets.udpBootstrap(config, ctx -> { ... })`
2. 创建 UDP 用 `ICrypto`
3. 添加 `CipherCodec.DEFAULT`、`SSProtocolCodec`、`SSUdpProxyHandler.DEFAULT`
4. 通过 `Sockets.bindChannels(...)` 绑定 UDP server endpoint

该链路的 UDP 出口主要落在 `SSUdpProxyHandler`，涉及 SS 协议包装/解包和 UDP relay 发送。

### SOCKS UDP

`SocksProxyServer` 主要管理 TCP SOCKS5 控制链路、UDP relay registry、udp2raw entry manager、relay group。SOCKS5 UDP_ASSOCIATE 创建 UDP relay channel 后，数据路径主要落到：

- `SocksUdpRelayHandler`：普通 SOCKS UDP relay。
- `Udp2rawHandler`：udp2raw tunnel 后的 per-client relay。

`SocksProxyServer` 还维护：

- `udpRelayRegistry`：relayPort -> relay channel。
- `UdpRelayGroupManager`：relay group 增删、heartbeat、capabilities。
- `Udp2rawServerEntryManager`：server 模式 udp2raw fixed entry。

### Udp2raw UDP

`Udp2rawHandler` 同时处理 client mode 和 server mode：

- Client mode：本地 SOCKS5 UDP -> decode SOCKS5 header -> wrap udp2raw -> send to udp2raw server。
- Client mode response：udp2raw server response -> unwrap -> send SOCKS5 UDP packet back to local app。
- Server mode：udp2raw client packet -> unwrap -> route -> send to real destination。
- Server mode response：real destination response -> wrap udp2raw -> send back to udp2raw client。

当前 `Udp2rawHandler` 中有多处直接 `relay.writeAndFlush(new DatagramPacket(...))` 出口。它们没有统一经过普通 UDP relay 的公共写出口，因此背压、MTU、metrics、ByteBuf release 策略难以统一。

## 当前实现意图与初步发现

1. 普通 SOCKS/SS UDP 路径已经存在或接近存在 `Sockets.writeUdp(...)` 这类公共写出入口，适合作为统一背压与 final MTU guard 的承载点。
2. `Udp2rawHandler` 仍有多处直接写出，应改为复用统一 UDP final egress 方法。
3. UDP 压缩能力由 `UdpCompress*` 系列类承担，UDP 多倍/冗余发送由 `UdpRedundant*` 系列类承担，但安装入口、执行顺序、最终出口校验还没有收敛成一个统一概念。
4. 本轮 review 未发现统一的 `udpMtu` / `mtu` / `maxDatagramSize` 配置，尤其没有发现“最终真实 UDP datagram 写出前”的统一 MTU 限制。

# 目标

1. 抽出 UDP 出站背压公共方法，供 `SocksUdpRelayHandler`、`SSUdpProxyHandler`、`Udp2rawHandler` 复用。
2. 抽出 UDP 压缩和 UDP 多倍/冗余发送的统一 pipeline 安装入口，确保顺序可控、场景可复用。
3. 新增 UDP MTU 能力，支持配置示例 `udpMtu = 1300`。
4. 确保 MTU 判断发生在最终 UDP 出口前，也就是压缩、多倍发送、SOCKS/SS/udp2raw 包装全部完成后，对每个真实 `DatagramPacket` 检查 `packet.content().readableBytes() <= udpMtu`。
5. 保持最小改动，不影响 TCP 链路，不引入重型依赖，不使用 JDK9+ API。

# 非目标

1. 本阶段不修改业务代码。
2. 不实现 UDP fragmentation/reassembly。如果最终 packet 大于 MTU，方案默认 drop 并记录，而不是自动分片。
3. 不修改 TCP proxy、TCP warm pool、HTTP tunnel 等非 UDP 路径。
4. 不升级 Netty/Maven 大版本依赖。
5. 不修改 secrets、token、证书、私钥。
6. 不发布 release。

# 设计方案

## 方案 1：统一 UDP final egress 写出入口

建议新增一个小型支持类，优先命名为 `UdpPipelineSupport`。如果为减少改动，也可以先在 `Sockets` 中扩展 `writeUdp(...)`，再由新类或桥接方法复用。

建议方法：

```java
static ChannelFuture writeUdp(Channel channel,
                              DatagramPacket packet,
                              SocksConfig config,
                              String path);
```

兼容已有调用时可以提供 overload：

```java
public static ChannelFuture writeUdp(Channel channel, DatagramPacket packet, SocksConfig config);
public static ChannelFuture writeUdp(Channel channel, DatagramPacket packet, SocksConfig config, String path);
```

处理顺序：

1. `channel == null || !channel.isActive()`：释放 packet，记录 inactive/drop。
2. `!channel.isWritable()`：不直接 `writeAndFlush`，释放 packet，记录 `socks.udp.backpressure.drop.count`。
3. `config.getUdpMtu() > 0 && packet.content().readableBytes() > config.getUdpMtu()`：释放 packet，记录 `socks.udp.mtu.drop.count` 和可选 debug log。
4. 通过 `channel.writeAndFlush(packet)` 正常写出。
5. write future failure 时记录 `socks.udp.write.fail.count`，但不改变 Netty 已接管的引用生命周期。

调用点收敛：

- `SocksUdpRelayHandler` 内所有 UDP 写出走统一方法。
- `SSUdpProxyHandler` 内所有 UDP 写出走统一方法。
- `Udp2rawHandler` 内直接 `relay.writeAndFlush(new DatagramPacket(...))` 替换为统一方法。

这是“UDP 背压抽出公共方法统一入口”的最小闭环。

## 方案 2：统一 UDP 压缩和多倍发送 pipeline 安装入口

建议新增：

```java
static void addUdpPipelineHandlers(ChannelPipeline pipeline,
                                   SocksConfig config,
                                   UdpPipelineRole role);
```

`UdpPipelineRole` 可先设计为：

```java
enum UdpPipelineRole {
    SOCKS_RELAY,
    SHADOWSOCKS_RELAY,
    UDP2RAW_RELAY,
    UDP2RAW_ENTRY
}
```

概念顺序：

- inbound：redundant decoder -> compress decoder -> protocol handler。
- outbound：protocol wrapping -> compress encoder -> redundant encoder -> final egress write method。

注意 Netty outbound handler 的执行顺序与 `addLast` 位置相关，实际实现必须以现有 `UdpCompressEncoder` / `UdpRedundantEncoder` 的 handler 类型为准，并用单测验证顺序。

统一安装的好处：

- `ShadowsocksServer` UDP bootstrap 不再感知压缩/冗余细节。
- SOCKS UDP relay channel 创建处不再分散安装 handler。
- udp2raw relay / entry channel 可以复用同一套安装逻辑。
- 后续增加 pacing、rate limit、final guard 等能力时有固定入口。

## 方案 3：新增 `udpMtu` 配置

在 `SocksConfig` 增加：

```java
/**
 * Max final UDP datagram payload bytes after all protocol wrapping,
 * compression and redundancy. <= 0 means disabled.
 */
private int udpMtu;
```

默认值必须为 `0`，表示 disabled，保持兼容旧行为。

命名建议使用 `udpMtu` 而不是泛化 `mtu`，避免与 TCP/MSS 或网卡 MTU 混淆。该值表示 final UDP datagram content bytes，不包括 IP/UDP header。

如 `ShadowsocksConfig` 不是 `SocksConfig` 派生，需要在实现阶段确认是上抽配置还是分别增加同名字段；优先避免破坏已有配置结构。

## 方案 4：MTU 检查位置

为了保证 `udpMtu = 1300` 的语义准确，不能在以下位置提前判断：

- 原始入站 payload 刚收到时。
- SOCKS5/SS/udp2raw 头部还未包装完成时。
- 压缩前。
- redundant encoder 还没生成多个真实 datagram 前。

必须在最终 `DatagramPacket` 写出前检查：

```java
int size = packet.content().readableBytes();
if (udpMtu > 0 && size > udpMtu) {
    // release + metric + log + no write
}
```

对多倍/冗余发送：

- 每个实际 `DatagramPacket` 单独检查 `size <= udpMtu`。
- 不按 `size * multiplier` 判断。
- 不把多倍发送总字节数当成单包 MTU。

对压缩：

- 原始 payload > 1300 但压缩后 final <= 1300，应允许写出。
- 压缩/包装后 final > 1300，应由 final egress drop。
- 因此不能在压缩前按原始长度 drop。

## 方案 5：ByteBuf release 策略

统一写出口接管 `DatagramPacket` 后，采用以下规则：

- 成功提交给 Netty：不手动 release。
- inactive / backpressure / mtu drop：由统一方法 release packet。
- 写出前抛异常：由统一方法 release packet 并记录。
- 返回 failed future 时，不要求调用方再次 release，避免 double release。

## 方案 6：metrics 和日志

建议新增或复用 metrics key：

- `socks.udp.write.count`
- `socks.udp.write.fail.count`
- `socks.udp.backpressure.drop.count`
- `socks.udp.mtu.drop.count`
- `socks.udp.mtu.drop.bytes`

建议 tag / path：

- `path=socks`
- `path=ss`
- `path=udp2raw-client`
- `path=udp2raw-server`

高频 UDP 路径中 debug log 必须受 `config.isDebug()` 控制，避免日志冲击性能。

# 修改文件列表

## 必选

- `rxlib/src/main/java/org/rx/net/socks/SocksConfig.java`
  - 新增 `udpMtu` 配置，默认 0。
- `rxlib/src/main/java/org/rx/net/Sockets.java` 或新增 `rxlib/src/main/java/org/rx/net/socks/UdpPipelineSupport.java`
  - 新增/改造 UDP 统一写出入口。
  - 新增/改造 UDP optimization pipeline 安装入口。
- `rxlib/src/main/java/org/rx/net/socks/SocksUdpRelayHandler.java`
  - UDP 写出收敛到统一出口。
- `rxlib/src/main/java/org/rx/net/socks/SSUdpProxyHandler.java`
  - UDP 写出收敛到统一出口。
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawHandler.java`
  - 替换直接 `writeAndFlush` 为统一 UDP 出口。

## 可能需要

- `rxlib/src/main/java/org/rx/net/socks/ShadowsocksServer.java`
  - 如果 UDP 压缩/冗余 handler 安装需要在 bootstrap 上统一，改为调用统一安装入口。
- `rxlib/src/main/java/org/rx/net/socks/Socks5CommandRequestHandler.java`
  - 如果 UDP relay channel 创建处直接安装 compress/redundant handler，改为统一入口。
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawServerEntryManager.java`
  - fixed entry channel 如需统一安装 UDP optimization handlers，需要接入统一入口。
- `rxlib/src/main/java/org/rx/net/socks/UdpRedundantEncoder.java`
  - 如内部生成多个 packet 但绕过 final egress，需要调整为最终也经过统一 MTU/backpressure guard。
- `rxlib/src/main/java/org/rx/net/socks/UdpCompressEncoder.java`
  - 一般不应改；仅在 handler 顺序无法统一时小范围调整。
- 测试文件
  - 新增 MTU、背压、压缩、多倍发送和 udp2raw 收敛相关测试。

# 风险点

1. **兼容性风险**：`udpMtu` 默认必须为 0/disabled，否则会改变现有 UDP 行为。
2. **性能风险**：UDP 是高频路径，统一出口不能引入阻塞队列、同步锁或大量对象分配。
3. **pipeline 顺序风险**：Netty outbound/inbound 顺序易错，必须通过单测验证 compress、redundant、final guard 的实际顺序。
4. **资源释放风险**：drop 路径必须 release，成功写出不得提前 release，避免 leak 或 double release。
5. **并发风险**：UDP relay channel attributes 和 ctx map 可能跨事件循环访问，统一入口不能破坏原有 event loop 约束。
6. **MTU 语义风险**：`udpMtu = 1300` 表示 UDP datagram content bytes，不含 IP/UDP header，需要在注释中说明。
7. **功能风险**：本方案对超 MTU packet 默认 drop，不做 fragmentation。大 UDP payload 场景会出现可观测丢包，但行为更明确。

# 验证方案

## 本地验证

编译：

```bash
mvn -pl rxlib -am test -DskipTests
```

相关单测：

```bash
mvn -pl rxlib -am test -Dtest=<相关UDP测试类>
```

## 建议新增测试点

1. `udpMtu = 0`：兼容旧行为，不做 MTU drop。
2. `udpMtu = 1300` 且 final datagram size = 1300：允许写出。
3. `udpMtu = 1300` 且 final datagram size = 1301：drop、release、记录 metric。
4. 原始 payload > 1300，压缩后 final <= 1300：允许写出。
5. 压缩/包装后 final > 1300：final egress drop。
6. redundant multiplier > 1：每个真实 datagram 分别检查 MTU，不按总字节数判断。
7. `Udp2rawHandler` client/server mode 的直接写出口全部收敛到统一 `writeUdp`。
8. 模拟 `channel.isWritable() == false`：验证 backpressure drop、release、metric。
9. handler order 测试：验证 inbound/outbound 中 compress、redundant、protocol、final guard 的实际顺序。

## GitHub Actions

代码实现 commit 后必须触发或依赖 `jdk8-unit-tests.yml`：

- `test_classes` 带上新增或修改的 UDP 测试类。
- 查询 workflow run 时按实现分支过滤。
- 只有 `conclusion=success` 才认为 CI 通过。
- 如 CI 失败，先区分编译失败、单测失败、format/checkstyle、依赖下载、JDK 版本或环境问题，再做最小范围修复。

# 分阶段执行建议

## 阶段 1：最小闭环

1. 增加 `udpMtu` 配置，默认 disabled。
2. 新增/改造 `writeUdp` 统一出口，覆盖 inactive、backpressure、mtu、write failure。
3. 将 `Udp2rawHandler` 直接 `writeAndFlush` 收敛到 `writeUdp`。
4. 确认 `SocksUdpRelayHandler` / `SSUdpProxyHandler` 都走统一出口。
5. 增加 MTU/backpressure 单测。

## 阶段 2：pipeline 安装收敛

1. 新增 `addUdpPipelineHandlers(...)`。
2. 将 compress/redundant handler 安装点切换到统一入口。
3. 增加 handler order 测试。

## 阶段 3：文档和清理

1. 如 `Sockets` 中有历史 helper 与新支持类重复，保留兼容 bridge 或直接收敛，以最小破坏为原则。
2. 在 config 注释中说明 `udpMtu` 表示 final UDP datagram content bytes，不含 IP/UDP header。
3. 如项目已有 README 或 config sample，补充 `udpMtu = 1300` 示例。
