# rxlib DNS cache prefetch / serve-expired 计划

创建日期：2026-06-13

## 背景

`rxlib` 当前 DNS 相关核心类：

- `org.rx.net.dns.DnsClient`
  - 基于 Netty `DnsNameResolver`。
  - 当前构造器里配置了 `.ttl(5, 300)`、`.negativeTtl(DnsServer.DEFAULT_NEGATIVE_TTL)`、`queryTimeoutMillis(5s)` 等解析行为。
  - 暴露 `query(DnsQuestion)`、`resolveAsync(String)`、`resolveAllAsync(String)`、同步 `resolve/resolveAll` 和 `clearCache()`。
- `org.rx.net.dns.DnsServer`
  - 负责 UDP/TCP DNS 服务监听。
  - 内部持有 `upstreamClient`，并通过 `DnsResolveCore.resolve(...)` 处理 hosts、interceptor、fake host、upstream 转发。
  - 已有 `ttl`、`hostsTtl`、`negativeTtl`、interceptor cache、请求合并 `resolvingPromises`、interceptor 熔断等基础设施。
- `org.rx.core.RxConfig.DnsConfig`
  - 目前已有 `directServers`、`remoteServers`、`localSystemFallback`、DoH 相关配置。

目标是在 `DnsClient` 与 `DnsServer` 之间增加一套公共 DNS 缓存增强能力，类似 Unbound：

- `prefetch`：热门域名缓存命中且快过期时，后台提前刷新。
- `serve-expired`：上游失败或超时时，允许继续返回过期缓存。

官方参考：

- Unbound `prefetch`：缓存命中且记录处于 TTL 最后 10% 时触发后台刷新。
- Unbound `serve-expired`：上游解析失败或超过 client timeout 时返回过期缓存，并用较小 TTL 响应客户端。

## 目标

1. 把 `prefetch` 与 `serve-expired` 做成公共配置项，默认关闭，配置后自动对 `DnsClient` 与 `DnsServer` 生效。
2. 保持当前 API 尽量兼容，不改变默认行为。
3. 不依赖 Netty `DnsNameResolver` 内部 cache 来实现 stale/prefetch，因为其 cache 不暴露足够的过期元数据与过期后读取能力。
4. 支持 A/AAAA/NXDOMAIN 的 MVP，后续再扩展到完整 RRSet/message cache。
5. 避免热点域名并发刷新风暴，复用当前请求合并思路。
6. 保证 Netty `ByteBuf` / DNS record 引用计数安全，不把上游响应对象直接长期放入 cache。

## 非目标

本次不实现完整递归解析器能力：

- 不实现 DNSSEC 验证。
- 不实现 QNAME minimization。
- 不实现 root hints 递归解析。
- 不实现 Unbound 全部 module 体系。

`rxlib` 当前更接近 DNS forwarder / proxy / local override，而不是完整 recursive resolver；上述能力需要另一套解析链路和验证器，不适合作为本次缓存增强的一部分。

## 公共配置设计

建议在 `RxConfig.ConfigNames` 与 `RxConfig.DnsConfig` 增加以下配置。

### 主开关

```properties
app.net.dns.prefetch=false
app.net.dns.serveExpired=false
```

### 支撑参数

```properties
# 命中缓存时，剩余 TTL 小于等于原始 TTL 的该比例即触发后台刷新。Unbound 默认语义是最后 10%。
app.net.dns.prefetchThresholdPercent=10

# 过期后仍可服务的最大时间。参考 Unbound/RFC 8767 推荐区间，可默认 86400 秒。
app.net.dns.serveExpiredTtlSeconds=86400

# 返回过期记录时下发给客户端的 TTL。Unbound 默认 30 秒。
app.net.dns.serveExpiredReplyTtlSeconds=30

# serve-expired 场景下等待上游多久，超时后返回 stale。
# 0 表示有 stale 时立即返回 stale，同时后台刷新。
app.net.dns.serveExpiredClientTimeoutMillis=1800

# 可选：限制自有 DNS cache 大小，避免无限增长。
app.net.dns.cacheMaximumSize=4096
```

### Java 配置对象

建议新增独立配置对象，避免继续把 `DnsConfig` 摊平：

```java
@Getter
@Setter
@ToString
public static class DnsCacheConfig {
    boolean prefetch;
    int prefetchThresholdPercent = 10;
    boolean serveExpired;
    int serveExpiredTtlSeconds = 86400;
    int serveExpiredReplyTtlSeconds = 30;
    int serveExpiredClientTimeoutMillis = 1800;
    int maximumSize = 4096;
}

public static class DnsConfig {
    ...
    DnsCacheConfig cache = new DnsCacheConfig();
}
```

对应系统属性名：

```java
String DNS_CACHE_PREFETCH = "app.net.dns.prefetch";
String DNS_CACHE_PREFETCH_THRESHOLD_PERCENT = "app.net.dns.prefetchThresholdPercent";
String DNS_CACHE_SERVE_EXPIRED = "app.net.dns.serveExpired";
String DNS_CACHE_SERVE_EXPIRED_TTL_SECONDS = "app.net.dns.serveExpiredTtlSeconds";
String DNS_CACHE_SERVE_EXPIRED_REPLY_TTL_SECONDS = "app.net.dns.serveExpiredReplyTtlSeconds";
String DNS_CACHE_SERVE_EXPIRED_CLIENT_TIMEOUT_MILLIS = "app.net.dns.serveExpiredClientTimeoutMillis";
String DNS_CACHE_MAXIMUM_SIZE = "app.net.dns.cacheMaximumSize";
```

`refreshFromSystemProperty()` 需要读取这些字段，并在 `afterSet()` 或 `DnsCacheConfig.normalize()` 里做边界校验：

- `prefetchThresholdPercent` 限制在 `1..100`。
- `serveExpiredTtlSeconds >= 0`，`0` 表示不过期限制或禁用 stale window 需明确选一种语义；建议与 Unbound 一致，`0` 表示不限制 stale window，但只有 `serveExpired=true` 时有效。
- `serveExpiredReplyTtlSeconds >= 1`。
- `serveExpiredClientTimeoutMillis >= 0`。
- `maximumSize >= 1`。

## 核心实现设计

### 1. 新增公共缓存组件

建议新增：

```text
rxlib/src/main/java/org/rx/net/dns/DnsCacheOptions.java
rxlib/src/main/java/org/rx/net/dns/DnsCacheKey.java
rxlib/src/main/java/org/rx/net/dns/DnsCacheEntry.java
rxlib/src/main/java/org/rx/net/dns/DnsCacheFacade.java
rxlib/src/main/java/org/rx/net/dns/CachedDnsRecord.java
```

职责：

- `DnsCacheOptions`
  - 从 `RxConfig.INSTANCE.getNet().getDns().getCache()` 构建不可变运行时配置。
  - 提供 `isEnabled()`、`isPrefetchEnabled()`、`isServeExpiredEnabled()` 等判断。
- `DnsCacheKey`
  - 维度至少包含：normalized domain、record type、record class。
  - Server message cache 后续可增加 DO/CD/EDNS 等维度。
- `DnsCacheEntry`
  - 保存 immutable DNS 结果，不保存 Netty 原始 `DnsResponse`。
  - 字段建议：`rcode`、`records`、`createdAtMillis`、`expiresAtMillis`、`staleUntilMillis`、`originalTtlSeconds`、`negative`。
  - 方法建议：`isFresh(now)`、`isStaleUsable(now, options)`、`remainingTtl(now)`、`staleReplyTtl(options)`、`shouldPrefetch(now, options)`。
- `CachedDnsRecord`
  - 保存 record name/type/class/content bytes/original ttl。
  - 每次响应时重新创建 `DefaultDnsRawRecord`，避免长期保存 ref-counted `ByteBuf`。
- `DnsCacheFacade`
  - 提供 get/put/evict/clear/markRefreshing/finishRefreshing。
  - 支持每个 key 同一时刻只有一个 refresh。
  - 可先用 `MemoryCache` 或 Caffeine 风格 maximumSize；若使用 rxlib `Cache`，需要确保 TTL 设置为 `ttl + serveExpiredTtl`，entry 内部再判断 fresh/stale。

### 2. DnsClient 接入点

`DnsClient` 目前依赖 Netty `DnsNameResolver` 的内部 cache。为了让公共功能对 `resolve/resolveAll` 生效，需要在外层加自有 cache：

- `resolveAllAsync(String inetHost)`：
  1. 生成 `DnsCacheKey(host, A/AAAA or addressTypes)`。
  2. 命中 fresh：立即返回 cached future；如果满足 prefetch 阈值，后台调用 `nameResolver.resolveAll()` 更新 cache。
  3. 命中 stale 且 `serveExpired=true`：
     - `serveExpiredClientTimeoutMillis == 0`：立即返回 stale，后台 refresh。
     - `>0`：先发起 upstream resolve；若 upstream 失败或 timeout，则返回 stale；若 upstream 先成功，则返回 fresh。
  4. 未命中：走原始 resolver，成功后写入 cache。
- `resolveAsync(String inetHost)`：可以基于 `resolveAllAsync` 取第一个结果，或单独缓存单 IP。
- `query(DnsQuestion)`：MVP 可先不缓存任意 query，只把 `DnsServer` upstream path 纳入 full response cache；后续再让 `DnsClient.query` 支持 raw DNS response cache。
- `clearCache()`：同时清理 Netty resolver cache 与新 `DnsCacheFacade`。

注意：`DnsClient` 当前 `.ttl(5, 300)` 会把 TTL 限定在 5 到 300 秒。若新增自有 cache，应该统一使用同一 TTL 策略，避免 Netty cache 和外层 cache 语义冲突。MVP 可继续让 Netty resolver 做下层 cache，但外层 cache 以实际响应 TTL 或配置 TTL 作为准。

### 3. DnsServer 接入点

`DnsServer` 处理上游查询的路径在 `DnsResolveCore.queryUpstream(...)`，建议替换为：

```text
queryUpstreamWithCache(server, upstream, query, isTcp, promise)
```

处理逻辑：

1. 根据 question 构造 `DnsCacheKey`。
2. fresh hit：
   - 构造新 response，写入剩余 TTL。
   - 若 `shouldPrefetch`，后台 refresh。
3. stale hit + serveExpired：
   - `clientTimeout == 0`：立即用 stale TTL 返回，并后台 refresh。
   - `clientTimeout > 0`：发起 upstream 查询，同时设置定时 fallback；upstream 成功则返回 fresh 并更新 cache；upstream 失败或 timeout 则返回 stale。
4. miss：
   - 正常上游查询；成功后 cache positive/NXDOMAIN/NODATA。
5. 上游成功但响应码异常：
   - SERVFAIL 不建议写入普通 cache。
   - NXDOMAIN/NODATA 可按 negative TTL 缓存。

需要新增辅助方法：

- `DnsMessageUtil.toCacheEntry(DnsResponse response, DnsQuestion question, DnsCacheOptions options)`
- `DnsMessageUtil.newResponseFromCache(DefaultDnsQuery query, boolean isTcp, DnsCacheEntry entry, long now, boolean stale)`
- `DnsMessageUtil.copyRecordWithTtl(DnsRecord record, long ttl)`

### 4. 与 interceptor cache 的关系

当前 interceptor cache 已按 A/AAAA 分开缓存，并使用 `server.ttl` / `server.negativeTtl`。

MVP 建议：

- 先把公共 cache 用于 upstream 结果和 `DnsClient.resolve*`。
- interceptor cache 可在第二阶段迁移到 `DnsCacheFacade`，以获得统一 prefetch/serve-expired。
- 原因：interceptor 可能代表自定义解析源，不一定适合自动 serve stale；但现有代码已有 interceptor 熔断与 fallback，上游 cache 可以先解决公共需求。

第二阶段迁移时：

- `ResolveInterceptor.resolveHost` 成功后写入 `DnsCacheFacade`。
- prefetch refresh 调用 interceptor；失败时按照 interceptor breaker 逻辑处理。
- stale 返回时需要区分 `SHADOW_STALE` 日志。

### 5. 并发与引用计数

重点风险：

- 不能把 `AddressedEnvelope<DnsResponse>` 或 `DefaultDnsResponse` 直接塞进 cache 后跨请求复用。
- 不能保存带 refCnt 生命周期的 `DefaultDnsRawRecord` content。

方案：

- 入 cache 时深拷贝 DNS record 的 byte[]。
- 出 cache 时创建新的 `DefaultDnsRawRecord` / `DefaultDnsResponse`。
- refresh 状态使用 `ConcurrentHashMap<DnsCacheKey, Promise/DnsCacheRefreshState>`，与现有 `resolvingPromises` 思路一致。
- prefetch 是 best-effort：已有 refresh 时不重复发起。

### 6. 日志与可观测性

建议新增 debug/info 级别日志：

- `CACHE_HIT`
- `CACHE_STALE_HIT`
- `CACHE_MISS`
- `PREFETCH_START`
- `PREFETCH_SUCCESS`
- `PREFETCH_FAIL_KEEP_OLD`
- `SERVE_EXPIRED_UPSTREAM_FAIL`
- `SERVE_EXPIRED_TIMEOUT`

可选新增计数器接口：

- freshHit
- staleHit
- miss
- prefetchStarted
- prefetchSuccess
- prefetchFailure
- upstreamFailureServedExpired

## 其他 Unbound 特性评估

结合 `rxlib` 当前架构，建议分为三类。

### 建议后续实现

1. `cache-min-ttl` / `cache-max-ttl`
   - 当前 `DnsClient` 已在 Netty resolver 上硬编码 `.ttl(5, 300)`。
   - 新增自有 cache 后应该把 min/max TTL 配成公共项。

2. `deny-any`
   - 拒绝 ANY 查询，减少放大攻击风险。
   - 可在 `DnsResolveCore.resolve(...)` 早期判断 `DnsRecordType.ANY` 并返回空响应或 REFUSED。

3. `rrset-roundrobin`
   - 对多 A/AAAA 记录轮转顺序，降低客户端粘住第一个 IP。
   - `hosts` 已有 weighted 行为，上游响应也可以做 response record shuffle/rotate。

4. DNS rebinding protection / private-address 保护
   - 对公网域名返回内网地址时拦截或过滤。
   - 对家庭网关、代理型 DNS 很有价值。

5. local-zone / local-data 增强
   - 当前已有 `addHosts`、`addHostsFile`。
   - 可扩展通配符域名、NXDOMAIN zone、redirect zone、transparent zone。

6. access-control / view
   - 当前 `DnsHandler` 已能拿到 `srcIp`。
   - 可基于来源 IP 做 allow/deny/refuse，或不同来源使用不同 upstream/interceptor/cache 策略。

7. RPZ-like policy
   - 可做黑名单、广告域名过滤、重定向、NXDOMAIN 策略。
   - 可以复用 interceptor 或新增 policy layer。

8. metrics / cache stats
   - 对调试 prefetch/serve-expired 很有价值。

### 已部分具备

1. local hosts
   - `DnsServer.addHosts`、`addHostsFile` 已具备基础 hosts 覆盖。

2. DoH server
   - `DnsServer.enableDoH(DnsDoHConfig)` 已存在。

3. upstream forward
   - `DnsClient.directNameServers()`、`remoteNameServers()`、`nameServerProvider(...)` 已支持配置上游。

4. negative TTL
   - `DnsServer.DEFAULT_NEGATIVE_TTL` 与 `DnsClient.negativeTtl(...)` 已存在。

5. 请求合并
   - `DnsServer.resolvingPromises` 已能避免 interceptor 场景下同域名重复解析。

### 暂不建议实现

1. DNSSEC validation
   - 需要完整验证链、trust anchor、RRSIG/DS/DNSKEY 验证。
   - 目前 Netty resolver + forwarder 架构不适合直接补一个轻量实现。

2. QNAME minimization
   - 这是 recursive resolver 向权威服务器递归查询时的隐私优化。
   - rxlib 当前主要把请求交给 upstream，不直接从 root/TLD/authoritative 递归。

3. `prefetch-key`
   - 依赖 DNSSEC validation 流程；没有 DNSSEC 验证时收益很小。

4. DNSCrypt / DoQ / full DoT
   - 属于加密传输能力，可另开专题。
   - 当前已有 DoH server，优先级低于缓存稳定性。

5. Redis cachedb / distributed cache
   - 可等本地 cache 语义稳定后再考虑。

## 实施步骤

### 第 1 步：配置与数据结构

- 修改 `RxConfig.ConfigNames`。
- 新增 `DnsCacheConfig`。
- 在 `refreshFromSystemProperty()` 读取新配置。
- 新增 `DnsCacheOptions`、`DnsCacheKey`、`DnsCacheEntry`、`CachedDnsRecord`、`DnsCacheFacade`。

### 第 2 步：DnsMessageUtil cache 编解码

- 从上游 `DnsResponse` 提取可缓存 records。
- 计算 positive / negative TTL。
- 从 cache entry 生成新的 DNS response。
- 支持 fresh TTL 递减与 stale reply TTL。

### 第 3 步：DnsServer upstream cache

- 改造 `DnsResolveCore.queryUpstream(...)`。
- 加入 miss/fresh/stale/prefetch 分支。
- 保持当前 `query.retain()/release()` 生命周期不破坏。
- 增加日志与异常保护。

### 第 4 步：DnsClient resolve cache

- 在 `DnsClient` 增加 `DnsCacheFacade` 字段。
- 改造 `resolveAsync/resolveAllAsync/resolve/resolveAll`。
- `clearCache()` 同时清理 Netty 与公共 cache。

### 第 5 步：测试

新增或扩展测试：

- `DnsCacheEntryTest`
  - TTL 计算、stale window、prefetch 阈值。
- `DnsServerServeExpiredTest`
  - 上游成功后缓存。
  - 上游失败时返回 stale。
  - client timeout 触发 stale。
- `DnsServerPrefetchTest`
  - fresh hit 不触发刷新。
  - TTL 最后 10% 命中触发一次后台刷新。
  - 并发命中只触发一个 refresh。
- `DnsClientCacheTest`
  - `resolveAllAsync` fresh hit/stale hit/miss。
  - `clearCache()` 清理公共 cache。
- `DnsMessageUtilCacheTest`
  - cache entry 出入转换不复用 ref-counted 对象。

### 第 6 步：文档

更新：

- `docs/reference/net/dns.md`
- 配置示例：

```yaml
app:
  net:
    dns:
      prefetch: true
      prefetchThresholdPercent: 10
      serveExpired: true
      serveExpiredTtlSeconds: 86400
      serveExpiredReplyTtlSeconds: 30
      serveExpiredClientTimeoutMillis: 1800
      cacheMaximumSize: 4096
```

## 验收标准

1. 默认配置下行为与当前版本一致。
2. 开启 `prefetch=true` 后，热门域名在 TTL 后 10% 命中时后台刷新，客户端仍立即得到当前缓存结果。
3. 开启 `serveExpired=true` 后，上游失败或超时但存在 stale entry 时，客户端能得到过期结果，响应 TTL 使用 `serveExpiredReplyTtlSeconds` 上限。
4. 同一 cache key 不会并发发起多个 prefetch/refresh。
5. `clearCache()` 能清理新旧 cache。
6. 测试覆盖 upstream 成功、失败、超时、NXDOMAIN、A/AAAA、并发刷新。

## 风险与注意事项

- serve-expired 会牺牲一部分正确性换取可用性。过期记录可能指向已经变更或不可用的地址。
- prefetch 会增加上游请求量，Unbound 文档提示大约会增加 10% 的流量和机器负载。
- DNS response cache 的 key 需要谨慎，后续若支持 EDNS、DNSSEC DO bit、不同 view，需要扩展 key 维度。
- 缓存 ref-counted Netty 对象容易产生内存泄漏或非法释放，必须使用 immutable record model。
- `DnsClient` 与 `DnsServer` 同时接入 cache 后，要避免重复缓存导致 TTL 语义不一致；建议 `DnsServer` 上游 full response cache 优先，`DnsClient` 只负责 `resolve*` API 的 address cache。
