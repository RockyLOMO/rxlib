# Adaptive ThreadPool V2 — Global / Net 双动态线程池重构计划

- 状态：设计待实施；本文不代表代码已经修改或性能已验证。
- 基准：master `53875522c751f93dd5ec0b68f6dfaaa250b72559`。
- 关联：[ThreadPool / ObjectPool Review](threadpool-objectpool-review-20261128.md)、[项目约束](../AGENTS.md)。
- 目标：Java 8、低分配、低锁竞争、快速扩容、缓慢缩容、有限阻塞补偿、网络业务隔离。
- RSS Server 验收基线：1 vCPU / 768 MiB 整机内存（含 OS）；RSS Client 单独验收。不引入第三方依赖。

## 1. 背景与问题

原有 `Tasks.nextPool()` / replicas 的目的之一，是降低任务 A 等待任务 B 而 B 尚在队列中的线程饥饿风险。多池只能降低发生概率，并不能证明依赖一定推进；真正的 A 等 B、B 等 A 循环依赖也无法通过增加线程解决。

现有实现：

- Tasks 默认两个动态池（replicas=2），P2C / SINGLE hash 分发、失败 fallback、热调整、退休池扫描。
- TcpServer / DefaultTcpClient 共用一套固定 RPC scheduler；TransportClient / TransportServer / Remoting 共用另一套固定 scheduler。两个固定池分别由 Sockets.newRpcScheduler() 创建，配置线程数至少 4、队列容量至少 1024；实际线程数取决于初始化和负载。
- 普通 EventPublisher 默认使用 Tasks.nextPool()，网络类覆盖 asyncScheduler()。
- CpuWatchman 默认 3000ms 采样、连续 2 次命中、步长 2、冷却 1000ms，CPU low/high 为 40/70。持续满足条件时首次普通扩容约需 3–6 秒（另有时间轮误差），多轮扩容更慢。PRIORITY 扩容路径在 beforeExecute，排队任务自身无法触发该路径。
- ThreadQueue 在有容量时优先入队；JDK ThreadPoolExecutor 通常在 offer 失败后才尝试创建非核心线程。Queue 未满不代表有空闲 Worker。BLOCK 队列满时还可能绕过正常扩容机会。
- setCorePoolSize 增大时 JDK 可以立即启动 Worker 消费已有队列，不需要等旧任务完成。问题在于触发时机、资源判断和扩容数量，而非 API 必须等待任务结束。

## 2. 最终架构（确定方向）

```text
普通业务 / 普通事件 / 通用缓存与存储 / 通用维护
                       -> Tasks.pool() -> Global ThreadPool
RPC / TCP / UDP / Transport / SOCKS / RSS 网络业务与维护
                       -> Sockets.scheduler() -> Net ThreadPool

Global 和 Net: 独立队列、独立线程预算、相同 ThreadPool 实现
                       -> 共享 CPU/线程资源控制器（演进 CpuWatchman）
Netty EventLoop / WheelTimer / 独立诊断基础设施：保持原有职责
```

默认框架业务池从四个实例合并为两个（相关类均初始化、默认配置时）。两个池都动态扩缩容；不再维持 Tasks 多副本，不追求 JVM 只有两个线程或禁止用户自定义池。

### 2.1 调度归属

| 任务/模块 | 目标 |
|---|---|
| Tasks、普通 EventPublisher、通用 ObjectPool 维护 | Global |
| TcpServer、DefaultTcpClient、TransportClient、TransportServer 异步业务回调 | Net |
| Remoting 默认请求执行、远程事件计算、网络重连/维护、SOCKS/RSS 网络业务任务 | Net |
| 网络专用 ObjectPool 维护 | 可显式选择 Net，通用默认 Global |
| Netty channel I/O、轻量协议状态更新 | 保留 EventLoop 亲和性，不强制二次调度 |
| 网络定时任务 | 复用 WheelTimer，指定 Net 作为执行器 |
| 通用定时任务 | 复用 WheelTimer，默认 Global |
| HttpServer BusinessExecutor | 后续合并评估，必须保留 maxAsyncRequests、非阻塞准入与拒绝语义 |
| HttpTunnelClient cached executor | 核对阻塞 HTTP、任务生命周期、关闭所有权后再迁移 |
| Shadowsocks CryptoGroup | 保留明确需要的 CPU 隔离选项；不机械迁移 |
| DiagnosticMonitor / NetworkFlowDiagnostics | 保留独立采样及故障取证，不依赖被监控池 |
| RpcServerConfig.executor / 用户显式 fixed/custom executor | 保留所有权及隔离能力 |

按任务职责选择调度器，而不是把 org.rx.net 下所有调用机械替换为 Net。网络池只能隔离线程与队列，不独占 CPU、内存；全局预算避免两个池分别耗尽整机资源。优先保证网络关键任务，同时不允许 Global 长期饥饿。

### 2.2 API 与所有权

- Tasks.pool()：唯一 Global 池；Tasks.nextPool() 保留 Deprecated 兼容入口并返回 Global。
- Sockets.scheduler()：唯一 Net 池，建议 lazy holder 或等价方式规避 Tasks / Sockets / CpuWatchman 静态初始化循环。
- 保留 Tasks.executor() 非拥有型包装，避免外部库 shutdown 全局池。共享池的真实关闭只能由框架生命周期管理，明确裸池访问器所有权；不能让关闭单个连接或 Server 关闭共享池。
- Sockets.newRpcScheduler() 先迁移内部调用，再废弃旧公开工厂；不能把 new 语义悄悄改成返回共享池，否则历史调用方可能 shutdown 共享实例。
- 删除内部 activePools、retiringPools、poolCount、P2C、hash 路由、fallback、退休扫描；replicas 旧配置接受但忽略并一次性告警（不再支持副本热调整）。
- 保留 SERIAL/SINGLE/TRANSFER/PRIORITY、Trace、FastThreadLocal 上下文、取消和原有任务结果语义。移除 hash 分发前验证 SINGLE 的全局互斥保证；PRIORITY 的扩容提示移至提交阶段，不擅自修改 FIFO/串行顺序。

## 3. 扩缩容模型：快扩慢缩

三个决策路径，不额外创建补偿线程池：

1. Demand Scaling：任务提交、积压、队列等待时间驱动普通扩容，使用共享缓存 CPU 预算和当前有效执行能力。
2. Managed Blocking：对明确会阻碍任务推进的依赖等待，即时、有限补偿；与普通 CPU 扩容限制分离。
3. CpuWatchman：统一 CPU 采样、反馈修正、预算协调、持续空闲缩容和诊断。保留原先以 CPU 利用率反推扩容需求的思想，但不让 3 秒轮询成为唯一入口。

任务提交、阻塞进入、阻塞结束、启动成功/失败、队列状态变化均可发出合并后的控制信号。已有任务积压但没有新提交时仍须由独立控制路径继续检查，不能依赖下一次任务结束。监控/扩容控制不得排到 Global 或 Net 业务队列中；优先复用现有监控基础设施，不再创建一套通用业务池。

## 4. 如何识别 CPU 密集还是 I/O Wait

Java Runnable/Callable 无法预知真实负载；不强制业务方标注，不根据任务类名建立长期画像，不做机器学习。

利用 Java 8 ThreadMXBean 对活跃 Worker 取两次 CPU 时间：

```text
r_i = deltaThreadCpuNanos / deltaWallNanos  (0..1, 单核等效)
R_pool = sum(validCpuDelta) / sum(validActiveWallDelta)
```

- Worker 需处于任务执行区间；剔除空闲、启动中、采样无效、跨任务窗口无法正确归属的样本。Managed Blocking 区间单独记录，普通 CPU 消耗估算不把已补偿等待重复计入。无需每个任务分配采样对象。
- R_pool 仅是当前可代表负载的 CPU 消耗估计；优先采用短窗口 EWMA，任务类型突变时降置信度。
- ThreadMXBean.getThreadCpuTime 支持性、enabled 状态、-1、线程退出、计数回退均需处理；不支持时回退到 CPU + 队列 + 小步探测，不让监控异常影响执行。已有 diagnostic.ResourceSampler 使用该 API，可复用底层能力而不是依赖诊断服务运行。
- Java RUNNABLE 可能在 native socket read；WAITING/TIMED_WAITING 可能是空闲、睡眠、锁或依赖；BLOCKED 指 monitor 竞争。仅作辅助，不把状态当作 I/O 类型。
- 非 CPU 时间不等于 I/O Wait：CPU 调度竞争、GC、安全点、锁竞争、连接池耗尽、下游限流都会造成低 CPU 比例。单核上的十个 CPU 密集线程也可能各只有约 10% CPU 时间。
- 使用 JVM/系统 CPU 利用率共同约束：系统 CPU/容器限额优先保护整机，进程 CPU 观察自身负载；统一单位和可用核数，避免重复归一化。Java 8 容器 CPU 识别因版本/部署不同而异，允许明确覆盖有效 CPU 核数并校验。
- 已知锁竞争、资源耗尽和下游过载不应触发无上限补偿；低 CPU + 高排队也不能单独证明扩容有益。

### 4.1 两级采样

| 模式 | 指标/频率 |
|---|---|
| 常驻轻量 | active/idle/starting、managedBlocked、O(1) queue depth、缓存 CPU；常规 CPU 约 1–3s |
| 积压升档 | 仅持续饱和/排队时，约 100–250ms 反馈；按预算采样活跃 Worker CPU、队列年龄、必要时 Thread.State |

初始反馈周期 200ms，恢复后自动降档。CPU 高负载、采样开销超预算时降低频率。管理器须支持亚秒级唤醒（原 CpuWatchman 时间轮 tick 800ms 不能直接宣称支持 200ms）；选择低成本独立控制路径并压测。禁止每次 submit 获取 MXBean、扫描 Worker、获取调用栈或做全局锁竞争；队列年龄可抽样或复用任务时间戳，不强制为所有任务增加对象。

## 5. 普通扩容数量计算

### 5.1 公式

C = 有效可用 CPU 核数；U_target = CPU 目标利用率（初始 0.70）；U_current = 可信当前 CPU 利用率（0..1，整机/进程统一核算）；R = 当前池每活跃 Worker 的单核等效 CPU 比例。

```text
H_cpu = C * max(0, U_target - U_current)
H_available = max(0, H_cpu - 尚未反馈的扩容 CPU 预留)
cpuGrowth = floor(H_available / max(R, minWorkerCpuRatio))

demand = max(0, runnableQueued - idleWorkers - startingWorkers)
step = min(maxGrowthStep, max(1, ceil(effectiveWorkers * growthFactor)))
growth = max(0, min(demand, cpuGrowth, step,
                    normalRemaining, poolHardRemaining, globalRemaining))
```

- effectiveWorkers：扣除已知阻塞的可执行能力；启动中线程单独计入 demand，避免重复。只计算可调度任务；SERIAL 内部尚未到队首的任务不等于 runnableQueued。
- normalRemaining：常规容量预算余量，扣除在途预留；poolHardRemaining/globalRemaining 同时扣除已存活与预留启动线程。补偿线程回到可执行状态时需重新核算，不能把它们永久视为不消耗 CPU。
- 两池使用同一 CPU 采样窗口及原子预算预留；不能每次提交重新花掉相同 CPU headroom。每次启动失败/取消归还预留，成功后等新鲜有效采样吸收，不无限叠加旧数据。
- U_current >= 高水位时停止普通扩容，回落后恢复；使用迟滞避免抖动。CPU 采样无效/过期或 R 无代表性时，在资源和探测节流允许且有真实需求时最多试增 1，待反馈后继续；已知 CPU 饱和则不探测。
- 扩容不能突破线程/内存/下游准入限制。队列任务负载可能与正在运行的不同，公式仅用于估算，不承诺精确最优值。
- 经典 N = C * U_target * (1 + W / S) 可用于离线解释/估算稳定混合负载；不能把锁竞争、CPU 调度等待、GC 直接当 W 后据此扩容。

### 5.2 示例

1 核、当前 CPU 30%、目标 70%，可用算力 0.4 核（无其他在途预留）。

| Worker CPU 比例 | 理论可新增线程（floor） |
|---:|---:|
| 100% | 0 |
| 50% | 0 |
| 20% | 2 |
| 10% | 4 |
| 5% | 8 |
| 2% | 20 |
| 1% | 40 |

当前有效 Worker 8、积压 20、R=5%，growthFactor=0.5，maxGrowthStep=8，预算充足：理论 8，本轮步长 4，实际先增加 4，之后用反馈决定是否再增加。不能一次性增加理论上的全部线程。

动态步长示例：N=1/2/4/8/16/32 时步长=1/1/2/4/8/8。固定值只作为安全边界和回退探测，不再以 resizeStep=2 作为唯一策略。

## 6. Managed Blocking 与依赖推进

### 6.1 统一等待入口

在 ThreadPool 层提供 managedBlock / beforeManagedBlock + afterManagedBlock（命名待实现），依据 PoolWorkerThread.ownerPool 获得实际所属池，而非固定 Tasks.pool()。非 Worker 快速直通；最外层等待计数一次，finally 恢复；已完成/零等待的 Future 不触发补偿。EventLoop、时间轮线程不能使用该 API 合法化阻塞。

接入范围：

- Tasks.await 及存在的等待变体、框架 Future.get / timed get，评估自有 CompletableFuture.join/get 包装。避免双重计数并保持异常、取消、interrupt 语义。
- ResetEventWait.waitOne：RPC MethodResponse、NetEventWait 等；仅在实际等待区间接入，不在持有 monitor 时做重型扩容或调用用户代码。
- Remoting.awaitComputedArgs 的 Netty Promise.await；同步 RPC、同步建连等等待按调用路径审核。
- ObjectPool.borrow 的资源等待单独分类：外部资源耗尽默认背压，不等同可补偿的任务依赖；确认存在能释放资源的待执行任务时才有限保护。
- 普通第三方 Future/锁/阻塞 I/O 不保证自动感知；按需 CPU 采样提供保守辅助。

建议阻塞原因至少区分 DEPENDENCY（依赖可推进任务）、IO（外部 I/O）、RESOURCE/UNKNOWN（资源或未知）；只有确认依赖推进需要的等待允许绕过普通 CPU 水位。I/O 等待通常使用普通 CPU 预算扩容，不能让资源等待无限消耗补偿预算。

### 6.2 补偿公式

```text
blockedNeed = max(0, dependencyBlocked - alreadyCompensatedOrReserved)
runnableNeed = max(0, runnableQueued - idleWorkers - startingWorkers)
blockGrowth = max(0, min(blockedNeed, runnableNeed,
                         compensationRemaining,
                         poolHardRemaining, globalRemaining))
```

在等待进入、等待结束、提交、启动失败等事件上重新计算；无积压时不盲目启动补偿线程。等待已发生、后续 Netty/外部线程才提交任务的情况也必须触发目标池检查。

跨池依赖：对明确已知目标池的 Future 或框架 RPC 路径记录需求到目标池，不能只补偿等待者所在池；未知目标时由两个池各自的待执行需求、可用 Worker 和有限依赖推进预算保守决策，不声称能自动识别所有跨池依赖。禁止持有 A 池管理锁时获取 B 池管理锁；全局预算单向获取，不建立互锁。

需要覆盖：普通子任务尚未入队、队列未满、队列已满、跨 Global/Net、递归多层依赖、RPC EventLoop 提交、等待超时/取消、补偿线程自身又等待。已接受任务达到硬上限时必须有明确失败/超时及关键响应推进策略，不能保证任意阻塞深度；不通过无限 CallerRuns 或执行任意队列任务规避真实循环依赖。SERIAL 同 ID 后继同步等待、同一 Future 自等待等可识别不可能推进的情况应 fail fast；其他真实循环依赖由超时/业务修正解决。

## 7. 即时启动 Worker 的实现约束

优先利用现有 ThreadPoolExecutor，不反射访问 JDK 私有 addWorker，不重写执行器主体，不改用 ForkJoinPool/虚拟线程。

- 普通 submit 走原快速路径，O(1) 判断空闲/启动/积压状态；存在需求才 CAS 合并扩容请求。
- 首次积压可以立即提出扩容请求（有可信 CPU 预算时按公式，未知时节流探测）；不必等待 beforeExecute、afterExecute 或常规 CPU 采样。
- 首选在独立控制慢路径中增加 setCorePoolSize 并让 JDK 启动 Worker 消费已有队列；当前实际核心目标 = 常规目标 + 有限补偿目标，统一序列化调整，避免 CpuWatchman 与提交/补偿互相覆盖。正常目标和补偿目标分别记账，不改变旧 maxPoolSize 的硬上限语义。
- offer(false) 触发 JDK Non-Core 是可选的提交快速扩容路径，必须在容量/资源预留成功后使用；不能对每个嵌套任务无条件扩容。JDK 并发扩容失败时，若池仍开放且队列有空间，应安全尝试重新入队并复核 shutdown 状态；不可重复执行、泄露 queue permit 或把主动拒绝变成无界等待。若此路径复杂，先采用入队 + 统一控制启动 Worker。
- EventLoop / WheelTimer / 任意 rxlib PoolWorkerThread 向饱和池提交不得无限 BLOCK。普通外部生产者仍可使用原有 BLOCK/TIMED/CALLER_RUNS 语义（非阻塞线程禁止 CallerRuns 任意用户任务）。线程创建、MXBean、扫描、日志不得在 I/O 热点阻塞；非阻塞信号唤醒控制路径，实际启动时延纳入验收。
- 不在 SERIAL map compute、队列/资源锁、ResetEventWait monitor 内调用用户代码或执行复杂扩容。
- fixed/custom pool 保持原固定/自定义容量语义，不默默动态化；需要时允许显式开启补偿。
- 缩容：持续无需求且空闲约 30–60s 后缓慢降低常规目标；补偿等待结束释放逻辑需求，实际线程空闲后按 keepAlive 回收。不要强制中断运行中任务；注意 core timeout、minIdleSize、shutdown、动态配置和 JDK core/max 修改顺序。
- 限制高频日志；计数器选择 AtomicInteger/LongAdder 等并明确精确预算与近似统计的区别。不能依赖每次 getActiveCount()/getPoolSize() 的锁或遍历；池内维护必要轻量状态，最终与实际 Worker 生命周期校验。

## 8. RPC、事件、时间轮的关键保护

源码审查定位：Remoting.awaitMethodResponse 使用 ResetEventWait；普通 MethodMessage 响应在接收路径 completeMethodResponse -> syncRoot.set，不需要再排入业务池，应保持此属性。RPC Server 默认 invokeAndReply 使用 Transport scheduler；客户端 EventMessage 回调以及服务端 COMPUTE_ARGS 处理也使用该 scheduler。

- 普通 RPC 响应、取消、断开唤醒、连接状态更新等轻量且非阻塞的控制动作尽量直接完成，不调度任意用户 listener 到 EventLoop；注意 CompletableFuture/Promise 完成可能同步执行 continuation，必须审计。
- COMPUTE_ARGS：客户端事件计算仍在 Net 业务池；服务端响应目前 BeanMapper.map + computedPromise.trySuccess，不能不经审计整体移到 I/O 线程。优先拆分轻量完成与可能昂贵的映射/回调，或通过受保护调度容量保证响应推进。保留同步计算事件的返回语义及订阅顺序。
- 网络控制任务需要有限保留准入/执行容量，避免普通 RPC 洪峰把关键响应、超时、释放任务堵在同一无界 FIFO 后。可用独立逻辑 lane/保留配额或安全直接完成；不是新增业务线程池，不能仅靠 PRIORITY 名称宣称有保障。具体策略需验证公平性、SERIAL 顺序和过载拒绝。
- RPC 请求拒绝须显式返回过载错误或关闭失败连接，不能吞掉 rejected Future 让调用者只能等待超时；ByteBuf retain/release 严格成对，拒绝也释放；检查 Socks5Client 等异步回调。
- 复用一个业务 WheelTimer，新增/复用显式 Executor 路由能力；网络定时回调投 Net，通用投 Global。保持时间轮线程非阻塞。
- 先修复上一轮发现的 one-shot timer transient rejection 丢任务问题：有界重试、明确最终失败/补偿、区分 shutdown 与临时饱和、取消/截止时间语义。RPC timeout、重连、ObjectPool minIdleMaintaining backoff、资源清理均不得静默丢失。到期超时信号若可安全直接完成，应独立于饱和业务队列。

## 9. 资源配置与兼容

建议先用内部常量或最少必要配置实现，压测后再决定公开字段；下列是候选起始值，不是已经确定的最佳默认值。

| 参数 | Global | Net |
|---|---:|---:|
| minIdleSize | 1 | 1 |
| 常规 Worker 上限（RSS Server 压测） | 4 | 8 |
| 额外依赖补偿预算（压测） | 4 | 8 |
| 实际硬上限（压测） | 8 | 16 |
| keepAliveSeconds | 60 | 60 |

共享整机/进程线程硬预算：压测先以两个默认业务池合计 24 为上界，并根据 JVM/Netty/OS/线程栈/堆外内存重新核算，不把 24 当成整机线程上限。实际线程栈 RSS 不等于 Xss 预留，需实测。大规格部署及 RSS Client 不应被强制限制在该表。

```yaml
# 设计示意，非现有配置
threadPool:
  scaling:
    targetCpu: 70
    growthFactor: 0.5
    maxGrowthStep: 8
    minWorkerCpuRatio: 0.01
    activeSamplingMillis: 200
    idleSamplingMillis: 3000
    shrinkIdleSeconds: 60
    managedBlocking: true
```

兼容原则：现有 maxPoolSize 保持线程硬上限（不解释为常规上限再额外加补偿），新 normalMax/compensationMax 在硬上限内分配；两个池的配置可继承共享默认值再做少量覆盖。resizeStep 可先兼容为步长上限/明确废弃，避免静默改变自定义配置；replicas 废弃；queueCapacity 保留真实有界和原特殊值解释（检查有效容量而非直接使用配置 0）。共享队列合并后按总内存/任务成本重新压测容量，不能直接加总旧副本容量。保留 patchCompletableFutureAsyncPool 默认关闭，不用 Unsafe 全局替换解决等待识别。

## 10. 分阶段实施（每阶段独立测试）

- [ ] P0 / Phase 0：冻结 baseline 与指标；复现队列未满饥饿、满队列重入、跨池等待、普通扩容慢、RPC COMPUTE_ARGS、timer rejection。先处理上一轮 P0/P1 影响推进的缺陷（SERIAL 取消/队列移除/关闭、timer、ObjectPool 维护拒绝等）。
- [ ] P0 / Phase 1：ThreadPool Worker 轻量状态、统一预算、即时需求扩容、动态步长、并发扩容及拒绝安全；保留现有多副本用于对照，不先合并。
- [ ] P0 / Phase 2：Managed Blocking、依赖目标/原因、跨池非阻塞提交、ResetEventWait/RPC 等接入、关键响应保护；验证无新任务提交也能推进既有积压。
- [ ] P1 / Phase 3：CpuWatchman 快慢采样、Worker CPU Time、EWMA/有效 CPU、共享预算、降档/缩容；无效采样保守回退；消除旧 PRIORITY 执行后扩容依赖。
- [ ] P1 / Phase 4：Tasks 单池化，删除 P2C/副本退休逻辑，保留兼容 API；建立 Sockets Net 动态池，迁移 TCP/Transport/Remoting/EventPublisher/网络定时任务。保留自定义 Executor 与 I/O 隔离。
- [ ] P2 / Phase 5：评估 HTTP/HttpTunnel/其他网络异步调用，按职责迁移；完成基准对比、配置说明、.memory/thread_and_timer.md 等 ADR 更新；不为架构统一强制迁移需要隔离的实现。

Phase 1–3 验证通过前不删除原有独立 Scheduler。阶段顺序以可独立回归为准。若自适应 CPU 估算的收益不足以覆盖采样成本，允许退回 CPU + 需求 + Managed Blocking + 动态步长的低成本版本；不回退到依赖副本随机分发来解决死锁。

## 11. 测试矩阵与验收

### 11.1 正确性

| 场景 | 必须验证 |
|---|---|
| 1 Worker / Queue 未满，父任务同步等待子任务 | 旧任务仍未完成时新 Worker 可执行子任务；不等周期采样 |
| 已阻塞后外部/Netty 才提交任务 | 同样触发推进 |
| Queue 满、并发扩容竞争、shutdown | 无丢任务、重复执行、permit 泄漏、无限阻塞 |
| 多层及跨 Global/Net 依赖 | 硬预算内推进；达到上限明确失败/超时 |
| 真正循环依赖、SERIAL 同 ID 后继 | fail fast 或有界超时，不无限增线程 |
| Managed Blocking 嵌套、interrupt、cancel、异常 | 计数及预算恢复，原异常语义不变 |
| CPU 密集、锁竞争、资源池耗尽 | 不误判为可无限扩容的 I/O |
| 低 CPU I/O、混合负载突变、MXBean 不支持/失效 | 保守反馈扩容，不依赖任务完成 |
| 两池同时请求扩容 | 不重复消费同一 CPU 预算、不突破总线程限制 |
| RPC LocalChannel / 双向嵌套 / COMPUTE_ARGS | 请求、计算回调及响应均可推进，超载可观测 |
| 网络池/全局池分别饱和 | 另一池仍可执行其独立任务；关键超时/清理可推进 |
| Netty EventLoop / WheelTimer | 非阻塞、无任意 CallerRuns；引用计数正确 |
| SERIAL/SINGLE/TRANSFER/PRIORITY | 顺序、互斥、取消、拒绝、上下文/Trace 保持 |
| 缩容、启动失败、动态配置、关闭网络对象 | 无线程泄漏、死锁、共享池误 shutdown |

### 11.2 性能实验

对同一 JDK/机器/参数与数据，比较 A=当前 master 多池、B=双池+即时需求、C=B+Managed Blocking、D=C+Worker CPU Time；分离架构与采样收益。用相同有效队列容量/硬线程预算做公平对照，并另测实际默认配置。预热、重复多轮，保存吞吐与延迟分布、环境、采样周期、配置及 commit SHA。不能只测平均值或空跑。

- 单核 768 MiB RSS Server（含 OS）为必须通过的资源基线；另测多核与 RSS Client。
- 负载：纯 CPU、可控阻塞 I/O（例如真实本地 socket）、CPU+I/O 混合、短任务高频提交、阶跃突发、持续过载、间歇负载、RPC 双向及网络代理端到端。sleep 可用于可重复的等待模拟，不代替真实 I/O 测试。
- 指标：TPS/吞吐字节、P50/P95/P99、队列等待 P99、首次需求到 Worker 开始执行耗时、启动批次与失败、峰值/空闲线程、managedBlocked/compensation 数、CPU、上下文切换、GC、RSS（进程驻留内存）、堆外、连接数、拒绝/超时/重试、EventLoop 延迟。
- 初始建议验收门槛（待 baseline 验证）：有预算且已有可执行积压的首次扩容不依赖任务结束或 3s 采样；空闲控制线程条件下 P95 启动目标 <= 50ms，未知负载首次探测后约 200ms 反馈；CPU 密集稳态吞吐相对 baseline 不降低超过 5%，短任务热路径无新增每任务分配；I/O 突发 P99 争取显著改善且无额外丢失；整机不 OOM、不失控扩容。数值为拟议测试目标，不是已实测保证；任何不达标需记录原因并调整，不能以更多线程换取不可接受的 CPU/内存开销。

### 11.3 工程流程

- Java 8 master 优先；在 ThreadPoolHardeningTest / ObjectPoolHardeningTest 及新增确定性并发测试中使用 latch/barrier、明确超时，不依赖 sleep 猜竞态。
- 执行 Maven 测试显式启用 `-DskipTests=false -Dmaven.test.skip=false`，不能把跳过测试的 SNAPSHOT 发布当作通过证据。
- 网络回归：RemotingTest、SocksProxyServerIntegrationTest、ShadowsocksServerIntegrationTest、Socks5ClientIntegrationTest、RrpIntegrationTest、DnsServerIntegrationTest；补 TCP/Transport、timer、ByteBuf 泄漏测试。
- master 验证并提交后，rss-21 仅通过 git merge master 接收；检查 merge-base --is-ancestor master rss-21，执行 JDK21 回归及部署包构建；真机使用隔离临时端口与目录，不影响线上服务。

## 12. 最终决策与非目标

确定方向：Global + Net 两个默认动态业务池；普通需求驱动快扩、CPU 反馈慢缩、Worker CPU Time 按需估算、明确依赖 Managed Blocking、全局硬资源预算。固定步长仅保留兼容/安全边界，不作为主要扩容算法。CPU/IO 分类是在线推断而非绝对识别；真正的循环依赖、第三方不可见阻塞、资源枯竭不能承诺由线程池完全解决。

非目标：增加多个业务池、无界队列/线程、强制任务类型标记、JDK 私有反射、默认修改 CompletableFuture 全局执行器、强行迁移全部 Executor、引入复杂画像/机器学习。优先复用现有 ThreadPool/ThreadQueue/CpuWatchman/Timer 结构。

本文参数、门槛、API 命名及控制路径实现为建议方案，需编码和 Benchmark 后确认。本文提交只包含计划，不修改源码、配置、分支历史或测试结果。
