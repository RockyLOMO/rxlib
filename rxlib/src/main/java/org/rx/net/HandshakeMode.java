package org.rx.net;

import java.util.Locale;

/**
 * Handshake strategy for rxlib private TCP links.
 * <p>
 * <ul>
 *     <li>{@link #LIGHTWEIGHT}: default for RSS↔RSS private TCP links. Preserves standard SOCKS5
 *         round-trip counts: pure {@code plain} non-AEAD uses standard SOCKS5 directly with zero
 *         Core state; non-AEAD enhanced profiles ({@code daily}/{@code weak}/{@code game}) and
 *         business {@code zlib} piggyback authenticated negotiation on the SOCKS5 auth round-trip
 *         with zero added RTT; protected {@link SecurityMode#FAST} AEAD ports use the single
 *         authenticated {@code RXTC} v2 exchange before SOCKS5 credentials.</li>
 *     <li>{@link #CORE}: explicit full {@code RXTC} v2 handshake on every connection (always used
 *         for RPC, RRP, Nameserver private RPC, and Rendezvous).</li>
 * </ul>
 */
public enum HandshakeMode {
    LIGHTWEIGHT("lightweight"),
    CORE("core");

    private final String wireName;

    HandshakeMode(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static HandshakeMode parse(String name) {
        if (name == null || name.trim().isEmpty()) {
            return LIGHTWEIGHT;
        }
        String norm = name.trim().toLowerCase(Locale.ROOT);
        if ("lightweight".equals(norm)) {
            return LIGHTWEIGHT;
        }
        if ("core".equals(norm)) {
            return CORE;
        }
        throw new IllegalArgumentException("Unknown HandshakeMode (expected lightweight|core): " + name);
    }
}
