# org.rx.net 包 follow-up review 计划（排除 FEC 与 HTTP Tunnel）

# 背景

用户要求在 `RockyLOMO/rxlib` 仓库 `master` 分支上，对 `rxlib` 模块中 `org.rx.net` 包下的类进行仔细 review，并明确排除 FEC 与 HTTP Tunnel 相关代码。

本轮 review 范围：

- 主代码：`rxlib/src/main/java/org/rx/net/**`
- 排除：`rxlib/src/main/java/org/rx/net/Fec*.java`、`rxlib/src/main/java/org/rx/net/socks/httptunnel/**`
- 关联测试：`rxlib/src/test/java/org/rx/net/**`
- 测试排除：`FecCodecTest`、`socks/httptunnel/**`

仓库中已存在历史计划 `docs/plan/review-rx-net-excluding-fec-httptunnel-20260505.md`，其中记录过上一轮 review 与部分修复结论。本计划不覆盖历史文件，而是作为当前 `master` 最新状态下的 follow-up review 计划，用于继续排查剩余风险与补充回归验证。

# 任务类型判断

本次任务归类为 **Review / 修复 / 优化需求**。

原因：

- 用户要求“仔细慢慢地 review”，目标是检查现有实现的风险点、调用链、边界条件和测试覆盖。
- 当前没有提出新增功能、新接口或新协议支持。
- 已按用户要求进入 follow-up 修复阶段：认可的 P0/P1 小范围问题直接修复并补测试；范围大或证据不足的项明确标为暂缓。
- 本次只修改 `org.rx.net` 范围内已确认的小问题，不扩大到 FEC、HTTP Tunnel 或大规模重构。

# 当前上下文

## 仓库与模块

- 仓库：`RockyLOMO/rxlib`
- 分支：`master`
- 模块：`rxlib`
- 构建：Maven 多模块父工程，`rxlib/pom.xml` 启用测试。
- Java 基线：Java 8 / source 1.8 / target 1.8。
- 网络栈核心依赖：Netty、BouncyCastle、SSHD、Fury、LZ4、fastutil、Caffeine、H2 等。
- 仓库约束：优先最小改动，不升级依赖，不引入重型依赖，不修改 secrets/token/证书/私钥，不发布 release。

## 已 review / 扫描的主代码文件

顶层 `org.rx.net`：

- `AuthenticEndpoint.java`
- `BackpressureHandler.java`
- `CipherDecoder.java`
- `CipherEncoder.java`
- `FuryCodecSupport.java`
- `GlobalChannelHandler.java`
- `HttpPseudoHeaderDecoder.java`
- `HttpPseudoHeaderEncoder.java`
- `NetEventWait.java`
- `OptimalSettings.java`
- `PingClient.java`
- `SocketConfig.java`
- `SocketInfo.java`
- `SocketProtocol.java`
- `Sockets.java`
- `TcpStatus.java`
- `TransportFlags.java`

明确排除：

- `FecConfig.java`
- `FecDecoder.java`
- `FecEncoder.java`
- `FecPacket.java`
- `FecUdpClient.java`

已纳入 review 的子包：

- `dns/**`
- `http/**`
- `nameserver/**`
- `ntp/**`
- `punch/**`
- `rpc/**`
- `socks/**`
- `socks/encryption/**`
- `socks/upstream/**`
- `support/**`
- `transport/**`
- `transport/hybrid/**`
- `transport/protocol/**`

明确排除：

- `socks/httptunnel/**`

## 已 review / 扫描的测试文件

顶层测试：

- `BackpressureHandlerTest.java`
- `GlobalChannelHandlerTest.java`
- `SocketsReusePortTest.java`
- `SocketsTest.java`
- `TestSocks.java`

子包测试：

- `dns/DnsClientSingletonTest.java`
- `dns/DnsOptimizationTest.java`
- `dns/DnsServerIntegrationTest.java`
- `dns/DnsTcpPortMuxHandlerTest.java`
- `dns/DoHClientIntegrationTest.java`
- `dns/DoHClientTest.java`
- `dns/DoHMessageCodecTest.java`
- `http/HttpClientIntegrationTest.java`
- `http/HttpClientTest.java`
- `http/HttpServerBlockingTest.java`
- `http/HttpServerExceptionTest.java`
- `socks/CipherCodecTest.java`
- `socks/RrpIntegrationTest.java`
- `socks/RrpRemoteSmokeTest.java`
- `socks/SSProtocolCodecTest.java`
- `socks/SSUdpProxyHandlerTest.java`
- `socks/ShadowsocksServerIntegrationTest.java`
- `socks/Socks5ClientIntegrationTest.java`
- `socks/Socks5ClientTest.java`
- `socks/Socks5CommandRequestHandlerTest.java`
- `socks/Socks5SessionPoolTest.java`
- `socks/SocksProxyServerIntegrationTest.java`
- `socks/SocksProxyServerTest.java`
- `socks/SocksUdpRelayHandlerTest.java`
- `socks/TimeoutVerificationTest.java`
- `socks/Udp2raw*Test.java`
- `socks/UdpCompressTest.java`
- `socks/UdpRedundant*Test.java`
- `socks/UdpValidationTest.java`
- `socks/upstream/SocksUdpUpstreamPortHoppingTest.java`
- `transport/**` 下 TCP / UDP / Hybrid transport 测试

明确排除：

- `FecCodecTest.java`
- `socks/httptunnel/HttpTunnelTest.java`

## 关键调用链

### 1. Netty Bootstrap 与全局 handler

- `Sockets.serverBootstrap(...)` / `Sockets.bootstrap(...)`
- `SocketChannelInitializer`
- `GlobalChannelHandler.DEFAULT`
- 各业务 `initChannel` 回调
- `TcpServer` / `DefaultTcpClient` / `UdpClient` / Hybrid transport 上层封装

关注点：

- `ChannelOption`、watermark、autoRead、keepAlive、reusePort、DNS resolver group 的一致性。
- `GlobalChannelHandler` 对 bind/connect/write/exception 的日志与 close 行为是否影响业务 handler。
- `write` listener 的注册时机是否足够稳健。

### 2. 背压链路

- relay/proxy handler 建立 inbound/outbound channel 对。
- `BackpressureHandler.install(inbound, outbound)` 安装在 outbound pipeline。
- outbound `channelWritabilityChanged`：
  - 不可写时回调 `onBackpressureStart(inbound, outbound)`，默认关闭 inbound autoRead。
  - 恢复可写时回调 `onBackpressureEnd(inbound, outbound, cause)`，默认恢复 inbound autoRead。
- `channelActive` / `channelInactive` / `exceptionCaught` 处理连接生命周期边界。

关注点：

- timer/cooldown 分支是否继续向后传播 writability 事件。
- outbound 关闭、异常、重连时是否总能恢复或清理 inbound 的读取状态。
- 自定义回调不仅控制 autoRead，还可能控制上层队列、限流器或业务暂停状态。

### 3. UDP transport

- `UdpClient.send(...)` / `requestAsync(...)`
- `SendContext`
- fragment/ack/retry
- `Sockets.writeUdp(...)`
- Netty `DatagramPacket`
- `channelRead0(...)`
- receive assembly / duplicate window / ack / timeout

关注点：

- pending send/request/receive 生命周期是否在 close、timeout、异常路径全部清理。
- ByteBuf / DatagramPacket 引用计数是否在失败、丢弃、超限、重复、过期路径释放。
- EventLoop 内关闭是否同步阻塞。
- FULL ack 与 async receive 如果业务回调长时间未完成，是否导致 inflight receive 或 ack 长期悬挂。

### 4. HTTP client / server

- `HttpClient` request 构造、channel pool、upload writer、response body 管理。
- `HttpClientCache` / `HttpClientCookieJar`
- `HttpServer` / `ServerRequest` / `ServerResponse`
- `RestClient`

关注点：

- streaming upload 是否保证不在 Netty EventLoop 上执行阻塞 I/O。
- response body / pooled channel 归还语义是否在异常、取消、超时、日志预览路径一致。
- active response close 与 pool close 是否覆盖调用方未显式 close 的场景。

### 5. DNS / DoH

- `Sockets` resolver group 注入
- `DnsClient`
- `DnsResolveCore`
- `DnsServer`
- `DoHClient`
- `DoHMessageCodec`
- `DnsTcpPortMuxHandler`

关注点：

- hosts/interceptor/cache/upstream 的 fallback 是否会泄漏 query response。
- Caffeine/H2 cache 异常时是否正确 evict 并重试。
- TCP DNS 与 UDP DNS 在超时、截断、连接复用上的边界测试。

### 6. SOCKS / Shadowsocks / RRP / UDP relay

- `SocksProxyServer`
- `SocksContext`
- `Socks5*Handler`
- TCP relay frontend/backend handler
- `SocksUdpRelayHandler`
- `SSUdpProxyHandler`
- upstream pool
- UDP2raw / redundant / compress / port hopping

关注点：

- channel attr、session tag、traffic metric、close hook 的一致性。
- UDP relay group、port hopping、redundant/compress 编解码的异常释放路径。
- `socks/upstream/**` 与 `socks/**` 之间的 session 生命周期是否存在双重 close 或遗漏 close。

### 7. RPC / Hybrid

- `Remoting`
- `FuryRemotingCodecFactory`
- `RpcClientPool`
- `RpcHybridClientPool`
- TCP / UDP / Hybrid transport
- `transport/hybrid/DefaultHybridSession`

关注点：

- pending call / event context / compute context 在 timeout、disconnect、reconnect 下是否清理。
- Hybrid route state、probe、TCP/UDP fallback 的状态迁移是否有并发竞态。
- Fury codec 对 ByteBuf / byte[] 的生命周期是否明确。

## 当前观察到的候选问题 / 风险

### P0：`Sockets.addAfter(...)` handler 插入方向疑似错误

状态：**已修复，已补测试，已验证通过**。

观察：

- `Sockets.addBefore(...)` 使用 `pipeline.addBefore(...)`，符合函数名语义。
- 修复前 `Sockets.addAfter(...)` 中按 reverse 顺序遍历 handler，但实际调用仍是 `pipeline.addBefore(baseName, ...)`。
- 从命名和调用意图看，`addAfter` 应该把 handlers 插到 `baseName` 后面；原实现会插到 `baseName` 前面，可能导致 codec、cipher、压缩、协议 handler 顺序反转或错位。

候选修复方向：

- 保持 reverse 遍历，但将 `pipeline.addBefore(baseName, ...)` 改为 `pipeline.addAfter(baseName, ...)`。
- 用 `EmbeddedChannel` 增加 pipeline 顺序回归测试：
  - `addBefore(base, A, B)` 期望 `A -> B -> base`
  - `addAfter(base, A, B)` 期望 `base -> A -> B`

实际修复：

- `Sockets.addAfter(...)` 保持 reverse 遍历，但改为调用 `pipeline.addAfter(baseName, ...)`。
- 在 `SocketsTest` 中新增 pipeline 顺序测试，固定 `addBefore` 和 `addAfter` 的插入语义。

### P1：`BackpressureHandler.channelInactive(...)` 关闭路径未触发 end callback

状态：**已修复，已补测试，已验证通过**。

观察：

- 不可写时，handler 通过 `paused=true` 和 `onBackpressureStart` 暂停 inbound。
- 恢复可写时，`handleRecovery(...)` 会调用 `onBackpressureEnd`。
- 修复前 outbound `channelInactive` 只停止 timer 并把 `paused=false`，没有调用 `onBackpressureEnd`。
- 默认 install 回调只控制 inbound autoRead；如果上层传入自定义回调，还可能维护业务队列暂停状态。关闭路径不触发 end callback，可能让外部状态停留在 paused。

候选修复方向：

- 当 `channelInactive` 发生且 `paused=true` 时，统一走恢复回调或显式调用 `onBackpressureEnd`，但必须避免重复恢复。
- 如果 inbound 已关闭，应确认回调是否仍需要执行；至少要在测试中覆盖 inbound open / closed 两种场景。
- 保持 `stopTimer()`，避免 delayed recovery 在 channel 关闭后再次运行。

实际修复：

- `channelInactive(...)` 先 `stopTimer()`，再通过 `handleRecovery(ctx.channel(), 0, null)` 统一触发恢复逻辑。
- `handleRecovery(...)` 自带 `paused` guard，可避免重复触发 end callback。
- `BackpressureHandlerTest` 覆盖 inbound open 与 inbound closed 两种关闭路径，确认外部回调会被执行且 paused 状态被清理。

### P1：`GlobalChannelHandler.write(...)` listener 注册时机可进一步稳健化

状态：**暂缓，不做本轮代码修改**。

观察：

- `GlobalChannelHandler.write(...)` 跳过 `voidPromise`，对普通 promise 在 `super.write(ctx, msg, promise)` 后追加 listener。
- 正常 Netty pipeline 中 write promise 多数不会同步完成，但在 `EmbeddedChannel` 或自定义 handler/future 中可能存在更早完成的路径。
- 对日志统计类 listener，错过 completion 的风险较低；如果未来在 listener 中加入资源清理或指标约束，风险会上升。

候选修复方向：

- 优先补充回归测试确认现有行为。
- 如需调整，尽量在 `super.write(...)` 前注册 listener，保持语义不变。

暂缓原因：

- 当前 listener 只做日志，不承担资源释放或背压状态维护。
- Netty `Future.addListener(...)` 对已完成 future 仍会回调 listener；当前风险没有稳定复现证据。
- 后续如果 listener 增加资源清理、指标强约束或测试复现同步完成边界，再单独处理。

### P1：`UdpClient.close()` 在 EventLoop 上同步等待 close 风险确认

状态：**已在历史修复中完成，本轮不重复改动**。

观察：

- `UdpClient.close()` 会清理 pending sends / requests / receives，然后遍历 channel 并调用 close。
- 历史计划中提过 `ch.close().syncUninterruptibly()` 在 EventLoop 中可能造成阻塞风险。
- 当前 master 已确认该路径避免 EventLoop 内同步等待，并已有测试覆盖。

候选修复方向：

- 如果仍存在同步等待，改为：
  - EventLoop 内直接 `ch.close()`，不等待。
  - 非 EventLoop 调用保留同步或统一异步 close 语义。
- 增加 EventLoop 内调用 close 不阻塞的测试。

当前确认：

- 当前代码已判断调用线程是否属于任一 UDP channel 的 EventLoop。
- EventLoop 内只发起异步 `ch.close()`，非 EventLoop 仍保留同步等待。
- 已有 `UdpTransportTest.closeFromEventLoopDoesNotBlockEventLoop` 覆盖该路径。

### P2：UDP FULL ack 与 async receive 悬挂风险需要验证

状态：**暂缓，保留后续专项评估**。

观察：

- UDP FULL ack 依赖业务 receive 处理完成后再发送 ack。
- 如果业务 `onReceive` future 长时间不完成，`inflightReceives` 可能长期保留，ack 也可能迟迟不发。
- 该设计可能是为了保证可靠投递语义，但需要明确是否有超时保护。

候选修复方向：

- 先审查现有 receive timeout / assembly expire 是否覆盖该场景。
- 如果没有保护，考虑为 async receive 增加轻量超时清理或文档化语义。
- 回归测试应模拟业务回调不完成、异常完成、延迟完成三类场景。

暂缓原因：

- 该项涉及可靠投递语义，提前 ack 或超时清理都可能改变协议行为。
- 需要先审清事件系统、receive timeout、assembly expire 与重试策略后再动代码。

### P2：`Sockets.writeUdp(...)` 失败路径引用计数语义需 leak-aware 测试确认

状态：**暂缓，本轮不改代码**。

观察：

- 当前实现对 inactive、unresolved recipient、pending over-limit、not writable、write throw 等路径有显式释放/丢弃逻辑。
- 对 `writeAndFlush` 后 listener failure 的 ownership 语义需要通过 Netty leak detector 或 refCnt 断言确认，避免误释放或未释放。
- UDP 高吞吐场景下，pending bytes 递减依赖 completion listener；如果 future 不完成，soft limit 可能持续生效。

候选修复方向：

- 增加 `EmbeddedChannel` 或 fake promise 的失败路径测试。
- 尽量只增强测试，除非确认存在引用计数漏洞。

暂缓原因：

- 当前已有 inactive、unresolved、pending over-limit、not writable、write throw 等常规失败路径测试。
- `writeAndFlush` listener failure 的 ownership 需要 leak-aware 或更精确 promise 模拟，避免误释放或双释放。

### P2：HTTP streaming upload / response close 需要继续看齐 EventLoop 模型

状态：**部分已在历史修复中完成；剩余 response close 细分场景暂缓**。

观察：

- `HttpClient` 已有 streaming upload、upload writer、response active set 与 pool close 逻辑。
- 日志预览会读取 response body stream 的前缀并释放临时 buffer。
- 仍需验证慢 body、异常 body、取消 body、调用方未 close body 的连接归还路径。

候选修复方向：

- 优先补充 `HttpClientTest` 中慢 body、write timeout、response close/pool release 的回归测试。
- 不改变现有 public API。

当前确认与暂缓原因：

- 历史修复已确认 streaming upload 通过业务线程执行，并在 `UploadWriter` 构造处加 EventLoop guard。
- 慢 body、异常 body、取消 body、调用方未 close body 等连接归还细分场景范围较大，本轮未展开，后续按实际复现补测试。

### P2：DNS / DoH cache 与 upstream fallback 测试覆盖需补齐

状态：**暂缓，保留后续按 DNS 专项补测**。

观察：

- `DnsResolveCore` 对 hosts、fake host suffix、interceptor cache、cache 异常 evict、upstream fallback 有多分支。
- DNS 是 `Sockets` 和 SOCKS/RPC 的基础设施，错误会表现为连接失败或代理异常。

候选修复方向：

- 对 cache `RuntimeException` evict 后重试、interceptor 空结果、`.lan` 跳过 interceptor、A/AAAA 以外类型 fallback 补测试。
- 保持 DNS 策略行为不变。

暂缓原因：

- 本轮无 DNS 行为修改，直接补大范围 DNS 分支测试会扩大验证面。
- DNS/DoH 需独立跑 `DnsOptimizationTest`、`DnsServerIntegrationTest`、`DoHMessageCodecTest` 等组合验证。

### P2：SOCKS / UDP relay / upstream 的 close 与 attr 清理需针对性补测试

状态：**暂缓，保留后续按实际 bug 或修改点补测**。

观察：

- SOCKS 与 UDP relay 相关类数量多，且包含 UDP2raw、redundant、compress、port hopping 等复杂路径。
- 当前已有较多测试，但仍建议按实际修复点补充小型单元/集成测试，避免一次性大重构。

候选修复方向：

- 优先围绕实际发现的具体 bug 增加测试。
- 避免一次性重构 `SocksProxyServer`、`SocksUdpRelayHandler`、`SocksUdpUpstream` 等大文件。
- 重点看 session map、channel attr、metric tag、pending queue 在 close/exception 中是否清理。

暂缓原因：

- SOCKS / UDP relay / upstream 影响面大、集成测试重。
- 当前没有本轮直接修改点，后续只在具体问题复现后做小步修复。

# 目标

1. 在当前 `master` 上更新本轮 follow-up 文档，记录已认可修复与暂缓项。
2. 明确 review 范围：`rxlib` 模块 `org.rx.net` 包，排除 FEC 与 HTTP Tunnel。
3. 记录当前关键调用链、已扫描文件、候选风险点和优先级。
4. 已完成认可的小范围修复：
   - 修复/验证 `Sockets.addAfter(...)` handler 顺序。
   - 修复/验证 `BackpressureHandler` 关闭恢复语义。
5. 将 UDP / HTTP / DNS / SOCKS / RPC 剩余风险按证据暂缓，后续专项处理。

# 非目标

1. 不 review 或修复 FEC 相关代码。
2. 不 review 或修复 HTTP Tunnel 相关代码。
3. 不升级 Netty、Fury、BouncyCastle、Spring 或其他依赖。
4. 不引入新框架或重型依赖。
5. 不修改 public API，除非后续修复必须且经过兼容性评估。
6. 不修改 secrets、token、证书、私钥。
7. 不发布 release。
8. 不一次性重构 SOCKS / UDP relay / RPC 大文件。

# 设计方案

## 阶段 0：计划提交

状态：**已完成**。

已更新计划文档：

- `docs/plan/review-org-rx-net-followup-20260505.md`

本轮已按用户要求进入小范围代码修复阶段。

## 阶段 1：P0 回归测试与最小修复

### `Sockets.addAfter(...)`

状态：**已完成**。

实际修改：

- `rxlib/src/main/java/org/rx/net/Sockets.java`
- `rxlib/src/test/java/org/rx/net/SocketsTest.java`

设计：

- 使用 Netty `EmbeddedChannel` 构造 pipeline。
- 构造 base handler 与两个 marker handler。
- 验证：
  - `addBefore(base, A, B)` 保持 `A -> B -> base`。
  - `addAfter(base, A, B)` 修复后为 `base -> A -> B`。
- 最小代码改动：
  - 保持 reverse loop。
  - 把 `pipeline.addBefore(baseName, ...)` 改为 `pipeline.addAfter(baseName, ...)`。
- 不调整调用方，不重构 pipeline 工具类。

异常处理：

- 如果 handler 名称冲突，应保持 Netty 原有异常语义，不吞异常。
- 测试避免复用同名 handler class 导致误判。

资源释放：

- 测试结束 close `EmbeddedChannel`。
- 不创建真实 socket。

## 阶段 2：P1 背压关闭恢复测试与修复

### `BackpressureHandler.channelInactive(...)`

状态：**已完成**。

实际修改：

- `rxlib/src/main/java/org/rx/net/BackpressureHandler.java`
- `rxlib/src/test/java/org/rx/net/BackpressureHandlerTest.java`

设计：

- 增加测试：
  - outbound not writable 触发 start callback。
  - 在 paused 状态下触发 outbound inactive。
  - 验证 end callback 是否被调用。
  - 验证 inbound autoRead 或自定义 paused flag 恢复。
- `channelInactive` 先 `stopTimer()`，再调用 `handleRecovery(...)`。
- `handleRecovery(...)` 自带 paused guard，避免重复恢复。
- 保持 cooldown/timer 逻辑不扩大范围。

异常处理：

- callback 抛异常时沿用 Netty handler 异常传播语义。
- 不吞掉 `super.channelInactive(ctx)`。

资源释放：

- 停止 timer。
- 不持有额外 channel 强引用。

## 阶段 3：P1/P2 只按证据补充测试

状态：**暂缓**。

候选范围：

- `GlobalChannelHandler.write(...)`
- UDP FULL ack 与 async receive
- `Sockets.writeUdp(...)`
- `HttpClient` streaming upload / response close
- `DnsResolveCore` cache fallback
- SOCKS / UDP relay close path
- RPC / Hybrid pending cleanup

原则：

- 先写能复现风险的小测试。
- 测试能稳定失败再做最小修复。
- 不把多个大模块问题揉进同一个 commit。
- 每个 commit 只解决一个明确风险点。

## 数据流与异常处理

- Netty inbound/outbound pipeline 的 handler 顺序必须由测试固定。
- 背压状态从 outbound writability 事件流向 inbound autoRead/业务暂停状态。
- UDP 数据包从 codec -> fragment -> `Sockets.writeUdp` -> Netty `DatagramPacket`，失败路径必须明确释放。
- HTTP streaming upload 从业务线程读取 input/stream，再写入 Netty channel；EventLoop 不做阻塞 I/O。
- DNS fallback 从 hosts/interceptor/cache/upstream 逐层降级；cache 异常需要 evict 后重试。
- RPC pending call/event context 在 timeout/disconnect/reconnect 中必须完成或清理。

## 资源释放策略

- `ByteBuf` / `DatagramPacket` 所有权必须在每个失败分支清晰可证明。
- `ChannelFuture` listener 不承担重复释放，除非明确拥有引用。
- `UdpClient.close()` 清理 pending sends / requests / receives 后关闭 channel。
- HTTP response body 必须由调用方 close 或 client close 兜底关闭。
- timer / scheduled future 在 inactive/close/error 中 cancel。
- static shared resources 不在本次修复中引入全局 shutdown，除非测试证明泄漏严重。

# 修改文件列表

## 本轮实际修改

- `docs/plan/review-org-rx-net-followup-20260505.md`
- `rxlib/src/main/java/org/rx/net/Sockets.java`
- `rxlib/src/main/java/org/rx/net/BackpressureHandler.java`
- `rxlib/src/test/java/org/rx/net/SocketsTest.java`
- `rxlib/src/test/java/org/rx/net/BackpressureHandlerTest.java`

## 本轮暂缓项后续可能修改

按优先级和测试证据拆分：

1. P1/P2 全局 handler：
   - `rxlib/src/main/java/org/rx/net/GlobalChannelHandler.java`
   - `rxlib/src/test/java/org/rx/net/GlobalChannelHandlerTest.java`

2. P2 UDP transport / writeUdp：
   - `rxlib/src/main/java/org/rx/net/transport/UdpClient.java`
   - `rxlib/src/test/java/org/rx/net/transport/UdpTransportTest.java`
   - 可能补充 `rxlib/src/test/java/org/rx/net/SocketsTest.java`

3. P2 HTTP：
   - `rxlib/src/main/java/org/rx/net/http/HttpClient.java`
   - `rxlib/src/test/java/org/rx/net/http/HttpClientTest.java`

4. P2 DNS：
   - `rxlib/src/main/java/org/rx/net/dns/DnsResolveCore.java`
   - `rxlib/src/test/java/org/rx/net/dns/DnsOptimizationTest.java`
   - `rxlib/src/test/java/org/rx/net/dns/DoHMessageCodecTest.java`

5. P2 SOCKS / UDP relay / upstream：
   - 只在测试复现后选择具体文件，例如：
     - `rxlib/src/main/java/org/rx/net/socks/SocksUdpRelayHandler.java`
     - `rxlib/src/main/java/org/rx/net/socks/SSUdpProxyHandler.java`
     - `rxlib/src/main/java/org/rx/net/socks/upstream/SocksUdpUpstream.java`
     - 对应 `rxlib/src/test/java/org/rx/net/socks/**`

6. P2 RPC / Hybrid：
   - 只在测试复现后选择具体文件，例如：
     - `rxlib/src/main/java/org/rx/net/rpc/Remoting.java`
     - `rxlib/src/main/java/org/rx/net/transport/hybrid/DefaultHybridSession.java`
     - 对应 `rxlib/src/test/java/org/rx/net/rpc/**` 或 `transport/hybrid/**`

# 风险点

## 兼容性风险

- `Sockets.addAfter(...)` 如果已有调用方“依赖错误行为”，修复后 pipeline 顺序会变化；必须用测试固定期望，并抽查调用方。
- `BackpressureHandler.channelInactive(...)` 如果改为触发 end callback，可能改变外部自定义回调触发次数；必须避免重复回调。
- HTTP / DNS / SOCKS / RPC 修改都可能影响 public behavior，因此后续必须最小化。

## 性能风险

- 背压 cooldown 不能变成高频事件抖动。
- UDP pending bytes / receive assembly 不能引入额外锁竞争或高频对象分配。
- HTTP streaming upload 不能把阻塞 I/O 放到 EventLoop。
- SOCKS UDP relay 热路径不能引入重型集合或频繁正则/反射。

## 并发风险

- `BackpressureHandler.timer` CAS 与 delayed task 必须保持线程安全。
- `UdpClient` pending maps 与 close 并发可能出现重复 complete/release。
- RPC pending/event context 与 reconnect/timeout 并发必须只完成一次。
- Hybrid route state 与 TCP/UDP 双通道状态更新存在竞态风险。

## 资源释放风险

- `ByteBuf` / `DatagramPacket` 引用计数最容易在失败分支、重复包、超限、write throw 中出错。
- HTTP response body / upload body 如果异常提前返回，可能影响 channel pool 归还。
- DNS query/response 如果 handler 中途异常，需确认对象释放。
- SOCKS UDP relay 中 channel attr、session map、metric tag 清理不一致可能造成内存泄漏。

## 测试风险

- 网络集成测试可能受端口、DNS、平台网络栈影响。
- JDK 8 CI 与本地 JDK 行为可能不同。
- 某些 SOCKS / UDP relay 测试较重，应优先跑小范围稳定测试，再跑组合测试。
- GitHub Actions `jdk8-unit-tests.yml` 是 `workflow_dispatch`，只有 `conclusion=success` 才能视为通过。

# 验证方案

## 当前修复验证

本地已执行：

```bash
mvn -pl rxlib "-Dgpg.skip=true" "-Dtest=SocketsTest,BackpressureHandlerTest" test
```

结果：

- Tests run: 37
- Failures: 0
- Errors: 0
- Skipped: 0
- BUILD SUCCESS

## 后续代码阶段本地验证建议

优先按修复范围跑小测试：

```bash
mvn -pl rxlib -Dgpg.skip=true -Dmaven.test.skip=false -DskipTests=false -Dtest=SocketsTest,BackpressureHandlerTest,GlobalChannelHandlerTest test
```

如果改动 UDP transport：

```bash
mvn -pl rxlib -Dgpg.skip=true -Dmaven.test.skip=false -DskipTests=false -Dtest=UdpTransportTest,SocketsTest test
```

如果改动 HTTP：

```bash
mvn -pl rxlib -Dgpg.skip=true -Dmaven.test.skip=false -DskipTests=false -Dtest=HttpClientTest,HttpServerBlockingTest,HttpServerExceptionTest test
```

如果改动 DNS / DoH：

```bash
mvn -pl rxlib -Dgpg.skip=true -Dmaven.test.skip=false -DskipTests=false -Dtest=DnsOptimizationTest,DnsServerIntegrationTest,DoHMessageCodecTest,DoHClientTest test
```

如果改动 SOCKS / UDP relay：

```bash
mvn -pl rxlib -Dgpg.skip=true -Dmaven.test.skip=false -DskipTests=false -Dtest=SocksUdpRelayHandlerTest,SocksProxyServerTest,Socks5ClientTest,Udp2rawHandlerTest,UdpCompressTest,UdpRedundantTest test
```

如果改动 RPC / Hybrid：

```bash
mvn -pl rxlib -Dgpg.skip=true -Dmaven.test.skip=false -DskipTests=false -Dtest=RemotingTest,FuryRemotingCodecTest test
```

大范围修改后再跑：

```bash
mvn -B -U -Dgpg.skip=true -Dmaven.test.skip=false -DskipTests=false clean test
```

## GitHub Actions 验证

后续代码 commit 后必须触发：

- workflow 文件：`.github/workflows/jdk8-unit-tests.yml`
- workflow 名称：`JDK 8 Unit Tests`
- 触发方式：`workflow_dispatch`
- 输入：
  - `reason`: 描述对应修复点
  - `test_classes`: 按修改范围选择，例如：
    - `SocketsTest,BackpressureHandlerTest,GlobalChannelHandlerTest`
    - 或 `UdpTransportTest,SocketsTest`
    - 或相关 DNS/HTTP/SOCKS/RPC 测试类

CI 规则：

- 查询 workflow run 时必须按当前分支过滤。
- `queued`、`in_progress`、`waiting` 不能视为成功。
- 只有 `conclusion=success` 才能认为 CI 通过。
- 如果失败，先读取失败日志并分类：编译失败、单测失败、格式失败、依赖下载失败、JDK 版本问题、环境问题、测试不稳定或 workflow 配置问题。
- 修复时只修改和失败直接相关的代码。
