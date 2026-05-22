# V2Ray Geodata (geoip/geosite) 高性能加载与匹配演进综合计划

本文档整合了 `v2ray-geodat` 相关的多轮设计及 Review 修复计划（包括 high-performance、hotpath-review、latest-update、post-update、close-crossload、schedule-future 计划），全面梳理了 V2Ray Geodata（geoip.dat/geosite.dat）在 `rxlib` 中的解析机制、热点匹配、生命周期安全与调度管理的演进路径。

---

## 1. 整体背景与目标

- **项目定位**：高性能模式（Netty 底层网络编程）。
- **核心诉求**：支持高性能读取 V2Ray 的 `geosite.dat` 和 `geoip.dat` 规则文件，用于代理路由和分流，确保在超大规模规则（十万级）下具备低延迟、低分配、无锁的查找体验。
- **演进策略**：经历了从“Protobuf 二进制极速解析”、“热点路径预编译与零分配”，再到“加载侧隔离、线程安全生命周期和可重排调度任务”的完整迭代闭环。

---

## 2. 第一部分：高吞吐解析与紧凑索引设计

为了将 dat 二进制格式高性能转换为内存匹配器，设计了以下底座逻辑：

### 2.1 轻量手写 Protobuf Reader
- 摒弃官方重型 `protobuf-java` 依赖，以降低依赖体积和运行时分配。
- 实现 `V2RayGeoDataReader` 覆盖 Varint、Length-delimited 等基本 Wire Type，支持未知字段的安全跳过。

### 2.2 GeoIP Compact Range 与二分查找
- **无符号 IP 段转换**：加载时将 CIDR 转换为起止 IP Range，对其合并排序。
- **零分配二分查找**：
  - IPv4：使用 unsigned int 二分查找区间，无 Map 寻址与装箱。
  - IPv6：使用两个 `long`（High/Low 64-bit）表达 128-bit 的起止段进行二分，避开 `BigInteger` 内存开销。

### 2.3 GeoSite Trie 树复用
- 将 `RootDomain` 规则编译为后缀匹配，传给已有低分配的 `UltraDomainTrieMatcher`。
- `Full`、`Plain`（Keyword）、`Regex` 规则分别映射为对应的精确匹配、关键词命中（Aho-Corasick）与正则匹配。

---

## 3. 第二部分：热点路径预编译优化 (Hotpath)

为了应对热点路径中超高频的 IP/域名匹配请求，必须消除运行时的解析和查找开销。

### 3.1 预编译与便捷 API 隔离
- **便捷 API（慢路径，不推荐热点调用）**：
  - `matchGeoIp(String code, String ip)` / `matchGeoSite(...)`。
  - 运行时涉及 IP 格式解析、字符串 Normalize、Attr 过滤器解析、以及 Map 查找。
- **预编译 API（推荐热点调用）**：
  - 配置加载期一次性调用 `compileGeoIpMatcher(code)` 生成 `CodeMatcher` 视图。
  - 配置加载期调用 `compileGeoSiteMatcher(code, attrFilter)` 生成 `GeoSiteMatcher`。
  - 匹配请求直接使用 `CodeMatcher.matches(byte[])` 和 `GeoSiteMatcher.matches(domain)`，实现请求期零字符串开销。

### 3.2 同 Code 规则合并与 Inverse 规则过滤
- 合并同 code 下多个 normal entry，减少线性遍历的退化风险。
- 在 `lookupCode(byte[])` 反向 IP 查找中，针对 normal 与 inverse entry（反向排除段）重叠的场景，通过定义优先级排序（TreeSet 按 orderId 选择首项）避免歧义。

---

## 4. 第三部分：加载隔离、生命周期与任务重排

### 4.1 细粒度 Scoped Load 隔离
- 隔离 IP 侧和 Site 侧加载：支持 `ensureIpLoaded()` 与 `ensureSiteLoaded()`。
- compile API 保证仅在 snapshot 缺失时等待自身侧相关的 load task，防止因为其中一侧（如 site.dat 损坏）异步加载失败误伤或阻塞另一侧已有的可用快照 compile。

### 4.2 线程安全关闭 (Close Lifecycle)
- 引入 `volatile boolean closed` 标记，将 `close()` 设计为终态，不可逆。
- `close()` 触发时除清理快照外，强行 cancel `dailyTasks` 以及正在进行的 `dTask`。
- `load` 核心逻辑中在 Snapshot 替换发布前二次校验 `closed` 状态，确保在 close 竞态下绝不重新发布或泄漏新快照。

### 4.3 动态调度更新 (Schedule Reschedule)
- 运行时支持调用 `setDailyDownloadTime(...)` 重置每日自动下载时钟。
- **防半更新设计**：先预校验时间格式是否符合 `Time.valueOf` 规范并尝试 schedule 新任务。当新 schedule 成功生成后，再 cancel 旧任务并更新字段。格式非法时抛出异常并完整保留旧状态。

---

## 5. 第四部分：属性过滤与边界保障

### 5.1 GeoSite 属性解析
- 支持 Domain 级属性（如 `geosite:google@ads`）过滤匹配。
- 严格解析 `bool_value`，若为 `false` 则忽略该属性 Key。
- 支持 `int_value` 存在性过滤，作为 key/bool 过滤语义的支撑。

### 5.2 边界测试覆盖要求
- **IP 边界**：覆盖 `0.0.0.0/0`、`255.255.255.255/32`、`::/0` 等极端 CIDR 边界。
- **加载异常**：验证无效 dat 格式或空文件下，`compile*` 同步抛出加载异常，而不静默返回 null。
- **并发竞争**：验证在加载中触发 `close()` 后的正确拦截行为。
