package org.rx.net.shadowsocks;

import io.netty.handler.codec.socksx.v5.Socks5CommandRequest;
import io.netty.util.AttributeKey;
import org.rx.net.shadowsocks.encryption.ICrypto;

import java.net.InetSocketAddress;

public class SSCommon {
    public static final AttributeKey<Boolean> IS_UDP = AttributeKey.valueOf("IS_UDP");
    public static final AttributeKey<ICrypto> CIPHER = AttributeKey.valueOf("CIPHER");
    public static final AttributeKey<InetSocketAddress> REMOTE_ADDRESS = AttributeKey.valueOf("REMOTE_ADDRESS");
    public static final AttributeKey<InetSocketAddress> REMOTE_DEST = AttributeKey.valueOf("REMOTE_DEST");
    public static final AttributeKey<InetSocketAddress> REMOTE_SRC = AttributeKey.valueOf("REMOTE_SRC");
    public static final AttributeKey<Socks5CommandRequest> REMOTE_SOCKS5_DEST = AttributeKey.valueOf("REMOTE_SOCKS5_DEST");

    public static final int UDP_PROXY_IDLE_TIME = 60 * 4;
    //udp proxy,when is dns proxy,listener timeout
    public static final int UDP_DNS_PROXY_IDLE_TIME = 10;
}
