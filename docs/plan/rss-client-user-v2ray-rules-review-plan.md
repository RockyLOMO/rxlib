# 背景

用户要求在当前已更新分支 `agent/rss-client-user-v2ray-rules-plan` 上再次 review。当前分支已经不只是计划文档，已有用户级 route/v2ray 规则相关实现、重命名和测试提交。本次按 Review 类任务处理：只 review 现有实现，提交 review 计划文档，不修改业务代码。

本次 review 到的当前 HEAD 为 `a4817942ce954fec87dff433901d7f9c89ee92b0`。

# 任务类型判断

本次归类为 Review / 修复 / 优化需求。

原因：分支上已经出现 `UserRule`、`UserRuleMatcher`、`RouteAction`、`RssClient`、`RssRuntime` 等实现变更，需要检查调用链、边界条件、兼容性风险、性能风险和测试覆盖，并给出后续修复计划。按照流程，本阶段仅提交 review 计划文档，等待用户明确要求后再修改业务代码。

# 当前上下文

## 已 review 的文件

- `rxlib/src/main/java/org/rx/util/rss/RssClientConf.java`
- `rxlib/src/main/java/org/rx/util/rss/ShadowUser.java`
- `rxlib/src/main/java/org/rx/util/rss/UserRule.java`
- `rxlib/src/main/java/org/rx/util/rss/UserRuleMatcher.java`
- `rxlib/src/main/java/org/rx/util/rss/RouteAction.java`
- `rxlib/src/main/java/org/rx/util/rss/RssClient.java`
- `rxlib/src/main/java/org/rx/util/rss/RssRuntime.java`
- `rxlib/src/test/java/org/rx/net/support/UserRuleMatcherTest.java`
- `rxlib/src/test/java/org/rx/util/rss/RssClientUserRouteTest.java`
- `rxlib/src/test/java/org/rx/util/rss/RssClientWeightedRoutingTest.java`
- `rxlib/src/test/java/org/rx/util/rss/RssTest.java`
- `docs/plan/rss-client-user-v2ray-rules-plan.md`

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
   - `BLOCK` 抛错拒绝，`DIRECT` 直连，`PROXY` 选上游。

3. Shadow/SS 专属链路：
   - `RssRuntime.buildShadowServers(...)`
   - `createShadowServer(...)`
   - `ShadowsocksServer.onTcpRoute/onUdpRoute`
   - `matchRoute(ref.routeMatcher, dstHost, dstPort, sourceEp)`
   - `routePlan.nextSupport(sourceIp, dstEp, srcSteeringTTL)`。

4. DNS 链路：
   - `DnsRemoteServer.ResolveInterceptor.resolveHost(srcIp, host)`
   - 目前调用 `matchRoute(null, host)`，只走全局 `defaultRouteMatcher`，不走用户级 `ShadowUser.route`。

5. 热更新链路：
   - `RssRuntime.buildShadowServers(...)`
   - 复用旧 `ShadowServerRef` 时调用 `oldRef.updateRouteMatcher(usr)`
   - `updateRouteMatcher` 会刷新 `routeMatcher` 与 `srcSteeringTTL`。

## 当前实现意图

当前实现已经从最初计划中的 `V2Ray*` 命名收敛为通用 route 命名：

- `V2RayRouteAction` -> `RouteAction`
- `V2RayUserRule` -> `UserRule`
- `V2RayUserRuleMatcher` -> `UserRuleMatcher`

配置模型变为：

```java
public class UserRule {
    public Boolean enabled;
    public int srcSteeringTTL;
    public List<String> rules;
}
```

规则使用有序文本格式：

```text
srcIp 192.168.31.7 direct
srcPort 40000-50000 block
dstIp 8.8.8.8 proxy
dstPort 443 direct
geosite:cn direct
geoip:cn direct
default proxy
```

## 已发现的问题或风险

1. `geoip:` / `dstIp` 对域名目标不会命中。
   - 当前 `RssClient` / `RssRuntime` 调用 `match(..., host, dstPort, srcEp)` 时没有传入解析后的目标 IP bytes。
   - `UserRuleMatcher` 只有在 host 是 IP literal 或显式传入 ipBytes 时才有目标 IP bytes。
   - 因此目标为域名时，`geosite:` / domain 规则可生效，但 `geoip:` / `dstIp` 规则不会基于 DNS 解析结果生效。

2. DNS 路由当前是全局策略，不是用户级策略。
   - DNS `resolveHost(srcIp, host)` 没有用户上下文，目前只调用 `matchRoute(null, host)`。
   - 如果某个用户希望 `route` 中 `block/proxy/direct` 对 DNS 解析也生效，当前实现无法区分用户。
   - 如果设计目标是“DNS 只走全局 defaultRouteRules，真正用户级路由在 CONNECT/UDP 目标阶段生效”，需要在文档和日志中明确。

3. `RssClientConf.RouteConf` 被删除存在兼容风险。
   - 旧字段 `route.enable`、`route.dstGeoSiteDirectRules`、`route.srcIpProxyRules`、`route.srcSteeringTTL` 已从配置类删除。
   - 老配置如果仍包含 `route`，可能被忽略，行为从旧 route 策略切换到默认 `defaultRouteRules`。
   - 需要明确是否保留向后兼容迁移逻辑，或至少补充迁移说明和测试。

4. 默认路由策略变为默认启用。
   - `defaultRouteRules` 为空时会使用 `geosite:cn direct`、`geoip:cn direct`、`default proxy`。
   - 这符合当前 plan 更新后的目标，但相对旧 `route.enable=false` 的语义可能是行为变化，需要确认是预期变更。

5. Source steering 的源地址空值需要防御。
   - `RssRuntime` 在 SS TCP/UDP route 中使用 `e.getSource().getAddress()` 参与上游亲和。
   - 如果 source endpoint 为空或未解析，可能导致 NPE 或不符合预期的 key 行为。
   - 建议在调用处显式判空，无法拿到源 IP 时降级为普通 weighted next。

6. 测试覆盖仍偏 matcher 层和静态方法层。
   - `UserRuleMatcherTest` 覆盖了规则顺序、端点 IP/端口、default、disabled 等。
   - `RssClientUserRouteTest` 覆盖了 normalize 和 `matchUserRoute`。
   - 还缺少 `RssRuntime.createShadowServer` route handler 层的覆盖，尤其是 SS TCP/UDP 的 `srcIp/srcPort/dstIp/dstPort`、`BLOCK`、`DIRECT`、热更新复用旧 server 刷新 matcher 和 `srcSteeringTTL` 的路径。

# 目标

1. 明确当前实现是否符合“用户级 route 规则”的语义边界。
2. 确认 DNS 阶段是否必须用户感知，还是只作为全局默认策略。
3. 检查并修复可能的空源地址、旧配置迁移和默认行为变化风险。
4. 补充缺失测试，覆盖运行时 route handler，而不只覆盖 matcher。
5. 保持 Java 8 兼容，不引入重型依赖，不做无关重构。

# 非目标

1. 不重写 `V2RayGeoManager`、geodat 下载或索引实现。
2. 不升级 JDK、Netty、Maven 插件或大版本依赖。
3. 不引入新的配置中心或 UI。
4. 不自动发布 release。
5. 不在 review 阶段修改业务代码。
6. 不改变 secrets、token、证书、私钥。

# 设计方案

## 1. 明确 GeoIP / dstIp 语义

建议第一版定义为：

- `domain` / `geosite:` 规则用于域名目标。
- `ip:` / `cidr:` / `dstIp` / `geoip:` 规则用于目标已经是 IP literal，或调用方能够提供目标 IP bytes 的场景。
- 不在 route matcher 内部主动 DNS 解析，避免在 Netty route 热路径引入阻塞或额外 DNS 污染。

如果需要让域名目标也走 GeoIP，则后续应在已有 DNS 解析结果可用的位置传入 `ipBytes`，而不是在 `UserRuleMatcher` 内部解析。

## 2. 明确 DNS 策略边界

建议保留当前实现的全局 DNS 策略，但补充注释和测试：

- DNS `ResolveInterceptor` 没有用户上下文，因此只应用 `defaultRouteRules`。
- 用户级 `ShadowUser.route` 在 TCP/UDP 目标 route 阶段生效。
- 若未来需要用户级 DNS，需要从认证会话或 source endpoint 建立用户上下文映射，再单独设计。

## 3. 兼容旧 RouteConf

两种可选策略：

- 保守兼容：保留 deprecated `RssClientConf.RouteConf route` 字段，反序列化后迁移到 `defaultRouteRules` / `UserRule.srcSteeringTTL`，并在注释中标记废弃。
- 明确破坏性变更：不保留旧字段，但在文档中写出迁移方式，并增加测试确认旧字段不会误导。

建议优先保守兼容，避免生产配置升级后静默改变路由行为。

## 4. Source steering 判空

建议新增小工具方法：

```java
static InetAddress sourceAddress(InetSocketAddress source) {
    return source == null ? null : source.getAddress();
}
```

并在 `nextSupport` / `nextUpstream` 中：

- `steeringTtl <= 0`：普通 weighted next。
- `srcHost == null`：普通 weighted next。
- 端口属于常见无状态端口：普通 weighted next。
- 其它情况才使用 source steering。

## 5. 测试补齐

建议增加或扩展以下测试：

1. `UserRuleMatcherTest`
   - 域名目标 + `geoip:` 不命中，IP literal + `geoip:` 命中。
   - `dstIp` 与 `ip:`/`cidr:` 行为一致。
   - `srcIp` 在 `srcEp` 地址为空时不命中且不异常。

2. `RssClientUserRouteTest`
   - `defaultRouteRules` 为空时默认规则生效。
   - 用户 route disabled 时走 defaultRouteRules。
   - 非 ShadowUser 只走 defaultRouteRules。

3. `RssRuntime` 相关测试
   - 复用旧 `ShadowServerRef` 时 `updateRouteMatcher` 刷新 matcher。
   - `srcSteeringTTL` 随用户 route 更新。
   - SS TCP/UDP route handler 传递 `dstPort` 和 `sourceEp` 后，`srcPort/dstPort/srcIp` 规则能命中。
   - source address 为空时不会 NPE。

# 修改文件列表

本 review 阶段新增：

1. `docs/plan/rss-client-user-v2ray-rules-review-plan.md`

若后续进入修复实现阶段，预计修改或新增：

1. `rxlib/src/main/java/org/rx/util/rss/RssClient.java`
   - 明确 DNS route 语义，必要时调整注释/日志。
   - 对 source address / source endpoint 做判空保护。

2. `rxlib/src/main/java/org/rx/util/rss/RssRuntime.java`
   - 对 SS TCP/UDP route 中 source address 做判空。
   - 必要时调整 `ShadowRoutePlan.nextSupport(...)` 退化逻辑。

3. `rxlib/src/main/java/org/rx/util/rss/RssClientConf.java`
   - 根据决策保留 deprecated `RouteConf` 并迁移，或补充迁移说明。

4. `rxlib/src/main/java/org/rx/util/rss/UserRuleMatcher.java`
   - 根据最终语义补充注释或小范围行为修正。

5. `rxlib/src/test/java/org/rx/net/support/UserRuleMatcherTest.java`
   - 增加域名 + GeoIP 不命中、IP literal + GeoIP 命中、srcEp 空地址等测试。

6. `rxlib/src/test/java/org/rx/util/rss/RssClientUserRouteTest.java`
   - 增加默认规则和旧配置迁移测试。

7. `rxlib/src/test/java/org/rx/util/rss/RssClientWeightedRoutingTest.java` / `RssTest.java`
   - 增加 source steering 判空和退化测试。

# 风险点

1. 保持旧配置兼容可能需要同时支持新旧字段，增加 normalize 复杂度。
2. 若强行让域名目标走 GeoIP，可能引入阻塞 DNS、重复解析或 DNS 污染风险。
3. DNS 阶段如果引入用户上下文，会影响现有 DNS server 架构和缓存模型，范围较大。
4. `defaultRouteRules` 默认启用可能改变旧行为，必须通过迁移说明或兼容字段降低风险。
5. source steering 修复必须避免破坏现有 weighted routing 与 fail-open/fail-close 逻辑。
6. 新增测试如果依赖 geodat 下载，会导致 CI 不稳定；应使用现有 `V2RayGeoDataTestUtil` 构造内存 geodat。

# 验证方案

1. 本 review 阶段：
   - 仅提交计划文档，不触发 CI，不声明 CI 通过。

2. 后续修复实现阶段本地/CI 编译：

```bash
mvn -pl rxlib -am -DskipTests compile
```

3. 后续单元测试建议：

```bash
mvn -pl rxlib -am -DskipTests=false -Dtest=UserRuleMatcherTest,RssClientUserRouteTest,RssClientWeightedRoutingTest,RssTest test
```

4. GitHub Actions：

- 代码 commit 后触发 `jdk8-unit-tests.yml`。
- `test_classes` 建议传：`UserRuleMatcherTest,RssClientUserRouteTest,RssClientWeightedRoutingTest,RssTest`。
- 查询 workflow run 时必须按当前分支过滤。
- 只有 `conclusion=success` 才认为 CI 通过。
- 若 CI 失败，按编译失败、测试失败、格式失败、依赖下载失败、JDK 版本不兼容、环境问题分类处理。
