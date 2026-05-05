# udp2raw 单向/双向多倍发送配置实现计划

# 背景

用户要求在当前 UDP pipeline MTU / backpressure / compression / redundant 工作基础上，为 udp2raw 增加一个配置，用于控制 udp2raw 多倍发送是单向还是双向，并将实现计划加入 `docs/plan/*` 计划文档。

当前分支：

- `agent/udp-pipeline-mtu-plan`
- 当前 HEAD：`f0c5266d94462549a6bbd252a10356b68b2da194`

当前已存在的相关能力：

- 普通 UDP redundant 配置：`UdpRedundantConfig`
- 普通 UDP pipeline redundant encoder：`UdpRedundantEncoder`
- udp2raw payload 层 redundant 支持：`Udp2rawPayloadSupport.writeEncoded(...)`
- udp2raw tunnel context 中保存：`Udp2rawTunnelContext.redundantConfig`、`redundantResolver`、`redundantStats`
- udp2raw 主要处理类：`Udp2rawHandler`、`Udp2rawSession`

本轮仅提交计划，不修改业务代码。

# 任务类型判断

本次任务归类为 **新需求类任务**。

原因：

- 用户明确要求“增加一个配置”。
- 该需求会新增配置项与方向控制语义。
- 需要影响 udp2raw request / response 两个方向的 redundant 写出策略。
- 按仓库 agent 规则，新需求必须先生成并提交计划文档，等待用户明确要求后再进入代码实现阶段。

# 当前上下文

## 已 review 的文件

本次计划前已读取/复核：

- `rxlib/src/main/java/org/rx/net/socks/SocksConfig.java`
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawHandler.java`
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawSession.java`
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawPayloadSupport.java`
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawTunnelContext.java`
- `rxlib/src/main/java/org/rx/net/socks/UdpRedundantConfig.java`
- `rxlib/src/main/java/org/rx/net/socks/UdpRelayAttributes.java`

## 关键调用链

### udp2raw client mode request

本地 SOCKS5 UDP app 发出 packet：

1. `Udp2rawHandler.handleClientModePacket(...)`
2. decode SOCKS5 UDP header，得到 local client 与目标 destination。
3. wrap 成 udp2raw stream frame。
4. `Sockets.writeUdp(...)` 写到 udp2raw peer / udp2raw server。
5. 该方向可以定义为：`REQUEST` / `CLIENT_TO_SERVER`。

### udp2raw client mode response

udp2raw peer / server 返回 packet：

1. `Udp2rawHandler.handleClientModeResponse(...)`
2. unwrap udp2raw frame。
3. 写回本地 SOCKS5 UDP app。
4. 该方向本质上是从 udp2raw server 回到 client，但写回本地 app 不一定应再做 udp2raw redundant；真正需要控制的是 tunnel peer 上的 encoded response。

### udp2raw server mode request

udp2raw client 发来 request：

1. `Udp2rawHandler.handleServerModePacket(...)`
2. unwrap udp2raw frame。
3. route 到真实 destination。
4. 写到真实 destination 不是 udp2raw tunnel peer 写出，不建议受“udp2raw 多倍发送方向”配置控制。

### udp2raw server mode response

真实 destination 返回 response：

1. `Udp2rawSession.writeToPeer(...)`
2. wrap 成 udp2raw frame。
3. `Udp2rawPayloadSupport.writeEncoded(...)` 写回 udp2raw client peer。
4. 该方向可以定义为：`RESPONSE` / `SERVER_TO_CLIENT`。

## 当前实现意图

`Udp2rawPayloadSupport.writeEncoded(...)` 当前根据 `UdpRedundantConfig` 判断是否多倍发送：

- `multiplier <= 1`：只发一次。
- `multiplier > 1` 或 adaptive/rules 命中：写首包，并复制/延迟发送副本。

当前没有一个明确配置来限制 redundant 只作用在 request 或 response。也就是说，只要某条 udp2raw encoded 写出路径传入了 enabled redundant config，就会按同一套 config 多倍发送。

# 目标

1. 新增 udp2raw redundant 方向配置，支持：
   - 单向 request 多倍发送。
   - 单向 response 多倍发送。
   - 双向多倍发送。
2. 默认保持当前兼容行为：如果已有 udp2raw redundant 配置启用，默认仍按双向处理。
3. 不影响普通 UDP redundant 配置语义。
4. 不影响 UDP compression 配置语义。
5. 保持 final egress MTU guard 语义：不论单向还是双向，最终真实 datagram 仍必须经过 MTU guard。
6. 保持 JDK8 兼容，不使用 JDK9+ API。

# 非目标

1. 本轮不直接修改业务代码。
2. 不重构 udp2raw 协议帧格式。
3. 不修改 udp2raw authentication、session id、seq window、peer guard 逻辑。
4. 不改变普通 SOCKS UDP redundant 行为。
5. 不改变 compression 是否双向的现有行为。
6. 不恢复 `SocksConfig.kcptunClient`，该项已按用户指示暂不处理。
7. 不触发 CI 或声称 CI 已通过。

# 设计方案

## 方案 1：新增方向枚举

建议新增枚举文件：

- `rxlib/src/main/java/org/rx/net/socks/Udp2rawRedundantMode.java`

枚举建议：

```java
public enum Udp2rawRedundantMode {
    /** Request frames only: local/client side -> udp2raw server side. */
    REQUEST_ONLY,

    /** Response frames only: server side -> udp2raw client side. */
    RESPONSE_ONLY,

    /** Request and response frames. Default for backward compatibility. */
    BIDIRECTIONAL
}
```

命名选择说明：

- 使用 `REQUEST_ONLY` / `RESPONSE_ONLY`，比 `UPLINK` / `DOWNLINK` 更贴近当前代码中的 `flow=client-request`、`flow=response`。
- 如果更希望表达隧道方向，也可以命名为 `CLIENT_TO_SERVER` / `SERVER_TO_CLIENT` / `BIDIRECTIONAL`。实现阶段建议二选一，不同时保留两个枚举，避免配置歧义。
- 推荐默认值为 `BIDIRECTIONAL`，以兼容当前行为。

## 方案 2：在 SocksConfig 增加配置字段

建议在 `SocksConfig` 增加：

```java
/**
 * Controls which udp2raw tunnel direction uses redundant multi-send.
 * This only affects udp2raw encoded frames and does not change normal SOCKS UDP redundant behavior.
 */
private Udp2rawRedundantMode udp2rawRedundantMode = Udp2rawRedundantMode.BIDIRECTIONAL;
```

setter 建议：

```java
public void setUdp2rawRedundantMode(Udp2rawRedundantMode mode) {
    this.udp2rawRedundantMode = mode != null ? mode : Udp2rawRedundantMode.BIDIRECTIONAL;
}
```

兼容性：

- 旧配置没有该字段时，默认 `BIDIRECTIONAL`。
- 如果 `udpRedundant.multiplier <= 1` 且 adaptive/rules 未启用，则该 mode 不会强行启用 redundant。
- 该字段只控制 udp2raw payload 层 redundant，不改变普通 UDP pipeline redundant。

## 方案 3：增加方向判断 helper

建议新增一个小 helper，避免方向判断散落在 handler 中。

可选位置：

- `Udp2rawPayloadSupport`
- 或新增 `Udp2rawRedundantSupport`

建议 API：

```java
static boolean allowRedundant(SocksConfig config, Udp2rawFlow flow);
```

同时新增内部 enum：

```java
enum Udp2rawFlow {
    REQUEST,
    RESPONSE
}
```

判断逻辑：

```java
Udp2rawRedundantMode mode = config != null && config.getUdp2rawRedundantMode() != null
        ? config.getUdp2rawRedundantMode()
        : Udp2rawRedundantMode.BIDIRECTIONAL;

switch (mode) {
    case REQUEST_ONLY:
        return flow == Udp2rawFlow.REQUEST;
    case RESPONSE_ONLY:
        return flow == Udp2rawFlow.RESPONSE;
    case BIDIRECTIONAL:
    default:
        return true;
}
```

该 helper 只判断方向，不判断 multiplier/adaptive/rules 是否启用。是否真正 redundant 仍交给 `Udp2rawPayloadSupport.isRedundantEnabled(...)` 和 `Udp2rawPayloadSupport.effectiveMultiplier(...)`。

## 方案 4：改造 udp2raw encoded 写出入口

当前 `Udp2rawPayloadSupport.writeEncoded(...)` 接收：

- `Channel channel`
- `ByteBuf encoded`
- `InetSocketAddress recipient`
- `UdpRedundantConfig redundant`
- `UdpRedundantStats stats`
- `UdpRedundantMultiplierResolver resolver`
- `String flowTag`

建议增加一个参数，表达当前写出方向是否允许 redundant：

```java
writeEncoded(...,
             Udp2rawFlow flow,
             boolean allowRedundant,
             String flowTag)
```

或更小改动：在调用前决定传入的 config：

```java
UdpRedundantConfig redundant = Udp2rawPayloadSupport.allowRedundant(config, flow)
        ? context.redundantConfig
        : null;
```

推荐小改动方案：**调用前过滤 config**。

优点：

- `writeEncoded(...)` 内部逻辑改动最小。
- 不破坏已有 effective multiplier、adaptive stats、destination rule resolver 逻辑。
- 当方向不允许 redundant 时，传入 `null`，`writeEncoded(...)` 自然只发送一次，并打上 `redundant=false` tag。

## 方案 5：映射具体 flow

建议定义以下映射：

| 代码路径 | flow | mode=REQUEST_ONLY | mode=RESPONSE_ONLY | mode=BIDIRECTIONAL |
| --- | --- | --- | --- | --- |
| `Udp2rawHandler.writeClientModePacket(...)` 写到 udp2raw peer | REQUEST | 多倍 | 单发 | 多倍 |
| `Udp2rawSession.writeToPeer(...)` 写回 udp2raw client peer | RESPONSE | 单发 | 多倍 | 多倍 |
| server mode unwrap 后写真实 destination | 非 udp2raw encoded peer write | 不受影响 | 不受影响 | 不受影响 |
| client mode unwrap 后写本地 SOCKS5 app | 非 udp2raw encoded peer write | 不受影响 | 不受影响 | 不受影响 |

注意：如果当前 client request 路径通过普通 UDP pipeline redundant 和 udp2raw payload redundant 同时生效，需要实现阶段确认是否存在“双重多倍发送”。方向配置应优先作用于 udp2raw payload 层，普通 UDP pipeline redundant 是否参与需要单独确认并避免重复叠加。

## 方案 6：避免和普通 UDP redundant 混淆

当前 `SocksConfig` 已有 `udpRedundant`，普通 UDP pipeline 与 udp2raw payload 层都可能复用该配置。

为了避免语义混淆，文档和注释必须明确：

- `udpRedundant`：描述 multiplier、interval、adaptive、destination rules 等参数。
- `udp2rawRedundantMode`：只描述 udp2raw tunnel encoded frame 哪个方向应用这些参数。
- 如果 mode 不允许当前方向，则该方向发送一次，不做多倍发送；compression 仍按原配置执行。

如果实现阶段发现普通 UDP pipeline redundant 会和 udp2raw payload redundant 叠加，需要优先做以下处理之一：

1. udp2raw tunnel encoded frame 使用 payload 层 redundant，避免再注册普通 pipeline redundant peer。
2. 或保留 pipeline redundant，但 payload 层 redundant 禁用，二者只保留一个入口。

建议优先保持当前结构，仅在新增测试中验证不会出现非预期乘法放大。

## 方案 7：metrics 与日志

建议在现有 `flowTag` 中增加方向信息：

- `flow=request`
- `flow=response`
- `udp2rawRedundantMode=request-only|response-only|bidirectional`

如果担心 tag 基数，mode tag 可以只在 debug log 中体现。方向 tag 本身是低基数，可以保留。

建议新增或复用 metrics：

- `socks.udp2raw.redundant.copy.count`
- `socks.udp2raw.redundant.direction.skip.count`

其中 skip metric 可选。如果为了最小改动，也可以不新增 skip metric，只通过 `redundant=false` tag 观察。

# 修改文件列表

预计实现阶段会修改/新增：

## 必选

- `rxlib/src/main/java/org/rx/net/socks/Udp2rawRedundantMode.java`
  - 新增 request-only / response-only / bidirectional 枚举。

- `rxlib/src/main/java/org/rx/net/socks/SocksConfig.java`
  - 新增 `udp2rawRedundantMode` 字段，默认 `BIDIRECTIONAL`。
  - 增加 null-safe setter。

- `rxlib/src/main/java/org/rx/net/socks/Udp2rawPayloadSupport.java`
  - 增加方向判断 helper，或让调用方传入过滤后的 `UdpRedundantConfig`。
  - 保持 `writeEncoded(...)` 对 null redundant config 的单发行为。

- `rxlib/src/main/java/org/rx/net/socks/Udp2rawHandler.java`
  - client request 写到 udp2raw peer 时按 `REQUEST` 判断是否启用 redundant。
  - 如该路径当前依赖 pipeline-level redundant peer，需要确认并按 mode 控制 peer registration 或 payload redundant config。

- `rxlib/src/main/java/org/rx/net/socks/Udp2rawSession.java`
  - response 写回 udp2raw peer 时按 `RESPONSE` 判断是否启用 redundant。

- `rxlib/src/main/java/org/rx/net/socks/Udp2rawTunnelContext.java`
  - 如需要缓存 mode 或 helper，可增加只读字段；也可直接从 `SocksConfig` 读取以减少状态复制。

## 测试

- 新增或扩展 udp2raw redundant 方向测试类，例如：
  - `rxlib/src/test/java/org/rx/net/socks/Udp2rawRedundantModeTest.java`

建议覆盖：

1. `BIDIRECTIONAL`：request 与 response 都按 multiplier 多倍发送。
2. `REQUEST_ONLY`：request 多倍，response 单发。
3. `RESPONSE_ONLY`：request 单发，response 多倍。
4. `multiplier=1`：无论 mode 如何，都单发。
5. `udpMtu=1300`：单向/双向多倍发送产生的每个真实 datagram 仍经过 final MTU guard。
6. adaptive mode：只在允许方向上更新/使用 adaptive stats。

# 风险点

1. **方向语义风险**
   - “单向”需要明确是 request-only 还是 response-only。计划建议通过 enum 显式表达，避免只用 boolean。

2. **兼容性风险**
   - 默认值必须为 `BIDIRECTIONAL`，否则可能改变当前已启用 udp2raw redundant 的行为。

3. **双重多倍发送风险**
   - 如果 udp2raw encoded frame 同时经过普通 pipeline redundant 和 payload-level redundant，可能出现 multiplier 相乘。实现阶段必须确认并用测试覆盖。

4. **MTU 风险**
   - 单向/双向 redundant 切换后，仍必须保证 final egress guard 对每个真实 datagram 生效。

5. **adaptive stats 风险**
   - 如果只在单向启用 redundant，adaptive loss stats 不应因为另一个方向的包而误调整 multiplier。

6. **配置命名风险**
   - `udp2rawRedundantMode` 与 `udpRedundant` 需要在注释中明确职责边界。

7. **JDK8 风险**
   - 新增 enum/helper/test 不得使用 `List.of`、`Map.of` 等 JDK9+ API。

# 验证方案

## 本地编译

```bash
mvn -pl rxlib -am test -DskipTests
```

## 单元测试

```bash
mvn -pl rxlib -am test -Dtest=Udp2rawRedundantModeTest,UdpPipelineMtuGuardTest,SocketsTest
```

如测试落在现有集成类中，可替换为：

```bash
mvn -pl rxlib -am test -Dtest=SocksProxyServerIntegrationTest
```

## GitHub Actions

实现 commit 后必须触发或依赖：

- `jdk8-unit-tests.yml`

建议 `test_classes`：

- `org.rx.net.socks.Udp2rawRedundantModeTest`
- `org.rx.net.socks.UdpPipelineMtuGuardTest`
- `org.rx.net.SocketsTest`

验证要求：

- 查询 workflow run 时按实现分支过滤。
- 只有 `conclusion=success` 才能认为 CI 通过。
- 如果 CI 失败，先分类为编译失败、单测失败、format/checkstyle、依赖下载、JDK 版本或环境问题，再做最小范围修复。

# 分阶段执行建议

## 阶段 1：配置与 helper

1. 新增 `Udp2rawRedundantMode` enum。
2. 在 `SocksConfig` 增加 `udp2rawRedundantMode = BIDIRECTIONAL`。
3. 增加 null-safe setter。
4. 增加方向判断 helper。

## 阶段 2：接入 udp2raw 写出路径

1. 在 request 写出路径上应用 `REQUEST` 判断。
2. 在 response 写出路径上应用 `RESPONSE` 判断。
3. 保持 compression 不受影响。
4. 确认 final MTU guard 不被绕过。

## 阶段 3：测试与验证

1. 新增 request-only / response-only / bidirectional 测试。
2. 覆盖 multiplier=1 与 MTU guard 边界。
3. 触发 `jdk8-unit-tests.yml`。
4. 根据 CI 结果继续最小范围修复。
