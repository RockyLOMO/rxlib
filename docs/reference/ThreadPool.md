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
   - 当队列已满且 **CPU 使用率 < 40%** 时，自动分批增加 `maxThreads` 以提升并发处理能力。
   - 当任务积压减少或 **CPU 使用率 > 60%** 时，自动收缩线程数以减少系统负荷，防止过度竞争。
3. **阻塞反馈**：当达到最大线程数且队列依然撑爆时，会产生背压（Back-pressure）。默认 `BLOCK` 模式保持旧行为，提交线程会等待队列释放 slot；也可以切换为超时拒绝或 caller-runs。

---

## 2. 核心功能特性

### 2.1 多维运行标志 (RunFlag)
`rxlib` 的线程池通过扩展 `RunFlag` 枚举，提供了比原生 JDK 更精细的任务控制流。

| 运行标志 | 功能描述 | 适用场景 |
| :--- | :--- | :--- |
| **`SINGLE`** | **唯一执行**：基于 `taskId` 检查。若已有相同 ID 的任务在运行，则当前任务直接跳过。 | 重复触发的刷新操作、互斥逻辑。 |
| **`SERIAL`** | **串行分发**：基于 `taskId` 进行串行队列化。采用无锁 `CompletableFuture` 生成任务链，不会阻塞物理线程。 | 需要严格顺序处理的会话消息、日志记录。 |
| **`TRANSFER`** | **移交执行**：阻塞提交线程，直到任务被工作线程接手或成功存入队列。 | 关键任务流控，防止生产速度失控。 |
| **`PRIORITY`** | **优先执行**：若当前无空闲线程且队列已满，强制新建一个临时线程处理。 | 紧急状态上报、高优监控。 |
| **`INHERIT_FAST_THREAD_LOCALS`** | **环境继承**：任务执行时复制父线程 `FastThreadLocal` 环境，结束后恢复旧 map。 | 链路参数透传、用户权限上下文传递。 |
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

高性能网络链路中，避免在 Netty `EventLoop` 上使用可能无限阻塞的提交路径；如果必须从 I/O 线程提交任务，优先配置 `TIMEOUT_REJECT` 并在上层做限流、降级或丢弃策略。

`CALLER_RUNS` 会把执行成本转移到提交线程；当前实现会走与 worker 线程一致的 `beforeExecute/afterExecute` 生命周期，覆盖 `SINGLE`、`THREAD_TRACE`、`INHERIT_FAST_THREAD_LOCALS` 和 `taskMap` 清理。`INHERIT_FAST_THREAD_LOCALS` 使用复制后的 `InternalThreadLocalMap.indexedVariables` 执行，任务内修改 `FastThreadLocal` 不会污染提交线程后续上下文。

### 3.3 SERIAL 容量

`SERIAL` 按 `taskId` 使用 `CompletableFuture` 链串行，不占用工作线程等待。每个 `taskId` 的链长度受以下配置限制：

```yaml
app:
  threadPool:
    serialQueueCapacity: 4096
    serialQueueHardLimit: 100000
```

超过容量会快速抛 `RejectedExecutionException`，避免单个热 key 积压大量 `CompletableFuture` 对象。`serialQueueHardLimit` 是最终保护上限，`serialQueueCapacity` 不会超过该值。

单个 `SERIAL` 任务异常只影响自己的 `Future`，不会阻断后续同 `taskId` 任务；链尾完成后会清理 `taskSerialMap / taskSerialCountMap`。

### 3.4 生命周期语义

`Tasks.executor()` 是全局共享入口，`Tasks.shutdown()` / `Tasks.shutdownNow()` 以及 `Tasks.executor().shutdown()` / `shutdownNow()` 均为 no-op 包装语义，不承担释放底层共享线程池、timer 或 watchman 的职责。

独立创建的 `new ThreadPool(...)` / `ThreadPool.fixed(...)` 自己管理生命周期：`shutdown()` 和 `shutdownNow()` 会先从 `CpuWatchman` 注销，再进入 JDK 线程池关闭流程。

`ThreadQueue` 的 `drainTo()` / `clear()` / `shutdownNow()` 路径会按实际移除数量释放 slot，并清理未执行任务的 `taskMap` 映射，避免 `counter` 与 `availableSlots` 不一致。

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

无 executor 参数的 `CompletableFuture.xxAsync()` 默认仍使用 JDK 默认 async pool。RXlib 可以通过 `app.threadPool.patchCompletableFutureAsyncPool=true` 兼容旧行为，但该能力会修改 JDK 全局静态字段，默认关闭；新代码应显式传入 `Tasks.executor()` 或使用 `ThreadPool.runAsync()`。

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
- **关闭语义**：`WheelTimer.shutdown()` 会拒绝新任务，并取消未执行 timeout task、带 taskId 的 holder task 以及 periodic task；`awaitTermination()` 在 holder、active timeout 和 periodic 集合清空后返回 true。

```java
WheelTimer timer = Tasks.timer();

// 设置一个重复执行的任务
timer.setTimeout(() -> {
    log.info("Heartbeat...");
    asyncContinue(true); // 返回 true 表示继续下次循环
}, 1000, "heartbeat-task", TimeoutFlag.PERIOD.flags());
```

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
| `rx.thread_pool.queue.offer.rejected.count` | 队列 offer 超时拒绝次数。 |
| `rx.thread_pool.queue.offer.caller_runs.count` | caller-runs 溢出执行次数。 |
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

网络项目还必须同时关注 JVM 堆外内存和 Netty allocator 指标，尤其是 direct memory 使用量、连接数、吞吐、P99/P999 延迟、写队列水位与拒绝/降级次数。

## 7. Object Pool 对象池

`rxlib` 提供了一个高性能、自适应的通用对象池实现 (`ObjectPool<T>`)，旨在解决高并发场景下频繁创建/销毁对象带来的 GC 压力与锁竞争。

### 7.1 核心设计

*   **L1 级线程本地缓存**：
    利用 `FastThreadLocal` 实现 L1 级缓存。当同一线程频繁 `borrow` / `recycle` 时，优先从本线程私有的缓存位获取，**完全无锁**且避免了对全局队列的竞争。
*   **自适应预填充 (Adaptive Refill)**：
    池化组件会定期采样借用频率。当负载升高时，它会根据 `demandFactor` 自动计算并预创建对象（暖机）；当负载下降时，自动回收空闲对象。
*   **无锁状态管理**：
    对象的 `IDLE`、`BORROWED`、`RETIRED` 状态切换全部通过 **CAS (Compare-And-Swap)** 实现，最大程度减少上下文切换。
*   **健康检测机制**：
    *   **校验（Validation）**：借出前、归还后均可进行活性检查。
    *   **泄漏检测（Leak Detection）**：定时扫描借出超时的对象并记录堆栈，帮助定位未归还对象的代码块。

### 7.2 技术指标

| 特性 | 实现方式 | 优势 |
| :--- | :--- | :--- |
| **高并发性能** | FastThreadLocal L1 + 无锁 CAS | 借还操作延迟极低，热点数据访问基本无竞争。 |
| **负载适应度** | 滑动窗口采样 + 动态预填充 | 流量洪峰来临时减少现场创建对象的开销。 |
| **内存安全性** | 自动状态回收 + 泄漏堆栈追踪 | 配合 `TraceHandler` 能够快速发现资源泄漏。 |

### 7.3 使用示例

```java
// 初始化对象池：最小 2，最大 20
ObjectPool<MyClient> pool = new ObjectPool<>(2, 20,
    () -> new MyClient(),      // createHandler
    client -> client.isAlive() // validateHandler
);

// 借用对象
MyClient client = pool.borrow();
try {
    client.execute();
} finally {
    // 归还对象
    pool.recycle(client);
}
```

---

## 8. 技术特性总结

- **基于 Netty `FastThreadLocal`**：显著优于 JDK 原生 `ThreadLocal` 的访问性能。
- **自研 `IntWaterMark` 负载算法**：精准控制核心线程规模。
- **无锁化串行逻辑**：ThreadPool 采用 `CompletableFuture` 无锁链，ObjectPool 采用 CAS 状态机，共同构建了高性能的并发基石。
- **背压一致性设计**：通过任务队列和借用超时反馈，天然支持流量整形。
