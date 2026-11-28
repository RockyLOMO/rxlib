# 背压与控流使用说明

本文说明 `rxlib` 当前网络流控能力的边界和使用方式，适用于 `org.rx.net` 下的 Netty TCP/UDP 传输、SOCKS relay、UDP relay、RPC/transport 等高性能网络路径。

## 概念边界

### 背压

背压的目标是防止内存和队列失控，不是限制网速。

典型触发条件：

- TCP outbound `Channel.isWritable() == false`
- Netty 写缓冲超过 `WriteBufferWaterMark.high`
- UDP 待完成写入字节数或包数超过上限
- UDP channel 不可写、连接已关闭、目标地址未解析

背压触发后的动作通常是暂停读取、拒绝写入、丢弃 UDP 包或 fail-fast。它应该尽快释放 `ByteBuf`，避免 heap / direct memory 继续上涨。

### 控流

控流的目标是控制吞吐速率或控制写入排队规模。

当前实现分四类：

- 用户/作用域共享限速（推荐用于多租户与 Shadowsocks 用户）：`BandwidthLimitConfig` + `BandwidthLimiter` + `TcpBandwidthPacer` + `BandwidthChannelHandler`，基于单调时钟令牌桶实现双向共享配额（TCP 切片平滑 pacing + 协调多原因读暂停 `Sockets.READ_PAUSE_BANDWIDTH`，UDP 超额即时早丢释放）。
- 全局限速（既有能力，默认关闭）：`NetworkFlowControl` 安装 Netty `GlobalChannelTrafficShapingHandler`，按 KB/s 配置、按 bytes/s 执行，并限制单次等待上限。
- TCP 背压：`TcpBackpressureHandler` 在 outbound 不可写时通过 `Sockets.pauseRead(..., READ_PAUSE_BACKPRESSURE)` 暂停 inbound `autoRead`。
- UDP 过载保护：`UdpBackpressurePolicy` 通过 pending bytes / pending packets / `isWritable()` 决定是否接受本次发送。

注意：`app.net.globalTraffic.enabled` 只控制全局 traffic shaping handler，不是 TCP/UDP 背压总开关，也不影响独立安装的 `BandwidthLimiter`。TCP 和 UDP 背压分别由 `tcpBackpressureEnabled`、`udpBackpressureEnabled` 控制。

运行期关闭 `app.net.globalTraffic.enabled` 后，新 channel 不再安装全局 shaping；如果已有 `GlobalChannelTrafficShapingHandler`，刷新配置时 read/write limit 会降为 `0`，避免旧 handler 继续排队。

## 配置方式

`rx.yml` 或业务配置中可增加：

```yaml
app:
  net:
    globalTraffic:
      enabled: true
      uploadKilobytesPerSecond: 10240
      downloadKilobytesPerSecond: 10240
      checkIntervalMillis: 100
      maxDelayMillis: 200
      tcpBackpressureEnabled: true
      udpBackpressureEnabled: true
      udpMaxPendingBytes: 1048576
      udpMaxPendingPackets: 512
```

字段含义：

| 配置项 | 作用 | 默认语义 |
| --- | --- | --- |
| `enabled` | 是否启用全局 Netty traffic shaping | `false` |
| `uploadKilobytesPerSecond` | 全局 outbound 写速率上限，单位 KB/s，0 表示不限 | `0` |
| `downloadKilobytesPerSecond` | 全局 inbound 读速率上限，单位 KB/s，0 表示不限 | `0` |
| `checkIntervalMillis` | traffic shaping 统计周期，建议 50-200ms | `100` |
| `maxDelayMillis` | 单次限速延迟上限，避免多连接下载出现秒级静默 | `200` |
| `tcpBackpressureEnabled` | 是否允许安装 TCP relay 背压处理器 | `true` |
| `udpBackpressureEnabled` | 是否启用 UDP pending / writable 过载保护 | `true` |
| `udpMaxPendingBytes` | UDP 每 channel 全局 pending bytes 上限，0 表示使用 channel/socket 默认 | `0` |
| `udpMaxPendingPackets` | UDP 每 channel pending packets 上限，0 表示不限制包数 | `0` |

也可以使用 JVM 参数覆盖：

```bash
-Dapp.net.globalTraffic.enabled=${GLOBAL_TRAFFIC_ENABLED}
-Dapp.net.globalTraffic.uploadKilobytesPerSecond=10240
-Dapp.net.globalTraffic.downloadKilobytesPerSecond=10240
-Dapp.net.globalTraffic.maxDelayMillis=200
-Dapp.net.globalTraffic.udpMaxPendingBytes=1048576
-Dapp.net.flowDebug.flags=0
```

如果运行期直接修改 `RxConfig.INSTANCE.getNet().getGlobalTraffic()`，修改后需要刷新：

```java
import org.rx.net.NetworkFlowControl;

NetworkFlowControl.refresh();
```

## TCP 背压使用

TCP relay 场景需要一对通道：`inbound` 是读入端，`outbound` 是写出端。不要把 `TcpBackpressureHandler` 当作普通单通道 handler 盲目安装。

```java
import io.netty.channel.Channel;
import org.rx.net.TcpBackpressureHandler;

public final class RelayBackpressureExample {
    public static void install(Channel inbound, Channel outbound) {
        TcpBackpressureHandler.install(inbound, outbound);
    }
}
```

行为：

- outbound 不可写时，暂停 inbound `autoRead`。
- outbound 恢复可写时，恢复 inbound `autoRead`。
- outbound inactive / exception 时强制结束背压，避免 inbound 永久停读。

使用建议：

- 确认 outbound 已设置合理的 `WRITE_BUFFER_WATER_MARK`。
- I/O 线程内不要执行阻塞逻辑，否则背压恢复事件也会被阻塞。
- 只在端到端 relay、代理转发、长连接桥接这类场景安装。

## UDP 过载保护使用

UDP 没有 TCP stream 级背压，当前策略是写侧保护：超限即拒绝或丢弃，不做无限排队。

推荐统一走 `Sockets.writeUdp(...)`：

```java
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.socket.DatagramPacket;
import org.rx.net.SocketConfig;
import org.rx.net.Sockets;

import java.net.InetSocketAddress;

public final class UdpWriteExample {
    public static Sockets.UdpWriteResult send(Channel channel, ByteBuf payload,
                                              InetSocketAddress recipient, SocketConfig config) {
        DatagramPacket packet = new DatagramPacket(payload, recipient);
        return Sockets.writeUdp(channel, packet, config, "socks.udp",
                Sockets.udpMetricTags("socks", "relay", "outbound", "write", null));
    }
}
```

调用约定：

- 返回 `ACCEPTED` 表示包已交给 Netty 写队列。
- 返回非 `ACCEPTED` 时，`Sockets.writeUdp(...)` 已释放 `DatagramPacket` / `ByteBuf`，调用方不要重复 release。
- `UNRESOLVED_RECIPIENT` 表示传入了未解析地址，关键路径应避免阻塞式 DNS。
- `PENDING_OVERLIMIT` / `PENDING_PACKETS_OVERLIMIT` 表示当前 channel 写入积压超限。
- `MTU_EXCEEDED` 表示 payload 超过 `SocketConfig.udpMtu`，MTU guard 独立于 UDP 背压开关。

如果 pipeline 中使用 UDP 压缩、冗余发送或 final MTU guard，按现有入口安装：

```java
import io.netty.channel.ChannelPipeline;
import org.rx.net.Sockets;
import org.rx.net.socks.SocksConfig;

public final class UdpPipelineExample {
    public static void install(ChannelPipeline pipeline, SocksConfig config) {
        Sockets.addUdpOptimizationHandlers(pipeline, config);
    }
}
```

## 参数建议

建议先按保守值上线，再根据指标调整：

| 场景 | 建议 |
| --- | --- |
| 普通 TCP relay | 保持 `tcpBackpressureEnabled=true`，使用默认水位或按连接内存预算调高 |
| 高并发 UDP relay | 设置 `udpMaxPendingBytes=262144` 到 `1048576` |
| UDP 小包高 PPS | 增加 `udpMaxPendingPackets`，避免单个 channel 包数堆积 |
| 弱网链路 | 背压不等于重传；需要结合上层超时、重试、冗余或 FEC |
| 全局出口限速 | 开启 `enabled=true`，设置 upload/download bytes/s |

不要用全局限速替代背压。限速会平滑吞吐，但如果上游持续写入且没有 pending 上限，仍可能造成排队和堆外内存压力。

## 用户/作用域共享双向带宽限速（SS 用户与通用组件）

为避免耦合 `GlobalChannelTrafficShapingHandler`，库提供独立、零反射的通用限速组件（位于 `org.rx.net`）：

- **`BandwidthLimitConfig`**：配置 `uploadKiBps`、`downloadKiBps`（单位 `KiB/s`，`1 KiB = 1024 B`；`0`/未配置表示不限速且走零开销快路径，负值拒绝校验）。
- **`BandwidthLimiter`**：按用户或任意作用域共享的双向单调时钟（`System.nanoTime()`）令牌桶，支持无额外突发 credit 放水的动态 `configure(...)`、单连接/单用户/进程三级待发队列上限与节流指标上报（`net.bandwidth.*`）。
- **`TcpBandwidthPacer`**：严格 EventLoop 亲和的零拷贝 TCP 分片 pacing 队列，通过 `Sockets.READ_PAUSE_BANDWIDTH` 与背压/建连暂停掩码协作，并在 `pauseChannel` 收到 EOF 关闭后继续按速率排空发往 `writeChannel` 的剩余字节再触发半关闭/关闭。
- **`BandwidthChannelHandler`**：可选挂载到任意 Netty Channel 的通用 `ChannelDuplexHandler`（通过 `NetworkFlowControl.installBandwidthLimiter(ch, limiter)` 安装，不会由 `NetworkFlowControl.install(ch)` 全局自动安装）。

在 RSS `conf.yml` 中可直接按 `ShadowUser` 配置（支持配置热更新实时生效，**不重启 `ShadowsocksServer` 监听端口**）：

```yaml
shadowUsers:
  - username: user_a
    ssPort: 16900
    ssPwd: "..."
    bandwidth:
      uploadKiBps: 512
      downloadKiBps: 2048
```

该用户的多条并发 TCP 连接与所有 UDP 会话共享同一 `BandwidthLimiter`（按入站解密后明文有效载荷字节计费）。

## RRP RAW 逻辑流中继 4 级内存预算与单流反压（`RrpServer`）

当启用 `RrpServer` 的 `enableRawRelay` 提供多路复用公网 TCP 中继时，为防止单个慢速客户端拖垮同连接其它逻辑流（队头阻塞）或撑爆单核 768 MiB 服务端堆外内存，中继层内置独立 4 级待发字节预算与反压状态机（不依赖全局 `GlobalChannelTrafficShapingHandler`）：

- **4 级待发字节预算**：同时约束 **全局（`maxGlobalRelayPendingBytes`）→ 单租户（`maxTenantRelayPendingBytes`）→ 单节点（`maxNodeRelayPendingBytes`）→ 单逻辑流（`maxStreamRelayPendingBytes`）**；任一级超限优先通过 `ACTION_RAW_PAUSE` / `ACTION_RAW_RESUME` 精准暂停/恢复对应逻辑流，极端慢速溢出时仅断开违规单流并释放全部 `ByteBuf`（`rawStreamOverflowCloses`），不拖累其他租户与连接。
- **轮询排空防队头阻塞**：单条 RRP 物理 TCP 连接上的多条 RAW 逻辑流采用公平轮询（Round-Robin）配额刷出，并与物理 Channel `channelWritabilityChanged` 严格联动，保证高/低速流隔离。
- **即时吊销清理**：`NodeRegistry` 触发禁用或改密事件时，`RrpServer` 立刻关闭该节点所有活跃 RAW 逻辑流并归零全部待发字节计数，防止堆外内存滞留。

## 监控与验证

上线前至少关注：

- 堆外内存：`jvm.direct.used.percent`、`jvm.direct.capacity.percent`、`jvm.direct.max.bytes`
- 连接数：`net.connection.active.count`
- 吞吐：`net.io.inbound.bytes.per.second`、`net.io.outbound.bytes.per.second`
- 用户带宽限速：`net.bandwidth.admitted.bytes`、`net.bandwidth.drop.count`、`net.bandwidth.delay.count`、`net.bandwidth.pending.tcp.bytes`
- RRP RAW 中继与协调：`RrpServer`（`activeRawStreamCount`、`rawStreamsOpenedCount`、`rawStreamOverflowCloses`、`globalRelayPendingBytes`、`rawRelayIngressBytes`、`rawRelayEgressBytes`）与 `RendezvousService`（`authenticatedNodeCount`、`obsRequestsReceived`、`obsRepliesSent`、`obsRejectedCount`、`punchSessionsCreated`、`punchSessionsRejected`）
- 写积压：`net.write.pending.bytes`
- 不可写通道：`net.write.unwritable.count`
- EventLoop 队列：`net.eventLoop.pending.count`、`net.eventLoop.pending.max.count`
- 全局限速安装数：`net.flow.global.traffic.channel.count`
- UDP 丢弃：`{metricPrefix}.drop.count`
- UDP pending：`{metricPrefix}.pending.write.bytes`、`{metricPrefix}.pending.write.packets`
- UDP MTU：`{metricPrefix}.mtu.drop.count`、`{metricPrefix}.mtu.drop.bytes`

推荐回归测试：

```bash
mvn -pl rxlib -DskipTests=false "-Dtest=BandwidthLimiterTest,SsUserBandwidthIntegrationTest,NetworkFlowControlTest,TcpBackpressureHandlerTest,SocketsTest" "-DargLine=-Dio.netty.leakDetection.level=PARANOID" test
```

网络链路改动后再补充集成测试：

```bash
mvn -pl rxlib -DskipTests=false -Dtest=SocksProxyServerIntegrationTest,Socks5ClientIntegrationTest,ShadowsocksServerIntegrationTest,RemotingTest test
```

## RSS 进程覆盖

库默认 `enabled=false`，`udpMaxPendingBytes` 和 `udpMaxPendingPackets` 为 0。部署脚本默认也不打开全局限速。打开后的预设是：Client 上传/下载 4785/23926 KiB/s，UDP pending 262144 字节 / 512 包；Server 上传/下载 6592/0 KiB/s，UDP pending 131072 字节 / 256 包。`FLOW_DEBUG_FLAGS` 默认 0。

`RssServer.launch` 在进程限额仍为 0 时，把 `udpProcessPendingBytes` 设为 4MiB、`udpProcessPendingPackets` 设为 4096，并打开 `shareTcpAcceptLoop`。这是进程级预算，和每 channel 的 `udpMaxPending*` 不是同一个字段。

这些数字只在对应开关打开或 RSS Server 启动路径里生效。不要把脚本里的 KB/s 写成库的默认限速。

## 风险清单

- `ByteBuf` 所有权必须清晰：`writeUdp` 拒绝路径已释放，调用方不能二次释放。
- TCP 背压必须覆盖恢复路径：异常、关闭、不可写到可写的状态切换都要验证。
- UDP 只做应用层保护，不能保证对端收到，也不会自动重传。
- `GlobalChannelTrafficShapingHandler` 是全局共享限速器，会把安装过的 channel 聚合到同一组速率预算里；`maxDelayMillis` 应保持较小，避免多下载流出现秒级静默。
- 不要在 EventLoop 中做阻塞 DNS、文件 I/O、长计算或阻塞锁等待。
