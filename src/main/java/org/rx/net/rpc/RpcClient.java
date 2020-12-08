package org.rx.net.rpc;

import io.netty.util.Attribute;
import org.rx.bean.FlagsEnum;
import org.rx.core.EventTarget;

import java.io.Serializable;
import java.util.Date;

public interface RpcClient extends EventTarget<RpcClient> {
    @Override
    default FlagsEnum<EventFlags> eventFlags() {
        return EventFlags.DynamicAttach.flags(EventFlags.ThreadSafe);
    }

    RpcClientConfig getConfig();

    boolean isConnected();

    Date getConnectedTime();

    void send(Serializable pack);

    boolean hasAttr(String name);

    <T> Attribute<T> attr(String name);
}
