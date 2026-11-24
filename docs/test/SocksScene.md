# 网络代理测试场景

类名是 `SocksProxyServer`。下面的 A/B 是测试里的两个代理角色，不是类名。DNS 污染场景优先远程解析。UDP associate 要考虑 Full Cone NAT。

RSS `daily` 的 FEC 和 30 秒端口跳跃不在这些场景的默认路径里，见 `docs/rss-transport.md`。

## 场景1
socks5 client -> SocksProxyServer A, udp -> dest
- 对应用例：
  - `org.rx.net.socks.SocksProxyServerIntegrationTest#socks5UdpRelay_e2e`

## 场景2
socks5 client -> SocksProxyServer A, udp associate -> SocksProxyServer B, udp -> dest
- 对应用例：
  - `org.rx.net.socks.SocksProxyServerIntegrationTest#socks5UdpRelay_chained_e2e`
  - `org.rx.net.socks.SocksProxyServerIntegrationTest#socks5UdpRelay_chained_withUdpRedundant_plainReplyToClient_e2e`
  - `org.rx.net.socks.SocksProxyServerIntegrationTest#socks5UdpRelay_chained_withUdpCompressAndRedundant_e2e`
  - `org.rx.net.socks.SocksProxyServerIntegrationTest#socks5UdpRelay_chained_withLeasePool_reusesProxyBRelay`
  - `org.rx.net.socks.SocksProxyServerIntegrationTest#socks5TcpConnect_chained_withCompressedTunnel_bypassCandidatePort_e2e`

## 场景3 - udp2raw场景
ShadowsocksClient -> ShadowsocksServer -> SocksProxyServer A，UDP 进入本机 udp2raw client -> udp2raw server -> SocksProxyServer B -> dest
- 对应用例：
  - `org.rx.net.socks.SocksProxyServerIntegrationTest#socks5UdpRelay_udp2raw_chained_e2e`
  - `org.rx.net.socks.Udp2rawHandlerTest#udp2raw_clientServerModeE2E`
  - `org.rx.net.socks.Udp2rawHandlerTest#udp2raw_serverMode_unwrapsAndForwardsToEcho`
  - `org.rx.net.socks.Udp2rawHandlerTest#udp2raw_clientMode_invalidMagicIsDiscarded`

## 场景4
ShadowsocksClient -> ShadowsocksServer -> SocksProxyServer A, udp associate -> SocksProxyServer B -> dest
- 域名被 geo 规则判为直连时，RSS Client 用直连 DNS；其余域名经 RPC 交给 RSS Server 解析。
- 直连建连使用 Netty 异步 DNS。代理分支保持域名 unresolved，交给上游解析。
- 对应用例：
  - `org.rx.net.socks.ShadowsocksServerIntegrationTest#shadowsocksUdpRelay_e2e`
  - `org.rx.net.socks.SocksProxyServerIntegrationTest#shadowsocksUdpRelay_socks5_chained_e2e`
  - `org.rx.net.socks.SocksProxyServerIntegrationTest#shadowsocksUdpRelay_socks5_chained_withUdpRedundantOnProxyA_e2e`
  - `org.rx.net.socks.SocksProxyServerIntegrationTest#shadowsocksUdpRelay_socks5_chained_withUdpCompressAndRedundantOnProxyAB_e2e`
  - `org.rx.net.socks.SocksProxyServerIntegrationTest#shadowsocksUdpRelay_socks5_chained_withPortHopping_e2e`
  - `org.rx.net.socks.SocksProxyServerIntegrationTest#shadowsocksUdpRelay_socks5_chained_withLeasePool_e2e`
  - `org.rx.net.socks.SocksProxyServerIntegrationTest#shadowsocksUdpRelay_sameDestinationDifferentClientPorts_e2e`
  - `org.rx.net.socks.SocksProxyServerIntegrationTest#shadowsocksUdpRelay_socks5_localChannel_preservesOrigin_e2e`

## 压缩/多倍发包专项
- 对应用例：
  - `org.rx.net.udp.UdpCompressTest`
  - `org.rx.net.udp.UdpRedundantTest`
