# Remoting / DNS / Nameserver Review 修复计划与复查结论

## 1. 背景

本文档记录对 `org.rx.net.rpc.Remoting`、`org.rx.net.dns.*`、`org.rx.net.nameserver.*` 相关实现的 review、修复状态与剩余待办。

原始 review 发现的问题主要集中在：

1. Remoting 服务端事件 `SUBSCRIBE` 重复 `attachEvent`，导致重复广播、重复 compute、潜在监听器泄漏。
2. Remoting 服务端同步反射执行业务方法，缺少可选业务线程池 offload 能力。
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

## 2. 明确不处理项

本轮仍不处理 DNSServer 第一个问题：

- `DnsResolveCore` interceptor cache / coalescing key 未包含 `srcIp`，可能污染按来源 IP 分流结果。

原因：该问题涉及 interceptor 语义、cache 粒度、缓存容量与分流策略，需要单独评估。后续如需要按客户端 IP、区域、线路、租户返回不同 DNS 结果，应新增独立计划：

- `DnsInterceptorSourceAwareCache-plan.md`

## 3. 最新复查结论

最新代码已完成本计划中除 `srcIp` cache 隔离以外的主要修复。重点修复提交：

- `f672ab1bfb059afd1fcb6b5415902084d755cc68`：Remoting 事件一次 attach、RPC executor、DNS queryType coalescing、DoH release、Nameserver snapshot/full sync 等。
- `85dd6b3e8aa776f66d8550b03c6b7a61794c5241`：DNS 低分配 normalize、A/AAAA family-aware cache、Nameserver source-aware replica version、相关测试。

总体判断：

1. Remoting 原始问题已基本闭环。
2. DNS 原始问题已基本闭环，但有一个 DNS rcode 语义细节建议后续确认：IPv4-only host 查询 AAAA 时，当前空族类可能返回 NXDOMAIN；严格 DNS 语义更接近 NOERROR/NODATA。
3. Nameserver 原始问题已基本闭环，但 full sync 仍通过 UDP 发送，大规模实例/属性时仍需评估包大小与分片风险。

## 4. Remoting 复查

### 4.1 已完成

1. `ServerBean.EventBean` 已增加 `AtomicBoolean listenerAttached`，服务端同一 eventName 只 attach 一次 listener。
2. `poolMode` 下调用三参数 `attachEvent` 会抛出 `InvalidException`，避免池化连接承载长连接事件订阅。
3. `registerBean` 使用 finally 清理 `serverInitLocks`，异常路径不再残留 lock object。
4. `currentLocalEndpoint()` 已改为读取 `HybridSession.tcpLocalEndpoint()`，`DefaultHybridSession` 已提供该方法。
5. `RpcServerConfig` 已增加 `Executor executor` 和 `executorForPing`，`Remoting` 服务端支持可选业务线程池 offload。
6. 参考文档已补充 poolMode/statefulMode 的事件订阅约束。

### 4.2 复查结论

Remoting 主风险已修复。当前实现保持默认 inline 执行，只有配置 executor 时才 offload，兼容性较好。

仍需注意：

- executor 开启时，`HybridSession.send()` 跨线程调用需要持续关注。当前 `DefaultHybridSession.send()` 最终走 `TcpClient.send()`，如果后续 TcpClient 写路径出现线程亲和问题，应改为投递到 Channel EventLoop 回写。
- `listenerAttached` 只避免重复 attach，不处理 eventName 无订阅者时 detach listener。考虑到 `EventPublisher` remove API 及竞态复杂度，当前保留 listener 是可接受的低风险方案。

### 4.3 建议保留测试

1. `eventSubscribeShouldAttachServerListenerOnce`
2. `eventResubscribeAfterReconnectShouldNotDuplicateBroadcast`
3. `poolModeShouldRejectEventSubscription`
4. `rpcExecutorShouldOffloadMethodInvocation`
5. `registerBeanShouldCleanupInitLockOnFailure`

## 5. DNS / DoH 复查

### 5.1 已完成

1. `resolveKey(domain)` 已改为 `resolveKey(domain, queryType)`。
2. `resolvingKeys` 已移除，仅保留 `resolvingPromises`。
3. `DnsServer` 的 `addHosts/removeHosts/getHosts/getAllHosts/cacheKey/resolveKey` 已统一走 normalize。
4. `DoHServerHandler` response body 已增加 `handedOff` 异常释放保护。
5. `normalizeDomain` 已改为 ASCII 扫描的保守低分配实现：
   - 小写无尾点域名直接返回原对象。
   - 小写带尾点域名只做一次 `substring`。
   - 只有检测到 ASCII 大写时才分配 `char[]` 并转小写。
6. interceptor 解析结果已按 A/AAAA 地址族拆分后分别写入 cache。
7. 空地址族结果使用 `negativeTtl`，避免反复触发 interceptor。
8. 已补充 normalize、A/AAAA family-aware cache、negativeTtl 相关测试。

### 5.2 当前实现确认

`normalizeDomain` 当前实现符合低分配目标：

```java
static String normalizeDomain(String questionName) {
    int len = questionName.length();
    if (len == 0) {
        return questionName;
    }

    int end = questionName.charAt(len - 1) == '.' ? len - 1 : len;
    boolean trimDot = end != len;
    boolean hasUpper = false;
    for (int i = 0; i < end; i++) {
        char c = questionName.charAt(i);
        if (c >= 'A' && c <= 'Z') {
            hasUpper = true;
            break;
        }
    }

    if (!trimDot && !hasUpper) {
        return questionName;
    }
    String normalized = trimDot ? questionName.substring(0, end) : questionName;
    return hasUpper ? asciiLower(normalized) : normalized;
}
```

A/AAAA cache 当前实现也已按族类拆分：

```java
List<InetAddress> aRecords = filterByType(resolvedIps, DnsRecordType.A);
List<InetAddress> aaaaRecords = filterByType(resolvedIps, DnsRecordType.AAAA);
server.interceptorCache.put(server.cacheKey(domain, DnsRecordType.A), aRecords,
        CachePolicy.absolute(aRecords.isEmpty() ? server.negativeTtl : server.ttl));
server.interceptorCache.put(server.cacheKey(domain, DnsRecordType.AAAA), aaaaRecords,
        CachePolicy.absolute(aaaaRecords.isEmpty() ? server.negativeTtl : server.ttl));
```

### 5.3 复查发现的语义细节

当前 `newInterceptorResponse` 对 family filter 后的空结果返回：

```java
DnsMessageUtil.newErrorResponse(query, DnsResponseCode.NXDOMAIN)
```

这会导致：

- interceptor 返回 IPv4。
- A 查询正确返回 A 记录。
- AAAA cache 被写入空 list。
- 后续 AAAA 查询命中空 list，返回 NXDOMAIN。

严格 DNS 语义上，如果域名存在但没有该类型记录，应更接近 **NOERROR / NODATA**，而不是 NXDOMAIN。当前行为是否可接受取决于 rxlib interceptor 的语义：

- 如果空 list 表示“该域名不存在/应拦截为 NXDOMAIN”，当前行为可接受。
- 如果空 list 表示“该地址族无记录”，建议返回 NOERROR 空 answer。

### 5.4 可选待办：区分 NXDOMAIN 与 NODATA

建议后续评估是否引入明确语义：

方案 A：空 family 返回 NOERROR 空 answer。

```java
if (CollectionUtils.isEmpty(familyIps)) {
    return DnsMessageUtil.newAddressResponse(query, isTcp, question, server.negativeTtl, Collections.emptyList());
}
```

方案 B：cache value 增加语义包装，区分：

```java
class InterceptorDnsResult implements Serializable {
    boolean nxdomain;
    List<InetAddress> addresses;
}
```

建议优先方案 A，改动小，DNS 语义更标准；如果现有业务依赖空 list 表示 NXDOMAIN，再考虑方案 B。

### 5.5 建议保留测试

1. `normalizeDomainShouldReturnSameInstanceForLowercaseWithoutTrailingDot`
2. `normalizeDomainShouldTrimTrailingDot`
3. `normalizeDomainShouldAsciiLowercase`
4. `domainShouldBeCaseInsensitive`
5. `aQueryShouldNotPolluteAaaaCacheWithIpv4OnlyResult`
6. `aaaaQueryShouldNotPolluteACacheWithIpv6OnlyResult`
7. `mixedInterceptorResultShouldPopulateAAndAaaaSeparately`
8. `emptyFamilyCacheShouldUseNegativeTtl`
9. `dohEncodeFailureShouldReleaseBody`
10. 可选新增：`emptyFamilyShouldReturnNoErrorNoDataIfDomainExists`

## 6. Nameserver 复查

### 6.1 已完成

1. `NameserverClient.dispose()` 已取消 `healthTask`。
2. retry timer key 已从 `appName` 改为 `tuple`。
3. replica 同步已引入 `ReplicaSnapshot` / `ReplicaFullSync` DTO，避免直接发送 live concurrent map/set。
4. 已引入 `replicaVersion` 与周期 full sync。
5. instance attrs key 已从单纯 `InetAddress` 改为 `appName#ip`。
6. replica packet 已增加 `sourceId`。
7. `lastAppliedReplicaVersion` 已替换为 `Map<String, Long> lastAppliedReplicaVersions`，按 source 维护最后应用版本。
8. `applySnapshot/applyFullSync/DeregisterInfo` 均已走 `acceptReplicaVersion(sourceId, version)`。
9. 已补充 per-source replica version 相关测试。

### 6.2 当前实现确认

source-aware version 判断逻辑：

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

该实现可以避免：

- 节点 A 的高版本导致节点 B/C 的低版本有效增量被误判 stale。
- 同一 source 的旧包覆盖新包。

### 6.3 复查结论

Nameserver 原始问题已基本闭环。

剩余风险主要是 full sync 仍通过 UDP 承载：

- 小规模 replica 同步可接受。
- 大规模实例、hosts、attrs 时可能超过 MTU，产生 UDP 分片和丢包。
- 如果后续 nameserver 用于跨公网/跨机房大规模注册中心，建议把 full sync 改成 RPC/TCP 拉取。

### 6.4 可选待办：ReplicaFullSync 大包策略

短期建议：

1. full sync 周期保持低频，默认 60s 可接受。
2. 记录 full sync 编码后字节数。
3. 超过阈值时 warn，例如 1200 bytes、1400 bytes 或配置项。

中期建议：

1. full sync 改为 RPC/TCP 拉取。
2. UDP 增量只传小变更。
3. 节点发现版本跳跃或长期未收到某 source 更新时，主动 RPC 拉取 full snapshot。

## 7. 最终状态表

| 模块 | 问题 | 状态 |
|---|---|---|
| Remoting | SUBSCRIBE 重复 attach | 已修复 |
| Remoting | 服务端业务方法缺少 offload | 已修复，默认关闭 executor |
| Remoting | poolMode 事件订阅冲突 | 已修复，直接拒绝 |
| Remoting | serverInitLocks 异常泄漏 | 已修复 |
| Remoting | currentLocalEndpoint 错误 | 已修复 |
| DNS | A/AAAA resolveKey 共用 | 已修复 |
| DNS | normalizeDomain 分配偏高 | 已修复 |
| DNS | A/AAAA cache 族类污染 | 已修复 |
| DNS | resolvingKeys 冗余 | 已修复 |
| DoH | body 编码异常泄漏 | 已修复 |
| NameserverClient | healthTask 未取消 | 已修复 |
| NameserverClient | retry key 粒度过粗 | 已修复 |
| NameserverImpl | replica 发送 live collection | 已修复 |
| NameserverImpl | replica version 非 source-aware | 已修复 |
| NameserverImpl | instance attrs 同 IP 冲突 | 已修复为 appName#ip |
| DNS | interceptor cache 未按 srcIp 隔离 | 本轮明确不处理 |
| DNS | 空地址族返回 NXDOMAIN 还是 NODATA | 可选待办 |
| Nameserver | ReplicaFullSync UDP 大包 | 可选待办 |

## 8. 后续建议

短期只建议继续处理两个边缘项：

1. **DNS 空地址族 rcode 语义**：确认 IPv4-only host 查询 AAAA 时是否应该返回 NOERROR/NODATA。
2. **ReplicaFullSync 大包策略**：增加编码尺寸监控和 warn，后续再决定是否改 RPC/TCP full snapshot。

其他原始 review 项可以视为已完成。
