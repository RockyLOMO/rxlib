# rxlib ThreadPool Review 与优化计划

> 生成日期：2026-05-03  
> 最近更新：2026-05-03  
> 分支：`agent/20260503-thread-pool-review`  
> 当前实现基线：`5dc391eda01459383776d1ef901e71bae2843d07`  
> 本次更新类型：实现后计划文档同步。  
> 明确范围：`SINGLE namespace / scope` 不实现。

# 背景

用户要求执行两个剩余 P1 待办：

1. 加固 `WheelTimer.stopTimer()` 异常路径。
2. 补 running task 场景下 `WheelTimer.shutdown()` / `awaitTermination()` 行为测试。

本次已经完成代码实现，并将结果同步更新到本计划文档。

# 任务类型判断

本次任务归类为 **Review / 修复 / 测试补强需求**。

原因：

- 用户明确要求“执行下修复 并更新计划文档”。
- 修改点属于已有实现的 shutdown 边界加固，不是新功能扩展。
- 本次不引入 `SINGLE namespace / scope`，不扩大 API 范围。
- 本次保持 JDK8 兼容，不修改 main/master 分支。

# 当前上下文

## 已 review / 修改的文件

- `rxlib/src/main/java/org/rx/core/WheelTimer.java`
- `rxlib/src/test/java/org/rx/core/WheelTimerShutdownPeriodicTest.java`
- `docs/plan/thread-pool-review-plan.md`

## 本次代码提交

- commit：`5dc391eda01459383776d1ef901e71bae2843d07`
- message：`fix(core): harden wheel timer shutdown`

## 关键调用链

### WheelTimer.stopTimer()

- `WheelTimer.shutdown()` / `shutdownNow()`
- `stopTimer()`
- `timerStopStarted.compareAndSet(false, true)`
- `timer.stop()`
- `timerStopped = true`
- `isTerminated()` 依赖 `timerStopped`

本次加固后，如果 `timer.stop()` 抛出 `RuntimeException` 或 `Error`，会恢复 `timerStopStarted=false` 并继续抛出异常，避免出现 `timerStopStarted=true` 但 `timerStopped=false` 后无法重试的不可终止状态。

### running task shutdown / awaitTermination

- timeout 触发后提交到外部 executor。
- 用户任务开始运行并阻塞。
- 调用 `WheelTimer.shutdown()`。
- running task 未完成前，`activeTasks` 仍应保留该任务。
- `awaitTermination(short timeout)` 应返回 false。
- 释放 running task 后，任务 finally 中执行 `completeTask()`，`activeTasks` 清空。
- `awaitTermination()` 返回 true。

# 目标

1. 修复 `stopTimer()` 在 `timer.stop()` 抛异常时无法重试的问题。
2. 补 running task 场景下 `shutdown()` / `awaitTermination()` 行为测试。
3. 同步更新计划文档，标记两个 P1 已完成。
4. 保持 JDK8 兼容。
5. 触发或检查 GitHub Actions 验证结果。

# 非目标

- 不实现 `SINGLE namespace / scope`。
- 不调整 `.github/workflows/**`。
- 不升级 JDK、Maven 插件或依赖版本。
- 不自动创建 PR、不 merge、不发布 release。
- 不修改无关模块。

# 设计方案

## stopTimer 异常路径

旧逻辑：

```java
if (timerStopStarted.compareAndSet(false, true)) {
    timer.stop();
    timerStopped = true;
}
```

问题：如果 `timer.stop()` 抛异常，`timerStopStarted` 已经为 true，但 `timerStopped` 仍为 false，后续无法重试，`awaitTermination()` 可能永久 false。

新逻辑：

```java
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
```

设计意图：

- 正常路径仍然只 stop 一次。
- 失败路径恢复 CAS 标记，允许后续重试。
- 不吞异常，避免 shutdown 失败被静默隐藏。
- 不引入新依赖。

## running task 测试

新增 `shutdownShouldAwaitRunningTaskCompletion`：

- 创建 standalone `WheelTimer` 和 `ThreadPool`。
- 立即 schedule 一个阻塞任务。
- 等任务进入运行态。
- 调用 `shutdown()`。
- 验证短超时 `awaitTermination()` 为 false。
- 验证 `activeTasks` 未提前清空。
- 释放任务。
- 验证 `awaitTermination()` 为 true，`holder / activeTasks / periodicTasks` 为空。

# JDK8 兼容性约束

本次实现保持 JDK8 兼容：

- 未使用 Java 9+ API。
- 未引入新依赖。
- 使用 `CountDownLatch`、`AtomicBoolean`、`ScheduledFuture`、`TimeUnit` 等 JDK8 可用 API。
- multi-catch `RuntimeException | Error` 属于 Java 7+ 语法，JDK8 可用。

# 修改文件列表

本次代码提交修改：

- `rxlib/src/main/java/org/rx/core/WheelTimer.java`
- `rxlib/src/test/java/org/rx/core/WheelTimerShutdownPeriodicTest.java`

本次计划文档提交修改：

- `docs/plan/thread-pool-review-plan.md`

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

# 剩余待办

## P0：完成最新分支 CI / Maven 验证

### 问题

本次已有代码提交，需要通过 CI 或 Maven 验证。只有 GitHub Actions run 的 `conclusion=success` 才能认为 CI 通过。

### 验证命令

```bash
mvn -pl rxlib -am -Dmaven.test.skip=false -DskipTests=false -Dgpg.skip=true -Dmaven.gpg.skip=true -Dtest=WheelTimerShutdownPeriodicTest,CpuWatchmanResizeTest,ThreadPoolQueueShutdownTest,SerialQueueCapacityTest test --batch-mode --no-transfer-progress
```

建议合并前继续跑完整线程池相关测试：

```bash
mvn -pl rxlib -am -Dmaven.test.skip=false -DskipTests=false -Dgpg.skip=true -Dmaven.gpg.skip=true -Dtest=ThreadPoolWheelTimerRegressionTest,ThreadPoolQueueOfferModeTest,RxConfigTest,TasksCompatibilityTest,ThreadPoolTest test --batch-mode --no-transfer-progress

mvn -pl rxlib -am -Dmaven.test.skip=false -DskipTests=false -Dgpg.skip=true -Dmaven.gpg.skip=true '-Dtest=*CpuWatchman*,*WheelTimer*,*Tasks*' test --batch-mode --no-transfer-progress
```

## P1：根据 CI 结果修复可能的编译或测试失败

### 关注点

- `WheelTimer.stopTimer()` multi-catch 语法在 JDK8 下应可编译。
- running task 测试涉及异步调度，若在高负载 runner 上偶发超时，需要调整等待方式而不是删除测试。
- 如果 `future.get(1, TimeUnit.SECONDS)` 因 shutdown/cancel 语义变化失败，需要根据实际语义判断测试断言是否应调整。

## P2：降低 WheelTimerShutdownPeriodicTest 对 Netty 私有字段的依赖

当前测试仍通过反射读取 `HashedWheelTimer.workerThread`，可以直接验证底层线程停止，但依赖 Netty internal 字段名。未来 Netty 升级时需要单独验证或改成只基于 public 行为断言。

## P2：Netty internal 反射兼容验证

`ThreadPool` 对 `InternalThreadLocalMap` 有反射依赖；`WheelTimerShutdownPeriodicTest` 对 `HashedWheelTimer.workerThread` 有反射依赖。Netty 升级时必须单独验证。

## P2：Metrics 标签基数约束

指标标签不能引入完整业务 taskId。若需要定位 taskId，应使用 hash 或采样日志。

## P3：CpuWatchman 锁粒度观察

当前不改。后续如出现采样抖动或锁竞争，再考虑复制 snapshot 后在 synchronized 外执行 resize。

# 风险点

- CI 风险：本次代码提交尚需 GitHub Actions / Maven 验证。
- 测试稳定性风险：running task shutdown 测试依赖线程调度，需要关注 CI 负载下是否稳定。
- 兼容性风险：Netty internal 反射测试在 Netty 升级时可能失败。
- 语义风险：`shutdown()` 对 running task 使用 `cancel(false)`，已开始执行的用户任务不会被中断，因此 awaitTermination 必须等待任务自然结束。

# 验证方案

本次代码提交后需要触发 GitHub Actions：

- 优先查找 `agent-ci.yml`。
- 如果没有 `agent-ci.yml`，使用仓库已有 CI workflow。
- 如果 workflow 支持 `workflow_dispatch`，手动触发。
- 如果 workflow 不支持 `workflow_dispatch`，依赖 push 触发并记录状态。
- 只在 `conclusion=success` 时认为 CI 通过。
- 如果 CI 失败，读取失败日志并按失败类型修复。

# 回滚方案

如本次实现导致问题：

```bash
git revert 5dc391eda01459383776d1ef901e71bae2843d07
```

如果只是测试不稳定：

- 优先调整等待条件和断言方式。
- 不直接删除测试。
- 保持 running task shutdown 语义覆盖。

# 当前结论

本次已完成两个指定 P1 项：

1. `WheelTimer.stopTimer()` 异常路径已加固，`timer.stop()` 失败后会恢复 `timerStopStarted=false`，允许后续重试，并继续向调用方抛出异常。
2. 已补 running task 场景下 `shutdown()` / `awaitTermination()` 测试，验证 running task 未完成前不应提前终止，释放后才进入终止状态。

下一步必须执行 CI / Maven 验证，并根据结果继续修复。
