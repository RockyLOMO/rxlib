# UDP pipeline MTU 实现二次 Review 计划与结论

# 背景

用户反馈 `agent/udp-pipeline-mtu-plan` 分支已更新，要求再次 review `docs/plan/udp-pipeline-mtu-unified-entry-plan.md` 对应实现。

本次 review 基于：

- 分支：`agent/udp-pipeline-mtu-plan`
- 上次 review commit：`197a4af5a4529eee65d9a3b78cdfee3cf1cb4c7d`
- 本次新增实现 commit：`8743b367cfbf728eac60bb16e7493200c4729f3a`
- 新增实现 message：`feat(net): implement UdpFinalEgressGuardHandler and robustify UDP integration tests`

本轮只做 review 和计划文档提交，不直接修改业务代码。

# 任务类型判断

本次任务归类为 **Review / 修复 / 重构 / 优化需求**。

原因：

- 用户要求“分支已更新，再 review 下”。
- 需要检查上一轮指出的 final MTU 位置、冗余副本绕过统一入口、测试覆盖和 CI 验证是否已修复。
- 按仓库 agent 规则，Review 类任务先提交 review/计划文档，等待用户明确要求后再进入代码修复阶段。

# 当前上下文

## 已 review 的文件

本次新增 commit 修改了：

- `rxlib/src/main/java/org/rx/net/Sockets.java`
- `rxlib/src/test/java/org/rx/net/socks/SocksProxyServerIntegrationTest.java`
- `rxlib/src/test/java/org/rx/net/socks/UdpPipelineMtuGuardTest.java`

结合上一轮 review，继续关注：

- `Sockets.writeUdp(...)`
- `Sockets.addUdpOptimizationHandlers(...)`
- `Sockets.UdpFinalEgressGuardHandler`
- `UdpRedundantEncoder` 产生真实 datagram 的位置
- UDP compression / redundant handler 的 outbound 顺序
- `SocketConfig.udpMtu`
- `SocksConfig` 上一次引入的无关字段删除

## 本次新增实现概览

本次更新新增了 `Sockets.UdpFinalEgressGuardHandler`，并将 final guard 安装到 UDP pipeline 中。

核心变化：

1. 新增 `UDP_FINAL_EGRESS_GUARD` handler 名称。
2. `udpBootstrap(...)` 中会安装 final guard：
   - `SocksConfig`：根据 `udpMtu` 或 redundant 是否启用安装。
   - 非 `SocksConfig`：根据 `udpMtu` 安装。
3. `addUdpOptimizationHandlers(...)` 内部也会先调用 `addUdpFinalEgressGuard(...)`，然后再安装 compression/redundant handlers。
4. `writeUdp(...)` 如果检测到 channel 已有 final guard，会跳过前置 MTU/pending/isWritable 判断，把最终检查交给 final guard。
5. 新增 `UdpPipelineMtuGuardTest`，覆盖：
   - final guard 与 encoder 的 pipeline 顺序。
   - redundant header 后超过 MTU 被 drop。
   - redundant 多副本在 MTU 边界通过。
   - 原始 payload 超过 MTU 但压缩后小于等于 MTU 时允许通过。

# 目标

1. 判断本次更新是否解决上一轮指出的核心问题。
2. 检查 `udpMtu = 1300` 是否已能约束 compression / redundant 后的最终真实 datagram。
3. 找出剩余风险、验证缺口和建议修复项。
4. 输出后续执行建议，供用户决定是否继续修复。

# 非目标

1. 本轮不直接修改业务代码。
2. 本轮不触发或伪造 CI 成功结果。
3. 不扩大到 TCP、HTTP tunnel 或非 UDP 业务重构。
4. 不引入新依赖、不升级 Maven 依赖、不发布 release。

# 设计方案

## Review 结论概览

本次更新已基本修复上一轮的最高优先级问题：

> MTU 检查必须发生在 compression / redundant 之后、transport 之前。

从当前实现看，`UdpFinalEgressGuardHandler` 被放在 pipeline 更靠近 head 的位置，而 `UdpCompressEncoder`、`UdpRedundantEncoder` 被放在它之后。Netty outbound 从 tail 向 head 执行，因此实际顺序是：

```text
业务写出 -> UdpCompressEncoder -> UdpRedundantEncoder -> UdpFinalEgressGuardHandler -> transport
```

这能让 final guard 看到压缩、多倍发送后生成的真实 `DatagramPacket`，并逐个检查 `packet.content().readableBytes() <= udpMtu`。

新增的 `UdpPipelineMtuGuardTest` 也覆盖了上一轮要求的两个关键反例：

1. 原始 payload 不超，但 redundant header 后超 MTU，应 drop。
2. 原始 payload 超 MTU，但压缩后小于等于 MTU，应允许发送。

因此，核心设计方向现在是正确的。

## 发现 1：final MTU 位置已基本正确

严重级别：已修复 / 正向确认。

当前 `Sockets.addUdpOptimizationHandlers(...)` 先安装 final guard，再安装 redundant/compress encoder。由于 outbound handler 执行方向是 tail -> head，因此最终发送前会经过 final guard。

新增测试 `finalGuardIsBeforeOutboundOptimizationEncoders()` 明确断言：

- `UDP_FINAL_EGRESS_GUARD` 存在。
- `UdpRedundantEncoder` 存在。
- `UdpCompressEncoder` 存在。
- pipeline 名称顺序满足 guard 在 redundant 前、redundant 在 compress 前。

该断言与 Netty outbound 执行方向配合，能证明当前设计意图是：compress -> redundant -> final guard。

结论：上一轮指出的“MTU 检查发生在 outbound encoder 之前”的核心问题，当前实现已基本修复。

## 发现 2：redundant 副本现在会经过 final guard

严重级别：已修复 / 正向确认。

`UdpRedundantEncoder` 内部通过 `ctx.write(new DatagramPacket(...))` 写出首包和副本。由于 final guard 位于 redundant encoder 的 outbound 下游，encoder 生成的每个真实 datagram 都会继续向 head 传播并经过 final guard。

新增测试覆盖：

- `finalGuardDropsRedundantPacketAfterHeaderExceedsMtu()`：1293 bytes payload + 8 bytes redundant header = 1301 bytes，在 `udpMtu=1300` 时最终 drop。
- `finalGuardAllowsEachRedundantPacketAtMtuBoundary()`：1292 bytes payload + 8 bytes header = 1300 bytes，在 `udpMtu=1300` 时 3 个副本都通过。

结论：上一轮指出的“UdpRedundantEncoder 副本绕过 final MTU”的核心风险，当前实现已明显收敛。

## 发现 3：压缩后再 MTU 的方向已修复

严重级别：已修复 / 正向确认。

`writeUdp(...)` 在检测到 channel 已安装 final guard 时，不再前置执行 `udpMtu` 检查，而是允许原始 payload 进入 pipeline。这样原始 payload 大于 MTU 时，仍有机会先被 `UdpCompressEncoder` 压缩，再由 final guard 对压缩后的真实 datagram 做 MTU 检查。

新增测试 `writeUdpAllowsRawPayloadAboveMtuWhenCompressionShrinksFinalDatagram()` 覆盖了该场景。

结论：上一轮指出的“原始包大于 MTU 但压缩后可通过会被提前 drop”的问题，当前实现已修复。

## 发现 4：final guard 的 promise 语义会把 final drop 表现为 write success

严重级别：中。

`UdpFinalEgressGuardHandler` 在 inactive、unresolved recipient、MTU exceeded、pending overlimit、not writable 等 drop 场景中会：

1. release packet。
2. 记录 metrics。
3. 调用 `completeDroppedWrite(promise)`，对非 void promise 执行 `promise.trySuccess()`。

这避免了上游 write future 被异常传播打断高频 UDP 链路，但也带来语义限制：

- `Sockets.writeUdp(...)` 返回 `ACCEPTED` 后，即使 final guard 后续 drop，调用方的 write future / completion listener 也会看到 success。
- 这会让调用方只能通过 metrics 观察 final drop，无法从当前 `UdpWriteResult` 或 write future 判断最终是否真的发送。

这不一定是 bug，但需要作为明确设计语义记录。特别是 `SSUdpProxyHandler` 里有 completion listener 用于释放 per-source pending，这种场景 success/drop 都需要释放，因此当前行为是可接受的；但如果未来有调用方依赖 write future 判断真实发送结果，就会误判。

建议：

- 在 `UdpFinalEgressGuardHandler` 注释中明确：final egress drop is metrics-observable and promise-success by design。
- 或增加可选策略，让 final drop 使用 `tryFailure`，但这可能影响现有 UDP 高频链路稳定性。

## 发现 5：public `addUdpOptimizationHandlers(...)` 的调用顺序仍有潜在脚枪

严重级别：中。

当前 `addUdpOptimizationHandlers(...)` 的顺序在 `udpBootstrap(...)` 场景下是正确的，因为它会先于业务 handler / protocol handler 安装。后续业务 handler 被 addLast 后，outbound 顺序是：业务/protocol -> compress -> redundant -> final guard。

但该方法是 public，如果外部在已经安装 protocol outbound handler 之后再调用：

```text
protocol handler -> final guard -> redundant encoder -> compress encoder
```

那么 outbound 执行可能变成：compress -> redundant -> final guard -> protocol handler。此时 protocol handler 仍可能在 final guard 之后修改 datagram 大小，重新破坏“最终真实 datagram <= mtu”的语义。

建议：

- 在 `addUdpOptimizationHandlers(...)` Javadoc 中明确要求：必须在业务/protocol outbound handler 之前安装。
- 或提供更明确的 API，例如 `addUdpOptimizationHandlersBeforeProtocol(...)` / `addUdpFinalEgressGuardBefore(...)`，避免外部误用。
- 或在主要调用点都只通过 `Sockets.udpBootstrap(...)` 自动安装，不鼓励手动调用。

## 发现 6：`SocksConfig.kcptunClient` 无关删除仍未恢复

严重级别：中。

上一轮指出的无关变更仍存在：`SocksConfig` 中的 `kcptunClient` 字段在本分支中已删除。

由于 `SocksConfig` 使用 Lombok `@Getter` / `@Setter`，删除字段会删除公开 getter/setter，可能影响外部配置或历史 API。该变更与 UDP MTU / 背压 / compression / redundant 目标无关。

建议：

- 如无明确需求，恢复 `private AuthenticEndpoint kcptunClient;`。
- 如果确实要删除，应拆成独立 commit，并说明兼容性和迁移路径。

## 发现 7：CI 仍未验证

严重级别：中。

查询 `agent/udp-pipeline-mtu-plan` 分支的 GitHub Actions workflow run，当前仍为 0 条记录。查询 commit `8743b367cfbf728eac60bb16e7493200c4729f3a` 的 combined status，也没有 status context。

因此当前不能声称 CI 通过。

建议：

- 后续代码修复或最终确认前，必须触发 `jdk8-unit-tests.yml`。
- `test_classes` 至少包含：
  - `org.rx.net.SocketsTest`
  - `org.rx.net.socks.UdpPipelineMtuGuardTest`
  - 如涉及集成路径，再加 `org.rx.net.socks.SocksProxyServerIntegrationTest` 的相关测试。

## 发现 8：测试覆盖已增强，但仍缺少真实业务路径的 MTU 集成测试

严重级别：低到中。

`UdpPipelineMtuGuardTest` 已覆盖 pipeline 层面核心机制，但仍偏向 EmbeddedChannel 单元测试。建议后续补充至少一个业务路径集成测试：

- SOCKS UDP relay + `udpMtu=1300` + redundant/compress。
- Shadowsocks UDP + `udpMtu=1300`，确认 SS protocol/cipher 包装后 final guard 仍能看到最终 datagram。
- Udp2raw client/server mode 中至少一个出口经过 final guard 的测试。

这些不是阻塞核心修复的必选项，但能降低未来 pipeline 顺序被重构破坏的风险。

# 修改文件列表

本轮 review 文档新增：

- `docs/plan/udp-pipeline-mtu-implementation-rereview.md`

如果后续继续修复，预计修改：

- `rxlib/src/main/java/org/rx/net/Sockets.java`
  - 补充 final guard/drop promise 语义注释。
  - 补充 `addUdpOptimizationHandlers(...)` 安装顺序说明，或改造 API 防误用。
- `rxlib/src/main/java/org/rx/net/socks/SocksConfig.java`
  - 恢复 `kcptunClient`，除非该删除是明确独立需求。
- `rxlib/src/test/java/org/rx/net/socks/UdpPipelineMtuGuardTest.java`
  - 可继续补充 final drop promise/metrics 行为测试。
- `rxlib/src/test/java/org/rx/net/socks/SocksProxyServerIntegrationTest.java`
  - 可补充业务路径 MTU 集成测试。

# 风险点

1. **CI 风险**：目前没有 Actions run，不能确认 JDK8 编译和单测通过。
2. **API 兼容风险**：`SocksConfig.kcptunClient` 删除仍未恢复。
3. **调用顺序风险**：public `addUdpOptimizationHandlers(...)` 如果被手动晚于 protocol handler 调用，会削弱 final MTU 语义。
4. **可观测性风险**：final guard drop 通过 metrics 观察，write promise 被标记 success；调用方无法从 `UdpWriteResult` 直接知道 final drop。
5. **业务路径覆盖风险**：目前新增测试主要是 pipeline 单元测试，真实 SOCKS/SS/udp2raw 路径仍建议补充至少一个集成测试。

# 验证方案

## 必跑编译和单测

```bash
mvn -pl rxlib -am test -DskipTests
mvn -pl rxlib -am test -Dtest=SocketsTest,UdpPipelineMtuGuardTest
```

## 建议补充/执行的集成测试

```bash
mvn -pl rxlib -am test -Dtest=SocksProxyServerIntegrationTest
```

如耗时过长，可先筛选相关 UDP case，但最终合入前建议至少跑一次相关集成测试。

## GitHub Actions

后续需要触发或依赖：

- `jdk8-unit-tests.yml`

要求：

- branch filter：`agent/udp-pipeline-mtu-plan`
- `test_classes` 包含 `org.rx.net.SocketsTest,org.rx.net.socks.UdpPipelineMtuGuardTest`
- 只有 workflow run `conclusion=success` 才能认为 CI 通过。
- 如果 CI 失败，先分类为编译失败、单测失败、format/checkstyle、依赖下载、JDK 版本或环境问题，再做最小修复。

# 当前 Review 结论

本次更新已经把核心 MTU guard 从提交前检查推进到 final egress 位置，方向正确，且新增测试覆盖了 compression 和 redundant 后的最终 datagram 场景。

当前仍建议处理三个收尾项后再认为实现可合入：

1. 恢复或解释 `SocksConfig.kcptunClient` 的无关删除。
2. 补充 `addUdpOptimizationHandlers(...)` 的安装顺序约束说明，避免 public API 被误用。
3. 触发 `jdk8-unit-tests.yml` 并确认 `conclusion=success`。
