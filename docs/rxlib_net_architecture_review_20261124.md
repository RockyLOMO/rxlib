# rxlib 网络层重构与 weak/game 性能整改方案

> 基线：[`master a22cd28a`](https://github.com/RockyLOMO/rxlib/commit/a22cd28a9c7bcc55550fe6703221259a792a2aa9)，2026-11-24 15:41（北京时间）。本次核对了完整 `org.rx.net` Java 文件树及关键实现。本文为静态 review 和待实施计划，不代表已修改代码或重新运行基准。
>
> 约束：服务端 1 vCPU / 768 MiB 整机；全局省流量优先、CPU 效率其次，允许有界内存换性能；game 以延迟和交付率为首要目标；weak 在资源和流量预算内尽量接近 kcptun。daily/game/weak 的 TCP 默认 zlib 3，保留已有加密端口及安全旁路。

## 1. 冷启动与预热的定义

当前 [UDP 实测记录](https://github.com/RockyLOMO/rxlib/blob/a22cd28a9c7bcc55550fe6703221259a792a2aa9/docs/reference/net/udp.md#L548-L563) 中：冷启动为新 JVM/新会话直接进入测试；预热为先发送 1200 个 UDP 请求（250 PPS，约 4.8 秒）再正式测试。不是简单区分 socket 是否已创建。预热同时改变 JVM/JIT、池化分配、认证处理及会话反馈状态，不能据此认定唯一瓶颈。

将冷启动拆成三个维度：

- 进程冷启动：JVM/JIT、Netty allocator、crypto provider 尚未充分预热。
- 会话冷启动：UDP socket/关联已建立，但尚未形成 RTT、丢包、方向反馈、发送倍率等统计。
- 负载突变：已稳定会话突然从低 PPS 进入高 PPS，不能依赖历史低负载结论。

不要求真实用户预发 1200 个包。测试四组：新 JVM+新会话、热 JVM+新会话、热 JVM+热会话、热会话低 PPS→1000 PPS。确认会话建立、首包时刻及实际预热包不计入正式指标；另单独统计建连耗时。

## 2. 当前性能问题：优先修复，不和目录迁移混为一个 commit

### P1 · weak 的孤立尾包修复

150ms RTT/双向各 5% 丢包顺序 TCP 回显：weak 30,551 B / P99 792.2ms / 最大 1354ms；daily 29,346 B / P99 644.5ms；kcptun 10:1 109,172 B / P99 510.1ms / 最大 694.1ms。weak 省流约 72%，但延迟目标未达成。单次、100 回显的样本不足以确认因果；两端和测试进程共享单核 cgroup，CPU 秒非单独 server 指标。

源码：[AdaptiveStream](https://github.com/RockyLOMO/rxlib/blob/a22cd28a9c7bcc55550fe6703221259a792a2aa9/rxlib/src/main/java/org/rx/net/transport/AdaptiveStream.java#L245-L257)、[tick 与回退](https://github.com/RockyLOMO/rxlib/blob/a22cd28a9c7bcc55550fe6703221259a792a2aa9/rxlib/src/main/java/org/rx/net/transport/AdaptiveStream.java#L327-L418)。顺序业务无后继帧时无法依赖 SACK 揭露缺口；FEC 最少两分片且 flush 最长 180ms，也不能保证覆盖孤立尾包。现有 RTO 初始采样 150ms 时约为 460ms（srtt + 4*variation + 10ms）。此后根据实际样本变化。

- 先做单次、有界的提前尾包修复（TCP 提前补发 vs UDP Tail Loss Probe A/B），不要等完整 RTO 才尝试。候选探测期限以 RTT、ACK 延迟、抖动为依据；不得固定无条件重复全部待确认帧。
- 对 UDP 快重传保持原始逻辑流序号，但重新认证外层包。若经 FEC 层发送，应明确新旧 FEC 序号、分组和统计关系，不能重复注入旧分片并污染 XOR；推荐专门协商 repair 类型或明确复用 DATA 语义。收到确认立即取消剩余修复，探测失败保留 TCP 兜底。
- 单次孤立丢包不必立即关闭 UDP 快路径；持续失败、预算耗尽或拥塞才回退。避免 TCP 与 UDP 修复重复发送，不能为了省流跳过可靠交付。
- 保留当前 SACK、按需恢复窗口及发送侧取消待发 parity。测试 ACK 丢失、TCP 补发丢失、迟到原包、FIN、回绕和连续多缺口。
- RS 8:2 双校验仍留 P2：对多分片突发丢失有意义，但不是本顺序回显问题的直接解法。普通 UDP 不自动增加可靠重传语义。

### P1 · game 冷启动高 PPS

80ms RTT/双向 5% 丢包，1000 PPS：冷启动 game 1561/2000（78.05%）、P99 213.7ms；plain 1803/2000（90.15%）、P99 184.9ms。热会话 game 1875/2000（93.75%），plain 1800/2000（90%）。P99 只针对收到的回包，必须与交付率联合评价。

源码：[UdpGameRedundancy](https://github.com/RockyLOMO/rxlib/blob/a22cd28a9c7bcc55550fe6703221259a792a2aa9/rxlib/src/main/java/org/rx/net/udp/UdpGameRedundancy.java)、[DailyUdpWire.sendGame](https://github.com/RockyLOMO/rxlib/blob/a22cd28a9c7bcc55550fe6703221259a792a2aa9/rxlib/src/main/java/org/rx/net/udp/DailyUdpWire.java#L285-L348)。目前 `app.rss.game.initialCopies` 默认 2，未知反馈超过 64 个原包会降至 1；每会话 512 额外包/秒和全局预算是限额，并非平滑令牌桶；每副本独立认证和 flush。瞬时 CPU/队列压力是候选原因，尚未通过 profiling 确认。

- 首先用现有 initialCopies=1/2 做冷启动 A/B，区分首批副本开销与 JVM 预热。推荐未知负载从 1 倍启动或少量试探副本，低 PPS 经短窗口确认有余量可尽快升到 2，高 PPS 延后升档。不能让首次游戏交互等预热。
- 只对额外副本做平滑 token bucket 和队列压力保护。原始包不因冗余额度被丢弃/延迟；额度不足跳过附加副本。避免为每个副本引入新定时任务。原始包优先于额外副本，不能承诺底层 socket 永不丢原包。
- `HISTORY=256` 反馈最大允许年龄 224 序号（含 32 窗口的限制）；1000 PPS 时 RTT/排队超过约 224ms 可能使报告失效。测试扩大到 1024，发送次数 byte[] 和发送时间 int[] 合计原始数组数据约 5 KiB；2048 仅在需要时启用。限制全局内存；独立检查认证重放窗口和交付去重窗口，不必机械地让三者等大。
- 增加反馈过期/无效、实际副本数、抑制原因、原始包 drop、EventLoop 延迟、认证 CPU 和 socket/qdisc drops 指标。预热与冷启动各多次、多随机种子。
- P2：对同一逻辑包的即时副本批量 write 后 flush；不跨逻辑包等待，不延迟首包，不删除 MAC/AEAD；验证 Netty promise、引用计数及异常处理。不要先用大规模重构来猜测性能瓶颈。

### zlib 结论

保持三个业务 profile 默认 zlib 3。仓库微测显示 zlib 6 相比 3：长文档省 7.8%/CPU 增 71%；短 JSON 省 5.1%/CPU 增 10%；合成游戏短包省 0.6%/CPU 增 21%；JAR 省 0.14%。允许按业务显式试验 6，暂不全局提升。保持安全旁路和压缩先于 FEC 的现有顺序。

## 3. 目录组织原则：按职责而非 Handler 后缀分包

不建议把所有 Netty Handler 扁平塞入 `handler`。FEC encoder/decoder、配置、统计、分组状态天然属于同一个能力；按功能聚合可保留 package-private，减少跨包 public 化。Netty handler 是实现方式，不是所有类的所属层。区分 **通用数据面能力**、**RSS 私有线协议/策略**、**SOCKS 控制面**、**通用传输**、**应用配置**。

目标依赖：

```
org.rx.util.rss                 用户配置、路由、部署与 profile 选择
        ↓
org.rx.net.socks                SOCKS/SS 协议、认证、RPC 控制面、relay
        ↓
org.rx.net.rss                  RSS 私有数据面、协商后的策略及线格式
        ↓
org.rx.net.transport / org.rx.net.udp / org.rx.net.handler
        ↓
org.rx.net                     SocketConfig、Sockets、公共类型与 Netty bootstrap
```

这是目标职责边界，不声称当前依赖已单向：`Sockets` 目前兼有 SOCKS 分支与 pipeline 安装；`UdpClient` 调用 `Sockets.addUdpPeer`，`SocketConfig` 持有 UDP 能力配置。先保留公共门面，不为追求图上的绝对无环强行重写。后续可将安装逻辑移到能力模块，必要时注入回调/策略，通用能力不依赖 RSS profile/SOCKS 业务。

## 4. 推荐目录树（以当前实际类为基础）

```
org.rx.net/
├── Sockets.java / SocketConfig.java / OptimalSettings.java / TransportFlags.java ...
├── handler/                    跨协议的通用 Netty handler
├── transport/                  通用 TCP/UDP/KCP/Hybrid client/server
│   ├── TcpClient.java / DefaultTcpClient.java / TcpServer.java ...
│   ├── UdpClient.java / UdpClientConfig.java / UdpClientCodec.java ...
│   ├── KcpClient.java / KcpClientConfig.java
│   ├── kcp/                    保留 RxKcp
│   ├── hybrid/                 保留独立通用 Hybrid 协议
│   └── protocol/               保留通用传输消息
├── udp/                        通用 UDP 能力（非 RSS 业务）
│   ├── compress/               压缩
│   ├── redundant/              RDNT 多倍发包
│   ├── fec/                    通用 UdpResilience/FEC
│   ├── hopping/                通用端口跳跃配置/策略
│   ├── flow/                   UDP 背压、MTU 出口限制
│   ├── punch/                  UDP 打洞
│   └── UdpSequenceWindow.java / UdpDedupWindow.java / UdpPeerAttributes.java
├── rss/                        RSS 私有网络传输实现（新目录）
│   ├── profile/                daily/game/weak 参数与策略（建议新增）
│   ├── tcp/                    RSS TCP/UDP 混合快路径、修复、handler
│   ├── udp/                    RSS UDP 会话线协议、FEC、复制、跳跃编排
│   └── security/               RSS TCP 加密/认证；按需迁移
├── socks/                      SOCKS、SS、relay/control plane（先保留）
│   ├── upstream/ / encryption/ / httptunnel/  （现有）
│   └── udp2raw/                后续单独迁移候选
├── dns/ / http/ / nameserver/ / ntp/ / rpc/ / support/ （原样保留）
└── ...

org.rx.util.rss/
└── RssTransport / RssClient / RssServer / RssClientConf / RssSupport ...
```

### 4.1 具体迁移映射

| 当前类或类组 | 推荐包 | 理由 |
| --- | --- | --- |
| `GlobalChannelHandler`, `TcpBackpressureHandler` | `net.handler` | 通用生命周期和背压；先审查 package-private 与 SOCKS 依赖 |
| `CipherEncoder`, `CipherDecoder`, `HttpPseudoHeaderEncoder/Decoder` | `net.handler.codec`（后续） | 通用编解码；保留旧协议行为，不和 RSS 新安全层混合 |
| `NetworkFlowControl`, `NetworkFlowDiagnostics`, `NetworkTrafficConfig` | 先保留根包，后续 `net.flow` | 公共配置/API，不为整洁破坏兼容 |
| `UdpCompressCodec/Config/Encoder/Decoder/Stats/Support` | `net.udp.compress` | 通用压缩能力整组迁移 |
| `UdpRedundantConfig/Encoder/Decoder/Stats/Mode/DestinationRule/MultiplierResolver` | `net.udp.redundant` | 既有 RDNT 协议；保持与 RSS game 独立 |
| `UdpResilience`, `UdpResilience*`, `FecDecodeGroup`, `FecEncodeGroup`, `FecGroupKey` | `net.udp.fec` | 既有通用 resilience pipeline，成组保留内部可见性 |
| `UdpPortHoppingConfig/Mode` | `net.udp.hopping` | 通用参数；实际 SOCKS relay 选择、RPC 扩容暂留 SOCKS |
| `UdpBackpressureDecision/Policy`, `UdpFinalEgressGuardHandler`, `UdpMtuProbeDatagramPacket` | `net.udp.flow` | UDP 最终出口控制/MTU；检查打洞及探测调用 |
| `UdpHolePunchClient/Packets/Registry/Server/Session` | `net.udp.punch` | 独立打洞能力 |
| `UdpSequenceWindow`, `UdpDedupWindow`, `UdpPeerAttributes` | 保持 `net.udp` | 跨能力共享；不强行合并不同序列号语义 |
| `AdaptiveStream`, `AdaptiveUdpPort`, `AdaptiveTcpHandler` | `net.rss.tcp` | RSS 特有的认证 UDP 快路径与 TCP 修复，不等同于通用 Hybrid |
| `SocksAdaptiveHandshake` | 保留 `net.socks` | SOCKS auth method/nonce/安装适配属于控制面；可仅调用公开 RSS 安装入口 |
| `DailyUdpWire`, `DailyUdpCipher`, `DailyUdpMac`, `DailyUdpCompression`, `UdpDailyFec`, `UdpGameRedundancy` | `net.rss.udp` | RSS 线协议与 package-private 组件整组迁移；`UdpDailyFec` 仍被 RSS TCP 共用 |
| `RssHopAes`, `RssHopTls` | 后续 `net.rss.security` | RSS 私有安全协商；先检查 `Sockets`、SOCKS、测试中的依赖 |
| `RssTransport` | 保持 `org.rx.util.rss` | YAML 用户策略/端口覆盖，映射到 `net.rss.profile` 的内部策略 |
| `UdpClient` / `TcpClient` / `TcpServer` / `KcpClient` / `SftpClient` | 保持 `net.transport` | 现有公共 API，没必要再按 tcp/udp 细拆 |
| `UdpRelayGroup*`, `UdpLeasePoolKey`, `UdpRelayAttributes`, `UdpManager`, `UdpRedundantSupport` | 保持 `net.socks` | RPC 控制面和 relay 管理，不误归为通用 UDP 能力 |
| `Udp2raw*` | 后续 `net.socks.udp2raw` | 私有隧道协议，不与 RSS daily/game/weak 混同；整组单独迁移 |

`DailyUdpWire` 已经支持 daily/game/weak，可在机械迁移完成后考虑改名 `RssUdpWire`；`UdpDailyFec` 可改名 `RssAdaptiveFec`。先移动后改名，不同时更改 pipeline handler 名称/协议标识。无需复制三份 encoder/decoder。

### 4.2 Profile 层建议

建议新增内部 `RssProfile` 与不可变 `RssRecoveryPolicy`（名称可按代码规范调整），管理协商后的策略标识、FEC 预算类型、窗口、尾包修复、game 冗余限制。现有 `plain/daily/game/weak` YAML 语义保持不变；保留显式 `fec/tcp/udp` 覆盖、默认 zlib 3、端口安全旁路。底层算法只接受必要参数，不到处写字符串判断 `game/weak`；保留已有 wire ID、feature bit、profileVersion，不能直接用 enum.ordinal() 代替协议常量。

- `org.rx.util.rss.RssTransport` 仍负责用户配置和端口规则，映射到内部策略；两端根据实际协商值实例化，不让客户端本地 profile 默认为服务端已启用。
- SOCKS handshake、relay RPC、`UdpLeasePoolKey`、TCP warm pool/配置 cache 的身份字段需覆盖影响线上协议的策略；不同 profile 不混用旧连接。热更新仅影响新关联，已有连接不在中途改变线格式。
- `DailyUdpWire`/game MAC/weak FEC 的协议与 legacy RDNT、通用 UdpResilience 不同，禁止仅因功能类似就合并 wire codec。可在后续验证共享纯 XOR、序号窗口、预算/调度原语，不必立即抽象。
- RSS 跳跃为已协商的 primary/alias 30 秒轮换；既有 SOCKS hopping 为 relay group 策略。共享配置或纯策略须保持不同的 NAT、RPC、回包和认证语义。

## 5. 最小风险实施顺序

1. 性能修复独立分支/commit：先完成 weak tail probe 与 game 冷启动 A/B 及回归测试；不得把行为调整和目录移动混在同一个提交。
2. 机械迁移低风险的内部 UDP 能力包：以能力完整集为单位，更新 imports、测试包、Javadoc 和配置引用；检查公开 API 的兼容性。若有外部调用者，保留旧 FQCN 转发门面或暂不移动；不要为了分包批量改 public。
3. RSS 私有层聚合：先 `rss.udp` 六个类，后 `rss.tcp` 三个类；`SocksAdaptiveHandshake` 保留在原包，通过最小公开 installer/factory 访问。注意原 `AdaptiveTcpHandler` package-private，避免为每个内部方法提升可见性。检查 `DailyUdpCompression` 依赖通用压缩 support 的可见性。
4. 独立提交策略抽取：保留 YAML/线协议不变，集中 profile 参数并消除分散的 magic number；通过现有握手/RPC 传递已协商策略。
5. 后续按需整理 `handler`、`rss.security`、`udp2raw`，不为了目录美观重写 2174 行 `Sockets` 或 1451 行 `SocksUdpUpstream`。有需求时逐步把 UDP pipeline 安装提取为独立 installer，`Sockets` 原静态入口保持转发。

### 迁移护栏

- 移动文件用 git mv 并维护测试同包路径；逐步构建。对 Netty `pipeline.get(Class)`、`getSimpleName()`、`AttributeKey.valueOf()`、反射/Fury 序列化、RPC DTO、配置类和外部模块 imports 做全局检查。FQCN 变更可能影响序列化和二进制兼容；不改变线上协议/认证域/DTO 兼容性，若不可兼容则另行版本协商或暂缓迁移。
- 不要同时重命名类和协议标识；不修改现有 package-private 测试的语义。保留 ByteBuf 引用计数、EventLoop confinement、定时器释放和全局预算。
- 所有目录是建议目标，不要求一次性迁移完整 `org.rx.net`；公共 API 兼容优先于形式统一。已有 `transport.hybrid`、`transport.kcp`、DNS、HTTP、RPC 目录不纳入本次全面重构。

## 6. 验收

- 单测：AdaptiveStreamTest、UdpDailyFecTest、UdpDailyFecCoverageTest、UdpGameRedundancyTest、DailyUdpWireTest、DailyRelayGroupTest、AdaptiveUdpPortTest、RssTest、UdpRedundantTest、UdpResilienceTest；SOCKS 集成、旧版协商 fallback、IPv4/IPv6、认证重放、端口跳跃与会话复用。
- 性能：同资源、相同有效负载和可复现丢包种子；冷/热各重复，多 RTT、PPS、独立/突发损失及混合并发。测试目标 weak 在 150ms/5% 下 P99 ≤ kcptun 10:1 的 1.2 倍、最长卡顿同样单独验收；现有样本对应约 612ms，40,000 B 为该样本候选流量预算而非固定生产上限。game 冷/热 1000 PPS 交付率及尾延迟至少不劣于 plain，并报告丢失/超时，不仅统计成功回包 P99。
- 统一双向 IP 字节计数点；不将 UDP qdisc 统计直接与 TCP 注入前字节比较。CPU 按服务端与 cgroup 分别记录；整机 768 MiB 下记录 JVM/Netty direct/kernel/其他进程占用及 GC；32 MiB 增强恢复窗口预算仍保留。未通过则先诊断而非扩容一切缓存。
- 机械迁移前后比较相同种子、同负载结果；行为不变，重点检测类加载/反射、旧客户端兼容以及遗漏 handler。目录迁移本身不是性能优化。

## 7. 最终决策

`transport` 是通用传输客户端/服务端；`udp` 下按通用能力分包；`rss` 是 RSS 私有数据面与 profile 策略；`socks` 管控制面；`org.rx.util.rss` 保持用户/部署应用层。先修关键性能，再小步拆包，不引入三套平行协议，不修改公共 API 只为整洁。
