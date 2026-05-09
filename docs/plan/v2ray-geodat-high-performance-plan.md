# 背景

收到用户诉求：如果要实现读取 v2ray 的 `geosite.dat` 和 `geoip.dat`，给出最高性能方式的实现计划。

需要满足：
- 读取 v2ray `geosite.dat`、`geoip.dat`；
- 设计以最高性能为目标；
- 先提交计划文档，不修改业务代码。

# 任务类型判断

本次任务归类为新需求：

- 用户提出“要实现读取 v2ray 的 geosite.dat geoip.dat”；
- 目标是新增一种数据格式读取能力，并需要和现有 geo/domain 匹配模块集成；
- 当前阶段用户明确要求“出个计划”，因此只做计划文档提交，不进入代码实现。

# 当前上下文

已扫描 master 分支当前上下文：

- 仓库是 Maven 多模块 Java 项目，父 POM `pom.xml` 下包含 `rxlib`、`rxlib-x` 模块，Java 版本配置为 1.8。
- `AGENTS.md` 要求网络相关改动优先落在 `rxlib/src/main/java/org/rx/net`，强调 Netty、低分配、ByteBuf 生命周期、I/O 不阻塞、必须显式验证。
- 当前 geo 相关代码集中在 `rxlib/src/main/java/org/rx/net/support`：
  - `GeoManager`：负责下载/加载现有 `geoip.mmdb` 和文本版 `geosite-direct.txt`，并以 volatile 快照方式热替换 `GeoIPSearcher`、`GeoSiteMatcher`。
  - `GeoIPSearcher`：基于 MaxMind MMDB 的 IP 地理查询，内部已有 Caffeine 缓存、private IP/unknown IP 兜底、Closeable 资源释放。
  - `GeoSiteMatcher`：支持 domain/full/keyword/regexp 风格规则，domain 规则通过 `UltraDomainTrieMatcher` 做 suffix 匹配，keyword 通过 Aho-Corasick，regexp 复用 Matcher。
  - `UltraDomainTrieMatcher`：已有面向 domain suffix 的紧凑 double-array/trie 实现，具备低分配匹配路径。
- 当前测试集中在 `rxlib/src/test/java/org/rx/net/support`：
  - `GeoIPSearcherTest`
  - `GeoSiteMatcherTest`
  - `UltraDomainMatcherTest`
- CI 中存在 `.github/workflows/jdk8-unit-tests.yml`，支持 `workflow_dispatch`，可传入 `test_classes` 精准跑相关测试。

外部格式依据：

- V2Fly 当前 `routercommon/common.proto` 中定义了 `Domain`、`CIDR`、`GeoIP`、`GeoIPList`、`GeoSite`、`GeoSiteList`。`GeoIPList`/`GeoSiteList` 都是 repeated entry 包装结构。
- `domain-list-community` 文档说明 geosite 数据最终会生成外部 geosite 文件，domain/full/keyword/regexp 分别映射到 protobuf `Domain` 的不同类型，并且属性会保留在最终列表中。
- `v2fly/geoip` 文档说明 `geoip.dat` 是 V2Ray GeoIP dat 输出格式，且 CLI 支持 `v2rayGeoIPDat` 输入和输出格式。

# 目标

1. 在 `rxlib` 中支持读取 v2ray `geosite.dat`：
   - 按 code 加载，例如 `cn`、`geolocation-!cn`、`category-ads-all`。
   - 支持属性过滤，例如 `geosite:google@ads` 或未来内部 API 的 `code + attributes`。
   - 将 RootDomain/Full/Plain/Regex 分别落到当前 `GeoSiteMatcher` 的 suffix/full/keyword/regexp 结构，复用已有高性能匹配器。

2. 在 `rxlib` 中支持读取 v2ray `geoip.dat`：
   - 按 country/code 加载 IPv4/IPv6 CIDR。
   - 支持快速 IP 命中判断和可选返回 code。
   - 与当前 `GeoManager.resolveIp` 或新增 API 协同，不破坏 MMDB 查询语义。

3. 最高性能优先：
   - 加载阶段允许做一次性解析和索引构建。
   - 查询阶段保持 immutable snapshot、无锁、尽量零分配。
   - 不在热路径使用 protobuf 反射、HashMap 装箱、正则重复创建、字符串反复 lower-case。

4. 兼容现有风格：
   - JDK8。
   - 最小依赖，不引入重型框架。
   - 使用现有 `org.rx.net.support` 包和已有 matcher。
   - 使用 volatile 快照 + 延迟关闭旧资源的方式完成热替换。

# 非目标

1. 本计划阶段不修改业务代码。
2. 不实现 v2ray 的完整路由配置解析，只读取 dat 文件本身。
3. 不下载和内置官方 dat 文件，不把大二进制文件提交到仓库。
4. 不替换现有 MaxMind MMDB 查询能力，只新增 v2ray dat 能力或提供可选切换。
5. 不支持 JDK9+ API，例如 `List.of`、`Files.mismatch`、VarHandle。
6. 不自动发布 release，不修改 secrets/token/证书。

# 设计方案

## 1. 文件格式读取策略

新增轻量 protobuf reader，仅覆盖本需求需要的 wire type：

- varint
- length-delimited
- bytes/string
- repeated message
- bool/uint32/string/bytes

不使用 protobuf 代码生成作为首选方案，原因：

- 当前仓库未引入 `protobuf-java`；新增它会增加依赖体积和运行时对象分配。
- 生成类解析会完整实例化 message tree，不利于“按 code 定位 + 一次性构建高性能索引”的目标。
- 手写 reader 可以跳过未知字段，直接将所需字段转成目标结构。

读取方式分两档：

- 默认档：`Files.readAllBytes` 读入 byte[]，用 offset/limit 顺序解析。对于常见 geosite/geoip 文件体积，heap byte[] 对 JDK8 更简单，生命周期由快照持有，关闭无特殊风险。
- 可选档：大文件超过阈值时使用 `FileChannel.map`，解析器统一抽象为 `GeoDataInput`。JDK8 下 MappedByteBuffer 主动 unmap 不稳定，因此默认不启用强制 unmap，避免引入 Unsafe 风险。

## 2. geosite.dat 解析与索引

新增类建议：

- `V2RayGeoSiteReader`
  - 输入：`File` 或 `byte[]`
  - 输出：`Map<String, GeoSiteEntry>` 或懒加载 `GeoSiteIndex`
  - 解析 `GeoSiteList.entry`，字段：`country_code/code/domain/resource_hash`

- `V2RayGeoSiteMatcherFactory`
  - 根据 `code` 和属性过滤构造 `GeoSiteMatcher`
  - 将 protobuf Domain.Type 映射为当前规则：
    - `RootDomain` -> 普通 domain suffix 规则，传给 `UltraDomainTrieMatcher`
    - `Full` -> `full:`
    - `Plain` -> `keyword:`
    - `Regex` -> `regexp:`
  - 属性过滤：按 Domain.Attribute 的 key/bool/int 过滤。首期建议支持：
    - 无属性：全部规则
    - `@attr`：包含 attr
    - `@!attr` 或排除 attr：不包含 attr

性能要点：

- 解析时直接按 code 分组，不为每条 Domain 暴露可变对象。
- `RootDomain`/`Full`/`Plain` 在构建阶段统一做 ASCII lower-case，避免查询时反复 lower-case。
- `Regex` 保持数量统计和告警；Regex 天生慢，仍复用当前 `ReusablePattern` 方案。
- 对同一个 `code + attrFilter` 构建结果做 bounded cache，缓存 value 是 immutable `GeoSiteMatcher`。

## 3. geoip.dat 解析与索引

新增类建议：

- `V2RayGeoIpReader`
  - 解析 `GeoIPList.entry`。
  - 每个 entry 解析 `country_code/code/cidr/inverse_match`。
  - 每个 CIDR 解析 `ip` bytes 和 `prefix`。

- `V2RayGeoIpMatcher`
  - 提供：
    - `boolean matches(String ip)`
    - `boolean matches(byte[] ipBytes)`
    - `String lookupCode(String ip)` 或可选 `Set<String> lookupCodes`
  - IPv4/IPv6 分开索引。

GeoIP 最高性能方案：

- 加载阶段将 CIDR 转成 compact range：
  - IPv4：`int start`, `int end`, `String codeId`
  - IPv6：两个 long 表达 128-bit start/end，或 byte[16] 排序段。首期可使用两个 long 数组避免 BigInteger。
- 对同一 code 的 CIDR 先排序并合并相邻/重叠段。
- 查询阶段：
  - IPv4 使用 unsigned int 二分查找 range。
  - IPv6 使用 high/low long 二分查找。
- 如需要同时支持多 code 查询：
  - 单 code matcher：每个 code 独立 range 数组，最快。
  - 全量 lookup：全局 range 数组存 codeId，命中返回 code。

不建议在热路径做：

- `InetAddress.getByName` DNS 解析。
- BigInteger。
- List/Map 迭代扫描 CIDR。
- 每次查询创建对象。

## 4. 与 GeoManager 集成

建议以增量兼容方式扩展 `GeoManager`：

- 新增配置字段：
  - `v2rayGeoIpFileUrl`
  - `v2rayGeoIpFile`
  - `v2rayGeoSiteFileUrl`
  - `v2rayGeoSiteFile`
  - `geoMode` 或两个 boolean：`enableV2RayGeoIpDat`、`enableV2RayGeoSiteDat`
- 保留当前 `geoip.mmdb` 和 `geosite-direct.txt` 默认路径，避免现有行为变化。
- 新增 API：
  - `boolean matchGeoSite(String code, String domain)`
  - `boolean matchGeoSite(String code, String attrFilter, String domain)`
  - `boolean matchGeoIp(String code, String ip)`
  - `String resolveGeoIpCode(String ip)`
- 现有 `matchSiteDirect(domain)` 继续使用当前 `GeoSiteMatcher`，或在配置开启后映射到 `geosite:direct`/指定 code。

热替换策略：

- 加载新 dat -> 构建完整 immutable index -> volatile 替换引用。
- 旧 index 如果持有 Closeable 资源，延迟关闭。
- 构建失败时不污染当前可用快照。

## 5. 资源释放与异常处理

- `V2RayGeoDataIndex` 实现 `Closeable`，即使默认 byte[] 无需释放，也为未来 mmap 留接口。
- 解析时校验：
  - varint 长度溢出
  - length-delimited 越界
  - CIDR IP 长度只能是 4 或 16
  - prefix 必须在 IPv4 0..32 或 IPv6 0..128
  - code 为空时回退到 country_code
- 对未知字段跳过，不因 v2ray 后续新增字段直接失败。
- 对不支持 wire type 抛出 `InvalidException` 或项目内一致异常。

## 6. 测试与基准

新增测试建议：

- `V2RayGeoDataReaderTest`
  - 构造最小 protobuf byte[]，验证能解析 GeoSiteList/GeoIPList。
  - 覆盖未知字段跳过、越界、非法 prefix。
- `V2RayGeoSiteMatcherTest`
  - RootDomain/Full/Plain/Regex 映射正确。
  - 属性 include/exclude 正确。
  - 大小写匹配正确。
- `V2RayGeoIpMatcherTest`
  - IPv4/IPv6 CIDR 命中、不命中、边界地址命中。
  - range merge 后结果正确。
- 如需要性能验证：增加 JMH 或普通 micro benchmark，但不让 CI 依赖不稳定耗时 benchmark。

# 修改文件列表

计划后续代码阶段预计新增/修改：

新增：

- `rxlib/src/main/java/org/rx/net/support/V2RayGeoDataReader.java`
- `rxlib/src/main/java/org/rx/net/support/V2RayGeoSiteReader.java`
- `rxlib/src/main/java/org/rx/net/support/V2RayGeoSiteIndex.java`
- `rxlib/src/main/java/org/rx/net/support/V2RayGeoIpReader.java`
- `rxlib/src/main/java/org/rx/net/support/V2RayGeoIpIndex.java`
- `rxlib/src/main/java/org/rx/net/support/V2RayGeoIpMatcher.java`
- `rxlib/src/test/java/org/rx/net/support/V2RayGeoDataReaderTest.java`
- `rxlib/src/test/java/org/rx/net/support/V2RayGeoSiteMatcherTest.java`
- `rxlib/src/test/java/org/rx/net/support/V2RayGeoIpMatcherTest.java`

可能修改：

- `rxlib/src/main/java/org/rx/net/support/GeoManager.java`
- `rxlib/src/main/java/org/rx/net/support/GeoSiteMatcher.java`
- `rxlib/src/main/java/org/rx/net/support/UltraDomainTrieMatcher.java`
- `rxlib/pom.xml`（仅当必须新增轻量测试依赖或 benchmark 配置；默认不改）

# 风险点

1. 格式兼容风险：v2ray dat 基于 protobuf，后续字段可能扩展。方案通过跳过未知字段降低风险。
2. 性能风险：regex/keyword 规则不可避免比 suffix/full 慢，需要统计数量并避免热路径额外分配。
3. 内存风险：geosite 全量加载会占用较多内存，应缓存 code 维度构建结果，并避免无界缓存。
4. MMap 风险：JDK8 主动 unmap 需要非公开 API，不作为默认方案。
5. IPv6 风险：128-bit range 比较容易出现有符号 long 边界错误，必须用 unsigned 比较测试覆盖。
6. 并发风险：热替换时旧快照仍可能被读线程使用，必须 immutable 且延迟关闭。
7. 行为兼容风险：不能改变现有 `GeoManager.matchSiteDirect` 和 `resolveIp` 默认行为。
8. 测试风险：真实 dat 文件体积大且会变化，CI 不应依赖外网下载；需要使用测试内构造的最小 protobuf 样本。

# 验证方案

代码实现阶段完成后：

1. 本地/CI 编译：
   - `mvn -B -U -Dgpg.skip=true -Dmaven.test.skip=false -DskipTests=false clean test`

2. 精准单测：
   - `V2RayGeoDataReaderTest`
   - `V2RayGeoSiteMatcherTest`
   - `V2RayGeoIpMatcherTest`
   - `GeoSiteMatcherTest`
   - `UltraDomainMatcherTest`
   - `GeoIPSearcherTest`

3. GitHub Actions：
   - 触发 `.github/workflows/jdk8-unit-tests.yml`
   - `test_classes` 建议传入：
     - `V2RayGeoDataReaderTest,V2RayGeoSiteMatcherTest,V2RayGeoIpMatcherTest,GeoSiteMatcherTest,UltraDomainMatcherTest,GeoIPSearcherTest`

4. 性能验证：
   - 构造 10w 级 domain suffix 样本，比较构建耗时和查询吞吐。
   - 构造 10w 级 IPv4/IPv6 CIDR 样本，验证二分查找复杂度稳定。
   - 确认查询阶段无 DNS、无锁、无正则重复编译、无 BigInteger。

# 后续执行条件

本提交只包含计划文档。收到“按计划执行 / 开始修改代码 / 实现代码”等明确指令后，再进入代码实现阶段。
