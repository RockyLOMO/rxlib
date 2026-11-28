# RXlib ThreadPool / ObjectPool 专项 Code Review

- 日期：2026-11-28
- 分支：master（Java 8）
- ThreadPool 审查基线：[ca3a58b](https://github.com/RockyLOMO/rxlib/commit/ca3a58b838166bba9745a3e149deda9458b8e5bf)
- ObjectPool 审查基线：[55aff29](https://github.com/RockyLOMO/rxlib/commit/55aff29f563802463b38085de1e379c926c18a3d)
- 状态：静态源码审查及修复建议，尚未执行复现测试、Maven 回归或性能压测；本次仅新增文档。

## 1. 背景与原则

RXlib 作为 RSS、SOCKS、RPC、UDP2RAW 等网络模块的底层基础库，需要优先保证任务调度、连接复用及资源生命周期的稳定性。依据 AGENTS.md，保持严格 Java 8、低分配、低锁竞争、不阻塞 Netty EventLoop；RSS Server 以单核 CPU / 768 MiB 整机内存（包含 OS）为验收基线。

保留现有 ThreadPoolExecutor、LinkedTransferQueue、Semaphore、CpuWatchman 及 ObjectPool 的 CAS 状态机、IdentityWrapper、共享 idle 链表。优先做最小修复与减法，不引入第三方池、不盲目增加线程池、不改变正常业务 API 的既有契约。性能改动以测试数据决定是否采纳。

优先级：P0 = 优先处理的严重风险；P1 = 重要缺陷或稳定性问题；P2 = 优化或契约完善。P0 不代表线上已经发生故障；部分依赖错误调用、配置或极端并发。下文明确区分触发条件与实际影响。

## 2. ThreadPool 审查

范围：ThreadPool、ThreadQueue、Task、Tasks、CpuWatchman、WheelTimer、RunFlag、RxConfig、线程池参考文档及部分回归测试、网络调用方。

架构：Tasks 多副本分发 → ThreadPool → ThreadQueue（LinkedTransferQueue + Semaphore）→ Worker；CpuWatchman 动态调整 corePoolSize，WheelTimer 负责定时调度，Task 提供 SERIAL/SINGLE/TRANSFER/PRIORITY、Trace 与 FastThreadLocal 传播。现有队列容量、背压、诊断、动态调节能力均有保留价值。

| ID | 优先级 | 问题 | 触发条件 |
| --- | --- | --- | --- |
| TP-01 | P0 | BLOCK 重入提交死锁 | 同池 worker 提交、队列饱和 |
| TP-02 | P0 | SERIAL 取消破坏执行顺序 | 调用者取消或提前完成串行 Future |
| TP-03 | P0 | 时间轮 / EventLoop 被提交反向阻塞 | 队列饱和，BLOCK 或 CALLER_RUNS |
| TP-04 | P1 | SERIAL 计数器删除竞态 | 同 ID 完成与新提交并发 |
| TP-05 | P1 | 线程池副本热调整生命周期 | replicas 配置变化 |
| TP-06 | P1 | 队列继承方法绕过容量管理 | purge、直接使用队列其他接口 |
| TP-07 | P1 | beforeExecute 异常未完成 Future | 非法 RunFlag 或上下文初始化异常 |
| TP-08 | P1 | CALLER_RUNS 混淆超时、中断、关闭 | 等待被中断或 shutdown |
| TP-09 | P1 | SERIAL 后续任务 RunFlag 不完整 | 串行链第二项及以后使用组合 flag |
| TP-10 | P2 | 动态扩缩容依据与并发状态 | CPU 采样、PRIORITY 并行调整 |
| TP-11 | P2 | 随机分发命中饱和节点 | 多副本负载不均 |
| TP-12 | P2 | FastThreadLocal 延迟快照 | 提交后父上下文发生变化 |
| TP-13 | P2 | CompletableFuture Unsafe 全局 patch | 显式开启 patch |

### TP-01：BLOCK 重入提交死锁

[源码 ThreadPool.java L100-246](https://github.com/RockyLOMO/rxlib/blob/ca3a58b838166bba9745a3e149deda9458b8e5bf/rxlib/src/main/java/org/rx/core/ThreadPool.java#L100-L246)

队列满时 offer 直接阻塞等待 Semaphore，而不是返回 false 给 JDK 的 execute 扩容逻辑。若所有 worker 都在向同一个饱和池提交任务，便没有 worker 消费队列。CpuWatchman 可能暂时缓解，但受最大线程数、CPU 水位和采样延迟限制，不能作为正确性保证。

建议保留外部生产者 BLOCK；检测同池 worker 重入，对饱和重入采用明确拒绝或经过验证的内联策略。评估是否允许普通 offer 失败后由 JDK 尝试非核心 worker，避免无意改变既有动态扩容策略。不能宣称解决所有死锁：同池同步 get/join 等待子任务依然需要调用约束，跨池循环等待也需要避免。

验证：fixed(1, queue=1)，用 latch 占满 worker 与队列，再触发嵌套提交，断言限定时间内返回或明确拒绝；补多 worker 饱和测试。

### TP-02：SERIAL 取消导致乱序

[源码 ThreadPool.java L1148-1193](https://github.com/RockyLOMO/rxlib/blob/ca3a58b838166bba9745a3e149deda9458b8e5bf/rxlib/src/main/java/org/rx/core/ThreadPool.java#L1148-L1193)

当前把返回给用户的 CompletableFuture 同时作为内部串行屏障，通过 prev.handleAsync 链接。A 运行，B 等待 A，C 等待 B；如果 B 被 cancel，C 可能提前执行。取消已经开始的 A 也不代表 A 的业务代码已经退出。用户主动 complete 同样可能提前推进内部链。

建议分离 internal completion 与 user future，内部屏障只在前序执行真实结束或按序跳过后完成。取消排队任务只标记不执行；取消运行任务不允许提前释放顺序约束。保持前一项异常不阻断后续任务，保留 completionReturnedValue 语义。任务提交、用户回调不能放在 ConcurrentHashMap.compute 内部锁范围中。

验证：A 被 latch 阻塞，取消 B 或 A，C 不得提前开始；异常、取消、拒绝后内部链能够回收。

### TP-03：WheelTimer / EventLoop 非阻塞提交

[WheelTimer.java L76-144](https://github.com/RockyLOMO/rxlib/blob/ca3a58b838166bba9745a3e149deda9458b8e5bf/rxlib/src/main/java/org/rx/core/WheelTimer.java#L76-L144) / [L368-413](https://github.com/RockyLOMO/rxlib/blob/ca3a58b838166bba9745a3e149deda9458b8e5bf/rxlib/src/main/java/org/rx/core/WheelTimer.java#L368-L413)

时间轮到期直接调用 executor.submit。默认 BLOCK 会在队列满时阻塞单个时间轮线程；CALLER_RUNS 则可能让业务直接在时间轮执行。心跳、重连、超时和维护任务可能整体延迟。Tasks.runAsync 并不天然保证提交非阻塞，SOCKS、UDP2RAW 等网络调用点同样需要核对。

建议新增或提供明确的非阻塞提交路径（例如 Tasks.tryRunAsync，具体签名待定），保证不等待 slot、不在提交者执行任意业务逻辑。定时任务遭拒时必须明确完成、重试、合并或取消，按任务类型制定策略，不能静默丢弃关键超时处理。优先复用既有执行器，不默认增加线程池。

验证：压满业务池，时间轮仍可调度其他到期任务；Netty EventLoop 提交及时返回；覆盖 BLOCK、TIMEOUT_REJECT、CALLER_RUNS。

### TP-04：SERIAL 容量计数竞态

[ThreadPool.java L1148-1191](https://github.com/RockyLOMO/rxlib/blob/ca3a58b838166bba9745a3e149deda9458b8e5bf/rxlib/src/main/java/org/rx/core/ThreadPool.java#L1148-L1191)

taskSerialCountMap 中 counter 减为 0 与 remove(taskId,counter) 不是同一个原子操作。新提交可能在删除前取得旧 counter 并递增，之后旧 counter 仍被删除，下一次提交建立新 counter，导致同 ID 出现两套容量计数。taskSerialMap 和计数 Map 独立维护也增加一致性负担。

建议合并为 SerialState（tail、pendingCount），同 key 原子更新与删除，实际调度在锁外，处理拒绝、取消和完成。全局 SERIAL 总积压上限作为后续优化评估。

验证：并发完成最后一个任务及提交同 ID 新任务，断言容量永不突破 configured limit，全部完成后状态清理。

### TP-05：线程池副本热调整

[Tasks.java L197-244](https://github.com/RockyLOMO/rxlib/blob/ca3a58b838166bba9745a3e149deda9458b8e5bf/rxlib/src/main/java/org/rx/core/Tasks.java#L197-L244)

replicas 从 4 改 5 时当前创建 5 个新池并插入旧列表，而不是增量增加 1 个。旧池只凭 getActiveCount()==0 移除并 unregister，未明确 shutdown，未检查队列；移除循环索引可能跳过元素。poolCount 非 volatile，分发与列表变更没有统一快照。由于旧池允许核心线程超时，不应直接断言永久线程泄漏。

建议使用原子发布的不可变活跃池快照；扩容增量添加，缩容先停止新流量路由，再 drain/shutdown，明确处理旧快照上的迟到提交及串行任务。保留 ID 路由语义。

验证：多次 4→5→2 调整，与提交并发，无丢任务、越界或长期残留池。

### TP-06：ThreadQueue 继承 API 遗漏

[ThreadPool.java L49-385](https://github.com/RockyLOMO/rxlib/blob/ca3a58b838166bba9745a3e149deda9458b8e5bf/rxlib/src/main/java/org/rx/core/ThreadPool.java#L49-L385)

现有 offer、take、poll、remove、drainTo、clear 已管理容量；不要重复推翻这些修复。LinkedTransferQueue 的 put、timed offer、transfer、tryTransfer、iterator.remove 等继承入口需要核对。尤其 JDK purge 可能通过 iterator.remove 移除取消的 Future 而绕过 slot 回收。

建议只允许经过统一容量管理的队列入口，或对不支持的直接队列操作明确拒绝；补齐 purge，避免并发 poll 和 iterator.remove 重复释放 permit。也应评估 TRANSFER 在无 worker 或关闭时的等待边界。

验证：每条支持的插入 / 移除路径结束后，静止状态 counter、实际元素数与 availableSlots 相符；purge、shutdownNow 与消费并发。

### TP-07：beforeExecute 异常处理

[ThreadPool.java L1214-1310](https://github.com/RockyLOMO/rxlib/blob/ca3a58b838166bba9745a3e149deda9458b8e5bf/rxlib/src/main/java/org/rx/core/ThreadPool.java#L1214-L1310)

SINGLE 无 taskId 在 beforeExecute 抛异常，JDK worker 可能退出而 FutureTask 未执行、Future 未完成；afterExecute 也未必运行。若已经获得 SINGLE 锁、设置 trace 或 map 后再抛异常，还可能残留上下文或锁。

建议将 flag 参数检查前移到提交阶段，初始化和清理路径必须覆盖失败；Future 明确异常完成或取消。不要仅吞掉异常后继续执行未经初始化的业务。

验证：非法 SINGLE、上下文初始化故障，Future 有终态，worker/互斥状态不泄漏。

### TP-08：CALLER_RUNS 中断与关闭

[ThreadPool.java L141-246](https://github.com/RockyLOMO/rxlib/blob/ca3a58b838166bba9745a3e149deda9458b8e5bf/rxlib/src/main/java/org/rx/core/ThreadPool.java#L141-L246)

waitForSlot 返回 false 同时表示超时、中断、shutdown，外层 CALLER_RUNS 分支仍执行任务。建议区分 ACQUIRED/TIMEOUT/INTERRUPTED/SHUTDOWN，仅 TIMEOUT 且池仍接受提交时允许内联，保持中断标志；明确 shutdown 并发线性化点。队列统计不应重复计数。

验证：等待队列 slot 时 interrupt、shutdown，业务不执行且无 permit 泄漏；正常超时 caller-runs 的 trace 与 flag 清理正确。

### TP-09：SERIAL 后续 RunFlag 生命周期

[ThreadPool.java L1148-1177](https://github.com/RockyLOMO/rxlib/blob/ca3a58b838166bba9745a3e149deda9458b8e5bf/rxlib/src/main/java/org/rx/core/ThreadPool.java#L1148-L1177) / [L1379-1409](https://github.com/RockyLOMO/rxlib/blob/ca3a58b838166bba9745a3e149deda9458b8e5bf/rxlib/src/main/java/org/rx/core/ThreadPool.java#L1379-L1409)

首项 supplyAsync(t, asyncExecutor)，后续 handleAsync(lambda,this) 只暴露外层 lambda，原始 Task 不一定经过完整 beforeExecute/afterExecute 生命周期。SINGLE、PRIORITY、TRANSFER、INHERIT_FAST_THREAD_LOCALS 可能未生效，THREAD_TRACE 语义可能不一致。依赖 JDK 内部 fn 字段反射本身也不是稳定契约。

建议与 TP-02/04 合并修复，使用统一任务封装与执行入口，明确哪些 flag 允许组合，保留 trace 与异常语义。不要依赖反射成功作为正确性前提。

### TP-10：CpuWatchman 动态调整

[CpuWatchman.java](https://github.com/RockyLOMO/rxlib/blob/ca3a58b838166bba9745a3e149deda9458b8e5bf/rxlib/src/main/java/org/rx/core/CpuWatchman.java)

保留现有 CPU 水位、连续采样、resizeStep、cooldown、jitter。CPU 高并不总说明本池过度并行（可能是其他进程负载或 IO 等待），CPU 低加队列非空也不等于需要大量 worker。建议引入池活跃度、持续饱和时间、排队延迟与 backlog 趋势，先低成本采样；普通采样与 PRIORITY 访问同一 resize state 应串行化，避免互相覆盖。

### TP-11：多副本分发优化

[Tasks.java L226-244](https://github.com/RockyLOMO/rxlib/blob/ca3a58b838166bba9745a3e149deda9458b8e5bf/rxlib/src/main/java/org/rx/core/Tasks.java#L226-L244)

随机分发可能命中满池，而其他池空闲。建议压测 Power of Two Choices（P2C）：抽两个活跃池，按归一化队列深度及活跃负载择优；O(1) 而非遍历所有池。SINGLE 等 ID 路由继续遵守既有语义。避免对每次任务调用代价较高的 getActiveCount 或增加全局锁，优先轻量计数或采样。单核/单副本无必要做额外分发。

### TP-12：FastThreadLocal 快照时机

[ThreadPool.java L497-501](https://github.com/RockyLOMO/rxlib/blob/ca3a58b838166bba9745a3e149deda9458b8e5bf/rxlib/src/main/java/org/rx/core/ThreadPool.java#L497-L501) / [L1246-1251](https://github.com/RockyLOMO/rxlib/blob/ca3a58b838166bba9745a3e149deda9458b8e5bf/rxlib/src/main/java/org/rx/core/ThreadPool.java#L1246-L1251)

任务创建只持有 parent map 引用，执行时才复制；期间父线程可能改变上下文。建议仅在 INHERIT_FAST_THREAD_LOCALS 开启时提交阶段快照，恢复与清理成对。浅复制仍共享可变业务对象，不承诺深复制。评估 Netty 内部 map 兼容性和分配成本。

### TP-13：CompletableFuture 全局 patch

[Tasks.java L121-177](https://github.com/RockyLOMO/rxlib/blob/ca3a58b838166bba9745a3e149deda9458b8e5bf/rxlib/src/main/java/org/rx/core/Tasks.java#L121-L177)

Unsafe 修改 JDK 全局静态字段会影响整个 JVM，且有 JDK 版本、模块访问、JIT 风险。保留可选兼容功能，默认关闭（配置字段本身已默认 false，部署配置仍须核对）；新代码显式传入 Executor。不要把 patch 成功当作正确性保证。

## 3. ObjectPool 审查

范围：ObjectPool、IdentityWrapper、Disposable、ObjectPoolTest、ObjectPoolRecycleOwnershipTest、参考文档、RPC 和 SOCKS5 UDP Lease 调用方。

现有架构：IdentityWrapper + ConcurrentHashMap 管理身份；CAS 预留容量；IDLE/BORROWED/VALIDATING/RETIRED 状态机；LIFO 共享 idle 链表 + Condition；L1 FastThreadLocal 只是共享队列 hint，不是独占缓存；周期校验、minIdle 补充、自适应预热、诊断。普通重复归还、跨线程归还可见性、L1 校验异常退休等历史修复应保留。

| ID | 优先级 | 问题 | 触发条件 |
| --- | --- | --- | --- |
| OP-01 | P0 | 重复创建关闭已有对象 | 工厂违反独立实例约定 |
| OP-02 | P0 | 延迟重复归还破坏所有权 | 旧借用者错误重复归还 |
| OP-03 | P1 | idleTimeout 被周期校验刷新 | 正常周期校验 |
| OP-04 | P1 | 自适应预热使用频率而非并发需求 | 高频短持有负载 |
| OP-05 | P1 | L1 Hint 强引用残留 | 长寿命线程、动态池 |
| OP-06 | P1 | 泄漏检测误关闭新借用 | 显式开启 closeObjectOnLeak |
| OP-07 | P1 | 异步补充拒绝导致标志卡住 | 执行器拒绝或调度失败 |
| OP-08 | P1 | 创建、校验与 close 竞态 | 并发关闭 |
| OP-09 | P2 | borrowTimeout 非端到端超时 | 工厂或回调阻塞 |
| OP-10 | P2 | maxPoolSize 调整后不主动收缩 | 热修改容量 |
| OP-11 | P2 | Live 链表无实际扫描用途 | 正常创建/退休 |
| OP-12 | P2 | L1 优化收益未经量化 | 正常借还 |

### OP-01：重复创建对象的处理

[ObjectPool.java L652-745](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/core/ObjectPool.java#L652-L745)

doCreate/doCreateIdle 使用 putIfAbsent 检测重复身份，但失败后 tryClose(wrapper) 关闭的是同一个实例，可能正在被另一线程使用。身份检查前已执行 activate 或 validate/passivate，也可能破坏原对象。现有测试 [ObjectPoolTest L323-369](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/test/java/org/rx/core/ObjectPoolTest.java#L323-L369) 明确要求重复对象关闭，属于历史设计决策，修复须同步调整测试，而非无意改变。

建议工厂必须返回新且非 null 的实例。先原子注册身份，再对独占的创建状态执行回调，重复实例只释放本次预留 slot、拒绝和告警，绝不操作已有实例。创建中对象不得被后台借出或校验；失败按确切注册记录清理。P0 是异常工厂行为触发的破坏性后果，正常独立工厂不触发。

验证：工厂返回同一个正在借出的实例，第二次借用失败，但原借用者资源仍有效；重复 idle 创建不关闭原实例，容量不泄漏。

### OP-02：延迟归还 ABA

[ObjectPool.java L890-945](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/core/ObjectPool.java#L890-L945)

A 借 X → A 归还 → B 借 X → A 再次误归还 X → C 可借 X，造成 B/C 并发使用。CAS 只能确认当前 BORROWED，无法区分借用代次。已有普通 double recycle、并发 recycle 测试没有覆盖跨代次归还。

这是裸对象 borrow/recycle API 的契约限制，不是正常调用必现缺陷。建议保留旧 API，明确每次借用只归还一次；对 RPC/UDP Lease 等场景可选新增带 generation/token 的 PoolLease（AutoCloseable），归还校验对象身份及借用代次，并保证 lease.close 幂等。仅用 Thread ID 无法解决合法跨线程归还，也不建议强制全项目迁移。

验证：旧 lease 延迟 close 不得改变新 lease 状态；跨线程正确归还仍有效。

### OP-03：idleTimeout 被校验刷新

[ObjectPool.java L64-84](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/core/ObjectPool.java#L64-L84) / [L481-528](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/core/ObjectPool.java#L481-L528)

默认 validationPeriod=30s、idleTimeout=600s、maxLifetime=0。validNow 将 IDLE→VALIDATING→IDLE，每次 CAS 刷新 stateTime，健康对象的连续空闲时间被重置，常规校验下超时回收可能永远不发生。已有 testAdaptiveRefillLoadDrop 先等 idleTimeout 再手动校验，没有覆盖周期短于 idleTimeout 的场景。

建议拆分最后实际归还时间、借用开始时间、校验时间与创建时间；健康后台校验不得刷新连续空闲时钟。退休前还需在取得当前状态所有权后重新确认超时条件，避免旧 idle 判断影响新一轮借还。保留 minIdle 下限，明确 maxLifetime 优先级。

验证：多次间隔小于 idleTimeout 的 validNow，累计超过 idleTimeout 后，健康且超出 minIdle 的对象被回收；新归还对象不误回收。

### OP-04：自适应容量算法

[ObjectPool.java L530-543](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/core/ObjectPool.java#L530-L543)

当前 12 周期窗口，target = ceil(avgPerPeriod * demandFactor)，再限制在 minIdle/maxPoolSize。借用次数不等于同时占用对象数。稳态 100 次/s、平均持有 10ms、30s 采样、demandFactor=2 时，未截断目标为 6000；平均并发需求仅为 1，maxPoolSize=100 时会预热到 100。这是模型示例，不是实测。

建议优先基于峰值实际借用数、等待人数及持续饱和时间，辅助使用借用速率 × 持有时间（Little's Law 稳态平均估算）。不必引入复杂算法，避免高频额外分配；扩容快、缩容慢，有滞后和下限保护。结合 OP-03 修复才能真正释放资源。现有 12 周期采样可保留为诊断或调整窗口。

验证：高频串行复用不会预热到 max；真实并发峰值能合理扩容；负载回落后回归目标；连接创建数、RSS 内存、P99 作为验收。

### OP-05：L1 Hint 强引用

[ObjectPool.java L97-108](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/core/ObjectPool.java#L97-L108) / [L796-819](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/core/ObjectPool.java#L796-L819) / [L935-939](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/core/ObjectPool.java#L935-L939)

长寿命线程的 FastThreadLocal 保存 ObjectConf 强引用，已退休/关闭对象可能在原线程再次访问前一直残留。动态创建池时每池 ThreadLocal 条目也有成本。此前跨线程可见性修复已正确将归还对象放入共享队列，不要退回独占 L1。

建议优先压测删除 L1 Hint（与 OP-12 合并）；若收益确实明显，考虑弱引用或有界全局线程缓存，并验证 close 后资源引用清理。WeakReference 不能消除 ThreadLocal 条目本身的成本。lookupKey 当前已在 finally 清空 instance，暂时保留并单独关注动态池条目。

### OP-06：强制泄漏回收竞态

[ObjectPool.java L494-503](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/core/ObjectPool.java#L494-L503) / [L595-627](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/core/ObjectPool.java#L595-L627)

后台判断 A 借用超时后，A 可能归还，B 再借，随后 doRetire 无条件删除并关闭 B 对象。只做 BORROWED→RETIRED CAS 仍存在 ABA。默认 closeObjectOnLeak=false 仅报警，应继续保持。

建议强制退休需要绑定准确借用代次、开始时间和状态，原子确认后再回收；同时明确强制关闭仍在使用的资源属于显式 opt-in 行为。注意 casState 后更新元数据与并发转换的顺序，不应把旧时间或 owner 覆盖到新代次。

验证：检测到旧借用超时后暂停扫描，归还并重新借出，再恢复扫描，新代次不被关闭。

### OP-07：minIdleMaintaining 卡住

[ObjectPool.java L549-593](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/core/ObjectPool.java#L549-L593)

标志 CAS 为 true 后提交 Tasks.run 或 Tasks.setTimeout，如果提交失败或延迟任务未真正执行，内部 finally 不会运行，后续即时补充被跳过。validNow 仍可能同步补充，所以并非全部 minIdle 失效。借用成功后触发维护失败还会走异常退休路径；BLOCK 提交也可能扩大借用等待或线程池饱和问题。

建议非阻塞提交，失败时恢复标志并明确后续周期重试；退避任务同样处理拒绝/取消，维护异常不得影响已经成功的 borrow/recycle，不引入无界重试或递归 caller-runs。与 TP-03/08 联动。

验证：注入拒绝/取消，标志恢复、下次补充成功、成功借用不被错误销毁。

### OP-08：关闭与注册/回调竞态

[ObjectPool.java L263-272](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/core/ObjectPool.java#L263-L272) / [L652-745](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/core/ObjectPool.java#L652-L745)

创建先 conf.putIfAbsent 后 linkLive；close 可以在二者之间删除并关闭对象，unlinkLive 找不到节点，随后创建线程又把退休对象加入 live 链表，再次 doRetire 无法清理。删除无实际用途的 live 链表可直接缩小竞态面。close 还可能与 activate/validate/passivate 并发，L1 借用路径缺少最终关闭检查；需要区分借用接受点与主动关闭的既有契约，不能承诺强制关闭后业务仍可使用对象。

建议保留现有 force-close 语义（网络调用方可能依赖主动断开），统一对象注册、退休、关闭检查与回调所有权；按确切 ObjectConf 条件删除，容量只释放一次，避免旧清理移除新注册记录。可选 graceful close：停止新借用，等待现有归还后销毁，须评估调用方再引入。

验证：latch 精确控制创建注册、回调及 close 的并发窗口；已退休对象不残留，close 回调至多一次，等待者及时唤醒。已有 stress 测试不替代确定性断言。

### OP-09：borrowTimeout 语义

[ObjectPool.java L748-887](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/core/ObjectPool.java#L748-L887)

borrowTimeout 主要约束借用循环与等待，不保证同步 create/validate/activate 在 deadline 内完成，L1 分支也在主循环计时之前。建议明确文档契约，统一从入口使用单调时钟 deadline，将剩余预算传给可控网络建连回调。不要每次借用额外创建异步任务强制超时；任意用户回调无法被通用对象池安全抢占。

### OP-10：动态容量收缩

[ObjectPool.java L218-226](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/core/ObjectPool.java#L218-L226) / [L629-645](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/core/ObjectPool.java#L629-L645)

setMaxPoolSize 只更新配置，已有资源不主动收缩，CAS reserve 与热修改也有窗口；提高上限时等待者唤醒需要核对。建议缩容先回收多余 idle，借出的对象归还时自然退出，不强制中断正常使用；扩容及时唤醒等待者。明确热配置是即时硬上限还是允许过渡期。

### OP-11：删除未使用的 Live 链表

[ObjectPool.java L430-489](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/core/ObjectPool.java#L430-L489)

liveHead/scanCursor/nextScanCandidate/liveLock 及 prevLive/nextLive 持续维护，但 validNow 实际扫描 new ArrayList<>(conf.values())。如全仓无其他依赖，建议删除 live 链表与维护锁，保留共享 idle 链表及 conf；同时消除 OP-08 的注册残留场景。大池扫描成本后续以 profiling 决定是否增量扫描，不应仅为潜在用途保留双重索引。

### OP-12：L1 性能收益与生命周期收敛

当前 L1 命中仍需 conf.get、CAS、idleLock unlink、validate；不是真正独立无锁快路径。优先测试仅共享 LIFO idle 的实现是否更简单且不降低吞吐。评估 unify borrow 生命周期，当前共享路径先 activate 后 validate，L1 先 validate 后 activate；不能在未确认 RPC/SOCKS 回调契约前直接改顺序。

建议普通资源继续低分配；额外 lease 对象只用于需要强所有权保障的 API。压测分别比较单线程、多线程、跨线程归还、动态池创建销毁，指标含 throughput、P99、allocation rate、GC、retained heap。

## 4. 调用方及兼容性

- RPC：[RpcTransportClientPoolImpl.java](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/net/rpc/RpcTransportClientPoolImpl.java)：创建 TransportClient，validate=isConnected，passivate 重置 handlers 和重连配置；须确认借还及 close 契约。
- SOCKS：[Socks5UpstreamPoolManager.java](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/net/socks/Socks5UpstreamPoolManager.java)：UDP Lease 使用 ObjectPool；TCP warm pool 为另一个独立实现，不应混同。验证 lease recycle/discard、断线、动态池回收。
- 网络线程调用 Tasks.runAsync 需要明确拒绝策略与失败补偿，尤其 UDP relay、连接初始化及 RPC；不能只将同步函数包进 Future 就认为 EventLoop 不阻塞。
- 全局 Tasks.shutdown/shutdownNow 目前是明确的 no-op 共享入口语义，不视为 standalone ThreadPool 的关闭 bug。
- 不默认启用 CompletableFuture 全局 patch、不默认开启强制泄漏回收。
- 保持 Java 8 和原有公共 API；新增 API 为可选能力，具体签名由实现阶段确定。

## 5. 实施计划（建议，待确认）

### 阶段一：稳定性修复

- [ ] TP-01 / TP-03 / TP-08：统一非阻塞入口、重入与拒绝结果；保护 WheelTimer 和 EventLoop。
- [ ] TP-02 / TP-04 / TP-09：统一 SERIAL 内部状态、容量及执行屏障；保证取消不乱序。
- [ ] TP-06 / TP-07：补队列容量入口与任务初始化失败清理。
- [ ] OP-03：修复 idleTimeout；优先解决正常运行下的资源残留。
- [ ] OP-01：修复重复工厂对象所有权（调整历史测试契约）。
- [ ] OP-07：修复异步维护拒绝及标志恢复。
- [ ] OP-06 / OP-08：强制回收与关闭竞态；优先删除冗余 live 链表（OP-11）。

### 阶段二：收敛架构与资源使用

- [ ] TP-05：副本增量调整、原子快照和旧池退出。
- [ ] TP-12：仅开启继承时做上下文快照。
- [ ] OP-04 / OP-10：基于实际并发需求预热、自然收缩与热配置。
- [ ] OP-05 / OP-12：压测 L1 Hint，决定删除或保留。
- [ ] OP-09：明确借用超时与网络连接预算。
- [ ] OP-02：可选租约 API，优先检查真实调用点是否存在跨代次错误归还。

### 阶段三：性能与诊断

- [ ] TP-10 / TP-11：采样策略、P2C 分发，按压测结果决定采纳。
- [ ] TP-13：确认各部署 profile 的 patch 配置及显式 executor 使用。
- [ ] 评估全局 SERIAL 积压保护、排队耗时、实际 active 对象数、维护拒绝和过期回收指标。
- [ ] 完成 RPC、SOCKS、RSS 网络回归与低资源长期运行验证。

## 6. 测试与验收

先添加可确定复现的回归测试，用 CountDownLatch/Barrier 控制时序，不依赖随机 sleep；所有等待有上限。避免全局 RxConfig/Tasks 的测试配置互相污染，finally 恢复配置、关闭自建资源。历史正常行为测试必须保留；有意改变的重复工厂语义需明确修改原断言。

建议 Java 8 定向测试（按仓库现有 Maven/CI 配置执行，默认跳过测试，需显式开启）：

```bash
mvn -pl rxlib -DskipTests=false -Dtest=ThreadPoolTest,ThreadPoolQueueOfferModeTest,ThreadPoolQueueShutdownTest,SerialQueueCapacityTest,ThreadPoolWheelTimerRegressionTest,ThreadPoolTraceIdTest,WheelTimerTraceIdTest,WheelTimerShutdownPeriodicTest,CpuWatchmanResizeTest,TasksTest,TasksCompatibilityTest test
mvn -pl rxlib -DskipTests=false -Dtest=ObjectPoolTest,ObjectPoolRecycleOwnershipTest test
```

网络影响范围确定后补充 RemotingTest、SocksProxyServerIntegrationTest、Socks5ClientIntegrationTest、ShadowsocksServerIntegrationTest、RrpIntegrationTest 等相关集成测试。优先 GitHub Actions，必要时本地兜底。实际开发先在 master 验证、提交；涉及 rss-21 时遵循 AGENTS.md 的 merge master 流程，再执行 JDK21 回归和部署包构建。

验收重点：

| 场景 | 验收条件 |
| --- | --- |
| 饱和线程池 | 无无限重入等待；I/O/时间轮不执行阻塞业务 |
| SERIAL | 同 ID 不重叠，取消/异常/拒绝不破坏顺序及容量 |
| 队列 | 静止时计数、队列长度、permit 一致；关闭/取消不泄漏 |
| 对象所有权 | 不借出退休对象、不因重复创建关闭已有实例；lease 归还代次准确 |
| 对象容量 | 预留/释放一次，稳定配置不超过上限，缩容符合过渡约定 |
| 生命周期 | idleTimeout 持续累计，维护失败可恢复，关闭后无残留引用或未处理对象 |
| 性能 | 对比修改前后吞吐、P50/P95/P99、CPU、线程数、堆/堆外、GC、socket 数、创建/关闭次数 |
| 低资源 | 1 vCPU / 768 MiB 整机预算下验证 RSS Server 的稳态及峰值资源占用 |

## 7. 总结

两套组件整体架构可保留。ThreadPool 优先解决饱和时的调度阻塞与 SERIAL 正确性；ObjectPool 优先解决正常负载下 idleTimeout 不生效、过度预热及维护失败，再处理异常调用的所有权防御。删除没有实际收益的复杂结构比盲目增加功能更符合低资源目标。

本轮只提交 Review 文档，没有修改生产代码或测试，没有触发 CI。所有并发时序为静态分析推导，性能建议为待验证假设；实施时重新读取最新源码，按独立测试确认每项问题后再修改。不把异常工厂、错误重复归还、可选强制回收的风险误描述为默认生产环境必现故障。
