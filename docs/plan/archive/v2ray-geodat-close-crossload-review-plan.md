# 背景

用户反馈“分支已更新，再 review 下”。本次 review 针对 `agent/v2ray-geodat-plan` 分支最新代码更新。

当前分支最新提交：

- HEAD：`766504c2bd3948f99f6f0705a0c7d9b13d7789da`
- commit message：`feat(net): improve V2RayGeoManager close lifecycle and cross-load task isolation`

本次只做 Review / 修复计划，不修改业务代码。

# 任务类型判断

本次任务归类为 Review / 修复 / 优化需求：

- 用户要求“再 review”；
- 分支已经存在多轮 v2ray geodata 实现和优化；
- 本轮更新主要处理上一轮提出的 cross-load isolation、close lifecycle、schedule 非法时间半更新和 API 语义文档；
- 需要继续确认实现是否真正满足热点路径和生命周期安全要求。

# 当前上下文

已 review 的关键文件：

- `rxlib/src/main/java/org/rx/net/support/V2RayGeoManager.java`
- `rxlib/src/test/java/org/rx/net/support/V2RayGeoManagerCloseTest.java`
- `rxlib/src/test/java/org/rx/net/support/V2RayGeoManagerCrossLoadIsolationTest.java`
- `rxlib/src/test/java/org/rx/net/support/V2RayGeoManagerScheduleTest.java`
- `rxlib/src/main/java/org/rx/core/Tasks.java`

关键调用链：

1. side-scoped load task：
   - `ensureIpLoaded()` / `ensureSiteLoaded()`
   - `ensureLoadTask(boolean loadIp, boolean loadSite)`
   - `dTaskLoadIp` / `dTaskLoadSite`
   - `currentLoadTask(boolean ipSide)`
   - `compileGeoIpMatcher` / `compileGeoSiteMatcher`

2. close lifecycle：
   - `close()`
   - `closed = true`
   - cancel `dTask`
   - cancel `dailyTasks`
   - clear `ipMatcher` / `siteIndex`
   - `load(boolean force)` 内部检查 `closed`

3. schedule lifecycle：
   - `setDailyDownloadTime(...)`
   - 先 `java.sql.Time.valueOf(...)` 校验
   - 成功后 schedule 新任务，再替换 `dailyTasks` 并 cancel old tasks

本轮更新已解决或改善的问题：

- 增加类级 Javadoc，明确：
  - match/resolve/geo 访问类 API 是 lazy non-blocking；
  - compile/waitLoad 是配置期同步 API；
  - set 配置是异步触发加载；
  - 热点路径应复用预编译 matcher。
- `setDailyDownloadTime` 已先校验时间格式，避免非法时间导致字段半更新。
- `setDailyDownloadTime` 已覆盖 reschedule 后旧任务 cancel。
- `close()` 已引入 `closed` 标记，取消 `dTask`，取消 `dailyTasks`，并清理 snapshot。
- `ensureLoadTask` 已引入 `dTaskLoadIp` / `dTaskLoadSite`，compile 只等待相关侧的 task。
- 新增 `V2RayGeoManagerCrossLoadIsolationTest` 覆盖已有 snapshot + 另一侧 failed future 的隔离。
- 新增 `V2RayGeoManagerCloseTest` 覆盖 close 后 cancel task、清 snapshot、reject reload。
- 新增 schedule 非法时间测试，验证旧状态保留。

# 目标

1. 保留当前新增的 API 语义文档、schedule 校验、close lifecycle 和测试方向。
2. 修复 side-scoped metadata 与实际 `load(false)` 行为不一致的问题。
3. 修复 `close()` 与 synchronized `load()` 没有同锁互斥的发布竞态。
4. 补齐真实 load 场景下的 cross-side failure 测试。
5. 最终通过 JDK8 GitHub Actions 验证。

# 非目标

1. 本 review 计划不直接修改业务代码。
2. 不改变当前 hand-written protobuf reader 方向。
3. 不引入 protobuf-java 或其他重型依赖。
4. 不修改 secrets/token/证书。
5. 不自动发布 release。
6. 不使用 JDK9+ API。

# 设计方案

## 1. 保留当前已经正确的方向

当前实现方向继续保持：

- GeoIP 热点路径使用 `compileGeoIpMatcher(code)` 后的 `CodeMatcher.matches(byte[])`。
- GeoSite 热点路径使用 `compileGeoSiteMatcher(code, attrFilter)` 后的 `GeoSiteMatcher.matches(domain)`。
- convenience API 是 lazy non-blocking，不承诺首次调用同步可用。
- setter 异步触发加载，不在调用线程直接下载/解析。
- schedule 支持动态重排，非法时间不破坏旧任务。
- close 后应拒绝重新加载和重新发布 snapshot。

## 2. 剩余问题一：dTaskLoadIp/Site 只是 metadata，实际 load 仍可能加载两侧

当前 `ensureLoadTask(true, false)` / `ensureLoadTask(false, true)` 只记录 `dTaskLoadIp` / `dTaskLoadSite`，但创建的任务仍是：

```java
Tasks.run(() -> load(false))
```

而 `load(false)` 内部仍按 `shouldLoadGeoConfig(...)` 同时判断 GeoIP 和 GeoSite：

- 如果 GeoIP 配置可加载，就加载 GeoIP；
- 如果 GeoSite 配置可加载，就加载 GeoSite；
- 它并不知道本次任务原本只为 IP 侧或 Site 侧触发。

因此存在真实场景风险：

- 调用 `compileGeoIpMatcher` 只想加载 GeoIP；
- 但 GeoSite 也满足 `shouldLoadGeoConfig`，并且 GeoSite 文件损坏或下载失败；
- `load(false)` 会因为 GeoSite 失败导致整个 task 失败；
- GeoIP compile 仍可能被另一侧真实加载失败误伤。

当前 `V2RayGeoManagerCrossLoadIsolationTest` 主要通过手动设置 completed/failed `dTask` 验证 `currentLoadTask` 不等待另一侧 failed future，但没有覆盖 `load(false)` 真实同时加载两侧的问题。

建议修复方向：

- 把 `load(boolean force)` 改为 `load(boolean force, boolean loadIp, boolean loadSite)`；
- `ensureLoadTask(loadIp, loadSite)` 创建任务时把 scope 传入 load；
- daily reload 或显式 `reload()` 可以传 `true, true`；
- `ensureLoaded()` 根据缺失侧传入实际 scope；
- setter 触发只传单侧 scope。

这样 `dTaskLoadIp/Site` 才不是单纯等待 metadata，而是和真实加载行为一致。

## 3. 剩余问题二：已有 dTask scope 扩展不会影响已启动 load 的实际范围

当前如果已有 `dTask` 正在运行，后续 `ensureLoadTask` 会把：

```java
dTaskLoadIp = dTaskLoadIp || loadIp;
dTaskLoadSite = dTaskLoadSite || loadSite;
```

但已启动的 `load(false)` 没有读取这些 flags，因此：

- metadata 显示这个 task 覆盖 IP + Site；
- 实际 load 行为不是由 metadata 决定，而是由当时配置和 `shouldLoadGeoConfig` 决定；
- 如果未来改成 scoped load，已运行任务也无法动态扩大 scope。

建议：

- 不要在 task 已启动后只修改 flags 并认为它会覆盖新增 scope；
- 对新增 scope 可以：
  - 等当前 task 完成后再启动下一轮 scoped task；
  - 或在创建 task 前确定需要加载的完整 scope；
  - 或保留全量 load 语义，但不要宣称 side isolation。

更稳妥的设计是维护两个独立 future：

- `ipTask`
- `siteTask`

如果实现成本可接受，两个 future 能彻底避免 cross-side failure 和 scope 扩展歧义。

## 4. 剩余问题三：close 与 synchronized load 没有同锁互斥，仍可能 close 后发布 snapshot

`load(boolean force)` 是 `synchronized` 方法，但 `close()` 不是 synchronized。

风险场景：

1. `load()` 正在执行，已经通过 `if (closed)` 检查；
2. `load()` 构建完新 matcher/index，进入发布前；
3. 另一个线程调用 `close()`，设置 `closed=true`，清空 `ipMatcher/siteIndex`；
4. `load()` 继续执行，把新 snapshot 写回 `ipMatcher/siteIndex`。

虽然 `load()` 中有第二次 `if (closed)`，但因为 `close()` 没有与 `load()` 使用同一把锁，这个检查与后续发布不是原子互斥的。

建议：

- `close()` 改为 `synchronized`，与 `load()` 互斥；
- 或把 `closed` 检查和 snapshot 发布放进 synchronized block，并让 close 也用同一锁；
- close 内先设置 `closed`，再 cancel task 和清 snapshot。

需要补测试：

- 构造可控的慢 load，在 close 与 publish 之间竞争，确保 close 后不会再发布 snapshot。

## 5. 剩余问题四：close 后 directSiteExtraMatcher 也被清空，setExtraRules 后不可恢复

当前 close 后清理了：

- `ipMatcher`
- `siteIndex`
- `directSiteMatcher`
- `directSiteExtraMatcher`

并且后续 `reload()` 会被 `checkOpen()` 拒绝。

这符合“close 后对象不可用”的语义。但需要注意：

- 如果调用方只想释放当前 dat snapshot 后重新配置复用 manager，不再支持；
- 类级 Javadoc 应明确 close 是终态，不支持 reopen。

## 6. 剩余问题五：CI 仍未验证

已查询 `agent/v2ray-geodat-plan` 分支 GitHub Actions runs，当前仍为：

- `total_count=0`

因此不能认为：

- JDK8 编译通过；
- 新增测试通过；
- `CompletableFuture<Void>` 作为 `Future<Void>` 后 `cancel(true)` 行为符合测试预期；
- `InvalidException` 被 `ApplicationException.sneaky` 传播后能被 `assertThrows(InvalidException.class, ...)` 捕获；
- `ScheduledFuture` 泛型和测试包可见字段访问无编译问题。

# 修改文件列表

后续修复阶段预计可能修改：

- `rxlib/src/main/java/org/rx/net/support/V2RayGeoManager.java`
- `rxlib/src/test/java/org/rx/net/support/V2RayGeoManagerCrossLoadIsolationTest.java`
- `rxlib/src/test/java/org/rx/net/support/V2RayGeoManagerCloseTest.java`
- `rxlib/src/test/java/org/rx/net/support/V2RayGeoManagerLoadTest.java`

可能新增：

- `rxlib/src/test/java/org/rx/net/support/V2RayGeoManagerScopedLoadTest.java`
- `rxlib/src/test/java/org/rx/net/support/V2RayGeoManagerCloseRaceTest.java`

# 风险点

1. 改成 scoped load 后，daily reload 和 explicit reload 必须仍能全量刷新两侧。
2. 如果拆成 `ipTask` / `siteTask`，需要处理同时 reload、close、setter 和 compile 的并发关系，改动会变大。
3. `close()` synchronized 后，若内部 cancel/wait 处理不当，可能和 load 长时间互斥；close 不应等待下载完成。
4. 为了测试 close race，不应引入不稳定 sleep 测试，最好使用可控 hook 或可替换 reader。
5. 当前还没有 CI 结果，review 结论不能替代 JDK8 编译和单测验证。

# 验证方案

修复实现后必须触发 `.github/workflows/jdk8-unit-tests.yml`，分支过滤为 `agent/v2ray-geodat-plan`。

建议 `test_classes`：

`V2RayGeoDataReaderTest,V2RayGeoSiteMatcherTest,V2RayGeoIpMatcherTest,V2RayGeoManagerTest,V2RayGeoIpMatcherHotPathTest,V2RayGeoSiteHotPathTest,V2RayGeoManagerLoadTest,V2RayGeoSiteAttributeTest,V2RayGeoIpLookupInverseTest,V2RayGeoManagerCompileFailureTest,V2RayGeoManagerScheduleTest,V2RayGeoManagerCrossLoadIsolationTest,V2RayGeoManagerCloseTest,V2RayGeoManagerScopedLoadTest,V2RayGeoManagerCloseRaceTest,GeoSiteMatcherTest,UltraDomainMatcherTest,GeoIPSearcherTest`

只有 workflow run 的 `conclusion=success` 才能认为 CI 通过。

# 后续执行条件

本提交仅新增 review 计划文档，不修改业务代码。

收到“按 review 计划修复 / 开始改代码 / 执行优化”后，再进入代码修复阶段。
