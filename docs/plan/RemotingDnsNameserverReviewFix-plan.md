# Remoting / DNS / Nameserver Review 修复计划

## 1. 背景

本计划来自对 `org.rx.net.rpc.Remoting`、`org.rx.net.dns.*`、`org.rx.net.nameserver.*` 相关实现的 review。

当前代码整体可用，但在事件订阅生命周期、RPC 调用线程模型、DNS A/AAAA 语义、DoH ByteBuf 异常释放、Nameserver 定时任务与副本同步一致性方面存在可修复风险。

## 2. 范围

### 2.1 本次需要修复

1. Remoting 服务端事件 `SUBSCRIBE` 重复 `attachEvent`，导致重复广播、重复 compute、潜在监听器泄漏。
2. Remoting 服务端同步反射执行业务方法，缺少可选业务线程池 offload 能力。
3. Remoting `poolMode` 与事件订阅语义冲突，需要禁止或显式降级。
4. Remoting `serverInitLocks` 异常路径未释放。
5. Remoting `currentLocalEndpoint()` 日志端点取值疑似错误。
6. DNS interceptor 对 A/AAAA 共用 `resolveKey(domain)`，可能导致 IPv4/IPv6 结果串扰。
7. DNS domain/hosts/cache key 未统一小写，导致大小写不敏感语义不完整。
8. DoH response body 在编码异常路径可能泄漏。
9. DNS `resolvingKeys` 冗余，移除以减少状态维护。
10. NameserverClient `healthTask` 未在 dispose 时取消。
11. NameserverClient 失败重试 timer key 使用 `appName`，多 nameserver 会互相覆盖。
12. NameserverImpl replica UDP 同步发送 live concurrent map/set，存在序列化一致性风险。
13. Nameserver replica UDP 同步缺少版本/全量校验，丢包后副本可能不一致。
14. Nameserver instance attrs 以 `InetAddress` 为 key，同 IP 多实例/NAT 场景会冲突。

### 2.2 本次明确不处理

DNSServer review 第一个问题暂不处理：

- `DnsResolveCore` interceptor cache / coalescing key 未包含 `srcIp`，可能污染按来源 IP 分流结果。

原因：该问题涉及 interceptor 语义、cache 粒度、缓存容量与分流策略，需要单独评估。本文档只保留接口兼容，不做 `srcIp` cache key 改造。

## 3. 修复原则

1. 保持 Java 8 兼容。
2. 默认行为尽量兼容现有调用方。
3. Netty I/O 线程避免阻塞；但为了兼容，offload 以可选配置开启。
4. DNS/DoH 涉及 `ByteBuf` 和 DNS record 引用计数时，必须保证异常路径 release。
5. Remoting 事件订阅必须以 session 生命周期为边界清理，避免监听器无限增长。
6. Nameserver replica 同步数据统一快照化，避免异步序列化过程中集合被修改。

## 4. Remoting 修复方案

### 4.1 服务端事件 listener 每个 eventName 只 attach 一次

#### 问题

当前每收到一次 `SUBSCRIBE` 都会执行：

```java
EventPublisher<?> eventTarget = (EventPublisher<?>) contractInstance;
eventTarget.attachEvent(p.eventName, listener, false);
eventBean.subscribe.add(session);
```

多客户端订阅同一事件、客户端重连重新订阅时，会重复挂载 listener。后续服务端发布一次事件时，多个 listener 都会遍历同一个 `eventBean.subscribe`，造成重复广播和重复 compute。

#### 修改

在 `Remoting.ServerBean.EventBean` 中增加：

```java
final AtomicBoolean listenerAttached = new AtomicBoolean();
```

`SUBSCRIBE` 分支改为：

```java
case SUBSCRIBE:
    if (eventBean.listenerAttached.compareAndSet(false, true)) {
        EventPublisher<?> eventTarget = (EventPublisher<?>) contractInstance;
        eventTarget.attachEvent(p.eventName, (sender, args) -> {
            ServerBean.EventContext eventContext = new ServerBean.EventContext((EventArgs) args);
            EventMessage computePack = prepareComputePack(bean, eventBean, p.eventName, (EventArgs) args, eventContext);
            if (computePack != null) {
                awaitComputedArgs(eventBean, eventContext, computePack, s);
            }
            List<HybridSession> broadcastTargets = collectBroadcastTargets(bean, eventBean, eventContext);
            doSendBroadcast(bean, p, eventContext, broadcastTargets);
        }, false);
    }
    eventBean.subscribe.add(session);
    break;
```

#### 注意

- `UNSUBSCRIBE` 和 `cleanupSubscriptions` 仍只移除 session。
- 不建议在 subscribe 为空时 detach listener，除非 `EventPublisher` 已有稳定 remove API；否则容易引入竞态。
- 如果后续需要释放 contractInstance 对应所有 listener，应单独为 `Remoting.register` 引入 close/unregister API。

### 4.2 RPC 服务端支持可选业务线程池 offload

#### 问题

`onServerReceive` 收到 `MethodMessage` 后直接反射调用业务方法。轻量方法低延迟，但通用 RPC 方法可能阻塞 I/O 线程。

#### 修改

在 `RpcServerConfig` 增加：

```java
private Executor executor;
private boolean executorForPing;
```

默认 `executor == null`，保持现有 inline 行为。

将 `onServerReceive` 的方法调用和回包逻辑拆成：

```java
private static void invokeAndReply(Object contractInstance, ServerBean bean, HybridServer s,
        HybridSession session, MethodMessage pack) {
    ...
    session.send(pack, RemotingHybridOptions.response(pack));
}
```

`onServerReceive` 中：

```java
Executor executor = bean.config.getExecutor();
if (executor == null) {
    invokeAndReply(contractInstance, bean, s, session, pack);
} else {
    executor.execute(() -> invokeAndReply(contractInstance, bean, s, session, pack));
}
```

#### 兼容策略

- 默认不启用 executor，避免改变现有吞吐和延迟表现。
- Nameserver 可暂不启用，因为当前 register/discover 较轻；但文档建议外部 I/O 型 RPC 服务开启。
- 如果使用 executor，需要确保 `HybridSession.send()` 支持跨线程调用；若不支持，则业务线程完成后通过 session/eventLoop 投递回写。

### 4.3 禁止 poolMode 使用事件订阅

#### 问题

`poolMode` 的 client 会被回收到对象池并 reset handler，不适合 `attachEvent` 这种长连接订阅语义。

#### 修改

在 `createFacade` 拦截 `attachEvent` 事件订阅分支时判断：

```java
if (config.isUsePool()) {
    throw new InvalidException("Remoting event subscription requires statefulMode");
}
```

适用范围：

- `attachEvent(String, ..., boolean)` 三参数订阅。
- 如后续存在其它订阅 API，也应统一拦截。

#### 文档

在 `docs/reference/net/rpc.md` 补充：

- 普通 request/response 可使用 `poolMode`。
- 事件订阅、事件广播、断线自动重订阅必须使用 `statefulMode`。

### 4.4 `serverInitLocks` 异常路径释放

#### 修改

`registerBean` 改为 finally 释放锁对象：

```java
Object initLock = serverInitLocks.computeIfAbsent(contractInstance, k -> new Object());
synchronized (initLock) {
    try {
        existing = serverBeans.get(contractInstance);
        if (existing != null) {
            return existing;
        }
        ServerBean bean = doRegister(contractInstance, config);
        serverBeans.put(contractInstance, bean);
        return bean;
    } finally {
        serverInitLocks.remove(contractInstance);
    }
}
```

### 4.5 修正 `currentLocalEndpoint()`

#### 问题

方法名是 local endpoint，但当前取的是 remote endpoint。

#### 修改

优先使用 `HybridSession` 的本地 TCP endpoint 方法，例如：

```java
InetSocketAddress local = session == null ? null : session.tcpLocalEndpoint();
```

如果 `HybridSession` 暂无该方法：

1. 在 `HybridSession` 增加 `tcpLocalEndpoint()`。
2. 或将 `currentLocalEndpoint()` 改名为符合实际语义的 `currentSessionEndpoint()`。

优先方案：补齐 `HybridSession.tcpLocalEndpoint()`，便于后续日志和诊断统一。

## 5. DNS 修复方案

### 5.1 A/AAAA coalescing key 加 queryType

#### 问题

当前 interceptor resolve 合并 key 是 domain 级别，同域名 A 和 AAAA 查询会共用同一个进行中的解析结果。后续 `newAddressResponse()` 虽按 query type 过滤，但容易产生 NOERROR 空答案缓存或 IPv4/IPv6 语义混乱。

#### 修改

新增：

```java
String resolveKey(String domain, DnsRecordType queryType) {
    return domainKeyCache.get("*:" + queryType.name() + ":" + domain,
            k -> DOMAIN_PREFIX.concat("int:*:").concat(queryType.name()).concat(":").concat(domain));
}
```

`DnsResolveCore.resolve()` 中：

```java
String resolveKey = server.resolveKey(domain, queryType);
```

#### 暂不修改

- 不加入 `srcIp` 到 key。
- 不修改 `ResolveInterceptor` 接口签名。

### 5.2 domain/hosts/cache key 统一 normalize lower-case

#### 修改

在 `DnsResolveCore.normalizeDomain` 中统一小写：

```java
static String normalizeDomain(String questionName) {
    int len = questionName.length();
    String domain = len > 0 && questionName.charAt(len - 1) == '.'
            ? questionName.substring(0, len - 1)
            : questionName;
    return domain.toLowerCase(Locale.ROOT);
}
```

在 `DnsServer` 中新增内部方法：

```java
String normalizeHost(String host) {
    return DnsResolveCore.normalizeDomain(host);
}
```

所有以下方法统一 normalize：

- `addHosts`
- `removeHosts`
- `getHosts`
- `getAllHosts`
- `cacheKey`
- `resolveKey`

#### 注意

hosts 文件读取时也应 normalize host，不改变 IP 原始字符串。

### 5.3 DoH response body 异常路径 release

#### 问题

`DoHServerHandler` 里分配 body 后，如果 `DoHMessageCodec.encodeResponse()` 抛异常，body 还未移交给 `FullHttpResponse`，需要释放。

#### 修改

```java
ByteBuf body = ctx.alloc().buffer(Math.max(64, dnsResponse.count(DnsSection.ANSWER) * 32));
boolean handedOff = false;
try {
    DoHMessageCodec.encodeResponse(body, dnsResponse);
    FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, body);
    handedOff = true;
    ...
} finally {
    if (!handedOff) {
        body.release();
    }
}
```

外层 finally 仍保留：

```java
ReferenceCountUtil.release(dnsResponse);
query.release();
```

### 5.4 移除冗余 `resolvingKeys`

#### 修改

删除 `DnsServer` 字段：

```java
final Set<String> resolvingKeys = ConcurrentHashMap.newKeySet();
```

删除 `DnsResolveCore` 中对应 add/remove：

```java
server.resolvingKeys.add(resolveKey);
server.resolvingKeys.remove(resolveKey);
```

实际 coalescing 只保留：

```java
final Map<String, Promise<List<InetAddress>>> resolvingPromises = new ConcurrentHashMap<>();
```

## 6. Nameserver 修复方案

### 6.1 dispose 取消 healthTask

#### 修改

`NameserverClient.dispose()`：

```java
@Override
protected void dispose() {
    group.remove(holder);
    for (NsInfo tuple : holder) {
        if (tuple.healthTask != null) {
            tuple.healthTask.cancel(false);
            tuple.healthTask = null;
        }
        tryClose(tuple.ns);
    }
    holder.clear();
}
```

如 `RandomList` 不支持 clear，则遍历后不强制 clear。

### 6.2 retry timer key 改为 tuple/registerEndpoint 级别

#### 问题

当前失败重试 key 使用 `appName`，同 app 多 nameserver 会互相覆盖。

#### 修改

```java
Object retryKey = tuple;
Tasks.setTimeout(() -> {
    ...
}, DEFAULT_RETRY_PERIOD, retryKey, TimeoutFlag.SINGLE.flags(TimeoutFlag.PERIOD));
```

或者：

```java
String retryKey = appName + "@" + regEp;
```

优先使用 `tuple`，避免字符串分配与 endpoint 格式差异。

### 6.3 replica UDP 同步使用 snapshot DTO

#### 问题

当前直接发送 `svrEps` 和 `attrs`，它们是 live concurrent collection。异步序列化期间集合可能变化，且协议暴露 ConcurrentHashMap/KeySetView 等实现细节。

#### 修改

新增 DTO：

```java
@RequiredArgsConstructor
@ToString
static class ReplicaSnapshot implements Serializable {
    private static final long serialVersionUID = 1L;
    final long version;
    final Set<InetSocketAddress> serverEndpoints;
    final Map<Object, Map<String, Serializable>> attrs;
}
```

发送 register endpoints：

```java
Set<InetSocketAddress> snapshot = new HashSet<>(svrEps);
ss.sendAsync(Sockets.newEndpoint(ssAddr, getSyncPort()), snapshot);
```

发送 attrs：

```java
Map<Object, Map<String, Serializable>> snapshot = snapshotAttrs();
ss.sendAsync(Sockets.newEndpoint(ssAddr, getSyncPort()), snapshot);
```

工具方法：

```java
Map<Object, Map<String, Serializable>> snapshotAttrs() {
    Map<Object, Map<String, Serializable>> snapshot = new HashMap<>();
    attrs.forEach((k, v) -> snapshot.put(k, new HashMap<>(v)));
    return snapshot;
}
```

### 6.4 replica 增加版本与低频全量同步

#### 修改

在 `NameserverImpl` 中增加：

```java
final AtomicLong replicaVersion = new AtomicLong();
volatile long lastAppliedReplicaVersion;
```

每次本地 registry/attrs 变更时：

```java
long version = replicaVersion.incrementAndGet();
```

新增全量同步 DTO：

```java
@RequiredArgsConstructor
@ToString
static class ReplicaFullSync implements Serializable {
    private static final long serialVersionUID = 1L;
    final long version;
    final Set<InetSocketAddress> serverEndpoints;
    final Map<String, List<InetAddress>> hosts;
    final Map<Object, Map<String, Serializable>> attrs;
}
```

新增周期任务：

```java
Future<?> replicaFullSyncTask;
```

构造函数中启动：

```java
replicaFullSyncTask = Tasks.schedulePeriod(this::syncFullSnapshot, config.getReplicaFullSyncPeriodMillis());
```

如果不想改 config，可先使用常量：

```java
static final int DEFAULT_REPLICA_FULL_SYNC_PERIOD = 60_000;
```

`close()` 中取消：

```java
if (replicaFullSyncTask != null) {
    replicaFullSyncTask.cancel(false);
}
```

#### 接收策略

- 收到 `ReplicaFullSync`：直接 merge 全量 endpoints/attrs/hosts。
- 收到旧版本增量：可忽略。
- 收到版本跳跃：暂不主动拉取，依赖下一轮 full sync 收敛。

### 6.5 instance attrs key 避免同 IP 多实例冲突

#### 问题

当前 instance attrs 使用 `InetAddress` 作为 key。同一 IP 多实例、NAT、k8s rolling update 场景下会覆盖。

#### 修改

引入 instance key：

```java
static final class InstanceKey implements Serializable {
    private static final long serialVersionUID = 1L;
    final String appName;
    final InetAddress address;
    final String instanceId;
}
```

实际最小改法：

1. `Nameserver.register()` 时从客户端 instance attr 或 `RxConfig.INSTANCE.getId()` 获取 instanceId。
2. 若调用 `register()` 时还没有 instance attr，则先使用 `appName + "#" + address.getHostAddress()`，后续 `instanceAttr(APP_ID)` 时迁移到 instanceId key。
3. `getDiscoverInfos()` 根据 host address 聚合多个 instance attr，优先返回当前 address 下匹配 appName 的所有 instance 信息。

#### 更推荐的兼容改法

短期先把 key 从 `InetAddress` 改成：

```java
String instanceKey(String appName, InetAddress address) {
    return appName + "#" + address.getHostAddress();
}
```

涉及方法：

- `doRegister`
- `doDeregister`
- `instanceAttr`
- `getDiscoverInfos`
- `syncAttributes`

后续再做真正 instanceId 化。

## 7. 测试计划

### 7.1 RemotingTest

新增/修改：

1. `eventSubscribeShouldAttachServerListenerOnce`
   - 两个 client 订阅同一 event。
   - server publish 一次。
   - 每个 client 只收到一次。

2. `eventResubscribeAfterReconnectShouldNotDuplicateBroadcast`
   - client stateful 订阅事件。
   - 模拟断线重连，触发重订阅。
   - server publish 一次。
   - client 只收到一次。

3. `poolModeShouldRejectEventSubscription`
   - 使用 `RpcClientConfig.poolMode`。
   - 调用 `attachEvent`。
   - 断言抛出 `InvalidException`。

4. `registerBeanShouldCleanupInitLockOnFailure`
   - 构造启动失败场景。
   - 断言 `serverInitLocks` 不残留。

### 7.2 DnsOptimizationTest / DnsServerIntegrationTest

新增/修改：

1. `aAndAaaaShouldUseDifferentResolveKey`
   - interceptor 返回 IPv4。
   - 并发查询 A 和 AAAA。
   - 断言 A 有 A 记录，AAAA 不因 A 查询写入错误缓存。

2. `domainShouldBeCaseInsensitive`
   - addHosts("Example.COM", ip)。
   - 查询 `example.com.` 和 `EXAMPLE.COM.`。
   - 均命中 hosts。

3. `dohEncodeFailureShouldReleaseBody`
   - 使用 EmbeddedChannel 或可控 codec 触发 encodeResponse 异常。
   - 开启 Netty leak detector 验证无泄漏。

4. `resolvingKeysRemovedNoFunctionalRegression`
   - 并发相同 domain 查询。
   - interceptor 只被调用一次。

### 7.3 NameserverImplTest / NameserverClientTest

新增/修改：

1. `disposeShouldCancelHealthTask`
   - registerAsync 后 close。
   - 验证 healthTask cancel。

2. `retryTimerShouldBePerNameserver`
   - 两个 register endpoint 都失败。
   - 验证两个 retry 不互相覆盖。

3. `replicaSyncShouldSendSnapshotNotLiveMap`
   - 触发 syncAttributes。
   - 修改原 attrs。
   - 验证已发送对象不受后续修改影响。

4. `replicaFullSyncShouldConvergeAfterDroppedDelta`
   - 模拟丢失 deregister 增量。
   - 下一次 full sync 后副本收敛。

5. `instanceAttrsShouldNotConflictBySameIpDifferentApp`
   - 同 IP 注册两个 app。
   - 设置 instanceAttr。
   - discover 返回各自属性，不互相覆盖。

## 8. 分阶段实施

### Phase 1：低风险修复

1. `serverInitLocks` finally 释放。
2. `currentLocalEndpoint()` 修正。
3. `resolvingKeys` 删除。
4. DoH body 异常释放。
5. NameserverClient healthTask cancel。
6. retry key 改 tuple。

预期影响：低。

### Phase 2：Remoting 事件语义修复

1. EventBean 增加 `listenerAttached`。
2. SUBSCRIBE 只 attach 一次。
3. poolMode 禁止事件订阅。
4. 补充事件重复广播测试。

预期影响：中。需要重点回归事件广播和重连重订阅。

### Phase 3：DNS A/AAAA 与大小写修复

1. resolveKey 加 queryType。
2. domain/host/cache key normalize lower-case。
3. 补充 A/AAAA 并发和大小写测试。

预期影响：中。需要回归 hosts、interceptor、upstream fallback。

### Phase 4：Nameserver replica 一致性修复

1. replica 发送 snapshot。
2. 引入 replica version。
3. 引入周期 full sync。
4. instance attrs key 从 InetAddress 改为 appName + address。

预期影响：中高。需要重点回归多副本注册、下线、discover、HTTP page 展示。

### Phase 5：RPC executor/offload 能力

1. RpcServerConfig 增加 executor。
2. Remoting 方法执行拆为 `invokeAndReply`。
3. 可选 offload。
4. 补充 executor 场景测试。

预期影响：中。默认关闭，兼容性较好；开启时需确认跨线程 send 安全。

## 9. 验收标准

1. 所有新增测试通过。
2. 现有以下测试通过：
   - `RemotingTest`
   - `DnsServerIntegrationTest`
   - `DnsOptimizationTest`
   - `DnsTcpPortMuxHandlerTest`
   - `NameserverImplTest`
3. 开启 Netty leak detector 后，DNS/DoH 相关测试无泄漏报警。
4. Remoting 多客户端订阅同一事件，server 发布一次，每个客户端只收到一次。
5. Remoting 客户端重连后事件不重复广播。
6. poolMode 事件订阅明确失败。
7. DNS hosts 查询大小写不敏感。
8. A/AAAA 并发查询不互相污染。
9. NameserverClient close 后不再执行 health handshake。
10. Nameserver replica 丢失单次 UDP 增量后，可通过 full sync 收敛。

## 10. 风险与回滚

### 10.1 Remoting 事件 listener 一次化

风险：如果原设计依赖多 listener 副作用，一次化后行为变化。

回滚：恢复 SUBSCRIBE 每次 attach，但不建议长期保留。

### 10.2 DNS lower-case normalize

风险：极少数非标准大小写敏感 host key 行为改变。

回滚：仅对 query normalize，不对 addHosts normalize。但 DNS 标准语义应大小写不敏感，建议不回滚。

### 10.3 Nameserver replica full sync

风险：全量同步包较大时增加 UDP 分片/丢包风险。

规避：

- full sync 周期默认 60s 起步。
- 包过大时改为 TCP/RPC 拉取 full snapshot。
- 或拆分多个 UDP 包并加 sequence。

### 10.4 RPC executor

风险：跨线程调用 `HybridSession.send()` 如果底层未完全线程安全，可能导致竞态。

规避：

- 默认关闭。
- 如果发现 send 非线程安全，改为 `session.eventLoop().execute(() -> session.send(...))`。

## 11. 不做事项记录

本轮不修复 DNS interceptor cache 按 `srcIp` 隔离问题。后续如 interceptor 需要按客户端 IP、区域、线路、租户返回不同 DNS 结果，应单独新增计划：

- `DnsInterceptorSourceAwareCache-plan.md`

建议该计划再评估：

1. `srcIp` 精确 key、CIDR key、region key 哪个更合适。
2. cache 容量和过期策略。
3. negative cache 是否按 source 维度隔离。
4. coalescing 是否按 source 维度隔离。
