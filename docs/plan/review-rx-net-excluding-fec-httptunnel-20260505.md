# 背景

用户要求对 `RockyLOMO/rxlib` 仓库中 `rxlib` 模块的 `org.rx.net` 包进行仔细 review，并明确排除 FEC 与 HTTP Tunnel 相关代码。

本次范围：

- 主代码：`rxlib/src/main/java/org/rx/net/**`
- 排除：`rxlib/src/main/java/org/rx/net/Fec*.java`、`rxlib/src/main/java/org/rx/net/socks/httptunnel/**`
- 关联测试：`rxlib/src/test/java/org/rx/net/**`
- 测试排除：`FecCodecTest`、`socks/httptunnel/**`

本计划文档最初记录 review 结论、风险点和后续修复计划；2026-05-05 已进入修复执行阶段，并在本文持续更新进度。

# 任务类型判断

本次任务归类为 **Review / 修复 / 优化需求**。

原因：用户要求“仔细慢慢地 review 下”现有实现；没有提出新增接口、协议或功能。按 agent 流程，review 类任务必须先理解现有代码、提交计划文档，等待用户明确要求后再进入代码实现阶段。

# 当前上下文

## 仓库与模块

- 仓库：`RockyLOMO/rxlib`
- 默认分支：`master`
- 模块：`rxlib`
- 目标包：`rxlib/src/main/java/org/rx/net`
- 技术栈：Java 8、Netty、Maven
- 风格约束：优先最小改动；保持现有 API 兼容；关注 ByteBuf / DatagramPacket / Channel 引用计数、释放、EventLoop 阻塞和网络生命周期。

## 已 review / 扫描的文件

顶层：

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

子包：

- `dns/**`
- `http/**`
- `nameserver/**`
- `ntp/**`
- `punch/**`
- `rpc/**`
- `socks/**`，排除 `socks/httptunnel/**`
- `socks/encryption/**`
- `socks/upstream/**`
- `support/**`
- `transport/**`
- `transport/hybrid/**`
- `transport/protocol/**`

明确排除：

- `FecConfig.java`
- `FecDecoder.java`
- `FecEncoder.java`
- `FecPacket.java`
- `FecUdpClient.java`
- `socks/httptunnel/**`

## 关键调用链

1. TCP / UDP lifecycle

   - `Sockets.serverBootstrap(...)`
   - `Sockets.bootstrap(...)`
   - `Sockets.bindChannels(...)`
   - `transport/TcpServer`
   - `transport/DefaultTcpClient`
   - `transport/UdpClient`
   - `GlobalChannelHandler`

2. 背压链路

   - relay / proxy handler 调用 `BackpressureHandler.install(inbound, outbound)`
   - outbound write 水位变化触发 `channelWritabilityChanged`
   - `Sockets.disableAutoRead(inbound)` 暂停上游读取
   - `Sockets.enableAutoRead(inbound)` 恢复上游读取

3. UDP transport 链路

   - `UdpClient.send(...) / requestAsync(...)`
   - `SendContext`
   - `writeFragments(...)`
   - `Sockets.writeUdp(...)`
   - Netty `DatagramPacket`
   - `channelRead0(...)`
   - `handlePacket(...)`
   - `handleData(...)`
   - `publishEventAsync(onReceive, ...)`
   - `sendAck(...)`

4. HTTP client 链路

   - `HttpClient` 请求构建
   - channel pool
   - request body / streaming upload
   - `UploadWriter.write/flush/finish`
   - response close / pool release

5. DNS / DoH 链路

   - `Sockets` DNS 注入与 resolver group
   - `DnsClient`
   - `DnsResolveCore`
   - `DnsServer`
   - `DoHClient`
   - `DoHMessageCodec`

6. RPC / Hybrid 链路

   - `Remoting`
   - `FuryRemotingCodecFactory`
   - TCP / UDP / Hybrid transport
   - `RpcClientPool`
   - `RpcHybridClientPool`

7. SOCKS / UDP relay 链路

   - `SocksProxyServer`
   - `SocksContext`
   - `Socks5*Handler`
   - TCP relay handlers
   - UDP relay handlers
   - upstream pool
   - UDP2raw / redundant / compress / port hopping

# 目标

1. 明确 `org.rx.net` 包在排除 FEC 与 HTTP Tunnel 后的代码边界。
2. 梳理连接生命周期、背压、UDP、HTTP、DNS、RPC、SOCKS、transport 的关键调用链。
3. 识别潜在稳定性、性能、并发、资源释放和测试覆盖风险。
4. 给出后续最小修复顺序与验证方案。
5. 执行已确认的最小修复，并持续更新进度与验证结论。

# 非目标

1. 不扩大到 FEC 与 HTTP Tunnel 相关代码。
2. 不 review / 修改 FEC 相关文件。
3. 不 review / 修改 HTTP Tunnel 相关文件。
4. 不升级依赖。
5. 不引入新框架或重型依赖。
6. 不改变公开 API，除非后续修复必须做兼容扩展。
7. 不发布 release。
8. 不修改 secrets、token、证书、私钥。

# 设计方案

## Review 初步结论与修复优先级

### P0：背压事件传播与 autoRead 目标通道确认

状态：**已修复，已补测试，已验证通过**。

候选问题：

- `BackpressureHandler.channelWritabilityChanged(...)` 在已有 timer 或 cooldown schedule 分支中直接 `return`，只有立即处理分支调用 `super.channelWritabilityChanged(ctx)`。
- 这可能导致 pipeline 后续 handler 收不到 writability change 事件。
- `channelActive(...)` 当前对 `ctx.channel()` 调用 `Sockets.enableAutoRead(...)`；该 handler 安装在 outbound pipeline 时，`ctx.channel()` 是 outbound channel，需要确认是否本意应恢复 captured inbound channel。

后续修复方向：

- 增加测试覆盖 timer 已存在、cooldown 分支、立即分支的事件传播行为。
- 确认 `channelActive` 应操作 outbound 还是 captured inbound。
- 若确认是风险，最小修复为确保事件继续传播，并修正 autoRead 目标通道。

实际修复：

- `BackpressureHandler.channelWritabilityChanged(...)` 改为 `try/finally` 传播 `super.channelWritabilityChanged(ctx)`，确保 timer 已存在、cooldown 延迟、立即处理三个分支都不会截断 pipeline 后续 handler。
- `BackpressureHandler.channelActive(...)` 改为恢复 captured `inbound` 的 `autoRead`，避免 handler 安装在 outbound pipeline 时误操作 outbound channel。
- 新增/扩展 `BackpressureHandlerTest` 覆盖事件传播和 inbound `autoRead` 恢复。

### P1：HTTP 流式上传阻塞 EventLoop 风险

状态：**已加防护，已补测试，已验证通过**。

候选问题：

- `HttpClient.StreamContent` 读取 `InputStream` / stream。
- `UploadWriter.flush()` 和 `finish()` 中存在等待写完成的同步逻辑。
- 需要确认该路径绝不在 Netty EventLoop 中执行；否则可能导致 EventLoop 阻塞或写完成等待互相卡住。

后续修复方向：

- 增加 EventLoop guard，或将阻塞 upload writer 转移到业务线程池。
- 增加大 body、慢 channel、write timeout 回归测试。
- 保持现有 API，不引入新依赖。

实际修复：

- 现有 `writeRequest(...)` 已在请求头写成功后通过 `Tasks.run(...)` 执行 `content.writeStreaming(...)`，流式读取不在 Netty EventLoop 上执行。
- `UploadWriter` 构造阶段新增 EventLoop guard，若未来调用路径误把流式上传放回 EventLoop，会立即失败而不是阻塞 I/O 线程。
- 新增 `HttpClientTest.testUploadWriterRejectsEventLoopThread`。

### P1：Sockets endpoint / proxy 解析边界

状态：**已修复，已补测试，已验证通过**。

候选问题：

- `Sockets.parseEndpoint(...)` 使用最后一个 `:` 分割 host 和 port。
- `Sockets.newUnresolvedEndpoint(...)` 对域名返回 unresolved 地址以避免本机 DNS。
- `Sockets.setHttpProxy(...)` 可能对 unresolved endpoint 调用 `getAddress().getHostAddress()`；域名场景下 `getAddress()` 可能为 `null`。
- IPv6 bracket host:port 格式也需要明确支持策略。

后续修复方向：

- `setHttpProxy` 对域名使用 `getHostString()`，避免 unresolved endpoint 空指针。
- 为域名代理、IPv4、IPv6 literal / bracket 格式补测试。
- 不改变避免本机 DNS 的既有设计。

实际修复：

- `Sockets.setHttpProxy(...)` 改为使用 `InetSocketAddress.getHostString()`，域名代理保持 unresolved，不触发本机 DNS，也不会因 `getAddress()==null` NPE。
- `Sockets.parseEndpoint(...)` 支持 `[IPv6]:port` bracket 格式，并校验空 host、缺失 port、非法 port 与无端口 IPv6。
- 新增/扩展 `SocketsTest` 覆盖域名代理、IPv4、IPv6 bracket 与 IPv6 literal。

### P1：UdpClient close 在 EventLoop 中同步等待风险

状态：**已修复，已补测试，已验证通过**。

候选问题：

- `UdpClient.close()` 遍历 channel 并调用 `ch.close().syncUninterruptibly()`。
- 如果 close 从对应 EventLoop 线程调用，可能阻塞 EventLoop 或造成死锁/延迟风险。

后续修复方向：

- 增加 EventLoop 内部调用 close 的测试。
- EventLoop 内直接异步 `ch.close()`，非 EventLoop 可保留同步等待或统一非阻塞关闭。
- 保持 pending sends / requests / receives 清理逻辑不变。

实际修复：

- `UdpClient.close()` 保持 pending send/request/receive 清理逻辑不变。
- 当调用线程属于任一 UDP channel 的 EventLoop 时，只发起异步 `ch.close()`，不再 `syncUninterruptibly()` 阻塞 I/O 线程。
- 非 EventLoop 调用仍保持原同步关闭语义。
- 新增 `UdpTransportTest.closeFromEventLoopDoesNotBlockEventLoop`。

### P2：UDP FULL ack 与 async receive 悬挂风险

状态：**未修改，保留后续评估**。

候选问题：

- `UdpClient` 对 FULL ack 消息在 `publishEventAsync(onReceive, ...)` 完成后才 `sendAck(...)`。
- 如果业务 onReceive future 长时间不完成，`inflightReceives` 可能长期保留，ack 也长期不发送。
- 这可能放大发送端重试、窗口压力和内存占用。

后续修复方向：

- 先确认事件系统是否已有超时保护。
- 如无保护，考虑为 FULL ack receive 增加超时清理。
- 是否提前 ack 需要谨慎，因为可能改变可靠投递语义。

### P2：Sockets.writeUdp 失败路径释放语义验证

状态：**未修改，已有相关测试通过，保留后续 leak-aware 强化**。

候选问题：

- `Sockets.writeUdp(...)` 对 inactive / unresolved / pending overlimit / notWritable / write throw 有显式 release。
- `writeAndFlush` 后的失败 listener 记录 metric 并减少 pending bytes。
- 需要用 leak-aware 测试确认 Netty outbound failure 是否已经释放 `DatagramPacket`，避免误判或双释放。

后续修复方向：

- 增加 EmbeddedChannel / failed write promise 的 leak-sensitive 测试。
- 如果确认需要显式释放，在 listener failure 分支谨慎处理引用计数。
- 避免破坏 Netty outbound ownership 语义。

### P2：SOCKS / UDP relay / upstream 边界测试补齐

状态：**未修改，保留后续按实际改动补齐**。

候选问题：

- SOCKS、UDP relay、UDP2raw、redundant、compress、port hopping 已有较多测试。
- 仍建议补充跨 handler 的关闭、异常、pending queue 清理、channel attr 清理、metric tag 一致性场景。

后续修复方向：

- 优先只为实际修改文件添加测试。
- 避免一次性大重构。

## 执行顺序与当前状态

1. 已完成：先补回归测试，复现或锁定 P0/P1 行为。
2. 已完成：修复 `BackpressureHandler` 的事件传播 / autoRead 目标通道问题。
3. 已完成：修复 `Sockets.setHttpProxy` 域名和 IPv6 endpoint 边界。
4. 已完成：验证并修复 `UdpClient.close()` EventLoop 同步等待风险。
5. 已完成：验证 HTTP 流式上传线程模型，并添加 EventLoop guard。
6. 待评估：UDP FULL ack 悬挂风险和 `writeUdp` 失败释放语义。
7. 待后续：每个剩余修复保持独立、小步提交，便于 CI 定位。

# 修改文件列表

本次修复实际修改：

- `docs/plan/review-rx-net-excluding-fec-httptunnel-20260505.md`
- `rxlib/src/main/java/org/rx/net/BackpressureHandler.java`
- `rxlib/src/main/java/org/rx/net/Sockets.java`
- `rxlib/src/main/java/org/rx/net/http/HttpClient.java`
- `rxlib/src/main/java/org/rx/net/transport/UdpClient.java`
- `rxlib/src/test/java/org/rx/net/BackpressureHandlerTest.java`
- `rxlib/src/test/java/org/rx/net/SocketsTest.java`
- `rxlib/src/test/java/org/rx/net/http/HttpClientTest.java`
- `rxlib/src/test/java/org/rx/net/transport/UdpTransportTest.java`

# 风险点

1. 兼容性风险

   - `BackpressureHandler` 行为可能已有调用方依赖“消费 writability event”的现状。
   - `setHttpProxy` 改为 `getHostString()` 后，对 IP 与域名均应保持兼容。
   - UDP FULL ack 语义如果调整，可能影响可靠投递语义。

2. 性能风险

   - 背压 cooldown 逻辑不能改成高频抖动。
   - HTTP upload 修复不能引入额外大对象分配或过多线程切换。
   - UDP write 保护不能降低高吞吐场景性能。

3. 并发风险

   - `UdpClient.close()` 与 pending send / receive / request map 清理存在并发交错。
   - `BackpressureHandler.timer` CAS 与 delayed task 需要保持线程安全。
   - RPC / Hybrid / SOCKS 的 channel lifecycle 不能引入竞态。

4. 资源释放风险

   - ByteBuf / DatagramPacket 引用计数需要测试验证。
   - HTTP request body、response body、streaming upload buffer 需要确保异常路径释放。
   - UDP pending receive assembly 需要确保 expire / duplicate / close 均释放。

5. 测试风险

   - 网络集成测试可能受端口、DNS、平台网络栈、GitHub Actions 环境影响。
   - 某些 SOCKS / UDP relay 测试较重，修复阶段应优先运行相关小测试，再跑组合测试。

# 验证方案

## 当前修复验证

本地已执行：

```bash
mvn -pl rxlib "-Dgpg.skip=true" "-Dtest=BackpressureHandlerTest,SocketsTest,UdpTransportTest,HttpClientTest" test
```

结果：

- Tests run: 63
- Failures: 0
- Errors: 0
- Skipped: 0
- BUILD SUCCESS

## 后续实现阶段

每次代码 commit 后必须触发 GitHub Actions：

- workflow：`.github/workflows/jdk8-unit-tests.yml`
- workflow 名称：`JDK 8 Unit Tests`
- 触发方式：`workflow_dispatch`
- 分支：后续实现分支
- `test_classes` 按修改范围选择。

建议测试集：

1. 背压 / Sockets：`BackpressureHandlerTest`、`SocketsTest`、`SocketsReusePortTest`
2. HTTP：`HttpClientTest`、`HttpClientIntegrationTest`、`HttpServerBlockingTest`
3. UDP transport：`UdpTransportTest`、`FuryUdpClientCodecTest`、`HybridTransportTcpOnlyIntegrationTest`、`HybridTransportUdpDirectIntegrationTest`
4. RPC：`RemotingTest`、`RemotingUdpEventRouteTest`、`FuryRemotingCodecTest`
5. SOCKS / UDP relay：`SocksUdpRelayHandlerTest`、`SocksProxyServerTest`、`Socks5ClientTest`、`Udp2rawHandlerTest`、`UdpCompressTest`、`UdpRedundantTest`
6. DNS：`DnsOptimizationTest`、`DnsServerIntegrationTest`、`DoHMessageCodecTest`

如果修改影响面较大，应运行全量 JDK8 测试：

```bash
mvn -B -U -Dgpg.skip=true -Dmaven.test.skip=false -DskipTests=false clean test
```

CI 只有在 workflow run `conclusion=success` 后才视为通过。
