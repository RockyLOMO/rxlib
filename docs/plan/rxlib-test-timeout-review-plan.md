# 背景

用户要求 review `rockylomo/rxlib` 仓库 `master` 分支下 `rxlib/src/test` 相关测试类，找出并优化可能导致全量测试一直卡住或运行很久的测试类。用户补充约束：普通测试中 `sleep` / 固定等待在 1 分钟内可以接受；压测 / 集成测试单个测试类最大可接受 5 分钟限制。

# 任务类型判断

本次任务归类为 **Review / 修复 / 优化需求**。

原因：用户要求 review 现有测试类并优化已有测试的耗时和卡住风险；当前不新增业务功能，也不改变生产代码行为。按流程，本阶段只提交计划文档，等待用户明确要求后再进入代码实现阶段。

# 当前上下文

已 review / 扫描的范围：

- `rxlib/src/test/java` 测试树。
- `.github/workflows/jdk8-unit-tests.yml`。
- 根 `pom.xml` 的 Java/JUnit/Maven 基础配置。
- 重点关注 `org.rx.core.ThreadPoolTest`、线程池 / 定时器测试、`org.rx.net.socks` 集成测试、`org.rx.util.rss.RssTest` 以及其他 `*IntegrationTest`、`*PerformanceTest`。

关键调用链与现状：

- `jdk8-unit-tests.yml` 是 `workflow_dispatch` 手动触发 workflow，支持 `test_classes` 输入；如果 `test_classes` 为空会执行全量 `mvn clean test`。
- 全量执行时 Maven Surefire 可能识别 `*Test` / `*IntegrationTest` 命名，网络、UDP、DNS、HTTP、Socks、RSS、线程池、定时器等测试会一起运行。
- 已发现高风险点：`ThreadPoolTest.threadPoolAutosize` 使用 1 线程、队列容量 1 的线程池，提交约 100 个每个 `sleep(5000)` 的任务；理论运行时间超过 5 分钟，不适合默认全量测试。
- `ThreadPoolTest.threadPool`、`inheritThreadLocal`、`timer` 等历史综合测试包含多段 `sleep(5000)`、`sleep(8000)`、异步任务链、定时器周期任务、并行流和 trace 传播验证。单段等待在用户给定 1 分钟范围内，但组合后容易拖慢全量测试。
- 网络 / 集成测试如 `SocksProxyServerIntegrationTest`、`Udp2rawFixedEntryIntegrationTest`、`RssTest` 文件较大，涉及本地端口、UDP/TCP、代理、RSS/HTTP 等场景，需要确认是否默认全量运行，以及是否存在无超时等待、外部网络依赖和资源未释放风险。

已发现的问题或风险：

1. `ThreadPoolTest.threadPoolAutosize` 有明显超过 5 分钟的运行风险。
2. 部分历史综合测试没有 JUnit 级 timeout 保护，内部异步任务、定时器或线程池逻辑回归时可能卡住而不是快速失败。
3. `Future.get()`、`CompletableFuture.join()`、`CountDownLatch.await()`、手工线程 `join()` 需要统一确认是否带超时。
4. 网络 / 集成测试如果默认纳入全量测试，需要明确是否依赖外部网络、固定端口、类级超时，以及是否应通过 tag / assumptions / system property 从默认单元测试中隔离。
5. 线程池和定时器测试需要检查资源释放，避免非 daemon 线程或周期任务遗留导致 JVM 不退出。

# 目标

1. 对 `rxlib/src/test` 中可能导致全量测试卡住或超过预期耗时的测试类进行最小化优化。
2. 普通单元测试中，单次 sleep / 固定等待不超过 1 分钟可保留；无超时等待必须改为有明确超时的等待或断言。
3. 压测 / 集成测试单个测试类默认执行时间控制在 5 分钟内。
4. 明显压测或依赖外部环境的场景避免进入默认全量测试，或用 JUnit tag / assumption / system property 显式控制。
5. 保持 JDK8 兼容，不使用 JDK9+ API。
6. 只修改测试代码或测试配置，不修改生产业务逻辑。
7. 修改后通过 GitHub Actions `jdk8-unit-tests.yml` 验证相关测试类。

# 非目标

1. 不重构生产代码。
2. 不改变线程池、网络、DNS、Socks、RSS 等业务逻辑语义。
3. 不删除有价值的测试断言。
4. 不为了让 CI 通过而简单注释掉测试。
5. 不升级 Maven/JUnit/Netty 等大版本依赖。
6. 不修改 secrets、token、证书、私钥。
7. 不发布 release。
8. 不在计划阶段修改任何业务或测试实现代码。

# 设计方案

总体策略：先收敛高风险，再补 timeout，再按需隔离集成 / 压测。

1. 优先处理确定会拖慢全量测试的类 / 方法。
2. 对等待类 API 做统一超时保护：
   - `Future.get()` 改为 `Future.get(timeout, TimeUnit.SECONDS)`。
   - `CompletableFuture.join()` 如可能永久等待，改为 `get(timeout, TimeUnit.SECONDS)`。
   - `CountDownLatch.await()` 改为 `await(timeout, TimeUnit.SECONDS)` 并断言返回值。
   - `Thread.join()` 改为 `join(timeoutMillis)` 并断言线程已结束或输出明确失败信息。
3. 对固定 sleep：
   - 1 分钟内且测试目标明确的 sleep 可保留。
   - 可被 latch / future / polling 替代的 sleep 优先替代，减少无意义等待。
4. 对压测 / 集成：
   - 超过 5 分钟或依赖外部环境的测试默认不进入全量单元测试。
   - 可使用 JUnit 5 `@Tag("integration")` / `@Tag("benchmark")`、`Assumptions.assumeTrue(Boolean.getBoolean(...))` 或 Maven Surefire exclude 方案。
   - 当前 workflow 可用 `-Dtest` 精准跑类，优先不大改 workflow；仅必要时调整测试类自身或模块 `pom.xml`。
5. 对资源释放：
   - 自建 `ThreadPool`、`WheelTimer`、server/client/channel/socket 等资源使用 `try/finally` 或 `@AfterEach` 清理。
   - 周期任务或 timer future 在测试结束时 cancel。
   - 避免后台非 daemon 线程遗留导致 JVM 退出卡住。

针对 `ThreadPoolTest` 的预期方向：

- `threadPoolAutosize`：将 100 个任务 × 5 秒的压测缩短到可控规模，或改为显式 benchmark / assumption，只在手工开启时运行。
- `threadPool`：收敛历史大杂烩式验证，保留必要断言，对关键 `get()` / `join()` 补充 timeout。
- `inheritThreadLocal`：控制总等待时间，补充 timeout，确保线程池和 timer 资源释放。
- `timer`：周期任务必须有退出条件和 cancel；scheduled future 获取结果必须带 timeout。

针对网络 / 集成测试的预期方向：

- 纯本地、可快速稳定结束的测试保留默认执行，但加类级或方法级 timeout。
- 依赖外部网络或超过 5 分钟的测试用 tag / assumption 隔离出默认全量测试。
- 本地端口冲突时快速失败或跳过，server/client/channel 在 finally 中关闭。

异常处理与资源释放：timeout 触发时用断言失败并输出测试阶段说明；需要跳过的外部依赖测试使用 `Assumptions` 给出清晰原因；捕获 `InterruptedException` 后恢复 interrupt 标记并失败或退出。

# 修改文件列表

计划阶段只新增：

- `docs/plan/rxlib-test-timeout-review-plan.md`

代码实现阶段预计可能修改：

- `rxlib/src/test/java/org/rx/core/ThreadPoolTest.java`
- `rxlib/src/test/java/org/rx/core/ThreadPoolWheelTimerRegressionTest.java`（如发现缺少 timeout / 资源释放）
- `rxlib/src/test/java/org/rx/core/WheelTimerShutdownPeriodicTest.java`（如发现周期任务未取消风险）
- `rxlib/src/test/java/org/rx/net/socks/SocksProxyServerIntegrationTest.java`（如默认全量运行且存在超 5 分钟或外部依赖风险）
- `rxlib/src/test/java/org/rx/net/socks/Udp2rawFixedEntryIntegrationTest.java`（如默认全量运行且存在超 5 分钟或外部依赖风险）
- `rxlib/src/test/java/org/rx/net/socks/UdpRedundantEncoderPerformanceTest.java`（如默认全量运行且属于压测）
- `rxlib/src/test/java/org/rx/util/rss/RssTest.java`（如存在无超时等待或外部依赖）
- 必要时修改 `rxlib/pom.xml` 或测试配置，但优先避免。

# 风险点

1. JDK8 下不能使用 `CompletableFuture.orTimeout` 等 JDK9+ API。
2. JUnit 5 timeout / tag 的使用要与当前 Maven Surefire 配置兼容。
3. 过度缩短等待可能导致并发 / 定时器测试偶发失败，需要用事件驱动等待替代简单缩短 sleep。
4. 原测试验证并发时序，修改等待方式可能改变竞争窗口，应保持核心并发断言不变。
5. 错误关闭全局线程池或 timer 可能影响同 JVM 后续测试，只释放测试自己创建的资源。
6. 隔离 `IntegrationTest` / `PerformanceTest` 可能改变默认全量测试覆盖范围，需要在执行后明确说明。
7. GitHub Actions 上端口、网络、IPv6/UDP 能力与本地不同，网络类测试可能仍存在环境性失败。
8. 当前只完成初步源码 review，尚未运行全量测试；最终需要以 CI 运行结果确认是否还有隐藏卡点。

# 验证方案

1. 计划阶段：仅提交 `docs/plan/rxlib-test-timeout-review-plan.md`；不触发代码测试，不声称 CI 通过。
2. 代码实现阶段：修改前再次读取最新文件内容和 sha。
3. 优先通过 `jdk8-unit-tests.yml` 手动触发验证：
   - `test_classes=org.rx.core.ThreadPoolTest`
4. 如修改网络 / 集成测试，再按实际修改类追加：
   - `org.rx.core.ThreadPoolWheelTimerRegressionTest`
   - `org.rx.core.WheelTimerShutdownPeriodicTest`
   - `org.rx.net.socks.SocksProxyServerIntegrationTest`
   - `org.rx.net.socks.Udp2rawFixedEntryIntegrationTest`
   - `org.rx.util.rss.RssTest`
5. 查询 workflow run 时必须按当前分支 `agent/review-rxlib-test-timeouts` 过滤；只有 `conclusion=success` 才认为通过。
6. 如果 CI 失败，读取失败日志，按编译失败、单元测试失败、格式失败、依赖下载失败、JDK8 不兼容、环境问题、测试不稳定、workflow 配置问题分类处理。
7. 相关测试类通过后，根据用户要求决定是否运行全量 `mvn clean test` 或继续收敛其他卡点。
