# UdpClient Codec 重构计划

## 本次模式

**高性能模式（Netty 底层网络编程）**

## 1. 背景与目标

当前 [`UdpClient`](../../rxlib/src/main/java/org/rx/net/transport/UdpClient.java) 同时承担三类职责：

- UDP 可靠传输语义：`ACK`、`SEMI/FULL` 同步、超时、重发、请求响应。
- UDP 分片与重组：按 `maxFragmentPayloadBytes` 拆成多个 `DatagramPacket`，接收端按 `messageId + sender` 聚合。
- 对象序列化：发送侧固定使用 `Serializer.DEFAULT.serializeToBytes(...)`，接收侧固定使用 `Serializer.DEFAULT.deserializeFromBytes(...)`。

本次重构目标：

1. 将对象编解码能力从 `UdpClient` 中抽出，由使用方通过配置设置。
2. `UdpClient` 默认 codec 切换为 **Apache Fury**。
3. 保留 `UdpClient` 自身的可靠 UDP、ACK、重发、分片与重组职责。
4. 分片与重组改为围绕 codec 产出的二进制 payload 工作，避免 transport 层绑定 JDK serializer。
5. 控制热点路径分配与复制，重点审计 `ByteBuf` 引用计数、重发场景和组包释放。

## 2. 范围与非目标

### 本期范围

- 新增 UDP payload codec 抽象。
- 新增 `UdpClientConfig`，由使用方显式配置 codec。
- `UdpClient(int bindPort)` 保留，默认使用 Fury codec。
- 用 Fury 替换当前默认 `Serializer.DEFAULT`。
- 重构 `SendContext` / `ReceiveAssembly` 的 payload 管理，覆盖拆包、装包、重发和过期释放。
- 补充单元测试与 UDP 打洞集成回归。

### 本期不做

- 不引入 UDP 端到端 codec 协商。
- 不改变 `ACK` 包格式。
- 不把 `UdpClient` 接入 TCP 的 `LengthFieldBasedFrameDecoder` / `LengthFieldPrepender`，UDP 仍按数据报边界处理。
- 不把 SOCKS5 UDP relay 的 `UdpCompress` / `UdpRedundant` 逻辑合并进 `UdpClient`。
- 不在 I/O 线程执行阻塞逻辑或重型压缩。

## 3. 当前协议现状

`UdpClient` 当前 wire header：

```text
----------------------+----------------------+
| MAGIC (4B)          | TYPE (1B)            |
+----------------------+----------------------+

ACK:
+----------------------+----------------------+
| MESSAGE_ID (4B)     |
+----------------------+

DATA:
+----------------------+----------------------+
| MESSAGE_ID (4B)     | ACK_SYNC (1B)        |
+----------------------+----------------------+
| ALIVE_MS (4B)       | FRAGMENT_INDEX (2B)  |
+----------------------+----------------------+
| FRAGMENT_COUNT (2B) | fragment payload     |
+----------------------+----------------------+
```

现有发送链路：

```text
Object -> Serializer.DEFAULT -> byte[] -> split -> DATA datagram(s)
```

现有接收链路：

```text
DATA datagram(s) -> byte[][] -> merge byte[] -> Serializer.DEFAULT -> Object
```

主要问题：

- transport 层被 JDK serializer 语义绑定，调用方无法替换 codec。
- 发送侧 `byte[]` 再写入 `ByteBuf`，接收侧 `byte[][]` 再合并成 `byte[]`，大包跨分片时有额外复制。
- `ReceiveAssembly` 过期释放只处理数组引用，后续切换 direct buffer 时必须明确引用计数。
- 默认 JDK object codec 不适合作为高性能默认实现。

## 4. 对外 API 设计

### 4.1 新增配置对象

建议新增：

```java
package org.rx.net.transport;

public class UdpClientConfig implements Serializable {
    private UdpClientCodec codec = FuryUdpClientCodec.createDefault();
    private int waitAckTimeoutMillis = 15 * 1000;
    private boolean fullSync;
    private int maxResend = 2;
    private int maxFragmentPayloadBytes = 1024;
    private int maxFragmentCount = 128;
}
```

构造入口：

```java
public UdpClient(int bindPort)
public UdpClient(int bindPort, UdpClientConfig config)
public UdpClient(int bindPort, UdpClientCodec codec)
```

原则：

- `codec` 只建议在构造时设置，不建议运行期热切换，避免同一 `UdpClient` 的待重组消息跨 codec 解码。
- 保留现有 setter 兼容 `waitAckTimeoutMillis`、`fullSync`、`maxResend`、`maxFragmentPayloadBytes`、`maxFragmentCount`。
- 如需运行期切换 codec，必须先关闭旧实例并创建新实例。

### 4.2 新增 codec 抽象

建议接口：

```java
package org.rx.net.transport;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

public interface UdpClientCodec extends Serializable {
    ByteBuf encode(ByteBufAllocator allocator, Object packet) throws Exception;

    Object decode(ByteBuf payload) throws Exception;
}
```

引用计数约定：

- `encode(...)` 返回 `refCnt = 1` 的 `ByteBuf`，所有权交给 `UdpClient`。
- `decode(...)` 不释放入参 `payload`，由 `UdpClient` 在 decode 完成后统一释放。
- codec 内部如果 `retain()` 或创建中间 `ByteBuf`，必须在异常分支释放。
- codec 不负责 UDP 分片、不负责 ACK、不负责重发。

### 4.3 兼容 codec

建议保留一个显式兼容实现：

```java
JdkSerializerUdpClientCodec
```

用途：

- 滚动升级期间，旧节点和新节点可以临时都指定 JDK serializer codec。
- 历史二进制协议兼容测试可以继续覆盖。

默认行为仍为 Fury，不再默认走 `Serializer.DEFAULT`。

## 5. 默认 Fury Codec 设计

新增：

```text
rxlib/src/main/java/org/rx/net/transport/FuryUdpClientCodec.java
```

建议 Fury payload 内层帧：

```text
+----------------------+----------------------+
| FRAME_MAGIC (2B)     | VERSION (1B)         |
+----------------------+----------------------+
| CODEC_ID (1B)        | PAYLOAD_LENGTH (4B)  |
+----------------------+----------------------+
| Fury payload bytes                          |
+---------------------------------------------+
```

设计要点：

- `FRAME_MAGIC` 可复用 RPC Fury 风格，例如 `0x5258`。
- `VERSION = 1`。
- `CODEC_ID_FURY = 1`。
- `PAYLOAD_LENGTH` 用于重组后校验，防止错误 codec 或残缺 payload 被误反序列化。
- Fury 实例用 `FastThreadLocal<Fury>` 绑定 Netty EventLoop，避免跨线程共享。
- 编码直接写入 Netty `ByteBufOutputStream`。
- 解码优先 `ByteBuf.nioBuffer()`，复合缓冲区再退化为 `ByteBufUtil.getBytes(...)`。
- 默认 allowlist 建议保持：
  - `java.`
  - `javax.`
  - `org.rx.`
- 业务方发送自定义包类型时，通过 `allowPrefix(...)` 或 `allowClass(...)` 显式开放。
- 继续保留 `DateTime` 自定义 serializer，避免退化到 JDK serialization。

示例使用：

```java
UdpClientConfig config = new UdpClientConfig();
config.setCodec(FuryUdpClientCodec.createDefault()
        .allowPrefix("com.mycompany."));
UdpClient client = new UdpClient(0, config);
```

## 6. 拆包与装包方案

### 6.1 出站装包顺序

```text
Object
  -> UdpClientCodec.encode(...)
  -> encoded ByteBuf
  -> 按 maxFragmentPayloadBytes 切 retainedSlice
  -> DATA header + fragment slice
  -> DatagramPacket
  -> Sockets.writeUdp(...)
```

建议重构点：

- `SendContext.payload` 从 `byte[]` 改为 `ByteBuf`。
- `fragmentCount(int payloadLength)` 继续基于 encoded payload 的 readable bytes 计算。
- `encodeData(...)` 不再复制 payload 字节，改为：
  - 分配固定长度 DATA header `ByteBuf`。
  - 对 encoded payload 调 `retainedSlice(offset, length)`。
  - 用 `CompositeByteBuf` 拼接 `header + fragmentSlice`。
  - `DatagramPacket` 接管 composite。
- `payloadLength == 0` 时仍发送一个空 payload DATA 包，保持当前行为。

### 6.2 入站拆包与重组顺序

```text
DatagramPacket
  -> 校验 UDP transport header
  -> fragment payload readRetainedSlice(...)
  -> ReceiveAssembly.add(index, fragment)
  -> 完整后 CompositeByteBuf 按序 addComponents
  -> UdpClientCodec.decode(...)
  -> Object
  -> handleLogicalMessage(...)
```

建议重构点：

- `ReceiveAssembly.fragments` 从 `byte[][]` 改为 `ByteBuf[]`。
- `handlePacket(...)` 读取 DATA header 后，对剩余 payload 使用 `readRetainedSlice(...)`，避免 inbound `DatagramPacket` 自动释放后悬挂引用。
- `ReceiveAssembly` 维护 `totalBytes`，超过 `maxEncodedPayloadBytes = maxFragmentPayloadBytes * maxFragmentCount` 时立即丢弃并释放已收 fragments。
- 完整后用 `CompositeByteBuf` 组包，不再 `merge byte[]`。
- duplicate fragment 需要立刻释放新收到的 fragment。
- assembly 过期、格式不一致替换、client close 时必须释放所有已保存 fragments。

### 6.3 Header 兼容性

本期建议不改变 UDP transport header，原因：

- `ACK` / `DATA` 的可靠传输语义不依赖对象 codec。
- 分片字段已经足够描述 encoded payload 的拆包顺序。
- codec 版本与 payload 长度由 codec 内层帧负责。

兼容性影响：

- 默认 codec 从 JDK serializer 切到 Fury 后，新旧默认实例不再 wire-compatible。
- 滚动升级需要双端同时升级，或双方显式配置 `JdkSerializerUdpClientCodec`。
- 若两端配置不同 codec，Fury 内层 `FRAME_MAGIC / CODEC_ID / PAYLOAD_LENGTH` 会在 decode 阶段失败，并走 `onError`。

## 7. 内存与生命周期规则

### 7.1 发送侧

- `beginSend(...)` 中 codec encode 成功后才创建 `SendContext`。
- `SendContext` 持有 encoded payload 的一个基础引用。
- 每个 fragment 通过 `retainedSlice(...)` 获得独立引用，写出后由 Netty / `DatagramPacket` 生命周期释放。
- `ACK != NONE` 时，`SendContext` 保留 payload 直到：
  - 收到 ACK；
  - ACK timeout；
  - 发送失败；
  - `UdpClient.close()`。
- `ACK == NONE` 时，初次 `writeFragments(...)` 调用完成后即可释放 `SendContext` 持有的基础 payload 引用。
- `Sockets.writeUdp(...)` 返回非 `ACCEPTED` 时会释放传入 `DatagramPacket`，调用方不得二次释放该 packet。

### 7.2 接收侧

- `ReceiveAssembly` 对每个 fragment 持有一个 retained `ByteBuf`。
- 以下分支必须释放 assembly 内所有 fragment：
  - assembly 过期；
  - 同一 key 收到不兼容的 `fragmentCount` / `ack` 后替换旧 assembly；
  - decode 成功或失败；
  - `UdpClient.close()`。
- `CompositeByteBuf` 传入 codec 后由 `UdpClient` 在 finally 中释放，间接释放各 fragment。
- decode 失败不能发送 ACK，避免对端误认为业务已接收。

## 8. 可靠性与背压

需要保持现有策略：

- 写出仍统一通过 `Sockets.writeUdp(...)`，继续使用 UDP pending bytes 软上限。
- `maxFragmentCount` 作为单条逻辑消息的硬上限，codec 输出超过上限直接失败。
- `maxFragmentPayloadBytes` 建议继续默认 `1024`，避免靠近 MTU 引发 IP 层分片。
- `FULL ACK` 仍在业务 handler 成功后发送。
- `SEMI ACK` 仍在完成 codec decode 与逻辑消息入队前后按现有语义处理。

额外建议：

- 记录 `udp.codec.encode.error.count`、`udp.codec.decode.error.count`。
- decode 失败时携带 sender、messageId、fragmentCount、payloadBytes，但不要打印 payload 内容。

## 9. 实施拆分

### 阶段 1：配置与接口

- 新增 `UdpClientCodec`。
- 新增 `UdpClientConfig`。
- `UdpClient` 增加配置构造器。
- `UdpClient(int bindPort)` 默认构造 `UdpClientConfig`，默认 codec 为 Fury。

### 阶段 2：Fury 与兼容 codec

- 新增 `FuryUdpClientCodec`。
- 新增 `JdkSerializerUdpClientCodec`。
- Fury 支持 allowlist、`allowPrefix(...)`、`allowClass(...)`。
- 补 `DateTime` serializer。

### 阶段 3：发送侧 ByteBuf 化

- `SendContext.payload` 改为 `ByteBuf`。
- `writeFragments(...)` 基于 `retainedSlice(...)` 拆包。
- `encodeData(...)` 改为 header + composite 装包。
- 审计 ACK、重发、失败和 close 的 payload 释放。

### 阶段 4：接收侧 ByteBuf 化

- `ReceiveAssembly.fragments` 改为 `ByteBuf[]`。
- `handlePacket(...)` 对 fragment payload 使用 `readRetainedSlice(...)`。
- 完整后 `CompositeByteBuf` 组包并交给 codec decode。
- 补过期、替换、重复 fragment 和 decode 异常释放。

### 阶段 5：调用方与测试

- 现有调用方保持默认 Fury：
  - `NameserverImpl`
  - `UdpHolePunchClient`
  - `UdpHolePunchServer`
- 对需要业务自定义类型的调用方补 `allowPrefix(...)` 示例或配置入口。
- 更新 `UdpTransportTest`。
- 回归 `UdpHolePunchIntegrationTest`。

## 10. 测试与验证计划

### 单元测试

- `FuryUdpClientCodecTest`
  - 普通对象往返。
  - 大 payload 往返。
  - `DateTime` 往返。
  - 非 allowlist 类型 decode 拒绝。
  - 错误 magic / version / codecId / payloadLength 拒绝。
- `UdpTransportTest`
  - 默认 Fury codec 下 `FULL ACK` 首次失败后重发成功。
  - 默认 Fury codec 下跨多 fragment request / response 成功。
  - 自定义 codec 可被 `UdpClientConfig` 设置并完成收发。
  - `JdkSerializerUdpClientCodec` 兼容收发。
  - 超过 `maxFragmentCount` 失败并释放 payload。
  - duplicate fragment、assembly timeout、decode exception 不泄漏。

### 集成测试

- `UdpHolePunchIntegrationTest`：默认 Fury 下两端打洞流程正常。
- Nameserver 副本同步路径：如已有稳定测试，补充默认 Fury 回归；否则至少补一个轻量 replica sync 测试。

### 建议执行命令

```bash
mvn -pl rxlib -DskipTests compile
mvn -pl rxlib -DskipTests test-compile
mvn -pl rxlib "-Dtest=FuryUdpClientCodecTest,UdpTransportTest,UdpHolePunchIntegrationTest" test
```

如涉及 Nameserver 集成测试，再补充对应 `-Dtest=...`。

## 11. 风险评估

### 协议兼容风险

- 默认 codec 改为 Fury 后，与旧默认 JDK serializer 不兼容。
- 最优解：双端同时升级；滚动升级窗口内显式配置 `JdkSerializerUdpClientCodec`。

### 内存泄漏风险

- 风险集中在 retained fragment、composite buffer、ACK 重发 payload。
- 最优解：新增 release helper，并在 success / failure / timeout / close / duplicate / replace / expire 全分支覆盖单测。

### EventLoop 延迟风险

- Fury encode/decode 在 I/O 线程执行，大对象可能抬高 p99。
- 最优解：保留 `maxFragmentCount` 和 payload 上限，后续按指标决定是否对超大对象走业务线程池或禁止发送。

### 安全风险

- 反序列化必须限制 class allowlist。
- 最优解：默认只允许 `java.`、`javax.`、`org.rx.`，业务类型必须显式 `allowPrefix(...)`。

### 背压风险

- 大对象拆成多 fragment 后会瞬时写入多个 datagram。
- 最优解：继续走 `Sockets.writeUdp(...)` 的 pending bytes 软上限，写入被拒绝时立刻失败当前 `SendContext`。

## 12. 核心监控指标建议

至少补充：

- 堆外内存占用：`PooledByteBufAllocator.DEFAULT.metric().usedDirectMemory()`
- `udp.codec.encode.count`
- `udp.codec.encode.error.count`
- `udp.codec.decode.count`
- `udp.codec.decode.error.count`
- `udp.fragment.out.count`
- `udp.fragment.in.count`
- `udp.fragment.assembly.timeout.count`
- `udp.fragment.assembly.bytes`
- `udp.ack.timeout.count`
- `udp.resend.count`
- `udp.pending.write.bytes`
- `udp.write.drop.count`
- `udp.message.latency.p50/p95/p99`

## 13. 当前结论

本次重构建议将 `UdpClient` 的职责收敛为“可靠 UDP transport + 分片重组”，对象序列化完全交给 `UdpClientCodec`。默认 Fury 能与当前 RPC/TCP codec 方向保持一致，同时通过构造配置给使用方保留自定义 codec 能力。拆包和装包不应继续依赖 `byte[] merge/copy`，而应改为 `ByteBuf retainedSlice + CompositeByteBuf`，这样可以减少大 payload 跨分片时的复制，并把引用计数规则集中在 `SendContext` 与 `ReceiveAssembly` 两处审计。
