# WheelTimer Shutdown 语义说明

> 适用范围：`org.rx.core.WheelTimer` 以及通过 `Tasks.timer()` 暴露的定时调度能力。  
> JDK 兼容性：JDK8。

## 1. 生命周期归属

`WheelTimer` 封装了 Netty `HashedWheelTimer`，并将到期后的用户任务提交到外部 `ExecutorService` 执行。

因此它的资源边界分成两层：

1. **WheelTimer 自身拥有的资源**
   - 底层 Netty `HashedWheelTimer`。
   - `holder` 中带 `taskId` 的 timeout future。
   - `activeTasks` 中仍被 timer 跟踪的 timeout task。
   - `periodicTasks` 中的周期任务。

2. **外部 executor 拥有的资源**
   - 已经提交到 `executor` 并开始运行的用户任务。
   - executor 的工作线程生命周期。

`WheelTimer.shutdown()` / `shutdownNow()` 只管理第一类资源，不负责关闭外部 executor。

## 2. shutdown()

`shutdown()` 的语义是有序关闭：

- 拒绝后续新任务。
- 取消尚未执行的 timeout task。
- 取消带 `taskId` 的 holder task。
- 取消 periodic task。
- 停止底层 Netty `HashedWheelTimer`。
- 对已经提交到外部 executor 且已经开始运行的用户任务，使用 `cancel(false)`，不会强制中断。

如果用户任务已经开始执行，`shutdown()` 不会提前把该 running task 从 `activeTasks` 中移除。该 task 会在用户代码自然完成后，通过 finally 路径清理 `activeTasks`。

因此：

```java
wheelTimer.shutdown();
boolean terminated = wheelTimer.awaitTermination(1, TimeUnit.SECONDS);
```

当存在仍在运行的用户任务时，`terminated` 可以是 `false`。只有 running task 自然完成、内部集合清空且底层 timer 已停止后，`awaitTermination()` 才会返回 `true`。

## 3. shutdownNow()

`shutdownNow()` 的语义是立即关闭 timer 自身：

- 设置 shutdown 标记。
- 设置 shutdownNow 标记。
- 对 holder / active / periodic task 执行 `cancel(true)`。
- 清空 `holder / activeTasks / periodicTasks`。
- 停止底层 Netty `HashedWheelTimer`。

需要注意：`shutdownNow()` 的 `isTerminated()` / `awaitTermination()` 语义只表示 `WheelTimer` 自己的 timer 和内部跟踪结构已经终止，不等价于外部 executor 中所有用户任务都已经停止。

如果用户任务已经被提交到外部 executor 并已经开始执行，且该任务忽略中断或没有响应中断，任务仍可能继续运行。调用方不应把 `WheelTimer.isTerminated()` 解读为外部 executor 中的 running task 必然已经退出。

## 4. stopTimer() 异常路径

底层 Netty `HashedWheelTimer.stop()` 在某些非法调用场景可能抛出异常，例如在 timer worker 线程内调用 stop。

`WheelTimer.stopTimer()` 使用 CAS 防止重复 stop，同时在 stop 抛异常时恢复状态：

```java
if (!timerStopStarted.compareAndSet(false, true)) {
    return;
}
try {
    timer.stop();
    timerStopped = true;
} catch (RuntimeException | Error e) {
    timerStopStarted.set(false);
    throw e;
}
```

这样可以避免出现以下不可终止状态：

- `timerStopStarted=true`
- `timerStopped=false`
- 后续 shutdown 无法重试 stop
- `awaitTermination()` 长期返回 false

异常不会被吞掉；调用方应能感知资源释放失败。

## 5. 重复和并发关闭

`shutdown()` 和 `shutdownNow()` 可以被重复或并发调用。正常路径下，底层 timer 只会由一个调用者执行实际 stop，其他调用者会直接返回。

推荐调用方仍然保持清晰的生命周期管理：

```java
try {
    // schedule tasks
} finally {
    wheelTimer.shutdown();
    wheelTimer.awaitTermination(3, TimeUnit.SECONDS);
}
```

如果需要强制关闭，调用方可以使用 `shutdownNow()`，但仍应理解它无法保证外部 executor 中已开始运行且忽略中断的用户任务立即停止。

## 6. 与 Tasks.timer() 的区别

全局入口 `Tasks.timer()` 属于共享资源，不建议业务代码主动关闭。业务代码如果需要独立生命周期，应创建 standalone `WheelTimer` 或使用拥有明确生命周期的上层组件。

全局入口和 standalone 实例的生命周期语义应区分：

- `Tasks.timer()`：共享入口，通常由进程生命周期管理。
- standalone `WheelTimer`：调用方是资源拥有者，应负责 shutdown / awaitTermination。
