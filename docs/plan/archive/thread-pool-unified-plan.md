# ThreadPool / ThreadQueue / WheelTimer 线程池与时间轮统一演进计划

本文档整合了 `thread-pool-review-plan.md` 和 `thread-pool-traceid-review-plan.md` 的核心设计与优化状态，提供了 `rxlib` 线程池、队列调度、时间轮生命周期以及 TraceId 传递机制的全局演进视图。

---

## 1. 整体背景与目标

- **项目定位**：高性能模式（Netty / 多线程并发）。
- **核心诉求**：提供健壮、高性能的并发工具（`ThreadPool`、`WheelTimer`、`CpuWatchman`），实现严格的生命周期闭环（拒绝泄漏与阻塞）与精准的分布式链路追踪支持（`traceId`）。
- **当前状态**：两阶段的重构（生命周期加固与 TraceId 缺陷修复）均已完成代码实现与本地验证，进入测试回归和细节收敛阶段。

---

## 2. 第一部分：ThreadPool & WheelTimer 生命周期演进（已完成）

### 2.1 ThreadPool & 队列核心逻辑加固
- **队列 Offer 策略**：支持 `BLOCK` / `TIMEOUT_REJECT` / `CALLER_RUNS`。
- **SERIAL 串行池**：实现了容量控制、compute 阶段重入风险修复，且确保前序任务异常不会阻断后续同 `taskId` 任务。
- **CpuWatchman 动态伸缩**：支持基于采样、cooldown 与抖动抑制的线程池大小动态 resize。
- **FastThreadLocal 隔离**：实现了工作线程执行任务前后 ThreadLocal 状态的安全清理与恢复。

### 2.2 WheelTimer 优雅关闭与异常自愈
- **优雅关闭 (Shutdown)**：拒绝新任务，取消未执行的 timeout 并清理 `activeTasks`。对已在执行的用户任务使用 `cancel(false)` 保证自然结束。
- **安全停止 (stopTimer)**：实现了对底层 Netty `HashedWheelTimer` 的 stop 控制，在 stop 失败异常路径下能够正确恢复状态并允许重试。
- **并发与重复 Shutdown**：通过原子状态位控制，保障多线程混合并发调用 `shutdown` 与 `shutdownNow` 时的状态一致性。

---

## 3. 第二部分：TraceId 自动传递与精准清理（已落地）

### 3.1 TraceId 传递架构设计
`ThreadPool.Task` 在构造时捕获提交线程的 `traceId`，在工作线程 `beforeExecute` 执行前切换上下文，并在 `afterExecute` 中进行清理。

### 3.2 核心缺陷修复与优化
- **Stale Trace 覆盖缺陷修复**：
  - **问题**：原 `beforeExecute` 使用 `requiresNew=false` 绑定，若 worker 线程已被继承了其他 trace，任务提交时捕获的 traceId 将被静默忽略。
  - **解法**：当 worker 已有不同 trace 时，强制以 `requires-new` 重新 push 任务 trace；若捕获为空则生成新 trace，彻底消除 Stale Trace 污染。
- **非对称清理缺陷修复**：
  - **问题**：旧版 `afterExecute` 无条件调用 `endTrace()`，在未成功 push trace 的场景下会误清空工作线程原有的外层 trace 栈。
  - **解法**：在 `Task` 中引入 `threadTraceStarted` 显式标记，仅在 `beforeExecute` 确实为该任务 push trace 成功时，才在 `afterExecute` 执行 `endTrace()` 清理。
- **正数归一化**：
  - `RxConfig` 对 `maxTraceDepth` 进行了正数归一化，规避默认值为 0 时导致 requires-new 嵌套任务被直接丢弃的风险。

---

## 4. 剩余待办与待观察事项

### P0：确认 CI 状态与稳定性
- 当前并发测试（重复/并发 shutdown、stale trace 强制切换、`CALLER_RUNS` 链路 Trace 切换等）已全部落地。需要确认最新 CI 结果为 `success`。

### P1：WheelTimer Shutdown 等待语义失效修复（暂缓）
- **问题**：在 running task 场景下，由于 `Task.cancel(false)` 判定 Future 取消成功后立即执行 `completeTask()`，导致任务过早移出 `activeTasks`。`shutdown()` 后立即调用 `awaitTermination()` 会提前返回 `true`，但实际任务仍在外部 executor 中执行。
- **修复建议**：对 `mayInterruptIfRunning=false` 且已在运行的任务，不立即触发 `completeTask()`，改由 `onExecutionFinished()` 在自然结束时清理。

### P2：降低对 Netty 内部反射的依赖
- 当前异常注入测试中依赖反射读取 `HashedWheelTimer.workerThread` 以及依赖其在 worker 线程 stop 抛出的异常。未来可考虑设计更温和的 Timer 模拟注入以解耦。

### P2：指标基数约束
- 线程池、SERIAL 及时间轮相关的 Trace 监控指标不能携带完整的业务 `taskId`，避免高基数指标标签压垮监控系统。
