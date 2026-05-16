# 背景

用户要求在当前已更新分支 `agent/rss-client-user-v2ray-rules-plan` 上再次 review，并明确说明：上一次 review 计划中的第 2、3、4 点不修改。

本次按 Review 类任务处理：只 review 最新代码，更新 review 计划文档，不修改业务代码。本次 review 到的当前 HEAD 为 `6542085f1effc380fc2ea96250eb8c9f6a2698d1`。

# 任务类型判断

本次归类为 Review / 修复 / 优化需求。

原因：分支上已有用户级 route 规则实现，且用户明确要求“代码更新了，再 review 下”。按照流程，本阶段只 review 相关代码、更新计划文档并提交，等待用户明确要求后才进入代码修改阶段。

# 当前上下文

## 已 review 的文件

本轮重点 review 了从上次 review commit `9e632458648f9d5d63d36db3e6065e51662dac1a` 到当前 HEAD 的新增 diff：

- `rxlib/src/main/java/org/rx/util/rss/RssClient.java`
- `rxlib/src/main/java/org/rx/util/rss/RssRuntime.java`
- `rxlib/src/main/java/org/rx/util/rss/UserRuleMatcher.java`
- `rxlib/src/test/java/org/rx/net/support/UserRuleMatcherTest.java`
- `rxlib/src/test/java/org/rx/util/rss/RssClientWeightedRoutingTest.java`
- `docs/plan/rss-client-user-v2ray-rules-plan.md`

同时结合既有相关文件继续确认：

- `rxlib/src/main/java/org/rx/util/rss/RssClientConf.java`
- `rxlib/src/main/java/org/rx/util/rss/ShadowUser.java`
- `rxlib/src/main/java/org/rx/util/rss/UserRule.java`
- `rxlib/src/main/java/org/rx/util/rss/RouteAction.java`
- `rxlib/src/test/java/org/rx/util/rss/RssClientUserRouteTest.java`
- `rxlib/src/test/java/org/rx/util/rss/RssTest.java`

## 本轮新增代码变化

1. `RssClient` 新增 `sourceAddress(InetSocketAddress source)`，并在通用入站上游选择里把 `e.getSource().getAddress()` 改成统一判空方法。
2. `RssClient.nextUpstream(...)` 现在只有在 `allowSourceSteering && srcHost != null && useSourceSteering(...)` 时才使用 source steering，否则降级为普通 weighted 选择。
3. `RssRuntime` 的 SS TCP/UDP route handler 也改为通过 `sourceAddress(e.getSource())` 获取源 IP。
4. `RssRuntime.ShadowRoutePlan.nextSupport(...)` 在 `srcHost == null` 时降级为普通 weighted 选择。
5. `UserRuleMatcherTest` 新增：
   - domain 目标不会因为 `geoip:` 命中，除非显式传入目标 IP bytes。
   - `dstIp` 使用目标 IP bytes。
   - `srcIp` 在 source endpoint 未解析时不命中且不抛异常。
6. `RssClientWeightedRoutingTest` 新增：
   - `nextUpstream` 在 source IP 缺失时不写入 null key 粘滞缓存。
   - `ShadowRoutePlan.nextSupport` 在 source IP 缺失时降级为 weighted 选择。
7. `docs/plan/rss-client-user-v2ray-rules-plan.md` 已同步新命名和语义：`V2Ray*` 改为 `UserRule` / `UserRuleMatcher` / `RouteAction`，并明确 `geoip/dstIp/ip/cidr` 不主动 DNS 解析。

## 关键调用链

1. 配置加载链路：
   - `RssClient.normalizeAndValidateRssConfig(conf)`
   - `normalizeAndValidateDefaultRouteRules(conf)`
   - `UserRuleMatcher.compileDefaultRouteRules(...)`
   - `normalizeAndValidateUserRoute(user)`
   - `UserRuleMatcher.compile(user.getRoute(), ...)`

2. 通用入站路由链路：
   - `RssClient.createInSvr(...)`
   - 根据 `TrafficUser` 取 `ShadowUser.routeMatcher`
   - `matchUserRoute(user, dstHost, dstPort, sourceEp)`
   - `BLOCK` 拒绝，`DIRECT` 直连，`PROXY` 选上游。

3. Shadow / SS 专属链路：
   - `RssRuntime.buildShadowServers(...)`
   - `createShadowServer(...)`
   - `ShadowsocksServer.onTcpRoute/onUdpRoute`
   - `matchRoute(ref.routeMatcher, dstHost, dstPort, sourceEp)`
   - `routePlan.nextSupport(sourceAddress(sourceEp), dstEp, srcSteeringTTL)`。

4. DNS 链路：
   - `DnsRemoteServer.ResolveInterceptor.resolveHost(srcIp, host)`
   - 当前仍调用 `matchRoute(null, host)`，只走全局 `defaultRouteMatcher`，不走用户级 `ShadowUser.route`。

5. 热更新链路：
   - `RssRuntime.buildShadowServers(...)`
   - 复用旧 `ShadowServerRef` 时调用 `oldRef.updateRouteMatcher(usr)`
   - `updateRouteMatcher` 会刷新 `routeMatcher` 与 `srcSteeringTTL`。

## 本轮 review 结论

1. 上次第 1 点“`geoip:` / `dstIp` 对域名目标不会命中”已经从风险变为明确设计边界。
   - 计划文档已说明不在 matcher 内主动 DNS 解析。
   - `UserRuleMatcherTest.matchGeoIpRequiresIpBytesForDomainTargets` 和 `matchDstIpRulesUseDestinationIpBytes` 已覆盖。

2. 上次第 5 点 source steering 空源地址风险已做代码防御。
   - `RssClient.sourceAddress(...)` 集中处理空 source。
   - 通用入站 `nextUpstream` 与 SS 专属 `ShadowRoutePlan.nextSupport` 都在 `srcHost == null` 时降级普通 weighted 选择。
   - 新测试覆盖了缺失 source IP 的退化行为。

3. 用户明确要求不修改的第 2、3、4 点保持不改。
   - DNS 仍是全局 `defaultRouteRules`，不做用户级 DNS。
   - 不恢复 / 不迁移旧 `RssClientConf.RouteConf`。
   - `defaultRouteRules` 默认启用行为保持不变。

4. 仍建议补充运行时 handler 层测试。
   - 目前 matcher 层、静态 route 方法和 weighted routing 测试已加强。
   - 但 `RssRuntime.createShadowServer(...)` 内部 `ShadowsocksServer.onTcpRoute/onUdpRoute` 的实际 handler 路径仍缺少直接覆盖。
   - 该点不要求立即改业务代码，但如果后续继续完善测试，建议优先补这部分。

# 目标

1. 记录本轮最新代码 review 结果。
2. 按用户要求把上次 review 计划中的第 2、3、4 点明确列为不修改项。
3. 保留可执行的后续验证方案。
4. 不修改业务代码，不触发 CI，不声称 CI 通过。

# 非目标

本轮明确不做以下事情：

1. 不把 DNS 路由改成用户级策略。
2. 不恢复、不迁移、不兼容旧 `RssClientConf.RouteConf`。
3. 不改变 `defaultRouteRules` 默认启用行为。
4. 不改 `V2RayGeoManager`、geodat 下载或索引实现。
5. 不升级 JDK、Netty、Maven 插件或大版本依赖。
6. 不引入新配置中心或 UI。
7. 不自动发布 release。
8. 不修改 secrets、token、证书、私钥。
9. 不在 review 阶段修改业务代码。

# 设计方案

## 1. 保留现有 GeoIP / dstIp 语义

当前设计保留：

- `domain` / `geosite:` 规则用于域名目标。
- `ip:` / `cidr:` / `dstIp` / `geoip:` 规则用于目标已经是 IP literal，或调用方显式提供目标 IP bytes 的场景。
- `UserRuleMatcher` 内部不主动 DNS 解析，避免在 Netty route 热路径引入阻塞或额外 DNS 污染。

本轮不建议再改该语义。

## 2. 保留全局 DNS 路由边界

根据用户要求，上次第 2 点不修改。因此当前 DNS 设计保持：

- DNS `ResolveInterceptor` 没有用户上下文，只应用 `defaultRouteRules`。
- 用户级 `ShadowUser.route` 只在 TCP/UDP 目标 route 阶段生效。
- 后续不在本任务中设计用户级 DNS。

## 3. 不处理旧 RouteConf 兼容

根据用户要求，上次第 3 点不修改。因此当前行为保持：

- 不恢复 `RssClientConf.RouteConf`。
- 不在 normalize 阶段迁移旧 `route.*` 字段。
- 老配置迁移由文档或外部配置调整承担，不在本轮代码里兼容。

## 4. 不改变默认路由启用行为

根据用户要求，上次第 4 点不修改。因此当前行为保持：

- `defaultRouteRules` 为空时使用默认规则：
  - `geosite:cn direct`
  - `geoip:cn direct`
  - `default proxy`
- 不恢复旧的 `route.enable=false` 风格全局开关。

## 5. 后续可选测试增强

如果后续用户要求继续完善，建议只补测试，不改变业务语义：

1. 增加 `RssRuntime` 内部 route handler 覆盖：
   - SS TCP `srcIp/srcPort/dstIp/dstPort` 命中。
   - SS UDP `srcIp/srcPort/dstIp/dstPort` 命中。
   - `BLOCK` 拒绝路径。
   - `DIRECT` 直连路径。
2. 覆盖热更新复用旧 `ShadowServerRef` 后：
   - `routeMatcher` 更新。
   - `srcSteeringTTL` 更新。
3. 覆盖 source endpoint 为 null / unresolved 时：
   - 不 NPE。
   - 不写入 source steering null key。

# 修改文件列表

本 review 阶段修改：

1. `docs/plan/rss-client-user-v2ray-rules-review-plan.md`

本 review 阶段不修改业务代码。

若后续仅补测试，预计可能修改：

1. `rxlib/src/test/java/org/rx/util/rss/RssClientWeightedRoutingTest.java`
2. `rxlib/src/test/java/org/rx/util/rss/RssClientUserRouteTest.java`
3. 可能新增一个专门覆盖 `RssRuntime` route handler 的测试类。

# 风险点

1. 运行时 handler 层覆盖仍不足，matcher 层通过不等于 SS TCP/UDP handler 全链路一定通过。
2. 不兼容旧 `RouteConf` 是有意保留的破坏性风险，后续配置升级需人工确认。
3. DNS 不用户化是有意保留的语义边界，用户级规则不会影响 DNS 阶段。
4. 默认路由默认启用是有意保留的行为变化风险。
5. 若新增测试依赖真实 geodat 下载，会导致 CI 不稳定；测试应继续使用现有 `V2RayGeoDataTestUtil` 构造内存 geodat。

# 验证方案

本 review 阶段：

- 只提交 review 计划文档。
- 不触发 GitHub Actions。
- 不声明 CI 通过。

后续如果进入代码或测试补充阶段，建议验证：

```bash
mvn -pl rxlib -am -DskipTests compile
```

```bash
mvn -pl rxlib -am -DskipTests=false -Dtest=UserRuleMatcherTest,RssClientUserRouteTest,RssClientWeightedRoutingTest,RssTest test
```

GitHub Actions：

- 代码 commit 后触发 `jdk8-unit-tests.yml`。
- `test_classes` 建议传：`UserRuleMatcherTest,RssClientUserRouteTest,RssClientWeightedRoutingTest,RssTest`。
- 查询 workflow run 时必须按当前分支过滤。
- 只有 `conclusion=success` 才认为 CI 通过。
- 如果 CI 失败，按编译失败、测试失败、格式失败、依赖下载失败、JDK 版本不兼容、环境问题分类处理。
