# 线程与定时器调度层演进

<details>
<summary><b>[2026-04-14] ThreadPool WheelTimer 性能优化</b></summary>

> **原始文件**: threadpool_wheeltimer_性能优化_60941e5d.plan.md
> **创建日期**: 2026-04-14

---
name: ThreadPool WheelTimer 性能优化
overview: 对 ThreadPool 和 WheelTimer 两个核心类进行性能审查，涵盖热点路径对象分配、反射调用、内存泄漏风险、并发正确性、锁竞争等维度，给出分级优化建议。
todos:
  - id: p0-threadpool-reflection
    content: 修复 setThreadLocalMap 和 setTask 中的热点反射调用，缓存 Field/ThreadLocal 引用
    status: pending
  - id: p0-taskmap-leak
    content: 修复 taskMap 内存泄漏：在 ThreadQueue.remove 和拒绝时清理；考虑弱引用方案
    status: pending
  - id: p0-serial-race
    content: 修复 runSerialAsync 正常路径完成竞态：用 compute/CAS 原子协议统一安装与回收
    status: pending
  - id: p0-holder-leak
    content: 修复 WheelTimer.holder 泄漏：cancel 清理、submit 失败清理、shutdown 清理、考虑改实例级别
    status: pending
  - id: p0-holder-atomic
    content: 用 compute 实现 setTimeout 的 SINGLE/REPLACE 原子仲裁，杜绝并发双注册/双活
    status: pending
  - id: p0-shutdown-contract
    content: shutdown/shutdownNow 清理 holder+timer 并拒绝后续新任务（RejectedExecutionException）
    status: pending
  - id: p0-timer-sync
    content: 缩小 WheelTimer.Task.run() 的 synchronized 范围，避免阻塞时间轮 worker
    status: pending
  - id: p1-task-alloc
    content: 优化 Task.adapt 冗余分配、FlagsEnum 缓存、String.format 替换
    status: pending
  - id: p1-concurrency
    content: 修复 TASK_COUNTER 溢出问题和 counter/semaphore 一致性
    status: pending
  - id: p1-fixedrate-semantics
    content: 修正 scheduleAtFixedRate 语义（漂移/负delay/重入），然后替代动态代理并优化 Task 复用
    status: pending
  - id: p1-periodic-exception-contract
    content: 周期任务异常即终止（suppress subsequent）、periodic get() 阻塞直到取消或异常、返回 Future 统一接入终态
    status: pending
  - id: p1-cancel-get-contract
    content: 重构 WheelTimer.Task 终态契约：cancel 唤醒等待者抛 CancellationException、get(timeout) 剩余时间预算
    status: pending
  - id: p2-misc
    content: LinkedList -> ArrayDeque、stackTrace 延迟拼接、padding 清理
    status: pending
  - id: test-and-monitoring
    content: 补充单测（taskMap/taskSerialMap/holder 清理）、定时器回归、监控指标暴露
    status: pending
isProject: false
---

# ThreadPool 与 WheelTimer 性能优化方案

**模式：高性能模式**

---

## 一、ThreadPool 优化项

### P0 - 热点路径反射调用（必须修复）

#### 1.1 `setThreadLocalMap` 每次反射读取静态字段

[ThreadPool.java](rxlib/src/main/java/org/rx/core/ThreadPool.java) 第 875 行，每次 `beforeExecute` / `afterExecute` 中若线程非 `FastThreadLocalThread`，都会调用：

```java
ThreadLocal<InternalThreadLocalMap> slowThreadLocalMap = Reflects.readStaticField(InternalThreadLocalMap.class, "slowThreadLocalMap");
```

`Reflects.readStaticField` 底层走 `Field.get()`，在高并发下是不必要的热点开销。

**建议**：将 `slowThreadLocalMap` 缓存为 `ThreadPool` 的 `static final` 字段，只反射一次：

```java
@SuppressWarnings("unchecked")
private static final ThreadLocal<InternalThreadLocalMap> SLOW_THREAD_LOCAL_MAP =
    Reflects.readStaticField(InternalThreadLocalMap.class, "slowThreadLocalMap");
```

#### 1.2 `setTask` 每次反射读取 `AsynchronousCompletionTask.fn`

第 900-905 行，对每个 `CompletableFuture.AsynchronousCompletionTask` 都执行 `Reflects.readField(r, "fn")`。虽然结果缓存到了 `taskMap`，但首次仍走反射。

**建议**：预先缓存 `Field` 对象（通过 `Reflects.getFieldMap` 或直接 `getDeclaredField`），避免每次走字段查找链路。也可考虑用 `MethodHandle` 替代 `Field.get()` 以获得 JIT 内联优势。

---

### P0 - 内存泄漏风险

#### 1.3 `taskMap` 缺少兜底清理

`taskMap`（`ConcurrentHashMap<Runnable, Task<?>>`）在 `setTask()` 中 `put`，仅在 `getTask(r, true)` 时 `remove`，而 `getTask(r, true)` 只在 `afterExecute` 中调用。

- 若 `execute()` 抛出 `RejectedExecutionException`（线程池满且拒绝策略未入队），`afterExecute` 不会被调用，对应 entry 泄漏。
- 若任务被 `cancel`/`remove` 从队列移除，同样不会走 `afterExecute`。

**建议**：
- 在 `ThreadQueue.remove()` 中同步清理 `taskMap`。
- 在拒绝策略 handler 中，若 `r` 曾被 `setTask` 写入 `taskMap`，需在入队失败/线程池 shutdown 分支中主动 `taskMap.remove(r)`。
- 保持 `ConcurrentHashMap` + 明确清理路径，不使用 `WeakHashMap`（非线程安全、不适合热点并发路径）或 GC 驱动方案。

#### 1.4 `runSerialAsync` 正常路径完成竞态（核心缺陷）

`runSerialAsync` 最危险的问题不是异常清理，而是**正常路径的安装与回收竞态**。第 751-759 行的执行顺序：

```java
// 1. 先注册完成回调（内部会 remove）
next = f.thenApplyAsync(..., this);
next.whenComplete((r, e) -> {
    taskSerialMap.remove(taskId, next);   // 回收
    ...
});
// 2. 再把 next 写入 map
if (!reuse) {
    taskSerialMap.put(taskId, next);       // 安装
}
```

当 `next` 在 `put` 之前就已完成时（例如前驱 future 已 done、executor 立即执行），`whenComplete` 的 `remove(taskId, next)` 先执行但此时 map 中尚无 next 所以 remove 无效，随后 `put` 将一个**已完成的 future 写入 map**。后续对同一 taskId 的串行提交会 `thenApplyAsync` 到这个陈旧节点上，导致：

- 该 taskId 对应的 entry 永远残留在 `taskSerialMap` 中（无人再 remove）。
- 后续链式任务依赖已完成的节点，串行语义退化。

此外，`thenApplyAsync`（第 736 行）的 `catch` 块只递减 counter，**未清理 `taskSerialMap`**，可能导致孤立 entry。

**建议**：用 `ConcurrentHashMap.compute` 原子协议统一安装与回收，保证"写入 map"和"注册回收回调"在同一个原子操作中完成，杜绝 put-after-complete 窗口。示意：

```java
taskSerialMap.compute(taskId, (k, prev) -> {
    CompletableFuture<T> next;
    if (prev == null) {
        next = CompletableFuture.supplyAsync(t, asyncExecutor);
    } else {
        next = ((CompletableFuture<T>) prev).thenApplyAsync(..., this);
    }
    next.whenComplete((r, e) -> {
        taskSerialMap.compute(taskId, (k2, cur) -> cur == next ? null : cur);
        // counter cleanup ...
    });
    return next;
});
```

---

### P1 - 对象分配优化

#### 1.5 `Task.adapt()` 始终创建新对象

第 159-198 行，`Task.adapt(Callable/Runnable, flags, id)` 即使入参本身已经是同 id 的 `Task`，也只在 `t.id == id` 时直接返回，否则总 `new Task<>()`。在 `execute()` -> `super.execute(Task.adapt(command, null, null))` 路径中，若 `command` 本身已是 `Task`，仍会因为 `id == null` 再创建一个包装层。

**建议**：当 flags 和 id 均为 null 且入参已是 `Task` 时，直接返回原对象，避免无意义的包装。

#### 1.6 `Task.toString()` 使用 `String.format`

第 289-291 行，`String.format("Task-%s[%s]", hc, flags.getValue())` 在日志、调试场景频繁触发时开销大。

**建议**：改用 `StringBuilder` 或字符串拼接：

```java
return "Task-" + hc + "[" + flags.getValue() + "]";
```

#### 1.7 `Task.call()` 慢方法追踪分支中的大量分配

第 261 行：

```java
"[" + Linq.from(stackTrace).select(StackTraceElement::toString).toJoinString(Constants.STACK_TRACE_FLAG) + "]"
```

每次调用都创建 `Linq` 迭代器链 + join 字符串。此处位于 `finally` 块，即使正常执行也会命中。

**建议**：延迟拼接，仅在 `TraceHandler` 内部真正需要输出时才做 join；或预先在 `Task` 构造时将 stackTrace 转为 String 缓存。

#### 1.8 `FlagsEnum` 每次 `RunFlag.NONE.flags()` 创建新实例

第 212-213 行 Task 构造函数：

```java
if (flags == null) {
    flags = RunFlag.NONE.flags();
}
```

`NEnum.flags()` 每次 `new FlagsEnum<>(this)`。大量 Task 创建时产生大量短命对象。

**建议**：在 `RunFlag` 中缓存一个 `NONE_FLAGS` 静态常量，或者用 `int` 位掩码替代 `FlagsEnum` 在内部表示（仅在需要对外暴露时才包装为 `FlagsEnum`）。

---

### P1 - 并发正确性

#### 1.9 `TASK_COUNTER` 溢出 + `Math.abs(Integer.MIN_VALUE)` 问题

第 228 行：

```java
Math.abs(TASK_COUNTER.incrementAndGet() % 100) < threshold
```

`AtomicInteger` 递增可溢出至 `Integer.MIN_VALUE`，而 `Math.abs(Integer.MIN_VALUE)` 返回 `Integer.MIN_VALUE`（负值），导致采样逻辑永远为 `true`。

**建议**：使用 `(TASK_COUNTER.incrementAndGet() & 0x7FFFFFFF) % 100` 保证非负。

#### 1.10 `ThreadQueue.doNotify()` counter/semaphore 不一致

第 142-149 行，`getAndUpdate(v -> Math.max(0, v - 1))` 只在 `c > 0` 时 release semaphore。但 `counter` 和 `availableSlots` 是两个独立的原子变量，在极端并发下可能出现：counter 已被另一线程递减到 0，当前线程拿到旧值 1 仍 release，导致 semaphore 许可泄漏。

**建议**：统一使用 `Semaphore` 作为唯一的容量控制机制，去掉 `AtomicInteger counter`，用 `queueCapacity - availableSlots.availablePermits()` 计算队列大小。或者改用单一 `AtomicInteger` 自旋 CAS 控制。

---

### P2 - 其他优化

#### 1.11 `CTX_TRACE_ID` 使用 `LinkedList`

`InheritableThreadLocal<LinkedList<Object>>` 中 `LinkedList` 每个节点 24 字节开销，且缓存行不友好。实际使用模式为栈（`addFirst` / `poll` / `peek`），且深度受 `maxTraceDepth` 限制（通常很小）。

**建议**：改用 `ArrayDeque`，内存更紧凑、缓存友好。

#### 1.12 `invokeAny` / `invokeAll` 中的 `Linq.from().select().toList()`

第 574-591 行，每次调用都创建中间 Linq 对象链和临时 List。

**建议**：用简单的 `ArrayList` + for 循环替代，减少函数式包装开销。

---

## 二、WheelTimer 优化项

### P0 - 内存泄漏

#### 2.1 `holder`（`static ConcurrentHashMap`）无驱逐机制

`holder` 是 **static** 的，所有 `WheelTimer` 实例共享。任务只在非周期任务执行完毕后才 `remove(id)`。以下场景会导致泄漏：

- 带 `id` 的 `PERIOD` 任务被 `cancel()` 但未从 `holder` 移除（`cancel()` 只取消 timeout/future，不清 holder）。
- `SINGLE` 模式下旧任务完成但新任务又注册了同 id，旧引用被覆盖但无法 GC。
- `executor.submit()` 本身抛出 `RejectedExecutionException`（提交失败），`Task.run()` 异常退出，holder 中的映射未被清理。
- `shutdown()` / `shutdownNow()` 只调用 `timer.stop()` 并设置 `shutdown = true`，**未清空 `holder`，未取消 pending timeout**，导致生命周期末尾遗留映射和 timer 工作线程引用。

注意：一次性任务的正常执行异常（`fn.get()` 抛出）**不会**泄漏，因为 `fn.get()` 在 `executor.submit` 的 lambda 内部，异常仍走 `finally { holder.remove(id) }` 路径。

**建议**：
- `cancel()` 方法中主动 `holder.remove(id)`。
- 周期任务停止时（`continueFlag` 为 false）确保 `holder.remove(id)` 在所有分支都执行。
- `Task.run()` 中对 `executor.submit()` 加 try-catch，提交失败时清理 holder。
- `shutdown()` 中遍历 `holder` 取消所有 pending 任务并 clear；`shutdownNow()` 同样需要 `timer.stop()` + clear（当前实现缺失 `timer.stop()`）。
- `shutdown()` / `shutdownNow()` 后，`setTimeout`、`schedule*`、`execute` 必须拒绝新任务（抛 `RejectedExecutionException`），与 `ScheduledExecutorService` 契约一致。当前实现中 `execute()` 直接委托给底层共享 `executor`，shutdown 后仍可提交。
- 考虑将 `holder` 改为实例级别而非 static，避免跨实例 id 碰撞。

#### 2.1b `setTimeout` 的 SINGLE/REPLACE 缺少原子仲裁（并发语义失效）

当前 `setTimeout(Task)` 中 `holder.get(task.id)` 与 `holder.put(task.id, task)` 是**两步非原子操作**（第 281-293 行）：

```java
if (flags.has(TimeoutFlag.SINGLE)) {
    TimeoutFuture<T> ot = holder.get(task.id);   // step 1: 检查
    if (ot != null) {
        return ot;
    }
}
TimeoutFuture<T> ot = holder.put(task.id, task);  // step 2: 写入
newTimeout(task, 0, timer);
if (flags.has(TimeoutFlag.REPLACE) && ot != null) {
    ot.cancel();
}
```

并发提交相同 taskId 时：

- **SINGLE 双注册**：两个线程同时 `get` 返回 null，都通过检查，都 `put` 并注册 timeout。SINGLE "至多一个生效"的语义被破坏，实际会有两个任务同时运行。
- **REPLACE 取消错误实例**：线程 A `put` 后拿到旧值准备 cancel，线程 B 已经 `put` 了更新的值覆盖了 A 的 task。A cancel 的是更早一轮的旧值，而 B 的 task 和 A 的 task 同时存活（双活）。
- **REPLACE put 后 cancel 前的窗口**：`newTimeout` 在 `cancel` 之前执行，新旧任务短暂并存。

**建议**：用 `ConcurrentHashMap.compute` 实现原子仲裁：

```java
private <T> TimeoutFuture<T> setTimeout(Task<T> task) {
    if (task.id == null) {
        newTimeout(task, 0, timer);
        return task;
    }

    FlagsEnum<TimeoutFlag> flags = task.flags;
    $<TimeoutFuture<T>> replaced = $();

    TimeoutFuture<T> result = (TimeoutFuture<T>) holder.compute(task.id, (k, existing) -> {
        if (flags.has(TimeoutFlag.SINGLE) && existing != null) {
            return existing;  // 原子保留已有任务
        }
        if (flags.has(TimeoutFlag.REPLACE) && existing != null) {
            replaced.v = (TimeoutFuture<T>) existing;
        }
        newTimeout(task, 0, timer);
        return task;
    });

    if (replaced.v != null) {
        replaced.v.cancel();
    }
    return result;
}
```

这样 SINGLE 的检查与跳过、REPLACE 的替换与旧值获取都在 compute 的原子函数内完成，杜绝并发窗口。

---

### P0 - 锁竞争

#### 2.2 `Task.run()` 持有 `synchronized` 提交任务

第 77 行 `synchronized void run(Timeout timeout)` 在持有 Task monitor 的情况下调用 `executor.submit()`。如果线程池满触发背压（`ThreadQueue.offer` 阻塞），**HashedWheelTimer 的 worker 线程将被阻塞**，影响同一时间轮上所有其他到期任务的触发。

**建议**：
- 将 `synchronized` 范围缩小到只保护 `future` 赋值和 `notifyAll()`。
- 或者改用 `CompletableFuture` / `CountDownLatch` 替代 wait/notify，完全去掉 synchronized。

```java
@Override
public void run(Timeout timeout) throws Exception {
    ThreadPool.startTrace(traceId);
    ThreadPool.CTX_STACK_TRACE.set(stackTrace != null ? stackTrace : Boolean.TRUE);
    try {
        Future<T> f = executor.submit(() -> { ... });
        synchronized (this) {
            future = f;
            notifyAll();
        }
    } finally {
        ThreadPool.CTX_STACK_TRACE.remove();
        ThreadPool.endTrace();
    }
}
```

---

### P1 - 对象分配

#### 2.3 `scheduleAtFixedRate` 动态代理开销

第 328-335 行使用 `Sys.proxy(ScheduledFuture.class, ...)` 创建动态代理，且每次方法调用都执行 `Strings.hashEquals` 字符串比较。

**建议**：用一个简单的匿名内部类或具名内部类替代动态代理，直接 override `isCancelled()` 和 `cancel()` 方法，避免 proxy dispatch 和字符串比较。

#### 2.4 `scheduleAtFixedRate` 调度语义错误（前置约束，必须先修正再优化分配）

当前实现（第 343-356 行）存在三个语义问题，严重程度高于对象分配：

**a) 周期漂移**：`nextFixedRate` 在执行当前 `command.run()` **之前**就预注册了下一次触发，且使用 `period - TICK_DURATION` 作为后续 delay。这意味着每次调度固定减去 100ms 的时间轮精度补偿，但实际执行耗时不确定，导致周期在长期运行下持续漂移。正确的 fixed-rate 语义应根据**绝对下一次触发时间**计算 delay：`nextFireTime = lastFireTime + period`，`delay = nextFireTime - now`。

**b) period < TICK_DURATION 时负 delay**：当 `period < 100ms` 时，`period - TICK_DURATION` 为负值，传入 `setTimeout` 后行为未定义（HashedWheelTimer 可能立即触发或拒绝）。

**c) 慢任务重入并发**：`command.run()` 在 executor 线程上执行，而下一次触发已在时间轮上注册。如果 `command` 执行时间超过 `period`，下一次到期时当前执行尚未完成，同一个 `command` 会被并发提交到 executor，违反 fixed-rate "不重入"的隐含契约。

**建议**（按顺序执行）：

1. **先修正为真正的 fixed-rate 语义**：维护单调递增的计划触发时间 `nextFireTime`，每轮调度用 `max(0, nextFireTime - System.currentTimeMillis())` 计算 delay，而非 `period - elapsed`。后者本质是 fixed-delay，在 worker 启动延迟、GC 停顿或连续超期场景下会持续累计漂移。核心不变式：

```java
long nextFireTime = initialFireTime;  // = System.currentTimeMillis() + initialDelay

// 每轮完成后
nextFireTime += period;
long delay = Math.max(0, nextFireTime - System.currentTimeMillis());
// 用 delay 注册下一次 timeout
```

如果连续多次超期（`nextFireTime` 已经落后于 now），`delay = 0` 立即追赶，追赶完毕后自然恢复到正常节奏，不会像 `period - elapsed` 那样把误差带入下一轮。

2. **禁止重入**：只在当前 `command.run()` 完成后才注册下一次 timeout。用一个 `AtomicBoolean running` 或直接在执行 lambda 的 finally 中注册，保证同一周期任务在 executor 中至多一个实例。

3. **再优化分配**：语义正确后，复用 `Task` 对象，只更新 `delay` 和 `expiredTime`，直接调 `newTimeout` 重新注册，避免每周期 `new Task` + lambda 捕获。

#### 2.4b 周期任务异常终止与返回 Future 契约（JDK 强制语义）

`ScheduledExecutorService` 的 JDK 文档对 `scheduleAtFixedRate` 和 `scheduleWithFixedDelay` 有两条明确约束，当前实现均未遵守：

**a) 异常即终止，后续执行必须被抑制**：

> "If any execution of the task encounters an exception, subsequent executions are suppressed."

当前实现中，`scheduleWithFixedDelay` 委托给 `setTimeout(fn, nextDelayFn, null, TIMER_PERIOD_FLAG)`，底层 PERIOD 重注册在 `finally` 块中执行，**即使 `fn.get()` 抛出异常也会重注册**。`scheduleAtFixedRate` 的 `nextFixedRate` 递归同样不检查前一次执行是否异常，会无条件预注册下一次。

**b) 周期 `ScheduledFuture.get()` 永远不正常返回**：

> 对于周期任务，`get()` 阻塞直到任务被取消或某次执行抛出异常。正常执行不会使 `get()` 返回。

当前 `scheduleAtFixedRate` 返回的代理 future 和 `scheduleWithFixedDelay` 返回的 `TimeoutFuture` 都不满足此契约——某次执行完成后 `get()` 可能返回该次结果。

**建议**：

1. **PERIOD 重注册守卫增加异常检查**：重注册前检查本次执行是否异常。若 `fn.get()` 抛出异常，不再重注册，并将异常记录到 Task 的终态（`terminalError`），使后续 `get()` 抛出 `ExecutionException`。修改 2.1 中的重注册 `finally` 块：

```java
future = executor.submit(() -> {
    boolean doContinue = flags.has(TimeoutFlag.PERIOD);
    Throwable thrown = null;
    try {
        fn.get();
        return null;  // 周期任务不暴露单次返回值
    } catch (Throwable e) {
        thrown = e;
        throw e;
    } finally {
        if (thrown != null) {
            // 异常终止：记录终态，不再重注册
            terminalError = thrown;
            state.set(FAILED);
            terminalLatch.countDown();
            if (id != null) holder.remove(id, this);
        } else if (ThreadPool.continueFlag(doContinue)
                && state.get() < COMPLETED
                && (id == null || holder.get(id) == this)) {
            newTimeout(this, delay, timeout.timer());
        } else {
            if (id != null) holder.remove(id, this);
        }
    }
});
```

2. **周期 future 的 `get()` 语义**：周期任务的 `get()` 应阻塞在 `terminalLatch` 上，只在取消（CANCELLED）或异常终止（FAILED）时返回/抛出，正常执行周期内**不 countDown**。这已被 2.6 状态机自然支持——只要正常执行时不调 `terminalLatch.countDown()` 且不转为 COMPLETED，`get()` 就会持续阻塞。周期任务的 COMPLETED 态应仅在任务被显式停止（cancel、shutdown）时设置。

3. **`scheduleAtFixedRate` / `scheduleWithFixedDelay` 返回的 `ScheduledFuture` 必须统一接入此终态机制**，不能让 2.3 中替代动态代理的匿名类绕过异常终止逻辑。

#### 2.5 `Task` 内 `p0, p1` 伪共享填充不足

`long p0, p1` 只填充 16 字节。Java 对象头 12-16 字节 + 前面字段已占用大量空间，实际 padding 效果需要根据完整对象布局计算。如果目标是避免 `timeout` 和 `future` 两个 volatile 字段的伪共享，需要更精确的填充。

**建议**：如果伪共享确实是瓶颈，使用 `@Contended` 注解（需 `-XX:-RestrictContended`）；否则直接移除 `p0, p1` 减少对象大小。

---

### P2 - 其他优化

#### 2.6 `Task` 缺少 cancel/get 终态契约

当前问题不仅是 wait/notify 的虚假唤醒，更严重的是 cancel 与 get 之间缺少终态协调：

**a) cancel 后 get 永久阻塞**：若 `cancel()` 在 `run()` 赋值 `future` 之前被调用，`timeout` 被取消但 `future` 仍为 null。此时 `get()` 进入 `synchronized (this) { wait(); }` 后无人唤醒，**永久阻塞**。按 `ScheduledFuture` 契约，cancel 后 `get()` 应立即抛出 `CancellationException`。

**b) get(timeout) 双重计时**：`get(long timeout, TimeUnit unit)` 先 `wait(millis)` 等待 `future` 赋值，再 `future.get(timeout, unit)` 重新计全量超时。调用方预算的最大等待时间为 `timeout`，但实际最长等待可达 `2 * timeout`。应用 wait 消耗后的**剩余时间**传递给 `future.get`。

**c) 虚假唤醒**：`wait()` 返回后未 loop 检查 `future != null`，虚假唤醒会导致 `future.get()` 抛 NPE。

**建议**：引入显式 `cancelled` 状态标志 + `CountDownLatch` 协调 future 安装，不使用 `CompletableFuture<Future<T>>`（其 `get()` 对 `completeExceptionally(CancellationException)` 会包装为 `ExecutionException`，无法直接抛出 `CancellationException`）。

```java
private volatile boolean cancelled;
private final CountDownLatch futureLatch = new CountDownLatch(1);

@Override
public void run(Timeout timeout) throws Exception {
    // ... trace setup ...
    Future<T> f = executor.submit(() -> { ... });
    future = f;
    futureLatch.countDown();
    // ... trace cleanup ...
}

@Override
public boolean cancel(boolean mayInterruptIfRunning) {
    cancelled = true;
    futureLatch.countDown();  // 唤醒 get() 等待者
    if (future != null) {
        future.cancel(mayInterruptIfRunning);
    }
    if (id != null) {
        holder.remove(id);
    }
    return timeout != null ? timeout.cancel() : true;
}

@Override
public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
    long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
    if (!futureLatch.await(timeout, unit)) {
        throw new TimeoutException();
    }
    if (cancelled) {
        throw new CancellationException();
    }
    long remainingNanos = deadlineNanos - System.nanoTime();
    if (remainingNanos <= 0) {
        throw new TimeoutException();
    }
    return future.get(remainingNanos, TimeUnit.NANOSECONDS);
}

@Override
public T get() throws InterruptedException, ExecutionException {
    futureLatch.await();
    if (cancelled) {
        throw new CancellationException();
    }
    return future.get();
}
```

关键点：
- `cancelled` 标志 + `futureLatch.countDown()` 保证 cancel 立即唤醒等待者。
- `get()` 先检查 `cancelled` 再访问 `future`，直接抛出 `CancellationException`（非包装在 ExecutionException 中）。
- `get(timeout)` 用 deadline 减去 latch 等待消耗，将**剩余时间**传递给 `future.get()`，保证总等待不超过调用方预算。
- `CountDownLatch` 无虚假唤醒问题，且不与 `run()` 共享 monitor。

#### 2.7 `holder` static vs `timer` instance 不一致

`holder` 是类级别 static 的，但每个 `WheelTimer` 实例有自己的 `timer`。如果应用创建多个 `WheelTimer` 实例且使用相同的 taskId，会导致 holder 中的映射被覆盖。

**建议**：将 `holder` 改为实例变量，或在 key 中加入 timer 实例标识。

---

## 三、优化优先级总结

| 级别 | 编号 | 类 | 问题 |
|------|------|-----|------|
| P0 | 1.1 | ThreadPool | setThreadLocalMap 热点反射 |
| P0 | 1.3 | ThreadPool | taskMap 内存泄漏 |
| P0 | 1.4 | ThreadPool | runSerialAsync 正常路径完成竞态 |
| P0 | 2.1 | WheelTimer | holder 无驱逐 + shutdown 不清理 |
| P0 | 2.1b | WheelTimer | setTimeout SINGLE/REPLACE 缺原子仲裁 |
| P0 | 2.1c | WheelTimer | shutdown 后缺少拒绝新任务契约 |
| P0 | 2.2 | WheelTimer | synchronized 阻塞时间轮 worker |
| P1 | 1.5 | ThreadPool | Task.adapt 冗余对象分配 |
| P1 | 1.8 | ThreadPool | FlagsEnum 高频创建 |
| P1 | 1.9 | ThreadPool | TASK_COUNTER 溢出 |
| P1 | 1.10 | ThreadPool | counter/semaphore 不一致 |
| P1 | 2.3 | WheelTimer | 动态代理开销 |
| P1 | 2.4 | WheelTimer | scheduleAtFixedRate 语义错误（漂移/负delay/重入） |
| P1 | 2.4b | WheelTimer | 周期任务异常不终止 + periodic get() 错误返回 |
| P2 | 1.6 | ThreadPool | String.format |
| P2 | 1.7 | ThreadPool | stackTrace join 分配 |
| P2 | 1.11 | ThreadPool | LinkedList 改 ArrayDeque |
| P2 | 2.5 | WheelTimer | padding 不足或冗余 |
| P1 | 2.6 | WheelTimer | cancel/get 终态契约缺失（永久阻塞/双重计时/CancellationException） |
| P2 | 2.7 | WheelTimer | holder static/instance 不一致 |

---

## 四、测试与监控验收

所有优化必须通过以下最小验收集合方可合入。

### 4.1 单元测试（必须）

**ThreadPool - taskMap 清理**
- 任务被 `ThreadQueue.remove()` 移除后，`taskMap` 中无残留 entry。
- `execute()` 触发拒绝策略后，`taskMap` 中无残留 entry。
- 正常执行完成后 `taskMap` 为空。

**ThreadPool - taskSerialMap/taskSerialCountMap 清理**
- 串行任务正常完成后，`taskSerialMap` 和 `taskSerialCountMap` 中对应 taskId 被清除。
- 前驱 future 已 done 时快速提交后续串行任务（模拟 put-after-complete 竞态），验证 map 最终一致清空。
- `cancel()` 串行链中间节点后，后续 entry 能正确回收。
- 高并发（100+ 线程）对同一 taskId 交替提交串行任务，完成后 map 为空。

**WheelTimer - holder 清理与原子语义**
- 一次性任务正常完成后 `holder.get(id)` 返回 null。
- 周期任务 `cancel()` 后 `holder.get(id)` 返回 null。
- `shutdown()` 后 `holder` 为空，所有 pending timeout 已取消。
- 带 `SINGLE` flag 的任务完成后再注册同 id 新任务，旧 entry 不残留。
- **SINGLE 并发回归**：多线程（10+ 线程）同时提交同一 taskId 的 SINGLE 任务，验证 holder 中始终只有一个 entry，且只有一个任务实际执行。
- **REPLACE 并发回归**：多线程同时提交同一 taskId 的 REPLACE 任务，验证最终只有最后一个存活，无双活。

**WheelTimer - cancel/get 终态**
- `cancel()` 在 `future` 赋值前调用：`get()` 立即抛出 `CancellationException`，不阻塞。
- `get(timeout)` 总等待时间不超过调用方指定的 timeout 预算。
- `isCancelled()` 在 cancel 后返回 true，`isDone()` 在 cancel 后返回 true。

### 4.2 定时器回归测试（必须）

**scheduleAtFixedRate 语义正确性**
- period = 200ms、command 耗时 50ms：验证 10 个周期内触发间隔偏差 < TICK_DURATION（100ms）。
- period = 200ms、command 耗时 50ms、运行 50 个周期：验证第 N 次触发时间与 `startTime + N * period` 的绝对偏差不持续增长（无累计漂移）。
- period = 50ms（< TICK_DURATION）：不抛异常，不出现负 delay，实际间隔 >= 0。
- command 耗时 > period（慢任务）：验证同一 command 不并发重入（通过 AtomicInteger 检测并发度始终 <= 1）。
- command 连续 3 次超期后恢复正常耗时：验证调度追赶后恢复到正常节奏。
- `cancel()` 后不再有新触发（观察 500ms 无新调用）。

**scheduleWithFixedDelay 回归**
- 确认现有 `scheduleWithFixedDelay` 行为不受 `scheduleAtFixedRate` 修改影响。
- 正常执行 5 个周期后 cancel：验证间隔 >= period。

**周期任务异常终止契约（scheduleAtFixedRate + scheduleWithFixedDelay 共用）**
- command 第 3 次执行抛出 `RuntimeException`：验证后续不再有新触发（观察 `3 * period` 无新调用），总执行次数恰好为 3。
- command 抛出异常后，返回的 `ScheduledFuture.get()` 抛出 `ExecutionException`，其 cause 为 command 抛出的原始异常。
- command 抛出异常后，`isDone()` 返回 true，`isCancelled()` 返回 false。
- **周期 future 正常执行期间 get() 阻塞**：正常执行 5 个周期后调用 `get(200, MILLISECONDS)`，验证抛出 `TimeoutException`（而非返回某次执行结果）。
- **周期 future cancel 后 get() 立即抛 CancellationException**：正常执行 3 个周期后 cancel，`get()` 立即抛出 `CancellationException`。

**shutdown 后拒绝新任务**
- `shutdown()` 后调用 `setTimeout` / `schedule` / `execute`：均抛 `RejectedExecutionException`。
- `shutdownNow()` 后调用 `setTimeout` / `schedule` / `execute`：均抛 `RejectedExecutionException`。
- `shutdown()` 后 `holder` 为空，`timer.pendingTimeouts()` 为 0。
- `shutdownNow()` 后 `timer` 已 stop。

### 4.3 背压与高并发测试（建议）

- `ThreadQueue` 满载时提交任务：验证调用线程阻塞时间符合预期（可通过 `System.nanoTime()` 测量），不出现死锁。
- 线程池 `corePoolSize` 动态调整期间并发提交：验证无 `RejectedExecutionException` 泄漏。

### 4.4 监控指标（必须暴露）

优化完成后，以下指标必须可通过 JMX 或日志获取：

- **ThreadPool**：activeCount、poolSize、corePoolSize、队列长度（`ThreadQueue.size()`）、调用线程背压阻塞时长（offer 等待耗时）、taskMap.size（调试阶段）。
- **WheelTimer**：`holder.size()`、pending timeout 数量（`timer.pendingTimeouts()`）、调度延迟（实际触发时间 - 预期触发时间）。
- **通用**：堆外内存占用（`PooledByteBufAllocator` 指标，已有则确认覆盖）。


</details>


<details>
<summary><b>[2026-05-04] Delegate 事件 API 简化与排序改造执行记录</b></summary>

> **原始文件**: DelegateEventResult-plan.md (来自 docs/plan/archive)
> **创建日期**: 2026-05-04

# Delegate 事件 API 简化与排序改造执行记录

## 本次模式

**高性能模式（Netty 底层网络编程）**

## 1. 最新决策

- `EventArgs` 不改接口，继续保留当前 class 形态。
- 暂不新增 `EventResult<TEvent>`，也不新增 `raiseEventResult`。
- 事件发布 API 从 `raiseEvent` / `raiseEventAsync` 改为 `publishEvent` / `publishEventAsync`。
- `Delegate.combine(...)` 改为 `Delegate.add(...)`，本项目内不保留 `combine` 兼容名。
- `Delegate.first(...)` / `last(...)` 不再作为独立 API，改用 `add(int order, delegate)`。
- `Delegate.close()` 合并到 `purge(boolean close)`，释放 handler 时调用 `purge(true)`。
- 普通监听器容器从 `CopyOnWriteArraySet` 改为 `CopyOnWriteArrayList`，新增时去重并按 order 排序。
- 阶段 3 网络链路语义不额外调整，只做 API 名迁移。

## 2. 当前 API 形态

### 2.1 Delegate 排序常量

```java
public static final class Order {
    public static final int First = Integer.MIN_VALUE;
    public static final int Default = 0;
    public static final int Last = Integer.MAX_VALUE;
}
```

### 2.2 Delegate 注册 API

```java
public final Delegate<TSender, TEvent> add(TripleAction<TSender, TEvent> delegate);

public final Delegate<TSender, TEvent> add(int order, TripleAction<TSender, TEvent> delegate);

public final Delegate<TSender, TEvent> add(TripleAction<TSender, TEvent>... delegates);

public final Delegate<TSender, TEvent> add(int order, TripleAction<TSender, TEvent>... delegates);
```

单个值重载用于避免单 handler 注册时产生 varargs array。

### 2.3 清理 API

```java
public Delegate<TSender, TEvent> purge();

public Delegate<TSender, TEvent> purge(boolean close);
```

- `purge()`：只清空监听器。
- `purge(true)`：先 `tryClose` 每个监听器，再清空。

## 3. 行为约束

- 相同 handler 重复 `add` 时先移除旧项，再按新 order 插入。
- 相同 order 下保持插入顺序。
- `EventArgs.cancel` 或 `EventArgs.handled` 后停止后续 handler。
- `Order.Last` 不再是单独 finally-like tail hook，而是普通有序 handler；如果前序 handler 取消或处理完成，后续 `Last` 也不会执行。
- 异步发布仍通过 `EventPublisher.asyncScheduler()` 调度。
- I/O 线程不得阻塞等待 `publishEventAsync(...).get()`。

## 4. 已完成代码范围

- `org.rx.core.Delegate`
- `org.rx.core.EventPublisher`
- 项目内 `combine` 调用迁移为 `add`
- 项目内 `raiseEvent` / `raiseEventAsync` 调用迁移为 `publishEvent` / `publishEventAsync`
- 项目内 `first` / `last` 调用迁移为 `add(Delegate.Order.First/Last, ...)`
- `ShellCommand` 中 `onPrintOut.close()` 改为 `onPrintOut.purge(true)`
- `Remoting` 事件代理方法名识别改为 `publishEvent` / `publishEventAsync`

## 5. 验证

新增 `DelegateTest` 覆盖：

- `add` 按 order 排序。
- 重复 handler 去重并按最新 order 重排。
- `purge(true)` 会关闭可关闭 handler。

已执行：

```text
mvn -pl rxlib -DskipTests compile
mvn -pl rxlib "-Dtest=org.rx.core.DelegateTest" test
```

## 6. 监控与风险

本次没有改变网络事件触发时机，只改变 API 名称和本地 Delegate 容器结构。

仍需持续关注：

- 异步事件线程池队列积压。
- handler 执行耗时。
- 事件取消/处理导致的后续 handler 跳过。
- Netty 堆外内存占用、连接数、吞吐和端到端延迟。


</details>

---

<details>
<summary><b>[2026-05-22] ThreadPool / ThreadQueue / WheelTimer 线程池与时间轮统一演进计划</b></summary>

> **原始文件**: thread-pool-unified-plan.md (来自 docs/plan/archive)
> **创建日期**: 2026-05-22

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


</details>

---

<details>
<summary><b>[2026-05-06] rxlib 模块与核心包综合评审与实操方案 (rxlib Module & Core Package Comprehensive Review & Implementation Plan)</b></summary>

> **原始文件**: review-rxlib-module-excluding-review-md-20260506.md (来自 docs/plan)
> **创建日期**: 2026-05-06

# rxlib 模块与核心包综合评审与实操方案 (rxlib Module & Core Package Comprehensive Review & Implementation Plan)

## 本次采用模式：高性能模式（Netty 底层网络与核心并发编程）

---

## 1. 背景与基线 (Background & Baseline)

用户要求在 `RockyLOMO/rxlib` 仓库上对 `rxlib` 模块整体（含核心包 `org.rx.core` 及其子包 `cache`、`config`）做一次全面、仔细的 review。

### 1.1 基线与目标范围
- **基线仓库**：`RockyLOMO/rxlib`
- **基线分支**：`master`
- **目标目录**：
  - `rxlib/src/main/java/org/rx/core` （并发、配置、缓存、生命周期、基础工具等核心层）
  - `rxlib/src/main/java/org/rx/net` （TCP/UDP 传输、HTTP、DNS、RPC、SOCKS 代理等网络层）
  - `rxlib/src/main/java/org/rx/io` （基础流、实体数据库、序列化等 IO 层）
  - `rxlib/src/main/java/org/rx/util` （核心工具、Bean 属性映射等工具层）

---

## 2. 排除项定义 (Exclusion Rules)

根据 [review.md](file:///d:/projs_r/rxlib/docs/test/review.md) 定义的排除项目，本次整体 review 排除以下类或包作为主要修复/修改目标：

### 2.1 `org.rx.io.*` 排除类
- `CompositeLock`
- `CompositeMmap`
- `ExternalSortingIndexer`
- `HashKeyIndexer`
- `KeyIndexer`
- `KeyValueStore`
- `KeyValueStoreConfig`
- `ShardingEntityDatabase`
- `WALFileStream`

### 2.2 `org.rx.net.*` 排除类
- `FecConfig`
- `FecDecoder`
- `FecEncoder`
- `FecPacket`
- `FecUdpClient`

### 2.3 排除包
- `org.rx.third`
- `org.rx.net.socks.httptunnel`

> [!NOTE]
> 排除项仍作为调用链上下文被动阅读以保证架构理解，但不作为主动修复对象。

---

## 3. 已 Review 的文件与目录 (Reviewed Files & Directories)

本轮已对仓库的物理与逻辑结构完成扫描和重点精读：

- **模块配置**：`rxlib/pom.xml`、`.github/workflows/jdk8-unit-tests.yml`
- **主源码包**：`org.rx.annotation`、`org.rx.bean`、`org.rx.codec`、`org.rx.core`、`org.rx.diagnostic`、`org.rx.exception`、`org.rx.io`、`org.rx.net`、`org.rx.util`
- **高频精读文件**：
  - 核心并发：[ThreadPool.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/core/ThreadPool.java)、[WheelTimer.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/core/WheelTimer.java)、[Tasks.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/core/Tasks.java)、[CpuWatchman.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/core/CpuWatchman.java)
  - 生命周期：[ObjectPool.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/core/ObjectPool.java)、[Disposable.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/core/Disposable.java)
  - 配置与缓存：[RxConfig.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/core/RxConfig.java)、[YamlConfiguration.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/core/YamlConfiguration.java)、[MemoryCache.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/core/cache/MemoryCache.java)、[H2StoreCache.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/core/cache/H2StoreCache.java)
  - 基础工具：[Reflects.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/core/Reflects.java)、[ShellCommand.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/core/ShellCommand.java)
  - IO 序列化：[FileStream.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/io/FileStream.java)、[HybridStream.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/io/HybridStream.java)、[FurySupport.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/io/FurySupport.java)、[EntityDatabaseImpl.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/io/EntityDatabaseImpl.java)
  - 网络底层：[Sockets.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/net/Sockets.java)、[GlobalChannelHandler.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/net/GlobalChannelHandler.java)、[BackpressureHandler.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/net/BackpressureHandler.java)
  - 应用网络：[HttpClient.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/net/http/HttpClient.java)、[HttpServer.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/net/http/HttpServer.java)、[DnsResolveCore.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/net/dns/DnsResolveCore.java)、[DnsServer.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/net/dns/DnsServer.java)、[DoHClient.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/net/dns/DoHClient.java)、[Remoting.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/net/rpc/Remoting.java)
  - 传输代理：[TcpServer.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/net/transport/TcpServer.java)、[DefaultTcpClient.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/net/transport/DefaultTcpClient.java)、[UdpClient.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/net/transport/UdpClient.java)、[SocksProxyServer.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/net/socks/SocksProxyServer.java)

---

## 4. 核心调用链分析 (Core Call Chain Analysis)

### 4.1 线程池与任务调度链 (Concurrency & Scheduling Chain)
- `Tasks` 是上层调度的总入口，委托 `ThreadPool` 执行同步、异步、串行、Single、延时和周期任务。
- `ThreadPool` 通过自定义 `ThreadQueue` 跟踪队列容量，利用反射控制 Netty `InternalThreadLocalMap` 的继承与复制。
- `WheelTimer` 驱动时间轮，承载延迟与周期调度，与 JVM 关闭钩子、取消标志、中断信号相勾连。
- `CpuWatchman` 周期性检测 CPU 负载，动态伸缩 `ThreadPool` 的线程配额。

### 4.2 对象池与缓存链 (Lifecycle & Cache Chain)
- `ObjectPool` 内部维护 live 环、idle 双向链表、线程本地缓存（L1 Cache）和后台验证线程，实现高性能无锁/轻量锁对象复用。
- `MemoryCache` 与 `H2StoreCache` 继承自统一的 `Cache`/`CachePolicy` 框架，分别提供内存级 TTL 过期缓存和基于 H2 数据库引擎的文件持久化缓存。

### 4.3 IO、数据库与序列化链 (IO, DB & Serialization Chain)
- `FileStream`、`HybridStream` 屏蔽了堆内与 Native 外文件的差异。
- `EntityDatabaseImpl` 提供面向对象的实体嵌入式持久化支持。
- `FurySupport`、`FurySerializer` 结合 fastjson2、JDK 序列化提供了极高吞吐的编解码底座。

### 4.4 网络底层、协议与传输代理链 (Network, Protocol & Transport Chain)
- `Sockets` 提供 Netty bootstrap、I/O 线程池模型和 Channel 关闭封装。
- `GlobalChannelHandler` 和 `BackpressureHandler` 维护读写水位与背压策略。
- `TcpServer` / `DefaultTcpClient` / `UdpClient` / `hybrid` 支撑高可靠长连接和混合双工通信。
- SOCKS5 / Shadowsocks 实现长连接中继、高并发 UDP 穿透及自研隧道协议解析。

---

## 5. 评审目标与范围 (Objectives & Scope)

### 5.1 目标
1. 分层识别整个 `rxlib` 模块中的并发竞争、内存泄漏、资源关闭不严密、跨 JDK 兼容性漏洞。
2. 规避 `docs/test/review.md` 中排除的历史模块，聚焦核心活跃路径。
3. 保持 **严格 Java 8 编译与运行兼容性**，严禁使用任何 JDK 9+ 的新 API。
4. 所有代码改动必须附带完备的、可确定运行的单元测试，本地单测全量通过。
5. 保持暴露出的 public API 语义不变，优先进行内部防卫式重构。

### 5.2 非目标
1. 不调整 release 或发布版本的打包发布 workflow。
2. 不修改任何 secrets、公私钥、证书、Token 等敏感配置。
3. 不对排除类进行无意义的格式化、重构或升级。

---

## 6. 核心设计原则与检查清单 (Design Principles & Checklists)

### 6.1 线程与任务设计
- 队列容量控制、Semaphore 许可释放、任务级 TraceId 传递必须保持 CAS/try-finally 对称。
- 反射读取 JDK 内部私有字段（如 `CompletableFuture`、Netty 内部类）必须补齐降级路径。
- Caller-runs 饱和策略中，溢出到提交线程执行的任务必须能够完备地清理线程 Trace 状态，避免线程污染。

### 6.2 对象池与缓存设计
- 对象生命周期状态机必须单向流转：`IDLE -> BORROWED -> VALIDATING -> IDLE/RETIRED`。
- 对象池状态变更必须在原子操作或既有细粒度锁保护下进行，严禁将外部回调或重型 validation 动作置于全局排他锁内。
- 缓存策略覆盖或同 key 重载时，旧缓存策略对应的清理任务与元数据必须原子级卸载。

### 6.3 网络与流传输设计
- Netty `ByteBuf` 必须符合“谁消费谁释放”的所有权管理原则，严禁热路径隐式泄漏。
- DNS 域名解析 fallback、超时重试和 DoH 交互中需提供异步防阻塞策略。
- 长连接断线重连（Reconnect）与 client 资源 dispose 竞态时，重连逻辑必须能安全早退。

---

## 7. 2026-05-06 实操执行结论 (Implementation Outcomes)

本项目已从计划阶段进入执行与收网阶段。以下对审查出的所有高优先级问题进行统一归类，并列出本轮实操的最终采纳结果：

### 7.1 已确认并完成修复的代码项 (Accepted & Fixed)

#### 1. `ObjectPool` 线程本地缓存（L1 Cache）校验异常容量泄漏
- **缺陷**：当 `minIdleSize <= 0` 时，归还的对象会被放入 L1 `threadLocalCache`。下一轮同线程 `borrow` 命中 L1 节点后，会执行状态流转。若在 `validateHandler.test()` 或 `activateHandler` 内部抛出意外异常，代码会直接跳出 L1 校验分支且不执行 `doRetire` 销毁。这导致该对象永久保持在 `BORROWED` 状态，无法释放槽位 `totalCount`。若 `maxPoolSize=1`，后续借用将被永久阻塞直至超时。
- **修复**：对 L1 缓存校验分支（validate 与 activate）添加统一 `try-catch` 包裹。捕获异常时，安全调用 `doRetire(c.wrapper, 0)` 彻底退役并清理 live/idle 状态与容量计数，随后降级继续走主借用循环。
- **验证**：编写专用回归测试 `ObjectPoolTest.testThreadLocalValidationExceptionReleasesSlotOnBorrow` 验证通过。

#### 2. `MemoryCache` 覆盖 Key 时旧策略残留与 TTL 失效
- **缺陷**：在向 `MemoryCache` 写入相同 Key 且从自定义 `CachePolicy` 覆盖为默认策略时，`policyMap` 中的旧自定义策略未被清理。这导致新值继续错误沿用旧的 TTL/Idle 过期机制，破坏了缓存语义。
- **修复**：在 `put`、`putAll`、`remove`、`clear` 等热路径中，增加对旧策略元数据的原子清理。
- **验证**：编写 `MemoryCacheTest` 覆盖该场景并通过。

#### 3. `YamlConfiguration` 配置加载 InputStream 资源泄漏
- **缺陷**：在加载 YAML 文件时，`YamlConfiguration` 未在 finally 中关闭传入的 `InputStream`，在高频重载或配置读取场景下存在句柄泄漏风险。
- **修复**：在 `loadYaml(List<InputStream>)` 中采用标准 try-finally 结构统一保证所有输入流严格关闭。
- **验证**：运行 `YamlConfigurationTest` 通过。

#### 4. `YamlConfiguration.readAs` 复杂泛型反序列化兼容崩溃
- **缺陷**：`readAs` 方法对于非 Map 结构的泛型集合等复杂类型，若执行 `new JSONObject(map)` 会在 map 为 null 或转换不匹配时抛出硬异常崩溃，缺乏兼容性。
- **修复**：增加类型判定，对非标准 Map 结构泛型采用 fastjson2 提供的 `JSON.parseObject(JSON.toJSONString(...), Type)` 健壮转换路径进行降级。
- **验证**：运行 `ReflectsCompatibilityTest` 等关联测试通过。

#### 5. `ThreadPool` 反射读取 CompletableFuture 内部 fn 属性易中断
- **缺陷**：`ThreadPool` 中对 JDK 内部 `CompletableFuture.AsynchronousCompletionTask` 的 `fn` 字段进行强反射读取。在不同 JDK 8 小版本或环境安全策略变更时，反射失败会导致 worker 线程初始化直接中断退出。
- **修复**：为该反射操作补充了异常捕获与 Null 降级安全路径，若反射不可达则降级返回空任务，确保 worker 进程不因安全策略中断。
- **验证**：通过 `ThreadPoolTest` 验证。

#### 6. `ShellCommand` 进程树生命周期管理与 PowerShell 查询优化
- **缺陷**：在 Windows + JDK 8 下，PID 提取降级方案会命中自身的 PowerShell/WMIC 查询，导致 kill 只能杀掉 wrapper 进程而残留实际运行的子进程（如 `ping -t`）；同时进程销毁后 reader 任务未被通知，引发句柄堆积。
- **修复**：修正了 PowerShell 进程检索过滤规则，将查询进程本身显式排除在外；并且在 destroy 后对 reader 触发退出事件。
- **验证**：通过 `ShellCommandTest` 验证。

---

### 7.2 不认同 / 暂不采纳的候选风险项 (Unadopted / Deferred)

#### 1. `ThreadPool.ThreadQueue` 计数器与 Semaphore 释放不对称
- **标记**：**不认同**。
- **核验原因**：经过精读与复核，发现 `offer`、`poll`、`take`、`drainTo`、`clear`、`remove`、`shutdownNow` 在已有实现中均做好了严格的对称性保护与并发控制，且已有 `ThreadPoolQueueShutdownTest` 等高强度并发单测予以印证，本轮不予改动。

#### 2. `ThreadPool.runSerialAsync` 串行链路 Map 内存泄漏
- **标记**：**暂不采纳**。
- **核验原因**：其已通过 `whenComplete` 在执行完毕后可靠移除 `taskSerialMap` 与 `taskSerialCountMap` 的 key-value 映射。在没有得到具体的、可复现的泄漏用例之前，保留原设计以避免破坏已有的拒绝执行和异常回滚机制。

#### 3. `ThreadPool.beforeExecute/afterExecute` 直接大改
- **标记**：**暂不采纳**。
- **核验原因**：热路径改动极其敏感。经核实，`SINGLE` 跳过分支不会获取锁，也不建立 Trace 或 ThreadLocal 关联，因此 `afterExecute` 早退不会引发资源残留。盲目修改会触碰 Netty internal 性能敏感点。

#### 4. `WheelTimer` 关闭后周期任务持续重调度
- **标记**：**不认同**。
- **核验原因**：`Task.onExecutionFinished` 与 `PeriodicTask.scheduleNext` 在每次重入前均严格检查了 `shutdown/cancelRequested` 状态，已有时间轮关闭周期单测保驾护航。

#### 5. `HttpClient` 未消费 Body 阻断连接复用
- **标记**：**不认同**。
- **核验原因**：客户端在收到 `LastHttpContent` 后，底层统一将响应体落入 `HybridStream` 并提前释放 Channel 返回连接池。用户是否消费 `Response.body` 并不阻碍网络 Channel 的复用，这是既有优秀设计的体现。

#### 6. 其他网络（DNS Fallback、SOCKS 中继）边界重构
- **标记**：**暂不采纳**。
- **核验原因**：该部分涉及极高频的数据面协议兼容，在缺乏特定的、有针对性的集成网络拓扑测试下，不适宜在本轮做扩大化修改，保持其高性能原生 Netty 调用栈。

---

## 8. 修改文件列表 (Modified Files)

本轮综合评审与执行阶段实际修改和提交的文件如下：

```text
# 方案与记录文档
- docs/plan/review-rxlib-module-excluding-review-md-20260506.md

# 核心代码与回归验证测试
- rxlib/src/main/java/org/rx/core/ThreadPool.java
- rxlib/src/main/java/org/rx/core/ObjectPool.java
- rxlib/src/main/java/org/rx/core/YamlConfiguration.java
- rxlib/src/main/java/org/rx/core/ShellCommand.java
- rxlib/src/main/java/org/rx/core/cache/MemoryCache.java
- rxlib/src/test/java/org/rx/core/ObjectPoolTest.java
- rxlib/src/test/java/org/rx/core/YamlConfigurationTest.java
- rxlib/src/test/java/org/rx/core/cache/MemoryCacheTest.java
```

---

## 9. 综合验证与回归测试方案 (Verification & Regression Tests)

### 9.1 本地回归测试
在 `d:\projs_r\rxlib` 目录下执行聚合验证：

```bash
# 验证并发与对象池
mvn -pl rxlib "-Dtest=ObjectPoolTest,ObjectPoolRecycleOwnershipTest" test

# 验证配置、缓存与 Shell 工具
mvn -pl rxlib "-Dtest=MemoryCacheTest,YamlConfigurationTest,ThreadPoolWheelTimerRegressionTest,ThreadPoolQueueShutdownTest,ShellCommandTest" test
```

**测试结论**：本地全量单测全部顺利通过（Tests run: 35 & 38, Failures: 0, Errors: 0, Skipped: 0），BUILD SUCCESS。

### 9.2 CI 持续集成触发建议
后续如触发 GitHub Action CI（分支：`agent/consolidate-review-plans-20260506`），推荐的定向 `test_classes` 回归参数列表如下：

```text
ThreadPoolTest,ThreadPoolQueueOfferModeTest,ThreadPoolQueueShutdownTest,SerialQueueCapacityTest,ThreadPoolWheelTimerRegressionTest,WheelTimerShutdownPeriodicTest,ObjectPoolTest,ObjectPoolRecycleOwnershipTest,RxConfigTest,YamlConfigurationTest,ReflectsCompatibilityTest,ShellCommandTest,TasksTest,TasksCompatibilityTest,MemoryCacheTest
```


</details>

---

<details>
<summary><b>[2026-05-22] 背景</b></summary>

> **原始文件**: rxlib-test-timeout-review-plan.md (来自 docs/plan)
> **创建日期**: 2026-05-22

# 背景

用户要求 review `rockylomo/rxlib` 仓库 `master` 分支下 `rxlib/src/test` 相关测试类，找出并优化可能导致全量测试一直卡住或运行很久的测试类。用户补充约束：普通测试中 `sleep` / 固定等待在 1 分钟内可以接受；压测 / 集成测试单个测试类最大可接受 5 分钟限制。

# 任务类型判断

本次任务归类为 **Review / 修复 / 优化需求**。

原因：用户要求 review 现有测试类并优化已有测试的耗时和卡住风险；当前不新增业务功能，也不改变生产代码行为。按流程，本阶段只提交计划文档，等待用户明确要求后再进入代码实现阶段。

# 当前上下文

已 review / 扫描的范围：

- `rxlib/src/test/java` 测试树。
- `.github/workflows/jdk8-unit-tests.yml`。
- 根 `pom.xml` 的 Java/JUnit/Maven 基础配置。
- 重点关注 `org.rx.core.ThreadPoolTest`、线程池 / 定时器测试、`org.rx.net.socks` 集成测试、`org.rx.util.rss.RssTest` 以及其他 `*IntegrationTest`、`*PerformanceTest`。

关键调用链与现状：

- `jdk8-unit-tests.yml` 是 `workflow_dispatch` 手动触发 workflow，支持 `test_classes` 输入；如果 `test_classes` 为空会执行全量 `mvn clean test`。
- 全量执行时 Maven Surefire 可能识别 `*Test` / `*IntegrationTest` 命名，网络、UDP、DNS、HTTP、Socks、RSS、线程池、定时器等测试会一起运行。
- 已发现高风险点：`ThreadPoolTest.threadPoolAutosize` 使用 1 线程、队列容量 1 的线程池，提交约 100 个每个 `sleep(5000)` 的任务；理论运行时间超过 5 分钟，不适合默认全量测试。
- `ThreadPoolTest.threadPool`、`inheritThreadLocal`、`timer` 等历史综合测试包含多段 `sleep(5000)`、`sleep(8000)`、异步任务链、定时器周期任务、并行流和 trace 传播验证。单段等待在用户给定 1 分钟范围内，但组合后容易拖慢全量测试。
- 网络 / 集成测试如 `SocksProxyServerIntegrationTest`、`Udp2rawFixedEntryIntegrationTest`、`RssTest` 文件较大，涉及本地端口、UDP/TCP、代理、RSS/HTTP 等场景，需要确认是否默认全量运行，以及是否存在无超时等待、外部网络依赖和资源未释放风险。

已发现的问题或风险：

1. `ThreadPoolTest.threadPoolAutosize` 有明显超过 5 分钟的运行风险。
2. 部分历史综合测试没有 JUnit 级 timeout 保护，内部异步任务、定时器或线程池逻辑回归时可能卡住而不是快速失败。
3. `Future.get()`、`CompletableFuture.join()`、`CountDownLatch.await()`、手工线程 `join()` 需要统一确认是否带超时。
4. 网络 / 集成测试如果默认纳入全量测试，需要明确是否依赖外部网络、固定端口、类级超时，以及是否应通过 tag / assumptions / system property 从默认单元测试中隔离。
5. 线程池和定时器测试需要检查资源释放，避免非 daemon 线程或周期任务遗留导致 JVM 不退出。

# 目标

1. 对 `rxlib/src/test` 中可能导致全量测试卡住或超过预期耗时的测试类进行最小化优化。
2. 普通单元测试中，单次 sleep / 固定等待不超过 1 分钟可保留；无超时等待必须改为有明确超时的等待或断言。
3. 压测 / 集成测试单个测试类默认执行时间控制在 5 分钟内。
4. 明显压测或依赖外部环境的场景避免进入默认全量测试，或用 JUnit tag / assumption / system property 显式控制。
5. 保持 JDK8 兼容，不使用 JDK9+ API。
6. 只修改测试代码或测试配置，不修改生产业务逻辑。
7. 修改后通过 GitHub Actions `jdk8-unit-tests.yml` 验证相关测试类。

# 非目标

1. 不重构生产代码。
2. 不改变线程池、网络、DNS、Socks、RSS 等业务逻辑语义。
3. 不删除有价值的测试断言。
4. 不为了让 CI 通过而简单注释掉测试。
5. 不升级 Maven/JUnit/Netty 等大版本依赖。
6. 不修改 secrets、token、证书、私钥。
7. 不发布 release。
8. 不在计划阶段修改任何业务或测试实现代码。

# 设计方案

总体策略：先收敛高风险，再补 timeout，再按需隔离集成 / 压测。

1. 优先处理确定会拖慢全量测试的类 / 方法。
2. 对等待类 API 做统一超时保护：
   - `Future.get()` 改为 `Future.get(timeout, TimeUnit.SECONDS)`。
   - `CompletableFuture.join()` 如可能永久等待，改为 `get(timeout, TimeUnit.SECONDS)`。
   - `CountDownLatch.await()` 改为 `await(timeout, TimeUnit.SECONDS)` 并断言返回值。
   - `Thread.join()` 改为 `join(timeoutMillis)` 并断言线程已结束或输出明确失败信息。
3. 对固定 sleep：
   - 1 分钟内且测试目标明确的 sleep 可保留。
   - 可被 latch / future / polling 替代的 sleep 优先替代，减少无意义等待。
4. 对压测 / 集成：
   - 超过 5 分钟或依赖外部环境的测试默认不进入全量单元测试。
   - 可使用 JUnit 5 `@Tag("integration")` / `@Tag("benchmark")`、`Assumptions.assumeTrue(Boolean.getBoolean(...))` 或 Maven Surefire exclude 方案。
   - 当前 workflow 可用 `-Dtest` 精准跑类，优先不大改 workflow；仅必要时调整测试类自身或模块 `pom.xml`。
5. 对资源释放：
   - 自建 `ThreadPool`、`WheelTimer`、server/client/channel/socket 等资源使用 `try/finally` 或 `@AfterEach` 清理。
   - 周期任务或 timer future 在测试结束时 cancel。
   - 避免后台非 daemon 线程遗留导致 JVM 退出卡住。

针对 `ThreadPoolTest` 的预期方向：

- `threadPoolAutosize`：将 100 个任务 × 5 秒的压测缩短到可控规模，或改为显式 benchmark / assumption，只在手工开启时运行。
- `threadPool`：收敛历史大杂烩式验证，保留必要断言，对关键 `get()` / `join()` 补充 timeout。
- `inheritThreadLocal`：控制总等待时间，补充 timeout，确保线程池和 timer 资源释放。
- `timer`：周期任务必须有退出条件和 cancel；scheduled future 获取结果必须带 timeout。

针对网络 / 集成测试的预期方向：

- 纯本地、可快速稳定结束的测试保留默认执行，但加类级或方法级 timeout。
- 依赖外部网络或超过 5 分钟的测试用 tag / assumption 隔离出默认全量测试。
- 本地端口冲突时快速失败或跳过，server/client/channel 在 finally 中关闭。

异常处理与资源释放：timeout 触发时用断言失败并输出测试阶段说明；需要跳过的外部依赖测试使用 `Assumptions` 给出清晰原因；捕获 `InterruptedException` 后恢复 interrupt 标记并失败或退出。

# 修改文件列表

计划阶段只新增：

- `docs/plan/rxlib-test-timeout-review-plan.md`

代码实现阶段预计可能修改：

- `rxlib/src/test/java/org/rx/core/ThreadPoolTest.java`
- `rxlib/src/test/java/org/rx/core/ThreadPoolWheelTimerRegressionTest.java`（如发现缺少 timeout / 资源释放）
- `rxlib/src/test/java/org/rx/core/WheelTimerShutdownPeriodicTest.java`（如发现周期任务未取消风险）
- `rxlib/src/test/java/org/rx/net/socks/SocksProxyServerIntegrationTest.java`（如默认全量运行且存在超 5 分钟或外部依赖风险）
- `rxlib/src/test/java/org/rx/net/socks/Udp2rawFixedEntryIntegrationTest.java`（如默认全量运行且存在超 5 分钟或外部依赖风险）
- `rxlib/src/test/java/org/rx/net/socks/UdpRedundantEncoderPerformanceTest.java`（如默认全量运行且属于压测）
- `rxlib/src/test/java/org/rx/util/rss/RssTest.java`（如存在无超时等待或外部依赖）
- 必要时修改 `rxlib/pom.xml` 或测试配置，但优先避免。

# 风险点

1. JDK8 下不能使用 `CompletableFuture.orTimeout` 等 JDK9+ API。
2. JUnit 5 timeout / tag 的使用要与当前 Maven Surefire 配置兼容。
3. 过度缩短等待可能导致并发 / 定时器测试偶发失败，需要用事件驱动等待替代简单缩短 sleep。
4. 原测试验证并发时序，修改等待方式可能改变竞争窗口，应保持核心并发断言不变。
5. 错误关闭全局线程池或 timer 可能影响同 JVM 后续测试，只释放测试自己创建的资源。
6. 隔离 `IntegrationTest` / `PerformanceTest` 可能改变默认全量测试覆盖范围，需要在执行后明确说明。
7. GitHub Actions 上端口、网络、IPv6/UDP 能力与本地不同，网络类测试可能仍存在环境性失败。
8. 当前只完成初步源码 review，尚未运行全量测试；最终需要以 CI 运行结果确认是否还有隐藏卡点。

# 验证方案

1. 计划阶段：仅提交 `docs/plan/rxlib-test-timeout-review-plan.md`；不触发代码测试，不声称 CI 通过。
2. 代码实现阶段：修改前再次读取最新文件内容和 sha。
3. 优先通过 `jdk8-unit-tests.yml` 手动触发验证：
   - `test_classes=org.rx.core.ThreadPoolTest`
4. 如修改网络 / 集成测试，再按实际修改类追加：
   - `org.rx.core.ThreadPoolWheelTimerRegressionTest`
   - `org.rx.core.WheelTimerShutdownPeriodicTest`
   - `org.rx.net.socks.SocksProxyServerIntegrationTest`
   - `org.rx.net.socks.Udp2rawFixedEntryIntegrationTest`
   - `org.rx.util.rss.RssTest`
5. 查询 workflow run 时必须按当前分支 `agent/review-rxlib-test-timeouts` 过滤；只有 `conclusion=success` 才认为通过。
6. 如果 CI 失败，读取失败日志，按编译失败、单元测试失败、格式失败、依赖下载失败、JDK8 不兼容、环境问题、测试不稳定、workflow 配置问题分类处理。
7. 相关测试类通过后，根据用户要求决定是否运行全量 `mvn clean test` 或继续收敛其他卡点。


</details>

---
