# 辅助支持模块 (org.rx.net.support)

提供给高级网络组件（尤其是 DNS 和 Proxy）的路由与域名规则匹配底层支持。它使用了高性能的数据结构，以极低延迟完成大量规则的命中匹配。

## 核心类介绍

- **`DomainDoubleArrayTrie` / `UltraDomainTrieMatcher`**:
  基于双数组 Trie 树（Double-Array Trie）实现的高性能域名匹配算法。用于在成千上万条代理分流规则中，对输入的 Hostname 极速进行前缀/后缀树匹配，不会引起性能瓶颈。

- **`GeoManager` / `GeoSiteMatcher` / `IpGeolocation`**:
  GeoIP（基于 IP 的地理位置定位）相关支持类。通常用于实现按地域（如国内直连、海外走代理服务器）的网络分流路由策略。

- **`PublicSuffixMatcher`**:
  公共后缀列表（Public Suffix List）解析器，可将类似 `www.sub.example.com.cn` 准确提取为根域名 `example.com.cn`，从而提升 DNS 缓存和 Cookie 管理等场景的准确率。

- **`EndpointTracer` / `UnresolvedEndpoint`**:
  网络连接端点抽象。它不仅包含了 IP 与端口信息，还记录了该端点的解析状态、延迟状态等追踪指标。

## 适用场景
- `socks` 代理和 `dns` 模块背后的核心路由引擎。
- 业务方需要高性能、大数据量的域名或 IP 归属地判断引擎。
