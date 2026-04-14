# RXlib 项目 AGENTS 规范（项目级）

## 1. 项目定位与模式
- 当前项目：`rxlib`
- 模式结论：**高性能模式（Netty 底层网络编程）**
- 适用范围：涉及网络协议、长连接、传输层、编解码、连接管理、多线程并发模型的所有代码。

## 2. 强制技术基线
- Java 版本：**严格 Java 8**。
- 网络框架：优先原生 Netty 能力，避免引入 Spring 风格封装到性能敏感路径。
- 性能目标：零分配优先、低延迟优先、吞吐优先。

## 3. 代码与性能约束（高性能模式）

### 3.1 内存与对象分配
- 热点路径禁止频繁 `new` 对象。
- 优先 `PooledByteBufAllocator`、Direct Buffer、零拷贝。
- `ByteBuf` 必须遵守引用计数语义，确保成对释放（必要时使用 `ReferenceCountUtil.release()`）。
- 禁止在高频路径构造临时字符串、正则对象、反射调用。

### 3.2 并发与线程模型
- 优先使用 `EventLoop` 线程亲和性，避免无意义线程切换。
- I/O 线程不得执行阻塞逻辑（阻塞 I/O、长计算、阻塞锁等待）。
- 业务线程池与 I/O 线程池职责分离，避免相互抢占。
- 降低锁竞争，优先无锁或低锁开销结构。

### 3.3 协议与编解码
- 自定义协议解析必须保持浅调用栈、低分支开销、低对象分配。
- 性能敏感循环避免过度抽象和高频 Lambda。
- 优先基本类型集合（例如 fastutil）而非装箱集合。

### 3.4 DNS 与网络策略
- 避免系统阻塞式 DNS 解析（如 `InetAddress.getByName()`）进入关键链路。
- 优先使用 Netty DNS 组件（如 `DnsNameResolver`）或远程解析策略，降低本地污染风险。

## 4. 必须覆盖的工程风险
任何网络改动都必须显式评估并验证以下项：
- 内存泄漏风险（ByteBuf/Channel/任务对象）
- 背压处理（读写水位、队列堆积、限流策略）
- 连接生命周期（建连、保活、半关闭、异常断开、重连）
- 线程模型合理性（EventLoop 数量、业务池隔离）
- 协议兼容性与编解码性能
- 核心监控指标（至少包含堆外内存占用、连接数、吞吐/延迟）

## 5. 本仓网络代码地图（快速定位）
核心目录：`rxlib/src/main/java/org/rx/net`
- `transport`：TCP/UDP 基础传输
- `socks`：SOCKS5/Shadowsocks/UDP 中继/HTTP Tunnel
- `rpc`：Remoting RPC
- `http`：HTTP Server/Client
- `dns`：DNS Client/Server
- `support`：路由、域名匹配、上游选择

优先关注类：
- `TransportFlags`、`Sockets`、`BackpressureHandler`
- `SocksProxyServer`、`SocksContext`
- `Remoting`、`RpcClientConfig`、`RpcServerConfig`
- `TcpServer`、`TcpClient`、`UdpClient`

## 6. 测试与验收要求
- 小改动：必须补充对应单元测试并验证通过。
- 大改动：必须补充单元测试 + 集成测试并验证通过。
- 网络相关优先回归：
  - `SocksProxyServerIntegrationTest`
  - `ShadowsocksServerIntegrationTest`
  - `Socks5ClientIntegrationTest`
  - `RrpIntegrationTest`
  - `RemotingTest`
  - `DnsServerIntegrationTest`

## 7. 提交前检查清单
- 是否引入热点路径对象分配。
- 是否存在 ByteBuf/Channel 生命周期泄漏点。
- 是否出现 I/O 线程阻塞。
- 客户端/服务端 `TransportFlags` 是否对齐。
- 是否补充并执行了相应测试。
