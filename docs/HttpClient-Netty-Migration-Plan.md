# HttpClient Netty 改造计划

## 模式

- 高性能模式
- Java 8 约束
- 目标：基于 Netty 重建 `HttpClient`，覆盖旧 `HttpClient` 主要能力，移除 `okhttp` 依赖，同时保持低延迟、低额外分配、可控的连接与内存生命周期。

## 进度同步（2026-04-21）

- `[已完成]` 现状盘点
  - 现有 [`HttpClient.java`](/D:/projs_r/rxlib/rxlib/src/main/java/org/rx/net/http/HttpClient.java) 完全基于 `okhttp`。
  - 当前已具备的能力：`GET/HEAD/POST/PUT/PATCH/DELETE`、`json body`、`application/x-www-form-urlencoded`、`multipart/form-data` 文件上传、请求头透传、超时、Cookie、代理、Servlet 转发、响应转字符串/文件/JSON/流。
  - 当前 `HttpClient` 语义是“单实例仅维护一个活动响应”，重复请求会关闭上一次 `ResponseContent`，且类本身标注了 `Not thread safe`。
- `[已完成]` 依赖边界确认
  - 仅新增 Netty 版本还不能马上删除 `okhttp` 依赖。
  - 除 `HttpClient` 外，当前还直接依赖 `okhttp` 的位置包括：
    - [`AuthenticProxy.java`](/D:/projs_r/rxlib/rxlib/src/main/java/org/rx/net/http/AuthenticProxy.java)
    - [`CookieContainer.java`](/D:/projs_r/rxlib/rxlib/src/main/java/org/rx/net/http/CookieContainer.java)
    - [`PersistentCookieStorage.java`](/D:/projs_r/rxlib/rxlib/src/main/java/org/rx/net/http/cookie/PersistentCookieStorage.java)
    - [`VolatileCookieStorage.java`](/D:/projs_r/rxlib/rxlib/src/main/java/org/rx/net/http/cookie/VolatileCookieStorage.java)
    - [`HttpTunnelClient.java`](/D:/projs_r/rxlib/rxlib/src/main/java/org/rx/net/socks/httptunnel/HttpTunnelClient.java)
- `[已完成]` `HttpClient` 核心代码实现
  - 新增 [`HttpClient.java`](/D:/projs_r/rxlib/rxlib/src/main/java/org/rx/net/http/HttpClient.java)。
  - 已实现：Netty `Bootstrap` 复用、`FixedChannelPool` 按目标端点建池、HTTP/HTTPS pipeline、GET/HEAD、JSON/form/multipart body、PUT/PATCH/DELETE body、基础 Cookie、响应 `toString()/toJson()/toFile()/toStream()`、Servlet `forward(...)` 流式转发、HTTP/SOCKS 代理配置。
  - 连接池已使用 `ChannelHealthChecker.ACTIVE`，并补齐 `maxConnectionsPerHost`、`maxPendingAcquires`、pending acquire timeout，避免无上限建连。
  - 响应未使用 `HttpObjectAggregator`，按 `HttpContent` 写入 `HybridStream`；大响应超过阈值后暂停 `autoRead`，把落盘写入切到业务线程，完成后再恢复读取。
  - multipart/stream 上传按写水位、累计字节数和 chunk 数批量 flush，文件读取放到 worker 线程，避免在 Netty I/O 线程执行阻塞文件读，且不再每个 8K chunk 阻塞等待。
  - 同步 API 已增加 EventLoop 线程运行时保护，禁止在共享 TCP Reactor 内阻塞等待响应。
  - Cookie 存储已拆分为内存存储与 H2 持久化存储；H2 版本基于 [`EntityDatabase.java`](/D:/projs_r/rxlib/rxlib/src/main/java/org/rx/io/EntityDatabase.java)，热请求匹配仍走内存快照，H2 只在加载/保存/删除时访问。
  - 已补充 [`HttpClientFeatureTest.java`](/D:/projs_r/rxlib/rxlib/src/test/java/org/rx/net/http/HttpClientFeatureTest.java) 覆盖 GET、JSON、form、multipart、内存 Cookie、H2 Cookie、forward、SOCKS 代理认证成功与失败。
  - 已补充 [`HttpClientIntegrationTest.java`](/D:/projs_r/rxlib/rxlib/src/test/java/org/rx/net/http/HttpClientIntegrationTest.java) 覆盖 GET/HEAD、JSON `POST/PUT/PATCH/DELETE`、form、multipart、Cookie、响应缓存、gzip 解压、keep-alive 复用、请求超时、FixedChannelPool acquire 限流、EventLoop 同步 API 保护。
- `[已完成]` 配置/测试兼容
  - `RxConfig` 当前已有 `net.http.*` 服务端配置结构。
  - `HttpClient` 已接入 Netty `HttpProxyHandler` / `Socks5ProxyHandler`，连接池 key 已按代理类型、地址、用户名隔离。
  - `AuthenticProxy` 已改为纯代理配置对象，旧 `okhttp3.Authenticator` 已清理。
  - 已执行：`mvn -pl rxlib "-Dtest=org.rx.net.http.HttpClientFeatureTest,org.rx.net.http.HttpClientIntegrationTest" test`，结果 20 个测试通过。
- `[已完成]` 旧 `HttpClient` / `HttpTunnelClient` / `CookieContainer` 的 `okhttp` 引用清理
  - [`HttpClient.java`](/D:/projs_r/rxlib/rxlib/src/main/java/org/rx/net/http/HttpClient.java) 已切为 Netty 原生实现，不再持有 `okhttp` 客户端。
  - [`CookieContainer.java`](/D:/projs_r/rxlib/rxlib/src/main/java/org/rx/net/http/CookieContainer.java)、[`PersistentCookieStorage.java`](/D:/projs_r/rxlib/rxlib/src/main/java/org/rx/net/http/cookie/PersistentCookieStorage.java)、[`VolatileCookieStorage.java`](/D:/projs_r/rxlib/rxlib/src/main/java/org/rx/net/http/cookie/VolatileCookieStorage.java) 已切到仓内 Cookie 模型。
  - [`HttpTunnelClient.java`](/D:/projs_r/rxlib/rxlib/src/main/java/org/rx/net/socks/httptunnel/HttpTunnelClient.java) 已切到 `HttpClient` 原始字节上传路径。
- `[已完成]` 调用方切换与 `okhttp` 彻底下线
  - [`RestClient.java`](/D:/projs_r/rxlib/rxlib/src/main/java/org/rx/net/http/RestClient.java)、[`HandlerUtil.java`](/D:/projs_r/rxlib/rxlib/src/main/java/org/springframework/service/HandlerUtil.java)、[`RWebConfig.java`](/D:/projs_r/rxlib/rxlib/src/main/java/org/springframework/service/RWebConfig.java)、[`GeoManager.java`](/D:/projs_r/rxlib/rxlib/src/main/java/org/rx/net/support/GeoManager.java)、[`GeoIPSearcher.java`](/D:/projs_r/rxlib/rxlib/src/main/java/org/rx/net/support/GeoIPSearcher.java)、[`NetEventWait.java`](/D:/projs_r/rxlib/rxlib/src/main/java/org/rx/net/NetEventWait.java)、[`Main.java`](/D:/projs_r/rxlib/rxlib/src/main/java/org/rx/Main.java) 已完成切换。
  - `rxlib/pom.xml` 已删除 `okhttp` 依赖；源码与测试源码已无 `okhttp3.*` / `okio.*` 直接引用。
  - 已追加验证：`mvn -pl rxlib "-Dtest=org.rx.diagnostic.DiagnosticHttpHandlerTest,org.rx.net.http.HttpClientTest,org.rx.net.http.HttpServerBlockingTest,org.rx.net.http.HttpClientFeatureTest,org.rx.net.http.HttpClientIntegrationTest,org.rx.net.support.GeoIPSearcherTest" test` 与 `mvn -pl rxlib "-Dtest=org.rx.net.socks.httptunnel.HttpTunnelTest" test`，结果均通过。

## 1. 背景与目标

当前 `org.rx.net.http.HttpClient` 是 `okhttp` 包装层，已经被以下场景依赖：

- `RestClient` 的 HTTP facade 调用
- `HandlerUtil` / `RWebConfig` 的 Servlet 转发
- SOCKS 相关测试中的表单和 JSON 回归
- 若干内部工具类的简单 GET 请求

本次改造目标不是机械复制旧实现，而是做一个面向 rxlib 的 Netty 原生 HTTP 客户端内核：

- 保留现有功能面，尤其是：
  - `json post`
  - `multi-part` 文件上传
  - 常规 `GET/POST/PUT/PATCH/DELETE`
  - 自定义 Header
  - Cookie
  - 代理
  - 超时
  - 响应转字符串/JSON/文件/流
  - `forward(HttpServletRequest, HttpServletResponse, ...)`
- 去掉关键链路对 `okhttp` 的绑定，后续支持彻底移除 Maven 依赖。
- 避免把阻塞式 DNS、全量响应聚合、临时大对象分配带入热点路径。

## 2. 现状兼容面清单

### 2.1 现有 `HttpClient` 功能面

必须覆盖的旧能力：

- 工具方法
  - `buildUrl`
  - `decodeQueryString`
  - `decodeHeader`
  - `encodeUrl`
  - `decodeUrl`
  - `saveRawCookie`
- 请求能力
  - `head`
  - `get`
  - `post/postJson`
  - `put/putJson`
  - `patch/patchJson`
  - `delete/deleteJson`
  - `multipart/form-data`
  - `application/x-www-form-urlencoded`
  - 请求 Header 注入
  - 超时设置
  - 代理设置
  - Cookie 开关
- 响应能力
  - `responseHeaders`
  - `responseStream`
  - `toString`
  - `toJson`
  - `toFile`
  - `toStream`
- 转发能力
  - Servlet 请求头、QueryString、Body 透传
  - 上游响应头/状态码/Body 回写

### 2.2 兼容策略

用户已经明确说明 Netty 版本与原 `HttpClient` 方法名可以不同，因此兼容分两层：

- 第一层：先把 Netty `HttpClient` 核心能力做完整，接口允许更现代化。
- 第二层：按需要补一个轻量兼容适配器，给 `RestClient`、`forward` 等现有调用点平滑切换。

建议不要继续复制旧类的“单实例仅保留一个活动响应”限制，Netty `HttpClient` 应设计为线程安全的请求执行器；如果需要兼容旧语义，再单独做 adapter。

## 3. `HttpClient` 目标设计

### 3.1 类分层

建议新增以下核心类型：

- `HttpClient`
  - 线程安全
  - 负责发起请求、维护共享连接池和公共配置
- `HttpClientRequest`
  - 方法、URL、Header、Body、超时、代理、Cookie 开关等
- `HttpClientBody`
  - `EmptyBody`
  - `JsonBody`
  - `FormBody`
  - `MultipartBody`
  - `BytesBody`
  - `StreamBody`
- `HttpClientResponseV2`
  - 状态码
  - Header
  - 流式读取
  - `toString()/toJson()/toFile()/toStream()`
- `HttpClientTransport`
  - Netty `Bootstrap`
  - 连接池
  - DNS 解析
  - TLS/代理建连
- `HttpClientCookieJar`
  - 替代 `okhttp3.CookieJar`
  - 保持现有会话 Cookie 和持久 Cookie 语义

### 3.2 Netty 传输模型

建议复用项目现有基础设施，而不是重新堆一套网络栈：

- `Bootstrap` 复用 [`Sockets.java`](/D:/projs_r/rxlib/rxlib/src/main/java/org/rx/net/Sockets.java) 的配置能力
- `EventLoopGroup` 使用共享 TCP Reactor，避免每个客户端自建线程池
- DNS 使用 `Sockets.tcpDnsAddressResolverGroup(...)` 或 [`DnsClient.java`](/D:/projs_r/rxlib/rxlib/src/main/java/org/rx/net/dns/DnsClient.java) 的 Netty DNS 能力，避免 `InetAddress.getByName()`
- `ByteBufAllocator` 使用 `PooledByteBufAllocator.DEFAULT`
- TLS 使用 Netty `SslContextBuilder.forClient()`

### 3.3 Pipeline 建议

基础 pipeline：

- `SslHandler`，仅 HTTPS 时启用
- `HttpClientCodec`
- `HttpContentDecompressor`
- 自定义 `HttpClientInboundHandler`

实现原则：

- 默认不要上 `HttpObjectAggregator`
  - 大响应直接流式处理，避免一次性聚合到内存
- 对小响应只在 `toString()/toJson()` 时懒缓存
- 对下载文件场景直接边读边写到 `HybridStream` 或文件，降低堆内复制

### 3.4 请求体策略

#### `json body`

- 使用 `fastjson2` 序列化为 UTF-8 字节
- 设置 `Content-Type: application/json; charset=UTF-8`
- 小 body 可直接一次写出

#### `form-urlencoded`

- 复用现有 `buildUrl`/URL 编码逻辑
- 直接写 `application/x-www-form-urlencoded`

#### `multipart/form-data`

必须支持：

- 文本字段
- 单文件/多文件
- 文件名与媒体类型
- `DuplexStream` 直接上传

建议分阶段实现：

- 第一阶段：自定义 multipart writer，按 part 逐段写出，避免将整个请求体拼成单个大数组
- 文件 part 优先走流式传输，不把文件完整读入内存
- 在非 TLS 下可评估 `FileRegion` 零拷贝；TLS 场景仍以 chunked stream 为主

## 4. 连接池与生命周期

### 4.1 连接池

`okhttp` 当前给了连接池复用能力，Netty 版本也必须补上，否则吞吐和延迟会明显退化。

建议按以下 key 做连接池：

- `scheme`
- `host`
- `port`
- 代理配置
- TLS 配置

实现状态：

- 已基于 Netty `FixedChannelPool`
- 已支持 keep-alive 复用
- 已支持最大并发建连数、等待队列上限、pending acquire 超时
- 已使用 `ChannelHealthChecker.ACTIVE` 做复用前活性校验
- 空闲连接淘汰当前依赖 Netty 池关闭和服务端 keep-alive 关闭；如后续压测发现长空闲连接堆积，再补主动 idle eviction

### 4.2 生命周期控制

必须明确处理：

- 请求超时
- 连接超时
- 响应读取超时
- 半关闭/远端提前断开
- 连接复用前状态校验
- client close 时池内连接关闭

## 5. 代理、Cookie 与转发

### 5.1 代理

现有 `AuthenticProxy` 依赖 `okhttp3.Authenticator`，这部分需要一起抽离。

建议改造方向：

- 把 `AuthenticProxy` 改为纯配置对象
  - `Proxy.Type`
  - `SocketAddress`
  - `username/password`
  - `directOnFail`
- HTTP/HTTPS 代理使用 Netty `ProxyHandler`
- HTTPS 需覆盖 `CONNECT` 建链与认证场景

### 5.2 Cookie

现有 Cookie 容器直接依赖 `okhttp3.Cookie` / `HttpUrl`，不能继续保留。

建议新增仓库内自有模型：

- `HttpClientCookie`
  - `name/value/domain/path/expiresAt/secure/httpOnly`
- `HttpClientCookieJar`
  - `loadForRequest`
  - `saveFromResponse`
  - `clearSession`
  - `clear`

迁移时保留：

- 过期淘汰
- 持久 Cookie 与会话 Cookie 区分
- 域名/path 匹配

### 5.3 `forward(...)`

`forward` 不能简单复制旧逻辑，需要避免两个问题：

- Servlet 线程把大请求体全部读入内存
- 响应转发时一次性聚合大文件

改造建议：

- 小 body 可沿用字节数组路径
- 大 body 优先使用流式桥接
- 对 multipart 转发直接按 `Part` 流式转上游
- 回写 Servlet 响应时按 chunk 透传，避免堆内聚合

## 6. 分阶段实施

### 阶段 1：能力盘点与接口定稿

交付物：

- `HttpClient` 对外 API 草案
- `HttpClientBody`/`HttpClientResponseV2` 类型设计
- 兼容清单和迁移顺序

验收标准：

- 明确哪些旧能力直接保留
- 明确哪些旧方法由 adapter 承接

### 阶段 2：Netty 传输内核

交付物：

- 共享 `Bootstrap`
- DNS/TLS/连接池
- `GET/HEAD`
- 基础响应读取

验收标准：

- 本地 `HttpServer` 回归通过
- keep-alive 复用生效
- 无 `ByteBuf` 泄漏

### 阶段 3：请求体能力

交付物：

- `postJson`
- `form-urlencoded`
- `PUT/PATCH/DELETE` body
- `multipart/form-data` 文件上传

验收标准：

- JSON 回包正确
- 文本表单与文件上传都能被服务端正确解析
- 大文件上传不出现整文件堆内缓存

### 阶段 4：高级能力

交付物：

- Cookie
- 代理
- `toFile()/toStream()/toJson()`
- `forward(...)`

验收标准：

- Cookie 往返正确
- 代理鉴权通过
- `forward` 可透传 query/header/body/status/header

### 阶段 5：调用方切换（已完成）

当前状态：主干调用方已切到 Netty `HttpClient`，旧 `HttpClient` 的 okhttp 实现已移除。

优先迁移：

- `RestClient`
- `HandlerUtil`
- `RWebConfig`
- 现有测试中直接依赖 `HttpClient` 的场景

验收标准：

- 主要调用方不再依赖 `okhttp` 类型
- 旧功能回归通过

### 阶段 6：彻底移除 `okhttp`（已完成）

当前状态：`okhttp` Maven 依赖与源码直接引用均已删除。

需要同时改造：

- `AuthenticProxy`
- `CookieContainer`
- `PersistentCookieStorage`
- `VolatileCookieStorage`
- `HttpTunnelClient`
- 测试中的 `okhttp` 直接 import

验收标准：

- `pom.xml` 删除 `okhttp` 依赖
- 全仓无 `okhttp3.*` / `okio.*` import

## 7. 风险点与最优处理

### 7.1 内存泄漏风险

重点风险：

- `HttpContent` / `ByteBuf` 未释放
- 上传文件流异常中断后未关闭
- 连接池中失效 Channel 未剔除

处理要求：

- 所有入站 `HttpContent` 在消费后立即释放
- `MultipartBody` 内部持有的 `DuplexStream` 必须成对关闭
- 单测与集成测试开启 Netty leak detection 做回归

### 7.2 背压风险

重点风险：

- 大文件上传时写队列堆积
- 大响应下载时消费方跟不上

处理要求：

- 监听 `Channel.isWritable()`
- 必要时按 chunk 写入并串行推进
- 下载到文件/流时采用边读边写，避免内存累计

### 7.3 连接生命周期风险

重点风险：

- keep-alive 复用到半关闭连接
- 服务端返回 `Connection: close` 但池未感知
- TLS/代理握手失败后 Channel 被错误复用

处理要求：

- 复用前校验 `channel.isActive()` 和 HTTP keep-alive 状态
- 异常连接直接从池移除
- 代理/TLS 握手失败链路必须独立关闭

### 7.4 协议兼容风险

重点风险：

- multipart boundary 拼装错误
- `Content-Length` / `Transfer-Encoding` 处理不一致
- gzip/deflate 自动解压行为与旧版不一致

处理要求：

- 先补集成测试再替换主实现
- 大 body 优先使用 chunked，避免错误预估 `Content-Length`

## 8. 测试与验证计划

本次属于大改动，必须执行“单元测试 + 集成测试”。

### 8.1 单元测试

已覆盖：

- `HttpClientFeatureTest`
  - GET、JSON、form、multipart
  - 内存 Cookie、H2 持久化 Cookie
  - Servlet forward
  - SOCKS 代理认证成功与失败

后续如继续细拆，可把当前覆盖拆成更小的 request builder、multipart、cookie jar、response cache 专项测试类。

### 8.2 集成测试

已覆盖：

- `HttpClientIntegrationTest`
  - GET、HEAD、metrics
  - JSON `POST/PUT/PATCH/DELETE`
  - form、multipart
  - Cookie 往返、响应缓存、`toStream()/responseStream()/toFile()`
  - gzip 响应解压
  - keep-alive 连接复用
  - 请求超时
  - FixedChannelPool 最大连接数与 pending acquire 超时
  - 同步 API 禁止在 EventLoop 调用

待补充：

- 真实 Servlet 容器级 forward 集成测试，当前只用 mock servlet 回归。
- HTTP 代理认证专项测试，当前已覆盖 Netty SOCKS5 代理认证成功与失败。

### 8.3 既有回归

优先回归：

- [`HttpClientTest.java`](/D:/projs_r/rxlib/rxlib/src/test/java/org/rx/net/http/HttpClientTest.java)
- [`HttpServerBlockingTest.java`](/D:/projs_r/rxlib/rxlib/src/test/java/org/rx/net/http/HttpServerBlockingTest.java)
- `TestSocks` 中使用 `HttpClient` 的表单与 JSON 用例
- `DiagnosticHttpHandlerTest`

## 9. 监控指标建议

必须补以下核心指标，至少先暴露进程内计数接口：

- 请求总数、成功数、失败数、超时数
- 按方法和目标主机维度的延迟统计：`p50/p95/p99/max`
- 连接池当前连接数、空闲连接数、等待获取连接数、建连失败数
- in-flight 请求数
- 上传字节数、下载字节数
- gzip 解压后字节数
- 代理握手失败数
- DNS 解析耗时与失败数
- 堆外内存占用
  - `PooledByteBufAllocator` direct arena 使用量
  - Netty direct memory 使用量

## 10. 最终验收标准

达到以下条件，才可以删除 `okhttp` 依赖：

- `HttpClient` 已覆盖当前主要调用场景
- `RestClient`、`forward(...)`、JSON POST、multipart 文件上传都完成切换
- 旧测试回归通过，新测试通过
- 全仓没有 `okhttp3.*` / `okio.*` 直接引用
- 压测下无明显 `ByteBuf` 泄漏、无异常连接堆积、无明显吞吐回退
