# rxlib ThreadPool Review 与优化计划

> 生成日期：2026-05-03  
> 最近更新：2026-05-03  
> 分支：`agent/20260503-thread-pool-review`  
> 当前 review 基线：`7e5a9524ccb1930ac8f322a988881222ff0792c3`  
> 本次更新类型：整体 Review 结果同步，仅更新计划文档，不修改业务代码。  
> 明确范围：`SINGLE namespace / scope` 不实现。

# 背景

用户要求再次 review 当前实现，将结果更新到 `docs/plan/thread-pool-review-plan.md`，并给出当前待办。

当前分支已经完成多轮线程池、时间轮、shutdown 生命周期和回归测试补强。最近一次实现提交为：

- `395359d7e858857c31f0cc04d64cc3e64586a3fb`：`test(core): cover wheel timer shutdown races`

最近一次计划文档提交为：

- `7e5a9524ccb1930ac8f322a988881222ff0792c3`：`docs(plan): add thread-pool-review plan`

本次 review 聚焦最新实现是否仍有功能缺口，以及当前剩余待办是否已经收敛。

# 任务类型判断

本次任务归类为 **Review / 修复后复查 / 待办梳理需求**。

原因：

- 用户要求“再 review 下，更新计划文档，给出目前待办”。
- 当前未要求继续修改业务代码。
- 本阶段目标是复查已有代码和测试状态，明确剩余风险和待办。
- 按 agent 流程，本次只更新计划文档，不修改业务代码。

# 当前上下文

## 已 review 的文件

- `rxlib/src/main/java/org/rx/core/WheelTimer.java`
- `rxlib/src/test/java/org/rx/core/WheelTimerShutdownPeriodicTest.java`
- `docs/reference/WheelTimerShutdown.md`
- `docs/plan/thread-pool-review-plan.md`

## 当前 CI 状态

最新查询到的 `Agent CI`：

- run id：`25284655775`
- branch：`agent/20260503-thread-pool-review`
- head sha：`7e5a9524ccb1930ac8f322a988881222ff0792c3`
- status：`in_progress`
- conclusion：`null`

因此当前还不能认为 CI 已通过。只有 `status=completed` 且 `conclusion=success` 才能标记为通过。

注意：本次 review 会再产生一个文档-only commit。由于业务代码和测试代码未变，`25284655775` 仍可用于判断最近代码/测试实现是否通过；但如果严格要求最新 head 验证，则需要在本次文档 commit 后重新触发一次 CI。

# 关键调用链

## WheelTimer.stopTimer()

当前核心实现：

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

当前语义：

- 正常路径只允许一个调用者执行底层 `timer.stop()`。
- 其他并发调用者直接返回。
- 如果 `timer.stop()` 抛异常，恢复 `timerStopStarted=false`，允许后续重试。
- 不吞异常，调用方可感知底层 timer stop 失败。

## stopTimer 异常注入测试

`stopTimerFailureShouldResetStateAndAllowRetry` 当前通过以下方式构造异常：

- 直接向底层 Netty `HashedWheelTimer` 提交一个 `TimerTask`。
- 在 Netty timer worker thread 内调用 `WheelTimer.shutdown()`。
- Netty `HashedWheelTimer.stop()` 在 worker thread 内调用时抛 `IllegalStateException`。
- 测试验证：
  - `timerStopStarted=false`
  - `timerStopped=false`
  - 外部线程再次调用 `shutdown()` 可成功
  - `awaitTermination()` 返回 true
  - 内部集合清空

## 并发 shutdown / shutdownNow 测试

`shutdownAndShutdownNowShouldBeSafeWhenRepeatedAndConcurrent` 当前覆盖：

- 8 个线程并发启动。
- 一半线程重复调用 `shutdownNow()`。
- 一半线程重复调用 `shutdown()`。
- 验证无异常。
- 验证最终 `awaitTermination()` 成功。
- 验证 `timerStopStarted=true`、`timerStopped=true`。
- 验证 `holder / activeTasks / periodicTasks` 为空。
- 验证 pending timeout 被取消。

## shutdownNow 文档语义

`docs/reference/WheelTimerShutdown.md` 已明确：

- `WheelTimer` 管理底层 Netty timer 和内部跟踪集合。
- 外部 executor 中已开始运行的用户任务不属于 `WheelTimer` 完整生命周期所有权。
- `shutdown()` 会等待 running task 自然完成后才能 terminated。
- `shutdownNow()` 只尽力 cancel 并清理 timer 内部状态，不保证外部 executor 中忽略中断的 running task 立即停止。
- `isTerminated()` 不等价于外部 executor 中所有 running task 均已停止。

# 目标

1. 复查当前最新实现是否符合预期。
2. 明确当前已完成项。
3. 列出当前仍需处理或观察的待办。
4. 保持 JDK8 兼容约束。
5. 本次只更新计划文档，不修改业务代码。

# 非目标

- 不实现 `SINGLE namespace / scope`。
- 不修改 `WheelTimer.java`、`ThreadPool.java`、`CpuWatchman.java` 或测试代码。
- 不调整 `.github/workflows/**`。
- 不升级 JDK、Maven 插件或依赖版本。
- 不自动创建 PR、不 merge、不发布 release。

# Review 结论

## 1. WheelTimer shutdown 实现主线已收敛

当前 `WheelTimer.shutdown()` / `shutdownNow()` / `stopTimer()` 的核心方向正确：

- `shutdown()` 会拒绝新任务、取消未执行 timeout、取消 holder、取消 periodic，并 stop 底层 timer。
- `shutdown()` 对已开始执行的用户任务使用 `cancel(false)`，不会强制中断，`awaitTermination()` 会等待其自然完成。
- `shutdownNow()` 会清理内部集合并 stop timer，但不承诺外部 executor 中已运行任务立即停止。
- `stopTimer()` 已处理 stop 失败后的可重试状态恢复。
- `isTerminated()` 已将 `timerStopped` 纳入判断。

本轮未发现新的必须修改业务代码的问题。

## 2. stopTimer 异常注入测试覆盖了关键 bug 边界

新增测试能直接验证 stop 失败后的状态恢复，不再只依赖代码 review 推理。

测试方案不引入生产 API，也不替换 final timer 字段，改动范围较小。它的代价是依赖 Netty `HashedWheelTimer.stop()` 在 worker thread 内抛 `IllegalStateException` 的语义。短期可接受，未来 Netty 升级时需要复查。

## 3. 重复 / 并发 shutdown 测试方向正确，但需要 CI 验证稳定性

当前并发测试覆盖了重复 shutdown、shutdownNow 与 shutdown 混合并发、内部集合清理和 pending timeout 取消。测试方向正确。

剩余风险主要是 CI 负载下的调度稳定性。如果 CI 失败，应优先根据失败栈调整等待条件或断言粒度，不应直接删除测试。

## 4. shutdownNow 语义已文档化

新增 `docs/reference/WheelTimerShutdown.md` 已明确 `shutdownNow()` 的资源边界，降低调用方误解 `isTerminated()` 的风险。

后续可以考虑在 `docs/reference/ThreadPool.md` 中增加跳转链接，但不是阻塞项。

## 5. ThreadPool / ThreadQueue / SERIAL / CpuWatchman 当前无新增阻塞项

本轮 focus 是 `WheelTimer` shutdown 相关实现。结合前序 review 结论：

- `ThreadQueue` slot/counter/taskMap 不变量已有测试覆盖。
- SERIAL 容量、异常链和清理路径已有测试覆盖。
- CpuWatchman resize 边界、cooldown、jitter 已有测试覆盖。

当前不建议继续扩大代码修改范围，除非 CI 暴露实际失败。

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
25. `stopTimer()` 异常注入测试，覆盖 stop 失败后可重试。
26. 重复 / 并发 `shutdown()` 与 `shutdownNow()` 测试。
27. `WheelTimer.shutdownNow()` 与外部 executor running task 语义参考文档。

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

# 当前待办明细

## P0：等待并确认 `Agent CI` 结果

### 当前状态

`Agent CI` run `25284655775` 当前仍是：

- status：`in_progress`
- conclusion：`null`

不能认为 CI 已通过。

### 完成标准

- workflow `status=completed`。
- workflow `conclusion=success`。

### 备注

本次 review 只新增计划文档 commit。若要求 CI 严格覆盖最新 head，需要在本次文档 commit 后重新触发一次 CI；若只验证最新代码/测试实现，run `25284655775` 已覆盖对应代码树。

## P1：根据 CI 结果修复可能的编译或测试失败

### 重点关注

- `stopTimerFailureShouldResetStateAndAllowRetry` 是否能稳定触发 Netty worker thread 内 stop 异常。
- `shutdownAndShutdownNowShouldBeSafeWhenRepeatedAndConcurrent` 是否在 CI 高负载下稳定。
- `standaloneShutdownShouldStopUnderlyingTimerAndRejectNewTasks` 对 `workerThread` 反射和线程停止断言是否稳定。
- 如果失败来自超时或调度抖动，应调整等待条件；不要删除测试覆盖。

### 完成标准

- 如果 CI 失败，读取失败日志。
- 按失败类型做最小修复。
- 再次触发 CI。
- 直到最新 CI `conclusion=success`。

## P2：降低 `WheelTimerShutdownPeriodicTest` 对 Netty internal 的依赖

### 问题

当前测试仍有 Netty internal 依赖：

- 反射读取 `HashedWheelTimer.workerThread`。
- 依赖 worker thread 内调用 `HashedWheelTimer.stop()` 抛 `IllegalStateException`。

### 建议

短期保留，因为这是最小化验证当前 bug 边界的方式。未来 Netty 升级时，建议单独改造成可注入 timer 或只基于 public 行为断言。

## P2：文档索引补链

### 问题

已新增 `docs/reference/WheelTimerShutdown.md`，但 `docs/reference/ThreadPool.md` 当前未必有指向该文档的显式链接。

### 建议

后续可在 `docs/reference/ThreadPool.md` 的时间轮章节加一句：更多 shutdown 语义见 `docs/reference/WheelTimerShutdown.md`。

该项非阻塞，不影响代码行为。

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
- 新增测试使用匿名类写法，避免引入更高版本语言特性。

# 修改文件列表

本次实际修改：

- `docs/plan/thread-pool-review-plan.md`

本次不修改业务代码和测试代码。

如果后续执行待办，预计可能修改：

- `rxlib/src/test/java/org/rx/core/WheelTimerShutdownPeriodicTest.java`
- `docs/reference/ThreadPool.md`
- `docs/reference/WheelTimerShutdown.md`

# 风险点

- CI 风险：最新 CI 仍未完成，不能确认本分支已经验证通过。
- 测试稳定性风险：并发 shutdown 测试和 worker thread 停止测试依赖线程调度。
- Netty 兼容性风险：测试依赖 `HashedWheelTimer.workerThread` 和 worker thread 内 stop 异常语义。
- 语义风险：`shutdownNow()` 与外部 executor running task 的边界需要调用方理解，已通过新文档降低风险。
- 可观测性风险：未来如果把完整 taskId 放入 metrics 标签，会引发高基数。

# 验证方案

当前必须等待或重新触发 `Agent CI`：

- workflow：`Agent CI`
- branch：`agent/20260503-thread-pool-review`
- 通过标准：`status=completed` 且 `conclusion=success`

建议 Maven 验证命令：

```bash
mvn -pl rxlib -am -Dmaven.test.skip=false -DskipTests=false -Dgpg.skip=true -Dmaven.gpg.skip=true -Dtest=WheelTimerShutdownPeriodicTest test --batch-mode --no-transfer-progress

mvn -pl rxlib -am -Dmaven.test.skip=false -DskipTests=false -Dgpg.skip=true -Dmaven.gpg.skip=true -Dtest=WheelTimerShutdownPeriodicTest,CpuWatchmanResizeTest,ThreadPoolQueueShutdownTest,SerialQueueCapacityTest test --batch-mode --no-transfer-progress

mvn -pl rxlib -am -Dmaven.test.skip=false -DskipTests=false -Dgpg.skip=true -Dmaven.gpg.skip=true '-Dtest=*CpuWatchman*,*WheelTimer*,*Tasks*' test --batch-mode --no-transfer-progress
```

# 回滚方案

本次只更新计划文档，回滚方式：

```bash
git revert <本次计划文档 commit>
```

如果后续 CI 证明最近测试 / 文档补强存在问题，可回滚代码 / 文档提交：

```bash
git revert 395359d7e858857c31f0cc04d64cc3e64586a3fb
```

如果只是测试不稳定：

- 优先调整等待条件。
- 不直接删除测试。
- 保持 stopTimer 失败重试、并发 shutdown、shutdownNow 语义覆盖。

# 当前结论

当前实现功能层面已经基本收敛。`WheelTimer.stopTimer()` 异常路径、running task shutdown、重复/并发 shutdown，以及 `shutdownNow()` 与外部 executor running task 的语义均已有实现、测试或文档覆盖。

目前最高优先级待办是等待 `Agent CI` 完成并确认 `conclusion=success`。如果 CI 失败，下一步应基于日志做最小修复。功能层面暂不建议继续扩大业务代码改动范围。
