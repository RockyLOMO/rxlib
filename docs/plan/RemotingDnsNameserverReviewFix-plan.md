# Remoting / DNS / Nameserver Review 修复计划与复查待办

## 1. 背景

本文档记录对 `org.rx.net.rpc.Remoting`、`org.rx.net.dns.*`、`org.rx.net.nameserver.*` 相关实现的 review、修复范围、复查结果与后续待办。

原始 review 发现的问题主要集中在：

1. Remoting 事件订阅重复 attach，导致重复广播/重复 compute/监听器泄漏。
2. Remoting 服务端同步反射执行业务方法，缺少可选业务线程池 offload。
3. Remoting `poolMode` 与事件订阅语义冲突。
4. Remoting `serverInitLocks` 异常路径未释放。
5. Remoting `currentLocalEndpoint()` 日志端点取值错误。
6. DNS A/AAAA 查询 coalescing key 共用 domain，IPv4/IPv6 语义容易串。
7. DNS domain/hosts/cache key 未统一大小写。
8. DoH response body 编码异常路径可能泄漏。
9. DNS `resolvingKeys` 冗余。
10. NameserverClient `healthTask` 未取消。
11. NameserverClient retry timer key 粒度过粗。
12. NameserverImpl replica UDP 同步发送 live concurrent map/set。
13. Nameserver replica UDP 同步缺少版本/全量校验。
14. Nameserver instance attrs 以 `InetAddress` 为 key，同 IP 多实例/NAT 场景会冲突。

## 2. 本轮明确不处理

DNSServer review 第一个问题暂不处理：

- `DnsResolveCore` interceptor cache / coalescing key 未包含 `srcIp`，可能污染按来源 IP 分流结果。

原因：该问题涉及 interceptor 语义、cache 粒度、缓存容量与分流策略，需要单独评估。后续如要支持按客户端 IP、区域、线路、租户返回不同 DNS 结果，应新增独立计划：

- `DnsInterceptorSourceAwareCache-plan.md`

## 3. 修复后复查状态

当前代码已完成大部分原计划修复项：

### 3.1 Remoting

已完成：

1. `EventBean` 增加 `AtomicBoolean listenerAttached`，服务端同一 eventName 只 attach 一次 listener。
2. `poolMode` 下调用三参数 `attachEvent` 会抛出 `InvalidException`，避免池化连接承载长连接事件订阅。
3. `registerBean` 使用 finally 清理 `serverInitLocks`。
4. `currentLocalEndpoint()` 已改为读取 `HybridSession.tcpLocalEndpoint()`。
5. `RpcServerConfig` 已增加 `Executor executor` 和 `executorForPing`，`Remoting` 服务端支持可选 offload。

复查结论：

- 主问题已修复。
- 仍建议补充/保留事件重复广播、重连重订阅、poolMode 事件订阅拒绝、executor offload 的回归测试。
- executor 开启时需要确认 `HybridSession.send()` 跨线程调用安全；如后续发现不安全，应改为投递到对应 EventLoop 回写。

### 3.2 DNS / DoH

已完成：

1. `resolveKey(domain)` 已改为 `resolveKey(domain, queryType)`。
2. `resolvingKeys` 已移除，仅保留 `resolvingPromises`。
3. `DnsServer` 的 `addHosts/removeHosts/getHosts/getAllHosts/cacheKey/resolveKey` 已统一走 normalize。
4. `DoHServerHandler` response body 已增加 `handedOff` 异常释放保护。

复查结论：

- queryType coalescing 已修复。
- `normalizeDomain` 当前实现仍会在 DNS 热路径产生不必要 `String` 分配，需要按本文档待办 1 优化。
- A/AAAA cache 写入仍有残留风险：一次 interceptor 解析结果仍同时写入 A 与 AAAA cache，需按本文档待办 2 继续修复。

### 3.3 Nameserver

已完成：

1. `NameserverClient.dispose()` 已取消 `healthTask`。
2. retry timer key 已从 `appName` 改为 `tuple`。
3. replica 同步已引入 `ReplicaSnapshot` / `ReplicaFullSync` DTO，避免直接发送 live concurrent map/set。
4. 已引入 `replicaVersion`、`lastAppliedReplicaVersion` 与周期 full sync。
5. instance attrs key 已从单纯 `InetAddress` 改为 `appName#ip`。

复查结论：

- 原始泄漏和 live collection 序列化问题已明显改善。
- replica version 当前是单机本地递增 long，跨节点版本没有 sourceId 维度，严格说不能全局比较。需要按本文档待办 3 做 source-aware version。
- full sync 当前可用于最终收敛，但如果 packet 较大，仍存在 UDP 分片/丢包风险。后续可考虑 TCP/RPC 拉取全量快照。

## 4. 待办 1：DNS normalizeDomain 保守优化

### 4.1 当前问题

当前实现类似：

```java
static String normalizeDomain(String questionName) {
    int len = questionName.length();
    String domain = len > 0 && questionName.charAt(len - 1) == '.' ? questionName.substring(0, len - 1) : questionName;
    return domain.toLowerCase(Locale.ROOT);
}
```

DNS 查询热路径中，`question.name()` 通常是 `example.com.` 这种带尾点格式，因此：

| 输入 | 当前分配情况 |
|---|---|
| `example.com` | `toLowerCase(Locale.ROOT)` 通常返回原对象，基本无新 String |
| `example.com.` | `substring` 创建 1 个新 String |
| `Example.COM.` | `substring` 1 个新 String + `toLowerCase` 1 个新 String |

### 4.2 优化目标

1. 普通小写且无尾点域名直接返回原对象。
2. 小写但有尾点，只做一次 `substring`。
3. 只有检测到 ASCII 大写时才分配 `char[]` 并转小写。
4. 避免在热路径调用 `toLowerCase(Locale.ROOT)`。
5. 保持现有内部规范：去掉尾点 + 小写。

### 4.3 推荐实现

```java
static String normalizeDomain(String name) {
    int len = name.length();
    if (len == 0) {
        return name;
    }

    int end = name.charAt(len - 1) == '.' ? len - 1 : len;
    boolean trimDot = end != len;

    boolean hasUpper = false;
    for (int i = 0; i < end; i++) {
        char c = name.charAt(i);
        if (c >= 'A' && c <= 'Z') {
            hasUpper = true;
            break;
        }
    }

    // 最常见路径：已是小写且无尾点，直接返回原对象
    if (!trimDot && !hasUpper) {
        return name;
    }

    // 只有尾点，没有大写：只分配一次 substring
    String normalized = trimDot ? name.substring(0, end) : name;
    if (!hasUpper) {
        return normalized;
    }

    return asciiLower(normalized);
}

static String asciiLower(String s) {
    int len = s.length();
    int firstUpper = -1;
    for (int i = 0; i < len; i++) {
        char c = s.charAt(i);
        if (c >= 'A' && c <= 'Z') {
            firstUpper = i;
            break;
        }
    }
    if (firstUpper < 0) {
        return s;
    }

    char[] chars = s.toCharArray();
    for (int i = firstUpper; i < len; i++) {
        char c = chars[i];
        if (c >= 'A' && c <= 'Z') {
            chars[i] = (char) (c + 32);
        }
    }
    return new String(chars);
}
```

### 4.4 分配预期

| 输入 | 优化后分配 |
|---|---|
| `example.com` | 0 |
| `example.com.` | 1 次 substring |
| `Example.COM` | 1 次 char[] + 1 次 String |
| `Example.COM.` | 1 次 substring + 1 次 char[] + 1 次 String |

### 4.5 测试要求

新增或更新测试：

1. `normalizeDomainShouldReturnSameInstanceForLowercaseWithoutTrailingDot`
2. `normalizeDomainShouldTrimTrailingDot`
3. `normalizeDomainShouldAsciiLowercase`
4. `domainShouldBeCaseInsensitive`

注意：第 1 个测试可用 `assertSame(input, normalizeDomain(input))`，确保常见路径零分配。

## 5. 待办 2：A/AAAA interceptor cache 写入按地址族拆分

### 5.1 当前残留风险

当前 `resolveKey` 已按 queryType 隔离，但 `resolveByInterceptor` 中仍类似：

```java
server.interceptorCache.put(server.cacheKey(domain, DnsRecordType.A), resolvedIps, policy);
server.interceptorCache.put(server.cacheKey(domain, DnsRecordType.AAAA), resolvedIps, policy);
```

如果 interceptor 返回纯 IPv4：

1. A 查询得到 IPv4，正确。
2. 同一批 IPv4 list 被写入 AAAA cache。
3. 后续 AAAA 查询命中 AAAA cache，但 `newAddressResponse` 会过滤掉 IPv4，返回 NOERROR 空答案。

严格来说，这仍是 A/AAAA 族类污染。

### 5.2 推荐修复

方案 A：只写当前 queryType cache。

```java
server.interceptorCache.put(server.cacheKey(domain, queryType), resolvedIps, policy);
```

优点：最小改动。

缺点：A 与 AAAA 会各调用一次 interceptor。

方案 B：按地址族拆分后分别写入。

```java
List<InetAddress> aRecords = filterByType(resolvedIps, DnsRecordType.A);
List<InetAddress> aaaaRecords = filterByType(resolvedIps, DnsRecordType.AAAA);
server.interceptorCache.put(server.cacheKey(domain, DnsRecordType.A), aRecords, policyFor(aRecords));
server.interceptorCache.put(server.cacheKey(domain, DnsRecordType.AAAA), aaaaRecords, policyFor(aaaaRecords));
```

优点：一次 interceptor 调用可同时填充 A/AAAA，语义正确。

缺点：需要明确空 list 是 negative cache，TTL 应使用 `negativeTtl`。

优先建议：方案 B。

### 5.3 测试要求

新增或更新测试：

1. `aQueryShouldNotPolluteAaaaCacheWithIpv4OnlyResult`
2. `aaaaQueryShouldNotPolluteACacheWithIpv6OnlyResult`
3. `mixedInterceptorResultShouldPopulateAAndAaaaSeparately`
4. `emptyFamilyCacheShouldUseNegativeTtl`

## 6. 待办 3：Nameserver replica version 改为 source-aware

### 6.1 当前风险

当前 `ReplicaSnapshot` / `ReplicaFullSync` 只有一个 `long version`，但每个 Nameserver 节点都是本地递增。跨节点时，`version` 不能作为全局有序版本比较。

可能场景：

1. 节点 A 发出 version=10，节点 B 记录 `lastAppliedReplicaVersion=10`。
2. 节点 C 新启动后发出 version=1 的有效增量。
3. 节点 B 会把 C 的 version=1 判断为 stale，导致有效增量被忽略。

虽然 full sync 最终可能收敛，但增量期间会出现状态延迟或错误。

### 6.2 推荐修复

给 replica packet 增加 source id：

```java
final String sourceId;
final long version;
```

服务端维护 per-source version：

```java
final Map<String, Long> lastAppliedReplicaVersions = new ConcurrentHashMap<>();
```

判断逻辑：

```java
boolean acceptReplicaVersion(String sourceId, long version) {
    if (sourceId == null || version <= 0) {
        return true;
    }
    Long last = lastAppliedReplicaVersions.get(sourceId);
    if (last != null && version <= last) {
        return false;
    }
    lastAppliedReplicaVersions.put(sourceId, version);
    return true;
}
```

source id 可优先使用：

1. `RxConfig.INSTANCE.getId()`。
2. 启动时生成的 stable node id。
3. `registerPort + local address` 组合，仅作为兜底。

### 6.3 测试要求

新增或更新测试：

1. `replicaVersionShouldBeTrackedPerSource`
2. `lowerVersionFromDifferentSourceShouldBeAccepted`
3. `lowerVersionFromSameSourceShouldBeIgnored`
4. `fullSyncShouldNotOverwriteNewerSameSourceState`

## 7. 待办 4：Nameserver replica full sync 大包策略

### 7.1 当前风险

`ReplicaFullSync` 通过 UDP 发送，包含：

1. nameserver endpoints。
2. hosts 快照。
3. attrs 快照。

当实例数或属性较多时，UDP 包可能超过 MTU，产生分片；公网/跨机房网络下，分片丢包会明显增加。

### 7.2 推荐策略

短期：

1. full sync 周期保持较低频率，默认 60s 可接受。
2. 记录 full sync 编码后字节数。
3. 超过阈值时 warn，例如 1200 bytes、1400 bytes 或配置项。

中期：

1. full sync 改为 RPC/TCP 拉取。
2. UDP 增量只传小变更。
3. 节点发现版本跳跃时，主动 RPC 拉取 full snapshot。

## 8. 待办 5：补齐回归测试

### 8.1 Remoting

1. `eventSubscribeShouldAttachServerListenerOnce`
2. `eventResubscribeAfterReconnectShouldNotDuplicateBroadcast`
3. `poolModeShouldRejectEventSubscription`
4. `rpcExecutorShouldOffloadMethodInvocation`
5. `registerBeanShouldCleanupInitLockOnFailure`

### 8.2 DNS / DoH

1. `normalizeDomainShouldReturnSameInstanceForLowercaseWithoutTrailingDot`
2. `normalizeDomainShouldTrimTrailingDot`
3. `normalizeDomainShouldAsciiLowercase`
4. `domainShouldBeCaseInsensitive`
5. `aQueryShouldNotPolluteAaaaCacheWithIpv4OnlyResult`
6. `aaaaQueryShouldNotPolluteACacheWithIpv6OnlyResult`
7. `mixedInterceptorResultShouldPopulateAAndAaaaSeparately`
8. `dohEncodeFailureShouldReleaseBody`

### 8.3 Nameserver

1. `disposeShouldCancelHealthTask`
2. `retryTimerShouldBePerNameserver`
3. `replicaSyncShouldSendSnapshotNotLiveMap`
4. `replicaFullSyncShouldConvergeAfterDroppedDelta`
5. `replicaVersionShouldBeTrackedPerSource`
6. `instanceAttrsShouldNotConflictBySameIpDifferentApp`

## 9. 建议实施顺序

1. 优先做 `normalizeDomain` 保守优化，风险最低，收益明确。
2. 修复 A/AAAA cache 按地址族拆分，避免 queryType 修复只完成一半。
3. 补 DNS normalize 与 A/AAAA cache 测试。
4. 做 Nameserver source-aware replica version。
5. 再评估 full sync 是否需要从 UDP 改为 RPC/TCP。

## 10. 验收标准

1. `normalizeDomain("example.com")` 返回同一个对象引用。
2. `normalizeDomain("example.com.")` 返回 `example.com`。
3. `normalizeDomain("Example.COM.")` 返回 `example.com`。
4. A 查询不会污染 AAAA cache。
5. AAAA 查询不会污染 A cache。
6. 多 source replica 版本互不影响。
7. Remoting 事件订阅不会重复广播。
8. NameserverClient close 后不再执行 health handshake。
9. DoH 编码异常路径无 ByteBuf 泄漏。
