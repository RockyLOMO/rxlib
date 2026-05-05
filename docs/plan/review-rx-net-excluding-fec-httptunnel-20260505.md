# org.rx.net 包 Review 计划（排除 FEC 与 HTTP Tunnel）

# 背景

用户要求对 `RockyLOMO/rxlib` 仓库中 `rxlib` 模块的 `org.rx.net` 包进行仔细 review，并明确排除 FEC 与 HTTP Tunnel 相关代码。

本轮范围：

- 主代码：`rxlib/src/main/java/org/rx/net/**`
- 排除：`rxlib/src/main/java/org/rx/net/Fec*.java`、`rxlib/src/main/java/org/rx/net/socks/httptunnel/**`
- 关联测试：`rxlib/src/test/java/org/rx/net/**`
- 测试排除：`FecCodecTest`、`socks/httptunnel/**`

本计划文档记录了 review 结论、风险点、已完成的修复以及验证方案。

# 任务类型判断

本次任务归类为 **Review / 修复 / 优化需求**。

原因：用户要求“仔细慢慢地 review 下”现有实现；目标是检查风险点、调用链和边界条件。目前已进入修复执行阶段，针对识别出的 P0/P1 问题进行最小化修复并补充回归测试。

# 当前上下文

## 仓库与模块

- 仓库：`RockyLOMO/rxlib`
- 分支：`master`
- 模块：`rxlib`
- 技术栈：Java 8、Netty、Maven
- 风格约束：优先最小改动；保持 API 兼容；关注引用计数、EventLoop 阻塞和连接生命周期。

## 已 review / 扫描的文件

已扫描 `org.rx.net` 及其下属子包（dns, http, nameserver, ntp, punch, rpc, socks, support, transport），并明确排除了 FEC 与 HTTP Tunnel 相关实现。

## 关键调用链

1. **Netty 生命周期与全局 Handler**：`Sockets` 引导类、`GlobalChannelHandler`、各业务初始化器。
2. **背压链路**：`BackpressureHandler` 安装在 outbound，通过控制 inbound 的 `autoRead` 或业务回调来实现流量控制。
3. **UDP 传输**：`UdpClient` 的分片、重试、ack 机制及 `Sockets.writeUdp` 的资源管理。
4. **HTTP 客户端/服务端**：`HttpClient` 流式上传、连接池管理、Response 生命周期。
5. **DNS / DoH**：`DnsResolveCore` 的多级降级（hosts -> cache -> upstream）与协议解析。
6. **SOCKS / Shadowsocks / RRP / UDP Relay**：各种代理协议的编解码、路由、session 管理及 UDP2raw/冗余/压缩等复杂扩展。
7. **RPC / Hybrid**：`Remoting` 的异步调用、Hybrid 模式的协议切换与状态同步。

# 目标

1. 识别并修复 `org.rx.net` 包中的关键稳定性、并发与资源泄漏风险。
2. 确保 Netty Pipeline Handler 顺序与事件传播逻辑的准确性。
3. 增强 HTTP 流式上传与 UDP 关闭路径的 EventLoop 亲和性，避免同步阻塞。
4. 补充针对边缘用例（如域名代理、IPv6 格式、连接异常中断）的回归测试。

# 非目标

1. 不涉及 FEC 与 HTTP Tunnel 相关代码。
2. 不进行大规模重构或升级第三方依赖。
3. 不改变既有的公开 API 语义，除非确属 Bug。

# 修复方案结论（已完成）

### 1. BackpressureHandler 事件传播与恢复逻辑 (P0/P1)
- **事件传播**：修正了 `channelWritabilityChanged` 的截断风险。现在无论在立即处理、timer 冷却还是延迟任务分支，均会通过 `finally` 确保 `super.channelWritabilityChanged(ctx)` 继续向下传播。
- **autoRead 恢复目标**：修正了 `channelActive` 恢复目标。现在会准确恢复被捕获的 `inbound` 通道的 autoRead，而不是误操作 handler 所在的 outbound 通道。
- **关闭路径恢复**：在 `channelInactive` 路径中新增了对恢复回调的显式调用。确保连接意外关闭时，外部关联的业务状态（如队列暂停标识）能够被正确重置。

### 2. Sockets 稳定性与正确性 (P1)
- **Endpoint 解析**：支持 `[IPv6]:port` 带中括号的解析格式，并增强了对非法端口和空主机的校验。
- **HTTP 代理**：修正了 `setHttpProxy` 处理域名上游时的 NPE 风险。现在使用 `getHostString()` 确保在地址尚未解析（unresolved）时也能正常获取 host 信息。
- **Pipeline 顺序**：修正了 `addAfter` 的插入语义。解决了原先在 reverse 循环中错误调用 `addBefore` 导致 Handler 顺序与预期不符的问题。

### 3. HTTP 流式上传线程模型 (P1)
- **EventLoop 保护**：在 `UploadWriter` 构造处增加了 EventLoop 线程检查。
- **安全性**：确保流式读取 `InputStream` 的阻塞操作只在业务线程（`Tasks.run`）中执行，若意外在 I/O 线程调用将立即报错，防止整个 EventLoop 挂起。

### 4. UDP 关闭路径优化 (P1)
- **非阻塞关闭**：修正了 `UdpClient.close()`。当从 EventLoop 内部调用时，改用异步关闭，避免 `syncUninterruptibly()` 造成的同步等待。

# 暂缓/后续关注项

- **UDP FULL ack 悬挂风险**：如果业务处理 Future 长期不完成，可能导致 inflight 缓存不释放。
- **writeUdp 失败释放语义**：Netty 在 write promise 失败后的 DatagramPacket 自动释放行为需进一步通过 leak detector 确认。
- **DNS Cache Fallback**：多级缓存异常时的自动清理与强制降级逻辑。

# 修改文件列表

- `rxlib/src/main/java/org/rx/net/BackpressureHandler.java`
- `rxlib/src/main/java/org/rx/net/Sockets.java`
- `rxlib/src/main/java/org/rx/net/http/HttpClient.java`
- `rxlib/src/main/java/org/rx/net/transport/UdpClient.java`
- `rxlib/src/test/java/org/rx/net/BackpressureHandlerTest.java`
- `rxlib/src/test/java/org/rx/net/SocketsTest.java`
- `rxlib/src/test/java/org/rx/net/http/HttpClientTest.java`
- `rxlib/src/test/java/org/rx/net/transport/UdpTransportTest.java`

# 验证结论

本地已执行回归测试集：
```bash
mvn -pl rxlib "-Dtest=BackpressureHandlerTest,SocketsTest,UdpTransportTest,HttpClientTest" test
```
**结果**：Tests run: 63, Failures: 0, Errors: 0, BUILD SUCCESS。覆盖了事件传播顺序、IPv6 解析、代理域名兼容、EventLoop 非阻塞关闭等核心风险点。
