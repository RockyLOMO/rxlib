# 背景

用户要求在 `RockyLOMO/rxlib` 仓库 `master` 分支上，参考 `docs/test/review.md` 中定义的排除项目，对 `rxlib` 模块整体做一次 review。

本次工作仅进入计划阶段：完成仓库结构扫描、排除范围识别、关键调用链和风险点梳理，并提交 review 计划文档。按流程约束，在用户明确要求“按计划执行 / 开始修改代码 / 继续写代码”前，不修改业务代码、不新增或修改测试代码。

# 任务类型判断

本次归类为 **Review / 修复 / 重构 / 优化需求**。

原因：

- 用户明确要求“整体做下 review”，属于现有实现审查。
- 任务目标不是新增功能，而是理解当前 `rxlib` 模块现状，找出潜在 bug、性能、并发、资源释放和测试风险。
- Review 类任务必须先 review 相关代码、生成并提交计划文档，等待用户明确继续后再进入代码修改阶段。

# 当前上下文

## 仓库与分支

- 仓库：`RockyLOMO/rxlib`
- 基准分支：`master`
- 基准提交：`05a89e526de48f885d9548a194caf054a0dedf04`
- 当前任务分支：`agent/rxlib-module-review-20260506`

## 排除规则

已读取 `docs/test/review.md`。本次 review 应排除以下类或包作为主要修复目标：

`org.rx.io.*` 中：

- `CompositeLock`
- `CompositeMmap`
- `ExternalSortingIndexer`
- `HashKeyIndexer`
- `KeyIndexer`
- `KeyValueStore`
- `KeyValueStoreConfig`
- `ShardingEntityDatabase`
- `WALFileStream`

`org.rx.net.*` 中：

- `FecConfig`
- `FecDecoder`
- `FecEncoder`
- `FecPacket`
- `FecUdpClient`

排除包：

- `org.rx.third`
- `org.rx.net.socks.httptunnel`

排除项仍可作为调用链上下文被动阅读，但不作为本次优先修复对象，除非后续用户明确要求。

## 已 review 的文件与目录

本轮已完成结构级扫描和重点文件阅读，范围包括：

- 模块配置：`rxlib/pom.xml`
- 排除清单：`docs/test/review.md`
- CI 配置：`.github/workflows/jdk8-unit-tests.yml`
- 主源码包：`org.rx.annotation`、`org.rx.bean`、`org.rx.codec`、`org.rx.core`、`org.rx.diagnostic`、`org.rx.exception`、`org.rx.io`、`org.rx.net`、`org.rx.util`
- 重点阅读文件：
  - `org.rx.core.ThreadPool`
  - `org.rx.core.ObjectPool`
  - `org.rx.core.RxConfig`
  - `org.rx.core.WheelTimer`
  - `org.rx.core.Tasks`
  - `org.rx.core.Sys`
  - `org.rx.core.ShellCommand`
  - `org.rx.io.EntityDatabaseImpl`
  - `org.rx.io.HybridStream`
  - `org.rx.io.FileStream`
  - `org.rx.io.FurySupport`
  - `org.rx.net.Sockets`
  - `org.rx.net.GlobalChannelHandler`
  - `org.rx.net.BackpressureHandler`
  - `org.rx.net.http.HttpClient`
  - `org.rx.net.http.HttpServer`
  - `org.rx.net.dns.DnsResolveCore`
  - `org.rx.net.dns.DnsServer`
  - `org.rx.net.dns.DoHClient`
  - `org.rx.net.nameserver.NameserverImpl`
  - `org.rx.net.rpc.Remoting`
  - `org.rx.net.transport.TcpServer`
  - `org.rx.net.transport.DefaultTcpClient`
  - `org.rx.net.transport.UdpClient`
  - `org.rx.net.transport.hybrid.*`
  - `org.rx.net.socks.*` 中非 `httptunnel` 部分
  - `org.rx.net.support.*`

## 测试覆盖现状

已扫描 `rxlib/src/test/java`：

- `org.rx.core` 下已有 `ThreadPoolTest`、`ThreadPoolQueueShutdownTest`、`ThreadPoolWheelTimerRegressionTest`、`ObjectPoolTest`、`ObjectPoolRecycleOwnershipTest`、`WheelTimerShutdownPeriodicTest`、`RxConfigTest` 等。
- `org.rx.io` 下已有 `EntityDatabaseTest`、`HybridStreamTest`、`FurySerializerTest`、`TestIO` 等。
- `org.rx.net` 下已有 `SocketsTest`、`BackpressureHandlerTest`、`GlobalChannelHandlerTest`、HTTP、DNS、RPC、transport、socks 相关测试。
- `.github/workflows/jdk8-unit-tests.yml` 支持 `workflow_dispatch`，并支持输入 `test_classes` 定向运行 JDK8 测试。

## 关键调用链

### 1. 线程池 / 定时器 / 任务执行链

- `Tasks` 暴露全局 executor / timer 能力。
- `ThreadPool` 自定义 `ThreadPoolExecutor` 队列、串行任务、single task、traceId 传播、FastThreadLocal 继承。
- `WheelTimer` / `Tasks.timer` 负责周期任务和延时任务。
- `CpuWatchman` 根据 CPU 水位动态调整 `ThreadPool`。
- `RxConfig` 控制线程池、trace、diagnostic 等行为。

主要风险集中在：线程池关闭与队列计数一致性、串行任务 map 清理、FastThreadLocal 复制兼容 Netty 内部实现、定时器 shutdown 后周期任务取消、caller-runs 模式中的上下文清理。

### 2. 对象池 / 缓存链

- `ObjectPool` 维护 live 环、idle 双向链表、threadLocal cache、定时 validate。
- `MemoryCache` / `H2StoreCache` 基于 `Cache` / `CachePolicy` 实现内存和持久化缓存。
- `ObjectPool` 被网络、HTTP client、缓冲对象等场景复用。

主要风险集中在：对象泄漏检测与 close 策略、validate 异常路径、borrow 超时、minIdle 自动补充、threadLocal 缓存与 shared idle 队列状态一致性。

### 3. IO / 序列化 / 数据库链

- `FileStream`、`HybridStream`、`BinaryStream` 提供基础流抽象。
- `EntityDatabaseImpl` 承担实体持久化、索引、查询和序列化。
- `FurySupport` / `FurySerializer` 与 `JdkAndJsonSerializer` 负责对象编码。

按排除规则，本轮不把 KV / mmap / WAL / indexer 类作为主要修复目标。非排除 IO 风险集中在流生命周期、临时文件释放、反序列化异常路径、EntityDatabase 的并发查询与资源关闭。

### 4. 网络 / HTTP / DNS / RPC / transport 链

- `Sockets` 提供 Netty bootstrap、channel 关闭、地址、端口、socket option 等工具。
- `HttpClient` / `HttpServer` 基于 Netty HTTP codec 与连接池。
- `DnsResolveCore`、`DnsClient`、`DnsServer`、`DoHClient` 覆盖 UDP/TCP/DoH DNS 解析。
- `Remoting` 使用 transport + codec 实现 RPC。
- `TcpServer`、`DefaultTcpClient`、`UdpClient`、`hybrid` transport 提供连接、重连、UDP/TCP 混合传输。
- `socks` 包中非 `httptunnel` 部分实现 socks5、shadowsocks、UDP relay、udp2raw、压缩、冗余、端口跳跃等。

主要风险集中在：Netty event loop 生命周期、ChannelFuture listener 异常吞吐、ByteBuf retain/release、UDP session 清理、RPC request future 清理、DNS fallback/timeout、HTTP client response body 关闭和连接复用。

# 目标

1. 形成 `rxlib` 模块整体 review 计划，覆盖 core / io / net / diagnostic / util 等主要区域。
2. 明确 `docs/test/review.md` 排除项，避免把历史或第三方/排除模块作为本次修复主体。
3. 后续进入代码阶段时，优先处理可通过最小改动修复且有测试支撑的问题。
4. 保持 JDK8 兼容，不引入 JDK9+ API。
5. 所有修复都必须具备可验证路径：定向 JDK8 单测优先，全量 JDK8 单测作为最终兜底。
6. 不改 secrets、token、证书、私钥，不发布 release。

# 非目标

1. 本计划阶段不修改业务代码。
2. 本计划阶段不新增或修改测试代码。
3. 不 review 或修复 `docs/test/review.md` 中明确排除的类/包，除非后续用户明确要求。
4. 不做大规模架构重写。
5. 不升级大版本依赖。
6. 不引入重型依赖。
7. 不改变公开 API 语义，除非发现明确 bug 且必须修复。
8. 不自动发布 Maven release / snapshot。

# 设计方案

## 阶段 1：收敛候选问题

后续如果用户要求继续执行，应先在当前计划范围内做二次精读，按风险优先级收敛候选问题：

1. 高优先级：可能造成死锁、线程泄漏、ByteBuf 泄漏、future 永久挂起、资源未关闭的问题。
2. 中优先级：边界条件下行为不一致、异常路径清理不完整、测试覆盖缺口。
3. 低优先级：可读性、日志、指标 tag、注释和轻微性能优化。

候选问题必须满足：有明确代码路径、能构造稳定 JDK8 测试、修复改动范围小、不涉及排除项作为主要目标。

## 阶段 2：核心设计原则

### 线程与任务类问题

- 保持 `ThreadPool` 的现有 API。
- 对计数器、queue permit、task map、serial map 的修复必须保持 CAS / finally 对称。
- 对 FastThreadLocal 传播的兼容性修复必须避免依赖新 JDK API。
- shutdown 行为优先补齐清理和测试，不改变既有关闭语义。

### 对象池类问题

- 保持 `borrow` / `recycle` API 语义。
- 修复必须保证状态机单向流转：`IDLE -> BORROWED -> VALIDATING -> IDLE/RETIRED`。
- idle 链表和 live 环的操作必须在既有锁策略下完成。
- 异常路径必须释放 reserved slot，避免 `totalCount` 偏移。
- 资源关闭使用现有 `Extends.tryClose` 风格。

### 网络类问题

- Netty `ByteBuf` / `ReferenceCounted` 对象必须明确所有权。
- Channel close / EventLoopGroup shutdown 不应阻塞 event loop 自身。
- request map / pending future / retry timer 必须有失败、超时、关闭三种清理路径。
- UDP session / relay / route manager 必须避免无限增长和重复关闭。
- DNS / HTTP / RPC 修复应优先加小型单元测试或本地 loopback 集成测试。

### IO / 序列化类问题

- 流关闭使用 try/finally，确保临时文件、native buffer、stream 均释放。
- 序列化异常应保持原异常上下文，不吞异常。
- `EntityDatabaseImpl` 变更必须先确认相关测试是否依赖磁盘状态和排序结果。

## 阶段 3：建议优先检查的问题池

后续代码阶段建议按以下顺序精修：

1. `ThreadPool`
   - 检查 `ThreadQueue.offer/poll/take/drainTo/clear` 中 `counter` 与 `availableSlots` 是否在所有异常路径严格对称。
   - 检查 `runSerialAsync` 中 `taskSerialMap`、`taskSerialCountMap` 在异常、取消、拒绝执行时是否彻底清理。
   - 检查 `beforeExecute/afterExecute` 中 `THREAD_TRACE`、FastThreadLocal map、single task 标记是否在跳过执行、异常执行、FutureTask 包装时都释放。
2. `ObjectPool`
   - 检查 `doCreate`、`doCreateIdle`、`borrow`、`recycle`、`doRetire` 中 reserved slot、idle list、live ring、threadLocal cache 是否在异常路径一致。
   - 检查 `dispose` 与定时 validate 并发时的状态处理和 signal。
   - 检查 borrow timeout 时是否可能因 create failure backoff 导致等待粒度过粗。
3. `WheelTimer` / `Tasks`
   - 检查 shutdown 后周期任务是否仍可能重调度。
   - 检查 delayed task cancel 后资源释放、trace 清理和异常吞吐。
4. `HttpClient` / `HttpServer`
   - 检查 response body/stream 未消费或异常时连接复用与关闭路径。
   - 检查 server handler 抛异常时是否稳定返回并关闭响应资源。
5. `DnsResolveCore` / `DoHClient` / `DnsServer`
   - 检查 resolver fallback、超时、取消和缓存污染。
   - 检查 DNS TCP/UDP 编解码边界长度与异常包处理。
6. `Remoting` / `transport`
   - 检查 pending request future 在 channelInactive、timeout、send failure 的清理。
   - 检查 TCP reconnect client dispose 后是否仍重连。
   - 检查 UDP client close 后 receive/send loop 的退出。
7. 非排除 socks
   - 检查 UDP relay、udp2raw、冗余/压缩 pipeline 的 MTU、session 清理、定时任务取消、ByteBuf 生命周期。

# 修改文件列表

本计划阶段实际修改：

- `docs/plan/review-rxlib-module-excluding-review-md-20260506.md`

后续若用户明确要求执行代码修复，预计可能修改以下范围中的少量文件，具体以确认的问题为准：

- `rxlib/src/main/java/org/rx/core/ThreadPool.java`
- `rxlib/src/main/java/org/rx/core/ObjectPool.java`
- `rxlib/src/main/java/org/rx/core/WheelTimer.java`
- `rxlib/src/main/java/org/rx/core/Tasks.java`
- `rxlib/src/main/java/org/rx/net/http/HttpClient.java`
- `rxlib/src/main/java/org/rx/net/http/HttpServer.java`
- `rxlib/src/main/java/org/rx/net/dns/DnsResolveCore.java`
- `rxlib/src/main/java/org/rx/net/dns/DoHClient.java`
- `rxlib/src/main/java/org/rx/net/dns/DnsServer.java`
- `rxlib/src/main/java/org/rx/net/rpc/Remoting.java`
- `rxlib/src/main/java/org/rx/net/transport/*.java`
- `rxlib/src/main/java/org/rx/net/transport/hybrid/*.java`
- `rxlib/src/main/java/org/rx/net/socks/*.java`
- `rxlib/src/main/java/org/rx/net/socks/upstream/*.java`
- 对应测试：`rxlib/src/test/java/org/rx/core/*Test.java`、`rxlib/src/test/java/org/rx/net/http/*Test.java`、`rxlib/src/test/java/org/rx/net/dns/*Test.java`、`rxlib/src/test/java/org/rx/net/rpc/*Test.java`、`rxlib/src/test/java/org/rx/net/transport/*Test.java`、`rxlib/src/test/java/org/rx/net/socks/*Test.java`

# 风险点

1. **兼容性风险**
   - 项目默认按 JDK8 验证，禁止使用 JDK9+ API。
   - `ThreadPool` 依赖 Netty internal class / field，Netty 版本差异可能导致反射脆弱。
   - 修复公开 API 行为可能影响上层模块。

2. **并发风险**
   - `ThreadPool`、`ObjectPool`、`WheelTimer`、Netty transport 均为高并发核心路径。
   - 修复计数器或状态机会影响吞吐和阻塞行为。
   - shutdown 与运行中任务并发是主要风险。

3. **资源释放风险**
   - Netty ByteBuf、Channel、EventLoopGroup、流、临时文件、timer task 都需要对称释放。
   - 修复网络路径时容易引入 double release 或提前关闭。

4. **性能风险**
   - 不能在热路径引入重锁、大量日志或重型对象分配。
   - 对象池和线程池修改必须避免放大锁竞争。
   - DNS/HTTP/RPC 修复不能降低正常路径吞吐。

5. **测试风险**
   - 网络和并发测试容易不稳定，应尽量使用 loopback、短 timeout、明确 latch。
   - 集成测试可能依赖外部网络，优先避免新增外网依赖。
   - JDK8 workflow 是手动触发，代码阶段必须主动触发并按分支过滤 run。

# 验证方案

计划阶段：

- 不运行 CI，因为只新增 review 计划文档，不修改代码或测试。

后续代码阶段：

1. 每次代码 commit 后触发 `.github/workflows/jdk8-unit-tests.yml`。
2. 如果只改某个类，优先用 `workflow_dispatch` 的 `test_classes` 参数运行相关测试，例如：
   - `ThreadPoolTest,ThreadPoolQueueShutdownTest,SerialQueueCapacityTest`
   - `ObjectPoolTest,ObjectPoolRecycleOwnershipTest`
   - `WheelTimerShutdownPeriodicTest,ThreadPoolWheelTimerRegressionTest`
   - `HttpClientTest,HttpServerBlockingTest,HttpServerExceptionTest`
   - `DnsOptimizationTest,DnsServerIntegrationTest,DoHClientTest`
   - `RemotingTest,FuryRemotingCodecTest`
   - `TcpTransportTest,UdpTransportTest`
   - `SocksProxyServerTest,SocksUdpRelayHandlerTest,Udp2rawHandlerTest`
3. CI run 查询必须按当前分支 `agent/rxlib-module-review-20260506` 过滤。
4. 只有 workflow run `conclusion=success` 才能认为通过。
5. 如果失败，先分类：编译失败、单元测试失败、Checkstyle / formatting 失败、依赖下载失败、JDK 版本不兼容、环境问题、测试不稳定、GitHub Actions 配置问题。
6. 修复失败时只改与失败直接相关的代码，并再次提交、再次触发 CI。
