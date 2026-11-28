package org.rx.net;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.rx.net.EgressCodec.HttpBodyEncoding;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Configuration and strict bounds for the shared physical TCP {@link EgressCodec}.
 * Clients MUST choose an explicit codec ({@code NONE}, {@code ZLIB_STORED}, or {@code HTTP});
 * {@code AUTO} is permitted on servers only with an explicit non-empty {@code allowedCodecs} set.
 */
@Getter
@Setter
@EqualsAndHashCode
@ToString
public class EgressConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    private EgressCodec tcpCodec = EgressCodec.NONE;
    /** Explicit allowlist required when {@code tcpCodec == AUTO} on server listeners. */
    private Set<EgressCodec> allowedCodecs;
    /** Optional per-body encoding inside HTTP framing; defaults to {@link HttpBodyEncoding#NONE} (raw binary body). */
    private HttpBodyEncoding httpBodyEncoding = HttpBodyEncoding.NONE;
    private int maxHeaderBytes = EgressCodec.DEFAULT_MAX_HEADER_BYTES;
    private int maxBodyBytes = EgressCodec.DEFAULT_MAX_BODY_BYTES;
    private int maxInflateBytes = EgressCodec.DEFAULT_MAX_INFLATE_BYTES;
    private int detectTimeoutMillis = EgressCodec.DEFAULT_DETECT_TIMEOUT_MILLIS;
    private String httpHost = "api.local";
    private String httpPath = "/v1/stream";

    public static EgressConfig none() {
        return new EgressConfig();
    }

    public static EgressConfig client(EgressCodec codec) {
        EgressConfig cfg = new EgressConfig();
        cfg.setTcpCodec(codec == null ? EgressCodec.NONE : codec);
        cfg.validateClient();
        return cfg;
    }

    public static EgressConfig server(EgressCodec codec) {
        EgressConfig cfg = new EgressConfig();
        EgressCodec effective = codec == null ? EgressCodec.NONE : codec;
        cfg.setTcpCodec(effective);
        if (effective == EgressCodec.AUTO) {
            cfg.setAllowedCodecs(EnumSet.of(EgressCodec.NONE, EgressCodec.ZLIB_STORED, EgressCodec.HTTP));
        }
        cfg.validateServer();
        return cfg;
    }

    public static EgressConfig serverAuto(EgressCodec... allowed) {
        EgressConfig cfg = new EgressConfig();
        cfg.setTcpCodec(EgressCodec.AUTO);
        Set<EgressCodec> set = new LinkedHashSet<EgressCodec>();
        if (allowed != null) {
            set.addAll(Arrays.asList(allowed));
        }
        cfg.setAllowedCodecs(set);
        cfg.validateServer();
        return cfg;
    }

    public static EgressConfig serverAutoAll() {
        return serverAuto(EgressCodec.NONE, EgressCodec.ZLIB_STORED, EgressCodec.HTTP);
    }

    public EgressConfig copy() {
        EgressConfig c = new EgressConfig();
        c.tcpCodec = this.tcpCodec;
        c.allowedCodecs = this.allowedCodecs == null ? null : new LinkedHashSet<EgressCodec>(this.allowedCodecs);
        c.httpBodyEncoding = this.httpBodyEncoding;
        c.maxHeaderBytes = this.maxHeaderBytes;
        c.maxBodyBytes = this.maxBodyBytes;
        c.maxInflateBytes = this.maxInflateBytes;
        c.detectTimeoutMillis = this.detectTimeoutMillis;
        c.httpHost = this.httpHost;
        c.httpPath = this.httpPath;
        return c;
    }

    public void validateClient() {
        validateCommon();
        if (tcpCodec == EgressCodec.AUTO) {
            throw new IllegalArgumentException("Client TCP egress codec cannot be AUTO");
        }
    }

    public void validateServer() {
        validateCommon();
        if (tcpCodec == EgressCodec.AUTO) {
            if (allowedCodecs == null || allowedCodecs.isEmpty()) {
                throw new IllegalArgumentException("Server AUTO egress requires non-empty allowedCodecs");
            }
            for (EgressCodec c : allowedCodecs) {
                if (c == null || c == EgressCodec.AUTO) {
                    throw new IllegalArgumentException("Server AUTO allowedCodecs cannot contain null or AUTO");
                }
            }
        }
    }

    private void validateCommon() {
        if (tcpCodec == null) {
            tcpCodec = EgressCodec.NONE;
        }
        if (httpBodyEncoding == null) {
            httpBodyEncoding = HttpBodyEncoding.NONE;
        }
        if (maxHeaderBytes < 256 || maxHeaderBytes > 64 * 1024) {
            throw new IllegalArgumentException("Invalid egress maxHeaderBytes: " + maxHeaderBytes);
        }
        if (maxBodyBytes < 1024 || maxBodyBytes > 64 * 1024 * 1024) {
            throw new IllegalArgumentException("Invalid egress maxBodyBytes: " + maxBodyBytes);
        }
        if (maxInflateBytes < 1024 || maxInflateBytes > 64 * 1024 * 1024) {
            throw new IllegalArgumentException("Invalid egress maxInflateBytes: " + maxInflateBytes);
        }
        if (detectTimeoutMillis < 100 || detectTimeoutMillis > 60_000) {
            throw new IllegalArgumentException("Invalid egress detectTimeoutMillis: " + detectTimeoutMillis);
        }
        if (httpHost == null || httpHost.trim().isEmpty() || containsControlChars(httpHost)) {
            throw new IllegalArgumentException("Invalid egress httpHost");
        }
        if (httpPath == null || !httpPath.startsWith("/") || httpPath.indexOf(' ') >= 0 || containsControlChars(httpPath)) {
            throw new IllegalArgumentException("Invalid egress httpPath");
        }
    }

    public Set<EgressCodec> effectiveAllowedCodecs() {
        if (tcpCodec != EgressCodec.AUTO) {
            return Collections.singleton(tcpCodec);
        }
        return allowedCodecs == null ? Collections.<EgressCodec>emptySet() : Collections.unmodifiableSet(allowedCodecs);
    }

    private static boolean containsControlChars(String s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < 0x20 || ch == 0x7f) {
                return true;
            }
        }
        return false;
    }
}
