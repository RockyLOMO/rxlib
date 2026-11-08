# rxlib HttpServer 源码 Review 与分阶段整改计划

> 审查日期：2026-10-26  
> 仓库：[`RockyLOMO/rxlib`](https://github.com/RockyLOMO/rxlib)  
> 分支 / 固定版本：`master` / `f831f76cde8257d8a33188ab37f315c72c456b43`  
> 范围：`org.rx.net.http.HttpServer`、`ServerRequest`、`ServerResponse`、相关集成测试，以及依赖的 `Sockets` / `Bytes` / Netty 资源管理路径。  
> 性质：**静态源码审查；尚未运行故障复现、单元测试或压力测试。** 建议按每项提供的测试先复现、后修复、再做回归。  
> 原始审查仅生成报告；2026-10-26 已按用户要求实施确认成立的非证书修复，处理结论见第 7 节。

## 1. 总体判断

HttpServer 使用 Netty `HttpServerCodec` + `HttpServerExpectContinueHandler` + `HttpContentCompressor` + `ChunkedWriteHandler`，支持基础 HTTP/1.1、Cookie、表单/Multipart、普通与 offload（`requestAsync`）Handler，架构适合内部微型 HTTP 服务。但需要优先强化：断线/异常资源回收、请求体与慢连接的限额、HTTP keep-alive/pipelining 边界、响应所有权和协议状态处理。没有真实基准测试之前，不应声称性能优于 JDK 或其他成熟 Server。

## 2. 问题清单

| ID | 等级 | 问题 | 位置 | 影响 | 验收测试 |
|---|---|---|---|---|---|
| HS-01 | P0 | 请求中途断开，只 `decoder.cleanFiles()`，未统一 `destroy()`；普通 `req.content` 未释放 | `HttpServer.java:269-275` | Netty ByteBuf / multipart 资源回收不完整 | JSON / form / multipart 分块中途断开，检查 `refCnt`、临时文件、活跃请求状态归零 |
| HS-02 | P0 | 无明确的请求总体、非 multipart 内存、文件/字段、并发上传和读取耗时配额 | `HttpServer.java:101-164,318,362-373`; `Bytes.java:76-82` | 过大的请求造成异常 / 堆、磁盘资源压力；当前普通缓冲最大容量 16 MiB 不是完整 413 机制 | Content-Length 超限、chunked 累计超限、单文件超限、慢速上传一致响应 413/超时并回收 |
| HS-03 | P1 | `requestAsync` 只 `setAutoRead(false)`，不能阻止*已解码、同批送入 pipeline* 的 HTTP pipelined 请求；`ServerHandler.state` 是单槽位 | `HttpServer.java:59-65,84-88,166-183,253-255` | pipelined 请求可能状态覆盖或响应乱序 | 同一 TCP write 连发多个请求，第一条异步延迟，验证请求关联、响应顺序、连接读状态 |
| HS-04 | P1 | `requestMapping` 处理器在 Netty EventLoop 上直接执行；只有 `requestAsync` offload | `HttpServer.java:187-197,397-403` | 阻塞 I/O/DB/睡眠拖累共享 EventLoop 上其他连接 | 一个 handler 睡眠同时其他连接请求，观察 p99、EventLoop 延迟 |
| HS-05 | P1 | `ServerResponse.jsonBody/htmlBody/setContent` 替换旧 ByteBuf 不释放；设置 Location 时生成空响应而舍弃已有 `res.content` | `ServerResponse.java:37-54`; `HttpServer.java:202-217` | 路由组合调用可泄漏 direct ByteBuf；旧响应体所有权不清 | 多次设置 body、body 后 redirect、错误异常时检查 refCnt |
| HS-06 | P1 | `ServerResponse.redirect()` 只设 `Location`；`res.status` 默认 `200 OK`，`ifNull(res.getStatus(), FOUND)` 不会产生 302 | `ServerResponse.java:26-27,52-54`; `HttpServer.java:205-209` | `redirect()` 默认实际返回 200，而不是重定向 | redirect 响应状态码、Location、空响应体断言 |
| HS-07 | P1 | 未统一处理 `HEAD`、1xx、204、304 禁止/省略响应体的语义 | `HttpServer.java:202-249` | 可能发送不应有的正文，Keep-Alive 下导致协议解析问题 | 原始 socket HEAD+GET 串联、204/304 带业务内容的场景 |
| HS-08 | P1 | `decoder.next()` 返回 `InterfaceHttpData` 未显式 release；需核实 `destroy()` 与调用方持有引用的所有权，避免重复或遗漏 | `HttpServer.java:134-153`; `HttpServer.java:263-266` | 正常 multipart 请求也可能有引用计数/磁盘资源泄漏 | 记录 `Attribute/FileUpload.refCnt`，对照 Netty 4.2.12 实现验证 exactly-once 释放 |
| HS-09 | P1 | `Tasks.run()` worker 提交/`ctx.executor().execute()`回投失败时没有回收兜底；`sendResponse` flush/关闭异常路径缺少统一回收闭环 | `HttpServer.java:168-183,232-265` | 服务关闭/负载满/拒绝执行时响应与 decoder 泄漏、连接卡住 | 模拟 executor 拒绝、channel 已关闭、发送失败、服务 stop race |
| HS-10 | P2 | 当前普通请求完整聚合后才处理，返回 `FullHttpResponse`，无真正流式请求/响应回调和端到端背压 | `HttpServer.java:113-197,202-249` | 大请求内存/磁盘开销，下载/转发无法流式处理 | 大文件上传下载时 RSS、堆、Direct、磁盘临时文件及吞吐曲线 |
| HS-11 | P2 | 缺少明确的 read-idle、请求头/体超时、keep-alive idle 和最大连接数等服务端治理 | `HttpServer.java:362-373` | 慢连接长期占用 FD/堆/连接资源 | 不发送完整请求头、间隔慢传、空闲 Keep-Alive 连接群 |
| HS-12 | P2 | 早期 400/404/405 回包仍按请求 Keep-Alive；请求体可能尚未收完 | `HttpServer.java:64-82,227-259` | 提前响应后的剩余 body / pipeline 帧处理需要明确隔离策略 | POST 带 body 访问不存在路由，紧接第二请求，验证行为或主动关闭连接 |
| HS-13 | P2 | 路由注册使用 `normalize()`，请求查表使用 `queryStringDecoder.path()` 原值 | `HttpServer.java:71-74,320-339,393-399` | `/a/` 和 `/a` 路由不一致（若非刻意设计） | 根路径、尾斜线、URL 编码路径、大小写、query 路由测试 |
| HS-14 | P2 | 缺少请求级指标和诊断标签，现有 `DiagnosticMetrics` 主要处于网络 pipeline 层 | `HttpServer.java:367-373` | 难分析路由延迟、活动请求、超限、拒绝、排队、flush 失败 | 指标计数准确、低基数路由归一化、采样开销压测 |
| HS-15 | P2 | TLS 缺少证书配置时自动退化为 localhost 自签证书，且 Server 默认绑定全网卡 | `HttpServer.java:355-375`; `Sockets.java:1073-1094,1909-1912` | 公网部署时易在不知情下运行非预期证书，诊断接口更需显式访问控制 | 非本机部署启动前检查证书 / bind host，明确 dev-only fallback |

### 2.1 对 HS-01/HS-08 的资源所有权补充

Netty 4.2 API 明确区分：`cleanFiles()` 只清除 disk HttpData，`destroy()` 用于销毁 Decoder 并释放全部资源。`InterfaceHttpPostRequestDecoder.next()` 返回的数据亦有释放约定。改动前必须在准确依赖版本 `4.2.12.Final` 上核实 `next()`、`destroy()`、`DefaultHttpDataFactory` 的引用所有权。不要粗暴地同时手工 `release()` 每个对象再 `destroy()`，以免 double-release。

**推荐原则**：每一请求有明确的一名资源 owner，`cleanupOnce()` 原子保护；读入的普通正文、Decoder、已移交的 `FileUpload`、生成的 Response ByteBuf 在明确时点且仅一次释放。异步回调不得在正文释放后继续读取文件或 ByteBuf。

### 2.2 对 HS-03 的处理建议

HTTP/1.1 pipelining 默认建议限制为**每条连接同一时间处理一个完整请求-响应周期**；支持 pipelining 必须队列化并严格按请求顺序写响应，否则考虑检测后明确拒绝/关闭，不应依赖 `setAutoRead(false)` 作为唯一防护。该调用不能撤回同一批数据已经被 HTTP codec 解码并向下游传播的消息。

### 2.3 对 HS-05/HS-06 的处理建议

建议 `redirect(String uri)` 默认设置 `302 FOUND`（或另设 `redirect(uri,status)` 明确 301/302/303/307/308）；调用 `redirect` 前后的既有 body 要按所有权契约释放或选择保留并发送，不能直接丢弃。`ServerResponse` 中新的 body 替换旧 body 时及时 release；调用 `DefaultFullHttpResponse` 构造后所有权才转移给 Netty。测试恶意/意外抛异常时 ByteBuf 回收。

### 2.4 对 HS-07 的处理建议

响应发送前依据请求方法和 HTTP status 确定 `Content-Length` / 实际 body 发送策略：HEAD 无消息体但长度元数据应代表对应 GET（如果可知）；204/304/1xx 严格遵循相关协议规定。避免直接把 `response.content().readableBytes()` 无差别设置为响应长度且写出正文。测试压缩启用时的 Content-Length/Transfer-Encoding 关系，暂不把压缩长度错误当成已确认 Bug。

## 3. 分阶段实施计划（保持 Java 8 兼容；不改其他 Netty 子系统）

### Phase 0 — 基线与复现

- [ ] 新增 `HttpServerLifecycleTest`、`HttpServerProtocolTest`、`HttpServerLimitsTest`、`HttpServerPipeliningTest` 和 `HttpServerBenchmark`，默认功能测试稳定可在 CI 执行，性能测试单独运行。
- [ ] 记录基线：吞吐 RPS、p50/p95/p99、CPU、GC、Heap、Direct、FD、临时文件、EventLoop pending tasks、异常数和流量。
- [ ] 在原实现复现 redirect=200、响应体替换、连接中途断开、超限处理；记录确凿证据再修复。

### Phase 1 — 资源所有权与协议正确性（P0/P1）

- [ ] `RequestState` 加入 exactly-once cleanup 标识；集中管理普通正文 ByteBuf、Multipart Decoder、未移交文件对象。
- [ ] `channelInactive()` / `exceptionCaught()` / `LastHttpContent` / `sendResponse` / rejected task / channel write failure 覆盖一致清理；注意 worker 执行期间与 channel close 的并发竞态。
- [ ] 补 `ServerResponse` buffer ownership；修复 redirect 的默认状态和丢弃旧内容；明确应用层是否允许自带 ByteBuf 转移所有权。
- [ ] 正确处理 HEAD、1xx、204、304；`Content-Length`、Connection、压缩管道、空响应行为回归。
- [ ] 验证 Netty decoder.next() 的 HttpData release 契约，避免 release 两次或遗漏。

### Phase 2 — 请求限流和连接治理（P0/P2）

- [ ] 增加可选 `HttpServerConfig`，保留 `new HttpServer(port,tls)` 原有构造器。
- [ ] 建议初值（**可配置的初始方案，非最佳性能断言**）：
  - `maxRequestBodyBytes`：8 MiB；`maxMultipartBytes`：64 MiB；`maxFileBytes`：32 MiB。
  - `maxFormFields`：128；`maxFormFieldBytes`：64 KiB；`maxUriLength`：4 KiB；`maxHeaderBytes`：8 KiB。
  - 请求头读取超时：10 s；请求体无进展超时：30 s；keep-alive idle：60 s。
  - 这些值须结合现有 `/rdiag`、文件上传和网关调用场景确认后启用；超限应响应 413/414/431 或合适的 4xx，而非 500。
- [ ] 在头部阶段早拒绝超限 `Content-Length`，chunked 流逐块累计、禁止溢出。
- [ ] Multiparts 使用单请求配额、单文件配额、字段上限以及临时磁盘预算；注意 Netty decoder 的 maxFields/maxBufferedBytes 与**实际文件字节限额不是同一概念**。
- [ ] 处理早期拒绝的 Keep-Alive：确定 drain 或 close 策略，防止污染下一个请求。
- [ ] 配置 bound address，明确 loopback / all-interfaces；如部署公网，禁用没有证书时自签 fallback 或明确标记为开发模式。

### Phase 3 — 异步状态机与背压（P1/P2）

- [ ] `state` 单槽方案增加 pipelining 保护；确保多请求按序响应，不让两个 Handler 写出交叉。
- [ ] 同步 Handler 说明只能执行非阻塞逻辑；可能阻塞的路由使用 `requestAsync` 或受限业务 executor；增加最大并发和队列上限，拒绝返回明确 503。
- [ ] 工作线程任务和 EventLoop 回投任务的提交失败均进行回收和连接状态恢复。
- [ ] 若新增流式响应 API，区分 `FullHttpResponse` 与 `HttpResponse + HttpContent + LastHttpContent`，按 `Channel.isWritable()`、write watermark 和 writability changed 做反压；在写完成/连接取消时回收内容资源。
- [ ] 请求流式处理与传统聚合接口并存，默认接口不破坏原来的 ServerRequest/ServerResponse 行为。

### Phase 4 — 指标、回归与性能（P2）

- [ ] `activeConnections`、`activeRequests`、`bodyBytesIn`、`bodyBytesOut`、`requestRejected`、`requestTimedOut`、`multipartTempBytes`、`asyncQueueDepth`、`writeFailures`。
- [ ] 路由维度只用注册模板或静态 route key，**不要使用原始 URL、文件名、IP 作为高基数指标标签**。
- [ ] 区分解析耗时、等待业务线程、业务 Handler 耗时、写回耗时及端到端延迟。
- [ ] 压测矩阵：并发 1/32/128/512；body 1KiB/32KiB/1MiB；HTTP 与 HTTPS；keep-alive 与短连接；同步/异步；拒绝请求/大 body；压缩开关。
- [ ] 至少覆盖中途断线、pipelining、超限、服务关闭 race、文件清理；无需在 CI 强制绝对 RPS 阈值，可用差分 benchmark 观察回归。

## 4. 验收标准

1. 重复 1000 次中断上传后，活动请求数回到零；所有受管理 ByteBuf 的 `refCnt`、Multipart 临时文件均无增长趋势，Netty ResourceLeakDetector 测试模式无泄漏告警。
2. 普通和 multipart 超限可预测地失败，客户端获得定义良好的 HTTP 状态；服务端内存、磁盘受配额约束。
3. keep-alive / HTTP pipelining/async 混合场景不存在响应乱序、状态覆盖和 data race。
4. `redirect()` 返回正确状态码，HEAD/204/304 不发送非法正文；ByteBuf 替换与响应发送失败不泄漏。
5. 同步与异步 API、已有路由、JSON/form/multipart、Cookie、压缩、TLS 测试均通过。
6. Java 8 source/target 兼容，保留现有 `HttpServer(int,boolean)` 入口，不修改与 HttpServer 无关的核心协议。
7. 交付每阶段前后性能/资源数据，不把未测的优化收益当成事实。

## 5. 给 Claude CLI 的实施指令

> 在 `RockyLOMO/rxlib` 的最新工作分支中，按 `docs/rxlib-httpserver-review-20261026.md` Review 清单依次实施，**先复现缺陷并添加失败测试，再逐项最小范围修改**。目标仅限 `HttpServer`/`ServerRequest`/`ServerResponse` 与必要配置、测试。保持 Java 8、已有构造器及公共 API 兼容，不修改其他独立网络模块。重点确保 Netty ByteBuf/HttpData/Decoder 在正常完成、取消、断线、timeout、executor 拒绝和服务关闭时只有一个 owner 且释放一次；严格验证 `HttpPostRequestDecoder.next()` 与 `destroy()` 释放责任。把性能改造与协议修复分提交，执行 JUnit + Maven 测试，最后提供基线与优化后指标，不虚构测试结果。

## 6. 核心源码链接

- [HttpServer.java](https://github.com/RockyLOMO/rxlib/blob/f831f76cde8257d8a33188ab37f315c72c456b43/rxlib/src/main/java/org/rx/net/http/HttpServer.java)
- [ServerRequest.java](https://github.com/RockyLOMO/rxlib/blob/f831f76cde8257d8a33188ab37f315c72c456b43/rxlib/src/main/java/org/rx/net/http/ServerRequest.java)
- [ServerResponse.java](https://github.com/RockyLOMO/rxlib/blob/f831f76cde8257d8a33188ab37f315c72c456b43/rxlib/src/main/java/org/rx/net/http/ServerResponse.java)
- [HttpServerBlockingTest.java](https://github.com/RockyLOMO/rxlib/blob/f831f76cde8257d8a33188ab37f315c72c456b43/rxlib/src/test/java/org/rx/net/http/HttpServerBlockingTest.java)
- [Sockets.java](https://github.com/RockyLOMO/rxlib/blob/f831f76cde8257d8a33188ab37f315c72c456b43/rxlib/src/main/java/org/rx/net/Sockets.java)
- [Netty 4.2 HttpPostMultipartRequestDecoder API](https://netty.io/4.2/api/io/netty/handler/codec/http/multipart/HttpPostMultipartRequestDecoder.html)
- [Netty 4.2 HttpPostRequestDecoder 源码](https://netty.io/4.2/xref/io/netty/handler/codec/http/multipart/HttpPostRequestDecoder.html)

## 7. 2026-10-26 实施结论

按当前 `master` 核实后实施下表，证书策略按用户要求排除。验证细节见 [HttpServer 修复验证记录](httpserver-fixes-validation-20261026.md)。

| 审查项 | 处理 |
| --- | --- |
| HS-01 | 统一原子 cleanup，覆盖普通正文、完整/部分 decoder、断线、异常和任务拒绝；运行中 worker 返回前不释放，排队但未开始任务可取消其资源所有权。 |
| HS-02 | 新增 HttpServerConfig：普通/MultiPart/文件/字段/URI/header 限额，以及上传并发限制；头部与累计正文分别检查，明确返回 4xx。 |
| HS-03 | 状态保留至 flush 完成；重叠 pipelining 主动关闭，禁止覆盖原请求。顺序 Keep-Alive 继续支持。 |
| HS-04 | 同步 EventLoop handler 是原 API 契约，不全局更改线程。明确非阻塞要求；requestAsync 默认独立有界执行器，允许注入及时返回的调用方执行器。 |
| HS-05/06 | 正文替换、handler 抛错、redirect、失败写入均有明确 owner；redirect 默认 302 并清理原正文。手工 Location header 不再使既有正文被无声丢弃。 |
| HS-07 | HEAD（包括错误响应）无正文，保留表示长度；204/205/304 不发送正文，合法 CL/TE 处理；不允许把应用 1xx 当最终响应，框架仍支持 100 Continue。 |
| HS-08 | 精确核对本地 4.2.12.Final sources：next 不 retain，destroy 清理 factory 与完整列表。完成项从 factory 清理别名移出，保留 decoder 列表的单一 owner；partial 保留 factory 管理。禁止盲目逐个 next().release()；显式业务 retain 由业务最终 release。 |
| HS-09 | worker submit、EventLoop 回投、write failure、inactive 与 stop 都有清理兜底；ChannelGroup stayClosed 防止停服后迟到 child 存活，计数先登记再入 group 避免同步关闭竞态。 |
| HS-10 | 这是新功能与架构扩展，不能把聚合接口本身认定为缺陷；本轮不新增流式 Server API，现接口由配额提供资源边界。 |
| HS-11 | 请求头总读取期限、正文无进展期限、keep-alive idle、最大连接数；正文进展只更新时间戳，不逐 chunk 新建定时任务。 |
| HS-12 | 提前拒绝后关闭连接，避免剩余 body 污染后续请求；头部通过路由/限额/格式校验后才发送 100 Continue。 |
| HS-13 | 查表与注册使用同一 normalize；保留大小写敏感，覆盖解码路径/query/root/尾斜线。 |
| HS-14 | 增加实例级活动请求/连接、异步/上传、收发正文、拒绝/超时/写失败、累计/最大延迟；Direct Memory 明确为 JVM 全局。分位数、阶段直方图和完整压测不在本次缺陷修复中宣称完成。 |
| HS-15 | 证书相关按用户要求不修改；默认 bind 行为保留，配置可显式给出 bindAddress。 |

本轮不是性能对照基准，未提供未测的 RPS、p99、Heap/GC 改善数字。完整流式 API 和性能矩阵仍属后续功能工作。
