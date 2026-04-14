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
