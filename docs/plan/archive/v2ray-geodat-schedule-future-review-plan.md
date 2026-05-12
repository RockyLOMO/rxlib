# 背景

用户反馈“分支已更新，再 review 下”。本次 review 针对 `agent/v2ray-geodat-plan` 分支最新代码更新。

当前分支最新提交：

- HEAD：`42b03f7428a975e42e2f2d71275c48b409aaa8d7`
- commit message：`feat(net): dynamic schedule reloading, race-free load futures, and IPv6 lookup priority`

本次只做 Review / 修复计划，不修改业务代码。

# 任务类型判断

本次任务归类为 Review / 修复 / 优化需求：

- 用户要求“再 review”；
- 分支已经有多轮 v2ray geodata 实现和优化提交；
- 本轮更新主要处理上一轮指出的 Future race、daily schedule 重排、加载失败传播、IPv6 inverse lookup 等问题；
- 需要继续复核热点路径、并发加载、生命周期关闭和 CI 状态。

# 当前上下文

已 review 的关键文件：

- `rxlib/src/main/java/org/rx/net/support/V2RayGeoManager.java`
- `rxlib/src/main/java/org/rx/net/support/V2RayGeoDataReader.java`
- `rxlib/src/main/java/org/rx/net/support/V2RayGeoIpMatcher.java`
- `rxlib/src/main/java/org/rx/net/support/V2RayGeoIpIndex.java`
- `rxlib/src/main/java/org/rx/net/support/V2RayGeoSiteIndex.java`
- `rxlib/src/main/java/org/rx/core/Tasks.java`
- `rxlib/src/test/java/org/rx/net/support/V2RayGeoManagerCompileFailureTest.java`
- `rxlib/src/test/java/org/rx/net/support/V2RayGeoManagerScheduleTest.java`
- `rxlib/src/test/java/org/rx/net/support/V2RayGeoIpLookupInverseTest.java`

关键调用链：

1. 加载 Future 链路：
   - `ensureLoaded()` / `ensureIpLoaded()` / `ensureSiteLoaded()`
   - `ensureLoadTask()`
   - `Tasks.run(() -> load(false))`
   - `awaitLoadTask(Future<Void>)`

2. compile 同步等待链路：
   - `compileGeoIpMatcher(code)`
   - `compileGeoSiteMatcher(code, attrFilter)`
   - 等待具体 `Future<Void>`
   - 返回预编译 matcher

3. daily schedule 生命周期：
   - `ensureDailyScheduled()`
   - `setDailyDownloadTime(...)`
   - `Tasks.scheduleDaily(...)`
   - `close()` 取消 `dailyTasks`

4. inverse lookup 链路：
   - `V2RayGeoIpMatcher.lookupCode(byte[])`
   - `V2RayGeoIpIndex.lookupCode(byte[])`
   - IPv4/IPv6 lookup segment 二分查找

本轮更新已解决或改善的问题：

- `ensureLoaded` / `ensureIpLoaded` / `ensureSiteLoaded` 已返回本次具体 load future，compile 阶段不再只依赖稍后读取 volatile `dTask`。
- `setDailyDownloadTime` 在已 schedule 后会重新 `Tasks.scheduleDaily` 并 cancel 旧任务。
- `close()` 会 cancel `dailyTasks`。
- setter 已补充注释，明确异步切换配置，需要同步生效时调用 compile 或 waitLoad。
- compile API 注释已明确可能等待下载和 dat 解析。
- `V2RayGeoDataReader` 已注释说明 int attribute 当前只做存在性过滤。
- 新增 `V2RayGeoManagerCompileFailureTest` 覆盖无效 dat 下 compile 异常传播。
- 新增 `V2RayGeoManagerScheduleTest` 覆盖 daily schedule 重排和旧任务 cancel。
- `V2RayGeoIpLookupInverseTest` 已补 IPv6 inverse priority 和 full inverse complement 行为。
- `Tasks.scheduleDaily(String...)` 当前确实返回 `List<? extends ScheduledFuture<?>>`，本轮 `dailyTasks` 类型方向可编译性上是合理的。

# 目标

1. 保留本轮对 load Future、schedule 重排和测试覆盖的改进。
2. 继续收敛剩余生命周期和并发语义风险。
3. 避免 compile 一侧 matcher 时被另一侧加载任务误伤。
4. 明确 close 与 in-flight load 的关系。
5. 最终通过 JDK8 GitHub Actions 验证。

# 非目标

1. 本 review 计划不直接修改业务代码。
2. 不改变当前 hand-written protobuf reader 方向。
3. 不引入 protobuf-java 或其他重型依赖。
4. 不修改 secrets/token/证书。
5. 不自动发布 release。
6. 不使用 JDK9+ API。

# 设计方案

## 1. 当前已基本达成的方向

当前实现已经基本符合热点路径要求：

- 请求期 GeoIP 应优先使用 `CodeMatcher.matches(byte[])`。
- 请求期 GeoSite 应优先使用 `GeoSiteMatcher.matches(domain)`。
- 字符串 IP 解析、code normalize、selector parse 都被挪到配置期或 convenience API。
- geoip range lookup 是数组 + 二分查找，没有回到 HashMap 扫描或 CIDR 线性扫描。
- geosite matcher 依旧复用现有 `GeoSiteMatcher` / `UltraDomainTrieMatcher`。

后续修复不应回退这些方向。

## 2. 剩余问题一：compile 可能等待或传播另一侧加载任务异常

当前 compile 逻辑中：

- `compileGeoIpMatcher` 调用 `ensureIpLoaded()`；
- 如果返回 `null`，会 fallback 到当前 `dTask`；
- `compileGeoSiteMatcher` 同理。

风险场景：

- GeoIP 已有可用 snapshot，但 GeoSite 正在异步加载；
- 此时调用 `compileGeoIpMatcher("cn")`，`ensureIpLoaded()` 返回 `null`，随后 fallback 到 `dTask`；
- 如果 `dTask` 是 GeoSite 加载任务，GeoIP compile 会无谓等待 GeoSite；
- 如果 GeoSite 加载失败，GeoIP compile 可能传播与 GeoIP 无关的异常。

建议：

- 移除 compile 方法中的无条件 `task = dTask` fallback；
- 或在 Future 旁增加 load target 标记，例如 `LoadScope.IP`、`LoadScope.SITE`、`LoadScope.ALL`，只等待与当前 compile 相关的 task；
- 更简单的做法：`ensureIpLoaded()` / `ensureSiteLoaded()` 如果目标 snapshot 已存在就直接返回 null，compile 直接用当前 snapshot，不等待全局 `dTask`。

需要补测试：

- GeoIP snapshot 已存在、GeoSite 异步加载失败时，`compileGeoIpMatcher` 不应失败；
- GeoSite snapshot 已存在、GeoIP 异步加载失败时，`compileGeoSiteMatcher` 不应失败。

## 3. 剩余问题二：close 取消 schedule，但不取消 in-flight load task

当前 `close()`：

- cancel dailyTasks；
- close `ipMatcher`；
- close `siteIndex`。

但如果 `dTask` 正在执行：

- close 不会 cancel `dTask`；
- `load(false)` 可能在 close 之后继续下载/解析；
- load 完成后仍可能写回 `ipMatcher` / `siteIndex`；
- 这会出现 close 后对象又被重新填充 snapshot 的生命周期问题。

建议：

- 增加 `volatile boolean closed`；
- `close()` 设置 closed，cancel `dTask`；
- `ensureLoadTask()` / `load()` 在 closed 后直接返回或抛明确异常；
- `load()` 在 replace snapshot 前检查 closed，若已关闭则 close 新构建对象并不发布。

如果项目约定 manager close 后不再使用，也建议通过注释或测试固定该语义。

## 4. 剩余问题三：daily schedule 重排异常时状态可能半更新

`setDailyDownloadTime` 当前先写 `this.dailyDownloadTime = dailyDownloadTime`，然后 schedule 新任务。

风险：

- 如果 `dailyDownloadTime` 格式非法，`Tasks.scheduleDaily` 内部 `Time.valueOf` 可能抛异常；
- 此时字段已经被改成非法值，但旧 schedule 仍在；
- 下一次重排或日志排查会看到不一致状态。

建议：

- 先调用 `Tasks.scheduleDaily` 成功生成 newTasks，再更新 `dailyDownloadTime` 和 `dailyTasks`；
- 或预校验 `Time.valueOf(dailyDownloadTime)`；
- 增加非法时间测试，确保失败后旧 schedule 和旧字段保持不变。

## 5. 剩余问题四：convenience API 仍会触发异步加载但不等待

`matchGeoIp`、`matchGeoSite`、`resolveGeoIpCode`、`geoIpMatcher`、`geoSiteIndex` 等 convenience API 会调用 `ensureIpLoaded()` 或 `ensureSiteLoaded()`，但不会等待。

这对非热点路径可接受，但需要明确：

- 首次调用可能返回 false/null；
- 后台加载完成后才会有结果；
- 配置期必须使用 compile 或 waitLoad。

建议继续补充类级别 Javadoc：

- `match*/resolve*` 是 lazy non-blocking API；
- `compile*` 是 blocking configuration API；
- `set*` 是 async configuration API；
- request hot path 只能持有预编译 matcher。

## 6. 剩余问题五：CI 仍未验证

已查询 `agent/v2ray-geodat-plan` 分支 GitHub Actions runs，当前仍为：

- `total_count=0`

因此不能认为：

- JDK8 编译通过；
- 新增测试通过；
- `ApplicationException.sneaky(...)` 与 `assertThrows(InvalidException.class, ...)` 的异常类型匹配一定成立；
- `ScheduledFuture` 泛型、`Tasks.scheduleDaily` 返回值、测试包可见字段访问等组合一定无编译问题。

# 修改文件列表

后续修复阶段预计可能修改：

- `rxlib/src/main/java/org/rx/net/support/V2RayGeoManager.java`
- `rxlib/src/test/java/org/rx/net/support/V2RayGeoManagerCompileFailureTest.java`
- `rxlib/src/test/java/org/rx/net/support/V2RayGeoManagerScheduleTest.java`
- `rxlib/src/test/java/org/rx/net/support/V2RayGeoManagerLoadTest.java`

可能新增：

- `rxlib/src/test/java/org/rx/net/support/V2RayGeoManagerCrossLoadIsolationTest.java`
- `rxlib/src/test/java/org/rx/net/support/V2RayGeoManagerCloseTest.java`

# 风险点

1. 移除 `task = dTask` fallback 时，要确保目标侧新加载失败仍能被 compile 观察到。
2. 增加 `closed` 状态后，要避免影响 `INSTANCE` 的长期使用语义。
3. cancel in-flight task 不一定能中断底层 HTTP 下载，仍需要 closed 检查避免发布新 snapshot。
4. daily schedule 非法时间处理需要保持旧任务不被误 cancel。
5. 当前还没有 CI 结果，review 结论不能替代 JDK8 编译和单测验证。

# 验证方案

修复实现后必须触发 `.github/workflows/jdk8-unit-tests.yml`，分支过滤为 `agent/v2ray-geodat-plan`。

建议 `test_classes`：

`V2RayGeoDataReaderTest,V2RayGeoSiteMatcherTest,V2RayGeoIpMatcherTest,V2RayGeoManagerTest,V2RayGeoIpMatcherHotPathTest,V2RayGeoSiteHotPathTest,V2RayGeoManagerLoadTest,V2RayGeoSiteAttributeTest,V2RayGeoIpLookupInverseTest,V2RayGeoManagerCompileFailureTest,V2RayGeoManagerScheduleTest,V2RayGeoManagerCrossLoadIsolationTest,V2RayGeoManagerCloseTest,GeoSiteMatcherTest,UltraDomainMatcherTest,GeoIPSearcherTest`

只有 workflow run 的 `conclusion=success` 才能认为 CI 通过。

# 后续执行条件

本提交仅新增 review 计划文档，不修改业务代码。

收到“按 review 计划修复 / 开始改代码 / 执行优化”后，再进入代码修复阶段。
