# 背景

用户在 `rockylomo/rxlib` 的 `agent/udp2raw-adaptive-mtu-plan` 分支上说明代码已更新，并要求继续 review。

本次 review 的重点是：

- `1200` 以下是否可以继续下探。
- server response 方向 MTU_ACK 是否绑定 pending probe peer。
- MTU control frame 是否接入 peer guard / auth failure fuse。
- tiny MTU 是否避免发送超过目标的 probe。
- 新增测试是否覆盖关键行为。

本阶段只 review 并提交计划文档，不修改业务代码。

# 任务类型判断

本次属于 Review / 修复 / 优化需求。

原因：

- 用户明确要求“再 review 下”。
- 代码已经在 feature 分支实现，需要检查最新改动的正确性、边界条件、资源释放和测试覆盖。
- 按仓库流程，本阶段不进入代码修改阶段。

# 当前上下文

review 分支：

- `agent/udp2raw-adaptive-mtu-plan`

review 基准提交：

- `4271fd5c357a7c35d807cc4577c131d05295222b`
- commit message：`feat(socks): enforce strict peer mapping for response-direction MTU ACKs and apply security fuses on MTU control packets`

已 review 的文件：

- `rxlib/src/main/java/org/rx/net/socks/Udp2rawMtuState.java`
  - 新增 `ADAPTIVE_MIN_MTU = 576`。
  - 默认 `Udp2rawMtuState(initialMtu, side)` 改为 `min=576, max=initialMtu`，使 `1200` 以下仍可继续下探。
  - `nextProbe(...)` 在 `currentMtu < Udp2rawMtuProbeSupport.MIN_PROBE_DATAGRAM_BYTES` 时不发送 probe，并记录 `probe-too-small`。
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawMtuProbeSupport.java`
  - 新增 `MIN_PROBE_DATAGRAM_BYTES = FIXED_HEADER_LENGTH + authLenByte + DEFAULT_TAG_BYTES`。
  - `encodeProbe(...)` 对过小 target MTU 直接抛 `IllegalArgumentException`，避免生成实际大于 target 的 probe。
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawTunnelContext.java`
  - 新增 `pendingMtuProbePeer`。
  - peer 变化时清空 pending peer。
  - `acceptMtuAck(...)` 要求 ACK sender 与 pending probe peer 一致，否则记录 `ack-peer-mismatch` 并拒绝更新 state。
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawServerEntryHandler.java`
  - `MTU_ACK` 路径接入 `isPeerBlocked(...)`、`allowPeerPacket(...)`。
  - ACK auth 失败时调用 `recordAuthFailure(...)`。
  - ACK 成功校验后调用 `tunnel.acceptMtuAck(...)`，由 tunnel 做 peer + seq + accepted MTU 检查。
- `rxlib/src/main/java/org/rx/net/socks/SocksProxyServer.java`
  - 新增 package-private `udp2rawTunnelContext(...)`，用于测试查看 tunnel context。
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawServerEntryManager.java`
  - 新增 package-private `context(String tunnelId)`，支持测试获取 context。
- `rxlib/src/test/java/org/rx/net/socks/Udp2rawMtuStateTest.java`
  - 新增 `defaultStateCanLowerBelow1200WhenHardCapIsSmall`。
  - 新增 `tinyMtuDoesNotEmitOversizedProbe`。
- `rxlib/src/test/java/org/rx/net/socks/Udp2rawFixedEntryIntegrationTest.java`
  - response direction probe 测试增强：非 probed peer 的 ACK 不更新 MTU，正确 peer ACK 会把 server state clamp 到 1290。
  - 新增 bad MTU_ACK auth 会触发 peer block fuse 的测试。

关键调用链：

```text
低 MTU 状态机:
new Udp2rawMtuState(1000, "client")
  -> min=576, max=1000, current=1000
  -> onWriteMtuDrop(1000)
  -> current=920

server response probe:
Udp2rawTunnelContext.runMtuProbe
  -> mtuState.nextProbe(now)
  -> sendMtuProbe(channel, peer, probe)
  -> markPendingMtuProbePeer(peer)
  -> Sockets.writeUdp(UdpMtuProbeDatagramPacket)

server ACK consume:
Udp2rawServerEntryHandler.handleMtuAck
  -> peer blocked / rate-limit check
  -> auth verify, auth failure fuse
  -> read strict 4-byte acceptedMtu
  -> tunnel.acceptMtuAck(sender, seq, acceptedMtu, now)
  -> pending peer must match
  -> mtuState.ack(seq, acceptedMtu, now)
```

当前实现意图：

- `1200` 不再是自适应 MTU 的硬下限，只是默认安全区间概念。
- 默认 adaptive min 为 576，允许极端 WAN 环境下继续向下回退。
- probe 包不能小于 udp2raw control frame 最小可编码大小。
- server response 方向 ACK 必须来自最近被 probe 的 peer，避免状态被其他 peer 劫持。
- bad ACK 与 DATA/MTU_PROBE 一样纳入 auth failure fuse。

# 目标

1. 确认 `1200` 以下继续下探的设计已正确实现。
2. 确认 tiny MTU 不会导致 oversized probe。
3. 确认 server-side response MTU ACK 的 peer mapping 已加强。
4. 确认 bad ACK 已进入 peer guard / auth failure fuse。
5. 识别剩余低风险边界问题和测试改进点。

# 非目标

1. 不修改业务代码。
2. 不触发 CI。
3. 不考虑旧协议兼容性。
4. 不实现 UDP payload 分片/重组。
5. 不修改 release、secrets、token、证书或私钥。

# 设计方案

本次 review 结论：主路径设计基本合理，没有看到新的明显 blocker。

## 已确认通过的点

1. `1200` 以下继续下探已落地

`Udp2rawMtuState(int initialMtu, String side)` 改为使用 `ADAPTIVE_MIN_MTU=576`，并将 `maxMtu` 设为 `initialMtu`。

例如：

```java
new Udp2rawMtuState(1000, "client")
```

现在语义是：

```text
min=576
max=1000
current=1000
```

因此 `onWriteMtuDrop(1000)` 后可以降到 `920`，符合“1200 还往下探”的目标。

2. tiny MTU probe guard 已补

`Udp2rawMtuProbeSupport.MIN_PROBE_DATAGRAM_BYTES` 明确了 MTU probe 的最小 datagram 大小。`encodeProbe(...)` 对更小 target 直接抛异常，`Udp2rawMtuState.nextProbe(...)` 会提前跳过并记录 `probe-too-small`。

这避免了之前 target MTU 小于 header 时实际 probe 包大于目标的问题。

3. response ACK peer 绑定已补

server-side `Udp2rawTunnelContext` 新增 `pendingMtuProbePeer`，`acceptMtuAck(...)` 会检查 ACK sender 是否等于 pending peer。

非 probed peer 的 ACK 不会更新 server response MTU state，测试也覆盖了这一点。

4. bad ACK fuse 已补

`Udp2rawServerEntryHandler.handleMtuAck(...)` 已接入：

- `isPeerBlocked(...)`
- `allowPeerPacket(...)`
- `recordAuthFailure(...)`

这与 DATA / MTU_PROBE 的安全策略更一致。

5. 测试覆盖明显增强

新增测试已经覆盖：

- 低于 1200 继续下探。
- tiny MTU 不发送 oversized probe。
- 非 probed peer ACK 不更新 state。
- 正确 peer ACK 能更新 server response MTU。
- bad MTU_ACK auth 会触发 peer block fuse。

## 剩余建议

### 1. pending peer 建议在 write ACCEPTED 后再标记，或 local drop 时清理

当前 `Udp2rawTunnelContext.sendMtuProbe(...)` 中：

```java
encoded = encodeProbe(...);
markPendingMtuProbePeer(peer);
Sockets.writeUdp(...);
```

也就是 pending peer 在 `Sockets.writeUdp` 之前设置。

如果本地写入失败，例如 channel not writable、write thrown、local drop，state 里仍保留 pending seq，`pendingMtuProbePeer` 也会保留到 timeout 或下一次 peer 变化。

这通常不会造成主路径错误，因为未发送的 probe 不应收到真实 ACK，且 `mtuState.ack(...)` 还要匹配 pending seq。但更严谨的做法是：

- 只有 `result == ACCEPTED` 后才 `markPendingMtuProbePeer(peer)`；或
- 如果 result 非 ACCEPTED，则清理 `pendingMtuProbePeer`，并考虑让 `Udp2rawMtuState` 取消 pending probe。

当前风险等级：低。

### 2. tiny MTU 低于 control header 时，只能靠 data/write drop，无法协议探测恢复

当 `currentMtu < MIN_PROBE_DATAGRAM_BYTES` 时，`nextProbe(...)` 会跳过 probe。这样可以避免发送 oversized probe，但也意味着该状态下协议级 probe/ack 不能主动恢复上探。

实际默认 min 是 576，正常不会进入这个状态。只有显式构造极小 min/max 或异常配置才会出现。

建议：

- 保持当前行为也可以。
- 或在构造器里把 adaptive min 限制为至少 `MIN_PROBE_DATAGRAM_BYTES`，避免状态进入“无法 probe”的区间。

当前风险等级：低。

### 3. 测试里使用固定 `Thread.sleep(...)`，可以逐步替换为 wait loop

新增测试中有 `Thread.sleep(100L/200L)`。这在大多数 CI 环境下可用，但偶发慢机器上可能产生不稳定。

建议后续把关键等待改成类似 `waitForMtu(...)` 的轮询，或者等待明确的 metrics/state 变化。

当前风险等级：低。

### 4. package-private test accessor 可接受，但建议避免继续扩散

`SocksProxyServer.udp2rawTunnelContext(...)` 和 `Udp2rawServerEntryManager.context(String)` 是 package-private，用于测试读取内部 state。当前没有扩大 public API，风险可接受。

建议后续不要再继续扩散生产类测试 accessor；如果需要更多内部状态断言，可以考虑专门的 package-private test support helper。

当前风险等级：低。

# 修改文件列表

本 review 阶段未修改业务代码，仅新增文档：

- `docs/plan/Udp2rawAdaptiveMtuBelow1200Review-plan.md`

如果后续要进一步修复低风险建议，预计可能涉及：

- `rxlib/src/main/java/org/rx/net/socks/Udp2rawTunnelContext.java`
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawMtuState.java`
- `rxlib/src/test/java/org/rx/net/socks/Udp2rawFixedEntryIntegrationTest.java`

# 风险点

1. pending peer 标记时机风险
   - local write drop 后仍保留 pending peer，理论上会让未真正发出的 probe 处于等待 ACK 状态，直到 timeout。

2. tiny MTU 无法主动恢复风险
   - current MTU 小于 probe header 最小尺寸时，协议级 probe 会停发，只能靠其他路径恢复或保持低值。

3. 测试稳定性风险
   - 固定 sleep 对 CI 慢环境不如条件轮询稳定。

4. 测试 accessor 扩散风险
   - package-private accessor 当前可接受，但不宜继续扩大。

# 验证方案

本阶段仅提交 review 计划文档，不触发 CI。

如果后续进入修复阶段，建议执行：

```bash
mvn -pl rxlib -am "-Dmaven.test.skip=true" compile
mvn -pl rxlib -am "-DskipTests" test-compile
mvn -pl rxlib -am "-Dtest=Udp2rawMtuStateTest,Udp2rawFixedEntryIntegrationTest,UdpRedundantTest" "-Dmaven.test.skip=false" test
```

GitHub Actions：

- 触发 `jdk8-unit-tests.yml`。
- `test_classes` 建议包含：
  - `Udp2rawMtuStateTest`
  - `Udp2rawFixedEntryIntegrationTest`
  - `UdpRedundantTest`

CI 判断：

- 只有 workflow run `conclusion=success` 才认为 CI 通过。
- 如果失败，先分类为编译失败、单测失败、JDK8 兼容失败、格式失败或环境问题，再按失败日志做最小修复。
