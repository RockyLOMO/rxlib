# Socks 场景4 RSS 控流与背压链路

本文记录 `SocksScene.md` 场景4 在 RSS client / RSS server 两侧的流量路径，以及每段链路上的控流、背压、UDP 压缩、多倍发送、端口跳跃和 lease pool 生效点。

## 场景拓扑

```text
ShadowsocksClient C
  -> RSS Client A: ShadowsocksServer
  -> RSS Client A: SocksProxyServer A
  -> RSS Server B: SocksProxyServer B
  -> dest
```

RSS client 入口配置来自 `/home/rss/conf.yml`。进程级网络控流参数来自两侧启动脚本中的 JVM system properties：

- RSS client A：`deploy/rss/start.sh`
- RSS server B：`deploy/rss-svr/start.sh`

RSS server 的 `deploy/rss-svr/rollback.sh` 也会独立启动 JVM，因此需要与 `start.sh` 保持同一组控流参数，避免回滚后丢失限速和 UDP pending 保护。

全局控流一键开关是 `GLOBAL_TRAFFIC_ENABLED`，映射到 `-Dapp.net.globalTraffic.enabled`。RSS client 默认 `true`；RSS server 当前默认 `false`，先关闭全局 shaping，只保留 TCP / UDP 背压。需要临时验证 server 全局 shaping 时，可在启动前显式设置 `GLOBAL_TRAFFIC_ENABLED=true`。

## RSS Client 启动控流

`deploy/rss/start.sh` 当前按运营商常用 Mbps 口径换算，`Mb/s` 表示 bit/s，不是 Byte/s。

| 标称带宽 | 计算 | 98% 目标 | 启动参数 |
| --- | --- | --- | --- |
| 上行 40Mb/s | `40,000,000 / 8 / 1024` | `4785 KiB/s` | `GLOBAL_TRAFFIC_UPLOAD_KBPS=4785` |
| 下行 200Mb/s | `200,000,000 / 8 / 1024` | `23926 KiB/s` | `GLOBAL_TRAFFIC_DOWNLOAD_KBPS=23926` |

RSS client 的 UDP pending 上限：

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `GLOBAL_UDP_MAX_PENDING_BYTES` | `262144` | 每 channel 最多 256KiB pending write bytes |
| `GLOBAL_UDP_MAX_PENDING_PACKETS` | `512` | 每 channel 最多 512 个 pending UDP packet |

## RSS Server 启动控流

`deploy/rss-svr/start.sh` 保留控流参数，但当前默认关闭全局 shaping。RSS server B 是场景4里的公网出口和回包入口，client A 的限速不能控制 B -> dest 或 B -> A 方向；如果后续需要限制 B 侧出口，可一键打开 `GLOBAL_TRAFFIC_ENABLED=true`。

| 标称带宽 | 计算 | 98% 目标 | 启动参数 |
| --- | --- | --- | --- |
| 上行 60Mb/s | `60,000,000 / 8 / 1024` | `7178 KiB/s` | `GLOBAL_TRAFFIC_UPLOAD_KBPS=7178` |
| 下行 60Mb/s | `60,000,000 / 8 / 1024` | `7178 KiB/s` | `GLOBAL_TRAFFIC_DOWNLOAD_KBPS=7178` |

上述 60Mb/s 参数只有在 server 启动时设置 `GLOBAL_TRAFFIC_ENABLED=true` 才参与 Netty `GlobalChannelTrafficShapingHandler`；当前默认不安装 server 侧全局 shaping。

RSS server 是 2c / 1.5GiB 小机器，`MaxDirectMemorySize=640m`，所以 UDP pending 比 client 更收敛：

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `GLOBAL_UDP_MAX_PENDING_BYTES` | `131072` | 每 channel 最多 128KiB pending write bytes |
| `GLOBAL_UDP_MAX_PENDING_PACKETS` | `256` | 每 channel 最多 256 个 pending UDP packet |

两侧公共 JVM 参数形态：

```bash
-Dapp.net.globalTraffic.enabled=${GLOBAL_TRAFFIC_ENABLED}
-Dapp.net.globalTraffic.uploadKilobytesPerSecond=${GLOBAL_TRAFFIC_UPLOAD_KBPS}
-Dapp.net.globalTraffic.downloadKilobytesPerSecond=${GLOBAL_TRAFFIC_DOWNLOAD_KBPS}
-Dapp.net.globalTraffic.checkIntervalMillis=${GLOBAL_TRAFFIC_CHECK_INTERVAL_MILLIS}
-Dapp.net.globalTraffic.tcpBackpressureEnabled=true
-Dapp.net.globalTraffic.udpBackpressureEnabled=true
-Dapp.net.globalTraffic.udpMaxPendingBytes=${GLOBAL_UDP_MAX_PENDING_BYTES}
-Dapp.net.globalTraffic.udpMaxPendingPackets=${GLOBAL_UDP_MAX_PENDING_PACKETS}
-Dapp.diagnostic.enabled=false
-Dapp.diagnostic.h2.enabled=false
-Dapp.diagnostic.disk.scan.enabled=false
-Dapp.diagnostic.nmt.enabled=false
-Dapp.trace.keepDays=0
```

`GLOBAL_TRAFFIC_ENABLED=false` 时，`NetworkFlowControl` 会跳过新 channel 的 `GlobalChannelTrafficShapingHandler` 安装；如果进程内刷新配置时已有 handler，也会把 read/write limit 配置为 `0`，避免旧 handler 继续按原限速排队。

## RSS 流量诊断开关

`deploy/rss/start.sh`、`deploy/rss-svr/start.sh`、`deploy/rss-svr/rollback.sh` 使用一个位掩码入口控制流量诊断，默认关闭。它用于排查并发大文件下载 5s 后出现的 3~5s 卡点，以及 UDP 背压/最终出口丢包：

| 环境变量 | 默认值 | JVM 参数 | 说明 |
| --- | --- | --- | --- |
| `FLOW_DEBUG_FLAGS` | `0` | `-Dapp.net.flowDebug.flags` | 位掩码；`0` 关闭，`3` 是场景 4 TCP/背压排查推荐值，`7` 额外打开 UDP drop 明细 |

位定义：

| 位 | 十进制 | 含义 |
| --- | --- | --- |
| `1 << 0` | `1` | 每秒输出 `NET_FLOW_DEBUG` 聚合日志 |
| `1 << 1` | `2` | 聚合日志附带 pending / 不可写 / 背压相关 top channel，默认 top 8 |
| `1 << 2` | `4` | 把 UDP backpressure / final egress drop 以 INFO 输出，方便线上直接 grep |

聚合扫描周期固定为 1s，top channel 固定为 8；现场只调整 `FLOW_DEBUG_FLAGS`，避免多个 debug 参数分散。

现场排查命令示例：

```bash
FLOW_DEBUG_FLAGS=3 ./start.sh publish replace
FLOW_DEBUG_FLAGS=7 ./start.sh publish replace
```

重点字段：

| 字段 | 判断方向 |
| --- | --- |
| `globalQueue` | Netty `GlobalChannelTrafficShapingHandler` 内部延迟队列；若卡顿时明显膨胀，优先看全局限速/调度公平性 |
| `outPending` | Netty channel outbound buffer；若持续膨胀，说明写端或系统 socket 发送侧拥塞 |
| `proxyReadDelta` / `proxyWriteDelta` | 本周期所有 SOCKS 会话经 `ProxyManageHandler` 统计到的读/写增量 |
| top channel 的 `rDelta` / `wDelta` | 单条 SOCKS 会话本周期读/写增量；用于判断多个下载是否公平分配带宽 |
| `autoReadOff` / `tcpBpPaused` | TCP 背压是否暂停上游读取；若卡顿时升高，说明单连接写水位在竞争 |
| `tcpBpDelta` / `avgPauseMs` | 本周期背压开始/结束/定时延迟次数和平均暂停时长 |
| `lastRead` / `lastWrite` | 全局 TrafficCounter 最近统计吞吐，用来对照下载进度是否被批量放行 |

RSS server B 额外设置 fake host 控制面恢复等待：

```bash
-Dapp.net.socks.fakeEndpointRecoverWaitMillis=${FAKE_ENDPOINT_RECOVER_WAIT_MILLIS}
```

性能优先策略：

- `checkIntervalMillis=100`，使用 Netty 默认级别的 100ms 调度粒度，减少多连接下载时的秒级批量放行。
- 全局限速只做粗保护，防止持续打满公网出口导致排队膨胀。
- RSS 生产脚本关闭诊断框架、诊断 H2 落库、磁盘扫描、NMT 采集和 trace agent，避免诊断线程、JFR sampler、ByteBuddy 动态 agent 在下载压测时抢 CPU / IO。
- 如果线上 RTT 抖动明显，优先把对应节点降到 95%；如果 RTT 平稳且吞吐不足，再临时调到 99% 或关闭全局限速压测。

## 2026-06-10 线上观测

并发下载 5 个文件时，旧配置较前一版已有改善，但仍出现 4 个下载进度在 20% / 30% 一段一段停顿的问题。登录 RSS client / RSS server 观察到：

| 节点 | 现象 | 判断 |
| --- | --- | --- |
| RSS client | `rx-diagnostic-h2-writer` 单线程可打满一个 CPU；当前流量很低时 JVM 仍约 49% CPU | 诊断 H2 写入/清理抢占 CPU，影响 EventLoop 和 traffic shaping 定时任务 |
| RSS server | 2c 小机上 `MVStore`、`H2-serializer`、G1 线程明显抢 CPU；日志有 `diagnostic h2 batch slow` / `queue pressure` | 诊断 H2 在小内存小 CPU 机器上成为热点 |
| 两侧网卡 | `tc qdisc` backlog 为 0，TCP `Send-Q/Recv-Q` 基本为 0 | 系统网卡队列不是主要瓶颈 |
| 全局限速 | 两侧实际运行 `checkIntervalMillis=1000` | 1s shaping tick 会造成按秒批量放行，不适合“5 个进度条都流畅”的体验 |
| fake host 控制面 | server 最近日志仍有大量 `recover dstEp ... fail`，client 只看到少量 `fakeEndpointRecovery` 事件重订阅 | 事件机制存在，但 server 侧 200ms 等待窗口偏窄，RTT/CPU 抖动下容易提前失败 |

处理结论：

- `deploy/rss/start.sh`、`deploy/rss-svr/start.sh`、`deploy/rss-svr/rollback.sh` 默认 `GLOBAL_TRAFFIC_CHECK_INTERVAL_MILLIS=100`。
- `deploy/rss-svr/start.sh`、`deploy/rss-svr/rollback.sh` 默认 `FAKE_ENDPOINT_RECOVER_WAIT_MILLIS=1200`，避免 server 缓存 miss 后 RPC 事件还没回包就返回 SOCKS failure。
- `SocksTcpUpstream.prepareDestination()` 在 client 本地先写入 fake host 映射，再异步推送给 server；即使初始 `fakeEndpoint` RPC push 慢或失败，server 后续 `fakeEndpointRecovery` 事件也能从 client cache 找到真实 endpoint。
- 启动脚本默认追加 `-Dapp.diagnostic.enabled=false -Dapp.diagnostic.h2.enabled=false -Dapp.diagnostic.disk.scan.enabled=false -Dapp.diagnostic.nmt.enabled=false -Dapp.trace.keepDays=0`。
- 这次调整优先消除本机诊断 CPU/IO 抖动，再提升全局控流调度平滑度；暂不继续放大 TCP/UDP pending 队列，避免游戏低延迟场景下排队变深。

## 2026-06-10 最终部署验证

本次最终上线版本额外处理了 5 个线上噪声/热点点：

| 项 | 处理 | 影响 |
| --- | --- | --- |
| SOCKS5 `UDP_ASSOCIATE` fake command dst | `Socks5CommandRequestHandler` 对 `UDP_ASSOCIATE` 的 fake command 目的地址跳过 command 阶段 recover；真实目标仍以 UDP packet header 为准 | 消除 server 端无 `COMPUTE_ARGS` 的 `recover dstEp ... fail` |
| SOCKS5 `CONNECT` DOMAIN 响应 | CONNECT 成功响应统一返回 `ATYP=IPv4, BND.ADDR=0.0.0.0, BND.PORT=0` | 修复 curl `--socks5-hostname` 域名型 CONNECT 卡在 request granted 前的问题，避免输出 `DOMAIN len=0` 的兼容性风险 |
| H2 cache 过期清理 | `H2StoreCache` 在没有 `onExpired` listener 时使用 id/version/expiration 轻量页查询，并按 `id + version` 条件删除 | 避免 `SELECT * FROM h2_cache_item`、key/value 反序列化和 tombstone 写放大 |
| 预期连接关闭日志 | `GlobalChannelHandler` 将 expected close / expected write failure 从 WARN 降到 DEBUG，并在 DEBUG 关闭时不构造 summary | 降低 server 高短连接/reset 场景的日志 IO 与字符串分配 |
| 生产诊断开销 | client/server `start.sh` 关闭 `app.diagnostic.enabled` 和 `app.trace.keepDays` | 不再启动 `rx-diagnostic-*` / JFR sampler，不再动态加载 trace ByteBuddy agent，降低空闲 CPU 和内存噪声 |

线上验证结果：

| 节点 | 验证项 | 结果 |
| --- | --- | --- |
| RSS client | 新进程参数 | `Xms1g/Xmx2g`、`reactorThreadAmount=8`、全局限速 `4785/23926 KiB/s`、UDP pending `256KiB/512` 生效 |
| RSS client | drain | 旧进程进入 180s drain，新进程 slot `b` 已绑定端口；上一轮旧进程已按 drain 退出 |
| RSS client | 23:33 后日志 | `recover dstEp=0`、`COMPUTE_ARGS=0`、`NativeIoException=0`、`decompress=0`、`slowSql=0`、`expected close/write=0` |
| RSS server | 新进程参数 | `Xms256m/Xmx256m`、`reactorThreadAmount=2`、全局限速参数保留为 `7178/7178 KiB/s` 但当前 `enabled=false`、UDP pending `128KiB/256`、fake recover `1200ms` 生效 |
| RSS server | 23:32 后日志 | `recover dstEp=0`、`COMPUTE_ARGS=0`、`NativeIoException=0`、`decompress=0`、`slowSql=0`、`expected close/write=0` |
| socks5h 连通性 | client 本机 `curl --socks5-hostname 127.0.0.1:6885` | `example.com=200`、`google generate_204=204`、`cloudflare trace=200`，域名型 CONNECT 已通 |
| 5 并发下载 | 5 路 `https://speed.cloudflare.com/__down?bytes=10485760` | 全部 HTTP 200，10MiB 完整下载，耗时 `8.37s..8.74s`，未见 4 路长时间 0 进度 |
| RSS client 灰节点 | `/home/rss/conf.yml` 中 `new104` / `new104-tun` | 2026-06-11 已将 `104.168.27.48` 两个上游权重设为 `0`，避免不可达灰节点继续触发 `9901` RPC 建连超时 |
| CPU/内存 | client/server 排水后最终采样 | client 即时 CPU 约 `2%`、RSS 约 `2.1GiB`；server 即时 CPU 约 `2%`、RSS 约 `474MiB`；主要 CPU 线程是 JIT compiler 和少量 EventLoop，未见诊断/JFR 线程 |

仍需注意：

- `104.168.27.48` 是灰节点，当前不可达时必须保持 `new104` / `new104-tun` 权重为 `0`。本次调整后 RSS client 新进程独占监听 `6885/8082`，最近日志未再出现 `104.168.27.48`、`:9901`、`ObjectPool doCreateIdle`、`ConnectTimeoutException` 或 `fail-open`。
- server 端 `Connection reset by peer` 属于对端关闭/短连接 reset，最终版本已降为 DEBUG，不再作为 WARN 噪声放大。

## 全局控流边界

`NetworkFlowControl` 在 `Sockets` 初始化 channel 时安装 Netty `GlobalTrafficShapingHandler`。

| 链路类型 | 是否安装全局限速 | 说明 |
| --- | --- | --- |
| TCP/UDP 网络 Channel | 是 | `uploadKilobytesPerSecond` 映射 Netty write limit，`downloadKilobytesPerSecond` 映射 read limit |
| `LocalChannel` / TCP loopback | 否 | 进程内或 `127.0.0.1` TCP 本地跳不走公网口，跳过全局限速 |
| UDP `Sockets.writeUdp(...)` | 是，且额外走 UDP 背压 | 写入前检查 pending bytes / packets / channel writable |
| TCP relay | 只在成对 relay 场景安装 TCP 背压 | outbound 不可写时暂停 inbound `autoRead` |

注意：全局限速不是精确网卡限速；它是 Netty channel 级读写节流。双进程灰度或 coexist 期间，每个进程各自有一份限速预算，总出口可能短时超过单进程配置。

开关边界：

| 配置 | 行为 |
| --- | --- |
| `GLOBAL_TRAFFIC_ENABLED=true` | 安装全局 shaping，按 upload/download KiB/s 粗控流 |
| `GLOBAL_TRAFFIC_ENABLED=false` | 不再给新 channel 安装全局 shaping；已有 handler 在配置刷新后 read/write limit 变为 `0` |
| `FLOW_DEBUG_FLAGS=3` | 每周期扫描 channel 并输出聚合诊断 + top channel，适合场景 4 TCP 下载/背压短时现场排查 |
| `FLOW_DEBUG_FLAGS=7` | 在 `3` 的基础上额外打开 UDP drop 入口日志 |

## UDP 请求方向链路

### 1. ShadowsocksClient C -> ShadowsocksServer A

入口是 RSS client 的 `ShadowsocksServer`。

| 能力 | 生效点 | 当前语义 |
| --- | --- | --- |
| 全局下载限速 | Shadowsocks UDP 网络入口 channel | 受 `downloadKilobytesPerSecond` 粗限速 |
| UDP 路由缓存 | `SSUdpProxyHandler` route map | 按 `source + destination` 复用 outbound |
| 路由初始化 pending | `MAX_PENDING_ROUTE_PACKETS=32`、`MAX_PENDING_ROUTE_BYTES=256KiB` | upstream 尚未初始化完成时最多暂存少量 UDP 包，超限丢弃 |
| Shadowsocks UDP outbound 池 | `udpOutboundPoolMaxSize=8192`、`udpOutboundPoolMaxPerSource=256` | 限制 SS UDP 到上游的 outbound channel 数量 |
| UDP 写背压 | `Sockets.writeUdp(...)` | 默认每 channel pending bytes 受全局 `262144` cap，pending packets 受 `512` cap |

该段不做 UDP 压缩和多倍发送；Shadowsocks 本地入口负责解密、路由、转成 SOCKS5 UDP payload。

### 2. ShadowsocksServer A -> SocksProxyServer A

RSS client 内部通常是本机 Socks A。若 `socksBindPort=false`，该段可能走 Netty `LocalChannel`。

| 能力 | 生效点 | 当前语义 |
| --- | --- | --- |
| 全局限速 | `LocalChannel` 不安装；TCP loopback / connect hint 指向 `localhost` 时也跳过 shaping | 本机链路不消耗公网带宽，不抢全局限速预算 |
| UDP 回程 decoder | `SSUdpProxyHandler.ensureRelayResponseDecoder(...)` | 仅补 `UdpRedundantDecoder` / `UdpCompressDecoder`，用于剥离 Socks A 回包里的 RDNT / UCMP |
| UDP request encoder | 不安装 | 避免把 SS 本地跳也多倍发送或二次压缩 |
| UDP 背压 | `Sockets.writeUdp(...)` 或 final egress guard | 超过 pending bytes / packets 即丢，释放 ByteBuf |

### 3. SocksProxyServer A -> SocksProxyServer B

这是场景4的主要公网 upstream 链路。

| 能力 | 配置/生效点 | 当前语义 |
| --- | --- | --- |
| 全局上传限速 | Socks A 到 Socks B 的 UDP/TCP 网络 channel | 受 `uploadKilobytesPerSecond` 粗限速 |
| UDP MTU | `udpMtu=1300` | final guard 丢弃超过 payload 上限的 datagram |
| UDP 压缩 | `RssSupport.applyUdpCompressionTrial` | LZ4 fast，`minPayloadBytes=96`、`minSavingsBytes=24`、`minSavingsRatio=0.12`，低收益目的地可 30s 自适应旁路 |
| UDP 多倍发送 | `udpRedundantMultiplier=2`、adaptive `1..3` | 按丢包统计在 1 到 3 倍间调整，默认 request/response 双向允许 |
| UDP 端口跳跃 | adaptive，`minHopCount=1`、`maxHopCount=2`、`ROUND_ROBIN` | RSS client 启用端口跳跃能力，默认从 1 个 relay 起步 |
| UDP lease pool | `enabled=true`、`min=2`、`max=32`、idle `300s` | 优先从 RPC lease pool 借 relay，失败回退慢路径 |
| UDP relay group | `UdpRelayControlMode.AUTO` | 有 RPC facade 且 B 支持能力时批量打开 relay group；否则 fallback SOCKS5 compatible |
| UDP 背压 | `UdpBackpressurePolicy` | 每 channel pending bytes 取本地 1MiB 与全局 256KiB 的较小值；pending packets 上限 512 |

这段既是公网出口，也是最容易堆积的链路。游戏低延迟场景下，当前策略是小队列、快速丢弃，不为了完整吞吐牺牲排队延迟。

## RSS Client 端口跳跃细节

RSS client 的端口跳跃配置写在 `RssClient.configureOutboundConfig(...)`，作用于 A -> B 的 SOCKS UDP upstream。当前 `/home/rss/conf.yml` 没有单独暴露这些字段，运行时按代码默认值生效。

| 参数 | 当前值 | 影响 |
| --- | --- | --- |
| `udpPortHoppingEnabled` | `true` | 打开 SOCKS UDP upstream 的端口跳跃能力 |
| `udpPortHoppingAdaptive` | `true` | 使用自适应模式，而不是固定 hop 数 |
| `udpPortHoppingMinHopCount` | `1` | 新建 UDP session group 时先开 1 个 relay |
| `udpPortHoppingMaxHopCount` | `2` | 自适应扩容最多到 2 个 relay |
| `udpPortHoppingMinActiveHops` | `1` | 至少保留 1 个可用 relay；低于该值会失效重建 |
| `udpPortHoppingMode` | `ROUND_ROBIN` | 多 relay 存在时按轮询选择 relay address |
| `adaptiveScaleUpBytes` | `0` | 当前不按累计流量主动扩容 |
| `adaptiveScaleUpActiveMillis` | `0` | 当前不按活跃时长主动扩容 |
| `adaptiveScaleUpCooldownMillis` | `1000` | 若未来开启扩容，扩容尝试最小间隔 1s |
| `replenishDelayMillis` | `1000` | relay 掉线后补齐尝试最小间隔 1s |

实际运行路径：

1. `SocksUdpUpstream.acquireGroup(...)` 创建 session group。
2. 自适应模式下初始 hop 数取 `minHopCount=1`。
3. 如果 RSS server B 支持 RPC relay group，A 会通过 `openUdpRelayGroup` 请求初始 1 个 relay，并携带最大 relay 数 2。
4. 发送 UDP 包时，`selectUdpRelayAddressAndRecord(...)` 从当前有效 relay 中选地址；只有存在多个 relay 时 `ROUND_ROBIN` 才会真正轮询。
5. 当前 `adaptiveScaleUpBytes=0` 且 `adaptiveScaleUpActiveMillis=0`，所以不会因流量或在线时长主动从 1 扩到 2。
6. relay 控制连接关闭后，如果剩余 active relay 少于 `minActiveHops=1`，session group 失效并重建；如果目标 hop 数未满足，会按 `replenishDelayMillis=1000` 尝试补齐。

结论：当前 RSS client 的端口跳跃是“能力打开、保守起步”。它不会默认把每条 UDP 流量拆到两个端口上，避免额外控制面和 relay 资源开销；如果后续确认单 UDP relay 端口存在限速、丢包或 NAT 抖动，可以再把 `adaptiveScaleUpBytes` 或 `adaptiveScaleUpActiveMillis` 暴露到 `conf.yml`，让热点会话逐步扩到 2 个 relay。

### 4. SocksProxyServer B -> dest

该段在 RSS server 侧执行，当前默认只受 `deploy/rss-svr/start.sh` 的 UDP/TCP 背压控制；server 全局 shaping 保留参数但默认关闭。

| 能力 | 生效点 | 当前语义 |
| --- | --- | --- |
| 全局上传限速 | Socks B 到 dest 的网络 channel | 仅 `GLOBAL_TRAFFIC_ENABLED=true` 时受 B 侧 `GLOBAL_TRAFFIC_UPLOAD_KBPS=7178` 粗限速；当前默认关闭 |
| 全局下载限速 | dest 到 Socks B 的网络 channel | 仅 `GLOBAL_TRAFFIC_ENABLED=true` 时受 B 侧 `GLOBAL_TRAFFIC_DOWNLOAD_KBPS=7178` 粗限速；当前默认关闭 |
| UDP relay | Socks B UDP outbound | `RssServer.configureOutboundConfig` 设置 MTU、压缩、多倍发送、端口跳跃 |
| 压缩/多倍发送 | A/B 协商链路上的 SOCKS UDP handler | A 发出的 RDNT / UCMP 在 B 侧解码后再转发到真实 dest；B 回 A 时也可按配置编码 |
| UDP 背压 | B 侧 `Sockets.writeUdp(...)` | pending bytes 上限 `128KiB`，pending packets 上限 `256` |
| TCP 背压 | B 侧 SOCKS / Shadowsocks TCP relay | 保持 `tcpBackpressureEnabled=true`，双向 relay 任一侧 outbound 不可写时暂停对端读取 |

RSS server B 的 `udpLeasePoolEnabled` 默认不是它自己继续向下游借 lease；lease pool 主要由 A 通过 B 的 RPC facade 使用。B 侧负责提供 `SocksProxyServer` 和 `RssRpcApp` 能力。

## UDP 回包方向链路

```text
dest
  -> SocksProxyServer B
  -> SocksProxyServer A
  -> ShadowsocksServer A
  -> ShadowsocksClient C
```

| 链路 | 控流/背压 | 压缩/多倍发送 |
| --- | --- | --- |
| dest -> B | server 全局 shaping 当前默认关闭；B 侧 UDP pending cap 为 `128KiB / 256 packets` | B 按 SOCKS UDP 配置处理后回 A |
| B -> A | server 全局 shaping 当前默认关闭；RSS client A 的入站 read limit 会参与；A 的 UDP write back to SS client 走 pending cap | Socks A 回包先经 RDNT / UCMP decoder 去重、解压 |
| Socks A -> ShadowsocksServer A | LocalChannel 不做全局 shaping；真实本机 UDP 则会进 shaping | 只解码，不在本地跳新增 encoder |
| ShadowsocksServer A -> Client C | RSS client 上传 write limit 生效；另有 per-source pending bytes `256KiB` | 还原 Shadowsocks UDP address header 后发给客户端 |

`SSUdpProxyHandler.UdpBackendRelayHandler` 对回客户端方向有单源 pending 限制：`udpWritePerSourceLimitBytes=256KiB`。这对游戏场景是必要保护，避免单个客户端或单个 NAT source 把入口 channel 写队列拖大。

## TCP 链路与背压

场景4主流量是 UDP，TCP 背压仍建议开启，但它不是主要限速手段。

| TCP 路径 | 当前背压状态 | 说明 |
| --- | --- | --- |
| SOCKS5 TCP relay | `TcpBackpressureHandler.installPair(...)` 双向安装 | 任一侧 outbound 不可写时暂停对端 `autoRead` |
| Shadowsocks TCP -> upstream | `SSTcpProxyHandler` 已双向安装 TCP 背压 | SS 公网入口与 upstream 任一侧写水位触发时，都会暂停另一侧读取 |
| RPC / 控制面 | 不依赖 TCP 背压 | 流量小，重点是超时和连接池 |

因为 RSS client 内存充足但目标是低延迟，TCP 水位不建议调大。`RssSupport.OUT_OPS` 已使用 `LOW_LATENCY`，比加大队列更适合游戏场景。

## 2026-06-15 TCP 下载背压验证

`rss-21` 分支部署到 RSS client / RSS server 后，使用 RSS client 本机 3 路 `curl --socks5-hostname 127.0.0.1:6885 https://proof.ovh.net/files/100Mb.dat` 压测 A -> B 主 upstream TCP 链路。

| 节点 | 观测结果 | 结论 |
| --- | --- | --- |
| RSS client A | `proxyWriteDelta` 持续约 `5..10.7MB/s`，`lastRead` 约 `3..11MB/s`；`outPending` 最高约 `32.6KiB`，`globalQueue` 最高约 `54.2KiB` | client 侧全局控流未形成 MB 级排队 |
| RSS server B | `proxyWriteDelta` 持续约 `5.8..7.3MB/s`；`outPending` 峰值约 `257.5KiB`；`tcpBpPaused` 峰值 `2` | server 侧 TCP 背压已实际触发，并把写队列限制在水位附近 |

这次验证只覆盖本机 socks -> RSS server 的 TCP upstream 主路径；外部 Shadowsocks client -> RSS client 的入口通道在同版日志中已显示 `bp=true`，但仍需要用真实外部并发下载继续观察 UI 进度是否流畅。

## 线上观测项

上线后重点看这些指标：

| 指标 | 预期 |
| --- | --- |
| `jvm.direct.used/capacity/max` | 不应持续上升到 max 附近 |
| `net.flow.global.traffic.channel.count` | 启用全局限速后应看到网络 channel 安装计数 |
| `*.udp.drop.count` | 少量 burst drop 可接受，持续高 drop 表示限速/背压过紧或链路拥塞 |
| `*.pending.write.bytes` | client 长时间贴近 `262144`、server 长时间贴近 `131072` 表示出口拥塞 |
| `*.pending.write.packets` | client 长时间贴近 `512`、server 长时间贴近 `256` 表示 PPS 堆积 |
| EventLoop pending task | 不应持续增长 |
| 游戏 RTT / jitter | 比吞吐更优先，出现排队抖动时先降低全局比例或 UDP pending cap |

## 调参建议

| 目标 | 建议 |
| --- | --- |
| 性能优先，粗控流 | 维持 98%、`checkIntervalMillis=100` |
| client RTT 抖动明显 | client 降到 95%，或将 `GLOBAL_UDP_MAX_PENDING_BYTES` 降到 `131072` |
| server RTT 抖动明显 | server 降到 95%，或将 `GLOBAL_UDP_MAX_PENDING_BYTES` 降到 `65536` |
| fake host recover fail 持续出现 | 先确认 client event `fakeEndpointRecovery` 已订阅，再临时把 server `FAKE_ENDPOINT_RECOVER_WAIT_MILLIS` 提到 `1500..2000` |
| UDP drop 明显但 RTT 平稳 | client 可提高到 `524288 / 512 packets`；server 可提高到 `262144 / 512 packets` |
| 严格出口整形 | 不建议在热点路径继续加逻辑，优先用 Linux `tc` / SQM 在网卡层处理 |

## 对应用例

- `org.rx.net.socks.ShadowsocksServerIntegrationTest#shadowsocksUdpRelay_e2e`
- `org.rx.net.socks.SocksProxyServerIntegrationTest#shadowsocksUdpRelay_socks5_chained_e2e`
- `org.rx.net.socks.SocksProxyServerIntegrationTest#shadowsocksUdpRelay_socks5_chained_withUdpRedundantOnProxyA_e2e`
- `org.rx.net.socks.SocksProxyServerIntegrationTest#shadowsocksUdpRelay_socks5_chained_withUdpCompressAndRedundantOnProxyAB_e2e`
- `org.rx.net.socks.SocksProxyServerIntegrationTest#shadowsocksUdpRelay_socks5_chained_withPortHopping_e2e`
- `org.rx.net.socks.SocksProxyServerIntegrationTest#shadowsocksUdpRelay_socks5_chained_withLeasePool_e2e`
- `org.rx.net.socks.SocksProxyServerIntegrationTest#shadowsocksUdpRelay_sameDestinationDifferentClientPorts_e2e`
- `org.rx.net.socks.SocksProxyServerIntegrationTest#shadowsocksUdpRelay_socks5_localChannel_preservesOrigin_e2e`
