# rxlib 最新测试与架构收口 Review（2026-11-28）

- 审查基线：[1503366f](https://github.com/RockyLOMO/rxlib/commit/1503366f74ccf5780945a51c24bea57a7b8b76ab)，北京时间 2026-11-28 00:01:23，merge docs。
- 关联文档：[测试及基准记录](test-benchmarks.md)、[Roadmap](roadmap-plan.md)、[网络架构](net-architecture.md)。
- 本文汇总最近几轮讨论、源码发现、实施边界与验收要求。保留现有文档结构，单独新增本次 Review，不覆盖历史测试或原 Roadmap。
- 目标：安全、正确性、稳定性为硬约束；优先降低真实流量和 CPU，内存有界。服务端严格按 1 vCPU / 768 MiB 整机（含 OS）验收；Java 8 master、JDK 21 回归，遵守 AGENTS.md。
- **本次明确决策：优先实施 SS 用户粒度限速；现有全局限速的缺陷只记录，不处理、不改动其实现或默认行为。NAT 打洞及公网 TCP Relay 仅记录设计，不在本轮实施。**
- 本 Review 是静态源码审查与仓库既有测试报告分析，未独立运行测试或修改代码。拟议类名、配置、容量和验收阈值不是已实现功能或实测结果。

## 1. 最新测试结果与待补齐验收

### 1.1 已记录结果

依据 [test-benchmarks.md §4](https://github.com/RockyLOMO/rxlib/blob/1503366f74ccf5780945a51c24bea57a7b8b76ab/docs/test-benchmarks.md#L103-L127)。实施/测试报告涉及 68e7f887、8062526c，不能等同于对当前文档提交全部代码重新执行的回归。

- TransportCoreTest：6/6 通过，报告 PARANOID LEAK 0；覆盖纯 TCP 无 UDP socket、daily、FRAME_READY、背压/半关闭、鉴权拒绝、认证分片+FEC。
- JDK21：DDNS、WoW、ReviewFixes、RSS 合计 99 run、0 fail、报告 0 leak；文档 RssTest 的通过/skip 口径需在下次报告以原始 Surefire 数字统一。
- JDK8：报告 DDNS 6（1 skip）、WoW 7、ReviewFixes 5，0 fail/0 leak；未记录同等完整 RSS/Core/RPC 回归矩阵。
- 公网 VPS：Debian12，1 vCPU/960 MiB，181.8 ms RTT、约 10%–15% 丢包，真实 SS aes-256-gcm，交错顺序两轮，每轮 512 KiB 冷下载、512 KiB 热下载、256 KiB 上传、58×256 B TCP 交互、150×256 B 普通 UDP Echo。

| Profile | R1/R2 持续交互 P95 ms | R1/R2 P99 ms | 热下载均值 KiB/s | 上传均值 KiB/s | UDP 送达率均值 | 总流量均值 KiB |
|---|---:|---:|---:|---:|---:|---:|
| plain | 999.2 / 1379.7 | 1791.3 / 1814.8 | 15.55 | 119.60 | 80.35% | 1713.85 |
| daily | 844.2 / 773.7 | 1535.2 / 1591.2 | 17.05 | 131.90 | 87.00% | 1800.00 |
| weak | 395.2 / 424.9 | 981.6 / 3864.3 | 13.55 | 96.85 | 84.35% | 2138.50 |

daily 综合表现较均衡；相对 plain 总流量均值 +5.0%，weak +24.8%。weak 交互 P95 明显改善，但第二轮 P99 3.864s，热下载/上传均值不占优。两轮实网小样本只支持描述性判断，P99 接近极值，不宜宣称统计显著或因果已证明。Netty 堆外报告 <=2.4 MiB、VmRSS <=470 MiB，不等同于整机 768 MiB 合格；本轮缺完整 CPU/有效交付量、OS/cgroup 内存及长期压力数据。game 未纳入此公网对照。

### 1.2 P1：weak 大流量及尾部停顿

报告已指出热下载部分阶段 fecParityBytes=0 后触发 TCP 补传。[AdaptiveTcpStream.java L110-L157](https://github.com/RockyLOMO/rxlib/blob/1503366f74ccf5780945a51c24bea57a7b8b76ab/rxlib/src/main/java/org/rx/net/rss/tcp/AdaptiveTcpStream.java#L110-L157) 中发送窗口预检、排空与 fec.write 相互关联，需用指标和复现定位原因，不能仅以增大 FEC/窗口解决。

- [ ] 分别统计原始 UDP DATA、parity、恢复数据、TCP repair、直接 TCP、ACK、探测、窗口排空原因、重传比例及拥塞；定位 FEC 成组、反馈、突发回落、尾包修复和可靠窗口的交互。
- [ ] 大流持续发送时若 UDP/FEC 收益不足，及时有界回落 TCP；保持弱网短流优势，不默认提高冗余或扩大窗口，不重新引入已淘汰的独立 Repair Wire。
- [ ] 分开测试短流、持续单大流、同会话混合流、并发独立短/长连接及高 BDP；观察 P50/P95/P99/Max、超时与公平性，不只看成功消息的延迟。
- [ ] 固定随机/突发丢包种子、多轮交错公网复测，覆盖 0/1/5/10/15% 丢包、方向不对称、低/高 RTT；对比相同有效工作量下总 IP bytes、CPU/MiB、交付率和资源峰值。保留历史基准，不复活已移除的单协议依赖。
- [ ] 优先保留已验证 daily/weak/game XOR 内核、zlib3、LZ4、认证分片；没有收益数据不做核心算法重写。

### 1.3 P1：Transport Core / RPC / RSS 完整回归

- [ ] 双 JDK clean build，显式 -DskipTests=false 与 PARANOID；补 RemotingTest（方法/响应/事件/重连/超时/池/非幂等调用）、NameserverImplTest 多节点同步、RRP、DNS、SS/SOCKS、Core 与 RSS 四 profile。
- [ ] RPC 继续通过 shared Transport Core 使用 daily；保留应用 Fury、事件、请求/响应语义。确认没有旧 Hybrid 整消息重发与新 FEC/TCP 修复双重执行，认证先于昂贵解析；传输成功不等于方法成功。重连不可盲目重放有副作用调用。
- [ ] 完整外部承载、固定 UDP、嵌套 SOCKS5、IPv4/IPv6、真实有效 MTU、热更/排空/回滚；Linux epoll/SO_REUSEPORT/nft（适用时）。生产旧路径切换及真实 WoW/QUIC/HTTP3 验收依原 Roadmap，本文不改部署。
- [ ] 1 vCPU / 768 MiB 整机或明确等效约束，统计 OS、JVM heap/direct、native/socket、cgroup peak、CPU throttling、timer/线程数；长时压力与资源回收。game 公网对照，认证分片 64 上限、MTU=256、前片失败 promise、实际 backing capacity。
- [ ] 记录源码 commit、命令、实际 JDK/Netty/OS、样本数、种子、测试 run/pass/skip/fail/error、双向真实 IP bytes、有效交付及失败。历史测试不当成最新验收。
- [ ] 文档架构中“任意网络条件下数据 100% 必达”应改成有条件的可靠传输：可靠 TCP 通道存在才可兜底；断线或所有路径不可用时应报告失败，普通 UDP 不承诺可靠交付。当前双 NAT Relay 未实现。

P2 保留原 Roadmap 的动态 PMTU/CHUNK、非地址 DNS RR TTL cache、连接复用、EDNS 等事项；根据实际瓶颈安排。DDNS 保留在应用层；此前仅本地 hook 强制 API Key 的校验问题在当前 [RssClient.java L513-L523](https://github.com/RockyLOMO/rxlib/blob/1503366f74ccf5780945a51c24bea57a7b8b76ab/rxlib/src/main/java/org/rx/util/rss/RssClient.java#L513-L523) 已按非空 domains 条件修正，报告含 DdnsJobTest，不再列为未修缺陷；实际 provider 更新与脚本执行仍按部署验收。

## 2. P1：RSS Geo 更新与路由一致性

当前 GeoSite 已有完整域名 hash、UltraDomainTrieMatcher、Aho-Corasick 关键词、预编译/可复用正则；GeoIP CIDR 索引、按 code/attribute 编译缓存。没有证据需替换底层库。优先正确性，再 CPU/内存。

### 2.1 定时 reloadAsync 未必真正更新远端文件

[V2RayGeoManager.java L219-L275](https://github.com/RockyLOMO/rxlib/blob/1503366f74ccf5780945a51c24bea57a7b8b76ab/rxlib/src/main/java/org/rx/net/support/V2RayGeoManager.java#L219-L275) 每日 06:30 调用 reloadAsync，异步 task 调用 load(false, ...)，而 [V2RayGeoManager.java L551-L577](https://github.com/RockyLOMO/rxlib/blob/1503366f74ccf5780945a51c24bea57a7b8b76ab/rxlib/src/main/java/org/rx/net/support/V2RayGeoManager.java#L551-L577) 仅在 force 或文件不存在时下载。现有文件存在时通常重新读取本地文件。另当前先 move 下载文件再由 load 解析，失败可能覆盖磁盘上一有效版本。

- [ ] 分离首次加载、本地重载、远端增量更新；有远端 URL 时按周期真正检查更新。支持 ETag/Last-Modified（对端支持才用），无变化不重新构建，失败退避；纯本地配置不产生网络请求。
- [ ] 下载到临时文件，检查 HTTP 状态、大小、数据格式及实际所需规则，成功解析/编译后安全替换有效文件与快照，失败保留旧内存及磁盘版本；防止旧任务覆盖新配置。记录版本/摘要、成功时间及错误指标。
- [ ] 更新测试：HTTP 304/200、错误响应、损坏/过大文件、断网、URL 切换、并发 reload/close、本地文件更新和进程重启。

### 2.2 已编译用户 matcher 长期持有旧 Geo 数据

[UserRuleMatcher.java L450-L526](https://github.com/RockyLOMO/rxlib/blob/1503366f74ccf5780945a51c24bea57a7b8b76ab/rxlib/src/main/java/org/rx/util/rss/UserRuleMatcher.java#L450-L526) 在 matcher==null 时才重试获取，非 null 后无 generation 校验。[V2RayGeoManager.java L310-L342](https://github.com/RockyLOMO/rxlib/blob/1503366f74ccf5780945a51c24bea57a7b8b76ab/rxlib/src/main/java/org/rx/net/support/V2RayGeoManager.java#L310-L342) 会替换索引并延迟关闭旧对象，但不能自动替换用户/默认规则的 matcher。更新可能不生效并使旧对象继续被引用。

- [ ] 建议轻量 GeoSnapshot/generation，后台从一致的 GeoIP/GeoSite 版本编译受影响的默认及 SS 用户规则，成功后一次原子发布；旧规则读者安全退出后释放旧资源。与 RSS 有效配置快照的热更/回滚协调。
- [ ] 冷启动与未知 code：显式定义数据未就绪策略，禁止将未加载的 BLOCK 安全规则默认为放行；不得无声使用半套配置。更新失败保留上一有效策略。禁止 I/O 热路径触发阻塞式 DNS/编译。
- [ ] 测试同一个已编译用户/默认规则在 GEO A→B 成功切换后真实命中变化，DIRECT/PROXY/BLOCK、attribute selector、IP v4/v6、规则顺序、DNS 与实际 TCP/UDP 路由一致，旧连接按定义处理。损坏更新保留旧判定。
- [ ] 保持 unresolved host 传递；不为了 GeoIP 强制本地解析所有域名，GeoIP 仅在已有/策略允许的实际 IP 上使用。

### 2.3 P2：按需降低 Geo 内存/CPU

默认 geosite:cn direct / geoip:cn direct / default proxy。已有按需 matcher 缓存，不另造一套 Trie。实测后评估只保留实际引用 code（兼顾公共查询 API）、相同版本/selector 共享 matcher、相同用户规则共享编译结果。仅在热点证明确有收益时加有界 route result cache，键需包含有效策略 generation、用户/规则身份、host/IP、必要源地址和目标端口等；不得仅按域名缓存跨用户结果。记录 cold load、refresh 及稳定态内存/CPU，不以对象搬包为优化。

## 3. P1（用户指定优先）：SS User 双向共享限速

### 3.1 现有实现及范围

[NetworkFlowControl.java L18-L75](https://github.com/RockyLOMO/rxlib/blob/1503366f74ccf5780945a51c24bea57a7b8b76ab/rxlib/src/main/java/org/rx/net/NetworkFlowControl.java#L18-L75) 使用全局 GlobalChannelTrafficShapingHandler；[NetworkTrafficConfig.java L15-L69](https://github.com/RockyLOMO/rxlib/blob/1503366f74ccf5780945a51c24bea57a7b8b76ab/rxlib/src/main/java/org/rx/net/NetworkTrafficConfig.java#L15-L69) 已有全局上传/下载 KiB/s、checkInterval/maxDelay 及背压配置；[Sockets.java L205-L220](https://github.com/RockyLOMO/rxlib/blob/1503366f74ccf5780945a51c24bea57a7b8b76ab/rxlib/src/main/java/org/rx/net/Sockets.java#L205-L220) 在统一 Channel 初始化安装全局 flow control。ProxyManageHandler 是按连接的统计与登录管理（[ProxyManageHandler.java L33-L65](https://github.com/RockyLOMO/rxlib/blob/1503366f74ccf5780945a51c24bea57a7b8b76ab/rxlib/src/main/java/org/rx/net/socks/ProxyManageHandler.java#L33-L65)），并非 SS 用户总带宽预算。UdpBackpressurePolicy 是 pending bytes/packets 资源保护，不是速率限制。

[RssRuntime.java L660-L678](https://github.com/RockyLOMO/rxlib/blob/1503366f74ccf5780945a51c24bea57a7b8b76ab/rxlib/src/main/java/org/rx/util/rss/RssRuntime.java#L660-L678) 为 ShadowUser 构建对应 ShadowsocksServer，适合绑定用户级共享预算。[ShadowsocksServer.java L53-L81](https://github.com/RockyLOMO/rxlib/blob/1503366f74ccf5780945a51c24bea57a7b8b76ab/rxlib/src/main/java/org/rx/net/socks/ShadowsocksServer.java#L53-L81) 显示 TCP 与 UDP 入口各有 pipeline，UDP 可能多 bind。[ShadowUser.java](https://github.com/RockyLOMO/rxlib/blob/1503366f74ccf5780945a51c24bea57a7b8b76ab/rxlib/src/main/java/org/rx/util/rss/ShadowUser.java) 无用户带宽配置。

**本轮优先实现用户粒度限速；通用组件允许以后按 listener、上游、连接等作用域绑定，但 RSS 只在 SS 入口应用，不给上游添加第二套限速，不改全局限速。**

### 3.2 统一抽象与计费

建议轻量 BandwidthLimitConfig + BandwidthLimiter（或按现有风格命名）及 NetworkFlowControl 的独立可选安装入口。公共 limiter 只认识共享作用域、方向、字节数、时间和允许/等待/拒绝结果，不依赖 SS/RSS/Geo/Fury。允许独立部署在普通代理或上游，但不在本次 RSS 上游路径配置或安装。

- 每个已启用限速的 SS 用户共享一份双向状态（上传、下载独立预算）；所有设备、源 IP、TCP 连接、UDP 路由、多 bind 和 EventLoop 聚合计费。身份用稳定且唯一的 SS 用户 key，不按内部 socksUser、源 IP 或 Channel 对象拆额度。校验身份重复、重命名与复用规则。
- 计费选 **SS 解密后的业务 payload 字节**：上传在 SS 用户入站认证/解码后、进入路由或 upstream 发送前，下载在向同一 SS 客户端写出前、SS 加密前。协议地址头是否计入需明确（建议不计）；TCP 与 UDP 统一定义。SS/上游协议头、FEC parity、复制、补传、内部转发和控制流量不重复扣额度。物理线路流量另计。用户主动 DNS 业务字节按统一边界计费，内部 DNS/RPC 不再次扣减。
- DIRECT 与 PROXY 全覆盖；plain/daily/weak/game、上游切换不改变用户额度。上传/下载方向始终以 SS 用户为准，不能直接照搬底层 read/write 命名。
- TCP 可评估 Netty 整形能力，但必须与 UDP 共用预算；不再为每协议或每连接分配完整用户速率。不限速时 fast path，不创建 limiter/timer/队列。按需选择低分配、有界 token bucket 或等价实现，单调时钟，安全处理整数上溢和最大 burst。

建议配置（仅提案，当前不支持；0/缺省表示不限速，负数等非法值应拒绝，明确 KiB/s = 1024 B/s）：

```yaml
shadowUsers:
  - username: userA
    ssPort: 6896
    bandwidth:
      uploadKiBps: 1024
      downloadKiBps: 5120
  - username: userB
    ssPort: 6898
    bandwidth:
      uploadKiBps: 0
      downloadKiBps: 0
```

独立方向可单独不限速。初版只暴露两个速率，burst 和调度按安全默认值处理；避免为每用户暴露大量调参字段。

### 3.3 TCP / UDP 实现及生命周期

- TCP 超额延迟/背压，不丢业务字节。与现有 TcpBackpressureHandler、连接初始化排队、半关闭、可写性及空闲超时协调。多种暂停原因必须同时清除才能恢复读取；禁止用单独 autoRead(true) 覆盖其他背压。不阻塞 EventLoop，不每包 sleep。待发字节/任务/等待时间有界，低速大 ByteBuf 可分段调度，避免永远等不到足额 token，公平分配给同用户多连接。
- UDP 超额默认丢弃完整数据报，避免游戏/QUIC 的长队列。上传在 [SSUdpProxyHandler.java L908-L942](https://github.com/RockyLOMO/rxlib/blob/1503366f74ccf5780945a51c24bea57a7b8b76ab/rxlib/src/main/java/org/rx/net/socks/SSUdpProxyHandler.java#L908-L942) 的早期认证后入口、路由缓存/待建连队列前控制；下载在 [SSUdpProxyHandler.java L848-L890](https://github.com/RockyLOMO/rxlib/blob/1503366f74ccf5780945a51c24bea57a7b8b76ab/rxlib/src/main/java/org/rx/net/socks/SSUdpProxyHandler.java#L848-L890) 的回程 SS 封包前控制，使用绑定的用户身份，不用共享上游反推。明确超额 drop/ByteBuf release、pending charge 和发送结果。合法最大 datagram 与 burst 的兼容须验证，不能因低速配置使所有正常包永久不可发送。
- TCP 接入点参考 [SSTcpProxyHandler.java L30-L75](https://github.com/RockyLOMO/rxlib/blob/1503366f74ccf5780945a51c24bea57a7b8b76ab/rxlib/src/main/java/org/rx/net/socks/SSTcpProxyHandler.java#L30-L75) 与 SS frontend/backend relay pipeline；上下行仅各一次收费，覆盖首包、直连、半关闭及异常。鉴权前恶意流量保护不等于用户速率预算，保留原有防护。
- 配置热更只改共享 limiter 的有效速率，不因限速变化重绑 listener；同一用户新旧 listener 排空期间共用预算。[RssRuntime.java L813-L847](https://github.com/RockyLOMO/rxlib/blob/1503366f74ccf5780945a51c24bea57a7b8b76ab/rxlib/src/main/java/org/rx/util/rss/RssRuntime.java#L813-L847) 可作为稳定绑定/更新边界，需与 reload 回滚一致。删除/禁用用户按原认证策略释放，未关闭旧会话不创建新的独立满额 limiter。更新 burst/token 不制造额外突发信用。
- 资源预算：仅限速用户分配状态；按用户及进程限制排队 bytes/任务数，复用 EventLoop/公共调度，不每包创建对象或 timer，不引入额外线程池/外部依赖。统计 admitted bytes、rate-limited/drop、delay、pending、实际吞吐；低频汇总，避免每包 log 和高基数指标。

### 3.4 验收（P1）

- [ ] 单/多 TCP、单/多 UDP、TCP+UDP 并发，多个源 IP、不同设备、多个 listener bind：同一用户各方向总速率满足配置；长期平均误差以合理 burst/计时容差验收，多连接不能倍增额度。不同用户互不扣费。
- [ ] 同时上/下载、单方向 0、两方向 0、未配置、低速大包、超额 UDP、持续 TCP、热点并发；验证有效 payload 计费与压缩/FEC/重传无重复计费。
- [ ] DIRECT/PROXY、四 profile、三类上游和切换，确认只在 SS 入口限速；普通 SOCKS、RPC、内部 DNS、上游未新增用户 limiter。已有背压资源保护继续生效。
- [ ] 无需 listener restart 的动态调速（含不限速→限速→不限速）、配置失败回滚、新旧 listener overlap、活跃会话关闭、鉴权失败、异常引用计数、TCP pause reason、半关闭无丢数据，关闭后无遗留调度/队列。
- [ ] 初始测试环境关闭旧全局限速，单独证明用户额度；另记录已有全局开启的交互现象但本轮不修它。无用户限速时行为和 CPU/流量/内存基线不回退；低配、多用户和 UDP 高频情况下实测。

### 3.5 已有全局限速 BUG / 风险（仅记录，暂不处理）

全局 handler 安装在通用 Channel initializer 上，可能对同一次代理业务的 SS frontend、内部 SOCKS 或 upstream 的不同 Channel 重复计费/整形；GlobalChannelTrafficShapingHandler 读写方向也不天然等同业务上传/下载。现有 loopback 跳过规则可避免部分重复，但不能证明所有路径只计一次。另 [NetworkFlowControl.java L38-L75](https://github.com/RockyLOMO/rxlib/blob/1503366f74ccf5780945a51c24bea57a7b8b76ab/rxlib/src/main/java/org/rx/net/NetworkFlowControl.java#L38-L75) refresh 只更新已创建的全局 handler，install 在禁用时跳过；从禁用热开启后，既有未安装 handler 的 Channel 可能不会获得新限速。

**本项明确 deferred：只记录静态风险和复现条件，不修复、不重构全局 handler、不改变其全局配置/安装/loopback 策略，不以其问题阻塞 SS 用户级限速的独立实现。** 新的通用预算/安装入口须避免自动挂到所有 Channel。若生产显式启用旧全局限速，应提示可能叠加，用户限速本身不承诺消除全局历史问题。现有 TCP/UDP 背压、MTU、pending limits 不属于重复业务限速，不能关闭。

## 4. P1：Nameserver 内部复制统一 RPC

最新源码并非仍用旧 UdpClient：[NameserverImpl.java L134-L157](https://github.com/RockyLOMO/rxlib/blob/1503366f74ccf5780945a51c24bea57a7b8b76ab/rxlib/src/main/java/org/rx/net/nameserver/NameserverImpl.java#L134-L157) 已使用 DatagramTransport + FuryUdpClientCodec；[NameserverImpl.java L441-L505](https://github.com/RockyLOMO/rxlib/blob/1503366f74ccf5780945a51c24bea57a7b8b76ab/rxlib/src/main/java/org/rx/net/nameserver/NameserverImpl.java#L441-L505) 将完整/增量对象直接编码后 ss.send，没有安装增强 handler、没有等待业务确认。DatagramTransport 是原始无连接数据报（[DatagramTransport.java L24-L32](https://github.com/RockyLOMO/rxlib/blob/1503366f74ccf5780945a51c24bea57a7b8b76ab/rxlib/src/main/java/org/rx/net/transport/core/DatagramTransport.java#L24-L32)），不等价于旧 UdpClient 的可靠逻辑消息。

### 4.1 问题

- 大快照可能超 MTU 或 UDP 最大报文，发送结果不处理，丢包后更新未必送达；直接反序列化的 replica 接收路径需核实身份认证、来源和资源边界，不能仅靠类 allowlist。
- acceptReplicaVersion 的 get/put 非原子，异步 apply 可能乱序；版本若混用不同消息类型、重启归零，需要定义 source epoch/seq 与完整同步恢复。applyFullSync 清空整个 DNS hosts，可能覆盖本地及其他来源注册；缺失删除、权威归属、快照原子性和多节点收敛测试。
- 当前 [NameserverImpl.java L275-L345](https://github.com/RockyLOMO/rxlib/blob/1503366f74ccf5780945a51c24bea57a7b8b76ab/rxlib/src/main/java/org/rx/net/nameserver/NameserverImpl.java#L275-L345) 使用 appName + remote IP 关联实例属性/删除，同 NAT 的同 app 实例可能共享/覆盖 attrs；不能用 IP 作为节点身份。独立 NodeRegistry 演进前也应明确不同来源/实例的身份与删除语义。

### 4.2 决策

注册、发现、属性、节点事件及 **Nameserver 服务间复制统一 RPC（daily 通过 Transport Core）**，而不是继续写一个私有 UDP 对象通信协议。RPC 已覆盖 Nameserver 所需可靠对象传输、请求响应及事件；直接 Core RAW 会增加重复序列化、分发及请求协议。原生 UDP 数据报用途仍使用 DatagramTransport；标准 DNS TCP/UDP 查询协议不改变。RPC 并不完全等价于所有 UDP 通信能力。

- [ ] Nameserver 复制采用有界的持久/按需复用 RPC 连接，增量 update/remove、请求确认、版本、断线重连恢复及按需完整快照。避免每次同步新连接或反射重复编码；不可达节点有退避，不阻塞其他 peers。
- [ ] 节点间强认证、授权 peer/来源、明确 source ownership、instanceId、epoch/monotonic version；串行或原子验证/应用；重试幂等，防重放/重复事件。远端 full snapshot 仅替换其权威分区，保留本地和其他节点数据；删除使用明确定义的 tombstone/lease 语义，防止旧快照复活实例。
- [ ] 快照有界、分页/分批（需要时），不构造无限大 ByteBuf；同步只必要活跃注册及变更，非必要历史统计不复制。兼容旧版本需要明确过渡窗口，不保留永久 UDP 双栈或未经认证回退。
- [ ] 用例：双/三节点、多同 IP 实例、并发注册删除、网络断开重连、丢包乱序、旧/重复版本、节点重启、超 MTU 大快照、未授权 peer、DNS 本地记录不被远端清空、无幽灵节点。
- [ ] 迁移完成后删除 Nameserver 专用 DatagramTransport replica socket、FuryUdpClientCodec 使用点及旧 sync port 配置（有明确迁移说明）；保留通用 DatagramTransport 和有其他消费者的 codec。旧 org.rx.net.transport.UdpClient 当前仍供旧打洞模块使用；待未来迁移或明确退役后删除，不因本轮 Nameserver RPC 改造偷偷实施 NAT 打洞。避免按名称误删外部 udpclient 承载。

## 5. P2：轻量 NodeRegistry 与 H2 冷数据

用户认可统一 NodeRegistry。服务发现、节点地址、状态、身份和 TTL 是公共控制面能力；Nameserver、未来 Rendezvous/Relay 可共用注册目录，允许同一进程按需启用，不混成单一大类。RPC 作为控制消息接口，Transport Core 提供数据传输。NodeRegistry 不依赖具体代理算法/业务协议，也不是分布式数据库。

### 5.1 数据模型及冷热边界

- 内存：在线 nodeId、必要服务/实例索引、当前经过验证的候选端点及协议/有效期、连接引用、在线 TTL、活跃协商/转发会话。离线及时移除；历史地址不能作为当前可达证据。会话/Channel/密码明文不落盘。
- H2：持久身份/授权（安全存储凭据或 verifier、最小必要字段）、节点长期元数据、低频自定义 attributes、持久服务配置、最后上线/离线时间、可选历史聚合与审计。历史公网 IP 默认不保存；过期路径与无业务价值的瞬时数据直接清除，不写每个 heartbeat/RTT/packet。
- 复用现有 EntityDatabase / EntityDatabaseImpl（H2）、执行器/连接管理，参考 [EDB](reference/EDB.md)、[RssUserTrafficStore.java L179-L203](https://github.com/RockyLOMO/rxlib/blob/1503366f74ccf5780945a51c24bea57a7b8b76ab/rxlib/src/main/java/org/rx/util/rss/RssUserTrafficStore.java#L179-L203) 与其批量 flush。复用组件不强制与 RSS 流量表共享同一数据库文件；同进程可合并连接池/缓存预算，按实际部署隔离。禁用模块不启动 DB、socket、timer。不引入 Redis/MySQL 独立进程。
- 初期三表：node（nodeId、身份/授权、长期元数据、lastSeen）；node_attribute（nodeId+key，有界值）；node_service（nodeId+appName+instanceId、持久服务配置）。服务 live 状态只在内存，重启需重新认证注册。按主键/必要索引分页读取，不全表加载。审计/历史表仅实际需要时扩展。

建议初始预算（不是实测/硬件承诺）：

| 项目 | 初始值 |
|---|---:|
| 在线节点 | 1024 上限 |
| 单节点候选端点 | 16 上限 |
| 单节点自定义 attributes | 8 KiB 上限 |
| 冷数据缓存 | 2 MiB 权重预算 |
| 待持久化队列 | 2 MiB 预算 |
| H2 数据缓存 | 8 MiB 初始目标 |
| 普通元数据/统计批量写 | 60s |
| 未来活跃打洞记录 TTL | 30s |
| 可选历史 | 7 天，历史 IP 默认关闭 |

NodeRegistry 自身（含其增量 H2/缓存/队列）目标约 16 MiB，初期验收上限 32 MiB；不含实际 TCP/UDP 会话及 Relay 数据缓冲，仍计入整机 768 MiB 总预算。H2 cache 配置非进程硬上限，需测 JDBC/MVStore、page cache、池、线程、堆/堆外。磁盘容量与 offline 节点/属性总量也应有限额、清理或管理员管理，不允许无限冷数据增长。

### 5.2 生命周期与落盘

- [ ] 认证 nodeId 唯一，服务实例按 nodeId/appName/instanceId，IP 只是可变属性；相同 NAT/地址变化不改变身份。租户/访问授权隔离，候选端点私密信息只向获授权对端提供。
- [ ] H2 查询/写入不在 EventLoop 上阻塞。低频元数据按 key 合并、有界异步批量写；失败退避、队列满时明确策略，不阻塞传输也不丢授权撤销。身份/ACL/禁用等安全关键变更可靠持久化及缓存失效，不能等统计 flush，无法确认授权时 fail closed。
- [ ] 在线使用已有会话 heartbeat 与一个共享 TTL 清理机制，不每 node/endpoint 创建定时器。离线节点清除热状态；历史统计/审计不跨节点全量复制，备份/恢复不恢复旧 online 及旧 relay。
- [ ] 按需功能开关：普通 RSS 不额外启动 NodeRegistry；Nameserver 启用必要目录；未来协调与 relay 独立启用。复用既有 EventLoop、线程池和调度，限制节点数、候选数、冷缓存、队列及查询结果。
- [ ] 测 0/100/1024 在线及大量离线资料、反复连接/重启、H2 慢盘/满盘/错误、授权变更、TTL、内存峰值、磁盘写量、并发查询、资源关闭。只有实际测得低配预算达标才算完成。

## 6. P2：统一地址发现；NAT 打洞/公网 Relay 仅 ADR

### 6.1 当前能力与共享

[Sockets.java L1884-L1924](https://github.com/RockyLOMO/rxlib/blob/1503366f74ccf5780945a51c24bea57a7b8b76ab/rxlib/src/main/java/org/rx/net/Sockets.java#L1884-L1924) 枚举本地 IPv4，过滤 loopback、virtual、down 等；不含完整 IPv6/候选模型。[Sockets.java L1758-L1782](https://github.com/RockyLOMO/rxlib/blob/1503366f74ccf5780945a51c24bea57a7b8b76ab/rxlib/src/main/java/org/rx/net/Sockets.java#L1758-L1782) 与 [PublicIpObserver.java](https://github.com/RockyLOMO/rxlib/blob/1503366f74ccf5780945a51c24bea57a7b8b76ab/rxlib/src/main/java/org/rx/net/PublicIpObserver.java) 提供公网 IPv4 HTTPS 观测/缓存。[NameserverClient.java L212-L218](https://github.com/RockyLOMO/rxlib/blob/1503366f74ccf5780945a51c24bea57a7b8b76ab/rxlib/src/main/java/org/rx/net/nameserver/NameserverClient.java#L212-L218) 上报 app ID 与单一 publicIp；Nameserver 注册时观察 TCP 对端。[UdpHolePunchRegistry.java L49-L79](https://github.com/RockyLOMO/rxlib/blob/1503366f74ccf5780945a51c24bea57a7b8b76ab/rxlib/src/main/java/org/rx/net/udp/UdpHolePunchRegistry.java#L49-L79) 每 peer 保存一个观测 UDP endpoint。以上地址能力适合抽取共享 AddressDiscovery/EndpointObserver（名字可调整），不意味着当前已具备多候选 ICE 或已实现中继。

- 候选是 (地址族、IP、端口、协议、候选类型、观测来源、TTL/验证状态)，不是仅枚举 IP。收集合理可用的局域网 IPv4/IPv6、全局 IPv6、实际 UDP socket 外部映射；按需选择虚拟/多网卡，去重且有界，不广播所有接口与隐私地址。
- HTTPS 公网 IP、TCP 观测端点、UDP 实际映射不能互相替代；按网络/接口/用途缓存且失效，不能把单个公网 IP 缓存当成全部 NAT 映射。NAT 端口与有效性受 socket/目标/路径影响，需实际验证。DDNS/WoW/Nameserver 共享适用的公共地址观察，保留业务用途及各自信任策略。
- 名称：STUN 类能力观察外部端点；Rendezvous 协调获授权双方的候选；Relay 承载业务数据。ICE 是候选/连接检查/选择框架，不必宣称现有私有打洞协议符合 ICE/STUN 标准。

### 6.2 未来设计（不实施）

一个可达公网节点可按需承载 Nameserver + Rendezvous + Relay，复用 NodeRegistry，不再维护完全独立的重复注册表。局域网/公网直连可用时优先验证；UDP 直连失败才按需打洞；双方 NAT 后无直接 TCP 时，A/B 均主动连接公网节点，B 反向注册授权服务，A 通过公网可靠中继与 B 通信。公网 TCP 主通道可先建立，UDP 快路径失败仍有真实可用的 TCP 回退；无任何可达可靠路径时必须报错。当前 UdpHolePunchServer 不是完整 relay，现有 RrpClient/RrpServer 是未来可评估的反向代理复用候选，不保证现成支持双 NAT Core 集成。

未来需独立设计双端身份、服务授权、候选隐私、端到端认证加密、防重放、目标绑定、限额/背压、断线清理、路径迁移、真实 NAT 及 IPv6 验证；协调不承担业务数据，中继不重复压缩/FEC。普通公网 RSS 场景默认不启用该能力。**本轮只记录上述思路，不迁移旧打洞代码、不增加公网端口/中继/保活、不安排实际部署。** 旧 UdpClient 删除的最后依赖如仍存在，应报告阻塞，等待明确退役或后续迁移决策。

## 7. 实施优先顺序与本轮边界

| 优先级 | 工作项 | 处理 |
|---|---|---|
| P1 最先 | SS 用户粒度共享双向限速及通用可选预算 | 实施 |
| P1 | weak 大流 FEC/repair 及极端停顿 | 优化、对照复测 |
| P1 | Geo 定时下载、文件安全替换及 matcher generation | 修复 |
| P1 | Nameserver 复制迁移 RPC、可靠性/身份/来源一致性 | 修复与迁移 |
| P1 | 双 JDK/Core/RPC/真实承载/Linux 低配/热更回归 | 验收 |
| P2 | NodeRegistry 在线目录 + EntityDatabase/H2 冷数据 | 规划后按阶段实施 |
| P2 | 共享地址发现/观察、Geo 按需性能、动态 PMTU、DNS EDNS | 按收益实施 |
| Deferred | 现有全局限速重复整形与热开启风险 | **仅记录，不处理** |
| ADR only | NAT 打洞及公网 TCP Relay | **仅记录，不实施** |
| 暂缓 | 无性能证据的进一步包名/接口重构 | 不安排 |

每项独立可回归提交，不同时重写传输核心、业务协议和包结构。优先完成 SS 用户限速，并保证其未配置时无额外运行成本；不在 RSS 上游重复限速，不修改现有全局限速。完成实际验证后将结果追加 test-benchmarks.md、待办状态与 roadmap-plan.md 对齐；按 master 验证后 merge rss-21 的既有流程执行。本次只提交 Review 文档，未执行实现或发布。
