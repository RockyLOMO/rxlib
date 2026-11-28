# ObjectPool 参考文档

`ObjectPool<T>` 是一个基于并发需求自适应扩缩容和低锁/代次 CAS 状态机设计的对象池组件，专为高性能、高并发场景（如网络连接、昂贵对象复用）设计。它完全兼容项目的高性能模式（Netty 底层网络编程），提供精细的生命周期管理、租约安全保护与严格的内存泄漏检测。

## 1. 核心特性：基于并发需求峰值的自适应扩缩容 (Adaptive Refill)

`ObjectPool` 根据真实的**在借并发数 + 等待借用数（`borrowed + waiting`）**动态计算并调节池总容量目标，既能在高并发突发时快速预热，又能避免低并发高频串行借还导致的对象池过度膨胀。

### 工作原理
1. **滑动窗口并发峰值采样**：每次进入 `BORROWED` 或加入等待队列时，无锁更新本周期的并发峰值 `periodPeakDemand`。后台周期巡检 `validNow()`（由 `validationPeriod` 控制）将峰值写入大小为 12 (`SAMPLE_COUNT`) 的环形缓冲区 `peakDemandSamples`（同时保留 `borrowSamples` 用于借用次数监控）。
2. **快扩慢缩目标容量**：
   - `effectiveDemand = max(peakInPeriod, avgPeakDemand)`
   - 有并发活动时（`peakInPeriod > 0`）按 `ceil(effectiveDemand * demandFactor)` 快速预热；无并发活动回落期按 `floor(effectiveDemand * demandFactor)` 平滑收缩；结果夹紧在 `[minIdleSize, maxPoolSize]`。
   - `demandFactor` 默认值为 `2.0`，设为 `0` 可关闭自适应预热（仅维持 `minIdleSize`）。
3. **动态容量的即时响应**：
   - `setMaxPoolSize` 扩容会立即唤醒等待中的 `borrow()` 线程并按需补充 `minIdleSize`；
   - `setMaxPoolSize` 缩容会立即淘汰多余空闲对象，且正在借用中的超额对象在 `recycle()` 归还时自然退休退出。

## 2. 高性能与并发安全设计

*   **代次化原子状态机 (`AtomicLong stateAndGen`)**：
    内部 `ObjectConf` 使用单 `AtomicLong` 打包 `(generation << 3) | state`（`IDLE`, `BORROWED`, `RETIRED`, `VALIDATING`, `CREATING`），每次进入 `BORROWED` 自动递增代次 `generation`，从根源上消除跨代次 ABA 与误关闭问题。
*   **可选租约句柄 `Lease<T>` (`borrowLease()`)**：
    提供 `try (ObjectPool.Lease<T> lease = pool.borrowLease())` 语义，支持幂等 `close()` / `recycle()` / `discard()` 与跨线程安全归还；旧代次句柄的延迟归还或关闭不会破坏新借用者。原有 `borrow()` / `recycle(T)` 保持零额外分配完全兼容。
*   **共享 LIFO 空闲栈 + 全局静态 `lookupKey`**：
    采用共享 LIFO 双向链表保证热点对象局部性，移除会产生强引用泄漏的实例级 `threadLocalCache`；归还查找键改为全 JVM 共享的 `static final FastThreadLocal<IdentityWrapper<Object>> lookupKey`，归还后立即清零引用。
*   **精确所有权与关闭竞态消除**：
    创建阶段先经 `CREATING` 状态通过 `conf.putIfAbsent` 原子注册再执行生命周期回调，配合 `disposing` Dekker 双边检查与 `conf.remove(c.wrapper, c)` 唯一退休所有权，确保每个已创建对象在并发 `close()` 下至多关闭一次且零残留。
*   **重复实例保护**：
    若 `createHandler` 误返回已在池中的实例，仅释放预留槽、记录告警与冷却，绝不对原在用/空闲实例执行 `activate`/`validate`/`passivate`/`close`。
*   **非阻塞后台维护**：
    `triggerMinIdleMaintain()` 使用 `Tasks.tryExecute()` 非阻塞提交，提交被拒或异常时立即复位 `minIdleMaintaining` 并记录拒绝指标，绝不阻塞 I/O 线程，也不影响已成功 `borrow()` 的对象。

## 3. 完善的生命周期管理

*   **独立语义时间戳**：
    拆分 `createTime`、`idleSinceTime`、`borrowTime` 与 `stateTime`。后台 `validNow()` 健康检查将健康对象放回空闲时**不刷新 `idleSinceTime`**，确保 `validationPeriod < idleTimeout` 时 `idleTimeout` 依然准确生效；`validNow()` 先 CAS 切入 `VALIDATING` 并摘离空闲链后再重判 `maxLifetime`（优先于 `minIdleSize`）与 `idleTimeout`。
*   **统一单调纳秒超时**：
    `borrowTimeout` 入口一次性计算 `deadlineNanos`，`doPollNanos` / `pollSharedIdleNanos` 全程纳秒推进，避免毫秒截断忙自旋。
*   **内存泄漏检测 (Leak Detection)**：
    通过 `leakDetectionThreshold` 设定；默认 `closeObjectOnLeak=false` 只上报 `OBJECT_POOL_LEAK` 指标；开启 `closeObjectOnLeak=true` 时，扫描采用双读 `stateAndGen` + `borrowTime` 与带代次 CAS 原子推进到 `RETIRED`，绝不误杀已归还并重新借出的新代次对象。
*   **生命周期回调 (Handlers)**：

```text
borrow 创建路径: create -> putIfAbsent(CREATING) -> activate -> BORROWED -> validate
预热/refill 路径: create -> putIfAbsent(CREATING) -> validate -> passivate -> IDLE
复用借出路径: idle -> BORROWED(CAS, gen++) -> activate -> validate
归还路径: BORROWED -> VALIDATING -> validate -> passivate -> IDLE (或超容退休)
```

## 4. 可观测性 (Metrics)

`DiagnosticMetrics` 开启后，记录以下核心指标：
*   `rx.object_pool.size.count`：池总大小
*   `rx.object_pool.idle.count`：共享空闲数
*   `rx.object_pool.borrowed.count`：精确在借数（与 `BORROWED` 状态严格配对）
*   `rx.object_pool.active.count`：当前活跃数（含 `BORROWED`/`VALIDATING`/`CREATING`）
*   `rx.object_pool.waiting.count`：等待借用线程数
*   `rx.object_pool.borrow.window.count`：滑动窗口内借用次数
*   `rx.object_pool.peak_demand.count`：本周期并发需求峰值
*   `rx.object_pool.target.total.count`（兼容保留 `rx.object_pool.target.count`）：动态目标总容量
*   `rx.object_pool.created.count` / `rx.object_pool.retired.count`：创建与销毁次数
*   `rx.object_pool.borrow.timeout.count`：借用超时次数
*   `rx.object_pool.maintain.rejected.count`：后台 `minIdle` 维护非阻塞提交拒绝次数
*   `OBJECT_POOL_LEAK`：泄漏检测命中次数

可通过 `setName(String)` 配置池名称，指标 tag 包含 `pool=<identity>`、`handler=<createHandler>` 和可选 `name=<poolName>`。
