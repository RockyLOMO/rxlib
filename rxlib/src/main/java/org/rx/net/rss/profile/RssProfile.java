package org.rx.net.rss.profile;

/** Immutable native-hop policy. The YAML name and negotiated TCP ID are independent. */
public enum RssProfile {
    PLAIN(0, 0, 0, 64),
    DAILY(0, 3, 1, 64),
    GAME(RssProfile.GAME_ID, 2, 1, 256),
    WEAK(RssProfile.WEAK_ID, 3, 1, 256);

    public static final int GAME_ID = 1, WEAK_ID = 2;
    public final int adaptiveId;
    public final int fecMask;
    public final int tcpMask;
    public final int maxWindow;

    RssProfile(int adaptiveId, int fecMask, int tcpMask, int maxWindow) {
        this.adaptiveId = adaptiveId;
        this.fecMask = fecMask;
        this.tcpMask = tcpMask;
        this.maxWindow = maxWindow;
    }

    public static RssProfile named(String name) {
        if (name == null) return null;
        switch (name) {
            case "plain": return PLAIN;
            case "daily": return DAILY;
            case "game": return GAME;
            case "weak": return WEAK;
            default: return null;
        }
    }

    public static RssProfile negotiated(int id) {
        switch (id) {
            case 0: return DAILY;
            case GAME_ID: return GAME;
            case WEAK_ID: return WEAK;
            default: throw new IllegalArgumentException("Unknown RSS adaptive profile: " + id);
        }
    }
}
