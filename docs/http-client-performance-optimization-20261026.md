# rxlib HttpClient 性能与流式 I/O 优化计划

> **状态**：P1 核心代码与定向回归已落地；完整性能基准、P2 仍待实施（见文末实施记录）  
> **日期**：2026-10-26  
> **仓库**：`RockyLOMO/rxlib`，`master`  
> **审阅基线**：`f831f76cde8257d8a33188ab37f315c72c456b43`  
> **文档位置**：`docs/http-client-performance-optimization-20261026.md`  
> **目标环境**：现有 `rxlib` 生产代码继续以 Java 8 编译（`pom.xml` 中 `<java.version>1.8</java.version>`）；单独使用 JDK 21 执行对照基准。

## 0. 目标与实施原则

针对 `org.rx.net.http.HttpClient` 的以下六项优化，**优先保证现有 API 与行为兼容、连接池安全、引用计数正确，再追求吞吐量提升**：

| ID | 优先级 | 工作项 | 预期收益 |
| --- | --- | --- | --- |
| HC-01 | P1 | 响应缓冲按需分配，取消默认 16 MiB 初始分配 | 降低堆/GC 开销与小响应延迟 |
| HC-02 | P1 | Multipart 大文件真正流式上传 | 消除整文件缓冲及巨大内存峰值 |
| HC-03 | P1 | 上传块从 256 B 调整为独立可配置的合理大小 | 降低分配、write/flush 次数，提高吞吐 |
| HC-04 | P2 | 只有必要时才触发 Cookie 相关异步处理 | 减少非必要线程切换，优化小请求尾延迟 |
| HC-05 | P2 | 新增 BodyConsumer/流式响应 API | 允许响应到达时增量消费，避免临时文件中转 |
| HC-06 | P2 | 连接池/TLS/内存指标与性能回归基准 | 可定位瓶颈、验证性能优化和回归 |

### 必须遵守的约束

- 不改核心定位：仍以 Netty `HttpClientCodec` + `FixedChannelPool` 为 HTTP/1.1 客户端；不在本计划内重写 HTTP/2。
- 不删除或修改现有 `get/postJson/execute/executeAsync/Response.bodyAsString/bodyStream/bodyAsFile` 的外部语义；`Response` 继续可重复读取并由调用方关闭。
- 同步 API 不允许阻塞 Netty EventLoop；网络 I/O、慢磁盘写入、回调用户代码需要有清晰的线程边界。
- 保留现有 Cookie、代理、Redirect、GZIP、磁盘缓存、超时、取消、连接复用行为（如新流式 API 有意不支持某个特性，必须显式文档化）。
- Netty `ByteBuf` 保证 **retain/release 恰好一次**；对错误、取消、重定向、超时等分支统一检查资源回收。
- 仅增加经验证的配置/API，默认值需要文档、测试与基准共同支撑。
- **安全依赖提醒（不属于上述六项）**：现有 `HttpClientConfig.defaultSslContext()` 使用 `InsecureTrustManagerFactory`、`verifyHostname` 默认关闭；必须作为单独 P0 跟踪，不能把性能测试的 TLS 行为等同于安全的生产设置。

## 1. 已确认的当前实现与修改点

| 位置 | 当前行为 | 具体风险 |
| --- | --- | --- |
| `HttpClient.java` `ClientInboundHandler.newResponseStream()` | 默认 `responseOffloadThreshold = 16 MiB`；构造 `new HybridStream(memoryLimit, false)` | 小响应也使用上限作为初始容量 |
| `HybridStream.java` 构造函数 | `new MemoryStream(this.maxMemorySize, directMemory)` | 使用 16 MiB 初始化内存缓冲，而非按需扩容 |
| `MemoryStream.java` 构造函数 | `Bytes.heapBuffer(initialCapacity)` | Netty 池化不能消除大初始容量占用 |
| `HttpClient.java` `MultipartContent.streaming()` | `totalLength < 0` 才流式 | 大多数可知文件长度的 Multipart 会整体编码到 `ByteBuf` |
| `MultipartContent.toFullContent()` | 分配接近请求体全长的 `ByteBuf`，并拷贝文件 | 大文件峰值高；`int` 大小边界需要防护 |
| `StreamContent.writeStreaming()` / `MultipartContent.writeStream()` | `Constants.HEAP_BUF_SIZE = 256` B | chunk 过细，过多分配及 write/flush |
| `UploadWriter.flush()` | flush 后同步 `awaitWrite()` | 小 flush 导致 worker 等待频繁 |
| `ClientInboundHandler.complete()` | `cookieEnabled` 即 `Tasks.run(completeFuture)` | 默认 CookieJar 打开时，无 Set-Cookie 也触发调度 |
| `RequestState.appendResponseContent()` | 响应存储经 `Tasks.executor()`，必要时关闭 `autoRead` | 已有磁盘背压基础，可复用到增量流消费 |
| `HttpClient.Metrics` | 请求量/传输字节/延迟/全局 Direct Memory | 尚无法直接判断连接池等待、TLS 握手等瓶颈 |

**源码基线**：

- [`HttpClient.java`](https://github.com/RockyLOMO/rxlib/blob/f831f76cde8257d8a33188ab37f315c72c456b43/rxlib/src/main/java/org/rx/net/http/HttpClient.java)
- [`HttpClientConfig.java`](https://github.com/RockyLOMO/rxlib/blob/f831f76cde8257d8a33188ab37f315c72c456b43/rxlib/src/main/java/org/rx/net/http/HttpClientConfig.java)
- [`HybridStream.java`](https://github.com/RockyLOMO/rxlib/blob/f831f76cde8257d8a33188ab37f315c72c456b43/rxlib/src/main/java/org/rx/io/HybridStream.java)
- [`MemoryStream.java`](https://github.com/RockyLOMO/rxlib/blob/f831f76cde8257d8a33188ab37f315c72c456b43/rxlib/src/main/java/org/rx/io/MemoryStream.java)
- [`HttpClientIntegrationTest.java`](https://github.com/RockyLOMO/rxlib/blob/f831f76cde8257d8a33188ab37f315c72c456b43/rxlib/src/test/java/org/rx/net/http/HttpClientIntegrationTest.java)

## 2. Phase 0：建立正确性基线与基准框架（先做）

1. 运行原有 `HttpClientTest`、`HttpClientIntegrationTest`，记录通过/失败项以及测试时的配置。
2. 把 HTTP/1.1 的轻量级本机服务端与重复调用脚本准备好；隔离客户端与服务端 CPU 时更能准确测出客户端开销。
3. 在未改代码前记录：1 KiB / 32 KiB / 1 MiB 响应，1 / 32 / 128 / 512 并发的 RPS、p50/p95/p99、CPU、Heap、GC、Direct Memory（全局）、错误率；另外测 100 MiB 上传、1 GiB 下载。
4. HTTP/1.1 对照双方均关闭日志/Cookie/缓存，统一长连接、压缩、连接限制及 TLS 配置；客户端实例复用，不能每请求 new Client。
5. 基准稳定性至少采用多轮预热与多次重复测量，并保存机器型号、OS、JDK、Netty 版本与测试参数；避免单次本机跑分作为结论。
6. **注意**：当前父 POM 仍以 Java 8 编译。与 `java.net.http.HttpClient` 的 JDK 21 对照代码放到独立 `benchmarks/httpclient-jdk21/`（独立 POM/构建入口），不要直接写进 Java 8 主模块。

**完成标准**：所有原有测试情况已记录；有独立可重复的 baseline 结果模板与可运行的基准入口。性能测试不应默认进入每次 CI 单测，以免受共享执行器噪声影响。

## 3. Phase 1 / HC-01：响应缓冲按需分配（P1）

### 设计

- 区分 `initialCapacity`（初始容量）与 `maxMemorySize`（内存保留上限）；不得再次把阈值直接传给内存缓冲构造函数。
- 在 `HybridStream` 增加兼容性构造重载，例如：

```java
// 伪接口；具体重载签名由实现时与旧构造函数一并审查
HybridStream(int maxMemorySize, boolean directMemory,
             String tempFilePath, int initialCapacity)
```

- 保留现有构造函数行为以降低对 `rxlib` 其他使用者的影响；**仅 HTTP Client 响应路径优先改用新重载**。如要全局改变默认行为，单独提交并扩展覆盖所有 `HybridStream` 用例。
- `HttpClientConfig` 建议增加 `responseInitialBufferBytes`，初始默认 **4 KiB**；继续保留 `responseOffloadThreshold` 默认 **16 MiB**。对非法配置和阈值倒挂做校验/归一化。
- 在 `Content-Length` 已知且大于阈值时，继续直接建立 file-backed `HybridStream`；小响应缓冲按需扩容，越过阈值继续沿用现有内存/文件混合策略。
- 保持现有 `HybridStream` 的 position、rewind、length、close/delete、内存与文件边界读写语义；不为此次优化修改数据语义。
- 不要把 Netty 全局 Direct Memory 指标当成该响应内存的精确占用；此处主要使用 Heap ByteBuf。

### 验证

- `0 B / 1 B / 1 KiB / 32 KiB / 阈值-1 / 阈值 / 阈值+1`；有无 `Content-Length`、chunked 均覆盖。
- 同一响应 `bodyAsString()` 多次、`bodyStream()` rewind、`bodyAsFile()` 和 `close()` 行为不变。
- 使用测试可见的初始 `ByteBuf.capacity()` 或受控分配器验证 **1 KiB 响应不再默认申请 16 MiB**；不要只看最终 GC 后的 Heap。
- 大数据切文件路径输出字节校验一致，临时文件关闭后无泄漏。

**验收**：小响应按 4 KiB 左右初始容量分配、按需增长；响应超过阈值后保持正确的文件承载与背压；内存峰值相较原版明显降低，正确性回归通过。

## 4. Phase 2 / HC-02 + HC-03：Multipart 流式上传及合理分块（P1）

### 4.1 不再由“是否知道文件长度”决定是否整包缓冲

- `MultipartContent.computeTotalLength()` 只负责**计算报文长度**；与 **缓冲/流式传输选择**分离。
- 默认 **Multipart 带文件就使用流式上传**；仅无文件且报文很小时可继续缓冲（如果选择加入小表单优化，要在测试中比较收益）。
- 为 `RequestContent` 增加向后兼容的 Java 8 `default long contentLength() { return -1L; }`，或采用等效内部抽象：
  - 已知长度：流式传输仍可设置 `Content-Length`，发送 `LastHttpContent`；
  - 未知长度：使用 `Transfer-Encoding: chunked`；
  - 已知长度的字节数不得强制转 `int` 并试图一次性申请完整 `ByteBuf`。
- 精确计算 multipart boundary、Header、CRLF 与 UTF-8 编码后的字节长度；超长加法使用溢出防护；文件变更导致长度不匹配需明确报错并关闭连接，避免协议串包。
- 保留 form、文件字段名、filename 转义、文件关闭所有权和原有 wire format；服务端应按一致字节流校验 body。
- `toFullContent()` 可保留用于明确的小请求，但不能成为大文件的默认路径。

### 4.2 分块与 flush 控制

建议新增独立配置（初值是**待基准验证的候选值**）：

| 配置 | 初步默认值 | 用途 |
| --- | ---: | --- |
| `uploadChunkBytes` | 16 KiB | 文件/流的单次读取和写入大小，替换 256 B |
| `uploadFlushBytes` | 128 KiB | 达到写入字节阈值则 flush |
| `uploadFlushChunks` | 8 | 达到块数阈值则 flush |
| `responseInitialBufferBytes` | 4 KiB | Phase 1 的响应初始容量 |
| `responseOffloadThreshold` | 16 MiB（现有） | 响应写临时文件阈值，**不是初始容量** |

- 需要将 `uploadChunkBytes` 正确透传到 `RequestState`、`StreamContent`、`MultipartContent.writeStream()`；不要改变全局 `Constants.HEAP_BUF_SIZE`，它有其他使用者。
- 独立 `channel.alloc().ioBuffer(uploadChunkBytes, uploadChunkBytes)`，并在所有 EOF、写入失败、取消分支释放临时 `ByteBuf`。
- 继续用业务/worker 线程读取文件；严禁 EventLoop 阻塞读取。优化写水位/批量 flush，避免每 4 KiB 就同步等待一次；必要时进一步做异步写完成背压，但**不能无限制排队 ByteBuf**。
- 遵循 `channel.isWritable()`、请求取消、总请求超时、写超时；限制在途写缓冲总量，取消后不再继续读文件。
- 设置 Config 时用范围检查、复制构造完整传递新字段；原有 `setUploadFlushBytes/Chunks` 继续可用。

### 验证

- 已知长度、未知长度、零字节、多个文件、多表单字段、非 ASCII 文件名、异常输入流、请求中止与取消。
- `1 KiB / 64 KiB / 1 MiB / 100 MiB / 1 GiB` 上传，按流重算 digest 比对，不仅检查返回码。
- 已知长度的 Multipart 在抓包/服务端确认 `Content-Length` 精确；未知长度正确使用 chunked。
- 限速服务端模拟背压；确认待发送数据量有界、取消能尽快停止读取、连接失败时池不会复用污染通道。
- 压测比较 256 B / 4 KiB / 16 KiB / 32 KiB / 64 KiB 的吞吐、CPU 与 p99，选最稳健默认值。

**验收**：大文件不再需要等量堆外请求缓冲；可复用连接、无 `ByteBuf` 泄漏、无 body 字节数差异；上传吞吐和 CPU 数据可追溯。

## 5. Phase 3 / HC-04：Cookie 与完成回调调度优化（P2）

### 当前路径

`ClientInboundHandler.complete()` 只要 `state.cookieEnabled` 即 `Tasks.run(() -> completeFuture(state))`；但 `completeFuture()` 执行的 Cookie 保存并非每次都必要。注意它还可能处理 **磁盘缓存、重定向、日志、CompletableFuture.complete()**。

### 设计

- 将“是否需写 Cookie”判定改为**响应有 `Set-Cookie` 且 CookieJar 已启用**；请求加载 Cookie 与响应写 Cookie 是不同动作。
- 不能简单把整个 `completeFuture()` 在无 `Set-Cookie` 时直接放回 EventLoop：磁盘缓存可能阻塞，`CompletableFuture.complete()` 会执行用户注册的同步依赖回调（`thenApply/whenComplete` 等），也可能阻塞。
- 把处理分层：
  1. EventLoop 上完成轻量状态更新、释放/回收 channel；
  2. 按需将持久化 Cookie、磁盘缓存等阻塞任务交给 worker；
  3. 通过统一 completion executor 交付用户 Future，避免未知耗时用户回调运行在 Netty I/O 线程。
- 合并同一响应的 Cookie + Cache + 完成回调任务，避免重复调度；若已在正确的 worker 上，不要重复切线程。可为处理阶段添加计数，证明相比现版减少了哪些额外任务。
- 不改变 `HttpClientCookieJar` 的 SameSite/过期/持久化规则；并发登录、多个 `Set-Cookie` 的顺序一致性要单独测试。

### 验证

- 不启用 Cookie / CookieJar 启用但无 Set-Cookie / 内存 Cookie / H2 持久化 Cookie。
- 开/关磁盘缓存、命中/未命中、302 重定向、异常/取消。
- 在完成回调中模拟慢处理，确认不会拖慢共享 Netty EventLoop 的其他连接。
- 对比每请求异步提交数、p50/p99、CPU；不能只用 `Tasks.run()` 数量判断性能。

**验收**：无 Cookie 写入的请求不再仅因为 CookieJar 非空触发 Cookie 专用 offload；EventLoop 不承担磁盘操作或任意用户回调；完成/失败恰好一次。

## 6. Phase 4 / HC-05：新增 BodyConsumer / 真正流式响应 API（P2）

### API 方案（建议，不直接替换原有 Response）

增加单独的流式请求入口，旧 `executeAsync(Request)` 仍维持**完整响应结束后交付可回读的 `Response`**。新接口采用响应头先到达、数据分块消费、完成后结束的模型，基于 Java 8 `CompletionStage` 支持异步背压。

```java
// 示意 API，具体类型名与异常处理在实现阶段统一
public interface BodyConsumer {
    void onHeaders(ResponseHead head) throws Exception;
    CompletionStage<Void> onChunk(ByteBuf chunk);
    default void onComplete() throws Exception {}
    default void onError(Throwable error) {}
}

// Future 只有在完整传输/消费并执行必要的完成动作后才完成
CompletableFuture<StreamResult> executeAsync(Request request, BodyConsumer consumer);
```

- `ResponseHead` 建议是 **状态码 + 已拷贝/不可变 Header + 最终 URL** 的轻量 DTO（Java 8 普通类，不使用 Java record）。
- `ByteBuf` 的所有权：框架在交给 consumer 前 `retain` 所需切片；consumer 回调的 `CompletionStage` 完成后框架 `release`；业务如需异步持有必须遵循 API 约定（若暴露 ByteBuf 太危险，可提供只读视图/复制接口作安全备选）。同一请求的 `onChunk()` **串行且有序**。
- 消费未完成时 `autoRead(false)`；完成后在对应 channel 的 EventLoop 上恢复 `autoRead`，以形成**端到端背压**；严禁无限队列和忙等。
- `onHeaders/onChunk/onComplete/onError` 用户逻辑不能未经约束运行在 I/O EventLoop；明确 worker 执行策略与线程池隔离。
- 响应数据不经 `HybridStream`；可给内置 consumer：`toFile(Path)`（Java 8 支持）、`discard()`、`toOutputStream(OutputStream)`。大文件下载可直接指定最终文件并在失败时执行明确的残留文件策略。
- 只有完整消费成功且对端响应合法，才把 HTTP/1.1 channel 返回连接池；消费者失败、取消、协议未完成等情况应关闭该连接，不能复用未读完的 body。
- 新模式先**明确不走现有 HTTP 响应磁盘缓存**（显式绕过并文档说明）；后续若要缓存，使用受背压控制的 tee-consumer。不得出现“用户得到流式结果后还在后台偷偷完整缓存 body”的行为。
- 自动解压、cookie、redirect、timeout、GZIP、代理的处理边界要延续现有请求语义；对 redirect 应只将最终响应发送到 consumer（中间响应正确排空/关闭），不要混合多个响应的内容。
- 完成、失败、取消只能触发一次终态；包括消费者抛异常、Future.cancel、网络断开、响应过大、慢消费、服务端提前关闭、`onHeaders` 抛异常等。

### 必测用例

- 服务端缓慢 chunked 发送：首 chunk 在整个 body 结束前就被 consumer 收到（证明是真流式）。
- 1 GiB 下载：主进程 Heap 不随文件大小等比例增长（与任务并发/池保留容量相关）；结果 SHA-256 正确。
- 故意让 consumer 每块休眠/异步阻塞，确认暂停读取、在途 chunk 有界、连接池不会提前归还。
- 处理无 Content-Length、零字节、GZIP、重定向、TLS、异常/中断/取消、连接复用。
- 多并发长下载时，既不会阻塞其他连接的 EventLoop，也不会耗尽 worker/Direct Memory；检查文件描述符与临时文件是否清理。

**验收**：可边到边消费；大响应无需默认落盘；正确限流和资源回收；旧 API 完全不受影响。

## 7. Phase 5 / HC-06：指标与回归基准（P2）

### 7.1 指标设计

优先在现有 `HttpClient.Metrics` 与诊断体系上扩展，不在热点路径引入字符串格式化、堆栈捕获或高基数 URL 标签。

| 类别 | 建议指标 | 注意 |
| --- | --- | --- |
| 请求 | started / succeeded / failed / cancelled / timeout；inFlight | 分清网络失败与消费者失败 |
| 连接池 | per-host active / idle / pendingAcquire；acquireWaitNs；newConnection / reusedConnection | 若 `FixedChannelPool` 无公有精确统计，则通过生命周期事件维护，不要猜内部字段 |
| TLS | handshakeSuccess / handshakeFailure；handshakeDurationNs | 从 `SslHandshakeCompletionEvent` / handshake Future 采集；避免泄露站点信息 |
| 内存 | responseBufferInitialBytes、activeResponseHeapBufferBytes（估计）、bufferOffloadCount、activeStreamChunks、pendingUploadBytes | 对 global Netty allocator 指标明确标注为 JVM/global，不冒充 HttpClient 实例独占值 |
| I/O | upload/download bytes、writeFlushCount、uploadChunkCount、responseChunkCount | 尽量使用计数器，按需采样 |
| 结果延迟 | total/request/connectionAcquire/TLS/bodyConsume 等阶段 p50/p95/p99（在基准程序中） | 核心业务路径可只记录轻量总和/最大值，直方图由外部工具完成 |

- 使用与当前 `DiagnosticMetrics` 相容的机制；高并发下衡量原子更新成本。
- `Metrics` 应在需要时初始化（保留当前 lazy 特性），不能为了采样每请求大量分配对象。
- `p95/p99` 不允许直接拿累积总耗时和请求数计算；通过 HdrHistogram/JMH/外部压测报表统计。
- 对每个 pool 的关闭与移除、客户端关闭后 metrics 清理做测试，防止标签或计数器无限增长。

### 7.2 基准用例矩阵

- **响应**：1 KiB、32 KiB、1 MiB；并发 1、32、128、512；HTTP/1.1 Keep-Alive。分别比较 rxlib-before / rxlib-after / JDK 21。
- **上传**：16 KiB、1 MiB、100 MiB、1 GiB；已知长度和 chunked 分开统计；校验服务端 digest。
- **下载**：32 KiB、1 MiB、1 GiB；rxlib 旧 buffer API、rxlib 新 stream API、JDK 21 `BodyHandlers.ofByteArray/ofFile` 分别按**相同消费语义**比较。
- **TLS**：HTTP/1.1 HTTPS 冷握手与复用连接分开；统一证书校验配置，避免用“不验证 TLS”的 rxlib 去对比正常校验的 JDK 21。
- **连接池压力**：每 Host 1、8、32、128 连接；短请求、慢响应、pendingAcquire 超时、目标主动断开。
- **Cookie**：禁用 Cookie、启用但无 `Set-Cookie`、响应包含 `Set-Cookie`、磁盘持久化。
- **异常**：取消、超时、断开、恶意/异常长度头、限速响应、内存压力。

报告字段：基线 commit、变更 commit、CPU/OS/JDK、协议、server/client 线程数、连接与并发限制、warmup、测量轮次、RPS、p50/p95/p99、CPU%、Heap Peak、allocation/op、GC count/time、Netty Direct Memory、临时文件峰值、FD 数、错误率。每一对性能数字应带环境和置信度信息。

**验收**：现有单测 + 新资源生命周期回归全部通过；基准可重复、结果留档；如果某一场景性能倒退，先定位影响，再决定保留/回滚。性能不设无依据的“必须提升 X%”硬指标。

## 8. 建议的文件改动范围

| 文件 | 预期变更 |
| --- | --- |
| `rxlib/src/main/java/org/rx/net/http/HttpClient.java` | 响应创建、Multipart 流式发送、chunk 大小、调度优化、流式 API、指标埋点 |
| `rxlib/src/main/java/org/rx/net/http/HttpClientConfig.java` | 新配置字段、默认值、复制构造及校验 |
| `rxlib/src/main/java/org/rx/io/HybridStream.java` | 新增支持独立初始容量的构造重载（尽量不改原语义） |
| `rxlib/src/main/java/org/rx/io/MemoryStream.java` | 必要时为测试与缓冲初始化提供最小兼容支持（能复用现有构造则不改） |
| `rxlib/src/main/java/org/rx/net/http/HttpClientCookieJar.java` | 仅在确实需要时增加是否为持久化操作的能力接口（非必改） |
| `rxlib/src/main/java/org/rx/net/http/HttpClientCache.java` | 如需要明确缓存绕过与安全的后台执行规则，做最小改动 |
| `rxlib/src/test/java/org/rx/net/http/HttpClientIntegrationTest.java` | 扩展功能测试；原测试必须保持通过 |
| `rxlib/src/test/java/org/rx/net/http/HttpClientStreamingTest.java`（新增） | 流式响应、背压、取消、引用计数、生命周期 |
| `rxlib/src/test/java/org/rx/net/http/HttpClientMemoryTest.java`（新增） | 缓冲初始容量/阈值切换/内存峰值相关测试 |
| `benchmarks/httpclient-jdk21/`（新增，独立构建） | rxlib vs JDK 21 公平对照负载、结果 CSV/Markdown 模板 |
| `docs/reference/net/http.md` | 新接口、线程约束、缓存与流式语义、参数示例 |

**建议拆分提交**：`baseline/bench` → `response-allocation` → `multipart-streaming` → `upload-chunks` → `cookie-completion` → `response-consumer` → `metrics` → `regression-report`。每次提交需要与改动范围匹配的测试，避免一个大 PR 同时改所有流程。

## 9. 实施清单（适合交给 AI Agent 逐项执行）

### P1

- [x] HC-01a 新增 `responseInitialBufferBytes`（或等效内部策略），只改 HTTP 响应路径。
- [x] HC-01b 小响应 16 MiB 初始容量问题修复，保留大响应落盘、rewind、close。
- [ ] HC-01c 补足响应内存与文件边界单测，测量 Heap/GC。
- [x] HC-02a 将 Multipart 的总长度计算与是否 streaming 解耦。
- [x] HC-02b 已知长度的大 Multipart 使用 `Content-Length` + 流式请求体。
- [x] HC-02c 未知长度的 Multipart 使用 chunked；确认 wire bytes 一致、避免长度溢出。
- [x] HC-03a 增加 `uploadChunkBytes`，替换 256 B 上传路径；16 KiB 默认值仍待完整基准调优。
- [ ] HC-03b 测试 flush/背压/异常取消、请求资源释放、连接池安全。

### P2

- [ ] HC-04a 拆分 Cookie 是否需要更新、缓存是否阻塞、用户回调交付阶段。
- [ ] HC-04b 避免 CookieJar 非空即产生的无意义独立调度；确保 EventLoop 无用户阻塞。
- [ ] HC-05a 定义 `BodyConsumer/ResponseHead/StreamResult` API 与所有权契约。
- [ ] HC-05b 在 inbound pipeline 接入增量 chunk，建立串行消费 + Netty autoRead 背压。
- [ ] HC-05c 覆盖成功/超时/取消/消费失败/重定向/解压/代理/连接复用。
- [ ] HC-05d 提供 `toFile`/`discard` 内置 consumer 及使用示例，文档说明与旧 API 区别。
- [ ] HC-06a 增加连接池/握手/响应 buffer/chunk/队列等轻量指标。
- [ ] HC-06b 建立独立 JDK 21 对照 benchmark；记录 before/after/JDK 21 三组结果。
- [ ] HC-06c 更新 `docs/reference/net/http.md` 并完成全量回归。

## 10. Definition of Done（最终交付检查）

1. **正确性**：Java 8 编译不回退；原有 HTTP 功能测试全部通过；新增资源/协议/背压/取消覆盖通过。
2. **内存**：1 KiB 响应不再初始申请 16 MiB；100 MiB Multipart 不再整包缓冲；1 GiB 流式下载不随 body 等比例增长堆内存。
3. **并发**：不阻塞 EventLoop；连接池 acquire/release 数量平衡；取消/断开不会错误复用 HTTP/1.1 连接。
4. **可观测**：区分客户端实例指标和全局 Netty 内存；可查看连接池与 TLS 的关键数值。
5. **对比测试**：有 JDK 21 实测表、运行脚本、环境描述与变更前后差异；无伪造 RPS/延迟数字。
6. **交付**：一个阶段对应可审阅的独立 commit/PR；完整修改文件列表、风险点、验证命令、性能报告。

## 11. AI Agent 执行指令（可直接复制）

```text
请基于 docs/http-client-performance-optimization-20261026.md 实施 rxlib HttpClient 性能优化。

先重新核对当前 master 的源码是否和计划基线一致，不要照抄过期行号。
按照 Phase 0 -> HC-01 -> HC-02/03 -> HC-04 -> HC-05 -> HC-06 顺序逐步实施；每阶段先阅读依赖调用链，再写/跑相应回归测试，然后才进入下一阶段。
保留 Java 8 编译目标和现有公开 API/语义；不要修改无关类，不要引入 HTTP/2，不要改动生产 HTTPS 信任策略（已有 P0 需要单独处理）。
大文件上传和下载必须是真流式，严守 ByteBuf 引用计数、Netty EventLoop 非阻塞、背压、取消、连接池生命周期和临时文件清理。
JDK 21 HttpClient 对照基准必须放独立构建模块/目录，不能将 Java 21 API 混进 Java 8 主模块。每个性能结论提供可复现测试数据，不允许估算冒充实测。
每阶段输出：修改文件、关键设计取舍、运行命令、测试结果、性能变化、未解决风险。不要在一轮中无验证地批量完成所有修改。
```

---

**说明**：未勾选项仍是实施计划；涉及默认 chunk/flush 大小、性能收益的数值均需通过上述基准确认。

## 12. 2026-10-26 P1 实施记录

- 响应使用独立 4 KiB 初始容量，保留 16 MiB 阈值与旧 HybridStream 构造语义。
- 文件 Multipart 改为流式发送，已知长度保留 Content-Length；字段与文件 Header 固定快照，逐文件及整体长度检查，防止长度变更与多文件长度抵消。
- 上传默认 16 KiB 分块、128 KiB / 8 块 flush，保留 worker 读取及批量等待写完成的有界背压；取消/提前响应不再复用尚未完整上传的连接，EventLoop 写入前再次核对请求归属。
- 修复读取抛异常时上传 ByteBuf 未释放，以及落盘响应 FileChannel 与 BufferedRandomAccessFile 混合访问时缓存/磁盘位置不一致的问题。
- 原有 HTTP 基线 42 项通过；新增内存、上传及混合 I/O 回归后共 61 项，在 Temurin 21.0.12.1 与 Temurin 8u504 下通过，生产编译目标保持 Java 8。
- 可复现命令、覆盖范围和剩余工作见 [P1 验证记录](http-client-performance-p1-validation-20261026.md)。HC-01c 的 Heap/GC 压测、HC-03b 的完整限速/大文件矩阵，以及 HC-04～06 尚未完成，不能视为整个计划验收。
