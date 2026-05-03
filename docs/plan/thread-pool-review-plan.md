# rxlib ThreadPool Review 与优化计划

> 生成日期：2026-05-03  
> 最近更新：2026-05-03  
> 分支：`agent/20260503-thread-pool-review`  
> 当前 review 基线：`bea5a3b3fca766589eb231ae052634d9f04f4417`  
> 本次更新类型：Review 类任务，仅更新计划文档，不修改业务代码。  
> 明确范围：`SINGLE namespace / scope` 不实现，已从待办中移除。

# 背景

用户要求在 `RockyLOMO/rxlib` 的 `agent/20260503-thread-pool-review` 分支上继续 review 当前线程池相关实现，并将 review 结果更新到 `docs/plan/thread-pool-review-plan.md`，同时明确指出还有哪些待办。

本次 review 关注 `rxlib` 中线程池、队列背压、全局任务入口、时间轮调度、CPU 动态扩缩容、shutdown 生命周期和相关测试状态。

# 任务类型判断

本次任务归类为 **Review / 修复 / 重构 / 优化需求**。

原因：

- 用户要求 “review 下目前的实现，结果更新到计划文档，指出还有哪些待办”。
- 用户未要求直接修改业务代码。
- 本阶段目标是复查当前实现状态、识别剩余风险、沉淀待办清单。
- 按 agent 流程，本次只允许更新计划文档；没有用户明确要求“按计划执行 / 开始修改代码 / 实现代码”前，不进入代码实现阶段。

# 当前上下文

## 已 review 的文件

- `rxlib/src/main/java/org/rx/core/ThreadPool.java`
- `rxlib/src/main/java/org/rx/core/WheelTimer.java`
- `rxlib/src/main/java/org/rx/core/CpuWatchman.java`
- `rxlib/src/test/java/org/rx/core/ThreadPoolQueueShutdownTest.java`
- `rxlib/src/test/java/org/rx/core/ThreadPoolWheelTimerRegressionTest.java`
- `rxlib/src/test/java/org/rx/core/ThreadPoolTest.java`
- `docs/plan/thread-pool-review-plan.md`

## 最近代码状态

当前分支最新代码提交为：

- `bea5a3b3fca766589eb231ae052634d9f04f4417`
- commit message：`ThreadPool 第四阶段收尾与回归测试修复`

该提交已包含：

- `ThreadPool.ThreadQueue.CapacitySnapshot` 测试可见容量快照。
- shutdown 后提交任务改为抛 `RejectedExecutionException`。
- `WheelTimer` executor reject / 初始调度失败路径的完成信号补强。
- `ThreadPoolQueueShutdownTest` 增加 shutdown race、CALLER_RUNS race、并发 remove/clear/drainTo 不变量测试。
- `ThreadPoolWheelTimerRegressionTest` 增加 executor reject、periodic reject、negative initial delay 发布取消测试。
- `ThreadPoolTest` 更新 shutdown 后提交任务的语义断言。

## 关键调用链

### ThreadPool 提交流程

- `ThreadPool.run(...)` / `runAsync(...)` / `execute(...)`
- `ThreadPool.Task.adapt(...)`
- `ThreadPool.setTask(...)`
- `ThreadPoolExecutor.execute(...)`
- `ThreadPool.ThreadQueue.offer(...)`
- `ThreadPool.beforeExecute(...)`
- `ThreadPool.afterExecute(...)`

当前 `ThreadQueue` 通过 `Semaphore availableSlots` 和 `AtomicInteger counter` 维护有界容量，并支持 `BLOCK / TIMEOUT_REJECT / CALLER_RUNS` 三种入队策略。`poll/take/remove/drainTo/clear` 路径已集中处理 slot 释放、counter 回收和 `taskMap` 清理。

### SERIAL 串行任务调用链

- `ThreadPool.runSerial(...)`
- `ThreadPool.runSerialAsync(...)`
- `taskSerialCountMap.computeIfAbsent(...)`
- `taskSerialMap.compute(...)`
- `CompletableFuture.supplyAsync(...)`
- 前序 future 的 `handleAsync(...)`
- `whenComplete(...)` 清理 `taskSerialMap / taskSerialCountMap`

当前实现通过 `serialQueueCapacity / serialQueueHardLimit` 控制同 taskId 串行链容量，并通过 `handleAsync` 避免前序 future 异常阻断后续同 taskId 任务。

### CALLER_RUNS 溢出执行调用链

- `ThreadQueue.acquireSlot(...)`
- 队列满且策略为 `CALLER_RUNS`
- `ThreadPool.runInCaller(...)`
- `beforeExecute(...)`
- 用户 task `run()`
- `afterExecute(...)`

当前 caller-runs 路径已纳入 `SINGLE`、`THREAD_TRACE`、`INHERIT_FAST_THREAD_LOCALS` 和 `taskMap` 生命周期处理。

### WheelTimer 调用链

- `WheelTimer.setTimeout(...)`
- `WheelTimer.setTimeout(Task)`
- `WheelTimer.scheduleInitial(...)`
- `WheelTimer.newTimeout(...)`
- Netty `HashedWheelTimer`
- `WheelTimer.Task.run(Timeout)`
- 外部 `executor.submit(...)`
- `Task.onExecutionFinished(...)`
- `activeTasks / holder / periodicTasks` 清理

当前实现已加强 executor reject 和初始调度失败路径，能唤醒 `get()` 等待方，并清理 active / holder / periodic 状态。

### CpuWatchman 扩缩容调用链

- `CpuWatchman.run(...)`
- 读取 OS CPU load
- `normalizeCpuLoad(...)`
- `thread(...)` 或 `scheduledThread(...)`
- `resize(...)`
- `incrSize(...)` / `decrSize(...)`
- cooldown / jitter / min-max 边界控制

当前实现已处理 CPU load 非法值、cooldown、per-pool jitter、PRIORITY 直调路径和 resize 上下界。

# 目标

1. 明确当前实现已完成内容。
2. 明确 `SINGLE namespace / scope` 不实现，并从后续待办移除。
3. 基于当前分支最新代码重新整理剩余待办。
4. 标注每个待办的优先级、原因、建议处理方式和验证方式。
5. 本次只更新计划文档，不修改业务代码、不触发 CI。

# 非目标

- 不实现 `SINGLE namespace / scope`。
- 不修改 `ThreadPool.java`、`WheelTimer.java`、`CpuWatchman.java` 或测试代码。
- 不调整 `.github/workflows/**`。
- 不升级 JDK、Maven 插件或依赖版本。
- 不自动创建 PR、不 merge、不发布 release。
- 不触发 GitHub Actions，因为本次不是代码提交。

# Review 结论

## 1. ThreadPool / ThreadQueue

当前实现方向正确。`ThreadQueue` 已覆盖 `offer/poll/take/remove/drainTo/clear` 等主要路径，且第四阶段新增 `CapacitySnapshot` 和并发测试，能更直接验证：

- `counter` 不小于 0。
- `availableSlots` 与逻辑剩余容量一致。
- `remove/clear/drainTo` 不泄露 slot。
- `shutdownNow()` 与 producer blocking 并发时不会永久阻塞。
- `CALLER_RUNS` 与 shutdown race 后不泄露 `taskMap`。

本轮 review 认为 `ThreadQueue` 的核心风险已经明显下降。后续仍需保留这组测试作为线程池安全边界回归测试。

## 2. shutdown 后提交语义

当前 `ThreadPool` 自定义 rejection handler 在 executor 已 shutdown 时会抛 `RejectedExecutionException`。这是比“只记录 warn 并吞掉任务”更符合 JDK executor 语义的方向。

需要同步注意：

- 文档和调用方预期要明确：shutdown 后提交任务会 reject，而不是静默丢弃。
- `Tasks.executor()` 的 no-op shutdown wrapper 与 standalone `ThreadPool.shutdown()` 语义不同，需要继续在文档中区分。

## 3. SERIAL 队列

当前 `SERIAL` 容量、异常链和清理路径已经有回归测试。剩余风险主要不是功能 bug，而是业务使用方式风险：

- 高基数 taskId 会产生大量短生命周期 map entry。
- 如果业务 taskId 直接来自用户输入，可能导致指标和日志基数过高。
- 单 taskId 长链仍可能产生背压和拒绝，需要调用方理解 `serialQueueCapacity` 语义。

## 4. WheelTimer

第四阶段已补强 `WheelTimer` executor reject 和 initial schedule 失败路径。当前 review 认为以下路径已经更安全：

- executor reject 后 `get()` 能被唤醒。
- `activeTasks` / `holder` / `periodicTasks` 清理有测试覆盖。
- negative initial delay 能发布取消并清理 holder。
- periodic executor reject 后能从 `periodicTasks` 移除。

仍需继续关注 `shutdown()` 与底层 Netty `HashedWheelTimer` 的生命周期一致性。当前 `shutdownNow()` 会 `timer.stop()`，但 `shutdown()` 主要是取消已跟踪 task，并未显式 stop 底层 timer。若 standalone `WheelTimer.shutdown()` 后底层 timer 线程仍存活，可能出现资源释放语义不完整的问题，需要单独确认和测试。

## 5. CpuWatchman

当前 `CpuWatchman` 已覆盖主要边界：

- CPU load `<0` 或 NaN 跳过。
- CPU load 上限 clamp 到 100。
- cooldown 统一判断。
- per-pool jitter 降低多 pool 同时扩缩容抖动。
- `incrSize()` / `decrSize()` 静态入口也进入 cooldown。
- resize 不越过 `minIdleSize / maxPoolSize`。

剩余风险主要是兼容性和锁粒度：

- `com.sun.management.OperatingSystemMXBean` 是 com.sun API，JDK8 常见但非标准。
- `holder` 使用 synchronized map，采样周期内遍历和 resize 可能放大锁持有时间；当前可接受，但需要避免未来在锁内做更重操作。

## 6. FastThreadLocal 隔离

当前通过反射复制 `InternalThreadLocalMap.indexedVariables`。JDK8 兼容，但依赖 Netty internal 结构，Netty 大版本升级时风险较高。

本轮不建议修改该实现；建议把 Netty 升级兼容验证作为独立待办。

## 7. Tasks 全局入口

当前 `Tasks.executor().shutdown()` / `shutdownNow()` 是 no-op wrapper，避免误关全局共享线程池、timer、watchman。这个方向符合全局入口设计。

需要持续在文档中强调：

- `Tasks` 是全局共享入口。
- standalone `ThreadPool` 由创建方管理生命周期。
- standalone `WheelTimer` 应具备清晰 shutdown / awaitTermination 语义。

# 已完成项

当前已完成并有对应实现或测试覆盖的事项：

1. 队列 offer 策略：`BLOCK / TIMEOUT_REJECT / CALLER_RUNS`。
2. SERIAL 容量控制。
3. SERIAL compute 重入风险修复。
4. SERIAL 前序异常不阻断后续同 taskId 任务。
5. CompletableFuture async pool patch 默认关闭。
6. CALLER_RUNS 生命周期补齐。
7. FastThreadLocal 隔离与恢复。
8. 配置解析和测试隔离。
9. Tasks no-op shutdown 包装语义。
10. WheelTimer active timeout tracking。
11. CpuWatchman resize 边界、cooldown 和 jitter。
12. ThreadQueue `drainTo/clear/remove` slot 释放。
13. standalone `ThreadPool.shutdown/shutdownNow` unregister。
14. WheelTimer shutdown periodic 测试。
15. CpuWatchman resize 测试。
16. ThreadQueue shutdown race / caller-runs race / 并发容量不变量测试。
17. WheelTimer executor reject / periodic reject / negative initial delay 测试。
18. shutdown 后提交任务改为 `RejectedExecutionException`。
19. `docs/reference/ThreadPool.md` 已有部分生命周期和指标说明。

# 不再作为待办

## SINGLE namespace / scope

用户已明确：`SINGLE namespace / scope` 不实现。

因此以下内容从当前待办中移除：

- 不新增 `SingleKey(namespace, taskId)`。
- 不新增带 namespace 的 `run/runAsync` 重载。
- 不修改 `runningSingleTasks` key 结构。
- 不增加跨 namespace 同 taskId 并行测试。
- 不围绕 namespace/scope 修改文档或指标。

保留现状：`runningSingleTasks` 继续作为 JVM 全局互斥集合使用。调用方如担心跨业务 taskId 冲突，应自行在 taskId 中加入业务前缀。

# 剩余待办

## P0：确认并修正 WheelTimer.shutdown() 底层 timer 生命周期语义

### 问题

当前 `WheelTimer.shutdownNow()` 会调用 `timer.stop()`，但 `shutdown()` 主要取消 `activeTasks` 和 `periodicTasks`，没有显式 stop Netty `HashedWheelTimer`。

需要确认：

- `shutdown()` 后底层 `HashedWheelTimer` 线程是否仍存活。
- `awaitTermination()` 返回 true 时，底层 timer 是否也已停止。
- standalone `new WheelTimer(...)` 调用 `shutdown()` 后是否存在 timer thread 泄露。

### 建议

若确认存在资源释放不完整：

- 在 `shutdown()` 中取消已跟踪 task 后安全 stop timer，或
- 在 active / holder / periodic 清空后 stop timer，确保 `awaitTermination()` 语义覆盖底层 timer。
- 增加测试验证 shutdown 后 timer thread 不再存活或 pending timeout 为 0。

### 验证

新增或补强 `WheelTimerShutdownPeriodicTest`：

- standalone `WheelTimer.shutdown()` 后 `awaitTermination()` 返回 true。
- shutdown 后不能再提交新 timeout。
- shutdown 后没有未清理 holder / active / periodic。
- 若可观测，验证底层 timer stop 行为。

## P1：同步文档中的 shutdown 后提交语义

### 问题

当前代码已将 shutdown 后提交任务调整为抛 `RejectedExecutionException`，但文档需要明确该语义，避免调用方误以为 shutdown 后提交只是 warn + ignore。

### 建议

更新 `docs/reference/ThreadPool.md`：

- standalone `ThreadPool.shutdown()` 后再 `execute/submit/run/runAsync` 会 reject。
- `Tasks.executor().shutdown()` 仍是 no-op wrapper，不会关闭共享线程池。
- 区分全局入口和 standalone 实例生命周期。

### 验证

文档变更不需要单测，但需要人工 review 文档表达是否与代码一致。

## P1：保留并扩展 ThreadQueue 并发不变量回归测试

### 问题

`ThreadQueue` 是线程池安全边界。当前已有并发测试，但后续任何队列修改都可能再次破坏 slot/counter/taskMap 不变量。

### 建议

把以下测试视为长期必跑回归：

- `shutdownNow()` 与 producer blocking 并发。
- `CALLER_RUNS` 溢出执行与 shutdown 并发。
- `remove()` / `clear()` / `drainTo()` 与 worker `poll/take` 并发。
- `counter >= 0`。
- `counter + availableSlots == capacity`。
- `queue.size() == counter` 在测试可控场景成立。
- `taskMap` 无泄漏。

### 验证

继续执行：

```bash
mvn -pl rxlib -am -Dmaven.test.skip=false -DskipTests=false -Dgpg.skip=true -Dmaven.gpg.skip=true -Dtest=ThreadPoolQueueShutdownTest test --batch-mode --no-transfer-progress
```

## P1：补一次完整 CI / Maven 验证记录

### 问题

当前分支已有第四阶段代码提交，但本次 review 只更新计划文档，没有触发 CI。需要在后续代码阶段或合并前确认最新 head 的测试结论。

### 建议

后续代码提交或合并前执行 GitHub Actions 或 Maven：

```bash
mvn -pl rxlib -am -Dmaven.test.skip=false -DskipTests=false -Dgpg.skip=true -Dmaven.gpg.skip=true -Dtest=ThreadPoolWheelTimerRegressionTest,ThreadPoolQueueOfferModeTest,RxConfigTest,TasksCompatibilityTest,ThreadPoolTest test --batch-mode --no-transfer-progress

mvn -pl rxlib -am -Dmaven.test.skip=false -DskipTests=false -Dgpg.skip=true -Dmaven.gpg.skip=true '-Dtest=*CpuWatchman*,*WheelTimer*,*Tasks*' test --batch-mode --no-transfer-progress

mvn -pl rxlib -am -Dmaven.test.skip=false -DskipTests=false -Dgpg.skip=true -Dmaven.gpg.skip=true -Dtest=WheelTimerShutdownPeriodicTest,CpuWatchmanResizeTest,ThreadPoolQueueShutdownTest,SerialQueueCapacityTest test --batch-mode --no-transfer-progress
```

如有 GitHub workflow：

- 优先触发 `agent-ci.yml`。
- 如果没有 `agent-ci.yml`，使用仓库现有 CI workflow。
- 只在 `conclusion=success` 时认为 CI 通过。

## P2：评估 `ThreadQueue.CapacitySnapshot` 是否保留在生产类中

### 问题

`CapacitySnapshot` 当前位于 `ThreadPool.ThreadQueue` 内，包可见，主要服务测试。它不是 public API，风险较低，但仍属于生产代码中的测试辅助结构。

### 建议

两种方案：

- 保留：作为包可见诊断快照，方便后续并发测试和问题排查。
- 收敛：如不希望生产类暴露测试辅助结构，可改为更通用的包可见诊断方法或通过现有 public 方法组合验证。

短期建议保留，不做无关重构。

## P2：Netty internal 反射兼容验证

### 问题

`ThreadPool` 对 `InternalThreadLocalMap` 构造器和 `indexedVariables` 字段有反射依赖。当前 JDK8 / 当前 Netty 版本下可用，但 Netty 升级可能破坏。

### 建议

将 Netty 升级作为单独任务处理，并在升级前先跑：

- `INHERIT_FAST_THREAD_LOCALS` 相关回归测试。
- FastThreadLocal 继承、恢复、隔离测试。
- 普通线程和 `FastThreadLocalThread` 两类路径。

## P2：Metrics 标签基数约束

### 问题

当前指标包含 pool name、reason、size、raw 等标签/字段。未来如果把完整业务 taskId 放入指标，会产生高基数风险。

### 建议

明确约束：

- 指标标签中不允许放完整 taskId。
- 如需定位 taskId，只输出 hash 或采样日志。
- 文档中补充 metrics label cardinality 注意事项。

## P3：CpuWatchman 锁粒度观察

### 问题

`CpuWatchman` 当前通过 synchronized map 持有 holder 并遍历 pool。现阶段逻辑不重，但如果后续在锁内增加重操作，会影响采样线程。

### 建议

短期不改。后续如遇到采样抖动或锁竞争，再考虑：

- synchronized 内复制 snapshot。
- synchronized 外执行 resize。
- 增加采样耗时指标。

# JDK8 兼容性约束

本仓库必须保持 JDK8 兼容。本次 review 和后续建议均遵守：

- 不使用 Java 9+ API。
- 不引入需要 JDK9+ 的依赖版本。
- 不使用 `Map.of`、`List.of`、`CompletableFuture` Java 9+ 新方法等 API。
- 继续使用 JDK8 可用的 `CompletableFuture`、`ConcurrentHashMap`、`LongAdder`、`Semaphore`。
- `com.sun.management.OperatingSystemMXBean` 在 JDK8 常见环境可用，但属于 com.sun API；如支持非 HotSpot/JDK 分发版，需要单独兼容验证。

# 修改文件列表

本次实际修改：

- `docs/plan/thread-pool-review-plan.md`

本次只更新计划文档，不修改业务代码和测试代码。

如果后续用户明确要求执行剩余待办，预计可能修改：

- `rxlib/src/main/java/org/rx/core/WheelTimer.java`
- `rxlib/src/test/java/org/rx/core/WheelTimerShutdownPeriodicTest.java`
- `docs/reference/ThreadPool.md`

# 风险点

- 兼容性风险：`InternalThreadLocalMap` 反射依赖 Netty internal 结构。
- 并发风险：ThreadQueue slot/counter 与 executor shutdown 并发仍是高风险区，需要长期回归。
- 资源释放风险：`WheelTimer.shutdown()` 与底层 Netty timer 生命周期语义需要确认。
- 可观测性风险：指标标签不能引入完整 taskId 等高基数信息。
- 测试风险：线程调度类测试可能受机器负载影响，需要避免脆弱 sleep 断言。
- 语义风险：`Tasks.executor()` no-op shutdown 与 standalone `ThreadPool.shutdown()` reject 语义需要文档明确区分。

# 验证方案

本次只更新计划文档，不触发 CI。

后续若进入代码实现阶段，建议执行：

```bash
mvn -pl rxlib -am -Dmaven.test.skip=false -DskipTests=false -Dgpg.skip=true -Dmaven.gpg.skip=true -Dtest=ThreadPoolWheelTimerRegressionTest,ThreadPoolQueueOfferModeTest,RxConfigTest,TasksCompatibilityTest,ThreadPoolTest test --batch-mode --no-transfer-progress

mvn -pl rxlib -am -Dmaven.test.skip=false -DskipTests=false -Dgpg.skip=true -Dmaven.gpg.skip=true '-Dtest=*CpuWatchman*,*WheelTimer*,*Tasks*' test --batch-mode --no-transfer-progress

mvn -pl rxlib -am -Dmaven.test.skip=false -DskipTests=false -Dgpg.skip=true -Dmaven.gpg.skip=true -Dtest=WheelTimerShutdownPeriodicTest,CpuWatchmanResizeTest,ThreadPoolQueueShutdownTest,SerialQueueCapacityTest test --batch-mode --no-transfer-progress
```

GitHub Actions 验证规则：

- 代码 commit 后再触发 CI。
- 优先查找 `agent-ci.yml`。
- 如果没有 `agent-ci.yml`，使用仓库已有 CI workflow。
- 只在 `conclusion=success` 时认为 CI 通过。
- 如果 CI 失败，需要读取失败日志并按失败类型修复。

# 回滚方案

本次只修改计划文档，回滚方式：

```bash
git revert <本次计划文档 commit>
```

若后续实现阶段出现问题：

- `WheelTimer.shutdown()` 问题：回滚 timer stop / shutdown 语义相关提交。
- 文档语义问题：revert 文档提交或再次修正文档。
- ThreadQueue 并发不变量问题：优先回滚最近队列路径提交，并保留回归测试定位。
- SERIAL 容量问题：临时调高 `serialQueueCapacity`，必要时回滚 SERIAL 容量限制提交。
- CompletableFuture patch 问题：保持 `patchCompletableFutureAsyncPool=false`。
- CpuWatchman resize 问题：调大 `resizeCooldownMillis` 或回滚 resize guard 提交。
- Tasks 入口问题：保持 no-op wrapper，不在生产路径关闭底层共享资源。

# 当前结论

ThreadPool 第一阶段安全边界和可观测性已经基本完成。当前代码已经覆盖队列 offer 策略、SERIAL 容量、CompletableFuture patch 默认关闭、CALLER_RUNS 生命周期、FastThreadLocal 隔离、配置解析容错、Tasks no-op 包装语义、WheelTimer active timeout 跟踪、CpuWatchman resize 边界、ThreadQueue slot 释放、standalone ThreadPool 生命周期、shutdown race 测试以及 WheelTimer executor reject 测试。

根据用户最新要求，`SINGLE namespace / scope` 不实现，已从待办移除。

当前剩余待办中，优先级最高的是确认并修正 `WheelTimer.shutdown()` 是否完整停止底层 Netty timer；其次是同步 shutdown 后提交语义文档、保留 ThreadQueue 并发不变量测试、补一次最新分支的完整 CI/Maven 验证记录。
