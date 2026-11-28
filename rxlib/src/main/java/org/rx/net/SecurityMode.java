package org.rx.net;

import java.util.Locale;

/**
 * Unified security mode for rxlib private transports.
 * <p>
 * <ul>
 *     <li>{@link #FAST}: default suite for all private secure links — fixed {@code AES-128-GCM}
 *         stream encryption with {@code HMAC-SHA256} handshake authentication (128-bit wire tag)
 *         and per-session, per-direction derived keys.</li>
 *     <li>{@link #NONE}: no additional transport encryption (does NOT disable business user
 *         authentication or authorization; allowed only on explicitly unprotected/bypass paths and
 *         never permitted as an automatic downgrade from {@link #FAST}).</li>
 * </ul>
 */
public enum SecurityMode {
    NONE("none"),
    FAST("fast");

    private final String wireName;

    SecurityMode(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static SecurityMode parse(String name) {
        if (name == null || name.trim().isEmpty()) {
            return FAST;
        }
        String norm = name.trim().toLowerCase(Locale.ROOT);
        if ("fast".equals(norm)) {
            return FAST;
        }
        if ("none".equals(norm)) {
            return NONE;
        }
        throw new IllegalArgumentException("Unsupported SecurityMode (only fast|none supported): " + name);
    }
}
