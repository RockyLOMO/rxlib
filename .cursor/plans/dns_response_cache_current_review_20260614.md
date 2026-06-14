# DNS response cache 当前实现 review

创建日期：2026-06-14

## Review 范围

本次 review 聚焦当前 `master` 上 DNS cache 相关实现，主要涉及：

- `rxlib/src/main/java/org/rx/net/dns/DnsServer.java`
- `rxlib/src/main/java/org/rx/net/dns/DnsClient.java`
- `rxlib/src/main/java/org/rx/net/dns/DnsResolveCore.java`
- `rxlib/src/main/java/org/rx/net/dns/DnsResolverSupport.java`
- `rxlib/src/main/java/org/rx/net/dns/DnsResponseCacheEntry.java`
- `rxlib/src/main/java/org/rx/core/RxConfig.java`
- `deploy/rss-svr/start.sh`

目标是确认 `prefetch` / `serve-expired` / response cache 是否已经真正生效，以及还有哪些实现风险需要继续修。

## 总体结论

当前实现已经比最初计划完整很多：

- `DnsServer` upstream query 路径已经有自己的 response cache。
- `prefetch` 已经有 fresh cache hit 后的后台刷新分支。
- `serve-expired` 已经有 upstream 失败或超时后返回 stale cache 的分支。
- `deploy/rss-svr/start.sh` 已经接入小内存运行参数。
- `DnsResponseCacheEntry` 已经避免直接长期缓存 Netty `DnsResponse` / `ByteBuf` 对象。

但仍有几个需要继续修的点：

1. `DnsClient.resolve*` 还没有覆盖 `prefetch` / `serve-expired`。
2. 默认行为已经改变：即使 `prefetch=false`、`serveExpired=false`，`DnsServer` 也会缓存 upstream response。
3. TTL=0 的 positive response 目前可能会被错误缓存。
4. `PERSISTENT` 模式容量参数看起来可能被限制成 1。
5. `clearCache()` 没有统一清理 `responseCache`。

## 已实现部分

### 1. DnsServer response cache 已接入

`DnsServer` 构造时已经初始化：

```java
responseCache = newResponseCache();
```

这说明普通 DNS server upstream 转发不再只依赖 Netty resolver cache，而是有 rxlib 自己的 response cache。

### 2. queryUpstream 已有 fresh / stale / prefetch 逻辑

当前 `DnsResolveCore.queryUpstream(...)` 已经包括：

```text
fresh cache hit
  -> 返回 cached response
  -> 若 shouldPrefetch，则后台 refresh

stale cache hit + serveExpired=true + clientTimeoutMillis=0
  -> 立即返回 stale
  -> 后台 refresh

stale cache hit + serveExpired=true + clientTimeoutMillis>0
  -> 先请求 upstream
  -> upstream 成功：返回 fresh 并更新 cache
  -> upstream 失败或超时：返回 stale

cache miss
  -> 请求 upstream
  -> 成功后写入 response cache
```

这已经覆盖了 Unbound-like `prefetch` 和 `serve-expired` 的核心语义，至少在 `DnsServer` upstream query 路径已经成立。

### 3. 后台刷新有并发去重

`refreshUpstream(...)` 使用：

```java
server.responseRefreshPromises.putIfAbsent(cacheKey, refreshPromise)
```

同一个 cache key 同一时间只有一个后台 refresh，避免热点域名在 TTL 后段触发刷新风暴。

### 4. ByteBuf / 引用计数处理方向正确

`DnsResponseCacheEntry` 入 cache 时把 `DnsRawRecord.content()` 拷贝成 `byte[]`：

```java
byte[] bytes = new byte[content.readableBytes()];
content.getBytes(content.readerIndex(), bytes);
```

出 cache 时重新构造：

```java
new DefaultDnsRawRecord(..., Unpooled.wrappedBuffer(record.content))
```

这避免了长期保存 ref-counted Netty 对象，是正确方向。

### 5. rss-svr 参数已接入

`deploy/rss-svr/start.sh` 已经加入：

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

并通过 `DNS_CACHE_OPTIONS` 加入 `APP_OPTIONS`。

这组参数适合 2C / 1.5G RAM / 256m heap 的 rss-svr：

- 小内存只用 `MEMORY`。
- `maximumSize=256`。
- `maximumBytes=65536`。
- `prefetch=false`，降低额外请求。
- `serveExpired=true`，提升上游 DNS 抖动时的可用性。

## 需要修的问题

### 问题 1：DnsClient resolve* 还没有接入 prefetch / serve-expired

最初目标是 `DnsClient` 与 `DnsServer` 两个类共享公共能力。

当前实现中，`DnsServer` upstream query 路径已经接入 response cache，但 `DnsClient.resolveAsync(...)` / `resolveAllAsync(...)` 仍然是：

```text
resolveLocalAllAsync(...)
  -> 命中 local interceptor：返回 local result
  -> 未命中：nameResolver.resolve / nameResolver.resolveAll
```

也就是说：

- `DnsClient.resolve*` 仍主要依赖 Netty `DnsNameResolver` 自带 cache。
- `DnsClient.resolve*` 没有 stale fallback。
- `DnsClient.resolve*` 没有 prefetch。
- `DnsClient.query(DnsQuestion)` 自身也没有 cache；cache 是在 `DnsServer.queryUpstream(...)` 外层做的。

#### 建议

短期建议在文档中明确：

```text
当前 prefetch / serve-expired 只覆盖 DnsServer upstream DNS query 路径，不覆盖 DnsClient.resolve / resolveAll。
```

如果要补齐 `DnsClient`，建议单独实现 address-level cache，而不是强行复用 full DNS response cache。

候选方案：

```text
DnsClient.resolveAllAsync(host)
  -> rxlib address cache fresh hit
  -> stale hit + serveExpired
  -> miss -> nameResolver.resolveAll
```

注意要处理 Netty resolve cache 与 rxlib 外层 cache 的 TTL 语义，避免双层 cache 让过期行为难以理解。

### 问题 2：默认行为已经改变

当前 `DnsServer` 构造时无条件创建：

```java
responseCache = newResponseCache();
```

而 `queryUpstream(...)` 也无条件读写 response cache。

这意味着即使配置为：

```properties
app.net.dns.prefetch=false
app.net.dns.serveExpired=false
```

`DnsServer` 仍然会启用普通 upstream DNS response cache。

默认配置又是：

```java
storage = HYBRID
maximumSize = 4096
```

所以库默认行为已经从“纯转发”变成“带 response cache 的转发”。这不一定是 bug，但属于行为变更，需要明确确认。

#### 建议

如果目标是“配置了就生效，不配置不改变旧行为”，建议增加主开关：

```properties
app.net.dns.cacheEnabled=false
```

逻辑建议：

```java
boolean enabled = config.isCacheEnabled()
        || config.isPrefetch()
        || config.isServeExpired();
```

rss-svr 可以显式配置：

```bash
DNS_CACHE_ENABLED=${DNS_CACHE_ENABLED:-true}
-Dapp.net.dns.cacheEnabled=${DNS_CACHE_ENABLED}
```

库默认保持旧行为更稳。

如果确认希望默认开启普通 DNS response cache，也应该在文档中明确这是 intentional behavior。

### 问题 3：TTL=0 的 positive response 可能被错误缓存

当前 `DnsResponseCacheEntry.tryCreate(...)` 的逻辑是：

```java
int ttlSeconds = minPositiveTtl(answers, authorities, additionals);
if (ttlSeconds <= 0) {
    ttlSeconds = Math.max(1, fallbackTtlSeconds);
}
```

这会产生两个问题。

#### 3.1 Positive answer TTL=0 被 fallback 成 negativeTtl

如果 upstream 返回 `NOERROR` 且 answer TTL 全部为 0，当前逻辑会用 `fallbackTtlSeconds`，也就是通常的 `negativeTtl=5`。

这会把本来明确要求“不缓存”的 positive response 缓存几秒。

#### 3.2 混合 TTL 时，TTL=0 record 可能被输出成 TTL=1

出 cache 时：

```java
long remain = record.ttl - elapsedSeconds;
recordTtl = remain <= 0 ? 1 : ...;
```

如果某条 record 原始 TTL=0，但整个 response 因其他 record 有正 TTL 而被缓存，后续输出会把这条 TTL=0 record 改成 TTL=1。

#### 建议

对 positive answer 更严格：

```text
NOERROR 且 ANSWER 非空：
  - 如果任一 answer TTL <= 0：不缓存整个 response
  - 否则 freshTtlSeconds = answer/authority/additional 中正 TTL 的最小值，或至少 answers 的最小 TTL

NXDOMAIN / NODATA：
  - 可使用 SOA negative TTL
  - 无 SOA 时才 fallback 到 server.negativeTtl
```

最小修复：

```java
if (response.code() == DnsResponseCode.NOERROR && !answers.isEmpty()) {
    if (hasZeroOrNegativeTtl(answers)) {
        return null;
    }
}
```

### 问题 4：PERSISTENT 模式容量参数需要确认

当前 `newResponseCache()` 中：

```java
case PERSISTENT:
    return new H2StoreCache<String, DnsResponseCacheEntry>(
            EntityDatabase.DEFAULT, 1L, 1);
```

`newInterceptorCache()` 里也有类似写法。

如果 `H2StoreCache(EntityDatabase, long, int)` 第二个参数是容量或 maxSize，那么 `PERSISTENT` 模式可能只有 1 个条目，基本不可用。

#### 建议

确认 `H2StoreCache` 构造器语义。

如果第二个参数是容量，应改为：

```java
new H2StoreCache<>(EntityDatabase.DEFAULT, config.getMaximumSize(), 1)
```

如果第二个参数不是容量，也建议封装命名方法，避免维护者误解。

rss-svr 当前默认 `MEMORY`，所以线上暂时不受这个问题影响。

### 问题 5：clearCache 没有统一清 responseCache

当前 `DnsClient.clearCache()` 清理：

```java
nameResolver.resolveCache().clear();
interceptorCache.clear();
resolvingPromises.clear();
domainKeyCache.clear();
```

但现在公共基类里已经有：

- `interceptorCache`
- `responseCache`
- `resolvingPromises`
- `responseRefreshPromises`
- `domainKeyCache`

`DnsServer.dispose()` 只清了 `responseRefreshPromises`，没有清 `responseCache`。

#### 建议

在 `DnsResolverSupport` 增加统一方法：

```java
public void clearDnsCache() {
    if (interceptorCache != null) {
        interceptorCache.clear();
    }
    if (responseCache != null) {
        responseCache.clear();
    }
    resolvingPromises.clear();
    responseRefreshPromises.clear();
    domainKeyCache.clear();
}
```

`DnsClient.clearCache()` 调用该方法并额外清 Netty resolver cache：

```java
public void clearCache() {
    nameResolver.resolveCache().clear();
    clearDnsCache();
}
```

`DnsServer` 也可以暴露：

```java
public void clearCache() {
    clearDnsCache();
    upstreamClient.clearCache();
}
```

## 次要优化建议

### 1. 指标命名更精确

当前 `responseCacheMisses` 在 stale 可用但需要等待 upstream 时也会增加，统计语义有点混杂。

建议拆成：

```text
responseCacheMisses
responseCacheFreshHits
responseCacheStaleWaits
responseCacheStaleHits
responseCacheUpstreamFailServedExpired
```

### 2. prefetch 与 stale-refresh 指标拆开

当前 `responseCachePrefetchStarted` 既用于普通 prefetch，也用于 stale-refresh。

建议拆成：

```text
responseCachePrefetchStarted
responseCacheStaleRefreshStarted
responseCacheRefreshSuccess
responseCacheRefreshFailure
```

### 3. response cache key 后续可能要扩展

当前 key 维度是：

```text
type + dnsClass + normalized domain
```

对普通 A/AAAA/IN 场景足够。

如果后续支持以下能力，需要扩展 key：

- EDNS client subnet
- DNSSEC DO/CD bit
- different view / source IP policy
- per-upstream cache isolation
- ANY / CNAME / MX / TXT 等更复杂 response behavior

## 建议修复优先级

### P0

1. 修 TTL=0 positive response 不应缓存。
2. 明确默认是否启用 response cache；如不想改变旧行为，增加 `app.net.dns.cacheEnabled`。

### P1

3. 增加公共 `clearDnsCache()`，确保 `responseCache` 与 `responseRefreshPromises` 能被清理。
4. 文档明确当前能力只覆盖 `DnsServer` upstream query，不覆盖 `DnsClient.resolve*`。

### P2

5. 确认并修正 `PERSISTENT` 模式容量参数。
6. 拆分 metrics 计数器语义。
7. 评估是否给 `DnsClient.resolve*` 增加 address-level stale cache。

## 当前推荐状态

对 rss-svr 当前配置，建议继续保持：

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

这组配置适合小内存节点，也避免 prefetch 增加额外 DNS 请求。

但在继续扩大使用前，建议先修 TTL=0 positive response 缓存问题，并确认默认 response cache 行为是否符合库级兼容性预期。
