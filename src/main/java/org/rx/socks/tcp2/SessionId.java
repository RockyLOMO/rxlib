package org.rx.socks.tcp2;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SessionId implements Serializable {
    private String appName;
    private String version;

    public final SessionId sessionId() {
        return new SessionId(appName, version);
    }

    public final void sessionId(SessionId sessionId) {
        appName = sessionId.appName;
        version = sessionId.version;
    }
}
