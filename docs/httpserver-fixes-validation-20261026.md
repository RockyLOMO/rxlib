# HttpServer 非证书修复验证（2026-10-26）

实现起点：`master` 的 `387791a2`。Java 8 source/target 保留；改动仅限 HttpServer、ServerResponse、新增 HttpServerConfig、相关测试与文档。
默认 TLS 证书、自动自签逻辑、Sockets 等其他网络子系统未修改。

## 缺陷证据与验证命令

ServerResponse 修复前新增 4 项测试，4 项失败：替换正文未释放旧引用、helper 覆盖泄漏、helper 异常改变旧正文、redirect 状态为 200。
修复后相应回归通过；额外验证 takeContent/releaseContent 所有权转移。

定向构建和回归：

```powershell
mvn -q -pl rxlib -am test '-Dtest=ServerResponseTest,HttpServerLifecycleTest,HttpServerLimitsTest,HttpServerProtocolTest,HttpServerBlockingTest#asyncHandler_*+htmlTemplate*+renderTemplate*,HttpClientIntegrationTest,HttpClientTest,HttpClientMemoryTest,HttpClientUploadTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DskipTests=false' '-Dio.netty.leakDetection.level=paranoid'
```

Java 8 通过 Scoop 已安装版本切换（不修改用户环境变量）：

```powershell
$env:JAVA_HOME = (scoop prefix temurin8-jdk).Trim()
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
```

Temurin 8u504：90 tests，0 failures，0 errors，exit 0。开启 Paranoid 泄漏检测；日志无 `LEAK:` 告警。
Temurin 21.0.12.1 的同组复核过程中，旧 `HttpClientIntegrationTest.fixedPoolLimitsConcurrentAcquire` 曾在 2 秒等待处出现一次 TimeoutException；其单项复跑通过，随后完整 90 项复跑通过。失败未通过放宽断言或更改客户端掩盖。
最终版本使用 Java 8 编译（HttpServer.class major version 52），再用 Java 21 执行同组 Surefire 回归；最终两次均为 90 tests，0 failures，0 errors，exit 0，两个日志均无 `LEAK:` 告警。
Java 21 对相同 class 文件的运行命令为上面的相同测试选择参数配合 `mvn -q -pl rxlib surefire:test`（无需重新编译）。

| 测试 | 数量 | 重点 |
| --- | ---: | --- |
| ServerResponseTest | 5 | 替换、异常、redirect 和转移所有权 |
| HttpServerLifecycleTest | 11 | 断线、Multipart、retain、任务拒绝、pipelining、stop 与迟到 child |
| HttpServerLimitsTest | 11 | 正文/Multipart/文件/字段/协议限额、408、连接/上传/异步配额 |
| HttpServerProtocolTest | 8 | HEAD、204/205/304、redirect、路由、100 Continue、gzip |
| HttpServerBlockingTest（非证书方法）| 4 | 同步/异步线程、顺序请求、模板 |
| HttpClientIntegrationTest | 20 | Cookie、缓存、304、redirect、gzip、连接池与超时等旧功能 |
| HttpClientTest | 22 | JSON/form/multipart、代理、Cookie 与日志等旧功能 |
| HttpClientMemoryTest | 3 | 响应内存/文件边界与重复读取 |
| HttpClientUploadTest | 6 | 上传内容、长度、异常释放、取消、提前响应 |
| 合计 | 90 | |

`HttpServerLifecycleTest` 包含单 server 连续 1000 次 Multipart 中断；每次分块写入超过内存阈值，断言实际临时文件创建，关闭后文件消失、FileUpload.refCnt 为 0、activeRequests/activeConnections 为 0。
运行中的 worker 使用 latch 验证断线时正文仍有效，返回后才释放；尚未执行的 queued worker 在断线时立即撤销借用权并释放正文。
本地 Netty 4.2.12.Final sources 用于核对 next、destroy、factory 清理和引用计数，测试覆盖显式 retain 存续。

初次包含原有证书测试时，`tlsWithoutCertificate_servesLocalhostRequest` 因 `Certificate: No provider succeeded to generate a self-signed certificate` 失败。
按用户排除证书的范围，本轮没有修复或更改这条路径；上面的命令明确仅选择该测试类的非证书方法，不能据此声称 HTTPS 已验证。

## 行为与资源边界

- 旧两参数构造器保留，默认启用明确资源配额；大请求场景需设置相应上限，见 [HTTP 参考文档](reference/net/http.md)。
- 连接上只保留一个请求至响应 flush；重叠 pipelining 直接关闭，不维护无限请求队列。
- 默认异步执行器独立、线程数有界、无等待队列；调用方自定义 executor 必须及时返回，服务端不接管其生命周期。
- 正文/Multipart/文件及字段都有限额；multipart 总量乘并发上传数为临时存储提供粗粒度上界，非精确磁盘配额管理器。
- handler 的请求内容/文件是借用，显式 retain 才能在回调返回后持有；response.setContent 接收引用所有权。
- 最终应用响应不接受 1xx；100 Continue 由框架在头部校验后处理，非最终业务响应。
- 指标使用低基数实例计数，没有原始 URL 标签；请求延迟从状态建立到 flush，Direct Memory 是 JVM 全局。

未运行全仓测试、HTTP/HTTPS 吞吐对照、p50/p95/p99 压测或 1 GiB 流式传输测试；本轮没有新增流式 API，也没有性能提升百分比结论。
