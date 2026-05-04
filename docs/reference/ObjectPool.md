# ObjectPool 参考文档

`ObjectPool<T>` 是一个基于自适应扩缩容和高性能无锁/低锁机制设计的对象池组件，专为高性能、高并发场景（如网络连接、昂贵对象复用）设计。它完全兼容项目的高性能模式（Netty 底层网络编程），并提供了精细的生命周期管理与严格的内存泄漏检测。

## 1. 核心特性：自适应扩缩容 (Adaptive Refill)

`ObjectPool` 最核心的亮点是能够根据实际的借用（Borrow）频率，**动态计算并调节池总容量目标（Target Total Size）**，兼顾了内存的节约与高并发下的极致性能。

### 工作原理
1. **滑动窗口采样**：后台周期性任务（由 `validationPeriod` 控制）会收集当前周期内的对象借用次数，并记录到一个大小为 12 (`SAMPLE_COUNT`) 的环形缓冲区 `borrowSamples` 中。
2. **平均需求计算**：计算过去 12 个周期内的平均单周期借用量 (`avgPerPeriod`)。
3. **动态目标总容量**：通过公式计算出最合适的池总大小：
   ```java
   int targetSize = Math.max(minIdleSize, 
       Math.min(maxPoolSize, (int) Math.ceil(avgPerPeriod * demandFactor)));
   ```
   *`demandFactor`（需求因子）默认值为 2.0，意味着系统会预备平时平均需求量两倍的对象，以应对突发的并发洪峰。*
4. **预热与维持**：如果当前池大小低于 `targetSize` 或 `minIdleSize`，后台线程会自动触发创建 idle 对象并将其放入共享空闲队列，降低突发 borrow 的等待概率。

通过这套机制，空闲大小 (`idleSize`) 是完全动态变更的。在流量低谷时，过期策略会自动清理多余对象；在流量高峰来临前，自适应机制会自动预热对象。

## 2. 极致性能优化

作为高性能模式的核心组件，`ObjectPool` 针对热点路径进行了极端的优化：

*   **L1 线程本地 Hint (ThreadLocal Hint)**：
    当 `minIdleSize <= 0` 时开启。归还对象始终进入共享 idle 队列，`FastThreadLocal` 只保存一个快速 hint；同线程下次借用命中 hint 后，必须通过 CAS 抢占并从共享 idle 队列 unlink。这样保留低竞争快路径，同时避免跨线程归还时对象只对归还线程可见。
*   **双向链表与 CAS 状态机**：
    内部对象包装类 `ObjectConf` 通过 CAS 管理状态（`IDLE`, `BORROWED`, `VALIDATING`, `RETIRED`）。后台 validate 会先把 idle 对象切到 `VALIDATING` 并从共享 idle 队列摘除，避免 validate 与 borrow 并发作用在同一个对象上。
*   **低锁竞争分配**：
    对象的创建和销毁被严格管理。等待获取对象的线程使用 `ReentrantLock` 和 `Condition`，而在存活对象扫描（如过期检查）时只使用极轻量的锁。

## 3. 完善的生命周期管理

对象在池中的状态转换由一系列严谨的时间阈值与回调函数控制：

*   **超时控制**：
    *   `borrowTimeout` (默认 10000ms)：借用对象时允许等待的最大时间。
    *   `idleTimeout` (默认 600000ms)：空闲对象在池中驻留的最大时间，超时将被清理。
    *   `maxLifetime` (默认 0，不限制)：对象的绝对最大存活时间。
*   **内存泄漏检测 (Leak Detection)**：
    通过 `leakDetectionThreshold` 设定。如果对象被借出且长时间未归还（超过阈值），后台扫描任务会记录泄漏指标（`OBJECT_POOL_LEAK`）。默认 `closeObjectOnLeak=false`，只报警不主动关闭 borrowed 对象；需要强制回收时可显式开启 `setCloseObjectOnLeak(true)`。
*   **生命周期回调 (Handlers)**：
    *   `createHandler`: 必须提供，定义如何创建新对象。
    *   `validateHandler`: 必须提供，在借出前和后台扫描时验证对象的可用性。
    *   `activateHandler`: 对象被借出前触发（如清理旧的上下文状态）。
    *   `passivateHandler`: 对象进入 idle 前触发（如释放不必要的强引用以协助 GC）。

当前生命周期约定：

```text
borrow 创建路径: create -> activate -> borrowed
预热/refill 路径: create -> validate -> passivate -> idle
复用借出路径: idle -> borrowed(CAS) -> validate/activate -> borrowed
归还路径: borrowed -> validate -> passivate -> idle
```

`passivateHandler` 必须允许作用在“新创建但未 activate”的对象上。它的语义是“进入 idle 前重置/清理”，不是“借用结束后的反向操作”。

## 4. 可观测性 (Metrics)

完全对接了 `DiagnosticMetrics`，记录了丰富的运行指标，包括但不限于：
*   池的总大小 (`size.count`)
*   当前空闲数 (`idle.count`)
*   当前活跃数估算 (`active.count`，总数减 shared idle，包含 borrowed/validating 等非 idle 状态)
*   等待借用的线程数 (`waiting.count`)
*   滑动窗口内的借用量 (`borrow.window.count`)
*   动态目标总容量 (`target.total.count`，兼容保留旧名 `target.count`)
*   创建、销毁、泄漏与超时次数

可通过 `setName(String)` 给池配置诊断名称，指标 tag 会同时包含 `pool=<identity>`、`handler=<createHandler>` 和可选 `name=<poolName>`。

## 总结

`ObjectPool` 将复杂的并发控制、对象分配和动态扩容策略封装在底层，保证了热点路径上极低的对象分配和线程阻塞开销。其**自适应空闲大小调节机制**是保障其在波动流量下依然能维持微秒级延迟的核心关键。
