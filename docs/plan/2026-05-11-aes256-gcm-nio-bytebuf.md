# 背景

用户希望优化 `rockylomo/rxlib` 中 `ShadowsocksServer` 的加解密路径：当前 Shadowsocks AEAD 加解密仍依赖 `bcprov-jdk18on`，并通过 `byte[]` 中间缓冲完成加解密。用户明确要求只实现 `aes-256-gcm`，并希望能够直接走 NIO 加解密，最好基于 Netty `ByteBuf`。

本次任务按“新需求类任务”处理：先完成仓库扫描、影响面分析和计划文档提交；在用户明确要求“按计划执行 / 开始修改代码 / 实现代码”之前，不修改业务代码。

# 任务类型判断

本次需求属于新需求 / 性能优化类任务，原因如下：

- 需要新增或替换 `aes-256-gcm` 的底层加解密实现。
- 当前实现已经有 `ICrypto` 的 `ByteBuf` 接口，但内部仍使用 Bouncy Castle `AEADCipher` 和 `byte[]` 缓冲。
- 用户要求将 `aes-256-gcm` 收敛到 NIO / Netty `ByteBuf` 路径，属于对现有加密模块的新实现方案。
- 该修改会影响 Shadowsocks TCP/UDP 加解密链路，需要补充测试并通过 JDK 8 CI 验证。

# 当前上下文

仓库默认分支为 `master`，当前计划分支为 `agent/aes256-gcm-nio-bytebuf`。

已 review 的关键文件：

- `AGENTS.md`
  - 项目要求严格 Java 8。
  - 网络路径优先原生 Netty 能力。
  - 性能敏感路径要求优先 Direct Buffer、零拷贝、低对象分配。
  - `ByteBuf` 必须遵守引用计数语义。
- `rxlib/src/main/java/org/rx/net/socks/encryption/ICrypto.java`
  - 对外接口已经是 `ByteBuf encrypt(ByteBuf in)` 和 `ByteBuf decrypt(ByteBuf in)`。
- `rxlib/src/main/java/org/rx/net/socks/encryption/CryptoAeadBase.java`
  - 当前 AEAD 公共逻辑依赖 Bouncy Castle `AEADCipher`、`HKDFBytesGenerator`、`SHA1Digest`、`AEADParameters` 等类型。
  - TCP/UDP 公共逻辑存在 `byte[] encBuffer`、`byte[] decBuffer` 与 UDP 线程本地 `byte[]` 缓冲。
  - 负责 salt、subkey、nonce、TCP 分块、UDP 包格式处理。
- `rxlib/src/main/java/org/rx/net/socks/encryption/impl/AesGcmCrypto.java`
  - 当前 AES-GCM 实现依赖 Bouncy Castle `AESEngine`、`GCMBlockCipher`、`AEADCipher`。
  - TCP 格式为 `[encrypted payload length][length tag][encrypted payload][payload tag]`。
  - UDP 格式为 `[salt][encrypted payload][tag]`。
  - 当前读写 payload 时会从 `ByteBuf` 拷贝到 `byte[]` 再调用 BC API。
- `rxlib/src/main/java/org/rx/net/socks/encryption/CipherKind.java`
  - 当前 `aes-128-gcm`、`aes-192-gcm`、`aes-256-gcm` 都映射到 `AesGcmCrypto`。
- `rxlib/src/main/java/org/rx/net/socks/CipherCodec.java`
  - Netty pipeline 中的加解密入口，从 `ShadowsocksConfig.CIPHER` 取 `ICrypto` 并调用 `encrypt/decrypt`。
- `rxlib/src/main/java/org/rx/net/socks/SSProtocolCodec.java`
  - 解密后解析 Shadowsocks 地址头，后续链路依赖解密结果仍为可读 `ByteBuf`。
- `rxlib/pom.xml`
  - 当前存在 `bcprov-jdk18on` 依赖。本任务优先让 `aes-256-gcm` 不再走 BC byte[] 加解密路径；是否移除 BC 依赖需在代码阶段进一步确认其它 cipher / HKDF 代码是否仍依赖 BC。
- `.github/workflows/jdk8-unit-tests.yml`
  - 支持 `workflow_dispatch`，并支持传入 `test_classes`。

可用相关测试：

- `rxlib/src/test/java/org/rx/net/socks/CipherCodecTest.java`
- `rxlib/src/test/java/org/rx/net/socks/SSProtocolCodecTest.java`
- `rxlib/src/test/java/org/rx/net/socks/ShadowsocksServerIntegrationTest.java`
- `rxlib/src/test/java/org/rx/net/socks/SSUdpProxyHandlerTest.java`

# 目标

1. 为 `aes-256-gcm` 新增基于 JDK 8 JCE NIO `ByteBuffer` API 的实现，避免在 payload 加解密热点路径中使用 Bouncy Castle `AEADCipher` 和大块 `byte[]` 中间缓冲。
2. 保持 `ICrypto` 对外接口不变，继续以 Netty `ByteBuf` 作为输入输出。
3. 加密输出优先使用 direct `ByteBuf`，并通过 `ByteBuf.nioBuffer(...)` / `ByteBuf.nioBuffers(...)` / 输出 `ByteBuffer` 写入能力对接 JCE `Cipher`。
4. 保持 Shadowsocks AEAD 协议兼容：TCP 首包 salt、TCP chunk length/tag、payload/tag、UDP salt + encrypted payload + tag。
5. 只切换 `aes-256-gcm` 路径；其它 cipher 不在本次需求范围内。
6. 补充 JDK 8 可运行测试，覆盖 TCP/UDP roundtrip、TCP 半包解密、direct `ByteBuf` 输入、认证失败等场景。
7. 代码提交后触发 `jdk8-unit-tests.yml` 并按 CI 结果继续修复，最终保持 CI 通过。

# 非目标

- 不实现 `aes-128-gcm`、`aes-192-gcm` 的 NIO 版本，除非为了兼容编译和最小改动必须调整映射。
- 不实现 `chacha20-ietf-poly1305`。
- 不重写 `SocksProxyServer`、`SSProtocolCodec`、UDP relay、RRP、udp2raw 等无关链路。
- 不引入新重型依赖。
- 不升级 Java 版本，不使用 JDK 9+ API。
- 不自动发布 release。
- 不修改 secrets、token、证书或私钥。
- 不在计划提交阶段修改业务代码。

# 设计方案

## 总体方案

新增一个只服务于 `aes-256-gcm` 的 JCE/NIO 实现，例如：

- `rxlib/src/main/java/org/rx/net/socks/encryption/impl/Aes256GcmByteBufCrypto.java`

该实现直接实现 `ICrypto`，或抽取一个 BC-free 的 JCE AEAD base。为了最小改动，优先选择新增独立实现，避免大范围改造现有 `CryptoAeadBase` 和 `AesGcmCrypto`。

`CipherKind.newInstance(...)` 中仅将 `aes-256-gcm` 映射到新的 NIO/ByteBuf 实现；`aes-128-gcm`、`aes-192-gcm` 暂时保留现有 `AesGcmCrypto`，从而降低兼容性风险。

## 加密算法

使用 Java 8 标准 JCE：

- `Cipher.getInstance("AES/GCM/NoPadding")`
- `SecretKeySpec(subkey, "AES")`
- `GCMParameterSpec(128, nonce)`

HKDF-SHA1 使用 Java 8 标准 `javax.crypto.Mac` 实现：

- Extract：`HmacSHA1(salt, masterKey)`
- Expand：info 为 `ss-subkey`
- 输出长度为 32 字节

这样 `aes-256-gcm` 新路径不依赖 Bouncy Castle HKDF 或 AEAD API。

## ByteBuf / NIO 数据流

输入：

- 对 `ByteBuf` 使用 `readerIndex()` 与 `readableBytes()` 获取可读区域。
- 如果 `nioBufferCount() == 1`，直接使用 `in.nioBuffer(readerIndex, len)` 作为 JCE 输入。
- 如果是 composite buffer，使用 `in.nioBuffers(readerIndex, len)` 分段调用 JCE `Cipher.update(ByteBuffer, ByteBuffer)`，最后 `doFinal(...)`。
- 输入消费成功后再推进 `readerIndex`，避免认证失败时破坏上游状态；如果现有调用语义要求边读边消费，则测试阶段确认并保持一致。

输出：

- 使用现有项目分配方式（例如 `Bytes.directBuffer(...)`）创建 direct `ByteBuf`。
- 通过输出 `ByteBuf` 的 NIO buffer 视图传入 `Cipher.update/doFinal`。
- 按 JCE 返回写入字节数精确推进 `writerIndex`。
- 失败时释放输出 `ByteBuf`，避免堆外内存泄漏。

## TCP 状态机

保持当前 AEAD TCP 语义：

1. 加密：首次加密生成 salt，并用 HKDF 生成 subkey；每个 chunk 最多 `0x3FFF` 字节；length 与 payload 分别加密并递增 nonce。
2. 解密：首次读取 salt 并生成 subkey；分阶段读取 encrypted length + tag 与 encrypted payload + tag；支持 TCP 半包；认证失败时抛出解码异常。

## UDP 处理

保持当前 AEAD UDP 语义：

- 每个 UDP 包独立生成 salt。
- nonce 使用 12 字节全零。
- 输出 `[salt][ciphertext][tag]`。
- 解密时从包头读取 salt，HKDF 得到 subkey，再解密剩余 payload + tag。

## 异常处理

JCE 认证失败通常表现为：

- `javax.crypto.AEADBadTagException`
- `javax.crypto.BadPaddingException`

需要在 `CipherCodec` 中把这些异常视为认证失败，行为与当前捕获 Bouncy Castle `InvalidCipherTextException` 一致：TCP 记录 warning 后关闭 inbound channel，UDP 记录 warning 并丢弃该包。

## 资源释放策略

- 新实现返回的 `ByteBuf` 所有权交给 pipeline 后续 handler。
- 如果加解密过程中出现异常，必须释放已分配的输出 `ByteBuf`。
- 不 retain 输入 `ByteBuf`，输入生命周期继续由 Netty codec 语义管理。
- 所有 direct buffer 写入必须正确维护 reader/writer index。
- 测试中增加直接 `release()`，避免内存泄漏。

# 修改文件列表

计划代码阶段预计新增或修改以下文件：

- 新增：`rxlib/src/main/java/org/rx/net/socks/encryption/impl/Aes256GcmByteBufCrypto.java`
- 修改：`rxlib/src/main/java/org/rx/net/socks/encryption/CipherKind.java`
- 可能修改：`rxlib/src/main/java/org/rx/net/socks/CipherCodec.java`
- 可能修改：`rxlib/pom.xml`
  - 只有在确认 BC 已不再被当前保留功能使用、且测试通过时才考虑移除 `bcprov-jdk18on`；本任务默认不强制移除依赖。
- 新增：`rxlib/src/test/java/org/rx/net/socks/encryption/Aes256GcmByteBufCryptoTest.java`
- 可能修改：`rxlib/src/test/java/org/rx/net/socks/CipherCodecTest.java`

# 风险点

1. **JDK 8 Provider 差异**：`AES/GCM/NoPadding` 在常见 JDK 8 provider 中可用，但不同发行版的 NIO `ByteBuffer` 行为和异常类型可能存在差异。
2. **Composite ByteBuf**：`ByteBuf.nioBuffer(...)` 对 composite buffer 可能产生合并视图或异常，需要处理 `nioBufferCount() > 1` 的场景。
3. **writerIndex 维护**：JCE NIO API 写入输出 `ByteBuffer` 后，必须按实际写入字节数推进 `ByteBuf.writerIndex`。
4. **TCP 半包状态**：新实现必须严格保持当前 length phase / payload phase 半包解密行为。
5. **认证失败行为变化**：BC 的 `InvalidCipherTextException` 与 JCE 的 `AEADBadTagException` 类型不同，需要保持 `CipherCodec` 的日志与关闭策略一致。
6. **内存泄漏**：direct `ByteBuf` 分配后如异常路径未释放，会引入堆外内存泄漏。
7. **性能回退**：应优先使用分段 NIO `ByteBuffer`，只在不可避免的小元数据场景使用小型 `byte[]`。
8. **依赖移除风险**：即使 `aes-256-gcm` 不再依赖 BC，AES-128/192 或其它保留代码可能仍引用 BC。是否移除 `bcprov-jdk18on` 需要单独以编译结果为准。

# 验证方案

代码实现阶段完成后，执行以下验证：

1. 单元测试：
   - `Aes256GcmByteBufCryptoTest`
   - `CipherCodecTest`
   - `SSProtocolCodecTest`
2. 集成回归：
   - `ShadowsocksServerIntegrationTest`
   - 必要时补充 `SSUdpProxyHandlerTest`
3. Maven 命令建议：

```bash
mvn -B -U -Dgpg.skip=true -Dmaven.test.skip=false -DskipTests=false -Dtest=Aes256GcmByteBufCryptoTest,CipherCodecTest,SSProtocolCodecTest,ShadowsocksServerIntegrationTest clean test
```

4. GitHub Actions：

代码 commit 后触发 `.github/workflows/jdk8-unit-tests.yml`，传入：

```text
test_classes=Aes256GcmByteBufCryptoTest,CipherCodecTest,SSProtocolCodecTest,ShadowsocksServerIntegrationTest
```

5. CI 判定：

- 仅当 workflow run 的 `conclusion=success` 时认为通过。
- 如果 CI 失败，先读取失败日志并分类，再只修改与失败直接相关的代码，修复后再次 commit 并重新触发 CI。
