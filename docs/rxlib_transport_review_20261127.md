# RSS 上游、Profile 与传输收口 — 实施后 Review（2026-11-27）

- 审查基线：[d08ecd3c](https://github.com/RockyLOMO/rxlib/commit/d08ecd3c1c395d5d8079f85de759ac1925eb4154)，北京时间 2026-11-27 16:55:51。
- 对比基线：[825c571b](https://github.com/RockyLOMO/rxlib/commit/825c571b78f7593b26865d9ee00a0574dbfca976)，第五版 [收口计划](rss-upstream-profile-consolidation-plan.md)；其后新增一个实施提交 `feat(rss): consolidate upstreams, profiles, DNS AOP, opt-in egress codec and renames`。
- 目标：正确性、安全与稳定性为硬约束，优先降低真实流量和 CPU 消耗，内存可适当放宽但必须有界，支持低配置 VPS。
- 范围：GitHub 最新源码及差异静态审查。本轮未独立编译、运行测试或操作现网；未找到此实施提交对应的完整双 JDK / Linux 实测报告。风险按源码证据和待复现条件描述，不把新增测试代码或历史通过记录当作本次验收结果。

## 1. 总体判断

核心架构已基本落实。现在从大规模结构重构转入稳定性、实际承载与性能验收阶段；优先解决热更新、能力匹配、DNS 生命周期及分路径健康状态。保留已验证的 FEC、复制、AES、zlib/LZ4、认证分片及恢复算法，不为抽象而重写。

| 项目 | 静态审查结果 |
|---|---|
| 四种 profile 固定算法 | 主体已实现 |
| 三类逻辑上游、统一池、单一入口 | 主体已实现 |
| Daily 系列类名调整 | 已实现 |
| 端口 codec、AES PSK、去除 RSS 专用证书 TLS | 已实现 |
| 全模式 DNS AOP 与 53 默认保护名单移除 | 主体已实现；策略和生命周期继续验收 |
| 独立真实 TCP egress codec | 已实现；双端配置、协议兼容待验证 |
| TCP FEC 不重复实际压缩 | 静态分层正确 |
| 普通 UDP 预留 AES/MAC 等开销 | 已实现 |
| TCP CHUNK 按协商 MTU 计算 | 已实现；尚非动态 PMTU |
| 分片 backing buffer 预算 | 已改进；物理内存待实测 |
| 多协议独立健康状态 | 未完全实现 |
| 热更新原子性与回滚 | 仍有风险 |
| 真实外部承载、Linux 低配性能 | 待验收 |

## 2. 已落实的关键改动

### 2.1 Profile 与命名

plain 保留原有 TCP 基础行为，普通 UDP 无传输增强（必须保护端口 AES-only 例外）。daily/weak：TCP adaptive FEC + 普通 UDP adaptive FEC；game：TCP adaptive FEC + 普通 UDP adaptive copies。增强普通 UDP 的 LZ4 可旁路，业务 codec 旁路不关闭 FEC/复制。53 统一 DNS AOP；TCP 实际压缩、AES、最终出口格式分别处理。

| 旧名称 | 新名称 |
|---|---|
| AdaptiveStream | AdaptiveTcpStream |
| DailyUdpWire | RssDatagramHandler |
| UdpDailyFec | AdaptiveUdpFec |
| UdpGameRedundancy | AdaptiveUdpReplication |
| DailyUdpCompression | RssDatagramCompression |
| DailyUdpFragmenter | RssDatagramFragmenter |
| DailyUdpCipher / DailyUdpMac | RssDatagramCipher / RssDatagramMac |
| RssHopTls | RssTcpCodecSelector（RSS 专用 TLS 分支移除） |

新增 RssPortPolicy、RssDnsInterceptor、RssUdpMtuBudget、RssEgressCodec、RssSocksCarrier 和 RssFixedUdpEntry；原计划建议的类型名称不是强制逐一实现，现有类型职责足够就不继续增加中间层。

证据：[RssTransport.java L27-L82](https://github.com/RockyLOMO/rxlib/blob/d08ecd3c1c395d5d8079f85de759ac1925eb4154/rxlib/src/main/java/org/rx/util/rss/RssTransport.java#L27-L82)、[RssClient.java L1741-L1801](https://github.com/RockyLOMO/rxlib/blob/d08ecd3c1c395d5d8079f85de759ac1925eb4154/rxlib/src/main/java/org/rx/util/rss/RssClient.java#L1741-L1801)、[RssEgressCodec.java L19-L97](https://github.com/RockyLOMO/rxlib/blob/d08ecd3c1c395d5d8079f85de759ac1925eb4154/rxlib/src/main/java/org/rx/net/RssEgressCodec.java#L19-L97)、[AdaptiveTcpHandler.java L36-L55](https://github.com/RockyLOMO/rxlib/blob/d08ecd3c1c395d5d8079f85de759ac1925eb4154/rxlib/src/main/java/org/rx/net/rss/tcp/AdaptiveTcpHandler.java#L36-L55)。

### 2.2 TCP UDP 快路径 MTU

[AdaptiveTcpStream.java L85-L88](https://github.com/RockyLOMO/rxlib/blob/d08ecd3c1c395d5d8079f85de759ac1925eb4154/rxlib/src/main/java/org/rx/net/rss/tcp/AdaptiveTcpStream.java#L85-L88) 已将固定 CHUNK 改为每连接协商后的预算。[AdaptiveUdpPort.java L25-L33](https://github.com/RockyLOMO/rxlib/blob/d08ecd3c1c395d5d8079f85de759ac1925eb4154/rxlib/src/main/java/org/rx/net/rss/tcp/AdaptiveUdpPort.java#L25-L33) 先扣除 32 B 外层会话/认证开销，再由 stream 扣 8 B FEC 最大头及 5 B stream header。

| 有效 RSS UDP payload 上限 | TCP 编码数据分段上限 |
|---:|---:|
| 1282 B | 1237 B |
| 1232 B | 1187 B |
| 1200 B | 1155 B |
| 1100 B | 1055 B |
| 1000 B | 955 B |

上述仅为静态保守预算，不是实际链路测量，也不含外部承载另计的开销。普通 UDP 使用独立实际 wire 预算，压缩后按预算分片，再 FEC/复制、认证/加密、最终 guard；不提前加密密文再压缩。现有代码支持建连协商 MTU，不意味着已支持动态 PMTU 和在途重分段。参见 [RssUdpMtuBudget.java](https://github.com/RockyLOMO/rxlib/blob/d08ecd3c1c395d5d8079f85de759ac1925eb4154/rxlib/src/main/java/org/rx/net/rss/RssUdpMtuBudget.java)、[SocksAdaptiveHandshake.java L84-L113](https://github.com/RockyLOMO/rxlib/blob/d08ecd3c1c395d5d8079f85de759ac1925eb4154/rxlib/src/main/java/org/rx/net/socks/SocksAdaptiveHandshake.java#L84-L113)、[RssDatagramHandler.java L575-L590](https://github.com/RockyLOMO/rxlib/blob/d08ecd3c1c395d5d8079f85de759ac1925eb4154/rxlib/src/main/java/org/rx/net/rss/udp/RssDatagramHandler.java#L575-L590)。

### 2.3 上一轮分片内存及本地写结果

[RssDatagramFragmenter.java L287-L321](https://github.com/RockyLOMO/rxlib/blob/d08ecd3c1c395d5d8079f85de759ac1925eb4154/rxlib/src/main/java/org/rx/net/rss/udp/RssDatagramFragmenter.java#L287-L321) 现已检查底层 backing capacity，超过 sliceLen + COMPACT_SLACK_BYTES 时使用紧凑缓冲区，否则按实际保留容量计费；增加 assembly 元数据预算，分配异常归还 charge。方向正确，避免小分片长期 pin 大接收缓冲区；仍须真实 pooled allocator / epoll 验证。此前多分片原始数据本地 write promise 聚合也已保留在新 handler，验收应继续覆盖前片失败、最后片成功、异常释放和 flush；不把本地写入成功视为远端交付成功。

## 3. P1：必须优先处理的问题

### P1-01 热更新发布与回滚不完整

证据：[RssRuntime.java L127-L186](https://github.com/RockyLOMO/rxlib/blob/d08ecd3c1c395d5d8079f85de759ac1925eb4154/rxlib/src/main/java/org/rx/util/rss/RssRuntime.java#L127-L186)。

reload 依次更换 DNS、入口 listener、upstreamSnapshot、authenticator、ShadowServer、有效策略和 activeConf；upstreamSnapshot 发布后 nextUpstream 被置 null。后续操作如果抛异常，catch 关闭待提交对象，但没有完整恢复已经发布的新引用及认证/配置状态。日志“keep previous runtime”不能保证运行态实际回到旧配置。不同引用读者也可能观察到不同 generation。

- [ ] 将所有可失败的构建、配置校验、listener 准备和资源申请尽量放在发布前。
- [ ] 对路由所需配置、上游、DNS 规则/缓存、用户认证视图建立一致 generation；普通 TCP/UDP 关联在创建时取得同一有效策略，不通过多个独立引用拼接新旧代。
- [ ] 必须先解绑再绑定的同端口 listener 使用明确事务阶段及恢复记录；不能假设所有网络资源都能预先成功绑定。发布后操作失败时真正恢复或明确进入受控故障态，不虚报回滚。
- [ ] 对 build、bind、auth reload、applyLiveConfig、RRp commit 等阶段注入失败；验证新旧用户、上下游端点、DNS、codec、连接池、旧快照 drain 与资源计数。并发创建会话时不出现混合 generation。
- [ ] 不为原子性引入每包全局锁；已建立普通业务会话按原契约完成或明确关闭，新连接使用新代。

### P1-02 按能力选择兼容候选，再按权重选路

证据：[RssClient.java L1637-L1648](https://github.com/RockyLOMO/rxlib/blob/d08ecd3c1c395d5d8079f85de759ac1925eb4154/rxlib/src/main/java/org/rx/util/rss/RssClient.java#L1637-L1648) 以及 createInSvr 的 TCP/UDP 路由选择。

当前先选择上游，再由 outboundForRoute 拒绝标准 SOCKS5 无法承载的保护/增强流量。plain 用户同时允许 RSS 和标准 SOCKS5，访问必须保护的 TCP 80 或自定义 UDP 保护端口时，可能随机选中不兼容出口而失败，即使仍有兼容 RSS 出口。拒绝不安全降级是正确的；问题在选择时序。

- [ ] 建连前按用户允许范围、有效 profile、实际 TCP/UDP codec、必须加密、hop/固定映射及必要 RPC 能力过滤候选，再执行健康、权重及源地址亲和性选择。
- [ ] 只有所有允许且兼容候选均不可用时才返回明确错误；fail-open 仅允许在兼容候选范围内按既有显式策略运行，不能绕过安全条件、用户上游名单或发送私有 frame 给普通 SOCKS5。
- [ ] 复用 generation 内的候选缓存，避免每包遍历。普通会话保持选定出口，不在已建立连接或 UDP association 中隐式迁移。
- [ ] 混合 1:1 RSS/标准 SOCKS5，分别测试普通、旁路、保护 TCP/UDP；覆盖零权重、故障、全部不兼容、热更、源地址 steering 与 DNS RPC 能力。安全与正确性优先于权重分布。

### P1-03 分能力健康状态，避免增加四套心跳

证据：[RssClient.java L1151-L1218](https://github.com/RockyLOMO/rxlib/blob/d08ecd3c1c395d5d8079f85de759ac1925eb4154/rxlib/src/main/java/org/rx/util/rss/RssClient.java#L1151-L1218)。pingUpstream 目前主要通过 Remoting.isHealthy(facade) 判断；没有 facade 的标准 SOCKS5 直接视为健康，不能代表数据面可用。

| 能力 | 应采用的已有反馈 |
|---|---|
| 可靠 TCP | CONNECT、认证及实际连接结果 |
| 普通 UDP | association/relay 初始化、实际响应及发送失败 |
| TCP adaptive UDP | 现有探测、FEC/ACK、路径回退结果 |
| RPC | 当前 remoting 健康及调用结果 |

- [ ] 单一上游身份保留各能力的独立状态/近期失败，区分未知、健康、降级、不可用；标准 SOCKS5 不因无 RPC 永久标记健康。
- [ ] Adaptive UDP 不通但 TCP 正常时继续可靠 TCP，不把整条上游判死；RPC 正常但普通 UDP 不通也不能宣称 UDP 健康。
- [ ] 优先被动业务反馈与低频已有探测；确实需要时才按需探测，无每用户/每连接新增定时器。
- [ ] 分别阻断 TCP、普通 UDP、adaptive UDP、RPC，验证候选过滤、正确降级、恢复和健康抖动抑制，避免不必要的跨出口切换。

### P1-04 DNS 查询与配置快照生命周期

证据：[RssDnsInterceptor.java L23-L84](https://github.com/RockyLOMO/rxlib/blob/d08ecd3c1c395d5d8079f85de759ac1925eb4154/rxlib/src/main/java/org/rx/util/rss/RssDnsInterceptor.java#L23-L84)、[RssRuntime.java L407-L416](https://github.com/RockyLOMO/rxlib/blob/d08ecd3c1c395d5d8079f85de759ac1925eb4154/rxlib/src/main/java/org/rx/util/rss/RssRuntime.java#L407-L416)、[RssClient.java L1068-L1122](https://github.com/RockyLOMO/rxlib/blob/d08ecd3c1c395d5d8079f85de759ac1925eb4154/rxlib/src/main/java/org/rx/util/rss/RssClient.java#L1068-L1122)。新 interceptor 捕获 matcher、servers、dns、cache。长时间存在的 TCP DNS 或 UDP DNS association 可能持有旧规则与旧 RPC facade。旧上游按业务连接计数 drain，当前审查未看到 DNS interceptor 为其完整存活期保留对应上游引用。风险是旧 RPC 被关闭后查询失败，或新 BLOCK / 权限规则不能作用到旧 DNS association。

- [ ] DNS 与普通业务会话区别处理：每次查询取得当前有效 DNS/权限/上游快照，在单次查询期间保持一致；不能仅刷新全局 matcher 而保留旧用户规则。验证用户禁用和认证撤销时的定义。
- [ ] 在途 RPC 查询持有快照/资源直到完成、异常、超时或取消，再归还；避免旧 DNS socket/association 永久阻碍旧快照回收。单次查询一致性与长期连接生命周期解耦。
- [ ] 每次查询先 ACL，再 hosts/cache/解析；缓存隔离用户、规则视图与必要 peer 身份，不共享已禁用的旧策略结果。无可用授权 RPC 时明确 SERVFAIL，不能回退系统 DNS。
- [ ] 保留 BLOCK/DIRECT/PROXY 语义。覆盖同一长 UDP association 与 TCP DNS pipeline 上的规则热更、上游切换、RPC 关闭、用户禁用、并发查询及缓存隔离。
- [ ] 验证原始 TCP/UDP 53 无未授权上游直出；Client/Server raw 53 拒绝及兼容配置符合安全边界。不能因为 DNS AOP 收口而默默泄漏 DNS。

### P1-05 固定 UDP 与嵌套 SOCKS5 真实双向路径

相关代码：[AdaptiveUdpPort.java L117-L162](https://github.com/RockyLOMO/rxlib/blob/d08ecd3c1c395d5d8079f85de759ac1925eb4154/rxlib/src/main/java/org/rx/net/rss/tcp/AdaptiveUdpPort.java#L117-L162)、[AdaptiveUdpPort.java L286-L325](https://github.com/RockyLOMO/rxlib/blob/d08ecd3c1c395d5d8079f85de759ac1925eb4154/rxlib/src/main/java/org/rx/net/rss/tcp/AdaptiveUdpPort.java#L286-L325)、[RssSocksCarrier.java L37-L120](https://github.com/RockyLOMO/rxlib/blob/d08ecd3c1c395d5d8079f85de759ac1925eb4154/rxlib/src/main/java/org/rx/net/socks/RssSocksCarrier.java#L37-L120)、[RssFixedUdpEntry.java](https://github.com/RockyLOMO/rxlib/blob/d08ecd3c1c395d5d8079f85de759ac1925eb4154/rxlib/src/main/java/org/rx/net/socks/RssFixedUdpEntry.java)、[SocksUdpUpstream.java L349-L464](https://github.com/RockyLOMO/rxlib/blob/d08ecd3c1c395d5d8079f85de759ac1925eb4154/rxlib/src/main/java/org/rx/net/socks/upstream/SocksUdpUpstream.java#L349-L464)。

新增承载、固定入口和认证路由的结构已存在，不能只以 TCP 成功或本机 loopback 测试证明部署可用。固定普通 UDP 和 adaptive UDP 使用不同映射，不混合协议；外部客户端的实际版本、双向回程和 MTU 均须独立验证。

- [ ] 以实际 6896 no-hop 部署作为一项验证（以当前真实配置为准，不猜测线上端口）；与现有其他用户、直达 RSS 的路径互不影响。
- [ ] 验证 TCP 可靠主通道、TCP adaptive UDP、普通业务 UDP、RPC 四条实际路径；抓包确认双向端点、认证、有效 MTU 与成功交付。
- [ ] nested SOCKS5 验证 CONNECT、UDP ASSOCIATE、租约关闭、真实 relay sender、地址族及返回路径；固定 UDP 验证普通与 adaptive 独立映射、无意外直出、关闭与回收。
- [ ] 单向/双向 UDP 阻断、RPC 失效、上游重启、错误 token 与重放；保留 TCP 可靠恢复，普通 UDP 不错误报告成功或静默降级。
- [ ] IPv4/IPv6、低 MTU、真实外部封装环境下核对 RSS 净 payload 预算与外层 IP 字节，不能将一个 RSS guard 等同于端到端物理 MTU 证明。

### P1-06 Linux 与双 JDK 回归为发布门槛

- [ ] JDK8 / JDK21 clean compile 与 RSS/SOCKS/SS/DNS/egress/MTU/池和热更定向回归，记录精确版本、测试数量、失败、错误、跳过及 PARANOID 泄漏检测结果。
- [ ] Client/Server 配套升级；自适应握手 method 和 MTU 字段、出口 codec、固定入口能力、新旧配置字段均是兼容变化，验证旧版本明确拒绝或受控可靠 TCP 回退。不能假定单端升级完全兼容。
- [ ] Linux 1 vCPU / 768 MiB cgroup，epoll/native、SO_REUSEPORT、nft（如使用）、实际外部承载，验证长连接、半关闭、短包、上传下载、并发 TCP/UDP、QUIC/HTTP3 和真实游戏。
- [ ] 多种随机/突发丢包、RTT、乱序、不同 MTU 和冷/热 JVM；用相同有效交付工作量进行 plain/daily/weak/game 对比。已剔除的单协议依赖不再作为代码或测试目标。
- [ ] 分层记录真实双向 IP bytes、有效交付、失败率、P99 和最大停顿、CPU/有效 MiB、系统唤醒、heap/direct/进程 RSS/cgroup peak。不能以丢包制造低流量，不能只报告成功包延迟。未通过不得标记生产验收完成。

## 4. P2：后续优化

### P2-01 DNS resolver 故障切换、非地址记录和 EDNS

证据：[DnsRecordExchange.java L26-L79](https://github.com/RockyLOMO/rxlib/blob/d08ecd3c1c395d5d8079f85de759ac1925eb4154/rxlib/src/main/java/org/rx/net/dns/DnsRecordExchange.java#L26-L79)、[DnsQueryUpstream.java L57-L76](https://github.com/RockyLOMO/rxlib/blob/d08ecd3c1c395d5d8079f85de759ac1925eb4154/rxlib/src/main/java/org/rx/net/dns/DnsQueryUpstream.java#L57-L76)、[RssDnsInterceptor.java L64-L84](https://github.com/RockyLOMO/rxlib/blob/d08ecd3c1c395d5d8079f85de759ac1925eb4154/rxlib/src/main/java/org/rx/util/rss/RssDnsInterceptor.java#L64-L84)。

A/AAAA 已有缓存；HTTPS/TXT/MX/SRV 等其他查询通过独立 record exchange。当前每次创建 UDP/TCP socket，循环中第一个可解析 resolver 的网络异常直接退出，未继续尝试后续 resolver；UDP 以 512 B 为上限，可能增加 TCP 重试。优先纠正多 resolver 故障切换及查询语义，不急于引入复杂池。

- [ ] 预算内按明确策略尝试后续允许的 resolver，区分正常 NXDOMAIN/NODATA 与网络超时/SERVFAIL；设置整体查询 deadline，避免多 resolver 累加长时间阻塞。
- [ ] EDNS、TC、DNS-over-TCP、各记录类型及 DNS 缓存的兼容回归；按实际使用需求决定非地址记录 TTL cache 和连接复用，尊重 DNS 策略及响应边界。
- [ ] 验证 DNS worker 并发限额、任务取消和超时、队列长度以及慢 resolver 对普通业务的影响。优化前先测 QPS、CPU、socket 创建频率及重复查询。

### P2-02 实际内存与动态路径 MTU

| 模块 | 现有逻辑预算 |
|---|---:|
| Adaptive TCP recovery | 32 MiB |
| UDP 分片重组默认全局预算 | 24 MiB |
| Game 扩展历史 | 8 MiB |
| 上述三项合计 | 64 MiB |

这些不是整进程上限；不含 allocator rounding/arena、临时重组/解压、连接缓冲、DNS cache、对象和内核 socket 等。保持现有预算，先用真实 1 vCPU / 768 MiB Linux 验证，必要时基于实测收紧。检查失败路径、长时间压力及回收，不继续无依据削减缓存。

动态路径 MTU 仍属后续独立工作；本次协商 CHUNK 已解决固定预算问题，但不自动解决中途路径变化或真实 PMTU 黑洞。保留现有 guard 和可靠 TCP 修复，按实际失败率和 CPU/流量收益决定是否开展低频路径 MTU 探测；不得再次重写 FEC 或加入不必要周期保活。

## 5. 最终任务顺序与交付

| 优先级 | 项目 | 处理 |
|---|---|---|
| P1 | reload 发布/回滚 | 修复 |
| P1 | 兼容候选预过滤 | 修复 |
| P1 | DNS 快照及在途查询生命周期 | 修复 |
| P1 | 分能力健康状态 | 修复 |
| P1 | 固定 UDP、嵌套 SOCKS5 真实双向路径 | 验证 |
| P1 | JDK8/21、Linux 低配与性能 | 验证 |
| P2 | DNS resolver 故障切换 | 修复与优化 |
| P2 | EDNS、非地址记录缓存/复用 | 按需 |
| P2 | 动态 PMTU、实际内存峰值 | 实测后决定 |
| P3 | 更多类名、接口和包结构抽象 | 暂停 |

建议拆分最小可回归的改动，分别记录修复提交、实际测试及剩余风险；不要用进一步抽象替代故障修复，不重写现有 Adaptive TCP、XOR FEC、Game 复制、AES、SS、zlib/LZ4 或认证分片核心。此次提交仅新增 Review 文档，原设计计划、旧 Review、业务源码、部署配置保持不变。
