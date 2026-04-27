# DNS 模块 (org.rx.net.dns)

提供基于 Netty 的非阻塞 DNS 解析能力，包含 DNS 客户端及本地 DNS 代理服务器功能。这在高性能网络中用于替换 Java 默认的阻塞式 `InetAddress.getByName` 调用。

## 核心类介绍

- **`DnsClient`**:
  高性能非阻塞 DNS 客户端，基于 Netty 提供的 `DnsNameResolver` 构建。支持自定义上游 DNS 服务器配置，并可以配置 DNS 缓存策略。

- **`DnsServer`**:
  基于 Netty `DatagramChannel` 构建的 DNS 服务器端。可以接收本地发出的 UDP DNS 查询请求并进行代理转发、本地缓存或劫持拦截。

- **`DnsHandler`**:
  `DnsServer` 中的 Netty 管道处理器，负责处理 DNS 解析的上下游路由逻辑，将 DNS 报文解码并分发到真正的解析流程中。

- **`DnsMessageUtil`**:
  辅助构建和解析 DNS 报文（`DnsQuery`, `DnsResponse`）的工具类。

## 适用场景
- 在代理服务器或高性能爬虫等需要大量 DNS 解析的场景中，防止 DNS 解析阻塞 Netty 线程池。
- 构建本地防污染 DNS 缓存代理服务器。
