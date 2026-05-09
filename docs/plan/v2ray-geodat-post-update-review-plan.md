# 背景

用户反馈“代码已更新，再 review 下”。本次 review 针对 `agent/v2ray-geodat-plan` 分支最新代码更新。

当前分支最新提交：

- HEAD：`b5459547a12b550ca38dd36714df55bdd53ffc4a`
- commit message：`perf(net): support pre-compiled CodeMatcher and lazy loading in V2RayGeoManager`

本次只做 Review / 修复计划，不修改业务代码。

# 任务类型判断

本次任务归类为 Review / 修复 / 优化需求：

- 用户要求“再 review”；
- 分支已有新的性能优化代码；
- 上一轮重点是热点路径，本轮需要确认热点路径风险是否已被消除，并识别新引入的语义、并发、加载和验证风险。

# 当前上下文

已 review 的关键文件：

- `rxlib/src/main/java/org/rx/net/support/V2RayGeoDataReader.java`
- `rxlib/src/main/java/org/rx/net/support/V2RayGeoIpIndex.java`
- `rxlib/src/main/java/org/rx/net/support/V2RayGeoIpMatcher.java`
- `rxlib/src/main/java/org/rx/net/support/V2RayGeoManager.java`
- `rxlib/src/main/java/org/rx/net/support/V2RayGeoSiteIndex.java`
- `rxlib/src/main/java/org/rx/net/support/V2RayGeoSiteReader.java`
- `rxlib/src/main/java/org/rx/net/support/V2RayGeoIpReader.java`
- 新增测试：
  - `V2RayGeoIpMatcherHotPathTest`
  - `V2RayGeoSiteHotPathTest`
  - `V2RayGeoManagerLoadTest`

关键调用链：

1. GeoIP 预编译热点路径：
   - `V2RayGeoManager.compileGeoIpMatcher(code)`
   - `V2RayGeoIpMatcher.matcher(code)`
   - `V2RayGeoIpIndex.matcher(code)`
   - `V2RayGeoIpMatcher.CodeMatcher.matches(byte[])`
   - `V2RayGeoIpIndex.CodeMatcher.matches(byte[])`

2. GeoSite 预编译热点路径：
   - `V2RayGeoManager.compileGeoSiteMatcher(code, attrFilter)`
   - `V2RayGeoSiteIndex.matcher(code, attrFilter)`
   - `GeoSiteMatcher.matches(domain)`

3. Lazy loading 链路：
   - `ensureIpLoaded()`
   - `ensureSiteLoaded()`
   - `ensureLoadTask()`
   - `load(force)`

本轮更新已解决或改善的问题：

- 新增 `V2RayGeoIpMatcher.CodeMatcher`，支持配置期绑定 code，请求期传入 `byte[]`，避免每次解析 code。
- `V2RayGeoIpIndex.buildCodeMatcher` 会合并同 code 下多个 normal entry 的 IPv4/IPv6 ranges，降低 `CodeMatcher.matches` 的线性遍历风险。
- `V2RayGeoManager` 已拆出 `ensureIpLoaded()` 和 `ensureSiteLoaded()`，避免只查 GeoIP 时强制加载 GeoSite。
- 新增热点路径测试覆盖预绑定 matcher、多 normal entry 合并、IPv4/IPv6 边界、单侧 lazy load 场景。
- 新增源码已恢复多行格式，可维护性比上一版明显改善。

# 目标

1. 保留当前已经完成的热点路径优化方向。
2. 修复或明确剩余的加载语义、异步编译 API、attribute 解析和 CI 验证风险。
3. 避免把 convenience API 误用于热点路径。
4. 确保长期运行时 dat 更新策略符合项目预期。
5. 补齐还缺的边界测试。

# 非目标

1. 本 review 计划不修改业务代码。
2. 不引入 protobuf-java 或其他重型依赖。
3. 不改变当前手写 protobuf reader 的总体方向。
4. 不改 secrets/token/证书。
5. 不自动发布 release。
6. 不使用 JDK9+ API。

# 设计方案

## 1. 继续保留的实现方向

当前代码已经把热点路径拆为两类 API：

- convenience API：
  - `matchGeoIp(String code, String ip)`
  - `matchGeoSite(String code, String attrFilter, String domain)`
- 预编译 API：
  - `compileGeoIpMatcher(String code)` -> `CodeMatcher.matches(byte[])`
  - `compileGeoSiteMatcher(String code, String attrFilter)` -> `GeoSiteMatcher.matches(domain)`

这个方向是正确的。后续修复不应回退到每请求 parse code、parse selector 或 scan code rules 的模式。

## 2. 剩余问题一：compile API 异步返回 null 的语义不清

当前 `compileGeoIpMatcher(code)` / `compileGeoSiteMatcher(code, attrFilter)` 会：

1. 调用 `ensureIpLoaded()` 或 `ensureSiteLoaded()`；
2. 如果尚未加载，只提交异步 `dTask`；
3. 立即读取当前 volatile 快照；
4. 快照还没准备好时返回 `null`。

这对热点路径配置期 API 来说容易误用：调用方以为 compile 是“同步编译并返回 matcher”，实际可能只是“触发异步加载”。新增测试也通过 `waitLoad()` 后再次 retry 规避了这个问题。

建议二选一：

- 增加同步 API：
  - `compileGeoIpMatcherBlocking(String code)`
  - `compileGeoSiteMatcherBlocking(String code, String attrFilter)`
  - 内部触发加载并等待 `waitLoad()`，失败时抛出明确异常。
- 或调整现有 compile API 语义：
  - compile 方法在需要加载时同步 `load(false)` 或等待 `dTask` 完成；
  - 保留非阻塞版本命名为 `tryCompile...`。

不建议让当前 `compile...` 在配置期静默返回 null，因为调用方很容易在启动配置阶段缓存了 null，导致后续请求永远没有预编译 matcher。

## 3. 剩余问题二：默认 INSTANCE 不自动刷新

当前 `V2RayGeoManager.INSTANCE = new V2RayGeoManager(false)`：

- 默认不会构造时加载；
- 默认不会注册 `Tasks.scheduleDaily`；
- 尽管 `geoIpFileUrl` / `geoSiteFileUrl` 已有默认远端 URL，长期运行时不会自动更新 dat。

这和现有 `GeoManager.INSTANCE` 的自动加载/每日刷新语义不同。

建议明确项目期望：

- 如果 v2ray dat 是核心路由数据，建议 `INSTANCE` 使用 auto-load，并注册 daily schedule；
- 如果必须 lazy，建议首次 `ensureLoadTask()` 时注册一次 daily schedule，或者文档明确必须由业务初始化调用 `reload()` / 自行调度。

## 4. 剩余问题三：setter 同步 load 可能阻塞调用线程

`setGeoIpFileUrl`、`setGeoIpFile`、`setGeoSiteFileUrl`、`setGeoSiteFile` 在配置满足时会直接调用 `load(false)`。

这在启动阶段可接受，但如果调用发生在 Netty EventLoop 或请求线程，会触发下载/文件 IO/protobuf 解析，造成阻塞。

建议：

- setter 只更新配置并清理旧快照或提交异步加载任务；
- 显式提供 `reload()` / `reloadAsync()`；
- 文档说明 setter 不应在 I/O 线程调用。

## 5. 剩余问题四：geosite attribute 只解析 key，忽略 typed value

`V2RayGeoDataReader.readGeoSiteAttributeKey` 当前只读取 Attribute 的 field 1 key，并跳过其他字段。

风险：

- 如果 dat 中出现 `bool_value=false`，当前实现仍把 key 当作存在；
- 如果未来使用 `int_value` 表达属性语义，当前实现完全忽略值；
- 当前 attr filter 只支持 key include/exclude，不能表达 typed attribute。

建议：

- 至少读取 `bool_value`，只有未设置或 true 时才认为 attr 存在；
- 如果 `int_value` 暂不支持，应在计划或注释中明确只支持 key/bool 语义；
- 增加测试覆盖 false bool attribute 不应命中 `@attr`。

## 6. 剩余问题五：lookupCode 与 inverse_match 组合需补测试

`lookupCode(byte[])` 构建了全局 IPv4/IPv6 lookup segments，并支持 inverse entry 的 complement range。

需要额外验证：

- normal 与 inverse entry 重叠时，返回 code 的优先级是否符合预期；
- 多个 inverse entries 同时存在时，`TreeSet` active first 的 order/id 选择是否符合 v2ray 数据语义；
- `lookupCode` 作为“返回一个 code”是否应该包含 inverse_match，因为 inverse_match 本质是匹配条件，不一定适合 geo code 解析。

建议增加 `V2RayGeoIpLookupInverseTest`，固定语义，避免后续误用。

## 7. 剩余问题六：CodeMatcher.matches 仍可被 inverse entries 拉回线性路径

当前 `buildCodeMatcher` 只合并 normal entries，inverse entries 仍保留逐个 `EntryMatcher` 遍历。

真实 dat 中同 code 多 inverse entry 可能很少，但热点路径建议：

- 加测试覆盖多 inverse entry 场景；
- 若存在实际数据，考虑对 inverse ranges 做 complement union 后合并；
- 或明确不优化多 inverse entry，因为 v2ray 官方 dat 数据不会产生该模式。

## 8. 剩余问题七：CI 未验证

已查询 `agent/v2ray-geodat-plan` 分支 GitHub Actions runs，当前 `total_count=0`。

因此不能认为：

- JDK8 编译通过；
- 单测通过；
- 新增代码没有 API 兼容问题。

# 修改文件列表

后续修复阶段预计可能修改：

- `rxlib/src/main/java/org/rx/net/support/V2RayGeoManager.java`
- `rxlib/src/main/java/org/rx/net/support/V2RayGeoDataReader.java`
- `rxlib/src/main/java/org/rx/net/support/V2RayGeoSiteIndex.java`
- `rxlib/src/main/java/org/rx/net/support/V2RayGeoIpIndex.java`
- `rxlib/src/main/java/org/rx/net/support/V2RayGeoIpMatcher.java`
- `rxlib/src/test/java/org/rx/net/support/V2RayGeoManagerLoadTest.java`
- `rxlib/src/test/java/org/rx/net/support/V2RayGeoIpMatcherHotPathTest.java`
- `rxlib/src/test/java/org/rx/net/support/V2RayGeoSiteHotPathTest.java`

可能新增：

- `rxlib/src/test/java/org/rx/net/support/V2RayGeoSiteAttributeTest.java`
- `rxlib/src/test/java/org/rx/net/support/V2RayGeoIpLookupInverseTest.java`
- `rxlib/src/test/java/org/rx/net/support/V2RayGeoManagerCompileTest.java`

# 风险点

1. compile API 改成阻塞可能影响现有非阻塞调用预期，需要命名清晰。
2. `INSTANCE` 改 auto-load 会改变启动时网络行为，需要确认是否允许默认触发远端下载。
3. setter 改异步可能改变测试或调用方对同步可用的假设。
4. attribute typed value 支持如果实现不完整，可能引入 geosite 过滤兼容问题。
5. inverse_match lookup 语义需要先用测试固定，否则容易修成另一个不兼容行为。
6. 新增热点路径测试不等于性能基准，仍需手动压测或 JMH 辅助验证。
7. 当前还没有 CI 结果，所有 review 结论都不能替代 JDK8 编译验证。

# 验证方案

修复实现后必须触发 `.github/workflows/jdk8-unit-tests.yml`，分支过滤为 `agent/v2ray-geodat-plan`。

建议 `test_classes`：

`V2RayGeoDataReaderTest,V2RayGeoSiteMatcherTest,V2RayGeoIpMatcherTest,V2RayGeoManagerTest,V2RayGeoIpMatcherHotPathTest,V2RayGeoSiteHotPathTest,V2RayGeoManagerLoadTest,V2RayGeoSiteAttributeTest,V2RayGeoIpLookupInverseTest,V2RayGeoManagerCompileTest,GeoSiteMatcherTest,UltraDomainMatcherTest,GeoIPSearcherTest`

只有 workflow run 的 `conclusion=success` 才能认为 CI 通过。

# 后续执行条件

本提交仅新增 review 计划文档，不修改业务代码。

收到“按 review 计划修复 / 开始改代码 / 执行优化”后，再进入代码修复阶段。
