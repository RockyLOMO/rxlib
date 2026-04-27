# UDP 打洞模块 (org.rx.net.punch)

专注于在 NAT（网络地址转换）环境下建立端到端（P2P）直接通信的 UDP Hole Punching 框架。

## 核心类介绍

- **`UdpHolePunchServer` / `UdpHolePunchRegistry`**:
  打洞协调服务器端（公网节点）。在 P2P 网络中作为信令服务器（Signaling Server），负责记录注册上来的各个 NAT 后方客户端的公网 IP 和端口（Endpoint），并向 A 和 B 双方交换彼此的网络信息。

- **`UdpHolePunchClient` / `UdpHolePunchSession`**:
  打洞客户端。它向信令服务器注册自己，并在获取目标对等节点的公网信息后，根据 NAT 行为特征，向对方发送 UDP 探测包，以在各自的网关或路由器上打开相应的 UDP 转换表项，实现直连。

## 适用场景
- 视频串流、游戏联机以及无需中继服务器耗费流量的高性能 P2P 隧道或代理节点。
