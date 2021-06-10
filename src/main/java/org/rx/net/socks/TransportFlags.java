package org.rx.net.socks;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.bean.NEnum;

@RequiredArgsConstructor
@Getter
public enum TransportFlags implements NEnum<TransportFlags> {
    NONE(0),
    FRONTEND_SSL(1),
    FRONTEND_AES(1 << 1),
    FRONTEND_COMPRESS(1 << 2),
    BACKEND_SSL(1 << 3),
    BACKEND_AES(1 << 4),
    BACKEND_COMPRESS(1 << 5),
    FRONTEND_ALL(FRONTEND_AES.value | FRONTEND_COMPRESS.value),
    BACKEND_ALL(BACKEND_AES.value | BACKEND_COMPRESS.value),
    ALL(FRONTEND_ALL.value | BACKEND_ALL.value);

    final int value;
}
