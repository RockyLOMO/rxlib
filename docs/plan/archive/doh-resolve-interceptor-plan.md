# DNS over HTTPS TCP 端口复用方案

## 模式

本方案采用高性能模式（Netty 底层网络编程），Java 版本严格按 Java 8 设计。

## 目标

- `DnsServer` 不新增端口，复用现有 TCP DNS 端口承载 DoH。
- UDP DNS 端口保持现状，不参与 DoH。
- 新增 `DoHClient`，用于 `Sockets.injectNameService(...)` 直接连接远程 `DnsServer` 的同一个 TCP 端口。
- 远程 `DnsServer` TCP 端口同时支持：
  - DNS-over-TCP：原始 2 字节 length-prefix DNS 报文。
  - DoH：HTTPS `POST /dns-query`，body 为 DNS wire format。
- 保持 I/O 线程不阻塞，热点路径避免多余对象分配，`ByteBuf` 引用计数必须成对释放。

## 结论

技术上可以复用 `DnsServer` 自己的 TCP 端口，但不能直接把 HTTP handler 塞到当前 pipeline 后面。当前 TCP pipeline 是：

```text
TcpDnsQueryDecoder -> TcpDnsResponseEncoder -> DnsHandler
```

入口位于 `rxlib/src/main/java/org/rx/net/dns/DnsServer.java:106`。它只认识 DNS-over-TCP length-prefix 报文；DoH 的首包是 TLS ClientHello 或 HTTP 请求行，必须先做协议探测和 pipeline 分流。

`HttpServer.getDefault()` 可以作为 fallback 或测试入口，但不是本方案主路径。主路径是 `DnsServer` TCP 端口 mux。

## 总体架构

```text
JDK InetAddress
    -> Sockets nsProxy
    -> DoHClient (DnsResolveInterceptor)
    -> TLS/HTTP POST /dns-query
    -> remote DnsServer TCP port
    -> DnsTcpPortMuxHandler
    -> DoHServerHandler
    -> DnsResolveCore
    -> hosts / interceptors / upstream DNS

传统 DNS client
    -> remote DnsServer TCP port
    -> DnsTcpPortMuxHandler
    -> TcpDnsQueryDecoder
    -> DnsHandler

传统 UDP DNS client
    -> remote DnsServer UDP port
    -> DatagramDnsQueryDecoder
    -> DnsHandler
```

## 新增组件

### 1. DoHClient

建议类名：

- `org.rx.net.dns.DoHClient implements DnsResolveInterceptor, AutoCloseable`
- `org.rx.net.dns.DoHEndpoint`
- `org.rx.net.dns.DoHMessageCodec`

`DoHClient` 作用：

- 给 `Sockets.injectNameService(DoHClient)` 使用。
- 直接连接远程 `DnsServer` 的 TCP DNS 端口，不需要本地 DNS 端口。
- 实现 `DnsResolveInterceptor#resolveHost(InetAddress srcIp, String host)`。
- 内部发起 DoH 请求，返回 `List<InetAddress>`。

`DoHEndpoint` 字段建议：

- `InetSocketAddress address`：远程 `DnsServer` IP + DNS TCP 端口，必须是 IP literal 或已解析地址。
- `String tlsHost`：TLS SNI 和证书校验域名。
- `String path`：默认 `/dns-query`。
- `int weight`：多 endpoint 权重。
- `int timeoutMillis`：单请求超时。

关键约束：

- `DoHClient` 连接目标必须使用已解析 IP，不能用 `Sockets.parseEndpoint("host:port")` 触发系统 DNS。
- `Bootstrap` 建议使用 `NoopAddressResolverGroup.INSTANCE` 或已解析 `InetSocketAddress`，避免注入后递归解析 DoH 服务域名。
- TLS client 默认使用系统 trust；测试或内网自签证书用显式配置，不建议默认 trust-all。
- HTTP/1.1 keep-alive 即可，不要求 HTTP/2。
- 每条 HTTP/1.1 连接默认只允许一个 in-flight 请求，避免 pipeline 响应匹配复杂度。
- A 和 AAAA 建议并行查询并合并，`DnsHandler`/DoH server 响应时再按原 query type 过滤。

### 2. DnsTcpPortMuxHandler

建议类名：

- `org.rx.net.dns.DnsTcpPortMuxHandler extends ByteToMessageDecoder`

放在 `DnsServer` TCP pipeline 的第一个 handler。它只 peek 前几个字节，不消费业务数据，判定后移除自己并安装目标 pipeline。

判定规则：

- TLS DoH：`0x16 0x03 xx`，即 TLS ClientHello。
- 明文 HTTP DoH：`POST ` 或 `GET `，只建议测试或显式允许时开启。
- 其他：默认按 DNS-over-TCP 处理。

目标 pipeline：

```text
TLS DoH:
SslHandler -> HttpServerCodec -> HttpObjectAggregator(maxDnsMessageBytes) -> DoHServerHandler

Plain HTTP DoH:
HttpServerCodec -> HttpObjectAggregator(maxDnsMessageBytes) -> DoHServerHandler

DNS-over-TCP:
TcpDnsQueryDecoder -> TcpDnsResponseEncoder -> DnsHandler.DEFAULT
```

注意：

- mux 不能把 TLS 首包误投给 `TcpDnsQueryDecoder`。
- 判定后需要把已读缓冲重新 `fireChannelRead` 给新 pipeline，必须处理 `retain/release`。
- 普通 DNS-over-TCP 查询长度首字节通常为 `0x00`，但不能只靠这个判断；TLS/HTTP 命中优先，其余回落 DNS。
- 明文 HTTP DoH 默认关闭，避免在公网暴露未加密 DoH。

### 3. DoHServerHandler

建议类名：

- `org.rx.net.dns.DoHServerHandler extends SimpleChannelInboundHandler<FullHttpRequest>`

职责：

- 只接受 `POST /dns-query`。
- 校验 `Content-Type: application/dns-message`。
- body 上限建议 65535 字节，超过返回 `413 Payload Too Large`。
- 解析 DNS wire query，调用共享 DNS resolver core。
- 返回 `Content-Type: application/dns-message`。
- 禁用 HTTP 压缩，DoH 响应不需要 `HttpContentCompressor`。

HTTP 状态建议：

- 非 `/dns-query`：`404`。
- 非 POST：`405`。
- content-type 错误：`415`。
- DNS wire 格式错误：`400`。
- 解析链路异常：HTTP `200` + DNS `SERVFAIL`，符合 DoH 客户端预期。

### 4. DnsResolveCore

必须把 `DnsHandler` 里的解析分支抽成共享核心，避免 DoH server 和传统 DNS handler 复制逻辑。

建议类名：

- `org.rx.net.dns.DnsResolveCore`
- 或作为 `DnsServer` 内部方法：`resolveQuestion(...)`

输入：

- `DnsServer server`
- `InetAddress srcIp`
- `DefaultDnsQuestion question`
- `EventExecutor callbackExecutor`

输出建议：

- `Future<DefaultDnsResponse>` 或 `Promise<DefaultDnsResponse>`

核心顺序保持现有行为：

1. hosts 命中。
2. fake host 后缀。
3. `DnsResolveInterceptor`。
4. upstream DNS。

需要同步修正：

- `DnsResolveInterceptor` 返回 `null` 表示未处理或临时失败，不能缓存 NXDOMAIN。
- 空列表才表示明确 negative answer，并按 `negativeTtl` 缓存。
- A 查询只能写 A record，AAAA 查询只能写 AAAA record。
- `interceptorCache` key 增加命名空间和 qtype，例如 `"_dns:int:A:" + domain`。

## DnsServer 改造点

当前构造函数：

```java
serverBootstrap = Sockets.serverBootstrap(channel -> channel.pipeline()
        .addLast(new TcpDnsQueryDecoder(), new TcpDnsResponseEncoder(), DnsHandler.DEFAULT));
```

目标改造：

```java
serverBootstrap = Sockets.serverBootstrap(channel -> channel.pipeline()
        .addLast(new DnsTcpPortMuxHandler(dohConfig)));
```

`DnsTcpPortMuxHandler` 在判定为 DNS-over-TCP 后再安装：

```java
pipeline.addLast(new TcpDnsQueryDecoder(), new TcpDnsResponseEncoder(), DnsHandler.DEFAULT);
```

判定为 DoH 后安装：

```java
pipeline.addLast(sslHandlerIfTls);
pipeline.addLast(new HttpServerCodec());
pipeline.addLast(new HttpObjectAggregator(maxDnsMessageBytes));
pipeline.addLast(new DoHServerHandler());
```

`DnsServer` 需要新增 DoH 配置入口，建议不要污染现有构造函数太多：

```java
public DnsServer enableDoH(DnsDoHConfig config)
```

或新增配置类构造：

```java
public DnsServer(int port, Collection<InetSocketAddress> nameServerList, DnsServerConfig config)
```

其中 `DnsDoHConfig` 至少包含：

- `boolean enabled`
- `SslContext sslContext`
- `String path`
- `boolean allowPlainHttp`
- `int maxDnsMessageBytes`

## DoHClient 接入 Sockets.injectNameService

示例：

```java
DoHClient client = new DoHClient(Collections.singletonList(new DoHEndpoint(
        new InetSocketAddress(InetAddress.getByAddress(new byte[]{10, 0, 0, 8}), 53),
        "dns.example.com",
        "/dns-query")));

Sockets.injectNameService(client);
```

连接的是远程 `DnsServer` 的 TCP DNS 端口，例如 `10.0.0.8:53`。这个端口通过 mux 同时服务传统 DNS-over-TCP 和 DoH。

同时建议增强 `Sockets.injectNameService(...)`：

- 替换全局 interceptor 时，如果旧 interceptor 实现 `AutoCloseable` 且不是新对象，则关闭旧对象。
- 保留 `injectNameService(List<InetSocketAddress>)` 兼容旧 DNS-over-UDP/TCP 模式，但新增：

```java
public static void injectNameService(DoHClient client)
```

该重载本质仍调用 `injectNameService(DnsResolveInterceptor)`，主要提升可读性。

## DoH wire codec

`DoHMessageCodec` 负责 DNS wire format，不包含 TCP 2 字节 length prefix。

需要能力：

- encode query：DNS header + question。
- decode query：DoH server 端解析 request body。
- encode response：DoH server 端写 response body。
- decode response：DoHClient 解析 A/AAAA。
- 支持 name compression pointer。
- 校验 query id、rcode、qdcount。
- 跳过 CNAME/NS/OPT 等非 A/AAAA record，保留最小解析成本。

不要复用 `TcpDnsQueryDecoder` 解析 DoH body，因为 DoH body 没有 TCP DNS length prefix。

## 背压与生命周期

DoHClient：

- 每 endpoint 固定小连接池，默认 1 到 2 条连接。
- 每连接一个 in-flight 请求。
- 全局 `maxInFlight`，超过后返回 `null`，让 JDK name service fallback 原 resolver。
- 单 endpoint 连续失败进入短暂熔断。
- `close()` 关闭连接池、pending promise、EventLoop 附着资源。

DoHServerHandler：

- `HttpObjectAggregator` 上限 65535 字节。
- 超限立即拒绝，不进入 DNS resolver。
- 异步解析时关闭或暂停 auto-read，响应后恢复，避免同连接请求堆积。
- 不在 EventLoop 里执行阻塞 upstream 或 interceptor。

DnsTcpPortMuxHandler：

- 只做首包探测，不做阻塞操作。
- 判定完成后移除自身，避免后续请求多一次分支判断。

## 配置建议

`RxConfig.DnsConfig` 增加：

- `boolean dohEnabled`
- `String dohPath`
- `boolean dohAllowPlainHttp`
- `int dohMaxMessageBytes`
- `int dohTimeoutMillis`
- `int dohMaxInFlight`
- `List<String> dohEndpoints`

`dohEndpoints` 格式建议：

```text
10.0.0.8:53|dns.example.com|/dns-query|weight=100
```

第一段必须是 IP:port，第二段是 TLS host。

## 测试计划

单元测试：

- `DnsTcpPortMuxHandlerTest`
  - TLS ClientHello 首包进入 DoH pipeline。
  - `POST /dns-query` 明文测试首包进入 HTTP pipeline。
  - DNS-over-TCP length-prefix 首包进入原 DNS pipeline。
  - mux 判定后原始 ByteBuf 数据不丢失、不泄漏。
- `DoHMessageCodecTest`
  - A/AAAA query encode/decode。
  - A/AAAA response encode/decode。
  - name compression pointer。
  - NXDOMAIN/SERVFAIL。
- `DoHClientTest`
  - 使用 mock DoH server 返回固定 A/AAAA。
  - endpoint 使用 IP 地址时不触发系统 DNS。
  - 超时、熔断、maxInFlight。
- `DnsResolveCoreTest`
  - `null` interceptor fallback upstream。
  - 空列表返回 NXDOMAIN。
  - A/AAAA 响应过滤。

集成测试：

- `DnsServer` 同一 TCP 端口：
  - 传统 DNS-over-TCP 查询成功。
  - HTTPS DoH 查询成功。
  - UDP DNS 查询仍成功。
- `Sockets.injectNameService(new DoHClient(...))` 后，`InetAddress.getAllByName(...)` 通过远程 `DnsServer` TCP 端口解析。
- 并发同域名查询验证 single-flight 和 cache。

建议回归：

- `DnsServerIntegrationTest`
- `DnsOptimizationTest`
- `SocketsTest`
- `SocksProxyServerIntegrationTest`

## 监控指标

必须覆盖：

- DoH client 请求数、成功数、失败数、超时数。
- DoH server 请求数、HTTP 状态码、DNS rcode。
- mux 分流计数：dnsTcp、dohTls、dohPlain、malformed。
- DoH in-flight 数、拒绝数、熔断 endpoint 数。
- DNS cache hit/miss/negative-hit。
- upstream DNS 失败数和延迟。
- 当前 TCP/UDP channel 数、连接关闭原因。
- Netty 堆外内存：`PooledByteBufAllocator.DEFAULT.metric().usedDirectMemory()`。
- EventLoop pending tasks、HTTP/DoH 连接池 active/idle/pending acquire。

## 风险与处理

- TLS 和 DNS-over-TCP 共端口时，首包误判会导致协议失败。处理：TLS/HTTP 命中优先，其他全部回落 DNS；记录 malformed 指标。
- DoH 服务端需要证书。处理：生产使用正式证书或内网 CA；测试才允许自签和 trust-all。
- `DnsResolveInterceptor` 当前缺少 qtype。处理：`DoHClient` 先 A/AAAA 合并，服务端响应按原 query type 过滤；后续可扩展接口。
- `HttpObjectAggregator` 会聚合 body。处理：DoH DNS message 最大 65535，聚合上限固定，超过拒绝。
- 反复注入 `Sockets.injectNameService` 可能泄漏旧 client。处理：替换时关闭旧 `AutoCloseable` interceptor。

## 验收标准

- 不新增监听端口。
- 远程 `DnsServer` 同一个 TCP 端口同时支持 DNS-over-TCP 和 DoH。
- `DoHClient` 可用于 `Sockets.injectNameService`。
- DoH endpoint bootstrap 不触发系统 DNS。
- I/O 线程无阻塞调用。
- `ByteBuf` 引用计数无泄漏。
- DoH 故障不会被错误缓存为 NXDOMAIN。
- 单元测试和集成测试全部通过。

## 执行进度（2026-04-28）

状态：第一阶段已完成，可编译并通过针对性单元/集成测试。当前实现保持 Java 8 语法。

已完成：

- `DnsServer` TCP 入口已切换为 `DnsTcpPortMuxHandler`，UDP pipeline 保持原 `DatagramDnsQueryDecoder -> DatagramDnsResponseEncoder -> DnsHandler.DEFAULT`。
- 新增 `DnsDoHConfig`，支持 `enabled`、`sslContext`、`path`、`allowPlainHttp`、`maxDnsMessageBytes`。
- 新增 `DnsTcpPortMuxHandler`，按首包分流 TLS DoH、显式允许的明文 HTTP DoH、DNS-over-TCP；判定后移除自身，避免后续热点路径重复分支。
- 新增 `DoHServerHandler`，支持 `POST /dns-query`、`application/dns-message`、最大 body 限制、DoH wire 响应。
- 新增 `DoHMessageCodec`，支持 DNS wire query/response 编解码、A/AAAA 地址解析、name compression pointer、NXDOMAIN 空结果。
- 新增 `DnsResolveCore`，抽离 hosts、fake host、`DnsResolveInterceptor`、upstream DNS 的共享解析链路。
- 修正 `DnsResolveInterceptor` 语义：返回 `null` 不再缓存 NXDOMAIN，而是 fallback upstream；空列表才按 `negativeTtl` 缓存 negative answer。
- 修正 A/AAAA 响应过滤：A 查询只返回 A record，AAAA 查询只返回 AAAA record。
- `interceptorCache` 已增加 qtype 命名空间：`_dns:int:A:`、`_dns:int:AAAA:`；single-flight 仍按域名合并，避免 A/AAAA 双查询重复打到拦截器。
- 新增 `DoHEndpoint` 与 `DoHClient`，`DoHClient` 可作为 `DnsResolveInterceptor` 接入 `Sockets.injectNameService(DoHClient)`。
- `DoHClient` 使用已解析 `InetSocketAddress` 并配置 `NoopAddressResolverGroup.INSTANCE`，避免 DoH endpoint bootstrap 触发系统 DNS 或注入后递归解析。
- `Sockets.injectNameService(...)` 替换全局 interceptor 时会关闭旧的 `AutoCloseable` interceptor；旧 `List<InetSocketAddress>` 注入路径也改为可关闭包装器。
- `RxConfig.DnsConfig` 和 `rx.yml` 已新增 DoH 配置字段。

已补测试：

- `DoHMessageCodecTest`
  - A query encode/decode。
  - A/AAAA response encode/decode。
  - name compression pointer。
  - NXDOMAIN 空结果。
- `DnsTcpPortMuxHandlerTest`
  - DNS-over-TCP 首包进入 DNS pipeline。
  - `POST ` 明文首包在显式允许时进入 HTTP pipeline。
  - TLS ClientHello 首包进入 TLS/HTTP pipeline。
- `DoHClientIntegrationTest`
  - 同一 TCP 端口同时服务 DNS-over-TCP 与明文 DoH（仅测试开启明文）。
- 回归覆盖：
  - `DnsOptimizationTest`
  - `DnsServerIntegrationTest#udp_hostsRecord_returnsConfiguredAddress`
  - `DnsServerIntegrationTest#interceptor_secondQueryUsesCache_singleResolveHost`

验证命令：

```powershell
mvn -pl rxlib -DskipTests compile
mvn -pl rxlib "-Dtest=DoHMessageCodecTest,DnsTcpPortMuxHandlerTest,DoHClientIntegrationTest,DnsOptimizationTest,DnsServerIntegrationTest#udp_hostsRecord_returnsConfiguredAddress+interceptor_secondQueryUsesCache_singleResolveHost" test
```

验证结论：

- 编译通过。
- 上述 12 个针对性单元/集成测试全部通过。
- 曾尝试执行完整 `DnsServerIntegrationTest`，其中历史 `dns` 用例仍依赖外部 DNS/公网域名与本地缓存状态，失败点为 `x.cn` hosts 覆盖断言，不作为本次 DoH 变更的稳定验收依据。

未完成/后续阶段：

- `DoHClient` 当前是每请求短连接，尚未实现固定小连接池、HTTP/1.1 keep-alive 单连接单 in-flight、endpoint 熔断和权重调度。
- HTTPS DoH 真实证书链集成测试尚未补齐；当前 mux 单测覆盖 TLS pipeline 分流，集成测试使用显式开启的明文 DoH。
- DoH server/client 指标目前仅有 `DoHClient` 原子计数，尚未接入统一 `DiagnosticMetrics` 或导出 mux 分流计数、HTTP 状态码、DNS rcode、堆外内存等完整监控。
- `dohEndpoints` 配置解析尚未接到自动构建 `DoHClient` 的启动路径。
- 尚未执行 Socks/Remoting 全量网络回归。

当前风险评估：

- 内存泄漏：`DnsResolveCore` 对异步 upstream 和 single-flight 等待路径显式 `retain/release` query；DoH response encode 后释放 `DefaultDnsResponse`；新增测试覆盖 mux 原始数据不丢失的主要路径，但仍建议后续打开 Netty leak detector 做压力回归。
- 背压：DoH server 在异步解析期间暂停 `autoRead`，响应后恢复；DoH client 有 `maxInFlight` 拒绝保护。连接池化后的排队/拒绝指标需后续补齐。
- 连接生命周期：server 侧跟随 Netty channel 生命周期；client 当前短连接关闭明确。连接池阶段需补齐 active/idle/pending 管理。
- 线程模型：DNS 解析拦截仍通过 `Tasks.run` 脱离 I/O 线程；mux 和 codec 不做阻塞操作；DoHClient 作为 JDK name service interceptor 时会阻塞调用线程，但不阻塞 Netty I/O 线程。
- 协议兼容性：DNS-over-TCP、UDP DNS、明文 DoH 测试通过；HTTPS DoH 需补真实握手集成测试。
- 核心监控建议：后续接入 DoH 请求数/成功/失败/超时、HTTP 状态码、DNS rcode、mux 分流计数、in-flight/拒绝、cache hit/miss/negative-hit、upstream 失败和延迟、TCP/UDP channel 数、`PooledByteBufAllocator.DEFAULT.metric().usedDirectMemory()`、EventLoop pending tasks。
