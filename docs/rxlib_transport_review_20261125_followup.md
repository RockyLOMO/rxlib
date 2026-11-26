# rxlib RSS Transport & Shadowsocks Follow-up Review — 2026-11-25（2026-11-26 更新）

原审查基线：[master d17cad55](https://github.com/RockyLOMO/rxlib/commit/d17cad55b925ab466b111923972320e357502fbc)，北京时间 2026-11-25 21:42。相对 [上一轮评审提交 0e0555fb](https://github.com/RockyLOMO/rxlib/commit/0e0555fbdc08a591eed2a2926a8e639ed3abf0e1) 新增 10 个 commit。

最新复查基线：[master f5583f3c](https://github.com/RockyLOMO/rxlib/commit/f5583f3c8202836277681394d01221a9751ce6c0)，北京时间 2026-11-26 00:05:52。相对上一版本文提交 [56851521](https://github.com/RockyLOMO/rxlib/commit/56851521de885cadc25a181f4f07959ff3b9f20e) 新增 2 个 commit。更新保留历史性能证据，并以本次源码核查的状态为准。

本文件为下一轮实施计划，不覆盖 [上一轮 Review 与实施记录](./rxlib_transport_review_20261125.md)。本次为静态源码审查，性能和测试数字来自仓库记录，未独立执行 Java 测试。以下未实现事项不得标记为已完成。

## 1. 设计定位与总体结论

- 正确性、认证安全、标准协议兼容为硬约束；优化优先级：真实双向网络流量 > CPU 时间、唤醒与分配 > 有界内存占用。
- 允许以内存换低流量、低 CPU；仍以 1 vCPU / 768 MiB 部署场景验证整机内存峰值，不引入无界缓存。
- 保留 plain / daily / weak / game 四种 profile 与轻量自适应 XOR 核心。现阶段不引入 RS、完整 KCP ARQ，不大规模重构，不默认提升压缩级别。
- 上一轮的大部分优化已实现。当前最高优先级仍是 RSS UDP MTU 与超长数据报的兼容性；SS 经典 AEAD 核心基本完整，继续做边界验证，不重写 ChaCha20。
- 2026-11-26：SS 每用户加密算法配置已落地，后端 EOF 清理已有针对性修复，但双向半关闭语义有取舍。Netty 已升级，须重新验证；单用户关闭端口跳跃仍只有设计方案。

## 2. 上一轮改动复查

| 项目 | 结论 |
|---|---|
| TCP/UDP 路径切换抑制、批量写入预判 | 保留 |
| UDP 探测指数退避、空闲暂停 | 保留 |
| FEC 累加器复用、完整组跳过 parity XOR | 保留；复核空闲缓存生命周期 |
| daily 扩窗 64→256 | 候选已实测负收益，维持 64 |
| weak/game 按需扩窗 | 保留现有 256 上限及预算 |
| game 自适应降档 | 保留，继续观察实际交付率 |
| weak 低 PPS 成对活动反馈 | 保留，补充部分窗口与丢反馈测试 |
| HMAC 前只读防重放、原地封装 | 保留，验签成功前禁止更新重放状态和回程地址 |
| SS 首地址跨 AEAD record、异常包处理 | 已修复主要缺口 |
| SS Salt 防重放、独立 TCP/UDP 互操作 | 已增加，复核容量；半关闭见 §5.2 |
| SS 每用户 ssCipher、热加载匹配及回滚 | 已实现，待实际热加载/回滚验收 |
| SS 后端先 EOF 时清理前端 | 已修复目标路径并新增回归，待运行及边界验证 |
| Netty 4.2.12.Final → 4.2.21.Final | 已更新依赖，待新版本回归 |
| 单用户 transport.udpHop | 方案存在，未实现 |
| SS ChaCha20 批量字运算、HKDF 与主密钥复用 | 保留 |
| RSS 普通 UDP 超长包分片/重组 | 尚未实现 |

### 2.1 仓库报告中的性能证据

来源：[实施与验收记录 §7](https://github.com/RockyLOMO/rxlib/blob/d17cad55b925ab466b111923972320e357502fbc/docs/rxlib_transport_review_20261125.md#L195-L289)。

- 1 小时 UDP 阻断、持续空闲：探测 1800→1 包，估算双向控制 IP 流量 117000→65 B（-99.94%）；持续每秒写入场景 1800→130 包（-92.8%）。
- FEC 微基准：内部缓冲分配次数降低约 90.1%–98.8%，所列恢复数不变；20 PPS/1% 丢包 CPU 31→20 ms。不是整机 CPU 降幅。
- 16 MiB 下载、150 ms RTT、8 Mbps 链路：daily-w256 相比 daily-w64 在 0/1/5% 丢包下真实 IP 字节分别由 17581190/17756072/18497253 增至 17906707/18674538/19476275；5% 丢包完成时间 16527→16543 ms，无收益。不采纳扩窗候选。
- 维持 zlib 3 和已有加密流量旁路策略；不可压缩随机数据不应因强制压缩增加开销。

## 3. P1-RSS：MTU 与 UDP 数据报完整性

### 3.1 现状与根因

2026-11-26 增量核查：两个新增提交未修改 DailyUdpWire、AdaptiveStream、MTU 配置或分片协议；RssClient 仍设置 UDP2RAW_MTU=1300，以下任务仍未完成。不要将 SS 65536 B 接收分配器或 Netty 升级等同于 RSS 分片支持。

[DailyUdpWire.write / deliver / maxPayload](https://github.com/RockyLOMO/rxlib/blob/d17cad55b925ab466b111923972320e357502fbc/rxlib/src/main/java/org/rx/net/rss/udp/DailyUdpWire.java#L273-L318) 在压缩前检查 maxPayload，超长包直接释放并令写 promise 成功，仅记录 mtuDrop。四种 profile 共用基础出口配置，[RssClient](https://github.com/RockyLOMO/rxlib/blob/d17cad55b925ab466b111923972320e357502fbc/rxlib/src/main/java/org/rx/util/rss/RssClient.java#L1005-L1045) / [RssServer](https://github.com/RockyLOMO/rxlib/blob/d17cad55b925ab466b111923972320e357502fbc/rxlib/src/main/java/org/rx/util/rss/RssServer.java#L138-L166) 默认设置 udpMtu=1300（UDP payload 字节数，不含 IP/UDP header）。

| profile | 默认出口 UDP payload 上限 | 常见内部封装前预算 | 超长包行为 |
|---|---:|---:|---|
| plain | 1300 | 1300（不含另行添加的目标地址等） | 出口 guard 丢弃 |
| daily FEC | 1300 | 1272 | 预检查丢弃 |
| daily FEC + AES | 1300 | 1248 | 预检查丢弃 |
| weak FEC + MAC/AES | 1300 | 1248 | 预检查丢弃 |
| game 冗余 + MAC/AES | 1300 | 1251 | 预检查丢弃 |

上述为典型功能组合，不是统一的业务 payload 上限。目标地址、端口策略、实际协商 feature、额外封装均须计入。plain 的特殊端口加密策略并不必然等于无封装。

TCP-over-UDP 的 AdaptiveStream 已具备流分段、重组和可靠 TCP 修复；固定 MAX_UDP_FRAME=1250，加 AdaptiveUdpPort 32 B 外层开销，IPv6+UDP 最终 IP 包可达 1330 B。不能把固定 1300 UDP payload 当作所有路径都安全的 IP MTU，尤其需验证 IPv6 1280 B 路径。

已有 Udp2rawMtuState / Udp2rawMtuProbeSupport 以及可靠 UdpClient 的分片重组能力，但它们并未自动使 RSS 普通 UDP 数据报支持拆包；FEC shard 也不是大数据报分片。

### 3.2 P1 实施要求

- [x] 统一 MTU 单位及配置来源：区分 IP/path MTU、最终 UDP payload 上限、业务载荷预算。客户端、服务端以及各 route clone 必须一致传递，按真实 feature 计算 header 开销。
- [x] plain 默认无主动应用层 MTU 限制（udpMtu=0），保留显式配置；只改变预期的原生透传路径，不能误关其他封装、udp2raw 或用户显式启用的 guard。仍受 OS、网络及协议最大数据报限制。（同时修复 `Sockets.udpBootstrap` 默认 `AdaptiveRecvByteBufAllocator(2048)` 对首包 >2048 B 的静默截断，统一采用 `FixedRecvByteBufAllocator(65536)`。）
- [x] daily/weak/game 默认保留 1300 B 最终 UDP payload 上限并支持配置。用双方协商的有效上限，旧端不能识别新分片时必须保留兼容路径，明确报告超限而非静默声称交付。
- [x] 按需压缩后计算是否分片。可压缩大包应允许先压缩到 MTU 内；接收端解压上限应为经验证的原始数据报大小预算，而非单分片预算。保留压缩收益阈值和加密端口策略。
- [x] 增加 RSS 私有 UDP fragmentation/reassembly：未超限的数据报走原 wire 格式，不增加额外分片头；只给超限包加消息 ID、分片索引/数量及必要长度校验。优先复用现有有界缓存或通用分片逻辑，不搬入可靠 UdpClient 的 ACK/重传热路径。
- [x] 顺序：完整业务数据报 → 按配置压缩 → 必要时分片 → FEC 或 game 冗余 → 认证/加密 → 最终 MTU guard。接收端执行逆序，完整重组后才允许解压并向普通 UDP 目标转发一次。
- [x] 新分片协议必须协商；分片元数据及内容需有认证保护，认证通过后再建立重组状态。daily 未启用认证的兼容路径不得直接接收无认证的大规模分片状态；评估认证升级的真实字节/CPU 成本，不给正常小包无条件增加开销。
- [x] 普通 UDP 不默认引入可靠重传或有序交付要求。丢失任意未恢复分片时，整个数据报过期，不交付残缺数据报；重复和乱序处理应保持 UDP 数据报边界。
- [x] 建议初始候选：原始数据报上限 65507 B（扣除实际外层地址字段并受地址族/目标协议限制）；重组超时 500–1000 ms（落地 750 ms）；全局重组预算 16–32 MiB（落地进程全局 16 MiB、单会话 512 KiB / 64 并发组）。超时、断开、端口切换、异常输入均释放 ByteBuf；生产压测阈值留待公网实测微调。
- [x] 最终 egress guard 继续作为兜底，记录原始数据报丢弃、分片数、重组成功/超时、重复、预算拒绝、最终 MTU drop；不把成功 write promise 计作成功交付。
- [x] 不改变外部经典 SS 或 SOCKS5 第三方协议。RSS 自定义分片只能在协商支持的自有链路内使用，不能发给普通 UDP 目标。

### 3.3 P2：动态路径 MTU

复用 Udp2rawMtuState 的低频探测思路；对 RSS 的 PING/PONG 与普通 UDP 设计有认证且尺寸可验证的探测。区分方向与路径，避免单次丢包即大幅降 MTU；有业务时按需探测、指数退避，空闲不新增周期唤醒。应用层探测须避免把 IP 分片后仍然成功的回包误认为无分片路径可用。验证 IPv4、IPv6、VPN/隧道、MTU 黑洞、单向阻断以及路径变更。

TCP/FEC 的 CHUNK 后续可根据有效路径 MTU 按方向调整；保持 frame 上限、最大 parity 预算和旧端兼容，不能破坏可靠 TCP 兜底。此项不阻塞普通 UDP 分片的正确性修复。

### 3.4 MTU 验收

- [x] plain 小包与大包默认透传；显式配置限制仍有效；验证不同端口策略和非 RSS 直连路由。
- [x] 三种增强模式分别测 IPv4 / IPv6 / 域名地址头、AES / MAC / FEC / 冗余组合，MTU 预算边界 -1 / 等于 / +1，以及 1400、1472、4096、接近合法最大值的不可压缩载荷。
- [x] 可压缩超长包压缩后可直接发送；压缩后仍超限则分片；每个最终 UDP datagram 均不越界，重组结果逐字节一致且仅一次交付。
- [x] 丢失、重复、乱序、迟到、非法长度/索引/数量、未认证片段、缓存耗尽、连接关闭；PARANOID 泄漏检查与预算检查。
- [ ] MTU 变化、旧端回退、两端不一致配置；QUIC/HTTP3、DNS 大响应、实际 UDP 应用。（已完成旧端未协商回退与两端不一致 MTU 协商单测；真实公网 QUIC/HTTP3 与外部 UDP 应用留待现网灰度验证。）
- [x] 小包无分片额外字节；统计真实双向编码字节与交付完整性，不将丢包误当作省流量。

## 4. P2-RSS：剩余轻量优化

### P2-RSS-01 FEC 空闲缓存生命周期

[UdpDailyFec.nextDelayMillis / tick](https://github.com/RockyLOMO/rxlib/blob/d17cad55b925ab466b111923972320e357502fbc/rxlib/src/main/java/org/rx/net/rss/udp/UdpDailyFec.java#L319-L358) 只调度活跃组过期，未显式调度不活跃缓冲的 5 秒回收及无活跃组时的反馈超时。可能在连接长期空闲时保留 XOR 缓冲，直到后续活动或 close；并非已证实的引用泄漏。

- [x] 用虚拟时钟验证发送/接收组完成后空闲超过 5 秒、无 timer、下一次写入以及 close 的状态和引用计数。
- [x] 优先采用明确的有界保留策略、全局内存预算或低频批量回收；若必须按时释放，使用一次性 deadline，不恢复每连接高频轮询。（新增 `nextIdleCleanupDelayMillis()` 一次性 5 秒空闲回收截止时间，回收后返回 `-1` 零周期轮询；并修复超过 5 秒空闲后仅收 `DATA` 时未回收旧 `receiving` 槽位的问题。）
- [x] 统计累计 session 数与 direct memory 峰值；只有测量表明确有收益时再改变缓存策略。（新增 `rss.udp.fec.sessions.total/active` 与 `rss.udp.fec.cache.bytes/peakBytes` 指标。）

### P2-RSS-02 weak 部分报告采样

[UdpDailyFec.observe / acceptReport](https://github.com/RockyLOMO/rxlib/blob/d17cad55b925ab466b111923972320e357502fbc/rxlib/src/main/java/org/rx/net/rss/udp/UdpDailyFec.java#L189-L258) 支持低 PPS 部分窗口报告，但 cleanSampled 通过 seq 差推算 covered（首份默认 32）；中间报告丢失或实际窗口短于序号差时可能高估连续干净样本。

- [x] 测首次部分报告、4–31 样本、连续部分报告、报告丢失/乱序/重复、序号跨界、低 PPS 转 burst，以及新旧端混用。
- [x] 若证明影响降档或流量，采用保守估计或经版本协商的准确样本数字段；不直接改变旧 wire。（保持 wire 格式不变：首包按就绪上限折算，发现 `delta > 32` 丢反馈缺口或超时跨窗时重置连续干净样本计数为保守单窗下界。）
- [x] 仍保持均匀低 PPS 无额外周期报告、空闲无心跳；按有效恢复数和真实字节判断收益。

## 5. Shadowsocks 经典 AEAD 复查

### 5.1 完整性结论

chacha20-ietf-poly1305 的 TCP/UDP、HKDF-SHA1、32 B salt、12 B nonce、16 B tag、TCP 长度/载荷 AEAD、半包粘包处理均已实现。本轮又补齐首目标地址跨记录、半关闭、截断检测、SaltHistory、防重放、65536 B UDP 接收分配器及 Python cryptography 独立实现 TCP/UDP 互操作；JCE 差分与 RFC 8439 向量覆盖。结论为经典 AEAD 功能基本完整，不等于完成独立安全审计，也不代表 SS2022。

仓库 JDK21 ChaCha20 CPU 时间（ms/MiB，越低越好）：

| 场景 | 优化前 | 优化后 |
|---|---:|---:|
| TCP 1400 B | 32.18 | 15.21 |
| TCP 16383 B | 28.75 | 11.00 |
| UDP 256 B | 76.80 | 54.40 |
| UDP 1200 B | 34.82 | 17.75 |

保留单一 JDK8/21 兼容实现、64-bit 批量操作、HKDF scratch 与主密钥复用；不因裸 JCE 基准直接替换完整 SS 实现。

### 5.2 P1-SS-01 TCP 半关闭与后端 EOF（已部分修复，仍需验证）

新增 [28b14f29](https://github.com/RockyLOMO/rxlib/commit/28b14f2913e322ba44d9e76af2cecc000c040960)：

- [Sockets.closeOnFlushed](https://github.com/RockyLOMO/rxlib/blob/f5583f3c8202836277681394d01221a9751ce6c0/rxlib/src/main/java/org/rx/net/Sockets.java#L1689-L1700) 由 isActive 判断改为 isOpen，非活跃但仍打开的 channel 直接 close，避免残留句柄。
- [SSTcpProxyHandler](https://github.com/RockyLOMO/rxlib/blob/f5583f3c8202836277681394d01221a9751ce6c0/rxlib/src/main/java/org/rx/net/socks/SSTcpProxyHandler.java#L51-L67) 不再为后端 socket 显式设置 ALLOW_HALF_CLOSURE=true；[SocksTcpBackendRelayHandler](https://github.com/RockyLOMO/rxlib/blob/f5583f3c8202836277681394d01221a9751ce6c0/rxlib/src/main/java/org/rx/net/socks/SocksTcpBackendRelayHandler.java#L17-L34) 删除后端 FIN 转发逻辑，由 channelInactive 在 flush 后关闭前端；SS 前端仍允许半关闭。
- [SocksTcpFrontendRelayHandler](https://github.com/RockyLOMO/rxlib/blob/f5583f3c8202836277681394d01221a9751ce6c0/rxlib/src/main/java/org/rx/net/socks/SocksTcpFrontendRelayHandler.java#L23-L145) 保留客户端先 FIN 的输出半关闭，检查 flush/shutdownOutput 失败，并新增 HalfCloseWatch：每 60 秒检查，后端无响应达到 120 秒则清理，实际触发可能在约 120–180 秒；仅半关闭连接才有 timer，前端关闭时取消。后端读事件更新最后活跃时间。
- [新增回归 backendClosesFirstWhileClientIdle_flushesTailAndClosesFrontendChannel](https://github.com/RockyLOMO/rxlib/blob/f5583f3c8202836277681394d01221a9751ce6c0/rxlib/src/test/java/org/rx/net/socks/ShadowsocksServerIntegrationTest.java#L412-L479) 使用 ChaCha20、SOCKS 上游、后端发送尾部数据后关闭，要求前端 channel 数归零且完整收到尾部数据。已见测试源码，未见本次 commit 的执行报告，不写成测试已通过。

**语义取舍（P1）：** 目标是避免后端已关闭而空闲移动客户端长时间占住 SS channel；正常完整 EOF 后 flush 再 close 应保留。另一方面，后端仅 shutdownOutput、仍期待客户端继续发送数据时，新代码未显式允许后端半关闭，默认 socket 行为可能将 FIN 变成整条连接关闭。这与此前双向透明半关闭方案不同。不能以“后端先完整关闭”用例证明所有 FIN 场景正确。应先明确产品是否需要透明双向半关闭；若需要，按后端 input/output 状态及真实业务关闭判定处理，而非无条件关闭另一方向。若刻意选择只支持客户端先 FIN，要记录限制并验证影响。

- [x] 增加后端完整关闭、前端仍空闲、末尾数据交付回归用例（源码已存在）。
- [x] 实际运行新增用例，并将三个经典 SS cipher、直连 / SOCKS / RSS adaptive 上游分别验证；双向同时 FIN、前端先 FIN、连接建立前 FIN（修复 `ProxyHandler.connectFuture()` 未完成时提前半关闭）、慢接收、大量输出积压。明确产品取舍为**仅支持客户端先 FIN 单向半关闭**（后端关闭时刷空尾部字节后释放前端连接，记录于 `rss-transport.md`）。
- [x] 验证 flush barrier 与 adaptive pending 数据可靠修复；测试 HalfCloseWatch 在无响应、持续响应、前端背压暂停后端读取（`!inbound.isWritable()` / `!outbound.isAutoRead()` / `totalPendingWriteBytes > 0` 不误杀）、关闭并发下的行为；改为按精确剩余 deadline 一次性调度，普通连接零额外 timer。
- [x] 验证超时清理、timer 取消、两端 channel 数、尾部字节与 ByteBuf `PARANOID` 泄漏检查。

### P2-SS-02 SaltHistory 容量与可观测性

[SaltHistory](https://github.com/RockyLOMO/rxlib/blob/d17cad55b925ab466b111923972320e357502fbc/rxlib/src/main/java/org/rx/net/socks/encryption/SaltHistory.java#L11-L79) 为进程内共享、16 分片、65536 条精确 FIFO，TTL 120 秒；按 cipher/masterKey 域隔离，但多个用户、TCP/UDP、发送和接收共同消耗容量。

容量满时可能早于 TTL 淘汰；120 秒不是高负载下保证的重放保护窗口，也不跨进程重启。低流量个人用途可继续使用当前方案。

- [x] 增加占用、容量淘汰、过期替换、重复拒绝指标（`ss.salt.size`、`capacityEvictions`、`expiredReplacements`、`replayRejections`，30 秒低频汇总，不逐包日志）。
- [x] 允许按部署 PPS 和目标窗口配置容量与 TTL（`-Drx.ss.saltHistory.capacity`、`-Drx.ss.saltHistory.ttlSeconds`）；增加按密钥域 `DomainKey` 的分片内 O(1) 公平预算，防止单用户洪泛挤占其他用户未过期条目。
- [x] 高 PPS、多用户、TCP/UDP 混合、跨 listener 并发、伪造报文不得污染缓存；原子登记和认证顺序不能回退。
- [x] 不为省 CPU 缩短安全窗口，不引入会误丢正常包的概率算法作为默认方案。

### 5.3 SS 每用户 ssCipher（已实现，待部署验证）

[f5583f3c](https://github.com/RockyLOMO/rxlib/commit/f5583f3c8202836277681394d01221a9751ce6c0) 新增：

- [ShadowUser.cipherName](https://github.com/RockyLOMO/rxlib/blob/f5583f3c8202836277681394d01221a9751ce6c0/rxlib/src/main/java/org/rx/util/rss/ShadowUser.java#L27-L59)：空值、空白保持 aes-256-gcm；非空先 trim，再通过 CipherKind.canonical 做大小写规范化，支持 aes-128-gcm、aes-256-gcm、chacha20-ietf-poly1305。
- [RssClient.normalizeAndValidateRssConfig](https://github.com/RockyLOMO/rxlib/blob/f5583f3c8202836277681394d01221a9751ce6c0/rxlib/src/main/java/org/rx/util/rss/RssClient.java#L476-L489) 拒绝非法算法；[RssRuntime](https://github.com/RockyLOMO/rxlib/blob/f5583f3c8202836277681394d01221a9751ce6c0/rxlib/src/main/java/org/rx/util/rss/RssRuntime.java#L476-L550) 用 cipherName 建立监听，ShadowServerRef.matches 纳入 canonical cipher，回滚时恢复旧算法。同端口算法改变使用已有停旧建新流程，有短暂重绑窗口；不承诺连接无中断。
- [RssTest.shadowUserCipherDefaultsToAesAndRejectsUnknown](https://github.com/RockyLOMO/rxlib/blob/f5583f3c8202836277681394d01221a9751ce6c0/rxlib/src/test/java/org/rx/util/rss/RssTest.java#L1407-L1421) 已覆盖默认、trim、ChaCha 与非法算法验证。未独立运行。实际 listener TCP/UDP 重绑和失败回滚尚需端到端测试。
- 设计符合低 CPU：算法只在配置/建监听时解析，未引入每包 YAML 或算法选择开销；不修改 RSS 内部 AES/MAC 或 FEC wire。

- [x] 用户级 ssCipher 配置、默认兼容、非法名称拒绝、监听创建及匹配/回滚代码。
- [x] P1 验证实际热加载：只变更目标用户的算法；其他用户监听不重绑且旧连接保持；切换后新连接 TCP/UDP 使用新算法、旧算法失败；非法配置保留上一份配置；注入 bind 失败确认原算法监听恢复（并修复 NIO Selector 延迟注销导致同端口重绑偶发 `BindException` 的问题）。
- [x] P2 补 canonical 大小写/空白等价时不重绑、密码与 cipher 同时变化、资源回收及失败回滚告警日志。

## 6. 最新新增方案：6896 单用户 no-hop（代码已实现，现网待切换）

参考 [rss-6896-cipher-nohop-plan.md](https://github.com/RockyLOMO/rxlib/blob/f5583f3c8202836277681394d01221a9751ce6c0/docs/rss-6896-cipher-nohop-plan.md)。

### P1（启用此配置前）实施与验收

- [x] 将 ssCipher 与 udpHop 分开：前者是外部 SS 入口密码算法，后者是 RSS 内部路由策略（同时支持客户端用户级 `transport.udpHop: false` 与服务端全局 `SocksConfig.udpHop` / `-udpHop=0`）。保留 daily 压缩、自适应 XOR 与 TCP repair，不用 fec:0 代替关闭跳跃。
- [x] 默认/省略 udpHop 与旧版一致；关闭只影响指定用户及指定数据面，服务端全局跳跃端口和其他用户不改。明确普通 UDP 走 udp2raw 与 TCP adaptive UDP 的实际出口、单端口协商和认证回包路径。
- [x] 采用签名 nonce 中 `primary == alias` 的单端口协商（TCP 握手方法 `0x87` + RPC `SUPPORT_NO_HOP_UDP`），验证双方正确解析并绑定可达单端口、旧端兼容回退、RPC 能力与认证回包路径。
- [x] OutboundProfiles 的 tcpCache / udpCache、TCP 预热池键 `TcpWarmPoolKey` 与 UDP 租约池键 `UdpLeasePoolKey` 全部纳入 `udpHop` 隔离，热加载后新会话立即按用户生效。
- [ ] 端到端现网抓包验收：6896 ChaCha + daily + no-hop；其他用户（含 6898 daily）保持原算法/跳跃；用双方抓包核对现网 TCP、adaptive UDP、普通 UDP 的真正外层路径和端口、流量与 CPU。（本地双 JDK 集成测试已验证单用户 no-hop 与其他用户跳跃并存隔离；现网双端抓包留待发布窗口执行。）
- [ ] kcptun 移除属于独立运维变更，不在本次提交中执行。先验证替代监听、端口冲突处理、路由连通性及可回退步骤，再在维护窗口迁移；保留 udp2raw 必需的进程与保活。未验证现网状态前不得标记 kcptun 已移除。

## 7. Netty 依赖升级与回归

[rxlib/pom.xml](https://github.com/RockyLOMO/rxlib/blob/f5583f3c8202836277681394d01221a9751ce6c0/rxlib/pom.xml#L22-L30) 已从 4.2.12.Final 更新为 4.2.21.Final。

- [x] P1 在新依赖上重新执行原 101 项定向测试，加本轮 no-hop、MTU/认证分片、TCP 半关闭矩阵、ssCipher 热更回滚、FEC 空闲回收与 SaltHistory 公平淘汰用例；记录准确通过/失败/跳过及实际版本（见 §9）。
- [ ] Linux epoll/native artifact、Windows NIO、JDK8/21、reuseport、背压与半关闭、RSS adaptive TCP/UDP、udp2raw、SS 各 cipher；检查依赖树版本一致、PARANOID 泄漏、长期连接及 native 兼容。（已完成 `mvn dependency:tree` 全部 40 个 `io.netty` 构件 `4.2.21.Final` 一致性核验，以及 Windows NIO 下 JDK 8 / JDK 21 全套 `PARANOID` 回归；Linux `epoll` native / `SO_REUSEPORT` 与长时公网连接留待 Linux 环境验证。）
- [ ] 与旧 Netty 版本在 Linux 生产同环境长时对照 CPU、内存、连接清理和实际业务字节。

## 8. 实施顺序与验收总表（2026-11-26 最新复查更新）

| 优先级 | 任务 | 状态 / 验收重点 |
|---|---|---|
| P1 | 统一 MTU、plain 透传、压缩后分片 | 已实现，保留；真实内存预算与发送结果见 §10 |
| P1 | UDP 分片实际内存预算 | 待修复或证明无放大风险 |
| P1 | 6896 no-hop / udp2raw 实际数据面 | Java 实现已完成，待双端抓包；不等于已经移除 kcptun |
| P1 | 分片本地写入结果聚合 | 待修复；不承诺远端 UDP 交付 |
| P1 | Linux 单核长期部署回归 | 待执行，1 vCPU / 768 MiB |
| P2 | RSS 动态路径 MTU、TCP/FEC CHUNK | 未实现，IPv4/IPv6 与黑洞恢复、低频探测 |
| P2 | 大包复制与分配优化 | 先测实际内存、CPU 和有效交付 |
| P2 | 文档参数一致性 | 按最新代码修正；另见 §10.4 |
| P2 | FEC 空闲缓存、weak 部分反馈 | 已改进，保持并观察 |
| P2 | SS TCP 半关闭、ssCipher 热加载 | 目标策略已实现并有本地回归；单向半关闭限制仍需明确 |
| P2 | Netty 新版本回归 | 双 JDK Windows 回归已记录，Linux native 待验证 |
| P3 | SaltHistory 多用户容量淘汰性能 | 已实现容量配置和公平预算；多域扫描按需优化 |

不再做：daily 默认扩窗、RS、完整 KCP ARQ、重写 ChaCha20、大规模架构重构。将不同候选分开 A/B，记录失败实验，不强行合入无净收益的优化。

历史仓库报告（d17cad55，Netty 升级前）每个 JDK 为 101 项（100 通过、1 跳过）。本轮实现后的仓库记录见 §9：JDK 8/21 各 153 项（151 通过、0 失败、2 跳过）。这些是仓库已有报告，不是本次静态 Review 独立执行的结果。Linux nft/真实单核 cgroup、多个并发连接、长时公网、实际 WoW TCP 游戏场景仍待验证。

**最新决策：功能开发逐步转向稳定性验证。优先修复分片内存预算和本地发送状态，验证实际 no-hop 数据面与 Linux 部署。已有低开销 FEC 和经典 SS AEAD 核心不再反复修改。**

## 9. 2026-11-26 实施与验收记录

基于 `19cc5db0` / `f5583f3c` 基线，在本机 Windows 环境（Temurin JDK `21.0.12.1`、Temurin JDK `1.8.0_504`、Python `cryptography`、Netty `4.2.21.Final`）完成本轮认可项的落地与双 JDK `PARANOID` 回归验证：

### 9.1 提交清单

| 提交 | 对应项 | 摘要 |
| --- | --- | --- |
| `f95c1199` | §6 `transport.udpHop` | 支持单用户 `transport.udpHop: false` 与服务端 `SocksConfig.udpHop` / `-udpHop=0` 关闭自适应 TCP/UDP FEC 端口跳跃（TCP `NO_HOP_METHOD = 0x87`、RPC `SUPPORT_NO_HOP_UDP`、签名 nonce `primary == alias`、旧端回退及 `OutboundProfiles` / 预热池 / 租约池键隔离），完整保留压缩、自适应 XOR 与 TCP 修复 |
| `1517f6cf` | §3.2 / §3.4 P1-RSS | 统一 UDP MTU 语义（`plain` 默认 `udpMtu = 0` 原生透传并修复 `Sockets.udpBootstrap` 首包 2048 B 接收分配截断；增强模式默认 `dailyUdpMtu = 1300` 协商）、压缩后超限判定及零小包额外开销的有界认证分片重组 `DailyUdpFragmenter`（`FRAG_MAGIC = 0x0001`、单会话 16 组 / 256 KiB、全局默认 24 MiB、750 ms 默认过期（按 0a0dcb83 源码复核修正；实际内存预算仍需验证）） |
| `bf635b9d` | §5.2 P1-SS-01 / §5.3 | 加固客户端先 FIN 单向半关闭（`ProxyHandler.connectFuture()` 建连前 FIN 屏障、SOCKS5 `CONNECT` 前端半关闭开启、感知下游背压的精确 deadline 一次性 `HalfCloseWatch`），修复 NIO Selector 延迟注销引起的同端口快速重绑 `BindException`，并完成 36 组合半关闭矩阵与 `ssCipher` 热更新隔离/回滚端到端验证 |
| `7845b55b` | §4 P2-RSS-01/02 & §5.2 P2-SS-02 | `UdpDailyFec` 增加 5 秒空闲一次性缓存回收截止时间与会话/直接内存峰值统计；`weak` 部分反馈在丢报告跨窗时保守重置连续干净样本计数；`SaltHistory` 增加容量/TTL 配置、按 `DomainKey` 分片内公平预算（部分淘汰路径扫描域）与 30 秒低频指标汇总 |

### 9.2 Netty `4.2.21.Final` 依赖树与双 JDK 回归结果

1. **依赖树一致性**：`mvn -pl rxlib dependency:tree -Dincludes=io.netty` 核验全部 40 个 `io.netty` 编译与平台运行时构件（含 `netty-transport-native-epoll` `linux-x86_64` / `linux-aarch_64` / `linux-riscv64`）均为 `4.2.21.Final`，无混用版本。
2. **Temurin JDK `21.0.12.1`（`-Dio.netty.leakDetection.level=PARANOID`）**：
   - RSS 传输、SOCKS5 与 `RssTest` 定向集（`AdaptiveStreamTest,AdaptiveUdpPortTest,UdpDailyFecTest,UdpDailyFecCoverageTest,UdpGameRedundancyTest,DailyUdpWireTest,UdpSequenceWindowTest,SocksProxyServerIntegrationTest` 及 `RssTest` 6 项 `udpHop` / `udpMtu` / `ssCipher` 用例）：**运行 98 项，96 通过，0 失败，0 错误，2 跳过**（Windows 下跳过 Linux 专用用例）。
   - Shadowsocks 经典 AEAD、编解码、`SaltHistory`、半关闭矩阵与 Python `cryptography` 独立互操作集（`ChaCha20Poly1305SupportTest,Aes256GcmByteBufCryptoTest,CipherCodecTest,SSProtocolCodecTest,SaltHistoryTest,ShadowsocksServerIntegrationTest,ShadowsocksPythonClientIntegrationTest,ShadowsocksTcpDownloadIntegrationTest`）：**运行 55 项，55 通过，0 失败，0 错误，0 跳过**。
   - 合计 **153 项（151 通过、0 失败、2 跳过），`PARANOID` 0 泄漏**。
3. **Temurin JDK `1.8.0_504`（`clean test` 全量重编译 + `-Dio.netty.leakDetection.level=PARANOID`）**：
   - 同一 RSS 传输集：**运行 98 项，96 通过，0 失败，0 错误，2 跳过**。
   - 同一 Shadowsocks 互操作集：**运行 55 项，55 通过，0 失败，0 错误，0 跳过**。
   - 合计 **153 项（151 通过、0 失败、2 跳过），`PARANOID` 0 泄漏**。

### 9.3 未执行与待部署环境验证边界

- **未纳入本轮编码**：按第 1、8 节约束，未改 `daily` 默认 64 窗口，未引入 RS、完整 KCP ARQ、ChaCha20 重写或动态路径 MTU（§3.3 P2）。
- **未改动现网**：未修改远端 `/home/rss/conf.yml`，未启停 `rssClient` / `rssServer` 进程，未移除 `kcptun`。
- **Linux 与公网实测边界**：Linux `epoll` native / `SO_REUSEPORT`、`nft` 端口重定向、1 vCPU / 768 MiB cgroup 整机峰值内存、6896 现网双端抓包及公网 QUIC/HTTP3 / 游戏长连接对照尚未在本机 Windows 环境执行，留待维护窗口在 Linux 环境按同口径核验。

## 10. 2026-11-26 15:58 版本增量 Review（最新）

审查基线：[0a0dcb83](https://github.com/RockyLOMO/rxlib/commit/0a0dcb839ca832feed8ee311f76643e88ec0a950)，北京时间 2026-11-26 15:58:22。相比上一轮评审文档提交 [19cc5db0](https://github.com/RockyLOMO/rxlib/commit/19cc5db0624eadc4eff528ff6adad3dc54f7e731) 新增 5 个 commit，涉及 42 个文件。以下为最新状态，优先于此前尚未实施时的描述。保留 §9 原有实施与验证记录；本次未独立运行测试、未操作现网。

### 10.1 已实现事项及保留决策

| 项目 | 结论 |
|---|---|
| plain 默认 udpMtu=0 | 保留，指原生路径不主动限长；特殊加密路由、udp2raw 不等于裸透传 |
| enhanced UDP MTU 协商，默认 1300 B | 保留，UDP payload 与 IP/path MTU 仍须区分 |
| 按需压缩、压缩后判断、认证分片和有界重组 | 已实现，继续验证真实资源预算及本地写入失败 |
| 单用户 no-hop，预热池、租约池、配置缓存隔离 | 已实现，实际外层路径待验证 |
| SS 半关闭、EOF、ssCipher 热加载与回滚 | 已增强，保留并明确客户端先 FIN 策略 |
| FEC 空闲一次性回收、weak 部分反馈 | 已改进，保持低唤醒，不恢复周期扫描 |
| SaltHistory 容量、TTL、域公平淘汰及低频统计 | 已实现，保留 |
| 动态路径 MTU / TCP FEC CHUNK | 尚未实现 |
| Linux 实际部署及 kcptun 新一轮对照 | 尚未验证 |

仓库 §9 报告 JDK8/21 各 153 项定向测试，151 通过、0 失败、2 跳过，Netty 4.2.21.Final、PARANOID 未报告泄漏。泄漏检测不等于验证了活跃会话内存预算，也不代表已完成 Linux 生产验证。

### 10.2 P1-RSS-01 分片真实内存预算（优先）

源码：[DailyUdpWire.sizeReceiveBuffer](https://github.com/RockyLOMO/rxlib/blob/0a0dcb839ca832feed8ee311f76643e88ec0a950/rxlib/src/main/java/org/rx/net/rss/udp/DailyUdpWire.java#L198-L201)、[DailyUdpFragmenter.accept](https://github.com/RockyLOMO/rxlib/blob/0a0dcb839ca832feed8ee311f76643e88ec0a950/rxlib/src/main/java/org/rx/net/rss/udp/DailyUdpFragmenter.java#L246-L299)。

接收分配器固定为 65536 B，以防较大原始 UDP 数据报截断；重组预算按每片 SLOT_OVERHEAD_BYTES(64) + sliceLen 计费，而 slot.parts 使用 frame.retainedSlice 保留父缓冲引用。特别是明文 daily 等直接使用原接收缓冲的路径，实际底层持有容量可能远大于计费长度。以 1200 B slice 为例，计费 1264 B，若独立保留一块 65536 B backing buffer，约为 51.8 倍。该比值为条件性上界示例，不是实测放大值；加密、MAC 和不同 allocator 路径可能不同。24 MiB 是逻辑计费预算，不是经证明的 direct memory 硬上限。完整重组时的额外输出缓冲及对象元数据也应纳入峰值分析。

- [ ] 使用真实 NIO / epoll socket 接收路径与池化 allocator，测量原始 backing buffer、重组逻辑计费、active direct memory、进程 RSS 和 cgroup 峰值。不要只用小型 Unpooled 测试输入证明预算正确。
- [ ] 对需跨事件长期持有的分片按实际 sliceLen 拷入容量匹配的池化 buffer 后释放父引用，或者建立可靠的实际 backing allocation 计费；选择对真实 CPU/内存综合收益更优的方案。保持正常未分片包快路径不变，不降低 65536 B 接收分配器导致截断。
- [ ] 校验单会话与全局预算、每组元数据、重组完成时临时输出缓冲及解压峰值。拒绝预算时不得长期占用无有效片段的 assembly slot；验证成功、超时、异常、关闭、分配失败均归还引用和预算。
- [ ] 压测多会话、大量缺片、乱序、重复、缓存满、FEC 恢复与认证失败；全部清理后逻辑计费归零，实际活跃内存回落。区分池化 allocator 保留内存与活跃引用占用，不将池保留直接判为泄漏。
- [ ] 同时记录分片路径 CPU/有效 MiB、GC、direct 分配、交付率与真实 IP 流量，确保对日常小包没有额外拷贝及每包新增对象。

### 10.3 P1-RSS-02 / 03 实际 no-hop 数据面及本地写入结果

**P1-RSS-02：6896 main-tun 路径验证。**

[AdaptiveUdpPort.client / remoteIp](https://github.com/RockyLOMO/rxlib/blob/0a0dcb839ca832feed8ee311f76643e88ec0a950/rxlib/src/main/java/org/rx/net/rss/tcp/AdaptiveUdpPort.java#L85-L111) 根据 TCP socket 的 remoteAddress 取得目标 IP，再使用握手协商的 primary/alias UDP 端口创建数据面。若 TCP 实际连接本机 127.0.0.1:4080，不能由 udpHop=false 推断 adaptive UDP 已经走 udp2raw 外层。普通业务 UDP 的 Udp2rawUpstream 路由与 TCP/FEC adaptive UDP 是两个不同的数据面。可能出现 TCP 正常但 UDP 探测失败、持续 TCP 回退；这是部署风险，尚未复现。

- [ ] 双端抓包及指标验证 6896 TCP 主通道、TCP adaptive UDP、普通业务 UDP 三条路径；检查远端 IP、实际映射端口、认证 PING/PONG、toUdp/toTcp 与实际有效交付，确认没有意外公网直出或本地未映射端口。
- [ ] 与 6898 保持跳跃的 daily 对照，确认单用户配置和池键隔离；旧端能力不足时记录实际行为，不把旧端回退当作 no-hop 完整支持。
- [ ] 若 UDP 映射不可达，先明确外层路由/映射策略或保留 TCP 回退，不以关闭 fec 或盲目增加探测掩盖问题。是否需要额外外层通道必须由部署实测决定。
- [ ] kcptun 下线仍是独立运维操作：先验证替代监听、端口冲突处理和可回退方案，维护窗口迁移；不宣称已切换现网。

**P1-RSS-03：多分片本地写入结果聚合。**

[DailyUdpWire.write](https://github.com/RockyLOMO/rxlib/blob/0a0dcb839ca832feed8ee311f76643e88ec0a950/rxlib/src/main/java/org/rx/net/rss/udp/DailyUdpWire.java#L337-L346) 中 dataPromise = last ? promise : null，前面分片使用 voidPromise，最后一片成功不能证明之前分片也成功完成本地 write。UDP 无远端可靠交付保证，但本地写入状态应准确，不能错误计入成功业务包。

- [ ] 仅在真正进入分片路径时聚合各原始分片的本地写结果；首片/中间片/尾片失败时原始 promise 只能完成一次并准确反映失败。不要把 parity 或可选 game 额外副本失败与原始数据分片失败混为一谈。
- [ ] 检查跨 Channel/回程 path 的 promise 归属、批量 flush、编码同步异常、最终 MTU guard、关闭、异步失败、引用释放。异常后已发送部分无法撤回，接收端按重组超时丢弃，不引入默认 UDP 重传。
- [ ] 保留小包原写入路径与正常单次 flush，避免每片无必要的新任务、每包聚合对象和频繁 flush。记录本地发送失败、缺片超时及真实成功重组数。

### 10.4 参数及文档一致性

按 [DailyUdpFragmenter.java](https://github.com/RockyLOMO/rxlib/blob/0a0dcb839ca832feed8ee311f76643e88ec0a950/rxlib/src/main/java/org/rx/net/rss/udp/DailyUdpFragmenter.java#L20-L34)、[DailyUdpWire.maxPayload](https://github.com/RockyLOMO/rxlib/blob/0a0dcb839ca832feed8ee311f76643e88ec0a950/rxlib/src/main/java/org/rx/net/rss/udp/DailyUdpWire.java#L541-L549) 核查当前实际值：

| 参数 | 源码 |
|---|---:|
| plain 默认 udpMtu | 0 |
| enhanced UDP 默认 payload MTU | 1300 B |
| 最大原始 RSS 封装载荷 | 65507 B（含目标地址等内层开销） |
| 最大分片数 | 64 |
| 单会话最大并发重组组 | 16 |
| 单会话计费预算 | 256 KiB |
| 进程全局默认计费预算 | 24 MiB |
| 默认重组超时 | 750 ms |
| 外层已有认证时分片头 | 10 B |
| 无外层认证 daily 的分片头、序号与 tag | 34 B |
| 普通未分片 SOCKS UDP | 0 B 额外分片开销 |

此前 docs/rss-transport.md 与 §9 实施记录写为 64 组 / 512 KiB / 16 MiB，不符合最新代码。本文 §9 参数已按当前源码校正；独立的 rss-transport.md 尚未修改，后续同步。不要仅为与旧文档一致而修改代码参数。rx.rss.udp.reassemblyTimeoutMillis 与 rx.rss.udp.reassemblyGlobalBytes 可配置；默认值不等于实测物理内存预算。

- [ ] 明确 64 分片上限与 MTU 对最大可传输业务载荷的限制：maxEncodedDatagram = min(65507, 64 * maxSliceBytes)，其中 maxSliceBytes = fragmentUnitLimit(maxPayload) - fragmentHeaderBytes；FEC 分片 unit 还受 1500 B shard 上限约束。实际业务上限再扣除地址头等，压缩收益另计。
- [ ] 对最小允许 MTU=256、默认 1300、IPv4/IPv6、不同保护组合、64 片边界和超限失败补充测试和文档。不盲目扩大 MAX_FRAGMENTS。
- [ ] 正常小包保持零额外分片头；特殊 magic 冲突转义路径与协议约束应单列，不宣称所有任意字节序列均零开销。

分片流量估算示例：不可压缩 4096 B RSS 内层数据报、1300 B UDP MTU，game + MAC（外层保守预算 49 B，分片头 10 B）：单片数据预算 1241 B，需 4 片，原始数据片 UDP payload 合计约 4332 B，额外 236 B（约 5.76%）。该值不含可选 game 副本、FEC parity、反馈、IP/UDP 头及链路层开销，不是实测。daily（无外层 AES）使用 34 B 分片认证头；weak 使用外层认证与 10 B 分片头。按实际模式和认证配置计算，不统一使用某一组开销值。

### 10.5 P2 动态 MTU 与低 CPU 优化

- [ ] RSS 动态路径 MTU 与 TCP/FEC 动态 CHUNK 仍未实现。区分 UDP payload 上限与 path IP MTU；固定 1250 B adaptive frame + 32 B 认证外层 + IPv6/UDP 48 B 可达 1330 B。路径 IP MTU 1280 B 时仍可能分片/黑洞。
- [ ] 复用已有 udp2raw MTU 探测思路，认证并核验实际尺寸，按方向/路径低频按需探测、退避、缓存，避免空闲心跳及探测导致 IP 分片的误判。改变 CHUNK 不影响 TCP 可靠兜底与旧端协议。
- [ ] 先解决实际分片预算，再用 profile 评估大包复制、分配和批量写。保留 FEC 5 秒一次性空闲回收及 weak 保守报告处理；不为理论收益重写 XOR、扩大 daily 默认窗口或提高 zlib 默认压缩等级。

### 10.6 SS 最新复查

- [SaltHistory](https://github.com/RockyLOMO/rxlib/blob/0a0dcb839ca832feed8ee311f76643e88ec0a950/rxlib/src/main/java/org/rx/net/socks/encryption/SaltHistory.java#L195-L231) 已有分片锁、容量和 TTL 配置、精确防重放、域内公平淘汰及 30 秒低频指标。部分容量满路径遍历 domains.values() 选 victim，复杂度与该分片域数相关，而非所有淘汰 O(1)。少量用户场景优先保持简单；P3 仅在实测多域锁竞争明显时考虑优化。不降低安全窗口或使用概率误判代替精确缓存。
- [SocksTcpFrontendRelayHandler](https://github.com/RockyLOMO/rxlib/blob/0a0dcb839ca832feed8ee311f76643e88ec0a950/rxlib/src/main/java/org/rx/net/socks/SocksTcpFrontendRelayHandler.java#L23-L170) 已引入可配置半关闭 idle deadline、背压判断、ProxyHandler.connectFuture 屏障；保持目标 EOF 清理和尾部数据验证。当前策略仍偏向客户端先 FIN 的单向半关闭，不等价于完整透明双向 TCP 半关闭；若业务要求后端先 shutdownOutput 后继续读取客户端数据，单独测试并明确支持范围。
- chacha20-ietf-poly1305 核心不重写；保留独立 TCP/UDP 互操作及 JCE 差分测试。没有新增证据表明需改变加密算法默认策略。

### 10.7 P1 Linux 验收与部署门槛

在 1 vCPU / 768 MiB 限额、真实独立进程上验证 Linux epoll/native、SO_REUSEPORT、nft、Netty 新版本、SS listener 重绑、半关闭、no-hop 及 UDP 分片。以 plain/daily/weak/game 和 kcptun 0:0 / 10:1 / 相近真实流量预算为对照；至少三个种子并重复、交替测试顺序，区分冷/热 JVM 及 session。测试空闲、短包/低 PPS、长上传下载、并发业务、QUIC/HTTP3、真实 TCP 游戏；覆盖 RTT、随机/突发丢包、乱序、UDP 单向/双向阻断及 IPv6 低 MTU。

分别记录双向 IP 字节、有效交付字节/数据报、P99/最大停顿与失败率、内核丢包、本地写失败、分片成功率、进程及整机 CPU/有效 MiB、唤醒、heap/direct/cgroup 峰值。以正确性、安全、兼容为硬约束，流量与 CPU 优先，内存放宽但必须有界。不能以丢包制造省流量结果，不能只报告成功包的延迟，也不能以 Windows 定向通过代替 Linux 公网验收。

**下一轮执行顺序：真实分片内存预算 → 本地写结果聚合 → 6896 实际路径抓包 → Linux 长时 A/B；随后根据实测推进动态路径 MTU 和少量大包优化。**
