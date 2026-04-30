package org.rx.net.socks;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.rx.bean.DateTime;

import java.io.Serializable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Getter
@Setter
@ToString
public class TrafficLoginInfo implements Serializable {
    private static final long serialVersionUID = 1264936011170722186L;

    DateTime latestTime;
    final AtomicInteger refCnt = new AtomicInteger();
    final AtomicLong activeSinceMillis = new AtomicLong();
    final AtomicLong totalActiveSeconds = new AtomicLong();
    final AtomicLong totalReadBytes = new AtomicLong();
    final AtomicLong totalWriteBytes = new AtomicLong();
    final AtomicLong totalReadPackets = new AtomicLong();
    final AtomicLong totalWritePackets = new AtomicLong();
}
