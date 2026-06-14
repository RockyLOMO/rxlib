# rss-svr DNS cache 运行参数建议与未实现项

创建日期：2026-06-14

## 背景

目标环境：`deploy/rss-svr/start.sh` 对应的 RSS server，约 2 core / 1.5G RAM。

当前启动脚本 JVM 内存参数：

```bash
-Xms256m -Xmx256m
-XX:MaxMetaspaceSize=96m
-XX:ReservedCodeCacheSize=96m
-XX:MaxDirectMemorySize=640m
```

DNS 上游当前在 `APP_OPTIONS` 中设置为：

```bash
-Dapp.net.dns.remoteServers=127.0.0.1:53,1.1.1.1:53
```

由于 heap 只有 256m，DNS memory cache 应尽可能小，优先保证进程稳定，不追求大缓存命中率。

## 最新实现观察

截至当前 `master`，DNS cache 配置项已经存在：

```properties
app.net.dns.prefetch
app.net.dns.prefetchThresholdPercent
app.net.dns.serveExpired
app.net.dns.serveExpiredTtlSeconds
app.net.dns.serveExpiredReplyTtlSeconds
app.net.dns.serveExpiredClientTimeoutMillis
app.net.dns.cacheStorage
app.net.dns.cacheMaximumSize
app.net.dns.cacheMaximumBytes
```

`RxConfig.DnsCacheConfig` 默认值：

```java
prefetch = false
prefetchThresholdPercent = 10
serveExpired = false
serveExpiredTtlSeconds = 86400
serveExpiredReplyTtlSeconds = 30
serveExpiredClientTimeoutMillis = 1800
storage = HYBRID
maximumSize = 4096
maximumBytes = 0
```

当前真正接入 cache 创建逻辑的是：

- `cache.storage`
- `cache.maximumSize`
- `cache.maximumBytes`

接入位置主要是 `DnsResolverSupport.newInterceptorCache()`，用于 interceptor/local resolve cache。

`prefetch` 与 `serveExpired` 配置目前已经被读取和 normalize，但从代码路径看，尚未真正接入：

- 没有看到 prefetch 命中后后台刷新逻辑。
- 没有看到 serve-expired 在上游失败或超时时返回 stale cache 的逻辑。
- `DnsResolveCore.queryUpstream(...)` 仍然直接调用 `upstream.query(question)`；失败时返回 `SERVFAIL`。

因此当前阶段配置这些开关，更多是为后续实现预留；实际对 upstream DNS 转发的 prefetch / stale fallback 收益还没有生效。

## 推荐运行参数

### 建议默认配置

针对 2C / 1.5G RAM / 256m heap 的 rss-svr，建议先使用极小内存 cache：

```bash
DNS_CACHE_PREFETCH=${DNS_CACHE_PREFETCH:-false}
DNS_CACHE_SERVE_EXPIRED=${DNS_CACHE_SERVE_EXPIRED:-true}
DNS_CACHE_STORAGE=${DNS_CACHE_STORAGE:-MEMORY}
DNS_CACHE_MAXIMUM_SIZE=${DNS_CACHE_MAXIMUM_SIZE:-256}
DNS_CACHE_MAXIMUM_BYTES=${DNS_CACHE_MAXIMUM_BYTES:-65536}
DNS_CACHE_SERVE_EXPIRED_TTL_SECONDS=${DNS_CACHE_SERVE_EXPIRED_TTL_SECONDS:-3600}
DNS_CACHE_SERVE_EXPIRED_REPLY_TTL_SECONDS=${DNS_CACHE_SERVE_EXPIRED_REPLY_TTL_SECONDS:-15}
DNS_CACHE_SERVE_EXPIRED_CLIENT_TIMEOUT_MILLIS=${DNS_CACHE_SERVE_EXPIRED_CLIENT_TIMEOUT_MILLIS:-300}
DNS_CACHE_PREFETCH_THRESHOLD_PERCENT=${DNS_CACHE_PREFETCH_THRESHOLD_PERCENT:-10}
```

追加到 `APP_OPTIONS`：

```bash
-Dapp.net.dns.prefetch=${DNS_CACHE_PREFETCH} \
-Dapp.net.dns.serveExpired=${DNS_CACHE_SERVE_EXPIRED} \
-Dapp.net.dns.cacheStorage=${DNS_CACHE_STORAGE} \
-Dapp.net.dns.cacheMaximumSize=${DNS_CACHE_MAXIMUM_SIZE} \
-Dapp.net.dns.cacheMaximumBytes=${DNS_CACHE_MAXIMUM_BYTES} \
-Dapp.net.dns.serveExpiredTtlSeconds=${DNS_CACHE_SERVE_EXPIRED_TTL_SECONDS} \
-Dapp.net.dns.serveExpiredReplyTtlSeconds=${DNS_CACHE_SERVE_EXPIRED_REPLY_TTL_SECONDS} \
-Dapp.net.dns.serveExpiredClientTimeoutMillis=${DNS_CACHE_SERVE_EXPIRED_CLIENT_TIMEOUT_MILLIS} \
-Dapp.net.dns.prefetchThresholdPercent=${DNS_CACHE_PREFETCH_THRESHOLD_PERCENT}
```

### 更保守配置

如果线上 heap 压力明显，或者 DNS interceptor 域名数量很少，可以缩到：

```bash
DNS_CACHE_STORAGE=MEMORY
DNS_CACHE_MAXIMUM_SIZE=128
DNS_CACHE_MAXIMUM_BYTES=32768
```

### 不建议配置

不建议在 rss-svr 小内存节点使用：

```bash
DNS_CACHE_STORAGE=HYBRID
```

原因：当前 `HYBRID` 会使用 `H2StoreCache`，适合更大缓存或需要持久化的场景；rss-svr 这种小节点 DNS cache 只需要轻量内存缓存即可，避免额外 H2/持久化开销。

也不建议当前打开：

```bash
DNS_CACHE_PREFETCH=true
```

原因：

1. 当前还没有看到 prefetch 实际刷新逻辑接入。
2. 后续实现后，prefetch 会增加额外 DNS 请求，对 2C 小机器收益有限。
3. rss-svr 更需要低抖动和低内存，而不是更激进的热点刷新。

`DNS_CACHE_SERVE_EXPIRED=true` 可以先放入启动参数：

- 当前实现阶段基本无实际效果，但不会明显增加开销。
- 后续 serve-expired 接入后，可以直接获得上游 DNS 故障时的可用性提升。

## 参数解释

### DNS_CACHE_STORAGE

推荐：`MEMORY`

可选值：

- `MEMORY`：只用内存，最适合 rss-svr 小节点。
- `PERSISTENT`：持久化，当前不建议。
- `HYBRID`：H2 混合缓存，默认值，但当前不适合小内存运行参数。

### DNS_CACHE_MAXIMUM_SIZE

推荐：`256`

含义：最多缓存多少个 DNS/interceptor 结果 key。

对小内存节点建议：

- 普通：`256`
- 极限保守：`128`
- 不建议超过：`1024`

### DNS_CACHE_MAXIMUM_BYTES

推荐：`65536`，即 64 KiB。

含义：对 memory cache 使用按估算字节数的上限。当前实现中若 `maximumBytes > 0`，会优先使用 weighted cache。

建议：

- 普通：`65536`
- 极限保守：`32768`
- 内存相对宽裕：`131072`

### DNS_CACHE_SERVE_EXPIRED_TTL_SECONDS

推荐：`3600`

默认值是 `86400`，但小节点不建议 stale window 太长。1 小时足够覆盖短时间上游 DNS 抖动。

### DNS_CACHE_SERVE_EXPIRED_REPLY_TTL_SECONDS

推荐：`15`

后续真正实现 serve-expired 后，返回 stale 记录时给客户端的 TTL 不宜过长，避免客户端长期持有过期结果。

### DNS_CACHE_SERVE_EXPIRED_CLIENT_TIMEOUT_MILLIS

推荐：`300`

后续真正实现 serve-expired 后，有 stale 记录时最多等上游 300ms；如果上游没有及时返回，就先返回 stale，避免 DNS 阻塞连接建立。

### DNS_CACHE_PREFETCH_THRESHOLD_PERCENT

推荐：`10`

保留 Unbound 的默认语义：缓存剩余 TTL 进入最后 10% 时才考虑预刷新。但当前建议 `DNS_CACHE_PREFETCH=false`。

## Netty DNS cache 是否重复

Netty `DnsNameResolver` 本身有 DNS resolve cache。

当前 `DnsClient` 已配置：

```java
.ttl(5, 300)
.negativeTtl(DnsServer.DEFAULT_NEGATIVE_TTL)
```

`DnsClient.clearCache()` 也会调用：

```java
nameResolver.resolveCache().clear();
```

需要区分两条路径：

### 1. DnsClient.resolve / resolveAll

这条路径会使用 Netty `DnsNameResolver` 的 resolve cache。

```text
DnsClient.resolveAsync / resolveAllAsync
  -> nameResolver.resolve / resolveAll
  -> Netty resolve cache
```

### 2. DnsServer upstream DNS 转发

这条路径当前走的是 raw DNS query：

```text
DnsServer
  -> DnsResolveCore.queryUpstream(...)
  -> upstream.query(question)
  -> nameResolver.query(question)
```

这类 `query(DnsQuestion)` 不等同于 `resolve/resolveAll`，不能指望它复用 Netty 的 resolve cache。

因此：

- Netty cache 与 rxlib interceptor cache 不完全重复。
- Netty cache 主要覆盖 `DnsClient.resolve*`。
- rss-svr 作为 DNS server 转发时，普通 upstream query 当前仍需要 rxlib 自己实现 message/cache/stale 逻辑。
- 当前新增的 rxlib cache 配置主要影响 interceptor/local resolve cache，不等于完整 upstream DNS response cache。

## 当前未实现 / 待补齐项

### 1. upstream response cache

需要在 `DnsResolveCore.queryUpstream(...)` 外层增加 response cache：

```text
cache key = normalized domain + record type + record class
```

命中 fresh cache 时直接返回缓存构造的新 response。

注意不能直接缓存 Netty `DnsResponse` / `DefaultDnsRawRecord` / `ByteBuf`，必须深拷贝 record 内容，出 cache 时重新创建 response，避免引用计数问题。

### 2. serve-expired 真正生效

需要实现：

```text
upstream 成功：更新 cache，返回 fresh
upstream 失败：如果存在 stale 且 serveExpired=true，返回 stale
upstream 超时：如果存在 stale 且 serveExpired=true，返回 stale
```

推荐逻辑：

- `serveExpiredClientTimeoutMillis == 0`：有 stale 就立即返回 stale，同时后台 refresh。
- `serveExpiredClientTimeoutMillis > 0`：先等上游；超时或失败后返回 stale。
- stale response TTL 使用 `serveExpiredReplyTtlSeconds`。

### 3. prefetch 真正生效

需要实现：

```text
fresh cache hit
  -> 如果剩余 TTL <= 原始 TTL * prefetchThresholdPercent / 100
  -> 后台 refresh
  -> 当前请求仍立即返回缓存结果
```

要求：

- 同一个 cache key 同一时刻只允许一个后台 refresh。
- refresh 失败不影响当前缓存。
- prefetch 默认关闭，rss-svr 小机器继续保持关闭。

### 4. DnsClient resolve cache 与 Netty cache 的边界

如果未来想让 `serveExpired` 也覆盖 `DnsClient.resolve*`，需要在 Netty resolve cache 外再包一层 rxlib cache。

但要避免双层 cache TTL 混乱。建议策略：

- `DnsClient.resolve*`：可以继续依赖 Netty cache，必要时再加 rxlib stale cache。
- `DnsServer.queryUpstream`：优先实现 rxlib 自己的 DNS response cache，因为 Netty `query` 路径不等同于 resolve cache。

### 5. start.sh 参数接入

建议后续把本文推荐参数正式加入 `deploy/rss-svr/start.sh`：

```bash
DNS_CACHE_PREFETCH=${DNS_CACHE_PREFETCH:-false}
DNS_CACHE_SERVE_EXPIRED=${DNS_CACHE_SERVE_EXPIRED:-true}
DNS_CACHE_STORAGE=${DNS_CACHE_STORAGE:-MEMORY}
DNS_CACHE_MAXIMUM_SIZE=${DNS_CACHE_MAXIMUM_SIZE:-256}
DNS_CACHE_MAXIMUM_BYTES=${DNS_CACHE_MAXIMUM_BYTES:-65536}
DNS_CACHE_SERVE_EXPIRED_TTL_SECONDS=${DNS_CACHE_SERVE_EXPIRED_TTL_SECONDS:-3600}
DNS_CACHE_SERVE_EXPIRED_REPLY_TTL_SECONDS=${DNS_CACHE_SERVE_EXPIRED_REPLY_TTL_SECONDS:-15}
DNS_CACHE_SERVE_EXPIRED_CLIENT_TIMEOUT_MILLIS=${DNS_CACHE_SERVE_EXPIRED_CLIENT_TIMEOUT_MILLIS:-300}
DNS_CACHE_PREFETCH_THRESHOLD_PERCENT=${DNS_CACHE_PREFETCH_THRESHOLD_PERCENT:-10}
```

并追加到 `APP_OPTIONS`。

## 建议落地顺序

1. 先合入 `start.sh` 参数，默认使用小内存 MEMORY cache。
2. 实现 upstream response cache。
3. 实现 serve-expired fallback。
4. 增加日志与计数：fresh hit / stale hit / miss / upstream fail served expired。
5. 再评估是否为非 rss-svr 场景打开 prefetch。

## 最终建议

当前 rss-svr 推荐：

```bash
DNS_CACHE_PREFETCH=false
DNS_CACHE_SERVE_EXPIRED=true
DNS_CACHE_STORAGE=MEMORY
DNS_CACHE_MAXIMUM_SIZE=256
DNS_CACHE_MAXIMUM_BYTES=65536
DNS_CACHE_SERVE_EXPIRED_TTL_SECONDS=3600
DNS_CACHE_SERVE_EXPIRED_REPLY_TTL_SECONDS=15
DNS_CACHE_SERVE_EXPIRED_CLIENT_TIMEOUT_MILLIS=300
DNS_CACHE_PREFETCH_THRESHOLD_PERCENT=10
```

其中真正影响当前实现内存大小的是：

```bash
DNS_CACHE_STORAGE=MEMORY
DNS_CACHE_MAXIMUM_SIZE=256
DNS_CACHE_MAXIMUM_BYTES=65536
```

`DNS_CACHE_PREFETCH` 当前建议保持关闭；`DNS_CACHE_SERVE_EXPIRED` 可以先打开作为后续实现预留，但当前 upstream 转发路径还没有 stale fallback，需要后续补齐。
