package org.rx.socks.tcp;

import lombok.Data;

import java.io.Serializable;

public interface SessionId extends Serializable {
    @Data
    class EmptySessionId implements SessionId {
        private EmptySessionId() {
        }
    }

    SessionId empty = new EmptySessionId();
}
