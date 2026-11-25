package org.rx.net.socks.encryption;

import java.util.Arrays;
import javax.crypto.AEADBadTagException;

/** Per-channel AEAD state. Server listeners share only the authenticated salt history. */
public abstract class AeadCrypto implements ICrypto {
    SaltHistory history;
    private byte[] firstSalt;

    protected final void receivedTcpSalt(byte[] salt, int length) {
        if (history != null) firstSalt = Arrays.copyOf(salt, length);
    }

    protected final void authenticatedTcpPayload() throws AEADBadTagException {
        if (firstSalt != null) {
            authenticatedSalt(firstSalt, firstSalt.length);
            firstSalt = null;
        }
    }

    protected final void authenticatedSalt(byte[] salt, int length) throws AEADBadTagException {
        if (history != null && !history.record(salt, length)) {
            throw new AEADBadTagException("Salt already used");
        }
    }

    protected final boolean reserveLocalSalt(byte[] salt, int length) {
        return history == null || history.record(salt, length);
    }
}
