package org.rx.net.rpc;

import org.rx.net.transport.TcpChannelCodec;
import org.rx.net.transport.UdpClientCodec;
import org.rx.net.transport.hybrid.HybridTcpChannelCodec;

import java.io.Serializable;

public interface RemotingCodecFactory extends Serializable {
    UdpClientCodec newCodec();

    default TcpChannelCodec newClientCodec(RpcClientConfig<?> config) {
        return new HybridTcpChannelCodec(newCodec());
    }

    default TcpChannelCodec newServerCodec(RpcServerConfig config) {
        return new HybridTcpChannelCodec(newCodec());
    }
}
