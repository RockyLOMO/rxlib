package org.rx.net.rpc;

import org.rx.net.transport.TcpChannelCodec;

import java.io.Serializable;

public interface RemotingCodecFactory extends Serializable {
    TcpChannelCodec newClientCodec(RpcClientConfig<?> config);

    TcpChannelCodec newServerCodec(RpcServerConfig config);
}
