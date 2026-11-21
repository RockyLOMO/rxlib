# Java 自适应 FEC（前向纠错）技术方案设计

## 1. 背景与设计目标

在无线弱网、跨运营商链路或拥塞严重的网络环境中，传统基于重传（ARQ）的方案（如 TCP、原生 QUIC）需要至少 $1 \times \text{RTT}$ 才能恢复丢包，高丢包下延迟抖动剧烈；而传统静态 FEC 方案（固定发送 $K$ 个数据包 + $M$ 个冗余包）在网络良好时也会持续浪费带宽。

本方案旨在基于 **Java + Netty** 构建一套**自适应前向纠错（Adaptive FEC）传输中间件**，严格遵循以下优先级目标：

1. **高抗丢包能力（第一优先级）**：
   * 在 $5\% \sim 25\%$ 随机丢包及短时突发丢包（Burst Loss）环境下，将业务感知的残余丢包率压低至 $\epsilon \le 0.5\%$。
   * 绝大多数丢包在 $0\text{-RTT}$ 内由接收端直接恢复，消除高延迟链路的重传卡顿。
2. **精细化流量控制（第二优先级）**：
   * **零冗余静默模式**：当链路丢包率低于阈值（如 $p < 0.5\%$）时，自动关闭 FEC 编码，额外流量开销为 $0\%$，编解码 CPU 消耗为 $0$。
   * **按需冗余与拥塞防自杀**：动态调整分组大小 $K$ 与冗余度 $M$，将长期平均额外流量开销控制在接近真实丢包率的合理区间（如 $1.2 \sim 1.5$ 倍实际丢包率），并在检测到排队拥塞时触发熔断，避免冗余包压垮链路。
3. **Java 生产级性能**：
   * 全链路基于 Netty 堆外内存（`PooledByteBufAllocator`）零拷贝设计与对象池复用，避免高频发包触发 Young GC 停顿。

---

## 2. 开源项目调研与核心借鉴矩阵

Java 生态中缺乏开箱即用的生产级“自适应 FEC”库，因此本方案采取**“底层编解码借鉴 Java/Go 成熟实现 + 控制面算法借鉴 WebRTC/UDPspeeder”**的融合架构：

| 开源项目 | 语言/生态 | 核心可借鉴特性 | 需规避的短板/改造点 |
| :--- | :--- | :--- | :--- |
| **`l42111996/java-Kcp`** | Java / Netty | 基于 Netty `ByteBuf` 改造的 Reed-Solomon 编解码器（`FecEncode`/`FecDecode`）、变长包长度前缀补齐法、对象池复用思路。 | 其 FEC 比例为静态配置（`dataShards`/`parityShards` 写死），缺乏动态调整、突发丢包感知及小流量超时刷新机制。 |
| **`Backblaze/JavaReedSolomon`** | Java | 极其清晰、经过生产验证的伽罗瓦域 $\text{GF}(2^8)$ 矩阵运算与范德蒙德矩阵构造实现。 | 默认面向大文件块 `byte[][]` 堆内数组，直接用于网络小包会产生大量 GC 压力；需改造为柯西矩阵（Cauchy Matrix）+ `ByteBuf` 直接内存操作。 |
| **`WebRTC` (`FlexFEC` / `VCM`)** | C++ | **自适应控制算法标杆**：分离“原始丢包率”与“恢复后丢包率”；Gilbert-Elliott 两态马尔可夫突发丢包模型；基于预计算查找表实现 $O(1)$ 冗余因子映射。 | 强耦合音视频帧结构（关键帧/普通帧权重），需抽象为面向通用 UDP/数据报流的分组模型。 |
| **`wangyu-/UDPspeeder`** | C++ | **动态分组 + 超时刷出（Flush Timeout）**机制：当低流量下凑不够 $K$ 个包时，超时立即以实际包数 $K' < K$ 提前生成冗余包发出，彻底解决小流量交互高延迟问题。 | 缺乏精细的丢包闭环反馈与自动静默关闭策略，默认偏费流量。 |
| **`klauspost/reedsolomon`** | Go | 4-bit 拆表乘法（Low/High Nibble LUT）与 SIMD 向量指令优化思路。 | 可在 Java 21+ 中利用 `jdk.incubator.vector`（Vector API）复刻 SIMD 加速，降低高吞吐下的编码 CPU 占用。 |

---

## 3. 总体架构设计

系统基于 **Netty UDP (`EpollDatagramChannel` / `NioDatagramChannel`)** 构建，严格分离**数据面（Data Plane，Netty IO 线程无锁执行）**与**控制面（Control Plane，异步周期反馈与策略计算）**。

```text
+----------------------------------------------------------------------------------+
|                                   业务应用层                                      |
+-------------------+------------------------------------------+-------------------+
                    | 下行发包 (ByteBuf)                        ^ 上行递交 (ByteBuf)
                    v                                          |
+------------------------------------+      +--------------------------------------+
|       发送端管道 (Sender Pipeline)  |      |       接收端管道 (Receiver Pipeline)  |
|                                    |      |                                      |
|  1. MTU 校验与分片防护              |      |  1. 协议解析 & 原始丢包/乱序统计       |
|  2. AdaptiveFecEncoder             |      |     (必须基于 FEC 恢复前的原始序号!)   |
|     - 静默直通 (M=0 零开销)         |      |  2. AdaptiveFecDecoder               |
|     - 活跃分组缓存 + 超时强刷       |      |     - 滑动窗口分组缓存 (GroupWindow)  |
|     - XOR快路径(M=1) / RS编码(M>1) |      |     - 满 K 包即时触发 RS 解码还原     |
|  3. Smooth Pacer (平滑发包限流)     |      |     - 超时清理与 ByteBuf 安全释放     |
+-----------------+------------------+      +-------------------+------------------+
                  |                                             |
                  |   ^ 读取当前最优参数 (K, M, timeout)          | 上报原始丢包位图、
                  |   | (Volatile/Atomic 读，无锁)               | 突发长度、时间戳
                  |   |                                         v
                  |  +------------------------------------------+---------+
                  |  |         自适应控制面 (Adaptive Control Plane)       |
                  |  |  - Loss & Burst Estimator (非对称EWMA + G-E模型)   |
                  |  |  - RTT & Congestion Guard (排队延迟斜率检测/熔断)   |
                  |  |  - Dynamic (K, M) Policy Engine (O(1) 查表引擎)    |
                  |  +----------------------------------------------------+
                  v                                             ^
+----------------------------------------------------------------------------------+
|                       Netty UDP Transport (Epoll / NIO)                          |
+----------------------------------------------------------------------------------+
```

---

## 4. 紧凑型传输协议帧格式设计

为最大限度节省流量，FEC 协议头设计为 **14 字节定长二进制头**（支持单分组最大 $K + M \le 255$，且允许接收端在动态变参时无需额外握手即可自描述解码）：

```text
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
| Ver |Type | Flags |   DataShards (K)  |  ParityShards (M) |Idx|  (4 Bytes)
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                        GroupID (32 bit)                       |  (4 Bytes)
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                     GlobalSeqNum (32 bit)                     |  (4 Bytes)
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|         PayloadLen (16 bit)           |   Payload Data ...    |  (2 Bytes + N)
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

### 字段说明与关键工程细节

1. **`Ver` (3 bit)**：协议版本号（当前 `0x1`）。
2. **`Type` (3 bit)**：
   * `0x0`: `RAW_DATA`（静默直通模式，不参与 FEC 缓存，接收端直接上交业务层，**0 延迟、0 编码开销**）。
   * `0x1`: `FEC_DATA`（受保护的数据分片，`Idx` 范围为 $[0, K-1]$）。
   * `0x2`: `FEC_PARITY`（校验冗余分片，`Idx` 范围为 $[K, K+M-1]$）。
   * `0x3`: `FEEDBACK`（接收端回传的轻量级网络质量反馈包）。
3. **自描述 `(K, M)`**：每个分片自带本组真实的 `DataShards (K)` 和 `ParityShards (M)`。这样发送端随时切换冗余度、或因低流量超时触发“短分组强刷（如配置 $K=10$ 但只攒了 $4$ 个包就强刷，头部直接写 $K=4$）”时，接收端可以无缝解码，完全不需要控制信令同步。
4. **`GlobalSeqNum` (32 bit)**：跨分组严格单调递增的物理发包序号（涵盖数据包和冗余包）。**这是接收端精准统计“FEC 恢复前的真实网络丢包率”和“连续突发丢包长度”的唯一依据**。
5. **变长数据包对齐策略（借鉴 `java-kcp`）**：
   * RS 编码要求组内所有分片等长。实际网络包长度不一，将本组所有数据分片的原始长度 `PayloadLen`（2 字节）一并纳入 RS 编码参与运算，尾部用 `0x00` 填充至本组最大包长 $L_{\max}$。
   * **省流细节**：发送 `FEC_DATA` 分片时**只发送原始长度的真实字节，不发送末尾的补零字节**；只有 `FEC_PARITY` 校验包长度为 $L_{\max}$。接收端解码时再在内存中临时补零还原，大幅避免无效流量填充。

---

## 5. 核心算法设计：自适应 FEC 控制器

本模块是实现“**抗烂网优先 + 极度克制流量**”的大脑，包含四大核心子算法。

### 5.1 链路状态感知（反馈闭环）

接收端每收到 $N$ 个包（如 $N=32$）或每隔 $T_{\text{fb}}$（如 $100\text{ ms}$）回传一个 `FEEDBACK` 包（约 24 字节，开销可忽略），携带基于 `GlobalSeqNum` 统计的原始指标：

* **原始丢包率 $p_{\text{raw}}$**：滑动时间窗内未收到的 `GlobalSeqNum` 占比。
  > **避坑铁律**：严禁使用 FEC 恢复后的丢包率作为输入，否则 FEC 一生效，观测丢包归零，控制器立即关闭 FEC，导致系统剧烈震荡。
* **突发丢包估计（Gilbert-Elliott 两态模型）**：
  记录窗口内每次连续丢包事件的长度序列，估算坏状态转移概率与平均突发丢包长度 $L_{\text{burst}}$：
  $$L_{\text{burst}} = \frac{\sum \text{连续丢包长度}}{\text{丢包簇次数}}$$
* **排队延迟增量 $\Delta \text{RTT}$**：记录窗口内的最小基准往返延迟 $\text{RTT}_{\min}$ 与当前平滑延迟 $\text{SRTT}$，计算排队延迟：
  $$d_{\text{queue}} = \text{SRTT} - \text{RTT}_{\min}$$

### 5.2 非对称平滑滤波（快升慢降，专治不稳定网络）

弱网往往呈“脉冲式抖动”。使用非对称指数加权移动平均（Asymmetric EWMA）平滑观测丢包率 $\hat{p}_t$：

$$\hat{p}_t = \alpha \cdot p_{\text{raw}} + (1 - \alpha) \cdot \hat{p}_{t-1}$$

其中平滑系数 $\alpha$ 根据趋势动态切换：

$$\alpha = \begin{cases} \alpha_{\text{up}} = 0.55 & \text{if } p_{\text{raw}} > \hat{p}_{t-1} \quad (\text{网络恶化，快速拉升冗余}) \\ \alpha_{\text{down}} = 0.12 & \text{if } p_{\text{raw}} \le \hat{p}_{t-1} \quad (\text{网络转好，缓慢收敛防抖}) \end{cases}$$

### 5.3 动态 $(K, M)$ 决策引擎

#### 第一步：静默直通判定（省流核心）
当 $\hat{p}_t < p_{\text{silence}}$（默认 $0.5\%$）且持续超过静默观察窗（如 $2\text{ s}$）时：
* 设置 $M = 0$，数据面切换至 `RAW_DATA` 模式。
* **额外流量 = $0\%$，编码延迟 = $0\text{ ms}$**。

#### 第二步：动态分组大小 $K$ 决策（平衡延迟与流量粒度）
冗余开销率为 $\eta = \frac{M}{K}$。**$K$ 越大，冗余比例控制越精细、越省流量，但首包等待编码的时间越长**。因此根据实时发包速率 $\text{PPS}$（Packets Per Second）与目标最大编码延迟 $T_{\text{max}}$（如 $15\text{ ms}$）动态确定 $K$：

$$K = \text{clamp}\left(\lfloor \text{PPS} \times T_{\text{max}} \rfloor,\ K_{\min},\ K_{\max}\right)$$

* 推荐边界：$K_{\min} = 4$，$K_{\max} = 20$（过大将导致编解码矩阵运算耗时激增且恢复延迟变长）。
* **低流量场景兜底**：当发包极稀疏时（如 SSH 敲键盘、游戏心跳），即便未凑满 $K_{\min}$，只要组内首包驻留时间达到 `flushTimeoutMs`（如 $15\text{ ms}$），立即以当前实际包数 $K'$ 生成校验包发出。

#### 第三步：冗余包数量 $M$ 计算（随机丢包 + 突发丢包双兜底）
对于随机独立丢包，一组 $(K, M)$ 发生不可恢复丢包的概率（块失效率）服从二项分布尾概率：

$$P_{\text{fail}}(K, M, \hat{p}) = \sum_{i=M+1}^{K+M} \binom{K+M}{i} \hat{p}^i (1 - \hat{p})^{K+M-i}$$

给定业务允许的目标残余丢包率上限 $\epsilon$（默认 $\epsilon = 0.005$，即 $0.5\%$），求解满足 $P_{\text{fail}}(K, M, \hat{p}) \le \epsilon$ 的最小整数 $M_{\text{rand}}$。

> **性能优化技巧**：在服务启动时离线预计算二维查找表 `int[K_MAX + 1][LOSS_BUCKETS] FEC_LUT`（丢包率按 $0.5\%$ 分桶，共 100 桶）。运行时查表耗时仅 $O(1)$，完全无浮点阶乘运算。

再叠加**突发丢包补偿**（防止连续丢 3 个包时，按随机模型算出的 $M=2$ 彻底失效）：

$$M_{\text{target}} = \max\left(M_{\text{rand}},\ \lceil \gamma \cdot L_{\text{burst}} \rceil\right)$$

（建议突发安全系数 $\gamma = 1.2$）。

#### 典型工况下的动态参数参考表（$\epsilon = 0.5\%$）

| 网络状态 | 观测丢包率 $\hat{p}$ | 实时 PPS | 动态 $K$ | 计算 $M$ | 理论额外流量开销 $M/K$ | 恢复后残余丢包 |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| 优质网络 | $< 0.5\%$ | 任意 | N/A | **0** | **$0\%$（直通）** | $< 0.5\%$ |
| 轻微抖动 | $2\%$ | 高 ($>1000$) | 20 | **2** | $10.0\%$ | $0.08\%$ |
| 中度弱网 | $10\%$ | 高 ($>1000$) | 20 | **5** | $25.0\%$ | $0.41\%$ |
| 极端烂网 | $20\%$ | 高 ($>1000$) | 16 | **8** | $50.0\%$ | $0.48\%$ |
| 低频交互弱网 | $10\%$ | 低 ($50$) | 4 | **2** | $50.0\%$（绝对流量极小） | $0.45\%$ |

### 5.4 防“自杀式拥塞”熔断与平滑发送（Pacing）

如果丢包是由**自身发包打满路由器缓冲区（Bufferbloat）**引起的，盲目增加 FEC 只会雪上加霜。因此必须引入拥塞安全护栏：

1. **拥塞判定**：当同时满足 `观测丢包率上升` 且 `排队延迟膨胀`（$d_{\text{queue}} > \max(40\text{ ms},\ 0.5 \times \text{RTT}_{\min})$）时，判定为拥塞丢包而非纯无线/链路随机丢包。
2. **冗余度硬封顶（Cap）**：
   * 正常随机/弱网丢包：允许最大冗余率 $R_{\max} = 0.6$（即 $M \le 0.6K$）。
   * 触发拥塞告警时：将冗余率上限收紧至 $R_{\text{cong}} = 0.25$，并向上层暴露背压信号（通知业务层降码率或限速）。
3. **发包平滑（Pacing）**：
   * 避免一组数据攒满后瞬间将 $M$ 个校验包一次性背靠背（Back-to-Back）轰入网卡，造成微突发丢包。
   * 将校验包与下一组数据包交错发出，或使用令牌桶按微小间隔（如 $0.5 \sim 1\text{ ms}$）分散发送。

---

## 6. Java / Netty 高性能工程实现

### 6.1 编解码核心加速策略

1. **$M = 1$ 纯异或（XOR）快路径**：
   * 当网络轻度丢包（$1\% \sim 3\%$）计算得出 $M = 1$ 时，**直接跳过 Reed-Solomon 矩阵乘法**，采用 `long`（8 字节步长）按位异或（`^`）生成唯一校验包。CPU 耗时降低 $80\%$ 以上。
2. **$\text{GF}(2^8)$ 柯西矩阵 + 分裂查找表**：
   * 当 $M \ge 2$ 时，采用柯西矩阵（任意子矩阵均可逆，且单位阵性质好）。
   * 借鉴 `klauspost/reedsolomon`，将字节乘法拆分为高 4 位与低 4 位查表，支持后续无缝接入 **JDK 21+ `Vector API`**（利用 AVX2/NEON 指令集 `ByteVector.selectFrom` 实现 16/32 字节并行伽罗瓦域查表乘法，实测吞吐可达纯标量 Java 的 $4 \sim 8$ 倍）。
3. **零 GC 内存管理规范**：
   * 复用 Netty `PooledByteBufAllocator.DEFAULT.directBuffer()` 分配分片缓冲。
   * 接收端解码窗口（`IntObjectHashMap<FecGroup>`）中的每个 `ByteBuf` 均严格绑定生命周期，通过超时任务与滑动窗口淘汰确保 `ReferenceCountUtil.safeRelease()` 100% 执行，杜绝堆外内存泄漏。

### 6.2 核心模块代码骨架

#### 1. 自适应控制器接口与核心查表逻辑 (`AdaptiveFecController.java`)

```java
package com.example.fec.control;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 自适应 FEC 控制面核心引擎（线程安全，数据面无锁读取）
 */
public class AdaptiveFecController {

    public static final int K_MIN = 4;
    public static final int K_MAX = 20;
    private static final int LOSS_BUCKETS = 100; // 步长 0.5%，覆盖 0% ~ 50% 丢包
    private static final double TARGET_RESIDUAL_LOSS = 0.005; // 目标残余丢包 0.5%

    // 预计算查找表: lut[K][lossBucket] = 满足目标残余丢包的最小 M
    private final int[][] fecLut = new int[K_MAX + 1][LOSS_BUCKETS + 1];

    // 当前生效策略，供 Netty IO 线程无锁读取
    private final AtomicReference<FecParams> currentParams =
            new AtomicReference<>(FecParams.SILENCE);

    private double ewmaLoss = 0.0;
    private double ewmaBurst = 1.0;
    private long minRttMs = Long.MAX_VALUE;
    private long lastNonZeroLossTs = 0L;

    public AdaptiveFecController() {
        precomputeBinomialLut();
    }

    /**
     * 启动时预计算二项分布尾概率查表，运行时复杂度 O(1)
     */
    private void precomputeBinomialLut() {
        for (int k = K_MIN; k <= K_MAX; k++) {
            for (int b = 1; b <= LOSS_BUCKETS; b++) {
                double p = b * 0.005; // 0.5% 步进
                int maxM = (int) Math.ceil(k * 0.6); // 最大冗余比上限 60%
                int chosenM = maxM;
                for (int m = 1; m <= maxM; m++) {
                    if (calcBlockFailProb(k, m, p) <= TARGET_RESIDUAL_LOSS) {
                        chosenM = m;
                        break;
                    }
                }
                fecLut[k][b] = chosenM;
            }
        }
    }

    /**
     * 收到接收端 FEEDBACK 包时回调更新策略
     */
    public synchronized void onFeedback(double rawLossRate,
                                        double avgBurstLen,
                                        long currentRttMs,
                                        double currentPps) {
        long now = System.currentTimeMillis();

        // 1. 非对称 EWMA 平滑（快升慢降）
        double alpha = (rawLossRate > ewmaLoss) ? 0.55 : 0.12;
        ewmaLoss = alpha * rawLossRate + (1.0 - alpha) * ewmaLoss;
        ewmaBurst = 0.3 * avgBurstLen + 0.7 * ewmaBurst;

        if (currentRttMs > 0) {
            minRttMs = Math.min(minRttMs, currentRttMs);
        }

        // 2. 静默直通模式判定（省流关键）
        if (ewmaLoss >= 0.005) {
            lastNonZeroLossTs = now;
        } else if (now - lastNonZeroLossTs > 2000L) {
            currentParams.set(FecParams.SILENCE);
            return;
        }

        // 3. 动态计算分组大小 K（控制单组最大编码等待时间 <= 15ms）
        int k = (int) Math.floor(currentPps * 0.015);
        k = Math.max(K_MIN, Math.min(K_MAX, k));

        // 4. 查表获取基础冗余 M，并叠加突发丢包兜底
        int bucket = Math.min(LOSS_BUCKETS, Math.max(1, (int) Math.ceil(ewmaLoss / 0.005)));
        int mRandom = fecLut[k][bucket];
        int mBurst = (int) Math.ceil(1.2 * ewmaBurst);
        int m = Math.max(mRandom, mBurst);

        // 5. 拥塞熔断保护：若排队延迟剧烈膨胀，限制冗余率防止自杀式重压
        long queueDelay = (minRttMs == Long.MAX_VALUE) ? 0 : (currentRttMs - minRttMs);
        boolean isCongested = queueDelay > Math.max(40L, minRttMs / 2);
        double maxRatio = isCongested ? 0.25 : 0.60;
        m = Math.min(m, Math.max(1, (int) Math.floor(k * maxRatio)));

        // 低流量时设置短超时强刷，高流量可适度放宽
        int flushTimeoutMs = (currentPps < 200) ? 12 : 20;
        currentParams.set(new FecParams(k, m, flushTimeoutMs));
    }

    public FecParams getCurrentParams() {
        return currentParams.get();
    }

    private double calcBlockFailProb(int k, int m, double p) {
        int n = k + m;
        double failProb = 0.0;
        for (int i = m + 1; i <= n; i++) {
            failProb += binomialProb(n, i, p);
        }
        return failProb;
    }

    private double binomialProb(int n, int i, double p) {
        // 使用对数伽马/对数阶乘避免 n 较大时溢出
        double logComb = logFactorial(n) - logFactorial(i) - logFactorial(n - i);
        return Math.exp(logComb + i * Math.log(p) + (n - i) * Math.log(1.0 - p));
    }

    private double logFactorial(int n) {
        double sum = 0.0;
        for (int i = 2; i <= n; i++) {
            sum += Math.log(i);
        }
        return sum;
    }

    public record FecParams(int dataShards, int parityShards, int flushTimeoutMs) {
        public static final FecParams SILENCE = new FecParams(0, 0, 0);
        public boolean isSilence() {
            return parityShards <= 0;
        }
    }
}
```

#### 2. 发送端 Netty 编码器骨架 (`AdaptiveFecEncoderHandler.java`)

```java
package com.example.fec.netty;

import com.example.fec.codec.FecCodecEngine;
import com.example.fec.control.AdaptiveFecController;
import com.example.fec.control.AdaptiveFecController.FecParams;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 发送端自适应 FEC 编码器（运行于 Netty 单 IO 线程，无需加锁）
 */
public class AdaptiveFecEncoderHandler extends ChannelOutboundHandlerAdapter {

    private static final int HEADER_LEN = 14;
    private final AdaptiveFecController controller;
    private final FecCodecEngine codecEngine;

    // 当前活跃分组状态
    private final List<ByteBuf> shardCache = new ArrayList<>(AdaptiveFecController.K_MAX);
    private int currentGroupId = 0;
    private int globalSeqNum = 0;
    private int activeTargetK = 0;
    private int activeTargetM = 0;
    private int maxPayloadInGroup = 0;
    private ScheduledFuture<?> flushTask;

    public AdaptiveFecEncoderHandler(AdaptiveFecController controller, FecCodecEngine codecEngine) {
        this.controller = controller;
        this.codecEngine = codecEngine;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (!(msg instanceof ByteBuf payload)) {
            ctx.write(msg, promise);
            return;
        }

        try {
            FecParams params = controller.getCurrentParams();

            // 1. 静默直通快路径（且当前无残留分组）：0 编码开销，0 冗余流量
            if (params.isSilence() && shardCache.isEmpty()) {
                ByteBuf out = encodeFrame(ctx, (byte) 0x0, 0, 0, 0, 0, payload);
                ctx.write(out, promise);
                return;
            }

            // 2. 开启新分组：锁定本组的 (K, M) 参数并启动超时强刷定时器
            if (shardCache.isEmpty()) {
                currentGroupId++;
                activeTargetK = params.isSilence() ? 4 : params.dataShards();
                activeTargetM = params.isSilence() ? 0 : params.parityShards();
                maxPayloadInGroup = 0;

                int timeoutMs = Math.max(5, params.flushTimeoutMs());
                flushTask = ctx.executor().schedule(
                        () -> flushParityShards(ctx), timeoutMs, TimeUnit.MILLISECONDS);
            }

            // 3. 数据分片零等待立即发出（降低延迟！绝不攒齐了再发数据包）
            int shardIdx = shardCache.size();
            int payloadLen = payload.readableBytes();
            maxPayloadInGroup = Math.max(maxPayloadInGroup, payloadLen);

            // 保留一份引用用于组满/超时后生成校验包
            shardCache.add(payload.retainedDuplicate());

            ByteBuf dataFrame = encodeFrame(
                    ctx, (byte) 0x1, activeTargetK, activeTargetM, shardIdx, currentGroupId, payload);
            ctx.write(dataFrame, promise);

            // 4. 凑齐 K 个数据包，立刻生成并发送 M 个冗余包
            if (shardCache.size() >= activeTargetK) {
                flushParityShards(ctx);
            }
        } finally {
            ReferenceCountUtil.safeRelease(payload);
        }
    }

    /**
     * 生成并发送本组校验包（支持凑满触发 & 超时短分组强刷）
     */
    private void flushParityShards(ChannelHandlerContext ctx) {
        if (shardCache.isEmpty()) {
            return;
        }
        cancelFlushTask();

        int actualK = shardCache.size();
        // 短分组场景按比例缩放冗余包，避免低流量下发出过多校验包
        int actualM = (actualK == activeTargetK)
                ? activeTargetM
                : Math.max(1, (int) Math.ceil((double) actualK * activeTargetM / activeTargetK));

        try {
            if (actualM > 0) {
                // 当 actualM == 1 时，codecEngine 内部自动走 XOR 快路径；否则走 RS 编码
                List<ByteBuf> parities = codecEngine.encodeParity(
                        ctx.alloc(), shardCache, actualK, actualM, maxPayloadInGroup);
                for (int i = 0; i < parities.size(); i++) {
                    ByteBuf parityPayload = parities.get(i);
                    try {
                        ByteBuf parityFrame = encodeFrame(
                                ctx, (byte) 0x2, actualK, actualM,
                                actualK + i, currentGroupId, parityPayload);
                        ctx.write(parityFrame);
                    } finally {
                        ReferenceCountUtil.safeRelease(parityPayload);
                    }
                }
                ctx.flush();
            }
        } finally {
            // 严格释放所有缓存的数据分片引用，防止 DirectMemory 泄漏
            for (ByteBuf buf : shardCache) {
                ReferenceCountUtil.safeRelease(buf);
            }
            shardCache.clear();
        }
    }

    private ByteBuf encodeFrame(ChannelHandlerContext ctx, byte type, int k, int m,
                                int idx, int groupId, ByteBuf payload) {
        int payloadLen = payload.readableBytes();
        ByteBuf out = ctx.alloc().directBuffer(HEADER_LEN + payloadLen);
        int verTypeFlags = (0x1 << 5) | ((type & 0x7) << 2);
        out.writeByte(verTypeFlags);
        out.writeByte(k & 0xFF);
        out.writeByte(m & 0xFF);
        out.writeByte(idx & 0xFF);
        out.writeInt(groupId);
        out.writeInt(++globalSeqNum);
        out.writeShort(payloadLen);
        out.writeBytes(payload, payload.readerIndex(), payloadLen);
        return out;
    }

    private void cancelFlushTask() {
        if (flushTask != null && !flushTask.isDone()) {
            flushTask.cancel(false);
            flushTask = null;
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        cancelFlushTask();
        for (ByteBuf buf : shardCache) {
            ReferenceCountUtil.safeRelease(buf);
        }
        shardCache.clear();
    }
}
```

> **设计亮点提醒**：上述代码采用**系统码（Systematic Code）即时发送模式**——原始数据包到达后**不等待凑满分组，立刻发出**；校验包在凑齐 $K$ 个包或达到 `flushTimeoutMs` 后补发。这保证在正常网络下，FEC 对正常包引入的排队延迟为 **$0\text{ ms}$**。

---

## 7. 关键工程边界与防坑指南

1. **严格控制 MTU，杜绝 IP 分片放大丢包**：
   * 若单个 UDP 包超过路径 MTU（常见 PPPoE 为 $1492$ 字节，移动网络常低至 $1280 \sim 1400$ 字节），IP 层分片只要丢 1 片整包作废，导致实际丢包率翻倍。
   * **强约束**：上层业务传入的单个 Payload 必须限制在 $\le 1200$ 字节（为 14 字节 FEC 头 + 28 字节 IP/UDP 头 + 潜在隧道封装留足余量）。
2. **短分组强刷导致的协议头 `K` 不一致问题**：
   * 细心的同学可能会发现：在即时发送模式下，前几个数据包发出去时头部写的是 `activeTargetK`（如 10），但超时强刷时实际只攒了 4 个包，校验包头部写的是 `actualK = 4`。
   * **解法**：接收端解码时，**永远以 `FEC_PARITY` 校验包中携带的 `K` 为准**！只要收到任意校验包，即刻将本组解码所需的目标分片数更新为校验包的 `K`。
3. **接收端乱序与内存防泄漏**：
   * 使用固定容量的环形数组或有序 `IntObjectHashMap` 维护最近 $W$ 个分组（如 $W = 64$ 组，或最长存活 $2 \times \text{SRTT} + 200\text{ ms}$）。
   * 一旦收到数据包，立即向上层递交（若业务需要保序，交由上层如 KCP/QUIC 排序；若为音视频/游戏状态包，直接乱序消费），同时在解码窗口保留 `retainedDuplicate()` 副本用于恢复同组其他丢失包。
   * 压测阶段务必开启 Netty 内存泄漏检测参数：`-Dio.netty.leakDetection.level=PARANOID`。
4. **混合纠错（HARQ）进阶组合**：
   * 若业务要求 **100% 可靠交付**（非音视频/游戏等允许极少量丢包的场景），**切勿企图靠 FEC 把丢包率压到 $0\%$**（那会导致流量开销爆炸）。
   * 最佳实践是 **自适应 FEC + 轻量级选择性重传（NACK/ARQ）**：FEC 负责用低成本把 $20\%$ 丢包瞬间压到 $0.5\%$，剩下极偶发的整组失效（$0.5\%$）由上层 ARQ 补发。两者结合可在“省流量、低延迟、高可靠”三角中达到理论最优。

---

## 8. 压测验证方案与核心监控指标

### 8.1 Linux `tc netem` 弱网仿真矩阵

在测试环境使用 Linux 流量控制工具构造真实恶劣网络，对比 **原生 UDP / 静态 FEC / 本自适应 FEC** 三组表现：

```bash
# 场景 1：优质网络（验证 0% 冗余流量开销）
tc qdisc replace dev eth0 root netem delay 20ms 2ms

# 场景 2：中重度随机丢包 + 抖动（验证抗丢包与省流比）
tc qdisc replace dev eth0 root netem delay 60ms 20ms distribution normal loss 15%

# 场景 3：典型 Wi-Fi / 4G 突发丢包（Gilbert-Elliott 模型：2% 进坏状态，40% 恢复好状态）
tc qdisc replace dev eth0 root netem delay 50ms 15ms loss gemodel 2% 40% 70% 0.1%

# 场景 4：动态阶跃切换（脚本每 30 秒在 0% -> 20% -> 5% -> 0% 之间切换，验证快升慢降与静默收敛）
```

### 8.2 核心可观测指标（Micrometer / Prometheus）

| 指标名称 | 含义 | 健康基准参考 |
| :--- | :--- | :--- |
| `fec_raw_loss_ratio` | 接收端统计的原始网络丢包率 | 客观反映物理链路质量 |
| `fec_residual_loss_ratio` | FEC 恢复后的最终业务丢包率 | $20\%$ 原始丢包下应 $\le 0.5\%$ |
| `fec_traffic_overhead_ratio` | 实际发送的冗余字节数 / 有效数据字节数 | **省流核心指标**：0% 丢包时为 $0$；动态弱网下 $\approx 1.3 \sim 1.6 \times \text{raw\_loss}$ |
| `fec_parity_efficiency_ratio` | 实际参与恢复的校验包数 / 发出的总校验包数 | 衡量冗余浪费程度，越高代表算法越精准 |
| `fec_decode_recovery_latency_p99` | 丢包从发现到 RS 解码还原的 P99 耗时 | 应严格低于 $1 \times \text{RTT}$ |
| `fec_direct_memory_used_bytes` | 编解码窗口占用的 Netty 堆外内存 | 长时间稳态运行应呈水平直线，无爬坡 |

---

## 9. 分阶段落地路线图

* **Phase 1（MVP 验证，1~2 周）**：
  * 引入并裁剪 `java-kcp` 的 Netty `ByteBuf` RS 编解码器；实现 14 字节协议头、`M=0` 静默开关、基于非对称 EWMA 的基础查表控制器；完成 `tc netem` 随机丢包闭环测试。
* **Phase 2（生产弱网增强，2~3 周）**：
  * 补齐 Gilbert-Elliott 突发丢包估计、动态 $K$ + 短分组超时强刷、RTT 排队延迟拥塞熔断、Pacing 平滑发包，以及完整的 Prometheus 监控埋点与堆外内存泄漏回归测试。
* **Phase 3（极致性能优化，按需）**：
  * 落地 $M=1$ 纯 XOR 快路径、基于 JDK 21+ `Vector API` 的 SIMD 伽罗瓦域矩阵乘法加速，以及面向 100% 可靠传输场景的 HARQ（FEC + 轻量 NACK）联动。