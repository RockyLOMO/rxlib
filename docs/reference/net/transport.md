# 基础传输模块 (org.rx.net.transport)

提供最基础的 TCP 和 UDP Client/Server 封装封装，高度抽取了 Netty 的样板代码。用于快速搭建网络端点应用。

## 核心类介绍

- **`TcpServer`**:
  TCP 服务器封装。支持直接配置监听端口、SSL 证书，并通过事件或处理器钩子处理连接上线、接收报文、下线等事件。

- **`TcpClient` / `AbstractTcpReconnectClient`**:
  TCP 客户端封装。自带断线重连逻辑、连接状态管理以及快速发送/接收抽象。

- **`UdpClient`**:
  高性能的 UDP 客户端与服务端一体化组件（在 Netty 中 UDP Server 与 Client 边界相对模糊）。内置对广播、组播的支持。

- **`SftpClient`**:
  基于 SFTP 协议构建的文件传输客户端，封装了底层连接，并暴露出对远端文件系统的常用操作（上传、下载、列出目录）。

## 适用场景
- 任何需要构建裸 TCP/UDP 服务的场景。
- 业务方需要进行最原始的报文交换（如物联网网关）。
