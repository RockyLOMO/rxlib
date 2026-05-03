# rxlib ThreadPool 第四轮 Review 与后续修复计划

> 日期：2026-05-03  
> 最近更新：2026-05-03  
> 关联主计划：`docs/plan/thread-pool-review-plan.md`  
> 关联补充：`docs/plan/thread-pool-review-round3.md`  
> 最新 review 基准：`2c6865a64e5f6624e68c680c809deeb8e5ad8122`

## 1. 本轮最新结论

`Tasks` 是全局入口包装类，不应该真正关闭底层共享线程池、timer 或 watchman。因此之前 `6ac0e570` 的“真实 shutdown”方向已由 `6ae2c595` 修正为 no-op wrapper。

本轮已补充提交：

- `b1c6dd4a910f9162e11d9e0bc1981aabac77ec71`：增强 `CpuWatchman`，补 CPU load 非法值跳过、cooldown 独立指标、PRIORITY 直调路径 cooldown。
- `6ae2c5950c931ad25d28df2dcd58d42636b41321`：修正 `Tasks.shutdown()` / `shutdownNow()` 为 no-op wrapper，不再关闭底层共享资源。
- `2c6865a64e5f6624e68c680c809deeb8e5ad8122`：增强 `WheelTimer`，新增 `activeTasks` 跟踪所有 timeout task，shutdown/shutdownNow 统一清理，termination 判断覆盖 active task。

说明：本轮通过 GitHub contents API 操作，提交按文件产生，没有 squash 成单个 commit。若需要单 commit 历史，建议后续本地 rebase/squash 或 PR squash merge。

当前环境未执行 Maven 测试，需要在本地或 CI 完成验证。

## 2. 已修复/已增强项

### 2.1 Tasks 入口包装语义

已完成：

- `Tasks.executor().shutdown()`：忽略并记录 warn。
- `Tasks.executor().shutdownNow()`：忽略并返回空列表。
- `isShutdown()` / `isTerminated()` 固定返回 false。
- `awaitTermination()` 固定返回 false。
- `Tasks.shutdown()` / `Tasks.shutdownNow()` 保留 API，但实际为 no-op。
- 移除 `nextPool()` 因入口 shutdown 状态拒绝任务的行为。

剩余建议：

- 补 `TasksCompatibilityTest`：调用 `Tasks.executor().shutdown()` 后仍可提交任务。
- 在 `docs/reference/ThreadPool.md` 明确 `Tasks.shutdown()` 是 no-op，不承担资源释放职责。

### 2.2 WheelTimer active timeout 跟踪

已完成：

- 新增 `activeTasks` 集合跟踪所有 `Task` timeout。
- `newTimeout()` 成功排队后加入 `activeTasks`。
- task 完成、取消、异常后从 `activeTasks` 移除。
- `shutdown()` 会 cancel `activeTasks` 和 `periodicTasks`。
- `shutdownNow()` 会 cancel holder、activeTasks、periodicTasks，并清空集合。
- `isTerminated()` 判断扩展为 `holder + activeTasks + periodicTasks`。
- 新增 `rx.wheel_timer.active.count` 指标。

剩余建议：

- 补 `WheelTimerShutdownPeriodicTest`，验证无 taskId long delay task、带 taskId period task、fixedRate/fixedDelay 在 shutdown 后都不会继续执行或重排。
- 同步 reference 文档：`shutdown()` 现在会取消未执行 timeout task，不再声明“已排队一次性任务继续执行”。

### 2.3 CpuWatchman resize 边界

已完成：

- CPU load `<0` 或 NaN 时跳过本轮 resize。
- CPU load 上限 clamp 到 100。
- 新增 `rx.thread_pool.cpu_load.invalid.count`。
- cooldown 判断抽到统一方法。
- 新增 `rx.thread_pool.resize.cooldown.skipped.count`。
- `incrSize()` / `decrSize()` 静态入口也接入 cooldown，降低 `RunFlag.PRIORITY` 绕过 cooldown 的风险。

剩余建议：

- 多副本 resize jitter 仍未实现。
- 补 `CpuWatchmanResizeTest`：invalid CPU load、cooldown skip 指标、PRIORITY 不绕过 cooldown、resize 不越界。

## 3. 仍未完成的代码项

### 3.1 ThreadQueue drain/clear slot 释放

仍未落地，优先级最高。

当前风险：`ThreadPoolExecutor.shutdownNow()` 可能通过队列 `drainTo()` 取走元素，而 `ThreadQueue` 目前只在 `poll/take/remove` 中释放 slot。如果 `drainTo()` / `clear()` 绕过释放逻辑，`counter` 和 `availableSlots` 可能不一致。

建议：

- 覆盖 `ThreadQueue.drainTo(Collection)`。
- 覆盖 `ThreadQueue.drainTo(Collection, int)`。
- 覆盖 `ThreadQueue.clear()`。
- 按实际移除数量释放 slot。
- `blockUntilSlot()` 循环中检测 pool shutdown，已关闭则快速 reject。

### 3.2 standalone ThreadPool 生命周期

仍未落地。

建议：

- `ThreadPool.shutdown()` override：先 `CpuWatchman.INSTANCE.unregister(this)`，再调用 super。
- `ThreadPool.shutdownNow()` override：先 unregister，再调用 super。
- 配合 ThreadQueue drain/clear 修复，保证 shutdownNow 后容量计数一致。

### 3.3 SERIAL 异常链测试

仍未验证。

推荐语义：单个 serial task 异常只影响自己的 Future，后续同 taskId 任务继续执行，链尾完成后清理 `taskSerialMap / taskSerialCountMap`。

### 3.4 SINGLE namespace / scope

仍未落地。

建议单独提交设计：`SingleKey(namespace, taskId)`，老 API 默认 namespace 为 global，新增带 namespace 的 run/runAsync 重载。

## 4. 推荐下一步顺序

1. 修 `ThreadQueue drainTo/clear` slot 释放。
2. 修 standalone `ThreadPool.shutdown/shutdownNow` unregister。
3. 补 `WheelTimerShutdownPeriodicTest`。
4. 补 `CpuWatchmanResizeTest`。
5. 补 SERIAL 异常链测试。
6. 单独设计并实现 SINGLE namespace/scope。

## 5. 建议验收命令

```bash
mvn -pl rxlib "-Dtest=ThreadPoolWheelTimerRegressionTest,ThreadPoolQueueOfferModeTest,RxConfigTest,TasksCompatibilityTest" test
mvn -pl rxlib "-Dtest=ThreadPoolTest" test
mvn -pl rxlib "-Dtest=*CpuWatchman*,*WheelTimer*,*Tasks*" test
```

补充测试后建议新增执行：

```bash
mvn -pl rxlib "-Dtest=WheelTimerShutdownPeriodicTest,CpuWatchmanResizeTest,ThreadPoolQueueShutdownTest,SerialQueueCapacityTest,SingleScopeTest" test
```

## 6. 总结

本轮已修复或增强 3 个方向：`Tasks` no-op 包装语义、`WheelTimer` active timeout 跟踪、`CpuWatchman` CPU load/cooldown/PRIORITY 边界。剩余最优先的是 `ThreadPool.java` 内部的队列 slot 一致性和 standalone pool 生命周期，其次是测试补齐，最后再做 SINGLE namespace 这类 API 级增强。
