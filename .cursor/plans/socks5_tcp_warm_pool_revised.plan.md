---
name: Socks5 TCP Warm Pool (Revised)
overview: 仅对 SOCKS5 TCP CONNECT 上游做认证预热；移除 UDP session 全局池化方案。新的实现不复用 ProxyHandler 生命周期，而是引入独立 auth-only handler，并保持现有 upstream 初始化、SocksContext 绑定、cipher/fake-endpoint 逻辑一致。
todos:
  - id: tcp-warm-handler
    content: "新增 auth-only SOCKS5 warm handler/state machine，完成到上游 SOCKS5 的 TCP connect + init + password auth，并提供单次 CONNECT future"
    status: pending
  - id: tcp-warm-pool
    content: "新增 per pool-key 的 TCP warm pool，后台补齐已认证空闲连接并淘汰失效/过期连接"
    status: pending
  - id: upstream-refactor
    content: "拆分 SocksTcpUpstream 的 destination 预处理与 transport 初始化，确保 fake-endpoint/transport handlers 在快慢路径一致执行"
    status: pending
  - id: tcp-integrate
    content: "改造 Socks5CommandRequestHandler：优先借 warm channel，但继续走统一的 SocksContext.markCtx / Backpressure / relay 时序"
    status: pending
  - id: udp-scope
    content: "删除 UDP 全局池化方案，保留当前每个 relay channel 持有一个上游 UDP session 的模型，并补文档/测试锁定该约束"
    status: pending
  - id: verify
    content: "编译验证 + TCP/UDP 集成测试"
    status: pending
isProject: false
---

# Socks5 TCP Warm Pool（修订版）

## Summary
- 只优化 `SocksTcpUpstream` 的上游 SOCKS5 `CONNECT`。目标是把“到上游代理的 TCP connect + init + password auth”从请求路径移到后台预热。
- 不实现 UDP session 全局池；保留当前“一条下游 UDP relay channel 对应一条上游 UDP_ASSOCIATE session”的模型，避免远端 relay 状态泄漏。
- 现有 `Socks5ClientHandler` 继续用于普通慢路径和 `Socks5Client`；新增独立 auth-only handler 处理 warm channel，避免复用 `ProxyHandler` 的单次 connect 生命周期。

## Key Changes
### TCP Warm Handler And Pool
- 新增包内类 `Socks5WarmupHandler`，位于 `org.rx.net.socks`，且不继承 `ProxyHandler`。它只负责两段握手：先完成到上游 proxy 的 TCP connect + SOCKS5 init/auth，再在 `READY` 状态下接受一次 `connect(UnresolvedEndpoint dst)` 发送 `CONNECT` 命令。
- `Socks5WarmupHandler` 维护显式状态机：`INIT -> AUTHING -> READY -> CONNECTING -> CONSUMED/FAILED`。`READY` 只允许进入一次 `CONNECTING`；一旦命中 `CONNECT`，该 channel 永不回池。
- 超时语义拆成两段并固定实现：warm 阶段使用 `SocketConfig.connectTimeoutMillis` 约束物理 connect + auth；auth 成功后必须取消该 timer；后续 `connect(dst)` 再启动一次同值 command timer，避免 ready channel 被旧 timer 误杀。
- 新增 `Socks5TcpWarmPool` 与 `Socks5WarmPoolKey`。pool key 固定为 `AuthenticEndpoint + reactorName + connectTimeoutMillis + transportFlags + cipher + cipherKeyHash`，避免同 endpoint 但不同传输配置误复用。
- `Socks5TcpWarmPool.borrow()` 必须是非阻塞 `poll`。借不到、借到失活 channel、借到过期 channel 时都立即返回 `null`，调用方直接降级慢路径，不在请求线程里创建新 warm channel。
- pool 内只缓存 `READY` 且未消费的 channel。后台 refill 只补到 `tcpWarmPoolMinSize`；`maxIdle`、channel inactive、auth 失败、command 失败都会 retire。
- `SocksConfig` 只新增 TCP 相关字段：`tcpWarmPoolEnabled=false`、`tcpWarmPoolMinSize=2`、`tcpWarmPoolMaxIdleMillis=60000`、`tcpWarmPoolRefillIntervalMillis=1000`。本计划不新增任何 UDP pool 配置项。

### Upstream And Connect Flow
- `SocksTcpUpstream` 拆分职责。新增 `prepareDestination()`，把当前 fake-endpoint / RPC 映射逻辑移到这里，并保证同一个 upstream 实例只执行一次；快慢路径都先调用它，再决定实际 `CONNECT` 目标。
- `SocksTcpUpstream` 新增 `initTransport(Channel)`，只负责 `Sockets.addTcpClientHandler(...)` 这类 transport handlers。现有 `initChannel(Channel)` 保留给慢路径，但只做 `initTransport(...) + 标准 Socks5ClientHandler`，不再承担 destination 改写。
- `Socks5CommandRequestHandler` 的 TCP 路径改成统一流程：先 `prepareDestination()`，再尝试 warm borrow，失败后再走慢路径。快慢路径都必须产出一个“SOCKS CONNECT 完成”的 `ChannelPromise`，并用它调用 `SocksContext.markCtx(...)`，不再直接用原始物理 socket connect future。
- 快路径借到 warm channel 后，直接对该 channel 调用 `warmHandler.connect(preparedDestination)`，把返回的 command promise 作为当前 outbound readiness。若该 promise 失败，只关闭该 borrowed channel，并回退一次慢路径；同一次请求不重复尝试 warm borrow。
- 慢路径仍然创建新 outbound channel，但由 `Socks5ClientHandler.connectFuture()` 驱动 readiness promise；这样 `SocksTcpFrontendRelayHandler` 的缓冲语义与快路径保持一致，只有 SOCKS CONNECT 成功后才标记 outbound ready。
- `Socks5CommandRequestHandler` 抽出统一的 `onProxyConnectReady(...)` 逻辑。快慢路径都走这一段完成 `BackpressureHandler` 安装、cipherRoute 后置处理和 `relay(...)`；不允许快路径绕开现有 frontend/backpressure/context 绑定。
- `onReconnecting` 的语义保持收敛：物理建连失败沿用现有重试；warm 快路径里的 command 失败只降级到一次慢路径，不进入无限递归重试。

### UDP Scope
- 删除原方案中的 `Socks5UpstreamPool`、UDP `ObjectPool`、`UdpSessionHolder`、`borrowUdp()/recycleUdp()` 和对应的 `SocksConfig` 参数。
- `SocksUdpUpstream` 保持当前模型：每个 relay channel 懒加载并缓存一个上游 `Socks5UdpSession`，该 session 生命周期继续绑定 relay channel close。
- 文档里明确新增一条约束：UDP upstream session 不跨 relay channel 复用，也不跨客户端复用。任何后续 UDP 优化都只能在单个 relay channel 生命周期内做缓存，不能做全局 session 池。

## Test Plan
- 为 `Socks5WarmupHandler` 增加单测，覆盖 no-auth、password-auth、auth timeout、command timeout、auth 成功后 idle 不会被旧 timer 关闭、以及 `READY` channel 只能消费一次。
- 为 `Socks5CommandRequestHandler` / `SocksTcpUpstream` 增加集成测试，覆盖 warm pool 命中、pool 为空自动降级、borrow 到坏 channel 自动丢弃、warm command 失败后回退慢路径。
- 增加一条带 fake-endpoint / `SocksRpcContract` 的 TCP 集成测试，确认 `prepareDestination()` 在快慢路径上行为一致；再加一条带 `TransportFlags.COMPRESS_BOTH` 的链路测试，确认 cipher/backpressure/relay 时序未被破坏。
- UDP 只做回归与约束测试：现有 chained UDP 用例必须保持通过；新增“两条不同客户端 control session 经 ProxyA -> ProxyB 发同一 UDP 目标”的用例，断言它们不会共享同一个上游 UDP session 状态。

## Assumptions
- 本修订版故意缩 scope：不做 UDP 全局池化，只做可证明正确的 TCP warm-auth。
- warm pool 只接入 `SocksTcpUpstream`；`Upstream` 直连、`SocksUdpUpstream`、`Shadowsocks`、`udp2raw` 路径不接入该优化。
- 默认关闭 `tcpWarmPoolEnabled`；只有显式打开时才启用该特性。
