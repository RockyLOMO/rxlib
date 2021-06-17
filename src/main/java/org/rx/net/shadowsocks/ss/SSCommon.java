package org.rx.net.shadowsocks.ss;

import io.netty.handler.codec.socksx.v5.Socks5CommandRequest;
import io.netty.util.AttributeKey;
import org.rx.net.shadowsocks.encryption.ICrypto;

import java.net.InetSocketAddress;

public class SSCommon {
    public static final AttributeKey<ICrypto> CIPHER = AttributeKey.valueOf("CIPHER");
    public static final AttributeKey<Boolean> IS_UDP = AttributeKey.valueOf("IS_UDP");
    public static final AttributeKey<InetSocketAddress> REMOTE_ADDR = AttributeKey.valueOf("ssclient");
    public static final AttributeKey<InetSocketAddress> REMOTE_DES = AttributeKey.valueOf("ssremotedes");
    public static final AttributeKey<InetSocketAddress> REMOTE_SRC = AttributeKey.valueOf("ssremotesrc");
    public static final AttributeKey<Socks5CommandRequest> REMOTE_DES_SOCKS5 = AttributeKey.valueOf("socks5remotedes");

    public static final int TCP_PROXY_IDLE_TIME = 120;
    public static final int UDP_PROXY_IDLE_TIME = 120;
    //udp proxy,when is dns proxy,listener timeout
    public static final int UDP_DNS_PROXY_IDLE_TIME = 10;
}
