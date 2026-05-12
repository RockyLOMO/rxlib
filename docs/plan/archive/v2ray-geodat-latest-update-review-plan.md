# 背景

用户反馈“分支已更新，再 review 下”。本次 review 针对 `agent/v2ray-geodat-plan` 分支最新代码更新。

当前分支最新提交：

- HEAD：`2a784b6dc37cfa9a58742be738ae4d98da16c499`
- commit message：`feat(net): enhance V2Ray GeoSite attribute parsing, lazy loader scheduling, and IP-byte resolve`

本次只做 Review / 修复计划，不修改业务代码。

# 任务类型判断

本次任务归类为 Review / 修复 / 优化需求：

- 用户要求“再 review”；
- 分支已有针对上一轮 review 的实现更新；
- 需要确认上轮剩余风险是否已消除，并继续审查热点路径、加载并发、attribute 语义和 CI 状态。

# 当前上下文

已 review 的关键文件：

- `rxlib/src/main/java/org/rx/net/support/V2RayGeoDataReader.java`
- `rxlib/src/main/java/org/rx/net/support/V2RayGeoManager.java`
- `rxlib/src/main/java/org/rx/net/support/V2RayGeoIpMatcher.java`
- `rxlib/src/main/java/org/rx/net/support/V2RayGeoIpIndex.java`
- `rxlib/src/main/java/org/rx/net/support/V2RayGeoSiteIndex.java`
- `rxlib/src/test/java/org/rx/net/support/V2RayGeoDataTestUtil.java`
- `rxlib/src/test/java/org/rx/net/support/V2RayGeoSiteAttributeTest.java`
- `rxlib/src/test/java/org/rx/net/support/V2RayGeoIpLookupInverseTest.java`
- `rxlib/src/test/java/org/rx/net/support/V2RayGeoManagerLoadTest.java`
- `rxlib/src/test/java/org/rx/net/support/V2RayGeoManagerTest.java`

关键调用链：

1. GeoSite attribute 读取：
   - `V2RayGeoDataReader.readGeoSiteDomain`
   - `readGeoSiteAttributeKey`
   - `V2RayGeoSiteIndex.normalizeAttributes`
   - `AttrSelector.matches`

2. GeoIP raw byte lookup：
   - `V2RayGeoManager.resolveGeoIpCode(byte[])`
   - `V2RayGeoIpMatcher.lookupCode(byte[])`
   - `V2RayGeoIpIndex.lookupCode(byte[])`

3. compile 阶段同步等待：
   - `compileGeoIpMatcher(code)` / `compileGeoSiteMatcher(code, attrFilter)`
   - `ensureIpLoaded()` / `ensureSiteLoaded()`
   - `ensureLoadTask()`
   - `awaitLoadTask()`

4. lazy daily schedule：
   - `ensureLoadTask()`
   - `ensureDailyScheduled()`
   - `Tasks.scheduleDaily(() -> load(true), dailyDownloadTime)`

本轮更新已解决或改善的问题：

- `readGeoSiteAttributeKey` 已读取 `bool_value`，`false` 时不再把 key 当成有效 attribute。
- `int_value` 已作为存在型 attribute 处理。
- 新增 `resolveGeoIpCode(byte[])`，避免 raw IP 场景反复解析字符串。
- setter 从同步 `load(false)` 改为 `ensureLoadTask()`，减少直接阻塞调用线程的风险。
- compile API 已调用 `awaitLoadTask()`，比上一版“触发异步加载后立刻返回 null”更可靠。
- lazy 首次加载会调用 `ensureDailyScheduled()`，默认 `INSTANCE` 后续可注册每日刷新。
- 新增 `V2RayGeoSiteAttributeTest` 和 `V2RayGeoIpLookupInverseTest`，覆盖 attribute false、int attribute、inverse lookup 语义。
- 当前代码仍保持 JDK8 兼容，没有看到 JDK9+ API。

# 目标

1. 保留当前已经完成的 attribute、raw byte lookup、lazy schedule 和 compile 等待改进。
2. 修复加载任务失败结果可能丢失的问题。
3. 明确 compile API、convenience API、setter 和 reload 的阻塞/异步语义。
4. 补齐 daily schedule 重配、加载失败、inverse lookup 边界等剩余测试。
5. 触发并验证 JDK8 GitHub Actions。

# 非目标

1. 本 review 计划不直接修改业务代码。
2. 不引入 protobuf-java 或其他重型依赖。
3. 不改变当前 hand-written protobuf reader 的总体方向。
4. 不修改 secrets/token/证书。
5. 不自动发布 release。
6. 不使用 JDK9+ API。

# 设计方案

## 1. 保留当前改进方向

当前实现已经基本符合热点路径设计：

- GeoIP 热点路径应使用 `compileGeoIpMatcher(code)` 后的 `CodeMatcher.matches(byte[])`。
- GeoSite 热点路径应使用 `compileGeoSiteMatcher(code, attrFilter)` 后的 `GeoSiteMatcher.matches(domain)`。
- `matchGeoIp(String code, String ip)` 和 `matchGeoSite(...)` 保留为 convenience API，不应作为每请求高频路径首选。
- `resolveGeoIpCode(byte[])` 是必要补充，避免已经有 raw IP bytes 的路径又回到字符串解析。

后续修复不要回退这些方向。

## 2. 剩余问题一：加载失败可能被 compile API 静默吞掉

当前 compile 流程是：

1. `ensureIpLoaded()` 或 `ensureSiteLoaded()` 触发 `ensureLoadTask()`；
2. `ensureLoadTask()` 设置 `dTask = Tasks.run(() -> load(false))`；
3. `compile...` 调用 `awaitLoadTask()`；
4. `awaitLoadTask()` 读取当前 `dTask` 并 `get()`。

风险点：

- `load(false)` 在 `finally` 中会把 `dTask = null`；
- 如果异步加载很快失败，并在 `awaitLoadTask()` 读取 `dTask` 之前已经把它置空，compile API 可能不会观察到 Future 的异常；
- 最终表现可能只是返回 `null` matcher，而不是暴露真实加载失败原因；
- 对配置期 compile 来说，这会导致错误被延后到请求期，排查困难。

建议：

- 让 `ensureLoadTask()` 返回本次创建或当前存在的 `Future<Void>`，compile API 等待这个返回值，而不是稍后再读 volatile `dTask`；
- 或新增 `lastLoadError` / `lastLoadFailureAt`，当 compile 后仍无快照时，如果最近加载失败，抛出明确异常；
- 测试增加：加载文件不存在或非法 dat 时，`compileGeoIpMatcher` / `compileGeoSiteMatcher` 应抛异常，而不是静默返回 null。

## 3. 剩余问题二：dailyDownloadTime 修改后不会重排 schedule

当前 `ensureDailyScheduled()` 只根据 `dailyScheduled` 布尔值注册一次每日任务。

风险点：

- 如果调用 `setDailyDownloadTime()` 发生在首次加载之后，已有 schedule 不会更新；
- 如果项目允许运行时调整下载时间，这个 setter 语义会不符合直觉。

建议：

- 如果运行时不支持重排，注释明确 `dailyDownloadTime` 必须在首次加载前设置；
- 如果支持运行时重排，需要保留 schedule handle 并 cancel/reschedule；
- 增加测试覆盖：首次加载后 setDailyDownloadTime 的预期行为。

## 4. 剩余问题三：compile API 会阻塞，必须标明不可在 EventLoop/请求线程使用

上一轮担心 compile API 可能返回 null；本轮改为等待加载任务。这解决了配置期 race，但引入另一层语义：

- compile 可能等待下载、文件 IO、dat 解析；
- 默认 timeout 是 5 分钟；
- 如果在 Netty EventLoop 或请求线程调用，会造成长时间阻塞。

建议：

- Javadoc 明确 compile API 是启动/配置阶段 API，不应在请求线程调用；
- 可提供非阻塞版本，例如 `tryCompileGeoIpMatcher` / `tryCompileGeoSiteMatcher`，只读当前 snapshot，不触发等待；
- convenience API 是否允许异步触发加载并返回 false/null，也要在注释中写清楚。

## 5. 剩余问题四：attribute int_value 语义被简化为“存在”

当前 `readGeoSiteAttributeKey`：

- `bool_value=false` 会忽略 attribute；
- `int_value` 会读掉数值并设置 `present = true`，但不会保留具体 int。

这对现有 `@attr` 过滤可能足够，但它不是完整 typed attribute 支持。

建议：

- 在类注释或方法注释中明确：当前只支持 attribute key 存在性过滤，`int_value` 不参与值比较；
- 如果未来需要 `@attr=7` 这类语义，需要引入 typed attribute model，而不是 `String[]`。

## 6. 剩余问题五：inverse lookup 测试覆盖仍偏少

新增 `V2RayGeoIpLookupInverseTest` 固定了一个 normal + 两个 inverse 的 IPv4 场景，这是好的。

仍建议补：

- IPv6 inverse lookup；
- inverse range 覆盖全量地址时的 lookup 行为；
- 多个 inverse entries 同时匹配时，是否按原始 entry order 决定 code；
- normal entry 与 inverse complement 重叠时，当前 `TreeSet` comparator 以 order/id 选 `first()`，应明确这是期望语义。

## 7. 剩余问题六：setter 异步化后，调用方立即读取可能看到旧快照

setter 改成 `ensureLoadTask()` 后不再同步阻塞，这是对 I/O 线程更安全的方向。

但语义变为：

- `setGeoIpFile(...)` / `setGeoSiteFile(...)` 返回后，新文件不一定已加载；
- 立即调用 `geoIpMatcher()` / `geoSiteIndex()` / convenience match 可能看到旧 snapshot；
- compile API 因为会等待 `dTask`，通常能拿到新 snapshot。

建议：

- setter Javadoc 明确“异步加载”；
- 调用方如果需要同步生效，应调用 `reload()` + `waitLoad()` 或 compile API；
- 增加测试覆盖 setter 后立即读取与等待后的行为。

## 8. 剩余问题七：CI 仍未验证

已查询 `agent/v2ray-geodat-plan` 分支 GitHub Actions runs，当前仍为：

- `total_count=0`

因此不能认为：

- JDK8 编译通过；
- 新增测试通过；
- `ApplicationException.sneaky(...)`、`Tasks.run(...)`、泛型访问级别等项目内 API 组合一定编译通过。

# 修改文件列表

后续修复阶段预计可能修改：

- `rxlib/src/main/java/org/rx/net/support/V2RayGeoManager.java`
- `rxlib/src/main/java/org/rx/net/support/V2RayGeoDataReader.java`
- `rxlib/src/main/java/org/rx/net/support/V2RayGeoIpMatcher.java`
- `rxlib/src/main/java/org/rx/net/support/V2RayGeoIpIndex.java`
- `rxlib/src/test/java/org/rx/net/support/V2RayGeoManagerLoadTest.java`
- `rxlib/src/test/java/org/rx/net/support/V2RayGeoManagerTest.java`
- `rxlib/src/test/java/org/rx/net/support/V2RayGeoIpLookupInverseTest.java`
- `rxlib/src/test/java/org/rx/net/support/V2RayGeoSiteAttributeTest.java`

可能新增：

- `rxlib/src/test/java/org/rx/net/support/V2RayGeoManagerCompileFailureTest.java`
- `rxlib/src/test/java/org/rx/net/support/V2RayGeoManagerScheduleTest.java`
- `rxlib/src/test/java/org/rx/net/support/V2RayGeoIpLookupInverseIpv6Test.java`

# 风险点

1. 修复加载失败可观测性时，如果直接把 compile API 改成同步 `load(false)`，可能让调用线程承担下载和解析成本；需要保持语义清晰。
2. daily schedule 如果支持重排，需要确认 `Tasks.scheduleDaily` 是否返回可取消 handle；如果不支持，只能文档化限制。
3. typed attribute 如果要完整支持，会改变现有 `String[] attributes` 结构，改动范围会变大。
4. setter 异步化与 compile 阻塞化并存，需要清楚区分“配置更新”和“配置生效”。
5. 当前还没有 CI 结果，review 结论不能替代 JDK8 编译和单测验证。

# 验证方案

修复实现后必须触发 `.github/workflows/jdk8-unit-tests.yml`，分支过滤为 `agent/v2ray-geodat-plan`。

建议 `test_classes`：

`V2RayGeoDataReaderTest,V2RayGeoSiteMatcherTest,V2RayGeoIpMatcherTest,V2RayGeoManagerTest,V2RayGeoIpMatcherHotPathTest,V2RayGeoSiteHotPathTest,V2RayGeoManagerLoadTest,V2RayGeoSiteAttributeTest,V2RayGeoIpLookupInverseTest,V2RayGeoManagerCompileFailureTest,V2RayGeoManagerScheduleTest,GeoSiteMatcherTest,UltraDomainMatcherTest,GeoIPSearcherTest`

只有 workflow run 的 `conclusion=success` 才能认为 CI 通过。

# 后续执行条件

本提交仅新增 review 计划文档，不修改业务代码。

收到“按 review 计划修复 / 开始改代码 / 执行优化”后，再进入代码修复阶段。
