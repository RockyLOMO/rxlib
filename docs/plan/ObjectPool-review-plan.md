# 背景

用户要求在 `rockylomo/rxlib` 仓库 `master` 分支上 review `ObjectPool` 相关实现，并将结果更新到 `docs/plan/ObjectPool-review-plan.md`。

本轮只做 review 与计划文档更新，不修改业务代码。review 基于 `master` 当前提交 `bcb1ad3bee05a439eb3ab0aa318db2585151bf7d`。

# 任务类型判断

本次任务归类为 **Review / 修复 / 优化需求**。

原因：用户明确要求 review 当前 `ObjectPool` 相关实现，并更新计划文档；没有要求直接实现代码。因此本轮只提交计划文档。后续只有在用户明确要求“按计划执行 / 开始修改代码”后，才进入代码实现阶段。

# 当前上下文

已 review 的核心文件：

- `rxlib/src/main/java/org/rx/core/ObjectPool.java`
- `rxlib/src/test/java/org/rx/core/ObjectPoolTest.java`

关联文件：

- `rxlib/src/main/java/org/rx/core/IdentityWrapper.java`
- `rxlib/src/main/java/org/rx/core/Disposable.java`
- `rxlib/src/main/java/org/rx/core/Extends.java`
- `rxlib/src/main/java/org/rx/core/Constants.java`
- `rxlib/src/main/java/org/rx/core/Tasks.java`
- `rxlib/src/main/java/org/rx/diagnostic/DiagnosticMetrics.java`

关键调用链：

```text
borrow()
  -> checkBorrowable()
  -> threadLocalCache fast path
  -> doPoll(0) / pollSharedIdle()
  -> doCreate()
  -> validateHandler
  -> return instance

recycle(obj)
  -> lookupKey FastThreadLocal IdentityWrapper
  -> conf.get(identity)
  -> validateHandler
  -> passivateHandler
  -> BORROWED -> IDLE CAS
  -> offerSharedIdle()
  -> optional threadLocalCache hint

validNow()
  -> scan live circular list
  -> IDLE -> VALIDATING CAS
  -> idle timeout / max lifetime / validateHandler
  -> retire or offer back to shared idle
  -> adaptive refill
  -> record metrics

dispose()
  -> disposing = true
  -> signal borrowers
  -> cancel timer future
  -> doRetire() all pooled objects
```

当前实现意图：`ObjectPool<T>` 通过 `ConcurrentHashMap<IdentityWrapper<T>, ObjectConf<T>>` 管理对象，使用 `IDLE / BORROWED / RETIRED / VALIDATING` 状态机，shared idle 链表提供跨线程复用，`FastThreadLocal` 作为 L1 hint，后台 timer 做校验、过期回收、泄漏检测和自适应 refill。

# 目标

1. 梳理 `ObjectPool` 当前状态机、对象生命周期、并发路径和资源释放路径。
2. 标记本轮 review 发现的高优先级风险点。
3. 给出后续最小修改方案，避免大规模重构。
4. 保持 JDK8 兼容，不引入 Java 9+ API。
5. 保持现有 public API 兼容。
6. 补充建议新增或加强的测试场景。
7. 后续如进入实现阶段，用 GitHub Actions `jdk8-unit-tests.yml` 验证 `org.rx.core.ObjectPoolTest`。

# 非目标

1. 本轮不修改 `ObjectPool.java` 或任何业务代码。
2. 本轮不修改 `.github/workflows/**`。
3. 本轮不升级 JDK 或依赖大版本。
4. 本轮不删除文件、不改 public 方法签名、不改包名。
5. 本轮不做 release、不删除分支。
6. 本轮不扩大到 ThreadPool、Remoting、DNS、UDP 之外的无关模块。

# 设计方案

## Review 结论 1：recycle 热路径的状态所有权需要优先收紧

当前归还路径中，`validateHandler` 和 `passivateHandler` 在 `BORROWED -> IDLE` CAS 前执行。风险是：同一个对象如果被重复 `recycle()` 或并发 `recycle()`，CAS 失败前仍可能重复执行 `passivateHandler`。

这对普通对象可能只是多一次 reset，但对 RPC / UDP / Netty 场景可能造成重复关闭 handler、重复释放 buffer、重复清理租约等副作用。

建议后续实现：

- 在 `recycle(ObjectConf<T> c, boolean allowThreadLocal)` 开头先校验 `conf.get(c.wrapper) == c`。
- 先通过 CAS 获得归还所有权，例如 `BORROWED -> VALIDATING`。
- 只有 CAS 成功的线程才执行 `validateHandler` 和 `passivateHandler`。
- 处理完成后再 `VALIDATING -> IDLE` 并放回 shared idle。
- CAS 失败时，如果对象已 retired 或已不属于 pool，直接 return；如果仍属于 pool 但不是 borrowed，保持现有 double recycle 异常语义。
- handler 异常路径必须 retire，并唤醒等待 borrower。

## Review 结论 2：duplicate object 创建分支语义需要文档明确

`doCreate()` / `doCreateIdle()` 中如果 `createHandler` 返回池中已存在对象，`conf.putIfAbsent(wrapper, c)` 会返回已有配置。

当前实现已有释放预占 slot、关闭 wrapper、warn 日志和返回 null 的防御处理。风险是：如果 `createHandler` 错误地返回一个仍被业务持有的池内对象，`tryClose(wrapper)` 可能关闭正在使用的对象。

该场景本质上是 `createHandler` 语义违规。建议保持当前防御逻辑，但在文档里明确：`createHandler` 必须返回全新对象，不得返回已在池内或仍被业务持有的对象。

## Review 结论 3：ThreadLocal cache 是 hint，但仍有长生命周期线程引用保留风险

当前 `threadLocalCache` 不再独占 idle 对象，`recycle()` 成功后会先 `offerSharedIdle(c)`，再把 `ObjectConf` 写入本线程 hint。borrow L1 path 也会检查 `conf.get(c.wrapper) == c` 和 `IDLE -> BORROWED` CAS，功能正确性较好。

仍需关注：Netty EventLoop / FastThreadLocal 长生命周期线程可能长期持有 retired `ObjectConf` 引用，直到下一次 borrow 清理或线程结束。

建议后续验证：

- 增加 stale ThreadLocal cache 测试，确保 retired 对象不会被借出。
- 增加长生命周期线程反复 retire/create 的压力测试，观察 size、retired、created 指标和内存保留。
- 如后续仍有保留风险，可考虑增加配置开关禁用 L1 hint，但默认保持当前性能路径。

## Review 结论 4：`demandFactor` 可见性建议修复

当前 `demandFactor` 由 setter 修改，后台 `validNow()` 读取，但字段不是 `volatile`。其他容量与时间配置大多使用 volatile。建议改为：

```java
@Getter
volatile double demandFactor = 2.0;
```

这属于小范围低风险修复，保持 API 不变，并符合 JDK8。

## Review 结论 5：创建失败退避已有改善，但需要继续验证中断与日志语义

当前 `borrow()` 已有 `backoffCreateFailure(remainingTime)`，避免 `createHandler` 持续失败时在 `borrowTimeout` 内 tight loop。

建议继续保留测试：

- `createHandler` 持续抛异常时，尝试次数不应过高。
- borrow 总耗时接近 `borrowTimeout`。
- 中断路径应恢复线程中断标记，并在最终 timeout message 中保留原始错误信息。

## Review 结论 6：泄漏检测默认不关闭 borrowed 对象是正确方向

当前 `closeObjectOnLeak=false` 默认只记录 leak 指标，不强制关闭 borrowed 对象；只有显式开启后才会 retire/close borrowed 对象。

建议文档明确：

- `leakDetectionThreshold=0` 表示关闭泄漏检测。
- `closeObjectOnLeak=false` 只告警。
- `closeObjectOnLeak=true` 是破坏性强制回收，调用方必须能承受业务对象被池关闭。

## Review 结论 7：retire action magic int 建议后续替换为常量

当前 `doRetire(wrapper, action)` 使用 magic int：`0 close`、`1 recycle validate`、`3 idleTimeout`、`4 leaked`。但 `maxLifetime` 和 validate failed 也可能复用 action 3，后续指标或日志扩展容易误分类。

建议后续用 JDK8 兼容的 `static final int` 常量表达原因，不急于大改。

# 修改文件列表

本轮修改：

- `docs/plan/ObjectPool-review-plan.md`

后续如用户要求执行，预计可能修改：

- `rxlib/src/main/java/org/rx/core/ObjectPool.java`
- `rxlib/src/test/java/org/rx/core/ObjectPoolTest.java`
- 可选：`docs/reference/ObjectPool.md`

# 风险点

## 兼容性风险

`ObjectPool` 是 core 通用类，影响 RPC、Socks、UDP、Remoting 等调用。不应改 public API，也不应改变已知 double recycle 的异常语义。

## 并发风险

`borrow/recycle/validNow/dispose` 四条路径可并发交错。归还状态机修复必须保证 handler 不在锁内执行、retire 后唤醒 borrower、shared idle 链表不会重复入队。

## 性能风险

recycle 热路径增加 CAS 状态转换会有额外开销。修复应保持最小改动，避免扩大 shared idle lock 临界区，避免后台扫描全量化。

## 资源释放风险

重复 `passivateHandler` 可能导致资源重复释放。duplicate object 分支 close 可能关闭错误返回的池内对象。`closeObjectOnLeak=true` 会关闭仍被业务持有的对象。

## Netty / 高频 Java 风险

如果池对象包装 Netty channel、handler、ByteBuf 或 UDP lease，需要特别关注：引用计数、重复 release、EventLoop 线程安全、阻塞 EventLoop、关闭路径资源释放、异常路径完整性。

## 测试风险

并发测试容易 flaky。新增 stress 测试应控制线程数、循环次数和超时，不应依赖精确调度顺序。

# 验证方案

本轮仅提交计划文档，不触发代码验证。

后续进入实现阶段后建议：

1. 触发 GitHub Actions：`jdk8-unit-tests.yml`。
2. `test_classes` 使用：`org.rx.core.ObjectPoolTest`。
3. 本地 / CI Maven 建议：`mvn -pl rxlib -Dtest=org.rx.core.ObjectPoolTest test`。
4. 重点补测：
   - double recycle 不重复 passivate；
   - concurrent recycle 只有一个线程执行 passivate；
   - stale ThreadLocal cache 不会借出 retired 对象；
   - demandFactor setter 与 validNow 并发 smoke；
   - create failure backoff 不 tight loop；
   - borrow / recycle / validNow / close race。
5. CI 状态必须以 workflow run `conclusion=success` 为准；queued、in_progress、waiting 不能视为通过。

# 建议后续实现顺序

1. P0：修复 recycle 状态所有权，确保只有成功从 `BORROWED` 转出状态的线程执行 `validateHandler/passivateHandler`。
2. P1：将 `demandFactor` 改为 `volatile double`。
3. P1：补充并发 double recycle、stale ThreadLocal、create failure backoff 测试。
4. P2：同步文档，明确 create/passivate/leak close 语义。
5. P2：逐步替换 retire action magic int 为常量。

# Review 结论

当前 `ObjectPool` 近期修复已覆盖 shared idle、失败退避、duplicate object、防 close 后创建、validation 状态、泄漏检测和指标标签等方向，整体比早期实现更稳。

本轮最优先建议是修复 `recycle()` 热路径的状态所有权：`passivateHandler` 必须只由成功获得归还所有权的线程执行。该问题是当前最值得优先处理的并发副作用风险。

在用户明确要求执行前，本轮不修改业务代码。
