package org.rx.net.socks;

import org.rx.bean.DateTime;
import org.rx.core.Linq;
import org.rx.io.Bytes;

import java.net.InetAddress;
import java.util.Map;

public interface TrafficUser {
    String getUsername();

    Map<InetAddress, TrafficLoginInfo> getLoginIps();

    int getIpLimit();

    DateTime getLastResetTime();

    void setLastResetTime(DateTime value);

    boolean isAnonymous();

    default long getTotalReadBytes() {
        return (long) Linq.from(getLoginIps().values()).sum(p -> p.getTotalReadBytes().get());
    }

    default long getTotalWriteBytes() {
        return (long) Linq.from(getLoginIps().values()).sum(p -> p.getTotalWriteBytes().get());
    }

    default long getTotalReadPackets() {
        return (long) Linq.from(getLoginIps().values()).sum(p -> p.getTotalReadPackets().get());
    }

    default long getTotalWritePackets() {
        return (long) Linq.from(getLoginIps().values()).sum(p -> p.getTotalWritePackets().get());
    }

    default String getHumanTotalReadBytes() {
        return Bytes.readableByteSize(getTotalReadBytes());
    }

    default String getHumanTotalWriteBytes() {
        return Bytes.readableByteSize(getTotalWriteBytes());
    }
}
