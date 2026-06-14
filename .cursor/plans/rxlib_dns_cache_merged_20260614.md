# rxlib DNS Cache 整体设计与运行建议 (2026-06)

本文档由早期的 `prefetch/serve-expired` 设计方案与后期的运行参数建议合并整理而来，反映了当前 DNS Cache 机制的实现现状及生产环境（如 `rss-svr` 小内存节点）的推荐配置。

---

## 1. 背景与目标

rxlib 提供了 `DnsClient` 与 `DnsServer`：
- **`DnsClient`** 基于 Netty `DnsNameResolver`，负责基础解析逻辑。
- **`DnsServer`** 负责接收外部 DNS 请求，处理 hosts、拦截器(interceptor)以及通过 upstream 转发解析。

**核心增强目标**是在 `DnsServer` 转发路径上提供一套公共 DNS 缓存增强能力（参考 Unbound），但不引入复杂的递归解析验证逻辑：
- **Prefetch（预获取）**：热门域名在缓存即将过期时，后台自动触发上游刷新，减少客户端阻塞。
- **Serve-Expired（过期服务）**：当上游发生超时或暂时性故障时，继续下发已过期的缓存数据，以牺牲少部分准确性换取极大的高可用。
- **内存安全**：保证不长期持有 Netty 引用计数对象（`ByteBuf`），使用深拷贝和不可变缓存对象记录 DNS 结果。

---

## 2. 核心架构与实现现状

截至目前（2026-06-14），该能力的核心骨架均**已实现并接入** `DnsServer`。

### 2.1 已实现能力
1. **Upstream Response Cache**：
   - 拦截并缓存 `DnsResolveCore.queryUpstream`。
   - Key 按 `normalized domain + record type + record class` 生成。
   - 缓存时深拷贝 DNS 记录内容，出缓存时重新创建 `DnsResponse` 和 `DefaultDnsRawRecord`，彻底避免对象引用计数泄漏。
2. **Serve-Expired (过期失效回退)**：
   - 上游查询成功：更新缓存，正常返回。
   - 上游查询失败或发生超时：如果存在过期 (stale) 缓存且 `serveExpired=true`，则直接返回 stale 数据。
3. **Prefetch (预获取合并)**：
   - 当缓存命中，且剩余 TTL 进入配置的最后百分比阈值（如最后 10%）时，后台触发 `refresh`。
   - 已实现并发请求合并，确保同一个 Cache Key 在同一时刻仅产生一个后台上游查询，不会引发风暴。
4. **日志与埋点**：
   - 已加入对应分支事件日志（如 fresh hit, stale hit, prefetch, upstream fail served expired 等）。

### 2.2 Netty 缓存的边界区分
- **`DnsServer` 转发路径**：走的是完整的 rxlib DNS response cache，支持过期回退与预获取。
- **`DnsClient.resolve*`**：这部分代码调用的依然是 Netty 底层自带的 resolve cache。因此，Netty 的缓存机制与新增的 rxlib 增强机制**并不完全重叠**。未来若需要对 `resolve` 接口也应用 serve-expired，需在 Netty 外层独立加盖封装，目前 MVP 阶段暂且区分开。

---

## 3. 配置参考与字典

系统新增了一组 `DnsCacheConfig` 参数（前缀 `app.net.dns.`）：

| 属性名 | 默认值 | 描述 |
| --- | --- | --- |
| `cacheEnabled` | `false` | 普通 upstream response cache 主开关；`prefetch=true` 或 `serveExpired=true` 时会自动启用 response cache。 |
| `prefetch` | `false` | 主开关：是否允许热点命中时在 TTL 尾期后台刷新。 |
| `prefetchThresholdPercent` | `10` | TTL 剩余比例 <= 10% 时触发 prefetch。 |
| `serveExpired` | `false` | 主开关：上游不可用时是否回退给客户端过期缓存。 |
| `serveExpiredTtlSeconds` | `86400` (1天) | 记录过期后，在此宽限期内依然允许当做 stale 记录服务。 |
| `serveExpiredReplyTtlSeconds` | `30` | 响应 stale 记录时下发给客户端的临时 TTL（不宜过长）。 |
| `serveExpiredClientTimeoutMillis`| `1800` | 返回 stale 前等待上游的最大时间 (毫秒)，0 表示有 stale 立即返回并异步刷新。 |
| `cacheStorage` | `HYBRID` | `MEMORY` / `PERSISTENT` / `HYBRID`。 |
| `cacheMaximumSize` | `4096` | 缓存 Key 条数上限。 |
| `cacheMaximumBytes` | `0` | 内存预估容量上限字节数，`>0` 时启用基于权重的驱逐策略。 |

---

## 4. 推荐运行参数 (以 rss-svr 为例)

`rss-svr` 这类节点通常只有极小的堆内存配置（`-Xms256m -Xmx256m`），因此我们的缓存参数必须偏向于**极小内存占用、高稳定、不频繁刷新**的策略。

**强烈建议 `rss-svr` 的 `start.sh` 使用以下环境变量配置：**

```bash
DNS_CACHE_PREFETCH=false
DNS_CACHE_ENABLED=true
DNS_CACHE_SERVE_EXPIRED=true
DNS_CACHE_STORAGE=MEMORY
DNS_CACHE_MAXIMUM_SIZE=256
DNS_CACHE_MAXIMUM_BYTES=65536
DNS_CACHE_SERVE_EXPIRED_TTL_SECONDS=3600
DNS_CACHE_SERVE_EXPIRED_REPLY_TTL_SECONDS=15
DNS_CACHE_SERVE_EXPIRED_CLIENT_TIMEOUT_MILLIS=300
DNS_CACHE_PREFETCH_THRESHOLD_PERCENT=10
```

### 推荐参数核心解读：
1. **`DNS_CACHE_STORAGE=MEMORY`**
   - 不要使用默认的 `HYBRID`，会引入非必要的 H2Store 开销，小内存节点不需要持久化 DNS 记录。
2. **`DNS_CACHE_PREFETCH=false`**
   - 保持关闭。预加载会带来额外的上游连接数与机器负载开销（预估额外 +10% 流量），在小规格机器上没有明显的成本收益比。
3. **`DNS_CACHE_SERVE_EXPIRED=true`**
   - 强烈建议打开。这是兜底可用性的利器。配置 `CLIENT_TIMEOUT_MILLIS=300` 代表上游 300ms 没回来就立马塞回过期缓存；下发 TTL 设置为 `15` 秒让客户端尽快重新拿最新解析。
4. **`MAXIMUM_SIZE=256` & `MAXIMUM_BYTES=64KB`**
   - 极端压榨缓存所占用的 Heap 空间，如果线上 Heap 紧张甚至可以缩小到 128/32KB，这足以容纳核心白名单路由的少数域名缓存。

---

## 5. 未实现功能与非目标防范

在对标 Unbound 级别特性的设计中，经过评估，rxlib 在当前阶段**不会或暂缓**实现以下能力：

### 暂不建议实现：
1. **DNSSEC validation (DNSSEC 安全验证)**：引入验证链与锚点太重，当前 Netty resolver + forwarder 的定位不符。
2. **QNAME minimization (隐私最小化)**：该优化主要应用于向权威服务器递归过程，rxlib 默认只向上游公共 DNS 转发，实现意义有限。
3. **Prefetch-key**：绑定在 DNSSEC 上，无 DNSSEC 则没意义。

### 建议留待未来增强：
1. **`cache-min-ttl` / `cache-max-ttl` 的公共覆盖**：当前仍然强依赖于 Netty 初始化的硬编码（5 ~ 300 秒）。
2. **`rrset-roundrobin`**：A 记录的多 IP 自动轮转打散，防止客户端持续死咬单一故障 IP。
3. **Access Control (IP 访问控制) / RBAC View**：利用 DnsHandler 中的 srcIp 去实施请求阻断或返回差异化配置。
4. **DNS Rebinding 防护**：防止公共域名恶意将解析结果指向内部私有局域网网段。
