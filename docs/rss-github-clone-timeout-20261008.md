# RSS GitHub 长下载超时排查（2026-10-08）

## 结论与证据边界

确认 RSS 的 TCP 入口存在单向长传输误关闭问题：配置的是读方向空闲，而不是双向空闲。持续下载属于入口通道的 write，不能刷新入口的 read idle。未发现 SOCKS TCP 转发阶段按连接总时长强制终止的定时器。

线上复现已对齐同一连接：客户端 `127.0.0.1:12650 -> 127.0.0.1:6885`，18:07:21 开始通过 RSS 下载 GitHub Linux 源码归档，强制 HTTP/1.1，限速 128 KiB/s。18:09:22.691 出现：

```text
ProxyChannelIdleHandler - TCP [id: 0xe1d0c53e,
L:/127.0.0.1:6885 - R:/127.0.0.1:12650] idle: READER_IDLE
```

18:09:07 的 `ss -tinp` 显示该连接累计接收 17,558,478 字节，`lastsnd:104773`；18:09:24 接收计数为 19,781,840 字节，`lastsnd:122163`。`bytes_sent` 始终为 754。说明下载仍有数据，但应用发送方向已空闲约 120 秒。内核 TCP ACK 不等同于 Java `channelRead`。

用户随后提供 wow-mobile clone 的两次失败时间与 Git 日志，已与 RSS 日志对齐：

| Git 失败时间（UTC+8） | 耗时 / 接收进度 | 前序 RSS 客户端关闭 |
| --- | --- | --- |
| 17:43:19 | 130 秒 / 321.54 MiB / 54% | 17:43:16.899，channel 13daebc6，loopback peer :64874，READER_IDLE |
| 17:46:21 | 132 秒 / 323.58 MiB / 54% | 17:46:17.679，channel 37fab2db，peer :5344；17:46:19.467，channel 1dd95a48，peer :5354，均 READER_IDLE |

两次 Git 均报 curl 56 Schannel missing close_notify、early EOF；RSS 关闭发生在 Git 报错前约 2–4 秒，且两次耗时均接近 120 秒加协商开销。结合线上单向下载复现，读空闲误关闭是高度可信的原因。当前 INFO 日志只记录通道及 loopback peer，没有这些通道的目标域名，仍缺少将上述历史通道唯一绑定到 Git clone 的证据。

17:47–17:52 第三次成功、总耗时 305 秒，不等于某一条 TCP 连接连续 305 秒没有上行数据；recursive clone 包含多次请求和连接。期间仍有其他 READER_IDLE 日志，无法仅凭总耗时确定第三次为何避开问题。

上述受控复现确认了长下载时仍会触发读空闲及 RSS 的关闭逻辑。最终 curl 28 来自排查命令的 150 秒上限，不代表已经复现 Git 原始报错；closeOnFlushed 及接收缓冲区会使触发 idle 与下载进程看到结束存在时间差。

## 代码路径

- `RssClient.configureInboundConfig` / `configureOutboundConfig`：把 `tcpTimeoutSeconds` 传给 `setReadTimeoutSeconds`；线上 `/home/rss/conf.yml` 为 120 秒。
- `SocksProxyServer.acceptChannel`：安装 `ProxyChannelIdleHandler(readTimeout, writeTimeout)`。
- `ProxyChannelIdleHandler`：调用 `super(readerIdle, writerIdle, 0)`，allIdle=0（未启用）；收到 idle 后执行 `Sockets.closeOnFlushed`。
- `SocksTcpFrontendRelayHandler.channelInactive`：入口关闭后关闭出口，传输中断。
- RSS Server 使用 `SocksConfig` 的默认读空闲 240 秒；今天 16:25:17.452 也有入口 `READER_IDLE` 日志。

已经从线上客户端 JAR 读取并反汇编 `ProxyChannelIdleHandler.class`，确认运行包仍使用上述构造及关闭逻辑；该包携带 Netty 4.2.12.Final。

## 验证记录

1. Java 8 编译临时 `IdleProbe`，使用项目已编译 handler 与 Netty 4.2.12.Final 的 EmbeddedChannel 虚拟时钟。每 30 秒 write：当前 handler 在 120 秒产生 READER_IDLE；候选 `(0, 0, 120)` 在持续 write 到 150 秒仍保持 active，停止 I/O 121 秒后产生 ALL_IDLE。探针覆盖 idle 计时，回调中直接 close；实际 RSS 的关闭实现通过源码和线上字节码确认。
2. 线上默认协议下载：HTTP 200，收到 19,726,336 字节，150.020389 秒；由排查命令主动设置的 `--max-time 150` 终止（curl 28），该连接未记录 RSS idle。HTTP/2 会回传流控帧，可能刷新读空闲；这次未输出协商版本，因此协议解释属于推断。
3. 首次强制 HTTP/1.1 请求返回 GitHub HTTP 429，未计入超时复现。随后直接访问 codeload URL，得到上述同一端口的 120 秒 READER_IDLE 证据。最终输出：HTTP 200、version=1.1、bytes=19,735,917、time=150.001267、starttransfer=1.735704、local_port=12650；由排查命令的 150 秒上限终止（curl 28）。
4. 本机 Git 全局/仓库配置查询未发现 http.lowSpeedTime、http.lowSpeedLimit、http.version 或 http.proxy 设置；这不代表其他 clone 进程、环境变量或命令行参数也不存在。

排查阶段的临时探针和输出在 `%TEMP%/ai/20261008/rss-clone-timeout/`；后续修复与发布记录见下文。

## 修复实现与发布

新增 `SocksConfig.tcpIdleTimeoutSeconds`（默认 0）与 `ProxyChannelIdleHandler` 三参数构造；TCP 入口按配置安装 all idle，沿用 handler 名称以确保 UDP_ASSOCIATE 仍能移除它。RSS Client 配置 reader=0、writer=0、allIdle=tcpTimeoutSeconds（现网 120 秒）；RSS Server 为 reader=0、writer=0、allIdle=240 秒。建连超时、方向性超时调用和 UDP/udp2raw 的 idle 配置继续保留。

all idle 使用 `observeOutput=true`；Netty 会统计读及完成的写，并观察 pending output。此次沿用 EventLoop 定时器与 Netty 复用的 write listener，无新增逐包任务、阻塞或 ByteBuf 所有权变化，TransportFlags 与协议报文未改。

已知限制：Netty 4.2.12 的 output observation 不抑制首次 idle 事件；若一笔极慢的大块写跨整个超时窗口、promise 始终未完成，部分写进展仍可能被首次 idle 误判。原 `closeOnFlushed` 还可能等待完全卡住的输出。本次修复持续单向传输中已完成的写不刷新 read idle 的主故障，未扩大修改上述生命周期问题。

验证结果：

- Java 8 定向回归两轮均 BUILD SUCCESS，首轮 9 项，第二轮 9 项。覆盖配置更新、持续单向读/写跨 360 秒虚拟时钟、静默关闭、禁用、旧方向性语义。
- 新增真实 SOCKS 集成测试：idle=1 秒，源端每 200 ms 发送一次、持续约 3 秒；客户端收齐全部数据，静默后 frontend/backend 均收到 EOF。通过。
- 压缩 SOCKS 链路、UDP relay 两项集成回归通过。
- `rss-21` 发布工作树基于 b0f71401，Java 21 `package -DskipTests=false` BUILD SUCCESS，13 项测试通过。主仓 Java 源码保持 Java 8 兼容。
- 发布包 90,336,287 字节；SHA-256 `f80a3843b71e890c6fd6d026d404961c613f46eeafe146f8d156154d4827cbea`，两端 `app.jar` 校验一致。
- RSS Server 18:21:56 启动 PID 1954841；9900/9901/9910/8082 监听正常。发布前旧包额外保存 `/home/rss-svr/app.jar.before-idle-fix.20261008_182153.jar`。
- RSS Client 18:22:26 发布新进程 PID 1648096；新进程接管 6885/6899/8082，旧进程进入排空。旧包额外保存 `/home/rss/app.jar.before-idle-fix.20261008_182226.jar`。
- 18:24 通过客户端 SOCKS 请求 Google generate_204：HTTP 204，0.974009 秒。
- 18:24:04–18:24:46，从客户端通过 `socks5h://127.0.0.1:6885` 强制 HTTP/1.1 执行 `git clone --bare https://github.com/RockyLOMO/wow-mobile.git`。退出码 0，3528/3528 objects，接收 806.56 MiB，HEAD `0c8068f617737d9c0fb6246f37b54f85b620b09d`。此次约 42 秒，验证真实 pack 传输；没有执行工作树 checkout/submodule clone，也没有覆盖 120 秒边界，因此另做限速下载跨窗口验收。
- 发布后日志已出现 TCP `ALL_IDLE`；UDP 仍记录原来的 `READER_IDLE`。旧客户端 PID 4159863 已退出，新 PID 1648096 独立接管监听。
- 18:26:21–18:31:26，通过同一 RSS SOCKS 入口强制 HTTP/1.1，以 192 KiB/s 下载 Git for Windows release 文件至 `/dev/null`；完整收到 60,027,568 字节，HTTP 200，耗时 304.387519 秒，curl_exit=0（测试上限 420 秒，未触发）。下载末段 local_port=29314。
- 18:28:43，该连接接收 29,150,857 字节，lastsnd=139724 ms；18:30:37 接收 51,599,450 字节，lastsnd=253748 ms，bytes_sent 始终为 1649。持续有下载数据、没有上行应用数据，跨越客户端 120 秒和服务端 240 秒阈值；对应端口没有 idle 关闭日志。
- 临时 bare clone 在确认 HEAD/pack 大小后已删除；远端 `/tmp/ai/20261008/rss-idle-fix/clone.log` 保留。源码改动保留在主仓 master 和已有 rss-21 发布工作树，尚未提交或推送。

修复验收：持续单向下载/上传超过阈值、双向静默关闭、背压后的恢复/停滞关闭、关闭后定时任务与两端 Channel 释放；回归 SOCKS 握手与 UDP relay。协议报文和 TransportFlags 应保持兼容。

核心监控：按协议和 idle 类型统计关闭数；活动连接数；每方向字节、吞吐和请求延迟；待发送字节/背压暂停时长；EventLoop 延迟；PooledByteBufAllocator direct memory 与 BufferPoolMXBean 堆外占用。

## 同时观察到的其他异常

客户端今天多次记录 GitHub 域名 `NoAvailableDnsInterceptorException`；服务端 16:43 左右有 api.github.com UDP DNS 查询超时。12:54 有 fakeEndpoint RPC 注册超时。这些主要影响解析/建连，不能据此解释已持续收包的 clone 中途失败。17:42 服务端的 NotSslRecordException 是 HTTP 请求打到 TLS 管理端口，与本次下载复现无对应关系。

## 参考

- [Netty IdleStateHandler 4.2.12.Final 实现](https://raw.githubusercontent.com/netty/netty/netty-4.2.12.Final/handler/src/main/java/io/netty/handler/timeout/IdleStateHandler.java)
- [RFC 9113 HTTP/2 流控](https://www.rfc-editor.org/rfc/rfc9113.html#section-5.2)
