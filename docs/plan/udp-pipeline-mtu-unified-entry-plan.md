# UDP pipeline MTU / redundant 方向控制统一计划

# 合并说明

本文档合并以下计划与 review 文档，作为后续唯一主文档：

- `docs/plan/udp-pipeline-mtu-unified-entry-plan.md`
- `docs/plan/udp-pipeline-mtu-implementation-review.md`
- `docs/plan/udp-pipeline-mtu-implementation-rereview.md`
- `docs/plan/udp-pipeline-mtu-kcptun-ignore-note.md`
- `docs/plan/udp2raw-redundant-direction-plan.md`

本轮 review 范围：`org.rx.net` package 下 UDP pipeline、SOCKS UDP、Shadowsocks UDP、udp2raw 相关类；排除 FEC 与 `httptunnel`。

# 模式结论

本任务采用 **高性能模式（Netty 底层网络编程）**。

约束：

- 严格 Java 8。
- UDP 热点路径避免无效对象分配、阻塞、反射、正则。
- `ByteBuf` 引用计数必须成对释放。
- 关注 final MTU、背压、连接生命周期、线程模型、协议兼容性。

# 当前目标

1. UDP 出站统一走 `Sockets.writeUdp(...)`。
2. UDP compression / redundant 通过 `Sockets.addUdpOptimizationHandlers(...)` 统一安装。
3. `udpMtu` 约束必须发生在 compression / redundant 之后、transport 之前。
4. udp2raw 和 SOCKS UDP 都支持 redundant request-only / response-only / bidirectional。
5. 默认兼容旧行为：未显式配置方向时按双向处理。

# 已实现结论

## UDP final MTU guard

当前实现已新增 `Sockets.UdpFinalEgressGuardHandler`：

- 安装名：`Sockets.UDP_FINAL_EGRESS_GUARD`。
- `udpBootstrap(...)` 会为 `SocketConfig` / `SocksConfig` 自动安装 final guard。
- `addUdpOptimizationHandlers(...)` 会先安装 final guard，再安装 compression / redundant handler。
- Netty outbound 实际顺序为：

```text
业务写出 -> UdpCompressEncoder -> UdpRedundantEncoder -> UdpFinalEgressGuardHandler -> transport
```

因此 final guard 能看到压缩、多倍发送后产生的真实 `DatagramPacket`，逐个检查：

```java
packet.content().readableBytes() <= udpMtu
```

## final guard promise 语义

final egress drop 当前设计为：

- release packet。
- 记录 metrics。
- 对非 void promise 执行 success。

原因：UDP 高频链路中，调用方多数只用 completion listener 清理 pending 状态，不应因 final drop 进入异常路径。最终是否 drop 通过 metrics 观察。

已在 `Sockets.UdpFinalEgressGuardHandler` 注释中记录该语义。

## public 安装顺序约束

`Sockets.addUdpOptimizationHandlers(...)` 是 public API，已补充注释：

- 必须在业务/protocol outbound handler 之前安装。
- 最终顺序应保持：

```text
业务写出 -> 压缩 -> 多倍发送 -> final egress guard -> transport
```

通过 `udpBootstrap(...)` 创建 UDP channel 时会自动满足该顺序。

# 本轮新增进度

## 1. 公共方向抽象

新增唯一公共方向枚举：

- `UdpRedundantMode`
  - `REQUEST_ONLY`
  - `RESPONSE_ONLY`
  - `BIDIRECTIONAL`

新增公共 helper：

- `UdpRedundantSupport`
  - 判断 redundant 是否已配置。
  - 判断 SOCKS UDP request / response 是否允许 RDNT。
  - 判断 udp2raw request / response 是否允许 RDNT。
  - 为 udp2raw payload 写出按方向过滤 `UdpRedundantConfig`。

## 2. SocksConfig 新增配置

新增：

```java
private UdpRedundantMode socksUdpRedundantMode = UdpRedundantMode.REQUEST_ONLY;
private UdpRedundantMode udp2rawRedundantMode = UdpRedundantMode.BIDIRECTIONAL;
```

实际默认：

- `socksUdpRedundantMode = REQUEST_ONLY`：保护普通 SOCKS5 UDP client，默认不向 client 响应方向加 RDNT 头。
- `udp2rawRedundantMode = BIDIRECTIONAL`：保持 udp2raw tunnel payload redundant 旧语义。

setter 均为 null-safe：

- `setSocksUdpRedundantMode(null)` 恢复 `REQUEST_ONLY`。
- `setUdp2rawRedundantMode(null)` 恢复 `BIDIRECTIONAL`。

`udpRedundantTrackClientPeer` 已移除，由 `socksUdpRedundantMode` 明确表达：

- `REQUEST_ONLY`：只登记上游 request peer。
- `RESPONSE_ONLY`：只登记 client response peer。
- `BIDIRECTIONAL`：同时登记 request/response peer。

## 3. SOCKS UDP 接入

SOCKS UDP 方向语义：

- request：client -> upstream relay。
- response：upstream relay -> client。

接入点：

- `Socks5CommandRequestHandler`
  - `UDP_ASSOCIATE` 创建 relay channel 时，按 socks/udp2raw 类型计算是否登记 client peer。
- `UdpRelayAttributes`
  - `shouldTrackClientAsRedundantPeer(config, udp2raw)` 按 response 方向判断。
- `SocksUdpUpstream`
  - 仅在 redundant 已配置且 request 方向允许时登记上游 relay peer。
- `RssServer`
  - 原 `udpRedundantTrackClientPeer=true` 迁移为 `socksUdpRedundantMode=BIDIRECTIONAL`。

优化：

- 未启用 redundant 时不再无效初始化 peer map。

## 4. udp2raw 接入

udp2raw 方向语义：

- request：udp2raw client -> udp2raw server。
- response：udp2raw server -> udp2raw client。

接入点：

- `Udp2rawHandler`
  - 直接 UDP2RAW relay 模式下，只有 request 方向允许且 redundant 已配置时才登记 tunnel peer。
- `Udp2rawOpenRequest`
  - 新增 `redundantMode`，client open tunnel 时携带方向配置。
- `Udp2rawServerEntryManager`
  - server tunnel context 记录 negotiated mode；旧 client 未传时使用 server config 默认值。
- `Udp2rawTunnelContext`
  - 保存 `redundantMode`。
- `Udp2rawSession`
  - response 写回 peer 时按 mode 过滤 redundant config。
- `Udp2rawUpstream`
  - request payload 写出时按 mode 过滤 redundant config。
  - `FLAG_REDUNDANT` 只在当前方向实际允许 redundant 时设置。

# 已知决策

## kcptunClient

`SocksConfig.kcptunClient` 删除仍按用户要求暂不恢复。

状态：

- 已知 API 兼容风险。
- 本轮不阻塞 UDP MTU / redundant 方向控制实现。
- 后续如发现外部配置依赖该 getter/setter，再单独恢复或提供迁移说明。

# 风险复核

1. **ByteBuf 生命周期**
   - drop 路径必须 release。
   - 成功提交给 Netty 后不提前 release。
   - udp2raw payload redundant 通过 retained duplicate 写副本，finally release 原始 encoded。

2. **背压**
   - `Sockets.writeUdp(...)` 处理提交前 inactive / unresolved / pending / notWritable。
   - final guard 在真实 datagram 出口处理 MTU 与最终 pending。
   - redundant 延迟副本仍经过 final guard。

3. **连接生命周期**
   - SOCKS UDP relay 继续依赖 TCP control close 关闭 UDP relay。
   - udp2raw tunnel/session idle、peer guard、auth failure fuse 逻辑未改变。

4. **线程模型**
   - route 初始化和 tunnel 初始化完成后回到对应 EventLoop 写出。
   - 新增方向判断为纯 enum/switch/field 读取，不引入阻塞和锁。

5. **协议兼容性**
   - 默认 `BIDIRECTIONAL`，旧配置行为不变。
   - `Udp2rawOpenRequest.redundantMode` 为新增可选字段，旧 client 未传时 server 使用默认双向。
   - 当前未修改 udp2raw 帧格式。

6. **热点路径分配**
   - peer map 登记前增加 `isConfigured` 判断，避免 redundant 未启用时无效创建 peer map。
   - 方向判断不引入集合、lambda、反射或正则。

# 核心监控指标建议

必须关注：

- 堆外内存占用：Netty pooled direct memory / allocator metrics。
- UDP active channel 数。
- UDP pending write bytes。
- `socks.udp.drop.count`
- `socks.udp.mtu.drop.count`
- `socks.udp.mtu.drop.bytes`
- `socks.udp2raw.drop.count`
- `socks.udp2raw.redundant.copy.count`
- `socks.udp2raw.redundant.duplicate.drop.count`
- `udp.redundant.decoder.drop.count`
- UDP 吞吐、P95/P99 写出延迟、retransmit/redundant multiplier。

# 验证结论

已执行并通过：

```bash
mvn -pl rxlib -am "-Dmaven.test.skip=true" compile
mvn -pl rxlib -am "-DskipTests" test-compile
mvn -pl rxlib -am "-Dtest=UdpRedundantTest" "-Dmaven.test.skip=false" test
mvn -pl rxlib -am "-Dtest=UdpPipelineMtuGuardTest" "-Dmaven.test.skip=false" test
```

结果：

- 主代码 Java 8 编译通过。
- 测试代码编译通过。
- `UdpRedundantTest`：43 tests，0 failures，0 errors。
- `UdpPipelineMtuGuardTest`：4 tests，0 failures，0 errors。

未执行：

- 全量单元测试。
- 网络集成测试。
- GitHub Actions `jdk8-unit-tests.yml`。

# 后续建议

合入前建议至少补跑：

```bash
mvn -pl rxlib -am "-Dtest=SocketsTest,UdpRedundantTest,UdpPipelineMtuGuardTest" "-Dmaven.test.skip=false" test
```

如时间允许，补跑相关集成：

```bash
mvn -pl rxlib -am "-Dtest=SocksProxyServerIntegrationTest,ShadowsocksServerIntegrationTest,Udp2rawFixedEntryIntegrationTest" "-Dmaven.test.skip=false" test
```

CI 要求：

- 触发 `jdk8-unit-tests.yml`。
- 只有 workflow run `conclusion=success` 才能认为 CI 通过。
