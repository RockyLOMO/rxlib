# RSS / SS 客户端更新下载卡住排查（2026-10-09）

## 当前结论

本机链路为 v2rayN 7.23.4 / Xray mixed :1080 -> 远程 RssClient Shadowsocks -> 本地 SOCKS 转发入口 -> RSS server -> 下载站点。当前 Xray 配置为 aes-256-gcm，RssClient SS 入口 :6899；本机未修改代理配置或更新已安装的软件。

已通过失败回归确认并修复两处 SS 编码缺陷：空 TCP 写入无法作为发送排空屏障；完成地址解析后的 1–3 字节 TCP 明文被丢弃。

两处缺陷均存在可复现的行为证据，但本次历史更新卡住的直接根因仍未确定。当前真实下载测试全部成功，没有取得历史失败连接对应的远端日志、FIN/RST、待发送字节或停止读取状态。不能把候选缺陷直接等同于此次线上根因。

## 截图及本机遗留文件

截图显示 v2rayN 80%、mihomo 50%、sing-box 50%，Xray 已最新，GeoFiles 中有下载成功项。不能把这个截图解释为所有 geo 文件均失败。

v2rayN 7.23.4 的 DownloaderHelper 只在整数进度为 10 的倍数时上报；截图数字是最后一次显示进度，不代表实际停止字节。

只读检查 `D:/home/v2rayN-windows-64/guiTemps` 的最近三份残留更新包：

| 文件名 | 文件长度 | 最后非零字节之后的偏移 | 近似写入比例 |
| --- | ---: | ---: | ---: |
| 3c27d9f0-1bc1-446b-a162-1fe493d05f9c.download | 143,961,123 | 126,353,408 | 87.77% |
| 43f6ebcb-719e-4576-9313-2a55bb707609.zip.download | 22,525,069 | 13,009,856 | 57.76% |
| 372f186d-6783-44af-a18e-ffc325c60704.zip.download | 32,857,903 | 19,221,120 | 58.50% |

三份文件均以 ZIP 头开始，末尾 64 KiB 全零，ZipFile.OpenRead 均报 `End of Central Directory record could not be found.`。长度与对应发布包相符，但预分配长度不能作为已下载字节数。最后非零偏移仅用于近似判断，不能替代下载器计数，也不能直接定位网络丢失边界。

## 确定缺陷及修复

### CipherCodec 的 TCP 空写入

`SocksTcpBackendRelayHandler.channelInactive` 调用 `Sockets.closeOnFlushed(sc.inbound)`；后者发送 EMPTY_BUFFER，并在其 future 完成后关闭通道。

此前 CipherCodec 对已经初始化的 AES 加密器执行 encrypt(empty)，结果为空，encode 不向 out 添加消息。Netty MessageToMessageEncoder 要求 encode 至少输出一条消息，因此空写 future 失败，CLOSE 立即执行，无法等待前序密文排空。远程慢接收端有 pending write 时可能被截断；局域网输出队列较快排空时可能不显现。

修复：TCP 空写入原样 retain 并输出，不生成盐或 AEAD record。UDP DatagramPacket 继续走原来的加密路径。

### SSProtocolCodec 的短 TCP 明文

此前 `<4` 字节检查位于 tcpAddressed 判断之前，地址解析完成后的 1–3 字节合法数据也被直接释放、丢弃。

修复：最低地址长度检查仅用于 UDP 或首次 TCP 地址解析；后续 TCP 数据原样转发。发生在 AEAD record 解密后的明文层，不能仅因 WAN 有 TCP 分片就断言一定触发。

首次地址头跨多个解密输出的累积处理仍是独立兼容性问题，本次未改。它主要影响初始建连，不能直接解释已下载大半才停止。

## 排除与证据边界

- 当前 RssClient.configureShadowConfig 显式设置 SS read/write timeout=0，ShadowsocksServer 不会安装 idle handler。不能根据裸 ShadowsocksConfig 默认 240 秒推断当前 SS 入口触发读空闲。
- SOCKS/RSS 入口已采用 all-idle。历史部署是否与当前源码一致仍需核对运行包；昨天 SOCKS 超时修复记录见 rss-github-clone-timeout-20261008.md。
- 当前 SS 默认 crypto 在 EventLoop 执行，backend 使用同一 EventLoop，安装双向 TcpBackpressureHandler。此次慢接收端测试日志确实出现背压 start/end，未复现永久停读。
- EOF 排空缺陷通常影响尚未发送的队列。历史残留缺失多个 MB 的情况，不能仅凭该缺陷解释，仍需判断上游为何提前结束或停止发送。

## 验证

使用 Java 8 JDK：`C:/Program Files/Java/jdk-1.8`。

修复前新增测试失败三项：空写首次生成 32 字节盐；密文仍 pending 时通道已经关闭；握手后 1 字节 TCP 数据未转发。原有测试通过。

修复后定向命令：

```powershell
mvn -pl rxlib -DskipTests=false '-Dtest=org.rx.net.socks.CipherCodecTest,SSProtocolCodecTest,ShadowsocksTcpDownloadIntegrationTest,ShadowsocksServerIntegrationTest#shadowsocksTcpConnect_e2e+shadowsocksUdpRelay_e2e,SocksTcpIdleIntegrationTest' test
```

结果 `BUILD SUCCESS`，10 项通过，Failures=0、Errors=0、Skipped=0。包括空写关闭屏障、首次空写不初始化加密、1–3 字节透传、512 KiB 慢接收端完整解密直到 EOF、SS TCP/UDP 和 SOCKS 单向传输及静默关闭回归。测试使用本地真实 TCP/UDP socket；ByteBuf/加密器与 source executor 均在 finally 释放或关闭。

真实代理下载，curl 的输出目标为 NUL；返回 HTTP 200、exit=0：

| 下载包 | 字节数 | 耗时 |
| --- | ---: | ---: |
| v2rayN-windows-64.zip 7.25.4 | 167,955,516 | 86.303 s |
| mihomo-windows-amd64-v1.19.32.zip | 22,492,402 | 69.935 s |
| sing-box-1.14.2-windows-amd64.zip | 32,857,903 | 71.539 s |

另用 .NET SocketsHttpHandler / HttpClient 经 HTTP :1080 下载与残留文件长度一致的 v2rayN-windows-64-desktop.zip：HTTP/1.1 200，Content-Length=143,961,123，实际读到 143,961,123 字节，55.749 s。这验证了 .NET HTTP 客户端路径，但没有执行原版 Downloader 库或 v2rayN 更新按钮流程。

上述远程代理实测使用仍在运行的线上服务；本次修复仅在本地源码与测试中验证，尚未部署，不能当作发布后的修复验收。

## 后续线上核对

按失败下载的时间、目标域名、两端四元组关联 RssClient 和 RSS server 日志，检查 `idle: READER_IDLE/ALL_IDLE`、EncoderException、cipher decode fail、连接异常与上游提前关闭。卡住时检查每段的 TCP send/recv queue、重传、零窗口、待写字节和 autoRead 状态，区分源站停止发送、WAN 丢包、背压不恢复及主动关闭。

建议监控：堆外占用（PooledByteBufAllocator direct memory / BufferPoolMXBean）、活动连接数、每方向吞吐和延迟、pending write bytes、背压暂停时间、EventLoop 延迟、idle/异常关闭计数及 TCP 重传。

## 源码参考

- https://github.com/2dust/v2rayN/blob/7.23.4/v2rayN/ServiceLib/Helper/DownloaderHelper.cs
- https://github.com/2dust/v2rayN/blob/7.23.4/v2rayN/ServiceLib/Services/DownloadService.cs

上述进度与超时语义按指定版本源码核对。BlockTimeout=30 秒是下载块相关配置，不能把它当作固定 30 秒总下载上限。
