package org.rx.net.socks;

public enum Udp2rawFrameType {
    DATA(1),
    PING(2),
    CLOSE(3),
    RESET(4);

    private final int code;

    Udp2rawFrameType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static Udp2rawFrameType fromCode(int code) {
        for (Udp2rawFrameType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown udp2raw frame type " + code);
    }
}
