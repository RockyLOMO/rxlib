package org.rx.net.rpc;

import org.rx.net.rpc.protocol.EventFlag;
import org.rx.net.rpc.protocol.EventMessage;
import org.rx.net.rpc.protocol.MethodMessage;
import org.rx.net.transport.hybrid.HybridSendOptions;

final class RemotingHybridOptions {
    static final HybridSendOptions CONTROL = HybridSendOptions.FORCE_TCP;
    static final HybridSendOptions METHOD = HybridSendOptions.FORCE_TCP;
    static final HybridSendOptions RESPONSE = HybridSendOptions.FORCE_TCP;
    static final HybridSendOptions EVENT = new HybridSendOptions();

    private RemotingHybridOptions() {
    }

    static HybridSendOptions event(EventMessage message) {
        return message != null && (message.flag == EventFlag.SUBSCRIBE || message.flag == EventFlag.UNSUBSCRIBE || message.flag == EventFlag.COMPUTE_ARGS)
                ? CONTROL : EVENT;
    }

    static HybridSendOptions response(MethodMessage message) {
        return message != null && message.errorMessage != null ? CONTROL : RESPONSE;
    }
}
