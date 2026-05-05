# 背景

用户要求对 `rockylomo/rxlib` 仓库中当前 `org.rx.core` package 做仔细 review。本阶段只提交计划文档，不修改业务代码。

基线信息：

- 仓库：`RockyLOMO/rxlib`
- 基线分支：`master`
- 基线提交：`f18565bdef7c0b58d020b3035b45ff116c4ec26e`
- 目标目录：`rxlib/src/main/java/org/rx/core`
- 子包：`org.rx.core.cache`、`org.rx.core.config`

# 任务类型判断

本次归类为 **Review / 修复 / 重构 / 优化需求**。

原因是用户明确要求“仔细 review”，目标是检查已有核心包的实现质量、调用链、边界条件、资源释放、并发和兼容性风险，而不是新增功能。按流程应先 review 相关代码并提交计划文档，用户明确要求后再进入代码实现阶段。

# 当前上下文

## 已 review 的文件

主包已扫描：`Arrays.java`, `Cache.java`, `CachePolicy.java`, `Constants.java`, `CpuWatchman.java`, `Delegate.java`, `Disposable.java`, `EventArgs.java`, `EventBus.java`, `EventPublisher.java`, `Extends.java`, `FluentWait.java`, `ForkJoinPoolWrapper.java`, `IOC.java`, `IdentityWrapper.java`, `JsonTypeInvoker.java`, `Linq.java`, `NEventArgs.java`, `NtpClock.java`, `Numbers.java`, `ObjectChangeTracker.java`, `ObjectChangedEvent.java`, `ObjectPool.java`, `Reflects.java`, `ResetEventWait.java`, `RunFlag.java`, `RxConfig.java`, `ShellCommand.java`, `StringBuilder.java`, `Strings.java`, `Sys.java`, `Tasks.java`, `ThreadPool.java`, `ThreadPoolQueueOfferMode.java`, `TimeoutFlag.java`, `TimeoutFuture.java`, `WaitHandle.java`, `WheelTimer.java`, `YamlConfiguration.java`。

子包已扫描：`cache/H2CacheItem.java`, `cache/H2StoreCache.java`, `cache/IntCompositeKey.java`, `cache/MemoryCache.java`, `config/ConfigChangeDetector.java`, `config/ConfigChangedEventArgs.java`, `config/ConfigReloadEventArgs.java`, `config/ConfigResource.java`, `config/ConfigResourceBinding.java`, `config/ConfigSource.java`, `config/ConfigValidator.java`, `config/YamlConfigSource.java`。

已定位相关测试：`ThreadPoolTest`, `ThreadPoolQueueOfferModeTest`, `ThreadPoolQueueShutdownTest`, `SerialQueueCapacityTest`, `ThreadPoolWheelTimerRegressionTest`, `WheelTimerShutdownPeriodicTest`, `CpuWatchmanResizeTest`, `TasksTest`, `TasksCompatibilityTest`, `ObjectPoolTest`, `ObjectPoolRecycleOwnershipTest`, `RxConfigTest`, `YamlConfigurationTest`, `ReflectsCompatibilityTest`, `ShellCommandTest`, `LinqTest`, `DelegateTest`, `ResetEventWaitTest`, `NtpClockTest`，以及 `org.rx.core.cache`、`org.rx.core.config` 下测试。

## 关键调用链

### 并发和调度链

`Tasks` 是上层任务入口，委托 `ThreadPool` 执行同步、异步、串行、single、延时和周期任务。`ThreadPool` 自定义 `ThreadQueue`、`Task` 包装、serial task chain、single task guard、trace id、ThreadLocal 继承，并与 `CpuWatchman` 动态 resize 相关。`WheelTimer` 提供延时和周期调度，影响 cancel、shutdown、异常传播和任务重排。

### 对象池和生命周期链

`ObjectPool` 管理对象创建、借出、回收、验证、销毁和事件通知。`Disposable`、`Delegate`、`EventPublisher` 参与资源释放与回调。该链路的核心风险是所有权、重复 recycle、close 期间活跃对象、回调异常和锁内回调。

### 配置链

`RxConfig` 是核心配置入口，影响 thread pool、trace、cache、yaml 等全局行为。`YamlConfiguration` 和 `config` 子包提供配置 source、binding、reload、change detector、validator。该链路需要重点关注热加载原子性、失败回滚和监听器异常隔离。

### 缓存链

`Cache` 与 `CachePolicy` 定义缓存抽象。`MemoryCache` 处理内存缓存、TTL、过期和容量。`H2StoreCache` 处理持久化缓存，涉及 JDBC、文件、事务、序列化、清理和关闭。

### 通用工具链

`Reflects`、`Linq`、`Strings`、`Numbers`、`Sys`、`ShellCommand` 被大量调用。风险集中在反射兼容性、classloader 缓存泄漏、延迟迭代副作用、shell 进程超时和 stdout/stderr 消费。

## 当前实现意图

`org.rx.core` 是 rxlib 的基础层，承担并发调度、配置、缓存、对象池、事件、反射、集合和系统工具能力。该包改动影响面很大，后续修复必须保持 Java 8 兼容并采用最小改动。

## 已发现的问题或风险

本阶段列出的是 review 后需要验证的风险，不等同于已确认 bug：

1. `ThreadPool.ThreadQueue` 用 `counter`、`Semaphore availableSlots` 和 `LinkedTransferQueue` 实现容量，必须确认 `offer`、`transfer`、`remove`、`poll`、`take`、`drainTo`、`clear`、`shutdownNow` 都能对称释放 slot。
2. `ThreadPool` 对 Netty `InternalThreadLocalMap` 私有字段/构造器有强反射依赖，Netty 版本或运行时策略变化时可能初始化失败。
3. `ThreadPool` 对 `CompletableFuture.AsynchronousCompletionTask` 私有字段 `fn` 有反射读取，JDK8 小版本差异或未来 JDK 可能导致失败，需要降级路径。
4. `ThreadPool` serial chain 依赖全局 `taskSerialMap` 和 `taskSerialCountMap`，需确认异常、拒绝、取消、shutdown、reuse=true 时不会泄漏 key 或阻塞同 key 后续任务。
5. `ThreadPool` 队列满后的 caller-runs 路径要确认与 worker 线程路径具有一致的 `beforeExecute/afterExecute` 清理语义。
6. `WheelTimer` 周期任务在 cancel、shutdown、异常、任务重入之间可能有竞态，需要确认不会 shutdown 后重排或取消后再次执行。
7. `ObjectPool` 需要确认重复 recycle、close 后 borrow、close 期间 active object、validator/destroy 抛异常时不会重复释放或泄漏。
8. `ObjectPool` 事件通知如果在锁内执行可能导致死锁或用户回调重入；如果在锁外执行则要保证状态快照一致。
9. `H2StoreCache` 的 JDBC、文件和后台清理任务生命周期较重，异常路径需要逐项确认释放。
10. `RxConfig` / `YamlConfiguration` 热加载需要确认解析失败、验证失败、监听器异常时保留旧配置。
11. `ShellCommand` 需要确认 stdout/stderr 并发消费、超时 kill、进程树释放、字符集和输出容量限制。
12. `Reflects` / `Linq` 需要确认缓存 key 不会持有 classloader 导致泄漏，延迟迭代不会被隐式多次消费造成副作用。

# 目标

1. 对 `org.rx.core` 主包、`cache` 子包和 `config` 子包做分层 review。
2. 识别并发、资源释放、异常恢复、兼容性和测试盲区。
3. 形成后续可执行的最小修复清单。
4. 明确每个候选修复需要补充的单元测试或回归测试。
5. 保持 Java 8 兼容，不使用 JDK9+ API。
6. 后续进入实现阶段时，优先修复可稳定复现并可测试验证的问题。

# 非目标

1. 本阶段不修改业务代码。
2. 本阶段不升级依赖大版本。
3. 本阶段不调整 release、snapshot、部署 workflow。
4. 本阶段不修改 secrets、token、证书、私钥。
5. 本阶段不做大范围格式化或无关重构。
6. 本阶段不改变 public API。
7. 本阶段不删除现有测试。

# 设计方案

## 第一层：并发核心

重点文件：`ThreadPool.java`, `Tasks.java`, `WheelTimer.java`, `CpuWatchman.java`, `ForkJoinPoolWrapper.java`, `ResetEventWait.java`, `FluentWait.java`, `WaitHandle.java`。

检查内容：队列容量计数和实际元素数量是否一致；阻塞 offer 是否能被 shutdown 唤醒；caller-runs 是否完整执行 trace、ThreadLocal、single lock 清理；serial/single/priority flag 组合行为；`CompletableFuture`、`FutureTask`、`Runnable` 包装是否造成任务身份丢失；schedule、periodic、cancel、shutdownNow 是否留下悬挂 future；中断标记是否保留；动态 resize 是否符合 JDK8 `ThreadPoolExecutor` 约束。

## 第二层：资源池和生命周期

重点文件：`ObjectPool.java`, `Disposable.java`, `EventPublisher.java`, `Delegate.java`, `ObjectChangeTracker.java`。

检查内容：borrow/recycle/close 状态机；对象所有权和重复归还保护；validator、destroy、factory、事件回调抛异常时的状态恢复；close 后阻止新 borrow，并安全处理 active/idle object；事件回调是否可能在锁内执行；`Disposable` 幂等性。

## 第三层：配置和 YAML

重点文件：`RxConfig.java`, `YamlConfiguration.java`, `config/*`。

检查内容：配置加载失败是否回滚；reload、changed、validator 的异常隔离；配置源资源关闭；多线程读取配置时的可见性；binding 更新是否原子；YAML 类型转换、默认值、空值和集合覆盖策略。

## 第四层：缓存

重点文件：`Cache.java`, `CachePolicy.java`, `MemoryCache.java`, `H2StoreCache.java`, `H2CacheItem.java`, `IntCompositeKey.java`。

检查内容：TTL、idle、capacity、evict 策略一致性；H2 连接与事务生命周期；清理线程或任务 shutdown；序列化失败、反序列化失败、DB 损坏时的降级；key equality/hashCode 稳定性；并发 put/get/remove/clear 一致性。

## 第五层：通用工具

重点文件：`Reflects.java`, `Linq.java`, `Strings.java`, `Numbers.java`, `Arrays.java`, `Sys.java`, `ShellCommand.java`, `NtpClock.java`。

检查内容：反射缓存是否可能泄漏 classloader；JDK8 兼容性；集合延迟执行和多次遍历副作用；shell 命令超时、输出消费、资源释放；NTP 网络调用超时和 fallback；字符串/数字工具对 null、空串、极值、溢出的处理。

## 后续修复原则

1. 每次只修复一个可验证问题。
2. 每个修复都补充单元测试或回归测试。
3. 对 public API 行为变更保持谨慎，优先兼容旧行为。
4. 对 `ThreadPool`、`WheelTimer`、`ObjectPool` 的修复避免扩大锁范围和引入死锁。
5. 反射兼容问题优先增加失败降级和诊断日志，不直接升级依赖。

# 修改文件列表

本阶段实际修改：`docs/plan/review-org-rx-core-20260505.md`。

后续如用户明确要求“按计划执行”，预计可能修改：`ThreadPool.java`, `WheelTimer.java`, `ObjectPool.java`, `RxConfig.java`, `YamlConfiguration.java`, `MemoryCache.java`, `H2StoreCache.java`, `YamlConfigSource.java`, `Reflects.java`, `ShellCommand.java`，以及相关 `rxlib/src/test/java/org/rx/core/**` 测试。

# 风险点

## 兼容性风险

- `ThreadPool` 对 Netty 内部类和 `CompletableFuture` 内部类反射，兼容性较脆弱。
- `Reflects` 可能受 JDK 反射访问策略变化影响。
- `Linq`、`Strings`、`Numbers` 是基础工具，修改影响面大。

## 性能风险

- `ThreadPool` trace stack sampling、serial chain、ThreadLocal copy 会增加任务提交成本。
- `ObjectPool` 若扩大锁范围，会降低高并发 borrow/recycle 性能。
- `H2StoreCache` 清理策略不当可能造成 I/O 抖动。

## 并发风险

- `ThreadPool.ThreadQueue` 计数和 semaphore 不一致会造成永久阻塞、错误拒绝或容量溢出。
- `taskSerialMap` / `taskSerialCountMap` 泄漏会导致同 key 任务长期堆积。
- `WheelTimer` 周期任务与 cancel/shutdown 竞态可能导致任务多执行或永不释放。
- `ObjectPool` active object 在 close/recycle 竞态下可能重复销毁。

## 资源释放风险

- H2 JDBC 资源、shell process streams、timer tasks、线程池队列 metadata 都需要检查异常路径。
- 用户事件回调异常可能中断释放流程。

## 测试风险

- 并发测试容易不稳定，应使用 `CountDownLatch`、`Semaphore`、`Atomic*` 和短超时做确定性控制。
- 时间轮和调度测试应避免真实长时间 sleep。
- H2 持久化测试应使用临时目录并确保清理。

# 验证方案

## 计划阶段验证

本阶段只提交计划文档，不触发代码 CI 作为通过依据。

## 后续实现阶段验证

如果用户明确要求进入代码实现阶段，每个代码 commit 后触发：

- workflow：`.github/workflows/jdk8-unit-tests.yml`
- workflow name：`JDK 8 Unit Tests`
- 触发方式：`workflow_dispatch`
- 分支：`agent/review-org-rx-core-20260505`

初始建议 `test_classes`：

```text
ThreadPoolTest,ThreadPoolQueueOfferModeTest,ThreadPoolQueueShutdownTest,SerialQueueCapacityTest,ThreadPoolWheelTimerRegressionTest,WheelTimerShutdownPeriodicTest,ObjectPoolTest,ObjectPoolRecycleOwnershipTest,RxConfigTest,YamlConfigurationTest,ReflectsCompatibilityTest,ShellCommandTest,TasksTest,TasksCompatibilityTest
```

本地等价 Maven 命令：

```bash
mvn -B -U -Dgpg.skip=true -Dmaven.test.skip=false -DskipTests=false -Dtest=ThreadPoolTest,ThreadPoolQueueOfferModeTest,ThreadPoolQueueShutdownTest,SerialQueueCapacityTest,ThreadPoolWheelTimerRegressionTest,WheelTimerShutdownPeriodicTest,ObjectPoolTest,ObjectPoolRecycleOwnershipTest,RxConfigTest,YamlConfigurationTest,ReflectsCompatibilityTest,ShellCommandTest,TasksTest,TasksCompatibilityTest clean test
```

CI 判定标准：`status=completed` 且 `conclusion=success`。

如果 CI 失败，先分类为编译失败、单元测试失败、格式失败、依赖下载失败、JDK 版本不兼容、环境问题、测试不稳定或 GitHub Actions 配置问题，然后只修改与失败直接相关的代码或测试，并再次提交和触发 CI。
