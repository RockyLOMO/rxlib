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

- [ ] 统一 MTU 单位及配置来源：区分 IP/path MTU、最终 UDP payload 上限、业务载荷预算。客户端、服务端以及各 route clone 必须一致传递，按真实 feature 计算 header 开销。
- [ ] plain 默认无主动应用层 MTU 限制（udpMtu=0），保留显式配置；只改变预期的原生透传路径，不能误关其他封装、udp2raw 或用户显式启用的 guard。仍受 OS、网络及协议最大数据报限制。
- [ ] daily/weak/game 默认保留 1300 B 最终 UDP payload 上限并支持配置。用双方协商的有效上限，旧端不能识别新分片时必须保留兼容路径，明确报告超限而非静默声称交付。
- [ ] 按需压缩后计算是否分片。可压缩大包应允许先压缩到 MTU 内；接收端解压上限应为经验证的原始数据报大小预算，而非单分片预算。保留压缩收益阈值和加密端口策略。
- [ ] 增加 RSS 私有 UDP fragmentation/reassembly：未超限的数据报走原 wire 格式，不增加额外分片头；只给超限包加消息 ID、分片索引/数量及必要长度校验。优先复用现有有界缓存或通用分片逻辑，不搬入可靠 UdpClient 的 ACK/重传热路径。
- [ ] 顺序：完整业务数据报 → 按配置压缩 → 必要时分片 → FEC 或 game 冗余 → 认证/加密 → 最终 MTU guard。接收端执行逆序，完整重组后才允许解压并向普通 UDP 目标转发一次。
- [ ] 新分片协议必须协商；分片元数据及内容需有认证保护，认证通过后再建立重组状态。daily 未启用认证的兼容路径不得直接接收无认证的大规模分片状态；评估认证升级的真实字节/CPU 成本，不给正常小包无条件增加开销。
- [ ] 普通 UDP 不默认引入可靠重传或有序交付要求。丢失任意未恢复分片时，整个数据报过期，不交付残缺数据报；重复和乱序处理应保持 UDP 数据报边界。
- [ ] 建议初始候选：原始数据报上限 65507 B（扣除实际外层地址字段并受地址族/目标协议限制）；重组超时 500–1000 ms；全局重组预算 16–32 MiB。另设每会话字节/分片数/并发组上限，超时、断开、端口切换、异常输入均释放 ByteBuf；所有数值须经实测，不直接视作生产默认值。
- [ ] 最终 egress guard 继续作为兜底，记录原始数据报丢弃、分片数、重组成功/超时、重复、预算拒绝、最终 MTU drop；不把成功 write promise 计作成功交付。
- [ ] 不改变外部经典 SS 或 SOCKS5 第三方协议。RSS 自定义分片只能在协商支持的自有链路内使用，不能发给普通 UDP 目标。

### 3.3 P2：动态路径 MTU

复用 Udp2rawMtuState 的低频探测思路；对 RSS 的 PING/PONG 与普通 UDP 设计有认证且尺寸可验证的探测。区分方向与路径，避免单次丢包即大幅降 MTU；有业务时按需探测、指数退避，空闲不新增周期唤醒。应用层探测须避免把 IP 分片后仍然成功的回包误认为无分片路径可用。验证 IPv4、IPv6、VPN/隧道、MTU 黑洞、单向阻断以及路径变更。

TCP/FEC 的 CHUNK 后续可根据有效路径 MTU 按方向调整；保持 frame 上限、最大 parity 预算和旧端兼容，不能破坏可靠 TCP 兜底。此项不阻塞普通 UDP 分片的正确性修复。

### 3.4 MTU 验收

- [ ] plain 小包与大包默认透传；显式配置限制仍有效；验证不同端口策略和非 RSS 直连路由。
- [ ] 三种增强模式分别测 IPv4 / IPv6 / 域名地址头、AES / MAC / FEC / 冗余组合，MTU 预算边界 -1 / 等于 / +1，以及 1400、1472、4096、接近合法最大值的不可压缩载荷。
- [ ] 可压缩超长包压缩后可直接发送；压缩后仍超限则分片；每个最终 UDP datagram 均不越界，重组结果逐字节一致且仅一次交付。
- [ ] 丢失、重复、乱序、迟到、非法长度/索引/数量、未认证片段、缓存耗尽、连接关闭；PARANOID 泄漏检查与预算检查。
- [ ] MTU 变化、旧端回退、两端不一致配置；QUIC/HTTP3、DNS 大响应、实际 UDP 应用。
- [ ] 小包无分片额外字节；统计真实双向 IP 字节、CPU/有效 MiB、交付率与延迟，不能将丢包误当作省流量。

## 4. P2-RSS：剩余轻量优化

### P2-RSS-01 FEC 空闲缓存生命周期

[UdpDailyFec.nextDelayMillis / tick](https://github.com/RockyLOMO/rxlib/blob/d17cad55b925ab466b111923972320e357502fbc/rxlib/src/main/java/org/rx/net/rss/udp/UdpDailyFec.java#L319-L358) 只调度活跃组过期，未显式调度不活跃缓冲的 5 秒回收及无活跃组时的反馈超时。可能在连接长期空闲时保留 XOR 缓冲，直到后续活动或 close；并非已证实的引用泄漏。

- [ ] 用虚拟时钟验证发送/接收组完成后空闲超过 5 秒、无 timer、下一次写入以及 close 的状态和引用计数。
- [ ] 优先采用明确的有界保留策略、全局内存预算或低频批量回收；若必须按时释放，使用一次性 deadline，不恢复每连接高频轮询。
- [ ] 统计累计 session 数与 direct memory 峰值；只有测量表明确有收益时再改变缓存策略。

### P2-RSS-02 weak 部分报告采样

[UdpDailyFec.observe / acceptReport](https://github.com/RockyLOMO/rxlib/blob/d17cad55b925ab466b111923972320e357502fbc/rxlib/src/main/java/org/rx/net/rss/udp/UdpDailyFec.java#L189-L258) 支持低 PPS 部分窗口报告，但 cleanSampled 通过 seq 差推算 covered（首份默认 32）；中间报告丢失或实际窗口短于序号差时可能高估连续干净样本。

- [ ] 测首次部分报告、4–31 样本、连续部分报告、报告丢失/乱序/重复、序号跨界、低 PPS 转 burst，以及新旧端混用。
- [ ] 若证明影响降档或流量，采用保守估计或经版本协商的准确样本数字段；不直接改变旧 wire。
- [ ] 仍保持均匀低 PPS 无额外周期报告、空闲无心跳；按有效恢复数和真实字节判断收益。

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
- [ ] 实际运行新增用例，并将三个经典 SS cipher、直连 / SOCKS / RSS adaptive 上游分别验证；后端仅 shutdownOutput 后仍读取客户端数据、双向同时 FIN、前端先 FIN、连接建立前 FIN、慢接收、大量输出积压、写入失败。
- [ ] 验证 flush barrier 与 adaptive pending 数据可靠修复；测试 HalfCloseWatch 在无响应、持续响应、前端背压暂停后端读取、关闭并发下的行为。不要把背压导致未读数据误判为后端无响应；必要时让 idle timeout 可配置或按精确剩余 deadline 一次性调度，不增加普通连接 timer。
- [ ] 验证超时清理、timer 取消、两端 channel 数、尾部字节与 ByteBuf 泄漏。仅在复现后做最小修复，不重新引入无限挂起。

### P2-SS-02 SaltHistory 容量与可观测性

[SaltHistory](https://github.com/RockyLOMO/rxlib/blob/d17cad55b925ab466b111923972320e357502fbc/rxlib/src/main/java/org/rx/net/socks/encryption/SaltHistory.java#L11-L79) 为进程内共享、16 分片、65536 条精确 FIFO，TTL 120 秒；按 cipher/masterKey 域隔离，但多个用户、TCP/UDP、发送和接收共同消耗容量。

容量满时可能早于 TTL 淘汰；120 秒不是高负载下保证的重放保护窗口，也不跨进程重启。低流量个人用途可继续使用当前方案。

- [ ] 增加占用、容量淘汰、过期替换、重复拒绝指标（低频汇总，不逐包日志）。
- [ ] 允许按部署 PPS 和目标窗口配置容量与 TTL；必要时增加按密钥域的公平预算，防止某用户挤占全部容量。
- [ ] 高 PPS、多用户、TCP/UDP 混合、跨 listener 并发、伪造报文不得污染缓存；原子登记和认证顺序不能回退。
- [ ] 不为省 CPU 缩短安全窗口，不引入会误丢正常包的概率算法作为默认方案。

### 5.3 SS 每用户 ssCipher（已实现，待部署验证）

[f5583f3c](https://github.com/RockyLOMO/rxlib/commit/f5583f3c8202836277681394d01221a9751ce6c0) 新增：

- [ShadowUser.cipherName](https://github.com/RockyLOMO/rxlib/blob/f5583f3c8202836277681394d01221a9751ce6c0/rxlib/src/main/java/org/rx/util/rss/ShadowUser.java#L27-L59)：空值、空白保持 aes-256-gcm；非空先 trim，再通过 CipherKind.canonical 做大小写规范化，支持 aes-128-gcm、aes-256-gcm、chacha20-ietf-poly1305。
- [RssClient.normalizeAndValidateRssConfig](https://github.com/RockyLOMO/rxlib/blob/f5583f3c8202836277681394d01221a9751ce6c0/rxlib/src/main/java/org/rx/util/rss/RssClient.java#L476-L489) 拒绝非法算法；[RssRuntime](https://github.com/RockyLOMO/rxlib/blob/f5583f3c8202836277681394d01221a9751ce6c0/rxlib/src/main/java/org/rx/util/rss/RssRuntime.java#L476-L550) 用 cipherName 建立监听，ShadowServerRef.matches 纳入 canonical cipher，回滚时恢复旧算法。同端口算法改变使用已有停旧建新流程，有短暂重绑窗口；不承诺连接无中断。
- [RssTest.shadowUserCipherDefaultsToAesAndRejectsUnknown](https://github.com/RockyLOMO/rxlib/blob/f5583f3c8202836277681394d01221a9751ce6c0/rxlib/src/test/java/org/rx/util/rss/RssTest.java#L1407-L1421) 已覆盖默认、trim、ChaCha 与非法算法验证。未独立运行。实际 listener TCP/UDP 重绑和失败回滚尚需端到端测试。
- 设计符合低 CPU：算法只在配置/建监听时解析，未引入每包 YAML 或算法选择开销；不修改 RSS 内部 AES/MAC 或 FEC wire。

- [x] 用户级 ssCipher 配置、默认兼容、非法名称拒绝、监听创建及匹配/回滚代码。
- [ ] P1 验证实际热加载：只变更目标用户的算法；其他用户监听不重绑且旧连接保持；切换后新连接 TCP/UDP 使用新算法、旧算法失败；非法配置保留上一份配置；注入 bind 失败确认原算法监听恢复。
- [ ] P2 补 canonical 大小写/空白等价时不重绑、密码与 cipher 同时变化、多 listener/reuseport、资源回收；必要时记录失败回滚告警。

## 6. 最新新增方案：6896 单用户 no-hop（尚未实现）

参考 [rss-6896-cipher-nohop-plan.md](https://github.com/RockyLOMO/rxlib/blob/f5583f3c8202836277681394d01221a9751ce6c0/docs/rss-6896-cipher-nohop-plan.md)。该文件原为草稿，ssCipher 部分已在同一 commit 落地；应区分其余设计与实际代码。

[RssTransport](https://github.com/RockyLOMO/rxlib/blob/f5583f3c8202836277681394d01221a9751ce6c0/rxlib/src/main/java/org/rx/util/rss/RssTransport.java#L9-L49) 目前只有 profile、fec/tcp/udp、tcpCompressionLevel，没有 Boolean udpHop；当前变更未触及 AdaptiveUdpPort、TCP nonce 协商或普通 UDP relay group 跳跃开关。不能宣称写入 transport.udpHop:false 已有效。原有 [createUdpRouteUpstream](https://github.com/RockyLOMO/rxlib/blob/f5583f3c8202836277681394d01221a9751ce6c0/rxlib/src/main/java/org/rx/util/rss/RssClient.java#L1727-L1740) 已按 udp2raw upstream 分流普通 UDP，但这不等于 TCP adaptive UDP 已经停止跳跃，也不证明所有数据面走同一条外层隧道。

### P1（启用此配置前）实施与验收

- [ ] 将 ssCipher 与 udpHop 分开：前者是外部 SS 入口密码算法，后者是 RSS 内部路由策略。保留 daily 压缩、自适应 XOR 与 TCP repair，不用 fec:0 代替关闭跳跃。
- [ ] 默认/省略 udpHop 与旧版一致；关闭只影响指定用户及指定数据面，服务端全局跳跃端口和其他用户不改。明确普通 UDP 走 udp2raw 与 TCP adaptive UDP 的实际出口、单端口协商和认证回包路径。
- [ ] 若采用 nonce 中 primary=alias 的现有 wire 语义，验证双方正确解析并绑定可达端口，旧端兼容、RPC 能力、认证、回退；不要仅改变客户端选端口却仍让服务端回到另一端口。
- [ ] OutboundProfiles 的 tcpCache / udpCache 等配置缓存必须将新增 hop 策略纳入缓存键或隔离，避免相同 profile 但不同用户错误共享策略；热加载后新会话应用，已有会话行为明确。
- [ ] 端到端验收：6896 ChaCha + daily + no-hop；其他用户（含 6898 daily）保持原算法/跳跃；用双方抓包核对 TCP、adaptive UDP、普通 UDP 的真正外层路径和端口、流量与 CPU；不能将 Java 单端口等同于外层 udp2raw 已自动承载所有流量。
- [ ] kcptun 移除属于独立运维变更，不在本次文档提交中执行。先验证替代监听、端口冲突处理、路由连通性及可回退步骤，再在维护窗口迁移；保留 udp2raw 必需的进程与保活。未验证现网状态前不得标记 kcptun 已移除。

## 7. Netty 依赖升级与回归

[rxlib/pom.xml](https://github.com/RockyLOMO/rxlib/blob/f5583f3c8202836277681394d01221a9751ce6c0/rxlib/pom.xml#L22-L30) 已从 4.2.12.Final 更新为 4.2.21.Final。本次审查仅确认依赖版本与代码变化，不宣称该版本已经通过 JDK8/21 或 Linux native transport 测试，也不从版本号推断性能/安全收益。

- [ ] P1 在新依赖上重新执行原 101 项定向测试，加本轮 EOF 与 ssCipher 用例；记录准确通过/失败/跳过及实际版本。
- [ ] Linux epoll/native artifact、Windows NIO、JDK8/21、reuseport、背压与半关闭、RSS adaptive TCP/UDP、udp2raw、SS 各 cipher；检查依赖树版本一致、PARANOID 泄漏、长期连接及 native 兼容。
- [ ] 与旧 Netty 版本同环境对照，报告 CPU、内存、连接清理和实际业务字节；有回归则独立定位升级和 EOF 修复，不混在一起归因。

## 8. 实施顺序与验收总表

| 优先级 | 任务 | 验收重点 |
|---|---|---|
| P1 | 统一 MTU 配置、plain 透传语义 | 真实出口预算、显式配置兼容 |
| P1 | 压缩后判断与增强模式按需分片重组 | 无超长包静默损失、认证、无界状态防护 |
| P1 | SS TCP 半关闭边界（已部分修复） | 后端完整 EOF 与仅 FIN 区分、watchdog、尾部字节 |
| P1 | ssCipher 配置（已实现） | 实际热加载、隔离及失败回滚 |
| P1（启用前） | 单用户 no-hop（未实现） | 不关闭 daily FEC、策略缓存隔离、真实链路 |
| P1 | Netty 新版本回归 | JDK8/21、Linux epoll、NIO |
| P2 | RSS 动态路径 MTU、TCP/FEC CHUNK | IPv4/IPv6 与黑洞恢复、低频探测 |
| P2 | FEC 空闲缓存 | 保留低 CPU、有界内存 |
| P2 | weak 部分反馈 | 反馈丢失下不误降档 |
| P2 | SaltHistory 容量与统计 | 实际保护窗口与公平性 |
| P2 | Linux 单核长时压力及真实网络验证 | 1 vCPU / 768 MiB、CPU/流量/交付率 |

不再做：daily 默认扩窗、RS、完整 KCP ARQ、重写 ChaCha20、大规模架构重构。将不同候选分开 A/B，记录失败实验，不强行合入无净收益的优化。

历史仓库报告（d17cad55，Netty 升级前）显示 JDK 8 与 JDK 21 各执行 101 项定向测试，各 100 通过、0 失败、1 跳过（Windows 上 Linux 专用用例）。最新两个提交新增了测试源码，但未发现对应运行结果；不得沿用历史数字宣称当前 f5583f3c 已通过。尚需 Linux nft/真实单核 cgroup、多个并发连接、长时公网、实际 WoW TCP 游戏场景；分别报告进程及整机 CPU、真实双向 IP 字节、有效业务字节、成功率、P99/最大停顿、direct/heap/cgroup 峰值。不以成功包延迟代替失败统计，不把未独立执行的测试写成本次通过。

**最终决策（2026-11-26）：MTU 与按需分片仍是 RSS 核心 P1；保留新 ssCipher，优先验证 Netty 升级及 EOF 清理语义；需要使用 6896 no-hop 时先完成并验收该独立策略。其他已证明有效的轻量优化保持不动。不修改现网或启停进程，不将计划当作已完成。**
