package org.rx.socks;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.rx.common.App;

import static org.rx.common.Contract.require;

@Data
@EqualsAndHashCode(callSuper = true)
public class SessionPack extends SessionId {
    public static volatile SessionId defaultId;

    static {
        defaultId = App.readSetting("app.sessionPack", SessionId.class);
    }

    public static <T extends SessionPack> T create(Class<T> type) {
        require(defaultId);

        T pack = App.newInstance(type);
        pack.setAppName(defaultId.getAppName());
        pack.setVersion(defaultId.getVersion());
        return pack;
    }

    public static SessionPack error(String errorMessage) {
        SessionPack pack = create(SessionPack.class);
        pack.setErrorMessage(errorMessage);
        return pack;
    }

    private String errorMessage;
}
