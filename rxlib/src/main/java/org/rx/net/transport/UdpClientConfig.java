package org.rx.net.transport;

import lombok.Getter;
import lombok.Setter;
import org.rx.net.SocketConfig;

@Getter
@Setter
public class UdpClientConfig extends SocketConfig {
    private static final long serialVersionUID = -8809090357842348604L;
    private UdpClientCodec codec = FuryUdpClientCodec.createDefault();
    private int waitAckTimeoutMillis = 15 * 1000;
    private boolean fullSync;
    private int maxResend = 2;
    private int maxFragmentPayloadBytes = 1024;
    private int maxFragmentCount = 128;
    /**
     * 自动登记压缩/冗余/peer-scoped FEC 对端的上限。0 表示仅允许调用方手动登记。
     */
    private int maxAutoPeers = 4096;
}
