package org.rx.net.socks;

import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.bean.FlagsEnum;
import org.rx.bean.NEnum;

@RequiredArgsConstructor
@Data
public class SslDirectConfig {
    @RequiredArgsConstructor
    @Getter
    public enum EnableFlags implements NEnum<EnableFlags> {
        NONE(0),
        FRONTEND(1),
        BACKEND(1 << 1),
        ALL(FRONTEND.value | BACKEND.value);

        final int value;
    }

    private final int listenPort;
    private final FlagsEnum<EnableFlags> enableFlags;
    private final boolean enableSsl, enableCompress;
}
