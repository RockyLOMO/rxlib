package org.rx.net.transport.protocol;

import lombok.Getter;
import org.rx.core.NtpClock;

import java.io.Serializable;

@Getter
public class PingPacket implements Serializable {
    private static final long serialVersionUID = 7964552443367680011L;
    final long timestamp = NtpClock.UTC.millis();
}
