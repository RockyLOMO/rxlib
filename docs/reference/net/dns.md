# DNS 模块 (org.rx.net.dns)

提供基于 Netty 的非阻塞 DNS 解析能力，包含 DNS 客户端及本地 DNS 代理服务器功能。这在高性能网络中用于替换 Java 默认的阻塞式 `InetAddress.getByName` 调用。

## 核心类介绍

- **`DnsClient`**:
  高性能非阻塞 DNS 客户端，基于 Netty 提供的 `DnsNameResolver` 构建。支持自定义上游 DNS 服务器配置，并共用 hosts 与 interceptor 逻辑。

- **`DnsServer`**:
  基于 Netty `DatagramChannel` 构建的 DNS 服务器端。可以接收本地发出的 UDP DNS 查询请求并进行代理转发、本地缓存或劫持拦截。

- **`DnsHandler`**:
  `DnsServer` 中的 Netty 管道处理器，负责处理 DNS 解析的上下游路由逻辑，将 DNS 报文解码并分发到真正的解析流程中。

- **`DnsMessageUtil`**:
  辅助构建和解析 DNS 报文（`DnsQuery`, `DnsResponse`）的工具类。

## 核心特点

| 特性 | 说明 |
|------|------|
| **双协议支持** | 同时监听 TCP/UDP，使用 Netty 内置编解码器 `TcpDnsQueryDecoder` / `DatagramDnsQueryDecoder` |
| **hosts 权重负载** | 支持 `enableHostsWeight` 模式，相同域名多 IP 时按权重分配，返回 1-2 个 IP |
| **解析拦截器** | `DnsResolveInterceptor` 接口支持自定义解析逻辑，可对接外部服务（如服务发现） |
| **防缓存击穿** | `resolvingPromises` 按域名与记录类型合并并发解析，防止缓存穿透（thundering-herd） |
| **上游响应缓存** | `DnsServer` 对 raw upstream DNS query 增加 response cache，支持 fresh 命中、serve-expired 与 prefetch |
| **H2 持久缓存** | 使用 `H2StoreCache` 作为 DNS 缓存后端，支持 TTL 和跨进程共享 |

## 本地解析与缓存配置

`DnsClient` 与 `DnsServer` 共用 hosts、interceptor、熔断和 interceptor cache 逻辑。解析顺序为：

1. hosts 本地命中直接返回。
2. interceptor cache 命中直接返回。
3. interceptor 未命中时异步解析，并按域名/记录类型合并并发请求。
4. interceptor 返回 `null` 时继续走上游 DNS。

`DnsServer -> queryUpstream -> upstream.query(question)` 路径额外有 response cache：

1. cache key 使用标准化域名、记录类型和 record class。
2. fresh 命中时用缓存快照重新构造 `DnsResponse`，不会复用 Netty `ByteBuf`。
3. `serveExpired=true` 且存在 stale entry 时，上游失败或超过 `serveExpiredClientTimeoutMillis` 会返回 stale response。
4. `prefetch=true` 时，fresh 命中进入 TTL 后段会触发单 key 后台刷新，当前请求仍立即返回缓存值。
5. 当前只缓存可深拷贝的 `DnsRawRecord`；遇到 OPT/EDNS 等非 raw record 时保守跳过整个 response cache。
6. `DnsClient.resolve/resolveAll` 目前不使用 rxlib response cache；它们仍由 Netty `DnsNameResolver` 自身处理 resolve cache。

系统属性配置：

```properties
app.net.dns.cacheEnabled=false
app.net.dns.prefetch=false
app.net.dns.prefetchThresholdPercent=10
app.net.dns.serveExpired=false
app.net.dns.serveExpiredTtlSeconds=86400
app.net.dns.serveExpiredReplyTtlSeconds=30
app.net.dns.serveExpiredClientTimeoutMillis=1800

# memory / persistent / hybrid，默认 hybrid
app.net.dns.cacheStorage=hybrid

# memory 模式 maximumBytes>0 时按轻量估算字节限流，否则按 item 数限流。
# persistent/hybrid 模式复用 EntityDatabaseImpl + H2StoreCache，maximumSize 控制 L1 item 数。
app.net.dns.cacheMaximumSize=4096
app.net.dns.cacheMaximumBytes=0
```

## 适用场景
- 在代理服务器或高性能爬虫等需要大量 DNS 解析的场景中，防止 DNS 解析阻塞 Netty 线程池。
- 构建本地防污染 DNS 缓存代理服务器。
- 本地开发 DNS 劫持/劫持测试。
- 配合 SOCKS5 实现智能 DNS 路由。
