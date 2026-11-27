package org.rx.net.rpc;

import java.io.Serializable;

public interface RemotingCodecFactory extends Serializable {
    RemotingCodec newCodec();
}
