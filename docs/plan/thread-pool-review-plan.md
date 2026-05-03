# rxlib ThreadPool Review 与优化计划

> 生成日期：2026-05-03
> 最近更新：2026-05-03
> 本次更新分支：`agent/20260503-thread-pool-review`
> 本次更新类型：Review 类任务，仅更新计划文档，不修改业务代码。

# 背景

用户要求对 `RockyLOMO/rxlib` 当前线程池相关实现继续 review，并将结果更新到 `docs/plan/thread-pool-review-plan.md`。

本次 review 聚焦 `rxlib` 中 thread pool、全局任务入口、时间轮调度和 CPU 动态扩缩容相关实现，目标是沉淀当前代码状态、已发现风险、后续修改计划与验证方式。

# 任务类型判断

本次任务归类为 **Review / 修复 / 重构 / 优化需求**。

原因：

- 用户明确要求 “review 下目前的实现，结果更新到计划文档”。
- 当前不要求直接修改业务代码。
- 任务重点是理解现有实现、梳理调用链、识别边界条件和风险，并更新计划文档。
- 按 agent 流程，本阶段只允许 review 并提交计划文档，后续需要用户明确要求“按计划执行 / 开始修改代码 / 实现代码”后才能进入代码实现阶段。

# 当前上下文

## 已 review 的文件

- `rxlib/src/main/java/org/rx/core/ThreadPool.java`
- `rxlib/src/main/java/org/rx/core/WheelTimer.java`
- `rxlib/src/main/java/org/rx/core/CpuWatchman.java`
- `docs/plan/thread-pool-review-plan.md`

## 关键调用链

### ThreadPool 提交流程

- `ThreadPool.run(...)` / `runAsync(...)` / `execute(...)`
- `ThreadPool.Task.adapt(...)`
- `ThreadPool.setTask(...)`
- `ThreadPoolExecutor.execute(...)`
- `ThreadPool.ThreadQueue.offer(...)`
- `ThreadPool.beforeExecute(...)`
- `ThreadPool.afterExecute(...)`

当前 `ThreadQueue` 通过 `Semaphore availableSlots` 和 `AtomicInteger counter` 维护有界容量，并支持 `BLOCK / TIMEOUT_REJECT / CALLER_RUNS` 入队策略。`poll/take/remove/drainTo/clear` 路径均需要保持 slot、counter、taskMap 一致。

### SERIAL 串行任务调用链

- `ThreadPool.runSerial(...)`
- `ThreadPool.runSerialAsync(...)`
- `taskSerialCountMap.computeIfAbsent(...)`
- `taskSerialMap.compute(...)`
- `CompletableFuture.supplyAsync(...)` 或前序 future 的 `handleAsync(...)`
- `whenComplete(...)` 清理 `taskSerialMap` / `taskSerialCountMap`

当前实现已避免在 `ConcurrentHashMap.compute()` 中直接执行快速完成任务造成的重入风险，并通过 `serialQueueCapacity / serialQueueHardLimit` 控制同 taskId 串行链容量。

### CALLER_RUNS 溢出执行调用链

- `ThreadQueue.acquireSlot(...)`
- 队列满且策略为 `CALLER_RUNS`
- `ThreadPool.runInCaller(...)`
- `beforeExecute(...)`
- task `run()`
- `afterExecute(...)`

当前实现将 caller-runs 路径纳入 `SINGLE`、`THREAD_TRACE`、`INHERIT_FAST_THREAD_LOCALS` 和 `taskMap` 生命周期处理，整体方向正确。

### WheelTimer 定时任务调用链

- `WheelTimer.setTimeout(...)`
- `WheelTimer.setTimeout(Task)`
- `WheelTimer.newTimeout(...)`
- Netty `HashedWheelTimer`
- `Task.run(Timeout)`
- 外部 `executor.submit(...)`
- `Task.onExecutionFinished(...)`
- `activeTasks` / `holder` / `periodicTasks` 清理

当前 `WheelTimer` 负责时间轮触发，实际任务执行委托给外部 executor。`shutdown()` / `shutdownNow()` 已覆盖 `holder`、`activeTasks` 和 `periodicTasks`。

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

1. 保留当前线程池 review 结论和已完成修复记录。
2. 补充本轮对当前实现的 review 结果。
3. 明确剩余风险、非目标、设计方案、JDK8 兼容约束、验证方案和回滚方案。
4. 不修改业务代码，不调整 workflow，不触发 CI。
5. 为后续用户明确要求实现时提供可执行的修改计划。

# 非目标

- 本次不修改 `ThreadPool.java`、`WheelTimer.java`、`CpuWatchman.java` 或测试代码。
- 本次不引入 `SINGLE namespace / scope` 新 API。
- 本次不调整 `.github/workflows/**`。
- 本次不升级 JDK、Maven 插件或依赖版本。
- 本次不自动创建 PR、不 merge、不发布 release。

# Review 结论

## 1. ThreadPool / ThreadQueue

`ThreadPool` 继承 `ThreadPoolExecutor`，通过自定义 `ThreadQueue` 实现有界队列与生产者背压。当前已支持：

- `BLOCK`：默认策略，保持旧行为，队列满时阻塞提交线程。
- `TIMEOUT_REJECT`：队列满后等待配置超时，仍无 slot 时拒绝。
- `CALLER_RUNS`：队列满且等待超时后在提交线程执行溢出任务。

本轮 review 认为当前方向正确，且 `ThreadQueue` 已覆盖 `offer/poll/take/remove/drainTo/clear` 等主要容量释放路径。后续若继续改动，应重点保护以下不变量：

- `counter` 不应小于 0。
- `availableSlots.availablePermits()` 与逻辑剩余容量应保持一致。
- 外部移除队列元素时必须同步清理 `taskMap`。
- pool shutdown 后等待 slot 的 producer 应快速退出，避免永久阻塞。
- `CALLER_RUNS` 路径执行失败时仍要进入 `afterExecute` 做上下文清理。

## 2. SERIAL 队列

当前 `runSerialAsync()` 已使用 `serialQueueCapacity / serialQueueHardLimit`，不再对单个 taskId 默认放大到 100000。通过 `handleAsync` 续接串行链，前序 Future 异常不会中断同 taskId 后续任务执行。

仍需关注：

- `taskSerialCountMap` 的计数必须在所有异常路径回收。
- `taskSerialMap.compute(...)` 中不能直接执行用户任务，避免 compute 重入和锁内执行。
- 业务使用高基数 taskId 时，仍可能产生大量短生命周期 map entry，需要靠指标观察。

## 3. CompletableFuture async pool patch

当前 patch 默认关闭，避免默认修改 JDK 全局静态字段。该决策较安全，应继续保持：

- `patchCompletableFutureAsyncPool=false` 为默认值。
- patch 仅在显式开启时运行。
- patch 失败只能降级和记录日志，不能影响线程池启动。

## 4. CALLER_RUNS 生命周期

当前 `runInCaller(...)` 显式调用 `beforeExecute/afterExecute`，使 caller-runs 路径和 worker 执行路径更接近。重点已覆盖：

- `RunFlag.SINGLE` acquire/release。
- `RunFlag.THREAD_TRACE` start/end。
- `RunFlag.INHERIT_FAST_THREAD_LOCALS` 上下文复制与恢复。
- `taskMap` 清理。

后续测试应继续保留 caller-runs 与普通 worker 执行路径的等价性验证。

## 5. FastThreadLocal 隔离

当前通过复制 `InternalThreadLocalMap.indexedVariables` 实现继承，并在执行后恢复旧 map，避免污染提交线程或 worker 后续任务。该实现依赖 Netty internal API 和反射字段名，存在版本兼容风险。

建议：

- 不升级 Netty 大版本，除非单独验证 internal 字段兼容。
- 保留针对 `INHERIT_FAST_THREAD_LOCALS` 的回归测试。
- 如果后续升级 Netty，需要优先验证 `InternalThreadLocalMap` 构造器和 `indexedVariables` 字段。

## 6. WheelTimer

`WheelTimer` 使用 Netty `HashedWheelTimer` 触发 timeout，任务执行委托给外部 executor。当前已新增：

- `holder`：按 taskId 跟踪可替换 / SINGLE 的 timeout。
- `activeTasks`：跟踪无 taskId 或普通 timeout。
- `periodicTasks`：跟踪 `scheduleAtFixedRate` / `scheduleWithFixedDelay`。
- `shutdown()`：取消 active 和 periodic。
- `shutdownNow()`：取消 holder、active、periodic，并 stop timer。
- `isTerminated()`：覆盖 shutdownNow 或 shutdown 后集合清空。

本轮 review 认为 shutdown 覆盖面已经明显改善。后续仍需关注 executor 拒绝任务时的完成信号、holder 清理和指标记录是否在所有异常路径执行。

## 7. CpuWatchman

`CpuWatchman` 按 CPU 水位和队列状态动态调整 `corePoolSize`。当前已具备：

- CPU load `<0` 或 NaN 时跳过。
- CPU load 上限 clamp 到 100。
- cooldown 统一判断。
- per-pool jitter 降低多池同时扩缩容。
- `incrSize()` / `decrSize()` 静态入口也进入 cooldown。
- resize 不越过 `minIdleSize` / `maxPoolSize`。

本轮 review 认为主要边界已覆盖。仍需关注：

- `com.sun.management.OperatingSystemMXBean` 是 com.sun API，JDK8 常见但仍属于非标准包。
- 弱引用 map 与外部 pool 生命周期相关，standalone pool shutdown/unregister 已是必要保护。
- jitter 基于 identityHashCode，足以打散同进程 pool，但不是跨 JVM 的全局协调。

## 8. Tasks 全局入口

当前 `Tasks.executor().shutdown()` 和 `shutdownNow()` 为 no-op wrapper 语义，避免关闭共享线程池、timer 和 watchman。该方向符合全局入口设计。

后续应继续区分：

- `Tasks`：全局共享入口，不承担真实资源释放。
- standalone `ThreadPool`：由创建方自己管理生命周期。
- `WheelTimer` standalone 实例：应支持 shutdown/awaitTermination。

# 已完成修复记录

当前计划文档和代码已体现以下完成项：

1. 队列 offer 策略：`BLOCK / TIMEOUT_REJECT / CALLER_RUNS`。
2. SERIAL 容量控制和 compute 重入风险修复。
3. CompletableFuture async pool patch 默认关闭。
4. CALLER_RUNS 生命周期补齐。
5. FastThreadLocal 隔离。
6. 配置解析与测试隔离。
7. Tasks no-op 包装语义。
8. WheelTimer active timeout 跟踪。
9. CpuWatchman resize 边界、cooldown 和 jitter。
10. ThreadQueue `drainTo/clear` slot 释放。
11. standalone `ThreadPool.shutdown/shutdownNow` unregister。
12. WheelTimer shutdown periodic 测试。
13. CpuWatchman resize 测试。
14. SERIAL 异常链测试。
15. `docs/reference/ThreadPool.md` 文档同步。

# 剩余风险和建议

## 1. SINGLE namespace / scope 仍暂缓

当前 `runningSingleTasks` 仍是 JVM 全局 Set，存在跨业务 taskId 冲突风险。建议后续单独做 API 设计：

- 引入 `SingleKey(namespace, taskId)`。
- 老 API 默认 namespace 为 `global`，保持兼容。
- 新增带 namespace 的 `run/runAsync` 重载。
- 日志输出 `taskIdHash`，避免暴露完整业务 key。

## 2. ThreadQueue 容量不变量需要持续测试

虽然 `drainTo/clear/remove` 已补齐，但该类是线程池安全边界，后续任何变更都必须补并发回归测试。重点测试：

- `shutdownNow()` 与 producer blocking 并发。
- `CALLER_RUNS` 溢出执行与 shutdown 并发。
- `remove()` / `clear()` / `drainTo()` 与 worker `poll/take` 并发。
- counter 不为负，slot 不泄露。

## 3. WheelTimer shutdown 与 executor 拒绝路径

`WheelTimer.Task.run()` 中提交外部 executor 后才真正执行用户任务。如果 executor 已 shutdown 或拒绝，当前路径会进入 catch 并完成任务，但后续需要继续保证：

- `activeTasks.remove(this)` 总是执行。
- `holder.remove(id, this)` 总是执行。
- `createdLatch` 必须发布，避免 `get()` 永久等待。
- periodic task 在异常后必须从 `periodicTasks` 移除并唤醒 await。

## 4. Netty internal 反射兼容风险

`ThreadPool` 对 `InternalThreadLocalMap` 的构造器和 `indexedVariables` 字段有反射依赖。JDK8 没问题，但 Netty 升级可能破坏：

- 字段名变更。
- 构造器可见性变更。
- `VARIABLES_TO_REMOVE_INDEX` 语义变更。

建议把 Netty 升级作为单独任务处理，并先跑 `INHERIT_FAST_THREAD_LOCALS` 回归测试。

## 5. Metrics 标签基数

当前指标中包含 pool name、reason、size、raw 等标签/字段。需要避免未来把完整业务 taskId 放入指标标签，否则会引发高基数风险。

# 设计方案

本次仅更新计划文档，不修改代码。若后续进入实现阶段，建议按以下设计推进。

## SINGLE namespace / scope 方案

- 新增内部 key 类型，例如 `SingleKey`，包含 `namespace` 和 `taskId`。
- 保持现有 API 行为，旧 API 仍使用 `global` namespace。
- 新 API 增加 namespace 参数，不修改现有 public 方法签名。
- `runningSingleTasks` 从 `Set<Object>` 迁移为兼容旧 key 的封装 key。
- 日志与指标仅输出 namespace 和 taskId hash，不输出完整业务 key。
- 增加跨 namespace 同 taskId 可并行、同 namespace 同 taskId 互斥的测试。

## ThreadQueue 不变量保护方案

- 为 `ThreadQueue` 增加仅测试可见的容量快照方法，避免测试依赖反射。
- 覆盖并发 shutdown/drain/clear/remove 场景。
- 若发现 counter/slot 不一致，优先在移除路径集中封装 release 操作，不在多个调用点重复写释放逻辑。

## WheelTimer 拒绝路径保护方案

- 将 `completeTask()` / `publish()` / metrics 记录保持在 finally 或 catch 全路径。
- executor 拒绝时明确设置 terminalError，并唤醒 `get()` 等待方。
- periodic 任务拒绝后应 signal terminal latch 并从 `periodicTasks` 移除。

# JDK8 兼容性约束

本仓库必须保持 JDK8 兼容。本次 review 和后续建议均遵守：

- 不使用 Java 9+ API。
- 不引入需要 JDK9+ 的依赖版本。
- 不使用 `Map.of`、`List.of`、`CompletableFuture` Java 9+ 新方法等 API。
- 继续使用 JDK8 可用的 `CompletableFuture`、`ConcurrentHashMap`、`LongAdder`、`Semaphore`。
- `com.sun.management.OperatingSystemMXBean` 在 JDK8 常见环境可用，但属于 com.sun API，后续如支持非 HotSpot/JDK 分发版需单独兼容验证。

# 修改文件列表

本次实际修改：

- `docs/plan/thread-pool-review-plan.md`

本次只更新计划文档，不修改业务代码和测试代码。

后续如果用户要求执行实现，预计可能修改：

- `rxlib/src/main/java/org/rx/core/ThreadPool.java`
- `rxlib/src/test/java/org/rx/core/*ThreadPool*Test.java`
- `docs/reference/ThreadPool.md`

# 风险点

- 兼容性风险：`InternalThreadLocalMap` 反射依赖 Netty internal 结构。
- 性能风险：SERIAL 高基数 taskId 会产生大量短生命周期 map entry。
- 并发风险：ThreadQueue slot/counter 与 executor shutdown 并发仍是高风险区。
- 资源释放风险：WheelTimer executor 拒绝路径和 shutdown 路径必须持续验证。
- 可观测性风险：指标标签不能引入完整 taskId 等高基数信息。
- 测试风险：线程调度类测试可能受机器负载影响，需要避免脆弱 sleep 断言。

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

- 队列策略问题：配置回 `BLOCK`，必要时回滚 `ThreadPoolQueueOfferMode` 相关提交。
- SERIAL 容量问题：临时调高 `serialQueueCapacity`，必要时回滚 SERIAL 容量限制提交。
- CompletableFuture patch 问题：保持 `patchCompletableFutureAsyncPool=false`。
- WheelTimer shutdown 问题：回滚 active timeout tracking 相关提交。
- CpuWatchman resize 问题：调大 `resizeCooldownMillis` 或回滚 resize guard 提交。
- Tasks 入口问题：保持 no-op wrapper，不在生产路径关闭底层共享资源。

# 当前结论

ThreadPool 第一阶段安全边界和可观测性已经基本完成。当前代码已覆盖队列 offer 策略、SERIAL 容量、CompletableFuture patch 默认关闭、CALLER_RUNS 生命周期、FastThreadLocal 隔离、配置解析容错、Tasks no-op 包装语义、WheelTimer active timeout 跟踪、CpuWatchman resize 边界、ThreadQueue slot 释放、standalone ThreadPool 生命周期以及相关测试。

本轮新增 review 结论是：当前实现整体方向正确，短期不建议继续扩大业务代码改动范围。后续最值得单独推进的是 `SINGLE namespace / scope` API 设计，以及继续强化 ThreadQueue 并发不变量和 WheelTimer executor 拒绝路径测试。
