# ℞lib-java

[English](#english) | [中文](#中文)

<h2 id="english">English</h2>

`rxlib` is a core Java foundation library designed for high-performance and high-concurrency scenarios. Built on top of Netty's low-level network programming, it provides extreme performance optimization and deep encapsulation for core components such as concurrency processing, I/O storage, network proxies, and RPC communication.

### Maven [![Java CI](https://github.com/RockyLOMO/rxlib/actions/workflows/maven.yml/badge.svg)](https://github.com/RockyLOMO/rxlib/actions/workflows/maven.yml)
```xml
<dependency>
    <groupId>com.github.rockylomo</groupId>
    <artifactId>rxlib</artifactId>
    <version>2.22.3</version>
</dependency>
```

---

### Core Features

For all detailed technical documentation and usage references, please check the `docs/reference/` directory.

#### 1. Network Components (org.rx.net)
A zero-allocation, low-latency, high-performance protocol stack based on Netty:
* **[Proxy & NAT Traversal (SOCKS / SS / RRP)](docs/reference/net/socks.md)**: Supports SOCKS5, Shadowsocks, and the custom RRP reverse relay protocol. Includes full TCP/UDP protocol forwarding, Double-Array Trie smart routing, and underlying UDP disguise/acceleration.
* **[RPC (Remoting)](docs/reference/net/rpc.md)**: A self-developed lightweight RPC framework supporting TCP/UDP hybrid transmission and zero-copy efficient serialization based on Fury.
* **[HTTP Client & Server](docs/reference/net/http.md)**: A lightweight, non-blocking HTTP server, alongside a high-performance HTTP client with connection pooling and cache control (includes `RestClient` similar to Feign).
* **[DNS Server & Client](docs/reference/net/dns.md)**: Replaces the blocking `InetAddress` to provide asynchronous, anti-breakdown DNS resolution and a local anti-pollution proxy.
* **[UDP Hole Punching](docs/reference/net/punch.md)**: A direct P2P communication framework for UDP in NAT environments.
* **[NTP Time Synchronization](docs/reference/net/ntp.md)**: A lightweight NTP client protocol implementation ensuring time consistency across distributed clusters.
* **[Internal Service Governance (Nameserver)](docs/reference/net/nameserver.md)**: Lightweight service registration, health keep-alive, and discovery.
* **[Base Transport Layer](docs/reference/net/transport.md)**: Highly encapsulated scaffolding for TCP/UDP clients and servers.

#### 2. Concurrency & Resource Pooling (org.rx.core)
* **[Adaptive Object Pool (ObjectPool)](docs/reference/ObjectPool.md)**: A lock-free/low-lock object pool tailored for hot paths, supporting adaptive dynamic scaling (Adaptive Refill) based on borrowing frequency.
* **[Dynamic Thread Pool (ThreadPool)](docs/reference/ThreadPool.md)**: A thread pool that adapts to the optimal number of threads, featuring efficient task scheduling based on a `HashedWheelTimer` and built-in support for full-link asynchronous traces.

#### 3. Storage & I/O Layer (org.rx.io)
* **[EntityDatabase (EDB)](docs/reference/EDB.md)**: A lightweight embedded entity database supporting Sharding mechanisms.
* **[High-Performance Hybrid Stream & KV Storage](docs/reference/IO.md)**: Includes `KeyValueStore` (based on WAL + MMAP) and `HybridStream` (a BIO that intelligently switches between Heap/Direct Buffer and Memory/File Stream).

#### 4. Common Base Components
* **[Distributed ID](docs/reference/DistributedId.md)**: A high-performance global unique identifier generator.
* **[Syntactic Sugar & Extension Methods (Nx / Extends)](docs/reference/Nx.md)**: Provides C# LINQ-like fluent collection processing extensions.
* **[Common Utilities (Util)](docs/reference/Util.md)**: A suite of infrastructure components including encryption, string processing, reflection caching, etc.

---

<h2 id="中文">中文</h2>

`rxlib` 是一套为高性能、高并发场景设计的核心 Java 基础类库。它以 Netty 底层网络编程为基石，并对并发处理、I/O 存储、网络代理、RPC 通信等核心组件进行了极端的性能优化与深度封装。

### Maven [![Java CI](https://github.com/RockyLOMO/rxlib/actions/workflows/maven.yml/badge.svg)](https://github.com/RockyLOMO/rxlib/actions/workflows/maven.yml)
```xml
<dependency>
    <groupId>com.github.rockylomo</groupId>
    <artifactId>rxlib</artifactId>
    <version>2.22.3</version>
</dependency>
```

---

### 核心特性 (Features)

所有详细的技术文档与使用参考，请查阅 `docs/reference/` 目录。

#### 1. 网络组件 (org.rx.net)
基于 Netty 实现的零分配、低延迟的高性能协议栈：
* **[代理与内网穿透 (SOCKS / SS / RRP)](docs/reference/net/socks.md)**：支持 SOCKS5、Shadowsocks 及自定义的 RRP 反向中继协议，涵盖 TCP/UDP 全协议转发、双数组 Trie 树智能路由与底层 UDP 伪装加速。
* **[RPC 远程调用 (Remoting)](docs/reference/net/rpc.md)**：自研轻量级 RPC 框架，支持 TCP/UDP 混合传输 (Hybrid) 与基于 Fury 的零拷贝高效序列化。
* **[HTTP 客户端与服务端](docs/reference/net/http.md)**：轻量级非阻塞 HTTP 服务器，以及带连接池复用和缓存控制的高性能 HTTP 客户端 (包含类似于 Feign 的 `RestClient`)。
* **[DNS 服务器与客户端](docs/reference/net/dns.md)**：取代阻塞式 `InetAddress`，提供异步防穿透 DNS 解析与本地防污染代理。
* **[UDP 打洞 (Hole Punching)](docs/reference/net/punch.md)**：用于 NAT 环境下的 UDP 对等通信 (P2P) 直连框架。
* **[NTP 时间同步](docs/reference/net/ntp.md)**：轻量的 NTP 客户端协议实现，保障分布式集群下的时间一致性。
* **[内部服务治理 (Nameserver)](docs/reference/net/nameserver.md)**：轻量级服务注册、健康保活与发现。
* **[基础传输层 (Transport)](docs/reference/net/transport.md)**：高度封装的 TCP / UDP 客户端与服务端脚手架。

#### 2. 并发与资源池 (org.rx.core)
* **[自适应对象池 (ObjectPool)](docs/reference/ObjectPool.md)**：专为热点路径设计的无锁/低锁对象池，支持基于借用频率的自适应动态扩缩容 (Adaptive Refill)。
* **[动态线程池 (ThreadPool)](docs/reference/ThreadPool.md)**：自适应最佳线程数，基于时间轮 (HashedWheelTimer) 的高效任务调度，并内置支持全链路异步 Trace。

#### 3. 存储与 I/O 层 (org.rx.io)
* **[实体数据库 (EntityDatabase)](docs/reference/EDB.md)**：支持 Sharding 机制的轻量级内嵌实体数据库。
* **[高性能混合流与 KV 存储](docs/reference/IO.md)**：包含 `KeyValueStore` (基于 WAL + MMAP) 与 `HybridStream` (基于 Heap/Direct Buffer 与 Memory/File Stream 智能切换的 BIO)。

#### 4. 通用基础组件
* **[分布式 ID](docs/reference/DistributedId.md)**：高性能全局唯一标识生成器。
* **[语法糖与扩展方法 (Nx / Extends)](docs/reference/Nx.md)**：提供近似 C# LINQ 的流式处理集合扩展。
* **[通用工具类](docs/reference/Util.md)**：加密、字符处理、反射缓存等一系列基建组件。
