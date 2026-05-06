# 网络代理
## 前置
所有场景考虑dns污染，尽量远程解析dns
udp associate需要考虑 Full Clone NAT

## 场景1
socks5 client -> SocksServerProxy A, udp -> dest
- 对应用例：
  - `org.rx.net.socks.SocksProxyServerIntegrationTest#socks5UdpRelay_e2e`

## 场景2
socks5 client -> SocksServerProxy A(广域网ip a), udp associate -> SocksServerProxy B(广域网ip b), udp -> dest
- 对应用例：
  - `org.rx.net.socks.SocksProxyServerIntegrationTest#socks5UdpRelay_chained_e2e`
  - `org.rx.net.socks.SocksProxyServerIntegrationTest#socks5UdpRelay_chained_withUdpRedundant_plainReplyToClient_e2e`
  - `org.rx.net.socks.SocksProxyServerIntegrationTest#socks5UdpRelay_chained_withUdpCompressAndRedundant_e2e`
  - `org.rx.net.socks.SocksProxyServerIntegrationTest#socks5UdpRelay_chained_withLeasePool_reusesProxyBRelay`
  - `org.rx.net.socks.SocksProxyServerIntegrationTest#socks5TcpConnect_chained_withCompressedTunnel_bypassCandidatePort_e2e`

## 场景3 - udp2raw场景
ShadowsocksClient(广域网ip c) 
-> ShadowsocksServer(广域网ip a) -> SocksServerProxy A(广域网ip a), udp direct to -> udp2raw client(广域网ip a) 
-> udp2raw server(广域网ip b) -> SocksServerProxy B(广域网ip b), udp -> dest
- 对应用例：
  - `org.rx.net.socks.SocksProxyServerIntegrationTest#socks5UdpRelay_udp2raw_chained_e2e`
  - `org.rx.net.socks.Udp2rawHandlerTest#udp2raw_clientServerModeE2E`
  - `org.rx.net.socks.Udp2rawHandlerTest#udp2raw_serverMode_unwrapsAndForwardsToEcho`
  - `org.rx.net.socks.Udp2rawHandlerTest#udp2raw_clientMode_invalidMagicIsDiscarded`

## 场景4
ShadowsocksClient(广域网ip c) -> ShadowsocksServer(广域网ip a) -> SocksServerProxy A(广域网ip a), udp associate -> SocksServerProxy B(广域网ip b), udp -> dest
- RSS Client DNS 路径：仅当 geo 规则确认域名直连时使用直连 DNS Client；其他域名必须通过 RPC 调用 RSS Server，由远程 DNS Client 解析，避免本地 DNS 污染。
- TCP/UDP 建连路径：直连分支使用 Netty Bootstrap 直连 DNS Client；代理分支保持域名未解析并交给上游/远端处理。
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
  - `org.rx.net.socks.UdpCompressTest`
  - `org.rx.net.socks.UdpRedundantTest`

# 缓存
## dns
H2StoreCache 主要缓存dns结果，属于热数据
