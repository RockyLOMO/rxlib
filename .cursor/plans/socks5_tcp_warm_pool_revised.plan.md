---
name: Socks5 Session Pool (TCP Warm + UDP Lease via RPC)
overview: TCP 继续使用 auth-only warm pool；UDP 改为 lease pool，不复用绑定到本地 relay channel 的 session，而是复用独立的远端 UDP_ASSOCIATE lease，并通过 SocksRpcContract 在借出时远端清理/重绑定 relay 状态。适用 docs/test/socksServer.md 的场景2、udp2raw 场景、场景4；无 RPC facade 时 UDP 自动回退现有逐次握手。
todos:
  - id: tcp-warm-handler
    content: "新增 TCP auth-only warm handler/state machine，替代直接复用 ProxyHandler 生命周期"
    status: pending
  - id: tcp-warm-pool
    content: "实现 per pool-key 的 TCP warm pool，并接入 SocksTcpUpstream / Socks5CommandRequestHandler"
    status: pending
  - id: udp-lease-api
    content: "新增 Socks5Client UDP lease API：只建立 TCP control + UDP_ASSOCIATE，不绑定本地 UDP channel"
    status: pending
  - id: udp-lease-pool
    content: "实现 per pool-key 的 UDP lease ObjectPool，并在 SocksUdpUpstream 中优先 borrow/create"
    status: pending
  - id: udp-rpc-control
    content: "扩展 SocksRpcContract 与服务端 relay registry，支持按 relayPort 远端 prepare/reset UDP relay"
    status: pending
  - id: udp-relay-hardening
    content: "为 SocksUdpRelayHandler / Udp2rawHandler 增加 pooled-relay 的 expected-client lock 与 stale-packet drop"
    status: pending
  - id: verify
    content: "补充场景2 / udp2raw / 场景4 的复用回归与编译验证"
    status: pending
isProject: false
---

# Socks5 Session Pool（修订版 v2）

## Summary
- TCP 方案沿用上一版修订思路：只预热到上游 SOCKS5 的物理 connect + auth，不复用 `ProxyHandler` 的单次 connect 生命周期。
- UDP 改为“lease 池”而不是“session 池”：池里对象只持有远端 `UDP_ASSOCIATE` 的 TCP control 与 relay 地址，不绑定当前本地 relay channel。
- 远端状态清理由 `SocksRpcContract` 控制面完成；RPC 是可选能力。
  有 facade 时启用安全复用；没有 facade 时 `SocksUdpUpstream` 自动回退当前逐次 `udpAssociateAsync(channel)` 慢路径。
- 目标覆盖 `docs/test/socksServer.md` 的场景2、udp2raw 场景、场景4。

## Public API / Type Changes
- `SocksRpcContract` 新增：
  - `boolean prepareUdpRelay(int relayPort, InetSocketAddress clientAddr)`
  语义固定为：找到指定 relay，清空远端路由状态，并把当前合法发送端锁定为 `clientAddr`；找不到或 relay 已关闭时返回 `false`。
- `Socks5Client` 新增：
  - `CompletableFuture<Socks5UdpLease> udpAssociateLeaseAsync()`
  - `Socks5UdpLease` 仅包含 `Channel tcpControl`、`InetSocketAddress relayAddress`、`int relayPort`，`close()` 只关闭 control channel。
  - 该 lease API 不创建也不持有本地 UDP channel，请求里的 UDP source 固定走 `0.0.0.0:0`。
- `SocksConfig` 新增默认关闭的池化配置：
  - TCP：`tcpWarmPoolEnabled=false`、`tcpWarmPoolMinSize=2`、`tcpWarmPoolMaxIdleMillis=60000`、`tcpWarmPoolRefillIntervalMillis=1000`
  - UDP：`udpLeasePoolEnabled=false`、`udpLeasePoolMinSize=2`、`udpLeasePoolMaxSize=32`、`udpLeasePoolIdleTimeoutMillis=300000`
- `SocksTcpUpstream` 新增显式拆分：
  - `prepareDestination()`
  - `initTransport(Channel)`
- `SocksProxyServer` 新增服务端 UDP relay registry，按 relay 本地端口索引 active UDP relay channel。

## Implementation Changes
### TCP Warm Pool
- 保留上一版的 `Socks5WarmupHandler` 独立状态机：`INIT -> AUTHING -> READY -> CONNECTING -> CONSUMED/FAILED`。
- `READY` channel 只能消费一次；借出后不归还。
- `Socks5CommandRequestHandler` 快慢路径统一先执行 `prepareDestination()`，再分别走 warm borrow 或普通建连；两条路径都必须走同一套 `SocksContext.markCtx(...)`、`BackpressureHandler.install(...)`、cipher/fake-endpoint 后置时序。
- pool key 固定为 `AuthenticEndpoint + reactorName + connectTimeoutMillis + transportFlags + cipher + cipherKeyHash`。

### UDP Lease Pool
- 不再池化 `Socks5UdpSession`，也不把 caller-supplied UDP channel 传进 pooled 对象。
- 新增 `Socks5UdpLeasePool`，底层直接使用 `ObjectPool<Socks5UdpLease>`。
  - `createHandler`：`new Socks5Client(ep, config).udpAssociateLeaseAsync().get(connectTimeout)`
  - `validateHandler`：`lease.tcpControl.isActive()`
  - `borrowTimeout`：沿用 `config.connectTimeoutMillis`
  - `idleTimeout`：沿用 `udpLeasePoolIdleTimeoutMillis`
- `SocksUdpUpstream.initChannel(channel)` 改成：
  - 若 channel 已绑定 holder 且仍有效，直接返回
  - 若 `udpLeasePoolEnabled && next.getFacade() != null`，先从 pool `borrow()`；borrow 成功后立即调用 `next.getFacade().prepareUdpRelay(lease.relayPort, (InetSocketAddress) channel.localAddress())`
  - `prepareUdpRelay(...)` 成功才把该 lease 绑定到当前 relay channel；失败则 retire 该 lease，并回退到现有 `udpAssociateAsync(channel)` 慢路径
  - relay channel close 时：pooled lease 只做 `pool.recycle(lease)`；非 pooled 慢路径仍按现有方式关闭 session/control
- `SocksUdpUpstream.getUdpRelayAddress(channel)` 继续作为唯一出口；relay handler 不感知 pooled 或非 pooled，只按是否存在 relay address 决定是否保留 SOCKS5 header。

### Remote Relay Reset Via RPC
- `SocksProxyServer` 在 `UDP_ASSOCIATE` 成功 bind 后，把新建 relay channel 注册到 `udpRelayRegistry[relayPort]`；close 时移除。
- `Main.prepareUdpRelay(...)` 直接委托给 `svrSide`。
- `SocksProxyServer.prepareUdpRelay(relayPort, clientAddr)` 的行为固定为：
  - 从 registry 取出 relay channel；不存在或不活跃返回 `false`
  - 清空该 relay 上的 `ctxMap` / `routeMap`
  - 把 `ATTR_CLIENT_ADDR` 重置为 `clientAddr`
  - 设置 `ATTR_CLIENT_LOCKED=true`
  - 不关闭 relay，不新建 channel，不做路由
- 该 prepare 逻辑必须同时支持 `SocksUdpRelayHandler` 和 `Udp2rawHandler`，通过共享 helper 或统一 attribute key 处理，不拆两套 RPC 接口。

### Relay Hardening For Safe Reuse
- 在 `SocksUdpRelayHandler` 和 `Udp2rawHandler` 增加 pooled-relay 约束：
  - 当 `ATTR_CLIENT_LOCKED=true` 时，只有 `sender == ATTR_CLIENT_ADDR` 的数据包才允许走“client -> upstream”路径
  - 不匹配的 sender 且又不在 `ctxMap` 中时，直接丢弃，不再按新客户端自动接管
- 非 pooled 慢路径保持旧行为：
  - `ATTR_CLIENT_LOCKED=false`
  - 第一包仍可把 `ATTR_CLIENT_ADDR` 从 TCP peer 修正为真实 UDP sender
- 这条约束是 UDP 复用正确性的核心，目的是在 lease 复用前后丢掉旧 borrower 的迟到包，避免 `ctxMap/routeMap` 被绕过 reset 后重新污染。

## Test Plan
- TCP：
  - warm pool 命中、pool 空时创建/降级、坏 channel 自动淘汰
  - fake-endpoint / `SocksRpcContract.fakeEndpoint` 在快慢路径一致
  - `TransportFlags.COMPRESS_BOTH` 路径下 `SocksContext.markCtx`、front/backpressure、relay 正常
- UDP 正常链路：
  - 场景2：ProxyA -> ProxyB chained UDP，连续两个不同客户端顺序复用同一个 remote relay lease，且访问相同 destination，必须重新触发 B 侧 routing，不得沿用旧 `routeMap`
  - 场景2：第一个 borrower 关闭后向旧 relay 注入迟到包，第二个 borrower 已 claim 的情况下不得串流
- UDP udp2raw：
  - 现有 `socks5UdpRelay_udp2raw_chained_e2e` 保持通过
  - 新增 sequential reuse 用例，确认 `prepareUdpRelay` 对 `Udp2rawHandler` 同样生效
- 场景4：
  - `Shadowsocks -> Socks A -> Socks B -> UDP dest` 回归通过
  - 有 facade 时可复用 lease；把 facade 置空时自动回退慢路径，功能不变
- Server-side registry：
  - relay close 后 `prepareUdpRelay(relayPort, ...)` 返回 `false`
  - lock 打开后错误 sender 会被丢弃，不会更新 `ATTR_CLIENT_ADDR`

## Assumptions / Defaults
- UDP 池化按你刚确认的选择执行为“RPC 可选”：
  有 `UpstreamSupport.facade` 时启用安全复用；无 facade 不报错，只回退现有逐次握手。
- UDP recycle 不走额外 RPC；远端状态只在下一次 borrow 时通过 `prepareUdpRelay(...)` 清理并重绑定。
- 本版不做“跨 endpoint 共享 UDP lease”，pool key 仍按 endpoint + transport config 分池。
- 所有新池化配置默认关闭；只有显式开启时才改变行为。
