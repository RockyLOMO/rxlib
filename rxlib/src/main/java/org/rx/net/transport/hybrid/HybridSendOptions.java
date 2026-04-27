package org.rx.net.transport.hybrid;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

@Getter
@Setter
public final class HybridSendOptions implements Serializable {
    private static final long serialVersionUID = -1119158453393019504L;

    public static final HybridSendOptions DEFAULT = new HybridSendOptions();
    public static final HybridSendOptions FORCE_TCP = forceTcp();

    private boolean forceTcp;
    private boolean fallbackToTcpOnUdpFailure = true;
    private int waitAckTimeoutMillis = -1;
    private boolean fullSync;

    public static HybridSendOptions forceTcp() {
        HybridSendOptions options = new HybridSendOptions();
        options.setForceTcp(true);
        return options;
    }
}
