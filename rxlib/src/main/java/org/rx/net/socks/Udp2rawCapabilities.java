package org.rx.net.socks;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter
@Setter
@ToString
public class Udp2rawCapabilities implements Serializable {
    private static final long serialVersionUID = 1L;

    private int protocolVersion = Udp2rawCodec.VERSION;
    private boolean fixedEntry = true;
    private boolean reusePort;
    private boolean redundant;
    private boolean compress;
    private int maxSessions;
    private Udp2rawAuthMode authMode = Udp2rawAuthMode.FIRST_PACKET_MAC;
}
