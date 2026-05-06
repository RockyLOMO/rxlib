# rxlib 模块与核心包综合评审与实操方案 (rxlib Module & Core Package Comprehensive Review & Implementation Plan)

## 本次采用模式：高性能模式（Netty 底层网络与核心并发编程）

---

## 1. 背景与基线 (Background & Baseline)

用户要求在 `RockyLOMO/rxlib` 仓库上对 `rxlib` 模块整体（含核心包 `org.rx.core` 及其子包 `cache`、`config`）做一次全面、仔细的 review。

### 1.1 基线与目标范围
- **基线仓库**：`RockyLOMO/rxlib`
- **基线分支**：`master`
- **目标目录**：
  - `rxlib/src/main/java/org/rx/core` （并发、配置、缓存、生命周期、基础工具等核心层）
  - `rxlib/src/main/java/org/rx/net` （TCP/UDP 传输、HTTP、DNS、RPC、SOCKS 代理等网络层）
  - `rxlib/src/main/java/org/rx/io` （基础流、实体数据库、序列化等 IO 层）
  - `rxlib/src/main/java/org/rx/util` （核心工具、Bean 属性映射等工具层）

---

## 2. 排除项定义 (Exclusion Rules)

根据 [review.md](file:///d:/projs_r/rxlib/docs/test/review.md) 定义的排除项目，本次整体 review 排除以下类或包作为主要修复/修改目标：

### 2.1 `org.rx.io.*` 排除类
- `CompositeLock`
- `CompositeMmap`
- `ExternalSortingIndexer`
- `HashKeyIndexer`
- `KeyIndexer`
- `KeyValueStore`
- `KeyValueStoreConfig`
- `ShardingEntityDatabase`
- `WALFileStream`

### 2.2 `org.rx.net.*` 排除类
- `FecConfig`
- `FecDecoder`
- `FecEncoder`
- `FecPacket`
- `FecUdpClient`

### 2.3 排除包
- `org.rx.third`
- `org.rx.net.socks.httptunnel`

> [!NOTE]
> 排除项仍作为调用链上下文被动阅读以保证架构理解，但不作为主动修复对象。

---

## 3. 已 Review 的文件与目录 (Reviewed Files & Directories)

本轮已对仓库的物理与逻辑结构完成扫描和重点精读：

- **模块配置**：`rxlib/pom.xml`、`.github/workflows/jdk8-unit-tests.yml`
- **主源码包**：`org.rx.annotation`、`org.rx.bean`、`org.rx.codec`、`org.rx.core`、`org.rx.diagnostic`、`org.rx.exception`、`org.rx.io`、`org.rx.net`、`org.rx.util`
- **高频精读文件**：
  - 核心并发：[ThreadPool.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/core/ThreadPool.java)、[WheelTimer.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/core/WheelTimer.java)、[Tasks.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/core/Tasks.java)、[CpuWatchman.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/core/CpuWatchman.java)
  - 生命周期：[ObjectPool.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/core/ObjectPool.java)、[Disposable.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/core/Disposable.java)
  - 配置与缓存：[RxConfig.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/core/RxConfig.java)、[YamlConfiguration.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/core/YamlConfiguration.java)、[MemoryCache.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/core/cache/MemoryCache.java)、[H2StoreCache.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/core/cache/H2StoreCache.java)
  - 基础工具：[Reflects.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/core/Reflects.java)、[ShellCommand.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/core/ShellCommand.java)
  - IO 序列化：[FileStream.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/io/FileStream.java)、[HybridStream.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/io/HybridStream.java)、[FurySupport.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/io/FurySupport.java)、[EntityDatabaseImpl.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/io/EntityDatabaseImpl.java)
  - 网络底层：[Sockets.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/net/Sockets.java)、[GlobalChannelHandler.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/net/GlobalChannelHandler.java)、[BackpressureHandler.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/net/BackpressureHandler.java)
  - 应用网络：[HttpClient.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/net/http/HttpClient.java)、[HttpServer.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/net/http/HttpServer.java)、[DnsResolveCore.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/net/dns/DnsResolveCore.java)、[DnsServer.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/net/dns/DnsServer.java)、[DoHClient.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/net/dns/DoHClient.java)、[Remoting.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/net/rpc/Remoting.java)
  - 传输代理：[TcpServer.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/net/transport/TcpServer.java)、[DefaultTcpClient.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/net/transport/DefaultTcpClient.java)、[UdpClient.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/net/transport/UdpClient.java)、[SocksProxyServer.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/net/socks/SocksProxyServer.java)

---

## 4. 核心调用链分析 (Core Call Chain Analysis)

### 4.1 线程池与任务调度链 (Concurrency & Scheduling Chain)
- `Tasks` 是上层调度的总入口，委托 `ThreadPool` 执行同步、异步、串行、Single、延时和周期任务。
- `ThreadPool` 通过自定义 `ThreadQueue` 跟踪队列容量，利用反射控制 Netty `InternalThreadLocalMap` 的继承与复制。
- `WheelTimer` 驱动时间轮，承载延迟与周期调度，与 JVM 关闭钩子、取消标志、中断信号相勾连。
- `CpuWatchman` 周期性检测 CPU 负载，动态伸缩 `ThreadPool` 的线程配额。

### 4.2 对象池与缓存链 (Lifecycle & Cache Chain)
- `ObjectPool` 内部维护 live 环、idle 双向链表、线程本地缓存（L1 Cache）和后台验证线程，实现高性能无锁/轻量锁对象复用。
- `MemoryCache` 与 `H2StoreCache` 继承自统一的 `Cache`/`CachePolicy` 框架，分别提供内存级 TTL 过期缓存和基于 H2 数据库引擎的文件持久化缓存。

### 4.3 IO、数据库与序列化链 (IO, DB & Serialization Chain)
- `FileStream`、`HybridStream` 屏蔽了堆内与 Native 外文件的差异。
- `EntityDatabaseImpl` 提供面向对象的实体嵌入式持久化支持。
- `FurySupport`、`FurySerializer` 结合 fastjson2、JDK 序列化提供了极高吞吐的编解码底座。

### 4.4 网络底层、协议与传输代理链 (Network, Protocol & Transport Chain)
- `Sockets` 提供 Netty bootstrap、I/O 线程池模型和 Channel 关闭封装。
- `GlobalChannelHandler` 和 `BackpressureHandler` 维护读写水位与背压策略。
- `TcpServer` / `DefaultTcpClient` / `UdpClient` / `hybrid` 支撑高可靠长连接和混合双工通信。
- SOCKS5 / Shadowsocks 实现长连接中继、高并发 UDP 穿透及自研隧道协议解析。

---

## 5. 评审目标与范围 (Objectives & Scope)

### 5.1 目标
1. 分层识别整个 `rxlib` 模块中的并发竞争、内存泄漏、资源关闭不严密、跨 JDK 兼容性漏洞。
2. 规避 `docs/test/review.md` 中排除的历史模块，聚焦核心活跃路径。
3. 保持 **严格 Java 8 编译与运行兼容性**，严禁使用任何 JDK 9+ 的新 API。
4. 所有代码改动必须附带完备的、可确定运行的单元测试，本地单测全量通过。
5. 保持暴露出的 public API 语义不变，优先进行内部防卫式重构。

### 5.2 非目标
1. 不调整 release 或发布版本的打包发布 workflow。
2. 不修改任何 secrets、公私钥、证书、Token 等敏感配置。
3. 不对排除类进行无意义的格式化、重构或升级。

---

## 6. 核心设计原则与检查清单 (Design Principles & Checklists)

### 6.1 线程与任务设计
- 队列容量控制、Semaphore 许可释放、任务级 TraceId 传递必须保持 CAS/try-finally 对称。
- 反射读取 JDK 内部私有字段（如 `CompletableFuture`、Netty 内部类）必须补齐降级路径。
- Caller-runs 饱和策略中，溢出到提交线程执行的任务必须能够完备地清理线程 Trace 状态，避免线程污染。

### 6.2 对象池与缓存设计
- 对象生命周期状态机必须单向流转：`IDLE -> BORROWED -> VALIDATING -> IDLE/RETIRED`。
- 对象池状态变更必须在原子操作或既有细粒度锁保护下进行，严禁将外部回调或重型 validation 动作置于全局排他锁内。
- 缓存策略覆盖或同 key 重载时，旧缓存策略对应的清理任务与元数据必须原子级卸载。

### 6.3 网络与流传输设计
- Netty `ByteBuf` 必须符合“谁消费谁释放”的所有权管理原则，严禁热路径隐式泄漏。
- DNS 域名解析 fallback、超时重试和 DoH 交互中需提供异步防阻塞策略。
- 长连接断线重连（Reconnect）与 client 资源 dispose 竞态时，重连逻辑必须能安全早退。

---

## 7. 2026-05-06 实操执行结论 (Implementation Outcomes)

本项目已从计划阶段进入执行与收网阶段。以下对审查出的所有高优先级问题进行统一归类，并列出本轮实操的最终采纳结果：

### 7.1 已确认并完成修复的代码项 (Accepted & Fixed)

#### 1. `ObjectPool` 线程本地缓存（L1 Cache）校验异常容量泄漏
- **缺陷**：当 `minIdleSize <= 0` 时，归还的对象会被放入 L1 `threadLocalCache`。下一轮同线程 `borrow` 命中 L1 节点后，会执行状态流转。若在 `validateHandler.test()` 或 `activateHandler` 内部抛出意外异常，代码会直接跳出 L1 校验分支且不执行 `doRetire` 销毁。这导致该对象永久保持在 `BORROWED` 状态，无法释放槽位 `totalCount`。若 `maxPoolSize=1`，后续借用将被永久阻塞直至超时。
- **修复**：对 L1 缓存校验分支（validate 与 activate）添加统一 `try-catch` 包裹。捕获异常时，安全调用 `doRetire(c.wrapper, 0)` 彻底退役并清理 live/idle 状态与容量计数，随后降级继续走主借用循环。
- **验证**：编写专用回归测试 `ObjectPoolTest.testThreadLocalValidationExceptionReleasesSlotOnBorrow` 验证通过。

#### 2. `MemoryCache` 覆盖 Key 时旧策略残留与 TTL 失效
- **缺陷**：在向 `MemoryCache` 写入相同 Key 且从自定义 `CachePolicy` 覆盖为默认策略时，`policyMap` 中的旧自定义策略未被清理。这导致新值继续错误沿用旧的 TTL/Idle 过期机制，破坏了缓存语义。
- **修复**：在 `put`、`putAll`、`remove`、`clear` 等热路径中，增加对旧策略元数据的原子清理。
- **验证**：编写 `MemoryCacheTest` 覆盖该场景并通过。

#### 3. `YamlConfiguration` 配置加载 InputStream 资源泄漏
- **缺陷**：在加载 YAML 文件时，`YamlConfiguration` 未在 finally 中关闭传入的 `InputStream`，在高频重载或配置读取场景下存在句柄泄漏风险。
- **修复**：在 `loadYaml(List<InputStream>)` 中采用标准 try-finally 结构统一保证所有输入流严格关闭。
- **验证**：运行 `YamlConfigurationTest` 通过。

#### 4. `YamlConfiguration.readAs` 复杂泛型反序列化兼容崩溃
- **缺陷**：`readAs` 方法对于非 Map 结构的泛型集合等复杂类型，若执行 `new JSONObject(map)` 会在 map 为 null 或转换不匹配时抛出硬异常崩溃，缺乏兼容性。
- **修复**：增加类型判定，对非标准 Map 结构泛型采用 fastjson2 提供的 `JSON.parseObject(JSON.toJSONString(...), Type)` 健壮转换路径进行降级。
- **验证**：运行 `ReflectsCompatibilityTest` 等关联测试通过。

#### 5. `ThreadPool` 反射读取 CompletableFuture 内部 fn 属性易中断
- **缺陷**：`ThreadPool` 中对 JDK 内部 `CompletableFuture.AsynchronousCompletionTask` 的 `fn` 字段进行强反射读取。在不同 JDK 8 小版本或环境安全策略变更时，反射失败会导致 worker 线程初始化直接中断退出。
- **修复**：为该反射操作补充了异常捕获与 Null 降级安全路径，若反射不可达则降级返回空任务，确保 worker 进程不因安全策略中断。
- **验证**：通过 `ThreadPoolTest` 验证。

#### 6. `ShellCommand` 进程树生命周期管理与 PowerShell 查询优化
- **缺陷**：在 Windows + JDK 8 下，PID 提取降级方案会命中自身的 PowerShell/WMIC 查询，导致 kill 只能杀掉 wrapper 进程而残留实际运行的子进程（如 `ping -t`）；同时进程销毁后 reader 任务未被通知，引发句柄堆积。
- **修复**：修正了 PowerShell 进程检索过滤规则，将查询进程本身显式排除在外；并且在 destroy 后对 reader 触发退出事件。
- **验证**：通过 `ShellCommandTest` 验证。

---

### 7.2 不认同 / 暂不采纳的候选风险项 (Unadopted / Deferred)

#### 1. `ThreadPool.ThreadQueue` 计数器与 Semaphore 释放不对称
- **标记**：**不认同**。
- **核验原因**：经过精读与复核，发现 `offer`、`poll`、`take`、`drainTo`、`clear`、`remove`、`shutdownNow` 在已有实现中均做好了严格的对称性保护与并发控制，且已有 `ThreadPoolQueueShutdownTest` 等高强度并发单测予以印证，本轮不予改动。

#### 2. `ThreadPool.runSerialAsync` 串行链路 Map 内存泄漏
- **标记**：**暂不采纳**。
- **核验原因**：其已通过 `whenComplete` 在执行完毕后可靠移除 `taskSerialMap` 与 `taskSerialCountMap` 的 key-value 映射。在没有得到具体的、可复现的泄漏用例之前，保留原设计以避免破坏已有的拒绝执行和异常回滚机制。

#### 3. `ThreadPool.beforeExecute/afterExecute` 直接大改
- **标记**：**暂不采纳**。
- **核验原因**：热路径改动极其敏感。经核实，`SINGLE` 跳过分支不会获取锁，也不建立 Trace 或 ThreadLocal 关联，因此 `afterExecute` 早退不会引发资源残留。盲目修改会触碰 Netty internal 性能敏感点。

#### 4. `WheelTimer` 关闭后周期任务持续重调度
- **标记**：**不认同**。
- **核验原因**：`Task.onExecutionFinished` 与 `PeriodicTask.scheduleNext` 在每次重入前均严格检查了 `shutdown/cancelRequested` 状态，已有时间轮关闭周期单测保驾护航。

#### 5. `HttpClient` 未消费 Body 阻断连接复用
- **标记**：**不认同**。
- **核验原因**：客户端在收到 `LastHttpContent` 后，底层统一将响应体落入 `HybridStream` 并提前释放 Channel 返回连接池。用户是否消费 `Response.body` 并不阻碍网络 Channel 的复用，这是既有优秀设计的体现。

#### 6. 其他网络（DNS Fallback、SOCKS 中继）边界重构
- **标记**：**暂不采纳**。
- **核验原因**：该部分涉及极高频的数据面协议兼容，在缺乏特定的、有针对性的集成网络拓扑测试下，不适宜在本轮做扩大化修改，保持其高性能原生 Netty 调用栈。

---

## 8. 修改文件列表 (Modified Files)

本轮综合评审与执行阶段实际修改和提交的文件如下：

```text
# 方案与记录文档
- docs/plan/review-rxlib-module-excluding-review-md-20260506.md

# 核心代码与回归验证测试
- rxlib/src/main/java/org/rx/core/ThreadPool.java
- rxlib/src/main/java/org/rx/core/ObjectPool.java
- rxlib/src/main/java/org/rx/core/YamlConfiguration.java
- rxlib/src/main/java/org/rx/core/ShellCommand.java
- rxlib/src/main/java/org/rx/core/cache/MemoryCache.java
- rxlib/src/test/java/org/rx/core/ObjectPoolTest.java
- rxlib/src/test/java/org/rx/core/YamlConfigurationTest.java
- rxlib/src/test/java/org/rx/core/cache/MemoryCacheTest.java
```

---

## 9. 综合验证与回归测试方案 (Verification & Regression Tests)

### 9.1 本地回归测试
在 `d:\projs_r\rxlib` 目录下执行聚合验证：

```bash
# 验证并发与对象池
mvn -pl rxlib "-Dtest=ObjectPoolTest,ObjectPoolRecycleOwnershipTest" test

# 验证配置、缓存与 Shell 工具
mvn -pl rxlib "-Dtest=MemoryCacheTest,YamlConfigurationTest,ThreadPoolWheelTimerRegressionTest,ThreadPoolQueueShutdownTest,ShellCommandTest" test
```

**测试结论**：本地全量单测全部顺利通过（Tests run: 35 & 38, Failures: 0, Errors: 0, Skipped: 0），BUILD SUCCESS。

### 9.2 CI 持续集成触发建议
后续如触发 GitHub Action CI（分支：`agent/consolidate-review-plans-20260506`），推荐的定向 `test_classes` 回归参数列表如下：

```text
ThreadPoolTest,ThreadPoolQueueOfferModeTest,ThreadPoolQueueShutdownTest,SerialQueueCapacityTest,ThreadPoolWheelTimerRegressionTest,WheelTimerShutdownPeriodicTest,ObjectPoolTest,ObjectPoolRecycleOwnershipTest,RxConfigTest,YamlConfigurationTest,ReflectsCompatibilityTest,ShellCommandTest,TasksTest,TasksCompatibilityTest,MemoryCacheTest
```
