# rxlib ThreadPool 第四轮 Review 与后续修复计划

> 日期：2026-05-03  
> 最近更新：2026-05-03  
> 关联主计划：`docs/plan/thread-pool-review-plan.md`  
> 关联补充：`docs/plan/thread-pool-review-round3.md`  
> 本轮 review 基准：`5b93688419a68050cddeb5919f251e9e6fa19d1b`

## 1. 说明

本文件用于记录第四轮 thread pool 相关代码修复后的 review 结论。当前已经提交了 3 个代码 commit：

- `6ac0e570fe705ea339e8b5620626a5dc34523850`：`fix(threadpool): add Tasks lifecycle shutdown`
- `d71b8784816616738fa0acf2667ff31a8ad3875c`：`fix(threadpool): add CpuWatchman resize cooldown`
- `5b93688419a68050cddeb5919f251e9e6fa19d1b`：`fix(threadpool): stop WheelTimer periodic reschedule after shutdown`

本轮 review 结论：

- 生命周期入口、WheelTimer 周期任务停止、CpuWatchman cooldown 已有代码落地。
- 但仍需要补测试和修若干边界问题，尤其是 `ThreadQueue` 在 `shutdownNow()` / drain 队列时的 slot 释放、`WheelTimer` 无 taskId 已排队任务的 termination 语义、`SINGLE namespace` 以及 SERIAL 异常链。
- 当前环境未执行 Maven 测试，下面所有验收项仍需要在本地/CI 中跑完后再关闭。

## 2. 已完成的阶段 1 能力

前三轮已经确认完成的内容：

- `ThreadPool.ThreadQueue` 支持 `BLOCK / TIMEOUT_REJECT / CALLER_RUNS`。
- 默认 `BLOCK`，兼容旧行为。
- `TIMEOUT_REJECT` 与 `CALLER_RUNS` 已有回归测试。
- `SERIAL` 容量改为 `serialQueueCapacity / serialQueueHardLimit`。
- `runSerialAsync()` 的 `ConcurrentHashMap.compute()` 重入挂死风险已修。
- `CompletableFuture` async pool patch 默认关闭，显式配置开启。
- `CALLER_RUNS` 路径已补 SINGLE / THREAD_TRACE / FastThreadLocal / taskMap 生命周期测试。
- `INHERIT_FAST_THREAD_LOCALS` 已改为复制父线程 `InternalThreadLocalMap.indexedVariables` 并恢复 old map。
- `ThreadPoolQueueOfferMode.parse()` 支持 trim、`timeout-reject`、`caller runs` 等宽松写法，未知值有 warn 和 metric。
- 新增 `ThreadPoolConfigSnapshot` 测试工具，降低单测全局配置串扰。

## 3. 本轮已落地修复

### 3.1 Tasks 生命周期语义 —— 已初步落地

已提交内容：

1. `Tasks.executor()` 返回的代理 executor 现在维护 `shutdown / shutdownNow` 状态。
2. `Tasks.shutdown()` 已委托到全局 executor 的 `shutdown()`：
   - 标记全局 executor 关闭。
   - 调用 `timer.shutdown()`。
   - 遍历 `nodes`，先 `CpuWatchman.INSTANCE.unregister(node)`，再 `node.shutdown()`。
   - 执行并清空 `shutdownActions`。
3. `Tasks.shutdownNow()` 已委托到全局 executor 的 `shutdownNow()`：
   - 调用 `timer.shutdownNow()`。
   - 遍历 `nodes`，先 unregister，再 `node.shutdownNow()` 并聚合 pending task。
   - 调用 `CpuWatchman.INSTANCE.shutdown()`。
   - 执行并清空 `shutdownActions`。
4. `nextPool()` / `nextPool(task, taskId, flags)` 在全局 executor shutdown 后会快速抛 `RejectedExecutionException`。
5. `createPool()` 在全局 executor shutdown 后不再创建新 pool。

Review 结论：方向正确，但仍需补测试和几个语义收口点。

剩余待办：

- [ ] 补 `TasksShutdownLifecycleTest`：
  - `Tasks.shutdown()` 后新提交任务必须抛 `RejectedExecutionException`。
  - `Tasks.shutdownNow()` 返回值应包含各 `ThreadPool` 未执行任务。
  - `Tasks.executor().awaitTermination()` 能在合理时间返回 true。
- [ ] 明确全局 `Tasks` 是否设计为“一次性 shutdown 后不可恢复”。当前实现是不可恢复，文档需要写清楚。
- [ ] `Tasks.shutdown()` 只 unregister `CpuWatchman` 的 nodes，但没有停止 `CpuWatchman` 自身 timer；如果目标是完整释放全局资源，建议 graceful shutdown 也调用 `CpuWatchman.INSTANCE.shutdown()`，或文档声明 graceful 仅注销 pool、不停止 watchman。
- [ ] `Tasks.shutdown()` 调用 `timer.shutdown()` 后，`timer.isTerminated()` 可能受 WheelTimer holder 中未取消任务影响，需要和 3.2 一起修。

### 3.2 WheelTimer shutdown 后周期任务不再重排 —— 已部分落地

已提交内容：

1. `Task.onExecutionFinished()` 已增加 `!shutdown` 判断，`TimeoutFlag.PERIOD` 在 shutdown 后不再重排。
2. `PeriodicTask.scheduleNext()` 已增加 `shutdown` 判断，`scheduleAtFixedRate` / `scheduleWithFixedDelay` 生成的周期任务在 shutdown 后不再重排。
3. 新增 `periodicTasks` 集合跟踪无 taskId 的 `PeriodicTask`。
4. `shutdown()` 会 cancel 当前 `periodicTasks`。
5. `shutdownNow()` 会 cancel holder 中任务、cancel `periodicTasks`、clear holder / periodicTasks 并 stop timer。
6. `isTerminated()` 已从仅检查 holder 扩展到 holder + periodicTasks。

Review 结论：`ScheduledExecutorService` 风格的周期任务已明显收敛，但 `setTimeout(... TimeoutFlag.PERIOD)` 以及无 taskId 已排队任务的 termination 语义仍不完整。

剩余待办：

- [ ] `shutdown()` 目前只 cancel `PeriodicTask`，没有 cancel holder 里的 `TimeoutFlag.PERIOD` 任务。若这类任务带 taskId 且 delay 很长，`holder` 可能长期不空，导致 `awaitTermination()` 等不到 true。
- [ ] `setTimeout(..., taskId=null, TimeoutFlag.PERIOD)` 不在 holder，也不在 `periodicTasks`，shutdown 后如果还没触发，`isTerminated()` 可能提前 true，但该任务未来仍可能执行一次。
- [ ] 建议新增 active timeout 集合，统一跟踪所有 `Task`，不仅仅是 holder by id 和 `PeriodicTask`。
- [ ] `shutdown()` 后已排队的一次性任务是否允许继续执行，需要写入 reference 文档并补测试。当前代码倾向“允许已排队 task 触发执行，但不再重排 period”。
- [ ] `PeriodicTask.resolveTerminal()` 在 shutdown 后抛 `CancellationException`，需要确认调用方预期是否接受。
- [ ] 补 `WheelTimerShutdownPeriodicTest`：
  - `scheduleAtFixedRate` shutdown 后 counter 不再增长。
  - `scheduleWithFixedDelay` shutdown 后 counter 不再增长。
  - `setTimeout(... TimeoutFlag.PERIOD)` shutdown 后不再重排。
  - 带 taskId 的 long delay period task 不会导致 awaitTermination 长时间 false。
  - 无 taskId long delay task 不会在 isTerminated=true 后继续执行。

### 3.3 CpuWatchman resize cooldown —— 已部分落地

已提交内容：

1. holder state 从 `int[2]` 改为 `long[3]`，新增 `LAST_RESIZE_MILLIS`。
2. `resizeCooldownMillis` 已在 `resize()` 中生效。
3. `setCorePoolSize()` 相关异常已有 catch 和 warn。
4. CPU load 已统一按 `rawLoad * 100D` 转为百分比。
5. 新增 `rx.thread_pool.resize.count` 指标，tags 包含 action、reason、before、after。
6. `CpuWatchman.shutdown()` 已能 clear holder 并 stop 内部 timer。

Review 结论：cooldown 主逻辑已落地，但 jitter、CPU load 异常值和指标命名还有待补齐。

剩余待办：

- [ ] `OperatingSystemMXBean.getSystemCpuLoad()` / `getProcessCpuLoad()` 可能返回 `-1`，当前会变成 `-100`，可能误触发扩容。建议 clamp 到 `0~100`，或遇到 `<0` 时跳过本轮 resize。
- [ ] 多副本 pool 的 resize jitter 还没有实现，多个 pool 仍可能在同一采样周期同步扩缩。
- [ ] 计划中的 `rx.thread_pool.resize.cooldown.skipped.count` 未单独实现，目前 cooldown 也记入 `rx.thread_pool.resize.count` 的 `action=cooldown`。如果监控侧需要单独图表，建议补独立指标。
- [ ] `PRIORITY` 直接调用 `CpuWatchman.incrSize(pool)` 的路径没有走 cooldown，仍可能绕过节流。建议新增 `CpuWatchman.tryIncrSize(pool, reason)` 或让 `incrSize()` 内部统一节流。
- [ ] `CpuWatchman.shutdown()` 是全局不可恢复；如果单测或业务在同 JVM 内 shutdown 后再创建 standalone `ThreadPool`，动态伸缩会失效。需要文档说明或支持 restart。
- [ ] 补 `CpuWatchmanResizeTest`：cooldown 生效、CPU load -1 跳过、resize 不越界、PRIORITY 不绕过 cooldown。

## 4. 本轮仍未修的代码问题

### 4.1 SINGLE namespace / scope —— 未落地

当前 `runningSingleTasks` 仍是 JVM 全局 Set，存在跨业务 taskId 冲突风险。

建议：

1. 引入 `SingleKey(namespace, taskId)`。
2. 老 API 默认 namespace 为 `global`，保持兼容。
3. 新增重载：
   - `runAsync(task, namespace, taskId, flags)`
   - `run(task, namespace, taskId, flags)`
4. 日志只输出 `taskIdHash`，避免暴露完整业务 key。
5. 单测：
   - 同 namespace + 同 taskId 跳过。
   - 不同 namespace + 同 taskId 可并行。

### 4.2 BLOCK 模式 interrupt / shutdownNow 解阻塞 —— 未完全落地

当前 `ThreadQueue.blockUntilSlot()` 在提交线程被 interrupt 时可以返回 reject，但 `pool.shutdownNow()` 本身未必能主动唤醒正在 `availableSlots.tryAcquire()` 的提交线程。

更重要的是：`ThreadPoolExecutor.shutdownNow()` 可能通过 `workQueue.drainTo()` 取走队列元素；当前 `ThreadQueue` 只在 `poll/take/remove` 中调用 `doNotify()`，没有覆盖 `drainTo()` / `clear()`。如果 shutdownNow drain 队列绕过 `doNotify()`，`counter` 与 `availableSlots` 可能不释放，导致后续等待 slot 的提交线程卡住或队列容量统计错误。

建议：

1. 覆盖 `ThreadQueue.drainTo(Collection)` 和 `drainTo(Collection, int)`，按实际 drain 数量释放 slot。
2. 覆盖 `clear()`，释放所有已计数 slot。
3. `blockUntilSlot()` 循环中增加 `pool == null || pool.isShutdown()` 判断，pool 已 shutdown 时直接返回 reject。
4. `ThreadPool.shutdown()` / `shutdownNow()` override 中显式 `CpuWatchman.INSTANCE.unregister(this)`。
5. 补测试：
   - 队列满后第三提交线程阻塞。
   - interrupt 提交线程后可退出。
   - shutdownNow 后可退出。
   - shutdownNow drain 后 `queue.size()` / `remainingCapacity()` / slot 计数一致。

### 4.3 SERIAL 异常链语义 —— 未验证

建议明确：同 taskId 的 serial 链中某个任务异常后，后续任务是否继续执行。

推荐语义：

- 单个任务异常只影响自身 Future。
- 后续 serial 任务继续执行。
- serial 链尾部完成后仍清理 `taskSerialMap / taskSerialCountMap`。

需要补测试：

- first 抛异常。
- second 正常返回。
- 两个 future 结果分别符合预期。
- map / counter 最终清理。

### 4.4 ThreadPool 独立实例生命周期 —— 未落地

`Tasks.shutdown()` 已对全局 nodes 做 unregister，但 standalone `new ThreadPool(...)` / `ThreadPool.fixed(...)` 的生命周期仍未 override。

建议：

1. `ThreadPool.shutdown()` override：先 unregister 自己，再调用 super。
2. `ThreadPool.shutdownNow()` override：先 unregister 自己，再调用 super，并保证 queue slot 释放。
3. 补 standalone pool shutdown 测试，避免 `CpuWatchman` 持有已 shutdown pool 到弱引用回收前才清理。

## 5. 推荐下一步落地顺序

1. **优先修 ThreadQueue drain/clear slot 释放**：这是 shutdownNow 场景下最可能出现真实卡死/容量计数错乱的问题。
2. **补 WheelTimer active timeout 跟踪**：让 shutdown/awaitTermination 对有 taskId、无 taskId、一次性、周期性任务都可预测。
3. **补 CpuWatchman CPU load clamp + PRIORITY cooldown**。
4. **补 standalone ThreadPool shutdown/unregister**。
5. **补 SERIAL 异常链测试**。
6. **最后做 SINGLE namespace/scope**，因为这会影响 API 设计，适合单独提交。

## 6. 验收命令建议

```bash
mvn -pl rxlib "-Dtest=ThreadPoolWheelTimerRegressionTest,ThreadPoolQueueOfferModeTest,RxConfigTest,TasksCompatibilityTest" test
mvn -pl rxlib "-Dtest=ThreadPoolTest" test
mvn -pl rxlib "-Dtest=*CpuWatchman*,*WheelTimer*,*Tasks*" test
```

新增/建议补充测试后，再执行：

```bash
mvn -pl rxlib "-Dtest=TasksShutdownLifecycleTest,WheelTimerShutdownPeriodicTest,CpuWatchmanResizeTest,ThreadPoolQueueShutdownTest,SerialQueueCapacityTest,SingleScopeTest" test
```

网络相关集成测试仍建议单独跑，避免 UDP flaky 误判线程池修复：

```bash
mvn -pl rxlib "-Dtest=SocksProxyServerIntegrationTest,ShadowsocksServerIntegrationTest,Socks5ClientIntegrationTest,RrpIntegrationTest,RemotingTest,DnsServerIntegrationTest" test
```

## 7. 总结

第四轮代码已经完成了 `Tasks` 生命周期入口、`WheelTimer` 基础周期任务停止、`CpuWatchman` resize cooldown 三个方向的初步修复。但当前还不能把 round4 判定为完全完成：`ThreadQueue` shutdownNow/drain slot 释放、`WheelTimer` 全量 active timeout 跟踪、`CpuWatchman` 异常 CPU load 和 PRIORITY cooldown、standalone ThreadPool unregister、SERIAL 异常链测试、SINGLE namespace 仍是剩余待办。建议先处理 shutdownNow 相关的队列 slot 释放和 WheelTimer termination 语义，再进入 API 层的 SINGLE namespace 设计。
