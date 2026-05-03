# rxlib ThreadPool Review 与优化计划

> 生成日期：2026-05-03  
> 最近更新：2026-05-03  
> 分支：`agent/20260503-thread-pool-review`  
> 当前 review 基线：`8debeba62518c3260af19d2a7d64e341700be884`  
> 本次更新类型：Review 类任务，仅更新计划文档，不修改业务代码。  
> 明确范围：`SINGLE namespace / scope` 不实现，已从待办中移除。

# 背景

用户说明当前分支已更新，要求重新 review 当前线程池相关实现，并将结果更新到 `docs/plan/thread-pool-review-plan.md`。

本次 review 基于 `agent/20260503-thread-pool-review` 最新 head `8debeba62518c3260af19d2a7d64e341700be884`，重点复查上一轮最高优先级待办：`WheelTimer.shutdown()` 是否完整停止底层 Netty `HashedWheelTimer`，以及最新代码更新后还剩哪些待办。

# 任务类型判断

本次任务归类为 **Review / 修复 / 重构 / 优化需求**。

原因：

- 用户要求 “再 review 下，结果更新到计划文档”。
- 当前未要求直接修改业务代码。
- 本阶段目标是理解最新分支实现、识别剩余风险、更新计划文档。
- 按 agent 流程，本次只允许 review 并提交计划文档；没有用户明确要求“按计划执行 / 开始修改代码 / 实现代码”前，不进入代码实现阶段。

# 当前上下文

## 已 review 的文件

- `rxlib/src/main/java/org/rx/core/ThreadPool.java`
- `rxlib/src/main/java/org/rx/core/WheelTimer.java`
- `rxlib/src/main/java/org/rx/core/CpuWatchman.java`
- `rxlib/src/test/java/org/rx/core/ThreadPoolQueueShutdownTest.java`
- `rxlib/src/test/java/org/rx/core/ThreadPoolWheelTimerRegressionTest.java`
- `rxlib/src/test/java/org/rx/core/WheelTimerShutdownPeriodicTest.java`
- `rxlib/src/test/java/org/rx/core/ThreadPoolTest.java`
- `docs/reference/ThreadPool.md`
- `docs/plan/thread-pool-review-plan.md`

## 最新代码提交

当前分支最新代码提交：

- commit：`8debeba62518c3260af19d2a7d64e341700be884`
- message：`WheelTimer shutdown 时序修复与状态一致性保证`

该提交主要包含：

- `WheelTimer` 新增 `timerStopStarted` 和 `timerStopped` 状态。
- 新增 `stopTimer()`，通过 CAS 防止重复停止底层 Netty timer。
- `shutdown()` 中增加 holder futures 取消，避免带 taskId 的 timeout 遗漏。
- `shutdown()` 中调用 `stopTimer()`，现在会停止底层 Netty `HashedWheelTimer`。
- `shutdownNow()` 改为复用 `stopTimer()`。
- `isTerminated()` 增加 `timerStopped` 判定。
- `WheelTimerShutdownPeriodicTest` 增加 standalone shutdown 停止底层 timer、拒绝新任务、清空状态的测试。
- `docs/reference/ThreadPool.md` 同步说明 standalone `ThreadPool` shutdown 后提交任务会抛 `RejectedExecutionException`，以及 `WheelTimer.shutdown()` 会停止底层 timer。

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

### WheelTimer shutdown 调用链

- `WheelTimer.shutdown()`
- `shutdown = true`
- 遍历 `holder.values()` 并 `future.cancel(false)`
- 遍历 `activeTasks` 并 `task.cancel(false)`
- 遍历 `periodicTasks` 并 `periodicTask.cancel(false)`
- `stopTimer()`
- `timer.stop()`
- `timerStopped = true`
- `recordDiagnosticMetrics(true)`
- `awaitTermination(...)` 轮询 `isTerminated()`
- `isTerminated()` 要求 `timerStopped && holder/active/periodic 清空`

最新实现已经解决上一轮 P0：`shutdown()` 不再只取消上层 task，也会停止底层 Netty timer。

### WheelTimer 定时任务执行调用链

- `WheelTimer.setTimeout(...)`
- `WheelTimer.setTimeout(Task)`
- `WheelTimer.scheduleInitial(...)`
- `WheelTimer.newTimeout(...)`
- Netty `HashedWheelTimer`
- `WheelTimer.Task.run(Timeout)`
- 外部 `executor.submit(...)`
- `Task.onExecutionFinished(...)`
- `activeTasks / holder / periodicTasks` 清理

当前实现已加强 executor reject、初始调度失败、negative delay、periodic reject 等路径，能唤醒 `get()` 等待方，并清理 active / holder / periodic 状态。

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

1. 复查当前最新分支实现。
2. 更新上一轮 P0 待办状态：`WheelTimer.shutdown()` 底层 timer 停止语义已实现。
3. 明确 `SINGLE namespace / scope` 不实现。
4. 基于最新代码重新列出剩余待办。
5. 本次只更新计划文档，不修改业务代码、不触发 CI。

# 非目标

- 不实现 `SINGLE namespace / scope`。
- 不修改 `ThreadPool.java`、`WheelTimer.java`、`CpuWatchman.java` 或测试代码。
- 不调整 `.github/workflows/**`。
- 不升级 JDK、Maven 插件或依赖版本。
- 不自动创建 PR、不 merge、不发布 release。
- 不触发 GitHub Actions，因为本次不是代码提交。

# Review 结论

## 1. WheelTimer.shutdown() P0 已完成

上一轮最高优先级待办是：确认并修正 `WheelTimer.shutdown()` 是否完整停止底层 Netty timer。

当前最新提交已经实现：

- `shutdown()` 会取消 holder、active、periodic 三类 task。
- `shutdown()` 会调用 `stopTimer()` 停止底层 `HashedWheelTimer`。
- `shutdownNow()` 复用同一个 `stopTimer()`，避免重复 stop。
- `isTerminated()` 需要 `timerStopped == true`。
- 文档已同步 `WheelTimer.shutdown()` 会停止底层 timer。
- 测试覆盖 standalone shutdown 后拒绝新任务、pending timeout 为 0、worker thread 停止、集合清空。

本轮 review 判断：该项已从 P0 待办转为已完成。

## 2. ThreadPool / ThreadQueue

当前实现方向正确。`ThreadQueue` 已覆盖 `offer/poll/take/remove/drainTo/clear` 等主要路径，且已有 `CapacitySnapshot` 和并发测试验证：

- `counter` 不小于 0。
- `availableSlots` 与逻辑剩余容量一致。
- `remove/clear/drainTo` 不泄露 slot。
- `shutdownNow()` 与 producer blocking 并发时不会永久阻塞。
- `CALLER_RUNS` 与 shutdown race 后不泄露 `taskMap`。

本轮未发现新的必须立即修改项。后续重点是保持这些回归测试长期必跑。

## 3. shutdown 后提交语义

当前 standalone `ThreadPool` shutdown 后再次提交任务会抛 `RejectedExecutionException`，`docs/reference/ThreadPool.md` 已同步该语义。

该方向符合 JDK executor 语义。仍需在合并前通过完整测试确认所有旧测试都已更新，不再依赖 warn + ignore 行为。

## 4. SERIAL 队列

当前 `SERIAL` 容量、异常链和清理路径已经有回归测试。剩余风险主要是使用方式风险：

- 高基数 taskId 会产生大量短生命周期 map entry。
- 单 taskId 长链仍可能触发 `serialQueueCapacity` 拒绝。
- 指标和日志不应包含完整业务 taskId。

本轮不建议再扩大 SERIAL 代码改动范围。

## 5. WheelTimer 剩余边界

当前 shutdown 主流程已明显改善，但仍有两个边界值得后续加固：

1. `stopTimer()` 没有对 `timer.stop()` 抛异常时的状态恢复做保护。如果 `timer.stop()` 抛出运行时异常，`timerStopStarted` 已经变为 true，但 `timerStopped` 可能仍为 false，后续重试 stop 会被 CAS 阻止，`awaitTermination()` 可能永久返回 false。Netty `HashedWheelTimer.stop()` 在某些非法调用场景可能抛异常，因此建议加固。
2. 新增测试通过反射读取 Netty `HashedWheelTimer.workerThread` 私有字段。该测试能验证底层线程停止，但对 Netty internal 字段名敏感；未来 Netty 升级可能导致测试失败。

这两个点不阻塞当前功能方向，但建议列为 P1/P2 待办。

## 6. CpuWatchman

当前 `CpuWatchman` 已覆盖主要边界：

- CPU load `<0` 或 NaN 跳过。
- CPU load 上限 clamp 到 100。
- cooldown 统一判断。
- per-pool jitter 降低多 pool 同时扩缩容抖动。
- `incrSize()` / `decrSize()` 静态入口也进入 cooldown。
- resize 不越过 `minIdleSize / maxPoolSize`。

本轮未发现新增阻塞风险。后续只保留锁粒度和 com.sun API 兼容性观察项。

## 7. FastThreadLocal 隔离

当前通过反射复制 `InternalThreadLocalMap.indexedVariables`。JDK8 兼容，但依赖 Netty internal 结构，Netty 大版本升级时风险较高。

本轮不建议修改该实现；建议把 Netty 升级兼容验证作为独立待办。

## 8. Tasks 全局入口

当前 `Tasks.executor().shutdown()` / `shutdownNow()` 是 no-op wrapper，避免误关全局共享线程池、timer、watchman。这个方向符合全局入口设计。

需要继续区分：

- `Tasks` 是全局共享入口。
- standalone `ThreadPool` 是资源拥有者，shutdown 后拒绝新任务。
- standalone `WheelTimer` 是资源拥有者，shutdown 后停止底层 timer 并拒绝新任务。

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
19. `docs/reference/ThreadPool.md` 同步生命周期和指标说明。
20. `WheelTimer.shutdown()` 停止底层 Netty `HashedWheelTimer`。
21. `WheelTimer.isTerminated()` 增加 `timerStopped` 判定。
22. `WheelTimer.shutdown()` 取消 holder 中带 taskId 的 timeout future。

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

## P0：补一次最新分支完整 CI / Maven 验证记录

### 问题

当前分支已有新的代码提交 `8debeba62518c3260af19d2a7d64e341700be884`，涉及 `WheelTimer.shutdown()` 时序、状态一致性、测试和参考文档。本次 review 只更新计划文档，没有触发 CI。

### 建议

合并前必须对最新 head 执行完整线程池相关测试，并记录结果。

### 验证

建议执行：

```bash
mvn -pl rxlib -am -Dmaven.test.skip=false -DskipTests=false -Dgpg.skip=true -Dmaven.gpg.skip=true -Dtest=ThreadPoolWheelTimerRegressionTest,ThreadPoolQueueOfferModeTest,RxConfigTest,TasksCompatibilityTest,ThreadPoolTest test --batch-mode --no-transfer-progress

mvn -pl rxlib -am -Dmaven.test.skip=false -DskipTests=false -Dgpg.skip=true -Dmaven.gpg.skip=true '-Dtest=*CpuWatchman*,*WheelTimer*,*Tasks*' test --batch-mode --no-transfer-progress

mvn -pl rxlib -am -Dmaven.test.skip=false -DskipTests=false -Dgpg.skip=true -Dmaven.gpg.skip=true -Dtest=WheelTimerShutdownPeriodicTest,CpuWatchmanResizeTest,ThreadPoolQueueShutdownTest,SerialQueueCapacityTest test --batch-mode --no-transfer-progress
```

若使用 GitHub Actions：

- 优先触发 `agent-ci.yml`。
- 如果没有 `agent-ci.yml`，使用仓库现有 CI workflow。
- 只在 `conclusion=success` 时认为 CI 通过。

## P1：加固 WheelTimer.stopTimer() 异常路径

### 问题

当前 `stopTimer()`：

```java
if (timerStopStarted.compareAndSet(false, true)) {
    timer.stop();
    timerStopped = true;
}
```

如果 `timer.stop()` 抛出运行时异常，`timerStopStarted` 已经变为 true，但 `timerStopped` 不会变为 true，后续调用也无法重试 stop，`awaitTermination()` 可能一直返回 false。

### 建议

后续实现阶段建议加固：

- catch `RuntimeException` / `Error` 后记录日志和指标。
- 失败时考虑将 `timerStopStarted` 恢复为 false，允许后续外部线程重试。
- 或明确将异常传播并保证调用方能感知 shutdown 失败。
- 增加并发 shutdown/shutdownNow 重入测试。

### 验证

新增或补强测试：

- 并发多线程同时调用 `shutdown()` / `shutdownNow()` 不抛异常。
- 重复调用 `shutdown()` / `shutdownNow()` 后 `awaitTermination()` 仍为 true。
- 如果能构造 stop 异常，验证不会造成永久不可终止状态。

## P1：补充 running task 场景下 WheelTimer.shutdown() 的 await 语义测试

### 问题

当前测试覆盖 long delay、holder、periodic、executor reject、底层 timer stop。但仍建议明确覆盖：timeout 已触发并提交到外部 executor 后，用户任务仍在运行，此时调用 `shutdown()` 的行为。

当前设计上：

- `shutdown()` 会 cancel active task。
- 如果外部 future 已开始执行且 `cancel(false)` 无法中断，`activeTasks` 应在 task 完成后由 `completeTask()` 移除。
- `awaitTermination()` 不应在 active task 完成前错误返回 true。

### 建议

增加测试：

- timeout 立即触发，用户任务阻塞。
- 调用 `shutdown()`。
- 确认 `awaitTermination(short timeout)` 在任务未释放前返回 false。
- 释放任务后确认 `awaitTermination()` 返回 true，且 active/holder/periodic 为空。

## P1：保留 ThreadQueue 并发不变量回归测试为必跑项

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

## P2：降低 WheelTimerShutdownPeriodicTest 对 Netty 私有字段的依赖

### 问题

最新测试通过反射读取 `HashedWheelTimer.workerThread` 私有字段来验证底层 timer thread 停止。该验证很直接，但依赖 Netty internal 字段名，未来 Netty 升级可能导致测试失败。

### 建议

短期可保留，因为本仓库已有 Netty internal 反射依赖。中期可考虑：

- 将该测试标注为 Netty internal 兼容性测试。
- 只用 `awaitTermination()`、`pendingTimeouts()`、拒绝新任务、状态集合清空验证语义，减少对 workerThread 字段的硬依赖。
- 若仍保留反射，失败时给出清晰断言信息，提示 Netty internal 字段变化。

## P2：评估 ThreadQueue.CapacitySnapshot 是否长期保留在生产类中

### 问题

`CapacitySnapshot` 当前位于 `ThreadPool.ThreadQueue` 内，包可见，主要服务测试。它不是 public API，风险较低，但仍属于生产代码中的测试辅助结构。

### 建议

短期保留，作为包可见诊断快照，方便后续并发测试和问题排查。若未来要收敛，可改为更通用的包可见诊断方法。

## P2：Netty internal 反射兼容验证

### 问题

`ThreadPool` 对 `InternalThreadLocalMap` 构造器和 `indexedVariables` 字段有反射依赖；`WheelTimerShutdownPeriodicTest` 也读取 `HashedWheelTimer.workerThread`。当前 JDK8 / 当前 Netty 版本下可用，但 Netty 升级可能破坏。

### 建议

将 Netty 升级作为单独任务处理，并在升级前先跑：

- `INHERIT_FAST_THREAD_LOCALS` 相关回归测试。
- FastThreadLocal 继承、恢复、隔离测试。
- WheelTimer shutdown internal 兼容性测试。
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
- 继续使用 JDK8 可用的 `CompletableFuture`、`ConcurrentHashMap`、`LongAdder`、`Semaphore`、`AtomicBoolean`。
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

- CI 风险：最新代码提交还需要完整 Maven / GitHub Actions 结果确认。
- 资源释放风险：`stopTimer()` 需要加固 `timer.stop()` 抛异常后的状态恢复或失败传播。
- 并发风险：ThreadQueue slot/counter 与 executor shutdown 并发仍是高风险区，需要长期回归。
- 测试脆弱性：`WheelTimerShutdownPeriodicTest` 反射读取 Netty 私有 `workerThread` 字段，Netty 升级时可能失败。
- 兼容性风险：`InternalThreadLocalMap` 反射依赖 Netty internal 结构。
- 可观测性风险：指标标签不能引入完整 taskId 等高基数信息。
- 语义风险：`Tasks.executor()` no-op shutdown、standalone `ThreadPool.shutdown()` reject、standalone `WheelTimer.shutdown()` stop timer 三者需要持续在文档中区分。

# 验证方案

本次只更新计划文档，不触发 CI。

后续若进入代码实现阶段或准备合并，建议执行：

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

- `WheelTimer.stopTimer()` 问题：回滚 stopTimer 加固提交，或恢复到当前 CAS stop 语义。
- `WheelTimer.shutdown()` 问题：回滚 timer stop / shutdown 语义相关提交。
- 文档语义问题：revert 文档提交或再次修正文档。
- ThreadQueue 并发不变量问题：优先回滚最近队列路径提交，并保留回归测试定位。
- SERIAL 容量问题：临时调高 `serialQueueCapacity`，必要时回滚 SERIAL 容量限制提交。
- CompletableFuture patch 问题：保持 `patchCompletableFutureAsyncPool=false`。
- CpuWatchman resize 问题：调大 `resizeCooldownMillis` 或回滚 resize guard 提交。
- Tasks 入口问题：保持 no-op wrapper，不在生产路径关闭底层共享资源。

# 当前结论

当前分支已经完成上一轮最高优先级待办：`WheelTimer.shutdown()` 现在会停止底层 Netty `HashedWheelTimer`，`isTerminated()` 也将 `timerStopped` 纳入判定，参考文档和测试已经同步更新。

`SINGLE namespace / scope` 不实现，已从待办中移除。

当前剩余待办中，最高优先级是补一次最新 head 的完整 CI / Maven 验证；其次是加固 `WheelTimer.stopTimer()` 在 `timer.stop()` 抛异常时的状态恢复或失败传播，并补充 running task 场景下 `shutdown()` / `awaitTermination()` 的行为测试。其余为测试脆弱性、Netty internal 兼容、指标标签基数和 CpuWatchman 锁粒度观察项。
