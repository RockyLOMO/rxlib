# 辅助支持模块 (org.rx.net.support)

提供给高级网络组件（尤其是 DNS 和 Proxy）的路由与域名规则匹配底层支持。它使用了高性能的数据结构，以极低延迟完成大量规则的命中匹配。

## 核心类介绍

- **`DomainDoubleArrayTrie` / `UltraDomainTrieMatcher`**:
  基于双数组 Trie 树（Double-Array Trie）实现的高性能域名匹配算法。用于在成千上万条代理分流规则中，对输入的 Hostname 极速进行前缀/后缀树匹配，不会引起性能瓶颈。

- **`GeoManager` / `GeoSiteMatcher` / `IpGeolocation`**:
  GeoIP（基于 IP 的地理位置定位）相关支持类。通常用于实现按地域（如国内直连、海外走代理服务器）的网络分流路由策略。

- **`PublicSuffixMatcher`**:
  公共后缀列表（Public Suffix List）解析器，可将类似 `www.sub.example.com.cn` 准确提取为根域名 `example.com.cn`，从而提升 DNS 缓存和 Cookie 管理等场景的准确率。

- **`EndpointTracer` / `Sockets.newUnresolvedEndpoint`**:
  网络连接端点追踪与未解析地址构造入口。未解析地址统一使用 `InetSocketAddress` 表达，字面量 IP 会转为已解析地址，域名保持 unresolved，避免触发本机 DNS。

---

## 核心实现细节

### UltraDomainTrieMatcher
高性能双数组 Trie 域名后缀匹配器。

| 特性 | 说明 |
|------|------|
| **Suffix Compression (Tail)** | 共享后缀存储，大幅降低内存占用 |
| **Zero-Allocation 查询** | 使用 `Window` 滑动窗口 + `CompactLabelMap` 扁平哈希，无临时对象分配 |
| **FastThreadLocal 零拷贝** | 查询过程不生成中间字符串，直接基于原始字符数组比较 |
| **CompactLabelMap** | 自定义开放寻址哈希表，替代 fastutil 运行时依赖，序列化友好 |

**复杂度：**
- 构建：O(N × L)，N 为规则数，L 为平均域名长度
- 查询：O(L)，与规则数无关

### GeoSiteMatcher
V2Ray GeoSite 格式规则匹配器，支持完整规则类型。

| 特性 | 说明 |
|------|------|
| **四层匹配策略** | 按优先级：①后缀 (Trie) ②完整 (HashSet) ③关键词 (Aho-Corasick) ④正则 (Regex) |
| **Aho-Corasick 多模式** | 使用 `hankcs/AhoCorasickDoubleArrayTrie` 实现 O(N) 多关键词匹配 |
| **零分配 Case-Insensitive** | `LowerAsciiCharSequence` 包装器避免 toLowerCase 字符串分配 |
| **线程安全 Regex** | `ReusablePattern` 使用 `FastThreadLocal<Matcher>` 避免重复编译 |

**规则格式支持：**
- `domain:example.com` - 后缀匹配（默认）
- `full:example.com` - 完整匹配
- `keyword:example` - 关键词包含
- `regexp:.*example.*` - 正则匹配

### DomainTrieMatcher (Deprecated)
早期域名 Trie 实现，使用压缩数组节点。
- 仅支持 38 字符集（a-z, 0-9, ., -）
- 反向插入实现后缀匹配
- 已被 `UltraDomainTrieMatcher` 取代

## 适用场景
- `socks` 代理和 `dns` 模块背后的核心路由引擎。
- 业务方需要高性能、大数据量的域名或 IP 归属地判断引擎。
