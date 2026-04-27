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
3. **阻塞反馈**：当达到最大线程数且队列依然撑爆时，会产生背压（Back-pressure），适度阻塞提交任务的线程，平衡生产与消费速度。

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
| **`INHERIT_THREAD_LOCALS`** | **环境继承**：子线程自动继承父线程的 `FastThreadLocal` 环境。 | 链路参数透传、用户权限上下文传递。 |
| **`THREAD_TRACE`** | **链路追踪**：开启异步 Trace，关联后续的所有异步调用流。 | 复杂异步系统全链路排障。 |

---

## 3. 使用示例

### 3.1 基础提交与分流控制
```java
ThreadPool pool = Tasks.pool();
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
    }, "serial-id", RunFlag.SERIAL.flags());
}
```

### 3.2 批量任务处理 (WaitAll / WaitAny)
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

RXlib 线程池不仅支持上下文传递，还深度集成了异步 Trace 功能，支持跨越 `Executor`、`WheelTimer` 以及 `CompletableFuture.xxAsync()` 的链路追踪。

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

```java
WheelTimer timer = Tasks.timer();

// 设置一个重复执行的任务
timer.setTimeout(() -> {
    log.info("Heartbeat...");
    asyncContinue(true); // 返回 true 表示继续下次循环
}, 1000, "heartbeat-task", TimeoutFlag.PERIOD.flags());
```

---

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
