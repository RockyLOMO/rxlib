# ThreadPool 线程池组件

## 1. 核心设计理念

在传统的 JDK 线程池中，开发者往往面临核心线程数（CoreSize）难以确定的困境：
- **核心线程过少**：无法充分利用多核性能，IO 等待时吞吐量急剧下降。
- **核心线程过多**：上下文切换开销巨大，甚至可能导致系统响应变慢。

RXlib 的 `ThreadPool` 采用**基于负载的自适应动态调整策略**：
> **核心公式**：`最佳线程数 = CPU 线程数 * (1 + CPU 等待时间 / CPU 执行时间)`

### 动态扩缩容逻辑
1. **负载监控**：实时监控 CPU 使用率与任务队列深度。
2. **动态调优**：
   - `app.threadPool.cpuWaterMark` 默认 low 40、high 70。队列非空且 CPU 低于 low，连续 `samplingTimes`（默认 2）次后调整 core。不是队列一满就增加 `maxThreads`。
   - CPU 高于 high 时收缩，避免线程过多。
3. **阻塞反馈**：当达到最大线程数且队列依然撑爆时，会产生背压（Back-pressure）。默认 `BLOCK` 模式保持旧行为，提交线程会等待队列释放 slot；也可以切换为超时拒绝或 caller-runs。

---

## 2. 核心功能特性

### 2.1 多维运行标志 (RunFlag)
`rxlib` 的线程池通过扩展 `RunFlag` 枚举，提供了比原生 JDK 更精细的任务控制流。

| 运行标志 | 功能描述 | 适用场景 |
| :--- | :--- | :--- |
| **`SINGLE`** | **唯一执行**：基于 `taskId` 检查（提交时校验 `taskId != null`）。若已有相同 ID 的任务在运行，则当前任务直接跳过。 | 重复触发的刷新操作、互斥逻辑。 |
| **`SERIAL`** | **串行分发**：基于 `taskId` 独立串行队列与状态屏障按序执行，不阻塞物理线程；支持前项取消/外部提前完成隔离、完整 `RunFlag` 生命周期与同池饱和 trampoline 接力。 | 需要严格顺序处理的会话消息、日志记录。 |
| **`TRANSFER`** | **移交执行**：阻塞提交线程直到任务被工作线程接手（每 500ms 轮询 `isShutdown()`；EventLoop/Timer/同池 worker 自动回退非阻塞入队防死锁）。 | 关键任务流控，防止生产速度失控。 |
| **`PRIORITY`** | **优先执行**：若队列非空则触发 `CpuWatchman` 扩容一次。 | 紧急状态上报、高优监控。 |
| **`INHERIT_FAST_THREAD_LOCALS`** | **环境继承**：提交时刻立即拍下父线程 `FastThreadLocal` 快照并在执行结束后恢复旧 map，避免提交后父线程修改污染子任务。 | 链路参数透传、用户权限上下文传递。 |
| **`THREAD_TRACE`** | **链路追踪**：开启异步 Trace，关联后续的所有异步调用流。 | 复杂异步系统全链路排障。 |

---

## 3. 使用示例

### 3.1 基础提交与分流控制
```java
ThreadPool pool = Tasks.nextPool();
AtomicInteger counter = new AtomicInteger();

// 1. SINGLE 模式：确保同一时间只有一个执行，避免冗余
pool.run(() -> {
    log.info("Do unique task...");
    sleep(1000);
}, "task-unique-id", RunFlag.SINGLE.flags());

// 2. SERIAL 模式：非阻塞串行队列，任务按序执行
for (int i = 0; i < 5; i++) {
    int seq = i;
    pool.runAsync(() -> {
        log.info("Batch seq: {}", seq);
        sleep(500);
        return null;
    }, "serial-id", RunFlag.SERIAL.flags());
}
```

### 3.2 队列背压配置

`ThreadPool.ThreadQueue` 使用 `LinkedTransferQueue + Semaphore` 控制容量。默认配置保留历史阻塞语义：

```yaml
app:
  threadPool:
    queueOfferMode: BLOCK
    queueOfferTimeoutMillis: 0
```

可选模式：

| 模式 | 行为 | 建议场景 |
| :--- | :--- | :--- |
| `BLOCK` | 队列满时一直等待 slot，形成强背压。 | 后台批处理、允许提交线程阻塞的场景。 |
| `TIMEOUT_REJECT` | 队列满后等待 `queueOfferTimeoutMillis`，超时抛 `RejectedExecutionException`。 | Netty EventLoop、低延迟入口、不可无限阻塞的链路。 |
| `CALLER_RUNS` | 队列满且等待超时后由提交线程执行溢出任务。 | 希望快速消化突发但能接受提交线程承担执行成本的场景。 |

高性能网络链路中，Netty `EventLoop` 与 `WheelTimer` 线程在满队列时会自动绕过 `BLOCK` / `CALLER_RUNS`（直接走非核心扩容或快速拒绝），避免 I/O 线程与时间轮线程被业务池阻塞或内联；同时提供显式非阻塞入口 `ThreadPool.tryExecute / trySubmit / tryRunAsync` 与 `Tasks.tryExecute / tryRun / tryRunAsync`。同池 worker 重入提交遇到满队列时亦会绕过阻塞以防 `fixed` 池自死锁。

`CALLER_RUNS` 仅在等待 slot 真正超时且池未关闭时由提交线程执行溢出任务（中断或关闭不再误执行）；执行时走与 worker 线程一致的 `beforeExecute/afterExecute` 生命周期，覆盖 `SINGLE`、`THREAD_TRACE`、`INHERIT_FAST_THREAD_LOCALS` 和 `taskMap` 清理。`INHERIT_FAST_THREAD_LOCALS` 在提交时刻即复制 `InternalThreadLocalMap.indexedVariables` 快照，任务内或父线程后续修改互不污染。

### 3.3 SERIAL 容量与隔离

`SERIAL` 按 `taskId` 使用单一 `taskSerialMap`（`SerialChain`）进行原子容量检查、排队、取消清理与串行派发，不依赖外部 `CompletableFuture` 驱动后继：

```yaml
app:
  threadPool:
    serialQueueCapacity: 4096
    serialQueueHardLimit: 100000
```

- 超过容量会快速抛 `RejectedExecutionException`，避免单个热 key 积压大量待执行节点。`serialQueueHardLimit` 是最终保护上限。
- 取消排队中的中间项或外部提前 `complete` 运行中项的 `Future`，绝不会导致后继任务在前序真正结束前并发启动。
- 链上全部任务均完整执行 `beforeExecute/afterExecute`（`SINGLE`/`PRIORITY`/`THREAD_TRACE`/`INHERIT_FAST_THREAD_LOCALS` 全生效），异常只影响自身 `Future` 不阻断后续；链空后原子清理 `taskSerialMap`。

### 3.4 生命周期与副本热调整语义

`Tasks.executor()` 是全局共享入口，`Tasks.shutdown()` / `Tasks.shutdownNow()` 以及 `Tasks.executor().shutdown()` / `shutdownNow()` 均为 no-op 包装语义，不承担释放底层共享线程池、timer 或 watchman 的职责。副本热调整采用不可变 `activePools` 快照增量扩容，缩容时先切快照再待宽限期与队列/活跃任务排空后才异步 `shutdown()` 旧池；多副本分发使用零锁 O(1) P2C（Power of Two Choices）比较队列深度。

独立创建的 `new ThreadPool(...)` / `ThreadPool.fixed(...)` 自己管理生命周期：`shutdown()` 和 `shutdownNow()` 会先从 `CpuWatchman` 注销，再进入 JDK 线程池关闭流程；关闭后再次提交会抛 `RejectedExecutionException`。

`ThreadQueue` 的 `add()` / `put()` / `offer(r, timeout, unit)` / `iterator().remove()`（含 `purge()`） / `drainTo()` / `clear()` / `shutdownNow()` 均保持 `counter` 与 `availableSlots` 严格一致，`transfer` / `tryTransfer` 显式抛 `UnsupportedOperationException`。

`beforeExecute` 内部失败时会自动回滚已获取的 `SINGLE` 锁、`FastThreadLocal`、trace，并将未启动的 `FutureTaskAdapter` / `AsyncTaskRunnable` / `SerialRunnable` 置为异常完成并推进串行后继。

### 3.5 批量任务处理 (WaitAll / WaitAny)
```java
List<Func<Integer>> tasks = Arrays.asList(
    () -> { sleep(500); return 1; },
    () -> { sleep(200); return 2; }
);

// 异步等待所有结束
ThreadPool.MultiTaskFuture<Void, Integer> mf = pool.runAllAsync(tasks);
mf.getFuture().join(); // 阻塞至全链路结束
```

---

## 4. 全链路异步追踪 (Async Trace)

RXlib 线程池不仅支持上下文传递，还深度集成了异步 Trace 功能，支持跨越 `Executor`、`WheelTimer` 的链路追踪。

无 executor 参数的 `CompletableFuture.xxAsync()` 默认不修改 JDK 全局静态字段（`app.threadPool.patchCompletableFutureAsyncPool=false`）；如需兼容旧行为启用全局 patch，可显式配置为 `true`。新代码建议显式传入 `Tasks.executor()` 或使用 `ThreadPool.runAsync()`。

Spring 管理的 `ThreadPoolTaskExecutor` 会自动套用 trace 任务装饰器；确需使用非 RXlib、非 Spring 管理的原生线程池时，可用 `ThreadPool.Task` 做一次轻量包装：

```java
ExecutorService executor = Executors.newFixedThreadPool(4);
executor.execute((Runnable) ThreadPool.Task.adapt((Runnable) () -> doWork()));
Future<String> future = executor.submit((Callable<String>) ThreadPool.Task.adapt((Callable<String>) () -> query()));
```

```java
// 初始化 Trace 配置
RxConfig.INSTANCE.getThreadPool().setTraceName("rx-traceId");
ThreadPool.traceIdGenerator = () -> UUID.randomUUID().toString().replace("-", "");

// 开启追踪
ThreadPool.startTrace(null);
pool.runAsync(() -> {
    log.info("Step 1 (Main Process)");
    pool.runAsync(() -> {
        log.info("Step 2 (Self-contained callback)");
    });
});
ThreadPool.endTrace();
```

---

## 5. 定时任务调度

### 5.1 增强型 ScheduledExecutorService
相比 JDK 默认固定大小的线程模型，RXlib 的定时线程池支持**自适应 CoreSize 调整**。当定时任务中存在大量阻塞 IO 时，它能伸缩其线程规模，保证其他定时任务不会因前面的阻塞而延期执行。

### 5.2 时间轮算法 (Netty WheelTimer)
RXlib 对 Netty 的 `HashedWheelTimer` 进行了封装，核心点在于：**只做调度，不做执行**。
- **调度效率**：时间轮算法在具有大量定时器时通过单线程调度极其高效。
- **并发执行**：所有触发的任务都会立即异步移交给 `ThreadPool` 执行，避免了传统时间轮“一处任务阻塞，整体调度停滞”的顽疾。
- **关闭语义**：`WheelTimer.shutdown()` 会拒绝新任务，并取消未执行 timeout task、带 taskId 的 holder task 以及 periodic task，同时停止底层 Netty `HashedWheelTimer`；`awaitTermination()` 在底层 timer 已停止且 holder、active timeout、periodic 集合清空后返回 true。更多 shutdown 语义及与外部 executor running task 的边界见 [WheelTimerShutdown.md](./WheelTimerShutdown.md)。

```java
Tasks.schedulePeriod(() -> log.info("Heartbeat..."), 1000);
```

周期任务靠 `TimeoutFlag.PERIOD` 继续，不是靠回调返回值。没有 `asyncContinue`。

---

## 6. 监控指标

`DiagnosticMetrics` 开启后，线程池会输出核心指标：

| 指标 | 含义 |
| :--- | :--- |
| `rx.thread_pool.core.count` | 当前 corePoolSize。 |
| `rx.thread_pool.size.count` | 当前线程池线程数。 |
| `rx.thread_pool.active.count` | 正在执行任务的线程数。 |
| `rx.thread_pool.queue.count` | 当前队列长度。 |
| `rx.thread_pool.queue.capacity` | 队列容量。 |
| `rx.thread_pool.queue.remaining` | 队列剩余 slot。 |
| `rx.thread_pool.completed.count` | 已完成任务数。 |
| `rx.thread_pool.task.rejected.count` | 线程池拒绝任务数。 |
| `rx.thread_pool.queue.offer.block.count` | 提交线程遇到满队列并等待的次数。 |
| `rx.thread_pool.queue.offer.block.millis` | 提交线程累计等待耗时。 |
| `rx.thread_pool.queue.offer.block.max.millis` | 单次最大等待耗时。 |
| `rx.thread_pool.queue.offer.rejected.count` | 队列 offer 超时/中断/关闭拒绝次数。 |
| `rx.thread_pool.queue.offer.caller_runs.count` | caller-runs 溢出执行次数。 |
| `rx.thread_pool.queue.offer.reentrant_bypass.count` | 同池 worker 满队列重入绕过阻塞次数。 |
| `rx.thread_pool.queue.offer.non_blocking_bypass.count` | EventLoop/WheelTimer/非阻塞提交满队列快速拒绝次数。 |
| `rx.thread_pool.serial.chain.count` | 当前串行 taskId 链数量。 |
| `rx.thread_pool.serial.rejected.count` | SERIAL 容量拒绝次数。 |
| `rx.thread_pool.single.skip.count` | SINGLE 重复任务跳过次数。 |
| `rx.thread_pool.config.invalid.count` | 线程池配置解析失败次数，当前用于 `queueOfferMode` 未知值。 |
| `rx.thread_pool.cpu_load.invalid.count` | CPU load 采样为 NaN 或负数时跳过 resize 的次数。 |
| `rx.thread_pool.resize.cooldown.skipped.count` | resize 因 cooldown 被跳过的次数。 |
| `rx.wheel_timer.active.count` | 当前 active timeout task 数量。 |
| `rx.wheel_timer.holder.count` | 当前带 taskId 的 holder 数量。 |
| `rx.wheel_timer.periodic.count` | 当前 periodic task 数量。 |
| `rx.wheel_timer.pending.count` | Netty 时间轮 pending timeout 数量。 |
| `rx.wheel_timer.rejected.count` | WheelTimer 提交目标线程池被饱和拒绝次数。 |

网络项目还必须同时关注 JVM 堆外内存和 Netty allocator 指标，尤其是 direct memory 使用量、连接数、吞吐、P99/P999 延迟、写队列水位与拒绝/降级次数。

## 7. 技术特性总结

- **基于 Netty `FastThreadLocal`**：显著优于 JDK 原生 `ThreadLocal` 的访问性能。
- **自研 `IntWaterMark` 负载算法**：默认水位是 low 40、high 70。
- **无锁化串行逻辑**：`SERIAL` 用 `CompletableFuture` 串起同一 `taskId`。对象池见 [ObjectPool.md](./ObjectPool.md)。`FastThreadLocal` 只在 `minIdleSize <= 0` 时保存 hint，借还仍走共享 idle 队列。
- **背压一致性设计**：通过任务队列和借用超时反馈，天然支持流量整形。
