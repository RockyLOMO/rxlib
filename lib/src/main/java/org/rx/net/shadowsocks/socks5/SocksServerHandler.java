package org.rx.net.shadowsocks.socks5;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.socksx.SocksMessage;
import io.netty.handler.codec.socksx.v5.*;
import org.rx.exception.TraceHandler;
import org.rx.net.Sockets;
import org.rx.net.shadowsocks.SSCommon;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;

@ChannelHandler.Sharable
public final class SocksServerHandler extends SimpleChannelInboundHandler<SocksMessage> {
    public static final SocksServerHandler DEFAULT = new SocksServerHandler();

    private SocksServerHandler() {
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, SocksMessage socksRequest) throws Exception {
        switch (socksRequest.version()) {
            case SOCKS5:
                if (socksRequest instanceof Socks5InitialRequest) {
                    // auth support example
                    //ctx.pipeline().addFirst(new Socks5PasswordAuthRequestDecoder());
                    //ctx.write(new DefaultSocks5AuthMethodResponse(Socks5AuthMethod.PASSWORD));
                    ctx.pipeline().addFirst(new Socks5CommandRequestDecoder());
                    ctx.write(new DefaultSocks5InitialResponse(Socks5AuthMethod.NO_AUTH));
                } else if (socksRequest instanceof Socks5CommandRequest) {
                    Socks5CommandRequest socks5CmdRequest = (Socks5CommandRequest) socksRequest;
                    if (socks5CmdRequest.type() == Socks5CommandType.CONNECT) {
                        ctx.pipeline().remove(this);
                        //ss-local just res SUCCESS
                        ctx.channel().writeAndFlush(new DefaultSocks5CommandResponse(
                                Socks5CommandStatus.SUCCESS,
                                Socks5AddressType.IPv4,
                                "0.0.0.0",
                                0));

                        ctx.channel().attr(SSCommon.REMOTE_SOCKS5_DEST).set(socks5CmdRequest);
                    } else if (socks5CmdRequest.type() == Socks5CommandType.UDP_ASSOCIATE) {
                        ctx.pipeline().remove(this);

                        InetSocketAddress bindAddr = (InetSocketAddress) ctx.channel().localAddress();
                        InetAddress bindId = bindAddr.getAddress();
                        Socks5AddressType bindAddrType = bindId instanceof Inet6Address
                                ? Socks5AddressType.IPv6 : Socks5AddressType.IPv4;

                        ctx.channel().writeAndFlush(new DefaultSocks5CommandResponse(
                                Socks5CommandStatus.SUCCESS,
                                bindAddrType,
                                bindId.getHostAddress(),
                                bindAddr.getPort()));
                    } else {
                        ctx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.FAILURE, socks5CmdRequest.dstAddrType())).addListener(ChannelFutureListener.CLOSE);
                    }
                } else {
                    ctx.close();
                }
                break;
            default:
                ctx.close();
                break;
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        TraceHandler.INSTANCE.log(cause);
        Sockets.closeOnFlushed(ctx.channel());
    }
}