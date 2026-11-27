# rxlib 网络传输与协议历史测试 / 基准记录

> 本文档按时间顺序汇总 `rxlib` 网络与传输层历次基准测试、A/B 对照实验（含被淘汰方案的数据）及双 JDK 回归结果，用于后续优化的横向基线对比。

---

## 1. 2026-11-24 · `weak` 尾包探测与 `game` 冷启动/跳载基准（Linux 1 vCPU / 768 MiB cgroup）

- **基线提交**：`a22cd28a` / `7a46f427`
- **环境**：Linux 1 vCPU / 768 MiB（no-swap cgroup），独立 Client / Server JVM，对比 `kcptun`（`10:1`、fast、AES、默认压缩）；统计 `tc` 双向 IPv4 字节（扣除 14 B L2 头）。

### 1.1 早期对照与 `zlib` 微基准
- **旧版 `daily` 20 PPS / 5% 名义丢包模拟**：随机丢包恢复 `33 / 83`；突发丢包（平均连续 3 包，反向无丢包）恢复 `1 / 63`。
- **串行 TCP Echo（100 次，150 ms RTT / 5% 双向丢包）**：`weak` `30,551 B`（P99 `792.2 ms`，Max `1,354 ms`，较 kcptun 省流 ~72%）；`daily` `29,346 B`（P99 `644.5 ms`）；`kcptun 10:1` `109,172 B`（P99 `510.1 ms`，Max `694.1 ms`）。
- **1000 PPS UDP（2,000 请求，80 ms RTT / 5% 双向丢包）**：冷启动 `game` `1,561/2,000 (78.05%)` / P99 `213.7 ms` vs `plain` `1,803/2,000 (90.15%)` / P99 `184.9 ms`；热会话 `game` `1,875/2,000 (93.75%)` vs `plain` `1,800/2,000 (90.0%)`。
- **`zlib 6` vs `zlib 3` 微基准**：长文档 `-7.8%` 字节 / `+71%` CPU；短 JSON `-5.1%` 字节 / `+10%` CPU；合成游戏短包 `-0.6%` 字节 / `+21%` CPU；JAR `-0.14%` 字节（因此不默认升级到 `zlib 6`）。

### 1.2 `weak` 尾包探测 A/B（100 串行 SOCKS TCP Echo，164 B/方向，150 ms RTT，5% 随机丢包/方向）

| Seed | 方案 | 双向字节 | P95 / P99 / Max (ms) | Server JVM CPU | cgroup 总 CPU |
| --- | --- | ---: | --- | ---: | ---: |
| 718 | 旧 `weak` | 27,259 B | 547.2 / 1168.3 / 1320.9 | 0.55 s | 1.17 s |
| 718 | **`weak` UDP 探测** | **38,968 B** | **495.5 / 676.5 / 928.0** | 0.63 s | 1.34 s |
| 718 | `weak` TCP 探测 | 36,708 B | 448.1 / 567.8 / 1784.5 | 0.84 s | 2.77 s |
| 718 | `kcptun 10:1` | 110,036 B | 339.3 / 694.9 / 874.9 | 0.38 s | 1.68 s |
| 2063 | 旧 `weak` | 28,526 B | 514.3 / 640.6 / 932.9 | 0.74 s | 1.53 s |
| 2063 | **`weak` UDP 探测** | **35,387 B** | **303.2 / 341.7 / 391.7** | 0.60 s | 1.23 s |
| 2063 | `weak` TCP 探测 | 35,359 B | 397.2 / 522.1 / 624.0 | 0.76 s | 1.76 s |
| 2063 | `kcptun 10:1` | 108,960 B | 334.9 / 576.8 / 2235.2 | 0.43 s | 1.78 s |
| 3491 | 旧 `weak` | 31,907 B | 524.2 / 580.2 / 690.7 | 0.49 s | 1.13 s |
| 3491 | **`weak` UDP 探测** | **38,083 B** | **349.6 / 411.5 / 439.2** | 0.55 s | 1.29 s |
| 3491 | `weak` TCP 探测 | 37,453 B | 347.3 / 498.2 / 638.4 | 0.56 s | 1.35 s |
| 3491 | `kcptun 10:1` | 108,620 B | 333.8 / 581.9 / 1080.9 | 0.27 s | 1.71 s |

- **结论**：`weak` UDP 探测三组种子全过 `< 40,000 B` 且 `<= 1.2x kcptun` P99/Max（流量省 **~65.6%**、cgroup CPU 降 **~25.3%**）；Server RSS 峰值 `135.5–142.0 MiB`，Netty Pooled Direct `4 MiB`，GC 增量 `0 ms`。移包后 Java 21 复测（seed 718）：`36,128 B`，P99 `492.4 ms`，Max `682.0 ms`。
- **突发丢包 + 并发 128 KiB 文件流**（80 ms RTT，Gilbert-Elliott seed 626：2% 进坏态 / 30% 出坏态 / 95% 坏态丢包 / 0.1% 好态丢包）：`weak` `189,159 B`（短流 P99 `460.7 ms`，Max `621.3 ms`）vs `kcptun 10:1` `281,895 B`（P99 `413.1 ms`，Max `631.9 ms`）。

### 1.3 `game` 冷启动与跳载（2,000 UDP 请求，1000 PPS，164 B/方向，MTU 1300，40 ms + 10 ms 抖动，5% 丢包/方向）
- **冷 JVM 新会话**：seed 626 `plain` `1803 / 175.8 ms` vs `game` `1826 / 255.9 ms / 1.21 MB / 0.278 s EL CPU`；seed 718 `plain` `1733 / 199.7 ms` vs `game` `1830 / 194.7 ms / 1.19 MB / 0.272 s`。
- **热 JVM 新会话**：seed 626 `plain` `1801 / 113.6 ms` vs `game` `1885 / 107.7 ms`；seed 718 `plain` `1806 / 99.9 ms` vs `game` `1891 / 102.1 ms`。
- **热会话**：seed 626 `plain` `1801 / 98.6 ms` vs `game` `1904 / 96.2 ms`；seed 718 `plain` `1806 / 100.9 ms` vs `game` `1902 / 98.2 ms`。
- **低→高 PPS 跳载（20→1000 PPS）**：seed 626 `plain` `1796 / 97.9 ms` vs `game` `1906 / 96.5 ms`；seed 718 `plain` `1800 / 97.6 ms` vs `game` `1888 / 105.9 ms`。
- **40 ms RTT / 1% 丢包补充（20 / 200 / 300 PPS）**：`game` `158/160`、`398/400`、`598/600` vs `plain` `155/160`、`393/400`、`591/600`；`game` 堆外分配器 `512 KiB`，0 重复交付，0 OOM。

---

## 2. 2026-11-25 · Linux NetNS 对照与 Windows 热点优化基准

### 2.1 Linux Network Namespace（1 vCPU / 768 MiB 容器，基线 `2ade72bc`）
- **被淘汰方案：P1-01 `weak` 独立 Repair Wire**（100 串行 Echo，164 B/方向）：
  - 150 ms / 5%：seed 718 `36,193 B / 859.3 / 953.9 ms -> 36,513 B / 458.0 / 639.2 ms`；seed 2063 `35,912 / 388.4 / 685.7 -> 33,566 / 370.9 / 717.2`；seed 3491 `38,374 / 395.5 / 425.7 -> 39,059 / 439.7 / 531.2`。
  - 80 ms 突发 + 128 KiB 并发（seed 626）：`188,915 B / 1035.8 / 1153.4 ms -> 179,762 B / 346.6 / 500.5 ms`。
  - **结论**：2/3 Echo 种子流量上升且 seed 3491 延迟恶化，**未合入**。
- **P1-02 TCP 回落省略应用 ACK**（30 ms RTT，4 MiB 不可压缩下载 + 恢复 Echo，3 次中位数）：
  - 双向阻断 UDP：应用 ACK `92 -> 33`，ACK 载荷 `460 -> 165 B`，双向 IP `4,235,449 -> 4,229,960 B`。
  - 下行阻断 UDP：应用 ACK `100 -> 2`，ACK 载荷 `500 -> 10 B`，双向 IP `4,234,583 -> 4,240,905 B`。
- **被淘汰方案：P1-03 `game` 进程级启动预热**（256 次加解密预热，耗时 `1.54–3.26 s` / `0.72–1.15 CPU s`）：冷 JVM seed 626 改善显著（P99 `295.7 -> 179.3 ms`），但 seed 718 P99 反升（`154.2 -> 273.1 ms`）、热态 RSS/GC 上升，**未采用**。
- **OpenTTD 15.3 真实 TCP 客户端经 SOCKS**：seed 626 加入耗时 `7.411 s -> 8.554 s`（双向 IP `45,737 -> 65,710 B`）；seed 718 `8.185 s -> 6.364 s`（`47,882 -> 55,724 B`）。
- **JDK 8 回归**：`219` 运行 / `217` 通过 / `2` 跳过 / `0` 失败。

### 2.2 Windows 热点优化与加密基准（Temurin JDK `21.0.12.1` & `1.8.0_504`，提交 `ded9bbb9` ~ `2f6bed70`）
- **1 小时 UDP 阻断与空闲探测退避**（65 B/探包）：
  - 空闲：探包 `1,800 -> 1`（控制流量 `117,000 -> 65 B`，**-99.94%**），定时器唤醒 `1,800 -> 2`，下次写后 `30 ms` 恢复。
  - 6 s / 20 s / 1 s 周期写入：探包 `1,800 -> 130–132`（**-92.7%~92.8%**），恢复 `30–660 ms`。
- **高 BDP 流与被淘汰的 `daily-w256`**（150 ms RTT，seed 718/2063/3491 均值）：
  - 128 KiB 单写溢出预检 vs 32 KiB 循环中排空（16 MiB @ 8 Mbps）：`17,509,798 -> 17,298,952 B`（**-211 KiB**）。
  - `daily-w256` vs `daily-w64`（16 MiB @ 8 Mbps）：0%/1%/5% 丢包流量分别增加 `+318 KiB` / `+918 KiB (+5.2%)` / `+979 KiB (+5.3%)`，**淘汰**。
- **有界 FEC 累加器复用与 Skip-XOR**（`10,000 × 1,200 B`，seed 17/43/89 均值，`PARANOID` 0 泄漏）：
  - 20 PPS / 1% 丢包：分配 `4,228 -> 49`（**-98.8%**），KiB `17,439 -> 12,539`，CPU `31 -> 20 ms`。
  - 20 PPS / 5% 丢包：分配 `6,381 -> 182`（**-97.1%**），CPU `31 -> 26 ms`，恢复 `536 / 1,510`。
  - 50 PPS / 1% 与 200 PPS / 1%：分配分别降 **-96.0%** / **-90.1%**。
  - **被淘汰方案**（无 `parityCredit` 直发 `DATA`）：60 ms 间隔 + 16 包突发恢复率从 `90.7% (272/300)` 暴跌至 `72.3% (217/300)`，**拒绝**。
- **`game` 滞回降档 / `weak` 低 PPS 微突发 / `zlib 1/3/6`**：
  - `game` 15% 突发→3x→2% 轻丢包：平均副本降至 `2.05x / 2.31x / 2.01x`（20/200/300 PPS），送达 `99.32%–99.37%`；稳态 5% 保持 `99.57%–99.62%`。
  - `weak` 1/2/5 PPS 均匀流 `0` 额外上报；成对微突发发 `19/24/29` parity 包恢复 `4/7/6` 分片。
  - 4 MiB TCP `zlib 1 / 3 / 6`：JSON 压缩比 `0.9% / 0.9% / 0.5%`（耗时 `3.91 / 2.60–3.91 / 7.81–9.11 ms/MiB`）；随机不可压缩流三档均为 `100.1%`（`35.16–37.76 ms/MiB`）。
- **Shadowsocks AEAD 加密优化（`2f6bed70`）**：

| 场景 | JDK 21 优化前 | JDK 21 优化后 | CPU 变化 | JDK 8 优化后 |
| --- | --- | --- | --- | --- |
| TCP `chacha20-ietf-poly1305` 1,400 B | 32.18 ms/MiB (23.3 kpps) | **15.21 ms/MiB (49.2 kpps)** | **-52.7%** | 21.07 ms/MiB (35.6 kpps) |
| TCP `chacha20-ietf-poly1305` 16,383 B | 28.75 ms/MiB (2.2 kpps) | **11.00 ms/MiB (5.8 kpps)** | **-61.7%** | 15.50 ms/MiB (4.1 kpps) |
| UDP `chacha20-ietf-poly1305` 256 B | 76.80 ms/MiB (53.3 kpps) | **54.40 ms/MiB (75.3 kpps)** | **-29.2%** | 70.40 ms/MiB (58.2 kpps) |
| UDP `chacha20-ietf-poly1305` 1,200 B | 34.82 ms/MiB (25.1 kpps) | **17.75 ms/MiB (49.2 kpps)** | **-49.0%** | 25.26 ms/MiB (34.6 kpps) |
| `SaltHistory.newCrypto` (50k 次) | 1,562.5 ns/conn | **~0–312.5 ns/conn (0 MD5)** | — | 312.5 ns/conn |
| TCP `aes-256-gcm` 1,400 / 16,383 B | 12.87 / 5.75 ms/MiB | 11.70 / 6.50 ms/MiB | — | 13.46 / 7.75 ms/MiB |

- **双 JDK 回归（`PARANOID` 0 泄漏）**：每版 JDK 各 `101` 用例（RSS 50 + SS AEAD/Python 互操作 51），`100` 通过、`1` 跳过（Windows）、`0` 失败。

---

## 3. 2026-11-26 · Netty `4.2.21.Final` 升级与双 JDK 回归

- **提交**：`f95c1199` / `1517f6cf` / `bf635b9d` / `7845b55b`
- **依赖校验**：`mvn -pl rxlib dependency:tree -Dincludes=io.netty` 确认全部 `40` 个 `io.netty` 构件（含 `linux-x86_64` / `aarch_64` / `riscv64` Epoll）统一为 `4.2.21.Final`。
- **双 JDK 回归（JDK `21.0.12.1` & `1.8.0_504`，`PARANOID` 0 泄漏）**：
  - RSS + SOCKS5 + `RssTest`（含 `udpHop`/`udpMtu`/`ssCipher` 新用例）：`98` 运行 / `96` 通过 / `2` 跳过（Linux-only）。
  - SS AEAD + `SaltHistory` + 36 组合半关闭矩阵 + Python `cryptography` 互操作：`55` 运行 / `55` 通过。
  - 合计每版 JDK **`153` 用例（151 通过，2 跳过，0 失败，`LEAK: 0`）**。

---

## 4. 2026-11-27 · Transport Core 整合与高丢包 VPS 实测（`182.255.85.247`）

### 4.1 单元测试与双 JDK 回归（提交 `68e7f887`、`8062526c`）
- `TransportCoreTest`：`6/6` 通过（`PARANOID LEAK: 0`：纯 TCP `udpPort()==0`、daily 自适应 UDP、`FRAME_READY` 屏障、背压/半关闭、鉴权拒绝、认证分片 + FEC）。
- DDNS / WoW / RSS 回归（`-DskipTests=false -Dio.netty.leakDetection.level=PARANOID`）：
  - JDK 21：`DdnsJobTest`(6/6) + `WowEndpointTest`(7/7) + `RssReviewFixesTest`(5/5) + `RssTest`(81/81, 2 skip) = **99 run, 0 fail, `LEAK: 0`**。
  - JDK 8：`DdnsJobTest`(6, 1 skip) + `WowEndpointTest`(7/7) + `RssReviewFixesTest`(5/5) = **0 fail, `LEAK: 0`**。

### 4.2 真实高丢包公网 VPS 实测（`182.255.85.247`）
- **环境**：Debian 12、`nftables v1.0.6`、1 vCPU / 960 MiB、公网实测 **10%–15% 丢包 / 181.8 ms RTT**，真实 Shadowsocks `aes-256-gcm` 入站。
- **负载**：两轮交错顺序（R1：`plain -> daily -> weak`，R2：`weak -> daily -> plain`）；每轮含 `512 KiB` 冷下载 + `512 KiB` 热下载 + `256 KiB` 上传 + `58 × 256 B` 交互 Echo + `150 × 256 B` 普通 UDP Echo。

| Profile | 轮次 | 冷/热下载 (KiB/s) | 上传 (KiB/s) | 持续交互 P50 / P95 / P99 (ms) | 全交互 P95 (ms) | 普通 UDP 送达 / P95 | 总流量 (TCP+UDP KiB) | 峰值堆外 / RSS |
| --- | :---: | --- | ---: | --- | ---: | --- | --- | --- |
| `plain` | R1 | 14.3 / 12.7 | 106.1 | 189.1 / 999.2 / 1791.3 | 1791.3 | 74.0% / 1739.8 ms | 1742.9 (1657+86) | 0.25–2.35 MiB / 313–470 MiB |
| `plain` | R2 | 15.2 / 18.4 | 133.1 | 188.1 / 1379.7 / 1814.8 | 1770.8 | 86.7% / 287.9 ms | 1684.8 (1589+96) | 同上 |
| `daily` | R1 | 13.1 / 17.7 | 114.6 | 180.0 / 844.2 / 1535.2 | 1768.3 | 86.7% / 687.2 ms | 1805.7 (1611+195) | 0.46–2.34 MiB / 331–469 MiB |
| `daily` | R2 | 18.7 / 16.4 | 149.2 | 190.2 / 773.7 / 1591.2 | 1782.6 | 87.3% / 283.3 ms | 1794.3 (1570+225) | 同上 |
| **`weak`** | R1 | 12.7 / 14.5 | 66.5 | **186.3 / 395.2 / 981.6** | **981.6** | **88.7% / 246.4 ms** | 2247.2 (1537+710) | **0.54–0.56 MiB / 388–422 MiB** |
| **`weak`** | R2 | 11.1 / 12.6 | 127.2 | **187.1 / 424.9 / 3864.3** | **988.2** | **80.0% / 192.0 ms** | 2029.8 (1632+398) | 同上 |

- **核心结论**：
  - `weak` 将持续交互 P95 压低 **~60%–65%**（vs `plain`）和 **~50%**（vs `daily`），全交互 P95 降 **~44%**；`rss.tcp.weak.probeAckedBeforeTcp` 命中 `5/5`（R1）与 `5/6`（R2），普通 UDP P95 最低（`192–246 ms`，FEC 恢复 5–6 包）。
  - 暴露出待调优项：`weak` 热 512 KiB 下载在突发窗口回落前 `AdaptiveUdpFec` 产出 `fecParityBytes = 0`，触发 TCP 补传，导致单流大下载总流量高 `+20%–29%` 且吞吐略低于 `daily`/`plain`（已记入 [计划文档](roadmap-plan.md)）。
  - 整机资源稳定：Netty 堆外峰值 `<= 2.4 MiB`，`VmRSS <= 470 MiB`，修复 `AdaptiveTcpHandler.close` 半关闭排空后 `0 EOFException`。

---

## 5. 2026-11-28 · Review P1/P2 全量落地、双 JDK 回归与 `182.255.85.247` 四档/限速实测

### 5.1 双 JDK `PARANOID` 回归与新增验证（提交 `8054ec22`..`d0b7bf3b`）
- **基线环境**：Windows 11；JDK `21.0.12.1` + Temurin JDK `1.8.0_504`；Netty `4.2.21.Final`；统一参数 `-DskipTests=false "-DargLine=-Dio.netty.leakDetection.level=PARANOID"`。
- **全量核心回归（26 个测试类，覆盖带宽限速、Geo 增量与代际一致、Nameserver 鉴权 RPC、Transport Core 双向 HMAC、RPC 非幂等断线保护、DNS 非地址 RR 缓存/EDNS/连接池、动态 PMTU/`CHUNK`、`AddressDiscovery`、`NodeRegistry` H2 冷存、`AdaptiveTcpStream`、`RssDatagramHandler`、RRP 等）**：
  - **JDK 21**（`bd4e5119`）：`359` 用例运行、`0` 失败、`0` 错误、`5` 既有跳过（Windows / Linux-only），`PARANOID LEAK: 0`（注：`DnsOptimizationTest.concurrentInterceptorQueries_shareSingleResolveTask` 批次偶发 1 次 200ms 调度抖动，单跑 `24/24` 通过）。
  - **JDK 8**（`bd4e5119`）：`359` 用例运行、`0` 失败、`0` 错误、`6` 跳过。
  - **补丁复测（`d0b7bf3b`，修复 `TcpBandwidthPacer` 在 `pauseChannel` EOF 关闭后停止排空及跨 EventLoop 回调问题 + 补齐 `RrpIntegrationTest` `EmbeddedChannel` 回收）**：`BandwidthLimiterTest` + `SsUserBandwidthIntegrationTest`（含 `40 KiB/s` 160 KiB 下载/上传 + `useDedicatedCryptoGroup=true` 回归）+ `RrpIntegrationTest` = **`33/33` 通过，JDK 21 & JDK 8 均 `PARANOID LEAK: 0`**。

### 5.2 高丢包公网 VPS 实测（`182.255.85.247`，提交 `d0b7bf3b`）
- **环境与参数**：
  - **Server**：`182.255.85.247`（Debian 12 `6.1.0-31-amd64`，1 vCPU / `960 MiB` RAM，OpenJDK `21.0.13.1`，`-Xms64m -Xmx256m -XX:MaxDirectMemorySize=256m -Dapp.net.reactorThreadAmount=2 -Dapp.net.reusePortBindCount=2`）；实测确认 Linux Native **Epoll**（`epollEventLoopGroup`）+ **`SO_REUSEPORT=2`**（双监听 fd `138`/`139`）生效。
  - **Client**：Linux `6.8.0-124-generic`，OpenJDK `21.0.13.0.3`；隔离临时端口（不触碰线上 `6885`），实测前公网 ping 基线 **7.5% 丢包 / 172.5 ms 平均 RTT**（`168.1–185.1 ms`），测试后 **0% 丢包 / 171.3 ms 平均 RTT**（`167.3–186.1 ms`）。
  - **负载**：两轮交错 4-profile 矩阵（R1：`plain -> daily -> weak -> game`，R2：`game -> weak -> daily -> plain`），每轮 `512 KiB` 冷下载 + `512 KiB` 热下载 + `256 KiB` 上传 + `58 × 256 B` 交互 Echo + `150 × 256 B` UDP Echo；双向 IP 层字节由 VPS `nftables` `inet rxbench_acct` 精确计数。

| Profile | 轮次 | 冷/热下载 (KiB/s) | 上传 (KiB/s) | 持续交互 P50 / P95 / P99 (ms) | 交互错误 | 普通 UDP 送达 / P95 (ms) | 总流量 (TCP+UDP KiB) | Server CPU / VmRSS / 峰值直接内存 |
| --- | :---: | --- | ---: | --- | :---: | --- | --- | --- |
| `plain` | R1 | 187.2 / 559.9 | 97.9 | 179.3 / 198.6 / 905.5 | 0 | 80.0% / 2051.1 | 1470.2 (1395.6+74.6) | 23.9s / 311.7 MiB / 0.07% |
| `plain` | R2 | 264.1 / 120.1 | 147.9 | 183.8 / 199.0 / 918.5 | 0 | 100.0% / 177.6 | 1485.4 (1391.4+93.9) | 8.1s / 418.5 MiB / 0.12% |
| `daily` | R1 | 250.3 / 577.5 | 107.3 | 179.0 / 399.7 / 916.7 | 0 | 98.0% / 927.6 | 1613.0 (1371.8+241.2) | 17.5s / 318.6 MiB / 0.08% |
| `daily` | R2 | 257.4 / 526.3 | 158.4 | 197.4 / 206.3 / 922.3 | 0 | 94.7% / 188.3 | 1616.6 (1367.9+248.7) | 6.8s / 401.9 MiB / 0.12% |
| **`weak`** | R1 | 239.7 / **507.0** | **149.0** | **178.4 / 386.2 / 881.3** | **0** | 92.7% / **188.4** | **1614.6** (1360.4+254.2) | 13.9s / 352.1 MiB / 0.10% |
| **`weak`** | R2 | 264.5 / **517.4** | **153.0** | **184.0 / 188.8 / 875.5** | **0** | **100.0% / 186.5** | **1687.3** (1362.6+324.8) | 7.1s / 401.3 MiB / 0.12% |
| `game` | R1 | 245.7 / 428.1 | 75.7 | 185.1 / 190.4 / 923.3 | 0 | 98.0% / 189.9 | 2221.8 (1277.6+944.3) | 14.4s / 358.0 MiB / 0.11% |
| `game` | R2 | 275.7 / 398.4 | 103.2 | 180.6 / 366.7 / 1462.6 | 0 | 95.3% / 189.3 | 1833.3 (1259.8+573.4) | 8.9s / 398.7 MiB / 0.11% |

- **SS 用户共享双向带宽限速 + 热更新不重启监听实测（`ssPort=16900`，`160 KiB` 下载/上传 + 交互 + `40` UDP）**：
  - 监听 Socket inode 变更校验：`ino:300485317` / `ino:300485318`（`SO_REUSEPORT=2`）在 `conf.yml` 热更新前后完全一致（**`listener_unchanged = true`**）。
  - 限速 `40 KiB/s`（上/下行）：冷下载 `35.20 KiB/s`、热下载 `44.42 KiB/s`（含 `16 KiB` 初始突发）、上传 `42.37 KiB/s`、交互错误 `0`、UDP 送达 `100.0%`。
  - 热更新改为 `0/0`（不限速）后立即生效：冷下载 `103.85 KiB/s`、热下载 `307.55 KiB/s`、上传 `107.68 KiB/s`、交互错误 `0`、UDP 送达 `97.5%`。
- **核心结论**：
  - **`weak` 大流调优生效**：两轮平均总流量 `1651.0 KiB`（仅比 `plain` 均 `1477.8 KiB` 高 **`+11.7%`**，较 2026-11-27 调优前 `+24.8%` 减半，且远低于 `game` `+37.2%`）；Server 侧稳定触发 `fallbacks[reason=bulk] = 2`（`directTcpBytes ~918 KiB`），`deliveryStallMillis` 压低至 `0–31.6 ms`，同时保持四档最低的持续交互 P99/Max（`875.5–881.3 ms`）与最高平均上传吞吐（`151.0 KiB/s`）。
  - **资源与泄漏基线达标**：单核 960 MiB VPS 全程 `VmHWM = 418.5 MiB`（远低于 `768 MiB` 整机红线）、线程数 `53–55`、cgroup `nr_throttled = 0`、服务端/客户端日志 **`LEAK = 0`**。
