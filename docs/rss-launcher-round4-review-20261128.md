# RSS Launcher Round 4 Review — RPC 安全、并发正确性与资源生命周期

- 日期：2026-11-28
- 审查基线：[`55aff29f`](https://github.com/RockyLOMO/rxlib/commit/55aff29f563802463b38085de1e379c926c18a3d)（master）。
- 状态：待实施；本文件仅为静态 Review 与修复计划，不代表问题已修复或测试已通过。
- 范围：RSS Launcher 的实际数据面与控制面依赖；合并本轮两份 Review，去重后形成统一任务清单。独立于此前 [rxlib-review-20261128.md](rxlib-review-20261128.md)，不覆盖历史计划、测试记录或源码。
- 约束：安全与正确性优先；保持 Java 8 兼容、JDK 21 回归；1 vCPU / 768 MiB **整机含 OS** 为生产验收目标；避免新增线程池、每连接定时器、无界缓存、额外协议栈及无必要的核心算法重写。

## 1. 审查提交

| Commit | 内容 |
| --- | --- |
| [3044e7c5](https://github.com/RockyLOMO/rxlib/commit/3044e7c5eba1fcd9802d5733e9231bf32b4ce65e) | 0x8c lightweight、FAST、安全配置与迁移修复 |
| [086be044](https://github.com/RockyLOMO/rxlib/commit/086be0449125b7fde2165bca5396fdb04a549e87) | RPC capabilities、plain UDP Group、容量预留与按需池 |
| [927475a6](https://github.com/RockyLOMO/rxlib/commit/927475a6c9344bbd6e0e77bedbacc3dede9fa2ba) | Round 3 §8–§12 新测试 |
| [55aff29f](https://github.com/RockyLOMO/rxlib/commit/55aff29f563802463b38085de1e379c926c18a3d) | 文档、双 JDK 回归与公网 A/B 基准 |

本轮没有直接修改 RssLauncher.java；关注实际运行链路。部分问题属于新引入的实现边界，部分为原有安全策略尚未迁移完成，不一律归因于最近四次提交。

## 2. 合并后的优先级

| ID | 优先级 | 事项 | 状态 |
| --- | --- | --- | --- |
| P1-01 | P1，最高，发布前验收 | FAST 实际启用、私有 RPC 强认证与加密 | 配置缺口已静态确认；实际线上套件待实测 |
| P1-02 | P1 | UDP Group 并发幂等与请求重试 | 静态竞态 |
| P1-03 | P1 | UDP Group 身份/所有权、per-client 计数及 Group 内 relay 配额 | 静态缺口与竞态 |
| P1-04 | P1 | TCP Warm Pool 关闭、连接超时、数量硬上限 | 静态竞态及清理风险 |
| P2-05 | P2 | RPC capabilities 唯一缓存及 generation 失效 | 静态缺口 |
| P2-06 | P2 | 资源预算、长期运行与基准口径 | 待验证，生产验收不可省略 |

P1-03、P1-04 已吸收前一份 Review 中独立列出的 per-client CAS、Group 内 relay 上限、pool key 上限及超时清理，不重复立项。P2-05 吸收原 P1 缓存问题；若失效会导致安全策略错误或不可恢复的业务中断，则提升为 P1。

## 3. P1-01：FAST 默认模式不代表实际启用 FAST 加密

### 证据与结论

- [TransportConfig.java L33-L74](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/net/transport/core/TransportConfig.java#L33-L74)：securityMode=FAST 与 enableEncryption、authSecret 独立；enableEncryption 默认为 false。setter 仅拒绝 NONE+encryption 的冲突。
- [RssServer.java L125-L137](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/util/rss/RssServer.java#L125-L137)：RSS RPC Server 设置 GFW/CIPHER_BOTH、超时和 sensitiveMethods，未显式设置 Core authSecret/enableEncryption。
- [RssClient.java L956-L989](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/util/rss/RssClient.java#L956-L989)：pooled 与 stateful/event RPC Client 同样配置旧 TransportFlags；[RpcClientConfig.java L29-L59](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/net/rpc/RpcClientConfig.java#L29-L59)、[RpcClientConfig.java L85-L94](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/net/rpc/RpcClientConfig.java#L85-L94)、[RpcServerConfig.java L34-L41](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/net/rpc/RpcServerConfig.java#L34-L41) 创建默认 TransportConfig。
- [TransportClient.java L193-L199](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/net/transport/core/TransportClient.java#L193-L199)、[TransportClient.java L425-L444](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/net/transport/core/TransportClient.java#L425-L444)：只有 enableEncryption=true 才请求 AEAD，开启但缺少 secret 才拒绝配置。[TransportServer.java L191-L197](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/net/transport/core/TransportServer.java#L191-L197)、[TransportServer.java L376-L405](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/net/transport/core/TransportServer.java#L376-L405) 对应服务端校验。
- [TransportProtocol.java L152-L166](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/net/transport/core/TransportProtocol.java#L152-L166) 与 [TransportProtocol.java L261-L275](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/net/transport/core/TransportProtocol.java#L261-L275)：String secret 为空时 verifyHelloAuth/verifyHelloAckAuth 返回 true；ByteArray 版本则拒绝空 secret。
- [Sockets.java L1014-L1019](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/net/Sockets.java#L1014-L1019)、[Sockets.java L1108-L1113](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/net/Sockets.java#L1108-L1113)：旧 cipher handler 路径仍在。因此不能把配置缺口直接表述为 RPC 完全明文，亦不能把 GFW、CIPHER_BOTH、日志 sensitiveMethods 或 RPC Token 当作已启用 Core AEAD 的证据。

风险：敏感 RPC（UDP Group/密钥、租约、DNS、WoW 注册等）可能未达到固定 FAST 的双向握手认证、会话方向独立密钥及加密完整性目标。实际线上配置、承载和旧 cipher 的安全性未独立验证。没有证据证明某次生产会话已被窃听或存在未授权访问。

### 实施要求

- [ ] 盘点所有私有 RPC 创建入口：RSS pooled/stateful client、server、外层 SOCKS carrier、Nameserver 私有复制、RRP、Rendezvous 等；逐项记录当前 Core enableEncryption/secret 来源、旧 cipher、是否要求保护、真实协商套件，不能只检查枚举值。
- [ ] 对要求保护的私有控制面显式启用 FAST + authSecret + enableEncryption=true；缺少有效密钥、错误套件、鉴权失败、降级或未知版本时 fail closed。服务端应在 bind/发布运行状态前完成必要校验；客户端在创建 facade/池前校验，包括 minPoolSize=0 的惰性路径；热更新失败保留上一有效配置。
- [ ] 明确密钥配置、强度、轮换及节点身份绑定；不得使用空值、公开默认值或记录明文；区分 RPC 传输密钥与业务 token、UDP data-plane cookie/secret。若采用共享 PSK，必须承认它只证明共享组身份，另有经验证的节点/用户凭据完成授权。
- [ ] 协议层固定 AES-128-GCM + HMAC-SHA256（握手 16 B tag，会话/方向独立派生），不引入新套件。不长期叠加无必要的 legacy cipher 与 Core AEAD；通过受控双端升级/回滚移除冗余层，禁止自动降级到未认证旧协议。
- [ ] 保留 RSS 数据面合法 plain、lightweight、目标端口加密策略和第三方标准 SOCKS5 兼容；不要把通用 TransportConfig 的默认值一刀切改成必须加密，破坏显式允许的 local/bypass 场景。保护场景与可选场景必须有明确边界。
- [ ] 敏感 RPC 请求和响应均需认证保护，尤其 openUdpRelayGroup 返回的密钥/凭据；复核日志、异常和诊断信息不会泄露 token、PSK 或返回 secret。不要将 sensitiveMethods 日志掩码视为加密。
- [ ] 对已有 RPC 会话验证授权后再分配业务资源；认证前的帧长度/握手超时/反序列化边界仍须有界。

### 验收

双 JDK 真实建连：pooled（min=0 和 min>0）、event/stateful、直连、嵌套 carrier、server；验证协商 AEAD flag、实际 coreAead handler、双方认证、密文及篡改拒绝，而非仅断言 SecurityMode 枚举。覆盖缺密钥、错误密钥、未认证、NONE、降级、重连、热更回滚、旧版本双端升级及无额外 cipher 叠加。受保护 RPC 在认证/加密失败时不得执行敏感方法或分配 Group；原合法数据面矩阵保持通过。

## 4. P1-02：UDP Group 并发幂等

源码：[UdpRelayGroupManager.java L113-L127](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/net/socks/UdpRelayGroupManager.java#L113-L127)、[UdpRelayGroupManager.java L176-L224](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/net/socks/UdpRelayGroupManager.java#L176-L224)。当前先 get 幂等缓存、分配资源、最后 put；没有在途 single-flight。相同请求的并发副本可能各自分配 Group/Socket，最后只能缓存其中一个结果。新增测试 [RssLightweightAndRpcOptimizationTest.java L877-L952](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/test/java/org/rx/net/socks/RssLightweightAndRpcOptimizationTest.java#L877-L952) 的重复请求为串行，不能证明并发幂等。

- [ ] 已认证 principal + 结构化 requestId 作为键；校验 requestId 长度及规范化，避免字符串拼接碰撞。相同 ID、不同请求参数应明确拒绝（对规范化参数做一致性检查）。
- [ ] 有界 in-flight single-flight，唯一 owner 进行实际 allocation；其他调用共享结果。不要持有全局锁进行 bind 或阻塞 EventLoop。等待超时与 owner 执行期限分离，单个 waiter 取消不应取消其他调用。
- [ ] 成功结果与活跃 Group 生命周期一致；明确 TTL、关闭、过期、重试、失败后重试、容量满策略；不得为接纳新请求随意淘汰尚需保证幂等的活跃条目。异常、取消、晚到 bind 及 manager dispose 均回收资源并终结 future。
- [ ] 客户端一次逻辑 open 生成一个 requestId，重试必须复用；不能对有副作用的 RPC 盲目用新 ID 重发。若在服务端重启后无法保证去重，应明确语义及恢复方式，不承诺跨重启 exactly-once。

验收：32 路相同已授权请求并发，全部成功响应指向同一 Group，实际 bind/预留仅一次；不同参数、不同 principal、丢失响应后重试、超时、取消、失败重试、关闭/重启及上限场景无孤儿资源。

## 5. P1-03：UDP Group 身份授权与严格资源配额

### 5.1 身份与所有权

[UdpRelayGroupManager.java L113-L116](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/net/socks/UdpRelayGroupManager.java#L113-L116)、[UdpRelayGroupManager.java L521-L562](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/net/socks/UdpRelayGroupManager.java#L521-L562)：clientKey 来自 request.clientId。[SocksProxyServer.java L368-L410](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/net/socks/SocksProxyServer.java#L368-L410)：open/add/remove/heartbeat/close 校验共享 RPC Token，但没有逐 Group principal 校验。[SocksRpcContract.java L69-L91](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/net/socks/SocksRpcContract.java#L69-L91)：token 来自共享配置。现状属于信任边界缺口，不代表未持有 token 的匿名请求能成功。

- [ ] 根据认证上下文获取服务端认可的稳定 principal（节点/用户作用域按真实拓扑定义），clientId 仅作为声明或诊断字段。多个用户共用 RSS 节点时，应明确可信节点代传用户身份的授权模型；不能凭自报用户 ID 实现用户级安全边界。
- [ ] Group 创建、续租、扩容、移除 relay、关闭、幂等返回均检查 owner；跨连接/重连仍以稳定 principal 校验，不能把 socket 地址或 NAT IP 当身份。保留内部受信任调用入口，避免远端绕过校验。
- [ ] 管理员跨 owner 操作须单独授权；认证失败不分配资源、不暴露密钥或 Group 详情。复核旧 claim/reset/udp2raw 等相邻敏感接口的同类边界，独立立项处理无关实现。

### 5.2 per-client 计数归零与删除非原子

[UdpRelayGroupManager.java L521-L550](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/net/socks/UdpRelayGroupManager.java#L521-L550)：releaseClientSlot 先递减至 0，再 remove(key,count)。并发 acquire 可以在两操作之间将同一计数器从 0 增至 1 并通过 map 校验，之后被旧 release 删除；后续创建使用新计数器，造成实际组数与配额不一致。

建议用 ConcurrentHashMap.compute/computeIfPresent 将同一 principal 的判断、递增、递减与零值删除放在同一原子操作中，配合 exactly-once reservation/rollback，避免以 clamp-to-zero 掩盖双重释放。单独 CAS 与事后校验不足以修复该交错。

### 5.3 单 Group relay 上限与关闭

[UdpRelayGroupManager.java L228-L315](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/net/socks/UdpRelayGroupManager.java#L228-L315)、[UdpRelayGroupManager.java L317-L418](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/net/socks/UdpRelayGroupManager.java#L317-L418)：add 根据 relays.size 检查可用数量，多个 add 可同时通过检查；全局 relay CAS 不能替代单 Group 的硬限制。close/remove 当前在异步 channel 关闭前释放配额，若预算含实际打开的 socket，需要单独统计 closing/reserved 数并避免瞬时突破硬上限。

- [ ] 为每 Group 原子预留 relay slot，或串行化该 Group 的扩容/关闭状态变更；不可全局锁住 I/O。
- [ ] 明确 pending → bound → closing → closed 所有权转移；全局、principal、Group 三级容量精确回滚。bind/注册失败、close 与 open/add 并发、manager dispose、晚到 Future 等路径不产生幽灵 relay。
- [ ] 区分逻辑 endpoint、跳端口 alias 与实际 socket；配额按实际成本及业务定义统一，检查 alias 不能产生越界。
- [ ] 当前默认 Group 4096、每 Client 256、Relay 8192 为逻辑上限，不代表 768 MiB 机器可以承载。配置可验证且有硬上限，实际默认值根据压力实测确定，不凭推测直接上调。

验收：并发 open/close 同 principal，尤其 0→1 交错；多个 principal、同 principal 多连接、跨 owner 操作被拒绝；并发 add 不超过每 Group cap；达到三级预算后拒绝且无需额外 socket；失败/关闭/晚到回调全部收敛到零。断言实际 channel/FD 与计数，而不只检查 map.size。

## 6. P1-04：TCP Warm Pool 生命周期及容量

源码：[Socks5UpstreamPoolManager.java L87-L177](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/net/socks/Socks5UpstreamPoolManager.java#L87-L177)、[Socks5UpstreamPoolManager.java L179-L269](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/net/socks/Socks5UpstreamPoolManager.java#L179-L269)、[Socks5UpstreamPoolManager.java L285-L292](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/net/socks/Socks5UpstreamPoolManager.java#L285-L292)、[Socks5UpstreamPoolManager.java L390-L464](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/net/socks/Socks5UpstreamPoolManager.java#L390-L464)。

| 资源 | 当前默认 |
| --- | ---: |
| TCP Pool key 数量 | 64 |
| 全局 warm channel 预算（含预留） | 64 |
| 并发建连 | 8 |
| 维护调度 | 全局共享 |

### 6.1 close/refill 竞态

refill 检查 isClosed/targetSize 后才 offerLast；dispose 可在两步之间排空队列，随后新 Channel 进入已关闭 Pool。热更新、路由切换、淘汰尤其需要正确释放。建议小范围同步所有权转移，或入队后重新校验状态、以 remove 成功者作为释放责任方；同时确保 borrow 不会交付已经关闭/淘汰 generation 的连接。检查正在补充的任务退出及预算归还。

### 6.2 创建超时及晚到连接

createWarmChannel 的 catch 仅尝试从已完成的 readyFuture 取得 Channel。建连/认证超时时 Future 可能仍未完成或异常完成，不能保证此处立即释放，底层是否最终关闭需针对性验证。保留 connectFuture/channel 引用，失败时取消、主动关闭并处理晚到成功；清理覆盖认证失败、超时、取消、任务提交拒绝及 manager dispose。inFlight 和 warm reservation 均由唯一 owner 在 finally/关闭回调归还，禁止计数泄漏及双重释放。

### 6.3 maxTcpPools 不是硬限制

evictIfPoolCapReached 与 computeIfAbsent 不原子，不同 key 并发时可突破限制。只在新 key 插入/淘汰路径同步或 CAS 预留，已有 pool 正常借用不增加全局锁；没有可淘汰 pool 时允许走冷连接，不应阻塞数据面。协调 shared maintenance 的启动/取消和新 pool 发布，避免有池无调度、无池仍调度；保留空闲缩容、退避与路由 generation 隔离。

### 验收

- [ ] 用 latch/barrier 精确覆盖 create 完成→close→入队、并发 borrow/retire、热更、淘汰/回滚、连接成功但认证超时、connect timeout 后迟到成功、任务拒绝/异常。
- [ ] 128 个以上不同 key 并发创建，poolCount<=64、warm reservation<=64、inFlight<=8；按实际关闭状态统计资源，不以计数归零代替 socket 已关闭。
- [ ] 空闲停止预热、无 pool 后调度取消、下一次需求可恢复；无专用 per-pool timer。关闭后 FD/Channel/预算回到基线。
- [ ] 保留 TCP 正常冷连接 fallback、预热命中、用户/路由/安全模式隔离；回归自适应健康回调与合法半关闭。

## 7. P2-05：RPC capabilities 双缓存及失效一致性

源码：[RssClient.java L280-L406](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/util/rss/RssClient.java#L280-L406)、[SocksUdpUpstream.java L431-L532](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/net/socks/upstream/SocksUdpUpstream.java#L431-L532)、[SocksRpcCapabilitiesCache.java L75-L157](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/rxlib/src/main/java/org/rx/net/socks/SocksRpcCapabilitiesCache.java#L75-L157)。

SocksUdpUpstream.forFacade 与 ForwardingSocksRpcContract.capabilitiesCache 两层重复。mismatch 路径只调用 invalidateFacade 清外层，内层仍可返回旧结果；两层 TTL 也可能延长旧能力可见期。invalidateAll 只清 entries，不阻止 inFlight loader 迟到后重新 store。普通 capability 不应充当授权判断。

- [ ] 每个 facade/token scope 只保留一份公共实现；通用 facade 和 RSS wrapper 明确缓存归属。按 peer/credential/generation 隔离，保持正/负 TTL、有界条目及不缓存 transient/auth exception。
- [ ] 使用 generation/epoch，使 invalidate(token)/invalidateAll、断线、服务重启、热更、配置 mismatch 与 loader 的结果发布原子协调；旧在途请求不可污染新 generation，失效后新调用不 join 旧 in-flight。清理以 key+future identity 为准。
- [ ] bounded in-flight、有限等待、不阻塞 EventLoop；不新增线程或定时器。保留 AUTO 在 RPC 不可用时合理回落的现有合法策略，不允许安全要求被缓存/回落绕过；记录低基数命中、失效及失败指标。

验收：并发首查只有一次真实 capabilities RPC；负 TTL、瞬态错误不缓存；执行中 invalidate、服务能力变化、断线重连、热更新、token 轮换及 mismatch 后下一请求真实刷新；旧 loader 晚到不覆盖新值；waiter 取消及 loader 异常不遗留 in-flight。

## 8. P2-06：性能与低配资源验收

依据 [test-benchmarks.md §8](https://github.com/RockyLOMO/rxlib/blob/55aff29f563802463b38085de1e379c926c18a3d/docs/test-benchmarks.md#L269-L308)（已有记录，非本次独立复测）：

- 双 JDK 各报告 389 用例、2 skip、0 failures/errors、PARANOID LEAK 0；这是功能覆盖证据，不证明上述交错、FD/Channel 生命周期或生产负载安全。
- lightweight 冷建连+64 B Echo 最低耗时下降 136.5–197.3 ms，plain-NONE 294→177 B（-39.8%），plain+zlib3 348→289 B（-17%）；仅支持减少握手 RTT/线字节。部分 P50/P95 反而上升，每组仅 10 次，不能声称弱网尾延迟普遍改善。
- Plain UDP RPC Group：缓存后首包 P50 782.2 vs 1015.9 ms，P95 1445.4 vs 2098.5 ms；专用 SOCKS TCP 控制连接 0 vs 8，不包含共享 RPC 连接；单发成功 7/10 vs 8/10，不能推出可靠性差异。
- 公网 VPS 短测进程 RSS ~205.3 MiB、heap used 39.77 MiB、pooled direct 8 MiB、JVM 26 threads，整机峰值 used 414/960 MiB；不是 768 MiB 整机/1024 节点/24h 验收结果。部署环境、统计口径与峰值必须明确。

- [ ] 真实 1 vCPU / 768 MiB 整机（含 OS；若使用 cgroup 需明确等效性），1024 节点及 >=24h 稳态，区分注册节点、活跃 session、UDP Group 与 socket 数。逐步升压，限制 FD、socket/kernel memory、native memory、heap/direct、timer、thread、队列及重试开销；超预算拒绝并可恢复，不 OOM。
- [ ] 记录实际 IP bytes、成功/失败/超时、P50/P95/P99/Max、CPU/有效交付量、cgroup/OS 峰值、FD 与资源回收曲线；加入连接风暴、网络故障、热更新、密钥轮换及断线重连。固定 seed 与随机/突发丢包、多轮交错 A/B，比较同等成功工作量并报告失败。
- [ ] 对修复前后运行 Java 8 + 21、Netty PARANOID、RSS/Core/SOCKS/RPC/Nameserver/RRP/SS 相关矩阵；输出准确 commit、命令、JDK、OS、Surefire pass/skip/fail/error；专门验证非 ByteBuf 的泄漏。
- [ ] 受保护的 RPC 新旧路径需分别基准，安全修复产生的合理成本单独记录，禁止为了维持旧性能而跳过认证或加密。
- [ ] 保持 plain 零额外 Core 状态、0x8c 的非 AEAD 轻量协商、受保护路径一次 Core FAST 握手、按需 RPC/Warm Pool、UDP Group 复用和既有迁移正确性；不新增加密套件，不复活已淘汰的协议设计。无认证/无加密的合法数据面与必需保护的控制面严格分离。

## 9. 实施顺序及发布门槛

1. P1-01：先完成入口盘点、密钥/身份模型和 fail-closed 测试，形成明确的双端升级/回滚策略。
2. P1-03 → P1-02：先定义 principal/owner 与资源预留，再在正确身份作用域内实现幂等；两项可同一批实现但分别验证。
3. P1-04：Warm Pool 小范围修复，保留热路径与共享 scheduler；补充精确竞态测试。
4. P2-05：唯一缓存、失效 epoch、边界测试；安全依赖则提前处理。
5. P2-06：全量回归与生产资源/弱网验收，更新既有 benchmark 文档；不能仅凭旧报告或单次 VPS 成功宣布达标。

建议每个主题独立 commit，测试随实现提交；不把此前独立 Review 的无关事项、NAT/Relay 设计或全局限速重构带入本轮。完成所有 P1、无已知安全降级、双 JDK 回归与实际资源验收后才视为可发布。当前文档不改变任何 Java 源码、配置、生产密钥、部署或服务；所有新增方案与测试均待实施。
