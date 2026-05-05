# 配置项清理 review 计划：SocksConfig / SocketConfig / RSSConf

# 背景

用户要求检查以下配置类中哪些配置项可以完全安全删除：

- `rxlib/src/main/java/org/rx/net/socks/SocksConfig.java`
- `rxlib/src/main/java/org/rx/net/SocketConfig.java`
- `rxlib/src/main/java/org/rx/util/rss/RSSConf.java`

本轮基于 `master` 分支当前代码做 review。由于这些类均属于 public 配置入口，且多数通过 Lombok 或 public field 暴露给外部配置文件/调用方，删除字段可能破坏源码兼容或配置反序列化兼容。本计划只记录 review 结论和后续清理方案，不修改业务代码。

# 任务类型判断

本次任务归类为 **Review / 清理评估需求**。

原因：

- 用户要求“看下哪些配置项完全可以安全删除”，属于已有配置 API 的使用链路和兼容性 review。
- 当前没有明确要求直接执行删除。
- 按流程，必须先提交计划文档，后续等用户明确“按计划执行”后再进入代码阶段。

# 当前上下文

## 已 review 文件

- `rxlib/src/main/java/org/rx/net/socks/SocksConfig.java`
- `rxlib/src/main/java/org/rx/net/SocketConfig.java`
- `rxlib/src/main/java/org/rx/util/rss/RSSConf.java`
- 关联调用链：
  - `rxlib/src/main/java/org/rx/net/Sockets.java`
  - `rxlib/src/main/java/org/rx/net/socks/SocksProxyServer.java`
  - `rxlib/src/main/java/org/rx/net/socks/Socks5CommandRequestHandler.java`
  - `rxlib/src/main/java/org/rx/net/socks/SocksUdpRelayHandler.java`
  - `rxlib/src/main/java/org/rx/net/socks/Socks5UpstreamPoolManager.java`
  - `rxlib/src/main/java/org/rx/net/socks/Socks5WarmupHandler.java`
  - `rxlib/src/main/java/org/rx/net/socks/Udp2rawServerEntryManager.java`
  - `rxlib/src/main/java/org/rx/net/socks/upstream/SocksUdpUpstream.java`
  - `rxlib/src/main/java/org/rx/util/rss/RssClient.java`
  - `rxlib/src/main/java/org/rx/util/rss/RssRuntime.java`
  - `rxlib/src/main/java/org/rx/util/rss/RssAuthenticator.java`
  - `rxlib/src/main/java/org/rx/util/rss/RssUserTrafficStore.java`

## 关键观察

### SocksConfig

- `kcptunClient` 是目前最明确的遗留字段：字段存在并通过 Lombok 暴露 getter/setter，但当前 SOCKS/UDP/udp2raw/upstream 运行链路未见读取。
- `udpRedundantTrackClientPeer` 当前是有效配置，控制 SOCKS5 UDP 回包方向是否登记 client UDP peer，不能删除。
- `udp2rawListenAddress` 是有效配置，用于 udp2raw server fixed entry 绑定地址，不能删除。
- `udp2rawClient`、`enableUdp2raw`、`udp2raw*`、`udpRelayControl*`、`udpLease*`、`tcpWarmPool*`、`udpRedundant*`、`udpCompress*`、`udpPortHopping*` 均有运行链路或派生配置使用，不应删除。

### SocketConfig

- `debug` 会被 `Sockets.SocketChannelInitializer` 用于安装 Netty logging handler，不能删除。
- `reactorName`、`optimalSettings`、`connectTimeoutMillis`、`transportFlags`、`reusePortBindCount`、`udpWriteLimitBytes`、`tcpCompressionLevel`、`cipher`、`cipherKey` 均在 bootstrap、pipeline 或懒加载配置中使用，不应删除。
- `udpWritePerSourceLimitBytes` 在 `SocketConfig` 中存在字段和 setter；当前已 review 的主要 UDP 写路径里尚未确认有实际读取。它是候选废弃项，但需要全仓精确搜索确认后才能处理。

### RSSConf

`RSSConf` 是 RSS 子系统的主配置，不是空壳类。当前已确认：

- `RssClient.normalizeAndValidateRssConfig` 会校验或规范化：
  - `route`
  - `nameserver`
  - `trafficRetentionDays`
  - `memoryRetentionHours`
  - `connectTimeoutSeconds`
  - `tcpTimeoutSeconds`
  - `udpTimeoutSeconds`
  - `rpcMinSize`
  - `rpcMaxSize`
  - `rpcPort`
  - `rpcRequestTimeoutMillis`
  - `rpcAutoWhiteListSeconds`
  - `shadowDnsPort`
  - `dnsTtlMinutes`
  - `socksPwd`
  - `shadowUsers`
  - `socksServers`
  - `rrpPort`
  - `ddnsApiKey`
  - `ddnsDomains`
- `RssRuntime` 会使用：
  - `socksBindPort`
  - `shadowDnsPort`
  - `socksPwd`
  - `memoryRetentionHours`
  - `route.dstGeoSiteDirectRules`
  - `socksServers`
  - `shadowUsers`
  - `nameserver`
- `RSSConf.SocksServer` 的 `tcpClient` 被 `RssRuntime` 用于 TCP_CLIENT 路由；不能删除。
- `RSSConf.SocksServer` 的 `udp2raw` 被用于区分 UDP2RAW 路由；不能删除。
- `RSSConf.SocksServer` 的 `udp2rawClient` 字段存在于自定义反序列化逻辑中，语义是固定 udp2raw 目标预留字段；需要继续精确确认是否仍被运行时读取。如果仅解析不使用，则属于候选废弃项，而不是可直接删除项。
- `RSSConf` 的 DDNS 相关字段 `ddnsJobSeconds`、`ddnsDomains`、`ddnsApiKey`、`ddnsApiProxy` 需要结合 `configureDdnsSchedule(...)` 继续核对。当前已确认 `normalizeAndValidateRssConfig` 对 DDNS 条件做校验，不能仅凭字段少见就直接删除。

# 目标

1. 明确哪些配置项可以进入废弃/删除候选。
2. 区分“库内未使用”和“public API 可安全删除”两类判断。
3. 给后续清理提供最小改动路径。
4. 避免误删仍在 RSS / SOCKS / UDP relay / udp2raw / RPC 路径中使用的配置项。

# 非目标

1. 本计划不删除字段。
2. 本计划不修改业务代码。
3. 本计划不修改测试代码。
4. 不修改配置格式兼容策略。
5. 不升级依赖。
6. 不发布 release。

# 设计方案

## 删除安全等级

### A. 当前可判定为“不能删”

`SocketConfig`：

- `debug`
- `reactorName`
- `optimalSettings`
- `connectTimeoutMillis`
- `transportFlags`
- `reusePortBindCount`
- `udpWriteLimitBytes`
- `tcpCompressionLevel`
- `cipher`
- `cipherKey`

`SocksConfig`：

- `udpRedundantTrackClientPeer`
- `udp2rawListenAddress`
- `udp2rawClient`
- `enableUdp2raw`
- `udp2raw*`
- `udpRelayControl*`
- `udpLease*`
- `tcpWarmPool*`
- `udpRedundant*`
- `udpCompress*`
- `udpPortHopping*`
- 认证、监听、超时、路由、upstream、DNS、traffic 相关字段

`RSSConf`：

- `logFlags`
- `shadowUsers`
- `socksServers`
- `socksBindPort`
- `socksPwd`
- `connectTimeoutSeconds`
- `tcpTimeoutSeconds`
- `udpTimeoutSeconds`
- `rpcMinSize`
- `rpcMaxSize`
- `rpcPort`
- `rpcRequestTimeoutMillis`
- `upstreamHealthCheckSeconds`
- `upstreamHealthFailureThreshold`
- `upstreamFailOpenWhenAllDown`
- `rpcAutoWhiteListSeconds`
- `udpLeasePoolEnabled`
- `udpLeasePoolMinSize`
- `udpLeasePoolMaxSize`
- `udpLeasePoolMaxIdleMillis`
- `udpLeaseRpcBreakerThreshold`
- `udpLeaseRpcBreakerOpenSeconds`
- `shadowDnsPort`
- `dnsTtlMinutes`
- `nameserver`
- `trafficRetentionDays`
- `memoryRetentionHours`
- `rrpToken`
- `rrpPort`
- `route`
- `ddns*` 字段在 DDNS 调度确认前暂列不能删
- `RSSConf.SocksServer.id/weight/endpoint/udp2raw/tcpClient`
- `RSSConf.RouteConf.enable/dstGeoSiteDirectRules/srcIpProxyRules/srcSteeringTTL`

### B. 候选废弃项，不建议直接删

- `SocksConfig.kcptunClient`
  - 当前主链路未见使用。
  - 但 Lombok 生成 public getter/setter，直接删除会破坏外部编译和旧配置兼容。
  - 建议第一步加 `@Deprecated`。

- `SocketConfig.udpWritePerSourceLimitBytes`
  - 当前 review 未确认主要 UDP 写路径使用它。
  - 需要全仓精确搜索后确认。
  - 如果确实没有运行链路，建议先 `@Deprecated`，不要直接删。

- `RSSConf.SocksServer.udp2rawClient`
  - 当前 `RSSConf` 自定义反序列化会读取并赋值。
  - 需要继续确认 `RssRuntime` / `RssClient` 是否实际使用该字段。
  - 如果只保留了解析但运行时不读，可标记为废弃字段。
  - 因为它是 RSS 配置文件字段，直接删除可能影响旧配置文件。

### C. 当前没有把握直接删除的字段

- `RSSConf.ddnsJobSeconds`
- `RSSConf.ddnsDomains`
- `RSSConf.ddnsApiKey`
- `RSSConf.ddnsApiProxy`

原因：`normalizeAndValidateRssConfig` 已对 DDNS 条件做校验，`RssRuntime` 中存在 `configureDdnsSchedule(...)` 调用，需要先精确看该方法内部再判断。

## 后续执行方案

1. 先做全仓精确搜索：
   - `kcptunClient`
   - `udpWritePerSourceLimitBytes`
   - `udp2rawClient`
   - `ddnsJobSeconds`
   - `ddnsApiProxy`
2. 对确认未使用但属于 public config 的字段，第一阶段只加 `@Deprecated` 和注释。
3. 增加配置兼容测试，确保旧配置仍可加载。
4. 下一大版本或明确允许 breaking change 时再删除字段。

# 修改文件列表

本计划阶段实际修改：

- `docs/plan/review-config-cleanup-socks-socket-rssconf-20260505.md`

后续如执行代码阶段，预计可能修改：

- `rxlib/src/main/java/org/rx/net/socks/SocksConfig.java`
- `rxlib/src/main/java/org/rx/net/SocketConfig.java`
- `rxlib/src/main/java/org/rx/util/rss/RSSConf.java`
- 可能新增/修改测试：
  - `rxlib/src/test/java/org/rx/net/socks/SocksConfigTest.java`
  - `rxlib/src/test/java/org/rx/net/SocketConfigTest.java`
  - `rxlib/src/test/java/org/rx/util/rss/RSSConfTest.java`

# 风险点

## 兼容性风险

- 删除 Lombok 字段等价于删除 getter/setter，可能导致外部源码编译失败。
- `RSSConf` 字段是 public field，旧 JSON/YAML 配置可能包含这些字段；直接删除可能导致加载失败或行为静默变化。
- `RSSConf.SocksServer` 还有自定义 FastJSON2 reader，删除字段需要同步更新 reader。

## 功能风险

- `RSSConf` 同时控制 socks、shadow、dns、nameserver、rrp、ddns、route、traffic store、lease pool 等功能，误删会影响运行时。
- `SocksConfig` UDP/udp2raw/redundant/compress/port hopping 配置互相关联，不能只看字段名判断。
- `SocketConfig` 是低层网络栈配置，误删可能改变 bootstrap 或 pipeline 行为。

## 测试风险

- 单测通过不代表外部配置兼容。
- 需要增加旧配置字段兼容加载测试。

# 验证方案

如果后续进入代码阶段，建议先只加 `@Deprecated`，不删除字段，然后执行：

```bash
mvn -pl rxlib -Dgpg.skip=true -Dmaven.test.skip=false -DskipTests=false -Dtest=SocksProxyServerTest,Socks5ClientTest,SocksUdpRelayHandlerTest,SocketsTest test
```

如果涉及 RSSConf：

```bash
mvn -pl rxlib -Dgpg.skip=true -Dmaven.test.skip=false -DskipTests=false -Dtest=Rss*Test test
```

如果真正删除字段，必须全量编译测试：

```bash
mvn -B -U -Dgpg.skip=true -Dmaven.test.skip=false -DskipTests=false clean test
```

代码 commit 后触发 `.github/workflows/jdk8-unit-tests.yml`，只有 `conclusion=success` 才能认为 CI 通过。
