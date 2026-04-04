---
name: SS Encryption Bug Fix
overview: 对 commit `1947e0c1` ("up ss") 及前序 commit `bdf913c9` ("up") 进行 review，发现 1 个严重加解密 Bug、2 个 buffer 估算错误、1 个资源浪费问题，以及 1 个 UdpRedundantEncoder 的 use-after-free Bug。
todos:
  - id: p0-revert-salt
    content: CryptoAeadBase.encrypt() UDP 盐值回退为 SecureRandom (或实现批量预生成方案)
    status: completed
  - id: p1-decrypt-estimate
    content: CryptoAeadBase.decrypt() estimatedSize 区分首包/后续包、区分 UDP/TCP
    status: completed
  - id: p1-encrypt-estimate
    content: CryptoAeadBase.encrypt() TCP 首包 estimatedSize 加上 getSaltLength()
    status: completed
  - id: p1-ure-uaf
    content: UdpRedundantEncoder intervalMicros>0 路径修复 slice 引用计数
    status: completed
  - id: p2-lazy-crypto-group
    content: ShadowsocksServer 延迟创建 sharedCryptoGroup
    status: completed
isProject: false
---

# SS 加解密问题排查与修复计划

## 变更范围

重点审查的两次提交：
- `1947e0c1` ("up ss") -- 性能优化 + cryptoGroup 可配置化
- `bdf913c9` ("up") -- CryptoAeadBase FastThreadLocal 重构 + UdpRedundantEncoder 零拷贝重构

涉及文件：[CryptoAeadBase.java](rxlib/src/main/java/org/rx/net/socks/encryption/CryptoAeadBase.java), [ShadowsocksServer.java](rxlib/src/main/java/org/rx/net/socks/ShadowsocksServer.java), [UdpRedundantEncoder.java](rxlib/src/main/java/org/rx/net/socks/UdpRedundantEncoder.java), [ShadowsocksConfig.java](rxlib/src/main/java/org/rx/net/socks/ShadowsocksConfig.java)

---

## BUG 1 (P0 - 严重): UDP 盐值改用 ThreadLocalRandom 导致 AES-GCM nonce reuse

**位置**: [CryptoAeadBase.java](rxlib/src/main/java/org/rx/net/socks/encryption/CryptoAeadBase.java) 第 159-160 行（commit `1947e0c1` 引入）

**改动**:

```java
// 改动前（安全）
byte[] salt = CodecUtil.secureRandomBytes(getSaltLength());

// 改动后（危险）
byte[] salt = new byte[getSaltLength()];
java.util.concurrent.ThreadLocalRandom.current().nextBytes(salt);
```

**问题分析**:

这是**最可能导致生产加解密问题**的变更。原因如下：

1. **AES-GCM nonce reuse 灾难**: Shadowsocks AEAD-UDP 协议中，每个 UDP 包独立加密，使用 `salt -> HKDF -> subkey`，nonce 固定为 `ZERO_NONCE`。这意味着**盐值的唯一性是唯一的安全保障**。`ThreadLocalRandom` 是**非密码学安全的 PRNG**，内部只有 64 位状态，盐值碰撞概率远高于 `SecureRandom`（128/192/256 位盐值）。一旦两个 UDP 包使用相同盐值，就会产生相同的 `(subkey, nonce)` 组合，AES-GCM 的安全性**完全崩塌**

2. **对端重放保护**: 部分 Shadowsocks 客户端/服务端实现了**盐值重放保护**（检查近期盐值是否重复），`ThreadLocalRandom` 较弱的随机性增加了碰撞概率，导致正常包被误判为重放而**被丢弃**

3. **BouncyCastle GCM 内部校验**: 当 nonce reuse 时解密端 GCM 认证标签校验会失败，抛出 `InvalidCipherTextException`，在 [CipherCodec.decode()](rxlib/src/main/java/org/rx/net/socks/CipherCodec.java) 第 54-59 行被捕获后打 warn 日志然后关闭连接

**修复**: 恢复使用 `CodecUtil.secureRandomBytes()`。如果确需优化 `SecureRandom` 性能，可以使用批量预生成方案：

```java
// 方案：每线程批量预生成盐值缓存
private static final FastThreadLocal<ByteBuffer> SALT_POOL = new FastThreadLocal<ByteBuffer>() {
    @Override
    protected ByteBuffer initialValue() {
        byte[] bulk = CodecUtil.secureRandomBytes(getSaltLength() * 64);
        return ByteBuffer.wrap(bulk);
    }
};
```

如果不需要极致性能优化，**直接回退到 `CodecUtil.secureRandomBytes()` 是最安全的做法**。

---

## BUG 2 (P1): TCP decrypt `estimatedSize` 对非首包计算错误

**位置**: [CryptoAeadBase.java](rxlib/src/main/java/org/rx/net/socks/encryption/CryptoAeadBase.java) 第 184 行

```java
int estimatedSize = Math.max(0, in.readableBytes() - getSaltLength() - TAG_LENGTH);
```

**问题**: TCP 流只有**首包**含 salt（`decCipher == null` 时才读取 salt）。但 `estimatedSize` **始终**减去 `getSaltLength()`，导致：
- **首包**: `in` 包含 `salt + chunk数据`，减去 salt 和一个 TAG 是合理的近似
- **后续包**: `in` 只有 `chunk数据`（无 salt），多减了 `getSaltLength()` 个字节（16/24/32），buffer 初始容量偏小

ByteBuf 会自动扩容，**不会丢数据**，但高频场景下频繁扩容 direct buffer 会增加系统调用和内存拷贝开销。

**修复**:

```java
int estimatedSize;
if (forUdp) {
    estimatedSize = Math.max(0, in.readableBytes() - getSaltLength() - TAG_LENGTH);
} else {
    if (decCipher == null) {
        // 首包：减去 salt + 每 chunk 的 overhead
        estimatedSize = Math.max(0, in.readableBytes() - getSaltLength());
    } else {
        // 后续包：输入全是 chunk 数据，输出略小于输入
        estimatedSize = in.readableBytes();
    }
}
```

---

## BUG 3 (P1): TCP encrypt `estimatedSize` 首包未计入 salt

**位置**: [CryptoAeadBase.java](rxlib/src/main/java/org/rx/net/socks/encryption/CryptoAeadBase.java) 第 153-155 行

```java
int estimatedSize = forUdp
    ? getSaltLength() + in.readableBytes() + TAG_LENGTH
    : in.readableBytes() + ((in.readableBytes() / PAYLOAD_SIZE_MASK) + 1) * (2 + TAG_LENGTH * 2);
```

**问题**: TCP 首次加密（`encCipher == null`）时会在输出中写入 salt（第 168-169 行 `out.writeBytes(salt)`），但 `estimatedSize` 计算不含 `getSaltLength()`。首包 buffer 一定会扩容一次。

**修复**: TCP 分支应在首包时加上 salt 长度：

```java
: (encCipher == null ? getSaltLength() : 0)
    + in.readableBytes() + ((in.readableBytes() / PAYLOAD_SIZE_MASK) + 1) * (2 + TAG_LENGTH * 2);
```

---

## BUG 4 (P1): UdpRedundantEncoder Use-After-Free

**位置**: [UdpRedundantEncoder.java](rxlib/src/main/java/org/rx/net/socks/UdpRedundantEncoder.java) 第 165-185 行（commit `bdf913c9` 引入）

**问题**: 当 `intervalMicros > 0` 时：

```java
ByteBuf payloadSlice = firstBuf.slice(...);
payloadSlice.retain();                         // firstBuf refCnt = 2

ctx.write(new DatagramPacket(firstBuf, ...));  // 异步写完成后 firstBuf refCnt - 1

for (...) {
    ctx.executor().schedule(() -> {
        // 定时任务在未来执行，此时 payload 可能已 released
        writeRedundantCopyOptimized(ctx, seqId, payload, recipient);
        // 内部调用 payload.retain() -> IllegalReferenceCountException!
    }, delayMicros, ...);
}

payloadSlice.release();                        // firstBuf refCnt - 1 -> 可能为 0
```

`payloadSlice` 是 `firstBuf` 的派生 slice，共享引用计数。在 `payloadSlice.release()` 执行后，如果 `ctx.write` 也已完成释放，`firstBuf` 的 refCnt 变为 0，缓冲区被回收。后续定时任务执行时尝试 `payload.retain()` 会抛 `IllegalReferenceCountException`，**导致 UDP 冗余副本发送全部失败**。

**修复**: 每个定时任务需要额外 retain，在任务内部 release：

```java
for (int i = 1; i < multiplier; i++) {
    final ByteBuf payload = payloadSlice.retainedSlice(); // 独立引用
    long delayMicros = (long) intervalMicros * i;
    ctx.executor().schedule(() -> {
        try {
            writeRedundantCopyOptimized(ctx, seqId, payload, recipient);
            ctx.flush();
        } finally {
            payload.release();
        }
    }, delayMicros, TimeUnit.MICROSECONDS);
}
// 不再需要最后的 payloadSlice.release()，因为每个任务有自己的引用
payloadSlice.release(); // 只释放初始 retain 的引用
```

---

## 问题 5 (P2): `sharedCryptoGroup()` 无条件创建线程池

**位置**: [ShadowsocksServer.java](rxlib/src/main/java/org/rx/net/socks/ShadowsocksServer.java) 第 40 行

```java
EventExecutorGroup cryptoGroup = sharedCryptoGroup(); // 无论是否使用都创建
```

**问题**: 当 `useDedicatedCryptoGroup = false`（默认）时，仍然创建了 `CPU_THREADS * 2` 个线程的 `DefaultEventExecutorGroup`，浪费系统资源（线程栈内存、OS 线程描述符）。

**修复**: 延迟创建，仅在需要时调用：

```java
EventExecutorGroup cryptoGroup = config.isUseDedicatedCryptoGroup() ? sharedCryptoGroup() : null;
```

---

## 修复优先级

| 优先级 | 问题 | 影响 | 根因 |
|--------|------|------|------|
| P0 | ThreadLocalRandom 盐值 | UDP 解密失败、连接断开 | nonce reuse / 重放检测误判 |
| P1 | TCP decrypt estimatedSize 错误 | 性能下降（频繁 buffer 扩容） | 未区分首包/后续包 |
| P1 | TCP encrypt estimatedSize 缺 salt | 性能下降（首包必扩容） | 未计入 salt 长度 |
| P1 | UdpRedundantEncoder UAF | UDP 冗余发送崩溃 | slice 引用计数错误 |
| P2 | cryptoGroup 无条件创建 | 资源浪费 | 缺少延迟初始化 |
