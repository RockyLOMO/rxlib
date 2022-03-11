package org.rx.net.rpc.protocol;

import lombok.RequiredArgsConstructor;
import org.rx.core.EventArgs;

import java.io.Serializable;

@RequiredArgsConstructor
public class EventMessage implements Serializable {
    private static final long serialVersionUID = 49475184213268784L;
    public final String eventName;
    public final EventFlag flag;
    public EventArgs eventArgs;
    public long computeId;
}
