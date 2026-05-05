# 背景

用户要求在 `RockyLOMO/rxlib` 仓库 `master` 分支上 review 并处理 `SocksProxyServer` 相关类。计划文档已先行提交，本轮已按“执行计划，先忽略 CI”的要求进入代码阶段，并持续更新本文档进度。

# 任务类型判断

本次归类为 Review / 修复 / 重构 / 优化需求。原因是任务对象是已有 `SocksProxyServer` 及 socks 相关实现，不是新增功能；处理方式以最小改动修复 review 中识别出的风险点为主。

# 当前上下文

## 已 review 的核心文件

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
- `rxlib/src/main/java/org/rx/net/socks/SocksRpcContract.java`

## 关键调用链

1. `SocksProxyServer` 构造阶段根据 `SocksConfig` 绑定 TCP 或 Local memory server，初始化 `UdpRelayGroupManager`，并在启用固定入口 udp2raw 且未配置 udp2raw client 时初始化 `Udp2rawServerEntryManager`。
2. `acceptChannel` 为入站 channel 挂载 `ProxyManageHandler`、`ProxyChannelIdleHandler`、SOCKS5 encoder/decoder、初始请求 handler、认证 handler 和命令请求 handler，并维护 `activeChannels`。
3. `Socks5InitialRequestHandler` 根据 `SocksProxyServer.isAuthEnabled()` 选择 `NO_AUTH` 或 `PASSWORD` 认证方式。
4. `Socks5PasswordAuthRequestHandler` 调用 `Authenticator`，将认证结果或连接标签写入 `SocksContext` / `SocksConnectionTagRegistry`。
5. `Socks5CommandRequestHandler` 负责 CONNECT / UDP_ASSOCIATE：构造 `SocksContext`、执行路由、连接上游或建立 UDP relay。
6. `SocksProxyServer` 暴露 RPC 管理能力：reset/claim UDP relay、UDP relay group open/add/remove/heartbeat/close、udp2raw tunnel open/heartbeat/close、capabilities 查询。
7. `dispose` 关闭 udp2raw entry、UDP relay group、UDP relay registry 和由 server 自身创建的 TCP bind channel。

# 目标

- 完成 `SocksProxyServer` 相关类 review 的计划文档。
- 处理计划文档中列出的可小范围修复风险点。
- 按用户要求先忽略 CI，不触发 GitHub Actions。
- 持续更新计划文档中的处理进度。

# 非目标

- 不做 socks 包大规模重构。
- 不升级依赖，不调整 Maven 或 GitHub Actions 配置。
- 不修改 secrets、token、证书、私钥，不发布 release。
- 本轮不触发 CI。

# 设计方案

采用最小改动策略：

1. 对 UDP relay registry 的端口冲突做明确处理，避免静默覆盖导致 channel 泄漏或错误路由。
2. 对 `withUdpRelay` 的跨 event loop 同步调用增加超时保护，避免 RPC 线程无限等待。
3. 对 `activeChannels` 关闭计数做防负数保护。
4. 对 `dispose` 遍历 UDP relay registry 做快照遍历，避免 close listener 同时修改 registry 的弱一致行为影响释放过程。
5. 对 `ProxyManageHandler` 在 memory/local channel 或无法解析远端地址的场景加保护，避免 traffic binding 路径 NPE。
6. RPC token 暴露边界经 review 后保持现有 public overload 以维持兼容性；远程暴露面以 `SocksRpcContract` 中带 token 的方法为准。未直接改方法可见性，避免破坏已有调用方。

# 修改文件列表

已新增/修改：

- `docs/plan/review-socks-proxy-server.md`
- `rxlib/src/main/java/org/rx/net/socks/SocksProxyServer.java`
- `rxlib/src/main/java/org/rx/net/socks/ProxyManageHandler.java`
- `rxlib/src/test/java/org/rx/net/socks/SocksProxyServerTest.java`

# 风险点处理进度

## 已处理

- [x] `registerUdpRelay` 原先使用 `put` 静默覆盖同端口 relay。现改为 `putIfAbsent` / `replace` 循环：
  - 无旧 relay 时注册新 relay。
  - 同一个 relay 重复注册时直接返回，避免重复 close listener。
  - 旧 relay 仍 active 时拒绝新重复 relay，并关闭新 relay，保留已有 active relay。
  - 旧 relay 非 active 时才替换。
  - 增加 `socks.udp.relay.duplicate.count` metric。
- [x] `withUdpRelay` 原先 `submit(task).get()` 可能无限阻塞。现增加 `UDP_RELAY_OPERATION_TIMEOUT_SECONDS = 5` 秒超时，超时后取消 future、记录 `socks.udp.relay.operation.timeout.count` metric，并返回 `false`。
- [x] `dispose` 原先直接遍历 `udpRelayRegistry.values()`，close listener 同时可能修改 registry。现改为 `toArray(new Channel[0])` 快照遍历后关闭，再 clear registry。
- [x] `activeChannels` 原先 closeFuture 中直接 decrement，异常重复关闭或外部 channel 复用场景存在负数风险。现使用 `updateAndGet(v -> v > 0 ? v - 1 : 0)` 防负数。
- [x] `ProxyManageHandler.setUser` 原先直接依赖 `Sockets.getOriginRemoteAddress(ctx.channel())` 并读取 `getAddress()`，memory/local channel 或未解析远端地址可能 NPE。现增加 `resolveRemoteAddress`，优先 origin remote，失败则尝试 `channel.remoteAddress()`；仍无法解析时绑定 anonymous 并返回。
- [x] `ProxyManageHandler.setUser` 在未解析远端地址时原先只把 channel attribute 绑定 anonymous，但 handler 内部仍保留真实 `TrafficUser`。现同步将内部 `trafficUser` 降级为 anonymous，并清空 `info`，避免后续 TCP/UDP context 或 session 统计继续使用未绑定真实用户。
- [x] `ProxyManageHandler.channelInactive` 的 remote 地址也改为同一解析逻辑，避免诊断与日志路径空地址异常。
- [x] RPC token 暴露边界已复核：`SocksRpcContract` 只声明带 token 的远程管理方法；`SocksProxyServer` 保留无 token public 方法作为本地 API，不调整可见性以避免破坏兼容性。
- [x] 已补充 `SocksProxyServerTest.testTrafficUserFallsBackToAnonymousWhenRemoteAddressUnresolved`，覆盖未解析远端地址时的 anonymous 降级。

## 未在本轮修改的项

- [ ] 未改变 RPC 方法签名或 public API。原因是兼容性风险高，且 contract 层远程暴露面已经是带 token 方法。

# 验证方案

本轮未触发 GitHub Actions CI，已执行本地 Java 8 Maven 验证：

1. `mvn -pl rxlib "-Dtest=SocksProxyServerTest" test`
   - 结果：通过，7 个用例全部成功。
2. `mvn -pl rxlib "-Dtest=SocksProxyServerIntegrationTest#shadowsocksUdpRelay_udp2rawUpstream_fixedEntry_compressAndRedundant_e2e" test`
   - 结果：通过，单独运行该集成用例成功。
3. `mvn -pl rxlib "-Dtest=SocksProxyServerTest,SocksProxyServerIntegrationTest,Socks5CommandRequestHandlerTest,SocksUdpRelayHandlerTest,Udp2rawFixedEntryIntegrationTest" test`
   - 结果：失败，51 个用例中 50 个通过；`SocksProxyServerIntegrationTest.shadowsocksUdpRelay_udp2rawUpstream_fixedEntry_compressAndRedundant_e2e` 在整组串跑时出现一次 payload 长度断言失败：expected 320, actual 288。
   - 备注：该用例单独运行通过，失败路径是 Shadowsocks UDP -> udp2raw fixed entry -> compress/redundant 组合，表现为整组运行时的 UDP redundant/compress 集成抖动，暂未归因到本轮 `SocksProxyServer` / `ProxyManageHandler` 修改。

后续需要 CI 验证时建议执行：

1. 触发 `.github/workflows/jdk8-unit-tests.yml`，使用 `workflow_dispatch`。
2. `test_classes` 优先带：
   - `org.rx.net.socks.SocksProxyServerTest`
   - `org.rx.net.socks.SocksProxyServerIntegrationTest`
   - `org.rx.net.socks.Socks5CommandRequestHandlerTest`
   - `org.rx.net.socks.SocksUdpRelayHandlerTest`
   - `org.rx.net.socks.Udp2rawFixedEntryIntegrationTest`
3. 如果后续补充 udp2raw / redundant / traffic accounting 修改，再补充：
   - `org.rx.net.socks.Udp2rawHandlerTest`
   - `org.rx.net.socks.UdpRedundantTest`
4. 只有 workflow run `conclusion=success` 才认为 CI 通过。

# 当前提交进度

- `a68004182be2817c6148083263a1a28a84c900e3`：`docs(plan): review SocksProxyServer related classes`
- `d182280b118f5a36efbba6f7e25a1d6d31977ead`：`fix: guard duplicate UDP relay registration`
- `3e21102a70b1d62ae0cb1fc4323e5ae9b2554cfb`：`fix: harden socks relay state management`

# 剩余风险

- CI 未运行，GitHub Actions 结果未知。
- 本地目标测试集存在 1 个整组串跑失败但单独运行通过的 UDP compress/redundant 集成抖动，需要后续单独排查是否有测试间 UDP 残留、端口复用或 redundant 序列状态隔离问题。
- 重复 UDP relay、relay event loop timeout 等场景仍建议继续补更细粒度单元测试。
- `withUdpRelay` 超时返回 `false` 是安全降级，但在极端 event loop 卡顿时管理操作可能失败，需要调用方按 false 处理重试或上报。
