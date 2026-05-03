# rxlib ThreadPool Review 与优化计划

> 生成日期：2026-05-03  
> 最近更新：2026-05-03  
> 合并来源：`thread-pool-review-plan.md`、`thread-pool-review-round3.md`、`thread-pool-review-round4.md`  
> 后续只保留本文件。  
> 最新 CI：ThreadPool Regression Tests 已通过，Run URL: https://github.com/RockyLOMO/rxlib/actions/runs/25273077455

## 1. 背景和目标

本计划聚焦 rxlib 中 thread pool、全局任务入口和定时调度相关实现，目标是沉淀当前 review 结论、已完成修复、CI 结果和剩余待办。

核心类：

- `org.rx.core.ThreadPool`
- `org.rx.core.ThreadPool.ThreadQueue`
- `org.rx.core.ThreadPoolQueueOfferMode`
- `org.rx.core.Tasks`
- `org.rx.core.WheelTimer`
- `org.rx.core.CpuWatchman`
- `org.rx.core.RunFlag`
- `org.rx.core.TimeoutFlag`
- `org.rx.core.RxConfig`

## 2. Review 结论

### 2.1 ThreadPool / ThreadQueue

`ThreadPool` 继承 `ThreadPoolExecutor`，通过自定义 `ThreadQueue` 实现有界队列和生产者背压。当前已支持 `BLOCK / TIMEOUT_REJECT / CALLER_RUNS` 三种入队策略，默认保持 `BLOCK`，兼容旧行为。

`ThreadQueue` 已有核心背压指标：阻塞次数、阻塞耗时、最大阻塞耗时、拒绝次数、caller-runs 次数。

### 2.2 Tasks

`Tasks` 是全局入口包装类，不应真正关闭底层共享线程池、timer 或 watchman。当前 `shutdown()` / `shutdownNow()` 已修正为 no-op wrapper 语义，避免误伤生产共享入口。

### 2.3 WheelTimer

`WheelTimer` 使用 Netty `HashedWheelTimer` 负责时间轮触发，实际任务执行委托给外部 executor。当前已新增 `activeTasks` 跟踪所有 timeout task，`shutdown()` / `shutdownNow()` 可以覆盖无 taskId timeout、带 taskId timeout 和 periodic task 的 termination 判断。

### 2.4 CpuWatchman

`CpuWatchman` 负责按 CPU 水位和队列状态动态调整 core size。当前已处理 CPU load 非法值、cooldown 独立指标，并让 `incrSize()` / `decrSize()` 直调路径也走 cooldown，降低 `RunFlag.PRIORITY` 绕过 cooldown 的风险。

## 3. 已完成修复

### 3.1 队列 offer 策略

- 新增 `ThreadPoolQueueOfferMode`。
- `BLOCK`：默认值，保持无限背压阻塞语义。
- `TIMEOUT_REJECT`：队列满后最多等待 `queueOfferTimeoutMillis`，超时抛 `RejectedExecutionException`。
- `CALLER_RUNS`：队列满且超时后在提交线程执行溢出任务。
- 已补 `TIMEOUT_REJECT` 和 `CALLER_RUNS` 回归测试。

### 3.2 SERIAL 容量和 compute 重入风险

- `runSerialAsync()` 改为使用 `serialQueueCapacity / serialQueueHardLimit`。
- 不再把每个 taskId 的串行链最低放大到 100000。
- 修复快速完成任务在 `ConcurrentHashMap.compute()` 内重入 compute 的挂死风险。
- 超容量快速拒绝和 map/counter 回收已有测试。

### 3.3 CompletableFuture async pool patch

- `CompletableFuture` 默认 async pool patch 改为显式开关。
- 默认 `patchCompletableFutureAsyncPool=false`。
- 默认不再修改 JDK 全局静态字段。
- patch 结果已有日志和指标。

### 3.4 CALLER_RUNS 生命周期

已补 caller-runs 路径生命周期等价性：

- `RunFlag.SINGLE` acquire/release。
- `THREAD_TRACE` 异常清理。
- `INHERIT_FAST_THREAD_LOCALS` 恢复提交线程上下文。
- `taskMap` 清理。

### 3.5 FastThreadLocal 隔离

`INHERIT_FAST_THREAD_LOCALS` 执行时复制父线程 `InternalThreadLocalMap.indexedVariables`，执行后恢复旧 map，避免污染 caller-runs 提交线程或 worker 后续任务。

### 3.6 配置解析与测试隔离

- `ThreadPoolQueueOfferMode.parse()` 支持 `trim()`。
- 支持 `timeout-reject`、`caller runs` 等宽松写法。
- 未知值 fallback default，同时 warn 并记录 `rx.thread_pool.config.invalid.count`。
- 新增 `ThreadPoolConfigSnapshot`，降低测试修改全局配置后的串扰风险。

### 3.7 Tasks no-op 包装语义

- `Tasks.executor().shutdown()`：忽略并记录 warn。
- `Tasks.executor().shutdownNow()`：忽略并返回空列表。
- `isShutdown()` / `isTerminated()` 固定返回 false。
- `awaitTermination()` 固定返回 false。
- `Tasks.shutdown()` / `Tasks.shutdownNow()` 保留 API，但实际为 no-op。
- `nextPool()` 不再因入口 shutdown 状态拒绝任务。

### 3.8 WheelTimer active timeout 跟踪

- 新增 `activeTasks` 集合。
- `newTimeout()` 成功排队后加入 `activeTasks`。
- task 完成、取消、异常后从 `activeTasks` 移除。
- `shutdown()` 会 cancel `activeTasks` 和 `periodicTasks`。
- `shutdownNow()` 会 cancel holder、activeTasks、periodicTasks，并清空集合。
- `isTerminated()` 覆盖 `holder + activeTasks + periodicTasks`。
- 新增 `rx.wheel_timer.active.count`。

### 3.9 CpuWatchman resize 边界

- CPU load `<0` 或 NaN 时跳过本轮 resize。
- CPU load 上限 clamp 到 100。
- 新增 `rx.thread_pool.cpu_load.invalid.count`。
- cooldown 判断抽到统一方法。
- 新增 `rx.thread_pool.resize.cooldown.skipped.count`。
- `incrSize()` / `decrSize()` 静态入口接入 cooldown。

## 4. CI 验证结果

`docs/ci/threadpool-regression-latest.md` 记录的 GitHub Actions 结果：

- Run URL: https://github.com/RockyLOMO/rxlib/actions/runs/25273077455
- Commit: `8b28d01bd0582bbc3ee09a6514fd4010e87d4f80`
- Branch: `master`
- Trigger: `push`
- Focused tests: `success`
- Pattern tests: `success`
- Updated at: `2026-05-03T07:30:45Z`

CI 命令：

```bash
mvn -pl rxlib -am -Dmaven.test.skip=false -DskipTests=false -Dgpg.skip=true -Dmaven.gpg.skip=true -Dtest=ThreadPoolWheelTimerRegressionTest,ThreadPoolQueueOfferModeTest,RxConfigTest,TasksCompatibilityTest,ThreadPoolTest test --batch-mode --no-transfer-progress
mvn -pl rxlib -am -Dmaven.test.skip=false -DskipTests=false -Dgpg.skip=true -Dmaven.gpg.skip=true '-Dtest=*CpuWatchman*,*WheelTimer*,*Tasks*' test --batch-mode --no-transfer-progress
```

结论：本轮 thread pool 聚焦回归测试已通过。

## 5. 剩余待办

### 5.1 ThreadQueue drain/clear slot 释放

优先级最高。`ThreadPoolExecutor.shutdownNow()` 可能通过 `drainTo()` 取走元素，而 `ThreadQueue` 目前主要在 `poll/take/remove` 中释放 slot。如果 `drainTo()` / `clear()` 绕过释放逻辑，`counter` 和 `availableSlots` 可能不一致。

建议：

- 覆盖 `ThreadQueue.drainTo(Collection)`。
- 覆盖 `ThreadQueue.drainTo(Collection, int)`。
- 覆盖 `ThreadQueue.clear()`。
- 按实际移除数量释放 slot。
- `blockUntilSlot()` 循环中检测 pool shutdown，已关闭则快速 reject。

### 5.2 standalone ThreadPool 生命周期

`Tasks` 是 no-op shutdown，但 standalone `new ThreadPool(...)` / `ThreadPool.fixed(...)` 应自己管理生命周期。

建议：

- `ThreadPool.shutdown()` override：先 `CpuWatchman.INSTANCE.unregister(this)`，再调用 super。
- `ThreadPool.shutdownNow()` override：先 unregister，再调用 super。
- 配合 ThreadQueue drain/clear 修复，保证 shutdownNow 后容量计数一致。

### 5.3 WheelTimer shutdown 测试

建议补测试：

- 无 taskId long delay task 在 shutdown 后不会执行。
- 带 taskId period task shutdown 后 holder 清理。
- `awaitTermination()` 能返回 true。
- `scheduleAtFixedRate` / `scheduleWithFixedDelay` shutdown 后 counter 不再增长。

### 5.4 CpuWatchman 测试和 jitter

剩余：

- 多副本 resize jitter 仍未实现。
- 需要补 `CpuWatchmanResizeTest`：invalid CPU load、cooldown skip 指标、PRIORITY 不绕过 cooldown、resize 不越界。

### 5.5 SERIAL 异常链测试

推荐语义：单个 serial task 异常只影响自己的 Future，后续同 taskId 任务继续执行，链尾完成后清理 `taskSerialMap / taskSerialCountMap`。

### 5.6 SINGLE namespace / scope

当前 `runningSingleTasks` 仍是 JVM 全局 Set，存在跨业务 taskId 冲突风险。

建议单独做 API 设计：

- 引入 `SingleKey(namespace, taskId)`。
- 老 API 默认 namespace 为 `global`，保持兼容。
- 新增带 namespace 的 `run/runAsync` 重载。
- 日志只输出 `taskIdHash`，避免暴露完整业务 key。

### 5.7 文档同步

建议同步更新 `docs/reference/ThreadPool.md`：

- `Tasks.shutdown()` / `shutdownNow()` 是 no-op，不承担资源释放职责。
- `WheelTimer.shutdown()` 当前会取消未执行 timeout task。
- 新增 `rx.wheel_timer.active.count`、`rx.thread_pool.cpu_load.invalid.count`、`rx.thread_pool.resize.cooldown.skipped.count` 指标说明。

## 6. 建议后续落地顺序

1. 修 `ThreadQueue drainTo/clear` slot 释放。
2. 修 standalone `ThreadPool.shutdown/shutdownNow` unregister。
3. 补 `WheelTimerShutdownPeriodicTest`。
4. 补 `CpuWatchmanResizeTest`。
5. 补 SERIAL 异常链测试。
6. 单独设计并实现 SINGLE namespace/scope。
7. 同步 `docs/reference/ThreadPool.md`。

## 7. 推荐验收命令

```bash
mvn -pl rxlib -am -Dmaven.test.skip=false -DskipTests=false -Dgpg.skip=true -Dmaven.gpg.skip=true -Dtest=ThreadPoolWheelTimerRegressionTest,ThreadPoolQueueOfferModeTest,RxConfigTest,TasksCompatibilityTest,ThreadPoolTest test --batch-mode --no-transfer-progress
mvn -pl rxlib -am -Dmaven.test.skip=false -DskipTests=false -Dgpg.skip=true -Dmaven.gpg.skip=true '-Dtest=*CpuWatchman*,*WheelTimer*,*Tasks*' test --batch-mode --no-transfer-progress
```

补齐剩余测试后建议新增执行：

```bash
mvn -pl rxlib -am -Dmaven.test.skip=false -DskipTests=false -Dgpg.skip=true -Dmaven.gpg.skip=true -Dtest=WheelTimerShutdownPeriodicTest,CpuWatchmanResizeTest,ThreadPoolQueueShutdownTest,SerialQueueCapacityTest,SingleScopeTest test --batch-mode --no-transfer-progress
```

网络相关集成测试仍建议单独跑，避免 UDP flaky 误判线程池修复。

## 8. 回滚方案

- 队列策略问题：配置回 `BLOCK`，必要时回滚 `ThreadPoolQueueOfferMode` 相关提交。
- SERIAL 容量问题：临时调高 `serialQueueCapacity`，必要时回滚 SERIAL 容量限制提交。
- CompletableFuture patch 问题：保持 `patchCompletableFutureAsyncPool=false`。
- WheelTimer shutdown 问题：回滚 active timeout tracking 相关提交。
- CpuWatchman resize 问题：调大 `resizeCooldownMillis` 或回滚 resize guard 提交。
- Tasks 入口问题：保持 no-op wrapper，不在生产路径关闭底层共享资源。

## 9. 总结

ThreadPool 第一阶段安全边界和可观测性已经完成，GitHub Actions 聚焦回归测试已通过。当前已解决队列 offer 策略、SERIAL 容量、CompletableFuture patch 默认关闭、CALLER_RUNS 生命周期、FastThreadLocal 隔离、配置解析容错、Tasks no-op 包装语义、WheelTimer active timeout 跟踪、CpuWatchman resize 边界。剩余优先处理 ThreadQueue slot 一致性和 standalone ThreadPool 生命周期，然后补齐测试与 SINGLE namespace 设计。
