package org.rx.net.rpc;

import io.netty.util.Attribute;
import org.rx.core.EventTarget;

import java.io.Serializable;
import java.util.Date;

public interface RpcClient extends RpcClientMeta, EventTarget<RpcClient> {
    RpcClientConfig getConfig();

    void send(Serializable pack);

    boolean hasAttr(String name);

    <T> Attribute<T> attr(String name);
}
