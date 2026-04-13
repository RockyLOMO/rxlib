# 网络代理
## 前置
所有场景考虑dns污染，尽量远程解析dns
udp associate需要考虑 Full Clone NAT

## 场景1
socks5 client -> SocksServerProxy A, udp -> dest

## 场景2
socks5 client -> SocksServerProxy A(广域网ip a), udp associate -> SocksServerProxy B(广域网ip b), udp -> dest

## udp2raw场景
socks5 client -> SocksServerProxy A(广域网ip a), udp associate -> udp2raw client(广域网ip a) -> udp2raw server(广域网ip b) -> SocksServerProxy B(广域网ip b), udp -> dest

## 场景4
ShadowsocksClient(广域网ip c) -> ShadowsocksServer(广域网ip a) -> SocksServerProxy A(广域网ip a), udp associate -> SocksServerProxy B(广域网ip b), udp -> dest

# 缓存
## dns
H2StoreCache 主要缓存dns结果，属于热数据