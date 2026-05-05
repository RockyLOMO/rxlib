# 背景

用户要求在 `RockyLOMO/rxlib` 仓库 `master` 分支上 review `SocksProxyServer` 相关类。本次任务属于 Review 类任务：先分析现有实现、调用链、边界条件、风险点和验证方式，并提交计划文档；在用户明确要求“按计划执行/开始修改代码”前，不修改业务代码。

# 任务类型判断

本次归类为 Review / 修复 / 重构 / 优化需求，原因是用户明确要求 review 已有 `SocksProxyServer` 相关类，而不是新增功能。当前阶段只进行代码阅读、风险识别和计划提交。

# 当前上下文

已 review 的核心文件：

- `rxlib/src/main/java/org/rx/net/socks/SocksProxyServer.java`
- `rxlib/src/main/java/org/rx/net/socks/SocksConfig.java`
- `rxlib/src/main/java/org/rx/net/socks/SocksContext.java`
- `rxlib/src/main/java/org/rx/net/socks/Socks5InitialRequestHandler.java`
- `rxlib/src/main/java/org/rx/net/socks/Socks5PasswordAuthRequestHandler.java`
- `rxlib/src/main/java/org/rx/net/socks/Socks5CommandRequestHandler.java`
- `rxlib/src/main/java/org/rx/net/socks/SocksTcpFrontendRelayHandler.java`
- `rxlib/src/main/java/org/rx/net/socks/SocksTcpBackendRelayHandler.java`
- `rxlib/src/main/java/org/rx/net/socks/ProxyChannelIdleHandler.java`
- `rxlib/src/main/java/org/rx/net/socks/ProxyManageHandler.java`
- `rxlib/src/main/java/org/rx/net/socks/SocksConnectionTagRegistry.java`
- `rxlib/src/main/java/org/rx/net/socks/SocksUserTraffic.java`
- `rxlib/src/main/java/org/rx/net/socks/SocksUdpRelayHandler.java`
- `rxlib/src/main/java/org/rx/net/socks/UdpRelayGroupManager.java`
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawServerEntryManager.java`
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawServerEntryHandler.java`
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawHandler.java`
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawPayloadSupport.java`

关键调用链：

1. `SocksProxyServer` 构造阶段根据 `SocksConfig` 绑定 TCP 或 Local memory server，初始化 `UdpRelayGroupManager`，并在启用固定入口 udp2raw 且未配置 udp2raw client 时初始化 `Udp2rawServerEntryManager`。
2. `acceptChannel` 为入站 channel 挂载 `ProxyManageHandler`、`ProxyChannelIdleHandler`、SOCKS5 encoder/decoder、初始请求 handler、认证 handler 和命令请求 handler，并维护 `activeChannels`。
3. `Socks5InitialRequestHandler` 根据 `SocksProxyServer.isAuthEnabled()` 选择 `NO_AUTH` 或 `PASSWORD` 认证方式。
4. `Socks5PasswordAuthRequestHandler` 调用 `Authenticator`，将认证结果或连接标签写入 `SocksContext` / `SocksConnectionTagRegistry`。
5. `Socks5CommandRequestHandler` 负责 CONNECT / UDP_ASSOCIATE：构造 `SocksContext`、执行路由、连接上游或建立 UDP relay。
6. `SocksProxyServer` 暴露 RPC 管理能力：reset/claim UDP relay、UDP relay group open/add/remove/heartbeat/close、udp2raw tunnel open/heartbeat/close、capabilities 查询。
7. `dispose` 关闭 udp2raw entry、UDP relay group、UDP relay registry 和由 server 自身创建的 TCP bind channel。

已发现的问题或风险候选：

- `withUdpRelay` 使用 `relay.eventLoop().submit(task).get()` 同步等待 relay event loop，RPC 调用线程可能被 relay event loop 阻塞放大。
- `registerUdpRelay` 以本地 UDP 端口作为 registry key，`put` 无冲突检查；多 bind 地址、端口复用或异常重绑场景可能覆盖旧 relay。
- `acceptChannel` 使用 `SOCKS_SVR` attr 避免重复初始化；外部传入 memory channel 时，需要确认不会跳过必要 handler 或导致 handler 顺序异常。
- `activeChannels` 依赖 `acceptChannel` increment 和 closeFuture decrement；外部传入 channel、重复 close 或复用场景需要额外测试。
- `isTrafficBindingEnabled()` 在 authenticator 或 connectionTagResolver 任一存在时挂载 `ProxyManageHandler`；需确认 TCP、UDP、udp2raw 三条路径的 attach / detach 一致性，避免漏计或重复计。
- RPC token 校验集中在带 token 的 public overload 中，但无 token overload 仍为 public；如果 RPC 框架直接暴露对象方法，需要确认 contract 层不会绕过 token 校验。
- `clearUdpRelayState` 在 relay event loop 上清理 udp2raw ctx/route map 或普通 UDP relay state；需验证 datagram 处理中的并发语义。
- `dispose` 遍历 `udpRelayRegistry.values()` close channel，close listener 同时会修改 registry；当前 `ConcurrentHashMap` 弱一致迭代可接受，但后续不要替换为非并发集合。

# 目标

- 完成 `SocksProxyServer` 相关类 review 的阶段性计划文档。
- 明确后续可验证、可最小化修复的风险点。
- 为后续代码阶段准备测试与 CI 策略。
- 当前阶段只新增 `docs/plan/*` 文档，不修改业务代码。

# 非目标

- 当前阶段不修改 `rxlib/src/main/java` 业务代码。
- 当前阶段不修改测试代码。
- 不升级依赖，不调整 Maven 或 GitHub Actions 配置。
- 不修改 secrets、token、证书、私钥，不发布 release。
- 不做 socks 包大规模重构。

# 设计方案

后续若用户明确要求执行代码修改，建议按最小改动原则推进：

1. 优先验证并修复高风险点：UDP relay registry 端口冲突、traffic binding 一致性、memory channel / activeChannels 计数边界、RPC token 暴露面。
2. 保持 API 兼容，不修改 `SocksProxyServer` 公开构造器、delegate 字段和 RPC 方法签名。
3. 涉及 Netty channel attr、pipeline、relay state 的修改继续在对应 event loop 上执行。
4. 如增加 UDP relay 冲突检查，失败路径必须关闭新建 relay，避免 channel 泄露。
5. 如调整 `withUdpRelay`，需保留调用方可判断成功/失败的同步语义，或同步修改 RPC contract 与测试。
6. 如补充 traffic binding 修复，需覆盖认证用户、connectionTagResolver、UDP relay、udp2raw fixed entry 等路径。

# 修改文件列表

当前计划阶段新增：

- `docs/plan/review-socks-proxy-server.md`

后续代码阶段可能涉及：

- `rxlib/src/main/java/org/rx/net/socks/SocksProxyServer.java`
- `rxlib/src/main/java/org/rx/net/socks/Socks5CommandRequestHandler.java`
- `rxlib/src/main/java/org/rx/net/socks/ProxyManageHandler.java`
- `rxlib/src/main/java/org/rx/net/socks/SocksConnectionTagRegistry.java`
- `rxlib/src/main/java/org/rx/net/socks/SocksUserTraffic.java`
- `rxlib/src/main/java/org/rx/net/socks/UdpRelayGroupManager.java`
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawServerEntryManager.java`
- `rxlib/src/test/java/org/rx/net/socks/SocksProxyServerTest.java`
- `rxlib/src/test/java/org/rx/net/socks/SocksProxyServerIntegrationTest.java`
- `rxlib/src/test/java/org/rx/net/socks/Socks5CommandRequestHandlerTest.java`
- `rxlib/src/test/java/org/rx/net/socks/SocksUdpRelayHandlerTest.java`
- `rxlib/src/test/java/org/rx/net/socks/Udp2rawFixedEntryIntegrationTest.java`

# 风险点

- 兼容性风险：`SocksProxyServer` 是 public 类，外部可能直接使用构造器、delegate 和 RPC 方法。
- 性能风险：TCP/UDP relay 是高频路径，不能在热路径引入重锁或阻塞。
- 并发风险：Netty event loop、`ConcurrentHashMap`、channel attr、closeFuture listener 和 RPC 线程之间存在跨线程访问。
- 资源释放风险：UDP relay、udp2raw entry、memory channel 和外部传入 channel 的关闭责任不同，需避免重复关闭或漏关。
- 测试风险：integration test 可能依赖本机网络、随机端口或时序，CI 中可能 flaky。
- 安全风险：RPC token 校验与无 token overload 的暴露边界需要结合 RPC contract 确认。

# 验证方案

后续代码阶段完成后：

1. 触发 `.github/workflows/jdk8-unit-tests.yml`，使用 `workflow_dispatch`。
2. `test_classes` 优先带：
   - `org.rx.net.socks.SocksProxyServerTest`
   - `org.rx.net.socks.SocksProxyServerIntegrationTest`
   - `org.rx.net.socks.Socks5CommandRequestHandlerTest`
   - `org.rx.net.socks.SocksUdpRelayHandlerTest`
   - `org.rx.net.socks.Udp2rawFixedEntryIntegrationTest`
3. 如果修改 udp2raw / redundant / traffic accounting，补充：
   - `org.rx.net.socks.Udp2rawHandlerTest`
   - `org.rx.net.socks.UdpRedundantTest`
4. 查询 GitHub Actions run 时按当前分支过滤。
5. 只有 workflow run `conclusion=success` 才认为 CI 通过。
6. 如果 CI 失败，先分类为编译失败、单测失败、格式失败、依赖下载失败、JDK8 不兼容、环境问题、flaky 或 Actions 配置问题，再只修复与失败直接相关的代码。
