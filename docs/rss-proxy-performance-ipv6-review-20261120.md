# RSS 代理性能与 IPv4 / IPv6 审查

2026-11-20，审查版本为 `master` 的 `b2487eab2b46b1c8342a648355e0c7d651988864`。在 Windows、Temurin 8、Netty NIO 上验证。SOCKS5 和 Shadowsocks 的 TCP、UDP 都能在 IPv4 与 IPv6 之间转发。使用仓库默认值部署到纯 IPv6 网络时，远端 DNS 和自动白名单仍依赖 IPv4。Server 按单核小内存优先控制进程级积压、复用解析与执行资源；Client 优先修复 EventLoop 锁顺序、ByteBuf 所有权与 DNS 热更回收。

## 本轮代码变更

下文的抓包、资源采样和 P1 表格记录了 `b2487eab` 的行为。本轮修复在本地源码验证，尚未部署。

| 范围 | 已实现的行为 |
| --- | --- |
| Server DNS | Server 启动时为应用的 Netty TCP、UDP 和 RPC 解析器关闭成功地址答案和 CNAME 缓存，保留 5 秒失败缓存与在途查询合并。TCP 的 REMOTE nameserver 列表只在创建共享 resolver group 时解析。Client 延续原缓存策略。 |
| 单核与整机内存 | 新建单监听的 Server TCP bootstrap 在同一 worker group 中 accept 和处理连接。关闭其中一个监听只关闭所属 channel。没有显式设置 reactor 线程数时，Server 每组默认 1 个。Server 所有经过最终 UDP 出口保护的待发包默认合计最多 4 MiB、4096 包，加上每 channel 的既有限额。等待目的地址解析的包也计入。两项上限均作用于积压，应用层路由队列、内核缓冲和其他 direct 分配另计。 |
| 代理生命周期 | 默认无重连订阅时建连失败立即返回，显式注册重连处理器的调用方继续控制重试。待建连的 TCP 数据按顺序保留，暂停读取，按高水位及 256 块封顶，失败与关闭释放。SS 转本机 SOCKS 的 LocalChannel 改用兼容的共享线程组。SS UDP 解绑父 channel 的回调，每个出站 socket 只装一个路由清理回调，按源计数原子预留与归还。独立 SOCKS5 UDP 接收者各自拥有一份引用。 |
| Client DNS 与控制面 | H2 冷查询移出 Netty I/O 线程，热拦截缓存和响应缓存可直接读内存。更换或清空拦截器、关闭 DNS 组件时回收所拥有的 H2 缓存，限时刷盘。认证读取同一版本的用户表和密码。fake endpoint 按路由、映射两级固定顺序加锁。 |
| 双栈 | SOCKS CONNECT、UDP ASSOCIATE、预热 CONNECT 使用真实地址类型。DNS 请求全部地址族时保留 IPv6 结果。本机解析 IPv6 字面量保留 scope。公网 IPv4 观测失败后，鉴权 RPC 仍可登记实际对端地址。纯 IPv6 网络仍需配置可达的 DNS 上游。 |

**后续容量验收**需要在 1 核、768 MiB 整机环境记录 PSS、Netty 实际堆外分配、活动连接、最终出口丢包原因、每秒转发包数及 P99。4 MiB 和 4096 包为初始预算。TCP 进程级建连限额、可选 DoH 的双查询并行及转发批量 flush 仍需负载数据。生产回程样本缺失也需独立定位。

## RSS Server 的整机预算与优化顺序

**设计验收以 1 核、768 MiB 整机内存为准。** OS、同机服务与内核网络缓冲也从这 768 MiB 中支出。Client 不套用这组硬件上限，两端均减少重复常驻状态。[项目基线](../AGENTS.md)

2026-11-20 16:56（UTC+8）通过两端 SSH 读取运行进程和 `/proc`。当时 Server 系统可见 2 个逻辑 CPU、约 1267 MiB 总内存，MemAvailable 约 468 MiB；Java 有 48 个线程、VmRSS 约 426 MiB、VmSwap 约 61 MiB。实际参数包括 256 MiB 固定堆和 640 MiB direct 上限。Client 系统可见 6 个逻辑 CPU、约 13.7 GiB 总内存。以上是当前主机的一次采样；1 核 / 768 MiB 条件下的承载能力仍要单独验证。

| 顺序 | Server 应怎样处理 | 核验方法 |
| --- | --- | --- |
| 1. 先给内存峰值设界 | 服务端先收敛失败连接重试；把所有连接的待建连数、所有 UDP relay 的待写包数/字节、尚在解析中的数据报都计入进程预算。在接管 ByteBuf 前预留额度，失败或关闭时归还。现有 128 KiB / 256 包设置按 channel 计，多 channel 的积压会相加。 | 在慢上游、DNS 延时及正常突发负载下记录进程 PSS、Netty direct、内核 socket 内存、MemAvailable、swap-in/out 和丢弃原因。640 MiB 是可申请上限；不要用它代替实际堆外占用，也不要按 `768 - Xmx` 把余量全部给 direct。[启动与限额](../deploy/rss-svr/start.sh#L51) · [最终发包预算](../rxlib/src/main/java/org/rx/net/udp/UdpFinalEgressGuardHandler.java#L101) |
| 2. DNS 答案主要留在 Client | 普通 Server 启动 SOCKS、RPC 及可选 udp2raw；创建带 H2 拦截/响应缓存的 `DnsServer` 是 Client 的职责。Server 的 RPC 查询与 REMOTE UDP 已共用单例 `DnsClient`，TCP 使用另一组复用的 Netty resolver。两处配置了 5–300 秒正缓存。服务端没有必要复制 Client 的长 TTL 答案库。建议先把 TCP 热路径里重复解析上游 DNS 配置的操作移到首次初始化，再比较“仅合并在途、完成即释放”和“小容量有界短缓存”两种 Server 策略。 | 按 RPC/TCP/UDP、A/AAAA 看实际查询次数、并发峰值、P99 和解析错误。保持原有 DNS 模式、路由隔离和负缓存语义可验证。脚本的 MEMORY / 256 条 / 64 KiB 是应用 DNS 缓存参数，不能限制上述两个 Netty resolver。服务端白名单和 fake endpoint 映射有独立的准入与连接还原用途。[Client DNS 入口](../rxlib/src/main/java/org/rx/util/rss/RssClient.java#L947) · [RPC 解析](../rxlib/src/main/java/org/rx/util/rss/RssRpcApp.java#L130) · [TCP resolver](../rxlib/src/main/java/org/rx/net/Sockets.java#L268) · [UDP resolver](../rxlib/src/main/java/org/rx/net/Sockets.java#L405) · [Netty TTL](../rxlib/src/main/java/org/rx/net/dns/DnsClient.java#L193) |
| 3. 单核复用线程、池与监听组 | TCP、UDP worker 已按 reactor 名称复用；多个 TCP listener 创建各自的 accept group，关闭 bootstrap 时会释放 parent。先核对活动组与持有者，再评估不同 listener 共用一个 accept loop、每个必要 I/O 组一个 loop。阻塞 RPC 和存储保留有界执行器，避免拖住数据转发。 | 对照空载线程/PSS、活跃连接数、每核 PPS、EventLoop 调度 P99 和慢 RPC 期间的转发延迟。合并 listener 后应能单独关停或热更其中一个监听。先测线程/队列，再定 `Xmx`、direct cap 和 allocator arena 数。[事件循环与复用](../rxlib/src/main/java/org/rx/net/Sockets.java#L587) · [accept 与释放](../rxlib/src/main/java/org/rx/net/Sockets.java#L750) · [启动配置](../deploy/rss-svr/start.sh#L64) |
| 4. 单独预算回程扩展 | 当前普通上行没有 RSS 冗余头，回程可复制、压缩。单核成本集中在哪一侧要按方向衡量。若考虑把 Server 回程改成单发，应作为单独的策略变更评估。 | 按上/下行统计应用包、线上包、压缩前后字节、CPU/包、丢包及客户端 P99。对小随机包和可压缩长包分别比较；保留兼容的接收解码能力。 |

Client 侧优先修复 SS 首包释放、建连前背压、UDP 历史回调引用及 DNS 热更遗留线程。Client 可以承担受容量、TTL 约束的 DNS 缓存和查询合并；这些修复与 Server 的 768 MiB 预算分别验收。

## 线上普通 UDP 是否只发一份

**是，适用于普通 `main` 的 Client → Server 业务数据报。** 核对了运行进程打开的 JAR 与磁盘 inode，以及两端 14 个相关类的完整 SHA-256；均与本地 `rss-21` 编译产物一致。生效线路根据客户端配置文件、两端相关字节码和网卡实测交叉核对。测试使用两个主机之间的 IPv4 路径。

| 实测范围 | 去程 Client → Server | 回程 Server → Client |
| --- | --- | --- |
| SS 入口 6898、6899，均选择 `main` | 两个入口各取一轮，每轮 4 个正式请求、1 个预热请求。排除预热后，共 8 个正式请求。客户端出口与服务端入口各数到 8 包，都是标准 SOCKS5 UDP，每个请求一份。线上 UDP payload 长度为 66 或 390 字节；较长包特意使用可压缩内容。 | 服务端回环 echo 每个请求只回一次。服务端公网网卡共发 16 包，8 包是 `RDNT → SOCKS5`（74 字节），8 包是 `RDNT → UCMP`（48 字节）。每个响应两份，均发往观测到的 NAT 对端端口。本次客户端网卡在此测试流的过滤范围内未见回包，发起端也未收到回显；回程到达率和缺失原因未确定。 |
| SS 入口 6897、6896 | 当前正权重的目标为 `main-tun`，配置了 `udp2raw=true` 和外部 UDP 入口；6896 另列一个权重为 0 的上游。上述普通线路抓包结论不覆盖这两个入口。 | 运行配置及代码核对，未对外部隧道抓包计数。 |

**为何配置写倍率 2，普通上行仍只发一次？** Client 用新建的 `inConf` 创建普通 SOCKS A 的 UDP relay；实际写包用的是这个 channel。倍率、压缩及跳端口配置写入另一份 `outConf`，该对象参与选择和管理 B 端 relay。一次业务写入只选一个 relay，本次每轮观察到一个目标端口。控制面的 RPC relay group、心跳和端口选择仍可工作；Server 回程使用自己的配置，已经在公网网卡观测到冗余头和条件压缩。普通数据包仍有标准 SOCKS5 UDP 头。[普通入口](../rxlib/src/main/java/org/rx/util/rss/RssRuntime.java#L347) · [配置分离](../rxlib/src/main/java/org/rx/util/rss/RssClient.java#L1034) · [实际建 channel](../rxlib/src/main/java/org/rx/net/socks/Socks5CommandRequestHandler.java#L119) · [上行写包](../rxlib/src/main/java/org/rx/net/socks/SocksUdpRelayHandler.java#L817) · [服务端配置](../rxlib/src/main/java/org/rx/util/rss/RssServer.java#L94)

验证只对本次测试流计数。使用随机标记关联数据报、短时非混杂网卡采样和 Server 的临时 loopback echo，测试结束后释放 socket 和采样进程。现网服务、配置和路由均未变更。

## 调用链和审查范围

| 路径 | 实际参与的组件 |
| --- | --- |
| 客户端入口 | `RssLauncher → RssClient → RssRuntime` 创建每用户 Shadowsocks 监听、内部 SOCKS 服务和路由。用户规则、源地址、上游权重及配置决定 DIRECT、经内部 SOCKS 或其他上游。[RssRuntime L590](../rxlib/src/main/java/org/rx/util/rss/RssRuntime.java#L590) · [RssClient L1452](../rxlib/src/main/java/org/rx/util/rss/RssClient.java#L1452) |
| 服务端出口 | `RssServer → SocksProxyServer` 安装握手、认证、流量统计、空闲检查和命令处理器。普通 SOCKS、可选 udp2raw 和 RPC 各有自己的入口。[RssServer L51](../rxlib/src/main/java/org/rx/util/rss/RssServer.java#L51) · [SocksProxyServer L185](../rxlib/src/main/java/org/rx/net/socks/SocksProxyServer.java#L185) |
| TCP | 检查了 SS AES-GCM 解密、SS 地址解码、SOCKS 冷建连和预热通道、上下行 relay、读写水位及关闭路径。SS 首包到建连完成之间的资源管理与已建立连接的背压各自处理。[CipherCodec L71](../rxlib/src/main/java/org/rx/net/socks/CipherCodec.java#L71) · [SSTcpProxyHandler L29](../rxlib/src/main/java/org/rx/net/socks/SSTcpProxyHandler.java#L29) · [Socks5CommandRequestHandler L379](../rxlib/src/main/java/org/rx/net/socks/Socks5CommandRequestHandler.java#L379) |
| UDP | 检查了 SS/SOCKS 地址编解码、UDP ASSOCIATE、出站池、租约、待路由队列、最终写入保护、udp2raw 封装和关闭回调。端口跳跃、冗余、压缩的线上收益依赖流量和丢包率，本次没有测量。[UdpManager L224](../rxlib/src/main/java/org/rx/net/socks/UdpManager.java#L224) · [SSUdpProxyHandler L446](../rxlib/src/main/java/org/rx/net/socks/SSUdpProxyHandler.java#L446) · [UdpFinalEgressGuardHandler L78](../rxlib/src/main/java/org/rx/net/udp/UdpFinalEgressGuardHandler.java#L78) |
| DNS 与控制面 | 检查了 Netty DNS、A/AAAA 回包与缓存、可选 DoH、用户规则及认证热更、RPC 白名单和公网地址观测。可选 RRP、外部 UDP 转发程序、管理 HTTP、DDNS/WoW 发布的实际部署不在本机端到端测试内。[DnsResolveCore L48](../rxlib/src/main/java/org/rx/net/dns/DnsResolveCore.java#L48) · [RssRuntime L208](../rxlib/src/main/java/org/rx/util/rss/RssRuntime.java#L208) · [RssRpcApp L134](../rxlib/src/main/java/org/rx/util/rss/RssRpcApp.java#L134) |

## 修复前的优先问题

以下 P1 均有可追踪的源码路径。表中注明了运行时观测项。未测量生产发生频率、吞吐损失或堆外内存增量。

| 优先级与所在侧 | 触发条件与影响 | 修复方向与代码位置 |
| --- | --- | --- |
| P1 · Client 上游建连 | **并发域名建连存在死锁路径。** TCP 上游为真实目的地建立映射时，先按路由键持有分片锁，再按映射键从同一组锁取锁。两条连接可能以相反顺序取得两个分片，使处理它们的 EventLoop 一起停住。缓存命中也会再次确认映射。 | 拆成路由锁与映射锁两组，固定获取顺序，或用原子映射操作保留冲突和 TTL 语义。[SocksTcpUpstream L149](../rxlib/src/main/java/org/rx/net/socks/upstream/SocksTcpUpstream.java#L149) · [SocksTcpUpstream L163](../rxlib/src/main/java/org/rx/net/socks/upstream/SocksTcpUpstream.java#L163) |
| P1 · Client SS | **SS TCP 路由拒绝时丢失首个 ByteBuf 的所有权。** 用户规则命中 BLOCK 会抛异常，入口 handler 在向下游交付已解密消息之前退出。该 handler 不会自动释放消息，此分支也没有关闭连接。 | 以交付下游为所有权边界，在此前的同步失败分支释放一次并关闭前端。用正常策略拒绝断言引用计数归零。[RssRuntime L614](../rxlib/src/main/java/org/rx/util/rss/RssRuntime.java#L614) · [SSTcpProxyHandler L25](../rxlib/src/main/java/org/rx/net/socks/SSTcpProxyHandler.java#L25) |
| P1 · Client SS | **慢建连期间 SS TCP 可积压堆外缓冲。** 每次读入数据都向同一连接 future 添加捕获消息的监听器。成对的读写背压在建连成功后才安装，此前消息未进入出站写队列，水位和 pending bytes 看不到。 | 建连前暂停读，对已经到达的缓冲设置字节限额，成功按序转交，失败、取消和关闭统一释放。[SocksTcpFrontendRelayHandler L18](../rxlib/src/main/java/org/rx/net/socks/SocksTcpFrontendRelayHandler.java#L18) · [SSTcpProxyHandler L42](../rxlib/src/main/java/org/rx/net/socks/SSTcpProxyHandler.java#L42) |
| P1 · Client SS | **SS UDP 长寿命监听器保留历史 socket 和路由。** 每个新出站 socket 都把捕获 key 和 future 的回调挂到入站监听器的 closeFuture 上。出站关闭仅从池中移除。每条路由也在共享出站 socket 上注册关闭回调，路由缓存淘汰后闭包仍可能被持有。 | 让每个父 channel 只遍历当前活动对象，或在子对象关闭、路由淘汰时解绑对应监听器。验收时同时看活动池大小和已关闭 channel 的堆引用。[SSUdpProxyHandler L462](../rxlib/src/main/java/org/rx/net/socks/SSUdpProxyHandler.java#L462) · [SSUdpProxyHandler L678](../rxlib/src/main/java/org/rx/net/socks/SSUdpProxyHandler.java#L678) |
| P1 · 两端 SOCKS，Server 优先 | **失败的 TCP 慢速建连路径最多发起 17 次尝试。** 处理器只判断重连委托非空，而服务端总会创建这个委托。默认无订阅仍可追加 16 次连接，条件中没有前端存活检查或总时间预算。上游不可用时，新建 socket 数和返回失败的延时都会放大。 | 仅在选到了可重试候选时重试，按错误类型区分，加入总时限并随前端关闭取消。对无候选路径验证只发起一次连接。[Socks5CommandRequestHandler L429](../rxlib/src/main/java/org/rx/net/socks/Socks5CommandRequestHandler.java#L429) · [SocksProxyServer L42](../rxlib/src/main/java/org/rx/net/socks/SocksProxyServer.java#L42) |
| P1 · Client DNS | **DNS 热更遗留 H2 缓存的后台线程。** 同端口重载也会重新设置拦截器；每次非空设置都新建缓存。默认 HYBRID 每个实例创建两个工作线程并安排清理任务，替换、清空以及 DNS 服务关闭时均缺少旧拦截缓存的关闭。 | 配置相同时复用实例；确需更换时让在途查询结束，再 flush/close 所拥有的缓存。检查启用响应缓存时的销毁路径。[RssClient L957](../rxlib/src/main/java/org/rx/util/rss/RssClient.java#L957) · [DnsResolverSupport L183](../rxlib/src/main/java/org/rx/net/dns/DnsResolverSupport.java#L183) · [DnsServer L58](../rxlib/src/main/java/org/rx/net/dns/DnsServer.java#L58) · [H2StoreCache L560](../rxlib/src/main/java/org/rx/core/cache/H2StoreCache.java#L560) |
| P1 · Client DNS | **冷 DNS 缓存在 I/O 线程上读取 H2。** UDP/TCP 查询在投递异步解析前同步访问拦截缓存，响应缓存路径也同步查表。默认 HYBRID 的 L1 未命中后可能读数据库或等待同键读取完成，一次冷查询可拖慢同一 loop 上的其他请求。 | 保留纯内存命中的快速路径，将 L2 交给有并发上限的执行器并合并同键查询。分开观察冷热缓存下的 DNS P99 和 EventLoop 延迟。[DnsResolveCore L74](../rxlib/src/main/java/org/rx/net/dns/DnsResolveCore.java#L74) · [DnsResolveCore L363](../rxlib/src/main/java/org/rx/net/dns/DnsResolveCore.java#L363) · [H2StoreCache L1262](../rxlib/src/main/java/org/rx/core/cache/H2StoreCache.java#L1262) |
| P1 · Client SS | **SS UDP 直连域名时，等待解析的数据报未计入最终写限额。** 直连路由保留未解析目的地；发送侧有最终保护时会推迟检查，最终 handler 又先发起异步解析，稍后才计量待写字节。即便底层复用 DNS 查询，每包仍持有缓冲和完成回调。 | 按目的地合并等待者并尊重 DNS TTL，对等待期设置包数、字节数和超时，上限覆盖关闭和解析失败分支。[SSUdpProxyHandler L296](../rxlib/src/main/java/org/rx/net/socks/SSUdpProxyHandler.java#L296) · [Sockets L1406](../rxlib/src/main/java/org/rx/net/Sockets.java#L1406) · [UdpFinalEgressGuardHandler L78](../rxlib/src/main/java/org/rx/net/udp/UdpFinalEgressGuardHandler.java#L78) |

### 其他可操作项

- **独立 SOCKS5 UDP 客户端的接收回调存在重复释放。** API 注释让回调释放 payload，异步派发完成时库也会释放 decoded。首轮回环收到了正确 payload，但日志实际记录了 `IllegalReferenceCountException: refCnt: 0, decrement: 1`。应明确回调期间借用、异步保留须 retain，或由单一接收者拥有，并使文档、实现、测试一致。RSS 上游传入已有 UDP channel 时走另一分支。[Socks5Client L82](../rxlib/src/main/java/org/rx/net/socks/Socks5Client.java#L82) · [Socks5Client L162](../rxlib/src/main/java/org/rx/net/socks/Socks5Client.java#L162) · [Socks5Client L273](../rxlib/src/main/java/org/rx/net/socks/Socks5Client.java#L273)
- **SS UDP 下行按来源限额有并发计数竞争。** 一个 loop 把计数减到零后移除映射，另一个 loop 可在两步之间重新预留同一个计数器。下一次读表会少算。按来源原子完成预留和归还，或把操作固定到同一 EventLoop。[SSUdpProxyHandler L730](../rxlib/src/main/java/org/rx/net/socks/SSUdpProxyHandler.java#L730) · [SSUdpProxyHandler L751](../rxlib/src/main/java/org/rx/net/socks/SSUdpProxyHandler.java#L751)
- **认证热更会短暂拒绝未变更的用户。** 两张共享表依次 clear/putAll，密码随后发布，读路径没有同一把锁。建议将两张表与认证配置按同一版本原子发布，保留活跃会话的统计对象。[RssAuthenticator L82](../rxlib/src/main/java/org/rx/util/rss/RssAuthenticator.java#L82) · [RssAuthenticator L111](../rxlib/src/main/java/org/rx/util/rss/RssAuthenticator.java#L111)
- **TCP 小包转发每次读取都执行 flush。** 已建立的上下行连接可以对同一轮 read batch 合并 flush，握手和关闭仍及时 flush。用短请求 P99、CPU 和实际 flush 次数评估收益。[SocksTcpFrontendRelayHandler L31](../rxlib/src/main/java/org/rx/net/socks/SocksTcpFrontendRelayHandler.java#L31) · [SocksTcpBackendRelayHandler L23](../rxlib/src/main/java/org/rx/net/socks/SocksTcpBackendRelayHandler.java#L23)
- **可选 DoH 串行等待 A 和 AAAA。** 任一族查询报错返回 null 会让整次结果回退，即使另一族已经有答案。可以分别保留成功结果，比较并发双查询与按需族查询的长尾。[DoHClient L95](../rxlib/src/main/java/org/rx/net/dns/DoHClient.java#L95)

## 修复前的双栈支持矩阵

箭头左侧表示客户端连接代理的地址族，右侧表示代理访问目标的地址族。回环目标为 IPv4 或 IPv6 字面量。

| 功能 | 当前结论与证据 |
| --- | --- |
| SOCKS5 普通 TCP / UDP | **实测四组通过**：4→4、4→6、6→4、6→6。每组验证 CONNECT、UDP ASSOCIATE 以及调用者提供 UDP channel 的收发。另用原始 IPv6 ATYP 0x04 的 CONNECT 请求完成密码认证与 TCP 回显。 |
| Shadowsocks AES-256-GCM TCP / UDP | **实测四组通过**。TCP 校验建立后的连续回显，UDP 校验多包回显。使用通配监听；实测平台为 Windows NIO。[RssRuntime L591](../rxlib/src/main/java/org/rx/util/rss/RssRuntime.java#L591) · [UdpManager L224](../rxlib/src/main/java/org/rx/net/socks/UdpManager.java#L224) |
| 固定入口 udp2raw | **IPv4 与 IPv6 回环均通过**。IPv6 用例覆盖 SOCKS 入口、两个代理间的数据通路和 IPv6 echo，RPC 控制由同进程测试 facade 提供。真实网络 RPC、Linux epoll、多网卡及外部 udp2raw 程序均未验证。[Udp2rawCodec L99](../rxlib/src/main/java/org/rx/net/socks/Udp2rawCodec.java#L99) |
| 域名与远端 DNS | A、AAAA、双地址族缓存分离已有定向测试。Netty 解析顺序是 IPv4 优先。仓库自带 `remoteServers` 只有两个 IPv4 地址，纯 IPv6 且无 IPv4 转换时，使用 REMOTE DNS 的出口需要配置可达的 IPv6 DNS。默认 TCP 建连未发现对两个地址族并行竞速，UDP 的单次解析只取一个地址。[默认配置](../rxlib/src/main/resources/rx.yml#L72) · [DnsClient L195](../rxlib/src/main/java/org/rx/net/dns/DnsClient.java#L195) · [Sockets L386](../rxlib/src/main/java/org/rx/net/Sockets.java#L386) |
| 公网源地址自动白名单 | 服务端可登记 RPC 对端的 IPv6。客户端在 RPC 前先调用只接受公网 IPv4 的观测器，失败后整次任务跳过。新客户端仅有 IPv6、且无既有放行记录时，可能连不上已开启白名单的出口。建议保留鉴权，由服务端独立登记实际 RPC 对端，再处理可选公网观测值。DDNS/WoW 的 IPv4 发布为另一项功能。[RssRuntime L208](../rxlib/src/main/java/org/rx/util/rss/RssRuntime.java#L208) · [PublicIpObserver L75](../rxlib/src/main/java/org/rx/net/PublicIpObserver.java#L75) · [RssRpcApp L134](../rxlib/src/main/java/org/rx/util/rss/RssRpcApp.java#L134) |
| 与第三方 SOCKS5 上游互操作 | 普通及预热 TCP 客户端把 IP 字面量也编码为 DOMAIN；本库服务端会重新识别文本 IP，所以自连用例可以通过。严格按 ATYP 处理的外部代理仍有兼容风险。建议按实际地址类型发送 IPv4/IPv6/DOMAIN，并保留真正域名的远端解析。[Socks5ClientHandler L212](../rxlib/src/main/java/org/rx/net/socks/Socks5ClientHandler.java#L212) · [Socks5WarmupHandler L139](../rxlib/src/main/java/org/rx/net/socks/Socks5WarmupHandler.java#L139) |
| IPv6 文本、内网与接口 | 普通 `[IPv6]:port` 可解析。Java 8 本机实测 `[fe80::1%7]:1080` 经项目解析后 scope 从 7 变为 0，需要指定出接口的链路本地目的地受影响。`Sockets.isPrivateIp(fd00::1)` 实测为 false，与方法注释对 ULA 的描述不同。应明确 ULA 白名单策略，再决定调整匹配还是文档。[Sockets L1722](../rxlib/src/main/java/org/rx/net/Sockets.java#L1722) · [Sockets L1939](../rxlib/src/main/java/org/rx/net/Sockets.java#L1939) · [Sockets L1992](../rxlib/src/main/java/org/rx/net/Sockets.java#L1992) |

## 验证与验收指标

使用上述提交的独立临时源码副本，在 Java 8 下离线运行 Maven，显式设置 `-DskipTests=false`。定向套件包括 DNS、路由、空闲处理、SS 编解码、SOCKS/SS 收发、链式代理和 udp2raw。双栈变体只调整测试端点、独立监听端口和测试端的 IPv6 报文编解码。采用同一套 payload 回显断言。最终同一轮 **71 项通过，0 失败、0 错误、0 跳过**。中间一次 SS 变体复用了固定测试端口，改为独立端口后全量重跑。个别异步日志异常单独记录在上文。

后续性能验收宜固定并发、报文大小、上游延迟、缓存冷热及压缩配置，对同一负载重复测量。建议优先记录：

- **建连和转发**：握手/首字节 P50、P95、P99，失败请求实际连接尝试数，活跃连接数、每秒字节数、小包 PPS、每核 CPU。
- **EventLoop 与堆外内存**：任务队列、调度延迟 P99、Netty allocator 的 usedDirectMemory、已分配与已释放缓冲差值、待写字节、不可写 channel 数；另计建连前与 DNS 等待期持有的字节。
- **DNS 与 UDP**：按地址族拆分成功率、DNS 冷热命中和 P99、UDP 排队与丢弃原因；多次热更后记录 H2 工作线程数，路由自然轮换后核对活动 socket 与保留对象数。[现有网络指标](../rxlib/src/main/java/org/rx/diagnostic/DiagnosticNetMetrics.java#L101) · [现有堆外占用告警](../rxlib/src/main/java/org/rx/diagnostic/DiagnosticMonitor.java#L283)

线上仅对选定 IPv4 普通线路做了上述 UDP 采样。1 核 / 768 MiB 压测、真实公网 IPv6 路径、第三方互操作及优化收益均未验证。
