# 背景

用户要求在 `rxlib` 库的 `master` 分支上 review `shadowsocksServer` 相关类。本阶段只做代码 review、调用链分析、风险梳理和后续修复计划沉淀，不修改业务代码。

目标仓库为 `RockyLOMO/rxlib`，目标基线为 `master` 分支。review 范围集中在 `rxlib/src/main/java/org/rx/net/socks` 及 Shadowsocks AEAD 加解密、TCP/UDP relay、route 和 upstream 相关实现。

# 任务类型判断

本次任务归类为 **Review / 修复 / 重构 / 优化需求**。

原因：用户描述为“review 下 shadowsocksServer 相关类”，属于对已有实现的代码审查；涉及现有 `ShadowsocksServer`、`SSProtocolCodec`、`CipherCodec`、`SSTcpProxyHandler`、`SSUdpProxyHandler` 等类的调用链、边界条件、性能和资源释放风险分析。用户没有要求立即实现代码，因此本阶段只提交计划文档，不修改业务代码。

# 当前上下文

## 已 review 的文件

主要生产代码：

- `rxlib/src/main/java/org/rx/net/socks/ShadowsocksServer.java`
- `rxlib/src/main/java/org/rx/net/socks/ShadowsocksConfig.java`
- `rxlib/src/main/java/org/rx/net/socks/CipherCodec.java`
- `rxlib/src/main/java/org/rx/net/socks/SSProtocolCodec.java`
- `rxlib/src/main/java/org/rx/net/socks/SSTcpProxyHandler.java`
- `rxlib/src/main/java/org/rx/net/socks/SSUdpProxyHandler.java`
- `rxlib/src/main/java/org/rx/net/socks/SocksTcpFrontendRelayHandler.java`
- `rxlib/src/main/java/org/rx/net/socks/SocksTcpBackendRelayHandler.java`
- `rxlib/src/main/java/org/rx/net/socks/SocksContext.java`
- `rxlib/src/main/java/org/rx/net/socks/UdpManager.java`
- `rxlib/src/main/java/org/rx/net/socks/UdpRelayAttributes.java`
- `rxlib/src/main/java/org/rx/net/socks/UdpRelayGroupManager.java`
- `rxlib/src/main/java/org/rx/net/socks/upstream/*`
- `rxlib/src/main/java/org/rx/net/socks/encryption/ICrypto.java`
- `rxlib/src/main/java/org/rx/net/socks/encryption/CryptoAeadBase.java`
- `rxlib/src/main/java/org/rx/net/socks/encryption/impl/AesGcmCrypto.java`
- `rxlib/src/main/java/org/rx/net/socks/encryption/impl/ChaCha20Poly1305Crypto.java`

相关测试：

- `rxlib/src/test/java/org/rx/net/socks/CipherCodecTest.java`
- `rxlib/src/test/java/org/rx/net/socks/SSProtocolCodecTest.java`
- `rxlib/src/test/java/org/rx/net/socks/SSUdpProxyHandlerTest.java`
- `rxlib/src/test/java/org/rx/net/socks/ShadowsocksServerIntegrationTest.java`
- `rxlib/src/test/java/org/rx/net/socks/SocksUdpRelayHandlerTest.java`
- `rxlib/src/test/java/org/rx/net/socks/TimeoutVerificationTest.java`
- `rxlib/src/test/java/org/rx/net/socks/UdpValidationTest.java`

## 关键调用链

### TCP Shadowsocks 链路

1. `ShadowsocksServer` 使用 `Sockets.serverBootstrap(config, ...)` 绑定 TCP `serverEndpoint`。
2. 每个 TCP channel 初始化独立 `ICrypto`，设置 `setForUdp(false)`，写入 `ShadowsocksConfig.CIPHER`。
3. Pipeline 依次加入可选 `ProxyChannelIdleHandler`、`CipherCodec.DEFAULT`、`SSProtocolCodec`、`SSTcpProxyHandler.DEFAULT`。
4. `CipherCodec.decode` 解密客户端首包。
5. `SSProtocolCodec.decode` 解析 Shadowsocks 地址头，并将目标地址写入 `ShadowsocksConfig.REMOTE_DEST`。
6. `SSTcpProxyHandler.channelRead` 根据 `REMOTE_DEST` 构造 `SocksContext`，触发 `server.onTcpRoute`。
7. route 回调设置 upstream，之后 `Bootstrap.connect` 建立后端连接。
8. 连接成功后加入 `SocksTcpFrontendRelayHandler` 和 `SocksTcpBackendRelayHandler` 进行双向 relay。

### UDP Shadowsocks 链路

1. `ShadowsocksServer` 使用 `Sockets.udpBootstrap(config, ...)` 绑定 UDP `serverEndpoint`。
2. UDP channel 初始化一个 `ICrypto`，设置 `setForUdp(true)`，写入 `ShadowsocksConfig.CIPHER`。
3. Pipeline 加入 `CipherCodec.DEFAULT`、`SSProtocolCodec`、`SSUdpProxyHandler.DEFAULT`。
4. `CipherCodec.decode` 解密 `DatagramPacket`。
5. `SSProtocolCodec.decode` 对每个 UDP datagram 调用 `UdpManager.decode` 解析目标地址，并将目标地址写入 `ShadowsocksConfig.REMOTE_DEST`。
6. `SSUdpProxyHandler.channelRead0` 以 `(source, destination)` 作为 route key，命中 route cache 时直接写入已有 route；未命中时进入 route init 状态，暂存 pending packets，触发 `server.onUdpRoute`。
7. route 完成后根据 upstream 类型打开或复用 outbound channel。
8. 后端响应由 `UdpBackendRelayHandler` 接收，重新补 Shadowsocks 地址头后通过 inbound UDP channel 写回客户端。

## 当前实现意图

当前实现意图是提供同时支持 TCP 和 UDP 的 Shadowsocks AEAD 服务端：默认 direct router，允许调用方通过 `onTcpRoute` / `onUdpRoute` 自定义 upstream；TCP 每连接独立 crypto state；UDP 在 server DatagramChannel 上复用 crypto，并在 AEAD 实现中使用线程本地 cipher / buffer 以避免共享可变状态；UDP route 层提供 route cache、route init pending queue、outbound pool、per-source 限流和 socks5 UDP relay 支持；`useDedicatedCryptoGroup` 可将 `CipherCodec`、`SSProtocolCodec` 和代理 handler 移到共享 crypto executor，降低 EventLoop crypto 压力。

## 已发现的问题或风险

1. `CipherCodec` 在处理 `InvalidCipherTextException` 时调用 `ExceptionUtils.getRootCause(e).toString()`，如果 root cause 为 `null`，异常处理分支本身可能抛出 `NullPointerException`，导致本应静默丢弃或关闭 TCP 的解密失败路径变成未预期异常。
2. `SSTcpProxyHandler` 中 `BackpressureHandler.install(inbound, outbound)` 当前被注释。需要确认 `SocksTcpFrontendRelayHandler` / `SocksTcpBackendRelayHandler` 或 `Sockets.writeAndFlush` 是否已经覆盖读写水位控制；否则大流量 TCP relay 可能存在堆积和内存压力风险。
3. `ShadowsocksServer` 的 `SHARED_CRYPTO_GROUP` 是静态共享 executor，当前 dispose server 时不会关闭该 executor。若服务在测试或长生命周期进程中频繁创建/销毁，可能保留线程和线程本地 crypto buffer。
4. `SSUdpProxyHandler` 使用静态 `OUTBOUND_POOL` 和 `OUTBOUND_POOL_SOURCE_COUNTS`。虽然存在 pool remove 路径，但仍需要验证 server dispose、inbound close、outbound connect failure、route init failure 等路径是否都能清理全局池和 per-source 计数，避免跨 server 实例残留。
5. `ShadowsocksConfig` 暴露 `udpReadTimeoutSeconds` / `udpWriteTimeoutSeconds`，但 UDP pipeline 里没有明显对应 `ProxyChannelIdleHandler` 接入点；需要确认这些配置是否为死配置，或是否在 `SSUdpProxyHandler` 的 outbound idle 逻辑中被替代。
6. `onTcpRoute` / `onUdpRoute` 是用户可替换 delegate。当前调用链对 route 结果的空 upstream、空 destination、非法 endpoint 等情况缺少集中防御，异常时可能表现为 NPE 或连接失败日志不够明确。
7. UDP route init pending queue 已有包数和字节上限，但 retained `ByteBuf` 的释放依赖 route 完成、失败、drop 和 write callback 多条路径；需要通过 leak detector 或针对性测试覆盖异常路径。
8. `activeChannelCount()` 当前只统计 TCP accepted channels，不包含 UDP server channel 或 UDP route/outbound channel。名称可能造成监控含义误解，需要确认这是预期语义还是应改名/补注释。
9. 默认 `useDedicatedCryptoGroup=false`，AEAD crypto 会在 EventLoop 上执行；高吞吐下可能影响 I/O 延迟。启用 dedicated group 后，又需要验证 route callback、UDP FastThreadLocal crypto state 和 handler 执行线程的串行性假设。

# 目标

- 沉淀 `ShadowsocksServer` 相关类的 review 结论。
- 明确 TCP / UDP 主调用链、资源生命周期、ByteBuf 所有权和线程模型。
- 给出后续可执行的最小修复计划。
- 保持 JDK8 兼容，不引入 Java 9+ API。
- 保持现有 public API 兼容，除非后续用户明确要求变更。
- 后续若进入实现阶段，优先补充针对性单元测试或集成测试，再进行最小代码修复。

# 非目标

- 本阶段不修改 `rxlib/src/main/java/**` 业务代码。
- 本阶段不修改测试代码。
- 不升级 JDK，不引入需要 JDK9+ 的依赖。
- 不重写 Shadowsocks 协议实现。
- 不改包名、不改 public 方法签名。
- 不修改 `.github/workflows/**`。
- 不发布 release。
- 不删除分支。

# 设计方案

本阶段只提交 review 计划文档。若用户后续明确要求“按计划执行”或“开始修改代码”，建议按以下最小改动顺序推进。

## 1. 加固解密失败异常处理

涉及 `CipherCodec.java`。将 `ExceptionUtils.getRootCause(e).toString()` 改成 null-safe 的错误消息提取；保持 TCP 解密失败关闭 channel、UDP 解密失败丢包的现有行为；增加或扩展 `CipherCodecTest`，覆盖 root cause 为 null 的 `InvalidCipherTextException` 路径。

## 2. 审核 TCP backpressure

涉及 `SSTcpProxyHandler.java`、`SocksTcpFrontendRelayHandler.java`、`SocksTcpBackendRelayHandler.java`、`BackpressureHandler.java`。先确认现有 relay handler 是否已经在 write pending、channel writability、autoRead 之间建立背压；如果没有覆盖，则优先恢复或等价接入 `BackpressureHandler.install(inbound, outbound)`；增加针对 channel writability 或大 payload relay 的测试。

## 3. 审核 UDP route / outbound pool 生命周期

涉及 `SSUdpProxyHandler.java`、`ShadowsocksServer.java`。梳理 `OUTBOUND_POOL` 的创建、connect failure、closeFuture、server dispose 和 route remove 路径；确认 `OUTBOUND_POOL_SOURCE_COUNTS` 在所有失败路径都 decrement/remove；若存在残留，增加 per-inbound close cleanup，只清理本 server / inbound 相关 key，避免误关其他 server 的 active outbound。

## 4. 审核 UDP timeout 配置是否生效

涉及 `ShadowsocksConfig.java`、`ShadowsocksServer.java`、`SSUdpProxyHandler.java`、`TimeoutVerificationTest.java`。确认 `udpReadTimeoutSeconds` / `udpWriteTimeoutSeconds` 是否应控制 server UDP channel、outbound UDP channel 或 route idle；若配置确认为预期生效但当前未接线，按现有 `ProxyChannelIdleHandler` 或 outbound idle 逻辑做最小接入；若配置不应生效，补充注释或调整文档。

## 5. 加固 route 回调结果校验

涉及 `SSTcpProxyHandler.java`、`SSUdpProxyHandler.java`、`SocksContext.java`。在 route 回调完成后集中校验 upstream、destination endpoint 和 relay endpoint；对非法结果输出清晰 warn 日志，并关闭 TCP 或丢弃 UDP pending packets；增加 route 返回 null / invalid upstream 的测试。

## 6. ByteBuf 所有权和 leak detector 验证

涉及 `SSProtocolCodec.java`、`SSUdpProxyHandler.java`、`UdpManager.java` 及对应测试。逐条检查 `retain()` / `release()` 所有权转移，重点覆盖 UDP route pending queue、`UdpBackendRelayHandler`、`Sockets.writeUdp` callback、route init failure/drop path。只修复确认存在的泄漏，不做无关重构。

# 修改文件列表

## 本阶段实际修改

- 新增 `docs/plan/ShadowsocksServer-master-review-2026-05-04.md`

## 后续如果用户确认执行，预计可能修改

- `rxlib/src/main/java/org/rx/net/socks/CipherCodec.java`
- `rxlib/src/main/java/org/rx/net/socks/SSTcpProxyHandler.java`
- `rxlib/src/main/java/org/rx/net/socks/SSUdpProxyHandler.java`
- `rxlib/src/main/java/org/rx/net/socks/ShadowsocksServer.java`
- `rxlib/src/main/java/org/rx/net/socks/ShadowsocksConfig.java`
- `rxlib/src/test/java/org/rx/net/socks/CipherCodecTest.java`
- `rxlib/src/test/java/org/rx/net/socks/SSUdpProxyHandlerTest.java`
- `rxlib/src/test/java/org/rx/net/socks/ShadowsocksServerIntegrationTest.java`
- `rxlib/src/test/java/org/rx/net/socks/TimeoutVerificationTest.java`

# 风险点

- **兼容性风险**：Shadowsocks TCP/UDP 行为涉及外部客户端兼容，不能随意改变地址头、AEAD salt/subkey/nonces、UDP packet 格式。
- **性能风险**：恢复或调整 backpressure、crypto executor、UDP pool cleanup 可能影响吞吐和延迟。
- **并发风险**：UDP route init、outbound pool、shared crypto executor 和 FastThreadLocal state 存在线程模型假设，修复时必须避免引入竞态。
- **资源释放风险**：ByteBuf、DatagramPacket、ChannelFuture、pending packet、source pending counter 和 static pool 清理路径多，异常路径容易遗漏 release。
- **测试风险**：网络集成测试可能受端口占用、时序和环境影响。应优先做小型单元测试，再跑集成测试。
- **JDK8 风险**：所有改动必须继续兼容 JDK8，不能使用 Java 9+ API 或升级到需要 JDK9+ 的依赖。
- **公共 API 风险**：`ShadowsocksConfig`、`ShadowsocksServer` 属于外部可用 API，除非用户明确要求，不应改 public 方法签名。

# 验证方案

本阶段只提交计划文档，不修改业务代码，因此不触发代码级 CI 验证。

如果用户确认进入代码实现阶段，代码 commit 后应触发 `.github/workflows/jdk8-unit-tests.yml`，并通过 `workflow_dispatch` 传入相关测试类：

```text
CipherCodecTest,SSProtocolCodecTest,SSUdpProxyHandlerTest,ShadowsocksServerIntegrationTest,SocksUdpRelayHandlerTest,TimeoutVerificationTest,UdpValidationTest
```

可复用 workflow 中的 Maven 命令模式：

```bash
mvn -B -U -Dgpg.skip=true -Dmaven.test.skip=false -DskipTests=false -Dtest=CipherCodecTest,SSProtocolCodecTest,SSUdpProxyHandlerTest,ShadowsocksServerIntegrationTest,SocksUdpRelayHandlerTest,TimeoutVerificationTest,UdpValidationTest clean test
```

重点验证项：JDK8 编译通过；`CipherCodec` 解密失败路径不再抛出额外 NPE；`SSProtocolCodec` TCP/UDP 地址解析和回写保持兼容；TCP relay 在后端慢写或不可写时不会无限堆积；UDP route init pending queue 在成功、失败、drop、dispose 路径均释放 ByteBuf；UDP outbound pool 在 close、connect failure、server dispose 后无静态残留；`ShadowsocksServerIntegrationTest` 覆盖 TCP/UDP 基础可用性。
