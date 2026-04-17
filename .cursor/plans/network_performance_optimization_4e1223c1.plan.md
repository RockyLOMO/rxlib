---
name: Network Performance Optimization
overview: 对 DnsServer、Remoting、ShadowsocksServer 三条核心数据路径进行系统性性能审计，按“低风险高收益优先、协议重设计后置”的原则给出分层优化方案：消除热点路径对象分配、降低锁竞争、改善编解码与日志开销、补全背压机制，并补齐测试与监控验收矩阵。
todos:
  - id: dns-string-alloc
    content: DnsHandler 域名规范化与 cache key 分配收敛（禁用 intern）
    status: pending
  - id: dns-log-downgrade
    content: DnsHandler 日志降级为 debug + 惰性求值
    status: pending
  - id: dns-hosts-copy
    content: DnsServer.getHosts() 避免每次 ArrayList 拷贝
    status: pending
  - id: dns-cache-memory
    content: interceptorCache 改用 MemoryCache 替代 H2StoreCache
    status: pending
  - id: dns-coalesce
    content: 雷鸣羊群并发请求合并（Promise 模式）
    status: pending
  - id: rpc-codec
    content: Remoting 传输 Codec 可插拔化，RPC 协议分层演进
    status: pending
  - id: rpc-object-pool
    content: RPC 请求对象生命周期梳理，谨慎评估池化边界
    status: pending
  - id: rpc-lazy-log
    content: Remoting 与 Sys.callLog 描述/参数快照改按策略延迟生成
    status: pending
  - id: rpc-event-lock
    content: EventBean synchronized+wait 改 Promise 异步模型
    status: pending
  - id: ss-backpressure
    content: SSTcpProxyHandler 恢复 BackpressureHandler 背压机制
    status: pending
  - id: ss-udp-alloc
    content: SSUdpProxyHandler 避免重复目标地址封装，但不按 channel 固定缓存
    status: pending
  - id: ss-udp-buf
    content: UdpBackendRelayHandler 使用安全的池化 CompositeByteBuf 组包
    status: pending
  - id: ss-crypto-factory
    content: 先基准化 ICrypto 构造成本，仅缓存无状态元数据
    status: pending
  - id: cross-attrkey
    content: AttributeKey.valueOf 改为 static final 常量
    status: pending
  - id: cross-log-hot
    content: 三模块数据路径日志统一降级
    status: pending
  - id: cross-validation
    content: 补充性能基准、回归测试与监控验收矩阵
    status: pending
isProject: false
---

# rxlib 网络层性能优化方案

**模式：高性能模式（Netty 底层网络编程）**

以下按 **影响面从大到小** 排列，每项标注影响级别（P0 最高）。

---

## 一、DnsServer / DnsHandler 优化

### 1.1 [P0] 热路径字符串分配消除

[DnsHandler.java](rxlib/src/main/java/org/rx/net/dns/DnsHandler.java) 第 46、63 行：

```java
String domain = question.name().substring(0, question.name().length() - 1); // 每查询 1 次 substring
String k = DOMAIN_PREFIX + domain; // 每查询 1 次 concat
```

**问题：** 每条 DNS 查询至少 2 次字符串分配，高 QPS（万级/秒）下造成 Young GC 压力。

**优化方案：**
- 将 `question.name()` 的标准化提取为独立 helper，避免重复 `name()/length()` 调用，并接受“若 cache API 仍要求 `String`，则保留一次受控字符串分配”的现实边界
- 若基准确认 `DOMAIN_PREFIX + domain` 有明显占比，可引入**有容量上限与 TTL 的 domain->cacheKey 本地缓存**，而不是每次重新拼接
- 明确禁止 `intern()`；DNS 域名来自外部输入，高基数场景会放大全局字符串池内存风险
- 不把 `FastThreadLocal<StringBuilder>` 作为主方案；最终 cache key 仍需落成 `String`，Builder 复用不能消除关键分配

### 1.2 [P0] DNS 查询日志降级

[DnsHandler.java](rxlib/src/main/java/org/rx/net/dns/DnsHandler.java) 第 51、131、150 行：

```java
log.info("dns query {}+{} -> {}[HOSTS]", srcIp, domain, ips.get(0).getHostAddress());
log.info("dns query {}+{} -> {}[ANSWER]", srcIp, domain, count);
log.info("dns query {}+{} -> {}[SHADOW]", srcIp, domain, ips.get(0).getHostAddress());
```

**问题：**
- 每条 DNS 查询触发 `log.info`，即使日志级别关闭，`getHostAddress()` 仍会被调用（先求值再传参）
- `getHostAddress()` 内部有字符串格式化与分配

**优化方案：**
- 全部降为 `log.debug`，或加 `if (log.isDebugEnabled())` 守卫
- `srcIp` 参数改为惰性求值（传 `InetAddress` 对象让 SLF4J 的 `toString()` 延迟调用）
- 去掉 `getHostAddress()` 显式调用，直接传 `InetAddress` 对象

### 1.3 [P1] getHosts() 每次查询返回新 ArrayList

[DnsServer.java](rxlib/src/main/java/org/rx/net/dns/DnsServer.java) 第 114 行：

```java
return enableHostsWeight ? Linq.from(ips.next(), ips.next()).distinct().toList() : new ArrayList<>(ips);
```

**问题：** 非加权模式下每次 DNS hosts 命中都 `new ArrayList<>(ips)` 拷贝整个列表。

**优化方案：**
- 在 `RandomList` 内部增加“内容变更即失效”的只读快照，`getHosts()` 直接返回该快照，避免每次重新拷贝
- 非加权模式优先返回快照，不直接暴露 `RandomList` 活视图；`Collections.unmodifiableList(ips)` 只能防写，不能解决底层集合仍在变化的问题
- 加权模式下 `Linq.from(...).distinct().toList()` 也有分配，可改为直接比较两个 `next()` 结果后返回单元素或双元素结果

### 1.4 [P1] interceptorCache 使用 H2StoreCache 过重

[DnsServer.java](rxlib/src/main/java/org/rx/net/dns/DnsServer.java) 第 78 行：

```java
interceptorCache = (Cache) cache;  // H2StoreCache.DEFAULT
```

**问题：** DNS 拦截器缓存使用磁盘型 H2 数据库，L1 内存层 maximumSize=2048，L2 涉及 `findById` / `save` 持久化操作。DNS 解析是高频低延迟场景，磁盘 I/O 与序列化开销不匹配。

**优化方案：**
- DNS 拦截器缓存改用纯 `MemoryCache`，配合 `CachePolicy.absolute(ttl)` 即可
- 若需持久化（重启恢复），可在后台异步写 H2，查询路径只走内存
- L1 容量从 2048 提升到至少 8192（DNS 域名集合通常较大）

### 1.5 [P2] 雷鸣羊群 fallback 放大上游负载

[DnsHandler.java](rxlib/src/main/java/org/rx/net/dns/DnsHandler.java) 第 71 行：

```java
if (server.resolvingKeys.add(k)) { ... } else { /* fall through to upstream */ }
```

**问题：** 并发请求同一域名时，首个线程走拦截器解析，其余直接 fallback 到上游 DNS，可能对上游造成放大效应。

**优化方案：**
- 优先使用 per-domain 的 Netty `Promise<List<InetAddress>>`；首个请求创建并解析，后续请求直接 `addListener` 复用结果
- Promise 完成后在各自请求对应的 `Channel.eventLoop()` 上回写响应，避免跨线程直接操作 `ChannelHandlerContext`
- 补充超时、异常完成与 map 清理逻辑，防止 promise 表在失败路径泄漏；若等待超时，再按策略决定回源上游或返回失败

---

## 二、Remoting 优化

### 2.1 [P0] 默认 Java 序列化需拆分为“传输层优化”和“RPC 协议重设计”

[TcpServer.java](rxlib/src/main/java/org/rx/net/transport/TcpServer.java) 与 [StatefulTcpClient.java](rxlib/src/main/java/org/rx/net/transport/StatefulTcpClient.java) pipeline 中默认使用 Java 原生序列化：

**问题：**
- Java 序列化本身在吞吐、分配和反序列化延迟上都偏重
- 但当前 Remoting 协议并非静态 Schema：`MethodMessage.parameters` 是 `Object[]`，`returnValue` 是 `Object`，事件链路还包含可变 `EventArgs`
- 因此“直接把 `ObjectDecoder/ObjectEncoder` 换成 Protobuf”不是短期优化，而是一次协议重设计

**优化方案（渐进式）：**
- **短期**：先把 Transport 层抽象为可插拔 Codec/Frame 策略，但默认保持现有协议与兼容行为不变；通过基准确认瓶颈是否真的落在 Java 序列化
- **中期**：在 RPC 层梳理热路径消息模型，先为高频 contract/事件建立**显式类型注册和版本协商**，解决 `Object[]/Object/EventArgs` 的兼容问题
- **长期**：在新旧协议可双栈运行、可灰度迁移后，再把热点 RPC 路径切到 `LengthFieldBasedFrameDecoder + 自定义二进制 Codec`；不要把“换 Protobuf”当作当前协议的直接替换

### 2.2 [P2] 每次 RPC 调用的对象分配

[Remoting.java](rxlib/src/main/java/org/rx/net/rpc/Remoting.java) 第 100、146 行：

```java
ClientBean clientBean = new ClientBean();  // 每次调用
pack = clientBean.pack = new MethodMessage(generator.increment(), m.getName(), args, ThreadPool.traceId());  // 每次调用
```

**问题：** 每次 RPC 方法调用至少分配 `ClientBean` + `MethodMessage` + `ResetEventWait`（内嵌于 `ClientBean`），但这些对象会跨异步等待、`clientBeans` 挂表以及重连重发路径存活，不是纯粹的线程内短命对象。

**优化方案：**
- 第一阶段**不直接引入 `ClientBean/MethodMessage` 对象池**；先补齐生命周期图，确认对象何时可安全释放、是否会被重连逻辑再次发送
- 优先采用低风险减分配手段：缩短等待对象生命周期、减少日志/trace 路径的额外对象创建、避免不必要的包装层
- 若基准证明请求对象分配确为主要瓶颈，只允许对**不跨异步边界、不进入 in-flight map、不参与重发**的临时对象做池化；请求包本身不得在边界不清晰前池化

### 2.3 [P1] String.format 日志与 callLog 开销

[Remoting.java](rxlib/src/main/java/org/rx/net/rpc/Remoting.java) 第 215-217、464-465 行：

```java
String.format("Client %s.%s [%s -> %s]", contract.getSimpleName(), methodMessage.methodName, ...)
String.format("Server %s.%s [%s -> %s]", contractInstance.getClass().getSimpleName(), pack.methodName, ...)
```

**问题：** 每次 RPC 调用无条件执行 `String.format` + `Sockets.toString()`，即使 callLog 内部不输出。

**优化方案：**
- `Remoting` 调用点的描述字符串改为惰性生成，避免每次无条件执行 `String.format` + `Sockets.toString()`
- `Sys.callLog` 入口进一步改造为“先判定 `LogStrategy` / matcher，再按需构造描述和参数快照”；仅改调用点还不够，因为当前 `callLog` 会提前生成 `paramSnapshot`
- 控制日志策略默认值，避免高频 RPC 路径在未输出日志时仍做对象转 JSON / 参数截断

### 2.4 [P1] synchronized(eventBean) 持锁等待

[Remoting.java](rxlib/src/main/java/org/rx/net/rpc/Remoting.java) 第 384-421 行：

```java
synchronized (eventBean) {
    // ... 
    eventBean.wait(s.getConfig().getConnectTimeoutMillis());  // 持锁阻塞等待
    broadcastTargets = collectBroadcastTargets(bean, eventBean, eCtx);
}
```

**问题：** COMPUTE_ARGS 场景中，在 `synchronized(eventBean)` 块内执行 `eventBean.wait()`，阻塞时间可达 `connectTimeoutMillis`（秒级）。期间所有同名事件的 SUBSCRIBE/PUBLISH/COMPUTE_ARGS 都被阻塞。

**优化方案：**
- 将 COMPUTE_ARGS 的等待模型改为 Netty `Promise`（必要时用 `CompletableFuture` 适配上层），避免在同步块内 wait
- `EventContext` 内嵌 `Promise<EventArgs>`，由 COMPUTE_ARGS 返回时 complete
- 广播目标收集移到 wait 完成后，缩短持锁范围

### 2.5 [P2] collectBroadcastTargets 每次 new ArrayList

[Remoting.java](rxlib/src/main/java/org/rx/net/rpc/Remoting.java) 第 490-506 行：

```java
List<TcpClient> targets = new ArrayList<>();
for (TcpClient client : eventBean.subscribe) { ... targets.add(client); }
```

**优化方案：**
- 首选 `new ArrayList<>(eventBean.subscribe.size())` 预分配容量，降低扩容次数
- 不将 `FastThreadLocal<ArrayList>` 作为默认方案；该列表会逃逸到锁外发送阶段，复用边界不如直接预分配清晰

### 2.6 [P2] ResetEventWait 虚假唤醒风险

[ResetEventWait.java](rxlib/src/main/java/org/rx/core/ResetEventWait.java) 的 `waitOne(timeoutMillis)` 超时分支：

**问题：** `wait()` 返回后若 `!open` 直接 `return false`，未区分虚假唤醒与真正超时，可能导致 RPC 调用提前超时返回。

**优化方案：**
- 超时 wait 分支改为 `while (!open && elapsed < timeout)` 循环，使用 `System.nanoTime()` 精确计算剩余等待时间

---

## 三、ShadowsocksServer 优化

### 3.1 [P0] 背压机制缺失

[SSTcpProxyHandler.java](rxlib/src/main/java/org/rx/net/socks/SSTcpProxyHandler.java) 中 `BackpressureHandler.install` 被注释：

**问题：** TCP 代理通道无背压控制，高流量时入站/出站缓冲区无限膨胀，可能导致 OOM 或 Direct Memory 耗尽。

**优化方案：**
- 恢复 `BackpressureHandler.install`，在 relay handler 安装到 inbound 和 outbound 两端
- 配合 `WriteBufferWaterMark` 设置合理水位（建议 32KB / 64KB）
- 确保 `ShadowsocksConfig` 的 `OptimalSettings` 已配置
- 为背压状态补充指标：不可写次数、暂停时长、恢复次数、堆外内存占用

### 3.2 [P1] SSUdpProxyHandler 每包对象分配

[SSUdpProxyHandler.java](rxlib/src/main/java/org/rx/net/socks/SSUdpProxyHandler.java) 第 98 行：

```java
UnresolvedEndpoint dstEp = new UnresolvedEndpoint(inbound.attr(ShadowsocksConfig.REMOTE_DEST).get());
```

**问题：** 每个 UDP 数据包都创建新的 `UnresolvedEndpoint` 对象，高 QPS UDP 场景下分配压力大。

**优化方案：**
- UDP 模式下**不能**把目标地址按 channel 生命周期固定缓存；`SSProtocolCodec` 会对每个数据包重新 decode 并更新 `REMOTE_DEST`
- 如需优化，改为 `SSProtocolCodec` 在每包 decode 后直接写入 `UnresolvedEndpoint`，后续 handler 读取同一对象，避免 `InetSocketAddress -> UnresolvedEndpoint` 的重复封装
- `routeMap` 仍按“每包最新目标地址”查找，确保同一 UDP association 发往多目标时不会串路由

### 3.3 [P1] UdpBackendRelayHandler 每包 buffer 分配

[SSUdpProxyHandler.java](rxlib/src/main/java/org/rx/net/socks/SSUdpProxyHandler.java) 第 71 行：

```java
ByteBuf addrBuf = ctx.alloc().buffer(64);
UdpManager.encode(addrBuf, realDstEp);
ByteBuf finalBuf = Unpooled.wrappedBuffer(addrBuf, outBuf.retain());
```

**问题：** 每个入站 UDP 响应包都分配 64 字节 `ByteBuf` 用于地址编码。

**优化方案：**
- 保留“每包一个小 header buffer”的安全边界，但统一使用池化 `CompositeByteBuf` 组包，减少包装对象与堆分配
- 将组包逻辑下沉为辅助方法，例如 `UdpManager` 提供基于 `ByteBufAllocator` 的地址头编码 + `CompositeByteBuf` 拼装
- 明确禁止 `FastThreadLocal<ByteBuf>` 复用该 header；它会跨异步 `writeAndFlush` 生命周期逃逸，存在数据覆盖与引用计数失配风险

### 3.4 [P2] 先基准化 ICrypto.get() 成本，避免缓存有状态实例

[ShadowsocksServer.java](rxlib/src/main/java/org/rx/net/socks/ShadowsocksServer.java) 第 43-44 行：

```java
ICrypto _crypto = ICrypto.get(config.getMethod(), config.getPassword());
```

**问题：**
- 每个新连接（TCP/UDP channel init）都会调用 `ICrypto.get()` 创建实例
- 但当前 `CipherKind.newInstance()` 实际主要是 `new AesGcmCrypto(...)`，不应在没有基准数据前假设它是主热点
- `AesGcmCrypto`/`CryptoAeadBase` 持有 nonce、subkey、分段解密状态和 UDP/TCP 模式状态，实例天然是有状态对象

**优化方案：**
- 先用 benchmark / profiler 确认 `ICrypto.get()`、`AesGcmCrypto` 构造、密钥派生在建连路径中的占比，再决定是否优化
- 不缓存 `ICrypto` 实例，不做模板 `clone()`，也不做共享 ThreadLocal crypto；这些方案很容易破坏 AEAD 会话状态正确性
- 若确实需要优化，只缓存**无状态元数据**，例如算法枚举解析结果、密码派生辅助数据；每个连接仍创建独立 `ICrypto` 实例

### 3.5 [P2] routeMap 惰性初始化可做分支收敛

[SSUdpProxyHandler.java](rxlib/src/main/java/org/rx/net/socks/SSUdpProxyHandler.java) 第 102-104 行：

```java
ConcurrentMap<...> routeMap = inbound.attr(ATTR_ROUTE_MAP).get();
if (routeMap == null) {
    inbound.attr(ATTR_ROUTE_MAP).set(routeMap = MemoryCache...build().asMap());
}
```

**问题：** 在 Netty 的“单 channel 绑定单 EventLoop”模型下，这里通常不是实质性的并发正确性 bug，但运行时判空 + 建表逻辑仍然重复，且同类写法在多个 UDP handler 中都存在。

**优化方案：**
- 若顺手清理该逻辑，可改用 `attr.setIfAbsent()` 或在 channel init 阶段预初始化 `ATTR_ROUTE_MAP`
- 将它视为“代码整洁性与分支收敛”优化，而不是高优先级并发缺陷

---

## 四、跨模块共性优化

### 4.1 [P1] AttributeKey.valueOf(name) 动态查找

[TcpClient.java](rxlib/src/main/java/org/rx/net/transport/TcpClient.java) 接口的 `attr()` 方法每次按名字查找 AttributeKey：

**优化方案：**
- 高频路径改为 `static final AttributeKey<T>` 常量声明，一次注册后直接引用
- `TcpClient.attr(String)` 仍可保留作通用扩展接口，但 Remoting / Socks / DNS 热点路径应直接使用 typed key

### 4.2 [P2] 全局日志热点

三个模块在热路径上均大量使用 `log.info`，建议统一：
- 数据路径（每包/每查询）：仅 `log.debug`，且必须加 `isDebugEnabled()` 守卫
- 控制路径（连接建立/关闭/错误）：保持 `log.info`
- 异常路径：`log.warn` / `log.error`
- `Sys.callLog` 视为独立热点，需和普通 SLF4J 日志一起纳入治理

---

## 五、实施顺序与验收

### 5.1 建议实施顺序

**第一阶段：低风险高收益**
- DNS 查询日志降级与惰性求值
- `Sys.callLog` 按策略延迟构造描述与参数快照
- `DnsServer.interceptorCache` 评估切换到内存缓存或双层缓存
- `collectBroadcastTargets` 预分配容量
- `AttributeKey` 热点常量化

**第二阶段：并发与背压治理**
- DNS 同域名请求合并（Promise）
- `EventBean` 的 `wait/notify` 改 Promise 模型
- `ResetEventWait` 超时循环修正
- `SSTcpProxyHandler` 恢复背压并校准写水位
- UDP routeMap 初始化改 `setIfAbsent()` 或在 channel init 阶段预建

**第三阶段：协议与结构性优化**
- UDP 目标地址按包透传，消除重复封装
- UDP 组包统一改为池化 `CompositeByteBuf`
- Remoting 的 Transport Codec 抽象
- RPC 协议版本化、类型注册和双栈兼容方案

### 5.2 必测项

**单元测试**
- `ResetEventWait`：虚假唤醒、精确超时、无限等待
- `DnsServer.getHosts()`：单值、多值、加权与非加权返回语义
- UDP 目标路由：同一 channel 连续发送不同目标地址时不串路由
- `BackpressureHandler`：高低水位切换、异常恢复、关闭清理

**集成测试**
- `SocksProxyServerIntegrationTest`
- `ShadowsocksServerIntegrationTest`
- `Socks5ClientIntegrationTest`
- `RrpIntegrationTest`
- `RemotingTest`
- `DnsServerIntegrationTest`

### 5.3 性能与监控验收指标

- DNS：QPS、平均延迟、P99、每请求分配字节数、缓存命中率
- Remoting：QPS、RTT、P99、等待中的 `clientBeans` 数量、重连重发成功率
- Shadowsocks TCP/UDP：吞吐、P99、背压触发次数、丢包/重试情况
- 连接生命周期：当前连接数、建连失败率、半关闭/异常断开数量
- 内存：堆使用量、Young GC 频率、**堆外内存占用**、DirectBuffer OOM 告警

### 5.4 验收原则

- 小改动至少具备对应单元测试与基准对比
- 涉及网络链路、线程模型、背压、协议兼容的改动，必须补充集成测试
- 所有“优化”结论以 benchmark / profiler / 指标对比为准，不接受仅凭代码直觉进行高风险重构
