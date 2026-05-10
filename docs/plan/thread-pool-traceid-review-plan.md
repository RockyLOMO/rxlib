# 背景

用户要求检查 `rockylomo/rxlib` 中 `ThreadPool` 的 `traceId` 相关逻辑是否存在 bug。本次任务聚焦 `org.rx.core.ThreadPool` 对 traceId 的捕获、传递、切换和清理逻辑，不直接修改业务代码。

# 任务类型判断

本次归类为 Review / 修复 / 重构 / 优化需求。

原因是用户描述为“看下 ThreadPool 的 traceId 相关内容是否有bug”，目标是 review 现有实现并判断风险点；按照流程，需要先完成相关代码 review、记录问题与修复计划，提交计划文档后，等待用户明确要求再进入代码实现阶段。

# 当前上下文

已 review 的文件：

- `rxlib/src/main/java/org/rx/core/ThreadPool.java`
- `rxlib/src/main/java/org/rx/core/RunFlag.java`
- `rxlib/src/main/java/org/rx/core/RxConfig.java`
- `rxlib/src/test/java/org/rx/core/ThreadPoolTest.java`
- `.github/workflows/jdk8-unit-tests.yml`

关键调用链：

1. `ThreadPool.Task` 构造时：
   - 如果 `RxConfig.INSTANCE.threadPool.traceName != null`，自动给任务添加 `RunFlag.THREAD_TRACE`。
   - 捕获当前提交线程的 `traceId()` 到 `task.traceId`。
2. 任务进入线程池执行前：
   - `ThreadPool.beforeExecute()` 通过 `setTask(r)` 获取 `Task`。
   - 如果任务带 `RunFlag.THREAD_TRACE`，调用 `startTrace(task.traceId)`。
3. 任务执行中：
   - 用户代码通过 `ThreadPool.traceId()` 读取当前线程 `CTX_TRACE_ID` 栈顶。
4. 任务执行后：
   - `ThreadPool.afterExecute()` 通过 `getTask(r, true)` 获取并清理任务映射。
   - 如果任务带 `RunFlag.THREAD_TRACE`，调用 `endTrace()`。

当前实现意图：

- 提交任务时捕获提交方 traceId。
- 工作线程执行任务前，把捕获到的 traceId 设置到当前线程上下文。
- 任务执行完成后，通过 `endTrace()` 清理本次任务 trace 上下文。
- `CTX_TRACE_ID` 使用 `InheritableThreadLocal<LinkedList<Object>>`，子线程会继承父线程栈顶 traceId，并包装为 `Tuple(traceId, 0)`。

已发现的问题或风险：

1. **高概率 bug：已有不同 trace 时，任务 traceId 不会被切换。**

   `beforeExecute()` 当前调用的是 `startTrace(task.traceId)`，即 `requiresNew=false`。当执行线程已有 traceId A，而任务提交时捕获的是 traceId B 时，`startTrace(B, false)` 会进入“已有 trace 且传入 trace 不同”的分支，只记录 warning，不会把当前线程切换到 B。此时任务实际看到的是 A，而不是提交方捕获的 B。

2. **高概率 bug：清理可能错误结束已有 trace。**

   即使 `startTrace(task.traceId)` 没有真正 push 或切换到任务 trace，`afterExecute()` 仍会无条件调用 `endTrace()`。当执行线程原本已有 trace A 时，任务结束可能把 A pop/decrement 掉，造成外层 trace 被误清理。

3. **`InheritableThreadLocal` 与线程池复用组合存在风险。**

   `CTX_TRACE_ID.childValue()` 会让新建线程继承父线程栈顶 traceId。线程池 worker 如果在某个 trace 上下文中被创建，worker 初始就可能带有父 trace。后续执行带 `THREAD_TRACE` 的任务时，当前实现可能因为已有 trace 与任务 trace 不同而不切换，并在任务结束时清掉继承来的 trace。

4. **`maxTraceDepth` 默认值和比较条件可能放大边界风险。**

   `ThreadPoolConfig.maxTraceDepth` 默认是 `0`，`RxConfig.afterSet()` 未对它做正数归一化。`startTrace()` 中使用 `queue.size() > maxTraceDepth` 判断是否丢弃。默认值下，只要队列已有 inherited/stale trace，requires-new 场景可能出现深度控制和预期不一致。

5. **现有测试覆盖不足。**

   `ThreadPoolTest` 已覆盖基础 `startTrace/endTrace/nesting/requiresNew` 类场景，但缺少“线程池执行线程已有不同 trace + 任务捕获 trace + before/after 自动切换/清理”的回归测试，也缺少 caller-runs 路径的 trace 清理验证。

# 目标

- 修复 `ThreadPool` 自动 trace 传递时，执行线程已有不同 trace 导致任务使用错误 traceId 的问题。
- 避免 `afterExecute()` 在 `beforeExecute()` 未实际建立任务 trace 时误调用 `endTrace()` 清理外层 trace。
- 补充单元测试覆盖：
  - worker 线程已有 stale/inherited trace 时，任务应使用提交时捕获的 trace。
  - 任务完成后，不应误清理执行线程原有 trace 栈。
  - 如可稳定构造，覆盖 `CALLER_RUNS` 路径下的 trace 切换和清理。
- 保持 Java 8 兼容，不引入新依赖，不扩大 ThreadPool 其他行为范围。

# 非目标

- 不重写整个 traceId 栈模型。
- 不改变 `TraceHandler`、诊断采样、slow method trace 的存储逻辑。
- 不调整线程池扩缩容、队列 offer 策略、serial/single task 语义。
- 不升级依赖或要求 JDK 9+。
- 不修改 secrets、token、证书、私钥。
- 不发布 release。

# 设计方案

建议采用最小改动方案。

## 1. 为任务 trace 建立显式状态

在 `ThreadPool.Task` 中增加轻量状态字段，例如：

- `volatile boolean threadTraceStarted;`

用于记录 `beforeExecute()` 是否确实为该任务建立了 trace 上下文。`afterExecute()` 仅在该字段为 true 时调用 `endTrace()`。

## 2. 新增线程池任务专用 trace 开始方法

当前 `startTrace(traceId, false)` 的语义适合普通业务代码：如果已有 trace 且传入不同 trace，不强行切换。但线程池任务执行前需要更强语义：任务应该以提交时捕获的 trace 运行。

建议新增私有 helper，例如：

- `private boolean beginTaskTrace(Task<?> task)`

核心语义：

1. 当前线程没有 trace：
   - 使用 `task.traceId`。
   - 如果 `task.traceId == null`，按现有生成规则生成新的 traceId。
   - push 后返回 true。
2. 当前线程已有 trace，且与 `task.traceId` 相同：
   - 保持现有 nested 语义，调用现有 start/nest 逻辑即可。
   - 返回 true。
3. 当前线程已有 trace，且与 `task.traceId` 不同：
   - 任务 traceId 非空时，强制以 requires-new 语义 push `task.traceId`。
   - 任务 traceId 为空时，应生成新的 traceId，而不是沿用当前 stale trace。
   - 返回 true。
4. 如果因 `maxTraceDepth` 限制等原因未能建立任务 trace：
   - 返回 false。
   - `afterExecute()` 不调用 `endTrace()`，避免误清理外层 trace。
   - 可保留 warning 日志，便于诊断。

注意：直接把 `beforeExecute()` 改成 `startTrace(task.traceId, true)` 不完全够，因为当 `task.traceId == null` 且当前线程已有 stale trace 时，现有 `startTrace(null, true)` 仍会走“同 trace / 未指定 trace”的 nesting 分支，可能沿用 stale trace，而不是生成任务新 trace。因此建议使用专用 helper 或调整 `startTrace` 的 requires-new + null 语义。

## 3. afterExecute 按状态对称清理

将当前逻辑：

```java
if (flags.has(RunFlag.THREAD_TRACE)) {
    endTrace();
}
```

调整为类似：

```java
if (task.threadTraceStarted) {
    endTrace();
    task.threadTraceStarted = false;
}
```

并确保 `skipExecution`、`SINGLE` 未获取锁、reject/queue remove 等路径不会误触发 trace 清理。

## 4. 测试设计

优先在 `ThreadPoolTest` 中补充小范围回归测试；如文件过大或已有测试组织不适合，乃可以新增 `ThreadPoolTraceIdTest`。

建议测试场景：

1. `threadTraceUsesCapturedTraceWhenWorkerHasDifferentTrace`
   - 固定线程池大小为 1。
   - 开启 `RxConfig.INSTANCE.threadPool.traceName`。
   - 第一段任务在线程池 worker 中制造 stale trace A。
   - 在提交线程中启动 trace B 后提交第二段任务。
   - 第二段任务中断言 `ThreadPool.traceId()` 为 B，而不是 A。
   - 任务后清理配置和线程池。
2. `threadTraceDoesNotEndUnrelatedWorkerTrace`
   - 构造 worker 已有 trace A，任务捕获 trace B。
   - 第二段任务结束后，再在同 worker 上检查 A 是否未被错误 pop，或者根据修复后的设计确认只清理任务自己 push 的 trace。
3. `callerRunsTraceUsesCapturedTrace`
   - 如可稳定构造，设置 `ThreadPoolQueueOfferMode.CALLER_RUNS`、小 queue capacity，让任务在 caller thread 执行。
   - caller 当前 trace 与任务捕获 trace 不同时，验证任务 trace 和 cleanup 对称。
4. `ThreadPoolConfigSnapshot`
   - 使用现有测试辅助类保存/恢复 `RxConfig.INSTANCE.threadPool`，避免污染其他测试。

# 修改文件列表

预计后续代码实现会修改或新增：

- `rxlib/src/main/java/org/rx/core/ThreadPool.java`
- `rxlib/src/test/java/org/rx/core/ThreadPoolTest.java`
  - 或新增 `rxlib/src/test/java/org/rx/core/ThreadPoolTraceIdTest.java`
- `docs/plan/thread-pool-traceid-review-plan.md`

本阶段仅新增计划文档，不修改业务代码。

# 风险点

- **兼容性风险**：改变 `THREAD_TRACE` 自动绑定语义后，已有依赖“worker 当前 trace 优先于提交 trace”的调用方可能观察到不同 traceId。但从任务提交捕获 trace 的现有设计看，使用提交 trace 更符合预期。
- **嵌套语义风险**：`startTrace/endTrace` 使用 `Tuple<String, Integer>` 表示 nesting 计数，修复时必须保持同 trace nesting 的原有计数行为。
- **深度限制风险**：`maxTraceDepth` 默认值为 0，调整 requires-new 行为时要避免默认配置下导致所有嵌套任务被意外丢弃。
- **资源清理风险**：`afterExecute()` 必须只清理本任务建立的 trace，不能漏清理，也不能误清理已有 trace。
- **并发风险**：线程池 worker 复用、caller-runs、serial task chain、single task skip 路径都可能影响 before/after 对称性。
- **测试稳定性风险**：需要避免依赖 sleep；优先使用 `CountDownLatch`、`Future.get()`、固定大小线程池保证 worker 复用。

# 验证方案

代码实现阶段完成后，验证方案如下：

1. 触发 GitHub Actions workflow：
   - workflow：`.github/workflows/jdk8-unit-tests.yml`
   - 名称：`JDK 8 Unit Tests`
   - 分支：`agent/threadpool-traceid-review`
   - `test_classes`：`ThreadPoolTest`，或新增测试类名 `ThreadPoolTraceIdTest`
2. 本地等价 Maven 命令：

```bash
mvn -B -U -Dgpg.skip=true -Dmaven.test.skip=false -DskipTests=false -Dtest=ThreadPoolTest clean test
```

3. 验证点：
   - 新增 traceId 回归测试通过。
   - 既有 ThreadPool 队列、serial/single、shutdown 测试不受影响。
   - CI run 必须以 `conclusion=success` 才视为通过。
