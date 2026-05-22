# 背景

用户要求 review `rockylomo/rxlib` 仓库 `agent/global-net-flow-control-20260521` 分支上的计划文档和当前提交。

本次已定位到相关计划文档：

- `docs/plan/global-net-flow-control-and-backpressure-plan.md`
- 计划提交：`3fa3a6b137b827ef1192e5256b1804e363276f49`
- 当前实现提交：`1a6d073522db7052d55b669f39ae09e6d930b973`

当前实现提交信息为：`feat: implement global network traffic control and TCP/UDP backpressure management`，统计为新增 665 行、删除 37 行，涉及 RxConfig、Sockets、全局流控类、TCP/UDP backpressure 策略和测试。

# 任务类型判断

本次任务归类为 Review / 修复 / 优化需求。

原因：

- 用户明确要求“review 下计划文档和当前提交”，不是直接要求实现新功能。
- 当前分支已经存在计划文档和实现提交，需要检查计划与代码是否一致、当前实现是否有风险、测试和 CI 是否完整。
- 按流程只提交本次 review 计划文档，不修改业务代码。

# 当前上下文

已 review 的文件和提交：

- `docs/plan/global-net-flow-control-and-backpressure-plan.md`
- `rxlib/src/main/java/org/rx/core/RxConfig.java`
- `rxlib/src/main/java/org/rx/net/NetworkFlowControl.java`
- `rxlib/src/main/java/org/rx/net/NetworkTrafficConfig.java`
- `rxlib/src/main/java/org/rx/net/TcpBackpressureManager.java`
- `rxlib/src/main/java/org/rx/net/UdpBackpressureDecision.java`
- `rxlib/src/main/java/org/rx/net/UdpBackpressurePolicy.java`
- `rxlib/src/main/java/org/rx/net/Sockets.java`
- `rxlib/src/main/java/org/rx/net/socks/Socks5CommandRequestHandler.java`
- `rxlib/src/test/java/org/rx/core/RxConfigTest.java`
- `rxlib/src/test/java/org/rx/net/NetworkFlowControlTest.java`
- `.github/workflows/jdk8-unit-tests.yml`

关键调用链：

- `RxConfig.afterSet()` / `refreshFromSystemProperty()` 初始化并刷新 `net.globalTraffic`。
- `NetworkFlowControl.refresh()` 获取配置快照并刷新 Netty `GlobalTrafficShapingHandler` 限速参数。
- `Sockets` channel 初始化时调用 `NetworkFlowControl.DEFAULT.install(ch)`，向 pipeline 注入全局 traffic shaping handler。
- `TcpBackpressureManager.DEFAULT.install()` 封装 `BackpressureHandler.install(...)`，当前只在 socks TCP relay 的 `ensureFrontendHandlers()` 中接入。
- UDP 写入路径通过 `UdpBackpressurePolicy.reserve(...)` 统一 pending bytes / pending packets / channel writable 判定，并通过 `UdpBackpressureDecision` 输出结果。

已发现的问题或风险：

1. 计划文档的影响面描述明显大于当前实现提交。计划中提到 `transport`、`remoting`、多个 socks UDP handler、`BackpressureHandlerTest`、`UdpBackpressurePolicyTest` 等；当前提交实际只改了 `Sockets`、一个 socks TCP 接入点、新增策略类和两个测试文件。
2. 当前分支未发现 GitHub Actions workflow run，`JDK 8 Unit Tests` 尚未在该分支上验证当前实现。
3. `app.net.globalTraffic.enabled` 只控制 traffic shaping 是否安装，不是 TCP / UDP backpressure 的总开关；而配置名容易被理解为“全局流控总开关”。需要文档明确语义，或者改成显式 master enable。
4. `TcpBackpressureManager` 当前只替换了 socks TCP relay 的安装入口，尚未覆盖计划中提到的 broader `transport` / `remoting` bootstrap 继承。
5. `NetworkFlowControl.install()` 的真实 TCP / UDP bootstrap 接入只通过 `EmbeddedChannel` 做了部分验证，尚缺真实 server/client/datagram channel 的集成验证。
6. UDP backpressure 测试覆盖了 pending bytes 和 packet count 的 drop，但尚缺 `udpBackpressureEnabled=false`、MTU 与 pending 限制组合、final egress guard、write failure / thrown path 的回归测试。
7. 计划文档推荐的测试类与实现提交新增测试不完全一致，需要同步更新计划或补齐测试类。
8. `UdpBackpressurePolicy.shouldInstallFinalGuard(...)` 的布尔表达式依赖 `&&` 高于 `||` 的优先级，行为大致符合当前意图，但建议加括号降低误读风险。

# 目标

本次 review 目标：

1. 检查计划文档是否先于代码提交。
2. 检查当前实现提交是否覆盖计划中的核心目标。
3. 检查当前提交的主要调用链、边界条件、资源释放和测试覆盖风险。
4. 提交本次 review 计划文档到 `docs/plan/*`。
5. 不修改业务代码。

# 非目标

本次不做以下事项：

1. 不修改 `rxlib/src/main/java/**` 业务代码。
2. 不删除或重写现有计划文档。
3. 不调整 GitHub Actions workflow 配置。
4. 不发布 release，不修改 secrets / token / 证书 / 私钥。
5. 不伪造 CI 结果；只有 workflow conclusion 为 `success` 才能认为 CI 通过。

# 设计方案

本次 review 的处理方案：

1. 以分支 HEAD 为准，先确认计划提交和实现提交顺序。
2. 对比计划文档列出的影响面与当前提交文件列表。
3. 按调用链拆分 review：
   - 配置入口：`RxConfig.NetConfig.globalTraffic`
   - 全局限速：`NetworkFlowControl` + Netty traffic shaping handler
   - TCP 背压：`TcpBackpressureManager` + `BackpressureHandler`
   - UDP 背压：`UdpBackpressurePolicy` + `Sockets.writeUdp(...)` / final egress guard
   - 测试与 CI：`RxConfigTest`、`NetworkFlowControlTest`、`jdk8-unit-tests.yml`
4. 输出 review 结论和后续修复建议。
5. 后续若用户要求“按计划执行”或“开始修改代码”，再进入代码修复阶段；修复应优先聚焦：
   - CI 验证失败项；
   - 配置语义和计划不一致；
   - 测试缺口；
   - 实际接入范围不符合计划的问题。

异常处理和资源释放关注点：

- `GlobalTrafficShapingHandler` 是长生命周期共享实例，需确认 handler lifecycle 与 executor 释放策略。
- UDP drop / thrown path 必须确保 `DatagramPacket` / `ByteBuf` 不泄漏、不重复 release。
- pending bytes / pending packets 计数必须在成功、失败、异常和拒绝路径保持平衡。
- TCP backpressure 的 autoRead 暂停/恢复必须覆盖 outbound inactive / exception 场景，避免 inbound 永久停读。

# 修改文件列表

本次预计只新增 review 计划文档：

- `docs/plan/review-global-net-flow-control-current-commit-20260522.md`

当前实现提交已涉及但本次不修改的文件：

- `rxlib/src/main/java/org/rx/core/RxConfig.java`
- `rxlib/src/main/java/org/rx/net/NetworkFlowControl.java`
- `rxlib/src/main/java/org/rx/net/NetworkTrafficConfig.java`
- `rxlib/src/main/java/org/rx/net/TcpBackpressureManager.java`
- `rxlib/src/main/java/org/rx/net/UdpBackpressureDecision.java`
- `rxlib/src/main/java/org/rx/net/UdpBackpressurePolicy.java`
- `rxlib/src/main/java/org/rx/net/Sockets.java`
- `rxlib/src/main/java/org/rx/net/socks/Socks5CommandRequestHandler.java`
- `rxlib/src/test/java/org/rx/core/RxConfigTest.java`
- `rxlib/src/test/java/org/rx/net/NetworkFlowControlTest.java`

# 风险点

1. 兼容性风险：新增全局配置项会影响 `RxConfig` 序列化 / 反序列化和 system property 热刷新语义。
2. 性能风险：共享全局 traffic shaping handler 会把不同协议和 channel 的流控聚合，需要确认是否符合预期的全局限速语义。
3. 并发风险：UDP pending bytes / packets 使用 channel attribute 中的 `AtomicInteger`，需要覆盖并发 write 和异常路径。
4. 资源释放风险：UDP drop 和 write thrown path 需要确保 ByteBuf 所有权清晰。
5. 行为风险：`globalTraffic.enabled=false` 与 `tcpBackpressureEnabled` / `udpBackpressureEnabled` 的组合语义不够直观。
6. 测试风险：当前测试偏单元级，缺少真实 Netty bootstrap / socks relay / remoting 集成验证。
7. CI 风险：当前分支未看到 Actions run，尚不能证明 JDK8 编译和测试通过。

# 验证方案

后续建议验证：

1. 手动触发 `JDK 8 Unit Tests` workflow。
2. 推荐 `test_classes`：
   ```text
   NetworkFlowControlTest,RxConfigTest,BackpressureHandlerTest,RemotingTest,SocksProxyServerIntegrationTest,Socks5ClientIntegrationTest,ShadowsocksServerIntegrationTest
   ```
3. 如果新增或调整测试类，应同步更新 `test_classes`。
4. 查询 workflow run 时必须按当前分支过滤。
5. 只有 `status=completed` 且 `conclusion=success` 才能认为 CI 通过。
6. 若 CI 失败，按以下顺序分类处理：
   - 编译失败；
   - 单元测试失败；
   - Checkstyle / formatting 失败；
   - 依赖下载失败；
   - JDK 版本不兼容；
   - 环境问题；
   - 测试不稳定；
   - GitHub Actions 配置问题。
