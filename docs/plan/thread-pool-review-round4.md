# rxlib ThreadPool 第四轮 Review 与后续修复计划

> 日期：2026-05-03  
> 关联主计划：`docs/plan/thread-pool-review-plan.md`  
> 关联补充：`docs/plan/thread-pool-review-round3.md`

## 1. 说明

上一轮我在本地临时环境生成了 `thread-pool-review-round4.md`，但没有真正提交到远端 GitHub 仓库。本文件用于把第四轮 review 结论正式落到 `RockyLOMO/rxlib` 的 `docs/plan` 下。

注意：本文件是第四轮 follow-up 计划文档，不声明代码已经全部完成。代码层面的生命周期、CpuWatchman、SINGLE namespace 等修复仍建议按本计划继续落地。

## 2. 当前已完成的阶段 1 能力

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

## 3. 本轮剩余待执行修复

### 3.1 Tasks 生命周期语义

当前主计划中仍保留 `Tasks.shutdown()` / `shutdownNow()` 生命周期项。建议实施：

1. 新增 `Tasks.shutdown()`：
   - 停止接收新的全局任务。
   - 调用 `timer.shutdown()`。
   - 遍历 `nodes` 调用每个 `ThreadPool.shutdown()`。
   - 执行已注册的 `shutdownActions`。
2. 新增 `Tasks.shutdownNow()`：
   - 调用 `timer.shutdownNow()`。
   - 遍历 `nodes` 调用 `shutdownNow()` 并聚合未执行任务。
   - 停止或 unregister `CpuWatchman` 里的 pool。
3. 明确 `Tasks.executor().shutdown()` 行为：
   - 方案 A：仅 warn，提示使用 `Tasks.shutdown()`。
   - 方案 B：直接委托到 `Tasks.shutdown()`。

建议选择方案 B，避免 `ExecutorService` 语义误导。

### 3.2 WheelTimer shutdown 后周期任务不再重排

当前建议：

1. `Task.onExecutionFinished()` 中增加 `!WheelTimer.this.isShutdown()` 条件。
2. `PeriodicTask.scheduleNext()` 中增加 shutdown 判断。
3. `shutdown()` 后：
   - 已经开始执行的一次性任务允许完成。
   - 周期任务不再排下一次。
   - 新任务继续拒绝。
4. `shutdownNow()` 继续取消 holder 和 stop timer。

需要补充测试：

- `scheduleAtFixedRate` 在 shutdown 后不再增加 counter。
- `scheduleWithFixedDelay` 在 shutdown 后不再重排。
- `awaitTermination()` 能在周期任务停止后返回 true。

### 3.3 CpuWatchman resize cooldown / jitter

`resizeCooldownMillis` 已有配置，但需要接入实际扩缩容路径。

建议：

1. 在 `CpuWatchman` holder state 中增加 `lastResizeMillis`。
2. `incrSize()` / `decrSize()` 前判断 cooldown。
3. 多副本 pool 的采样重排增加小 jitter，避免同时扩缩。
4. `setCorePoolSize()` 包裹异常保护。
5. 增加 resize 指标：
   - `rx.thread_pool.resize.count`
   - `rx.thread_pool.resize.cooldown.skipped.count`
   - tags: `poolName`、`reason`、`before`、`after`

### 3.4 SINGLE namespace / scope

当前 `runningSingleTasks` 是全 JVM 全局 Set，存在跨业务 taskId 冲突风险。

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

### 3.5 BLOCK 模式 interrupt / shutdownNow 解阻塞

建议补测试：

1. 固定线程池 `size=1, queueCapacity=1`。
2. 一个任务占满 worker，一个任务占满队列。
3. 第三个提交线程阻塞在 `BLOCK offer`。
4. interrupt 提交线程，确认能抛 `RejectedExecutionException` 或返回失败。
5. shutdownNow 后确认阻塞线程不会永久卡住。

### 3.6 SERIAL 异常链语义

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

## 4. 推荐落地顺序

1. 先修 `WheelTimer.shutdown()` 周期任务重排语义，风险低、收益直接。
2. 再补 `Tasks.shutdown()` / `shutdownNow()`，固定全局生命周期。
3. 补 `BLOCK` interrupt / shutdownNow 解阻塞测试。
4. 补 `SERIAL` 异常链语义测试。
5. 最后做 `CpuWatchman` cooldown / jitter 和 SINGLE namespace。

## 5. 验收命令建议

```bash
mvn -pl rxlib "-Dtest=ThreadPoolWheelTimerRegressionTest,ThreadPoolQueueOfferModeTest,RxConfigTest,TasksCompatibilityTest" test
mvn -pl rxlib "-Dtest=ThreadPoolTest" test
mvn -pl rxlib "-Dtest=*CpuWatchman*,*WheelTimer*,*Tasks*" test
```

网络相关集成测试仍建议单独跑，避免 UDP flaky 误判线程池修复：

```bash
mvn -pl rxlib "-Dtest=SocksProxyServerIntegrationTest,ShadowsocksServerIntegrationTest,Socks5ClientIntegrationTest,RrpIntegrationTest,RemotingTest,DnsServerIntegrationTest" test
```

## 6. 总结

ThreadPool 阶段 1 已经完成，当前剩余事项集中在生命周期、动态扩缩容稳定性、SINGLE 作用域隔离以及少量边界测试。建议下一步按本文件第 4 节顺序继续执行，避免一次性改动过大影响网络链路测试稳定性。
