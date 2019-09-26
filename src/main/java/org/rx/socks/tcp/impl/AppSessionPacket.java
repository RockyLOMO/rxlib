package org.rx.socks.tcp.impl;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.rx.core.Reflects;
import org.rx.socks.tcp.SessionPacket;

import static org.rx.core.Contract.require;

@Data
@EqualsAndHashCode(callSuper = true)
public class AppSessionPacket extends AppSessionId implements SessionPacket {
    public static <T extends AppSessionPacket> T create(Class<T> type) {
        require(defaultId);

        T pack = Reflects.newInstance(type);
        pack.setAppName(defaultId.getAppName());
        pack.setVersion(defaultId.getVersion());
        return pack;
    }

    public AppSessionId sessionId() {
        return new AppSessionId(getAppName(), getVersion());
    }
}
