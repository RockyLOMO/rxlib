# 前置
所有场景考虑dns污染，尽量远程解析dns

# 场景1
client -> SocksServerProxy A, udp -> dest

# 场景2
client -> SocksServerProxy A(广域网ip a), udp associate -> SocksServerProxy B(广域网ip b), udp -> dest
