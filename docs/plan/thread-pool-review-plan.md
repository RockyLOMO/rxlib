# rxlib ThreadPool 相关类 Review 与优化计划

> 生成日期：2026-05-03  
> 最近更新：2026-05-03
> 范围：`org.rx.core.ThreadPool`、`Tasks`、`WheelTimer`、`CpuWatchman`、`RunFlag`、`TimeoutFlag`、`ThreadPoolTest`

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

验证：

- `mvn -pl rxlib "-Dtest=ThreadPoolWheelTimerRegressionTest,TasksCompatibilityTest,RxConfigTest" test`：通过，16 个用例。
- `mvn -pl rxlib "-Dtest=ThreadPoolTest" test`：通过，56 个用例。
- `mvn -pl rxlib "-Dtest=SocksProxyServerIntegrationTest,ShadowsocksServerIntegrationTest,Socks5ClientIntegrationTest,RrpIntegrationTest,RemotingTest,DnsServerIntegrationTest" test`：75/76 个用例通过；`SocksProxyServerIntegrationTest.shadowsocksUdpRelay_socks5_chained_withUdpCompressAndRedundantOnProxyAB_e2e` 首次 UDP payload 长度 304/320 失败，单测方法复跑通过。

剩余未落地项：

- `Tasks.shutdown()` / `shutdownNow()` 生命周期语义。
- `WheelTimer.shutdown()` 后周期任务重排策略。
- `CpuWatchman` resize cooldown / jitter 实际扩缩容控制。
- `SINGLE` namespace/scope 与 `FastThreadLocal` old map 恢复测试。

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

`ThreadPool` 继承 `ThreadPoolExecutor`，通过自定义 `ThreadQueue` 实现有界队列和生产者阻塞：

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

#### 3.1 ThreadQueue.offer() 阻塞语义容易把调用方卡死

当前队列满时 `offer()` 会一直阻塞直到有 slot。优点是天然背压，缺点是：

- `ThreadPoolExecutor` 内部调用 `workQueue.offer()` 时，调用线程可能是业务线程、Netty EventLoop、CompletableFuture async 链线程。
- 如果在不可阻塞线程上提交任务，可能造成级联阻塞，尤其是网络 IO 线程或全局调度线程。
- `RejectedExecutionHandler` 又会再次调用 `executor.getQueue().offer(r)`，语义上不是传统拒绝，而是“继续阻塞入队”。这对吞吐有利，但对低延迟场景不透明。

建议：

1. 保留当前默认阻塞策略，但新增可配置的 `queueOfferTimeoutMillis`。
2. 支持 3 种模式：
   - `BLOCK`：当前行为，适合后台批处理。
   - `TIMEOUT_REJECT`：阻塞指定时间后抛 `RejectedExecutionException`。
   - `CALLER_RUNS`：超过阈值后调用方执行，避免无限积压。
3. 对 Netty/EventLoop 调用方增加保护：检测线程名或提供 `ThreadPool.assertNonEventLoopSubmit()` 诊断日志。
4. 队列满时打点：阻塞次数、阻塞总耗时、最大阻塞耗时、超时拒绝数。

#### 3.2 `Tasks.executor().shutdown()` 不会真正关闭底层 ThreadPool

`Tasks.executor` 的 `shutdown()` 当前只设置代理自身 `shutdown=true`，没有传播到 `nodes`；`shutdownNow()` 也返回空列表。作为全局库可以理解为不鼓励关闭，但语义上不符合 `ExecutorService` 预期。

建议：

1. 明确拆分：
   - `Tasks.executor()`：全局不可关闭代理。
   - `Tasks.shutdown()`：显式关闭 timer、CpuWatchman timer、所有 nodes。
2. `executor.shutdown()` 至少记录 warn：全局 executor 不会停止底层 pool。
3. 测试补齐：`Tasks.executor().shutdown()` 后再 submit 的行为要固定化。

#### 3.3 `CompletableFuture` 默认 async pool patch 风险较高

`Tasks` 初始化后延迟反射/Unsafe patch `CompletableFuture.asyncPool/ASYNC_POOL`，目的是让无 executor 的 `thenApplyAsync` 等走 rxlib pool。这个设计能减少 commonPool 问题，但有风险：

- JDK 版本差异较大，字段名和 final 语义不稳定。
- Java 17+ 强封装下可能依赖 `--add-opens` 或失败。
- 修改 JDK 全局静态字段会影响整个 JVM 内所有库的 CompletableFuture 行为。
- 故障时虽然记录 warn，但业务可能误以为无 executor 的 async 已被接管。

建议：

1. 默认关闭全局 patch，改为配置项：`app.threadPool.patchCompletableFutureAsyncPool=false`。
2. 推荐库内全部显式使用 `Tasks.executor()` 或 `ThreadPool.asyncExecutor`。
3. 启动时打印 patch 结果指标：成功/失败、字段名、JDK 版本、异常类型。
4. 单测按 JDK 11/17 两组验证。

#### 3.4 SERIAL 链最大容量目前硬性放大到 100000

`runSerialAsync` 使用 `taskSerialCountMap` 控制每个 taskId 的串行链长度，但代码把配置容量和默认容量再 `Math.max(maxCap, 100000)`，实际等于最低允许 10 万。这样测试容易过，但线上某个 taskId 被刷爆时会造成大量 `CompletableFuture` 链对象滞留。

建议：

1. 改为 `Math.min(configured, hardLimit)` 或新增配置：
   - `app.threadPool.serialQueueCapacity`，默认建议 `4096` 或 `queueCapacity`。
   - `app.threadPool.serialQueueHardLimit`，默认建议 `100000`，作为最后保护。
2. SERIAL 拒绝时记录 taskId hash、链长度、poolName。
3. 单测补齐：超过容量必须快速失败，且 `taskSerialCountMap` 无泄漏。

### P1：中期优化

#### 3.5 SINGLE 语义是全 JVM 全局 taskId，可能跨 pool / 跨业务冲突

`runningSingleTasks` 是静态全局 Set，taskId 相同就互斥。优点是全局唯一，缺点是不同业务模块如果 taskId 碰撞，会互相跳过。

建议：

1. 引入 scope：`SingleKey(poolName, taskId)` 或 `SingleKey(namespace, taskId)`。
2. `RunFlag.SINGLE` 默认保持兼容；新增 `RunFlag.SINGLE_LOCAL` 或 API 参数 `scope`。
3. 日志中输出 poolName + taskId + caller trace，方便定位谁占用了 SINGLE。

#### 3.6 FastThreadLocal 继承/恢复需要更强的 finally 保障

`INHERIT_FAST_THREAD_LOCALS` 会捕获父线程 `InternalThreadLocalMap` 并设置到工作线程。这里属于高风险优化：

- 如果 afterExecute 未完全恢复/清理，可能污染后续任务。
- 直接操作 Netty 内部结构，版本兼容性需要测试。
- 对 `FastThreadLocalThread` 与普通 Thread 的路径要分别验证。

建议：

1. 明确保存 old map，执行后恢复 old map，而不是简单 set/remove。
2. 增加测试：任务 A 设置 FastThreadLocal，任务 B 不继承时必须看不到 A 的值。
3. 增加 Netty 4.1.127 / 4.2.x 兼容性测试。

#### 3.7 CpuWatchman 扩缩容需要增加抖动保护和边界保护

当前策略比较直接：CPU 低且队列非空扩容，CPU 高则缩容。需要注意：

- `getSystemCpuLoad()` 和 `getProcessCpuLoad()` 返回值语义不同；当前系统 CPU 分支未乘 100，进程 CPU 分支乘 100，需要确认 `Decimal` 与水位单位是否一致。
- 多 pool 同时采样时可能同步扩容/缩容，造成抖动。
- `setCorePoolSize()` 在 shutdown 或边界变动时可能抛异常，需要保护。

建议：

1. 统一 CPU load 单位为百分比 0~100，并补测试。
2. 增加 resize cooldown：同一个 pool 两次 resize 间隔不少于 `resizeCooldownMillis`。
3. 对多副本 pool 加随机 jitter，避免同一采样点同时扩缩。
4. `incrSize/decrSize` 捕获异常并打指标。

#### 3.8 WheelTimer shutdown 语义需要和 ExecutorService 语义对齐

`WheelTimer.shutdown()` 只设置 shutdown，未停止底层 timer；`shutdownNow()` 才 `timer.stop()`。这对“优雅停止已提交任务”合理，但要文档化：

- shutdown 后新任务拒绝，已有 timeout 是否继续执行？当前可能继续。
- 周期任务是否继续 reschedule？依赖 `shutdown` 只在入口检查，内部重排需确认。
- `awaitTermination()` 通过轮询 holder，若无 taskId 的周期任务不在 holder，可能无法准确表示终止。

建议：

1. 定义三种状态：RUNNING / SHUTDOWN_GRACEFUL / STOPPED。
2. shutdown 后周期任务不再重排；已有一次性任务允许执行。
3. 对所有周期任务维护 active set，不只 holder by id。
4. 增加测试：shutdown 后 `scheduleAtFixedRate` 不再继续触发。

### P2：可观测性与易用性

#### 3.9 ThreadPool 指标需要补齐

建议新增以下指标：

- `rx.thread_pool.core.size`
- `rx.thread_pool.pool.size`
- `rx.thread_pool.active.count`
- `rx.thread_pool.queue.size`
- `rx.thread_pool.queue.capacity`
- `rx.thread_pool.queue.remaining`
- `rx.thread_pool.completed.count`
- `rx.thread_pool.task.submitted.count`
- `rx.thread_pool.task.rejected.count`
- `rx.thread_pool.queue.offer.block.count`
- `rx.thread_pool.queue.offer.block.millis`
- `rx.thread_pool.serial.chain.count`
- `rx.thread_pool.single.skip.count`

tags 建议：`poolName`、`replicaIndex`、`mode`、`reason`。

#### 3.10 API 命名和文档

建议补一份 `docs/thread-pool.md`，说明：

- `run` vs `runAsync` vs `submit` 的区别。
- `SINGLE` 是跳过重复，不是排队。
- `SERIAL` 是按 taskId 排队，不占用工作线程等待。
- `TRANSFER` 对 `LinkedTransferQueue` 的影响。
- 哪些线程禁止提交可能阻塞的任务。
- CompletableFuture 默认 async pool patch 是否开启。

## 4. 建议实施计划

### 阶段 1：安全边界和可观测性（优先）

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

验收：

- [x] 队列满时可按配置阻塞、超时拒绝或 caller-runs。
- [x] SERIAL 链超过容量快速失败，map 计数可回收。
- [x] 默认不修改 JDK `CompletableFuture` 全局 async pool。

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
  - 队列满后 `TIMEOUT_REJECT` 按预期抛异常。
  - `CALLER_RUNS` 不增加队列长度。
  - BLOCK 模式保持兼容。
- `SerialQueueCapacityTest`
  - 同 taskId 超容量快速失败。
  - 任务完成后 `taskSerialCountMap` 清理。
  - 异常任务不阻断后续串行链是否按预期。
- `SingleScopeTest`
  - 相同 namespace + taskId 跳过。
  - 不同 namespace + 相同 taskId 可并行。
- `FastThreadLocalIsolationTest`
  - 继承后恢复。
  - 不继承时不可见。
- `WheelTimerShutdownTest`
  - shutdown 后拒绝新任务。
  - shutdown 后周期任务不再重排。
  - shutdownNow 取消 holder 和 timer。
- `CpuWatchmanResizeTest`
  - load 单位正确。
  - cooldown 生效。
  - resize 不超过 max/min。

### 压测/基准

- 1C/2C/4C 小机器下：短任务、高频提交、队列满背压。
- Netty EventLoop 提交场景：确认不会被无界阻塞。
- SERIAL 单 key 高压：10w 提交前后的内存曲线。
- 多副本 pool replicas=1/2/4 对吞吐和尾延迟的影响。

## 6. 推荐落地顺序

1. 先做配置和指标，不改变默认行为。
2. 再改 `CompletableFuture` patch 默认关闭，作为一次兼容性变更。
3. 然后修正 SERIAL 容量，给出迁移说明。
4. 最后处理生命周期、CPU 扩缩容和上下文隔离。

## 7. 风险与兼容策略

- `ThreadQueue` 当前阻塞行为可能已有业务依赖，因此第一阶段必须保持默认 `BLOCK`。
- `CompletableFuture` patch 默认关闭可能影响依赖无 executor async 也走 rxlib pool 的业务，建议先加日志开关观察一版。
- SERIAL 容量收紧可能暴露已有堆积问题，建议先 warn，再 reject。
- SINGLE 引入 namespace 时保持老 API 走全局 namespace，避免破坏兼容。

## 8. 总结

当前 thread pool 实现已经具备比较完整的高性能库特征：任务包装、上下文透传、背压、串行/单例任务、动态扩缩容、定时调度和诊断指标。主要问题不在功能缺失，而在“默认行为偏强势”：无限阻塞入队、全局 CompletableFuture patch、SERIAL 过大链容量、shutdown 语义不够清晰。建议优先从配置化、指标化、测试覆盖入手，逐步把这些强行为改成可控策略。
