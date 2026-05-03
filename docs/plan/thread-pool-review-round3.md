# rxlib ThreadPool 第三轮 Review 摘要

> 日期：2026-05-03  
> 基准提交：`b7dd8c49c575ba53a8b837b7399866a184b46163`  
> 关联主计划：`docs/plan/thread-pool-review-plan.md`

## 1. Review 结论

第三轮 review 后，`ThreadPool` 阶段 1 可以判定为完成。相比上一轮，新增修复已覆盖当时遗留的关键边界：

- `CALLER_RUNS` 生命周期等价性。
- `INHERIT_FAST_THREAD_LOCALS` old map 恢复与污染隔离。
- `queueOfferMode` 配置解析容错与未知值可观测性。
- 单测修改全局 `RxConfig.INSTANCE.threadPool` 后的 snapshot/restore。

后续重点建议转向：

1. `Tasks` / `WheelTimer` 生命周期语义。
2. `CpuWatchman` 扩缩容稳定性。
3. `SINGLE` namespace/scope。
4. traceId 泄漏测试与 FastThreadLocal worker 路径隔离测试。

## 2. 本轮已确认修复

### 2.1 CALLER_RUNS 生命周期

已新增 caller-runs 回归测试，覆盖：

- `RunFlag.SINGLE` 在 caller-runs 路径下仍能正确跳过重复任务，并在原任务完成后释放 `runningSingleTasks`。
- caller-runs 后 `taskMap` 可清理。
- `THREAD_TRACE` 在 caller-runs 路径下异常时也能正确 end，不污染提交线程 trace 栈。
- `INHERIT_FAST_THREAD_LOCALS` 在 caller-runs 路径下可读取父线程上下文，但任务内修改不会污染提交线程后续上下文。

结论：caller-runs 路径不再只是“直接 r.run()”，而是基本走统一生命周期，风险明显下降。

### 2.2 FastThreadLocal 隔离

`ThreadPool.Task` 新增：

- `threadLocalMapSet`
- `oldThreadLocalMap`

执行前保存当前线程 old map，执行时使用复制后的父线程 `InternalThreadLocalMap.indexedVariables`，执行后恢复 old map。

优点：

- 避免直接复用父线程 `InternalThreadLocalMap` 导致任务内修改污染父线程。
- caller-runs 路径下尤其关键，因为提交线程就是执行线程。

注意：

- 当前实现依赖 Netty internal 构造器和 `indexedVariables` 字段，后续升级 Netty 需重点回归。

### 2.3 queueOfferMode parse 容错

`ThreadPoolQueueOfferMode.parse()` 已增强：

- 支持 `trim()`。
- 支持 `timeout-reject`、`caller runs` 这类宽松写法。
- 未知值 fallback 到 default。
- 未知值记录 warn。
- 未知值记录 `rx.thread_pool.config.invalid.count`。

`RxConfig.refreshFrom()` 也已对 `app.threadPool.queueOfferMode` 走专用 parse，避免普通 enum 转换不兼容宽松写法。

### 2.4 测试配置恢复

新增 `ThreadPoolConfigSnapshot`：

- 捕获 `RxConfig.INSTANCE.threadPool` 主要字段。
- try-with-resources 自动恢复。
- `close()` 后调用 `RxConfig.INSTANCE.afterSet()` 重新 normalize。

结论：串行测试下全局配置串扰风险已明显降低。

## 3. 仍建议补强的点

### 3.1 BLOCK 模式 interrupt / shutdownNow 解阻塞

当前 `BLOCK` 是默认兼容模式，仍建议补测试：

- 队列满后一个提交线程阻塞在 offer。
- 对提交线程 interrupt 后能否快速返回/抛拒绝。
- 对 pool `shutdownNow()` 后是否能释放等待方或避免永久阻塞。

这对生产事故处理很重要：如果调用方是业务线程池中的线程，不能因为目标线程池满而永久挂住。

### 3.2 SERIAL 异常链语义

建议明确并测试：

- 某个 serial 任务抛异常后，后续同 taskId 任务是继续执行、跳过，还是被异常链阻断。
- 当前 API 应文档化这一点，否则上层业务会误判串行队列可靠性。

### 3.3 FastThreadLocal worker 路径测试

已覆盖 caller-runs 路径，但还建议补 worker 路径：

- `INHERIT_FAST_THREAD_LOCALS` 时，worker 可读取父线程值。
- worker 内修改不会污染后续任务。
- 未设置 `INHERIT_FAST_THREAD_LOCALS` 时，worker 不应看到父线程 FastThreadLocal。

### 3.4 配置并行测试策略

`ThreadPoolConfigSnapshot` 可恢复状态，但如果 JUnit parallel 开启，不同测试仍可能同时修改同一个全局配置对象。

建议二选一：

- 给线程池配置相关测试加 `@ResourceLock("RxConfig.threadPool")`。
- CI 中固定这些测试串行执行。

## 4. 下一阶段计划建议

### 阶段 2：生命周期语义

优先级最高。

- `Tasks.shutdown()` / `shutdownNow()` 明确关闭所有 nodes、全局 timer、CpuWatchman timer。
- `Tasks.executor().shutdown()` 是 warn-only 还是委托到底层，需要固定。
- `WheelTimer.shutdown()` 后周期任务是否继续重排需要固定。
- `ThreadPool.shutdown()` 时 unregister `CpuWatchman`。

验收测试：

- shutdown 后拒绝新任务。
- shutdownNow 后队列任务清理。
- 周期任务 shutdown 后不再重排。
- awaitTermination 能真实反映终止状态。

### 阶段 3：CpuWatchman 稳定性

- 统一 CPU load 单位为 0~100。
- `resizeCooldownMillis` 实际接入 `incrSize/decrSize`。
- 多副本 resize 加 jitter。
- `PRIORITY` 触发扩容加速率限制。
- 增加 resize 指标。

### 阶段 4：语义增强

- `SINGLE` 增加 namespace/scope。
- traceId 泄漏测试。
- FastThreadLocal worker 路径完整隔离测试。
- 文档补齐 `run` / `runAsync` / `submit` / `SERIAL` / `SINGLE` / `TRANSFER` 差异。

## 5. 总结

本轮修复质量较好，已经把阶段 1 从“基本完成”推进到“完成”。现在 ThreadPool 的队列背压、SERIAL 容量、CompletableFuture patch 开关、caller-runs 生命周期和配置解析容错都具备了可测试边界。下一步不建议继续在队列策略上反复打磨，优先投入生命周期与 CpuWatchman 扩缩容稳定性。