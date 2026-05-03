# rxlib ThreadPool 相关类 Review 与优化计划

> 生成日期：2026-05-03  
> 最近更新：2026-05-03  
> 二次 Review 基准提交：`f5eab853c919727acce1d3a29e72e41e210dc6b0`  
> 范围：`org.rx.core.ThreadPool`、`Tasks`、`WheelTimer`、`CpuWatchman`、`RunFlag`、`TimeoutFlag`、`ThreadPoolQueueOfferMode`、`ThreadPoolTest`、`ThreadPoolWheelTimerRegressionTest`、`TasksCompatibilityTest`、`RxConfigTest`

## 0. 本轮修复记录

状态：阶段 1 已落地，默认兼容旧行为。

- `ThreadPool.ThreadQueue` 新增 `queueOfferMode`：
  - `BLOCK`：默认值，保持原有无限背压阻塞语义。
  - `TIMEOUT_REJECT`：队列满后最多等待 `queueOfferTimeoutMillis`，超时快速抛 `RejectedExecutionException`。
  - `CALLER_RUNS`：队列满且超时后在提交线程执行溢出任务，避免继续堆积。
- `ThreadQueue` 补充队列阻塞、阻塞耗时、最大阻塞耗时、拒绝、caller-runs 计数指标。
- `runSerialAsync()` 改为使用 `serialQueueCapacity` / `serialQueueHardLimit`，不再把每个 taskId 的串行链最低放大到 100000。
- 修复 `runSerialAsync()` 快速完成任务在 `ConcurrentHashMap.compute()` 内重入 compute 的挂死风险：完成回调移到 compute 外执行。
- `CompletableFuture` 默认 async pool patch 改为显式开关 `patchCompletableFutureAsyncPool=false`，默认不再修改 JDK 全局静态字段。
- 新增配置项已写入 `rx.yml`，并补充配置解析与线程池回归测试。
- `docs/reference/ThreadPool.md` 已补充队列背压模式、SERIAL 容量、CompletableFuture patch 开关和线程池监控指标。

提交记录中的验证：

- `mvn -pl rxlib "-Dtest=ThreadPoolWheelTimerRegressionTest,TasksCompatibilityTest,RxConfigTest" test`：通过，16 个用例。
- `mvn -pl rxlib "-Dtest=ThreadPoolTest" test`：通过，56 个用例。
- `mvn -pl rxlib "-Dtest=SocksProxyServerIntegrationTest,ShadowsocksServerIntegrationTest,Socks5ClientIntegrationTest,RrpIntegrationTest,RemotingTest,DnsServerIntegrationTest" test`：75/76 个用例通过；`SocksProxyServerIntegrationTest.shadowsocksUdpRelay_socks5_chained_withUdpCompressAndRedundantOnProxyAB_e2e` 首次 UDP payload 长度 304/320 失败，单测方法复跑通过。

## 0.1 二次 Review 结论

结论：阶段 1 可视为基本验收通过。当前修复已经解决上轮最关键的 3 个强行为风险：无限阻塞入队不可配置、SERIAL 链容量过大、默认 patch JDK `CompletableFuture` 全局 async pool。

确认已完成：

- [x] 队列满时支持 `BLOCK` / `TIMEOUT_REJECT` / `CALLER_RUNS` 三种策略。
- [x] 默认仍为 `BLOCK`，兼容历史行为。
- [x] `TIMEOUT_REJECT`、`CALLER_RUNS` 已有回归测试。
- [x] SERIAL 容量从固定最低 100000 改为 `serialQueueCapacity` / `serialQueueHardLimit`。
- [x] SERIAL 快速完成任务的 `ConcurrentHashMap.compute()` 重入风险已有回归测试。
- [x] `CompletableFuture` async pool patch 默认关闭，改为 `patchCompletableFutureAsyncPool=true` 显式开启。
- [x] `rx.yml`、`RxConfig`、`docs/reference/ThreadPool.md` 已同步新增配置。

二次 review 新发现/建议：

1. `CALLER_RUNS` 会让任务在提交线程内直接执行，需要补一组“生命周期等价性”测试：
   - `RunFlag.SINGLE` 在 caller-runs 路径下仍能正确 acquire/release。
   - `THREAD_TRACE` 在 caller-runs 路径下能正确 start/end，异常时也不泄漏。
   - `INHERIT_FAST_THREAD_LOCALS` 在 caller-runs 路径下不会污染提交线程后续上下文。
   - caller-runs 后 `taskMap`、serial counter、single lock 都能清理。
2. `ThreadPoolQueueOfferMode.parse()` 建议对输入做 `trim()`，并对未知值打印 warn 或指标。当前未知值静默回退 default，线上配置写错时不容易发现。
3. `RxConfigTest` / `ThreadPoolWheelTimerRegressionTest` 会直接修改 `RxConfig.INSTANCE.threadPool` 全局配置。默认串行跑问题不大；如果以后开启 JUnit 并行，建议加 JUnit `@ResourceLock("RxConfig.threadPool")` 或统一的 config snapshot/restore 工具。
4. 指标命名需要以实际实现和 `docs/reference/ThreadPool.md` 为准。计划文档原先写的 `rx.thread_pool.core.size` / `pool.size` / `queue.size` 应同步为当前文档中的 `rx.thread_pool.core.count` / `size.count` / `queue.count`。
5. `resizeCooldownMillis` 已有配置项，但 `CpuWatchman` resize cooldown / jitter 还没有实际落地，继续保留在阶段 3。
6. 集成测试里 UDP 场景首次 payload 长度 304/320 失败、复跑通过，暂不归因到 ThreadPool。本计划只记录为“需要独立跟踪的网络链路 flaky”，不阻塞阶段 1 验收。

剩余未落地项：

- `Tasks.shutdown()` / `shutdownNow()` 生命周期语义。
- `WheelTimer.shutdown()` 后周期任务重排策略。
- `CpuWatchman` resize cooldown / jitter 实际扩缩容控制。
- `SINGLE` namespace/scope 与 `FastThreadLocal` old map 恢复测试。
- `CALLER_RUNS` 生命周期等价性测试。
- 配置 parse 容错和未知值可观测性。

## 1. Review 范围

本次 review 聚焦 rxlib 中通用线程池与定时调度相关代码，重点检查：

- 任务提交路径：`execute` / `submit` / `run` / `runAsync` / `runSerialAsync`。
- 队列与背压：`ThreadPool.ThreadQueue` 基于 `LinkedTransferQueue + Semaphore` 的有界阻塞策略。
- 任务语义：`RunFlag.SINGLE`、`SERIAL`、`TRANSFER`、`PRIORITY`、`INHERIT_FAST_THREAD_LOCALS`、`THREAD_TRACE`。
- 全局入口：`Tasks.executor()`、`Tasks.timer()`、多副本 `ThreadPool` 负载分散。
- 延迟调度：`WheelTimer` 对 `ScheduledExecutorService` 的适配、周期任务、取消、指标。
- 动态扩缩容：`CpuWatchman` 根据 CPU 水位和队列状态调整 `corePoolSize`。
- 可观测性：`DiagnosticMetrics`、慢方法采样、traceId 传递。

## 2. 当前实现概览

### 2.1 ThreadPool

`ThreadPool` 继承 `ThreadPoolExecutor`，通过自定义 `ThreadQueue` 实现有界队列和生产者背压：

- 构造时传入 `ThreadQueue(checkCapacity(queueCapacity))`，shutdown 时忽略并记录日志，非 shutdown 的拒绝会清理任务映射并抛 `RejectedExecutionException`。
- `ThreadQueue.offer()` 先尝试获取 `Semaphore` slot，默认 `BLOCK` 模式下队列满时每 500ms 记录一次阻塞日志并等待 slot；也可配置为 `TIMEOUT_REJECT` 或 `CALLER_RUNS`。
- `Task` 包装 `Runnable/Callable`，负责 flags、taskId、traceId、慢调用 stack trace、FastThreadLocal 继承等上下文。
- `SINGLE` 通过全局 `runningSingleTasks` 去重运行中任务。
- `SERIAL` 通过 `taskSerialMap` + `CompletableFuture.thenApplyAsync` 按 taskId 串行。
- `PRIORITY` 在队列非空时触发 `CpuWatchman.incrSize(this)`，尝试临时扩容。

### 2.2 Tasks

`Tasks` 是全局静态入口：

- 维护 `CopyOnWriteArrayList<ThreadPool> nodes`，通过 `RxConfig.INSTANCE.threadPool.replicas` 创建多个线程池副本。
- `executor` 是一个 `AbstractExecutorService` 代理，提交任务时随机选择下一个 `ThreadPool`。
- 对 `RunFlag.SINGLE` 且带 `taskId` 的任务，使用 `taskId.hashCode()` 稳定映射到同一个 pool，避免 SINGLE 语义跨副本失效。
- 初始化时创建全局 `WheelTimer(executor)`。
- 仅在 `patchCompletableFutureAsyncPool=true` 时尝试通过反射/Unsafe patch `CompletableFuture` 的默认 async pool；默认关闭。

### 2.3 WheelTimer

`WheelTimer` 继承 `AbstractExecutorService` 并实现 `ScheduledExecutorService`：

- 底层使用 Netty `HashedWheelTimer` 负责时间轮触发。
- 实际任务执行委托给传入的 `ExecutorService`，避免 timer 线程直接跑业务。
- `setTimeout` 支持 `SINGLE`、`REPLACE`、`PERIOD`，并用 `holder` 按 taskId 管理替换/去重。
- `scheduleAtFixedRate` 与 `scheduleWithFixedDelay` 由内部 `PeriodicTask` 单独实现。
- 已记录 holder、pending、scheduled、executed、cancelled、error、rejected、shutdown 等指标。

### 2.4 CpuWatchman

`CpuWatchman` 使用独立 `HashedWheelTimer` 周期采样：

- 可按系统 CPU 或进程 CPU 负载判断。
- 对普通线程池：队列非空且 CPU 低于低水位时扩容；CPU 高于高水位且 corePoolSize 大于 minIdle 时缩容。
- 对 `ScheduledExecutorService`：结合 active/core 比例和 CPU 水位扩缩容。
- 通过 `ReferenceIdentityMap` 弱引用保存被监控线程池，降低长期泄漏风险。

## 3. 主要发现

### P0/P1：需要优先处理

#### 3.1 ThreadQueue.offer() 阻塞语义容易把调用方卡死 —— 已修复核心风险

上轮问题：队列满时 `offer()` 会一直阻塞直到有 slot。优点是天然背压，缺点是：

- `ThreadPoolExecutor` 内部调用 `workQueue.offer()` 时，调用线程可能是业务线程、Netty EventLoop、CompletableFuture async 链线程。
- 如果在不可阻塞线程上提交任务，可能造成级联阻塞，尤其是网络 IO 线程或全局调度线程。
- 原先拒绝策略语义不是传统拒绝，而是“继续阻塞入队”。这对吞吐有利，但对低延迟场景不透明。

当前状态：

- [x] 保留默认 `BLOCK`，兼容历史行为。
- [x] 新增 `TIMEOUT_REJECT`。
- [x] 新增 `CALLER_RUNS`。
- [x] 队列满时已有阻塞、拒绝、caller-runs 指标。
- [ ] 建议继续补：Netty/EventLoop 调用方保护或诊断日志，例如 `ThreadPool.assertNonEventLoopSubmit()`。
- [ ] 建议继续补：caller-runs 路径的 SINGLE/TRACE/FastThreadLocal 生命周期等价性测试。

#### 3.2 `Tasks.executor().shutdown()` 不会真正关闭底层 ThreadPool

`Tasks.executor` 的 `shutdown()` 当前只设置代理自身 `shutdown=true`，没有传播到 `nodes`；`shutdownNow()` 也返回空列表。作为全局库可以理解为不鼓励关闭，但语义上不符合 `ExecutorService` 预期。

建议：

1. 明确拆分：
   - `Tasks.executor()`：全局不可关闭代理。
   - `Tasks.shutdown()`：显式关闭 timer、CpuWatchman timer、所有 nodes。
2. `executor.shutdown()` 至少记录 warn：全局 executor 不会停止底层 pool。
3. 测试补齐：`Tasks.executor().shutdown()` 后再 submit 的行为要固定化。

#### 3.3 `CompletableFuture` 默认 async pool patch 风险较高 —— 已修复核心风险

上轮问题：`Tasks` 初始化后延迟反射/Unsafe patch `CompletableFuture.asyncPool/ASYNC_POOL`，目的是让无 executor 的 `thenApplyAsync` 等走 rxlib pool。这个设计能减少 commonPool 问题，但会影响整个 JVM。

当前状态：

- [x] 默认关闭全局 patch：`app.threadPool.patchCompletableFutureAsyncPool=false`。
- [x] patch 结果有日志和 `DiagnosticMetrics` 指标。
- [x] 已有默认关闭回归测试。
- [ ] 建议继续补：开启 patch 的兼容性测试按 JDK 11 / 17 分组；新代码继续推荐显式传 `Tasks.executor()`。

#### 3.4 SERIAL 链最大容量硬性放大到 100000 —— 已修复核心风险

上轮问题：`runSerialAsync` 使用 `taskSerialCountMap` 控制每个 taskId 的串行链长度，但实际把最低容量放大到 100000，线上某个热 taskId 可能滞留大量 `CompletableFuture` 链对象。

当前状态：

- [x] 使用 `app.threadPool.serialQueueCapacity`。
- [x] 使用 `app.threadPool.serialQueueHardLimit` 作为最终保护。
- [x] `RxConfig.afterSet()` 会把 capacity 限制到 hardLimit 内。
- [x] 超容量快速 `RejectedExecutionException` 已有测试。
- [x] 完成后 `taskSerialMap` / `taskSerialCountMap` 回收已有测试。
- [ ] 建议继续补：SERIAL 拒绝时记录 taskId hash、链长度、poolName，避免日志暴露完整业务 key。

### P1：中期优化

#### 3.5 SINGLE 语义是全 JVM 全局 taskId，可能跨 pool / 跨业务冲突

`runningSingleTasks` 是静态全局 Set，taskId 相同就互斥。优点是全局唯一，缺点是不同业务模块如果 taskId 碰撞，会互相跳过。

建议：

1. 引入 scope：`SingleKey(poolName, taskId)` 或 `SingleKey(namespace, taskId)`。
2. `RunFlag.SINGLE` 默认保持兼容；新增 `RunFlag.SINGLE_LOCAL` 或 API 参数 `scope`。
3. 日志中输出 poolName + taskId hash + caller trace，方便定位谁占用了 SINGLE。

#### 3.6 FastThreadLocal 继承/恢复需要更强的 finally 保障

`INHERIT_FAST_THREAD_LOCALS` 会捕获父线程 `InternalThreadLocalMap` 并设置到工作线程。这里属于高风险优化：

- 如果 afterExecute 未完全恢复/清理，可能污染后续任务。
- 直接操作 Netty 内部结构，版本兼容性需要测试。
- 对 `FastThreadLocalThread`、普通 Thread、caller-runs 三条路径都要验证。

建议：

1. 明确保存 old map，执行后恢复 old map，而不是简单 set/remove。
2. 增加测试：任务 A 设置 FastThreadLocal，任务 B 不继承时必须看不到 A 的值。
3. 增加 caller-runs 路径下的 FastThreadLocal 隔离测试。
4. 增加 Netty 4.1.127 / 4.2.x 兼容性测试。

#### 3.7 CpuWatchman 扩缩容需要增加抖动保护和边界保护

当前策略比较直接：CPU 低且队列非空扩容，CPU 高则缩容。需要注意：

- `getSystemCpuLoad()` 和 `getProcessCpuLoad()` 返回值语义不同；需要确认最终统一为百分比 0~100。
- 多 pool 同时采样时可能同步扩容/缩容，造成抖动。
- `resizeCooldownMillis` 已有配置项，但扩缩容实际逻辑仍需要落地。
- `setCorePoolSize()` 在 shutdown 或边界变动时可能抛异常，需要保护。

建议：

1. 统一 CPU load 单位为百分比 0~100，并补测试。
2. 增加 resize cooldown：同一个 pool 两次 resize 间隔不少于 `resizeCooldownMillis`。
3. 对多副本 pool 加随机 jitter，避免同一采样点同时扩缩。
4. `incrSize/decrSize` 捕获异常并打指标。

#### 3.8 WheelTimer shutdown 语义需要和 ExecutorService 语义对齐

`WheelTimer.shutdown()` 只设置 shutdown，未停止底层 timer；`shutdownNow()` 才 `timer.stop()`。这对“优雅停止已提交任务”合理，但要文档化：

- shutdown 后新任务拒绝，已有 timeout 是否继续执行？当前可以继续执行。
- 周期任务是否继续 reschedule？依赖 `shutdown` 只在入口检查，内部重排需确认。
- `awaitTermination()` 通过轮询 holder，若无 taskId 的周期任务不在 holder，可能无法准确表示终止。

建议：

1. 定义三种状态：RUNNING / SHUTDOWN_GRACEFUL / STOPPED。
2. shutdown 后周期任务不再重排；已有一次性任务允许执行。
3. 对所有周期任务维护 active set，不只 holder by id。
4. 增加测试：shutdown 后 `scheduleAtFixedRate` 不再继续触发。

### P2：可观测性与易用性

#### 3.9 ThreadPool 指标 —— 已补齐核心项，后续保持命名一致

当前文档已列出的指标：

- `rx.thread_pool.core.count`
- `rx.thread_pool.size.count`
- `rx.thread_pool.active.count`
- `rx.thread_pool.queue.count`
- `rx.thread_pool.queue.capacity`
- `rx.thread_pool.queue.remaining`
- `rx.thread_pool.completed.count`
- `rx.thread_pool.task.rejected.count`
- `rx.thread_pool.queue.offer.block.count`
- `rx.thread_pool.queue.offer.block.millis`
- `rx.thread_pool.queue.offer.block.max.millis`
- `rx.thread_pool.queue.offer.rejected.count`
- `rx.thread_pool.queue.offer.caller_runs.count`
- `rx.thread_pool.serial.chain.count`
- `rx.thread_pool.serial.rejected.count`
- `rx.thread_pool.single.skip.count`

继续建议：

1. 补充 `rx.thread_pool.task.submitted.count`，便于计算 reject / submitted 比例。
2. tags 建议统一包含：`poolName`、`mode`、`reason`；多副本场景可加 `replicaIndex`。
3. caller-runs 执行的任务不会自然进入 `ThreadPoolExecutor.completedTaskCount`，需要明确用 caller-runs 独立指标统计。

#### 3.10 API 命名和文档 —— 已部分完成

`docs/reference/ThreadPool.md` 已补充队列背压模式、SERIAL 容量、CompletableFuture patch 开关和监控指标。

继续建议补充：

- `run` vs `runAsync` vs `submit` 的区别。
- `SINGLE` 是跳过重复，不是排队。
- `SERIAL` 是按 taskId 排队，不占用工作线程等待。
- `TRANSFER` 对 `LinkedTransferQueue` 的影响和阻塞风险。
- 哪些线程禁止提交可能阻塞的任务。
- caller-runs 对任务生命周期、指标和延迟的影响。

#### 3.11 配置解析容错

`ThreadPoolQueueOfferMode.parse()` 当前能大小写不敏感匹配枚举值，但建议增强：

1. `value.trim()` 后再匹配，兼容 YAML / system property 中的空格。
2. 未知值时记录 warn 或指标：`rx.thread_pool.config.invalid.count`。
3. 可选：支持 `timeout-reject` / `timeout_reject` 这类宽松写法，降低配置错误概率。

#### 3.12 测试并发隔离

新增测试会直接改 `RxConfig.INSTANCE.threadPool`。如果后续开启 JUnit parallel，需要防止测试互相污染。

建议：

1. 抽一个 `ThreadPoolConfigSnapshot` 测试工具，try-with-resources 自动 restore。
2. 或对这些测试加 `@ResourceLock("RxConfig.threadPool")`。
3. CI 保持线程池相关测试串行执行。

## 4. 建议实施计划

### 阶段 1：安全边界和可观测性（已基本完成）

1. [x] 新增配置项：
   - `app.threadPool.queueOfferMode=BLOCK|TIMEOUT_REJECT|CALLER_RUNS`
   - `app.threadPool.queueOfferTimeoutMillis=0`
   - `app.threadPool.serialQueueCapacity=4096`
   - `app.threadPool.serialQueueHardLimit=100000`
   - `app.threadPool.patchCompletableFutureAsyncPool=false`
   - `app.threadPool.resizeCooldownMillis=1000`
2. [x] `ThreadQueue.offer()` 统计阻塞耗时和次数。
3. [x] `runSerialAsync()` 使用独立 serial 容量配置，不再默认最低 100000。
4. [x] `Tasks.initCompletableFutureAsyncPool()` 改为配置开启，并输出明确日志与指标。
5. [x] 补测试：队列 timeout reject、caller-runs、serial 容量、patch 开关、配置解析。
6. [ ] 补 caller-runs 生命周期等价性测试。
7. [ ] `ThreadPoolQueueOfferMode.parse()` 增加 trim、未知值 warn/metric。
8. [ ] 补测试全局配置并发隔离工具或 `@ResourceLock`。

验收：

- [x] 队列满时可按配置阻塞、超时拒绝或 caller-runs。
- [x] SERIAL 链超过容量快速失败，map 计数可回收。
- [x] 默认不修改 JDK `CompletableFuture` 全局 async pool。
- [ ] caller-runs 路径与 worker 路径在 SINGLE/TRACE/FastThreadLocal/taskMap 清理方面语义一致。

### 阶段 2：生命周期语义修正

1. `Tasks` 增加显式 `shutdown()` / `shutdownNow()`。
2. 全局 `executor.shutdown()` 行为文档化或委托到底层 shutdown。
3. `WheelTimer` 明确 shutdown 后周期任务不再重排。
4. `ThreadPool` shutdown 时 unregister `CpuWatchman`，避免弱引用回收前继续扫描。
5. 补测试：shutdown、shutdownNow、awaitTermination、周期任务取消。

验收：

- 所有关闭行为与文档一致。
- shutdown 后无新增任务执行。
- timer 线程和 pool 线程可退出。

### 阶段 3：动态扩缩容稳定性

1. 统一 CPU load 单位为 0~100。
2. `CpuWatchman` 增加 resize cooldown 和 jitter。
3. `PRIORITY` 触发扩容增加速率限制，避免短时间内多次 `setCorePoolSize`。
4. 增加 resize 指标：原因、前后 core size、CPU load、queue size。
5. 压测：CPU-bound、IO-bound、混合负载、突发提交。

验收：

- CPU 高压时不会持续扩容放大竞争。
- CPU 低且队列堆积时能稳定扩容。
- 多副本 pool 不会周期性同步抖动。

### 阶段 4：上下文和语义增强

1. SINGLE 支持 namespace/scope，避免全局 taskId 冲突。
2. `INHERIT_FAST_THREAD_LOCALS` 执行后恢复旧 map，补污染隔离测试。
3. traceId `InheritableThreadLocal` 增加最大深度和泄漏测试。
4. 文档补齐 API 使用规则。

验收：

- 不同 namespace 的 SINGLE 任务互不影响。
- FastThreadLocal 不会跨任务污染。
- traceId 在嵌套、异常、异步链下正确清理。

## 5. 建议测试清单

### 单元测试

- `ThreadQueueOfferTimeoutTest`
  - [x] 队列满后 `TIMEOUT_REJECT` 按预期抛异常。
  - [x] `CALLER_RUNS` 不增加队列长度。
  - [ ] caller-runs 路径下 SINGLE/THREAD_TRACE/FastThreadLocal/taskMap 清理等价。
  - [ ] BLOCK 模式长时间阻塞但可被 shutdownNow / interrupt 解开。
- `SerialQueueCapacityTest`
  - [x] 同 taskId 超容量快速失败。
  - [x] 任务完成后 `taskSerialCountMap` 清理。
  - [ ] 异常任务不阻断后续串行链是否按预期。
- `SingleScopeTest`
  - [ ] 相同 namespace + taskId 跳过。
  - [ ] 不同 namespace + 相同 taskId 可并行。
- `FastThreadLocalIsolationTest`
  - [ ] 继承后恢复。
  - [ ] 不继承时不可见。
  - [ ] caller-runs 路径不可污染提交线程。
- `WheelTimerShutdownTest`
  - [x] shutdown 后拒绝新任务。
  - [ ] shutdown 后周期任务不再重排。
  - [x] shutdownNow 取消 holder 和 timer。
- `CpuWatchmanResizeTest`
  - [ ] load 单位正确。
  - [ ] cooldown 生效。
  - [ ] resize 不超过 max/min。
- `ThreadPoolQueueOfferModeTest`
  - [ ] parse 支持 trim。
  - [ ] 未知值有 warn/metric 或至少可测试地 fallback。
- `ThreadPoolConfigIsolationTest`
  - [ ] 测试修改全局 `RxConfig.INSTANCE.threadPool` 后能稳定 restore。
  - [ ] 并行测试场景下不会互相污染。

### 压测/基准

- 1C/2C/4C 小机器下：短任务、高频提交、队列满背压。
- Netty EventLoop 提交场景：确认不会被无界阻塞。
- SERIAL 单 key 高压：容量拒绝前后的内存曲线。
- CALLER_RUNS 突发场景：提交线程延迟、吞吐、tail latency。
- 多副本 pool replicas=1/2/4 对吞吐和尾延迟的影响。

### 需要独立跟踪的 flaky

- `SocksProxyServerIntegrationTest.shadowsocksUdpRelay_socks5_chained_withUdpCompressAndRedundantOnProxyAB_e2e` 首次 UDP payload 长度 304/320 失败但单测复跑通过。建议单独记录为网络链路 flaky，排查多倍发包、压缩/冗余、UDP relay 首包、端口跳跃相关路径；暂不阻塞 ThreadPool 阶段 1 验收。

## 6. 推荐落地顺序

1. 阶段 1 追加小修：caller-runs 生命周期测试、parse 容错、测试配置隔离。
2. 生命周期阶段：`Tasks.shutdown()` / `WheelTimer.shutdown()` 语义固定。
3. 扩缩容阶段：`CpuWatchman` cooldown / jitter / CPU load 单位统一。
4. 语义增强阶段：SINGLE namespace、FastThreadLocal 恢复、trace 泄漏测试。

## 7. 风险与兼容策略

- `ThreadQueue` 默认仍为 `BLOCK`，因此兼容旧行为；低延迟链路应显式改为 `TIMEOUT_REJECT`。
- `CompletableFuture` patch 默认关闭，可能影响依赖无 executor async 也走 rxlib pool 的旧业务；建议发布说明中明确要求显式传 `Tasks.executor()` 或开启兼容开关。
- SERIAL 容量收紧可能暴露已有热 key 堆积问题；建议先观察 `rx.thread_pool.serial.rejected.count`，必要时按业务 key 分类限流。
- `CALLER_RUNS` 能保护队列，但会把执行成本转移到提交线程；Netty EventLoop 上慎用，除非任务极短且可控。
- SINGLE 引入 namespace 时保持老 API 走全局 namespace，避免破坏兼容。

## 8. 总结

当前 ThreadPool 第一阶段修复方向正确：把原先几个“强默认行为”变成了可配置、可观测、可测试的策略。阶段 1 可以进入收尾状态，剩余建议集中在边界测试和容错细节；下一步更值得投入的是生命周期语义、CpuWatchman 扩缩容稳定性，以及上下文隔离能力。