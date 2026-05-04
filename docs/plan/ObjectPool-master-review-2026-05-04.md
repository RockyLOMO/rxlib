# ObjectPool master 分支 review 计划

# 背景

用户要求在 `rxlib` 仓库 `master` 分支上 review `ObjectPool`。本轮只做 review 与计划提交，不修改业务代码。

当前基线：

- 仓库：`RockyLOMO/rxlib`
- 分支：`master`
- 基线提交：`20ac55ba74cd4a59b88f470ff0185ccf6578fd4d`
- 目标类：`rxlib/src/main/java/org/rx/core/ObjectPool.java`
- 兼容约束：保持 Java 8，不使用 Java 9+ API。

# 任务类型判断

本次归类为 **Review / 修复 / 重构 / 优化需求**。

原因：用户明确要求 review 当前 `ObjectPool`，未要求立即实现代码。按仓库流程，需要先 review 相关代码、记录调用链、风险点和验证方案，并提交 `docs/plan/*` 计划文档。只有用户后续明确说“按计划执行 / 开始修改代码 / 实现代码”后，才进入业务代码修改阶段。

# 当前上下文

已 review 文件：

- `rxlib/src/main/java/org/rx/core/ObjectPool.java`
- `rxlib/src/test/java/org/rx/core/ObjectPoolTest.java`
- `rxlib/src/test/java/org/rx/core/ObjectPoolRecycleOwnershipTest.java`
- `rxlib/src/test/java/org/rx/net/socks/Socks5SessionPoolTest.java`
- `docs/plan/ObjectPool-review-plan.md`
- `AGENTS.md`

关键调用链：

- `borrow()`：先尝试 `threadLocalCache` L1 hint，再从 shared idle poll；若池未满则 `doCreate()` 创建 borrowed 对象；失败或池满时等待 idle，最终超时抛 `TimeoutException`。
- `recycle(T obj)`：通过 `IdentityWrapper` 查找 `ObjectConf`，再进入 `recycle(ObjectConf<T>, boolean)` 做 validate/passivate、状态转换和重新入 idle 队列。
- `validNow()`：周期扫描 live 对象，对 borrowed 做 leak 检测，对 idle 做 timeout / lifetime / validate，并根据 borrow 采样触发自适应补建。
- `doCreateIdle()`：用于 min idle 和 target size 维护，master 最新实现已加入 idle 创建失败冷却，duplicate / validate failed / exception 会设置 100ms backoff。
- `doRetire()`：从 `conf`、idle 链表和 live 链表移除对象，减少 `totalCount`，唤醒等待 borrower，并按 action 决定是否 close。

当前实现意图：`ObjectPool` 以对象身份为 key，维护 `IDLE / BORROWED / RETIRED / VALIDATING` 状态机，支持最大池容量、min idle、borrow timeout、idle timeout、max lifetime、leak 检测、shared idle 队列、ThreadLocal hint 和诊断指标。

已发现问题 / 风险：

1. **P1：`recycle(ObjectConf<T>, boolean)` 在抢占归还所有权前执行 handler。** 当前代码先执行 `validateHandler.test(...)` 和 `passivateHandler.accept(...)`，再尝试 `VALIDATING -> IDLE`。如果同一个 borrowed 对象被多个线程重复 recycle，多线程可能都执行 validate/passivate，最终只有一个线程成功转回 IDLE。对 ByteBuf、Channel、session、UDP lease 等非幂等资源，可能导致重复 passivate 或重复释放外部资源。该风险与 `ObjectPoolRecycleOwnershipTest` 的测试意图冲突。
2. **P2：`threadLocalCache` 是 hint，功能正确性有 CAS 和 `conf.get` 防护，但长生命周期线程可能短期保留 retired `ObjectConf` 引用。** 当前不建议扩大修复范围。
3. **P3：create failure WARN 可能较密。** idle 创建已有 100ms backoff，borrow 直接创建已有 `backoffCreateFailure()`，但持续远端不可达时仍可能产生较多 WARN。当前建议先通过指标和日志策略观察。
4. **已确认历史 P1 已修复：duplicate idle object 导致 idle 维护 tight loop。** master 最新提交已加入 `idleCreateBackoffUntilNanos`，`insureMinIdle()`、`insureTargetSize()`、`triggerMinIdleMaintain()` 会在冷却期跳过补建。

# 目标

本轮 review 阶段目标：

1. 明确 `ObjectPool` 当前状态机、调用链和资源释放路径。
2. 识别 master 最新代码中的高风险并发 / 资源释放问题。
3. 提交计划文档，记录后续可执行的最小修复方案。
4. 不修改业务代码，等待用户明确授权后再实现。

如用户后续要求执行，代码阶段目标：

1. 修复 `recycle()` 的所有权抢占顺序，确保只有 CAS 成功线程执行 validate/passivate。
2. 补充或修正回归测试，覆盖重复 recycle 并发下 passivate 只执行一次。
3. 保持 public API、包名、依赖和 Java 8 兼容性不变。
4. 通过 JDK 8 相关 GitHub Actions 验证。

# 非目标

- 本轮不修改业务代码。
- 不改 `.github/workflows/**`。
- 不升级 JDK 或依赖版本。
- 不重构整个 `ObjectPool` 状态机。
- 不改变 public 方法签名或包名。
- 不改变默认 `closeObjectOnLeak=false` 语义。
- 不发布 release，不删除分支。
- 如进入代码阶段，也不处理 ThreadLocal 长期保留优化、日志聚合策略或 RPC / Socks / UDP 大范围压测框架改造。

# 设计方案

核心设计：将 `recycle(ObjectConf<T> c, boolean allowThreadLocal)` 调整为“先抢占状态，再执行 handler”。

建议流程：

1. 先确认 `conf.get(c.wrapper) == c`。
2. 执行 `c.casState(BORROWED, VALIDATING)`：成功者获得 recycle 所有权；失败者不得执行 validate/passivate。
3. CAS 失败时，如果对象已 retired 或已不再属于池，直接 return；否则保留现有 duplicate recycle 异常语义。
4. 只有拥有 `VALIDATING` 状态的线程执行 `validateHandler` 和 `passivateHandler`。
5. validate/passivate 失败时，通过 `VALIDATING -> RETIRED` 和 `doRetire()` 清理对象。
6. validate/passivate 成功时，再确认对象仍属于本池，并执行 `VALIDATING -> IDLE`，然后 `offerSharedIdle(c)`；如允许则写入 `threadLocalCache` 作为 L1 hint。
7. close/dispose 并发时避免 retired 对象重新进入 idle 队列。

类职责不变：`ObjectPool` 负责容量、borrow/recycle、idle 维护、validate/retire；`ObjectConf` 负责状态与链表节点；`IdentityWrapper` 负责对象身份语义；handlers 负责业务态转换。

接口变化：无 public API 变化。

异常处理：handler 抛异常时 retire 对象，并保持现有异常传播或 duplicate recycle 语义。资源释放仍统一走 `doRetire()`。

# 修改文件列表

本轮计划阶段新增：

- `docs/plan/ObjectPool-master-review-2026-05-04.md`

如用户后续要求进入代码阶段，预计修改：

- `rxlib/src/main/java/org/rx/core/ObjectPool.java`
- `rxlib/src/test/java/org/rx/core/ObjectPoolRecycleOwnershipTest.java`
- 可能补充：`rxlib/src/test/java/org/rx/core/ObjectPoolTest.java`

# 风险点

- 兼容性风险：调整 recycle 顺序不能改变 public API，也应尽量保持 duplicate recycle 的外部异常语义。
- 并发风险：recycle、validNow、close、doRetire 并发时，必须避免对象卡在 `VALIDATING` 或 retired 后重新进入 idle 队列。
- 资源释放风险：修复前风险是重复 passivate；修复后要确保 passivate 抛异常时不会泄漏对象或重复 close。
- 性能风险：不应引入全局锁；只调整 CAS 顺序，保持 shared idle 锁粒度不变。
- 测试风险：并发回归测试应使用 latch/barrier 控制 interleaving，避免依赖 sleep 导致 flaky。

# 验证方案

计划阶段：

- 仅提交文档，不触发代码级 CI 结论。
- 不把历史 master 的 workflow 成功或失败当成本分支验证结论。

代码阶段如果用户授权执行：

1. 触发 `JDK 8 Unit Tests` workflow。
2. `test_classes` 建议包含：`org.rx.core.ObjectPoolRecycleOwnershipTest`、`org.rx.core.ObjectPoolTest`、`org.rx.net.socks.Socks5SessionPoolTest`。
3. 若 workflow 支持 `workflow_dispatch`，手动触发；否则依赖 push 触发并说明限制。
4. 查询 workflow run 时按当前分支过滤。
5. 只有 `conclusion=success` 才认为 CI 通过。
6. 如果失败，按编译失败、单元测试失败、格式失败、依赖下载失败、JDK 版本不兼容、环境问题、测试不稳定或 Actions 配置问题分类。
7. 只修复与失败直接相关的代码，修复后再次提交并再次触发 CI。

# 2026-05-04 执行记录

本轮已进入代码阶段并完成本地修复验证。

## 修复结论

1. `ObjectPool.recycle(ObjectConf<T>, boolean)` 当前实现已符合计划中的核心修复方案：先通过 `BORROWED -> VALIDATING` CAS 抢占归还所有权，再执行 `validateHandler` / `passivateHandler`。CAS 失败线程不会执行 passivate，避免 ByteBuf、Channel、session、UDP lease 等非幂等资源被重复 passivate 或重复释放。
2. 本次实际修改集中在 `ObjectPoolRecycleOwnershipTest`：`testStaleThreadLocalHintDoesNotBorrowRetiredObject` 原先断言 invalid idle 校验后 `pool.size()==0`，但 `validNow()` 可能按自适应 target 立即补建新 idle，对象池总量不一定归零。测试已调整为等待旧对象身份不再属于池，并继续验证后续 borrow 不会返回旧 ThreadLocal hint。
3. 未改 public API、包名、依赖和 Java 版本语法；保持 Java 8 兼容。

## 本地验证结论

已执行：

```powershell
mvn -pl rxlib "-Dtest=ObjectPoolRecycleOwnershipTest,ObjectPoolTest,Socks5SessionPoolTest" test --batch-mode --no-transfer-progress
```

结果：

- Tests run: 41
- Failures: 0
- Errors: 0
- Skipped: 0
- BUILD SUCCESS

## 高性能风险复核

- 内存泄漏：本次未新增 ByteBuf/Channel 持有路径；旧对象退休校验改为身份级断言，覆盖 stale ThreadLocal hint 不会借出 retired 对象。
- 背压：未修改 borrow 等待、水位或队列逻辑。
- 连接生命周期：未修改网络连接创建、保活、半关闭、异常断开或重连逻辑。
- 线程模型：未引入锁或阻塞到 ObjectPool 热点路径；测试等待仅限单元测试。
- 协议兼容性：未改协议编解码或网络协议行为。
- 核心监控指标建议保持：对象池 size/idle/active/waiting/borrow timeout/created/retired，加上网络侧连接数、吞吐/延迟、Netty 堆外内存占用 `PooledByteBufAllocator.DEFAULT.metric().usedDirectMemory()`。
