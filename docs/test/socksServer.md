# 前置
所有场景考虑dns污染，尽量远程解析dns

# 场景1
client -> SocksServerProxy A, udp -> dest

# 场景2
client -> SocksServerProxy A(广域网ip a), udp associate -> SocksServerProxy B(广域网ip b), udp -> dest

# udp2raw场景
client -> SocksServerProxy A(广域网ip a), udp associate -> udp2raw client(广域网ip a) -> udp2raw server(广域网ip b) -> SocksServerProxy B(广域网ip b), udp -> dest
