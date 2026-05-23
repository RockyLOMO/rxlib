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
   - `org.rx.net.udp.UdpRedundantTest`
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

# 附：Socks5 Session Pool 设计方案（修订版 v6）

## Summary
- TCP 做暖池；UDP 做 lease 池。TCP 目标是消掉到 ProxyB 的 `connect + auth` RTT，UDP 目标是复用远端 `UDP_ASSOCIATE` relay，覆盖 `../test/SocksScene.md` 的场景2、udp2raw 场景、场景4。
- 不直接用 `AuthenticEndpoint` 做 map key；新增不可变 snapshot key 类，避免 `AuthenticEndpoint` 缺少 `equals/hashCode` 且字段可变导致 pool 永远 miss。
- TCP 暖连接是后台预建的，borrow 后与 inbound 不同 `eventLoop`；这会多一次跨线程 hop，但不是正确性问题。实现和代码注释里要明确这一点。
- UDP 复用依赖 `SocksRpcContract` 的远端控制面，拆成 `resetUdpRelay(relayPort)` 和 `claimUdpRelay(relayPort, clientAddr)`；`reset` 在 recycle 后异步执行，`claim` 在 borrow 时同步短超时执行。
- 没有 facade、RPC breaker 打开、或 `claim` 失败时，UDP 直接回退现有逐次 `udpAssociateAsync(channel)` 慢路径。

## API And Type Changes
- 新增 `TcpWarmPoolKey`；字段固定为 `hostString + port + username + password + reactorName + connectTimeoutMillis + transportFlagsBits + cipher + cipherKeyHash`。
- 新增 `UdpLeasePoolKey`；字段与 TCP key 使用同一套 transport profile，但不额外包含本地 `enableUdp2raw`。注释里说明：如果部署保证同一 endpoint 的 transport config 永远一致，这个 key 可以被视为等价于 endpoint snapshot。
- 不修改 `AuthenticEndpoint.equals/hashCode`；原因是该类已有 setter 和 mutable `parameters`，全局改变语义风险高。pool 相关逻辑一律通过 key snapshot 构造函数取值。
- `SocksRpcContract` 新增：
  - `boolean resetUdpRelay(int relayPort)`
  - `boolean claimUdpRelay(int relayPort, InetSocketAddress clientAddr)`
- `Socks5Client` 新增：
  - `CompletableFuture<Socks5UdpLease> udpAssociateLeaseAsync()`
  - `Socks5UdpLease` 只持有 `tcpControl`、`relayAddress`、`relayPort`
- `SocksContext` 新增：
  - `markCtx(Channel inbound, Channel outbound, SocksContext sc)`；内部使用 `outbound.newSucceededFuture()` 并直接设置 `outboundActive=true`
- `SocksTcpUpstream` 拆分：
  - `prepareDestination()`
  - `initTransport(Channel)`
  - `initProxyHandler(Channel)`

## Implementation Changes
### TCP Warm Pool
- 新增 `Socks5WarmupHandler`，不继承 `ProxyHandler`；状态固定为 `INIT -> AUTHING -> READY -> CONNECTING -> CONSUMED/FAILED`。
- warm channel 创建顺序固定为：bootstrap initializer 内先执行 `Sockets.addTcpClientHandler(channel, config, svrEp.getEndpoint())`，再 `pipeline.addLast(Socks5WarmupHandler)`。这样 TLS/cipher/compress 对 SOCKS5 auth 报文同样生效。
- `READY` 时记录 `readyAtMillis`；borrow 时同时检查 `channel.isActive()` 和 `now - readyAtMillis < tcpWarmPoolMaxIdleMillis`，任一失败都 retire。
- `Socks5WarmupHandler` 提供单次 `connect(UnresolvedEndpoint dst)` 和单次 `setConnectedCallback(Action)`；调用方必须先 `setConnectedCallback(...)` 再 `connect(...)`。
- `setConnectedCallback(...)` 的执行时序固定为：
  - 收到 `Socks5CommandResponse(SUCCESS)` 后，先在 handler 内部同步执行 `connectedCallback.invoke()`，用于安装 `cipherRoute + COMPRESS_BOTH` 的 `CipherDecoder/CipherEncoder`
  - 然后移除 warmup handler 自己加的 SOCKS codec
  - 最后才将 `connect promise` 标记为 success
  - 语义上对齐现有 `Socks5ClientHandler.handleResponse()` 中 `handshakeCallback.invoke()` 先于 `ProxyHandler` 完成 promise 的顺序，确保 `relay()` 开始前 cipher handler 已经在 pipeline 中
- `Socks5CommandRequestHandler` 快慢路径都先执行 `prepareDestination()`；快路径 borrow 命中后调用 `markCtx(inbound, outbound, sc)` 新重载，再设置 `connectedCallback`，再调用 `warmupHandler.connect(preparedDestination)`；慢路径保持现有 connect future 路径。
- 快路径 CONNECT 失败策略固定为：不在该 warm channel 上重试；立即关闭该 channel；只降级一次到慢路径；后续重连完全交给现有 `onReconnecting + 最多 16 次` 逻辑；本次请求不再消耗第二个暖连接。
- `Socks5TcpWarmPool` 增加 create-failure backoff；auth/connect 创建失败记一次连续失败，refill delay 按 `min(baseInterval * 2^n, 30000ms)` 退避，首次成功后清零。
- TCP pool manager 负责 `close(key)` / `closeAll()`；关闭时 cancel refill task，关闭全部 READY channel。

### UDP Lease Pool
- `udpAssociateAsync(channel)` 和 `udpAssociateLeaseAsync()` 都必须修正 TCP control channel 的 initializer 顺序：先 `Sockets.addTcpClientHandler(ch, config, proxyServer.getEndpoint())`，再加 `Socks5ClientHandler`，确保 control channel 也走 TLS/cipher/compress。
- `SocksUdpUpstream.initChannel(channel)` 固定流程为：先查本地 holder；若启用 lease pool 且 `next.getFacade() != null` 且 breaker 未打开则 borrow lease；borrow 成功后用当前 `channel.localAddress()` 调 `claimUdpRelay(relayPort, clientAddr)`；claim 成功才把 lease 绑定到该 relay channel；否则直接回退慢路径。
- recycle 侧固定流程为：relay close 时先从本地 channel 摘 holder；异步执行 `resetUdpRelay(relayPort)`；reset 成功且 `!pool.isClosed()` 时才 `pool.recycle(lease)`；其余所有情况都执行 `lease.close()`，避免 pool 关闭与异步 reset 交错时泄漏 TCP control channel。
- `claim` 不能挪到 return；return 时不知道下一次 borrow 的 `clientAddr`，只能做 reset。
- UDP `effectiveIdle` 由本地配置和 endpoint hint 共同决定：若 `AuthenticEndpoint.parameters["udpRelayIdleSeconds"]` 存在且可解析，则取 `min(localUdpLeasePoolMaxIdleMillis, hint*1000 - 5000)`；否则直接使用本地 `udpLeasePoolMaxIdleMillis`；若结果 `< 1000ms`，该 endpoint 的 UDP pool 直接禁用并记 warning。

### Remote Relay Control And Concurrency
- `SocksProxyServer` 新增 UDP relay registry：`relayPort -> Channel`；`UDP_ASSOCIATE` bind 成功时注册，relay close 时移除。
- `resetUdpRelay` 与 `claimUdpRelay` 的服务端实现都必须：先从 registry 取 relay channel；不存在或不活跃返回 `false`；若当前线程不在 `relay.eventLoop()` 则 `submit()` 到该 `eventLoop` 并等待结果。
- `resetUdpRelay` 行为固定为：`ctxMap.clear()`、`routeMap.clear()`、`ATTR_CLIENT_ADDR = null`、`ATTR_CLIENT_LOCKED = true`。
- `claimUdpRelay` 行为固定为：再次 `clear ctxMap/routeMap`、`ATTR_CLIENT_ADDR = clientAddr`、`ATTR_CLIENT_LOCKED = true`。
- `SocksUdpRelayHandler` 与 `Udp2rawHandler` 共用 lock 语义：`ATTR_CLIENT_LOCKED=false` 时保持现有慢路径行为；`ATTR_CLIENT_LOCKED=true && ATTR_CLIENT_ADDR==null` 时丢弃新的 client-side 包；`ATTR_CLIENT_LOCKED=true && sender!=ATTR_CLIENT_ADDR` 时直接丢弃，不更新 clientAddr，不建 routeMap。
- 由于 `reset/claim` 和 `channelRead0` 都在同一个 relay `eventLoop` 上执行，所以不会出现 `routeMap.clear()` 与 `routeMap.put()` 的竞态。

### Breaker, Lifecycle, Metrics
- UDP breaker 继续用现有 `Cache + CachePolicy.absolute(...)`；连续 `udpLeaseRpcBreakerThreshold` 次 `claim/reset` 失败后，打开 `udpLeaseRpcBreakerOpenSeconds`；打开期间直接慢路径；任一次 `claim/reset` 成功即清零。
- `claim` RPC 使用短超时，固定为 `min(500ms, max(100ms, connectTimeoutMillis / 4))`。
- pool 生命周期统一由 `Socks5UpstreamPoolManager` 管理，不绑单个 `SocksProxyServer.dispose()`；配置热更新移除 endpoint 时同步关闭对应 pools，应用退出时 `closeAll()`。
- 指标写入 `TraceHandler.INSTANCE.saveMetric()`：
  - TCP：warm hit/miss、stale-age retire、inactive retire、create-failure backoff、slow-path fallback、cross-eventloop borrow count
  - UDP：lease borrow、claim success/fail、reset success/fail、breaker open、retire reason、effective idle、slow-path fallback

## Test Plan
- 新增 key 正确性测试：两个字段相同但实例不同的 `AuthenticEndpoint` 构造出来的 `TcpWarmPoolKey` / `UdpLeasePoolKey` 必须相等且 hash 相同。
- TCP 测试覆盖：warm channel 在 `maxIdle` 内命中，超过 `maxIdle` 被 age filter 淘汰；warm path 下 auth 报文确实走 `Sockets.addTcpClientHandler(...)`；`cipherRoute=true + COMPRESS_BOTH` 时快路径会安装额外 cipher handlers；`cipherRoute=false` 时快路径直接 `relay(...)`，不安装额外 cipher handlers；快路径 CONNECT 失败只降级一次到慢路径；auth 连续失败时 refill backoff 生效；`connectedCallback` 执行顺序必须早于 codec cleanup 和 promise success，可通过在 callback 中插入标记 handler 并验证 `relay()` 开始前 pipeline 已包含 cipher handler。
- UDP 测试覆盖：场景2 两个不同客户端顺序复用同一 lease，访问同一 destination，不得命中旧 `routeMap`；recycle 后注入旧 borrower 的迟到包，reset/claim 之间必须被 drop；`claim` 连续失败达到阈值后 breaker 打开并直接慢路径；存在 `udpRelayIdleSeconds` hint 时会 clamp `effectiveIdle`；无 hint 时使用本地 idle；`closeAll()` 与异步 reset 交错时 lease 的 TCP control channel 不泄漏。
- udp2raw 测试覆盖：`Udp2rawHandler` 复用同一套 reset/claim/lock 语义；现有 udp2raw chained e2e 保持通过；新增 sequential reuse + stale packet drop 回归。
- 场景4 测试覆盖：`Shadowsocks -> Socks A -> Socks B -> UDP dest` 有 facade 时命中 lease pool；无 facade 时自动慢路径。

## Assumptions
- `udpRelayIdleSeconds` 作为每个上游 endpoint 的运维 hint，放在 `AuthenticEndpoint.parameters`，不走额外 RPC。
- UDP 池化仍然是“RPC 可选”；没有 facade 只回退慢路径。
- `reset` 放 recycle，`claim` 放 borrow；两者服务端都在 relay `eventLoop` 上执行。
- 所有新池化能力默认关闭，需要显式开启。
