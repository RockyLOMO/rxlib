# RpcTcp 通用传输层与 Remoting Codec 重构执行记录

## 本次模式

**高性能模式（Netty 底层网络编程）**

## 需求变更确认

相较原计划，本次执行按以下约束落地：

- 不再使用 Kryo，`Remoting` 默认 codec 直接切换为 **Apache Fury**。
- 不再保留 `RpcTcpClient` / `DefaultRpcTcpClient` / `RpcTcpServer` 的 `@Deprecated` 兼容壳，直接切换到新命名。
- transport 层彻底移除默认 Java object codec 绑定，序列化职责下沉到 `RemotingCodecFactory`。

## 执行进度

- [x] 新增通用传输抽象：`TcpClient`、`DefaultTcpClient`、`TcpServer`
- [x] 新增 transport codec 装配接口：`TcpChannelCodec`
- [x] `TcpClientConfig` / `TcpServerConfig` 增加 `codec` 配置
- [x] 移除 transport 层默认 `ObjectEncoder` / `ObjectDecoder`
- [x] 新增 `RemotingCodecFactory`
- [x] 新增 `FuryRemotingCodecFactory`
- [x] `RpcClientConfig` / `RpcServerConfig` 默认 codecFactory 切换为 Fury
- [x] `Remoting`、连接池、上下文切换到 `TcpClient` / `TcpServer`
- [x] 测试代码迁移到新命名
- [x] 补充 Fury codec 单元测试
- [x] 完成核心单元测试与 RPC 集成测试验证
- [ ] 后续补 benchmark，对比 Fury 与旧 JDK object codec 的延迟/吞吐

## 已落地实现

### 1. 通用 TCP 传输层解耦

transport 层现在只负责：

- 建连、断连、重连、保活
- 背压、水位、连接生命周期事件
- 传输管线公共能力装配

transport 层不再负责：

- Java 对象序列化
- RPC 协议消息类型绑定
- JDK object codec 默认注入

对应调整：

- `RpcTcpClient` -> `TcpClient`
- `DefaultRpcTcpClient` -> `DefaultTcpClient`
- `RpcTcpServer` -> `TcpServer`
- `TcpServerEventArgs<T>` 的消息泛型已放宽，避免 transport 被 RPC `Serializable` 语义绑死

### 2. Codec 装配方式

新增接口：

```java
public interface TcpChannelCodec extends Serializable {
    void install(ChannelPipeline pipeline);
}
```

初始化顺序保持为：

1. transport 公共 handler
2. `config.getCodec().install(pipeline)`
3. `TcpServer` / `DefaultTcpClient` 终端 handler

这样可以保证：

- RPC、SOCKS、自定义协议可以各自装配 framing 和序列化
- 非 `@Sharable` codec handler 不会被错误复用
- transport 与协议编解码职责边界清晰

### 3. Remoting 默认 Apache Fury

新增：

- `org.rx.net.rpc.RemotingCodecFactory`
- `org.rx.net.rpc.FuryRemotingCodecFactory`

默认行为：

- `RpcClientConfig.codecFactory = FuryRemotingCodecFactory.createDefault()`
- `RpcServerConfig.codecFactory = FuryRemotingCodecFactory.createDefault()`
- `Remoting` 在创建 client/server 前强制写入对应 Fury codec，避免误跑成裸 TCP

### 4. Fury 帧格式与安全约束

当前 Fury pipeline：

```text
LengthFieldBasedFrameDecoder
FuryMessageDecoder
LengthFieldPrepender
FuryMessageEncoder
```

内层帧头：

- `magic`
- `version`
- `codecId`
- `payloadLength`

当前约束：

- `codecId = 1` 表示 Fury
- payload 上限受 `Constants.MAX_HEAP_BUF_SIZE` 约束
- 通过 `ClassChecker` 做类名前缀 allowlist，默认允许：
  - `java.`
  - `javax.`
  - `org.rx.`

已注册核心消息类型：

- `PingPacket`
- `ErrorPacket`
- `MetadataMessage`
- `MethodMessage`
- `EventMessage`
- `EventFlag`
- `EventArgs`
- `NEventArgs`
- `RemotingEventArgs`

### 5. Fury 线程模型与内存语义

- Fury 实例通过 `FastThreadLocal<Fury>` 绑定到 Netty 线程，避免跨线程共享
- 编码直接写入 Netty `ByteBufOutputStream`
- 解码优先走 `ByteBuf.nioBuffer()`，复合缓冲区再退化为 `byte[]`
- decoder 只消费当前 frame，避免 `ByteBuf` 生命周期泄漏

## 风险评估

### 已覆盖风险

- **内存泄漏**：新增 codec 单测在 `PARANOID` leak detector 下回归；`ByteBuf` 在编解码测试中已验证可正常释放
- **连接生命周期**：`RemotingTest` 已验证 stateful 模式重连、同端口重启、断链重发
- **线程模型**：Fury 实例线程本地化，未引入 I/O 线程阻塞逻辑
- **协议切换**：transport 与 RPC codec 已完成解耦，后续可扩展其他 codec，不需要再改 transport 主链路

### 当前剩余说明

- Fury 在 `build()` 阶段仍会打印一条“未强制 class registration”的初始化告警；功能不受影响，因为 `ClassChecker` 会在 build 后注入。
- `org.rx.bean.DateTime` 当前会触发 Fury 的 `ObjectStreamSerializer` 警告；如果该类型进入 RPC 热路径，建议补自定义 Fury serializer，避免退化到 JDK serialization。
- 如果后续需要更强反序列化安全边界，建议新增“显式注册业务参数类型”的扩展点，并切换到 `requireClassRegistration(true)`。

## 核心监控指标建议

本次改动后，建议至少补齐以下监控：

- 堆外内存占用：`PooledByteBufAllocator.DEFAULT.metric().usedDirectMemory()`
- active channel 数、建连成功数、断连数、重连数
- 入站/出站 bytes/s、messages/s
- RPC p50/p95/p99 延迟、超时数、重发数
- `Channel.isWritable() == false` 次数与持续时间
- Fury encode/decode 耗时、异常数、解码拒绝数

## 测试与验证

已执行：

```bash
mvn -pl rxlib -DskipTests compile
mvn -pl rxlib -DskipTests test-compile
mvn -pl rxlib "-Dtest=FuryRemotingCodecTest,TcpTransportTest,RemotingTest,RrpIntegrationTest" test
```

当前结论：

- `FuryRemotingCodecTest` 通过
- `TcpTransportTest` 通过
- `RemotingTest` 通过
- `RrpIntegrationTest` 通过

说明：

- 小改动单测已覆盖 Fury codec 往返与 `TcpClientConfig` 深拷贝场景
- 集成测试已覆盖 RPC 注册、调用、事件广播、连接池、断链恢复、双次重启恢复，以及 RRP 远程转发链路

## 结果结论

本次重构已经按“直接 Apache Fury、无 Deprecated 兼容层”的版本完成主链路落地。当前 `rxlib` 的 TCP transport 与 RPC codec 职责已经拆开，`Remoting` 默认通过 Apache Fury 进行编解码，且核心单元测试与 RPC 集成测试已验证通过。
