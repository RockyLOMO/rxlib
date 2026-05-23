package org.rx.net.socks;

import org.rx.net.udp.*;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.net.InetSocketAddress;

@Getter
@Setter
@ToString
public class Udp2rawOpenRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    private String clientId;
    private InetSocketAddress clientBindAddress;
    private int protocolVersion = Udp2rawCodec.VERSION;
    private int maxSessions;
    private int idleTimeoutSeconds;
    private UdpCompressConfig compress;
    private UdpRedundantConfig redundant;
    private UdpRedundantMode redundantMode;
    private String connectionTag;
    private String trafficUser;
}
