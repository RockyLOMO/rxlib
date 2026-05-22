# 背景

用户希望在 `rockylomo/rxlib` 的 `master` 分支中，为 `org.rx.net` 增加一个进程级全局网络限速能力，例如上行 20Mb/s、下行 40Mb/s。要求所有通过 `org.rx.net.Sockets` 创建的 Netty bootstrap / channel，不管实例数量多少，都共享同一套全局限速配额。

用户同时提出希望把 bootstrap 实例的 TCP 背压，以及 socks proxy / UDP relay 中类似 UDP 背压或过载保护的逻辑抽出来统一管理，并确认“背压主要是防止内存爆，和网速限制不是一回事”。本计划认同这个判断：全局限速和背压都属于网络流控，但目标不同，应统一入口、分离职责。

主要场景包括 `remoting`、`socksproxyserver`、`transport`、socks UDP relay / shadowsocks UDP 等。

# 任务类型判断

本次任务属于“新需求 + 现有实现 review / 重构规划”：

- 新需求：新增全局上行 / 下行带宽限制，并要求作用于 `Sockets` 创建的所有 Netty bootstrap。
- Review / 重构：梳理现有 TCP `BackpressureHandler` 和 socks / UDP 中分散的 pending bytes、writable、drop / fail-fast 等过载保护逻辑，设计统一管理入口。

根据仓库规则，本阶段只提交计划文档，不修改业务代码。用户明确说“按计划执行 / 开始修改代码 / 继续写代码”后，才进入实现阶段。

# 当前上下文

已 review / 定位的相关文件：

- `AGENTS.md`
- `rxlib/src/main/java/org/rx/net/Sockets.java`
- `rxlib/src/main/java/org/rx/net/BackpressureHandler.java`
- `rxlib/src/main/java/org/rx/net/GlobalChannelHandler.java`
- `rxlib/src/main/java/org/rx/net/SocketConfig.java`
- `rxlib/src/main/java/org/rx/net/transport/TcpServer.java`
- `rxlib/src/main/java/org/rx/net/transport/DefaultTcpClient.java`
- `rxlib/src/main/java/org/rx/net/transport/UdpClient.java`
- `rxlib/src/main/java/org/rx/net/rpc/Remoting.java`
- `rxlib/src/main/java/org/rx/net/socks/SocksProxyServer.java`
- `rxlib/src/main/java/org/rx/net/socks/SocksTcpFrontendRelayHandler.java`
- `rxlib/src/main/java/org/rx/net/socks/SocksTcpBackendRelayHandler.java`
- `rxlib/src/main/java/org/rx/net/socks/SocksUdpRelayHandler.java`
- `rxlib/src/main/java/org/rx/net/socks/SSUdpProxyHandler.java`
- `rxlib/src/main/java/org/rx/net/socks/upstream/SocksUdpUpstream.java`
- `rxlib/src/main/java/org/rx/net/socks/UdpManager.java`
- `rxlib/src/main/java/org/rx/core/RxConfig.java`
- `.github/workflows/jdk8-unit-tests.yml`

仓库结构：

- `org.rx.net` 是网络基础层，`Sockets` 是 TCP / UDP bootstrap、channel option、initializer、event loop 和 handler 接入的主要统一入口。
- `org.rx.net.transport` 提供 TCP / UDP client / server 基础传输能力。
- `org.rx.net.socks` 包含 socks5、shadowsocks、udp2raw、UDP relay 等上层场景。
- `org.rx.net.rpc.Remoting` 依赖 `Sockets` / transport 层创建连接。
- `RxConfig` 已有 `app.net.*` 配置体系，适合增加全局流控配置。
- CI 中 `.github/workflows/jdk8-unit-tests.yml` 是 manual-only `workflow_dispatch`，支持 `test_classes` 输入，代码阶段可定向触发。

当前实现意图和关键调用链：

- TCP 背压：`BackpressureHandler` 基于 Netty `Channel.isWritable()` 与写缓冲水位，在 outbound 不可写时暂停 inbound `autoRead`，在恢复可写时恢复读取，目的是阻止写队列持续增长导致 heap / direct memory 压力。
- UDP 过载保护：UDP 没有 TCP stream 层的天然背压，现有 socks / UDP relay 多在应用层通过 channel writable、pending bytes / packets、MTU、drop / fail-fast / `UdpSendResult` 等策略控制内存和队列。
- 全局网速限制：目标是控制总吞吐 bytes per second，可能引入延迟或有限排队；它不能替代背压，也不应与背压混成一个开关。

# 目标

1. 增加 `org.rx.net` 下的全局网络流控管理层，统一承载全局限速、TCP 背压安装、UDP 过载策略入口。
2. 以 `Sockets` 创建 bootstrap / channel initializer 为统一入口，让 `remoting`、`socksproxyserver`、`transport` 默认继承全局流控能力。
3. 支持进程级全局上行 / 下行限速，多个 bootstrap / channel 共享同一限速配额。
4. 默认配置下保持兼容：未启用限速时，不改变现有吞吐、连接、UDP drop 策略。
5. 抽出 TCP 和 UDP 背压的共性管理 facade，但保留二者机制差异：TCP 以 writable / autoRead 为主，UDP 以有界 pending / drop / fail-fast 为主。
6. 保持 Java 8 兼容，不引入重型依赖。
7. 代码阶段通过定向单测和 GitHub Actions `JDK 8 Unit Tests` 验证。

# 非目标

1. 不引入大型第三方限流依赖。
2. 不修改 secrets、token、证书、私钥。
3. 不自动发布 release。
4. 首期不做每用户、每 endpoint、每协议的独立限速配额。
5. 不把 UDP 改造成强排队的可靠传输层；UDP 过载时仍允许 drop / fail-fast。
6. 首期不改变 socks proxy 的 routing、auth、encryption、udp2raw 协议语义。
7. 不把“带宽限制”和“防内存爆背压”合并为同一个策略；两者可由同一 facade 管理，但配置、日志和指标要分离。

# 设计方案

## 总体设计

新增轻量的 `NetworkFlowControl` / `NetworkTrafficManager` 类组，由 `Sockets` 统一调用：

1. `RxConfig.NetConfig` 增加全局流控配置，例如 `globalTraffic`。
2. `Sockets` 在创建 TCP server child channel、TCP client channel、UDP datagram channel 时，统一调用 flow-control installer。
3. 全局限速优先评估 Netty 内置 `GlobalTrafficShapingHandler` 或 `GlobalChannelTrafficShapingHandler`。
4. TCP 背压继续复用现有 `BackpressureHandler`，但通过 `TcpBackpressureManager` 统一安装和管理。
5. UDP 过载保护抽出 `UdpBackpressurePolicy` / `UdpOverloadPolicy`，上层 UDP 写入点在发送前统一询问策略。

## 配置设计

建议新增配置项：

- `app.net.globalTraffic.enabled`
- `app.net.globalTraffic.uploadBytesPerSecond`
- `app.net.globalTraffic.downloadBytesPerSecond`
- `app.net.globalTraffic.checkIntervalMillis`
- `app.net.globalTraffic.tcpBackpressureEnabled`
- `app.net.globalTraffic.udpBackpressureEnabled`
- `app.net.globalTraffic.udpMaxPendingBytes`
- `app.net.globalTraffic.udpMaxPendingPackets`

`uploadBytesPerSecond` / `downloadBytesPerSecond` 为 0 或小于 0 时表示不限制。实现阶段必须通过测试确认 Netty traffic shaping 的 readLimit / writeLimit 与“上行 / 下行”语义没有接反。

## 建议新增类

- `NetworkTrafficConfig`：全局限速与背压相关配置 POJO。
- `NetworkFlowControl`：统一 facade，负责安装 traffic shaping handler、暴露 TCP / UDP 策略入口、刷新配置。
- `TcpBackpressureManager`：封装 `BackpressureHandler.install(...)`，避免 socks / transport / remoting 分散直接调用。
- `UdpBackpressurePolicy`：统一 UDP 写入前的过载判断。
- `UdpBackpressureDecision`：表达 allow / delay / drop / fail-fast 与 reason。

## TCP 接入

- 在 `Sockets` 的 TCP server child pipeline 和 TCP client pipeline 初始化时安装全局 traffic shaping handler。
- `BackpressureHandler` 仍只在端到端 relay 场景安装，因为它需要 inbound / outbound 两个 channel 的关系，不适合所有 TCP channel 默认安装。
- 现有 `BackpressureHandler` 核心逻辑尽量不改，只通过 manager 收束入口和后续指标。

## UDP 接入

- 在 `Sockets` 创建 Datagram bootstrap / channel pipeline 时安装全局 traffic shaping handler。
- `UdpClient`、`SocksUdpRelayHandler`、`SSUdpProxyHandler`、`SocksUdpUpstream` 保留现有 pending / drop 语义，但逐步改为调用统一 `UdpBackpressurePolicy`。
- UDP 限速不能导致无限排队；必须保留有界 pending bytes / packets 或最大延迟策略。

## 资源和异常处理

- 全局 traffic shaping handler 应使用现有 Netty event loop / executor，避免每个 bootstrap 创建新线程。
- 需确认 Netty handler 是否可多 pipeline 共享；若不可共享，则改为共享 counter / executor 的 per-channel handler 方案。
- traffic shaping 延迟写入、UDP drop / fail-fast 都必须明确 ByteBuf 所有权，避免重复 release 或泄漏。
- 配置刷新应避免影响已存在 channel 的稳定性；如需替换 handler，应明确旧 channel 行为。

# 修改文件列表

预计实现阶段新增 / 修改：

- `rxlib/src/main/java/org/rx/core/RxConfig.java`
- `rxlib/src/main/java/org/rx/net/NetworkTrafficConfig.java`
- `rxlib/src/main/java/org/rx/net/NetworkFlowControl.java`
- `rxlib/src/main/java/org/rx/net/TcpBackpressureManager.java`
- `rxlib/src/main/java/org/rx/net/UdpBackpressurePolicy.java`
- `rxlib/src/main/java/org/rx/net/UdpBackpressureDecision.java`
- `rxlib/src/main/java/org/rx/net/Sockets.java`
- `rxlib/src/main/java/org/rx/net/BackpressureHandler.java`
- `rxlib/src/main/java/org/rx/net/transport/UdpClient.java`
- `rxlib/src/main/java/org/rx/net/socks/SocksUdpRelayHandler.java`
- `rxlib/src/main/java/org/rx/net/socks/SSUdpProxyHandler.java`
- `rxlib/src/main/java/org/rx/net/socks/upstream/SocksUdpUpstream.java`
- `rxlib/src/test/java/...` 相关单测 / 集成测试

本次仅新增计划文档：

- `docs/plan/global-net-flow-control-and-backpressure-plan.md`

# 风险点

1. 上行 / 下行方向语义可能因 client、server、proxy 角色不同而混淆，必须实测验证。
2. Netty 全局 traffic shaping handler 是否可多 pipeline 共享需确认，否则要采用共享 counter 的 per-channel handler。
3. UDP 如果只做限速而没有有界队列，可能把过载从“丢包”变成“内存堆积”。
4. ByteBuf 所有权和 release 时机可能受延迟写入 / drop / close 影响。
5. 全局计数器、timer、atomic 操作可能增加高并发路径开销，需避免每包重日志和额外对象分配。
6. 默认未启用限速时必须保持现有行为。
7. 限速类测试容易受时序影响，阈值要留余量，避免 flaky。

# 验证方案

代码实现后建议验证：

1. 单测：
   - `NetworkFlowControlTest`
   - `BackpressureHandlerTest`
   - `UdpBackpressurePolicyTest`
2. 集成测试：
   - `RemotingTest`
   - `SocksProxyServerIntegrationTest`
   - `Socks5ClientIntegrationTest`
   - `ShadowsocksServerIntegrationTest`
3. 行为验证：
   - 全局限速为 0 / disabled 时保持现有行为。
   - 小带宽限制下，多个 TCP channel 共享同一限速配额。
   - UDP 小带宽限制下 pending bytes / packets 不应无限增长。
   - TCP relay outbound 不可写时仍能触发 inbound pause，恢复可写后恢复读取。
4. GitHub Actions：
   - 代码 commit 后手动触发 `.github/workflows/jdk8-unit-tests.yml`。
   - 使用 `workflow_dispatch` 的 `test_classes` 输入。
   - 按当前 agent 分支过滤 workflow run。
   - 只有 `conclusion=success` 才认为 CI 通过。

推荐首次 `test_classes`：

```text
NetworkFlowControlTest,BackpressureHandlerTest,UdpBackpressurePolicyTest,RemotingTest,SocksProxyServerIntegrationTest,Socks5ClientIntegrationTest,ShadowsocksServerIntegrationTest
```

如部分测试类在实现前不存在，则按实际新增或现有测试类调整，不伪造 CI 结果。
