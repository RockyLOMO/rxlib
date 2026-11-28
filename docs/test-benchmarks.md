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

---

## 6. 2026-11-28 · `NodeRegistry` + 统一 `EgressCodec` + `Transport Core v2` + 双 NAT / RRP RAW 中继实测

### 6.1 双 JDK `PARANOID` 全量回归（提交 `4dd85305`..`d4890e01`）
- **基线环境**：Windows 11；Temurin JDK `21.0.12.1` + Temurin JDK `1.8.0_504`；Netty `4.2.21.Final`；统一参数 `-DskipTests=false "-DargLine=-Dio.netty.leakDetection.level=PARANOID"`。
- **18 个核心测试类（`DnsRecordExchangeTest`、`UserRuleMatcherTest`、`V2RayGeoManagerTest`、`AddressDiscoveryTest`、`NodeRegistryTest`、`NameserverImplTest`、`EgressCodecTest`、`TransportCoreTest`、`RssTcpCodecSelectorTest`、`RssHopAesTest`、`RrpIntegrationTest`、`RrpRawRelayTest`、`RendezvousAndPathSelectorTest`、`AdaptiveTcpStreamTest`、`AdaptiveUdpPortTest`、`RemotingTest`、`RemotingTransportConfigTest`、`RssRuntimeNodeServiceIntegrationTest`）**：
  - **JDK 21**：`158/158` 通过，`0` 失败，`0` 错误，`PARANOID LEAK: 0`。
  - **JDK 8**：`158/158` 通过，`0` 失败，`0` 错误，`PARANOID LEAK: 0`。
  - 覆盖要点：DNS RR TTL 衰减/字节预算、Geo 缺失 `BLOCK` fail-closed 与非 EventLoop 编译、`NodeRegistry` 分片锁/单调代际/租户限额/子系统共享会话/立即吊销、`EgressCodec`（`NONE`/纯 RFC 1950 `ZLIB_STORED`/方案 A `HTTP` + Server `AUTO` 零拷贝与粘包分片边界）、`Transport Core v2` 每会话 AEAD+`zlib3`+nonce 防重放+`LocalChannel`、`RrpServer` RAW 中继 4 级预算与轮询防队头阻塞、`RendezvousService`+`UdpEndpointObserver`+`PeerUdpPuncher`（含 RFC 8445 触发检查与 2s 被动滞留）+直连优先 `PathSelector`、以及 `RssRuntime` 默认关闭零副作用与事务式热重载。

### 6.2 高丢包公网 VPS 实测：统一 `EgressCodec` 固定模式 vs Server `AUTO`（`182.255.85.247`）
- **环境**：公网 VPS `182.255.85.247`（Debian 12、1 vCPU / `960 MiB` RAM、OpenJDK `21.0.13.1`、`-Xmx256m -Dio.netty.leakDetection.level=PARANOID`，隔离测试端口 `18910`–`18913`，不触碰生产服务）；客户端本机 `Youfan-PC`（`-Xmx256m -Dio.netty.leakDetection.level=PARANOID`）；VPS `nftables` `inet rx_live_verify` 精确统计双向 IP 层字节。
- **负载**：每组 `6` 次独立建连+首包 RTT 探测 + `512 KiB` 双向加密（`Transport Core v2` AES-128-GCM）不可压缩随机字节流回显（`1,048,576 B` 双向有效业务字节），逐字节校验 `SHA-256`。

| 模式 | VPS 端口 | 建连+首包 P50 / P95 (ms) | 双向有效吞吐 (Mbps) | 耗时 (ms) | VPS `nft` 线字节 (In / Out / Total) | 线字节 / 有效字节 | SHA-256 | 客户端 / VPS `LEAK` |
| --- | :---: | ---: | ---: | ---: | ---: | ---: | :---: | :---: |
| `fixed-NONE` | `18910` | 589.48 / 1519.52 | 0.32 | 26,140 | 546,955 / 593,105 / 1,140,060 | 1.0872× | 一致 | `0 / 0` |
| `fixed-ZLIB_STORED` | `18911` | 691.91 / 1637.43 | 0.39 | 21,276 | 544,712 / 615,914 / 1,160,626 | 1.1069× | 一致 | `0 / 0` |
| `fixed-HTTP`（方案 A） | `18912` | 589.76 / 889.59 | 0.35 | 24,059 | 551,413 / 609,769 / 1,161,182 | 1.1074× | 一致 | `0 / 0` |
| `auto-NONE` | `18913` | 604.73 / 1278.86 | 0.43 | 19,517 | 545,487 / 590,390 / 1,135,877 | 1.0833× | 一致 | `0 / 0` |
| `auto-ZLIB_STORED` | `18913` | 665.59 / 2561.93 | 0.32 | 26,397 | 542,535 / 608,244 / 1,150,779 | 1.0975× | 一致 | `0 / 0` |
| `auto-HTTP`（方案 A） | `18913` | 591.81 / 1239.40 | 0.49 | 17,061 | 553,073 / 600,838 / 1,153,911 | 1.1005× | 一致 | `0 / 0` |

- **结论**：Server `AUTO` 单端口（`18913`）在高丢包公网链路上对 `NONE`、纯 RFC 1950 `ZLIB_STORED`（无 `RSZ` 头）与方案 A `HTTP` 三种编解码 **100% 准确识别、0 误判、0 泄漏**；`ZLIB_STORED` 与方案 A `HTTP` 相对 `NONE` 的公网 IP 层额外线开销仅 **`+1.31%`～`+1.85%`**；VPS `egress-server` 结束时堆内 `19.3 MiB`、堆外直接内存 `8.33 MiB`、活跃线程 `7`、整机已用内存 `336 / 960 MiB`。

### 6.3 真实多网卡与跨运营商双 NAT 实测（`182.255.85.247` + `AnZhi-PC` + `Xsy-PC` + `Youfan-PC`）
- **部署**：公网 VPS `182.255.85.247` 单进程托管共享 `NodeRegistry` + `RendezvousService`（`18920` TCP `AUTO` + `18921` UDP Observer）+ `RrpServer` RAW Relay（`18922` TCP `AUTO`）；测试节点为同办公网多网卡主机 `AnZhi-PC`（`172.23.36.239` / 公网出口 `61.169.146.210`）、`Xsy-PC`（`192.168.137.1`, `192.168.1.10`, `172.23.37.51` / 公网出口 `61.169.146.210`）与独立家庭宽带 NAT 主机 `Youfan-PC`（公网出口 `180.108.234.255`）。每组传输 `512 KiB` 双向加密随机字节流（`1,048,576 B` 有效载荷）。

| 拓扑与模式 | 选中 TCP 路径 | 选中 UDP 路径 / 升轨 | 双向吞吐 / 耗时 | VPS RAW 中继流 / 载荷字节 | VPS `nft` 线字节（控制 / 观测 / 中继） | 关键验证结果 |
| --- | --- | --- | ---: | ---: | ---: | --- |
| **Topo 1a · 多网卡直连优先**<br>(`AnZhi-PC → Xsy-PC`) | `DIRECT`<br>(`172.23.37.51:61175`, `168 ms`) | `DIRECT_OR_PUNCH`<br>(`172.23.37.51:52185`, RTT `6.63 ms`, 升轨=`true`) | **17.43 Mbps**<br>(`481 ms`) | **`0` 流**<br>**`0 / 0 B`** | `5,696 B` / `173 B` / `1,673 B`（仅鉴权控制） | 跳过不可达网卡选通直连；UDP 升轨 + **中继断洞无损修复=`true`**；E2E AEAD + `SHA-256` 一致；**中继数据流省流 `100%`（公网总流量降 `99.68%`）** |
| **Topo 1b · 强制中继基线**<br>(`AnZhi-PC → VPS → Xsy-PC`) | `RELAY`<br>(`rrp-relay:xsy-pc/svc-xsy`, `1,499 ms`) | `NONE` | 0.31 Mbps<br>(`26,791 ms`) | `2` 流（含 RPC）<br>`1,051,982 / 1,051,982 B` | — / — / `2,339,113 B` | E2E AEAD + `SHA-256` 一致 + **中继上层 E2E AEAD RPC=`true`**；直连优先较强制中继吞吐提升 **`56.2×`** |
| **Topo 2a · 跨运营商双 NAT 直连优先**<br>(`Youfan-PC → VPS ↔ AnZhi-PC`) | `RELAY`（按需兜底）<br>(`rrp-relay:anzhi-pc/svc-anzhi`, `2,407 ms`) | `NONE`（企业 NAT 对称映射，受控探包 `2,448 B` 后平稳退出） | 0.31 Mbps<br>(`26,892 ms`) | `1` 流<br>`1,051,545 / 1,051,545 B` | `6,822 B` / `173 B` / `2,307,875 B` | 双端均真实观测到跨 ISP 公网映射（`180.108.234.255:62794` vs `61.169.146.210:28814`）；直连受阻后自动按需启用 E2E AEAD RAW Relay，`SHA-256` 一致 |
| **Topo 2b · 跨运营商双 NAT 禁 UDP**<br>(`Youfan-PC → VPS ↔ AnZhi-PC`) | `RELAY`（纯 TCP）<br>(`2,615 ms`) | `NONE`（0 探包） | 0.25 Mbps<br>(`33,710 ms`) | 复用中继链路 | — | 纯 TCP 按需中继 + **中继上层 E2E AEAD `Remoting` RPC=`true`** + `SHA-256` 一致 |

- **公网安全矩阵（`182.255.85.247` 实测）**：错误节点密钥认证拒绝=`true`；跨租户（`tenant-2` `outsider-c` → `tenant-1` `anzhi-pc`）Rendezvous `discoverPeer` 拒绝=`true`；跨租户 RRP RAW `openRawStream` 拒绝=`true`；伪造 UDP 观测包被静默丢弃（`forgedObsDropped=true`、`vpsObsRejectedDelta=1`、`0` 回包，无放大风险）。
- **VPS 资源与泄漏核验**：VPS `node-server` 运行结束时 `activeRawStreams=0`、`globalRelayPendingBytes=0`、堆内 `31.5 MiB`、堆外直接内存 `8.62 MiB`、线程 `24`、整机已用内存 `392 / 960 MiB`（可用 `567 MiB`）；全部 4 台机器在 `PARANOID` 模式下 **`LEAK = 0`**，测试完成后临时进程/目录/`nft` 表全部清空。

---

## 7. 2026-11-28 · 第二轮 Review（P1 §3–§5）、跨网打洞诊断、30 轮 HTTP 出口基准与 RSS `Transport Core v2` 真机实测

### 7.1 双 JDK `PARANOID` 28 类全量超集回归（提交 `29bd0e65`..`0c47859f`）
- **基线环境**：Windows 11；Temurin JDK `21.0.12.1` + Temurin JDK `1.8.0_504`；Netty `4.2.21.Final`；统一参数 `-DskipTests=false "-DargLine=-Dio.netty.leakDetection.level=PARANOID"`（并设置临时测试 `RSS_TCP_AES_KEY` 覆盖 PSK 与 `ZLIB_STORED` 场景）。
- **28 个测试类超集**（覆盖 §5 旧 26 类核心与 §6/本轮全部新增专项：含 `NameserverImplTest`、`NodeRegistryTest`、`RrpIntegrationTest`、`RrpRawRelayTest`、`RendezvousAndPathSelectorTest`、`EgressCodecTest`、`TransportCoreTest`、`TransportCoreCarrierTest`、`RssTest`、`RssRuntimeNodeServiceIntegrationTest`、`SsUserBandwidthIntegrationTest`、`AdaptiveTcpStreamTest`、`AdaptiveUdpPortTest`、`RssDatagramHandlerTest`、`DnsRecordExchangeTest`、`DnsOptimizationTest`、`UserRuleMatcherTest`、`V2RayGeoManagerTest`、`AddressDiscoveryTest`、`RemotingTest`、`RemotingTransportConfigTest`、`BandwidthLimiterTest` 等）：
  - **JDK 21 (`21.0.12.1`)**：`373` 用例运行、`0` 失败、`0` 错误、**`PARANOID LEAK: 0`**。
  - **JDK 8 (`1.8.0_504`)**：`373` 用例运行、`0` 失败、`0` 错误、**`PARANOID LEAK: 0`**。
  - **构建验证**：`mvn -pl rxlib package -DskipTests=true` 成功产出 `rxlib-2.22.8-SNAPSHOT.jar`。

### 7.2 跨局域网双 NAT 类型诊断与双向同时打洞实测（`YOUFAN-PC` ↔ `182.255.85.247` ↔ `AnZhi-PC`）
- **拓扑与环境**：两台处于**不同局域网与不同运营商出口**的主机——本地 `YOUFAN-PC`（公网出口 `180.108.234.255`，Windows 防火墙公用/专用关闭）与远端 `AnZhi-PC`（公网出口 `61.169.146.210`），由公网高丢包 VPS `182.255.85.247` 托管单进程 `NodeRegistry` + `RendezvousService`（`18920` TCP `AUTO` + `18921` UDP Observer）+ 双端口 RFC 5780 诊断（`18928`/`18929` 映射、`18930`/`18931` 过滤）+ `RrpServer` RAW Relay（`18922` TCP `AUTO`）。
- **RFC 5780 映射与过滤行为诊断结果**：

| 主机 | 本机 UDP 端口 | 观测端口 A1 (`18928`) | 观测端口 B (`18929`) | 观测端口 A2 (`18928`) | `portDelta` | 映射行为 | 同端口/异端口回包 | NAT 类型判定 |
| --- | :---: | --- | --- | --- | :---: | --- | :---: | --- |
| **`YOUFAN-PC`** | `63936` | `180.108.234.255:63936` | `180.108.234.255:63936` | `180.108.234.255:63936` | `0` | `ENDPOINT_INDEPENDENT_CONE` | `true / false` | **端口受限锥型 NAT（PRC）** |
| **`AnZhi-PC`** | `52645` | `61.169.146.210:31349` | `61.169.146.210:31353` | `61.169.146.210:31349` | `+4` | `PORT_DEPENDENT_SYMMETRIC` | `true / false` | **对称型 NAT（Symmetric）** |

- **双向同时 UDP 打洞与按需中继实测**（两端均通过 `RendezvousService` 下发打洞邀请并发射受控签名探包）：

| 方向 | 业务载荷（双向有效） | 候选/计划对数 | 发起端 / 应答端打洞探包 (`Tx / Rx`) | 最终 TCP 路径 / 建连耗时 | 双向有效吞吐 / 耗时 | VPS RAW 中继载荷 (`In / Out`) / `nft` 线字节 | E2E AEAD / `SHA-256` | 三端 `LEAK` |
| --- | ---: | :---: | :---: | --- | ---: | ---: | :---: | :---: |
| **Dir 1 · `YOUFAN-PC → AnZhi-PC`** | `1,048,576 B`<br>(`512 KiB`×2) | 3 远端 / 8 UDP 对 | `5,712 / 0 B`<br>`5,712 / 0 B` | `RELAY`（按需兜底）<br>`2,532 ms` | `0.25 Mbps`<br>`34,218 ms` | `1,051,562 / 1,051,562 B`<br>`2,366,452 B` | `true / true` | **`0 / 0 / 0`** |
| **Dir 2 · `AnZhi-PC → YOUFAN-PC`** | `524,288 B`<br>(`256 KiB`×2) | 7 远端 / 8 UDP 对 | `5,712 / 0 B`<br>`5,712 / 0 B` | `RELAY`（按需兜底）<br>`3,475 ms` | `0.27 Mbps`<br>`15,455 ms` | `525,881 / 525,881 B`<br>`1,182,494 B` | `true / true` | **`0 / 0 / 0`** |

- **结论**：经 RFC 5780 实证，`YOUFAN-PC`（PRC NAT）↔ `AnZhi-PC`（Symmetric NAT）属于物理上单端口直打必败的组合（对称 NAT 为新目的元组分配动态端口，PRC NAT 丢弃未知源端口入包；本实现严格遵守有界零放大设计、不采用盲扫端口池策略）。两方向均在受控 `5,712 B` 探包后平稳按需回退至 E2E AEAD RRP RAW Relay，全量 `SHA-256` 一致、三端 `PARANOID LEAK: 0`。两端均为锥型 NAT 的公网跨 ISP 打洞直连升轨验收留待合适测试主机补充（见 [roadmap-plan.md](roadmap-plan.md) §1）。

### 7.3 高丢包公网 VPS 30 轮 `AUTO` 出口基准：`NONE` / `ZLIB_STORED` / `HTTP_FIXED` / `HTTP_RANDOM`（`182.255.85.247:18913`）
- **环境与负载**：单端口 `18913`（`Transport Core v2` AES-128-GCM + Server `AUTO` 全白名单）；每种模式完整执行：① **`30` 次独立建连 + ping**（统计 P50/P95/P99）、② **`20 × 256 B` 小包交互 RTT**、③ **`4 × 64 KiB` 并发流 Echo**、④ **`256 KiB` 单流双向大包 Echo**（合计有效双向业务字节 `1,058,816 B`），VPS `nftables` 精确计量双向 IP 层总字节。

| 出口模式 | 30 轮建连 P50 / P95 / P99 (ms) | 小包交互 P50 / P95 (ms) | 4×64KiB 并发耗时 / 校验 | 256KiB 双向吞吐 / 耗时 / SHA-256 | VPS `nft` 双向线字节 (`In / Out / Total`) | 线字节 / 有效字节 | 相对 `NONE` 额外线开销 | 客户端 / VPS `LEAK` |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | :---: |
| **`NONE`** | 600.74 / 2085.20 / 2593.55 | 203.14 / 602.04 | 2,722 ms / 全通过 | 0.73 Mbps / 5,784 ms / 一致 | 578,920 / 633,804 / **1,212,724** | 1.1454× | 基准 | `0 / 0` |
| **`ZLIB_STORED`** | 592.82 / 2167.48 / 2200.89 | 192.90 / 615.08 | 4,759 ms / 全通过 | 0.49 Mbps / 8,607 ms / 一致 | 571,719 / 644,614 / **1,216,333** | 1.1488× | **+0.30%** | `0 / 0` |
| **`HTTP_FIXED`** | 604.91 / 1655.58 / 1714.86 | 197.96 / 692.60 | 2,058 ms / 全通过 | 0.32 Mbps / 13,195 ms / 一致 | 621,707 / 666,285 / **1,287,992** | 1.2164× | +6.21% | `0 / 0` |
| **`HTTP_RANDOM`**（恢复旧随机模板） | 660.82 / 1808.39 / 2596.55 | 202.35 / 624.99 | 2,123 ms / 全通过 | 0.76 Mbps / 5,549 ms / 一致 | 624,227 / 631,641 / **1,255,868** | 1.1861× | **+3.56%** | `0 / 0` |

- **结论**：Server `AUTO` 单端口（`18913`）对 `NONE`、纯 RFC 1950 `ZLIB_STORED`、固定 `HTTP_FIXED` 与恢复后的 `HTTP_RANDOM`（80% `GET` / 20% `POST` + 随机 Host/Path/Query + Chrome 137 / `Accept` / `Connection` / UTC `Date` / `Server: Tengine`）在 **120 次独立建连 + 80 次小包交互 + 16 条并发流 + 4 轮大流** 下 **100% 识别准确、0 误判、0 泄漏**；`HTTP_RANDOM` 相对 `NONE` 额外线开销仅 **`+3.56%`**（高丢包线路上因单次大流 TCP 重传波动略低于 `HTTP_FIXED` `+6.21%`），小包交互 P50（`202.35 ms`）与 `NONE`（`203.14 ms`）基本一致。

### 7.4 RSS 私有增强链路统一 `Transport Core v2` 真机实测（`182.255.85.247:18915`）
- **环境与负载**：同一 VPS 进程托管 RSS `TransportCoreCarrier` 服务端（TCP `18915` 启用 `AUTO` 出口全白名单 + `udpHop` 双端口 `18940`/`18941` + `RSS_TCP_AES_KEY` PSK + `zlib 3`），客户端经公网向服务端本地回显后端传输 `32 KiB` 可压缩业务流（双向 `64 KiB`），验证 P1 §3.3（无加密业务 `zlib 3` 过 `AUTO` 无歧义）与 P1 §5 四档 profile 实机运行。

| 场景 | AEAD | Adaptive Profile | 实测 `usingUdp` | 业务 `zlib` | 建连耗时 (ms) | 32 KiB 回显 (ms) | VPS TCP / UDP 线字节 | `SHA-256` | 客户端 / VPS `LEAK` |
| --- | :---: | :---: | :---: | :---: | ---: | ---: | ---: | :---: | :---: |
| **`plain-zlib-NONE`**（§3.3） | `false` | — | `false` | `true` | 1,640 | 183 | `1,834 / 0 B` | 一致 | `0 / 0` |
| **`plain-zlib-ZLIB_STORED`**（§3.3） | `false` | — | `false` | `true` | 1,303 | 187 | `2,126 / 0 B` | 一致 | `0 / 0` |
| **`plain-zlib-HTTP_RANDOM`**（§3.3） | `false` | — | `false` | `true` | 1,437 | 231 | `4,790 / 0 B` | 一致 | `0 / 0` |
| **`daily-aead-HTTP_RANDOM`** | `true` | `daily (0)` | **`true`** | `true` | 1,647 | 204 | `7,874 / 1,364 B` | 一致 | `0 / 0` |
| **`weak-aead-ZLIB_STORED`** | `true` | `weak (2)` | **`true`** | `true` | 1,318 | 194 | `1,895 / 1,614 B` | 一致 | `0 / 0` |
| **`game-aead-NONE`** | `true` | `game (1)` | **`true`** | `true` | 1,387 | 454 | `2,132 / 753 B` | 一致 | `0 / 0` |

- **结论**：
  - **彻底消除 §3.3 歧义**：前 3 组无 AEAD `plain + zlib 3` 在单端口 `AUTO` 下横跨 `NONE`、`ZLIB_STORED` 与 `HTTP_RANDOM` 全部零误判通过，业务 `zlib 3` 将 `64 KiB` 双向明文压至 `1.8–4.8 KiB` 线字节。
  - **四档统一 Core v2 全通**：`daily`、`weak`、`game` 在公网高丢包环境下全部经 `RXTC` v2 完成双向认证、单层 AEAD、`zlib 3` 业务压缩与 `18940`/`18941` 自适应 UDP 升轨（`usingUdp = true`），全部 `SHA-256` 一致且双端 `PARANOID LEAK = 0`。

### 7.5 资源基线与远端清理核验
- **VPS JVM 与主机快照**（`egress-rss-server` 结束时）：`autoSessions = 0`、`rssActiveChannels = 0`、堆内已用 `33.17 MiB`、堆外直接内存 **`8.62 MiB`**（`9,037,313 B`）、活跃线程 `15`、整机已用内存 `374 / 960 MiB`（可用 `586 MiB`），服务端与全部客户端 **`PARANOID LEAK = 0`**（注：本次测试为短时功能/基准验收，1024 节点 / ≥24h `768 MiB` 整机稳态压测仍列于 [roadmap-plan.md](roadmap-plan.md) §3 遗留项）。
- **远端严格清理验证**：VPS `182.255.85.247` 核验 `PROC:clean` / `DIR:clean` / `NFT:clean`（未触碰线上 `/home/rss` 及生产端口）；`AnZhi-PC` 核验 `PROC=0` / `DIR=False`。

---

## 8. 2026-11-28 · 第三轮 Review（P1 §8–§12）：RSS 轻量握手、统一 `FAST` 套件、RPC 降 RTT 与按需资源池双 JDK 与公网真机 A/B 实测

### 8.1 双 JDK `PARANOID` 32 类全量超集回归（提交 `3044e7c5`..`927475a6`）
- **基线环境**：Windows 11；Temurin JDK `21.0.12.1` + Temurin JDK `1.8.0_504`；Netty `4.2.21.Final`；统一参数 `-DskipTests=false "-DargLine=-Dio.netty.leakDetection.level=PARANOID"`（临时配置 32 字节测试 `RSS_TCP_AES_KEY` / `RSS_UDP_AES_KEY`）。
- **32 个测试类超集**（覆盖 §7 既有核心 + 新增 `RssLightweightAndRpcOptimizationTest`、`RssRound3ConfigAndAuthTest` 以及 `SocksProxyServerIntegrationTest`、`DailyRelayGroupTest`、`Socks5SessionPoolTest`、`Socks5CommandRequestHandlerTest`、`SocksUdpRelayHandlerTest`、`SocksUdpUpstreamPortHoppingTest`、`RssSocksCarrierMtuTest`、`RssTest`、`RssAuthenticatorTest`、`RssReviewFixesTest`、`RssRuntimeNodeServiceIntegrationTest`、`SsUserBandwidthIntegrationTest`、`TransportCoreCarrierTest`、`TransportCoreTest`、`EgressCodecTest`、`AdaptiveTcpStreamTest`、`AdaptiveUdpPortTest`、`RssDatagramHandlerTest`、`RssDatagramCipherTest`、`RssDatagramCompressionTest`、`NameserverImplTest`、`NodeRegistryTest`、`RendezvousAndPathSelectorTest`、`RrpIntegrationTest`、`RrpRawRelayTest`、`RemotingTest`、`RemotingTransportConfigTest`、`DnsRecordExchangeTest`、`DnsOptimizationTest`、`BandwidthLimiterTest`）：
  - **JDK 21 (`21.0.12.1`)**：`389` 用例运行（`2` 既有环境跳过）、`0` 失败、`0` 错误、**`PARANOID LEAK: 0`**。
  - **JDK 8 (`1.8.0_504`)**：`389` 用例运行（`2` 既有环境跳过）、`0` 失败、`0` 错误、**`PARANOID LEAK: 0`**。
  - **构建验证**：`mvn -pl rxlib package -DskipTests=true` 成功产出 `rxlib-2.22.8-SNAPSHOT.jar`。

### 8.2 高丢包公网 VPS 同配置 A/B 基准：`LIGHTWEIGHT` vs `CORE`（`182.255.85.247:18960`）
- **环境与负载**：隔离单端口 `18960`（Server `AUTO` 全白名单 `none`/`zlib-stored`/`http` + `udpHop 18970-18979` + `RSS_TCP_AES_KEY` PSK + 单用户鉴权），客户端与 VPS 双端开启 `-Dio.netty.leakDetection.level=PARANOID`。每组同配置执行 10 轮冷建连 + 64 B Echo（记录无重传底线 `min` 与高丢包波动下 `P50`/`P95`）、客户端双向线字节计量、32 KiB 可压缩双向 Echo `SHA-256` 校验，以及 `plain-NONE` 5 轮 TCP Warm Pool 命中测试；线路基础 RTT 约 `185 ms` 且伴随突发性高丢包（TCP 1s RTO 引起单轮 P50 波动，以 `min` 与确定性线字节对照零重传净收益）。

| 场景（单端口 Server `AUTO`） | `HandshakeMode` | 成功 | 冷建连+64B `min` (ms) | `min` 净降幅 | `P50 / P95` (ms) | 单次冷建连+Echo 线字节 | 线字节净省 | 32 KiB 回显 (ms) / 线字节 / `SHA-256` | Warm 命中 `P50` (ms) |
| --- | --- | :---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **`plain-NONE`**（无 AEAD） | **`LIGHTWEIGHT`**（纯 SOCKS5） | `10/10` | **947.8** | **-137.2 ms** | 1947.1 / 3969.5 | **177.0 B** | **-117.0 B (-39.8%)** | 2075.6 / 65,585 B / 一致 | **428.3** |
| **`plain-NONE`**（无 AEAD） | `CORE`（`RXTC` v2） | `10/10` | 1085.0 | 基准 | 1261.2 / 2394.7 | 294.0 B | 基准 | 2068.1 / 65,702 B / 一致 | — |
| **`plain-HTTP_RANDOM`**（无 AEAD） | **`LIGHTWEIGHT`**（纯 SOCKS5） | `10/10` | **930.9** | **-174.1 ms** | **1048.4** / 3393.0 | **1,853.5 B** | **-541.6 B (-22.6%)** | 1798.3 / 68,102 B / 一致 | — |
| **`plain-HTTP_RANDOM`**（无 AEAD） | `CORE`（`RXTC` v2） | `10/10` | 1105.0 | 基准 | 1166.2 / 2337.5 | 2,395.1 B | 基准 | 2719.3 / 68,692 B / 一致 | — |
| **`plain-zlib3-NONE`**（`AUTO` 无 AEAD） | **`LIGHTWEIGHT`**（`0x8c` 捎带） | `10/10` | **919.9** | **-197.3 ms** | 1929.3 / 3149.9 | **289.0 B** | **-59.0 B (-17.0%)** | 1682.3 / **675 B** / 一致 | — |
| **`plain-zlib3-NONE`**（`AUTO` 无 AEAD） | `CORE`（`RXTC` v2） | `10/10` | 1117.2 | 基准 | 1642.1 / 3143.2 | 348.0 B | 基准 | 1630.8 / 734 B / 一致 | — |
| **`plain-zlib3-HTTP_RANDOM`**（`AUTO` 无 AEAD） | **`LIGHTWEIGHT`**（`0x8c` 捎带） | `10/10` | **986.5** | **-180.3 ms** | **1479.0** / 8723.6 | **2,392.7 B** | **-50.8 B (-2.1%)** | 1193.7 / **3,467 B** / 一致 | — |
| **`plain-zlib3-HTTP_RANDOM`**（`AUTO` 无 AEAD） | `CORE`（`RXTC` v2） | `10/10` | 1166.8 | 基准 | 1568.3 / 4478.2 | 2,443.5 B | 基准 | 1139.2 / 3,477 B / 一致 | — |
| **`daily-noAead-NONE`**（Adaptive UDP） | **`LIGHTWEIGHT`**（`0x8c` 捎带） | `10/10` | **1041.6** | **-136.5 ms** | 1656.7 / **3190.6** | — | — | 1398.6 / — / 一致 | — |
| **`daily-noAead-NONE`**（Adaptive UDP） | `CORE`（`RXTC` v2） | `10/10` | 1178.1 | 基准 | 1616.7 / 4644.5 | — | — | 1210.1 / — / 一致 | — |
| **`weak-FAST_AEAD-ZLIB_STORED`**（`SecurityMode.FAST`） | `LIGHTWEIGHT`（自动单次 `RXTC` AEAD） | `8/8` | 1169.8 | 单次 AEAD 握手 | 1323.4 / 1970.8 | — | — | 2758.2 / — / 一致 | — |

- **结论**：全部 5 组同配置 A/B 对照中，`LIGHTWEIGHT` 相对 `CORE` 稳定节省约 **1 个完整 RTT（`136.5–197.3 ms`）**，同时单连接线字节减少 `50.8–541.6 B`（其中 `plain-HTTP_RANDOM` 省去一整对独立 HTTP 请求/响应头达 `-22.6%`，`plain-NONE` 减少 `-39.8%`）；`plain+zlib 3` 经 `0x8c` 捎带在单端口 `AUTO` 下无任何首字节歧义（64 KiB 压缩至 `675 B`），受保护 `weak-FAST_AEAD-ZLIB_STORED` 自动走单次 `RXTC` `AES-128-GCM + HMAC-SHA256` 握手并全量通过。

### 8.3 高丢包公网 VPS `plain` UDP A/B：RSS RPC Relay Group vs 标准 SOCKS5 `UDP ASSOCIATE`（`182.255.85.247:18960/18961`）
- **环境与负载**：同一 VPS 托管 `SocksProxyServer`（`18960`）与 `RssRpcApp`（`18961`）及 2 个本地 UDP 回显目标（`18963`/`18964`），对比 10 次独立 association 在高丢包单发 UDP 下的首包耗时、同关联跨目标复用耗时、RPC 调用数与专用 SOCKS5 TCP 控制连接数。

| 控制路径 | 单发成功率（高丢包） | 冷能力首包 (ms) | 缓存能力首包 `P50 / P95` (ms) | 同关联跨目标复用 `P50` (ms) | `capabilities()` RPC 次数 | `openUdpRelayGroup()` 次数 | 专用 SOCKS5 TCP 控制连接数 |
| --- | :---: | ---: | ---: | ---: | :---: | :---: | :---: |
| **`RSS_RPC_GROUP`**（`AUTO` + `SocksRpcCapabilitiesCache`） | `7/10` | `1,790.4`（含首帧 Fury JIT） | **`782.2 / 1,445.4`**（**P50 `-23.0%` / P95 `-31.1%`**） | **`192.9`**（纯 1 RTT UDP，0 控制 RTT） | **`1`** | `10` | **`0`** |
| **`SOCKS5_UDP_ASSOCIATE`**（`SOCKS5_COMPAT`） | `8/10` | — | `1,015.9 / 2,098.5` | — | `0` | `0` | **`8`** |

### 8.4 VPS 资源快照、`nftables` 计量与远端清理核验
- **惰性状态验证**：服务端启动时 `hasCoreReplayCache() == false`，仅在收到首条 `0x8c`/`RXTC` 连接后惰性创建。
- **VPS 结束快照**：进程 RSS `210,260 KiB`（`~205.3 MiB`，37 OS LWPs），JVM 堆内 `39.77 MiB`、Netty `pooledDirect = 8.00 MiB`、JVM `BufferPoolMXBean("direct") = 8.67 MiB`、活跃线程 `26`；整机 `free -m` 峰值已用 `414 / 960 MiB`（可用 `546 MiB`，远低于 `768 MiB` 整机红线）。
- **VPS `nftables` IP 层总计量**：TCP（`18960,18961`）`1,691 pkts / 305,147 B` 入、`1,814 pkts / 309,726 B` 出；Adaptive UDP（`18970-18979`）`104 pkts / 9,673 B` 入、`97 pkts / 9,346 B` 出。
- **泄漏与清理**：客户端与 VPS **`PARANOID LEAK = 0`**；清理复核 **`CLEANUP_VERIFY PROC:0 DIR:clean NFT:0`**，整机可用内存回落至 `747 / 960 MiB`，生产服务零触碰。
