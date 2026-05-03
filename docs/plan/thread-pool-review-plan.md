# rxlib ThreadPool Review 与优化计划

> 生成日期：2026-05-03  
> 最近更新：2026-05-03  
> 分支：`agent/20260503-thread-pool-review`  
> 当前实现基线：`395359d7e858857c31f0cc04d64cc3e64586a3fb`  
> 本次更新类型：执行 P1 待办后的计划文档同步。  
> 明确范围：`SINGLE namespace / scope` 不实现。

# 背景

用户要求继续执行三个 P1 待办，并更新计划文档：

1. 补充 `stopTimer()` 异常注入测试。
2. 补充重复 / 并发 `shutdown()` 与 `shutdownNow()` 测试。
3. 明确 `WheelTimer.shutdownNow()` 与外部 executor running task 的语义。

本次已完成实现和参考文档补充，并将结果同步到本计划文档。

# 任务类型判断

本次任务归类为 **Review / 修复后测试补强 / 文档补强需求**。

原因：

- 用户明确要求“执行代码修复 并更新计划文档”。
- 修改点是已有 shutdown 实现的测试和文档补强，不是新增业务功能。
- 本次不实现 `SINGLE namespace / scope`。
- 本次保持 JDK8 兼容，不修改 main/master 分支。

# 当前上下文

## 已 review / 修改的文件

本次代码与文档提交修改：

- `rxlib/src/test/java/org/rx/core/WheelTimerShutdownPeriodicTest.java`
- `docs/reference/WheelTimerShutdown.md`

本次计划文档提交修改：

- `docs/plan/thread-pool-review-plan.md`

## 本次代码 / 文档提交

- commit：`395359d7e858857c31f0cc04d64cc3e64586a3fb`
- message：`test(core): cover wheel timer shutdown races`

## 关键调用链

### stopTimer 异常注入

- Netty `HashedWheelTimer` worker thread 执行测试 `TimerTask`。
- 测试任务在 timer worker thread 内调用 `WheelTimer.shutdown()`。
- `WheelTimer.shutdown()` 调用 `stopTimer()`。
- 底层 `HashedWheelTimer.stop()` 在 worker thread 内调用时抛出 `IllegalStateException`。
- `stopTimer()` 捕获异常，恢复 `timerStopStarted=false` 并重新抛出。
- 测试在外部线程再次调用 `shutdown()`，验证可重试并最终 terminated。

### 并发 shutdown / shutdownNow

- 创建 pending scheduled task。
- 多个线程同时反复调用 `shutdown()` 或 `shutdownNow()`。
- 通过 `timerStopStarted` 和 `timerStopped` 验证 shutdown 结束状态。
- 验证 `holder / activeTasks / periodicTasks` 清空。
- 验证 pending timeout 被取消。

### shutdownNow 文档语义

新增 `docs/reference/WheelTimerShutdown.md`，明确：

- `WheelTimer` 拥有底层 Netty timer 和内部跟踪集合。
- 外部 executor 中已运行的用户任务不属于 `WheelTimer` 完整生命周期所有权。
- `shutdown()` 不会中断已运行任务，awaitTermination 会等待 running task 自然完成。
- `shutdownNow()` 只尽力 cancel 并清理 timer 内部状态，不保证外部 executor 中忽略中断的 running task 立即停止。

# 目标

1. 用回归测试直接覆盖 `stopTimer()` stop 失败后允许重试。
2. 用回归测试覆盖重复 / 并发 `shutdown()` 与 `shutdownNow()`。
3. 用参考文档明确 `shutdownNow()` 与外部 executor running task 的边界。
4. 同步计划文档，标记三个 P1 已完成。
5. 触发 GitHub Actions 验证。

# 非目标

- 不实现 `SINGLE namespace / scope`。
- 不修改 `.github/workflows/**`。
- 不升级 JDK、Maven 插件或依赖版本。
- 不修改无关模块。
- 不自动创建 PR、不 merge、不发布 release。

# 设计方案

## 1. stopTimer 异常注入测试

新增测试：`stopTimerFailureShouldResetStateAndAllowRetry`。

设计点：

- 不引入生产 API。
- 不替换 final timer 字段。
- 利用 Netty 自身语义：在 `HashedWheelTimer` worker thread 内调用 `stop()` 会抛 `IllegalStateException`。
- 验证异常后：
  - `timerStopStarted=false`
  - `timerStopped=false`
  - 外部线程再次 `shutdown()` 可成功
  - `awaitTermination()` 返回 true
  - 内部集合最终清空

## 2. 重复 / 并发 shutdown 测试

新增测试：`shutdownAndShutdownNowShouldBeSafeWhenRepeatedAndConcurrent`。

设计点：

- 8 个线程并发启动。
- 一半线程重复调用 `shutdownNow()`。
- 一半线程重复调用 `shutdown()`。
- 验证没有异常。
- 验证最终 terminated。
- 验证 `timerStopped=true`。
- 验证 `holder / activeTasks / periodicTasks` 为空。
- 验证 pending timeout 被取消。

## 3. shutdownNow 参考文档

新增文档：`docs/reference/WheelTimerShutdown.md`。

设计点：

- 避免大范围重写已有 `ThreadPool.md`。
- 单独说明 `WheelTimer` 生命周期边界。
- 明确 `shutdown()` 与 `shutdownNow()` 差异。
- 明确 `isTerminated()` 不等价于外部 executor running task 全部停止。
- 保持 JDK8 和现有 API 兼容。

# JDK8 兼容性约束

本次实现保持 JDK8 兼容：

- 未使用 Java 9+ API。
- 未引入新依赖。
- 测试使用 `CountDownLatch`、`AtomicReference`、`AtomicInteger`、`TimeUnit`、`ScheduledFuture` 等 JDK8 API。
- 测试中的匿名类写法兼容 JDK8。
- 新增 Markdown 文档不影响编译。

# 修改文件列表

本次代码 / 文档提交：

- `rxlib/src/test/java/org/rx/core/WheelTimerShutdownPeriodicTest.java`
- `docs/reference/WheelTimerShutdown.md`

本次计划文档提交：

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

# 剩余待办明细

## P0：完成最新分支 CI / Maven 验证

### 当前状态

本次已有新提交 `395359d7e858857c31f0cc04d64cc3e64586a3fb`，需要触发并确认最新 head 的 CI 结果。

### 完成标准

- 最新 `Agent CI` 对应当前 head。
- workflow `status=completed`。
- workflow `conclusion=success`。

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

## P1：根据 CI 结果修复可能的测试失败

### 重点关注

- `stopTimerFailureShouldResetStateAndAllowRetry` 依赖 Netty `HashedWheelTimer.stop()` 在 worker thread 内抛 `IllegalStateException` 的语义。
- `shutdownAndShutdownNowShouldBeSafeWhenRepeatedAndConcurrent` 是并发测试，如果 CI 负载较高，可能需要调整等待超时。
- 如果 `future.get()` 断言与 shutdownNow 取消竞态相关，需要根据实际失败栈判断是否应该放宽为 cancelled/done 语义，而不是删除测试。

### 完成标准

- 如果 CI 失败，补修复 commit。
- 再次触发 CI。
- 直到最新 CI `conclusion=success`。

## P2：降低 WheelTimerShutdownPeriodicTest 对 Netty internal 的依赖

### 问题

当前测试仍包含两类 Netty internal 语义依赖：

- 反射读取 `HashedWheelTimer.workerThread`。
- 依赖 worker thread 内调用 `HashedWheelTimer.stop()` 抛 `IllegalStateException`。

### 建议

短期保留，因为这是验证当前 bug 边界的最小改动。未来 Netty 升级时，需要单独验证或改造成可注入 timer 的测试结构。

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

# 风险点

- CI 风险：本次新增测试尚未由最新 CI 验证。
- 测试稳定性风险：新增并发 shutdown 测试依赖线程调度。
- Netty 兼容性风险：异常注入测试依赖 `HashedWheelTimer.stop()` 的 worker thread 保护语义。
- 语义风险：`shutdownNow()` 的 terminated 语义可能被误解为外部 executor 用户任务全部停止，已通过新文档降低该风险。

# 验证方案

代码提交后需要触发 GitHub Actions：

- 优先使用 `agent-ci.yml`。
- 如果 workflow 支持 `workflow_dispatch`，手动触发。
- 查询 workflow run 时必须按 `agent/20260503-thread-pool-review` 分支过滤。
- 只有 `conclusion=success` 才算通过。

建议 Maven 验证命令：

```bash
mvn -pl rxlib -am -Dmaven.test.skip=false -DskipTests=false -Dgpg.skip=true -Dmaven.gpg.skip=true -Dtest=WheelTimerShutdownPeriodicTest test --batch-mode --no-transfer-progress

mvn -pl rxlib -am -Dmaven.test.skip=false -DskipTests=false -Dgpg.skip=true -Dmaven.gpg.skip=true -Dtest=WheelTimerShutdownPeriodicTest,CpuWatchmanResizeTest,ThreadPoolQueueShutdownTest,SerialQueueCapacityTest test --batch-mode --no-transfer-progress

mvn -pl rxlib -am -Dmaven.test.skip=false -DskipTests=false -Dgpg.skip=true -Dmaven.gpg.skip=true '-Dtest=*CpuWatchman*,*WheelTimer*,*Tasks*' test --batch-mode --no-transfer-progress
```

# 回滚方案

如本次测试 / 文档补强导致问题：

```bash
git revert 395359d7e858857c31f0cc04d64cc3e64586a3fb
```

如果只是测试不稳定：

- 优先调整等待条件。
- 不直接删除测试。
- 保持 stopTimer 失败重试、并发 shutdown、shutdownNow 语义覆盖。

# 当前结论

本次已完成用户指定的三个 P1 待办：

1. 新增 `stopTimer()` 异常注入测试，验证 stop 失败后状态恢复并允许重试。
2. 新增重复 / 并发 `shutdown()` 与 `shutdownNow()` 测试，覆盖幂等和并发关闭边界。
3. 新增 `docs/reference/WheelTimerShutdown.md`，明确 `shutdownNow()` 与外部 executor running task 的生命周期边界。

下一步必须触发并确认最新 `Agent CI`，若失败则按日志继续修复。
