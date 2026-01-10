package org.rx.net.shadowsocks;

import io.netty.handler.codec.socksx.v5.Socks5CommandRequest;
import io.netty.util.AttributeKey;
import org.rx.net.shadowsocks.encryption.ICrypto;

import java.net.InetSocketAddress;

public class SSCommon {
    public static final AttributeKey<ICrypto> CIPHER = AttributeKey.valueOf("CIPHER");
    public static final AttributeKey<InetSocketAddress> REMOTE_ADDRESS = AttributeKey.valueOf("REMOTE_ADDRESS");
    public static final AttributeKey<InetSocketAddress> REMOTE_DEST = AttributeKey.valueOf("REMOTE_DEST");
    public static final AttributeKey<InetSocketAddress> REMOTE_SRC = AttributeKey.valueOf("REMOTE_SRC");
}
