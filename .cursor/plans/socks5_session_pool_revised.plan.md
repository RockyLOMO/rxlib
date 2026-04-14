# Socks5 Session Pool（修订版 v5）

## Summary
- TCP 做暖池；UDP 做 lease 池。TCP 目标是消掉到 ProxyB 的 `connect + auth` RTT，UDP 目标是复用远端 `UDP_ASSOCIATE` relay，覆盖 `docs/test/socksServer.md` 的场景2、udp2raw 场景、场景4。
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
- `Socks5WarmupHandler` 提供单次 `connect(UnresolvedEndpoint dst)` 和单次 `setConnectedCallback(Action)`；callback 在 `CONNECT SUCCESS` 后、promise 成功前触发，用于与慢路径等价地安装 `cipherRoute + COMPRESS_BOTH` 的 `CipherDecoder/CipherEncoder`，随后再进入 `relay(...)`。
- `Socks5CommandRequestHandler` 快慢路径都先执行 `prepareDestination()`；快路径 borrow 命中后调用 `markCtx(inbound, outbound, sc)` 新重载，再调用 `warmupHandler.connect(preparedDestination)`；慢路径保持现有 connect future 路径。
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
- TCP 测试覆盖：warm channel 在 `maxIdle` 内命中，超过 `maxIdle` 被 age filter 淘汰；warm path 下 auth 报文确实走 `Sockets.addTcpClientHandler(...)`；`cipherRoute=true + COMPRESS_BOTH` 时快路径会安装额外 cipher handlers；`cipherRoute=false` 时快路径直接 `relay(...)`，不安装额外 cipher handlers；快路径 CONNECT 失败只降级一次到慢路径；auth 连续失败时 refill backoff 生效。
- UDP 测试覆盖：场景2 两个不同客户端顺序复用同一 lease，访问同一 destination，不得命中旧 `routeMap`；recycle 后注入旧 borrower 的迟到包，reset/claim 之间必须被 drop；`claim` 连续失败达到阈值后 breaker 打开并直接慢路径；存在 `udpRelayIdleSeconds` hint 时会 clamp `effectiveIdle`；无 hint 时使用本地 idle。
- udp2raw 测试覆盖：`Udp2rawHandler` 复用同一套 reset/claim/lock 语义；现有 udp2raw chained e2e 保持通过；新增 sequential reuse + stale packet drop 回归。
- 场景4 测试覆盖：`Shadowsocks -> Socks A -> Socks B -> UDP dest` 有 facade 时命中 lease pool；无 facade 时自动慢路径。
- 生命周期测试覆盖：配置热更新移除 endpoint 后对应 TCP/UDP pools 被关闭；`closeAll()` 与异步 reset 交错时不会泄漏 lease 的 TCP control channel。

## Assumptions
- `udpRelayIdleSeconds` 作为每个上游 endpoint 的运维 hint，放在 `AuthenticEndpoint.parameters`，不走额外 RPC。
- UDP 池化仍然是“RPC 可选”；没有 facade 只回退慢路径。
- `reset` 放 recycle，`claim` 放 borrow；两者服务端都在 relay `eventLoop` 上执行。
- 所有新池化能力默认关闭，需要显式开启。
