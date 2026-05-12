# 背景

用户反馈：分支已更新，需要再次 review，并强调 v2ray geodat 读取与匹配会处于热点路径。

本次 review 针对 `agent/v2ray-geodat-plan` 分支最新实现提交：

- HEAD：`d4b41f2df3c65daf7cb8ea8fec2f15338d33e6cf`
- commit message：`feat: implement V2Ray geodata reader and matcher support`

本次只做 Review / 修复计划，不修改业务代码。

# 任务类型判断

本次任务归类为 Review / 修复 / 优化需求：

- 用户要求“再 review 下”；
- 分支已经存在 v2ray geodata 代码实现；
- 用户特别指出“这个是热点路径”，所以 review 重点应从普通功能正确性提升到性能、分配、锁、缓存、热替换和调用链稳定性。

# 当前上下文

已 review 的文件：

- `rxlib/src/main/java/org/rx/net/support/V2RayGeoDataReader.java`
- `rxlib/src/main/java/org/rx/net/support/V2RayGeoSiteIndex.java`
- `rxlib/src/main/java/org/rx/net/support/V2RayGeoSiteReader.java`
- `rxlib/src/main/java/org/rx/net/support/V2RayGeoIpIndex.java`
- `rxlib/src/main/java/org/rx/net/support/V2RayGeoIpMatcher.java`
- `rxlib/src/main/java/org/rx/net/support/V2RayGeoIpReader.java`
- `rxlib/src/main/java/org/rx/net/support/V2RayGeoManager.java`
- `rxlib/src/main/java/org/rx/net/support/GeoManager.java`
- `rxlib/src/test/java/org/rx/net/support/V2RayGeoDataReaderTest.java`
- `rxlib/src/test/java/org/rx/net/support/V2RayGeoSiteMatcherTest.java`
- `rxlib/src/test/java/org/rx/net/support/V2RayGeoIpMatcherTest.java`
- `rxlib/src/test/java/org/rx/net/support/V2RayGeoManagerTest.java`

关键调用链：

1. geosite 读取链路：
   - `V2RayGeoSiteReader.read(file/byte[])`
   - `V2RayGeoDataReader.readGeoSiteList`
   - `V2RayGeoSiteIndex`
   - `V2RayGeoSiteIndex.matcher(code, attrFilter)`
   - `GeoSiteMatcher.matches(domain)`

2. geoip 读取链路：
   - `V2RayGeoIpReader.read(file/byte[])`
   - `V2RayGeoDataReader.readGeoIpList`
   - `V2RayGeoIpIndex`
   - `V2RayGeoIpMatcher.matches(code, ip)` / `lookupCode(ip)`

3. manager 热替换链路：
   - `V2RayGeoManager.ensureLoaded()`
   - `V2RayGeoManager.load(force)`
   - volatile 替换 `ipMatcher`、`siteIndex`、`directSiteMatcher`
   - 延迟关闭旧 index

当前实现意图：

- 手写最小 protobuf reader，避免引入 `protobuf-java`。
- geosite 按 code 分组，按需构造 `GeoSiteMatcher` 并用 Caffeine cache 缓存。
- geoip 将 CIDR 转为 range set，IPv4/IPv6 分离，查询使用二分查找。
- `V2RayGeoManager` 独立于现有 `GeoManager`，提供 v2ray dat 的 match API。

已发现的问题或风险：

1. `matchGeoIp(String code, String ip)` 是热点路径时，每次都会：
   - trim IP 字符串；
   - 调用 `NetUtil.createByteArrayFromIpAddressString` 解析 IP；
   - 进入 `V2RayGeoIpIndex.matches` 后再次 normalize code。
   这会在高频请求中产生 CPU 和分配压力。

2. `V2RayGeoIpIndex.matches(code, ipBytes)` 每次按字符串 code 查 map，并对 code 做 trim/lower/prefix 处理。对于规则固定的代理/路由热点路径，应该在配置加载时预绑定 `CodeMatcher`。

3. `CodeMatcher.matches` 对同一 code 下多个 `EntryMatcher` 线性遍历。真实 dat 多数 code 可能只有一个 entry，但实现不应依赖这个假设；如果同 code 多 entry 或被后续合并输入触发，会退化成 O(entryCount * log ranges)。

4. `V2RayGeoSiteIndex.matches(code, attrFilter, domain)` 每次都会 parse selector、构造 cacheKey、进入 cache。即使 matcher 已缓存，仍存在字符串处理开销。热点调用应直接持有 `GeoSiteMatcher`。

5. `V2RayGeoSiteIndex.matcher(code, attrFilter)` 首次 cache miss 会扫描该 code 的全部 rule 并构造 `GeoSiteMatcher`。如果热点路径中出现大量动态 code/attr 组合，首次请求延迟不可控。

6. `V2RayGeoManager.ensureLoaded()` 使用 `ipMatcher != null || siteIndex != null` 作为已加载判断。对于只加载了一侧、另一侧配置后来可用、或某一侧加载失败恢复的场景，调用另一侧 API 可能不会触发补加载。建议拆成 `ensureIpLoaded` 和 `ensureSiteLoaded`。

7. `V2RayGeoManager.INSTANCE = new V2RayGeoManager(false)` 默认不会构造时自动加载，也不会注册 daily schedule。虽然 lazy load 可用，但这和现有 `GeoManager.INSTANCE` 的自动加载/定时刷新语义不同，可能是行为兼容风险。

8. 新增 Java 源文件在 raw 内容中表现为单行文件。Java 编译可通过不代表可维护，后续 diff、review、定位 CI 报错都会很困难。建议恢复项目常规格式。

9. 测试覆盖了基本功能，但还缺少热点路径相关回归：
   - `matchGeoIp(code, byte[])` 与预绑定 matcher 的零字符串解析路径；
   - 多 entry 同 code 合并；
   - IPv6 unsigned 边界、`ffff:ffff:...` 邻接合并；
   - `ensureLoaded` 单侧加载；
   - matcher cache 命中/失效；
   - 大规则集性能基线。

10. 当前分支未发现 `jdk8-unit-tests.yml` 在 `agent/v2ray-geodat-plan` 上的 workflow run，不能认为 CI 已通过。

# 目标

1. 将 v2ray geo 匹配热路径改为“配置期绑定、请求期零解析或低解析”。
2. 保持现有 API 可用，但文档和内部调用优先使用预编译 matcher。
3. 减少每次请求的字符串 normalize、cache key 构造、map lookup、IP parse。
4. 提升 `V2RayGeoManager` 加载语义的可预测性。
5. 补齐热点路径单测和边界测试。
6. 代码格式恢复为项目可 review 的多行 Java 源码。
7. 最终必须通过 JDK8 GitHub Actions 验证。

# 非目标

1. 本 review 计划不直接修改业务代码。
2. 不引入 protobuf-java 或其他重型依赖。
3. 不改变 v2ray dat 文件来源和下载协议。
4. 不删除现有 `GeoManager` 的 mmdb/text 逻辑。
5. 不引入 JDK9+ API。
6. 不做 release、不修改 secrets/token/证书。

# 设计方案

## 1. GeoIP 热路径改造

建议新增或暴露预绑定 matcher：

- `V2RayGeoIpMatcher.matcher(String code)` 返回 `V2RayGeoIpMatcher.CodeMatcherView` 或直接返回内部 `CodeMatcher` 包装。
- 调用方在规则加载/配置编译阶段解析一次 code。
- 请求期调用：
  - `matches(byte[] ipBytes)`
  - 或 `matchesIpv4(int ip)` / `matchesIpv6(long high, long low)`

建议保留现有便捷 API：

- `matches(String code, String ip)` 作为非热点路径 convenience API。
- Javadoc 或注释明确：热点路径不要使用该重载。

进一步优化：

- 将 `V2RayGeoIpIndex.matches(code, ipBytes)` 的 code normalize 移到 matcher 获取阶段。
- 如果同 code 多 entry，加载阶段合并为一个 `EntryMatcher`：
  - normal entry：合并 IPv4/IPv6 ranges；
  - inverse entry：明确语义后再合并，避免简单 union 破坏 inverse 规则。
- 对 `lookupCode(String ip)` 当前已有 cache，可保留，但热点路径优先使用 `lookupCode(byte[])` 或预解析后的 IP 表示。

## 2. GeoSite 热路径改造

建议将 geosite 分为“编译阶段”和“匹配阶段”：

- 编译阶段：`GeoSiteMatcher matcher = index.matcher("cn", null)`。
- 请求阶段：`matcher.matches(domain)`。

对 `V2RayGeoManager` 增加明确方法：

- `GeoSiteMatcher compileGeoSiteMatcher(String code)`
- `GeoSiteMatcher compileGeoSiteMatcher(String code, String attrFilter)`

保留：

- `matchGeoSite(code, attrFilter, domain)` 作为非热点便捷 API。

缓存策略：

- 对配置中声明的 code/attr 组合，在 load 完成后预热构建，避免首个请求承担全量规则过滤和 trie 构建。
- `matcherCache.maximumSize(256)` 可保留，但需考虑配置项数量，防止动态 code/attr 输入造成 cache churn。

## 3. Manager 加载语义修正

建议拆分加载判断：

- `ensureIpLoaded()`：仅 geoip API 调用。
- `ensureSiteLoaded()`：仅 geosite API 调用。
- `ensureLoaded()` 可保留给同时需要两者的场景。

`INSTANCE` 初始化策略建议二选一：

- 与现有 `GeoManager` 对齐：`new V2RayGeoManager(true)`，启动时异步加载并注册 daily schedule。
- 如果必须 lazy：保留 false，但在首次加载时也要确保 daily schedule 被注册一次。

`load(force)` 建议保持“构建成功后再整体替换”的当前方向，但要确保某一侧失败不会阻断另一侧已有可用快照，也不要因为一侧已加载导致另一侧永远不加载。

## 4. 源码格式与可维护性

新增源码和测试应恢复多行格式，至少满足：

- package/import/class/method 正常换行；
- 单个方法不要压成一行；
- 便于 GitHub diff 和 CI 错误定位；
- 不改变逻辑，仅格式化时单独 commit，便于 review。

## 5. 测试补强

新增/补充测试：

- `V2RayGeoIpMatcherHotPathTest`
  - 预绑定 code matcher 后 byte[] 查询不需要重复 code normalize。
  - 多 entry 同 code 合并后结果一致。
  - IPv4/IPv6 边界：`0.0.0.0/0`、`255.255.255.255/32`、`::/0`、`ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff/128`。
- `V2RayGeoSiteHotPathTest`
  - `index.matcher(code, attr)` 返回对象可重复复用。
  - cache miss 只发生在编译阶段，匹配阶段不 parse selector。
- `V2RayGeoManagerLoadTest`
  - 仅 site 配置、仅 ip 配置、两者配置、一侧失败恢复。
  - `INSTANCE` 是否 lazy/auto-load 的预期行为固定下来。

## 6. 性能验证

不建议让 CI 跑耗时 benchmark，但应保留可手动执行的轻量性能验证：

- 10w domain suffix 构建 + 100w 次匹配；
- 10w IPv4 range + 100w 次 `matches(byte[])`；
- 10w IPv6 range + 100w 次 `matches(byte[])`；
- 比较 convenience API 和预绑定 API 的耗时差异。

# 修改文件列表

预计后续修复阶段可能修改：

- `rxlib/src/main/java/org/rx/net/support/V2RayGeoIpMatcher.java`
- `rxlib/src/main/java/org/rx/net/support/V2RayGeoIpIndex.java`
- `rxlib/src/main/java/org/rx/net/support/V2RayGeoSiteIndex.java`
- `rxlib/src/main/java/org/rx/net/support/V2RayGeoManager.java`
- `rxlib/src/main/java/org/rx/net/support/V2RayGeoDataReader.java`（如仅格式化也应单独处理）
- `rxlib/src/test/java/org/rx/net/support/V2RayGeoIpMatcherTest.java`
- `rxlib/src/test/java/org/rx/net/support/V2RayGeoSiteMatcherTest.java`
- `rxlib/src/test/java/org/rx/net/support/V2RayGeoManagerTest.java`

可能新增：

- `rxlib/src/test/java/org/rx/net/support/V2RayGeoIpMatcherHotPathTest.java`
- `rxlib/src/test/java/org/rx/net/support/V2RayGeoSiteHotPathTest.java`
- `rxlib/src/test/java/org/rx/net/support/V2RayGeoManagerLoadTest.java`

# 风险点

1. 预绑定 matcher API 如果设计不好，可能暴露内部可变结构；必须只暴露 immutable view。
2. 合并同 code 多 entry 时，normal/inverse 语义不能混淆。
3. IPv6 unsigned 比较和边界加减容易出错，必须测试覆盖。
4. convenience API 与 hotspot API 并存时，调用方可能继续误用慢路径，需要命名或注释清楚。
5. 格式化单行文件可能产生巨大 diff，建议单独 commit，避免和逻辑修复混在一起。
6. `INSTANCE` 从 lazy 改 auto-load 可能改变启动时网络行为，需要确认项目期望。
7. 当前分支未跑 CI，任何 review 结论都不能替代 JDK8 编译和测试验证。

# 验证方案

修复实现后必须触发 `.github/workflows/jdk8-unit-tests.yml`，分支过滤为 `agent/v2ray-geodat-plan`。

建议 `test_classes`：

`V2RayGeoDataReaderTest,V2RayGeoSiteMatcherTest,V2RayGeoIpMatcherTest,V2RayGeoManagerTest,V2RayGeoIpMatcherHotPathTest,V2RayGeoSiteHotPathTest,V2RayGeoManagerLoadTest,GeoSiteMatcherTest,UltraDomainMatcherTest,GeoIPSearcherTest`

只有 workflow run 的 `conclusion=success` 才能认为 CI 通过。

# 后续执行条件

本提交仅新增热点路径 review 计划文档，不修改业务代码。

收到“按 review 计划修复 / 开始改代码 / 执行优化”后，再进入代码修复阶段。
