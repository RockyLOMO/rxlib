# rxlib RSS Transport & Shadowsocks Review — 2026-11-25

基线：[master 32b9be27](https://github.com/RockyLOMO/rxlib/commit/32b9be2792c55c47c8b54955995fba8589d9230a)（北京时间 2026-11-25 08:53）。相对 7a46f427 共 19 个提交。第 1–6 节保留当日静态源码复查及原始实施清单；本轮全部代码提交、基准原始数据、失败候选与 JDK 8 / JDK 21 回归结果见[第 7 节 实施与验收记录](#7-实施与验收记录)。

## 1. 设计约束与总体结论

- 正确交付、认证安全、互操作性为硬约束。优化优先级：**实际双向网络字节最小化 > CPU 时间/唤醒/分配最小化 > 有界内存开销**。允许以内存换流量和 CPU；内存并非无限，保持 1 vCPU / 768 MiB 整机目标，按实测调整预算。
- `plain` 保持默认原生 TCP/UDP；`daily` 以日常省流、低 CPU 为主；`weak`（此前称 weak-network）针对弱网，按收益增加修复；`game` 优先交付和尾延迟，但限制冗余。
- 保留 zlib 3 和用户可配置压缩级别、压缩先于 RSS FEC、既有已加密端口旁路及安全端口策略；不默认提高压缩级别，不关闭有效压缩，不通过降低认证安全换取性能。
- 继续共享 FEC、恢复及认证基础能力，不复制多套实现，不将 RSS 私有 wire format 引入外部 Shadowsocks 协议。
- 本轮不建议整体回滚或引入 RS、完整 KCP ARQ。重点从功能扩张转向消除无效流量和 CPU。

## 2. 已实施修改：保留 / 调整

| 项目 | 决策 | 理由 |
|---|---|---|
| 64→512 序号位图 | 保留 | 每份位图原始空间仅增加 56 B；避免晚到合法帧被过滤。 |
| FEC 接收组 4→16 按需扩展，300 ms 过期 | 保留 | 有界 XOR 缓存；不为节省少量内存回退恢复能力。 |
| daily 最少 2 分片、15–180 ms 动态 flush | 保留 | 原包立即发；维持 parityCredit，稀疏流不要求全覆盖。 |
| daily 暂存前缀，按需 XOR | 保留并测量 | 降低部分无效 XOR；暂存本身仍有复制与分配成本。 |
| TCP 接管取消 pending parity | 保留 | 避免无效校验；接收状态不应一并无条件删除。 |
| game/weak 64→256 按需恢复窗口，32 MiB JVM 预算 | 保留 | 内存换减少窗口满导致的重复发送；测量实际占用。 |
| weak 字节预算 XOR、SACK、可靠前缀 | 保留 | 更贴近流量目标，减少重复修复。 |
| weak 单次尾包探测 | 暂保留 | 增加部分流量但改善部分尾延迟；独立 repair wire 候选未全面胜出，不发布。 |
| weak 大 TCP 帧省略不必要累计 ACK | 保留 | 已有混合及阻断回归；净 IP 字节收益仍受 TCP 波动影响。 |
| 按需 timer、MAC 对象复用 | 保留 | 空闲已就绪连接可减少唤醒；不改变安全属性。 |
| game 1x 起步、自适应 1–3x | 保留独立模式，优化降档 | 真实交付收益存在，但高副本带宽和 CPU 成本大。 |
| 默认 RS、复杂 ARQ、永久多发、zlib 6 | 暂缓 | 未证明收益大于成本。 |

## 3. 实测证据与边界

来源：[架构 Review 实施与验收记录](https://github.com/RockyLOMO/rxlib/blob/32b9be2792c55c47c8b54955995fba8589d9230a/docs/rxlib_net_architecture_review_20261124.md#L164-L216)、[最新 follow-up](https://github.com/RockyLOMO/rxlib/blob/32b9be2792c55c47c8b54955995fba8589d9230a/docs/rxlib_transport_review_20261124_followup.md)。以下 weak 对照为 11-24 UDP 尾包候选实验，并非对最新 commit 的重新跑分。150 ms RTT，双向各 5% 随机丢包，100 次顺序 TCP 回显，每方向 164 B，1 vCPU / 768 MiB cgroup；kcptun 10:1、fast、AES、默认压缩。

| 种子 | weak IP B | kcptun IP B | weak P99 ms | kcptun P99 ms | weak 整组 CPU s | kcptun 整组 CPU s |
|---|---:|---:|---:|---:|---:|---:|
| 718 | 38,968 | 110,036 | 676.5 | 694.9 | 1.34 | 1.68 |
| 2063 | 35,387 | 108,960 | 341.7 | 576.8 | 1.23 | 1.78 |
| 3491 | 38,083 | 108,620 | 411.5 | 581.9 | 1.29 | 1.71 |

合计流量约少 65.6%，整组 CPU 时间约少 25.3%；不能推断 server JVM 单独 CPU 更低（该列原实验中反而更高）。突发混合负载 weak 189,159 B / P99 460.7 ms，kcptun 281,895 B / 413.1 ms；weak 更省流但该样本 P99 较高。后续须比较 kcptun FEC 0:0、10:1 和匹配实际冗余预算的模式，并控制压缩、密码、并发、总有效交付和计量范围。

最新 follow-up 记录：独立 repair wire 候选在突发混合负载下改善明显，但部分随机种子流量和尾延迟变差，故保留现行协议；大 TCP 帧省略 ACK 令无 pending ACK 减少，实际 IP 总量变化方向不同；game 预热候选未全面获益，保持单发起步。219 项 JDK8 定向回归：217 通过、2 跳过（仓库记录，非本次测试结果）。

Game 热会话/种子 626：plain→game，有效回包 1801/2000→1903/2000，P99 99.2→98.4 ms，双向 IP 746,880→1,461,385 B（+95.7%），整组 CPU 0.88→1.33 s（+51.1%）。交付率增加 5.1 个百分点。OpenTTD 短时 TCP 游戏验证通过，但 WoW、公网与长时游戏未验证。WoW 是 TCP 游戏，普通 UDP 副本并不直接改善其 TCP 字节流；关注 AdaptiveStream。

## 4. 下一阶段 RSS 优化清单

源码链接均固定在本文基线。每项单独提交，先复现或测量，再做最小实现；不确定收益则保留现状。

### P1-RSS-01：抑制 TCP/UDP 路径反复切换（首要性能项）

源码：[AdaptiveStream.write/sendCapacity](https://github.com/RockyLOMO/rxlib/blob/32b9be2792c55c47c8b54955995fba8589d9230a/rxlib/src/main/java/org/rx/net/rss/tcp/AdaptiveStream.java#L89-L119)、[sendCapacity](https://github.com/RockyLOMO/rxlib/blob/32b9be2792c55c47c8b54955995fba8589d9230a/rxlib/src/main/java/org/rx/net/rss/tcp/AdaptiveStream.java#L206-L239)、[drainPendingToTcp](https://github.com/RockyLOMO/rxlib/blob/32b9be2792c55c47c8b54955995fba8589d9230a/rxlib/src/main/java/org/rx/net/rss/tcp/AdaptiveStream.java#L488-L509)。

- 当前 daily 64×1237 B 约 77 KiB pending；窗口满会补发待确认帧并回退 TCP，2 s 后重新探测。高 BDP/大 write 可能反复进入 UDP→TCP，产生重复字节、认证、复制和控制帧。此为源码推断，需复现。
- [x] 统计 reason=window/timeout/fin/gap/sack、每连接切换次数、pending 峰值、重复修复字节、UDP 有效传输比例和 TCP 驻留时长。
- [x] 测试大批量写入与持续大文件，区分窗口耗尽、实际丢包、EventLoop 过载。对重复窗口耗尽优先探索 TCP 驻留/冷却，而不是一律增加 UDP 冗余。
- [x] 以 128/256 帧作为可选扩窗候选；优先 high-BDP、UDP 确实有收益的流。daily 现行协议接收窗口 64，不能仅扩大发送端；需明确双方窗口能力、旧端回退和共享内存预算。默认策略须用基准决定。
- [x] 保留已排队可靠修复、按序交付、SACK/GAP、FIN 和 close 回收；测试大流+短流并发、单向阻断、恢复 UDP、高 RTT 与序号回绕。
- 验收：相同有效交付下实际 IP 字节、CPU/有效 MiB 与路径抖动下降；吞吐、尾延迟不明显退化。

### P1-RSS-02：UDP 探测退避，空闲暂停

源码：[AdaptiveStream.tick/nextDelayMillis](https://github.com/RockyLOMO/rxlib/blob/32b9be2792c55c47c8b54955995fba8589d9230a/rxlib/src/main/java/org/rx/net/rss/tcp/AdaptiveStream.java#L400-L450)、[AdaptiveTcpHandler.schedule](https://github.com/RockyLOMO/rxlib/blob/32b9be2792c55c47c8b54955995fba8589d9230a/rxlib/src/main/java/org/rx/net/rss/tcp/AdaptiveTcpHandler.java#L137-L154)。

- 已就绪且无工作时支持无 timer；未就绪的已开始连接仍可能每 2 s 发 PING，阻断或空闲连接累积无效唤醒和字节。
- [x] 首次立即探测；候选退避 2→4→8→16→30 s（上限和抖动需测量），空闲且没有恢复任务时暂停主动探测；业务恢复允许受限提前探测。
- [x] 对大量连接加随机抖动，避免同周期探测风暴；仅针对探测 timer，不误停 ACK、FEC flush、expiry、GAP 或可靠 repair；区分正常空闲与 NAT 映射保活需求。
- [x] 统计 1h 空闲和长期 UDP 阻断的控制字节、唤醒、CPU；测解除阻断后的恢复时间。

### P1-RSS-03：减少无效 FEC XOR 与内存复制

源码：[UdpDailyFec.write](https://github.com/RockyLOMO/rxlib/blob/32b9be2792c55c47c8b54955995fba8589d9230a/rxlib/src/main/java/org/rx/net/rss/udp/UdpDailyFec.java#L78-L138)、[read](https://github.com/RockyLOMO/rxlib/blob/32b9be2792c55c47c8b54955995fba8589d9230a/rxlib/src/main/java/org/rx/net/rss/udp/UdpDailyFec.java#L140-L196)。

- 接收端收到 SHARD 即维护 XOR，但部分 daily 稀疏组最终无 parity。发送端已暂存短前缀，不代表接收端 CPU 已节省。
- [x] 统计未发送/未收到校验的组、XOR 字节、staging/fold 次数、缓冲申请/释放及 copy bytes，使用 JFR/async-profiler 或等效工具测热点。
- [x] 实验预计无预算组直接发送 DATA 的策略（沿用已有 DATA/SHARD 帧；必须正确切组、不跨序号缺口、不破坏预算及自适应统计）。避免先组 SHARD 再错误跳过组内序号。
- [x] 实验有界 scratch/缓冲复用、减少中间复制；不扩大无效缓存、不阻塞原包；并发 burst、极低 PPS、target 切换、回绕、close 及 leak detection 回归。
- 验收：daily 原始字节预算不提高，恢复率和有效业务 P99 不显著退化，CPU/分配确有收益才合入。

### P2-RSS-04：game 冗余降档和反馈

源码：[UdpGameRedundancy.report/change](https://github.com/RockyLOMO/rxlib/blob/32b9be2792c55c47c8b54955995fba8589d9230a/rxlib/src/main/java/org/rx/net/rss/udp/UdpGameRedundancy.java#L153-L211)。

- 现有降档要求连续无物理丢失窗口；逻辑交付已足够但仍有少量物理丢包时，可能长期保持 2x/3x。不要简单以逻辑零丢失直接降档（副本可能确实必要）。
- [x] 结合原包丢失、额外副本边际收益、业务交付率、RTT/排队趋势，用带滞回的小范围降档试探；保留全局及会话 PPS/字节限制，拥塞时不盲目加副本。
- [x] 测 20/200/300/1000 PPS，冷 JVM、热 JVM 新会话、热会话、负载突变、突发与随机丢包；优先真实 TCP 游戏和低 PPS UDP 游戏；未证明收益不启用额外预热。

### P2-RSS-05：认证前只读防重放过滤、减少复制

源码：[AdaptiveUdpPort.receive](https://github.com/RockyLOMO/rxlib/blob/32b9be2792c55c47c8b54955995fba8589d9230a/rxlib/src/main/java/org/rx/net/rss/tcp/AdaptiveUdpPort.java#L184-L215)、[DailyUdpMac.open](https://github.com/RockyLOMO/rxlib/blob/32b9be2792c55c47c8b54955995fba8589d9230a/rxlib/src/main/java/org/rx/net/rss/udp/DailyUdpMac.java#L75-L95)。

- [x] AdaptiveUdpPort 可以在来源/长度/会话检查后、HMAC 前只读判断确定重复或过旧的 seq；必须在认证成功后才 mark、更新回程地址和会话状态。复用 DailyUdpMac 已有检查顺序，不缩短 tag，不跳过有效包认证。
- [x] 认证/封装热点实测后考虑最终数据报直接编码、减少 ByteBuf/nioBuffer 复制，维持引用计数、EventLoop 线程约束；无证据不重写 MAC。

### P2-RSS-06：weak 反馈、修复时序与压缩

- [x] 极低 PPS 下 32 包报告周期可能超过 5 s 超时，weak 可实验活动时按包数或时间触发有限反馈；空闲不发额外心跳，不将无反馈视为无丢包；daily 不强制 100% 覆盖。
- [x] 观察 80 ms 最短 TCP repair 与 FEC 15–180 ms flush 的实际冲突（当前 RTO 还受 RTT/variation 调整）；优先依据实际可恢复分组、ACK/SACK 和排队情况减少重复修复，不恢复固定 500 ms，不无条件放宽所有超时。保留已经实现的 cancelPendingParity。
- [x] 继续压缩收益统计，比较 zlib 1/3/6 的实际字节和 CPU/有效 MiB；默认 3 不动。避免对已加密/不可压缩内容重复压缩，保持现有安全旁路；UDP LZ4 维持净收益门槛。

## 5. 独立专项：SS `chacha20-ietf-poly1305` 实现完整性

### 5.1 标准对照及结论

本节只审查经典 Shadowsocks AEAD（SIP004/SIP007），不是 AEAD-2022、XChaCha20 或 RSS 内部的 AES/HMAC/端口跳跃机制。规范：[Shadowsocks AEAD](https://shadowsocks.org/doc/aead)、[RFC 8439](https://www.rfc-editor.org/rfc/rfc8439)。

源码：[CipherKind](https://github.com/RockyLOMO/rxlib/blob/32b9be2792c55c47c8b54955995fba8589d9230a/rxlib/src/main/java/org/rx/net/socks/encryption/CipherKind.java#L12-L30)、[ChaCha20Poly1305ByteBufCrypto](https://github.com/RockyLOMO/rxlib/blob/32b9be2792c55c47c8b54955995fba8589d9230a/rxlib/src/main/java/org/rx/net/socks/encryption/impl/ChaCha20Poly1305ByteBufCrypto.java)、[ChaCha20Poly1305Support](https://github.com/RockyLOMO/rxlib/blob/32b9be2792c55c47c8b54955995fba8589d9230a/rxlib/src/main/java/org/rx/net/socks/encryption/impl/ChaCha20Poly1305Support.java)、[ShadowSocksKey](https://github.com/RockyLOMO/rxlib/blob/32b9be2792c55c47c8b54955995fba8589d9230a/rxlib/src/main/java/org/rx/net/socks/encryption/ShadowSocksKey.java)。

| 核查项 | 代码现状 | 判断 |
|---|---|---|
| method 注册 | CipherKind + ICrypto factory，SS Server TCP/UDP 都装载 | 已实现 |
| master key | 密码 UTF-8，兼容 EVP_BytesToKey 的 MD5 链，32 B | 符合密码模式 |
| subkey | HKDF-HMAC-SHA1，随机 32 B salt，info=ss-subkey，32 B 输出 | 已实现 |
| TCP wire | 初始 salt；2 B 大端长度+16 B tag；数据+16 B tag，最大 0x3FFF | 已实现 |
| TCP nonce | 双向分别从 0 开始，12 B 小端，每次 AEAD 增 1 | 已实现 |
| TCP 半包/粘包 | 独立 salt/长度/载荷状态机，pending buffer | 加密层已实现；地址层另有缺口 |
| UDP wire | 每包随机 32 B salt + 密文 + 16 B tag，独立 subkey，zero nonce | 已实现 |
| ChaCha20/Poly1305 | 自研 Java ByteBuf 路径，block0 派生认证密钥、block1 开始加密；完整 tag 比较、先验签后解密 | 源码符合结构，存在独立测试向量；未做完整密码学审计 |
| 资源释放 | CipherCodec handlerRemoved 调用 close；释放 pending buffer，清理 TCP 子密钥与 nonce | 已实现基础清理 |
| TCP / UDP salt 防重放 | 所审查 SS crypto/codec/server 路径未见跨连接、跨 UDP 包的已认证 salt 记录 | 安全加固待做 |
| 第三方互操作 | Python cryptography 独立 TCP 测试；ChaCha UDP 有本项目 round-trip，但主要 SS Server UDP 集成固定 AES256 | UDP 跨实现验证不足 |

结论：**核心算法与经典 AEAD TCP/UDP 编码已经实现，不能称作缺少 UDP 或仅 TCP 的半成品；端到端完整性仍需补齐。** 不建议先重写算法，更不应为了兼容性直接换成 AEAD-2022 或把 nonce 改成 24 字节。

现有测试：[ChaCha20Poly1305SupportTest](https://github.com/RockyLOMO/rxlib/blob/32b9be2792c55c47c8b54955995fba8589d9230a/rxlib/src/test/java/org/rx/net/socks/encryption/impl/ChaCha20Poly1305SupportTest.java) 有 RFC 8439 AAD 向量、空 AAD 向量、边界长度和篡改 tag；[Aes256GcmByteBufCryptoTest](https://github.com/RockyLOMO/rxlib/blob/32b9be2792c55c47c8b54955995fba8589d9230a/rxlib/src/test/java/org/rx/net/socks/encryption/Aes256GcmByteBufCryptoTest.java) 尽管文件名为 AES，内含 ChaCha 独立 TCP 向量、salt 拆包及 TCP/UDP round-trip；[ShadowsocksPythonClientIntegrationTest](https://github.com/RockyLOMO/rxlib/blob/32b9be2792c55c47c8b54955995fba8589d9230a/rxlib/src/test/java/org/rx/net/socks/ShadowsocksPythonClientIntegrationTest.java) 具有 ChaCha TCP Python 互操作，依赖 python cryptography，可能跳过。本次未执行这些测试。

### P1-SS-01：修复 TCP 目标地址跨 AEAD record 的处理（正确性）

源码：[SSProtocolCodec.decode](https://github.com/RockyLOMO/rxlib/blob/32b9be2792c55c47c8b54955995fba8589d9230a/rxlib/src/main/java/org/rx/net/socks/SSProtocolCodec.java#L47-L77)。

- 当前首个解密输出不足 4 字节直接 return；MessageToMessageCodec 会释放该输入，没有累计前缀。若有 4 字节但完整 IPv4/IPv6/域名地址尚未到齐，则直接调用 UdpManager.decode，可能报错关闭。TCP 传输分片本身由加密层处理，但多个合法 AEAD record 的明文地址可以分段输出。此为静态可推导的协议层缺口，影响共享 SS 地址解析，不限于 ChaCha。
- [x] 新增失败复现：独立客户端将目标地址拆成多个独立、合法的 AEAD record，分别送达；IPv4/IPv6/域名各测试每个拆分位置以及首段 1–3 B，附带首段无业务数据、后续正常 payload。
- [x] 仅在 TCP 首地址未完成时进行有界累计（最大地址头 259 B），先检查类型和所需长度，再解析并准确转发后续 payload；释放待解析 buffer；保持 UDP 每包独立，非法地址仍按原安全策略拒绝。测试连接关闭、非法类型、截断和内存泄漏。
- 验收：标准地址跨 record 能正确连接目标，既有单 record 快路径不增加常驻开销，无重复/丢失业务字节。

### P1-SS-02：明确经典 AEAD replay protection 及认证失败处理（安全）

源码：[ChaCha TCP salt](https://github.com/RockyLOMO/rxlib/blob/32b9be2792c55c47c8b54955995fba8589d9230a/rxlib/src/main/java/org/rx/net/socks/encryption/impl/ChaCha20Poly1305ByteBufCrypto.java#L211-L261)、[UDP](https://github.com/RockyLOMO/rxlib/blob/32b9be2792c55c47c8b54955995fba8589d9230a/rxlib/src/main/java/org/rx/net/socks/encryption/impl/ChaCha20Poly1305ByteBufCrypto.java#L263-L294)、[CipherCodec.decode](https://github.com/RockyLOMO/rxlib/blob/32b9be2792c55c47c8b54955995fba8589d9230a/rxlib/src/main/java/org/rx/net/socks/CipherCodec.java#L62-L119)。

- 正常随机 256 位 salt 的意外碰撞概率极低，但合法密文的重放不等同于随机碰撞。单连接 nonce 或 RSS 内部 UDP 防重放不能阻止外部 SS 连接/数据报重放。经典 AEAD 不等同于 AEAD-2022 的完整重放保护；这里建议经典 AEAD 的实用防重放加固，不声称改变 wire 即可实现全生命周期完美防重放。
- [x] 先用独立客户端验证同一合法 TCP salt+首个认证 record 重放到新连接、同一合法 UDP 包重复发送，确认当前行为；检查完整仓库是否已有外层 salt filter，避免重复实现。
- [x] 设计按 master key/算法正确隔离、跨相关连接/UDP listener 共享的有界已认证 salt cache。考虑跨方向、TCP/UDP 共用密钥的 salt 域及同密码多端口部署；不因不同密钥误判。先认证，再原子 check-and-record，未认证 salt 不污染缓存；首个长度认证与完整首个 payload 认证之间的时机需明确，避免并发重复转发。
- [x] 用精确、有界、可过期的内存结构优先评估，内存换安全/CPU；评估高 PPS UDP 下 cache 查找成本、锁竞争、容量/TTL、跨进程和重启边界，明确过期后无法保证无限期防重放。不要在热路径持久化每包 salt。
- [x] CipherCodec 对 BadPaddingException 派生的 tag 失败已有 TCP close/UDP drop，但短 UDP（不足 salt+tag）抛出的 DecoderException 不属于该分支；明确无效报文边界，验证 UDP 只丢坏包且下一合法包可用。TCP 非法长度/截断需明确关闭/释放行为；对重复认证失败日志限速，避免大量 WARN/I/O 增加 CPU。不得吞掉程序错误或隐瞒异常计数。
- [x] 回归 ChaCha 和 AES128/256：有效密文、错误密码、salt/tag/密文篡改、并发 replay、不同 key、UDP 换源地址、TTL/容量、异常后正常流、未认证 salt 不占 cache、正常合法重发使用新 salt；明确对旧端互操作影响。

### P1-SS-03：补齐独立客户端 TCP/UDP 互操作与边界矩阵

- [x] 使用 shadowsocks-rust（或独立 Python cryptography 实现）对 rxlib SS Server 进行真实双向 TCP、UDP relay 测试；固定外部 SS method=chacha20-ietf-poly1305，不借助 RSS 内部加密来证明 SS 合规。
- [x] 独立 UDP 固定向量：固定 password/salt，验证 HKDF、zero nonce、地址+payload、tag 字节；同时测试独立端加密→rxlib 解密、rxlib 加密→独立端解密。测试多包 salt 不同、乱序、不同源、IPv4/IPv6/域名、UDP MTU 边界及 malformed 包。
- [x] TCP：逐字节拆 salt、18 B 加密长度、载荷/tag，多 record 粘包、0/1/15/16/17/63/64/65/16382/16383/16384 B、大文件及长连接，多次写入与 nonce 进位；非法长度保留位、篡改长度/tag/数据、EOF 截断、空写 flush、half-close；验证只交付认证成功的完整明文。空载荷的接受行为按经典规范及参考实现确认。
- [x] 参数化现有 ShadowsocksServerIntegrationTest、ShadowsocksTcpDownloadIntegrationTest 和 CipherCodecTest 覆盖三个已支持 cipher；测试 heap/direct/composite ByteBuf、不同 readerIndex、引用计数与 close；JDK8 和 JDK21 都执行。独立互操作测试若依赖缺失须显式记录跳过，不将跳过计作通过。

### P2-SS-04：低 CPU 优化，保留现有 wire

- 当前 ChaCha20Poly1305Support 复用 FastThreadLocal scratch 与 Poly1305State；但 XOR 及 LE 读写存在逐字节 ByteBuf 操作，master key 每 TCP 连接执行密码 MD5 派生，UDP 每包 HKDF 多次 Mac.init。以上是候选热点，非已测得瓶颈。
- [x] JDK8/JDK21、1 核、具备/不具备 AES 加速环境下，比较当前自研 ChaCha、JDK 支持时的标准 ChaCha20-Poly1305 provider、可信库候选及 AES-GCM；同一数据、密文格式和完整认证，比较 cycles/byte 或 CPU/有效 MiB、PPS、分配率、GC、P99。
- [x] 优先用内存复用 immutable master key（严格隔离配置/生命周期，不向外暴露可变 key）、scratch 和批量 XOR/LE 操作，减少 HKDF 临时分配；必要时探索标准 provider 快路径、保留 JDK8 兼容实现。不能共享可变 nonce、跨线程复用不安全的 Mac/Cipher，也不允许缓存 UDP subkey 以重复 salt/nonce。
- [x] 检查自研算法的随机差分测试、tag 比较和 key/scratch 生命周期；保留 16 B tag、随机 salt 及完整认证，不用缩短 tag 或省略验证换 CPU。理论上 JDK/本地 provider 可能更快，但无基准不默认更换。
- 资源收益：SS classic ChaCha UDP 的固定加密开销为 48 B/包，TCP 每方向首次 32 B salt、每 record 34 B 额外字节；这些是标准格式的必要成本，不能为了省流量私自修改。应优先减少内存复制、每次 record 分配及合理合并业务写入（不得增加不可接受延迟）。

## 6. 统一验收与执行顺序（供 Codex）

### 6.1 执行顺序

1. P1-SS-01/02：先复现正确性和安全缺口，再最小修复；P1-SS-03 与修复同步补独立互操作测试。
2. P1-RSS-01/02：先采样 TCP/UDP 切换及探测空闲成本；分开实现驻留/退避，随后评估有协商保护的 daily 扩窗。
3. P1-RSS-03：先 CPU profile，实验减少无效 XOR；不因理论改善直接改变分组策略。
4. P2-RSS-04/05/06、P2-SS-04：仅在上述稳定后，独立 A/B 选择净收益为正的候选。
5. 不修改 plain 默认、现有 SS method 名称、密码兼容及 wire；RSS 协议变化要有能力协商和旧端回退。补 README/配置文档与测试报告；保留变更前后原始数据和失败候选，不只保存获胜样本。

### 6.2 测试矩阵与验收口径

- 同一硬件和环境，1 vCPU / 768 MiB，至少 3 个随机种子并重复、交替 A/B 顺序；真实 TCP/UDP 两端独立进程；区分 cold/warm JVM 与 session；对照 plain、daily、weak、game，以及 kcptun 0:0/10:1/相近实际开销。
- 负载：短回显、TCP 游戏、低 PPS UDP、长下载/上传（可压缩与不可压缩各一类）、长时空闲、并发大流与短流；RTT 30/80/150/300 ms，0/1/5/10/20% 随机丢包、突发丢包、抖动、乱序、反向丢包、UDP 单/双向阻断与恢复。按实际生产负载裁剪矩阵，避免无边界测试。
- 流量：丢包注入前双向 IP 字节，含原包、IP/UDP/TCP 头、内核重传、应用 ACK/GAP/SACK、parity、探测、压缩和加密；另记有效业务字节、各路径分类，避免将交付失败误记为省流。
- CPU：进程/整组 cgroup CPU 秒、CPU/有效 MiB、PPS、空闲唤醒、分配/复制、GC、EventLoop 排队及内核丢包；内存记录 JVM RSS、堆、direct、内核 socket、cgroup 峰值和缓存回收。整机内存与进程 RSS 不混用。
- 正确性：业务完整性、严格一次有序 TCP 交付、UDP 不新增重复交付、无引用计数泄漏、认证失败不泄漏明文、不污染重放状态；旧端协商与回退均通过。
- daily：无损时 parity 为 0；对轻微丢包保持原预算，真实字节和 CPU 不增加或有明确、事先认可的权衡；不以 100% 覆盖为目标。
- weak：允许在明确预算内以冗余改善 P99/最大停顿；复测历史 150 ms / 5% 样本。历史 40,000 B 和 kcptun 1.2× P99/最大停顿仅为对应测试候选门槛，不作通用生产指标。
- game：共同报告超时/丢包与成功回包 P99，降档不能因只看成功包延迟而恶化有效交付率。对 TCP 游戏独立验证，不将 UDP 多发效果套用到 WoW。
- SS：RFC 向量、独立实现互操作、地址分段、认证/防重放、JDK8/JDK21 全部验证；不要用本项目自加自解替代独立验证。

候选测试命令（仓库根目录；按当前 Maven profile、JDK 和测试依赖调整，未在本次执行）：

```bash
mvn -f rxlib/pom.xml -DskipTests=false \
  -Dtest=AdaptiveStreamTest,AdaptiveUdpPortTest,UdpDailyFecTest,UdpDailyFecCoverageTest,UdpGameRedundancyTest,DailyUdpWireTest,UdpSequenceWindowTest test
mvn -f rxlib/pom.xml -DskipTests=false \
  -Dtest=ChaCha20Poly1305SupportTest,Aes256GcmByteBufCryptoTest,CipherCodecTest,SSProtocolCodecTest,ShadowsocksServerIntegrationTest,ShadowsocksPythonClientIntegrationTest,ShadowsocksTcpDownloadIntegrationTest test
```

**最终决策：** 保留现有四个 profile 和轻量 XOR 核心；优先修复 SS 协议正确性/安全缺口，RSS 重点去除重复发送与探测/计算开销。内存可放宽以换真实流量、CPU 和安全收益，但保留上限。没有基准证据的性能优化不强行合入。

## 7. 实施与验收记录

2026-11-25，在本机 Windows 环境（Temurin JDK `21.0.12.1`、Temurin JDK `1.8.0_504`、Python `3.13.16` + `cryptography 48.0.0`）完成全部 P1 / P2 实施、独立客户端互操作、微基准对照及 Netty `PARANOID` 泄漏检查。第 1–6 节保留原始评审正文。

### 7.1 提交清单

| 提交 | 对应项 | 摘要 |
| --- | --- | --- |
| `ded9bbb9` | P1-SS-01 | TCP 首地址跨多个合法 AEAD record 时做 259 B 有界累计，完整解析后再路由并透传尾随 payload |
| `4ea063fb` | P1-SS-02 | 新增跨 SS listener 共享的 16 分片有界已认证 salt 缓存（`SaltHistory`，容量 65,536、TTL 2 分钟）及 30 s 限速拒绝统计 |
| `fed29877` | P1-RSS-01（前半） | 发送窗口耗尽回退指数冷却（`2→4→8→16→30 s`）及路径有效交付指标 |
| `bdb23dbc` | P1-SS-03 | 补齐三密码 TCP/UDP Python `cryptography` 互操作、65,536 B UDP 接收缓冲、TCP `ALLOW_HALF_CLOSURE` 半关闭转发与 `hasIncompleteRecord` EOF 截断拒绝 |
| `be69d1e9` | P1-RSS-01 / 02 | 阻断 UDP 探测指数退避（含 `[0, interval/8)` 抖动）、空闲无恢复任务暂停 timer、周期小写入保留退避、单次大写入溢出前直接走 TCP |
| `ca9d20c6` | P1-RSS-03 | 复用每会话有界 `ByteBuf` XOR 累加器（1 发送 + 4 接收槽）、满组到达 `PARITY` 立即跳过 XOR、移除 `3,004 B` 中间暂存缓冲、统计无校验组 |
| `54d75cf2` | P2-RSS-04 / 05 / 06 | `game` 带滞回边际降档、`AdaptiveUdpPort` 验签前只读防重放与原地零拷贝封装、`weak` 低 PPS 成对活动有限反馈及可靠修复覆盖后取消 pending parity |
| `2f6bed70` | P2-SS-04 | `SaltHistory` 缓存不可变 `ShadowSocksKey`、零分配 `FastThreadLocal` HKDF scratch、TCP `SecretKeySpec` 复用、`ChaCha20Engine` / `Poly1305` 64-bit LE 批量字操作与随机差分校验 |

### 7.2 P1-RSS-01 & P1-RSS-02：路径切换抑制、大包溢出保护与阻断探测退避

1. **1 小时 UDP 阻断与空闲探测对照**（解除阻断后模拟 RTT 30 ms，控制帧按 IPv4+UDP+认证封装 65 B/包计）：

| 场景（1 小时） | 探测包数（原 → 新） | 双向控制 IP B（原 → 新） | Timer 唤醒次数（原 → 新） | 解除阻断恢复耗时 |
| --- | ---: | ---: | ---: | ---: |
| 初始探测后持续空闲（阻断） | 1,800 → 1 | 117,000 → 65（**-99.94%**） | 1,800 → 2 | 下次写入后 30 ms |
| 每 6 s 业务写入（阻断） | 1,800 → 132 | 117,000 → 8,580（**-92.7%**） | 1,800 → 132 | 30–660 ms |
| 每 20 s 业务写入（阻断） | 1,800 → 130 | 117,000 → 8,450（**-92.8%**） | 1,800 → 133 | 30–660 ms |
| 每 1 s 持续写入（阻断） | 1,800 → 130 | 117,000 → 8,450（**-92.8%**） | 1,800 → 130 | 660 ms |

空闲超 30 s 后恢复业务写入时重置退避并立即受限探测；周期性间隔（`< 30 s`）不清空退避计数，同时保留 `probing` 位以接收跨暂停期晚到的合法 `PONG`。

2. **高 BDP 批量流路径切换与失败候选 `daily-w256`**（RTT 150 ms，随机种子 `718 / 2063 / 3491` 均值）：
   - 单次 `128 KiB` 写入（共 `16 MiB`，`8 Mbps` 链路）在进入分片循环前预判 `pending.size() + chunks > maxSlots`，避免先在 UDP 上发出 64 个注定被排空重传的分片，双向 IP 字节由 `17,509,798 B`（`32 KiB` 分块触发中途溢出）降至 `17,298,952 B`（净省 **211 KiB**）。
   - **失败候选（未采纳）：`daily` 扩窗至 256 槽（`daily-w256`）**。在 `8 Mbps`、`16 MiB` 下载下对比现行 `daily-w64`（含窗口耗尽指数冷却）：
     - `0%` 丢包：`daily-w64` `17,581,190 B`（`toTcp=4`） vs `daily-w256` `17,906,707 B`（**+318 KiB**）；
     - `1%` 丢包：`daily-w64` `17,756,072 B` vs `daily-w256` `18,674,538 B`（**+918 KiB / +5.2%**）；
     - `5%` 丢包：`daily-w64` `18,497,253 B`（完成 `16,527 ms`） vs `daily-w256` `19,476,275 B`（**+979 KiB / +5.3%**，完成 `16,543 ms`）；
     - `16 Mbps`、`4 MiB` 下载：`daily-w256` 同样多消耗 `+246～+283 KiB`（`+5.5%～+6.5%`）。
     原因：高 BDP 持续打满大窗口时，一旦发生丢包，整窗补发与校验开销显著高于提前切回可靠 TCP 驻留。因此保留 `daily` 默认 64 槽，不引入扩窗协商。

### 7.3 P1-RSS-03：有界 FEC 累加器复用、满组跳过 XOR 与失败候选

1. **有界累加器复用与满组跳过 XOR**（`10,000 × 1,200 B` 数据包，种子 `17 / 43 / 89` 均值）：

| 速率与丢包 | FEC 内部分配次数（原 → 新） | 总分配 KiB（原 → 新） | CPU ms（原 → 新） | 恢复分片 / 丢失分片 |
| --- | ---: | ---: | ---: | ---: |
| 20 PPS，1% 丢包 | 4,228 → 49（**-98.8%**） | 17,439 → 12,539 | 31 → 20 | 42 / 312（不变） |
| 20 PPS，5% 丢包 | 6,381 → 182（**-97.1%**） | 20,756 → 13,489 | 31 → 26 | 536 / 1,510（不变） |
| 50 PPS，1% 丢包 | 1,754 → 71（**-96.0%**） | 14,515 → 12,566 | 24 → 19 | 108 / 313（不变） |
| 200 PPS，1% 丢包 | 916 → 91（**-90.1%**） | 13,530 → 12,589 | 21 → 18 | 170 / 311（不变） |

满组齐备后到达的 `PARITY` 直接清理接收槽，省去 `1,200 B` 无效 XOR；5 秒反馈超时、`cancelPendingParity` 与 `close` 时主动释放池化缓冲，`PARANOID` 级别下 0 泄漏。

2. **失败候选（未采纳）：`daily` 预计无 `parityCredit` 时首包直接发 `DATA`**。虽可将 20 PPS 稀疏流未发校验组从 `1,139` 降至 `31`，但在“60 ms 稀疏间隔后突发 16 包 burst（300 轮，组内随机丢首包）”测试中，由于突发首包已作为 `DATA` 发出无法追溯纳入 XOR 组，恢复数从 `272 / 300`（`90.7%`）跌至 `217 / 300`（`72.3%`，**-18.4 个百分点**），`50 PPS 1%` 恢复数亦从 `108 / 313` 降至 `92 / 313`。按第 6.1 节与验收标准拒绝合入，保留现有分组策略。

### 7.4 P2-RSS-04 / 05 / 06：`game` 滞回降档、验签前防重放、`weak` 低 PPS 反馈与压缩复核

1. **P2-RSS-04 `UdpGameRedundancy` 带滞回边际降档**（2,000 包，种子 `626 / 718 / 2063` 均值）：
   - 先经历 `15%` 突发丢包升至 `3x`，随后转入 `2%` 轻微丢包时，满足原包几乎无损（`unique == 32` 且 `3x` 第三副本边际贡献 `<= 1` 连续 4 窗、`2x` 原包全达连续 6 窗）才逐级试探降档：`20 / 200 / 300 PPS` 平均发送倍率降至 `2.05x / 2.31x / 2.01x`，有效交付率保持 `99.32%–99.37%`；持续 `5%` 丢包（`steady5%`）不误降档，交付率维持 `99.57%–99.62%`。
2. **P2-RSS-05 `AdaptiveUdpPort` 验签前只读防重放与原地封装**：
   - 在长度、类型与会话校验后、HMAC 计算前执行 `peer.replay.isDuplicate(seq)` 只读拦截重复/过期包，重放攻击或网络重复包不再消耗 SHA-256 HMAC；`mark(seq)` 与 NAT 回程地址刷新仍严格置于验签通过之后。
   - `AdaptiveUdpPort.send` 对具备 `>= 16 B` 头部预留（`UdpDailyFec.WIRE_HEADROOM = 20`）与 `>= 16 B` 尾部可写空间的帧直接原地写入 16 B 头与 16 B tag，省去每包二次直接内存分配与载荷拷贝。
3. **P2-RSS-06 `weak` 低 PPS 成对活动反馈、取消冗余 parity 与 `zlib 1/3/6` 统计**：
   - 500 包、`5%` 丢包下，原 `weak` 在 `1 / 2 / 5 PPS` 因 32 包周期超过 5 s `REPORT_TIMEOUT` 导致 `parity=0`、恢复 `0 / 72`。改为仅在观测到 `< 180 ms` 成对微突发且累计 `>= 4` 个样本、距上次报告 `>= 3 s` 时触发有限 `REPORT`，并以累计样本数 `cleanSampled >= 96` 控制关闭阈值：均匀 `1 / 2 / 5 PPS` 保持 `0` 额外报告（字节 `125,320 / 125,370 / 125,515 B` 不变），成对微突发 `1 / 2 / 5 PPS` 发出 `19 / 24 / 29` 个校验包并恢复 `4 / 7 / 6` 个缺失分片；完全空闲时 0 心跳。
   - `AdaptiveStream` 在 TCP `GAP` 或 `SACK` 可靠补发已覆盖全部未确认序号（`nextTx - reliableUntil <= 0`）时立即调用 `fec.cancelPendingParity()`，避免与 15–180 ms FEC flush 重叠发出无效校验。
   - `4 MiB` TCP `zlib 1 / 3 / 6` 实测：可压缩 JSON 结构文本编码后分别为 `35,960 B`（`0.9%`，`3.91 ms/MiB`）、`35,946 B`（`0.9%`，`2.60–3.91 ms/MiB`）、`21,880 B`（`0.5%`，`7.81–9.11 ms/MiB`）；不可压缩随机字节在三个级别下均为 `4,196,872 B`（`100.1%`，`35.16–37.76 ms/MiB`）。确认维持默认 `zlib 3` 与已加密端口跳过压缩策略。

### 7.5 P1-SS-01 / 02 / 03 & P2-SS-04：经典 Shadowsocks AEAD 完整性、防重放与低 CPU 优化

1. **正确性、防重放与互操作（`ded9bbb9`、`4ea063fb`、`bdb23dbc`）**：
   - `SSProtocolCodec` 对分段到达的首个目标地址做 259 B 上限累计，覆盖 IPv4 / IPv6 / 域名在任意字节边界（含 1–3 B 首段及空业务载荷首段）跨多个 AEAD record 拆分。
   - `SaltHistory` 按 `(cipher ordinal, masterKey)` SHA-256 域隔离，跨同进程 TCP/UDP listener 共享 16 分片精确 FIFO 表（总容量 65,536，2 分钟过期）；TCP 在首个有效载荷认证通过后登记 salt，UDP 在整包验签通过后原子登记，坏包不污染缓存且 UDP 通道遇短包/坏包仅丢弃单包不中断后续合法包。
   - `ShadowsocksServer` 与 `SSTcpProxyHandler` 启用 `ALLOW_HALF_CLOSURE` 并在前后向 relay handler 中透传半关闭（`SHUT_WR`），修复客户端写半关闭后服务端提前关闭导致下行响应丢失的问题；`CipherCodec` 在 `ChannelInputShutdownEvent` 时检查 `hasIncompleteRecord()`，拒绝半途截断的 AEAD 帧；UDP 配置 `FixedRecvByteBufAllocator(65536)` 覆盖大报文边界。
   - 独立客户端 `classic_aead_client.py`（Python `cryptography`）验证 `aes-128-gcm`、`aes-256-gcm`、`chacha20-ietf-poly1305` 三种密码的双向 TCP（含拆包、半关闭）与双向 UDP relay。

2. **P2-SS-04 加密热点基准与 JCE 对照（`2f6bed70`）**：

| 场景 | JDK 21 优化前（cpuMs/MiB / kpps） | JDK 21 优化后（cpuMs/MiB / kpps） | JDK 8 优化后（cpuMs/MiB / kpps） |
| --- | ---: | ---: | ---: |
| TCP `chacha20-ietf-poly1305` 1,400 B | 32.18 / 23.3 | **15.21 / 49.2**（-52.7% CPU） | 21.07 / 35.6 |
| TCP `chacha20-ietf-poly1305` 16,383 B | 28.75 / 2.2 | **11.00 / 5.8**（-61.7% CPU） | 15.50 / 4.1 |
| UDP `chacha20-ietf-poly1305` 256 B | 76.80 / 53.3 | **54.40 / 75.3**（-29.2% CPU） | 70.40 / 58.2 |
| UDP `chacha20-ietf-poly1305` 1,200 B | 34.82 / 25.1 | **17.75 / 49.2**（-49.0% CPU） | 25.26 / 34.6 |
| 新建连接 `SaltHistory.newCrypto`（50,000 次） | 1,562.5 ns/conn | **~0–312.5 ns/conn**（0 次 MD5） | 312.5 ns/conn |
| TCP `aes-256-gcm` 1,400 B / 16,383 B | 12.87 / 5.75 ms/MiB | 11.70 / 6.50 ms/MiB | 13.46 / 7.75 ms/MiB |
| UDP `aes-256-gcm` 256 B / 1,200 B | 67.20 / 17.07 ms/MiB | 70.40 / 16.38 ms/MiB | 83.20 / 19.11 ms/MiB |

   - **JDK 21 `Cipher("ChaCha20-Poly1305")` 候选对照与取舍**：单次裸缓冲 JCE `ChaCha20-Poly1305`（不含 TCP 2 B 长度 AEAD 记录与 UDP 每包 HKDF）在 JDK 21 下为 `38.40 / 11.61 / 7.25 ms/MiB`（`256 / 1200 / 16383 B`），但 SunJCE `ChaCha20Cipher` 每次 `init` 需新建 `IvParameterSpec` 并克隆 nonce，且解密路径会将整段密文缓存在堆内存 `ibuffer` 直至 `doFinal`；而本轮 64-bit LE 批量字操作优化后的自研 `ByteBuf` 实现全程零堆分配、直接支持 `CompositeByteBuf` 且同时兼容 JDK 8 与 JDK 21，在含完整 SS 协议封装下已达 `11.00–15.21 ms/MiB`，因此保持单一零分配实现，并通过 160 轮随机长度/偏移/复合缓冲与 JCE `ChaCha20-Poly1305` 逐字节差分校验及 128 位逐比特 tag 篡改测试。

### 7.6 定向回归验收与验证边界

在 Temurin JDK `21.0.12.1` 与 Temurin JDK `1.8.0_504` 下分别执行候选测试集（补充 `SaltHistoryTest`）：

1. **RSS 传输回归集**（`AdaptiveStreamTest,AdaptiveUdpPortTest,UdpDailyFecTest,UdpDailyFecCoverageTest,UdpGameRedundancyTest,DailyUdpWireTest,UdpSequenceWindowTest`）：JDK 21 与 JDK 8 各运行 **50 项（49 通过、0 失败、1 跳过：`DailyUdpWireTest` 中仅限 Linux 的用例在 Windows 跳过）**，启用 `-Dio.netty.leakDetection.level=PARANOID` 检查 0 泄漏。
2. **Shadowsocks AEAD 单元、编解码与独立客户端集成集**（`ChaCha20Poly1305SupportTest,Aes256GcmByteBufCryptoTest,CipherCodecTest,SSProtocolCodecTest,SaltHistoryTest,ShadowsocksServerIntegrationTest,ShadowsocksPythonClientIntegrationTest,ShadowsocksTcpDownloadIntegrationTest`）：JDK 21 与 JDK 8 各运行 **51 项（51 通过、0 失败、0 跳过，其中 `org.rx.net.socks.*` 49 项 + `org.rx.net.CipherCodecTest` 2 项；Python `cryptography` 独立客户端互操作实际执行）**。两集合计 **101 项（100 通过、1 跳过、0 失败）**。
3. **环境与未验证边界**：本轮基准与回归均在单机 Windows 环境通过本地回路及模拟丢包/延迟链路完成；Linux `nft` 端口重定向、1 vCPU / 768 MiB cgroup 容器整机峰值内存，以及跨公网长时连接和 WoW 实机体验未在本机 Windows 环境执行，留待 Linux 部署环境按相同指标核验。