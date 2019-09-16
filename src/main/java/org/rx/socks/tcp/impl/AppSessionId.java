package org.rx.socks.tcp.impl;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.rx.core.App;
import org.rx.socks.tcp.SessionId;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AppSessionId implements SessionId {
    public static volatile AppSessionId defaultId;

    static {
        defaultId = App.readSetting("app.sessionId", AppSessionId.class);
    }

    private String appName;
    private String version;
}
