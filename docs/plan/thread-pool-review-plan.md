# rxlib ThreadPool Review 与优化计划

> 生成日期：2026-05-03  
> 最近更新：2026-05-03  
> 分支：`agent/20260503-thread-pool-review`  
> 当前 review 基线：`f54b20eb07f6739f4d567d0b60e1e3ad50359a20`  
> 本次更新类型：整体 Review 结果同步，仅更新计划文档，不修改业务代码。  
> 明确范围：`SINGLE namespace / scope` 不实现。

# 背景

用户要求基于当前 `agent/20260503-thread-pool-review` 分支再次整体 review 线程池相关实现，并将 review 结果更新到 `docs/plan/thread-pool-review-plan.md`，同时指出剩余待办明细。

当前分支最近已完成两个修复项：

1. `WheelTimer.stopTimer()` 异常路径加固。
2. running task 场景下 `WheelTimer.shutdown()` / `awaitTermination()` 测试补强。

本次 review 聚焦这些修复是否合理、线程池整体实现是否还有明显风险、以及后续待办的优先级。

# 任务类型判断

本次任务归类为 **Review / 修复后复查 / 待办梳理需求**。

原因：

- 用户要求“整体 review 下实现，结果更新到计划文档，指出待办明细”。
- 当前不要求继续修改业务代码。
- 本阶段目标是复查现有实现、识别剩余风险和验证缺口。
- 按 agent 流程，本次只更新计划文档，不修改业务代码。

# 当前上下文

## 已 review 的文件

- `rxlib/src/main/java/org/rx/core/WheelTimer.java`
- `rxlib/src/test/java/org/rx/core/WheelTimerShutdownPeriodicTest.java`
- `rxlib/src/main/java/org/rx/core/ThreadPool.java`
- `rxlib/src/main/java/org/rx/core/CpuWatchman.java`
- `docs/plan/thread-pool-review-plan.md`

## 当前关键提交

- `5dc391eda01459383776d1ef901e71bae2843d07`：`fix(core): harden wheel timer shutdown`
- `f54b20eb07f6739f4d567d0b60e1e3ad50359a20`：`docs(plan): add thread-pool-review plan`

## 当前 CI 状态

最新查询到的 `Agent CI`：

- run id：`25282967592`
- branch：`agent/20260503-thread-pool-review`
- head sha：`f54b20eb07f6739f4d567d0b60e1e3ad50359a20`
- status：`in_progress`
- conclusion：`null`

因此当前还不能认为 CI 已通过。只有 `conclusion=success` 才能标记为通过。

# 关键调用链

## WheelTimer.shutdown()

- `shutdown = true`
- 遍历 `holder.values()` 并 `future.cancel(false)`
- 遍历 `activeTasks` 并 `task.cancel(false)`
- 遍历 `periodicTasks` 并 `periodicTask.cancel(false)`
- 调用 `stopTimer()`
- `recordDiagnosticMetrics(true)`
- `awaitTermination(...)` 轮询 `isTerminated()`

当前 `isTerminated()` 要求：

- `timerStopped == true`
- 并且 `shutdownNow == true` 或 `shutdown && holder/activeTasks/periodicTasks 全部为空`

## WheelTimer.stopTimer()

当前实现：

```java
private void stopTimer() {
    if (!timerStopStarted.compareAndSet(false, true)) {
        return;
    }
    try {
        timer.stop();
        timerStopped = true;
    } catch (RuntimeException | Error e) {
        timerStopStarted.set(false);
        throw e;
    }
}
```

该实现解决了 `timer.stop()` 抛异常后 `timerStopStarted=true`、`timerStopped=false` 且无法重试的问题。

## running task shutdown / awaitTermination

当前测试覆盖：

- 任务已触发并进入外部 executor 运行。
- 调用 `shutdown()`。
- running task 未释放前，短超时 `awaitTermination()` 返回 false。
- 任务释放后，`awaitTermination()` 返回 true。
- `holder / activeTasks / periodicTasks` 清空。

# 目标

1. 复查当前实现是否符合预期。
2. 明确已完成项和仍需关注的风险。
3. 明确剩余待办明细和优先级。
4. 保持 JDK8 兼容约束。
5. 本次只更新计划文档，不修改业务代码。

# 非目标

- 不实现 `SINGLE namespace / scope`。
- 不修改 `WheelTimer.java`、`ThreadPool.java`、`CpuWatchman.java` 或测试代码。
- 不调整 `.github/workflows/**`。
- 不升级 JDK、Maven 插件或依赖版本。
- 不自动创建 PR、不 merge、不发布 release。

# Review 结论

## 1. WheelTimer.stopTimer() 异常路径方向正确

当前实现通过 CAS 保证正常路径只 stop 一次，并在 `timer.stop()` 抛出 `RuntimeException` 或 `Error` 时恢复 `timerStopStarted=false`。

这能避免旧风险：

- stop 开始标记已经置位。
- stop 实际失败。
- `timerStopped` 仍为 false。
- 后续 shutdown / shutdownNow 无法重试。
- `awaitTermination()` 永久 false。

当前实现选择继续抛出异常，而不是吞掉异常。这是合理的，因为底层 timer stop 失败属于资源释放失败，调用方应感知。

剩余缺口：目前没有直接构造 `timer.stop()` 抛异常的回归测试，因此异常重试路径属于代码 review 证明，尚不是测试证明。

## 2. running task shutdown 语义符合设计

`shutdown()` 使用 `cancel(false)`，因此已开始执行的用户任务不会被强制中断。

当前 `Task.cancel(false)` 在 future 已运行且取消失败时，不会提前 `completeTask()`，所以 `activeTasks` 会保留 running task，直到用户任务自然完成后由 `onExecutionFinished()` 进入 `completeTask()`。

新增测试 `shutdownShouldAwaitRunningTaskCompletion` 覆盖了这一语义：

- running task 未释放前，`awaitTermination(200ms)` 返回 false。
- `activeTasks` 不提前清空。
- running task 释放后，`awaitTermination(3s)` 返回 true。
- `holder / activeTasks / periodicTasks` 最终为空。

该测试方向正确，能防止未来把 running task 误清理导致 `awaitTermination()` 过早返回 true。

## 3. WheelTimer.shutdownNow() 语义需要继续文档化

当前 `shutdownNow()` 会：

- cancel holder / active / periodic。
- clear `holder / activeTasks / periodicTasks`。
- stop timer。
- `isTerminated()` 在 `shutdownNow == true && timerStopped == true` 时可返回 true。

这意味着 `WheelTimer` 的 terminated 语义主要表示 timer 自身和内部跟踪集合已终止，不代表外部 executor 中已经开始执行且忽略中断的用户任务一定停止。

该行为可接受，但需要在文档中保持清晰说明：`WheelTimer` 不拥有外部 executor 的完整生命周期，`shutdownNow()` 不能保证已运行用户任务被强制停止。

## 4. ThreadPool / ThreadQueue 当前核心风险已下降

当前 `ThreadQueue` 已具备：

- 有界队列 slot 管理。
- `BLOCK / TIMEOUT_REJECT / CALLER_RUNS` 入队策略。
- `remove / poll / take / drainTo / clear` 路径释放 slot。
- `CapacitySnapshot` 测试诊断结构。
- shutdown race / caller-runs race / 容量不变量测试。

本轮未发现新的必须修改项。后续关键是保持这些测试为必跑回归，不再无关重构 `ThreadQueue`。

## 5. SERIAL 队列当前不建议继续扩大修改范围

当前 SERIAL 链已具备容量限制、异常链继续执行、计数回收和回归测试。剩余风险主要来自业务使用方式：

- 高基数 taskId 会带来大量短生命周期 map entry。
- 单 taskId 长链可能触发 `serialQueueCapacity` 拒绝。
- 指标和日志不应引入完整业务 taskId。

## 6. CpuWatchman 本轮无新增阻塞问题

`CpuWatchman` 当前已覆盖：

- CPU load NaN / 负数跳过。
- CPU load clamp 到 100。
- resize cooldown。
- per-pool jitter。
- min / max pool size 边界。
- shutdown / unregister 生命周期。

剩余只是观察项：`com.sun.management.OperatingSystemMXBean` 兼容性和 synchronized holder 锁粒度。

# 已完成项

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
23. `WheelTimer.stopTimer()` 异常路径加固，stop 失败后允许重试。
24. running task 场景下 `shutdown()` / `awaitTermination()` 测试补强。

# 不再作为待办

## SINGLE namespace / scope

用户已明确：`SINGLE namespace / scope` 不实现。

因此以下内容不再作为当前任务待办：

- 不新增 `SingleKey(namespace, taskId)`。
- 不新增带 namespace 的 `run/runAsync` 重载。
- 不修改 `runningSingleTasks` key 结构。
- 不增加跨 namespace 同 taskId 并行测试。
- 不围绕 namespace/scope 修改文档或指标。

保留现状：`runningSingleTasks` 继续作为 JVM 全局互斥集合使用。调用方如担心跨业务 taskId 冲突，应自行在 taskId 中加入业务前缀。

# 待办明细

## P0：等待并确认最新 Agent CI 结论

### 当前状态

`Agent CI` run `25282967592` 当前仍是：

- status：`in_progress`
- conclusion：`null`

不能标记为通过。

### 完成标准

- 最新 head 对应的 `Agent CI` 运行结束。
- `conclusion=success`。

### 如果失败

必须读取失败日志，并分类处理：

1. 编译失败。
2. 单元测试失败。
3. Checkstyle / formatting 失败。
4. 依赖下载失败。
5. JDK 版本不兼容。
6. 环境问题。
7. 测试不稳定。
8. GitHub Actions 配置问题。

## P1：根据 CI 结果修复可能的编译或测试失败

### 重点关注

- `WheelTimer.stopTimer()` multi-catch 在 JDK8 下应可编译。
- `shutdownShouldAwaitRunningTaskCompletion` 依赖线程调度，如果 CI runner 负载高，可能需要调整等待条件。
- `future.get(1, TimeUnit.SECONDS)` 当前预期是 running task 自然完成后正常返回；若 CI 失败，需要根据实际失败栈判断是否是取消语义变化、竞态，还是测试等待不足。

### 完成标准

- 如果 CI 失败，补修复 commit。
- 再次触发 CI。
- 直到最新 CI `conclusion=success`。

## P1：补充 stopTimer 异常注入测试

### 问题

当前 `stopTimer()` 异常恢复逻辑已经实现，但尚没有测试能直接让 `timer.stop()` 抛异常，从而验证：

- `timerStopStarted` 会恢复为 false。
- 后续 shutdown / shutdownNow 可重试。
- shutdown 失败不会被静默吞掉。

### 建议

后续可选方案：

- 引入最小测试钩子或包可见构造方式，用可控 fake timer 触发 stop 异常。
- 或通过反射替换 `timer` 字段进行异常注入；但这会进一步增加 Netty/internal/反射依赖，不是首选。
- 如果不引入测试钩子，则至少保留当前 review 结论和代码注释说明。

### 完成标准

新增回归测试覆盖 stop 失败后重试行为，且不引入生产 API 破坏。

## P1：补充重复 / 并发 shutdown 与 shutdownNow 测试

### 问题

当前已有 standalone shutdown 和 running task shutdown 测试，但对多线程同时调用 `shutdown()` / `shutdownNow()` 的覆盖仍不够直接。

### 建议

增加测试：

- 多线程同时调用 `shutdown()`，不应抛异常。
- `shutdown()` 后重复调用 `shutdown()`，应保持幂等。
- `shutdown()` 与 `shutdownNow()` 并发时，最终 `awaitTermination()` 可返回 true。
- `timer.stop()` 正常路径只应执行一次。

### 完成标准

新增并发 shutdown 测试，并在 JDK8 CI 上稳定通过。

## P1：明确 WheelTimer.shutdownNow() 与外部 executor running task 的语义

### 问题

`WheelTimer` 不拥有外部 executor 的完整生命周期。`shutdownNow()` 会清理内部集合并停止底层 timer，但已提交到外部 executor 且忽略中断的用户任务可能仍在执行。

### 建议

在 `docs/reference/ThreadPool.md` 中补充：

- `WheelTimer.shutdown()` 等待 running task 自然完成后才 terminated。
- `WheelTimer.shutdownNow()` 只尽力 cancel，并立即清理 timer 内部状态。
- `WheelTimer.isTerminated()` 不等价于外部 executor 内所有用户任务必然停止。

### 完成标准

参考文档明确该语义，避免调用方误解。

## P2：降低 WheelTimerShutdownPeriodicTest 对 Netty 私有字段的依赖

### 问题

当前测试通过反射读取 `HashedWheelTimer.workerThread` 来验证底层 timer thread 停止。该断言直接有效，但依赖 Netty internal 字段名。

### 建议

- 短期保留，因为当前仓库已有 Netty internal 反射依赖。
- 中期可以优先用 public 行为验证：`awaitTermination()`、`pendingTimeouts()`、拒绝新任务、集合清空。
- 如果保留反射，失败信息应明确提示 Netty internal 字段变更。

## P2：Netty internal 反射兼容验证

### 问题

当前有两类 Netty internal 依赖：

- `ThreadPool` 反射 `InternalThreadLocalMap` 构造器和 `indexedVariables`。
- `WheelTimerShutdownPeriodicTest` 反射 `HashedWheelTimer.workerThread`。

### 建议

未来升级 Netty 时单独处理，不和线程池行为修改混在一个 commit。

## P2：Metrics 标签基数约束

### 问题

线程池、SERIAL、WheelTimer 指标不应把完整业务 taskId 放入标签，否则会产生高基数问题。

### 建议

- 指标标签继续保持 pool name、reason、executor class 等低基数字段。
- 如需定位 taskId，使用 hash 或采样日志。
- 文档中保留 cardinality 注意事项。

## P3：CpuWatchman 锁粒度观察

### 问题

`CpuWatchman` 使用 synchronized map 维护 holder，并在采样时遍历。当前逻辑较轻，不构成立即问题。

### 建议

短期不改。后续如发现采样抖动或锁竞争，再考虑：

- synchronized 内复制 snapshot。
- synchronized 外执行 resize。
- 增加采样耗时指标。

# JDK8 兼容性约束

本次 review 和后续建议均遵守 JDK8：

- 不使用 Java 9+ API。
- 不引入需要 JDK9+ 的依赖版本。
- 不使用 `Map.of`、`List.of`、Java 9+ `CompletableFuture` API。
- 继续使用 JDK8 可用的 `AtomicBoolean`、`CountDownLatch`、`ConcurrentHashMap`、`Semaphore`、`LongAdder`。
- `com.sun.management.OperatingSystemMXBean` 在常见 JDK8 HotSpot 环境可用，但属于 com.sun API，未来如支持其他 JDK 分发版需单独验证。

# 修改文件列表

本次实际修改：

- `docs/plan/thread-pool-review-plan.md`

本次不修改业务代码和测试代码。

如果后续执行待办，预计可能修改：

- `rxlib/src/main/java/org/rx/core/WheelTimer.java`
- `rxlib/src/test/java/org/rx/core/WheelTimerShutdownPeriodicTest.java`
- `docs/reference/ThreadPool.md`

# 风险点

- CI 风险：最新 CI 仍未完成，不能认为当前分支已验证通过。
- 测试稳定性风险：running task shutdown 测试依赖线程调度，需要关注 CI 负载下是否稳定。
- 语义风险：`shutdownNow()` 的 terminated 语义可能被误解为外部 executor 用户任务全部停止。
- 兼容性风险：Netty internal 反射在 Netty 升级时可能失效。
- 可观测性风险：未来如果把完整 taskId 放入 metrics 标签，会引发高基数。

# 验证方案

当前必须等待或重新触发 `Agent CI`，并确认最新 head 的 run：

- workflow：`Agent CI`
- branch：`agent/20260503-thread-pool-review`
- 通过标准：`status=completed` 且 `conclusion=success`

建议 Maven 验证命令：

```bash
mvn -pl rxlib -am -Dmaven.test.skip=false -DskipTests=false -Dgpg.skip=true -Dmaven.gpg.skip=true -Dtest=WheelTimerShutdownPeriodicTest,CpuWatchmanResizeTest,ThreadPoolQueueShutdownTest,SerialQueueCapacityTest test --batch-mode --no-transfer-progress

mvn -pl rxlib -am -Dmaven.test.skip=false -DskipTests=false -Dgpg.skip=true -Dmaven.gpg.skip=true -Dtest=ThreadPoolWheelTimerRegressionTest,ThreadPoolQueueOfferModeTest,RxConfigTest,TasksCompatibilityTest,ThreadPoolTest test --batch-mode --no-transfer-progress

mvn -pl rxlib -am -Dmaven.test.skip=false -DskipTests=false -Dgpg.skip=true -Dmaven.gpg.skip=true '-Dtest=*CpuWatchman*,*WheelTimer*,*Tasks*' test --batch-mode --no-transfer-progress
```

# 回滚方案

本次只更新计划文档，回滚方式：

```bash
git revert <本次计划文档 commit>
```

如果后续发现 `WheelTimer.stopTimer()` 或 running task 测试引入问题，可回滚代码提交：

```bash
git revert 5dc391eda01459383776d1ef901e71bae2843d07
```

如果只是测试不稳定：

- 优先调整等待条件。
- 不直接删除测试。
- 保持 running task shutdown 语义覆盖。

# 当前结论

当前实现整体方向正确：`WheelTimer.stopTimer()` 已避免 stop 失败后不可重试，running task 下的 `shutdown()` / `awaitTermination()` 语义也有回归测试保护。`ThreadPool / ThreadQueue / SERIAL / CpuWatchman` 本轮未发现新的必须立即修改项。

当前最重要的待办是等待最新 CI 完成并确认 `conclusion=success`。如果 CI 失败，需要先按日志修复。功能层面的后续增强主要是：为 `timer.stop()` 异常路径补直接注入测试、增加重复/并发 shutdown 测试、补充 `shutdownNow()` 与外部 executor running task 的文档语义。
