# 背景

用户要求在 `rxlib` 库、`master` 分支 review `SocksProxyServer` 相关类。本阶段只做代码 review 与计划文档提交，不修改业务代码。review 基于 `master` 当前提交 `077a8e24d5970a133b533fad62db2a0ee7298106`，计划分支复用 `agent/review-socks-proxy-server`。

# 任务类型判断

本次归类为 Review / 修复 / 重构 / 优化需求。

原因：
- 用户明确要求 “review 下 SocksProxyServer 相关类”。
- 目标是检查现有实现、调用链、边界条件、风险点和验证方案。
- 按仓库 agent 流程，review 阶段只提交 `docs/plan/*` 计划文档；只有用户后续明确要求“按计划执行 / 开始修改代码 / 继续写代码”后，才进入业务代码修改阶段。

# 当前上下文

## 已 review 的文件

核心文件：
- `rxlib/src/main/java/org/rx/net/socks/SocksProxyServer.java`
- `rxlib/src/main/java/org/rx/net/socks/Socks5InitialRequestHandler.java`
- `rxlib/src/main/java/org/rx/net/socks/Socks5PasswordAuthRequestHandler.java`
- `rxlib/src/main/java/org/rx/net/socks/Socks5CommandRequestHandler.java`
- `rxlib/src/main/java/org/rx/net/socks/ProxyManageHandler.java`
- `rxlib/src/main/java/org/rx/net/socks/ProxyChannelIdleHandler.java`
- `rxlib/src/main/java/org/rx/net/socks/SocksTcpFrontendRelayHandler.java`
- `rxlib/src/main/java/org/rx/net/socks/SocksTcpBackendRelayHandler.java`
- `rxlib/src/main/java/org/rx/net/socks/SocksUdpRelayHandler.java`
- `rxlib/src/main/java/org/rx/net/socks/UdpRelayGroupManager.java`
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawServerEntryManager.java`
- `rxlib/src/main/java/org/rx/net/socks/SocksConnectionTagRegistry.java`
- `rxlib/src/main/java/org/rx/net/socks/SocksUserTraffic.java`
- `rxlib/src/main/java/org/rx/net/socks/SocksContext.java`

相关测试：
- `rxlib/src/test/java/org/rx/net/socks/SocksProxyServerTest.java`
- `rxlib/src/test/java/org/rx/net/socks/SocksProxyServerIntegrationTest.java`
- `rxlib/src/test/java/org/rx/net/socks/Socks5CommandRequestHandlerTest.java`
- `rxlib/src/test/java/org/rx/net/socks/SocksUdpRelayHandlerTest.java`
- `rxlib/src/test/java/org/rx/net/socks/Udp2rawFixedEntryIntegrationTest.java`
- `rxlib/src/test/java/org/rx/net/socks/Udp2rawHandlerTest.java`
- `rxlib/src/test/java/org/rx/net/socks/UdpRedundantTest.java`

验证配置：
- `.github/workflows/jdk8-unit-tests.yml`

## 关键调用链

1. `SocksProxyServer` 构造阶段根据 `SocksConfig` 选择普通 TCP 监听、Local memory channel 或外部传入 memory channel。
2. `acceptChannel(Channel)` 初始化 Netty pipeline：可选 `ProxyManageHandler`、可选 `ProxyChannelIdleHandler`、通用 TCP server handler、SOCKS5 encoder、initial decoder/handler、可选 password auth decoder/handler、command request decoder/handler。
3. `Socks5InitialRequestHandler` 根据是否启用 authenticator 选择 NO_AUTH 或 PASSWORD。
4. `Socks5PasswordAuthRequestHandler` 通过 `Authenticator` 写入 `SocksContext` 的认证结果。
5. `Socks5CommandRequestHandler` 根据 CONNECT / UDP_ASSOCIATE 分流，触发 `onTcpRoute` / `onUdpRoute`，创建 TCP backend 或 UDP relay。
6. TCP relay 使用 frontend/backend handler 双向转发并依赖 channel close 释放资源。
7. UDP relay 通过 `SocksUdpRelayHandler`、`registerUdpRelay`、`udpRelayRegistry` 与 RPC 管理接口协作。
8. udp2raw fixed-entry 通过 `Udp2rawServerEntryManager` 启动入口、打开/心跳/关闭 tunnel。
9. 流量统计通过 `ProxyManageHandler`、`SocksConnectionTagRegistry`、`SocksUserTraffic` 绑定 channel / session / user。

## 当前实现意图

- `SocksProxyServer` 是 SOCKS5 server 的入口和生命周期管理器。
- `onTcpRoute` / `onUdpRoute` 允许调用方自定义上游选择。
- `udpRelayRegistry` 支撑 relay port 到 channel 的管理操作。
- token overload 用于 RPC 管理接口鉴权。
- `ProxyManageHandler` 与 tag registry 用于流量统计和 session 归属。
- `udp2rawEntryManager` 只在 fixed-entry server 模式启用。

## 已发现的问题或风险

1. `openUdp2rawTunnel` 在未启用 `udp2rawEntryManager` 时返回 unsupported 结果，但 `heartbeatUdp2rawTunnel` / `closeUdp2rawTunnel` 仅返回 `false`，调用方无法区分 unsupported 与 not found。
2. `activeChannels` 的 close listener 在 `acceptChannel` 末尾注册，pipeline 初始化异常路径缺少显式关闭与计数保护测试。
3. `udpRelayRegistry` 使用本地 port 作为 key，未来如支持同端口多地址监听、reusePort 或多 group 复用端口，存在覆盖风险。
4. `withUdpRelay` 在非 relay EventLoop 线程使用 `.get()` 同步等待，RPC 管理线程可能被长时间阻塞。
5. RPC token 保护集中在 token overload，无 token public 方法仍直接执行管理操作，需要确认 RPC 暴露层不会暴露这些内部入口。
6. `connectionTagResolver` 返回 null、抛异常或连接失败时，流量绑定释放路径需要更明确测试覆盖。

# 目标

1. 完成 `SocksProxyServer` 及相关 SOCKS5、TCP relay、UDP relay、udp2raw、流量统计链路 review。
2. 提交 `docs/plan/review-socks-proxy-server.md`。
3. 明确后续可能的最小修复范围。
4. 明确测试与 GitHub Actions 验证方式。
5. 当前阶段不修改业务代码。

# 非目标

1. 不修改 `rxlib/src/main/java` 下业务代码。
2. 不新增或修改测试代码。
3. 不调整 public API，除非用户后续明确确认兼容性风险。
4. 不升级依赖、Maven 插件或 GitHub Actions。
5. 不修改 secrets、token、证书、私钥。
6. 不自动发布 release。

# 设计方案

后续如用户要求按计划执行，建议采用“先补测试复现，再做最小行为修复”的策略。

## 方案 A：测试优先与约束文档化

适用场景：风险尚未确认是生产 bug。

计划：
- 增加 `SocksProxyServerTest` / `SocksProxyServerIntegrationTest` 用例覆盖：
  - `acceptChannel` 重复调用不重复计数。
  - memory channel 生命周期不被 server 误关闭。
  - UDP relay port 唯一约束。
  - `connectionTagResolver` 返回 null / 抛异常 / 连接失败。
  - udp2raw unsupported 与 not found 语义。
- 对隐含约束加注释：
  - UDP relay registry 当前按 port 唯一。
  - `withUdpRelay` 同步切换到 relay EventLoop 的目的。
  - token overload 与内部无 token 方法的边界。

优点：兼容性风险低，能先确认真实问题。
缺点：不直接改变运行时行为。

## 方案 B：最小行为修复

适用场景：用户确认需要修复相关风险。

候选修改：
1. `SocksProxyServer.registerUdpRelay`
   - 若 port 已存在且旧 channel active，拒绝覆盖或记录诊断指标。
   - close listener 继续用 `remove(key, relay)`，避免误删后注册的新 relay。
2. `SocksProxyServer.withUdpRelay`
   - 增加 timeout 或 fail-fast 保护，避免无限等待。
   - 保持 JDK8 API。
3. `SocksProxyServer` udp2raw RPC 方法
   - 对 unsupported 场景补充 diagnostic metric 或日志。
   - 如要变更返回语义，另起兼容性评估。
4. `ProxyManageHandler` / `SocksConnectionTagRegistry`
   - 增强 resolver 异常保护和 finally 释放检查。
5. RPC token API
   - 确认 RPC 暴露层仅暴露 token overload。
   - 如果不能保证，考虑把无 token public 方法改为内部方法或新增安全包装；这可能影响外部调用方，需用户确认。

## 异常处理策略

- RPC token 校验失败继续抛 `SecurityException`，并记录现有诊断指标。
- UDP relay 不存在继续返回 `false`，除非确认要引入明确结果类型。
- resolver 异常不应导致 tag 泄漏；建议关闭连接或降级为未绑定 tag，并确保 session close 记录一致。

## 资源释放策略

- `dispose()` 继续按当前顺序关闭 `udp2rawEntryManager`、`udpRelayGroupManager`、已注册 UDP relay、registry、自有 TCP channel 和 bootstrap。
- 外部传入 memory channel 不主动关闭，保持当前所有权语义。
- relay registry 修改必须防止 close listener 误删新 channel。
- pipeline 初始化失败时应尽量显式关闭 channel 或保证上游会关闭。

## 并发策略

- channel attr 与 relay 状态清理尽量在对应 EventLoop 内执行。
- 避免 EventLoop 内跨线程同步等待。
- 共享结构继续使用 `ConcurrentHashMap`。
- 计数器继续使用 `AtomicInteger`，但增加异常路径测试。

# 修改文件列表

本阶段修改：
- `docs/plan/review-socks-proxy-server.md`

如果后续进入代码实现阶段，预计可能修改：
- `rxlib/src/main/java/org/rx/net/socks/SocksProxyServer.java`
- `rxlib/src/main/java/org/rx/net/socks/ProxyManageHandler.java`
- `rxlib/src/main/java/org/rx/net/socks/SocksConnectionTagRegistry.java`
- `rxlib/src/main/java/org/rx/net/socks/SocksUserTraffic.java`
- `rxlib/src/test/java/org/rx/net/socks/SocksProxyServerTest.java`
- `rxlib/src/test/java/org/rx/net/socks/SocksProxyServerIntegrationTest.java`
- `rxlib/src/test/java/org/rx/net/socks/Socks5CommandRequestHandlerTest.java`
- `rxlib/src/test/java/org/rx/net/socks/SocksUdpRelayHandlerTest.java`
- `rxlib/src/test/java/org/rx/net/socks/Udp2rawFixedEntryIntegrationTest.java`

# 风险点

1. 兼容性风险：`SocksProxyServer` public 管理方法可能已有外部调用方，改可见性或返回语义需谨慎。
2. 性能风险：`withUdpRelay(...).get()` 同步等待可能影响 RPC 管理吞吐。
3. 并发风险：UDP relay port key 覆盖、close listener 与新注册 relay 竞态。
4. 资源释放风险：memory channel 所有权不同，不能误关外部 channel；pipeline 初始化异常也需释放。
5. 测试风险：SOCKS/UDP/udp2raw 集成测试依赖本地端口与 Netty timing，可能存在 flaky。
6. JDK 风险：项目默认按 JDK8 验证，后续实现禁止使用 JDK9+ API。
7. 安全风险：RPC token overload 与无 token public 方法边界需要确认。

# 验证方案

本阶段只提交计划文档，不将 CI 结果声明为业务修复通过。

后续如进入代码修改阶段：
1. 提交代码后手动触发 `.github/workflows/jdk8-unit-tests.yml`。
2. workflow 名称：`JDK 8 Unit Tests`。
3. branch：`agent/review-socks-proxy-server`。
4. `test_classes` 建议：
   - `org.rx.net.socks.SocksProxyServerTest`
   - `org.rx.net.socks.SocksProxyServerIntegrationTest`
   - `org.rx.net.socks.Socks5CommandRequestHandlerTest`
   - `org.rx.net.socks.SocksUdpRelayHandlerTest`
   - `org.rx.net.socks.Udp2rawFixedEntryIntegrationTest`
5. 如果影响 udp2raw payload 或 redundant 逻辑，追加：
   - `org.rx.net.socks.Udp2rawHandlerTest`
   - `org.rx.net.socks.UdpRedundantTest`
6. CI 如失败，先分类为编译失败、单测失败、格式失败、依赖下载失败、JDK8 不兼容、环境问题、测试不稳定或 workflow 配置问题。
7. 修复时只修改与失败直接相关的代码。
8. 修复后再次 commit 并重新触发 `jdk8-unit-tests.yml`。
9. 只有 workflow run `conclusion=success` 才认为 CI 通过。
