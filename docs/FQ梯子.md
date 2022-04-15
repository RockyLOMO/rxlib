## 设计思路

避免协议嗅探（[详见](https://www.chinagfw.org/2020/02/shadowsocks.html)），自实现通信协议，在socks5的基础上加入混淆。

避免流量拦截，Q内加网关，对外暴露SS协议。

```java
@SneakyThrows
@Test
public void socks5Proxy() {
    SocksConfig backConf = new SocksConfig(1081);
    backConf.setTransportFlags(TransportFlags.FRONTEND_COMPRESS.flags());
    backConf.setConnectTimeoutMillis(connectTimeoutMillis);
    SocksProxyServer backSvr = new SocksProxyServer(backConf);
    backSvr.setAesRouter(SocksProxyServer.DNS_AES_ROUTER);

    RpcServerConfig rpcServerConf = new RpcServerConfig(1181);
    rpcServerConf.setTransportFlags(TransportFlags.FRONTEND_COMPRESS.flags());
    Remoting.listen(new Main(backSvr), rpcServerConf);

    SocksConfig frontConf = new SocksConfig(1090);
    frontConf.setTransportFlags(TransportFlags.BACKEND_COMPRESS.flags());
    frontConf.setConnectTimeoutMillis(connectTimeoutMillis);
    SocksProxyServer frontSvr = new SocksProxyServer(frontConf, null,
            dstEp -> new Socks5Upstream(dstEp, frontConf, new AuthenticEndpoint("127.0.0.1:1081")));
    frontSvr.setAesRouter(SocksProxyServer.DNS_AES_ROUTER);

    RpcClientConfig rpcClientConf = RpcClientConfig.poolMode("127.0.0.1:1181", 2);
    rpcClientConf.setTransportFlags(TransportFlags.BACKEND_COMPRESS.flags());
    SocksSupport support = Remoting.create(SocksSupport.class, rpcClientConf);
    frontSvr.setSupport(support);
    sleep(2000);
    support.addWhiteList(InetAddress.getByName(HttpClient.getWanIp()));

    System.in.read();
}
```

```java
@SneakyThrows
@Test
public void directProxy() {
    DirectConfig frontConf = new DirectConfig(3307);
    frontConf.setTransportFlags(TransportFlags.BACKEND_AES_COMBO.flags());
    DirectProxyServer frontSvr = new DirectProxyServer(frontConf, p -> Sockets.parseEndpoint("127.0.0.1:3308"));

    DirectConfig backConf = new DirectConfig(3308);
    backConf.setTransportFlags(TransportFlags.FRONTEND_AES_COMBO.flags());
    DirectProxyServer backSvr = new DirectProxyServer(backConf, p -> Sockets.parseEndpoint("mysql.rds.aliyuncs.com:3306"));

    System.in.read();
}
```



#### Thanks

* https://github.com/hsupu/netty-socks
* https://github.com/TongxiJi/shadowsocks-java
