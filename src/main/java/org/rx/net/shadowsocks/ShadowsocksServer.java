//package org.rx.net.shadowsocks;
//
//import io.netty.bootstrap.Bootstrap;
//import io.netty.bootstrap.ServerBootstrap;
//import io.netty.channel.ChannelHandler;
//import io.netty.channel.ChannelInitializer;
//import io.netty.channel.ChannelOption;
//import io.netty.channel.socket.nio.NioDatagramChannel;
//import io.netty.handler.timeout.IdleState;
//import io.netty.handler.timeout.IdleStateEvent;
//import io.netty.handler.timeout.IdleStateHandler;
//import lombok.NonNull;
//import lombok.extern.slf4j.Slf4j;
//import org.rx.net.Sockets;
//import org.rx.net.shadowsocks.encryption.CryptoFactory;
//import org.rx.net.shadowsocks.encryption.ICrypto;
//import org.rx.net.shadowsocks.ss.*;
//import org.rx.net.shadowsocks.ss.obfs.ObfsFactory;
//
//import java.util.List;
//import java.util.concurrent.TimeUnit;
//
//@Slf4j
//public class ShadowsocksServer {
//    final ServerBootstrap bootstrap;
//
//    public ShadowsocksServer(@NonNull ShadowsocksConfig config) {
//        bootstrap = Sockets.serverBootstrap(config, ctx -> {
//            ctx.attr(SSCommon.IS_UDP).set(false);
//
//            ICrypto _crypt = CryptoFactory.get(config.getMethod(), config.getPassword());
//            _crypt.setForUdp(false);
//            ctx.attr(SSCommon.CIPHER).set(_crypt);
//
//            ctx.pipeline().addLast("timeout", new IdleStateHandler(0, 0, SSCommon.TCP_PROXY_IDEL_TIME, TimeUnit.SECONDS) {
//                @Override
//                protected IdleStateEvent newIdleStateEvent(IdleState state, boolean first) {
//                    ctx.close();
//                    return super.newIdleStateEvent(state, first);
//                }
//            });
//
//            //obfs pugin
//            List<ChannelHandler> obfsHandlers = ObfsFactory.getObfsHandler(config.getObfs());
//            if (obfsHandlers != null) {
//                for (ChannelHandler obfsHandler : obfsHandlers) {
//                    ctx.pipeline().addLast(obfsHandler);
//                }
//            }
//
//            //ss
//            ctx.pipeline().addLast("ssCheckerReceive", new SSServerCheckerReceive())
//                    .addLast("ssCheckerSend", new SSServerCheckerSend())
//                    .addLast("ssCipherCodec", new SSCipherCodec())
//                    .addLast("ssProtocolCodec", new SSProtocolCodec())
//                    .addLast("ssTcpProxy", new SSServerTcpProxyHandler());
//        });
//        bootstrap.bind(config.getEndpoint());
//    }
//
//    private void startSingle(String server, Integer port, String password, String method, String obfs, String obfsparam) throws Exception {
//
//
////            logger.info("TCP Start At Port " + config.get_localPort());
//        tcpBootstrap.bind(server, port).sync();
//
//        //udp server
//        Bootstrap udpBootstrap = new Bootstrap();
//        udpBootstrap.group(bossGroup).channel(NioDatagramChannel.class)
//                .option(ChannelOption.SO_BROADCAST, true)// 支持广播
//                .option(ChannelOption.SO_RCVBUF, 64 * 1024)// 设置UDP读缓冲区为64k
//                .option(ChannelOption.SO_SNDBUF, 64 * 1024)// 设置UDP写缓冲区为64k
//                .handler(new ChannelInitializer<NioDatagramChannel>() {
//
//                    @Override
//                    protected void initChannel(NioDatagramChannel ctx) throws Exception {
//
//                        ctx.attr(SSCommon.IS_UDP).set(true);
//
//                        ICrypto _crypt = CryptoFactory.get(method, password);
//                        assert _crypt != null;
//                        _crypt.isForUdp(true);
//                        ctx.attr(SSCommon.CIPHER).set(_crypt);
//
//                        ctx.pipeline()
////                                .addLast(new LoggingHandler(LogLevel.INFO))
//                                // in
//                                .addLast("ssCheckerReceive", new SSServerCheckerReceive())
//                                // out
//                                .addLast("ssCheckerSend", new SSServerCheckerSend())
//                                //ss-cypt
//                                .addLast("ssCipherCodec", new SSCipherCodec())
//                                //ss-protocol
//                                .addLast("ssProtocolCodec", new SSProtocolCodec())
//                                //proxy
//                                .addLast("ssUdpProxy", new SSServerUdpProxyHandler())
//                        ;
//                    }
//                })
//        ;
//        udpBootstrap.bind(server, port).sync();
//        logger.info("listen at {}:{}", server, port);
//    }
//}
