# RSS 路由与 Geo 数据引擎统一架构及实现报告

## 一、 概述与背景

为了在基于 JDK 1.8 的底层高性能网络协议库 `rxlib` 中集成精细化的路由控制能力，本项目实现了对 V2Ray `geoip.dat` 与 `geosite.dat` 的高性能解析，并以此为基础构建了 RSS 客户端的用户级路由规则系统。

### 核心目标
*   **极致性能**：热点路径零对象分配（Zero Allocation）、无锁竞争、非阻塞 DNS。
*   **用户级隔离**：支持在 `ShadowUser` 维度配置独立的路由规则表。
*   **有序决策**：支持 `PROXY`、`DIRECT`、`BLOCK` 动作，规则按序匹配，首位命中。
*   **源地址粘滞**：支持基于源 IP 的上游亲和性（Source Steering）。

---

## 二、 底层引擎：V2Ray GeoDat 解析架构

### 2.1 轻量级流式解析 (Protobuf Reader)
*   **零依赖设计**：不引入 `protobuf-java`，手写无状态 `V2RayGeoDataReader`。
*   **流式处理**：通过指针偏移顺序迭代二进制数据，支持未知字段平滑跳过。
*   **内存策略**：默认全量载入内存以换取极速检索，通过 Immutable Snapshot 实现无缝热替换。

### 2.2 GeoIP 检索与 CIDR 二分查找
*   **数据结构**：IPv4 转为 32 位无符号整数区间数组；IPv6 拆解为 `high/low long` 数组。
*   **检索算法**：$O(\log N)$ 的无符号二分查找。
*   **补集处理**：支持 `inverse_match` 语义，在构建期预计算补集区间。

### 2.3 GeoSite 匹配逻辑
*   **类型映射**：
    *   `RootDomain` $\rightarrow$ `UltraDomainTrieMatcher` (双数组 Trie 树)。
    *   `Full` $\rightarrow$ 哈希精确匹配。
    *   `Plain` $\rightarrow$ AC 自动机多模式匹配。
    *   `Regex` $\rightarrow$ 预编译正则复用。
*   **属性过滤**：支持 `@ads` 等属性标识的解析与过滤，利用配置期缓存 (`Caffeine`) 加速。

---

## 三、 业务逻辑：用户级路由规则 (UserRule)

### 3.1 配置模型
*   **全局默认**：在 `RssClientConf` 中定义 `defaultRouteRules`。
*   **用户级规则**：每个 `ShadowUser` 包含可选的 `UserRule route` 字段。
*   **规则行格式**：`<目标规则> <动作>` (例如 `geosite:cn direct`)。

### 3.2 路由选择算法
1.  **用户匹配**：根据认证上下文获取当前 `ShadowUser`。
2.  **规则执行**：
    *   若用户配置了 `route` 且启用，优先使用用户级 `UserRuleMatcher`。
    *   否则回落至全局 `defaultRouteMatcher`。
3.  **源地址粘滞 (Source Steering)**：
    *   非 443/80 等无状态端口启用。
    *   在 `srcSteeringTTL` 时间内，相同源 IP 固定转发至同一上游。
    *   若源 IP 缺失，自动退化为加权轮询。

### 3.3 关键设计边界
*   **DNS 隔离**：DNS 解析阶段仅应用全局 `defaultRouteRules`，不涉及用户级路由。
*   **不主动解析**：`UserRuleMatcher` 内部匹配 `geoip:` 或 `dstIp` 时不触发 DNS 解析，避免热路径阻塞。
*   **无感热更新**：通过原子替换 `volatile` 引用实现配置热加载。

---

## 四、 验证与质量保障

### 4.1 单元测试矩阵
*   **Matcher 验证**：覆盖 CIDR 边界、正则匹配、属性过滤、反向 IP 匹配等。
*   **RssClient 验证**：模拟多用户、多规则、配置重载、源地址缺失等复杂场景。
*   **性能回归**：验证在极速请求压力下无内存泄漏与 NPE。

### 4.2 验证结论
*   **编译**：JDK 1.8 环境通过。
*   **集成测试**：`UserRuleMatcherTest` 与 `RssClientUserRouteTest` 核心 13 项测试全部通过。
*   **代码状态**：已完成分支合流，代码合并至 `master`。

---

## 五、 运维与风险点
*   **Geodat 依赖**：若 Dat 文件缺失，Geo 相关规则将失效，系统会自动回落至 `default` 兜底规则并记录告警。
*   **配置兼容性**：移除了旧版的 `RouteConf` 字段，现有用户需迁移至新的规则行格式。
*   **资源回收**：`V2RayGeoManager` 作为单例生命周期随进程结束，中间热重载产生的旧快照由 GC 自动回收。
