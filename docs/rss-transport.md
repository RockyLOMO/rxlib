# RSS Client → RSS Server 传输配置

适用于同版两端的内置 RSS 跳。外部 Shadowsocks 协议沿用原配置。直连目的地和显式绕过 RSS 的外部固定隧道执行自己的传输策略。

## 用户策略

在 Client 的 `conf.yml` 中给已有的 SS 用户增加 `transport`。省略时使用 `plain`，普通 TCP 走 TCP，普通 UDP 单发。两类传输均遵守下面的 TCP 端口规则。下面是已有 6898 用户的配置片段，其余身份和路由字段按原配置填写。

```yaml
shadowUsers:
  - ssPort: 6898
    transport:
      profile: daily
```

| 字段 | 值 | plain | daily |
| --- | --- | --- | --- |
| `fec` | 0 关闭，1 仅 UDP，2 仅 TCP，3 两者 | 0 | 3 |
| `tcp` | 位 1 为 zlib 3，位 2 为 AES-128-GCM，位 4 为 TLS。2 与 4 使用同一套校验证书的 TLS 1.3 / AES-128-GCM；组合只装一层 | 0 | 1 |
| `udp` | 位 1 为逐包 LZ4 fast，位 2 为 AES-128-GCM，3 为先压缩再加密 | 0 | 0 |

在同一个 `transport` 下写 `fec`、`tcp`、`udp` 可覆盖 profile 对应字段，包括显式 0。`daily` 的 UDP 默认仅 FEC 和跳跃。游戏 profile 尚未开放。热更新影响新建代理连接和 UDP 会话。用户按外部 SS 用户名匹配，内部 SOCKS 账号可复用。旧 `adaptiveTcp` 用户字段已移除。

## TCP 端口

| Client 顶层字段 | 默认目标 TCP 端口 | 行为 |
| --- | --- | --- |
| `tcpCipherPorts` | 21、23、25、53、80、110、143、389、587、1883、8080 | 默认认证加密。覆盖 FTP 控制、Telnet、可明文或 STARTTLS 的邮件和目录协议、DNS、HTTP、MQTT |
| `tcpAlreadyEncryptedPorts` | 22、443、465、636、853、990、993、995、8883 | 按 SSH、HTTPS、隐式 TLS 邮件、LDAPS、DoT、FTPS 和安全 MQTT 透传，跳过 RSS 压缩和加密；仍可走 TCP FEC |

端口号是策略约定，不能证明业务实际开启了加密。使用非标准端口时调整列表。两表不得重叠。Server 的 `-tcpCipherPorts=21,23,...` 设置同一保护下限。对其余端口应用用户的 `tcp`。明确组合如 `tcp: 3` 或 `tcp: 5` 会先 zlib 再 TLS；日常模式的 `tcp: 1` 在强制保护端口只做加密，避免 HTTP Cookie 与可控输入共享压缩上下文泄漏长度。

Server 读取 `RSS_TCP_TLS_CERT` 和 `RSS_TCP_TLS_KEY` 指向的 PEM 证书链与私钥。证书 SAN 需匹配 Client 上配置的 RSS 上游域名或 IP。Client 信任 JVM 信任库，私有 CA 可用 `RSS_TCP_TLS_CA` 指定 PEM 路径。验证失败关闭保护连接。TCP/FEC 先在 TCP 上协商，再探测 UDP；成功后直接拆分已编码的字节流，UDP 外层保留签名、防重放、FEC 和端口跳跃。UDP 不可用时继续 TCP。

## UDP 和跳跃端口

普通 UDP 的任意一个扩展位（FEC、LZ4、AES）都要求两端成功建立跳跃会话。能力或端口不足时该会话失败。LZ4 对至少 96 B 的包尝试，净省至少 24 B 且 12% 才使用，否则原包进入后续传输。UDP AES 要求两端通过环境变量 `RSS_UDP_AES_KEY` 提供相同的 32 个随机字节的 Base64 编码。每次会话按双方随机值派生方向密钥；认证覆盖跳跃代数、序号及数据，接收端检查重放。TCP/FEC 不使用普通 UDP 的 LZ4 或 AES 管线。

默认专用 UDP 范围为 `20000-20127`，Server 通过 `-udpHopPorts=起始-结束` 覆盖。范围长度必须为偶数，最少四端口。首对由全部 TCP/FEC 流共用，剩余端口每个普通 UDP 扩展会话占用一对，默认最多 63 组。两端按 30 秒轮换。外部安全组、主机过滤规则以及容器或 NAT 的 UDP 映射需放行整段范围。

- **Windows Server**绑定两个 UDP socket，共用 EventLoop、会话和 FEC 状态。对外目标看到稳定的主端口。
- **Linux Server**启动时用 `ip` 和 `nft` 在当前网络命名空间建立专有 inet NAT 表，第二端口重定向到第一端口。TCP/FEC 共用一个服务端 socket；每个普通 UDP relay 组各用一个服务端 socket。脚本按启动时的本机 IPv4/IPv6 地址建立入口规则。需要 `CAP_NET_ADMIN` 和空闲的专用端口段。规则安装失败会禁用跳跃扩展，普通传输仍可用。JVM 退出后辅助进程清理自己创建的表。若辅助进程和命名空间内的 JVM 同时被强杀且规则残留，先核对表名和归属再清理。

部署观察每方向 FEC 缺口与恢复数、压缩节省字节、TCP/UDP 切换、活跃组数及剩余端口对，同时对照 CPU、进程 RSS、Netty 堆外内存和 P95/P99。Server 按 1 核、768 MiB 整机预算设计。

[Hysteria 2 的 Linux 端口重定向](https://v2.hysteria.network/docs/advanced/Port-Hopping/) · [IANA 端口登记](https://www.iana.org/assignments/service-names-port-numbers/service-names-port-numbers.xhtml)
