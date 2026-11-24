# RSS Client → RSS Server 传输配置

适用于同版两端的内置 RSS 跳。外部 Shadowsocks 协议沿用原配置。直连目的地和显式绕过 RSS 的外部固定隧道执行自己的传输策略。

## 用户策略

在 Client 的 `conf.yml` 中给已有的 SS 用户增加 `transport`。省略时使用 `plain`，普通 TCP 走 TCP，普通 UDP 单发。TCP 与 UDP 分别应用各自的端口规则。下面是已有 6898 用户的配置片段，其余身份和路由字段按原配置填写。

```yaml
shadowUsers:
  - ssPort: 6898
    transport:
      profile: daily
```

| `profile` | TCP 默认 | 普通 UDP 默认 |
| --- | --- | --- |
| `plain` | 普通 TCP | 单发 |
| `daily` | zlib 3、自适应 XOR、TCP 修复 | 自适应 XOR |
| `game` | zlib 3、自适应 XOR、按需扩窗 | 首份认证副本立即交付；按方向自适应 1～3 倍 |
| `weak` | zlib 3、按编码字节预算的 XOR、按需扩窗、选择性 TCP 修复 | 按编码字节预算的 XOR |

在已有用户的 `transport` 下设置 `profile: game` 或 `profile: weak` 即可。热更新影响新建连接和 UDP 会话。用户按外部 SS 用户名匹配，内部 SOCKS 账号可复用。

- `fec` 的 0/1/2/3 分别表示关、仅 UDP、仅 TCP、两者。上述四个 profile 默认依次为 0/3/2/3。`game` 显式开启 UDP FEC 时，该方向改用 XOR，每个原包只发一份。
- `tcp` 的位 1、2、4 分别为 zlib、共享密钥 AES-128-GCM、校验证书的 TLS。AES 与 TLS 两位同时出现时使用 TLS。`udp` 的位 1、2 分别为 LZ4 和 AES。保留显式 0 和按目的端口覆盖。
- `tcpCompressionLevel` 取 1～9，默认 3，压缩位开启时生效。Client 上的用户字段控制上行编码，Server 的 `-tcpCompressionLevel=3` 独立控制下行编码。解码允许两端使用不同级别。
- TCP 新模式通过独立 SOCKS 方法在两端选择。旧 Server 选择常规认证时，该连接继续走 TCP，`rss.tcp.adaptive.negotiationFallback` 按请求的 profile 记录回退。普通 UDP 的新模式要求 RPC 明确确认版本与特性，旧端协商失败。
- 游戏副本默认单发起步，按方向反馈升档。可用 `-Dapp.rss.game.initialCopies=2` 对照测试双发起步。weak 尾包探测可用 `-Dapp.rss.weak.tailProbe=tcp` 选择 TCP 候选；UDP 候选使用新认证序号，超时仍由可靠 TCP 接管。
- 游戏副本和两种增强 profile 的 UDP FEC 都逐包认证。启用 AES 时沿用部署密钥；其余端口由已加密的 RSS 控制 RPC 下发每组独立的认证密钥。认证完成后更新防重放窗口和回程地址；同一组的两个端口共享状态。

## 按协议选择目标端口

| Client 顶层字段 | 默认目标端口 | 行为 |
| --- | --- | --- |
| `tcpCipherPorts` | 21、23、25、53、80、110、143、389、587、1883、8080 | 默认认证加密。覆盖 FTP 控制、Telnet、可明文或 STARTTLS 的邮件和目录协议、DNS、HTTP、MQTT |
| `tcpAlreadyEncryptedPorts` | 22、443、465、636、853、990、993、995、8883 | 按 SSH、HTTPS、隐式 TLS 邮件、LDAPS、DoT、FTPS 和安全 MQTT 透传，跳过 RSS 压缩和加密；仍可走 TCP FEC |
| `udpCipherPorts` | 53 | UDP DNS 走共享密钥 AES-128-GCM，并启用端口跳跃 |
| `udpAlreadyEncryptedPorts` | 443、853 | QUIC、DoQ 跳过额外压缩和加密，沿用用户的 FEC 或游戏副本策略 |

端口号是策略约定，不能证明业务实际开启了加密。使用非标准端口时调整列表。每种协议的两张表不得重叠。Server 的 `-tcpCipherPorts` 与 `-udpCipherPorts` 可分别覆盖强制保护端口。UDP/80 使用用户的 UDP 策略。TCP 显式组合 `3` 先 zlib 再共享密钥 AES，`5` 先 zlib 再 TLS。日常模式的强制保护 TCP 端口只用共享密钥 AES，避免 HTTP Cookie 与可控输入共享压缩上下文泄漏长度。

TCP 的 AES 模式由两端读取同一个 `RSS_TCP_AES_KEY`（32 个随机字节的 Base64），与 UDP 密钥独立。双方随机数和部署密钥派生每连接、每方向独立的 128 位 AES 密钥；握手一次往返，最多 16 KiB 的数据记录额外占 18 字节。此模式没有前向保密，变更密钥应协调两端发布。

选择 TCP 的 TLS 位时，Server 读取 `RSS_TCP_TLS_CERT` 和 `RSS_TCP_TLS_KEY` 指向的 PEM 证书链与私钥。证书 SAN 需匹配 Client 上配置的 RSS 上游域名或 IP。Client 信任 JVM 信任库，私有 CA 可用 `RSS_TCP_TLS_CA` 指定 PEM 路径。验证失败关闭保护连接。TCP/FEC 先在 TCP 上协商，再探测 UDP；成功后直接拆分已编码的字节流，UDP 外层保留签名、防重放、FEC 和端口跳跃。UDP 不可用时继续 TCP。

## UDP 和跳跃端口

普通 UDP 的任意扩展（FEC、副本、LZ4、AES）都要求两端成功建立跳跃会话。能力或端口不足时该会话失败。LZ4 对至少 96 B 的包尝试，净省至少 24 B 且 12% 才使用，否则原包进入后续传输。UDP AES 要求两端通过环境变量 `RSS_UDP_AES_KEY` 提供相同的 32 个随机字节的 Base64 编码。每次会话按双方随机值派生方向密钥；认证覆盖跳跃代数、序号及数据，接收端检查重放。TCP/FEC 不使用普通 UDP 的 LZ4 或 AES 管线。

默认专用 UDP 范围为 `20000-20127`，Server 通过 `-udpHopPorts=起始-结束` 覆盖。范围长度必须为偶数，最少四端口。首对由全部 TCP/FEC 流共用，剩余端口每个普通 UDP 扩展会话占用一对，默认最多 63 组。两端按 30 秒轮换。外部安全组、主机过滤规则以及容器或 NAT 的 UDP 映射需放行整段范围。

- **Windows Server**绑定两个 UDP socket，共用 EventLoop、会话和 FEC 状态。对外目标看到稳定的主端口。
- **Linux Server**启动时用 `ip` 和 `nft` 在当前网络命名空间建立专有 inet NAT 表，第二端口重定向到第一端口。TCP/FEC 共用一个服务端 socket；每个普通 UDP relay 组各用一个服务端 socket。脚本按启动时的本机 IPv4/IPv6 地址建立入口规则。需要 `CAP_NET_ADMIN` 和空闲的专用端口段。规则安装失败会禁用跳跃扩展，普通传输仍可用。JVM 退出后辅助进程清理自己创建的表。若辅助进程和命名空间内的 JVM 同时被强杀且规则残留，先核对表名和归属再清理。

部署观察每方向 FEC 缺口与恢复数、压缩节省字节、TCP/UDP 切换、活跃组数及剩余端口对，同时对照 CPU、进程 RSS、Netty 堆外内存和 P95/P99。Server 按 1 核、768 MiB 整机预算设计。

## Actions 发布

两端工作流共用仓库 Secret `RSS_DEPLOY`。本机恢复副本在 `D:\home\.envKeys\rss\deploy.json`，签发用的 CA 私钥仅保存在该目录。轮换时先更新 JSON，再通过标准输入传给 `gh secret set RSS_DEPLOY`。每个 SSH 目标固定主机公钥。两端分别接收 TCP、UDP 共享密钥。Server 单独接收内部账号。启用证书 TLS 时，Client 接收信任证书，Server 接收证书及私钥。证书 SAN 要覆盖 Client 配置的实际 upstream。

协议变更时先用 `rss-21` 手动发布主 Server，再发布 Gateway。两端各自的发布目录下保存本次 `.release-<run id>-<attempt>/deploy.log`、原启动脚本及变更过的用户配置；目录仅部署账号可访问。Server 的工作流在切换前验证 nft 权限并合并预留 UDP 端口；firewalld 活跃时同时放行运行时和永久端口。主 Server 当前的 nft INPUT 策略允许这段流量。上游安全组或 NAT 在主机权限之外，发布后需从 Client 走真实业务验证。工作流在本地监听以外还检查首尾 nft 映射和公网 TCP 入口。

[Hysteria 2 的 Linux 端口重定向](https://v2.hysteria.network/docs/advanced/Port-Hopping/) · [IANA 端口登记](https://www.iana.org/assignments/service-names-port-numbers/service-names-port-numbers.xhtml)
