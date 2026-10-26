# HttpClient P1 验证记录（2026-10-26）

基线：`f831f76c`，分支 `master`。Windows 10 amd64，Maven 3.10.0，Netty 4.2.12.Final。
本次完成 P1 核心代码与定向正确性验证，未完成整个性能优化计划。

## 验证命令与结果

原有 HTTP 基线（修改生产代码前启动并完成编译）：

```powershell
mvn -pl rxlib -am test '-Dtest=HttpClientTest,HttpClientIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DskipTests=false'
```

结果：42 tests，0 failures，0 errors，BUILD SUCCESS（Temurin 21.0.12.1）。

修改后定向编译与测试：

```powershell
mvn -q -pl rxlib -am test '-Dtest=HttpClientMemoryTest,HttpClientUploadTest,HybridStreamTest,HttpClientTest,HttpClientIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DskipTests=false'
```

Temurin 21.0.12.1 与 Temurin 8u504 各执行一次，均为 exit 0：

| 测试类 | 数量 | 失败 / 错误 |
| --- | ---: | --- |
| HttpClientTest | 22 | 0 / 0 |
| HttpClientIntegrationTest | 20 | 0 / 0 |
| HybridStreamTest | 10 | 0 / 0 |
| HttpClientMemoryTest | 3 | 0 / 0 |
| HttpClientUploadTest | 6 | 0 / 0 |
| 合计 | 61 | 0 / 0 |

切换到已安装的 Java 8 后重跑相同命令：

```powershell
$env:JAVA_HOME = (scoop prefix temurin8-jdk).Trim()
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
```

Surefire 结果在 `rxlib/target/surefire-reports/`，会被后续运行覆盖。

## 已验证的行为

- 直接检查 ByteBuf 初始 capacity：HTTP 小响应 4096 B，旧两参数 HybridStream 仍按阈值分配；初始值超过阈值会收敛到阈值。
- 已知 Content-Length 与 chunked：0、1、1024、32767、32768、32769 B，阈值 32768 B；重复 bodyAsString、rewind、bodyAsFile、close 与临时文件回收。
- 内存/文件边界读写、ByteBuf 引用释放、FileChannel 与缓冲数组 I/O 切换。
- Multipart 原始报文字节 SHA-256、Unicode 字段和文件名、空文件与多文件、Content-Length/chunked、配置分块大小。
- 文件长度过长/过短、读取异常后 ByteBuf.refCnt 为 0，文件关闭。
- 取消与提前 413 后停止后续读取，并使用新连接完成下一请求。
- 原有 Cookie、缓存/304、重定向、GZIP、代理、超时、连接复用回归。

## 资源与性能边界

本次证明的是分配策略和协议正确性：1 KiB 响应不再默认分配 16 MiB，文件 Multipart 默认不走整包缓冲。
这不等于实测 Heap 峰值、GC、吞吐或 p99 改善，本文不提供未测的提升百分比。

上传在达到块数/字节数阈值或不可写时等待 flush 完成，保留有界队列；读取、写入前与 EventLoop 执行写入时检查请求归属。
阻塞输入流的取消只能在当前 read 返回后生效；普通 InputStream 关闭所有权仍属于调用方。

剩余：100 MiB / 1 GiB 生成式上传与 digest、持续限速背压、Heap/GC 峰值、多并发/故障矩阵、独立 JDK 21 before/after 对照基准、完整全仓回归。
HC-04 Cookie 调度、HC-05 BodyConsumer、HC-06 连接池/TLS 指标未实施。原有 HTTPS 信任策略未更改。

建议持续观察连接数/池等待、吞吐、p50/p95/p99、错误率、Heap/GC、临时文件/FD、JVM 全局 Netty 堆外内存；
现有 usedDirectMemory 指标不代表客户端独占内存。
