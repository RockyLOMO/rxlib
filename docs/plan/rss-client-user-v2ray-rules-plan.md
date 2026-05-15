# 背景

用户要求在 `rockylomo/rxlib` 仓库 `master` 分支中，围绕 `RssClientConf` 类上的 `public List<ShadowUser> shadowUsers;` 增加用户级 `route` 路由规则，规则实现参考 `V2RayGeoManager`，并给出 `RssClient` 的接入计划方案。

当前已进入代码实现阶段，用户级规则按有序配置行落地，老分组字段不再兼容。

# 任务类型判断

本次归类为新需求。

原因：用户明确提出新增用户级 route 规则能力，涉及配置模型扩展、规则编译、用户维度路由决策和 `RssClient` 接入链路调整。

# 当前上下文

仓库是 Maven 多模块项目，根 `pom.xml` 配置 Java 版本为 1.8；当前主模块包括 `rxlib` 与 `rxlib-x`。

已检查文件和模块：

- `AGENTS.md`
- `pom.xml`
- `rxlib/src/main/java/org/rx/net/support/V2RayGeoManager.java`
- `rxlib/src/main/java/org/rx/net/support/GeoSiteMatcher.java`
- `rxlib/src/main/java/org/rx/net/support/V2RayGeoIpMatcher.java`
- `rxlib/src/main/java/org/rx/net/socks/SocksConfig.java`
- `rxlib/src/main/java/org/rx/net/socks/SocksUser.java`
- `rxlib/src/main/java/org/rx/net/socks/UdpRedundantDestinationRule.java`
- `docs/plan/*` 现有计划文档风格

`V2RayGeoManager` 已提供 geodat 加载、热更新和匹配能力，可复用以下 API：

- `siteMatcher(code)` / `compileGeoSiteMatcher(code)` / `tryCompileGeoSiteMatcher(code)`
- `matchGeoSite(code, domain)`
- `compileGeoIpMatcher(code)` / `tryCompileGeoIpMatcher(code)`
- `matchGeoIp(code, ip)`
- `matchSiteDirect(domain)`
- `setDirectGeoSiteCode(...)` / `setDirectSiteExtraRules(...)`

通过当前 GitHub Contents / Trees API 对公开仓库 master 的顶层模块、`rxlib`、`rxlib-x`、`agent`、`daemon`、`deploy`、`docs` 做了定向扫描，未在当前可见文件树中直接定位到 `RssClientConf`、`RssClient`、`ShadowUser` 的源文件路径。因此本计划按用户提供的类名和字段作为业务上下文设计，代码实现阶段需要先全文检索或由用户补充路径确认具体文件；若当前 master 确实缺失，则先定位等价配置类或补齐模型后再最小接入。

# 目标

1. 在 `RssClientConf.shadowUsers` 的每个 `ShadowUser` 上支持用户级 `route` 规则配置。
2. 用户级规则可用有序规则行表达 GeoSite、GeoIP、domain、IP、CIDR、`default` 目标。
3. 规则实现复用 `V2RayGeoManager`，不重复解析 `geosite.dat` / `geoip.dat`。
4. `RssClient` 在处理请求目标时，能按当前认证或匹配到的 `ShadowUser` 读取该用户规则，决定走代理、直连或阻断。
5. 规则编译在配置加载或用户对象构建阶段完成，热路径只做 matcher 判断。
6. 保持 Java 8 兼容，不引入重型依赖，不改 secrets / token / 证书 / 私钥。

# 非目标

1. 不重写 `V2RayGeoManager` 的 geodat 下载、热更新和索引实现。
2. 不升级 JDK、Netty、Maven 插件或大版本依赖。
3. 不继续保留 `RssClient` 旧的 `GeoManager` 全局 direct 判断链路。
4. 不做 UI、配置中心或发布流程改造。
5. 老分组字段不做兼容；配置统一迁移到 `rules` 有序列表。
6. 不自动发布 release。

# 设计方案

## 配置模型

在 `shadowUsers` 同级增加全局默认规则：

```java
public List<ShadowUser> shadowUsers;
public List<String> defaultRouteRules;
```

在 `ShadowUser` 中新增可选字段：

```java
public V2RayUserRule route;
```

新增配置类命名为 `V2RayUserRule`：

```java
public class V2RayUserRule {
    public Boolean enabled;
    public List<String> rules;
}
```

`rules` 每行格式为：

```text
<目标规则> <动作>
```

示例：

```text
192.168.31.1 direct
geoip:cn proxy
geosite:cn direct
example.com direct
10.0.0.0/8 block
default proxy
```

`V2RayRouteAction` 包含 `PROXY`、`DIRECT`、`BLOCK`。`default` 是规则目标关键字，表示前面所有规则都未命中时的兜底动作。

`defaultRouteRules` 默认值：

```text
geosite:cn direct
geoip:cn direct
default proxy
```

## 运行期规则对象

新增 `V2RayUserRuleMatcher`，负责把配置转换为热路径可用 matcher：

```java
public final class V2RayUserRuleMatcher {
    V2RayRouteAction match(String host, byte[] ipBytes);
}
```

职责：

- 在配置加载或 `RssClient` 初始化阶段编译 `geoSite code` 为 `GeoSiteMatcher`。
- 编译 `geoIp code` 为 `V2RayGeoIpMatcher.CodeMatcher`。
- 编译或缓存自定义 domain 规则。
- 热路径按配置行顺序判断，先命中先生效。
- 用户 `route` 为空或 `enabled=false` 时不创建用户 matcher，请求回落到全局 `defaultRouteRules` matcher。

## 规则优先级

规则优先级就是 `rules` 的先后顺序，越靠前优先级越高。

例如：

```text
192.168.31.1 direct
geoip:cn proxy
```

当目标为 `192.168.31.1` 且同时满足后续 `geoip:cn` 时，按第一行走 `direct`。

`ShadowUser.route` 为空或未启用时使用全局 `defaultRouteRules`。默认全局规则为 `geosite:cn direct`、`geoip:cn direct`、`default proxy`，即 cn 直连，其他代理。

## `V2RayGeoManager` 复用方式

优先使用：

```java
V2RayGeoManager.INSTANCE.tryCompileGeoSiteMatcher(code, attrFilter);
V2RayGeoManager.INSTANCE.tryCompileGeoIpMatcher(code);
```

原因：避免 `RssClient` 初始化或请求热路径被 geodat 下载、解析阻塞。如果 geodat 已加载，则直接复用当前快照；如果未加载，相关 geo 规则本次不命中，由后续有序规则或 `default` 兜底。

如业务要求启动时必须强校验规则，可在配置加载阶段改用阻塞 `compile*` 方法，并将失败作为配置错误抛出，但第一版建议采用非阻塞 try 编译加日志告警。

## `RssClient` 接入点

实现阶段先定位 `RssClient` 的以下链路：配置加载、用户识别、目标解析、路由决策、配置 reload 生命周期。

计划接入步骤：

1. 配置加载后遍历 `RssClientConf.shadowUsers`。
2. 对每个 `ShadowUser.route` 构建 `V2RayUserRuleMatcher`。
3. 将 matcher 挂到 `ShadowUser` transient 字段，或放入 `Map<userKey, V2RayUserRuleMatcher>`，避免影响序列化输出。
4. `RssClient` 每次处理目标时，根据当前用户取 matcher。
5. 调用 `matcher.match(host, ipBytes)`。
6. `BLOCK` 复用现有错误关闭或拒绝策略；`DIRECT` 走现有直连路径；`PROXY` 走现有代理 / upstream 路径；未命中则由 `default` 规则兜底。
7. 配置热更新时重建 matcher，以新对象替换旧对象；不手动 close `V2RayGeoManager.INSTANCE`。

## 异常处理

- 配置为空或 `enabled=false`：视为未启用。
- geosite / geoip code 不存在：记录 warn，相关 matcher 为空，不影响其它规则。
- host 为空但 IP 存在：只执行 GeoIP 规则。
- 目标为 domain 且尚未解析 IP：先执行 domain / GeoSite 规则，避免额外 DNS 解析。
- 规则解析异常：记录用户标识和规则 code，但不打印敏感密码。
- 阻断动作：复用现有协议错误处理，不新增协议错误格式。

## 资源释放策略

- 不关闭 `V2RayGeoManager.INSTANCE`，它是全局 singleton。
- `GeoSiteMatcher` / `CodeMatcher` 若实现 `Closeable`，只在确认不是共享快照且被替换时关闭；否则交由 GC。
- 配置热更新通过整体替换 matcher 保证并发可见性，避免请求看到半初始化对象。
- 禁止在 Netty EventLoop 或请求热路径执行阻塞 geodat 下载。

# 修改文件列表

本计划阶段新增：

1. `docs/plan/rss-client-user-v2ray-rules-plan.md`

后续代码实现阶段预计修改或新增：

1. `RssClientConf` 所在文件：保留 `public List<ShadowUser> shadowUsers;`，增加用户级规则初始化入口。
2. `ShadowUser` 所在文件：增加可选 `route` 字段和 transient matcher。
3. 新增 `V2RayUserRule`。
4. 新增 `V2RayUserRuleMatcher`。
5. `RssClient` 所在文件：在配置加载、用户选择和目标路由决策处接入用户规则。
6. 测试文件：新增或扩展 RssClient / rule matcher 单元测试。

# 风险点

1. `RssClient` / `RssClientConf` / `ShadowUser` 当前路径未在可见文件树中定位，后续实现需先确认源文件位置。
2. 用户级规则优先级若与现有全局规则冲突，可能改变历史路由行为。
3. GeoSite / GeoIP matcher 编译若放入请求热路径，会引入性能风险，必须避免。
4. geodat 文件缺失或下载失败时，geo 规则不命中，需要日志可观测。
5. 新增字段需保持 JSON/YAML 等配置反序列化向后兼容。
6. 配置热更新并发发生时，matcher 替换需保证可见性和不可变性。
7. 阻断行为需复用现有协议错误处理，否则客户端表现可能不一致。
8. Java 8 环境下不能使用 `List.of`、`Map.of`、`var`、`Optional.stream` 等 JDK9+ API。

# 验证方案

1. 编译验证：

```bash
mvn -pl rxlib -am -DskipTests compile
```

如 RssClient 在其它模块，则按实际模块追加对应 `-pl`。

2. 单元测试：

- 用户规则 matcher：空配置、disabled、domain direct/proxy/block、geosite code 编译失败、geoip 命中、block 优先级。
- RssClient 接入：相同目标在不同 ShadowUser 下得到不同动作；未配置用户规则时走默认 cn direct / 其他 proxy；配置热更新后新 matcher 生效。

3. GitHub Actions：

- 代码实现 commit 后触发 `jdk8-unit-tests.yml`。
- `test_classes` 带上相关测试类名，例如 `V2RayUserRuleMatcherTest,RssClientTest`，以实际测试类为准。
- 查询 workflow run 必须按当前 agent 分支过滤。
- 只有 `conclusion=success` 才认为 CI 通过。

4. 人工验证：

- userA：`route.rules=["geosite:cn direct","default proxy"]`
- userB：`route.rules=["geosite:cn proxy","default direct"]`
- 对相同 domain 验证不同用户得到不同路由结果。
- 验证用户未配置 `route` 或 `route.enabled=false` 时，全局 `defaultRouteRules` 默认 cn direct / 其他 proxy 生效。
